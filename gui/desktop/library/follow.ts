// Following collections: re-reads a downloaded playlist or channel, downloads what is new, and tells the user
// when a lot is waiting. Runs only while the app runs: a timer every 15 minutes plus a check shortly after launch.
import {scrubToolNames} from '../../shared/errors.js'
import {
  entryKey,
  gateNew,
  isInfiniteSource,
  newEntries,
  removedFromSource,
  type Collection,
  type FollowCheckResult,
} from '../../shared/library.js'
import type {PlaylistEntry} from '../../shared/contracts.js'
import {isDue} from '../../shared/core/spaces.js'
import type {LibraryIndex} from './index.js'

/** The slice of the download manager a follow check needs. */
export type FollowHost = {
  /** Changes whenever cookies or the proxy change; a check that spans a change is discarded. */
  readonly metadataEpoch: number
  followDiscover(url: string, cap: number): Promise<{entries: PlaylistEntry[]; truncated: boolean; partial: boolean; title?: string}>
  followEnqueue(collectionId: string, entries: PlaylistEntry[]): Promise<number>
}

/** Time source, injectable so schedules can be tested without waiting. */
export type FollowClock = {
  now(): number
  every(ms: number, run: () => void): () => void
  after(ms: number, run: () => void): () => void
}

export const systemClock: FollowClock = {
  now: () => Date.now(),
  every: (ms, run) => {
    const timer = setInterval(run, ms)
    timer.unref?.()
    return () => clearInterval(timer)
  },
  after: (ms, run) => {
    const timer = setTimeout(run, ms)
    timer.unref?.()
    return () => clearTimeout(timer)
  },
}

export type FollowNotice = {collectionId: string; title: string; body: string}

export type FollowOptions = {
  index: LibraryIndex
  host: FollowHost
  /** Shows a system notification (the caller respects the user's notification settings). */
  notify: (notice: FollowNotice) => void
  /** An automatic check found something (queued or held for review): the open app says so in a toast. */
  onFound?: (found: {id: string; name: string; added: number; pending?: number}) => void
  /** A check (automatic, Check now or Review) queued new downloads. Feeds Activity. */
  onQueued?: (queued: {id: string; name: string; added: number; space: string}) => void
  /** A check found more than the confirm limit and is holding them for review (called when the count changes). */
  onHeld?: (held: {id: string; name: string; count: number; limit: number; space: string}) => void
  clock?: FollowClock
  /** How often due collections are looked for. */
  tickMs?: number
  /** Delay before the first look after launch. */
  launchDelayMs?: number
  /** Entries read per check. */
  cap?: number
}

export const FOLLOW_TICK_MS = 15 * 60_000
export const FOLLOW_LAUNCH_DELAY_MS = 20_000
export const FOLLOW_CAP = 1000

// Whether a collection should be checked now is decided by the shared core (gui/shared/core/spaces.ts).
export {isDue}

const failure = (id: string, error: string): FollowCheckResult => ({id, added: 0, removed: 0, error})

function friendly(error: unknown): string {
  const text = error instanceof Error ? scrubToolNames(error.message) : ''
  return text.trim().slice(0, 300) || 'Keepfin could not check this collection. It will try again at the next interval.'
}

export class FollowEngine {
  private readonly clock: FollowClock
  private readonly inflight = new Map<string, Promise<FollowCheckResult>>()
  private chain: Promise<unknown> = Promise.resolve()
  private stops: (() => void)[] = []
  private stopped = false

  constructor(private readonly options: FollowOptions) {
    this.clock = options.clock ?? systemClock
  }

  /** Looks for overdue collections shortly after launch, then on a timer. */
  start() {
    this.stopped = false
    this.stops.push(
      this.clock.after(this.options.launchDelayMs ?? FOLLOW_LAUNCH_DELAY_MS, () => void this.checkDue()),
      this.clock.every(this.options.tickMs ?? FOLLOW_TICK_MS, () => void this.checkDue()),
    )
  }

  stop() {
    this.stopped = true
    for (const stop of this.stops) stop()
    this.stops = []
  }

  /** Checks every collection that is due, one at a time. */
  async checkDue(): Promise<FollowCheckResult[]> {
    const { index } = this.options
    if (this.stopped || !index.enabled) return []
    const now = this.clock.now()
    const results: FollowCheckResult[] = []
    for (const collection of index.collectionList()) {
      if (this.stopped) break
      if (!isDue(collection, now)) continue
      const result = await this.check(collection.id)
      results.push(result)
      if (!this.stopped && !result.error && !result.skipped && (result.added > 0 || result.pending))
        this.options.onFound?.({id: collection.id, name: collection.name, added: result.added, pending: result.pending})
    }
    return results
  }

  /** Checks one collection now ("Check now"), whatever its follow mode. */
  check(id: string): Promise<FollowCheckResult> {
    return this.schedule(id, false)
  }

  /** Downloads what a check found but held back because it was more than the confirm limit. */
  review(id: string): Promise<FollowCheckResult> {
    const collection = this.options.index.collection(id)
    if (!collection.follow?.pending) throw new Error('Nothing is waiting for review.')
    return this.schedule(id, true)
  }

  private schedule(id: string, approved: boolean): Promise<FollowCheckResult> {
    const running = this.inflight.get(id)
    if (running) return running
    const job = this.chain.then(() => this.run(id, approved))
    this.chain = job.catch(() => {})
    const tracked = job.finally(() => this.inflight.delete(id))
    this.inflight.set(id, tracked)
    return tracked
  }

  private async run(id: string, approved: boolean): Promise<FollowCheckResult> {
    const { index, host } = this.options
    let collection: Collection
    try {
      collection = index.collection(id)
    } catch (error) {
      return failure(id, friendly(error))
    }
    const source = collection.source
    if (!source || collection.kind === 'own') return failure(id, 'Only collections downloaded from a link can be checked.')
    if (isInfiniteSource(source.url)) return failure(id, 'Radio mixes never end, so Keepfin does not check them.')
    if (!index.enabled) return failure(id, 'Turn on the library to follow collections.')

    const epoch = host.metadataEpoch
    const recordError = (message: string) => {
      // An error is final until the next interval: no retry loop.
      index.updateCollection(id, (current) => {
        if (current.follow) current.follow = {...current.follow, lastCheckedAt: this.clock.now(), lastError: message}
      })
      return failure(id, message)
    }
    let listing
    try {
      listing = await host.followDiscover(source.url, this.options.cap ?? FOLLOW_CAP)
    } catch (error) {
      if (host.metadataEpoch !== epoch) return {id, added: 0, removed: 0, skipped: true}
      return recordError(friendly(error))
    }
    // Cookies or the proxy changed while reading: the listing may belong to another account, so nothing is recorded.
    if (host.metadataEpoch !== epoch) return {id, added: 0, removed: 0, skipped: true}
    try {
      collection = index.collection(id)
    } catch {
      return {id, added: 0, removed: 0, skipped: true}
    }

    const fresh = newEntries(listing.entries, collection.seen)
    // What left the source is judged only from a complete listing.
    let removedNow: string[] | undefined
    if (!listing.partial) {
      const present = new Set(listing.entries.map((entry) => entryKey(entry.url)))
      removedNow = removedFromSource(index.membersOf(id), present)
    }
    const follow = collection.follow
    if (!follow) return failure(id, 'This collection is not followed.')
    const decision = gateNew(fresh.length, follow.confirmAbove, approved)
    const now = this.clock.now()

    if (decision === 'confirm') {
      const previous = follow.pending
      index.updateCollection(id, (current) => {
        current.follow = {...follow, lastCheckedAt: now, lastError: undefined, pending: {count: fresh.length, at: previous?.count === fresh.length ? previous.at : now}}
        if (removedNow) current.removedItemIds = removedNow.length ? removedNow : undefined
      })
      if (previous?.count !== fresh.length)
        this.options.onHeld?.({id, name: collection.name, count: fresh.length, limit: follow.confirmAbove, space: collection.space})
      if (follow.notify && previous?.count !== fresh.length)
        this.options.notify({collectionId: id, title: 'Keepfin', body: `${fresh.length} new in ${collection.name} · Review`})
      return {id, added: 0, pending: fresh.length, removed: removedNow?.length ?? collection.removedItemIds?.length ?? 0}
    }

    let added = 0
    if (decision === 'enqueue') {
      try {
        added = await host.followEnqueue(id, fresh)
      } catch (error) {
        return recordError(friendly(error))
      }
    }
    index.updateCollection(id, (current) => {
      current.follow = {...(current.follow ?? follow), lastCheckedAt: now, lastError: undefined, pending: undefined}
      if (removedNow) current.removedItemIds = removedNow.length ? removedNow : undefined
      if (added) current.newItemIds = undefined
    })
    if (added) this.options.onQueued?.({id, name: collection.name, added, space: collection.space})
    return {id, added, removed: removedNow?.length ?? collection.removedItemIds?.length ?? 0}
  }
}
