// Which items a smart space gathers, read from the SQL state: what a copy that holds a smart space holds (spec 2). A smart space stores
// nothing; it names rules over every space's items. The rules are the core's (`matchesSmartRules`, the same ones the library screens
// use), so a copy holds exactly the items the space shows. Rule values name the app's own ids (a space as the library knows it, a
// collection by its id), so each item is described with those, not with the wire ids of the state.
import type {DatabaseSync} from 'node:sqlite'
import type {LibraryItem, SmartRules} from '../../shared/library.js'
import {isKeptSpace, matchesSmartRules} from '../../shared/core/spaces.js'
import {BUILTIN_SPACE_IDS} from '../store/mapping.js'
import {LIVE, playlistItems, readContainers, setMembers} from './desired.js'

const builtinByWire = new Map(Object.entries(BUILTIN_SPACE_IDS).map(([id, wire]) => [wire, id]))

const asString = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined)
const strings = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [])
const KINDS = new Set(['video', 'audio', 'image', 'other'])

/** The rules of a smart space as the library stores them; null for a space that is not smart or has no rules yet. */
function rulesOf(fields: Record<string, unknown>): SmartRules | null {
  if (fields.type !== 'smart') return null
  const rules = fields.rules as Partial<SmartRules> | undefined
  if (!rules || typeof rules !== 'object' || !Array.isArray(rules.rules)) return null
  return {match: rules.match === 'all' ? 'all' : 'any', rules: rules.rules, ...(rules.onlyKind ? {onlyKind: rules.onlyKind} : {}), ...(Array.isArray(rules.excludeSpaces) ? {excludeSpaces: rules.excludeSpaces} : {})}
}

/** The smart spaces that exist (id as the state holds it, with their rules). */
export function readSmartSpaces(db: DatabaseSync): {id: string; name: string; rules: SmartRules}[] {
  const out: {id: string; name: string; rules: SmartRules}[] = []
  for (const s of readContainers(db, 'space')) {
    // Favorites is a smart space every library has; a copy holds it as its own Favourites row
    if (isKeptSpace(asString(s.fields.legacyId) ?? builtinByWire.get(s.id) ?? s.id)) continue
    const rules = rulesOf(s.fields)
    if (rules) out.push({id: s.id, name: asString(s.fields.name) ?? 'Smart space', rules})
  }
  return out
}

/** What the smart spaces a place holds gather, or undefined when it holds none. */
export const heldSmart = (db: DatabaseSync, holds: {smart: readonly string[]}): Set<string> | undefined => (holds.smart.length > 0 ? smartMembers(db, holds.smart) : undefined)

type Row = {id: string; fields: string; plays: number}

type Described = (id: string, fields: Record<string, unknown>, plays: number) => LibraryItem

/** Reads what the rules need to know about an item that its row does not hold: the library's own space ids, hearts, tags and collections. */
function describer(db: DatabaseSync, spaces: {rules: SmartRules}[], onlyItem?: string): {describe: Described; collectionsOf: (id: string) => string[]} {
  // the library's own id of each space (a built-in one has a fixed id, an older one keeps the id it was made with)
  const modelOf = new Map<string, string>()
  for (const s of readContainers(db, 'space')) modelOf.set(s.id, asString(s.fields.legacyId) ?? builtinByWire.get(s.id) ?? s.id)
  const model = (wire: string | undefined) => (wire === undefined ? '' : modelOf.get(wire) ?? builtinByWire.get(wire) ?? wire)
  const favourites = new Set(setMembers(db, 'favourites'))
  const userTags = new Map<string, string[]>()
  const tagSql = `SELECT p.k1 AS k1, p.k2 AS k2 FROM present p WHERE p.scope = 'set' AND ${onlyItem === undefined ? "p.k1 LIKE 'tags:%'" : 'p.k1 = ?'} AND ${LIVE('p.scope', 'p.k1', 'p.k2')}`
  const tagRows = (onlyItem === undefined ? db.prepare(tagSql).all() : db.prepare(tagSql).all(`tags:${onlyItem}`)) as {k1: string; k2: string}[]
  for (const r of tagRows) {
    const id = r.k1.slice('tags:'.length)
    const list = userTags.get(id)
    if (list) list.push(r.k2)
    else userTags.set(id, [r.k2])
  }
  // collections are only looked up when a rule names one
  const collections = new Map<string, string[]>()
  if (spaces.some((s) => s.rules.rules.some((r) => r.field === 'collection'))) {
    for (const pl of readContainers(db, 'playlist')) {
      for (const item of playlistItems(db, pl.id)) {
        if (onlyItem !== undefined && item !== onlyItem) continue
        const list = collections.get(item)
        if (list) list.push(pl.id)
        else collections.set(item, [pl.id])
      }
    }
  }
  const describe: Described = (id, fields, plays) => {
    const kind = asString(fields.media)
    return {
      id,
      space: model(asString(fields.space)),
      kind: kind && KINDS.has(kind) ? kind : 'other',
      tags: strings(fields.tags),
      userTags: userTags.get(id) ?? [],
      artist: asString(fields.artist),
      albumArtist: asString(fields.albumArtist),
      album: asString(fields.album),
      favorite: favourites.has(id),
      plays,
      addedAt: typeof fields.addedAt === 'number' ? fields.addedAt : 0,
      sourceUrl: asString(fields.sourceUrl),
      sourceId: asString(fields.sourceId),
    } as unknown as LibraryItem
  }
  return {describe, collectionsOf: (id) => collections.get(id) ?? []}
}

/** Calls [found] with each present item (one item with [onlyItem]) and the ids of the smart spaces among [spaces] that gather it. */
function scan(db: DatabaseSync, spaces: {id: string; rules: SmartRules}[], now: number, found: (item: string, spaceIds: string[]) => void, onlyItem?: string): void {
  if (spaces.length === 0) return
  const {describe, collectionsOf} = describer(db, spaces, onlyItem)
  const sql = "SELECT v.id AS id, v.fields AS fields, v.plays AS plays FROM item_view v WHERE v.status = 'present'" + (onlyItem === undefined ? '' : ' AND v.id = ?')
  const stmt = db.prepare(sql)
  for (const r of (onlyItem === undefined ? stmt.iterate() : stmt.iterate(onlyItem)) as Iterable<Row>) {
    let fields: Record<string, unknown>
    try {
      fields = JSON.parse(r.fields) as Record<string, unknown>
    } catch {
      continue
    }
    const item = describe(r.id, fields, r.plays)
    const hit = spaces.filter((s) => matchesSmartRules(s.rules, item, {now, collectionsOf})).map((s) => s.id)
    if (hit.length) found(r.id, hit)
  }
}

/**
 * The ids of the present items that any of [smartIds] gathers. Smart spaces that no longer exist gather nothing. One pass over the
 * items, done only when a copy holds a smart space.
 */
export function smartMembers(db: DatabaseSync, smartIds: readonly string[], now = Date.now()): Set<string> {
  const wanted = new Set(smartIds)
  const members = new Set<string>()
  scan(db, readSmartSpaces(db).filter((s) => wanted.has(s.id)), now, (id) => members.add(id))
  return members
}

/** The smart spaces (their ids) that gather one item: for the line that says where a file is kept. */
export function smartSpacesOf(db: DatabaseSync, itemId: string, now = Date.now()): string[] {
  const out: string[] = []
  scan(db, readSmartSpaces(db), now, (_id, ids) => out.push(...ids), itemId)
  return out
}
