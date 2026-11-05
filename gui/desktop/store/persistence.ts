// The library on top of the Keepfin store: LibraryPersistence (what LibraryIndex saves through) and LibraryPort
// (what the storage service creates, opens and asks for the overview).
//
// save() is a diff writer: it compares the model it is given with the last one it saved and applies only the changed
// operations, so every existing LibraryIndex mutation persists without edits. Remote changes come back through
// onRemoteChange(); the index saves its pending edits first and then calls reload().
import {createHash} from 'node:crypto'
import {createReadStream} from 'node:fs'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {StorageOverview} from '../../shared/storage.js'
import {defaultLibrarySettings, type Collection, type LibrarySettings, type Space} from '../../shared/library.js'
import type {SyncItem, SyncJson, SyncPartialViews, SyncPlaylist, SyncSpace, SyncTouched} from '../../shared/core/sync.js'
import {companionField, companionsOf, companionSuffix} from '../../shared/core/copies.js'
import {sanitizeCollection, type LibraryEntry, type LibraryPersistence, type PersistedDelta, type PersistedLibrary} from '../library/index.js'
import {CACHE_FORMAT, collectionLine, entryLine, ProjectionCache, removedCollectionLine, removedItemLine, type CacheMeta, type CacheRead} from './cache.js'
import {CollectionTracker, ItemTracker} from './dirty.js'
import {StringPool} from './intern.js'
import {Slicer} from './slices.js'
import {isUuid} from './mapping.js'
import {DEVICE_ITEM_KEYS, DeviceFiles, placementOf, resolvePlacement, unplacedPath, type DeviceCollection, type DeviceData, type DeviceItem, type SpaceRoot} from './device.js'
import {recordsDir, type KeepfinFolder} from './keepfinFolder.js'
import {
  answerObserved,
  collectionSnap,
  deviceCollectionOf,
  deviceItemOf,
  diffSnaps,
  emptySnapshot,
  itemSnap,
  type CollectionSnap,
  type ItemSnap,
  projectCollection,
  snapshotFrozen,
  type ItemChange,
  projectEnv,
  projectItem,
  RecordingObserved,
  snapshot,
  spaceRels,
  spaceRoots,
  type DiffContext,
  type Model,
  type ModelSnap,
  type ObservedLookup,
  type OpDraft,
  type ProjectEnv,
  type Routes,
} from './mapping.js'
import type {LibraryPort} from './service.js'
import {keyString, listingLast, type StoreOptions} from './store.js'
import {StoreClient, type ProjectedAll, type StoreEvent, type WorkerStoreOptions} from './storeClient.js'
import type {Plan} from './migration/types.js'

export type StoreLibraryDeps = {
  /** %APPDATA%\Keepfin */
  deviceDir: string
  /**
   * The profile's device folder (`profiles\<id>`): the outbox, placement-state.json and linked.json live there. Without
   * it (the folder is being created, the profile does not exist yet) `<deviceDir>\actors\<actorId>` is used.
   */
  profileDir?: string
  newId(): string
  now(): number
  appVersion?: string
  /** Called when a restored or cloned install made the actor retire; by default the profile in profiles.json is updated. */
  onRotate?(oldActor: string, newActor: string): Promise<void> | void
  /** Store options (tests: idle flush time, no watcher, checkpoint interval, a hook for each checkpoint written). */
  store?: Partial<StoreOptions>
  /** How long after opening the items without a recorded content are looked at (default 10 s; tests: shorter). */
  contentFillDelayMs?: number
  /** Show the library from the projection cache and open the store in the background (see SessionOptions). */
  deferStore?: boolean
  onProgress?: SessionOptions['onProgress']
}

type PartialJob = {
  /** Items saved before that changed: their new snapshot and what changed (from the tracker's hashes). */
  changed: Map<string, {snap: ItemSnap; change: ItemChange}>
  /** Items new to the store. */
  items: Map<string, ItemSnap>
  removedItems: string[]
  collections: Map<string, CollectionSnap>
  removedCollections: string[]
  deviceItems: Map<string, DeviceItem | null>
  deviceCollections: Map<string, DeviceCollection | null>
  /** Projection-cache journal lines for the synced changes (made at save time, while the entries are in this state). */
  journal: string[]
}

export type SessionOptions = {
  /**
   * Show the library from the projection cache when it is current and open the store in the background: the first
   * snapshot does not wait for the store. `session.ready` resolves when the store is open; saves wait for it.
   */
  deferStore?: boolean
  onProgress?(progress: {phase: 'cache' | 'replaying' | 'projecting'; done: number; total: number}): void
}

/** What fetchRemote() brings from the store thread: a delta of partial views, or a whole projection. */
export type RemoteFetch = {kind: 'all'; data: ProjectedAll} | {kind: 'delta'; part: SyncPartialViews; more: SyncPartialViews | undefined; touched: SyncTouched}

const yieldNow = () => new Promise<void>((resolve) => setImmediate(resolve))
const TUNING_KEYS = ['flushIdleMs', 'flushOps', 'checkpointEvery', 'segmentMaxOps', 'watch', 'watchDebounceMs', 'retryMs', 'checkpointDelayMs'] as const

/** A library opened on one Keepfin folder. The store itself runs in a worker thread (storeClient.ts). */
export class LibrarySession implements LibraryPersistence {
  private _store?: StoreClient
  /** How long the steps of the last open took (ms), for the performance report. */
  readonly timings: Record<string, number> = {}
  private lap(name: string, since: number) {
    this.timings[name] = Math.round((performance.now() - since) * 10) / 10
  }
  /** Resolves when the store is open (at once when the session was opened without deferring it). */
  ready: Promise<void> = Promise.resolve()
  private baseline: ModelSnap = emptySnapshot()
  private baselineReady: Promise<void> = Promise.resolve()
  private readonly itemTracker = new ItemTracker()
  private readonly collectionTracker = new CollectionTracker()
  private roots: SpaceRoot[] = []
  private env?: ProjectEnv
  private spaceViews: SyncSpace[] = []
  private profileView: Record<string, SyncJson> = {}
  /** The operations the in-memory model reflects (what the projection cache says it reflects). */
  private modelLast = new Map<string, number>()
  /** The items that are favourites, kept so that a remote change to the set is applied by its difference. */
  private favourites = new Set<string>()
  /** The cache was current when opened but the store disagrees, or the store restarted: the next remote fetch is a full one. */
  private forceAll = false
  private firstModel?: PersistedLibrary
  private spacesKey = ''
  /** A save failed after its changes were noticed: the next save compares everything again. */
  private needsFull = false
  /** This actor's own play counters, seeded once and kept here (never read back on a save). */
  private readonly ownPlays = new Map<string, number>()
  private chain: Promise<unknown> = Promise.resolve()
  private lastSpaces: Space[] = []
  private lastRoutes: Routes = {music: 'music', other: 'downloads'}
  private readonly listeners = new Set<() => void>()
  private unsubscribe?: () => void
  private checking = false
  private closed = false

  private constructor(
    readonly device: DeviceFiles,
    readonly folder: KeepfinFolder,
    private readonly deps: StoreLibraryDeps,
    private art: Set<string>,
    private readonly cache: ProjectionCache,
    private readonly options: SessionOptions,
  ) {}

  get store(): StoreClient {
    if (!this._store) throw new Error('The library store is still opening.')
    return this._store
  }

  static async open(folder: KeepfinFolder, actorId: string, deps: StoreLibraryDeps, options: SessionOptions = {}): Promise<LibrarySession> {
    const stateDir = deps.profileDir ?? path.join(deps.deviceDir, 'actors', actorId)
    await fs.mkdir(stateDir, {recursive: true})
    const outboxDir = path.join(stateDir, 'outbox')
    const device = new DeviceFiles(stateDir)
    // The device data and the cache are read at the same time (their file reads overlap).
    const t0 = performance.now()
    const deviceLoad = Promise.all([readArt(folder), device.load()]).then(([art]) => {
      session.art = art
      session.timings.deviceAndArt = Math.round((performance.now() - t0) * 10) / 10
    })
    const cache = new ProjectionCache(path.join(stateDir, 'cache', 'library-cache.v1'))
    const session = new LibrarySession(device, folder, deps, new Set(), cache, options)
    const tuning: WorkerStoreOptions['tuning'] = {}
    for (const key of TUNING_KEYS) if (deps.store?.[key] !== undefined) (tuning as Record<string, unknown>)[key] = deps.store[key]
    const openStore = () =>
      StoreClient.open(
        {root: folder.root, records: folder.records, libraryId: folder.libraryId, actor: actorId, deviceDir: deps.deviceDir, outboxDir, appVersion: deps.appVersion, deviceName: deps.store?.deviceName, tuning, customRotate: !!deps.onRotate},
        (event) => session.onStoreEvent(event),
      )
    if (options.deferStore) {
      const key = keyString(await listingLast(folder.root, outboxDir, folder.records))
      const tCache = performance.now()
      const cached = await cache.read({key, libraryId: folder.libraryId, replicaId: folder.replicaId, root: folder.root}, {onProgress: (done, total) => options.onProgress?.({phase: 'cache', done, total})})
      session.lap('cacheRead', tCache)
      await deviceLoad
      if (cached) {
        const tInit = performance.now()
        await session.initFromCache(cached)
        session.lap('cacheInit', tInit)
        // The store opens on its own thread while the cache is already on screen.
        session.ready = openStore().then((store) => session.attach(store, key))
        session.ready.catch(() => undefined)
        return session
      }
    }
    await deviceLoad
    const tStore = performance.now()
    session.attach(await openStore())
    session.lap('storeOpen', tStore)
    await session.reloadInChunks()
    return session
  }

  /** Events from the store thread. */
  private onStoreEvent(event: StoreEvent) {
    if (event.name === 'change') for (const listener of [...this.listeners]) listener()
    else if (event.name === 'progress') this.options.onProgress?.(event.data)
    else if (event.name === 'checkpoint') this.deps.store?.onCheckpoint?.(event.data)
    else if (event.name === 'rotated') void this.deps.onRotate?.(event.data.oldActor, event.data.newActor)
    else if (event.name === 'restarted') {
      // The worker restarted and replayed the outbox: what this session has may be behind the store, so load it again.
      this.forceAll = true
      this.needsFull = true
      for (const listener of [...this.listeners]) listener()
    }
  }

  /** The store is open: it reports other devices' changes from now on. */
  private attach(store: StoreClient, expectedKey?: string) {
    this._store = store
    if (expectedKey !== undefined && keyString(store.last) !== expectedKey) {
      // Segments changed between the listing and the open: the cache is behind. A full reload brings it up to date.
      this.forceAll = true
      queueMicrotask(() => {
        for (const listener of [...this.listeners]) listener()
      })
    }
  }

  // ------------------------------------------------------------------ reading

  get newId() {
    return this.deps.newId
  }

  private deviceData(): DeviceData {
    return {
      replicaId: this.folder.replicaId,
      root: this.folder.root,
      linked: this.device.linked,
      items: this.device.items,
      collections: this.device.collections,
      art: this.art,
      check: this.device.check,
    }
  }

  private projectArgs() {
    return {replicaId: this.folder.replicaId, root: this.folder.root, linked: this.device.linked}
  }

  /** Lays this device's own data over a projected entry (the store thread knows nothing of it). */
  /** A string pool for one load, seeded with the ids of this device's records so that the entries share their copies. */
  private newPool(): StringPool {
    const pool = new StringPool()
    for (const id of this.device.items.keys()) pool.add(id)
    return pool
  }

  private overlay(entry: LibraryEntry, pool?: StringPool) {
    pool?.entry(entry)
    const local = this.device.items.get(entry.id)
    if (local) for (const key of DEVICE_ITEM_KEYS) if (local[key] !== undefined) (entry as Record<string, unknown>)[key] = local[key]
    entry.hasArt = this.art.has(entry.id)
  }

  private overlayCollection(collection: Collection) {
    const local = this.device.collections[collection.id]
    if (local && collection.follow) {
      if (local.lastCheckedAt !== undefined) collection.follow.lastCheckedAt = local.lastCheckedAt
      if (local.lastError !== undefined) collection.follow.lastError = local.lastError
    }
  }

  /** The whole library from the store thread, in batches, with this device's data laid over it in slices. */
  private async fetchAll(): Promise<ProjectedAll> {
    const tProject = performance.now()
    const projected = await this.store.projectAll(this.projectArgs(), (kind, count) => this.options.onProgress?.({phase: 'projecting', done: count, total: count}))
    this.lap('project', tProject)
    const pool = this.newPool()
    const slicer = new Slicer()
    for (let i = 0; i < projected.items.length; i++) {
      this.overlay(projected.items[i], pool)
      if ((i & 63) === 63) await slicer.tick()
    }
    for (const collection of projected.collections) this.overlayCollection(collection)
    return projected
  }

  /** The model the store describes right now, without touching the baseline. */
  async model(): Promise<Model> {
    const projected = await this.fetchAll()
    return {items: projected.items, collections: projected.collections, spaces: projected.spaces, routes: projected.routes}
  }

  private cacheMeta(): CacheMeta {
    return {
      format: CACHE_FORMAT,
      libraryId: this.folder.libraryId,
      replicaId: this.folder.replicaId,
      root: this.folder.root,
      key: keyString(this.modelLast),
      gen: 0,
      spaces: this.lastSpaces,
      routes: this.lastRoutes,
      spaceViews: this.spaceViews,
      profile: this.profileView,
      own: Object.fromEntries(this.ownPlays),
      baseItems: 0,
      baseCollections: 0,
      journalBytes: 0,
      journalLines: 0,
    }
  }

  private noteProjected(projected: ProjectedAll) {
    this.spaceViews = projected.spaceViews
    this.profileView = projected.profile
    this.env = projectEnv({spaces: projected.spaceViews, profile: projected.profile}, this.deviceData())
    this.favourites = new Set(projected.items.filter((entry) => entry.favorite).map((entry) => entry.id))
    this.forceAll = false
    this.modelLast = new Map(this.store.last)
    this.ownPlays.clear()
    for (const [id, count] of Object.entries(projected.own)) this.ownPlays.set(id, count)
    this.lastSpaces = projected.spaces
    this.lastRoutes = projected.routes
  }

  /** Takes a whole projection as the model (and the baseline for the next diff): trackers, space roots, baseline. */
  private adoptAll(projected: ProjectedAll): PersistedLibrary {
    const model: Model = {items: projected.items, collections: projected.collections, spaces: projected.spaces, routes: projected.routes}
    this.noteProjected(projected)
    this.baseline = emptySnapshot()
    this.baseline.rels = spaceRels({spaces: projected.spaceViews})
    this.afterPrime(model)
    this.itemTracker.rebuild(model.items)
    this.collectionTracker.rebuild(model.collections)
    this.startBaseline(model)
    this.firstModel = undefined
    return {items: model.items, collections: model.collections, spaces: model.spaces, routes: model.routes}
  }

  /** The current projection as the model, for callers that are not the index (tests, the migration). */
  async reload(): Promise<PersistedLibrary> {
    await this.ready
    await this.ensureTrackers()
    return this.adoptAll(await this.fetchAll())
  }

  /** The first projection of a big library: no pending edits exist yet, so the work is spread over slices. */
  private async reloadInChunks() {
    const projected = await this.fetchAll()
    const model: Model = {items: projected.items, collections: projected.collections, spaces: projected.spaces, routes: projected.routes}
    this.noteProjected(projected)
    this.baseline = emptySnapshot()
    this.baseline.rels = spaceRels({spaces: projected.spaceViews})
    this.afterPrime(model)
    const tTrack = performance.now()
    await this.itemTracker.rebuildInChunks(model.items)
    this.lap('trackers', tTrack)
    this.collectionTracker.rebuild(model.collections)
    this.startBaseline(model)
    this.firstModel = {items: model.items, collections: model.collections, spaces: model.spaces, routes: model.routes}
    // The cache is written in the background; the next open can use it.
    void this.cache.writeBase(model.items, model.collections, this.cacheMeta()).catch((error) => console.error('Could not write the library cache:', error))
  }

  /** Starts from the projection cache: entries are laid over with this device's data; the store opens on its own thread. */
  private async initFromCache(cached: CacheRead) {
    const {meta} = cached
    let t = performance.now()
    const pool = this.newPool()
    const slicer = new Slicer()
    for (let i = 0; i < cached.items.length; i++) {
      this.overlay(cached.items[i], pool)
      cached.items[i].managed = true
      if ((i & 63) === 63) await slicer.tick()
    }
    for (const collection of cached.collections) this.overlayCollection(collection)
    this.lap('init.overlay', t)
    t = performance.now()
    const model: Model = {items: cached.items, collections: cached.collections, spaces: meta.spaces, routes: meta.routes}
    this.spaceViews = meta.spaceViews
    this.profileView = meta.profile
    this.env = projectEnv({spaces: meta.spaceViews, profile: meta.profile}, this.deviceData())
    this.favourites = new Set(cached.items.filter((entry) => entry.favorite).map((entry) => entry.id))
    this.modelLast = new Map(meta.key ? meta.key.split(',').map((pair) => [pair.slice(0, pair.lastIndexOf(':')), Number(pair.slice(pair.lastIndexOf(':') + 1))] as const) : [])
    for (const [id, count] of Object.entries(meta.own)) this.ownPlays.set(id, count)
    this.lastSpaces = meta.spaces
    this.lastRoutes = meta.routes
    this.baseline = emptySnapshot()
    this.baseline.rels = spaceRels({spaces: meta.spaceViews})
    this.lap('init.setup', t)
    t = performance.now()
    // The saved values of the entries are taken in the background, once the first snapshot is out: nothing needs them until
    // a save or a remote change (both wait for them), and they are most of what the open would otherwise do after reading.
    this.collectionTracker.rebuild(cached.collections)
    const items = cached.items
    this.trackersBuilt = false
    this.trackersReady = new Promise<void>((resolve, reject) => {
      this.trackersStart = () => {
        this.trackersStart = undefined
        const begun = performance.now()
        this.itemTracker.rebuildInChunks(items).then(() => {
          this.trackersBuilt = true
          this.lap('init.trackers', begun)
          resolve()
        }, reject)
      }
    })
    this.trackersReady.catch((error) => console.error('Could not read the saved values of the library:', error))
    this.afterPrime(model)
    this.firstModel = {items: cached.items, collections: cached.collections, spaces: meta.spaces, routes: meta.routes}
    this.startBaseline(model)
    // A long journal is folded into a new base once the window is up.
    if (meta.journalLines > 20_000) setTimeout(() => void this.compactCache(model), 2000).unref?.()
  }

  /**
   * The baseline (what the next diff compares with) is built in the background from the trackers' saved values, so
   * edits made meanwhile still count as changes. A save waits for it.
   */
  private startBaseline(model: Model) {
    const rels = this.baseline.rels
    // Only the collections, spaces and routes are kept as a baseline. The items are not: what an item looked like when it was
    // last saved is in the tracker, and the tracker hands the old values to the save that changes them (a few entries).
    this.baselineReady = this.trackersReady.then(() => snapshotFrozen(model, {root: this.folder.root, rels}, this.itemTracker)).then((baseline) => {
      this.baseline = baseline
    })
    this.baselineReady.catch(() => undefined)
  }

  // The item tracker is filled from the cache in the background (see initFromCache), starting when the index has taken the
  // first snapshot (it then subscribes to remote changes) or when anything needs it, whichever is first.
  private trackersReady: Promise<void> = Promise.resolve()
  private trackersBuilt = true
  private trackersStart?: () => void

  private ensureTrackers(): Promise<void> {
    this.trackersStart?.()
    return this.trackersReady
  }

  private async compactCache(model: Model) {
    try {
      await this.ready
      await this.baselineReady
      if (!this.closed) await this.cache.writeBase(model.items, model.collections, this.cacheMeta())
    } catch {
      // the journal stays valid
    }
  }

  private afterPrime(model: Model) {
    this.roots = spaceRoots(model.spaces, this.folder.root, this.baseline.rels)
    this.spacesKey = JSON.stringify([model.spaces, model.routes])
    this.lastSpaces = model.spaces
    this.lastRoutes = model.routes
    this.needsFull = false
    // items opened without a recorded content get it once the library has settled
    this.scheduleFill(this.deps.contentFillDelayMs ?? 10_000)
  }

  /**
   * What other devices changed, fetched from the store thread (the core reports the keys touched and gives partial
   * views for them). A whole projection when the change is wide: spaces, places, the profile, a rebuild or a restart.
   */
  /**
   * [previous] is a fetch that was not applied (a local edit arrived while it was fetched): its keys are fetched again
   * with any new ones, since the store reports each remote change once.
   */
  async fetchRemote(previous?: unknown): Promise<RemoteFetch> {
    await this.ready
    await this.ensureTrackers()
    const prev = previous as RemoteFetch | undefined
    if (this.forceAll || !this.env || prev?.kind === 'all') return {kind: 'all', data: await this.fetchAll()}
    const touched = await this.store.takeRemoteTouched()
    if (prev) {
      touched.all ||= prev.touched.all
      touched.profile ||= prev.touched.profile
      touched.findings ||= prev.touched.findings
      for (const key of ['items', 'playlists', 'spaces', 'sets', 'places'] as const) touched[key] = [...new Set([...prev.touched[key], ...touched[key]])]
    }
    if (touched.all || touched.spaces.length || touched.places.length || touched.profile) return {kind: 'all', data: await this.fetchAll()}
    const part = await this.store.viewsFor(touched)
    // Items and playlists whose set-derived fields changed but which the core did not list themselves.
    const listed = new Set(part.items.map((view) => view.id))
    const listedLists = new Set(part.playlists.map((playlist) => playlist.id))
    const moreItems = new Set<string>()
    const moreLists = new Set<string>()
    for (const name of Object.keys(part.sets)) {
      if (name.startsWith('tags:')) {
        const id = name.slice(5)
        if (!listed.has(id) && this.itemTracker.has(id)) moreItems.add(id)
        continue
      }
      const m = /^(?:seen|new|removed):(.+)$/.exec(name)
      if (m && !listedLists.has(m[1]) && this.collectionTracker.has(m[1])) moreLists.add(m[1])
    }
    if (part.sets.favourites) {
      const now = new Set(part.sets.favourites)
      for (const id of now) if (!this.favourites.has(id) && !listed.has(id) && this.itemTracker.has(id)) moreItems.add(id)
      for (const id of this.favourites) if (!now.has(id) && !listed.has(id) && this.itemTracker.has(id)) moreItems.add(id)
    }
    const more = moreItems.size || moreLists.size ? await this.store.viewsFor({all: false, items: [...moreItems], playlists: [...moreLists], spaces: [], sets: [], places: [], profile: false, findings: false}) : undefined
    return {kind: 'delta', part, more, touched}
  }

  /** Projects what fetchRemote() brought, for those keys only, and makes the saved copies follow. Cheap and synchronous. */
  applyRemote(fetched: RemoteFetch): PersistedDelta | PersistedLibrary {
    if (fetched.kind === 'all') return this.adoptAll(fetched.data)
    const env = this.env as ProjectEnv
    const {part, more} = fetched
    const views = new Map<string, SyncItem>(part.items.map((view) => [view.id, view]))
    const placements: SyncPartialViews['placements'] = {...part.placements}
    const lists = new Map<string, SyncPlaylist>(part.playlists.map((playlist) => [playlist.id, playlist]))
    const sets: Record<string, string[]> = {...part.sets}
    if (more) {
      for (const view of more.items) views.set(view.id, view)
      Object.assign(placements, more.placements)
      for (const playlist of more.playlists) lists.set(playlist.id, playlist)
    }
    const favourites = part.sets.favourites ? new Set(part.sets.favourites) : undefined
    const removedItems = new Set<string>(part.goneItems.filter((id) => this.itemTracker.has(id)))
    const items: LibraryEntry[] = []
    const field = `plays@${this.store.actorId}`
    for (const view of views.values()) {
      if (view.status === 'deleted') {
        if (this.itemTracker.has(view.id)) removedItems.add(view.id)
        continue
      }
      const favourite = favourites ? favourites.has(view.id) : this.favourites.has(view.id)
      const tags = sets[`tags:${view.id}`] ?? (this.itemTracker.get(view.id, 'userTags') as string[] | undefined)
      const entry = projectItem(view, placements[view.id], favourite, tags, env)
      this.overlay(entry)
      items.push(entry)
      const own = view.fields[field]
      if (typeof own === 'number') this.ownPlays.set(view.id, own)
    }
    const projected = new Set(items.map((entry) => entry.id))
    const present = (id: string) => projected.has(id) || (this.itemTracker.has(id) && !removedItems.has(id))

    const collections: Collection[] = []
    const removedCollections: string[] = []
    for (const playlist of lists.values()) {
      const saved = this.collectionTracker.sets(playlist.id)
      const collection = projectCollection(
        playlist,
        {seen: sets[`seen:${playlist.id}`] ?? saved.seen, news: sets[`new:${playlist.id}`] ?? saved.news, removed: sets[`removed:${playlist.id}`] ?? saved.removed},
        present,
        env,
      )
      if (collection) collections.push(collection)
      else if (this.collectionTracker.has(playlist.id)) removedCollections.push(playlist.id)
    }

    // The saved copies follow, so the next save sees these entries as unchanged; the cache journal follows too.
    const journal: string[] = []
    for (const entry of items) {
      this.itemTracker.adopt(entry)
      if (entry.favorite) this.favourites.add(entry.id)
      else this.favourites.delete(entry.id)
      journal.push(entryLine(entry))
    }
    for (const id of removedItems) {
      this.itemTracker.remove(id)
      this.ownPlays.delete(id)
      this.favourites.delete(id)
      journal.push(removedItemLine(id))
    }
    for (const collection of collections) {
      this.collectionTracker.adopt(collection)
      this.baseline.collections.set(collection.id, collectionSnap(collection, this.itemTracker))
      journal.push(collectionLine(collection))
    }
    for (const id of removedCollections) {
      this.collectionTracker.remove(id)
      this.baseline.collections.delete(id)
      journal.push(removedCollectionLine(id))
    }
    this.modelLast = new Map(this.store.last)
    this.cache.record(journal, () => this.cacheMeta())
    return {items, removedItems: [...removedItems], collections, removedCollections}
  }

  async load(): Promise<PersistedLibrary> {
    if (this.firstModel) {
      const model = this.firstModel
      this.firstModel = undefined
      return model
    }
    await this.ready
    await this.ensureTrackers()
    const art = await readArt(this.folder)
    this.art.clear()
    for (const id of art) this.art.add(id)
    for (const id of this.deviceArt) this.art.add(id)
    return this.adoptAll(await this.fetchAll())
  }

  /** Items whose art this device saved in its own art folder (not .keepfin/art). */
  private readonly deviceArt = new Set<string>()

  noteArt(id: string, has: boolean) {
    if (has) {
      this.deviceArt.add(id)
      this.art.add(id)
    } else if (this.deviceArt.delete(id)) this.art.delete(id)
  }

  async folderArt(id: string): Promise<string | undefined> {
    if (!this.art.has(id)) return
    for (const ext of ['jpg', 'png', 'webp']) {
      const file = path.join(recordsDir(this.folder), 'art', `${id}.${ext}`)
      if ((await fs.stat(file).catch(() => undefined))?.isFile()) return file
    }
  }

  onRemoteChange(listener: () => void): () => void {
    // The index subscribes once it has the first snapshot: the saved values can be taken now.
    this.trackersStart?.()
    this.listeners.add(listener)
    return () => void this.listeners.delete(listener)
  }

  // ------------------------------------------------------------------ writing

  /**
   * The library's own saves wait until the returned function is called (a combine or a separate writes to the store meanwhile:
   * dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 4). Playback and everything read-only continue.
   */
  holdWrites(): () => void {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const previous = this.gate
    this.gate = previous ? previous.then(() => gate) : gate
    return () => {
      release()
      if (this.gate === gate) this.gate = undefined
    }
  }

  private gate?: Promise<void>

  /** The absolute path of a synced placement on this device (a linked space has its own folder), or undefined. */
  resolvePath(placement: string): string | undefined {
    return resolvePlacement(placement, this.folder.root, this.roots)
  }

  save(data: PersistedLibrary): Promise<void> {
    if (this.gate) return this.gate.then(() => this.save(data))
    // The saved values are still being read from the cache (see initFromCache): the save is taken up when they are there, and
    // compares the entries as they are then (the index passes its live entries and the ids it noted).
    if (!this.trackersBuilt) return this.ensureTrackers().then(() => this.save(data))
    // Everything that depends on the live objects is copied here, before the first await.
    const spaces = data.spaces ?? this.lastSpaces
    const routes = data.routes ?? this.lastRoutes
    const key = JSON.stringify([spaces, routes])
    if (key !== this.spacesKey || this.needsFull) return this.saveFull(data.touched ? {...data, items: data.touched.all()} : data, spaces, routes, key)
    const root = this.folder.root
    const scan = data.touched ? this.itemTracker.scanIds(data.touched.ids, data.touched.entry) : this.itemTracker.scan(data.items)
    const cscan = this.collectionTracker.scan(data.collections)
    if (!scan.added.length && !scan.synced.length && !scan.device.length && !scan.removed.length && !cscan.added.length && !cscan.changed.length && !cscan.removed.length) return this.chain.then(() => undefined)
    const job: PartialJob = {changed: new Map(), items: new Map(), removedItems: scan.removed, collections: new Map(), removedCollections: cscan.removed, deviceItems: new Map(), deviceCollections: new Map(), journal: []}
    for (const entry of scan.added) {
      job.items.set(entry.id, itemSnap(entry, this.roots, root))
      job.deviceItems.set(entry.id, deviceItemOf(entry))
      job.journal.push(entryLine(entry))
    }
    for (const entry of scan.synced) {
      const change = scan.changes.get(entry.id)
      if (change) job.changed.set(entry.id, {snap: itemSnap(entry, this.roots, root), change})
      if (change || !job.items.has(entry.id)) job.journal.push(entryLine(entry))
    }
    for (const entry of scan.device) job.deviceItems.set(entry.id, deviceItemOf(entry))
    for (const id of scan.removed) {
      job.deviceItems.set(id, null)
      job.journal.push(removedItemLine(id))
    }
    for (const collection of [...cscan.added, ...cscan.changed]) {
      job.collections.set(collection.id, collectionSnap(collection, this.itemTracker))
      job.deviceCollections.set(collection.id, deviceCollectionOf(collection) ?? null)
      job.journal.push(collectionLine(collection))
    }
    for (const id of cscan.removed) {
      job.deviceCollections.set(id, null)
      job.journal.push(removedCollectionLine(id))
    }
    return this.enqueue(() => this.commitPartial(job))
  }

  /** The spaces changed (or a save failed): everything is compared again. */
  private saveFull(data: PersistedLibrary, spaces: Space[], routes: Routes, key: string): Promise<void> {
    const model: Model = {items: data.items, collections: data.collections, spaces, routes}
    const after = snapshot(model, {root: this.folder.root, rels: this.baseline.rels})
    const items = new Map<string, DeviceItem>()
    for (const entry of data.items) if (entry.managed !== false && after.items.has(entry.id)) items.set(entry.id, deviceItemOf(entry))
    const collections: Record<string, DeviceCollection> = {}
    for (const collection of data.collections) {
      const local = deviceCollectionOf(collection)
      if (local && after.collections.has(collection.id)) collections[collection.id] = local
    }
    const linked: Record<string, string> = {}
    const roots = spaceRoots(spaces, this.folder.root, this.baseline.rels)
    for (const space of roots) if (space.linked) linked[space.id] = space.abs
    // What the items looked like when they were last saved is not kept (the tracker holds hashes). After a failed save the tracker
    // may be ahead of the store, so then the store is asked what it holds; otherwise only what changed since the last save is written.
    const failed = this.needsFull
    const oldRoots = this.roots
    this.roots = roots
    let itemChanges: DiffContext['itemChanges'] | undefined
    let added: Map<string, ItemSnap> | undefined
    if (failed) this.itemTracker.rebuild(data.items)
    else {
      const scan = this.itemTracker.scan(data.items)
      added = new Map()
      for (const entry of scan.added) {
        const snap = after.items.get(entry.id)
        if (snap) added.set(entry.id, snap)
      }
      const changed = new Map<string, {snap: ItemSnap; change: ItemChange}>()
      for (const entry of scan.synced) {
        const change = scan.changes.get(entry.id)
        const snap = after.items.get(entry.id)
        if (change && snap) changed.set(entry.id, {snap, change})
      }
      // Linked spaces moved: an item whose path did not change can still have a different placement.
      const linkedOf = (list: readonly SpaceRoot[]) => JSON.stringify(list.filter((space) => space.linked).map((space) => [space.id, space.rel, space.abs]))
      if (linkedOf(oldRoots) !== linkedOf(roots)) {
        for (const entry of data.items) {
          const snap = after.items.get(entry.id)
          if (!snap || added.has(entry.id) || !this.itemTracker.has(entry.id) || changed.get(entry.id)?.change.place) continue
          if (placementOf(entry.path, this.folder.root, oldRoots) === snap.place) continue
          const known = changed.get(entry.id)
          if (known) known.change = {...known.change, place: true}
          else changed.set(entry.id, {snap, change: {fields: [], place: true, playsWas: snap.plays}})
        }
      }
      itemChanges = {changed, removed: scan.removed}
    }
    this.collectionTracker.rebuild(data.collections)
    this.spacesKey = key
    this.lastSpaces = spaces
    this.lastRoutes = routes
    this.needsFull = false
    return this.enqueue(async () => {
      const drafts = failed
        ? await this.diffWithTags({...this.baseline, items: await this.committedItems()}, after)
        : await this.diffWithTags({...this.baseline, items: new Map()}, {...after, items: added as Map<string, ItemSnap>}, itemChanges)
      await this.store.apply(drafts)
      this.noteApplied(drafts)
      this.baseline = {...after, items: new Map()}
      this.device.replaceAll(items, collections)
      this.device.linked = linked
      this.modelLast.set(this.store.actorId, this.store.last.get(this.store.actorId) ?? 0)
      // Everything may have changed: the cache starts over from this model.
      void this.cache.writeBase(model.items, model.collections, this.cacheMeta()).catch((error) => console.error('Could not write the library cache:', error))
    })
  }

  /** The item snapshots of what the store holds now (after a failed save, when the saved values may be ahead of it). */
  private async committedItems(): Promise<Map<string, ItemSnap>> {
    const projected = await this.fetchAll()
    const roots = spaceRoots(projected.spaces, this.folder.root, this.baseline.rels)
    const out = new Map<string, ItemSnap>()
    const slicer = new Slicer()
    for (let i = 0; i < projected.items.length; i++) {
      const entry = projected.items[i]
      if (entry.managed !== false && isUuid(entry.id)) out.set(entry.id, itemSnap(entry, roots, this.folder.root))
      if ((i & 63) === 63) await slicer.tick()
    }
    return out
  }

  private enqueue(work: () => Promise<void>): Promise<void> {
    const run = this.chain.then(async () => {
      if (this.closed) throw new Error('The library is closed.')
      await this.ready
      await this.baselineReady
      try {
        await work()
      } catch (error) {
        this.needsFull = true
        throw error
      }
    })
    this.chain = run.catch(() => undefined)
    return run
  }

  /**
   * The operations for a change. A delete or remove must carry what the writer observed, and a reorder needs the
   * playlist's songs: the first pass records what the diff asks about, the store thread answers in one round trip, and
   * the second pass builds the operations with the answers.
   */
  private async diffWithTags(before: ModelSnap, after: ModelSnap, itemChanges?: DiffContext['itemChanges']): Promise<OpDraft[]> {
    const recorder = new RecordingObserved()
    const wanted = new Set<string>()
    const base = {actor: this.store.actorId, replicaId: this.folder.replicaId, newId: this.deps.newId, ownPlays: (id: string) => this.ownPlays.get(id) ?? 0}
    const first = diffSnaps(before, after, {...base, itemChanges, songs: (playlist) => (wanted.add(playlist), []), observed: recorder})
    if (recorder.empty && !wanted.size) return first
    const [answer, songs] = await Promise.all([recorder.empty ? undefined : this.store.observed(recorder.query()), wanted.size ? this.store.songs([...wanted]) : ({} as Record<string, never[]>)])
    const observed: ObservedLookup = answer ? answerObserved(answer) : answerObserved({items: {}, playlists: {}, spaces: {}, setElements: {}})
    return diffSnaps(before, after, {...base, itemChanges, songs: (playlist) => songs[playlist] ?? [], observed})
  }

  /** Keeps the local play counters in step with what was written; an item placed here gets its content recorded soon after. */
  private noteApplied(drafts: readonly OpDraft[]) {
    const field = `plays@${this.store.actorId}`
    for (const draft of drafts) if (draft.kind === 'item.set' && draft.body.field === field && typeof draft.body.value === 'number') this.ownPlays.set(draft.body.item as string, draft.body.value)
    const items = new Set<string>()
    for (const draft of drafts) if (draft.kind.startsWith('item.') && typeof draft.body.item === 'string') items.add(draft.body.item)
    if (items.size) this.scheduleFill(2000, items)
  }

  private fillTimer?: ReturnType<typeof setTimeout>
  private filling?: Promise<void>
  /** The items the next pass looks at: those changed since the last one, or all of them (at the start). */
  private fillWanted: Set<string> | 'all' = new Set()
  /** `size:mtime` of each lyrics, subtitle or info file whose record this session wrote or confirmed, by its path. */
  private readonly companionSigs = new Map<string, string>()

  private scheduleFill(delay = 2000, ids?: Iterable<string>) {
    if (this.closed) return
    if (!ids) this.fillWanted = 'all'
    else if (this.fillWanted !== 'all') for (const id of ids) this.fillWanted.add(id)
    if (this.fillTimer) return
    this.fillTimer = setTimeout(() => {
      this.fillTimer = undefined
      // one pass at a time; what arrived meanwhile waits for the next
      if (this.filling) return void this.filling.then(() => this.scheduleFill(0, []))
      const wanted = this.fillWanted
      this.fillWanted = new Set()
      this.filling = this.fillContent(wanted)
        .catch(() => undefined)
        .finally(() => (this.filling = undefined))
    }, delay)
    this.fillTimer.unref?.()
  }

  /**
   * A copy carries only what it can check by its bytes, and it carries everything that belongs to an item. An item placed here
   * without a recorded content (a download added since setup, or a file setup could not match) gets it from its file; its
   * lyrics, subtitles and info file beside it get their records (`companion:<suffix>`, dev-docs/plans/COPIES-COMPANIONS.md). A few
   * at a time, through the same queue as every save.
   */
  private async fillContent(wanted: Set<string> | 'all'): Promise<void> {
    if (wanted !== 'all' && !wanted.size) return
    await this.ready
    await this.ensureTrackers()
    const unplaced = (id: string) => unplacedPath(this.folder.root, id)
    const ids = wanted === 'all' ? this.itemTracker.ids() : [...wanted]
    const apply = (drafts: OpDraft[]) =>
      this.enqueue(async () => {
        await this.store.apply(drafts)
        this.modelLast.set(this.store.actorId, this.store.last.get(this.store.actorId) ?? 0)
      })
    for (let from = 0; from < ids.length && !this.closed; from += 1000) {
      const part = await this.store.viewsFor({all: false, items: ids.slice(from, from + 1000), playlists: [], spaces: [], sets: [], places: [], profile: false, findings: false})
      let drafts: OpDraft[] = []
      for (const view of part.items) {
        if (this.closed) return
        if (view.status !== 'present') continue
        const file = this.itemTracker.get(view.id, 'path') as string | undefined
        if (!file || file === unplaced(view.id)) continue
        if (!view.heads.length) {
          const size = (await fs.stat(file).catch(() => undefined))?.size
          const hex = size === undefined ? undefined : await sha256File(file).catch(() => undefined)
          if (hex) drafts.push({kind: 'item.revise', body: {item: view.id, content: `sha256:${hex}`, size: size!, parents: []}})
        }
        drafts.push(...(await this.companionDrafts(view, file)))
        if (drafts.length >= 25) {
          await apply(drafts)
          drafts = []
        }
      }
      if (drafts.length) await apply(drafts)
      await yieldNow()
    }
  }

  /**
   * The records of an item's companions as they are beside [file] now. A file is hashed when it has no record, its size
   * differs from the record, or it changed since this session last looked; otherwise its record stands. A record is cleared
   * only for a file this session saw and that is gone now: a device that never had the lyrics never erases them for others.
   */
  private async companionDrafts(view: SyncItem, file: string): Promise<OpDraft[]> {
    const device = this.device.items.get(view.id)
    const dir = path.dirname(file)
    const media = path.basename(file)
    const recorded = new Map(companionsOf(view.fields).map((c) => [c.suffix, c]))
    const out: OpDraft[] = []
    const seen = new Set<string>()
    for (const sidecar of [device?.lrc, device?.infoJson, device?.videoPath, ...(device?.subPaths ?? [])]) {
      if (!sidecar || path.dirname(sidecar) !== dir) continue
      const suffix = companionSuffix(media, path.basename(sidecar))
      if (!suffix || seen.has(suffix)) continue
      seen.add(suffix)
      const stat = await fs.stat(sidecar).catch(() => undefined)
      if (!stat?.isFile()) continue
      const sig = `${stat.size}:${Math.round(stat.mtimeMs)}`
      const was = recorded.get(suffix)
      const known = this.companionSigs.get(sidecar)
      if (known === sig || (known === undefined && was?.size === stat.size)) {
        this.companionSigs.set(sidecar, sig)
        continue
      }
      const hex = await sha256File(sidecar).catch(() => undefined)
      if (!hex) continue
      this.companionSigs.set(sidecar, sig)
      if (was?.content === `sha256:${hex}` && was.size === stat.size) continue
      out.push({kind: 'item.set', body: {item: view.id, field: companionField(suffix), value: {content: `sha256:${hex}`, size: stat.size}}})
    }
    // gone since this session saw it
    for (const [suffix] of recorded) {
      if (seen.has(suffix)) continue
      const gone = path.join(dir, path.parse(media).name + suffix)
      if (!this.companionSigs.has(gone) || (await fs.stat(gone).then(() => true, () => false))) continue
      this.companionSigs.delete(gone)
      out.push({kind: 'item.set', body: {item: view.id, field: companionField(suffix), value: null}})
    }
    return out
  }

  private async commitPartial(job: PartialJob) {
    const before = emptySnapshot()
    const after = emptySnapshot()
    before.rels = after.rels = this.baseline.rels
    for (const [id, snap] of job.items) after.items.set(id, snap)
    for (const [id, snap] of job.collections) {
      const was = this.baseline.collections.get(id)
      if (was) before.collections.set(id, was)
      after.collections.set(id, snap)
    }
    for (const id of job.removedCollections) {
      const was = this.baseline.collections.get(id)
      if (was) before.collections.set(id, was)
    }
    const drafts = await this.diffWithTags(before, after, {changed: job.changed, removed: job.removedItems})
    await this.store.apply(drafts)
    this.noteApplied(drafts)
    for (const id of job.removedItems) this.ownPlays.delete(id)
    for (const [id, snap] of job.collections) this.baseline.collections.set(id, snap)
    for (const id of job.removedCollections) this.baseline.collections.delete(id)
    // lyrics or subtitles found beside an item, or gone from it (a download, a move, a rename) get their records
    const hasSidecars = (record: DeviceItem | null | undefined) => !!(record && (record.lrc || record.infoJson || record.videoPath || record.subPaths?.length))
    const sidecars = [...job.deviceItems].filter(([id, record]) => hasSidecars(record) || hasSidecars(this.device.items.get(id))).map(([id]) => id)
    for (const [id, record] of job.deviceItems) this.device.setItem(id, record)
    if (sidecars.length) this.scheduleFill(2000, sidecars)
    for (const [id, record] of job.deviceCollections) this.device.setCollection(id, record)
    // The model now includes this device's own operations (and not remote ones it has not seen yet).
    this.modelLast.set(this.store.actorId, this.store.last.get(this.store.actorId) ?? 0)
    if (job.journal.length) this.cache.record(job.journal, () => this.cacheMeta())
  }

  /** Waits for every save started so far. */
  async idle(): Promise<void> {
    await this.chain
  }

  /** Applies operations that are not a diff of the model (the migration): they go straight into the store. */
  async applyDrafts(drafts: OpDraft[]): Promise<void> {
    await this.ready
    await this.chain
    await this.store.apply(drafts)
  }

  // ------------------------------------------------------------------ the storage service

  /** Files, bytes and missing files per space, from the saved copies (no projection). */
  async overview() {
    await this.ensureTrackers()
    const summary = this.itemTracker.summary()
    const spaces: StorageOverview['spaces'] = this.lastSpaces
      .filter((space) => space.type !== 'smart')
      .map((space) => ({id: space.id, name: space.name, files: summary.get(space.id)?.files ?? 0, bytes: summary.get(space.id)?.bytes ?? 0}))
    let bytes = 0
    let missing = 0
    for (const row of summary.values()) {
      bytes += row.bytes
      missing += row.missing
    }
    const musicVideoBytes = await this.store.musicVideoBytes()
    return {spaces, bytes, missing, musicVideoBytes, checking: this.checking, lastCheckedAt: this.device.check?.at, mismatched: this.device.check?.mismatched}
  }

  /** Hashes every placed file that has a recorded revision and counts the ones that no longer match. */
  async checkFiles(): Promise<void> {
    if (this.checking) return
    this.checking = true
    try {
      await this.ready
      await this.ensureTrackers()
      const ids = this.itemTracker.ids()
      let mismatched = 0
      const unplaced = (id: string) => unplacedPath(this.folder.root, id)
      for (let from = 0; from < ids.length; from += 1000) {
        const slice = ids.slice(from, from + 1000)
        const part = await this.store.viewsFor({all: false, items: slice, playlists: [], spaces: [], sets: [], places: [], profile: false, findings: false})
        for (const view of part.items) {
          const expected = view.heads[0]?.content
          const file = this.itemTracker.get(view.id, 'path') as string | undefined
          if (!expected?.startsWith('sha256:') || !file || file === unplaced(view.id)) continue
          const actual = await sha256File(file).catch(() => undefined)
          if (actual !== undefined && `sha256:${actual}` !== expected) mismatched++
        }
        await yieldNow()
      }
      this.device.setCheck({at: this.deps.now(), mismatched})
      await this.device.save()
    } finally {
      this.checking = false
    }
  }

  async close(): Promise<void> {
    if (this.closed) return
    if (this.fillTimer) clearTimeout(this.fillTimer)
    this.fillTimer = undefined
    await this.ready.catch(() => undefined)
    // Saved values never asked for need not be read now; ones being read are waited for.
    if (this.trackersStart) this.trackersStart = undefined
    else await this.trackersReady.catch(() => undefined)
    await this.chain
    this.closed = true
    this.unsubscribe?.()
    this.listeners.clear()
    await this._store?.close()
    await this.device.save().catch(() => undefined)
    await this.cache.close()
  }
}

async function readArt(folder: KeepfinFolder): Promise<Set<string>> {
  const names = await fs.readdir(path.join(recordsDir(folder), 'art')).catch(() => [] as string[])
  return new Set(names.map((name) => name.replace(/\.(jpg|png|webp)$/, '')))
}

function sha256File(file: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha256')
    const stream = createReadStream(file)
    stream.on('data', (chunk) => hash.update(chunk))
    stream.on('error', reject)
    stream.on('end', () => resolve(hash.digest('hex')))
  })
}

/** The default spaces and routes of a new library (desktop spec section 6), with their folders under the Keepfin folder. */
export function defaultModel(root: string): Model {
  const settings = defaultLibrarySettings({downloads: path.join(root, 'Downloads'), music: path.join(root, 'Music'), videos: path.join(root, 'Videos')})
  return {items: [], collections: [], spaces: settings.spaces, routes: settings.routes}
}

/** The LibraryPort of the storage service, on top of LibrarySession. */
export class StoreLibrary implements LibraryPort {
  private session?: LibrarySession

  constructor(private readonly deps: StoreLibraryDeps) {}

  /** The open library; the index is given this as its persistence. */
  get persistence(): LibrarySession {
    if (!this.session) throw new Error('The library is not open.')
    return this.session
  }

  get isOpen() {
    return !!this.session
  }

  async create(folder: KeepfinFolder, actorId: string): Promise<void> {
    const session = await LibrarySession.open(folder, actorId, this.deps)
    try {
      const model = defaultModel(folder.root)
      const after = snapshot(model, {root: folder.root})
      const drafts = diffSnaps(emptySnapshot(), after, {
        actor: session.store.actorId,
        replicaId: folder.replicaId,
        newId: this.deps.newId,
        ownPlays: () => 0,
        songs: () => [],
        observed: answerObserved({items: {}, playlists: {}, spaces: {}, setElements: {}}),
      })
      await session.store.apply(drafts)
    } finally {
      await session.close()
    }
  }

  async open(folder: KeepfinFolder, actorId: string): Promise<void> {
    await this.session?.close()
    this.session = await LibrarySession.open(folder, actorId, this.deps, {deferStore: this.deps.deferStore, onProgress: this.deps.onProgress})
  }

  async overview() {
    return this.persistence.overview()
  }

  async checkFiles() {
    await this.persistence.checkFiles()
  }

  async close() {
    await this.session?.close()
    this.session = undefined
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// The migration (the setup window's switch point)
// ---------------------------------------------------------------------------------------------------------------------

export type ImportExtras = {
  /** The old settings (spaces carry layout, naming and colours; library.json does not). Without them spaces get defaults. */
  settings?: LibrarySettings
  /** Content of each copied file by old item id, from the migration journal (written as `item.revise`). */
  hashes?: Record<string, {content: string; size: number}>
}

const text = (value: unknown) => (typeof value === 'string' ? value : undefined)
const number = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : undefined)

/**
 * The model a migration plan describes: the old library.json items and collections under their new ids and paths, the
 * spaces moved into the Keepfin folder (or linked where they were left). Pure; `importPlan` turns it into operations.
 */
export function modelFromPlan(plan: Plan, extras: ImportExtras = {}): {model: Model; revisions: {item: string; content: string; size: number}[]} {
  const root = plan.target
  const settingsSpaces = new Map((extras.settings?.spaces ?? []).map((space) => [space.id, space]))
  const spaces: Space[] = []
  for (const planned of plan.spaces) {
    const old = settingsSpaces.get(planned.id)
    const base: Space = old ? structuredClone(old) : {id: planned.id, name: planned.name, kind: planned.id === 'music' ? 'music' : planned.id === 'videos' ? 'video' : 'mixed', folder: planned.folder, layout: 'flat', naming: 'title', skipExisting: false, type: 'folder'}
    const inventorySpace = plan.inventory.spaces.find((space) => space.id === planned.id)
    if (!old && inventorySpace?.ignore) base.ignore = inventorySpace.ignore
    base.folder = planned.choice === 'leave' || planned.destRel === null ? planned.folder : path.join(root, ...planned.destRel.split('/').filter(Boolean))
    spaces.push(base)
  }
  // Smart spaces own no folder and are not in the plan; they keep their rules.
  for (const old of extras.settings?.spaces ?? []) if (old.type === 'smart' && !spaces.some((space) => space.id === old.id)) spaces.push(structuredClone(old))
  const routes: Routes = extras.settings?.routes ?? {music: 'music', other: 'downloads'}

  const placed = (old: string): string | undefined => {
    const rel = plan.mapping.paths[old]
    return rel ? path.join(root, ...rel.split('/')) : undefined
  }
  const items: LibraryEntry[] = []
  const revisions: {item: string; content: string; size: number}[] = []
  for (const item of plan.inventory.library.items) {
    const id = plan.mapping.items[item.id]
    if (!id) continue
    const raw = item.raw
    const file = placed(item.path) ?? item.path
    const entry: Record<string, unknown> = {
      id,
      space: text(raw.space) ?? item.space,
      path: file,
      folder: '',
      kind: text(raw.kind) ?? 'other',
      title: text(raw.title) ?? path.basename(file),
      size: number(raw.size) ?? 0,
      modifiedAt: number(raw.modifiedAt) ?? 0,
      addedAt: number(raw.addedAt) ?? 0,
      hasArt: raw.hasArt === true,
      managed: raw.managed !== false,
    }
    for (const key of ['artist', 'album', 'albumArtist', 'genre', 'sourceUrl', 'sourceId'] as const) if (text(raw[key]) !== undefined) entry[key] = raw[key]
    for (const key of ['track', 'disc', 'year', 'duration', 'width', 'height', 'position', 'plays', 'lastPlayedAt', 'gain'] as const) if (number(raw[key]) !== undefined) entry[key] = raw[key]
    for (const key of ['hasLyrics', 'watched', 'favorite'] as const) if (typeof raw[key] === 'boolean') entry[key] = raw[key]
    for (const key of ['tags', 'userTags', 'subtitles'] as const) if (Array.isArray(raw[key]) && (raw[key] as unknown[]).every((v) => typeof v === 'string')) entry[key] = raw[key]
    if (Array.isArray(raw.chapters)) entry.chapters = raw.chapters
    if (text(raw.jobId)) entry.jobId = raw.jobId
    items.push(entry as LibraryEntry)
    const hash = extras.hashes?.[item.id]
    if (hash) revisions.push({item: id, content: hash.content, size: hash.size})
  }
  const known = new Set(items.map((entry) => entry.id))
  const collections: Collection[] = []
  for (const old of plan.inventory.library.collections) {
    const collection = sanitizeCollection(old.raw)
    const id = plan.mapping.collections[old.id]
    if (!collection || !id) continue
    const remap = (ids: string[] | undefined) => (ids ?? []).map((value) => plan.mapping.items[value]).filter((value): value is string => !!value && known.has(value))
    collection.id = id
    collection.itemIds = remap(collection.itemIds)
    if (collection.newItemIds) collection.newItemIds = remap(collection.newItemIds)
    if (collection.removedItemIds) collection.removedItemIds = remap(collection.removedItemIds)
    if (collection.coverItemId) collection.coverItemId = plan.mapping.items[collection.coverItemId]
    collections.push(collection)
  }
  return {model: {items, collections, spaces, routes}, revisions}
}

/**
 * Turns a migration plan into the library's first operations (the setup window's switch point). The model is diffed
 * against an empty library; the item revisions known from the journal follow. Safe to run again after a crash: it only
 * writes when the library holds no items yet.
 */
export async function importPlan(session: LibrarySession, plan: Plan, extras: ImportExtras = {}): Promise<boolean> {
  const existing = await session.store.views()
  if (existing.items.length || existing.spaces.length) return false
  const {model, revisions} = modelFromPlan(plan, extras)
  const after = snapshot(model, {root: session.folder.root})
  const drafts = diffSnaps(emptySnapshot(), after, {
    actor: session.store.actorId,
    replicaId: session.folder.replicaId,
    newId: session.newId,
    ownPlays: () => 0,
    songs: () => [],
    observed: answerObserved({items: {}, playlists: {}, spaces: {}, setElements: {}}),
  })
  for (const revision of revisions) drafts.push({kind: 'item.revise', body: {item: revision.item, content: revision.content, size: revision.size, parents: []}})
  // Spaces left where they were are linked: their folders on this device go into linked.json.
  for (const space of spaceRoots(model.spaces, session.folder.root)) if (space.linked) session.device.linked[space.id] = space.abs
  await session.device.save()
  await session.applyDrafts(drafts)
  await session.store.flush()
  await session.reload()
  return true
}
