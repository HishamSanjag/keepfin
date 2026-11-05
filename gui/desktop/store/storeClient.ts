// The main-thread side of the store worker (worker.ts): the same operations as KeepfinStore, as promises. Nothing here
// calls the core. A crashed worker is started again and replays the outbox; requests in flight fail and the owner is told
// (`restarted`), so it can load the library again from the store.
import {existsSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {Worker} from 'node:worker_threads'
import type {SyncMembership, SyncObserved, SyncObservedQuery, SyncPartialViews, SyncSong, SyncTouched, SyncViews} from '../../shared/core/sync.js'
import type {Collection, Space} from '../../shared/library.js'
import type {LibraryEntry} from '../library/index.js'
import type {OpDraft, Routes} from './mapping.js'
import type {MergeMeta, ScanRow} from './mergeScan.js'
import type {OpenStats} from './store.js'
import type {ProjectArgs, WorkerStoreOptions} from './worker.js'
import type {CopyCover, WhereCounts, WhereItemFacts} from './where.js'
import type {SyncJson, SyncSpace} from '../../shared/core/sync.js'

export type {WorkerStoreOptions, ProjectArgs}

export class WorkerCrashed extends Error {
  constructor() {
    super('The library store stopped unexpectedly and was restarted.')
    this.name = 'WorkerCrashed'
  }
}

export type StoreEvent =
  | {name: 'change'; data: {last: [string, number][]}}
  | {name: 'progress'; data: {phase: 'replaying'; done: number; total: number}}
  | {name: 'checkpoint'; data: {parts: number; buildMs: number; bytes: number}}
  | {name: 'rotated'; data: {oldActor: string; newActor: string}}
  | {name: 'restarted'; data: Record<string, never>}

export type ProjectedAll = {
  items: LibraryEntry[]
  collections: Collection[]
  spaces: Space[]
  routes: Routes
  spaceViews: SyncSpace[]
  profile: Record<string, SyncJson>
  own: Record<string, number>
}

/** The worker's file next to this module: `worker.js` in the built app, `worker.ts` under tsx. */
function workerUrl(): URL {
  const built = new URL('./worker.js', import.meta.url)
  return existsSync(fileURLToPath(built)) ? built : new URL('./worker.ts', import.meta.url)
}

type Pending = {resolve(value: any): void; reject(error: Error): void; onBatch?: (kind: string, data: unknown[]) => void}

export class StoreClient {
  private worker!: Worker
  private nextId = 1
  private readonly pending = new Map<number, Pending>()
  private readonly listeners = new Set<(event: StoreEvent) => void>()
  private closing = false
  /** Set while a crashed worker is being started again: requests wait for it. */
  private restart?: Promise<void>
  private _last = new Map<string, number>()
  private _pending = 0
  actorId: string
  stats!: OpenStats

  private constructor(private options: WorkerStoreOptions) {
    this.actorId = options.actor
  }

  /** The device key every store opened from here on signs with (set once at boot; a test can pass its own in the options). */
  static defaultSigner: WorkerStoreOptions['signer']

  static async open(options: WorkerStoreOptions, listener?: (event: StoreEvent) => void): Promise<StoreClient> {
    const client = new StoreClient(options.signer || !StoreClient.defaultSigner ? options : {...options, signer: StoreClient.defaultSigner})
    if (listener) client.listeners.add(listener)
    client.spawn()
    await client.start()
    return client
  }

  private spawn() {
    // The merge state lives in SQLite, not in this thread's heap: what the thread holds is batches, one shard of a part and
    // the projection's id sets (tens of MB at 200,000 items). The limit is a cap, there to catch a leak early.
    this.worker = new Worker(workerUrl(), {resourceLimits: {maxOldGenerationSizeMb: 1536}})
    this.worker.on('message', (message) => this.receive(message))
    this.worker.on('error', (error) => console.error('The library store worker failed:', error))
    this.worker.on('exit', () => this.exited())
  }

  private async start() {
    const opened = await this.request<{actorId: string; stats: OpenStats; last: [string, number][]}>('open', this.options, undefined, true)
    this.actorId = opened.actorId
    this.stats = opened.stats
    this._last = new Map(opened.last)
  }

  private receive(message: {type: string; id?: number; ok?: boolean; value?: any; error?: string; kind?: string; data?: any; name?: string}) {
    if (message.type === 'res') {
      const entry = this.pending.get(message.id as number)
      if (!entry) return
      this.pending.delete(message.id as number)
      if (message.ok) entry.resolve(message.value)
      else entry.reject(new Error(message.error))
    } else if (message.type === 'batch') {
      this.pending.get(message.id as number)?.onBatch?.(message.kind as string, message.data)
    } else if (message.type === 'event') {
      const event = {name: message.name, data: message.data} as StoreEvent
      if (event.name === 'change') this._last = new Map(event.data.last)
      this.emit(event)
    }
  }

  private emit(event: StoreEvent) {
    for (const listener of [...this.listeners]) {
      try {
        listener(event)
      } catch (error) {
        console.error('Store observer failed:', error)
      }
    }
  }

  private exited() {
    if (this.closing) return
    // A crash: what was in flight fails, the worker is started again and replays the outbox.
    for (const entry of this.pending.values()) entry.reject(new WorkerCrashed())
    this.pending.clear()
    this.options = {...this.options, actor: this.actorId}
    this.spawn()
    this.restart = this.start().then(
      () => {
        this.restart = undefined
        this.emit({name: 'restarted', data: {}})
      },
      (error) => {
        this.restart = undefined
        console.error('The library store could not be restarted:', error)
      },
    )
  }

  private async request<T>(op: string, args?: unknown, onBatch?: Pending['onBatch'], starting = false): Promise<T> {
    if (!starting && this.restart) await this.restart
    return new Promise<T>((resolve, reject) => {
      const id = this.nextId++
      this.pending.set(id, {resolve, reject, onBatch})
      this.worker.postMessage({id, op, args})
    })
  }

  private noteState(state: {last: [string, number][]; pending?: number}) {
    this._last = new Map(state.last)
    if (state.pending !== undefined) this._pending = state.pending
  }

  /** The highest seq per actor the store holds, as of its last answer or event. */
  get last(): ReadonlyMap<string, number> {
    return this._last
  }

  onEvent(listener: (event: StoreEvent) => void): () => void {
    this.listeners.add(listener)
    return () => void this.listeners.delete(listener)
  }

  /** A change made outside the library index (a copy run): the index takes it in through its remote path. */
  applyExternal(drafts: readonly OpDraft[]): Promise<void> {
    return this.apply(drafts, {external: true})
  }

  /** [external]: see KeepfinStore.apply — a change the library index does not have yet (a copy run). */
  async apply(drafts: readonly OpDraft[], options: {external?: boolean} = {}): Promise<void> {
    if (!drafts.length) return
    this.noteState(await this.request('apply', {drafts, external: options.external === true}))
  }

  async flush(): Promise<void> {
    this.noteState(await this.request('flush'))
  }

  async rescan(options: {force?: boolean} = {}): Promise<void> {
    this.noteState(await this.request('rescan', options))
  }

  async status(): Promise<{last: [string, number][]; pending: number; findings: string[]; actorId: string; revision: number}> {
    const state = await this.request<{last: [string, number][]; pending: number; findings: string[]; actorId: string; revision: number}>('status')
    this.noteState(state)
    this.actorId = state.actorId
    return state
  }

  /** Who may write, as the admitted member ops define it (network spec 9.1). */
  membership(): Promise<SyncMembership> {
    return this.request('membership')
  }

  async enableMembership(grant?: {actor: string; spkiBase64: string; perms: string[]}): Promise<void> {
    this.noteState(await this.request('enableMembership', grant))
  }

  async grantMember(actor: string, spkiBase64: string, perms: string[]): Promise<void> {
    this.noteState(await this.request('grantMember', {actor, spki: spkiBase64, perms}))
  }

  async revokeMember(actor: string, cutoff: number): Promise<void> {
    this.noteState(await this.request('revokeMember', {actor, cutoff}))
  }

  contentItems(contentId: string): Promise<{id: string; placements: Record<string, string | null>}[]> {
    return this.request('contentItems', {contentId})
  }

  /** Where the library's files are, counted in the database (see where.ts). */
  whereCounts(mainReplica: string, copies: readonly CopyCover[] = []): Promise<WhereCounts> {
    return this.request('whereCounts', {mainReplica, copies})
  }

  musicVideoBytes(): Promise<number> {
    return this.request('musicVideoBytes', {})
  }

  whereItem(itemId: string): Promise<WhereItemFacts> {
    return this.request('whereItem', {itemId})
  }

  async pendingOps(): Promise<number> {
    return (await this.status()).pending
  }

  async findings(): Promise<string[]> {
    return (await this.status()).findings
  }

  observed(query: SyncObservedQuery): Promise<SyncObserved> {
    return this.request('observed', {query})
  }

  songs(playlists: string[]): Promise<Record<string, SyncSong[]>> {
    return this.request('songs', {playlists})
  }

  viewsFor(touched: SyncTouched): Promise<SyncPartialViews> {
    return this.request('viewsFor', {touched})
  }

  async takeRemoteTouched(): Promise<SyncTouched> {
    const out = await this.request<{touched: SyncTouched; last: [string, number][]}>('takeRemoteTouched')
    this._last = new Map(out.last)
    return out.touched
  }

  /** Every view (small libraries and tests: the payload is the whole state). */
  views(): Promise<SyncViews> {
    return this.request('views')
  }

  /** The library as the app model, delivered in bounded batches. Device-only data is not applied. */
  async projectAll(args: ProjectArgs, onBatch?: (kind: 'items' | 'collections', count: number) => void): Promise<ProjectedAll> {
    const items: LibraryEntry[] = []
    const collections: Collection[] = []
    const done = await this.request<{spaces: Space[]; routes: Routes; spaceViews: SyncSpace[]; profile: Record<string, SyncJson>; own: Record<string, number>; last: [string, number][]}>('projectAll', args, (kind, data) => {
      // Appending a batch is cheap; the structured-clone read of the batch already happened when the message arrived.
      if (kind === 'items') for (const entry of data as LibraryEntry[]) items.push(entry)
      else for (const collection of data as Collection[]) collections.push(collection)
      onBatch?.(kind as 'items' | 'collections', data.length)
    })
    this._last = new Map(done.last)
    return {items, collections, spaces: done.spaces, routes: done.routes, spaceViews: done.spaceViews, profile: done.profile, own: done.own}
  }

  /** The library merge's scan: every present item as a row, delivered in batches (the outbox is written out first). */
  async mergeScan(onRows: (rows: ScanRow[]) => void | Promise<void>, batch = 1000): Promise<void> {
    let chain: Promise<void> = Promise.resolve()
    const done = await this.request<{last: [string, number][]}>('mergeScan', {batch}, (_kind, data) => {
      chain = chain.then(() => onRows(data as ScanRow[]))
    })
    this._last = new Map(done.last)
    await chain
  }

  mergeMeta(): Promise<MergeMeta> {
    return this.request('mergeMeta')
  }

  memory(): Promise<{heapUsed: number; external: number; rss: number}> {
    return this.request('memory')
  }

  /** Tests: the worker dies without a word. */
  crashForTest(): Promise<unknown> {
    return this.request('crash').catch(() => undefined)
  }

  /** Tests: stop writing, as a crash would, without restarting. */
  async abandon(): Promise<void> {
    this.closing = true
    await this.request('abandon').catch(() => undefined)
    await this.worker.terminate()
  }

  /** Flushes the outbox, writes a due checkpoint and stops the worker. */
  async close(): Promise<void> {
    if (this.closing) return
    this.closing = true
    try {
      await this.request('close')
    } finally {
      await this.worker.terminate()
      this.listeners.clear()
    }
  }
}
