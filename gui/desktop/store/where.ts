// "Where your files are", counted in the library's state database (dev-docs/plans/WHERE-FILES.md). Every place that keeps the library is a
// replica, and an item's placements say which replicas have a file for it, so the counts are read, not guessed. One pass over the
// placement registers (a range scan of the primary key, joined to the item projection by its key) gives, per replica and per kind and
// space, how many files it holds, how many bytes, and how many of them no other replica holds. Nothing is scanned per render: the host
// caches the answer and asks again only after a library change.
//
// A copy on a drive or in a cloud account does not write placements (its files are listed by its manifest). The host says which copies
// are complete (everything they should hold was verified), and what each holds; a file that only this computer has and a complete copy
// also holds is not a single. A copy that has not finished counts for nothing yet.
import {smartMembers, smartSpacesOf} from '../sqlstate/smart.js'
import type {SqlState} from '../sqlstate/sqlstate.js'

export type CopyCover = {
  id: string
  /** Space ids (as the library stores them), playlist ids, favourites, smart space ids (a rule decides, so they are counted item by item). */
  holds: {all: boolean; spaces: string[]; playlists: string[]; favourites: boolean; smart?: string[]}
  /** Items the copy's last finished run lacks: it holds the rest of what it selects. */
  except?: string[]
}

/** What one replica holds: its placements joined to live items. */
export type ReplicaTotal = {replica: string; /** The actors that wrote its placements (to tell which device it is). */ actors: string[]; items: number; bytes: number}

/** Items whose only placement is on [replica], by kind (`media`) and space. */
export type SoloGroup = {replica: string; media: string; space: string; count: number}

export type WhereCounts = {
  /** Live items in the library. */
  items: number
  replicas: ReplicaTotal[]
  solos: SoloGroup[]
  /** What each complete copy holds (items and bytes), by copy id. */
  copies: Record<string, {items: number; bytes: number}>
}

const SOLO = "NOT EXISTS (SELECT 1 FROM regs o WHERE o.scope = 'placement' AND o.k1 = r.k1 AND o.k2 <> r.k2 AND o.value <> 'null')"

/** One pass: per replica, writer, kind and space: files, bytes and files that no other replica has. */
const GROUPED =
  'SELECT r.k2 AS replica, r.oactor AS actor, coalesce(json_extract(v.fields, \'$.media\'), \'\') AS media, coalesce(json_extract(v.fields, \'$.space\'), \'\') AS space, ' +
  `count(*) AS n, sum(coalesce(json_extract(v.heads, '$[0].size'), 0)) AS bytes, sum(CASE WHEN ${SOLO} THEN 1 ELSE 0 END) AS solo ` +
  "FROM regs r JOIN item_view v ON v.id = r.k1 WHERE r.scope = 'placement' AND r.value <> 'null' AND v.status <> 'deleted' GROUP BY r.k2, r.oactor, media, space"

/** The items of the main replica one by one (only when a copy holds playlists or favourites, which are not a column). */
const MAIN_ITEMS =
  "SELECT v.id AS id, coalesce(json_extract(v.fields, '$.media'), '') AS media, coalesce(json_extract(v.fields, '$.space'), '') AS space, coalesce(json_extract(v.heads, '$[0].size'), 0) AS bytes, " +
  `${SOLO} AS solo FROM regs r JOIN item_view v ON v.id = r.k1 WHERE r.scope = 'placement' AND r.k2 = ? AND r.value <> 'null' AND v.status <> 'deleted'`

type Grouped = {replica: string; actor: string; media: string; space: string; n: number; bytes: number; solo: number}
type MainItem = {id: string; media: string; space: string; bytes: number; solo: number}

/** [completeCopies]: the copies that hold everything they should, each with what it holds. [mainReplica]: this computer's replica. */
export function whereCounts(sql: SqlState, mainReplica: string, completeCopies: readonly CopyCover[] = []): WhereCounts {
  const items = (sql.prep("SELECT count(*) AS n FROM item_view WHERE status <> 'deleted'").get() as {n: number}).n
  const totals = new Map<string, {actors: Set<string>; items: number; bytes: number}>()
  const soloGroups = new Map<string, SoloGroup>()
  const main = {items: 0, bytes: 0, bySpace: new Map<string, {items: number; bytes: number}>()}
  for (const row of sql.prep(GROUPED).iterate() as Iterable<Grouped>) {
    let total = totals.get(row.replica)
    if (!total) totals.set(row.replica, (total = {actors: new Set(), items: 0, bytes: 0}))
    total.actors.add(row.actor)
    total.items += row.n
    total.bytes += row.bytes
    if (row.solo > 0) {
      const key = `${row.replica}\0${row.media}\0${row.space}`
      const group = soloGroups.get(key)
      if (group) group.count += row.solo
      else soloGroups.set(key, {replica: row.replica, media: row.media, space: row.space, count: row.solo})
    }
    if (row.replica === mainReplica) {
      main.items += row.n
      main.bytes += row.bytes
      const space = main.bySpace.get(row.space) ?? {items: 0, bytes: 0}
      space.items += row.n
      space.bytes += row.bytes
      main.bySpace.set(row.space, space)
    }
  }

  const copies: WhereCounts['copies'] = {}
  const itemLevel = completeCopies.some((copy) => (copy.except ?? []).length > 0 || (!copy.holds.all && (copy.holds.favourites || copy.holds.playlists.length > 0 || (copy.holds.smart ?? []).length > 0)))
  if (!itemLevel) {
    // everything or whole spaces: the per-space totals of this computer's files say it all
    for (const copy of completeCopies) {
      const held = {items: 0, bytes: 0}
      if (copy.holds.all) {
        held.items = main.items
        held.bytes = main.bytes
      } else {
        for (const id of copy.holds.spaces) {
          const space = main.bySpace.get(id)
          if (space) {
            held.items += space.items
            held.bytes += space.bytes
          }
        }
      }
      copies[copy.id] = held
    }
    // a file only this computer has, that a complete copy also holds, is on two places
    if (completeCopies.length) {
      for (const [key, group] of soloGroups) {
        if (group.replica !== mainReplica) continue
        if (completeCopies.some((copy) => copy.holds.all || copy.holds.spaces.includes(group.space))) soloGroups.delete(key)
      }
    }
  } else {
    // playlists and favourites are not a column of the item: look at this computer's files one by one, once
    const favourites = new Set(sql.setsNamed(['favourites']).favourites ?? [])
    const inList = completeCopies.map((copy) => {
      const set = new Set<string>()
      for (const playlist of copy.holds.playlists) for (const song of sql.songs(playlist)) set.add(song.item)
      return set
    })
    const lacking = completeCopies.map((copy) => new Set(copy.except ?? []))
    const inSmart = completeCopies.map((copy) => ((copy.holds.smart ?? []).length ? smartMembers(sql.db, copy.holds.smart ?? []) : new Set<string>()))
    for (const copy of completeCopies) copies[copy.id] = {items: 0, bytes: 0}
    const covered = new Map<string, number>()
    for (const row of sql.prep(MAIN_ITEMS).iterate(mainReplica) as Iterable<MainItem>) {
      let any = false
      completeCopies.forEach((copy, index) => {
        const h = copy.holds
        if (!lacking[index].has(row.id) && (h.all || h.spaces.includes(row.space) || (h.favourites && favourites.has(row.id)) || inList[index].has(row.id) || inSmart[index].has(row.id))) {
          const held = copies[copy.id]
          held.items++
          held.bytes += row.bytes
          any = true
        }
      })
      if (any && row.solo) {
        const key = `${mainReplica}\0${row.media}\0${row.space}`
        covered.set(key, (covered.get(key) ?? 0) + 1)
      }
    }
    for (const [key, count] of covered) {
      const group = soloGroups.get(key)
      if (!group) continue
      group.count -= count
      if (group.count <= 0) soloGroups.delete(key)
    }
  }

  return {
    items,
    replicas: [...totals].map(([replica, total]) => ({replica, actors: [...total.actors].sort(), items: total.items, bytes: total.bytes})),
    solos: [...soloGroups.values()],
    copies,
  }
}

/** What the per-file line needs to know about one item: who has a file for it, and what a copy would select it by. */
export type WhereItemFacts = {id?: string; replicas: {replica: string; actor: string}[]; space: string; favourite: boolean; playlists: string[]; smart?: string[]}

export function whereItem(sql: SqlState, itemId: string): WhereItemFacts {
  const replicas = (sql.prep("SELECT k2 AS replica, oactor AS actor FROM regs WHERE scope = 'placement' AND k1 = ? AND value <> 'null' ORDER BY k2").all(itemId) as {replica: string; actor: string}[]).map((r) => ({replica: r.replica, actor: r.actor}))
  const space = (sql.prep("SELECT coalesce(json_extract(fields, '$.space'), '') AS space FROM item_view WHERE id = ?").get(itemId) as {space: string} | undefined)?.space ?? ''
  // a live member of the favourites set: an add that no remove names
  const favourite =
    sql
      .prep(
        "SELECT 1 AS yes FROM tags a WHERE a.scope = 'set' AND a.k1 = 'favourites' AND a.k2 = ? AND a.kind = 'a' " +
          "AND NOT EXISTS (SELECT 1 FROM tags r WHERE r.scope = a.scope AND r.k1 = a.k1 AND r.k2 = a.k2 AND r.kind = 'r' AND r.actor = a.actor AND r.seq = a.seq) LIMIT 1",
      )
      .get(itemId) !== undefined
  const playlists = (sql.prep('SELECT DISTINCT playlist FROM entries WHERE item = ? AND entry NOT IN (SELECT entry FROM removed_entries)').all(itemId) as {playlist: string}[]).map((r) => r.playlist)
  return {id: itemId, replicas, space, favourite, playlists, smart: smartSpacesOf(sql.db, itemId)}
}
