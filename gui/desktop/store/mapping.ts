// Pure mapping between today's library model (LibraryEntry, Collection, Space, routes) and sync operations
// (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md section 3, normative; dev-docs/plans/KEEPFIN-SYNC-SPEC.md).
//
//   diff(before, after, ctx)  -> the operations that turn `before` into `after` (minimal; nothing for unchanged data)
//   project(views, device)    -> the model a SyncViews and this device's data describe
//
// Wire conventions decided here (the Android store must follow them to read the same library):
//   * Sync numbers are integers, so fractional values are scaled: duration, position and chapter starts are
//     milliseconds, gain is milli-decibels. Other numbers are rounded to integers.
//   * `media` carries the item kind (audio, video, image, other). `space` carries the space's wire id.
//   * The built-in space ids (downloads, music, videos) have fixed wire UUIDs (BUILTIN_SPACE_IDS). Any other id that is
//     not a UUID gets a name-derived UUID and the space stores the original in `legacyId`.
//   * The user's own tags are the set `tags:<item>` (sorted when projected), favourites the set `favourites`.
import {createHash} from 'node:crypto'
import path from 'node:path'
import type {Collection, CollectionFollow, CollectionKind, LibraryItemKind, Space} from '../../shared/library.js'
import {keyBetween, type SyncItem, type SyncJson, type SyncObserved, type SyncObservedQuery, type SyncPlaylist, type SyncSong, type SyncSpace, type SyncViews} from '../../shared/core/sync.js'
import type {LibraryEntry} from '../library/index.js'
import {DEVICE_ITEM_KEYS, deviceRecord, placementOf, resolvePlacement, relativeBelow, isBelow, unplacedPath, type DeviceCollection, type DeviceData, type DeviceItem, type SpaceRoot} from './device.js'

export type Routes = {music: string; other: string}
export type Model = {items: LibraryEntry[]; collections: Collection[]; spaces: Space[]; routes: Routes}
export type OpDraft = {kind: string; body: Record<string, SyncJson>}

// ---------------------------------------------------------------------------------------------------------------------
// Ids
// ---------------------------------------------------------------------------------------------------------------------

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export const isUuid = (value: string) => UUID.test(value)

/** Fixed wire ids of the built-in spaces. */
export const BUILTIN_SPACE_IDS: Readonly<Record<string, string>> = {
  downloads: '6b8e1c10-2d3a-4f55-8a01-000000000001',
  music: '6b8e1c10-2d3a-4f55-8a01-000000000002',
  videos: '6b8e1c10-2d3a-4f55-8a01-000000000003',
}
const builtinByWire = new Map(Object.entries(BUILTIN_SPACE_IDS).map(([id, wire]) => [wire, id]))

function uuidFromName(name: string): string {
  const hex = createHash('sha256').update(`keepfin-space:${name}`).digest('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-5${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`
}

/** The id a space has on the wire. */
export function spaceWireId(id: string): string {
  if (isUuid(id)) return id
  return BUILTIN_SPACE_IDS[id] ?? uuidFromName(id)
}

const needsLegacy = (id: string) => !isUuid(id) && !(id in BUILTIN_SPACE_IDS)

// ---------------------------------------------------------------------------------------------------------------------
// Field codecs
// ---------------------------------------------------------------------------------------------------------------------

type Codec = {enc(value: unknown): SyncJson | undefined; dec(value: SyncJson): unknown}

const finite = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value)
const safeInt = (value: number): number | undefined => {
  const rounded = Math.round(value)
  return Number.isSafeInteger(rounded) ? (Object.is(rounded, -0) ? 0 : rounded) : undefined
}

const str: Codec = {enc: (v) => (typeof v === 'string' ? v : undefined), dec: (v) => (typeof v === 'string' ? v : undefined)}
const bool: Codec = {enc: (v) => (typeof v === 'boolean' ? v : undefined), dec: (v) => (typeof v === 'boolean' ? v : undefined)}
const int: Codec = {enc: (v) => (finite(v) ? safeInt(v) : undefined), dec: (v) => (typeof v === 'number' && Number.isSafeInteger(v) ? v : undefined)}
const scaled: Codec = {
  enc: (v) => (finite(v) ? safeInt(v * 1000) : undefined),
  dec: (v) => (typeof v === 'number' && Number.isSafeInteger(v) ? v / 1000 : undefined),
}
const strings: Codec = {
  enc: (v) => (Array.isArray(v) && v.length && v.every((x) => typeof x === 'string') ? (v as string[]) : undefined),
  dec: (v) => (Array.isArray(v) && v.length && v.every((x) => typeof x === 'string') ? (v as string[]) : undefined),
}
const chapters: Codec = {
  enc(v) {
    if (!Array.isArray(v) || !v.length) return undefined
    const out: SyncJson[] = []
    for (const chapter of v as {start?: unknown; title?: unknown}[]) {
      const start = finite(chapter?.start) ? safeInt(chapter.start * 1000) : undefined
      if (start === undefined || typeof chapter.title !== 'string') return undefined
      out.push({start, title: chapter.title})
    }
    return out
  },
  dec(v) {
    if (!Array.isArray(v) || !v.length) return undefined
    const out: {start: number; title: string}[] = []
    for (const chapter of v as {start?: unknown; title?: unknown}[]) {
      if (!chapter || typeof chapter.start !== 'number' || typeof chapter.title !== 'string') return undefined
      out.push({start: chapter.start / 1000, title: chapter.title})
    }
    return out
  },
}

/** The synced metadata fields of an item: wire name, model key, codec. `media` and `space` are handled apart. */
const ITEM_FIELDS: readonly {name: string; key: keyof LibraryEntry; codec: Codec}[] = [
  {name: 'title', key: 'title', codec: str},
  {name: 'artist', key: 'artist', codec: str},
  {name: 'album', key: 'album', codec: str},
  {name: 'albumArtist', key: 'albumArtist', codec: str},
  {name: 'track', key: 'track', codec: int},
  {name: 'disc', key: 'disc', codec: int},
  {name: 'year', key: 'year', codec: int},
  {name: 'genre', key: 'genre', codec: str},
  {name: 'duration', key: 'duration', codec: scaled},
  {name: 'width', key: 'width', codec: int},
  {name: 'height', key: 'height', codec: int},
  {name: 'chapters', key: 'chapters', codec: chapters},
  {name: 'gain', key: 'gain', codec: scaled},
  {name: 'subtitles', key: 'subtitles', codec: strings},
  {name: 'hasLyrics', key: 'hasLyrics', codec: bool},
  {name: 'sourceUrl', key: 'sourceUrl', codec: str},
  {name: 'sourceId', key: 'sourceId', codec: str},
  {name: 'addedAt', key: 'addedAt', codec: int},
  {name: 'tags', key: 'tags', codec: strings},
  {name: 'lastPlayedAt', key: 'lastPlayedAt', codec: int},
  {name: 'position', key: 'position', codec: scaled},
  {name: 'watched', key: 'watched', codec: bool},
]

const PLAYS_PREFIX = 'plays@'
const FAVOURITES = 'favourites'
const KINDS = new Set<string>(['video', 'audio', 'image', 'other'])

// ---------------------------------------------------------------------------------------------------------------------
// Snapshots: a model reduced to what is synced, cheap to compare
// ---------------------------------------------------------------------------------------------------------------------

export type ItemSnap = {
  id: string
  fields: Record<string, SyncJson>
  favorite: boolean
  userTags: string[]
  plays: number
  place: string | null
  key: string
}

export type CollectionSnap = {
  id: string
  fields: Record<string, SyncJson>
  itemIds: string[]
  seen: string[]
  news: string[]
  removed: string[]
  key: string
}

export type SpaceSnap = {id: string; wire: string; fields: Record<string, SyncJson>; key: string}

export type ModelSnap = {
  items: Map<string, ItemSnap>
  collections: Map<string, CollectionSnap>
  spaces: Map<string, SpaceSnap>
  /** The routes as a wire value, or null when unknown. */
  routes: {value: SyncJson; key: string} | null
  /** The synced folder each space had, so a linked space keeps its virtual name. */
  rels: Map<string, string>
  /** Item and collection ids left out because they are not UUIDs. */
  skipped: string[]
}

export type SnapContext = {
  /** The Keepfin folder. */
  root: string
  /** Virtual folder names already given to linked spaces. */
  rels?: ReadonlyMap<string, string>
}

const cleanName = (name: string) => name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim().replace(/^\.+/, '') || 'Space'

/** The space folders as the path mapping sees them; linked spaces keep `prev` names and otherwise get a unique one. */
export function spaceRoots(spaces: readonly Space[], root: string, prev?: ReadonlyMap<string, string>): SpaceRoot[] {
  const out: SpaceRoot[] = []
  const used = new Set<string>()
  const pending: Space[] = []
  for (const space of spaces) {
    if (space.type === 'smart' || !space.folder) continue
    const inside = isBelow(root, space.folder) || path.relative(root, space.folder) === ''
    if (inside) {
      const rel = relativeBelow(root, space.folder) ?? ''
      used.add(rel.toLowerCase())
      out.push({id: space.id, rel, abs: space.folder, linked: false})
    } else pending.push(space)
  }
  for (const space of pending) {
    const kept = prev?.get(space.id)
    if (kept) used.add(kept.toLowerCase())
  }
  for (const space of pending) {
    let rel = prev?.get(space.id)
    if (!rel) {
      const base = cleanName(space.name)
      rel = base
      for (let n = 2; used.has(rel.toLowerCase()); n++) rel = `${base} (${n})`
      used.add(rel.toLowerCase())
    }
    out.push({id: space.id, rel, abs: space.folder, linked: true})
  }
  return out
}

function put(fields: Record<string, SyncJson>, name: string, value: SyncJson | undefined) {
  if (value !== undefined) fields[name] = value
}

/** The wire names of an item's synced fields, in the order itemSnap writes them (`media`, `space`, then the codec fields). */
export const ITEM_WIRE_NAMES: readonly string[] = ['media', 'space', ...ITEM_FIELDS.map((field) => field.name)]
export const ITEM_WIRE_COUNT = ITEM_WIRE_NAMES.length

/** The kind as the wire carries it. */
export const wireMedia = (kind: string) => (KINDS.has(kind) ? kind : 'other')
/** An entry's user tags as the sync set holds them: unique, non-empty, sorted. */
export const normalizeTags = (tags: readonly string[] | undefined): string[] => [...new Set(tags ?? [])].filter((tag) => tag.length > 0).sort()
/** An entry's play count as the sync counter holds it. */
export const normalizePlays = (plays: number | undefined) => (finite(plays) && plays > 0 ? Math.round(plays) : 0)
/** The wire value of the codec field at `index` (2 and up; 0 and 1 are `media` and `space`), or undefined when the entry has none. */
export const encodeItemField = (entry: LibraryEntry, index: number): SyncJson | undefined => {
  const field = ITEM_FIELDS[index - 2]
  return field.codec.enc(entry[field.key])
}

export function itemSnap(entry: LibraryEntry, roots: readonly SpaceRoot[], root: string): ItemSnap {
  const fields: Record<string, SyncJson> = {media: wireMedia(entry.kind), space: spaceWireId(entry.space)}
  for (const field of ITEM_FIELDS) put(fields, field.name, field.codec.enc(entry[field.key]))
  const userTags = normalizeTags(entry.userTags)
  const favorite = entry.favorite === true
  const plays = normalizePlays(entry.plays)
  const place = placementOf(entry.path, root, roots)
  return {id: entry.id, fields, favorite, userTags, plays, place, key: JSON.stringify([fields, favorite, userTags, plays, place])}
}

/**
 * What changed in an item since it was last saved, from the dirty tracker's hashes (it keeps no copy of the old values).
 * The new values come from the item's current snapshot; this says which of them to write.
 */
export type ItemChange = {
  /** Indexes into ITEM_WIRE_NAMES of the synced fields whose value changed. */
  fields: readonly number[]
  place: boolean
  /** The play count when last saved. */
  playsWas: number
  /** The favourite flag when last saved, when it changed. */
  favoriteWas?: boolean
  /** The user tags when last saved (normalized), when they changed. */
  tagsWas?: readonly string[]
}

/** The operations for one changed item: the fields that changed with their new values, nothing else. */
function changedItemOps(a: ItemSnap, change: ItemChange, ctx: DiffContext, out: OpDraft[]) {
  const id = a.id
  const cleared: string[] = []
  for (const index of change.fields) {
    const name = ITEM_WIRE_NAMES[index]
    const value = a.fields[name]
    if (value === undefined) cleared.push(name)
    else out.push({kind: 'item.set', body: {item: id, field: name, value}})
  }
  for (const name of cleared) out.push({kind: 'item.set', body: {item: id, field: name, value: null}})
  if (change.place) out.push({kind: 'item.place', body: {item: id, place: ctx.replicaId, path: a.place}})
  if (a.plays > change.playsWas) out.push({kind: 'item.set', body: {item: id, field: PLAYS_PREFIX + ctx.actor, value: ctx.ownPlays(id) + (a.plays - change.playsWas)}})
  if (change.favoriteWas !== undefined && a.favorite !== change.favoriteWas) {
    if (a.favorite) out.push({kind: 'set.add', body: {set: FAVOURITES, element: id}})
    else {
      const observedTags = ctx.observed.member(FAVOURITES, id)
      if (observedTags.length) out.push({kind: 'set.remove', body: {set: FAVOURITES, element: id, observedTags}})
    }
  }
  if (change.tagsWas) setOps(`tags:${id}`, change.tagsWas, a.userTags, ctx, out)
}

/** The device-only fields of an entry (the keys that never sync). */
export function deviceItemOf(entry: LibraryEntry): DeviceItem {
  const out: Record<string, unknown> = {}
  for (const key of DEVICE_ITEM_KEYS) {
    const value = (entry as Record<string, unknown>)[key]
    if (value !== undefined) out[key] = value
  }
  return out as DeviceItem
}

export function deviceCollectionOf(collection: Collection): DeviceCollection | undefined {
  const follow = collection.follow
  if (!follow || (follow.lastCheckedAt === undefined && follow.lastError === undefined)) return undefined
  const out: DeviceCollection = {}
  if (follow.lastCheckedAt !== undefined) out.lastCheckedAt = follow.lastCheckedAt
  if (follow.lastError !== undefined) out.lastError = follow.lastError
  return out
}

function followWire(follow: CollectionFollow): SyncJson {
  const out: Record<string, SyncJson> = {mode: follow.mode, intervalHours: follow.intervalHours, wifiOnly: follow.wifiOnly, notify: follow.notify, confirmAbove: follow.confirmAbove}
  if (follow.pending && finite(follow.pending.count) && finite(follow.pending.at)) out.pending = {count: Math.round(follow.pending.count), at: Math.round(follow.pending.at)}
  return out
}

export function collectionSnap(collection: Collection, itemIds: {has(id: string): boolean}): CollectionSnap {
  const own = collection.kind === 'own'
  const fields: Record<string, SyncJson> = {kind: own ? 'playlist' : 'collection'}
  if (!own) fields.collectionKind = collection.kind
  fields.name = collection.name
  fields.space = spaceWireId(collection.space)
  if (collection.source) {
    const source: Record<string, SyncJson> = {url: collection.source.url, provider: collection.source.provider}
    if (collection.source.id !== undefined) source.id = collection.source.id
    fields.source = source
  }
  if (collection.follow) fields.follow = followWire(collection.follow)
  put(fields, 'coverItemId', typeof collection.coverItemId === 'string' ? collection.coverItemId : undefined)
  put(fields, 'createdAt', int.enc(collection.createdAt))
  put(fields, 'updatedAt', int.enc(collection.updatedAt))
  const known = (ids: readonly string[] | undefined) => [...new Set(ids ?? [])].filter((id) => itemIds.has(id)).sort()
  const seen = [...new Set(collection.seen)].filter((value) => value.length > 0).sort()
  const news = known(collection.newItemIds)
  const removed = known(collection.removedItemIds)
  const members = [...new Set(collection.itemIds)].filter((id) => itemIds.has(id))
  return {id: collection.id, fields, itemIds: members, seen, news, removed, key: JSON.stringify([fields, seen, news, removed])}
}

function spaceSnap(space: Space, order: number, roots: ReadonlyMap<string, SpaceRoot>): SpaceSnap {
  const fields: Record<string, SyncJson> = {name: space.name, kind: space.kind}
  const mapped = roots.get(space.id)
  fields.folder = space.type === 'smart' ? '' : (mapped?.rel ?? '')
  fields.layout = space.layout
  fields.naming = space.naming
  fields.skipExisting = space.skipExisting
  if (space.ignore?.length) fields.ignore = space.ignore
  put(fields, 'scanFolder', bool.enc(space.scanFolder))
  fields.type = space.type
  put(fields, 'color', str.enc(space.color))
  put(fields, 'icon', str.enc(space.icon))
  put(fields, 'pinned', bool.enc(space.pinned))
  if (space.rules) fields.rules = JSON.parse(JSON.stringify(space.rules)) as SyncJson
  if (mapped?.linked) fields.linked = true
  fields.order = order
  if (needsLegacy(space.id)) fields.legacyId = space.id
  return {id: space.id, wire: spaceWireId(space.id), fields, key: JSON.stringify(fields)}
}

/** Reduces a model to its synced content. Only managed items sync; ids that are not UUIDs are skipped and listed. */
export function snapshot(model: Model, context: SnapContext): ModelSnap {
  const skipped: string[] = []
  const roots = spaceRoots(model.spaces, context.root, context.rels)
  const rootById = new Map(roots.map((r) => [r.id, r]))
  const rels = new Map(roots.map((r) => [r.id, r.rel]))
  const items = new Map<string, ItemSnap>()
  for (const entry of model.items) {
    if (entry.managed === false) continue
    if (!isUuid(entry.id)) {
      skipped.push(entry.id)
      continue
    }
    items.set(entry.id, itemSnap(entry, roots, context.root))
  }
  const known = new Set(items.keys())
  const collections = new Map<string, CollectionSnap>()
  for (const collection of model.collections) {
    if (!isUuid(collection.id)) {
      skipped.push(collection.id)
      continue
    }
    collections.set(collection.id, collectionSnap(collection, known))
  }
  const spaces = new Map<string, SpaceSnap>()
  model.spaces.forEach((space, order) => spaces.set(space.id, spaceSnap(space, order, rootById)))
  const routes = model.spaces.length || model.routes ? {value: {music: spaceWireId(model.routes.music), other: spaceWireId(model.routes.other)} as SyncJson, key: ''} : null
  if (routes) routes.key = JSON.stringify(routes.value)
  return {items, collections, spaces, routes, rels, skipped}
}

/** The same as snapshot(), in slices that give the thread back in between (a library of 200,000 items takes seconds). */
export async function snapshotInChunks(model: Model, context: SnapContext, chunk = 4000): Promise<ModelSnap> {
  const skipped: string[] = []
  const roots = spaceRoots(model.spaces, context.root, context.rels)
  const rootById = new Map(roots.map((r) => [r.id, r]))
  const rels = new Map(roots.map((r) => [r.id, r.rel]))
  const items = new Map<string, ItemSnap>()
  for (let i = 0; i < model.items.length; i++) {
    const entry = model.items[i]
    if (entry.managed !== false) {
      if (isUuid(entry.id)) items.set(entry.id, itemSnap(entry, roots, context.root))
      else skipped.push(entry.id)
    }
    if ((i + 1) % chunk === 0) await new Promise((resolve) => setImmediate(resolve))
  }
  const collections = new Map<string, CollectionSnap>()
  for (const collection of model.collections) {
    if (!isUuid(collection.id)) skipped.push(collection.id)
    else collections.set(collection.id, collectionSnap(collection, items))
  }
  const spaces = new Map<string, SpaceSnap>()
  model.spaces.forEach((space, order) => spaces.set(space.id, spaceSnap(space, order, rootById)))
  const routes = {value: {music: spaceWireId(model.routes.music), other: spaceWireId(model.routes.other)} as SyncJson, key: ''}
  routes.key = JSON.stringify(routes.value)
  return {items, collections, spaces, routes, rels, skipped}
}

/**
 * A baseline of collections, spaces and routes (the items are not kept: what an item looked like when it was last saved is
 * in the item tracker's hashes). Built at once, after a turn of the event loop.
 */
export async function snapshotFrozen(model: Pick<Model, 'collections' | 'spaces' | 'routes'>, context: SnapContext, known: {has(id: string): boolean}): Promise<ModelSnap> {
  const roots = spaceRoots(model.spaces, context.root, context.rels)
  const rootById = new Map(roots.map((r) => [r.id, r]))
  const base = emptySnapshot()
  base.rels = new Map(roots.map((r) => [r.id, r.rel]))
  for (const collection of model.collections) if (isUuid(collection.id)) base.collections.set(collection.id, collectionSnap(collection, known))
  model.spaces.forEach((space, order) => base.spaces.set(space.id, spaceSnap(space, order, rootById)))
  const routes = {value: {music: spaceWireId(model.routes.music), other: spaceWireId(model.routes.other)} as SyncJson, key: ''}
  routes.key = JSON.stringify(routes.value)
  base.routes = routes
  await new Promise((resolve) => setImmediate(resolve))
  return base
}

// ---------------------------------------------------------------------------------------------------------------------
// Diff
// ---------------------------------------------------------------------------------------------------------------------

export type DiffContext = {
  /** This writer's actor id (for its own play counter). */
  actor: string
  /** This device's place id (placements are written under it). */
  replicaId: string
  newId(): string
  /** This actor's own play counter for an item (kept locally, never read back from the views). */
  ownPlays(id: string): number
  /** A playlist's current songs (entry ids and keys), for reorders. */
  songs(playlist: string): SyncSong[]
  /** What deletes and removes must observe (exists-tags, revision heads, set element tags). */
  observed: ObservedLookup
  /**
   * Items that were saved before and changed (their new snapshot and what changed) and items that were removed, from the dirty
   * tracker. The `before` snapshot has no entry for these: only their changes are written.
   */
  itemChanges?: {changed: ReadonlyMap<string, {snap: ItemSnap; change: ItemChange}>; removed: readonly string[]}
}

/** The tags and heads a delete or remove carries, by key. Unknown keys answer with empty lists. */
export interface ObservedLookup {
  item(id: string): {tags: string[]; heads: string[]}
  playlist(id: string): string[]
  space(id: string): string[]
  member(set: string, element: string): string[]
}

const same = (a: SyncJson | undefined, b: SyncJson | undefined) => a === b || JSON.stringify(a) === JSON.stringify(b)

/** Field-level set operations between two field maps; a removed field is written as null. */
function fieldOps(kind: 'item' | 'playlist' | 'space', id: string, before: Record<string, SyncJson>, after: Record<string, SyncJson>, out: OpDraft[]) {
  for (const name of Object.keys(after)) if (!same(before[name], after[name])) out.push({kind: `${kind}.set`, body: {[kind]: id, field: name, value: after[name]}})
  for (const name of Object.keys(before)) if (!(name in after) && before[name] !== null) out.push({kind: `${kind}.set`, body: {[kind]: id, field: name, value: null}})
}

function setOps(set: string, before: readonly string[], after: readonly string[], ctx: DiffContext, out: OpDraft[]) {
  const had = new Set(before)
  const has = new Set(after)
  for (const element of after) if (!had.has(element)) out.push({kind: 'set.add', body: {set, element}})
  for (const element of before) {
    if (has.has(element)) continue
    const observedTags = ctx.observed.member(set, element)
    if (observedTags.length) out.push({kind: 'set.remove', body: {set, element, observedTags}})
  }
}

/** Indexes of a longest strictly increasing subsequence of `values`. */
function longestIncreasing(values: readonly number[]): number[] {
  const tails: number[] = []
  const prev = new Array<number>(values.length).fill(-1)
  for (let i = 0; i < values.length; i++) {
    let lo = 0
    let hi = tails.length
    while (lo < hi) {
      const mid = (lo + hi) >> 1
      if (values[tails[mid]] < values[i]) lo = mid + 1
      else hi = mid
    }
    if (lo > 0) prev[i] = tails[lo - 1]
    tails[lo] = i
  }
  const out: number[] = []
  for (let at = tails.length ? tails[tails.length - 1] : -1; at >= 0; at = prev[at]) out.push(at)
  return out.reverse()
}

/**
 * The entry operations that turn a playlist's old item order into the new one. Entries of kept items stay (their keys
 * are untouched) except the minimum set outside a longest increasing subsequence, which gets `entry.move`; new items
 * get `entry.insert`; removed items get `entry.remove`.
 */
function entryOps(playlist: string, oldIds: readonly string[], newIds: readonly string[], songs: readonly SyncSong[], ctx: DiffContext, out: OpDraft[]) {
  const was = new Set(oldIds)
  const now = new Set(newIds)
  const byItem = new Map<string, SyncSong[]>()
  for (const song of songs) {
    const list = byItem.get(song.item)
    if (list) list.push(song)
    else byItem.set(song.item, [song])
  }
  for (const item of oldIds) if (!now.has(item)) for (const song of byItem.get(item) ?? []) out.push({kind: 'entry.remove', body: {entry: song.entry}})

  // Kept items that still have an entry, in their current playlist order (songs are sorted by key, then entry id).
  const position = new Map(newIds.map((item, at) => [item, at]))
  const keptSongs: SyncSong[] = []
  const seenItem = new Set<string>()
  for (const song of songs) {
    if (!was.has(song.item) || !now.has(song.item) || seenItem.has(song.item)) continue
    seenItem.add(song.item)
    keptSongs.push(song)
  }
  const stable = new Set<string>()
  const tied = keptSongs.some((song, at) => at > 0 && song.key === keptSongs[at - 1].key)
  if (!tied) for (const at of longestIncreasing(keptSongs.map((song) => position.get(song.item) as number))) stable.add(keptSongs[at].item)
  const keyOf = new Map(keptSongs.map((song) => [song.item, song.key]))
  const entryOf = new Map(keptSongs.map((song) => [song.item, song.entry]))

  let left: string | null = null
  for (let at = 0; at < newIds.length; ) {
    const item = newIds[at]
    if (stable.has(item)) {
      left = keyOf.get(item) as string
      at++
      continue
    }
    // A run of items that need a key: between the last stable key and the next one.
    let end = at
    while (end < newIds.length && !stable.has(newIds[end])) end++
    const right: string | null = end < newIds.length ? (keyOf.get(newIds[end]) as string) : null
    let cursor: string | null = left
    for (let k = at; k < end; k++) {
      const target = newIds[k]
      // A kept item with no entry any more (removed elsewhere) is not resurrected; it simply falls out on the next projection.
      if (was.has(target) && !entryOf.has(target)) continue
      cursor = keyBetween(cursor, right)
      if (entryOf.has(target)) out.push({kind: 'entry.move', body: {entry: entryOf.get(target) as string, key: cursor}})
      else out.push({kind: 'entry.insert', body: {playlist, entry: ctx.newId(), item: target, key: cursor}})
    }
    left = cursor
    at = end
  }
}

function addItem(a: ItemSnap, ctx: DiffContext, out: OpDraft[]) {
  out.push({kind: 'item.add', body: {item: a.id, fields: a.fields}})
  if (a.place) out.push({kind: 'item.place', body: {item: a.id, place: ctx.replicaId, path: a.place}})
  if (a.plays > 0) out.push({kind: 'item.set', body: {item: a.id, field: PLAYS_PREFIX + ctx.actor, value: a.plays}})
  if (a.favorite) out.push({kind: 'set.add', body: {set: FAVOURITES, element: a.id}})
  for (const tag of a.userTags) out.push({kind: 'set.add', body: {set: `tags:${a.id}`, element: tag}})
}

/** The operations that turn `after` into the state `before` described. Both are snapshots of the same library. */
export function diffSnaps(before: ModelSnap, after: ModelSnap, ctx: DiffContext): OpDraft[] {
  const out: OpDraft[] = []

  // spaces
  for (const [id, a] of after.spaces) {
    const b = before.spaces.get(id)
    if (!b) out.push({kind: 'space.add', body: {space: a.wire, fields: a.fields}})
    else if (a.key !== b.key) fieldOps('space', a.wire, b.fields, a.fields, out)
  }
  for (const [id, b] of before.spaces) {
    if (after.spaces.has(id)) continue
    out.push({kind: 'space.delete', body: {space: b.wire, observedTags: ctx.observed.space(b.wire)}})
  }
  if (after.routes && after.routes.key !== before.routes?.key) out.push({kind: 'profile.set', body: {field: 'routes', value: after.routes.value}})

  // items
  for (const [id, a] of after.items) {
    const b = before.items.get(id)
    if (!b) {
      addItem(a, ctx, out)
      continue
    }
    if (a.key === b.key) continue
    fieldOps('item', id, b.fields, a.fields, out)
    if (a.place !== b.place) out.push({kind: 'item.place', body: {item: id, place: ctx.replicaId, path: a.place}})
    if (a.plays > b.plays) {
      out.push({kind: 'item.set', body: {item: id, field: PLAYS_PREFIX + ctx.actor, value: ctx.ownPlays(id) + (a.plays - b.plays)}})
    }
    if (a.favorite !== b.favorite) {
      if (a.favorite) out.push({kind: 'set.add', body: {set: FAVOURITES, element: id}})
      else {
        const observedTags = ctx.observed.member(FAVOURITES, id)
        if (observedTags.length) out.push({kind: 'set.remove', body: {set: FAVOURITES, element: id, observedTags}})
      }
    }
    setOps(`tags:${id}`, b.userTags, a.userTags, ctx, out)
  }
  for (const [id] of before.items) {
    if (after.items.has(id)) continue
    const seen = ctx.observed.item(id)
    out.push({kind: 'item.delete', body: {item: id, observedTags: seen.tags, observedHeads: seen.heads}})
  }
  if (ctx.itemChanges) {
    for (const {snap, change} of ctx.itemChanges.changed.values()) changedItemOps(snap, change, ctx, out)
    for (const id of ctx.itemChanges.removed) {
      const seen = ctx.observed.item(id)
      out.push({kind: 'item.delete', body: {item: id, observedTags: seen.tags, observedHeads: seen.heads}})
    }
  }

  // collections
  for (const [id, a] of after.collections) {
    const b = before.collections.get(id)
    if (!b) {
      out.push({kind: 'playlist.add', body: {playlist: id, fields: a.fields}})
      entryOps(id, [], a.itemIds, [], ctx, out)
      setOps(`seen:${id}`, [], a.seen, ctx, out)
      setOps(`new:${id}`, [], a.news, ctx, out)
      setOps(`removed:${id}`, [], a.removed, ctx, out)
      continue
    }
    if (a.key !== b.key) {
      fieldOps('playlist', id, b.fields, a.fields, out)
      setOps(`seen:${id}`, b.seen, a.seen, ctx, out)
      setOps(`new:${id}`, b.news, a.news, ctx, out)
      setOps(`removed:${id}`, b.removed, a.removed, ctx, out)
    }
    if (a.itemIds.length !== b.itemIds.length || a.itemIds.some((item, at) => item !== b.itemIds[at])) {
      entryOps(id, b.itemIds, a.itemIds, ctx.songs(id), ctx, out)
    }
  }
  for (const [id] of before.collections) {
    if (!after.collections.has(id)) out.push({kind: 'playlist.delete', body: {playlist: id, observedTags: ctx.observed.playlist(id)}})
  }
  return out
}

/** An empty library: the baseline for creating one. */
export function emptySnapshot(): ModelSnap {
  return {items: new Map(), collections: new Map(), spaces: new Map(), routes: null, rels: new Map(), skipped: []}
}

/** The virtual folder names the views give to linked spaces, by model space id (for the next snapshot). */
export function spaceRels(views: Pick<SyncViews, 'spaces'>): Map<string, string> {
  const out = new Map<string, string>()
  for (const row of views.spaces) {
    if (!row.present || row.fields.linked !== true || typeof row.fields.folder !== 'string') continue
    const id = (typeof row.fields.legacyId === 'string' ? row.fields.legacyId : undefined) ?? builtinByWire.get(row.id) ?? row.id
    out.set(id, row.fields.folder)
  }
  return out
}

/** The operations that turn `before` into `after`. */
export function diff(before: Model, after: Model, context: SnapContext & DiffContext): OpDraft[] {
  const was = snapshot(before, {root: context.root, rels: context.rels})
  const now = snapshot(after, {root: context.root, rels: was.rels})
  return diffSnaps(was, now, context)
}

// ---------------------------------------------------------------------------------------------------------------------
// Projection
// ---------------------------------------------------------------------------------------------------------------------

const folderOf = (space: Space | undefined, file: string) => {
  if (!space || !space.folder) return ''
  const relative = path.relative(space.folder, path.dirname(file))
  return relative.startsWith('..') || path.isAbsolute(relative) ? '' : relative.split(path.sep).join('/')
}

const asRecord = (value: SyncJson | undefined): Record<string, SyncJson> | undefined => (value && typeof value === 'object' && !Array.isArray(value) ? value : undefined)
const asString = (value: SyncJson | undefined) => (typeof value === 'string' ? value : undefined)

function projectSpaces(views: {spaces: readonly SyncSpace[]}, device: DeviceData): {spaces: Space[]; modelId: (wire: string) => string} {
  const rows = views.spaces
    .filter((space) => space.present)
    .map((space) => {
      const f = space.fields
      const order = typeof f.order === 'number' ? f.order : Number.MAX_SAFE_INTEGER
      return {space, order}
    })
    .sort((a, b) => a.order - b.order || (a.space.id < b.space.id ? -1 : 1))
  const modelIds = new Map<string, string>()
  for (const {space} of rows) modelIds.set(space.id, asString(space.fields.legacyId) ?? builtinByWire.get(space.id) ?? space.id)
  const modelId = (wire: string) => modelIds.get(wire) ?? builtinByWire.get(wire) ?? wire
  const spaces = rows.map(({space: row}): Space => {
    const f = row.fields
    const id = modelId(row.id)
    const rel = asString(f.folder) ?? ''
    const smart = f.type === 'smart'
    const linked = f.linked === true
    const folder = smart ? '' : linked ? (device.linked[id] ?? '') : rel ? path.join(device.root, ...rel.split('/')) : device.root
    const space: Space = {
      id,
      name: asString(f.name) ?? id,
      kind: f.kind === 'music' || f.kind === 'video' || f.kind === 'mixed' ? f.kind : 'mixed',
      folder,
      layout: f.layout === 'flat' || f.layout === 'collection' || f.layout === 'platform' || f.layout === 'artist-album' ? f.layout : 'flat',
      naming: (asString(f.naming) as Space['naming']) ?? 'title',
      skipExisting: f.skipExisting === true,
      type: smart ? 'smart' : 'folder',
    }
    if (Array.isArray(f.ignore) && f.ignore.every((x) => typeof x === 'string')) space.ignore = f.ignore as string[]
    if (typeof f.scanFolder === 'boolean') space.scanFolder = f.scanFolder
    if (typeof f.color === 'string') space.color = f.color as Space['color']
    if (typeof f.icon === 'string') space.icon = f.icon as Space['icon']
    if (typeof f.pinned === 'boolean') space.pinned = f.pinned
    if (f.rules && typeof f.rules === 'object') space.rules = f.rules as unknown as Space['rules']
    return space
  })
  return {spaces, modelId}
}

/** What projecting needs about the spaces: built once from the space views and reused for single items and playlists. */
export type ProjectEnv = {
  spaces: Space[]
  routes: Routes
  modelId: (wire: string) => string
  spaceById: Map<string, Space>
  roots: SpaceRoot[]
  device: DeviceData
}

export function projectEnv(views: Pick<SyncViews, 'spaces' | 'profile'>, device: DeviceData): ProjectEnv {
  const {spaces, modelId} = projectSpaces(views, device)
  const spaceById = new Map(spaces.map((space) => [space.id, space]))
  const roots: SpaceRoot[] = views.spaces
    .filter((row) => row.present && row.fields.type !== 'smart')
    .map((row) => ({id: modelId(row.id), rel: asString(row.fields.folder) ?? '', abs: spaceById.get(modelId(row.id))?.folder ?? '', linked: row.fields.linked === true}))
  const routesField = asRecord(views.profile.routes)
  const routes: Routes = {
    music: modelId(asString(routesField?.music) ?? BUILTIN_SPACE_IDS.music),
    other: modelId(asString(routesField?.other) ?? BUILTIN_SPACE_IDS.downloads),
  }
  return {spaces, routes, modelId, spaceById, roots, device}
}

/** One item as the library model has it on this device. `favorite` and `userTags` come from the sets. */
export function projectItem(view: SyncItem, placements: Record<string, string | null> | undefined, favorite: boolean, userTags: readonly string[] | undefined, env: ProjectEnv): LibraryEntry {
  const {device} = env
  const f = view.fields
  const kindText = asString(f.media)
  const kind: LibraryItemKind = kindText && KINDS.has(kindText) ? (kindText as LibraryItemKind) : 'other'
  const spaceId = env.modelId(asString(f.space) ?? '')
  const placement = placements?.[device.replicaId]
  const abs = typeof placement === 'string' ? resolvePlacement(placement, device.root, env.roots) : undefined
  const local = deviceRecord(device.items, view.id) ?? {}
  const entry: Record<string, unknown> = {
    id: view.id,
    space: spaceId,
    path: abs ?? unplacedPath(device.root, view.id),
    folder: abs ? folderOf(env.spaceById.get(spaceId), abs) : '',
    kind,
    title: asString(f.title) ?? '',
    size: local.size ?? view.heads[0]?.size ?? 0,
    modifiedAt: local.modifiedAt ?? 0,
    addedAt: 0,
    hasArt: device.art.has(view.id),
    managed: true,
  }
  for (const field of ITEM_FIELDS) {
    const raw = f[field.name]
    if (raw === undefined || raw === null) continue
    const value = field.codec.dec(raw)
    if (value !== undefined) entry[field.key] = value
  }
  if (favorite) entry.favorite = true
  if (userTags?.length) entry.userTags = [...userTags].sort()
  if (view.plays > 0) entry.plays = view.plays
  for (const key of DEVICE_ITEM_KEYS) if (key !== 'size' && key !== 'modifiedAt' && local[key] !== undefined) entry[key] = local[key]
  if (!abs) entry.missing = true
  return entry as LibraryEntry
}

/** One playlist as a collection; undefined when it is not present or not one this app knows. */
export function projectCollection(playlist: SyncPlaylist, sets: {seen: readonly string[]; news: readonly string[]; removed: readonly string[]}, present: (id: string) => boolean, env: ProjectEnv): Collection | undefined {
  if (!playlist.present) return undefined
  const f = playlist.fields
  const own = f.kind === 'playlist'
  if (!own && f.kind !== 'collection') return undefined
  const kind: CollectionKind = own ? 'own' : ((['playlist', 'album', 'channel'] as const).find((value) => value === f.collectionKind) ?? 'playlist')
  const itemIds: string[] = []
  const seenItems = new Set<string>()
  for (const song of playlist.songs) {
    if (!present(song.item) || seenItems.has(song.item)) continue
    seenItems.add(song.item)
    itemIds.push(song.item)
  }
  const collection: Collection = {
    id: playlist.id,
    name: asString(f.name) ?? '',
    kind,
    space: env.modelId(asString(f.space) ?? ''),
    itemIds,
    seen: [...sets.seen].sort(),
    createdAt: typeof f.createdAt === 'number' ? f.createdAt : 0,
    updatedAt: typeof f.updatedAt === 'number' ? f.updatedAt : 0,
  }
  if (typeof f.coverItemId === 'string') collection.coverItemId = f.coverItemId
  const source = asRecord(f.source)
  if (source && typeof source.url === 'string') {
    collection.source = {url: source.url, provider: asString(source.provider) ?? ''}
    if (typeof source.id === 'string') collection.source.id = source.id
  }
  const follow = asRecord(f.follow)
  if (!own && follow) {
    const value: CollectionFollow = {
      mode: follow.mode === 'auto' || follow.mode === 'manual' || follow.mode === 'off' ? follow.mode : 'off',
      intervalHours: ([1, 6, 12, 24, 168] as const).find((hours) => hours === follow.intervalHours) ?? 24,
      wifiOnly: follow.wifiOnly === true,
      notify: follow.notify === true,
      confirmAbove: typeof follow.confirmAbove === 'number' ? follow.confirmAbove : 50,
    }
    const pending = asRecord(follow.pending)
    if (pending && typeof pending.count === 'number' && typeof pending.at === 'number') value.pending = {count: pending.count, at: pending.at}
    const local = env.device.collections[playlist.id]
    if (local?.lastCheckedAt !== undefined) value.lastCheckedAt = local.lastCheckedAt
    if (local?.lastError !== undefined) value.lastError = local.lastError
    collection.follow = value
  }
  const news = sets.news.filter(present).sort()
  if (news.length) collection.newItemIds = news
  const removed = sets.removed.filter(present).sort()
  if (removed.length) collection.removedItemIds = removed
  return collection
}

/**
 * The model that a set of views describes on this device. Items without a placement here are shown as missing. Long
 * projections hand the thread back every `chunk` entries when `yieldEvery` is given (see projectInChunks).
 */
export function project(views: SyncViews, device: DeviceData): Model {
  const env = projectEnv(views, device)
  const favourites = new Set(views.sets[FAVOURITES] ?? [])
  const items: LibraryEntry[] = []
  const present = new Set<string>()
  for (const view of views.items) {
    if (view.status === 'deleted') continue
    present.add(view.id)
    items.push(projectItem(view, views.placements[view.id], favourites.has(view.id), views.sets[`tags:${view.id}`], env))
  }
  const collections: Collection[] = []
  for (const playlist of views.playlists) {
    const collection = projectCollection(playlist, {seen: views.sets[`seen:${playlist.id}`] ?? [], news: views.sets[`new:${playlist.id}`] ?? [], removed: views.sets[`removed:${playlist.id}`] ?? []}, (id) => present.has(id), env)
    if (collection) collections.push(collection)
  }
  return {items, collections, spaces: env.spaces, routes: env.routes}
}

/** The same projection in slices that give the thread back in between, reporting how far it is. */
export async function projectInChunks(views: SyncViews, device: DeviceData, options: {chunk?: number; onProgress?(done: number, total: number): void} = {}): Promise<Model> {
  const chunk = options.chunk ?? 4000
  const env = projectEnv(views, device)
  const favourites = new Set(views.sets[FAVOURITES] ?? [])
  const items: LibraryEntry[] = []
  const present = new Set<string>()
  const total = views.items.length + views.playlists.length
  let done = 0
  const pause = async () => {
    options.onProgress?.(done, total)
    await new Promise((resolve) => setImmediate(resolve))
  }
  for (const view of views.items) {
    if (view.status !== 'deleted') {
      present.add(view.id)
      items.push(projectItem(view, views.placements[view.id], favourites.has(view.id), views.sets[`tags:${view.id}`], env))
    }
    if (++done % chunk === 0) await pause()
  }
  const collections: Collection[] = []
  for (const playlist of views.playlists) {
    const collection = projectCollection(playlist, {seen: views.sets[`seen:${playlist.id}`] ?? [], news: views.sets[`new:${playlist.id}`] ?? [], removed: views.sets[`removed:${playlist.id}`] ?? []}, (id) => present.has(id), env)
    if (collection) collections.push(collection)
    if (++done % chunk === 0) await pause()
  }
  options.onProgress?.(total, total)
  return {items, collections, spaces: env.spaces, routes: env.routes}
}

// ---------------------------------------------------------------------------------------------------------------------
// Observed lookups (two passes: the first records what a diff needs to observe, the second has the answer)
// ---------------------------------------------------------------------------------------------------------------------

export class RecordingObserved implements ObservedLookup {
  readonly items = new Set<string>()
  readonly playlists = new Set<string>()
  readonly spaces = new Set<string>()
  readonly elements = new Map<string, Set<string>>()
  item(id: string) {
    this.items.add(id)
    return {tags: [] as string[], heads: [] as string[]}
  }
  playlist(id: string) {
    this.playlists.add(id)
    return [] as string[]
  }
  space(id: string) {
    this.spaces.add(id)
    return [] as string[]
  }
  member(set: string, element: string) {
    const list = this.elements.get(set)
    if (list) list.add(element)
    else this.elements.set(set, new Set([element]))
    return [] as string[]
  }
  get empty() {
    return !this.items.size && !this.playlists.size && !this.spaces.size && !this.elements.size
  }
  query(): SyncObservedQuery {
    const setElements: [string, string][] = []
    for (const [set, list] of this.elements) for (const element of list) setElements.push([set, element])
    return {items: [...this.items], playlists: [...this.playlists], spaces: [...this.spaces], setElements}
  }
}

export function answerObserved(answer: SyncObserved): ObservedLookup {
  return {
    item: (id) => answer.items[id] ?? {tags: [], heads: []},
    playlist: (id) => answer.playlists[id] ?? [],
    space: (id) => answer.spaces[id] ?? [],
    member: (set, element) => answer.setElements[set]?.[element] ?? [],
  }
}
