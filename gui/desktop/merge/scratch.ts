// The on-disk scratch the merge planner works against (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 5, budget line): a SQLite file
// next to the journal holding T's hash index (hash, add HLC, id), T's items (for the planner's `item` lookups), T's entity names,
// the S items in planning order, the first S item of each content and the S-to-T map being built. Memory stays bounded by one page.
// The choice of an index: the store's own state database (`library-state.v1.db`) has no content-hash index (a lookup is a LIKE scan
// over `item_view.heads`), so a dedicated indexed table is built once per planning pass from a scan of T instead.
import fs from 'node:fs'
import path from 'node:path'
import {DatabaseSync, type StatementSync} from 'node:sqlite'
import type {MapLine, PlannerStores, SourceItem, TargetItem} from '../../shared/core/merge.js'

export class Scratch {
  readonly db: DatabaseSync
  private readonly cache = new Map<string, StatementSync>()

  /** A fresh file: a planning pass never reuses another pass's rows (the contract: start with fresh scratch data). */
  constructor(readonly file: string) {
    fs.mkdirSync(path.dirname(file), {recursive: true})
    for (const suffix of ['', '-wal', '-shm', '-journal']) fs.rmSync(file + suffix, {force: true})
    this.db = new DatabaseSync(file)
    this.db.exec('PRAGMA journal_mode = WAL; PRAGMA synchronous = OFF;')
    this.db.exec(`
      CREATE TABLE t_item (id TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL) WITHOUT ROWID;
      CREATE TABLE t_hash (hash TEXT NOT NULL, added TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (hash, added, id)) WITHOUT ROWID;
      CREATE TABLE t_name (kind TEXT NOT NULL, name TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (kind, name, id)) WITHOUT ROWID;
      CREATE TABLE s_item (added TEXT NOT NULL, id TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY (added, id)) WITHOUT ROWID;
      CREATE TABLE s_ids (id TEXT NOT NULL PRIMARY KEY) WITHOUT ROWID;
      CREATE TABLE first_src (hash TEXT NOT NULL PRIMARY KEY, src TEXT NOT NULL) WITHOUT ROWID;
      CREATE TABLE s_map (s TEXT NOT NULL PRIMARY KEY, t TEXT NOT NULL, how TEXT NOT NULL) WITHOUT ROWID;
    `)
  }

  private p(sql: string): StatementSync {
    let s = this.cache.get(sql)
    if (!s) {
      s = this.db.prepare(sql)
      this.cache.set(sql, s)
    }
    return s
  }

  close(): void {
    this.cache.clear()
    try {
      this.db.close()
    } catch {
      // already closed
    }
    for (const suffix of ['', '-wal', '-shm', '-journal']) fs.rmSync(this.file + suffix, {force: true})
  }

  /** Runs [work] in one transaction (bulk inserts). */
  tx<R>(work: () => R): R {
    this.db.exec('BEGIN')
    try {
      const out = work()
      this.db.exec('COMMIT')
      return out
    } catch (error) {
      this.db.exec('ROLLBACK')
      throw error
    }
  }

  // ---- T -------------------------------------------------------------------------------------------------------------------
  addTarget(item: TargetItem): void {
    this.p('INSERT OR REPLACE INTO t_item (id, json) VALUES (?, ?)').run(item.id, JSON.stringify(item))
    if (item.revisionHash) this.p('INSERT OR IGNORE INTO t_hash (hash, added, id) VALUES (?, ?, ?)').run(item.revisionHash, item.added, item.id)
  }

  addName(kind: 'playlist' | 'space', name: string, id: string): void {
    this.p('INSERT OR IGNORE INTO t_name (kind, name, id) VALUES (?, ?, ?)').run(kind, name, id)
  }

  targetCount(): number {
    return (this.p('SELECT COUNT(*) AS n FROM t_item').get() as {n: number}).n
  }

  hasTarget(id: string): boolean {
    return this.p('SELECT 1 AS x FROM t_item WHERE id = ?').get(id) !== undefined
  }

  // ---- S -------------------------------------------------------------------------------------------------------------------
  addSource(item: SourceItem): void {
    this.p('INSERT OR REPLACE INTO s_item (added, id, json) VALUES (?, ?, ?)').run(item.added, item.id, JSON.stringify(item))
    this.p('INSERT OR IGNORE INTO s_ids (id) VALUES (?)').run(item.id)
  }

  hasSource(id: string): boolean {
    return this.p('SELECT 1 AS x FROM s_ids WHERE id = ?').get(id) !== undefined
  }

  sourceCount(): number {
    return (this.p('SELECT COUNT(*) AS n FROM s_item').get() as {n: number}).n
  }

  /** The next [limit] S items after (added, id) in planning order. */
  sourcePage(after: {added: string; id: string} | null, limit: number): SourceItem[] {
    const rows = after
      ? (this.p('SELECT json FROM s_item WHERE added > ?1 OR (added = ?1 AND id > ?2) ORDER BY added, id LIMIT ?3').all(after.added, after.id, limit) as {json: string}[])
      : (this.p('SELECT json FROM s_item ORDER BY added, id LIMIT ?').all(limit) as {json: string}[])
    return rows.map((r) => JSON.parse(r.json) as SourceItem)
  }

  // ---- the planner's view ----------------------------------------------------------------------------------------------------
  mapCount(): number {
    return (this.p('SELECT COUNT(*) AS n FROM s_map').get() as {n: number}).n
  }

  stores(): PlannerStores {
    return {
      nextMatch: (hash, after) => {
        if (after === null) return (this.p('SELECT id FROM t_hash WHERE hash = ? ORDER BY added, id LIMIT 1').get(hash) as {id: string} | undefined)?.id ?? null
        const at = this.p('SELECT added FROM t_hash WHERE hash = ? AND id = ?').get(hash, after) as {added: string} | undefined
        if (!at) return null
        return (this.p('SELECT id FROM t_hash WHERE hash = ?1 AND (added > ?2 OR (added = ?2 AND id > ?3)) ORDER BY added, id LIMIT 1').get(hash, at.added, after) as {id: string} | undefined)?.id ?? null
      },
      target: (id) => {
        const row = this.p('SELECT json FROM t_item WHERE id = ?').get(id) as {json: string} | undefined
        if (!row) throw new Error(`The target library has no item ${id}.`)
        return JSON.parse(row.json) as TargetItem
      },
      nextName: (kind, name, after) =>
        (after === null
          ? (this.p('SELECT id FROM t_name WHERE kind = ? AND name = ? ORDER BY id LIMIT 1').get(kind, name) as {id: string} | undefined)
          : (this.p('SELECT id FROM t_name WHERE kind = ? AND name = ? AND id > ? ORDER BY id LIMIT 1').get(kind, name, after) as {id: string} | undefined)
        )?.id ?? null,
      firstSource: (hash) => (this.p('SELECT src FROM first_src WHERE hash = ?').get(hash) as {src: string} | undefined)?.src ?? null,
      rememberContent: (hash, source) => void this.p('INSERT OR REPLACE INTO first_src (hash, src) VALUES (?, ?)').run(hash, source),
      mapped: (source) => {
        const row = this.p('SELECT s, t, how FROM s_map WHERE s = ?').get(source) as MapLine | undefined
        return row ? {s: row.s, t: row.t, how: row.how} : null
      },
      record: (line) => void this.p('INSERT INTO s_map (s, t, how) VALUES (?, ?, ?)').run(line.s, line.t, line.how),
    }
  }
}

/** A small lasting lookup of the S-to-T map (the map file is the truth; this is its index for remapping ids). */
export class MapIndex {
  readonly db: DatabaseSync

  constructor(file: string) {
    fs.mkdirSync(path.dirname(file), {recursive: true})
    for (const suffix of ['', '-wal', '-shm', '-journal']) fs.rmSync(file + suffix, {force: true})
    this.db = new DatabaseSync(file)
    this.db.exec('PRAGMA journal_mode = OFF; PRAGMA synchronous = OFF; CREATE TABLE m (s TEXT NOT NULL PRIMARY KEY, t TEXT NOT NULL, how TEXT NOT NULL) WITHOUT ROWID; CREATE INDEX m_t ON m (t);')
  }

  load(lines: Iterable<MapLine>): void {
    const insert = this.db.prepare('INSERT OR REPLACE INTO m (s, t, how) VALUES (?, ?, ?)')
    this.db.exec('BEGIN')
    try {
      for (const line of lines) insert.run(line.s, line.t, line.how)
      this.db.exec('COMMIT')
    } catch (error) {
      this.db.exec('ROLLBACK')
      throw error
    }
  }

  toT(s: string): string | undefined {
    return (this.db.prepare('SELECT t FROM m WHERE s = ?').get(s) as {t: string} | undefined)?.t
  }

  /** Does a line with this T item exist that is not a link (the item came across with this device)? */
  broughtT(t: string): boolean {
    return this.db.prepare("SELECT 1 AS x FROM m WHERE t = ? AND how <> 'linked' LIMIT 1").get(t) !== undefined
  }

  /** The S ids that map to [t] (a linked T item can be several S items' target). */
  fromT(t: string): string[] {
    return (this.db.prepare('SELECT s FROM m WHERE t = ? ORDER BY s').all(t) as {s: string}[]).map((r) => r.s)
  }

  count(): number {
    return (this.db.prepare('SELECT COUNT(*) AS n FROM m').get() as {n: number}).n
  }

  *all(): Generator<MapLine> {
    for (const r of this.db.prepare('SELECT s, t, how FROM m ORDER BY s').iterate() as Iterable<MapLine>) yield {s: r.s, t: r.t, how: r.how}
  }

  close(): void {
    try {
      this.db.close()
    } catch {
      // already closed
    }
  }
}
