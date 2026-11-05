// Activity centre (main process): the persistent list in <userData>/activity.json and the recorder that turns
// real events (download batches, failures, followed collections, app updates) into entries.
// The renderer only reads, marks read and removes; it never writes entries (dev-docs/architecture/ACTIVITY.md).
import fs from 'node:fs/promises'
import path from 'node:path'
import {randomUUID} from 'node:crypto'
import type {Job, Snapshot} from '../shared/contracts.js'
import {
  awayCount,
  batchDoneDetail,
  mergeSyncFound,
  noun,
  pruneActivity,
  sanitizeEntry,
  settleSyncFound,
  syncFoundText,
  type ActivityEntry,
  type ActivityKind,
  type ActivityList,
  type ActivityTarget,
} from '../shared/activity.js'
import {friendlyError} from '../shared/errors.js'
import {providerName} from '../shared/providers.js'
import {compareReleases} from '../shared/updates.js'
import {renameWithRetry} from './fsutil.js'

type Stored = {version: 1; entries: ActivityEntry[]; lastVersion?: string; closedAt?: number; announcedAt?: number}

export type ActivityStoreOptions = {
  file: string
  now?: () => number
  /** Debounce for writes (ms). */
  delay?: number
  /** Called after every change with the new list (the push to the renderer). */
  onChange?: (list: ActivityList) => void
}

export type NewEntry = {
  kind: ActivityKind
  title: string
  detail?: string
  target?: ActivityTarget
  count?: number
}

export class ActivityStore {
  private entries: ActivityEntry[] = []
  private lastVersion?: string
  private previousClosedAt?: number
  /** When the last "while you were away" notice was handed out; entries after it are news at the next start. */
  private announcedAt = 0
  private awayTaken = false
  private timer?: NodeJS.Timeout
  private chain: Promise<void> = Promise.resolve()
  private dirty = false

  constructor(private readonly options: ActivityStoreOptions) {}

  private now() {
    return this.options.now?.() ?? Date.now()
  }

  async init() {
    try {
      const data = JSON.parse(await fs.readFile(this.options.file, 'utf8')) as Partial<Stored>
      this.entries = (Array.isArray(data.entries) ? data.entries : []).map(sanitizeEntry).filter((entry): entry is ActivityEntry => !!entry)
      this.lastVersion = typeof data.lastVersion === 'string' ? data.lastVersion : undefined
      this.previousClosedAt = typeof data.closedAt === 'number' && Number.isFinite(data.closedAt) ? data.closedAt : undefined
      this.announcedAt = typeof data.announcedAt === 'number' && Number.isFinite(data.announcedAt) ? data.announcedAt : (this.previousClosedAt ?? 0)
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT')
        await fs.copyFile(this.options.file, `${this.options.file}.corrupt-${this.now()}`).catch(() => {})
    }
    this.entries = pruneActivity(this.entries, this.now())
  }

  /** Entries newest first (a copy). */
  all(): ActivityEntry[] {
    return structuredClone(this.entries)
  }

  /** The list for a push: no away count. */
  snapshot(): ActivityList {
    return {entries: this.all(), away: 0}
  }

  /**
   * The list for the renderer's request; the "while you were away" count is handed out once per start. Windows records
   * only while Keepfin runs, so it counts unread entries recorded since the last notice (the previous session's news),
   * never the same ones twice. "Keepfin updated" has its own What's new and is not counted.
   */
  list(): ActivityList {
    let away = 0
    if (!this.awayTaken) {
      this.awayTaken = true
      away = awayCount(this.entries.filter((entry) => entry.kind !== 'app-updated'), this.announcedAt)
      this.announcedAt = this.now()
      this.changed(false)
    }
    return {entries: this.all(), away}
  }

  get(id: string): ActivityEntry | undefined {
    return this.entries.find((entry) => entry.id === id)
  }

  find(predicate: (entry: ActivityEntry) => boolean): ActivityEntry | undefined {
    return this.entries.find(predicate)
  }

  record(input: NewEntry): ActivityEntry {
    const entry: ActivityEntry = {id: randomUUID(), kind: input.kind, at: this.now(), title: input.title.slice(0, 300), read: false}
    if (input.detail) entry.detail = input.detail.slice(0, 400)
    if (input.target) entry.target = input.target
    if (input.count !== undefined) entry.count = input.count
    this.entries = pruneActivity([entry, ...this.entries], this.now())
    this.changed()
    return structuredClone(entry)
  }

  /** Replaces fields of an entry in place (same id); an updated entry is unread again and moves to now. */
  update(id: string, change: Partial<Omit<ActivityEntry, 'id'>>, options: {touch?: boolean} = {}): ActivityEntry | undefined {
    const current = this.get(id)
    if (!current) return
    const touch = options.touch ?? true
    const next: ActivityEntry = {...current, ...change, id}
    if (touch) {
      next.at = change.at ?? this.now()
      next.read = change.read ?? false
    }
    this.entries = pruneActivity(
      this.entries.map((entry) => (entry.id === id ? next : entry)),
      this.now(),
    )
    this.changed()
    return structuredClone(next)
  }

  markRead(ids: string[] | 'all') {
    const all = ids === 'all'
    const set = new Set(all ? [] : ids)
    let touched = false
    this.entries = this.entries.map((entry) => {
      if (entry.read || !(all || set.has(entry.id))) return entry
      touched = true
      return {...entry, read: true}
    })
    if (touched) this.changed()
  }

  remove(id: string) {
    const before = this.entries.length
    this.entries = this.entries.filter((entry) => entry.id !== id)
    if (this.entries.length !== before) this.changed()
  }

  removeWhere(predicate: (entry: ActivityEntry) => boolean) {
    const before = this.entries.length
    this.entries = this.entries.filter((entry) => !predicate(entry))
    if (this.entries.length !== before) this.changed()
  }

  get previousVersion() {
    return this.lastVersion
  }
  setVersion(version: string) {
    if (this.lastVersion === version) return
    this.lastVersion = version
    this.changed(false)
  }

  private changed(notify = true) {
    this.dirty = true
    if (notify) this.options.onChange?.(this.snapshot())
    if (this.timer) return
    this.timer = setTimeout(() => {
      this.timer = undefined
      void this.flush()
    }, this.options.delay ?? 400)
    this.timer.unref?.()
  }

  /** Writes now (temp file, then rename). Serialized: a later write never overtakes an earlier one. */
  flush(closing = false): Promise<void> {
    clearTimeout(this.timer)
    this.timer = undefined
    if (!this.dirty && !closing) return this.chain
    this.dirty = false
    const body: Stored = {version: 1, entries: this.entries, lastVersion: this.lastVersion, closedAt: closing ? this.now() : this.previousClosedAt, announcedAt: this.announcedAt}
    const text = JSON.stringify(body)
    const write = this.chain.then(async () => {
      await fs.mkdir(path.dirname(this.options.file), {recursive: true})
      const temp = `${this.options.file}.tmp`
      await fs.writeFile(temp, text)
      await renameWithRetry(temp, this.options.file)
    })
    this.chain = write.catch(() => {})
    return write
  }

  /** Final write at quit; records the close time that the next start measures "away" from. */
  async close() {
    await this.flush(true).catch(() => {})
  }
}

// ---------------------------------------------------------------------------

export type RecorderOptions = {
  store: ActivityStore
  /** Whether the app window is focused (a single download finished in front of the user is not recorded). */
  focused: () => boolean
  now?: () => number
}

type SyncBatch = {collectionId: string; groups: Set<string>; open: boolean}

const settledStatuses: Job['status'][] = ['completed', 'failed', 'cancelled']
const SINGLE_MERGE_MS = 30_000

/**
 * Turns queue snapshots and follow/update events into Activity entries. It only observes, so it works
 * whether or not system notifications are on. One entry per batch, never per item.
 */
export class ActivityRecorder {
  private readonly memo = new Map<string, {status: Job['status']; attempt: number}>()
  private readonly groupKey = new Map<string, string>()
  private readonly syncs = new Map<string, SyncBatch>()
  private readonly groupSync = new Map<string, string>()
  private single?: {entryId: string; at: number; jobIds: string[]}

  constructor(private readonly options: RecorderOptions) {}

  private now() {
    return this.options.now?.() ?? Date.now()
  }
  private get store() {
    return this.options.store
  }

  /** Learns the queue as it was at launch: nothing already settled is announced, and open sync entries are adopted. */
  seed(snapshot: Snapshot) {
    for (const job of snapshot.jobs) this.memo.set(job.id, {status: job.status, attempt: job.attempt ?? 0})
    for (const [id, members] of groupsOf(snapshot)) {
      if (members.every((job) => settledStatuses.includes(job.status))) this.groupKey.set(id, keyOf(members))
      const entry = this.store.find((item) => item.kind === 'sync-found' && item.target?.groupId === id)
      if (entry?.target?.collectionId) {
        const batch = this.syncs.get(entry.id) ?? {collectionId: entry.target.collectionId, groups: new Set<string>(), open: /^Downloading/.test(entry.detail ?? '')}
        batch.groups.add(id)
        this.syncs.set(entry.id, batch)
        this.groupSync.set(id, entry.id)
      }
    }
  }

  consume(snapshot: Snapshot) {
    const now = this.now()
    const spaces = new Map(snapshot.settings.library.spaces.map((space) => [space.id, space]))
    const finished: Job[] = []
    const failedFresh: Job[] = []
    for (const job of snapshot.jobs) {
      const prior = this.memo.get(job.id)
      const attempt = job.attempt ?? 0
      const freshRun = !prior || attempt > prior.attempt
      if (job.status === 'failed' && (freshRun || prior!.status !== 'failed')) failedFresh.push(job)
      if (job.status === 'completed' && !job.existing && (prior ? prior.status !== 'completed' : true)) finished.push(job)
      this.memo.set(job.id, {status: job.status, attempt})
    }
    const live = new Set(snapshot.jobs.map((job) => job.id))
    for (const id of this.memo.keys()) if (!live.has(id)) this.memo.delete(id)

    // Sign-in problems: one entry per service while it is unread, from any failed download.
    for (const job of failedFresh) if (needsSignIn(job)) this.signIn(job)

    const groups = groupsOf(snapshot)
    for (const [id, members] of groups) {
      if (!members.every((job) => settledStatuses.includes(job.status))) continue
      const key = keyOf(members)
      if (this.groupKey.get(id) === key) continue
      this.groupKey.set(id, key)
      this.settleGroup(id, members, spaces)
    }
    this.settleSyncs(groups)

    // Downloads outside a collection: a failure that is final is recorded; a success only when the window was not in front.
    for (const job of failedFresh) {
      if (job.group || needsSignIn(job)) continue
      this.store.record({
        kind: 'failed',
        title: `${shorten(job.title)} failed`,
        detail: job.error,
        target: {jobIds: [job.id]},
        count: 1,
      })
    }
    const singles = finished.filter((job) => !job.group)
    if (singles.length && !this.options.focused()) this.recordSingles(singles, spaces, now)
  }

  private recordSingles(jobs: Job[], spaces: Map<string, {name: string}>, now: number) {
    const ids = jobs.map((job) => job.id)
    const previous = this.single && now - this.single.at < SINGLE_MERGE_MS ? this.store.get(this.single.entryId) : undefined
    if (previous && this.single) {
      const all = [...this.single.jobIds, ...ids]
      this.single = {entryId: previous.id, at: now, jobIds: all}
      this.store.update(previous.id, {title: `${all.length} downloads finished`, detail: undefined, target: {jobIds: all.slice(-50)}, count: all.length})
      return
    }
    const first = jobs[0]
    const space = first.space ? spaces.get(first.space)?.name : undefined
    const entry = this.store.record(
      jobs.length === 1
        ? {kind: 'batch-done', title: `${shorten(first.title)} saved`, detail: space ? `Saved to ${space}` : undefined, target: {jobIds: ids}, count: 1}
        : {kind: 'batch-done', title: `${jobs.length} downloads finished`, target: {jobIds: ids.slice(-50)}, count: jobs.length},
    )
    this.single = {entryId: entry.id, at: now, jobIds: ids}
  }

  private settleGroup(id: string, members: Job[], spaces: Map<string, {name: string; kind?: string}>) {
    const counted = members.filter((job) => job.status !== 'cancelled')
    if (!counted.length) return
    const saved = counted.filter((job) => job.status === 'completed').length
    const failedJobs = counted.filter((job) => job.status === 'failed')
    const group = members[0].group!
    const space = members.find((job) => job.space)?.space
    const music = isMusic(counted, space ? spaces.get(space)?.kind : undefined)
    const spaceName = space ? spaces.get(space)?.name : undefined
    const collectionId = members.find((job) => job.collectionId)?.collectionId
    const name = group.title.slice(0, 120)

    const syncEntry = this.groupSync.get(id)
    if (!syncEntry && counted.length >= 2 && saved > 0) {
      const existing = this.store.find((entry) => entry.kind === 'batch-done' && entry.target?.groupId === id)
      const next = {
        title: `${name} finished`,
        detail: batchDoneDetail(saved, counted.length, music, spaceName),
        target: {groupId: id, ...(collectionId ? {collectionId} : {})},
        count: saved,
      }
      if (existing) this.store.update(existing.id, next)
      else this.store.record({kind: 'batch-done', ...next})
    }
    if (failedJobs.length) {
      const existing = this.store.find((entry) => entry.kind === 'failed' && entry.target?.groupId === id)
      const next = {
        title: `${failedJobs.length} ${noun(failedJobs.length, music)} from ${name} failed`,
        detail: failedJobs[0].error,
        target: {groupId: id, jobIds: failedJobs.map((job) => job.id).slice(0, 50)},
        count: failedJobs.length,
      }
      if (existing) this.store.update(existing.id, next)
      else this.store.record({kind: 'failed', ...next})
    } else {
      // A retry fixed everything: the old failure no longer needs attention.
      const old = this.store.find((entry) => entry.kind === 'failed' && entry.target?.groupId === id && !entry.read)
      if (old) this.store.markRead([old.id])
    }
  }

  /** Closes the "Downloading to…" line of a followed collection once every one of its batches has settled. */
  private settleSyncs(groups: Map<string, Job[]>) {
    for (const [entryId, batch] of this.syncs) {
      const members = [...batch.groups].flatMap((id) => groups.get(id) ?? [])
      if (!members.length || !members.every((job) => settledStatuses.includes(job.status))) continue
      const counted = members.filter((job) => job.status !== 'cancelled')
      const saved = counted.filter((job) => job.status === 'completed').length
      const failed = counted.filter((job) => job.status === 'failed').length
      const entry = this.store.get(entryId)
      if (!entry) {
        this.syncs.delete(entryId)
        continue
      }
      const settled = settleSyncFound(entry, saved, failed)
      if (settled.detail !== entry.detail) this.store.update(entryId, {detail: settled.detail}, {touch: false})
      batch.open = false
    }
  }

  private signIn(job: Job) {
    const provider = (job.provider || providerName(job.url)).slice(0, 60)
    const existing = this.store.find((entry) => entry.kind === 'sign-in' && !entry.read && entry.target?.provider === provider)
    if (existing) {
      this.store.update(existing.id, {})
      return
    }
    this.store.record({
      kind: 'sign-in',
      title: `Sign in to ${provider} again`,
      detail: 'Private or members-only downloads need it',
      target: {provider},
    })
  }

  // ------------------------------------------------------------------ followed collections

  /** A check queued new items: one entry per collection while its batch runs. */
  syncFound(found: {id: string; name: string; added: number; space?: string}, snapshot: Snapshot) {
    const now = this.now()
    const spaces = new Map(snapshot.settings.library.spaces.map((space) => [space.id, space]))
    const jobs = snapshot.jobs.filter((job) => job.collectionId === found.id && job.group)
    const known = new Set(this.groupSync.keys())
    // The newest batch of this collection that no entry owns yet.
    const fresh = [...groupsOf({...snapshot, jobs}).entries()]
      .filter(([id]) => !known.has(id))
      .sort((a, b) => Math.max(...b[1].map((j) => j.createdAt)) - Math.max(...a[1].map((j) => j.createdAt)))[0]
    const groupId = fresh?.[0]
    const spaceId = found.space ?? jobs.find((job) => job.space)?.space
    const spaceInfo = spaceId ? spaces.get(spaceId) : undefined
    const music = isMusic(fresh?.[1] ?? [], spaceInfo?.kind)
    const name = found.name.slice(0, 120)

    // The review that held these items is answered.
    this.store.removeWhere((entry) => entry.kind === 'sync-review' && entry.target?.collectionId === found.id)

    const running = [...this.syncs.entries()].find(([, batch]) => batch.collectionId === found.id && batch.open)
    const target: ActivityTarget = {collectionId: found.id, ...(groupId ? {groupId} : {})}
    let entryId: string
    const open = running && this.store.get(running[0])
    if (running && open) {
      const merged = mergeSyncFound(open, found.added, name, music, spaceInfo?.name, now)
      this.store.update(open.id, {title: merged.title, detail: merged.detail, count: merged.count, target}, {})
      entryId = open.id
      if (groupId) running[1].groups.add(groupId)
    } else {
      const text = syncFoundText(name, found.added, music, spaceInfo?.name)
      entryId = this.store.record({kind: 'sync-found', ...text, target, count: found.added}).id
      this.syncs.set(entryId, {collectionId: found.id, groups: new Set(groupId ? [groupId] : []), open: true})
    }
    if (groupId) this.groupSync.set(groupId, entryId)
  }

  /** More than the limit were found: they wait for the user. */
  syncHeld(found: {id: string; name: string; count: number; limit: number; space?: string}, snapshot: Snapshot) {
    const kind = found.space ? snapshot.settings.library.spaces.find((space) => space.id === found.space)?.kind : undefined
    const word = noun(found.count, kind === 'music')
    const next = {
      title: `${found.name.slice(0, 120)} has ${found.count} new ${word} waiting`,
      detail: `More than ${found.limit}, so they wait for you`,
      target: {collectionId: found.id},
      count: found.count,
    }
    const existing = this.store.find((entry) => entry.kind === 'sync-review' && entry.target?.collectionId === found.id)
    if (existing) this.store.update(existing.id, next)
    else this.store.record({kind: 'sync-review', ...next})
  }

  // ------------------------------------------------------------------ app updates

  /** First start of a new version records "updated to". The first ever start only remembers the version. */
  appStarted(current: string) {
    const previous = this.store.previousVersion
    if (previous && compareReleases(current, previous) > 0)
      this.store.record({
        kind: 'app-updated',
        title: `Keepfin updated to ${current}`,
        detail: 'See what changed in this version',
        target: {version: current},
      })
    this.store.setVersion(current)
  }

  updateReady(version: string) {
    if (this.store.find((entry) => entry.kind === 'update-ready' && entry.target?.version === version)) return
    this.store.record({kind: 'update-ready', title: `Keepfin ${version} is ready to install`, target: {version}})
  }
}

function groupsOf(snapshot: Pick<Snapshot, 'jobs'>): Map<string, Job[]> {
  const map = new Map<string, Job[]>()
  for (const job of snapshot.jobs) {
    if (!job.group) continue
    const list = map.get(job.group.id)
    if (list) list.push(job)
    else map.set(job.group.id, [job])
  }
  return map
}

/** Identifies what a settled batch looks like, so a retry that changes the outcome updates the entry once more. */
const keyOf = (members: Job[]) =>
  `${members.length}/${members.filter((job) => job.status === 'completed').length}/${members.filter((job) => job.status === 'failed').length}`

const shorten = (title: string) => (title.length > 90 ? `${title.slice(0, 89)}…` : title)

function needsSignIn(job: Job): boolean {
  for (const text of [job.errorDetail, job.error])
    if (text && friendlyError(text).action === 'signIn') return true
  return false
}

function isMusic(jobs: Job[], spaceKind?: string): boolean {
  if (spaceKind) return spaceKind === 'music'
  return jobs.length > 0 && jobs.every((job) => job.kind === 'audio')
}
