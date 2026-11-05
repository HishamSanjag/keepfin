// "Where your files are", main-process side (dev-docs/plans/WHERE-FILES.md). Free of Electron imports: keepfinBoot.ts passes the pieces in.
//
// The heavy part is the count in the state database (store/where.ts, run in the store's worker thread). It is cached and read again only
// after a library change (3 s after the last one) or when a copy becomes complete or stops being; a copy that syncs or a device that comes
// and goes only rebuilds the answer from the cached counts, which is cheap. Pushes go out (at most 2 per second) only while the screen
// has asked for the state in the last ten minutes, so a library nobody looks at is never counted behind its back.
import type {SyncMembership} from '../shared/core/sync.js'
import type {CopyPlaceView, CopyRow} from '../shared/copies.js'
import type {DevicesState} from '../shared/devices.js'
import {emptyWhere, type WhereItemView, type WhereState} from '../shared/where.js'
import type {StoreClient} from './store/storeClient.js'
import type {WhereCounts} from './store/where.js'
import {buildWhere, completeCovers, itemPlaces, type WhereCopy} from './whereState.js'

export type WhereHostDeps = {
  store(): StoreClient
  mainReplica: string
  /** The copies and the spaces they can hold, when the library has copies. */
  copies?(): {copies: {view: CopyPlaceView; replica: string | null}[]; rows: CopyRow[]}
  /** The paired devices and this device's name, when devices are available. */
  devices?(): DevicesState | undefined
  push(event: {type: 'where'; state: WhereState}): void
  thisName(): string
  now?(): number
}

const WATCH_MS = 10 * 60_000
const LIBRARY_QUIET_MS = 3000
const LIGHT_MS = 400
const MIN_PUSH_MS = 500

export class WhereHost {
  private counts?: WhereCounts
  private coversKey = ''
  private dirty = true
  private computing?: Promise<WhereCounts>
  private timer?: NodeJS.Timeout
  private watchUntil = 0
  private lastPushed = ''
  private lastPushAt = 0
  private closed = false

  constructor(private readonly deps: WhereHostDeps) {}

  private now() {
    return this.deps.now ? this.deps.now() : Date.now()
  }

  /** The library changed (a segment was written or taken in): the counts are stale. */
  noteLibraryChanged(): void {
    this.dirty = true
    this.schedule(LIBRARY_QUIET_MS)
  }

  /** A copy or a device moved on: the answer is rebuilt from the counts already held. */
  noteOthersChanged(): void {
    this.schedule(LIGHT_MS)
  }

  close(): void {
    this.closed = true
    if (this.timer) clearTimeout(this.timer)
  }

  private schedule(ms: number): void {
    if (this.closed || this.now() >= this.watchUntil) return
    // a later, longer wait wins over an earlier short one only when the counts are stale: never push counts that are known to be old
    if (this.timer) clearTimeout(this.timer)
    this.timer = setTimeout(() => {
      this.timer = undefined
      void this.refreshAndPush().catch(() => undefined)
    }, this.dirty ? Math.max(ms, LIBRARY_QUIET_MS) : ms)
    this.timer.unref?.()
  }

  private async refreshAndPush(): Promise<void> {
    if (this.closed || this.now() >= this.watchUntil) return
    const state = await this.build()
    const text = JSON.stringify(state)
    const wait = this.lastPushAt + MIN_PUSH_MS - this.now()
    if (text === this.lastPushed) return
    if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait))
    if (this.closed) return
    this.lastPushed = text
    this.lastPushAt = this.now()
    this.deps.push({type: 'where', state})
  }

  private copyInputs(): {copies: WhereCopy[]; rows: CopyRow[]} {
    const read = this.deps.copies?.()
    return {copies: read?.copies ?? [], rows: read?.rows ?? []}
  }

  /** The counts: from the cache while they are fresh and the same copies are complete; else counted again (once, however many ask). */
  private async countsFor(copies: WhereCopy[]): Promise<WhereCounts> {
    const covers = completeCovers(copies)
    const key = JSON.stringify(covers)
    if (this.counts && !this.dirty && key === this.coversKey) return this.counts
    if (!this.computing) {
      this.dirty = false
      this.coversKey = key
      this.computing = this.deps
        .store()
        .whereCounts(this.deps.mainReplica, covers)
        .then((counts) => (this.counts = counts))
        .catch((error) => {
          this.dirty = true
          throw error
        })
        .finally(() => {
          this.computing = undefined
        })
    }
    const counts = await this.computing
    // the copies changed while counting: the answer is for the earlier ones, so ask again
    if (this.coversKey !== key) return this.countsFor(copies)
    return counts
  }

  /** The state of the screens now. Asking marks the screen as watching, so changes are pushed for the next ten minutes. */
  async state(): Promise<WhereState> {
    this.watchUntil = this.now() + WATCH_MS
    return this.build()
  }

  private async build(): Promise<WhereState> {
    const {copies, rows} = this.copyInputs()
    const counts = await this.countsFor(copies)
    const net = this.deps.devices?.()
    const membership = await this.membership()
    return buildWhere({thisName: net?.thisDevice?.name ?? this.deps.thisName(), mainReplica: this.deps.mainReplica, counts, copies, rows, paired: net?.paired ?? [], membership})
  }

  private async membership(): Promise<SyncMembership | null> {
    try {
      return await this.deps.store().membership()
    } catch {
      return null
    }
  }

  /** The places one file is on. */
  async item(itemId: string): Promise<WhereItemView> {
    if (typeof itemId !== 'string' || !itemId || itemId.length > 200) throw new Error('Invalid request.')
    const store = this.deps.store()
    const [facts, membership] = await Promise.all([store.whereItem(itemId), this.membership()])
    const net = this.deps.devices?.()
    return itemPlaces({mainReplica: this.deps.mainReplica, thisName: net?.thisDevice?.name ?? this.deps.thisName(), paired: net?.paired ?? [], membership, copies: this.copyInputs().copies, facts})
  }
}

export const whereUnavailable: WhereState = emptyWhere
