// Checkpoint parts (sync spec 10.4) written straight from the SQL tables.
//
// The bytes are the ones the core writes for the same state (`partBytes` of an in-memory log; views.test.ts compares them
// byte for byte), but the state is never built: the rows of one shard are read in key order by a few cursors and written
// as the canonical JSON line of the part body, one item at a time. The sha-256 of the body is the platform's.
//
// Canonical form (spec section 8): no spaces, object keys in UTF-16 code unit order, integers only. The register values
// are kept in the tables as their canonical text, so they are copied as they are.
import {createHash} from 'node:crypto'
import type {DatabaseSync, StatementSync} from 'node:sqlite'
import {FORMAT, SEMANTICS} from '../../shared/core/sync.js'

type Row = unknown[]

/** A forward cursor over rows that are in key order, grouped by the string in column 0. */
class Rows {
  private readonly it: Iterator<unknown>
  private head: Row | undefined
  private done = false

  constructor(stmt: StatementSync, ...params: (string | number)[]) {
    stmt.setReturnArrays(true)
    this.it = (stmt.iterate(...params) as Iterable<unknown>)[Symbol.iterator]()
    this.advance()
  }

  private advance(): void {
    if (this.done) return
    const n = this.it.next()
    if (n.done) {
      this.done = true
      this.head = undefined
    } else this.head = n.value as Row
  }

  get peek(): Row | undefined {
    return this.head
  }

  /** The rows whose first cell is [key] (skipping any that sort before it). */
  group(key: string): Row[] {
    const out: Row[] = []
    while (this.head && (this.head[0] as string) < key) this.advance()
    while (this.head && this.head[0] === key) {
      out.push(this.head)
      this.advance()
    }
    return out
  }

  next(): Row | undefined {
    const row = this.head
    this.advance()
    return row
  }
}

const q = (text: string) => JSON.stringify(text)
const opText = (actor: unknown, seq: unknown) => `"${actor as string}:${seq as number}"`
const pad = (n: number, width: number) => String(n).padStart(width, '0')
const byKey = (a: [string, string], b: [string, string]) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)

/** `{"hlc":...,"op":...,"value":...}` for a register row (wall, counter, hactor, oactor, oseq, value from column [at]). */
function reg(row: Row, at: number): string {
  return `{"hlc":"${pad(row[at] as number, 13)}.${pad(row[at + 1] as number, 6)}.${row[at + 2] as string}","op":${opText(row[at + 3], row[at + 4])},"value":${row[at + 5] as string}}`
}

/** `{"name":reg,...}` for the register rows of one key, names in UTF-16 order. */
function regMap(rows: Row[], nameAt: number, valueAt: number): string {
  const parts: [string, string][] = rows.map((r) => [r[nameAt] as string, reg(r, valueAt)])
  if (parts.length > 1) parts.sort(byKey)
  return `{${parts.map(([name, text]) => `${q(name)}:${text}`).join(',')}}`
}

/** `[ids]` of the tag rows of one kind, in the order of the rows (actor, then seq as a number). */
function ids(rows: Row[], kind: string, at: number): string {
  const out: string[] = []
  for (const r of rows) if (r[at] === kind) out.push(opText(r[at + 1], r[at + 2]))
  return `[${out.join(',')}]`
}

const orSet = (rows: Row[], at: number) => `{"adds":${ids(rows, 'a', at)},"removes":${ids(rows, 'r', at)}}`

/** The ranges of the first key of a shard: one hex digit, or everything else (`meta`). */
function range(shard: string, col: string): string {
  if (shard === 'meta') return `(${col} < '0' OR (${col} >= ':' AND ${col} < 'a') OR ${col} >= 'g')`
  return `${col} >= '${shard}' AND ${col} < '${shard === '9' ? ':' : shard === 'f' ? 'g' : String.fromCharCode(shard.charCodeAt(0) + 1)}'`
}

export interface PartHooks {
  /** Called now and then so the thread can be handed back. */
  yield(): Promise<void>
}

/** The part file of [shard]. */
export async function writePart(db: DatabaseSync, library: string, shard: string, coverage: Record<string, number>, maxHlc: string | null, hooks: PartHooks): Promise<Uint8Array> {
  const meta = shard === 'meta'
  const prep = (sql: string) => db.prepare(sql)
  const inShard = range(shard, 'k1')
  const chunks: string[] = []
  let sinceYield = 0
  const tick = async (weight: number) => {
    sinceYield += weight
    if (sinceYield >= 20_000) {
      sinceYield = 0
      await hooks.yield()
    }
  }

  // entries: {"<entry>":{"item":..,"op":..,"playlist":..}}
  chunks.push('{"entries":{')
  {
    const rows = new Rows(prep(`SELECT entry, playlist, item, actor, seq FROM entries WHERE ${range(shard, 'entry')} ORDER BY entry`))
    let first = true
    for (let r = rows.next(); r; r = rows.next()) {
      chunks.push(`${first ? '' : ','}${q(r[0] as string)}:{"item":${q(r[2] as string)},"op":${opText(r[3], r[4])},"playlist":${q(r[1] as string)}}`)
      first = false
      await tick(3)
    }
  }
  // entryKey: registers keyed by entry
  chunks.push('},"entryKey":{')
  {
    const rows = new Rows(prep(`SELECT k1, wall, counter, hactor, oactor, oseq, value FROM regs WHERE scope = 'entryKey' AND ${inShard} ORDER BY k1`))
    let first = true
    for (let r = rows.next(); r; r = rows.next()) {
      chunks.push(`${first ? '' : ','}${q(r[0] as string)}:${reg(r, 1)}`)
      first = false
      await tick(3)
    }
  }
  // findings
  {
    const rows = prep(meta ? "SELECT text FROM findings WHERE text NOT GLOB 'entry-conflict:[0-9a-f]*'" : `SELECT text FROM findings WHERE text GLOB 'entry-conflict:${shard}*'`)
    rows.setReturnArrays(true)
    const texts = (rows.all() as unknown as Row[]).map((r) => r[0] as string).sort()
    chunks.push(`},"findings":[${texts.map(q).join(',')}],"items":{`)
  }
  // items, one at a time: the cursors run in step over the sorted ids
  {
    const present = new Rows(prep(`SELECT k1 FROM present WHERE scope = 'item' AND ${inShard} ORDER BY k1`))
    const tags = new Rows(prep(`SELECT k1, kind, actor, seq FROM tags WHERE scope = 'item' AND ${inShard} AND k2 = '' ORDER BY k1, kind, actor, seq`))
    const regs = new Rows(prep(`SELECT k1, k2, wall, counter, hactor, oactor, oseq, value FROM regs WHERE scope = 'item' AND ${inShard} ORDER BY k1, k2`))
    const revisions = new Rows(prep(`SELECT item, actor, seq, content, size FROM revisions WHERE ${range(shard, 'item')} ORDER BY item, actor, seq`))
    const parents = new Rows(prep(`SELECT item, actor, seq, pactor, pseq FROM rev_parents WHERE ${range(shard, 'item')} ORDER BY item, actor, seq, pactor, pseq`))
    let first = true
    for (let p = present.next(); p; p = present.next()) {
      const id = p[0] as string
      const tagRows = tags.group(id)
      const regRows = regs.group(id)
      const revRows = revisions.group(id)
      const parentRows = parents.group(id)
      let revisionsText = ''
      if (revRows.length) {
        const byParent = new Map<string, string[]>()
        for (const r of parentRows) {
          const key = `${r[1] as string}:${r[2] as number}`
          const list = byParent.get(key)
          const text = opText(r[3], r[4])
          if (list) list.push(text)
          else byParent.set(key, [text])
        }
        const events: [string, string][] = revRows.map((r) => {
          const key = `${r[1] as string}:${r[2] as number}`
          return [key, `{"content":${q(r[3] as string)},"parents":[${(byParent.get(key) ?? []).join(',')}],"size":${r[4] as number}}`]
        })
        if (events.length > 1) events.sort(byKey) // the keys are op ids as text, in text order
        revisionsText = events.map(([key, text]) => `${q(key)}:${text}`).join(',')
      }
      chunks.push(`${first ? '' : ','}${q(id)}:{"deletedHeads":${ids(tagRows, 'h', 1)},"exists":${orSet(tagRows, 1)},"fields":${regMap(regRows, 1, 2)},"revisions":{${revisionsText}}}`)
      first = false
      await tick(2 + tagRows.length + regRows.length * 2 + revRows.length + parentRows.length)
    }
  }
  // placements: {"<item>":{"<place>":reg}}
  chunks.push('},"placements":{')
  {
    const rows = new Rows(prep(`SELECT k1, k2, wall, counter, hactor, oactor, oseq, value FROM regs WHERE scope = 'placement' AND ${inShard} ORDER BY k1, k2`))
    let first = true
    for (let id = rows.peek?.[0] as string | undefined; id !== undefined; id = rows.peek?.[0] as string | undefined) {
      chunks.push(`${first ? '' : ','}${q(id)}:${regMap(rows.group(id), 1, 2)}`)
      first = false
      await tick(3)
    }
  }
  // the keys of the other scopes live in the meta shard only
  if (meta) {
    chunks.push('},"places":{')
    chunks.push(groupedRegs(prep, 'place'))
    chunks.push('},"playlists":{')
    chunks.push(entities(prep, 'playlist'))
    chunks.push('},"profile":')
    {
      const rows = prep("SELECT k1, wall, counter, hactor, oactor, oseq, value FROM regs WHERE scope = 'profile'")
      rows.setReturnArrays(true)
      const list: Row[] = rows.all() as unknown as Row[]
      chunks.push(regMap(list, 0, 1))
    }
  } else {
    chunks.push('},"places":{},"playlists":{},"profile":{}')
  }
  // removed entries
  {
    const rows = prep(`SELECT entry FROM removed_entries WHERE ${range(shard, 'entry')} ORDER BY entry`)
    rows.setReturnArrays(true)
    chunks.push(`,"removedEntries":[${(rows.all() as unknown as Row[]).map((r) => q(r[0] as string)).join(',')}]`)
  }
  // sets and spaces (meta shard only)
  chunks.push(',"sets":{')
  if (meta) chunks.push(sets(prep))
  chunks.push('},"spaces":{')
  if (meta) chunks.push(entities(prep, 'space'))
  chunks.push('}}\n')

  const body = Buffer.from(chunks.join(''), 'utf8')
  const actors = Object.keys(coverage).sort()
  const header = `{"coverage":{${actors.map((a) => `${q(a)}:${coverage[a]}`).join(',')}},"library":${q(library)},"maxHlc":${maxHlc === null ? 'null' : q(maxHlc)},"part":${q(shard)},"semantics":${SEMANTICS},"sha256":"${createHash('sha256').update(body).digest('hex')}"}`
  return Buffer.concat([Buffer.from(`KFCKP ${FORMAT}\n${header}\n`, 'utf8'), body])
}

/** `{"<k1>":{"<k2>":reg}}` for one register scope. */
function groupedRegs(prep: (sql: string) => StatementSync, scope: string): string {
  const stmt = prep(`SELECT k1, k2, wall, counter, hactor, oactor, oseq, value FROM regs WHERE scope = '${scope}'`)
  stmt.setReturnArrays(true)
  const byKey = new Map<string, Row[]>()
  for (const r of stmt.all() as unknown as Row[]) {
    const list = byKey.get(r[0] as string)
    if (list) list.push(r)
    else byKey.set(r[0] as string, [r])
  }
  return [...byKey.keys()]
    .sort()
    .map((key) => `${q(key)}:${regMap(byKey.get(key)!, 1, 2)}`)
    .join(',')
}

/** `{"<id>":{"exists":{...},"fields":{...}}}` for playlists or spaces. */
function entities(prep: (sql: string) => StatementSync, scope: string): string {
  const all = (sql: string) => {
    const s = prep(sql)
    s.setReturnArrays(true)
    return s.all() as unknown as Row[]
  }
  const group = (rows: Row[]) => {
    const out = new Map<string, Row[]>()
    for (const r of rows) {
      const list = out.get(r[0] as string)
      if (list) list.push(r)
      else out.set(r[0] as string, [r])
    }
    return out
  }
  const tagRows = group(all(`SELECT k1, kind, actor, seq FROM tags WHERE scope = '${scope}' ORDER BY k1, kind, actor, seq`))
  const regRows = group(all(`SELECT k1, k2, wall, counter, hactor, oactor, oseq, value FROM regs WHERE scope = '${scope}'`))
  const idList = all(`SELECT k1 FROM present WHERE scope = '${scope}'`).map((r) => r[0] as string).sort()
  return idList.map((id) => `${q(id)}:{"exists":${orSet(tagRows.get(id) ?? [], 1)},"fields":${regMap(regRows.get(id) ?? [], 1, 2)}}`).join(',')
}

/** `{"<set>":{"<element>":{"adds":..,"removes":..}}}`. */
function sets(prep: (sql: string) => StatementSync): string {
  const all = (sql: string) => {
    const s = prep(sql)
    s.setReturnArrays(true)
    return s.all() as unknown as Row[]
  }
  const tagRows = new Map<string, Row[]>()
  for (const r of all("SELECT k1, k2, kind, actor, seq FROM tags WHERE scope = 'set' ORDER BY k1, k2, kind, actor, seq")) {
    const key = `${r[0] as string}\u0000${r[1] as string}`
    const list = tagRows.get(key)
    if (list) list.push(r)
    else tagRows.set(key, [r])
  }
  const bySet = new Map<string, string[]>()
  for (const r of all("SELECT k1, k2 FROM present WHERE scope = 'set'")) {
    const list = bySet.get(r[0] as string)
    if (list) list.push(r[1] as string)
    else bySet.set(r[0] as string, [r[1] as string])
  }
  return [...bySet.keys()]
    .sort()
    .map((name) => {
      const elements = bySet.get(name)!.sort()
      return `${q(name)}:{${elements.map((el) => `${q(el)}:${orSet(tagRows.get(`${name}\u0000${el}`) ?? [], 2)}`).join(',')}}`
    })
    .join(',')
}
