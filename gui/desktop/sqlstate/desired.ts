// The desired set D of a copy (spec section 2) and the place registers, read straight from the SQL merge state with plain
// SELECTs on a connection of their own. Same semantics as the core's `copies/Desired.kt` (`desiredSetFrom`,
// `placeConfigFrom`), which reads the in-memory State; desired.test.ts proves the two equal on random histories. The
// copies worker uses this: the store's state lives in a database file, and the app no longer holds a State in memory.
import type {DatabaseSync} from 'node:sqlite'
import {companionCandidatesOf, conflictPath, fastKey, keepCompanions, type DesiredEntry, type NameRulesId} from '../../shared/core/copies.js'
import {sqlQueries, type SyncHead, type SyncJson} from '../../shared/core/sync.js'

export type CopyModeId = 'twoWay' | 'backup' | 'mirror'

export interface Holds {
  all: boolean
  spaces: string[]
  playlists: string[]
  favourites: boolean
  /** Smart spaces need a predicate from the caller; none is wired yet (spec 4.8 rule 12). */
  smart: string[]
}

export interface PlaceOptions {
  playlistFiles: boolean
  flat: boolean
  mp3: boolean
  /** Lyrics, subtitles and info files travel with their items (on unless turned off). */
  companions: boolean
  /** Each song's music video travels with it. Stored only once the person chooses; until then a copy that holds everything carries them (placeMusicVideos). */
  musicVideos?: boolean
  versionsKeepDays: number
  deleteGuardPercent: number
}

export interface PlaceConfig {
  id: string
  kind: string
  name: string
  replica: string | null
  mode: CopyModeId
  holds: Holds
  options: PlaceOptions
  /** `everyHours` is the spec's field; `auto` ("sync by itself": when the copy appears, when the library changes) is the app's. */
  schedule: {everyHours: number | null; auto: boolean}
  /** The user removed the relationship; the copy's files stay where they are. */
  removed: boolean
  verified: {at?: number; manifest?: number; sha256?: string; generation?: number} | null
}

export const DEFAULT_OPTIONS: PlaceOptions = {playlistFiles: false, flat: false, mp3: false, companions: true, versionsKeepDays: 90, deleteGuardPercent: 10}

const asString = (v: SyncJson | undefined): string | undefined => (typeof v === 'string' ? v : undefined)
const strings = (v: SyncJson | undefined): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [])
const object = (v: SyncJson | undefined): Record<string, SyncJson> => (v && typeof v === 'object' && !Array.isArray(v) ? v : {})

/** A place from the values of its `place.set` registers (the core's `placeConfigFrom`); null when the mode is missing or unknown. */
export function placeConfigFrom(id: string, fields: Record<string, SyncJson>): PlaceConfig | null {
  const mode = fields.mode
  if (mode !== 'twoWay' && mode !== 'backup' && mode !== 'mirror') return null
  const h = object(fields.holds)
  const o = object(fields.options)
  const int = (v: SyncJson | undefined, fallback: number) => (typeof v === 'number' && Number.isFinite(v) ? Math.trunc(v) : fallback)
  const s = object(fields.schedule)
  const verified = object(fields.verified)
  return {
    id,
    kind: asString(fields.kind) ?? 'folder',
    name: asString(fields.name) ?? '',
    replica: asString(fields.replica) ?? null,
    mode,
    holds: {all: h.all === true, spaces: strings(h.spaces), playlists: strings(h.playlists), favourites: h.favourites === true, smart: strings(h.smart)},
    options: {
      playlistFiles: o.playlistFiles === true,
      flat: o.flat === true,
      mp3: o.mp3 === true,
      companions: o.companions !== false,
      ...(typeof o.musicVideos === 'boolean' ? {musicVideos: o.musicVideos} : {}),
      versionsKeepDays: int(o.versionsKeepDays, DEFAULT_OPTIONS.versionsKeepDays),
      deleteGuardPercent: int(o.deleteGuardPercent, DEFAULT_OPTIONS.deleteGuardPercent),
    },
    schedule: {everyHours: typeof s.everyHours === 'number' ? Math.trunc(s.everyHours) : null, auto: s.auto !== false},
    removed: fields.removed === true,
    verified: Object.keys(verified).length ? (verified as PlaceConfig['verified']) : null,
  }
}

/** Every place that has a usable mode, with its registers read from the tables. */
export function readPlaces(db: DatabaseSync): PlaceConfig[] {
  const fields = new Map<string, Record<string, SyncJson>>()
  for (const r of db.prepare("SELECT k1, k2, value FROM regs WHERE scope = 'place' ORDER BY k1, k2").all() as {k1: string; k2: string; value: string}[]) {
    let f = fields.get(r.k1)
    if (!f) fields.set(r.k1, (f = {}))
    f[r.k2] = JSON.parse(r.value) as SyncJson
  }
  const out: PlaceConfig[] = []
  for (const [id, f] of fields) {
    const place = placeConfigFrom(id, f)
    if (place) out.push(place)
  }
  return out
}

/** The holds of a place as one comparable string (a coverage manifest records it, spec 6). */
export function holdsKey(holds: Holds): string {
  return holds.all ? 'all' : JSON.stringify([[...holds.spaces].sort(), [...holds.playlists].sort(), holds.favourites, [...holds.smart].sort()])
}

export function optionsKey(options: PlaceOptions): string {
  const key: unknown[] = [options.playlistFiles, options.flat, options.mp3, options.versionsKeepDays, options.deleteGuardPercent, options.companions]
  // added only once chosen, so a copy that never chose keeps its key
  if (options.musicVideos !== undefined) key.push(options.musicVideos)
  return JSON.stringify(key)
}

/** Whether the place carries music videos: the person's choice, else on for a copy that holds everything (as the core reads it). */
/** As the core reads it: until the person chooses, a copy of everything carries them, unless it already leaves out the files beside each song. */
export const placeMusicVideos = (place: {holds: Holds; options: PlaceOptions}): boolean => place.options.musicVideos ?? (place.holds.all && place.options.companions)

/** SQL for "this OR-set key has an add that no remove names" (the same text the SQL state uses). */
const LIVE = (scope: string, k1: string, k2: string) =>
  `EXISTS (SELECT 1 FROM tags a WHERE a.scope = ${scope} AND a.k1 = ${k1} AND a.k2 = ${k2} AND a.kind = 'a' ` +
  "AND NOT EXISTS (SELECT 1 FROM tags r WHERE r.scope = a.scope AND r.k1 = a.k1 AND r.k2 = a.k2 AND r.kind = 'r' AND r.actor = a.actor AND r.seq = a.seq))"

/** The live members of one named set. */
export function setMembers(db: DatabaseSync, set: string): string[] {
  const sql = `SELECT p.k2 AS el FROM present p WHERE p.scope = 'set' AND p.k1 = ? AND ${LIVE('p.scope', 'p.k1', 'p.k2')}`
  return (db.prepare(sql).all(set) as {el: string}[]).map((r) => r.el)
}

/** The items on a present playlist (the core's `songs` query; none for a playlist that is not present). */
export function playlistItems(db: DatabaseSync, playlist: string): string[] {
  const q = sqlQueries()
  if ((db.prepare(q.playlistPresent).get(playlist) as {present: number}).present !== 1) return []
  return (db.prepare(q.songs).all(playlist) as {item: string}[]).map((r) => r.item)
}

type Row = {id: string; space: string | null; heads: string; path: string | null; companions: string | null}

/**
 * Entries as they come from the tables: unsorted. Items without a placement for [mainReplica] cannot be copied and are skipped.
 * With [companions] each item's lyrics, subtitles and info file follow, after every media entry (the core decides which and
 * where: `companionCandidatesOf`, `keepCompanions`). With [musicVideos] each song's music video follows too; by default only a
 * copy that holds everything carries them (dev-docs/plans/MUSIC-VIDEOS.md decision 5).
 */
export function* desiredEntries(db: DatabaseSync, mainReplica: string, holds: Holds, companions = true, musicVideos = holds.all): Generator<DesiredEntry> {
  const favourites = holds.favourites ? new Set(setMembers(db, 'favourites')) : new Set<string>()
  const inPlaylists = new Set<string>()
  for (const pl of holds.playlists) for (const item of playlistItems(db, pl)) inPlaylists.add(item)
  const spaces = new Set(holds.spaces)
  // the fields only of an item that records a companion (most have none); read here, natively
  const gather = companions || musicVideos
  const companionSql = gather ? "CASE WHEN instr(v.fields, '\"companion:') > 0 THEN v.fields END" : 'NULL'
  const stmt = db.prepare(
    "SELECT v.id AS id, json_extract(v.fields, '$.space') AS space, v.heads AS heads, " +
      "(SELECT CASE WHEN json_type(p.value) = 'text' THEN json_extract(p.value, '$') END FROM regs p WHERE p.scope = 'placement' AND p.k1 = v.id AND p.k2 = ?) AS path, " +
      `${companionSql} AS companions FROM item_view v WHERE v.status = 'present'`,
  )
  const mediaKeys = new Set<string>()
  const withCompanions: {itemId: string; path: string; heads: number; fields: Record<string, unknown>}[] = []
  for (const r of stmt.iterate(mainReplica) as Iterable<Row>) {
    const selected = holds.all || (typeof r.space === 'string' && spaces.has(r.space)) || favourites.has(r.id) || inPlaylists.has(r.id)
    if (!selected || r.path === null) continue
    const heads = JSON.parse(r.heads) as SyncHead[]
    for (let i = 0; i < heads.length; i++) {
      const h = heads[i]
      const path = i === 0 ? r.path : conflictPath(r.path, h.content)
      if (gather) mediaKeys.add(fastKey('ascii', path))
      yield {path, itemId: r.id, revisionOp: h.op, contentId: h.content, bytes: h.size}
    }
    if (r.companions) withCompanions.push({itemId: r.id, path: r.path, heads: heads.length, fields: JSON.parse(r.companions) as Record<string, unknown>})
  }
  if (withCompanions.length) yield* keepCompanions(companionCandidatesOf(withCompanions, musicVideos, companions), (key) => mediaKeys.has(key))
}

/** D sorted the way the planner needs it: by (name key, path). */
export function desiredSorted(db: DatabaseSync, mainReplica: string, holds: Holds, rules: NameRulesId, companions = true, musicVideos = holds.all): DesiredEntry[] {
  const list = [...desiredEntries(db, mainReplica, holds, companions, musicVideos)]
  const keyed = list.map((e) => ({e, k: fastKey(rules, e.path)}))
  keyed.sort((a, b) => (a.k !== b.k ? (a.k < b.k ? -1 : 1) : a.e.path === b.e.path ? 0 : a.e.path < b.e.path ? -1 : 1))
  return keyed.map((x) => x.e)
}

/** The present spaces or playlists with their registers (a smart space has no folder and cannot be held by id). */
export function readContainers(db: DatabaseSync, scope: 'space' | 'playlist'): {id: string; fields: Record<string, SyncJson>}[] {
  const sql = `SELECT p.k1 AS id FROM present p WHERE p.scope = ? AND p.k2 = '' AND ${LIVE('p.scope', 'p.k1', "''")} ORDER BY p.k1`
  const reg = db.prepare('SELECT k2, value FROM regs WHERE scope = ? AND k1 = ?')
  const out: {id: string; fields: Record<string, SyncJson>}[] = []
  for (const r of db.prepare(sql).all(scope) as {id: string}[]) {
    const fields: Record<string, SyncJson> = {}
    for (const f of reg.all(scope, r.id) as {k2: string; value: string}[]) fields[f.k2] = JSON.parse(f.value) as SyncJson
    out.push({id: r.id, fields})
  }
  return out
}

/** Present items whose revisions are in a content conflict (more than one head), with the heads in the desired set's order. */
export function conflictItems(db: DatabaseSync, limit = 200): {id: string; title: string; heads: SyncHead[]}[] {
  const rows = db
    .prepare("SELECT id, json_extract(fields, '$.title') AS title, heads FROM item_view WHERE status = 'present' AND conflict = 1 ORDER BY id LIMIT ?")
    .all(limit) as {id: string; title: string | null; heads: string}[]
  return rows.map((r) => ({id: r.id, title: r.title ?? '', heads: JSON.parse(r.heads) as SyncHead[]}))
}

/** Item ids an `item.delete` removed (status deleted): the planner's `deleted` list. */
export function deletedItems(db: DatabaseSync): string[] {
  return (db.prepare("SELECT id FROM item_view WHERE status = 'deleted' ORDER BY id").all() as {id: string}[]).map((r) => r.id)
}
