// Copies on folders and drives, main-thread side (dev-docs/plans/KEEPFIN-COPIES-SPEC.md sections 2, 7, 8, 10, 5.8). Free of Electron
// imports: keepfinBoot.ts passes the folder dialog and the push channel in.
//
// What lives where:
//  - The places (name, mode, holds, options, schedule, replica) are `place.set` registers in the library, written through the
//    store, so every device sees them. Where a copy is on THIS computer, when it last ran and what it decided is device-local
//    (`<profile>/copies.json`, and `<profile>/copies/<placeId>/` for the baseline, journal and the last run).
//  - A sync runs in its own worker thread (copiesWorker.ts) over a read-only connection to the library's state database. Its
//    library-side actions come back here and become ordinary library operations.
//  - A second worker job reads what each copy holds (kept / waiting per row) from the manifests; the answer is cached and the
//    renderer is told when it changes.
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {Worker} from 'node:worker_threads'
import {existsSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import type {SyncReport} from '../shared/core/copies.js'
import {auditDue, syncReport} from '../shared/core/copies.js'
import type {
  CloudAccountView,
  CopiesArgs,
  CopiesState,
  CopyDraft,
  CopyFolderCheck,
  CopyPlaceView,
  CopyPreview,
  CopyReviewAction,
  CopyVersion,
} from '../shared/copies.js'
import {isInFolder} from '../shared/library.js'
import {holdsKey, optionsKey, type PlaceConfig} from './sqlstate/desired.js'
import {readRelationship, writeRelationship} from './copies/generation.js'
import type {RunSummary} from './copies/placeRun.js'
import type {CloudJobPayload, FromHost, Snapshot, ToHost, WorkerJob} from './copiesWorker.js'
import {CloudHost} from './cloud/host.js'
import type {AccountView, CloudAccounts} from './cloud/accounts.js'
import type {BuildConfig} from './cloud/oauth.js'
import type {CloudTestRoute} from './cloud/route.js'
import type {PeerBridge, PeerJobPayload, PeerPlaceSpec} from './net/peerPlace.js'
import {readJson, writeJsonAtomic} from './store/atomic.js'
import {inspectFolderChoice, openKeepfinFolder} from './store/keepfinFolder.js'
import type {OpDraft} from './store/mapping.js'
import type {StoreClient} from './store/storeClient.js'
import {CopiesBackups} from './copiesHost/backups.js'
import {CopiesCloud} from './copiesHost/cloud.js'
import type {HostCore, LocalFile, Pending, Runtime} from './copiesHost/core.js'
import {cloudStopped, emptyPreview, inside, prepareCopyFolder, previewFrom, removeFreshCopyFolder, sameFolder, suggestName, winDrives} from './copiesHost/helpers.js'
import {LibrarySide} from './copiesHost/librarySide.js'
import {CopiesReview} from './copiesHost/review.js'
import {CopiesView} from './copiesHost/view.js'
import {retryDelayMs, runUnfinished} from './copiesHost/retry.js'

// prepareCopyFolder stays importable from here
export {prepareCopyFolder} from './copiesHost/helpers.js'

export type CopiesPush = {type: 'copies'; state: CopiesState}

export type CopiesHostDeps = {
  /** This device's folder for the profile (`profiles\<id>`). */
  profileDir: string
  mainRoot: string
  mainReplica: string
  libraryId: string
  /** The library's state database (read by the worker). */
  dbPath: string
  store(): StoreClient
  newId(): string
  now(): number
  device: string
  push(event: CopiesPush): void
  pickFolder(defaultPath: string): Promise<string | undefined>
  /** The audio converter for MP3 exports (the download engine's), when there is one. */
  ffmpeg?: () => string | undefined
  /** The worker script; by default `copiesWorker.js` next to this module, else `copiesWorker.ts`. */
  workerUrl?: URL
  /** Timers: the drive check and the schedule. Off in tests that drive everything by hand. */
  watch?: boolean
  watchMs?: number
  /** Debounce of "sync when the library changes". */
  changeDebounceMs?: number
  /** The drive roots to look at (default: the Windows drive letters). */
  drives?(): Promise<string[]>
  /** Cloud accounts (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md). Absent: the screen offers no cloud path. */
  cloud?: {accounts: CloudAccounts; config: BuildConfig; openExternal(url: string): Promise<void>; test?: CloudTestRoute}
  /** Paired devices (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md 5): a copy can be kept in another device's Keepfin folder. Absent: no such path. */
  peers?: () => PeerBridge | undefined
  /** Moves a folder to the Recycle Bin (Remove copy… with its files). Absent: a copy's files always stay. */
  trash?(target: string): Promise<void>
  /** Shows what a sync the person started did, once it has finished (a system notification). */
  notify?(report: SyncReport): void
}

function workerUrl(): URL {
  const built = new URL('./copiesWorker.js', import.meta.url)
  return existsSync(fileURLToPath(built)) ? built : new URL('./copiesWorker.ts', import.meta.url)
}

export class CopiesHost {
  private local: LocalFile = {version: 1, places: {}, acks: []}
  private snap: Snapshot = {places: [], rows: [], spaceFolders: {}, coverage: {}, versions: {}, conflicts: []}
  private readonly rt = new Map<string, Runtime>()
  private readonly pending = new Map<string, Pending>()
  private found: {path: string; name: string}[] = []
  private ready = false
  private closed = false
  private pushTimer?: NodeJS.Timeout
  private snapTimer?: NodeJS.Timeout
  private watchTimer?: NodeJS.Timeout
  private lastDriveKey = ''
  private lastRecognitionAt = 0
  private libraryChangedAt = 0
  private seenSum = -1
  private readonly queue: string[] = []
  private active = 0
  private chain: Promise<unknown> = Promise.resolve()
  private offStore?: () => void
  private readonly cloud?: CloudHost
  /** The accounts as the screen last saw them (the state is built without waiting for the secure storage). */
  private accountViews: CloudAccountView[] = []
  private accountList = new Map<string, AccountView>()

  private readonly cloudPart: CopiesCloud
  private readonly reviewPart: CopiesReview
  private readonly viewPart: CopiesView
  private readonly libraryPart: LibrarySide
  private readonly backupsPart: CopiesBackups

  constructor(private readonly deps: CopiesHostDeps) {
    if (deps.cloud) this.cloud = new CloudHost({...deps.cloud, libraryId: deps.libraryId, mainReplica: deps.mainReplica}, deps.now)
    const core: HostCore = {
      deps,
      cloud: this.cloud,
      local: () => this.local,
      snap: () => this.snap,
      setSnap: (snap) => {
        this.snap = snap
      },
      rt: () => this.rt,
      pending: () => this.pending,
      found: () => this.found,
      report: () => this.report,
      closed: () => this.closed,
      accountViews: () => this.accountViews,
      setAccountViews: (views) => {
        this.accountViews = views
      },
      accountList: () => this.accountList,
      setAccountList: (list) => {
        this.accountList = list
      },
      runtime: (id) => this.runtime(id),
      stateDir: (placeId) => this.stateDir(placeId),
      saveLocal: () => this.saveLocal(),
      schedulePush: () => this.schedulePush(),
      scheduleSnapshot: (ms) => this.scheduleSnapshot(ms),
      idArg: (value) => this.idArg(value),
      setRegisters: (placeId, fields) => this.setRegisters(placeId, fields),
      noteLibraryChanged: () => this.noteLibraryChanged(),
      syncPlace: (placeId, flags) => this.syncPlace(placeId, flags),
      pause: (placeId) => this.pause(placeId),
      checkConnected: (place) => this.checkConnected(place),
      cancelPreview: (placeId) => this.cancelPreview(placeId),
      placeFrom: (id, draft, replica) => this.placeFrom(id, draft, replica),
      runWorker: (place, copyRoot, stateDir, flags) => this.runWorker(place, copyRoot, stateDir, flags),
      workerUrl: () => deps.workerUrl ?? workerUrl(),
      build: () => this.viewPart.build(),
      refreshSnapshot: () => this.refreshSnapshot(),
      rebuildReview: (place, rt) => this.reviewPart.rebuildReview(place, rt),
      conflictReview: () => this.reviewPart.conflictReview(),
      signInReview: () => this.reviewPart.signInReview(),
      refreshCloud: () => this.cloudPart.refreshCloud(),
      refreshQuotas: (force) => this.cloudPart.refreshQuotas(force),
      abs: (rel) => this.libraryPart.abs(rel),
      archiveMain: (file, why) => this.libraryPart.archiveMain(file, why),
      revise: (action, stagedPath, restoring) => this.libraryPart.revise(action, stagedPath, restoring),
      importNew: (action, stagedPath, destPath) => this.libraryPart.importNew(action, stagedPath, destPath),
    }
    this.cloudPart = new CopiesCloud(core)
    this.reviewPart = new CopiesReview(core)
    this.viewPart = new CopiesView(core)
    this.libraryPart = new LibrarySide(core)
    this.backupsPart = new CopiesBackups(core)
  }

  // ------------------------------------------------------------------ lifecycle

  private get localFile() {
    return path.join(this.deps.profileDir, 'copies.json')
  }

  private stateDir(placeId: string) {
    return path.join(this.deps.profileDir, 'copies', placeId)
  }

  private starting?: Promise<void>
  start(): Promise<void> {
    return (this.starting ??= this.doStart().catch((error) => {
      this.starting = undefined
      throw error
    }))
  }

  private async doStart(): Promise<void> {
    const saved = await readJson<LocalFile>(this.localFile).catch(() => undefined)
    if (saved && saved.version === 1) this.local = {version: 1, places: saved.places ?? {}, acks: saved.acks ?? []}
    await this.refreshSnapshot()
    await this.cloudPart.refreshCloud()
    for (const place of this.snap.places) await this.checkConnected(place)
    this.ready = true
    void this.cloudPart.refreshQuotas().catch(() => undefined)
    this.schedulePush()
    if (this.deps.watch !== false) {
      const ms = this.deps.watchMs ?? 5000
      this.watchTimer = setInterval(() => void this.tick().catch(() => undefined), ms)
      this.watchTimer.unref?.()
    }
  }

  async close(): Promise<void> {
    this.closed = true
    if (this.watchTimer) clearInterval(this.watchTimer)
    if (this.pushTimer) clearTimeout(this.pushTimer)
    if (this.snapTimer) clearTimeout(this.snapTimer)
    this.offStore?.()
    for (const rt of this.rt.values()) rt.abort?.()
    // a run that is stopping finishes its current action first; give it a moment, then end the thread
    const deadline = Date.now() + 8000
    while ([...this.rt.values()].some((rt) => rt.worker) && Date.now() < deadline) await new Promise((r) => setTimeout(r, 50))
    for (const rt of this.rt.values()) await rt.worker?.terminate().catch(() => undefined)
    await this.saveLocal().catch(() => undefined)
  }

  /** For "Where your files are": every copy with the replica it keeps, and the spaces and playlists a copy can hold (for their names). */
  whereInputs(): {copies: {view: CopyPlaceView; replica: string | null}[]; rows: CopiesState['rows']} {
    return {copies: this.snap.places.filter((p) => !p.removed).map((p) => ({view: this.viewPart.view(p), replica: p.replica, missing: this.snap.coverage[p.id]?.missingItems ?? null})), rows: this.snap.rows}
  }

  /** A paired device came or went, or changed what this device may do: the screen is told (a copy on it is connected or not). */
  devicesChanged(): void {
    this.schedulePush()
  }

  /**
   * The library changed (another device, or an import): look again soon, and sync the copies that sync by themselves. A change
   * the copies wrote themselves (a run's verified record) was counted when it was written, so it starts no sync.
   */
  noteLibraryChanged(): void {
    void this.librarySum()
      .then((sum) => {
        if (sum === this.seenSum) return
        const first = this.seenSum < 0
        this.seenSum = sum
        if (!first) this.libraryChangedAt = this.deps.now()
      })
      .catch(() => (this.libraryChangedAt = this.deps.now()))
    this.scheduleSnapshot(1500)
  }

  private async saveLocal() {
    await writeJsonAtomic(this.localFile, this.local)
  }

  private runtime(id: string): Runtime {
    let rt = this.rt.get(id)
    if (!rt) {
      rt = {connected: false, paused: false, previewPending: false, review: []}
      this.rt.set(id, rt)
    }
    return rt
  }

  // ------------------------------------------------------------------ state

  async state(): Promise<CopiesState> {
    await this.start().catch(() => undefined)
    await this.cloudPart.refreshCloud().catch(() => undefined)
    return this.viewPart.build()
  }

  /** Reads the places, rows and what each copy holds (a worker job), then the device-side facts about each copy. */
  async refreshSnapshot(): Promise<void> {
    return this.viewPart.refreshSnapshot()
  }

  async resolve(reviewId: string | undefined, action: CopyReviewAction | undefined): Promise<CopiesState> {
    return this.reviewPart.resolve(reviewId, action)
  }

  async versions(placeId: string): Promise<CopyVersion[]> {
    return this.backupsPart.versions(placeId)
  }

  /** Brings an earlier version back into the library as a new file under "Restored from backup"; the version stays on the backup. */
  async restore(placeId: string, entryId: string | undefined): Promise<CopiesState> {
    return this.backupsPart.restore(placeId, entryId)
  }

  async cleanup(placeId: string): Promise<CopiesState> {
    return this.backupsPart.cleanup(placeId)
  }

  private schedulePush(): void {
    if (this.closed || this.pushTimer) return
    this.pushTimer = setTimeout(() => {
      this.pushTimer = undefined
      if (!this.closed) this.deps.push({type: 'copies', state: this.viewPart.build()})
    }, 250)
    this.pushTimer.unref?.()
  }

  private scheduleSnapshot(ms = 800): void {
    if (this.closed) return
    if (this.snapTimer) clearTimeout(this.snapTimer)
    this.snapTimer = setTimeout(() => {
      this.snapTimer = undefined
      void this.refreshSnapshot().catch(() => undefined)
    }, ms)
    this.snapTimer.unref?.()
  }

  // ------------------------------------------------------------------ operations

  async handle(op: string, args: CopiesArgs): Promise<unknown> {
    switch (op) {
      case 'copiesState':
        return this.state()
      case 'copiesPickFolder':
        return (await this.deps.pickFolder(os.homedir())) ?? null
      case 'copiesCheckFolder':
        return this.checkFolder(this.pathArg(args.path))
      case 'copiesPreview':
        return this.preview(this.draftArg(args.draft), args.placeId)
      case 'copiesCancelPreview':
        await this.cancelPreview(this.idArg(args.placeId))
        return null
      case 'copiesAdd':
        return this.add(this.idArg(args.placeId))
      case 'copiesAdopt':
        return this.adopt(this.pathArg(args.found))
      case 'copiesSync':
        return this.syncCommand(args.placeId)
      case 'copiesPause':
        this.pause(args.placeId)
        return this.viewPart.build()
      case 'copiesEdit':
        return this.edit(args)
      case 'copiesRemove':
        return this.remove(this.idArg(args.placeId), args.deleteFiles === true)
      case 'copiesResolve':
        return this.resolve(args.reviewId, args.action)
      case 'copiesVersions':
        return this.versions(this.idArg(args.placeId))
      case 'copiesRestore':
        return this.restore(this.idArg(args.placeId), args.entryId)
      case 'copiesCleanup':
        return this.cleanup(this.idArg(args.placeId))
      case 'copiesCloudSignIn':
        return this.cloudPart.cloudSignIn(args.cloud)
      case 'copiesCloudCancel':
        this.cloud?.cancelSignIn()
        return null
      case 'copiesCloudWebdav':
        return this.cloudPart.cloudWebdav(args.cloud)
      case 'copiesCloudFind':
        return this.cloudPart.cloudFind(args.cloud)
      case 'copiesCloudAttach':
        return this.cloudPart.cloudAttach(args.cloud)
      case 'copiesCloudDisconnect':
        return this.cloudPart.cloudDisconnect(args.cloud)
      case 'copiesCloudRevoke':
        return this.cloudPart.cloudRevoke(args.cloud)
      default:
        throw new Error('Unknown copies request.')
    }
  }

  private idArg(value: unknown): string {
    if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(value)) throw new Error('Invalid request.')
    return value
  }

  private deviceArg(value: unknown): string {
    if (typeof value !== 'string' || !/^[0-9a-f]{64}$/.test(value)) throw new Error('Invalid request.')
    return value
  }

  private pathArg(value: unknown): string {
    if (typeof value !== 'string' || !value || value.length > 1024 || value.includes('\0') || !path.isAbsolute(value)) throw new Error('Invalid folder.')
    return path.resolve(value)
  }

  private draftArg(draft: CopyDraft | undefined): CopyDraft {
    if (!draft || typeof draft !== 'object') throw new Error('Invalid request.')
    const mode = draft.mode
    if (mode !== 'twoWay' && mode !== 'backup' && mode !== 'mirror') throw new Error('Invalid request.')
    const list = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x.length <= 80).slice(0, 5000) : [])
    const h = draft.holds ?? ({} as CopyDraft['holds'])
    const every = draft.schedule?.everyHours
    return {
      path: draft.cloud || draft.device ? '' : this.pathArg(draft.path),
      ...(draft.cloud ? {cloud: {accountId: this.idArg(draft.cloud.accountId), folder: String(draft.cloud.folder ?? '').trim().slice(0, 200)}} : {}),
      ...(draft.device ? {device: {deviceId: this.deviceArg(draft.device.deviceId)}} : {}),
      name: String(draft.name ?? '').trim().slice(0, 80) || 'Copy',
      mode,
      holds: {all: h.all === true, spaces: list(h.spaces), playlists: list(h.playlists), favourites: h.favourites === true, smart: list(h.smart)},
      schedule: {everyHours: typeof every === 'number' && every >= 1 && every <= 24 * 30 ? Math.trunc(every) : null, auto: draft.schedule?.auto !== false},
      companions: draft.companions !== false,
      ...(typeof draft.musicVideos === 'boolean' ? {musicVideos: draft.musicVideos} : {}),
    }
  }

  async checkFolder(target: string): Promise<CopyFolderCheck> {
    const keepfinFolder = await fs.access(path.join(target, '.keepfin', 'folder.json')).then(() => true, () => false)
    const base: CopyFolderCheck = {path: target, ok: false, suggestedName: suggestName(target), keepfinFolder}
    // the folder may not exist yet (the Keepfin folder the wizard makes inside the picked one): its parent must
    const stat = await fs.lstat(target).catch(() => undefined)
    const parent = stat ? undefined : await fs.lstat(path.dirname(target)).catch(() => undefined)
    if (!stat && !parent?.isDirectory()) return {...base, problem: 'This folder does not exist.'}
    if (stat?.isSymbolicLink()) return {...base, problem: 'This folder is reached through a link. Pick the real folder.'}
    if (stat && !stat.isDirectory()) return {...base, problem: 'This is not a folder.'}
    const real = stat ? await fs.realpath(target) : path.join(await fs.realpath(path.dirname(target)), path.basename(target))
    const mainReal = await fs.realpath(this.deps.mainRoot).catch(() => this.deps.mainRoot)
    if (inside(real, mainReal) || inside(mainReal, real)) return {...base, problem: 'A copy cannot be inside your Keepfin folder, or contain it.'}
    const choice = await inspectFolderChoice(target)
    const opened = await openKeepfinFolder(target)
    let isCopy = false
    if (opened.ok) {
      if (opened.folder.libraryId !== this.deps.libraryId) return {...base, problem: 'This folder belongs to a different Keepfin library.'}
      if (opened.folder.replicaId === this.deps.mainReplica) return {...base, problem: 'This is your Keepfin folder itself.'}
      const known = this.snap.places.find((p) => !p.removed && p.replica === opened.folder.replicaId)
      if (known) return {...base, problem: `This is already your copy "${known.name || 'Copy'}".`}
      isCopy = true
    } else if (opened.reason === 'newer-format') return {...base, problem: 'This folder was made by a newer version of Keepfin.'}
    else if (opened.reason === 'corrupt') return {...base, problem: 'This Keepfin folder is damaged.'}
    if (!choice.writable) return {...base, problem: 'Keepfin cannot write to this folder. Pick another one.'}
    for (const [id, l] of Object.entries(this.local.places)) {
      if (this.snap.places.some((p) => p.id === id && !p.removed) && sameFolder(path.resolve(l.path), path.resolve(target))) return {...base, problem: 'This folder is already one of your copies.'}
    }
    let existing = 0
    for (const e of await fs.readdir(target).catch(() => [] as string[])) {
      if (e !== '.keepfin' && e !== '$RECYCLE.BIN' && e !== 'System Volume Information') existing++
      if (existing >= 1000) break
    }
    const space = await fs.statfs(stat ? target : path.dirname(target)).catch(() => undefined)
    return {
      ...base,
      ok: true,
      isCopy,
      existingFiles: existing,
      ...(stat ? {} : {willCreate: true}),
      ...(space ? {freeBytes: Number(space.bavail) * Number(space.bsize), totalBytes: Number(space.blocks) * Number(space.bsize)} : {}),
      insideCloudApp: choice.insideCloudApp,
    }
  }

  // ---- the wizard

  async preview(draft: CopyDraft, previousId?: string): Promise<CopyPreview> {
    if (draft.cloud) return this.cloudPart.previewCloud(draft, previousId)
    if (draft.device) return this.previewPeer(draft, previousId)
    const check = await this.checkFolder(draft.path)
    if (!check.ok) return emptyPreview(previousId ?? '', 'refused', check.problem ?? 'This folder cannot be used.')
    let entry = previousId ? this.pending.get(previousId) : undefined
    if (entry && !sameFolder(entry.draft.path, draft.path)) {
      await this.cancelPreview(entry.placeId)
      entry = undefined
    }
    if (!entry) {
      const prepared = await prepareCopyFolder(draft.path, this.deps.libraryId, this.deps.newId, new Date(this.deps.now()).toISOString())
      entry = {draft, placeId: this.deps.newId(), replica: prepared.replicaId, created: prepared.created, madeRoot: prepared.madeRoot, root: draft.path}
      this.pending.set(entry.placeId, entry)
    }
    entry.draft = draft
    const place = this.placeFrom(entry.placeId, draft, entry.replica)
    const stateDir = this.stateDir(entry.placeId)
    await fs.mkdir(stateDir, {recursive: true})
    const summary = await this.runWorker(place, draft.path, stateDir, {dryRun: true})
    return previewFrom(entry.placeId, summary)
  }

  // ------------------------------------------------------------------ a copy on a paired device (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md 5)

  private async previewPeer(draft: CopyDraft, previousId?: string): Promise<CopyPreview> {
    const bridge = this.deps.peers?.()
    if (!bridge || !draft.device) throw new Error('Devices are not available in this version of Keepfin.')
    const choice = bridge.devices().find((d) => d.deviceId === draft.device!.deviceId)
    if (!choice) return emptyPreview(previousId ?? '', 'refused', 'That device is not paired with this one.')
    if (!choice.allowed) return emptyPreview(previousId ?? '', 'refused', choice.why ?? 'That device does not allow a copy on it.')
    const spec: PeerPlaceSpec = {deviceId: choice.deviceId, libraryId: this.deps.libraryId}
    let entry = previousId ? this.pending.get(previousId) : undefined
    if (entry && entry.peer?.deviceId !== spec.deviceId) {
      await this.cancelPreview(entry.placeId)
      entry = undefined
    }
    if (!entry) {
      const placeId = this.deps.newId()
      const mirror = this.cloudPart.mirrorDir(placeId)
      await fs.mkdir(mirror, {recursive: true})
      const prepared = await prepareCopyFolder(mirror, this.deps.libraryId, this.deps.newId, new Date(this.deps.now()).toISOString())
      entry = {draft, placeId, replica: prepared.replicaId, created: true, root: mirror, peer: spec}
      this.pending.set(placeId, entry)
    }
    entry.draft = draft
    const place: PlaceConfig = {...this.placeFrom(entry.placeId, draft, entry.replica), kind: 'device'}
    const stateDir = this.stateDir(entry.placeId)
    await fs.mkdir(stateDir, {recursive: true})
    const payload = this.peerPayload(spec)
    if ('summary' in payload) return previewFrom(entry.placeId, payload.summary)
    const summary = await this.runWorker(place, entry.root, stateDir, {dryRun: true, peer: payload.payload})
    return previewFrom(entry.placeId, summary)
  }

  /** What the worker needs to run a device copy: where the device is now and the key to reach it with. An unreachable device ends the run here. */
  private peerPayload(spec: PeerPlaceSpec): {payload: PeerJobPayload} | {summary: RunSummary} {
    const connection = this.deps.peers?.()?.connection(spec.deviceId)
    if (!connection) return {summary: cloudStopped('disconnected', 'That device is not connected right now. The copy continues when it is.', 'offline', this.deps.now())}
    return {payload: {spec, connection}}
  }

  async cancelPreview(placeId: string): Promise<void> {
    const entry = this.pending.get(placeId)
    if (!entry) return
    this.pending.delete(placeId)
    this.rt.delete(placeId)
    await fs.rm(this.stateDir(placeId), {recursive: true, force: true})
    // a folder the wizard made a Keepfin folder of, and nothing else has used since, goes back as it was
    if (entry.created) await removeFreshCopyFolder(entry.root, entry.madeRoot)
  }

  private placeFrom(id: string, draft: CopyDraft, replica: string): PlaceConfig {
    return {
      id,
      kind: 'folder',
      name: draft.name,
      replica,
      mode: draft.mode,
      holds: {...draft.holds, smart: draft.holds.smart ?? []},
      options: {playlistFiles: false, flat: false, mp3: false, companions: draft.companions !== false, ...(draft.musicVideos !== undefined ? {musicVideos: draft.musicVideos} : {}), versionsKeepDays: 90, deleteGuardPercent: 10},
      schedule: draft.schedule,
      removed: false,
      verified: null,
    }
  }

  async add(placeId: string): Promise<CopiesState> {
    const entry = this.pending.get(placeId)
    if (!entry) throw new Error('This preview has expired. Start again.')
    const draft = entry.draft
    const stateDir = this.stateDir(placeId)
    await fs.mkdir(stateDir, {recursive: true})
    await writeRelationship(stateDir, {generation: null, previewConfirmed: true})
    this.local.places[placeId] = entry.cloud ? {path: entry.root, cloud: entry.cloud} : entry.peer ? {path: entry.root, peer: entry.peer} : {path: draft.path}
    await this.saveLocal()
    await this.setRegisters(placeId, {
      kind: entry.cloud ? `cloud:${entry.cloud.provider}` : entry.peer ? 'device' : 'folder',
      name: draft.name,
      replica: entry.replica,
      mode: draft.mode,
      holds: {all: draft.holds.all, spaces: draft.holds.spaces, playlists: draft.holds.playlists, favourites: draft.holds.favourites, smart: draft.holds.smart ?? []},
      options: {playlistFiles: false, flat: false, mp3: false, companions: draft.companions !== false, ...(draft.musicVideos !== undefined ? {musicVideos: draft.musicVideos} : {}), versionsKeepDays: 90, deleteGuardPercent: 10},
      schedule: {everyHours: draft.schedule.everyHours, auto: draft.schedule.auto},
    })
    this.pending.delete(placeId)
    if (entry.cloud) await this.cloudPart.refreshCloud()
    const rt = this.runtime(placeId)
    rt.connected = true
    rt.previewPending = false
    await this.refreshSnapshot()
    void this.syncPlace(placeId, {previewConfirmed: true})
    return this.viewPart.build()
  }

  /** A drive that holds a copy of this library but is not one of the copies yet: the wizard starts at its folder. */
  private async adopt(target: string): Promise<CopyFolderCheck> {
    return this.checkFolder(target)
  }

  // ---- changing a place

  private async setRegisters(placeId: string, fields: Record<string, unknown>): Promise<void> {
    const drafts: OpDraft[] = Object.entries(fields).map(([field, value]) => ({kind: 'place.set', body: {place: placeId, field, value: value as never}}))
    await this.deps.store().applyExternal(drafts)
  }

  private async edit(args: CopiesArgs): Promise<CopiesState> {
    const id = this.idArg(args.placeId)
    const place = this.snap.places.find((p) => p.id === id && !p.removed)
    if (!place) throw new Error('That copy is gone.')
    const fields: Record<string, unknown> = {}
    if (typeof args.name === 'string' && args.name.trim() && args.name.trim() !== place.name) fields.name = args.name.trim().slice(0, 80)
    if (args.holds) {
      const d = this.draftArg({path: this.local.places[id]?.path ?? os.homedir(), name: place.name, mode: place.mode, holds: args.holds, schedule: place.schedule})
      if (holdsKey({...d.holds, smart: d.holds.smart ?? []}) !== holdsKey(place.holds)) fields.holds = d.holds
    }
    if (args.schedule) {
      const d = this.draftArg({path: this.local.places[id]?.path ?? os.homedir(), name: place.name, mode: place.mode, holds: place.holds, schedule: args.schedule})
      fields.schedule = {everyHours: d.schedule.everyHours, auto: d.schedule.auto}
    }
    let options = place.options
    if (args.exports) {
      if (this.local.places[id]?.cloud || this.local.places[id]?.peer) throw new Error('Playlist files and other exports are for copies on a folder or drive.')
      const e = args.exports
      options = {...options, playlistFiles: e.playlistFiles === true, flat: e.flat === true, mp3: e.mp3 === true}
    }
    // turning them off never deletes the ones already on the copy (a left-out row never does)
    if (typeof args.companions === 'boolean') options = {...options, companions: args.companions}
    if (typeof args.musicVideos === 'boolean') options = {...options, musicVideos: args.musicVideos}
    if (optionsKey(options) !== optionsKey(place.options)) fields.options = options
    if (Object.keys(fields).length) {
      await this.setRegisters(id, fields)
      await this.refreshSnapshot()
      if ((fields.holds || fields.options) && place.schedule.auto) void this.syncPlace(id, {})
    }
    return this.viewPart.build()
  }

  /**
   * Stops keeping a copy. Its files stay where they are, unless [deleteFiles]: then the copy's folder (a folder or drive copy, never
   * the Keepfin folder or one around it) goes to the Recycle Bin first, so a folder that cannot move leaves the copy as it was.
   */
  private async remove(id: string, deleteFiles = false): Promise<CopiesState> {
    const rt = this.runtime(id)
    // a second click, or the page opened again: the removal already running is the only one
    if (rt.removal?.state === 'removing') return this.viewPart.build()
    const wasPaused = rt.paused
    const name = this.snap.places.find((p) => p.id === id)?.name || 'The copy'
    const step = (text: string) => {
      rt.removal = {state: 'removing', text}
      this.schedulePush()
    }
    const local = this.local.places[id]
    step(deleteFiles ? 'Moving its files to the Recycle Bin…' : 'Stopping the copy…')
    try {
      this.pause(id)
      if (deleteFiles) {
        const folder = local && !local.cloud && !local.peer ? local.path : undefined
        if (!folder || !this.deps.trash) throw new Error('Only a copy in a folder or on a drive can have its files moved to the Recycle Bin.')
        if (isInFolder(folder, this.deps.mainRoot) || isInFolder(this.deps.mainRoot, folder)) throw new Error('This copy is inside or around your Keepfin folder, so its files stay.')
        try {
          if (await fs.stat(folder).catch(() => undefined)) await this.deps.trash(folder)
        } catch {
          throw new Error(`Keepfin could not move ${folder} to the Recycle Bin. Another program (often File Explorer) may have it open; close it and try again. The copy was not removed.`)
        }
      }
      step('Forgetting the copy…')
      await this.setRegisters(id, {removed: true})
      delete this.local.places[id]
      this.local.acks = this.local.acks.filter((k) => !k.startsWith(`${id}|`))
      await this.saveLocal()
      await fs.rm(this.stateDir(id), {recursive: true, force: true})
    } catch (error) {
      rt.paused = wasPaused
      rt.removal = {state: 'failed', text: error instanceof Error ? error.message : String(error)}
      this.schedulePush()
      throw error
    }
    this.rt.delete(id)
    await this.refreshSnapshot()
    const files = deleteFiles ? 'Its files are in the Recycle Bin.' : local && !local.cloud && !local.peer && local.path ? `Its files stay in ${local.path}.` : 'Its files stay where they are.'
    this.report = {title: `${name} was removed`, detail: files, warn: false, at: this.deps.now()}
    this.schedulePush()
    return this.viewPart.build()
  }

  // ------------------------------------------------------------------ running

  private async syncCommand(placeId: string | undefined): Promise<CopiesState> {
    const ids = placeId ? [this.idArg(placeId)] : this.snap.places.filter((p) => !p.removed).map((p) => p.id)
    const runs: Promise<[string, RunSummary | undefined]>[] = []
    for (const id of ids) {
      const rt = this.runtime(id)
      // a copy that is already syncing reports through its own run
      const running = !!rt.worker || this.queue.includes(id)
      rt.paused = false
      rt.error = undefined
      const run = this.syncPlace(id, {})
      if (!running) runs.push(run.then((summary) => [id, summary]))
    }
    if (runs.length) {
      this.report = undefined
      void Promise.all(runs).then((done) => this.reportSync(done))
    }
    return this.viewPart.build()
  }

  /** What the sync the person started did: kept for the page and shown once as a notification. Paused runs say nothing. */
  private reportSync(done: [string, RunSummary | undefined][]): void {
    if (this.closed) return
    const nameOf = (id: string) => this.snap.places.find((p) => p.id === id)?.name ?? 'A copy'
    let synced = 0
    let changed = 0
    let problems = 0
    const notReached: string[] = []
    const needOk: string[] = []
    for (const [id, s] of done) {
      if (s?.status === 'paused') continue
      if (s?.status === 'done') {
        synced++
        changed += s.committed
        problems += s.failedCount
      } else if (s && (s.status === 'held' || s.status === 'refused' || s.status === 'preview')) needOk.push(nameOf(id))
      else notReached.push(nameOf(id))
    }
    if (!synced && !notReached.length && !needOk.length) return
    const report = syncReport(synced, changed, problems, notReached, needOk)
    this.report = {...report, at: this.deps.now()}
    this.schedulePush()
    this.deps.notify?.(report)
  }

  pause(placeId?: string): void {
    for (const [id, rt] of this.rt) {
      if (placeId && id !== placeId) continue
      if (rt.worker) {
        rt.paused = true
        rt.abort?.()
      } else {
        rt.paused = true
        rt.waitingFor = undefined
        const at = this.queue.indexOf(id)
        if (at >= 0) this.queue.splice(at, 1)
      }
    }
    this.schedulePush()
  }

  /** Starts a sync of one place (queued: at most two copies run at once); resolves when it has finished. */
  syncPlace(placeId: string, flags: {previewConfirmed?: boolean; confirmHeld?: boolean; audit?: boolean}): Promise<RunSummary | undefined> {
    const rt = this.runtime(placeId)
    if (rt.worker || this.queue.includes(placeId) || rt.removal?.state === 'removing') return Promise.resolve(undefined)
    rt.paused = false
    return new Promise((resolve) => {
      const go = async () => {
        this.active++
        rt.waitingFor = undefined
        try {
          resolve(await this.runNow(placeId, flags))
        } catch (error) {
          rt.error = error instanceof Error ? error.message : String(error)
          // a run that threw never reached its result: nothing is marked as seen, and it tries again on its own
          if (!this.closed) this.planRetry(rt, rt.error)
          resolve(undefined)
        } finally {
          this.active--
          this.pump()
        }
      }
      this.jobs.set(placeId, go)
      this.queue.push(placeId)
      // both slots are taken: the row says whom it waits for
      if (this.active >= 2) {
        const names = this.snap.places.filter((p) => this.rt.get(p.id)?.worker).map((p) => p.name || 'another copy')
        rt.waitingFor = names.join(' and ') || 'another copy'
      }
      this.pump()
    })
  }

  private planRetry(rt: Runtime, reason: string): void {
    const attempts = (rt.retry?.attempts ?? 0) + 1
    rt.retry = {attempts, at: this.deps.now() + retryDelayMs(attempts), reason}
  }

  private readonly jobs = new Map<string, () => Promise<void>>()
  /** The last sync the person started, once it finished. */
  private report: CopiesState['report']
  private pump(): void {
    while (this.active < 2 && this.queue.length) {
      const id = this.queue.shift()!
      const job = this.jobs.get(id)
      this.jobs.delete(id)
      if (job) void job()
    }
  }

  private async runNow(placeId: string, flags: {previewConfirmed?: boolean; confirmHeld?: boolean; audit?: boolean}): Promise<RunSummary | undefined> {
    const place = this.snap.places.find((p) => p.id === placeId && !p.removed)
    const local = this.local.places[placeId]
    const rt = this.runtime(placeId)
    if (!place || !local || this.closed) return undefined
    if (local.cloud) await this.cloudPart.refreshCloud().catch(() => undefined)
    await this.checkConnected(place)
    if (!rt.connected) {
      this.schedulePush()
      return undefined
    }
    // Publish before copying: operations wait in the outbox for a while (batched flushes), and the copy must not lag them
    // (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md R7). A failed flush changes nothing: the ops are safe in the outbox.
    await Promise.resolve().then(() => this.deps.store().flush()).catch(() => undefined)
    const stateDir = this.stateDir(placeId)
    await fs.mkdir(stateDir, {recursive: true})
    const deletesFile = path.join(stateDir, 'confirmed-deletes.json')
    const confirmedDeletes = (await readJson<string[]>(deletesFile).catch(() => undefined)) ?? []
    rt.error = undefined
    const cloud = local.cloud ? await this.cloudPart.cloudPayload(local.cloud) : undefined
    const peer = local.peer ? this.peerPayload(local.peer) : undefined
    const stopped = cloud && 'summary' in cloud ? cloud.summary : peer && 'summary' in peer ? peer.summary : undefined
    const summary = stopped ?? (await this.runWorker(place, local.path, stateDir, {...flags, confirmedDeletes, ...(cloud && 'payload' in cloud ? {cloud: cloud.payload} : {}), ...(peer && 'payload' in peer ? {peer: peer.payload} : {})}))
    if (confirmedDeletes.length && summary.status === 'done') await fs.rm(deletesFile, {force: true})
    rt.last = summary
    rt.progress = undefined
    local.lastRunAt = this.deps.now()
    if (summary.status === 'done' && summary.manifest) {
      await this.setRegisters(placeId, {verified: {at: summary.finishedAt, manifest: summary.manifest.n, sha256: summary.manifest.sha256, generation: summary.generation.committedAt ?? 0}}).catch(() => undefined)
    }
    // what the run itself wrote to the library, its verified record included, is not "the library changed"
    const sum = await this.librarySum().catch(() => undefined)
    this.seenSum = sum ?? this.seenSum
    // only a run that finished marks the changes as seen; one that could not reach the copy or found it held tries again with back-off
    if (runUnfinished(summary)) this.planRetry(rt, summary.cloud?.message ?? summary.reason ?? 'The copy could not be reached.')
    else {
      local.seenLast = sum
      rt.retry = undefined
    }
    rt.paused = summary.status === 'paused'
    await this.saveLocal().catch(() => undefined)
    this.reviewPart.rebuildReview(place, rt)
    if (local.cloud) {
      await this.cloudPart.refreshCloud().catch(() => undefined)
      await this.checkConnected(place)
    }
    await this.refreshSnapshot().catch(() => undefined)
    if (local.cloud) void this.cloudPart.refreshQuotas(true).catch(() => undefined)
    return summary
  }

  private runWorker(place: PlaceConfig, copyRoot: string, stateDir: string, flags: {dryRun?: boolean; previewConfirmed?: boolean; confirmHeld?: boolean; audit?: boolean; confirmedDeletes?: string[]; cloud?: CloudJobPayload; peer?: PeerJobPayload}): Promise<RunSummary> {
    const rt = this.runtime(place.id)
    const job: WorkerJob = {
      job: 'run',
      dbPath: this.deps.dbPath,
      mainRoot: this.deps.mainRoot,
      copyRoot,
      stateDir,
      place,
      mainReplica: this.deps.mainReplica,
      device: this.deps.device,
      ffmpeg: this.deps.ffmpeg?.(),
      ...flags,
    }
    return new Promise<RunSummary>((resolve, reject) => {
      const worker = new Worker(this.deps.workerUrl ?? workerUrl(), {workerData: job, resourceLimits: {maxOldGenerationSizeMb: 1536}})
      if (!flags.dryRun) {
        rt.worker = worker
        rt.startedAt = this.deps.now()
        rt.progress = {doneActions: 0, totalActions: 0, doneBytes: 0, totalBytes: 0, current: null}
        rt.abort = () => worker.postMessage({t: 'abort'} satisfies FromHost)
        this.schedulePush()
      }
      let settled = false
      const finish = (fn: () => void) => {
        if (settled) return
        settled = true
        if (rt.worker === worker) {
          rt.worker = undefined
          rt.abort = undefined
        }
        void worker.terminate()
        fn()
        this.schedulePush()
      }
      worker.on('message', (m: ToHost) => {
        if (m.t === 'progress') {
          if (!flags.dryRun) {
            rt.progress = m.p
            this.schedulePush()
          }
        } else if (m.t === 'host') {
          this.chain = this.chain.then(() =>
            this.libraryPart.libraryAction(m.method, m.req).then(
              (value) => worker.postMessage({t: 'hostRes', id: m.id, ok: true, value} satisfies FromHost),
              (error) => worker.postMessage({t: 'hostRes', id: m.id, ok: false, error: error instanceof Error ? error.message : String(error)} satisfies FromHost),
            ),
          )
        } else if (m.t === 'cloudAuth') {
          // the worker never holds a refresh token: it asks here, and a refused refresh comes back as a sign-in problem
          const reply = (r: FromHost) => worker.postMessage(r)
          const spec = flags.cloud?.spec
          if (!spec || !this.cloud) reply({t: 'cloudAuthRes', id: m.id, ok: false, error: 'Not a cloud copy.'})
          else
            this.cloud.workerAuth(spec.accountId, m.force).then(
              (a) => reply({t: 'cloudAuthRes', id: m.id, ok: true, header: a.header, expiresAtMs: a.expiresAtMs}),
              (error) => reply({t: 'cloudAuthRes', id: m.id, ok: false, error: error instanceof Error ? error.message : String(error)}),
            )
        } else if (m.t === 'done' && m.summary) finish(() => resolve(m.summary!))
        else if (m.t === 'error') finish(() => reject(new Error(m.message)))
      })
      worker.on('error', (error) => finish(() => reject(error)))
      worker.on('exit', () => finish(() => reject(new Error('The copy sync stopped unexpectedly.'))))
    })
  }

  // ------------------------------------------------------------------ recognition and schedule (spec 7, 8)

  /** Is the copy at its remembered path? If not, was it seen elsewhere (another drive letter)? */
  private async checkConnected(place: PlaceConfig): Promise<void> {
    const rt = this.runtime(place.id)
    const local = this.local.places[place.id]
    const was = rt.connected
    let now = false
    if (local?.cloud) {
      // a cloud copy is reachable when this computer holds a working sign-in for its account
      const acct = this.accountList.get(local.cloud.accountId)
      now = !!acct && !acct.needsSignIn
    } else if (local?.peer) {
      // a copy on a paired device is reachable while that device is connected
      now = this.deps.peers?.()?.online(local.peer.deviceId) === true
    } else if (local) {
      const opened = await openKeepfinFolder(local.path).catch(() => undefined)
      now = !!opened?.ok && opened.folder.replicaId === place.replica && opened.folder.libraryId === this.deps.libraryId
    }
    rt.connected = now
    if (now && !was && !local?.cloud && !local?.peer) {
      const s = await fs.statfs(local!.path).catch(() => undefined)
      if (s) {
        rt.freeBytes = Number(s.bavail) * Number(s.bsize)
        rt.totalBytes = Number(s.blocks) * Number(s.bsize)
      }
    }
  }

  private async recognise(): Promise<void> {
    const missing = this.snap.places.filter((p) => !p.removed && this.local.places[p.id] && !this.local.places[p.id].cloud && !this.local.places[p.id].peer && !this.runtime(p.id).connected)
    const drives = await (this.deps.drives ?? winDrives)()
    const key = drives.join(',')
    const changed = key !== this.lastDriveKey
    this.lastDriveKey = key
    const due = this.deps.now() - this.lastRecognitionAt > 60_000
    if (!missing.length && !changed) return
    if (!changed && !due) return
    this.lastRecognitionAt = this.deps.now()
    const found: {path: string; name: string}[] = []
    const known = new Set(this.snap.places.filter((p) => !p.removed).map((p) => p.replica))
    for (const root of drives) {
      const candidates = [root]
      for (const e of (await fs.readdir(root, {withFileTypes: true}).catch(() => [])).slice(0, 200)) if (e.isDirectory() && !e.name.startsWith('$') && e.name !== 'System Volume Information') candidates.push(path.join(root, e.name))
      for (const dir of candidates) {
        if (sameFolder(path.resolve(dir), path.resolve(this.deps.mainRoot))) continue
        const opened = await openKeepfinFolder(dir).catch(() => undefined)
        if (!opened?.ok || opened.folder.libraryId !== this.deps.libraryId || opened.folder.replicaId === this.deps.mainReplica) continue
        const place = missing.find((p) => p.replica === opened.folder.replicaId)
        if (place) {
          // recognised by its replica id, never by its drive letter
          this.local.places[place.id].path = dir
          await this.saveLocal()
        } else if (!known.has(opened.folder.replicaId)) found.push({path: dir, name: suggestName(dir)})
      }
    }
    this.found = found.slice(0, 3)
    this.schedulePush()
  }

  private async librarySum(): Promise<number> {
    // the store's version vector: one number per actor, kept current by the client without a round trip to the worker
    let sum = 0
    for (const n of this.deps.store().last.values()) sum += n
    return sum
  }

  /** When each copy's last background audit was asked for in this session, so a failed one is not retried every few seconds. */
  private readonly auditAsked = new Map<string, number>()

  private async auditIsDue(placeId: string, now: number): Promise<boolean> {
    if (now - (this.auditAsked.get(placeId) ?? 0) < 6 * 3_600_000) return false
    const rel = await readRelationship(this.stateDir(placeId)).catch(() => undefined)
    if (!rel || !auditDue(rel.auditedAt, now)) return false
    this.auditAsked.set(placeId, now)
    return true
  }

  private async tick(): Promise<void> {
    if (this.closed) return
    if (!this.ready) {
      await this.start().catch(() => undefined)
      return
    }
    await this.recognise()
    const now = this.deps.now()
    let sum: number | undefined
    try {
      sum = await this.librarySum()
    } catch {
      sum = undefined
    }
    if (sum !== undefined && this.seenSum >= 0 && sum !== this.seenSum) {
      this.seenSum = sum
      this.libraryChangedAt = now
    } else if (sum !== undefined && this.seenSum < 0) this.seenSum = sum
    const debounce = this.deps.changeDebounceMs ?? 30_000
    for (const place of this.snap.places) {
      if (place.removed) continue
      const rt = this.runtime(place.id)
      const local = this.local.places[place.id]
      if (!local) continue
      const was = rt.connected
      await this.checkConnected(place)
      if (rt.connected !== was) this.schedulePush()
      if (!rt.connected || rt.worker || rt.paused || this.queue.includes(place.id) || rt.previewPending) continue
      if (rt.last?.status === 'held' || (rt.last?.status === 'refused' && !rt.last.cloud)) continue
      if (rt.last?.cloud?.state === 'needsSignIn' || rt.last?.cloud?.state === 'quota') continue
      if (!place.schedule.auto && !place.schedule.everyHours) continue
      if (rt.retry) {
        if (now >= rt.retry.at) void this.syncPlace(place.id, {})
        continue
      }
      const appeared = rt.connected && !was
      // a full audit (every file hashed) by itself every 30 days, while the copy is here and nothing else is running (spec 3)
      if (!local.cloud && !local.peer && (await this.auditIsDue(place.id, now))) {
        void this.syncPlace(place.id, {audit: true})
        continue
      }
      const due = place.schedule.everyHours ? now - (local.lastRunAt ?? 0) >= place.schedule.everyHours * 3_600_000 : false
      const changed = place.schedule.auto && this.libraryChangedAt > (local.lastRunAt ?? 0) && now - this.libraryChangedAt >= debounce
      if ((place.schedule.auto && appeared) || due || changed) void this.syncPlace(place.id, {})
    }
  }
}

