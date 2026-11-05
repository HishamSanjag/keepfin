// What the library merge (gui/desktop/merge) reads from a store: every item as one row (with the HLC of its item.add, which the state
// does not keep: the segments do), and the small metadata (spaces, playlists with their songs, the sets that belong to playlists).
// Runs wherever the store runs: in the worker thread in the app, in the caller's thread for a store opened directly.
import type {SyncJson} from '../../shared/core/sync.js'
import {segmentFilesSync, segmentOps, type KeepfinStore} from './store.js'

/** One item of a library, as the merge planner wants to see it. Wire units, as in the sync views. */
export type ScanRow = {
  id: string
  /** The HLC (wire text) of the earliest `item.add` of this item. */
  added: string
  addActor: string
  addSeq: number
  fields: Record<string, SyncJson>
  plays: number
  /** The only revision's content id and size; null when the item has none yet or its revisions conflict. */
  head: {content: string; size: number} | null
  /** Replica id to relative path (the placements that are not cleared). */
  placements: Record<string, string>
  /** Field name to the HLC (wire text) of its register. */
  clocks: Record<string, string>
  favourite: boolean
  tags: string[]
}

export type MergeMeta = {
  spaces: {id: string; present: boolean; fields: Record<string, SyncJson>}[]
  playlists: {id: string; present: boolean; fields: Record<string, SyncJson>; songs: {item: string; entry: string; key: string}[]}[]
  /** `seen:`, `new:` and `removed:` sets of the playlists. */
  sets: Record<string, string[]>
  /** The profile's routes (wire space ids), when set. */
  routes: {music?: string; other?: string} | null
}

const wire = (wall: number, counter: number, actor: string) => `${String(wall).padStart(13, '0')}.${String(counter).padStart(6, '0')}.${actor}`

/** The earliest item.add of every item in the segments of [store] (its outbox must have been flushed). */
export function addIndex(store: KeepfinStore): Map<string, {hlc: string; actor: string; seq: number}> {
  const adds = new Map<string, {hlc: string; actor: string; seq: number}>()
  for (const op of segmentOps(segmentFilesSync(store.opsPath), store.libraryId)) {
    if (!op.line.includes('"kind":"item.add"')) continue
    let parsed: {id?: string; hlc?: string; kind?: string; body?: {item?: string}}
    try {
      parsed = JSON.parse(op.line)
    } catch {
      continue
    }
    if (parsed.kind !== 'item.add' || typeof parsed.body?.item !== 'string' || typeof parsed.hlc !== 'string' || typeof parsed.id !== 'string') continue
    const at = parsed.id.lastIndexOf(':')
    const known = adds.get(parsed.body.item)
    if (!known || parsed.hlc < known.hlc) adds.set(parsed.body.item, {hlc: parsed.hlc, actor: parsed.id.slice(0, at), seq: Number(parsed.id.slice(at + 1))})
  }
  return adds
}

/** Every present item, one row at a time. Call `store.flush()` first. */
export function* scanRows(store: KeepfinStore): Generator<ScanRow> {
  const adds = addIndex(store)
  const sql = store.state
  const favourites = new Set(sql.setsNamed(['favourites']).favourites)
  const tags = sql.setsByPrefix('tags:')
  const clocks = sql.prep("SELECT k2 AS field, wall, counter, hactor FROM regs WHERE scope = 'item' AND k1 = ?")
  for (const {item, placements} of sql.iterateViews()) {
    if (item.status === 'deleted') continue
    const add = adds.get(item.id)
    if (!add) continue
    const clockOf: Record<string, string> = {}
    for (const r of clocks.all(item.id) as {field: string; wall: number; counter: number; hactor: string}[]) clockOf[r.field] = wire(r.wall, r.counter, r.hactor)
    const where: Record<string, string> = {}
    for (const [replica, path] of Object.entries(placements ?? {})) if (typeof path === 'string' && path) where[replica] = path
    yield {
      id: item.id,
      added: add.hlc,
      addActor: add.actor,
      addSeq: add.seq,
      fields: item.fields,
      plays: item.plays,
      head: item.heads.length === 1 ? {content: item.heads[0].content, size: item.heads[0].size} : null,
      placements: where,
      clocks: clockOf,
      favourite: favourites.has(item.id),
      tags: tags.get(`tags:${item.id}`) ?? [],
    }
  }
}

export function readMeta(store: KeepfinStore): MergeMeta {
  const sql = store.state
  const spaces = sql.containers('space').filter((s) => s.present)
  const playlists = sql
    .containers('playlist')
    .filter((p) => p.present)
    .map((p) => ({id: p.id, present: true, fields: p.fields, songs: sql.songs(p.id).map((s) => ({item: s.item, entry: s.entry, key: s.key}))}))
  const sets: Record<string, string[]> = {}
  for (const prefix of ['seen:', 'new:', 'removed:']) for (const [name, members] of sql.setsByPrefix(prefix)) sets[name] = members
  const routes = sql.profile().routes
  const out = routes && typeof routes === 'object' && !Array.isArray(routes) ? (routes as {music?: string; other?: string}) : null
  return {spaces, playlists, sets, routes: out}
}
