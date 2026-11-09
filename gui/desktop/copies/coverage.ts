// What each copy holds, and how much of it is kept: the library's desired set against the latest coverage manifest (spec 6).
// Read-only; runs in the copies worker over its own connection to the library's state database.
import type {DatabaseSync} from 'node:sqlite'
import type {CopyCell, CopyRow} from '../../shared/copies.js'
import {desiredEntries, placeMusicVideos, playlistItems, readContainers, setMembers, type PlaceConfig} from '../sqlstate/desired.js'
import {heldSmart, readSmartSpaces, smartMembers} from '../sqlstate/smart.js'
import {latestManifest} from './manifest.js'

export interface Coverage {
  /** Files in D / of those, verified by the latest manifest / not yet. */
  total: number
  kept: number
  waiting: number
  /** A manifest exists (the copy has been verified at least once). */
  verified: boolean
  verifiedAt: number | null
  cells: Record<string, CopyCell>
}

export interface Rows {
  rows: CopyRow[]
  /** Each space's folder under the Keepfin folder ('' when unknown), by wire id: where an imported file belongs. */
  spaceFolders: Record<string, string>
}

export function readRows(db: DatabaseSync): Rows {
  const smartRows: CopyRow[] = readSmartSpaces(db).map((s) => ({key: `smart:${s.id}`, kind: 'smart', id: s.id, name: s.name}))
  const rows: CopyRow[] = []
  const spaceFolders: Record<string, string> = {}
  for (const s of readContainers(db, 'space')) {
    if (s.fields.type === 'smart') continue
    const name = typeof s.fields.name === 'string' ? s.fields.name : 'Space'
    rows.push({key: `space:${s.id}`, kind: 'space', id: s.id, name})
    spaceFolders[s.id] = typeof s.fields.folder === 'string' && s.fields.linked !== true ? s.fields.folder : ''
  }
  rows.push({key: 'favourites', kind: 'favourites', id: 'favourites', name: 'Favourites'})
  for (const p of readContainers(db, 'playlist')) {
    const name = typeof p.fields.name === 'string' ? p.fields.name : 'Playlist'
    rows.push({key: `playlist:${p.id}`, kind: 'playlist', id: p.id, name})
  }
  rows.push(...smartRows)
  return {rows, spaceFolders}
}

const lineKey = (itemId: string, contentId: string, path: string) => `${itemId}\n${contentId}\n${path}`

/**
 * Kept = the manifest lists the entry's item at its current revision and path. A row is `kept` when none of its held items
 * waits, `waiting` when some do, `review` when an item that needs a decision is in it, `notHeld` when the place does not hold it.
 */
export async function coverage(db: DatabaseSync, place: PlaceConfig, mainRoot: string, mainReplica: string, rows: CopyRow[], reviewItems: ReadonlySet<string>): Promise<Coverage> {
  const manifest = await latestManifest(mainRoot, place.id).catch(() => null)
  const keptKeys = new Set<string>()
  if (manifest) for (const line of manifest.lines()) keptKeys.add(lineKey(line.itemId, line.contentId, line.path))
  let total = 0
  let kept = 0
  const waitingItems = new Set<string>()
  const smart = heldSmart(db, place.holds)
  for (const e of desiredEntries(db, mainReplica, place.holds, place.options.companions, placeMusicVideos(place), smart)) {
    total++
    if (keptKeys.has(lineKey(e.itemId, e.contentId, e.path))) kept++
    else waitingItems.add(e.itemId)
  }
  keptKeys.clear()
  const cells: Record<string, CopyCell> = {}
  const spaceStmt = db.prepare("SELECT id FROM item_view WHERE status = 'present' AND json_extract(fields, '$.space') = ?")
  for (const row of rows) {
    const held = place.holds.all || (row.kind === 'space' && place.holds.spaces.includes(row.id)) || (row.kind === 'playlist' && place.holds.playlists.includes(row.id)) || (row.kind === 'favourites' && place.holds.favourites) || (row.kind === 'smart' && place.holds.smart.includes(row.id))
    if (!held) {
      cells[row.key] = 'notHeld'
      continue
    }
    let hasReview = false
    let hasWaiting = false
    const visit = (id: string) => {
      if (reviewItems.has(id)) hasReview = true
      if (waitingItems.has(id)) hasWaiting = true
    }
    if (row.kind === 'space') for (const r of spaceStmt.iterate(row.id) as Iterable<{id: string}>) visit(r.id)
    else if (row.kind === 'playlist') for (const id of playlistItems(db, row.id)) visit(id)
    else if (row.kind === 'smart') for (const id of smartMembers(db, [row.id])) visit(id)
    else for (const id of setMembers(db, 'favourites')) visit(id)
    cells[row.key] = hasReview ? 'review' : hasWaiting ? 'waiting' : 'kept'
  }
  return {total, kept, waiting: total - kept, verified: !!manifest, verifiedAt: manifest?.header.at ?? null, cells}
}
