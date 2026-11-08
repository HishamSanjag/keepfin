// The library index: managed media items, their metadata, playback state and playlists.
// Persisted to <userData>/library.json (atomic, debounced, never on progress ticks).
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {StoredJob as Job} from '../migrate.js'
import {
  canonicalSourceUrl,
  channelArtist,
  collectionFromPlaylist,
  defaultFollow,
  defaultSpaceFor,
  folderSpaces,
  followIntervals,
  isInfiniteSource,
  mediaKindOf,
  normalizeTags,
  normalizeTitle,
  parseLrc,
  restoreOrder,
  sameRecording,
  sortBySourceOrder,
  sourceIdFromUrl,
  sourceIdOfCollection,
  type Collection,
  type CollectionFollow,
  type CollectionKind,
  type ExistingMatch,
  type ExistingProbe,
  type LibraryItem,
  type LibrarySettings,
  type LibrarySnapshot,
  type Lyrics,
  type Space,
} from '../../shared/library.js'
import type {LibraryBatchPush, LibraryDeltaPush, LibraryMeta} from '../../shared/contracts.js'
import {providerName} from '../../shared/providers.js'
import {ArtStore, posterFrame} from './art.js'
import {findSidecars, isMusicVideoFile, musicVideoNames, readEmbeddedLyrics, readInfoChapters, readTags} from './tags.js'
import {fingerprint} from './fingerprint.js'
import {renameWithRetry} from '../fsutil.js'
import {videoCompanionStem, videoCompanionSuffix} from '../../shared/core/copies.js'
import {matchDamaged} from '../engine/outputs.js'
import {Slicer} from '../store/slices.js'
import {LibraryCollections} from './parts/collections.js'
import type {LibraryCore} from './parts/core.js'
import {Enrichment} from './parts/enrichment.js'
import {Relinker} from './parts/relink.js'
import {isInside, itemIdFor, keyOf, publicItem, relativeFolder, sanitizeCollection, sanitizeEntry, sanitizePlaylist, titleFromName} from './parts/sanitize.js'
import {Scanner} from './parts/scanner.js'
import {type AfterFacts, noSaving, runAfterDownload, SAVING} from '../afterDownload.js'
import {addEach} from './partial.js'

/** A library item plus bookkeeping that never reaches the renderer. */
export type LibraryEntry = LibraryItem & {
  /** Downloaded or imported by Keepfin. Unmanaged entries come from a space's "show other files" scan. */
  managed: boolean
  /** `${size}:${modifiedAt}` the metadata was last read for. */
  sig?: string
  subPaths?: string[]
  lrc?: string
  infoJson?: string
  /** The song's music video file (Song.video.mp4) beside it; the renderer sees musicVideo. */
  videoPath?: string
  /** Loudness was measured (or could not be), so it is not queued again. */
  analysed?: boolean
  /** Content fingerprint, computed on demand for duplicate detection. */
  hash?: string
  /** Place in the source collection it was downloaded from (0-based); keeps downloaded collections in source order. */
  colIndex?: number
  /** Imported tags are read from the file's own genre once it is tagged (collection downloads carry no source tags). */
  tagsPending?: boolean
}

/** Where items stood in one collection when they were deleted (taken before, for Undo). */
export type CollectionMembership = {id: string; itemIds: string[]; newItemIds?: string[]; removedItemIds?: string[]}

export type LibraryHooks = {
  /** A managed file moved or was renamed: job history follows. */
  relocated(from: string, to: string): void
  /** Files were deleted or left the library for good. */
  forgotten(paths: string[]): void
  /** Store mode: another device changed the spaces or routes (the settings follow). */
  spacesChanged?(spaces: Space[], routes: LibrarySettings['routes']): void
}

/** What the library keeps between runs, as the store projects it. */
export type PersistedLibrary = {
  items: LibraryEntry[]
  collections: Collection[]
  /** The store also carries the spaces and routes (store mode only). */
  spaces?: Space[]
  routes?: LibrarySettings['routes']
  /**
   * Save only: when present, `items` is empty and only these entries may have changed (an id with no entry was
   * removed). Collections and spaces are always complete.
   */
  touched?: {ids: readonly string[]; entry(id: string): LibraryEntry | undefined; all(): LibraryEntry[]}
}

/** Only what changed after a remote change: entries and collections to put in, and ids to take out. */
export type PersistedDelta = {
  items: LibraryEntry[]
  removedItems: string[]
  collections: Collection[]
  removedCollections: string[]
}

/**
 * Store mode (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md section 4): instead of library.json the library lives in the
 * Keepfin folder as sync operations. The index keeps its in-memory model; this seam replaces its one load point and
 * its one save point.
 */
export interface LibraryPersistence {
  load(): Promise<PersistedLibrary>
  /**
   * After a remote change: brings what other devices changed from the store's thread (asynchronous, off the main thread).
   * What it returns is opaque to the index; it is handed to applyRemote().
   */
  fetchRemote(previous?: unknown): Promise<unknown>
  /**
   * Projects what fetchRemote() brought (synchronous and cheap: a delta is projected for its keys only; a whole library
   * comes back as a PersistedLibrary). The index calls it in the same tick as it swaps its model in, so nothing a user
   * did in between can be lost.
   */
  applyRemote(fetched: unknown): PersistedDelta | PersistedLibrary
  /** Persists a model. Called with live objects: implementations must copy what they need before their first await. */
  save(data: PersistedLibrary): Promise<void>
  /** Other devices' changes arrived. Returns the unsubscribe function. */
  onRemoteChange(listener: () => void): () => void
  /** This device saved (or removed) art for an item in `artDir`: what the store projects as hasArt must say so too. */
  noteArt?(id: string, has: boolean): void
  /** The item's art in the Keepfin folder (.keepfin/art), for art this device has not saved itself. */
  folderArt?(id: string): Promise<string | undefined>
}

export type LibraryOptions = {
  /** library.json */
  file: string
  /** <userData>/library-art */
  artDir: string
  ffmpeg: () => string | undefined
  /** Durable artwork of a job (its saved thumbnail), if any. */
  jobArt?: (job: {id: string; thumbnail?: string; file: string}) => Promise<{bytes: Uint8Array; mime?: string} | undefined>
  /** Completed jobs to adopt (history from before the library, or after turning it back on). */
  jobs?: () => Job[]
  /** Whether a download is still on its way (queued, running, paused): a music video waits for its song while it is. */
  songPending?: (jobId: string) => boolean
  now?: () => number
  /** Items per push batch, and the size up to which a library is pushed whole (default 5,000). */
  pushBatch?: number
  /** Store mode: persist through the Keepfin folder's sync store instead of `file`. */
  persistence?: LibraryPersistence
  /** Store mode: ids of new items and collections (UUIDs). */
  newId?: () => string
  saveDelay?: number
  pushDelay?: number
  watchDelay?: number
  /** fs.watch on space folders (default true). */
  watch?: boolean
  /** Background loudness analysis, and in store mode covers for items that have none (default true). */
  analyse?: boolean
}

let tempCounter = 0

const recordingOf = (entry: LibraryEntry) => ({
  artist: entry.artist ?? entry.albumArtist,
  title: entry.title,
  album: entry.album,
  duration: entry.duration,
})

export type LibraryOperation = NonNullable<LibrarySnapshot['operation']>

/** What the window is sent when the library changes (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md 10.2). */
export type LibraryPushEvent = {type: 'library'; library: LibrarySnapshot} | LibraryDeltaPush | LibraryBatchPush

/** Libraries up to this many items are sent whole; bigger ones go in batches of this size. */
export const PUSH_BATCH = 5000

export class LibraryIndex {
  hooks: LibraryHooks = {relocated() {}, forgotten() {}}
  private readonly entries = new Map<string, LibraryEntry>()
  private readonly byPath = new Map<string, string>()
  private readonly collections = new Map<string, Collection>()
  private readonly art: ArtStore
  private settings?: LibrarySettings
  private readonly listeners = new Set<(snapshot: LibrarySnapshot) => void>()
  // pushes to the window: deltas by the ids changed since the last push
  private readonly pushListeners = new Set<(event: LibraryPushEvent) => void>()
  private pushSeq = 0
  private readonly pushIds = new Set<string>()
  private pushAll = true
  private pushCollections = false
  private readonly sentCollections = new Map<string, string>()
  private sentMeta = ''
  private sending = false
  private version = 0
  private pushed = -1
  private lastPush = 0
  private pushTimer?: NodeJS.Timeout
  private saveTimer?: NodeJS.Timeout
  private saveChain: Promise<void> = Promise.resolve()
  private closing = false
  private operation?: LibraryOperation
  private cancelController?: AbortController
  /** Completed jobs being added to the library (their files are read before the item exists). */
  private adopting = 0
  private readonly artRequests = new Map<string, {id: string; thumbnail?: string; file: string}>()
  private readonly idle = new Set<() => void>()
  // lookup tables for duplicate detection, rebuilt after any change to the entries
  private tables?: {bySource: Map<string, LibraryEntry[]>; byTitle: Map<string, LibraryEntry[]>}
  // lyrics text cache (memory only)
  private readonly lyricsText = new Map<string, {sig: string; text: string}>()
  private held = 0

  private readonly collectionsPart: LibraryCollections
  private readonly enrichment: Enrichment
  private readonly scanner: Scanner
  private readonly relinker: Relinker

  constructor(private readonly options: LibraryOptions) {
    this.art = new ArtStore(options.artDir)
    const core: LibraryCore = {
      options,
      entries: () => this.entries,
      byPath: () => this.byPath,
      collections: () => this.collections,
      settings: () => this.settings,
      closing: () => this.closing,
      held: () => this.held,
      hooks: () => this.hooks,
      art: () => this.art,
      artRequests: () => this.artRequests,
      lyricsText: () => this.lyricsText,
      clearTables: () => {
        this.tables = undefined
      },
      now: () => this.now(),
      newId: () => this.newId(),
      changed: (persist, items, collections) => this.changed(persist, items, collections),
      space: (id) => this.space(id),
      spaces: () => this.spaces(),
      spaceOf: (file) => this.spaceOf(file),
      item: (id) => this.item(id),
      all: () => this.all(),
      entryAt: (file) => this.entryAt(file),
      drop: (ids, dropOptions) => this.drop(ids, dropOptions),
      relocate: (id, newPath, stat) => this.relocate(id, newPath, stat),
      addFile: (file, addOptions) => this.addFile(file, addOptions),
      syncJobs: () => this.syncJobs(),
      putArt: (id, bytes, mime) => this.putArt(id, bytes, mime),
      queueEnrich: (id) => this.queueEnrich(id),
      queueLoudness: (id) => this.enrichment.queueLoudness(id),
      relinkEntries: (entries) => this.relinker.relinkEntries(entries),
    }
    this.collectionsPart = new LibraryCollections(core)
    this.enrichment = new Enrichment(core)
    this.scanner = new Scanner(core)
    this.relinker = new Relinker(core)
  }

  private now() {
    return this.options.now?.() ?? Date.now()
  }

  // ------------------------------------------------------------------ lifecycle

  async init() {
    if (this.options.persistence) return this.initFromStore(this.options.persistence)
    let data: {items?: unknown; collections?: unknown; playlists?: unknown} = {}
    try {
      data = JSON.parse(await fs.readFile(this.options.file, 'utf8'))
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT')
        await fs.copyFile(this.options.file, `${this.options.file}.corrupt-${this.now()}`).catch(() => {})
    }
    // The interim model recorded a downloaded collection on each item; those become persistent collections.
    const interim = new Map<string, {title: string; members: {id: string; position: number; addedAt: number; space: string; sourceId?: string}[]}>()
    for (const raw of Array.isArray(data.items) ? data.items : []) {
      const legacy = raw && typeof raw === 'object' ? (raw as {collection?: unknown}).collection : undefined
      const entry = sanitizeEntry(raw)
      if (!entry) continue
      if (legacy && typeof legacy === 'object') {
        const group = legacy as {id?: unknown; title?: unknown; position?: unknown}
        if (typeof group.id === 'string' && typeof group.title === 'string') {
          const position = typeof group.position === 'number' && Number.isFinite(group.position) ? group.position : 0
          entry.colIndex ??= position
          const known = interim.get(group.id) ?? {title: group.title, members: []}
          known.members.push({id: entry.id, position, addedAt: entry.addedAt, space: entry.space, sourceId: entry.sourceId})
          interim.set(group.id, known)
        }
        delete (entry as {collection?: unknown}).collection
      }
      this.entries.set(entry.id, entry)
      this.byPath.set(keyOf(entry.path), entry.id)
    }
    let migrated = interim.size > 0
    if (Array.isArray(data.collections)) {
      for (const raw of data.collections) {
        const collection = sanitizeCollection(raw)
        if (collection) this.collections.set(collection.id, collection)
      }
    } else {
      // Playlists from before collections are collections of kind 'own'.
      for (const raw of Array.isArray(data.playlists) ? data.playlists : []) {
        const playlist = sanitizePlaylist(raw)
        if (playlist) {
          this.collections.set(playlist.id, collectionFromPlaylist(playlist, 'music'))
          migrated = true
        }
      }
    }
    for (const [id, group] of interim) {
      if (this.collections.has(id)) continue
      group.members.sort((a, b) => a.position - b.position || a.addedAt - b.addedAt || a.id.localeCompare(b.id))
      const spaces = new Map<string, number>()
      for (const member of group.members) spaces.set(member.space, (spaces.get(member.space) ?? 0) + 1)
      const space = [...spaces].sort((a, b) => b[1] - a[1])[0]?.[0] ?? 'downloads'
      const now = this.now()
      this.collections.set(id, {
        id,
        name: group.title,
        kind: 'playlist',
        space,
        itemIds: group.members.map((member) => member.id),
        // Skipped items were not recorded then: only what was saved is known, so following stays off until the user opts in.
        follow: {...defaultFollow('playlist'), mode: 'off'},
        seen: [...new Set(group.members.map((member) => member.sourceId).filter((value): value is string => !!value))],
        createdAt: Math.min(...group.members.map((member) => member.addedAt)),
        updatedAt: now,
      })
    }
    // Collections never keep ids of items that are not in the library (a crash between a delete and its save).
    for (const collection of this.collections.values()) {
      const known = (ids: string[]) => ids.filter((id) => this.entries.has(id))
      collection.itemIds = known(collection.itemIds)
      if (collection.newItemIds) collection.newItemIds = known(collection.newItemIds)
      if (collection.removedItemIds) collection.removedItemIds = known(collection.removedItemIds)
    }
    this.tables = undefined
    if (migrated) this.changed()
  }

  // ------------------------------------------------------------------ store mode

  private unsubscribeRemote?: () => void
  /** Store mode: the spaces (absolute folders on this device) and routes the Keepfin folder holds; the settings mirror them. */
  private stored?: {spaces: Space[]; routes?: LibrarySettings['routes']}
  storeSpaces() {
    return this.stored
  }
  private remoteChain: Promise<unknown> = Promise.resolve()

  private newId() {
    return this.options.newId?.() ?? randomUUID()
  }

  private async initFromStore(persistence: LibraryPersistence) {
    // Art this device saved itself (downloads, combined libraries) lives in artDir, not in .keepfin/art: the store learns it first.
    const own = persistence.noteArt ? await this.art.ids() : []
    for (const id of own) persistence.noteArt!(id, true)
    const loaded = await persistence.load()
    if (loaded.spaces) this.stored = {spaces: loaded.spaces, routes: loaded.routes}
    await this.applyPersistedInSlices(loaded)
    // the first model may have been projected before the store knew of this art
    for (const id of own) {
      const entry = this.entries.get(id)
      if (entry) entry.hasArt = true
    }
    this.unsubscribeRemote = persistence.onRemoteChange(() => {
      // One at a time and in order: a later fetch must never be applied before an earlier one.
      this.remoteChain = this.remoteChain.then(() => this.reproject()).catch((error) => console.error('Could not apply changes from other devices:', error))
    })
    if (this.options.analyse !== false) setTimeout(() => void this.backfillArt().catch(() => undefined), 5_000).unref?.()
  }

  /**
   * Store mode: items without art (downloaded before the move, or added while the library was elsewhere) get it once, one at a
   * time: the download's thumbnail, the file's own cover, or a frame of the video. Ids tried are remembered, so a file that has no
   * picture is not opened again on every start.
   */
  private async backfillArt() {
    const triedFile = path.join(path.dirname(this.options.artDir), 'library-art-tried.json')
    const tried = new Set<string>(await fs.readFile(triedFile, 'utf8').then((text) => JSON.parse(text) as string[], () => []))
    const wanted = [...this.entries.values()].filter((entry) => entry.kind !== 'image' && !entry.hasArt && !entry.missing && !tried.has(entry.id)).map((entry) => entry.id)
    if (!wanted.length) return
    const jobs = this.options.jobs?.() ?? []
    const byFile = new Map(jobs.filter((job) => job.file).map((job) => [keyOf(job.file!), job]))
    let unsaved = 0
    const save = () => fs.writeFile(triedFile, JSON.stringify([...tried])).catch(() => undefined)
    for (const id of wanted) {
      if (this.closing) break
      const entry = this.entries.get(id)
      if (!entry || entry.hasArt) continue
      const job = (entry.jobId ? jobs.find((item) => item.id === entry.jobId) : undefined) ?? byFile.get(keyOf(entry.path))
      if (job && !this.artRequests.has(id)) this.artRequests.set(id, {id: job.id, thumbnail: job.thumbnail, file: entry.path})
      if (entry.kind === 'audio') {
        const picture = (await readTags(entry.path, true).catch(() => undefined))?.picture
        if (picture) entry.hasArt = await this.putArt(id, picture.data, picture.format)
      }
      if (!entry.hasArt) await this.enrichment.makePoster(entry, this.options.ffmpeg()).catch(() => undefined)
      this.artRequests.delete(id)
      tried.add(id)
      if (entry.hasArt) this.changed(false, [id], false)
      if (++unsaved >= 20) {
        unsaved = 0
        await save()
      }
    }
    await save()
  }

  /** Saves an item's art and tells the store, so the cover shows wherever the item is listed. */
  private async putArt(id: string, bytes: Uint8Array, mime?: string): Promise<boolean> {
    const saved = await this.art.put(id, bytes, mime)
    if (saved) this.options.persistence?.noteArt?.(id, true)
    return saved
  }

  /** The same as applyPersisted() for the first load, in slices that give the thread back (nothing can be edited yet). */
  private async applyPersistedInSlices(data: PersistedLibrary) {
    const slicer = new Slicer()
    for (let i = 0; i < data.items.length; i++) {
      const entry = data.items[i]
      this.entries.set(entry.id, entry)
      this.byPath.set(keyOf(entry.path), entry.id)
      if ((i & 31) === 31) await slicer.tick()
    }
    await slicer.yield()
    for (const collection of data.collections) this.collections.set(collection.id, collection)
    for (const collection of this.collections.values()) {
      const known = (ids: string[]) => ids.filter((id) => this.entries.has(id))
      collection.itemIds = known(collection.itemIds)
      if (collection.newItemIds) collection.newItemIds = known(collection.newItemIds)
      if (collection.removedItemIds) collection.removedItemIds = known(collection.removedItemIds)
    }
    this.tables = undefined
  }

  /** Replaces the in-memory model with the store's. Entries that only exist in memory (listed by a scan, not managed) stay. */
  private applyPersisted(data: PersistedLibrary) {
    this.pushAll = true
    const unmanaged = [...this.entries.values()].filter((entry) => !entry.managed)
    this.entries.clear()
    this.byPath.clear()
    this.collections.clear()
    for (const entry of data.items) {
      this.entries.set(entry.id, entry)
      this.byPath.set(keyOf(entry.path), entry.id)
    }
    for (const entry of unmanaged) {
      if (this.entries.has(entry.id) || this.byPath.has(keyOf(entry.path))) continue
      this.entries.set(entry.id, entry)
      this.byPath.set(keyOf(entry.path), entry.id)
    }
    for (const collection of data.collections) this.collections.set(collection.id, collection)
    // Collections never keep ids of items that are not in the library.
    for (const collection of this.collections.values()) {
      const known = (ids: string[]) => ids.filter((id) => this.entries.has(id))
      collection.itemIds = known(collection.itemIds)
      if (collection.newItemIds) collection.newItemIds = known(collection.newItemIds)
      if (collection.removedItemIds) collection.removedItemIds = known(collection.removedItemIds)
    }
    this.tables = undefined
  }

  /** Puts changed entries and collections in and takes removed ones out; everything else stays as it is. */
  private applyPersistedDelta(delta: PersistedDelta) {
    for (const id of delta.removedItems) this.pushIds.add(id)
    for (const entry of delta.items) this.pushIds.add(entry.id)
    if (delta.collections.length || delta.removedCollections.length) this.pushCollections = true
    for (const id of delta.removedItems) {
      const old = this.entries.get(id)
      if (!old) continue
      this.entries.delete(id)
      this.byPath.delete(keyOf(old.path))
      this.lyricsText.delete(id)
    }
    for (const entry of delta.items) {
      const old = this.entries.get(entry.id)
      if (old) this.byPath.delete(keyOf(old.path))
      this.entries.set(entry.id, entry)
      this.byPath.set(keyOf(entry.path), entry.id)
    }
    for (const id of delta.removedCollections) this.collections.delete(id)
    for (const collection of delta.collections) this.collections.set(collection.id, collection)
    const known = (ids: string[]) => ids.filter((id) => this.entries.has(id))
    const prune = (collection: Collection) => {
      collection.itemIds = known(collection.itemIds)
      if (collection.newItemIds) collection.newItemIds = known(collection.newItemIds)
      if (collection.removedItemIds) collection.removedItemIds = known(collection.removedItemIds)
    }
    for (const collection of delta.collections) prune(collection)
    this.tables = undefined
  }

  /**
   * Another device's changes arrived. Local edits are saved first (so they are part of the store), then the store's
   * projection is fetched, and applied in one synchronous step only if nothing was edited while it was fetched; otherwise
   * the edit is saved and the same keys are fetched again. A fetch taken before a save would undo that save.
   */
  private async reproject() {
    const persistence = this.options.persistence
    if (!persistence) return
    let fetched: unknown = this.unapplied
    this.unapplied = undefined
    for (let attempt = 0; attempt < 5 && !this.closing; attempt++) {
      await this.flush()
      const seen = this.version
      fetched = await persistence.fetchRemote(fetched)
      if (this.version !== seen) continue
      const applied = persistence.applyRemote(fetched)
      if ('removedItems' in applied) {
        this.applyPersistedDelta(applied)
        this.changed(false)
        return
      }
      const data = applied
      this.applyPersisted(data)
      this.pushAll = true
      if (data.spaces) this.stored = {spaces: data.spaces, routes: data.routes ?? this.stored?.routes}
      if (data.spaces && this.settings) {
        this.settings = {...this.settings, spaces: data.spaces, routes: data.routes ?? this.settings.routes}
        this.scanner.syncWatchers()
        this.hooks.spacesChanged?.(data.spaces, this.settings.routes)
      }
      this.changed(false)
      return
    }
    // Edits kept arriving: keep what was fetched and try again shortly, so no remote change is skipped.
    if (this.closing) return
    this.unapplied = fetched
    setTimeout(() => {
      this.remoteChain = this.remoteChain.then(() => this.reproject()).catch((error) => console.error('Could not apply changes from other devices:', error))
    }, 250).unref?.()
  }
  private unapplied: unknown

  async close() {
    this.unsubscribeRemote?.()
    this.closing = true
    this.touchedAll = true // the last save compares everything, hints or not
    clearTimeout(this.pushTimer)
    this.enrichment.stop()
    this.scanner.stopWatchers()
    this.cancelController?.abort()
    await this.flush()
  }

  /** Writes library.json now (and waits for any write in flight). */
  async flush() {
    clearTimeout(this.saveTimer)
    this.saveTimer = undefined
    await this.write()
  }

  subscribe(listener: (snapshot: LibrarySnapshot) => void) {
    this.listeners.add(listener)
    return () => void this.listeners.delete(listener)
  }

  /** Resolves when adoption of finished jobs and metadata, art and loudness work queued so far is finished (tests, smoke checks). */
  async settled(timeoutMs = 20_000) {
    const deadline = Date.now() + timeoutMs
    while (this.busy() && Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 20))
  }
  private busy() {
    return this.enrichment.working() || !!this.scanner.scanning || this.adopting > 0
  }

  // ------------------------------------------------------------------ settings

  /** Applies the library settings: re-attaches orphaned items, starts or stops folder watching, and adopts history. */
  configure(settings: LibrarySettings) {
    const wasEnabled = this.settings?.enabled
    const normalizeChanged = this.settings?.normalize !== settings.normalize
    this.settings = structuredClone(settings)
    const spaces = new Map(folderSpaces(settings.spaces).map((space) => [space.id, space]))
    const fallback = folderSpaces(settings.spaces)[0]?.id
    for (const collection of this.collections.values())
      if (fallback && !spaces.has(collection.space)) collection.space = spaces.has(settings.routes.music) ? settings.routes.music : fallback
    for (const entry of this.entries.values()) {
      if (spaces.has(entry.space)) continue
      // Items of a removed space come back when a space covers their folder again.
      const home = this.spaceOf(entry.path)
      if (home) {
        entry.space = home.id
        entry.folder = relativeFolder(home, entry.path)
      }
    }
    this.tables = undefined
    this.changed()
    if (!settings.enabled) {
      this.scanner.stopWatchers()
      return
    }
    this.scanner.syncWatchers()
    if (!wasEnabled) void this.scan()
    else if (normalizeChanged && settings.normalize) this.enrichment.queueLoudnessForAll()
  }

  get enabled() {
    return !!this.settings?.enabled
  }
  /** The spaces that hold files. Smart spaces gather items by rules and own none. */
  spaces(): Space[] {
    return folderSpaces(this.settings?.spaces ?? [])
  }
  space(id: string | undefined): Space | undefined {
    return id ? this.spaces().find((space) => space.id === id) : undefined
  }
  /** The deepest space whose folder contains the file. */
  spaceOf(file: string): Space | undefined {
    let best: Space | undefined
    for (const space of this.spaces())
      if (isInside(space.folder, file) && (!best || space.folder.length > best.folder.length)) best = space
    return best
  }

  // ------------------------------------------------------------------ snapshot & events

  private visible(entry: LibraryEntry) {
    if (!this.settings?.enabled) return false
    const space = this.space(entry.space)
    return !!space && (entry.managed || !!space.scanFolder)
  }

  snapshot(): LibrarySnapshot {
    const items: LibraryItem[] = []
    for (const entry of this.entries.values()) if (this.visible(entry)) items.push(publicItem(entry))
    return {
      items,
      collections: [...this.collections.values()].map((collection) => structuredClone(collection)),
      scanning: !!this.scanner.scanning,
      scannedAt: this.scanner.scannedAt,
      problems: [...this.scanner.problems].map(([space, message]) => ({space, message})),
      operation: this.operation ? {...this.operation} : undefined,
    }
  }

  /** Marks the snapshot dirty: one debounced push (≤ 4 Hz) and one debounced save. */
  changed(persist = true, items?: readonly string[], collections = true) {
    this.version++
    // For the window: hinted ids go out as a delta; an unhinted change that persists has unknown extent and goes out whole;
    // a change that does not persist is metadata only (scanning, operation, problems), compared at push time.
    if (items) {
      for (const id of items) this.pushIds.add(id)
      if (collections) this.pushCollections = true
    } else if (persist) this.pushAll = true
    // Store mode: callers that know exactly which entries they changed say so, and the next save looks at those only.
    // Any other change makes the next save compare every entry. (Collections are always compared.)
    if (persist) {
      if (items) for (const id of items) this.touched.add(id)
      else this.touchedAll = true
    }
    if (this.closing) return
    this.schedulePush()
    if (persist) this.scheduleSave()
  }
  private readonly touched = new Set<string>()
  private touchedAll = true

  private schedulePush() {
    if (this.pushTimer) return
    const gap = this.options.pushDelay ?? 250
    const wait = Math.max(0, this.lastPush + gap - Date.now())
    this.pushTimer = setTimeout(() => {
      this.pushTimer = undefined
      if (this.version === this.pushed) return
      this.pushed = this.version
      this.lastPush = Date.now()
      if (this.listeners.size) {
        const snapshot = this.snapshot()
        for (const listener of this.listeners) {
          try {
            listener(snapshot)
          } catch (error) {
            console.error('Library observer failed:', error)
          }
        }
      }
      this.flushPush()
    }, wait)
  }

  // ------------------------------------------------------------------ pushes to the window

  /** Receives library pushes (deltas, whole libraries, batches). Returns the unsubscribe function. */
  subscribePush(listener: (event: LibraryPushEvent) => void) {
    this.pushListeners.add(listener)
    return () => void this.pushListeners.delete(listener)
  }

  private emitPush(event: LibraryPushEvent) {
    for (const listener of this.pushListeners) {
      try {
        listener(event)
      } catch (error) {
        console.error('Library push observer failed:', error)
      }
    }
  }

  private meta(): LibraryMeta {
    return {scanning: !!this.scanner.scanning, scannedAt: this.scanner.scannedAt, problems: [...this.scanner.problems].map(([space, message]) => ({space, message})), operation: this.operation ? {...this.operation} : undefined}
  }

  private visibleCount() {
    let count = 0
    for (const entry of this.entries.values()) if (this.visible(entry)) count++
    return count
  }

  /** Sends what changed since the last push: a delta, or the whole library when the extent is unknown. */
  private flushPush() {
    if (!this.pushListeners.size) {
      this.pushIds.clear()
      this.pushCollections = false
      this.pushAll = true // nobody has seen anything yet: the first listener gets it whole
      return
    }
    if (this.sending) {
      this.pushed = -1 // a batched send is running: this push waits for it
      return
    }
    if (this.pushAll) {
      this.pushAll = false
      this.pushIds.clear()
      this.pushCollections = false
      this.sendFull()
      return
    }
    const items: LibraryItem[] = []
    const removedItems: string[] = []
    for (const id of this.pushIds) {
      const entry = this.entries.get(id)
      if (entry && this.visible(entry)) items.push(publicItem(entry))
      else removedItems.push(id)
    }
    this.pushIds.clear()
    let collections: Collection[] | undefined
    let removedCollections: string[] | undefined
    if (this.pushCollections) {
      this.pushCollections = false
      const changedList: Collection[] = []
      const seen = new Set<string>()
      for (const collection of this.collections.values()) {
        seen.add(collection.id)
        const signature = JSON.stringify(collection)
        if (this.sentCollections.get(collection.id) === signature) continue
        this.sentCollections.set(collection.id, signature)
        changedList.push(structuredClone(collection))
      }
      const gone = [...this.sentCollections.keys()].filter((id) => !seen.has(id))
      for (const id of gone) this.sentCollections.delete(id)
      if (changedList.length) collections = changedList
      if (gone.length) removedCollections = gone
    }
    const meta = this.meta()
    const signature = JSON.stringify(meta)
    const metaChanged = signature !== this.sentMeta
    if (!items.length && !removedItems.length && !collections && !removedCollections && !metaChanged) return
    this.sentMeta = signature
    const base = this.pushSeq
    const delta: LibraryDeltaPush = {type: 'libraryDelta', seq: ++this.pushSeq, base}
    if (items.length) delta.items = items
    if (removedItems.length) delta.removedItems = removedItems
    if (collections) delta.collections = collections
    if (removedCollections) delta.removedCollections = removedCollections
    if (metaChanged) delta.meta = meta
    this.emitPush(delta)
  }

  private noteSent() {
    this.sentCollections.clear()
    for (const collection of this.collections.values()) this.sentCollections.set(collection.id, JSON.stringify(collection))
    this.sentMeta = JSON.stringify(this.meta())
  }

  /** The whole library: in one push when it is small, in batches that give the thread back when it is big. */
  private sendFull() {
    const seq = ++this.pushSeq
    if (this.visibleCount() <= (this.options.pushBatch ?? PUSH_BATCH)) {
      this.noteSent()
      this.emitPush({type: 'library', library: {...this.snapshot(), seq}})
      return
    }
    void this.sendBatches(seq)
  }

  private async sendBatches(seq: number) {
    this.sending = true
    try {
      const size = this.options.pushBatch ?? PUSH_BATCH
      const total = Math.ceil(this.visibleCount() / size)
      let index = 0
      let batch: LibraryItem[] = []
      const send = async (last: boolean) => {
        const event: LibraryBatchPush = {type: 'libraryBatch', seq, index, total, items: batch}
        if (index === 0) {
          event.collections = [...this.collections.values()].map((collection) => structuredClone(collection))
          event.meta = this.meta()
          this.noteSent()
        }
        index++
        batch = []
        this.emitPush(event)
        if (!last) await new Promise((resolve) => setImmediate(resolve))
      }
      for (const entry of [...this.entries.values()]) {
        if (this.closing) return
        if (!this.visible(entry)) continue
        batch.push(publicItem(entry))
        if (batch.length >= size && index < total - 1) await send(false)
      }
      while (index < total) await send(index === total - 1)
    } finally {
      this.sending = false
      // What changed while the batches went out follows as a delta on top of them.
      if (this.pushIds.size || this.pushCollections || this.pushAll) this.schedulePush()
    }
  }

  /**
   * The answer to libraryState, libraryResync and libraryScan: the whole library when it is small; otherwise a marker
   * (`batched`) and the library follows as batches. Either way `seq` is the push sequence the answer corresponds to.
   */
  stateResponse(): LibrarySnapshot {
    if (this.visibleCount() <= (this.options.pushBatch ?? PUSH_BATCH) || !this.pushListeners.size) return {...this.snapshot(), seq: this.pushSeq}
    this.pushAll = false
    this.pushIds.clear()
    this.pushCollections = false
    const seq = ++this.pushSeq
    void this.sendBatches(seq)
    return {items: [], collections: [], scanning: !!this.scanner.scanning, problems: [], seq, batched: true}
  }

  private scheduleSave() {
    if (this.saveTimer) return
    this.saveTimer = setTimeout(() => {
      this.saveTimer = undefined
      void this.write().catch((error) => console.error('Could not persist the library:', error))
    }, this.options.saveDelay ?? 1000)
  }

  private write(): Promise<void> {
    const persistence = this.options.persistence
    if (persistence) {
      // Store mode: the store compares with what it last saved and writes only the changed operations.
      const hinted = !this.touchedAll
      const touched = [...this.touched]
      this.touched.clear()
      this.touchedAll = false
      const job = persistence.save({
        items: hinted ? [] : [...this.entries.values()],
        collections: [...this.collections.values()],
        spaces: this.settings?.spaces,
        routes: this.settings?.routes,
        touched: hinted ? {ids: touched, entry: (id) => this.entries.get(id), all: () => [...this.entries.values()]} : undefined,
      })
      this.saveChain = job.catch(() => {})
      return job
    }
    const data = JSON.stringify({version: 2, items: [...this.entries.values()], collections: [...this.collections.values()]})
    const job = this.saveChain.then(async () => {
      await fs.mkdir(path.dirname(this.options.file), {recursive: true})
      // A name of its own per write: two index instances on one file (tests, a reopened window) never share a temp file.
      const temporary = `${this.options.file}.${process.pid}.${++tempCounter}.tmp`
      await fs.writeFile(temporary, data)
      await renameWithRetry(temporary, this.options.file)
    })
    this.saveChain = job.catch(() => {})
    return job
  }

  // ------------------------------------------------------------------ entries

  item(id: unknown): LibraryEntry {
    const entry = typeof id === 'string' ? this.entries.get(id) : undefined
    if (!entry || !this.visible(entry)) throw new Error('That item is no longer in your library.')
    return entry
  }
  /** The entry for an id when it is part of the visible library (protocol lookups); never throws. */
  lookup(id: string): LibraryEntry | undefined {
    const entry = this.entries.get(id)
    return entry && this.visible(entry) ? entry : undefined
  }
  entryAt(file: string): LibraryEntry | undefined {
    const id = this.byPath.get(keyOf(file))
    return id ? this.entries.get(id) : undefined
  }
  itemsOf(spaceId: string): LibraryEntry[] {
    return [...this.entries.values()].filter((entry) => entry.space === spaceId && this.visible(entry))
  }
  all(): LibraryEntry[] {
    return [...this.entries.values()].filter((entry) => this.visible(entry))
  }

  /** Registers one media file. Managed files stay in the library; unmanaged ones only show in "show other files" spaces. */
  async addFile(
    file: string,
    options: {space: string; managed: boolean; job?: Job; addedAt?: number; sourceUrl?: string},
  ): Promise<LibraryEntry | undefined> {
    const kind = mediaKindOf(file)
    if (!kind) return
    const stat = await fs.stat(file).catch(() => undefined)
    if (!stat?.isFile()) return
    const known = this.entryAt(file)
    // a song's music video is carried by the song, not an item of its own: the song looks beside it again
    if (!known && (await isMusicVideoFile(file))) {
      const song = this.songOf(file)
      if (song) this.queueEnrich(song.id)
      return
    }
    if (known) {
      let touched = false
      if (options.managed && !known.managed) {
        known.managed = true
        touched = true
      }
      if (known.space !== options.space && this.space(options.space)) {
        known.space = options.space
        touched = true
      }
      if (options.job && !known.jobId) {
        this.applyJob(known, options.job)
        this.placeJob(known, options.job)
        touched = true
      }
      if (known.missing) {
        known.missing = undefined
        touched = true
      }
      if (touched) {
        this.tables = undefined
        this.changed(true, [known.id])
      }
      if (known.sig !== `${stat.size}:${Math.round(stat.mtimeMs)}`) this.queueEnrich(known.id)
      return known
    }
    const space = this.space(options.space) ?? this.spaceOf(file)
    // Store mode: items have UUIDs that other devices know; otherwise the id comes from the path.
    const id = this.options.persistence ? this.newId() : itemIdFor(file)
    const entry: LibraryEntry = {
      id,
      space: space?.id ?? options.space,
      path: path.normalize(file),
      folder: space ? relativeFolder(space, file) : '',
      kind,
      title: titleFromName(file),
      size: stat.size,
      modifiedAt: Math.round(stat.mtimeMs),
      addedAt: options.addedAt ?? this.now(),
      hasArt: false,
      managed: options.managed,
    }
    if (options.sourceUrl) {
      entry.sourceUrl = options.sourceUrl
      entry.sourceId = sourceIdFromUrl(options.sourceUrl)
    }
    if (options.job) this.applyJob(entry, options.job)
    this.entries.set(id, entry)
    this.byPath.set(keyOf(entry.path), id)
    if (options.job) this.placeJob(entry, options.job)
    this.tables = undefined
    this.changed(true, [id])
    this.queueEnrich(id)
    return entry
  }

  /** What the download itself knew, before the file's own tags are read. */
  private applyJob(entry: LibraryEntry, job: Job) {
    const {music} = job
    entry.jobId = job.id
    entry.sourceUrl = job.url
    entry.sourceId = sourceIdFromUrl(job.url)
    entry.title = music?.track || job.title || entry.title
    entry.artist ??= music?.artist ?? channelArtist(job.uploader)
    entry.album ??= music?.album
    entry.year ??= music?.releaseYear
    entry.track ??= music?.trackNumber
    entry.duration ??= job.duration
    this.artRequests.set(entry.id, {id: job.id, thumbnail: job.thumbnail, file: entry.path})
    if (this.settings?.importTags !== false) {
      const tags = normalizeTags(job.tags)
      if (tags.length) entry.tags = tags
      else entry.tagsPending = true
    }
  }

  /** A finished download joins its collection at its place in the source. */
  private placeJob(entry: LibraryEntry, job: Job) {
    const id = job.collectionId ?? (job.group && this.collections.has(job.group.id) ? job.group.id : undefined)
    const collection = id ? this.collections.get(id) : undefined
    if (!collection) return
    if (job.collectionIndex !== undefined) entry.colIndex = job.collectionIndex
    if (collection.itemIds.includes(entry.id)) return
    collection.itemIds = sortBySourceOrder([...collection.itemIds, entry.id], (itemId) => this.entries.get(itemId)?.colIndex)
    if (job.followed) collection.newItemIds = [...(collection.newItemIds ?? []), entry.id]
    collection.updatedAt = this.now()
  }

  private spaceForJob(job: Job, file: string): string | undefined {
    if (job.space && this.space(job.space)) return job.space
    return this.spaceOf(file)?.id ?? (this.settings ? defaultSpaceFor(job.url, this.settings) : undefined)
  }

  /** Adds a completed job to the library: the saving steps of the after-download plan (the queue runs the others). */
  async adoptJob(job: Job) {
    if (!this.settings?.enabled || job.status !== 'completed') return
    const {facts, items, steps} = await this.savingPlan(job)
    await runAfterDownload(facts, steps, {only: [...SAVING, 'settleVideos'], items})
  }

  /** The library's part of a finished job's follow-up steps (desktop/afterDownload.ts): the plan's facts and the saving steps. `existing` is the recording already there; with the library off every step does nothing. */
  async savingPlan(job: Job, existing = job.existing) {
    const song = this.settings?.enabled && job.attachVideo && !existing ? await this.songOfVideo(job.attachVideo) : undefined
    const facts: AfterFacts = {
      audio: job.kind === 'audio', hasFile: !!job.file && !existing, existing: !!existing, playlist: !!job.playlist,
      video: !job.attachVideo || existing ? 'none' : song === 'wait' ? 'wait' : song ? 'attach' : 'ordinary',
    }
    if (!this.settings?.enabled) return {facts, items: [], steps: noSaving}
    const attach = typeof song === 'string' && song !== 'wait' ? song : undefined,
      had = existing && (existing.itemId ?? this.entryAt(existing.path)?.id)
    return {facts, items: had ? [had] : [], steps: {
      ...noSaving,
      save: () => this.saveJobFiles(job),
      attachVideo: async () => (attach && job.file && (await this.attachMusicVideo(attach, job.file)) ? [attach] : undefined),
      joinPlaylist: async (ids: string[]) => this.joinPlaylist(job, ids),
      settleVideos: () => this.adoptWaitingVideos(job),
    }}
  }

  /** Adds a finished job's media files (a gallery's images too) and returns their items. Throws when a file is gone or cannot be added. */
  private async saveJobFiles(job: Job): Promise<string[]> {
    this.adopting++
    try {
      // a photo post saves file by file: one failure never loses the others, and Save again adds only what is missing
      return await addEach(job.format.type === 'gallery', new Set([job.file, ...(job.files ?? [])]), async (file) => {
        const kind = file && mediaKindOf(file)
        const space = file && kind && path.isAbsolute(file) && (job.format.type === 'gallery' || kind !== 'image') ? this.spaceForJob(job, file) : undefined
        if (!file || !space) return
        if (!(await fs.stat(file).catch(() => undefined))) throw new Error('The downloaded file is no longer there.')
        return (await this.addFile(file, {space, managed: true, job, addedAt: job.completedAt}))?.id
      })
    } finally {
      this.adopting--
    }
  }

  /** "Add to playlist" from the review: the items a finished download made (or already was) join that playlist, in order. A playlist deleted since is skipped. */
  private joinPlaylist(job: Job, ids: string[]) {
    const playlist = job.playlist ? this.collections.get(job.playlist) : undefined
    if (playlist?.kind === 'own' && ids.length) this.collectionsPart.addToCollection(playlist.id, ids)
  }

  /** The music videos that finished before the song [job] and were waiting for it. */
  private async adoptWaitingVideos(job: Job) {
    for (const video of this.options.jobs?.() ?? []) if (video.attachVideo?.jobId === job.id && video.status === 'completed') await this.adoptJob(video)
  }

  /** The library song a music video job belongs to: its item id, wait while the song is a download still to be adopted, undefined when there is none. */
  private async songOfVideo({itemId, jobId}: NonNullable<Job['attachVideo']>): Promise<string | 'wait' | undefined> {
    if (itemId || !jobId) return itemId
    // [jobs] lists the finished downloads: a song that is not among them is still on its way, or failed, or was cancelled
    const song = this.options.jobs?.().find((item) => item.id === jobId)
    if (!song) return this.options.songPending?.(jobId) ? 'wait' : undefined
    const entry = (song.file ? this.entryAt(song.file) : undefined) ?? [...this.entries.values()].find((item) => item.jobId === jobId)
    if (entry) return entry.id
    // the song was already in the library: the video goes beside that one
    if (song.existing) return song.existing.itemId && this.entries.has(song.existing.itemId) ? song.existing.itemId : undefined
    // finished but not in the library yet: it is being adopted while its file is there; a song deleted since lets its video go
    return song.file && (await fs.stat(song.file).catch(() => undefined)) ? 'wait' : undefined
  }

  async syncJobs() {
    const jobs = this.options.jobs?.() ?? []
    // Collections migrated from the interim model learn their source link from the job that downloaded them.
    let learned = false
    for (const collection of [...this.collections.values()]) {
      if (collection.source || collection.kind === 'own') continue
      const group = jobs.find((job) => job.group?.id === collection.id)?.group
      if (!group) continue
      const kind: CollectionKind = group.kind === 'album' || group.kind === 'channel' ? group.kind : 'playlist'
      const canonical = canonicalSourceUrl(group.url)
      const twin = [...this.collections.values()].find((other) => other !== collection && other.source?.url === canonical)
      if (twin) {
        twin.itemIds = sortBySourceOrder([...new Set([...twin.itemIds, ...collection.itemIds])], (id) => this.entries.get(id)?.colIndex)
        twin.seen = [...new Set([...twin.seen, ...collection.seen])]
        this.collections.delete(collection.id)
      } else {
        collection.kind = kind
        collection.source = {url: canonical, provider: providerName(group.url), id: sourceIdOfCollection(group.url)}
        collection.follow = {...defaultFollow(kind, group.url), mode: 'off'}
      }
      learned = true
    }
    // Items adopted before collections were recorded learn their place from the job that downloaded them.
    const byId = new Map(jobs.map((job) => [job.id, job]))
    for (const entry of this.entries.values()) {
      const job = entry.jobId ? byId.get(entry.jobId) : undefined
      if (!job || entry.colIndex !== undefined) continue
      if (job.collectionIndex !== undefined) {
        entry.colIndex = job.collectionIndex
        learned = true
      }
    }
    if (learned) {
      this.tables = undefined
      this.changed()
    }
    for (const job of jobs) {
      if (job.status === 'completed' && job.file && !job.existing && !this.entryAt(job.file)) await this.adoptJob(job)
      if (this.closing) return
    }
  }

  /** Drops entries (and their art) from the library. Files on disk are untouched. */
  drop(ids: string[], options: {forget?: boolean} = {}) {
    const paths: string[] = []
    for (const id of ids) {
      const entry = this.entries.get(id)
      if (!entry) continue
      this.entries.delete(id)
      this.byPath.delete(keyOf(entry.path))
      this.lyricsText.delete(id)
      this.artRequests.delete(id)
      paths.push(entry.path)
      void this.art.remove(id)
      this.options.persistence?.noteArt?.(id, false)
    }
    if (!paths.length) return
    const gone = new Set(ids)
    for (const collection of this.collections.values()) {
      const kept = collection.itemIds.filter((id) => !gone.has(id))
      if (kept.length !== collection.itemIds.length) {
        collection.itemIds = kept
        collection.updatedAt = this.now()
      }
      if (collection.newItemIds) collection.newItemIds = collection.newItemIds.filter((id) => !gone.has(id))
      if (collection.removedItemIds) collection.removedItemIds = collection.removedItemIds.filter((id) => !gone.has(id))
    }
    this.tables = undefined
    this.changed(true, ids)
    if (options.forget) this.hooks.forgotten(paths)
  }

  /** Where each of these items stands in the collections that hold them, taken before they are dropped (for Undo of Delete). */
  memberships(ids: readonly string[]): CollectionMembership[] {
    const wanted = new Set(ids)
    const out: CollectionMembership[] = []
    for (const collection of this.collections.values()) {
      if (!collection.itemIds.some((id) => wanted.has(id)) && !collection.newItemIds?.some((id) => wanted.has(id)) && !collection.removedItemIds?.some((id) => wanted.has(id))) continue
      out.push({
        id: collection.id,
        itemIds: [...collection.itemIds],
        newItemIds: collection.newItemIds && [...collection.newItemIds],
        removedItemIds: collection.removedItemIds && [...collection.removedItemIds],
      })
    }
    return out
  }

  /**
   * Puts an item back after an Undo of Delete: the same id (so what other devices hold for it lines up), with its tags, favourite and
   * plays as they were, in the collections it was in at the places it had. A collection changed since keeps its changes.
   */
  restoreEntry(entry: LibraryEntry, memberships: readonly CollectionMembership[], stat: {size: number; mtimeMs: number}): LibraryEntry {
    if (this.entries.has(entry.id) || this.byPath.has(keyOf(entry.path))) throw new Error('That item is already back in the library.')
    const restored: LibraryEntry = {...structuredClone(entry), missing: undefined, sig: undefined, size: stat.size, modifiedAt: Math.round(stat.mtimeMs)}
    this.entries.set(restored.id, restored)
    this.byPath.set(keyOf(restored.path), restored.id)
    for (const held of memberships) {
      const collection = this.collections.get(held.id)
      if (!collection) continue
      if (held.itemIds.includes(restored.id)) collection.itemIds = restoreOrder(held.itemIds, collection.itemIds, new Set([restored.id]))
      if (held.newItemIds?.includes(restored.id) && !collection.newItemIds?.includes(restored.id)) collection.newItemIds = [...(collection.newItemIds ?? []), restored.id]
      if (held.removedItemIds?.includes(restored.id) && !collection.removedItemIds?.includes(restored.id)) collection.removedItemIds = [...(collection.removedItemIds ?? []), restored.id]
      collection.updatedAt = this.now()
    }
    this.tables = undefined
    this.changed(true, [restored.id])
    return restored
  }

  /** Forgets entries for files that were deleted by history actions. */
  forgetPaths(files: string[]) {
    const ids = files.map((file) => this.byPath.get(keyOf(file))).filter((id): id is string => !!id)
    if (ids.length) this.drop(ids)
  }

  /** Re-keys an entry after its file moved or was renamed. State (favorites, plays, playlists, art) follows. */
  relocate(id: string, newPath: string, stat?: {size: number; mtimeMs: number}): LibraryEntry {
    const entry = this.entries.get(id)
    if (!entry) throw new Error('That item is no longer in your library.')
    const from = entry.path
    // Store mode keeps the item's UUID (favourites, plays and playlists on other devices follow it); only the path changes.
    const newId = this.options.persistence ? id : itemIdFor(newPath)
    const clash = this.options.persistence ? this.entryAt(newPath) : this.entries.get(newId)
    if (clash && clash !== entry) this.drop([clash.id])
    // Store mode keeps the id: the entry stays where it is in the order (a delete and re-add would move it to the end).
    if (newId !== id) this.entries.delete(id)
    this.byPath.delete(keyOf(from))
    entry.id = newId
    entry.path = path.normalize(newPath)
    const space = this.spaceOf(newPath) ?? this.space(entry.space)
    if (space) {
      entry.space = space.id
      entry.folder = relativeFolder(space, newPath)
    }
    if (stat) {
      entry.size = stat.size
      entry.modifiedAt = Math.round(stat.mtimeMs)
    }
    entry.missing = undefined
    entry.sig = undefined
    this.entries.set(newId, entry)
    this.byPath.set(keyOf(entry.path), newId)
    if (newId !== id) {
      const swap = (ids: string[]) => ids.map((item) => (item === id ? newId : item))
      for (const collection of this.collections.values()) {
        if (collection.itemIds.includes(id)) collection.itemIds = swap(collection.itemIds)
        if (collection.newItemIds?.includes(id)) collection.newItemIds = swap(collection.newItemIds)
        if (collection.removedItemIds?.includes(id)) collection.removedItemIds = swap(collection.removedItemIds)
        if (collection.coverItemId === id) collection.coverItemId = newId
      }
      void this.art.rename(id, newId)
      const cached = this.lyricsText.get(id)
      if (cached) {
        this.lyricsText.delete(id)
        this.lyricsText.set(newId, cached)
      }
    }
    this.tables = undefined
    this.changed(true, [newId])
    this.queueEnrich(newId)
    this.hooks.relocated(from, entry.path)
    return entry
  }

  // ------------------------------------------------------------------ files whose saved name was damaged

  /** An item that opens as missing: its file is looked for under its real name once, before the user is told it is gone. */
  relinkItem(id: unknown): Promise<LibraryEntry | undefined> {
    return this.relinker.relinkItem(id)
  }

  // ------------------------------------------------------------------ playback state, favorites, tags, collections

  recordPlayback(id: unknown, position: unknown, finished: unknown) {
    const entry = this.item(id)
    const at = typeof position === 'number' && Number.isFinite(position) ? Math.max(0, position) : 0
    const done = finished === true
    const duration = entry.duration
    let counted = this.counted.get(entry.id) ?? false
    if (at < 5 && !done) counted = false
    if ((done || at >= 30) && !counted) {
      entry.plays = (entry.plays ?? 0) + 1
      counted = true
    }
    this.counted.set(entry.id, done ? false : counted)
    entry.lastPlayedAt = this.now()
    if (done) {
      entry.position = undefined
      if (entry.kind === 'video') entry.watched = true
    } else {
      entry.position = Math.round(at * 10) / 10
      if (entry.kind === 'video' && duration && at / duration >= 0.92) entry.watched = true
    }
    this.changed(true, [entry.id], false)
  }
  private readonly counted = new Map<string, boolean>()

  setFavorite(ids: unknown, favorite: unknown) {
    const list = Array.isArray(ids) ? ids : [ids]
    if (typeof favorite !== 'boolean') throw new Error('Choose whether to add or remove the favorite.')
    let touched = false
    for (const id of list) {
      const entry = this.item(id)
      if ((entry.favorite ?? false) === favorite) continue
      entry.favorite = favorite || undefined
      touched = true
    }
    if (touched) this.changed(true, list as string[], false)
  }

  /** Adds and removes the user's own tags on items. Tags are normalised like source tags; an item keeps at most 50. */
  setUserTags(ids: unknown, add: unknown, remove: unknown) {
    const list = Array.isArray(ids) ? ids : [ids]
    if (!list.length || list.length > 20_000) throw new Error('Choose items to tag.')
    const adding = normalizeTags(add, 50)
    const removing = normalizeTags(remove, 50)
    if (!adding.length && !removing.length) throw new Error('Type a tag to add or remove.')
    let touched = false
    for (const id of new Set(list)) {
      const entry = this.item(id)
      const current = entry.userTags ?? []
      const next = [...current.filter((tag) => !removing.includes(tag)), ...adding.filter((tag) => !current.includes(tag))].slice(0, 50)
      if (next.length === current.length && next.every((tag, at) => tag === current[at])) continue
      entry.userTags = next.length ? next : undefined
      touched = true
    }
    if (touched) this.changed(true, [...new Set(list)] as string[], false)
  }

  // ------------------------------------------------------------------ collections

  collection(id: unknown): Collection {
    return this.collectionsPart.collection(id)
  }
  collectionList(): Collection[] {
    return this.collectionsPart.collectionList()
  }
  collectionForSource(url: string): Collection | undefined {
    return this.collectionsPart.collectionForSource(url)
  }
  saveCollection(input: {id?: string; name: string; itemIds?: string[]; space?: string}): Collection {
    return this.collectionsPart.saveCollection(input)
  }
  deleteCollection(id: unknown) {
    return this.collectionsPart.deleteCollection(id)
  }
  restoreCollection(saved: Collection): Collection {
    return this.collectionsPart.restoreCollection(saved)
  }
  addToCollection(id: unknown, ids: unknown): Collection {
    return this.collectionsPart.addToCollection(id, ids)
  }
  removeFromCollection(id: unknown, ids: unknown): Collection {
    return this.collectionsPart.removeFromCollection(id, ids)
  }
  reorderCollection(id: unknown, ids: unknown): Collection {
    return this.collectionsPart.reorderCollection(id, ids)
  }
  setFollow(id: unknown, patch: unknown): Collection {
    return this.collectionsPart.setFollow(id, patch)
  }
  ensureCollection(input: {url: string; name?: string; kind: Exclude<CollectionKind, 'own'>; space: string; keepInSyncHours?: CollectionFollow['intervalHours']}): Collection {
    return this.collectionsPart.ensureCollection(input)
  }
  noteSeen(id: unknown, keys: Iterable<string>) {
    return this.collectionsPart.noteSeen(id, keys)
  }
  updateCollection(id: unknown, mutate: (collection: Collection) => void): Collection {
    return this.collectionsPart.updateCollection(id, mutate)
  }
  markOpened(id: unknown) {
    return this.collectionsPart.markOpened(id)
  }
  membersOf(id: unknown): {id: string; sourceId?: string}[] {
    return this.collectionsPart.membersOf(id)
  }
  maxCollectionIndex(id: unknown): number {
    return this.collectionsPart.maxCollectionIndex(id)
  }

  // ------------------------------------------------------------------ duplicate detection

  private lookups() {
    if (this.tables) return this.tables
    const bySource = new Map<string, LibraryEntry[]>()
    const byTitle = new Map<string, LibraryEntry[]>()
    for (const entry of this.entries.values()) {
      if (entry.sourceId) bySource.set(entry.sourceId, [...(bySource.get(entry.sourceId) ?? []), entry])
      const key = normalizeTitle(entry.title, entry.artist ?? entry.albumArtist)
      if (key) byTitle.set(key, [...(byTitle.get(key) ?? []), entry])
    }
    return (this.tables = {bySource, byTitle})
  }

  /** Tier 1 (source id) and tier 3 (artist, title, duration) against one space, or every space when [spaceId] is undefined. */
  findExisting(spaceId: string | undefined, probe: ExistingProbe, only?: LibraryEntry['kind']): ExistingMatch | undefined {
    if (!this.settings?.enabled || (spaceId && !this.space(spaceId))) return
    const {bySource, byTitle} = this.lookups()
    const usable = (entry: LibraryEntry) => (!spaceId || entry.space === spaceId) && (!only || entry.kind === only) && !entry.missing && this.visible(entry)
    const source = probe.sourceId ? bySource.get(probe.sourceId)?.find(usable) : undefined
    if (source) return {itemId: source.id, path: source.path, space: source.space, by: 'source'}
    if (probe.recording) {
      const key = normalizeTitle(probe.recording.title, probe.recording.artist)
      const found = key ? byTitle.get(key)?.find((entry) => usable(entry) && sameRecording(probe.recording!, recordingOf(entry))) : undefined
      if (found) return {itemId: found.id, path: found.path, space: found.space, by: 'metadata'}
    }
  }

  /** Tier 2: the same bytes (size plus partial SHA-256) already in the space. */
  async findByContent(spaceId: string, file: string, size: number): Promise<ExistingMatch | undefined> {
    const candidates = this.itemsOf(spaceId).filter((entry) => entry.size === size && !entry.missing)
    if (!candidates.length) return
    const wanted = await fingerprint(file, size)
    for (const entry of candidates) {
      entry.hash ??= await fingerprint(entry.path, entry.size).catch(() => undefined)
      if (entry.hash === wanted) return {itemId: entry.id, path: entry.path, space: spaceId, by: 'content'}
    }
  }

  // ------------------------------------------------------------------ metadata work, loudness, lyrics

  queueEnrich(id: string) {
    this.enrichment.queueEnrich(id)
  }
  /** Path of the art (cover, poster, or the image itself) served at keepfin-media://art/<id>. */
  artPath(id: string): Promise<string | undefined> {
    return this.enrichment.artPath(id)
  }
  /** Replaces an item's cover (after a tag edit). */
  setArt(id: string, bytes: Uint8Array, mime?: string) {
    return this.enrichment.setArt(id, bytes, mime)
  }
  lyrics(id: unknown): Promise<Lyrics | undefined> {
    return this.enrichment.lyrics(id)
  }
  /** Ids of items whose lyrics contain the text (case- and accent-insensitive). */
  searchLyrics(text: unknown): Promise<string[]> {
    return this.enrichment.searchLyrics(text)
  }

  // ------------------------------------------------------------------ operations (move, import, edit)

  /** Starts a long operation; the returned handle reports progress through the snapshot and carries the cancel signal. */
  beginOperation(kind: LibraryOperation['kind'], total: number) {
    if (this.operation) throw new Error('Wait for the current file operation to finish.')
    const controller = new AbortController()
    this.cancelController = controller
    this.operation = {kind, done: 0, total}
    this.held++
    this.changed(false)
    return {
      signal: controller.signal,
      step: (current?: string) => {
        if (!this.operation) return
        this.operation = {...this.operation, current}
        this.changed(false)
      },
      advance: () => {
        if (!this.operation) return
        this.operation = {...this.operation, done: this.operation.done + 1}
        this.changed(false)
      },
      end: () => {
        this.operation = undefined
        this.cancelController = undefined
        this.held = Math.max(0, this.held - 1)
        this.changed()
        if (!this.held) this.scanner.runDeferred()
      },
    }
  }

  cancelOperation() {
    this.cancelController?.abort()
  }

  /** Holds folder reconciles (a file operation is in flight and would be mistaken for outside changes). */
  hold() {
    this.held++
    return () => {
      this.held = Math.max(0, this.held - 1)
      if (!this.held) this.scanner.runDeferred()
    }
  }

  // ------------------------------------------------------------------ scanning and watching

  /** Full pass: checks managed files, lists new files in "show other files" spaces and adopts history. */
  scan(): Promise<void> {
    return this.scanner.scan()
  }

  /** The song a music video file (`Song.video.mp4`) belongs to: the audio item of that stem in the same folder. */
  private songOf(videoFile: string): LibraryEntry | undefined {
    const stem = videoCompanionStem(path.basename(videoFile))?.toLowerCase()
    if (stem === undefined) return
    const dir = path.dirname(videoFile).toLowerCase()
    for (const entry of this.entries.values())
      if (entry.kind === 'audio' && path.dirname(entry.path).toLowerCase() === dir && path.parse(entry.path).name.toLowerCase() === stem) return entry
  }

  /** The path of a song's music video, for the media protocol; undefined when it has none. */
  videoFile(id: string): string | undefined {
    return this.entries.get(id)?.videoPath
  }

  /** Re-reads one item's tags now (after an edit). */
  async refresh(id: string) {
    const entry = this.entries.get(id)
    if (!entry) return
    entry.sig = undefined
    this.queueEnrich(id)
    await this.settled()
  }

  /**
   * Keeps [file], a finished music video download, beside the song [itemId] as `<song stem>.video.<ext>`. Returns false and
   * touches nothing when it cannot (the song is gone or not audio, the container is not a supported one, a video is already
   * there), so the caller keeps the file as an ordinary download: a file is never lost or overwritten.
   */
  attachMusicVideo(itemId: string, file: string): Promise<boolean> {
    // one at a time: two finished downloads for one song must not both pass the "nothing there yet" check
    const run = this.attaching.then(() => this.attachNow(itemId, file))
    this.attaching = run.catch(() => undefined)
    return run
  }

  private attaching: Promise<unknown> = Promise.resolve()

  private async attachNow(itemId: string, file: string): Promise<boolean> {
    const entry = this.entries.get(itemId)
    const suffix = videoCompanionSuffix(path.extname(file).slice(1).toLowerCase())
    if (!entry || entry.kind !== 'audio' || entry.missing || !suffix || !path.isAbsolute(file)) return false
    if (!(await fs.stat(file).catch(() => undefined))?.isFile()) return false
    const target = path.join(path.dirname(entry.path), path.basename(entry.path, path.extname(entry.path)) + suffix)
    const taken = (name: string) => fs.access(name).then(() => true, () => false)
    // any supported container already beside the song counts as "a video is already there"
    for (const ext of ['mp4', 'webm', 'm4v', 'mov']) if (await taken(path.join(path.dirname(target), path.basename(entry.path, path.extname(entry.path)) + '.video.' + ext))) return false
    try {
      await renameWithRetry(file, target)
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EXDEV') return false
      try {
        await fs.copyFile(file, target, fs.constants.COPYFILE_EXCL)
      } catch (copyError) {
        // only a half-written copy of ours is removed, never a file that was already there
        if ((copyError as NodeJS.ErrnoException).code !== 'EEXIST') await fs.rm(target, {force: true}).catch(() => undefined)
        return false
      }
      await fs.rm(file, {force: true}).catch(() => undefined)
    }
    // a folder scan may have listed the finished download before it was moved here
    const stray = this.entryAt(file)
    if (stray && stray.id !== itemId) this.drop([stray.id])
    this.hooks.relocated(file, target)
    await this.refresh(itemId)
    return true
  }
}
export {isInside, itemIdFor, sanitizeCollection, titleFromName} from './parts/sanitize.js'
