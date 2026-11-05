// The device-local merge state held in SQLite (decision D-T3 of the desktop spec), as the desktop uses it.
//
// Since decision D-A3 the rules AND the control flow live once, in the shared core (core/.../sqlstate, common Kotlin):
// judging an op line, the statements that join it, the admission flow of spec section 4 over the
// `held`/`variants`/`equiv`/`vv`/`pending` tables, the touched-key tracker, the item projection, the sorted bulk loader,
// the rebuild after an equivocation, the checkpoint parts and what a remove must observe. Android runs the same code over
// its own SQLite driver.
//
// This file is the thin desktop adapter: it opens the `node:sqlite` files for the core (WAL, mmap and the rest are the
// core's pragmas), keeps the public API the store uses, and runs the plain reads whose SQL text the core owns
// (SqlViews) and whose rows become the objects the app uses.
//
// The op bytes are NOT kept here: the segments hold them. The database keeps a SHA-256 per held op, so "the same id
// with the same bytes" is an equal hash.
import {existsSync, renameSync, rmSync} from 'node:fs'
import {DatabaseSync, type StatementSync} from 'node:sqlite'
import {sqlDbOpen, sqlPartBytes, sqlQueries, sqlShardQueries, sqlViewQueries, type SqlDb, type SqlRows, type SqlViewQueries, type SyncItem, type SyncJson, type SyncMembership, type SyncObserved, type SyncObservedQuery, type SyncPartialViews, type SyncSong, type SyncTouched, type SyncViews} from '../../shared/core/sync.js'

/** The tables' rows are read by the core's queries for the host to rebuild the State in tests (shard rows included). */
export const ALL_SHARDS = (1 << 17) - 1

export interface SqlStateOptions {
  /** `FULL` waits for the disk on every commit (a crash loses nothing); `NORMAL` may lose the last commits in WAL mode. */
  synchronous?: 'FULL' | 'NORMAL'
  /** For tests of the tests: replaces statements of the catalog by id (a deliberately wrong join must be caught). */
  catalogOverride?: Record<number, string>
  /** Page cache size in MB. */
  cacheMb?: number
  /** How much of the database file may be memory-mapped for reads, in MB (virtual address space, not resident memory). */
  mmapMb?: number
  /** Page size of a database built by the bulk loader (bytes; the SQLite default is 4096). */
  bulkPageSize?: number
}

type ViewRow = {id: string; status: string; fields: string; heads: string; plays: number; conflict: number; dch: string}

export interface BulkLoadResult {
  ok: boolean
  rows: number
  ms: number
  /** The largest resident set seen while loading (sampled after every shard). */
  peakRssMb: number
  /** Where the time went: the core decoding parts, and writing rows into SQLite. */
  coreMs: number
  parseMs: number
  insertMs: number
}

export interface PartHooks {
  /** Called now and then so the thread can be handed back. */
  yield(): Promise<void>
}

let views: SqlViewQueries | undefined
const V = (): SqlViewQueries => (views ??= sqlViewQueries())

export class SqlState {
  /** The open connection (it changes when a bulk load or a rebuild switches the file in). */
  db!: DatabaseSync
  private readonly path: string
  private readonly core: SqlDb
  private named = new Map<string, StatementSync>()

  constructor(path: string, options: SqlStateOptions = {}) {
    this.path = path
    try {
      this.core = sqlDbOpen(
        {
          open: (file: string) => {
            this.db = new DatabaseSync(file)
            this.named = new Map()
            return this.db
          },
          remove: (file: string) => rmSync(file, {force: true}),
          rename: (from: string, to: string) => renameSync(from, to),
          residentMb: () => process.memoryUsage().rss / 1e6,
        },
        path,
        options,
      )
    } catch (error) {
      // a file that is not a database: do not leave it locked (the host deletes it and starts again)
      try {
        this.db?.close()
      } catch {
        // not open
      }
      throw error
    }
  }

  /** Set when an equivocation moved a set-aside range: call [rebuild] before the next read of the state. */
  get rebuildRequired(): boolean {
    return this.core.rebuildRequired()
  }

  /** Checkpoint shards (bit per shard) that changed since their part was written; everything is dirty after an open. */
  get dirtyShards(): number {
    return this.core.dirtyShards()
  }

  /** A prepared statement for any SQL text, cached until the database is switched. */
  prep(sql: string): StatementSync {
    let s = this.named.get(sql)
    if (!s) {
      s = this.db.prepare(sql)
      this.named.set(sql, s)
    }
    return s
  }

  getMeta(key: string): string | null {
    return this.core.getMeta(key)
  }

  setMeta(key: string, value: string): void {
    this.core.setMeta(key, value)
  }

  /** The keys changed since the last call; `all` after a rebuild, a bulk load or a fresh open (everything may have changed). */
  takeTouched(): SyncTouched {
    return this.core.takeTouched()
  }

  markShardsClean(mask: number): void {
    this.core.markShardsClean(mask)
  }

  close(): void {
    this.core.close()
  }

  /**
   * Folds the write-ahead log into the database file. Commits never do this themselves (it would show up as a latency
   * spike on a save), so the host calls it when idle, and often enough that the log stays small. Returns the number of
   * pages the log held.
   */
  checkpoint(mode: 'PASSIVE' | 'TRUNCATE' = 'PASSIVE'): number {
    return this.core.checkpoint(mode === 'TRUNCATE')
  }

  // ---- admission (spec section 4; the flow is the core's) ------------------------------------------------------------

  /**
   * Admits a batch of canonical op lines in one transaction and answers with one admission code per op, the codes of
   * `OpLog.admit`: admitted, duplicate, kept, invalid:<reason>, equivocation, setAside, covered, malformed:<problem>.
   * The item projection of every touched item is refreshed in the same transaction.
   *
   * An equivocation that moves a set-aside range sets [rebuildRequired]: the join cannot undo the effects of the
   * actor's ops from that seq on, so the state tables are rebuilt from the segments by [rebuild].
   */
  admit(ops: string[], signer: string | null = null): string[] {
    return this.core.admit(ops, signer)
  }

  /** Who may write, as the admitted member ops define it (network spec 9.1 #1): inactive until a genesis op is held. */
  membership(): SyncMembership {
    return this.core.membership()
  }

  /**
   * The first import of a library into an empty database: the codes `admit` would give for the same ops, but the rows go
   * into the tables once, in key order, instead of op by op (see core `SqlStateDb.importFresh`). Null, and nothing done,
   * when the database already holds anything.
   */
  importFresh(ops: Iterable<string>, signers?: Iterable<string | null>): string[] | null {
    return this.core.importFresh(ops, signers)
  }

  /** Joins a batch of ops and answers how many were admitted (the code `admitted`). */
  applyOps(ops: string[]): number {
    return this.admit(ops).filter((c) => c === 'admitted').length
  }

  /** Recomputes the projection rows of [ids] (the core projects; this stores). One set of queries for the whole batch. */
  refreshItems(ids: string[]): void {
    this.core.refreshItems(ids)
  }

  // ---- reading --------------------------------------------------------------------------------------------------------

  private toItem(r: ViewRow): SyncItem {
    return {
      id: r.id,
      status: r.status as SyncItem['status'],
      fields: JSON.parse(r.fields) as Record<string, SyncJson>,
      heads: JSON.parse(r.heads),
      contentConflict: r.conflict === 1,
      deleteConflictHeads: JSON.parse(r.dch),
      plays: r.plays,
    }
  }

  /** The projection read in one indexed scan: every known item as the plain object the app uses. */
  readItems(): SyncItem[] {
    const out: SyncItem[] = []
    const s = this.db.prepare(V().itemViews)
    for (const r of s.iterate() as Iterable<ViewRow>) out.push(this.toItem(r))
    return out
  }

  /** The projection rows of the listed items only (a remote batch re-reads just these). */
  readSome(ids: string[]): SyncItem[] {
    const s = this.prep(V().itemViewOne)
    const out: SyncItem[] = []
    for (const id of ids) {
      const r = s.get(id) as ViewRow | undefined
      if (r) out.push(this.toItem(r))
    }
    return out
  }

  /** A present playlist's songs, ordered by (key, entry) (the ORDER BY of the core's `songs` query). */
  songs(playlist: string): SyncSong[] {
    if ((this.q('playlistPresent').get(playlist) as {present: number}).present !== 1) return []
    return (this.q('songs').all(playlist) as {entry: string; item: string; key: string}[]).map((r) => ({entry: r.entry, item: r.item, key: r.key}))
  }

  private queries?: Record<string, string>
  private q(name: string): StatementSync {
    this.queries ??= sqlQueries()
    return this.prep(this.queries[name])
  }

  /**
   * Every known item as the app reads it, with its placements, in one scan of the projection (the placements of an item
   * are one range lookup of the registers). Nothing is held: the caller takes one row at a time.
   */
  *iterateViews(): Generator<{item: SyncItem; placements: Record<string, string | null> | undefined}> {
    const s = this.db.prepare(V().itemViewsWithPlacements)
    for (const r of s.iterate() as Iterable<ViewRow & {pl: string | null}>) {
      const placements = r.pl && r.pl !== '{}' ? (JSON.parse(r.pl) as Record<string, string | null>) : undefined
      yield {item: this.toItem(r), placements}
    }
  }

  // ---- the other views (playlists, spaces, sets, placements, places, profile), read from the tables --------------------

  /** Playlists or spaces that exist in the state and have at least one add, with presence and fields, ordered by id. */
  containers(scope: 'playlist' | 'space', ids?: readonly string[]): {id: string; present: boolean; fields: Record<string, SyncJson>}[] {
    const sql = V().containers
    const out: {id: string; present: boolean; fields: Record<string, SyncJson>}[] = []
    const fieldsOf = (id: string) => {
      const fields: Record<string, SyncJson> = {}
      for (const r of this.prep(V().containerFields).all(scope, id) as {name: string; value: string}[]) fields[r.name] = JSON.parse(r.value)
      return fields
    }
    if (ids) {
      const one = this.prep(sql + 'AND p.k1 = ?2')
      for (const id of [...ids].sort()) {
        const r = one.get(scope, id) as {id: string; present: number} | undefined
        if (r) out.push({id: r.id, present: r.present === 1, fields: fieldsOf(id)})
      }
      return out
    }
    for (const r of this.prep(sql + 'ORDER BY p.k1').all(scope) as {id: string; present: number}[]) out.push({id: r.id, present: r.present === 1, fields: fieldsOf(r.id)})
    return out
  }

  private membersOf(sql: string, ...params: string[]): Map<string, string[]> {
    const out = new Map<string, string[]>()
    for (const r of this.prep(sql).all(...params) as {name: string; el: string}[]) {
      const list = out.get(r.name)
      if (list) list.push(r.el)
      else out.set(r.name, [r.el])
    }
    for (const list of out.values()) if (list.length > 1) list.sort()
    return out
  }

  /** Every set that has members, with its members sorted. */
  allSets(): Record<string, string[]> {
    return Object.fromEntries(this.membersOf(V().membersAll))
  }

  /** The named sets with their full membership (an empty list when a set is empty or unknown). */
  setsNamed(names: readonly string[]): Record<string, string[]> {
    const out: Record<string, string[]> = {}
    for (const name of [...names].sort()) out[name] = this.membersOf(V().membersNamed, name).get(name) ?? []
    return out
  }

  /** The sets whose name starts with [prefix], by name (a set with no members is absent). */
  setsByPrefix(prefix: string): Map<string, string[]> {
    const upper = prefix.slice(0, -1) + String.fromCharCode(prefix.charCodeAt(prefix.length - 1) + 1)
    return this.membersOf(V().membersRange, prefix, upper)
  }

  /** `scope` registers of the listed keys (all keys when none are listed) as `{k1: {k2: value}}`; a key without registers is absent. */
  registers(scope: 'placement' | 'place', keys?: readonly string[]): Record<string, Record<string, SyncJson>> {
    const out: Record<string, Record<string, SyncJson>> = {}
    const add = (rows: {k1: string; k2: string; value: string}[]) => {
      for (const r of rows) (out[r.k1] ??= {})[r.k2] = JSON.parse(r.value)
    }
    if (!keys) add(this.prep(V().registersAll).all(scope) as never)
    else for (const key of [...keys].sort()) add(this.prep(V().registersOf).all(scope, key) as never)
    return out
  }

  profile(): Record<string, SyncJson> {
    const out: Record<string, SyncJson> = {}
    for (const r of this.prep(V().profile).all() as {k1: string; value: string}[]) out[r.k1] = JSON.parse(r.value)
    return out
  }

  /** The full views, as `SyncLog.views()` gives them (small libraries and tests: the payload is the whole state). */
  views(): SyncViews {
    const lists = this.containers('playlist')
    return {
      items: this.readItems(),
      playlists: lists.map((p) => ({id: p.id, present: p.present, fields: p.fields, songs: p.present ? this.songs(p.id) : []})),
      spaces: this.containers('space'),
      sets: this.allSets(),
      placements: this.registers('placement') as SyncViews['placements'],
      places: this.registers('place'),
      profile: this.profile(),
      findings: this.findings(),
    }
  }

  /** The views of the touched keys only (see `SyncPartialViews`); everything for `touched.all`. */
  viewsFor(touched: SyncTouched): SyncPartialViews {
    if (touched.all) return {...this.views(), goneItems: []}
    const ids = [...touched.items].sort()
    const items = this.readSome(ids)
    const have = new Set(items.map((i) => i.id))
    const part: SyncPartialViews = {
      items,
      goneItems: ids.filter((id) => !have.has(id)),
      playlists: this.containers('playlist', touched.playlists).map((p) => ({id: p.id, present: p.present, fields: p.fields, songs: p.present ? this.songs(p.id) : []})),
      spaces: this.containers('space', touched.spaces),
      sets: this.setsNamed(touched.sets),
      placements: this.registers('placement', ids) as SyncPartialViews['placements'],
      places: this.registers('place', touched.places),
    }
    if (touched.profile) part.profile = this.profile()
    if (touched.findings) part.findings = this.findings()
    return part
  }

  /** What a remove or delete must observe (see `SyncObserved`): live add-tags and revision heads. */
  observed(query: SyncObservedQuery): SyncObserved {
    return this.core.observed(query)
  }

  // ---- checkpoint parts (sync spec 10.4) ------------------------------------------------------------------------------

  /**
   * The library's coverage for a checkpoint: the contiguous version vector, with an equivocating actor cut before its
   * set-aside range. Null when ops are held above a gap (the tables then hold ops a checkpoint must not): the host writes
   * nothing and tries again later.
   */
  checkpointCoverage(): Record<string, number> | null {
    return this.core.checkpointCoverage()
  }

  /** The rows of the tables that belong to one shard (every row of the shard's keys), in the column order of the schema. */
  shardRows(shard: string): SqlRows {
    const out: Record<string, unknown[][]> = {}
    for (const [table, sql] of Object.entries(sqlShardQueries(shard))) {
      const s = this.db.prepare(sql)
      s.setReturnArrays(true)
      out[table] = s.all() as unknown as unknown[][]
    }
    return out as unknown as SqlRows
  }

  /**
   * The part file of one shard, written from the tables by the core. [coverage] is [checkpointCoverage] (the caller
   * guarantees it is not null). Hands the thread back through [hooks] every so often; no write may happen in the
   * database until it returns.
   */
  async partBytes(library: string, shard: string, coverage: Record<string, number>, hooks: PartHooks = {yield: async () => undefined}): Promise<Uint8Array> {
    const job = this.core.partJob(library, shard, coverage)
    while (!job.step()) await hooks.yield()
    return job.bytes()
  }

  /** The same part through the core (rows, then a state, then bytes): slow, kept as the reference the writer is tested against. */
  partBytesViaCore(library: string, shard: string, coverage: Record<string, number>): Uint8Array {
    const bytes = sqlPartBytes(this.shardRows(shard), library, shard, coverage, this.maxHlc())
    if (!bytes) throw new Error(`Not a checkpoint shard: ${shard}`)
    return bytes
  }

  versionVector(): Record<string, number> {
    return this.core.versionVector()
  }

  pending(): Record<string, number[]> {
    return this.core.pending()
  }

  setAside(): Record<string, number> {
    return this.core.setAside()
  }

  maxHlc(): string | null {
    return this.core.maxHlc()
  }

  /** The state's findings, `invalid:<op>` for each invalid op, `equivocation:<op>` for each equivocated id and `revision-cycle:<item>`. */
  findings(): string[] {
    return this.core.findings()
  }

  /** Every state table as rows, for rebuilding the State in tests. */
  readRows(): SqlRows {
    const out: Record<string, unknown[][]> = {}
    for (const [table, sql] of Object.entries(V().stateRows)) out[table] = (this.db.prepare(sql).all() as Record<string, unknown>[]).map((r) => Object.values(r))
    return out as unknown as SqlRows
  }

  // ---- the cold path and the rebuild (the core's) ---------------------------------------------------------------------

  /**
   * Replaces this database by one built from checkpoint parts (sync spec 10.4), without ever holding more than one
   * shard of the state: parts are read shard by shard, their rows written in primary-key order inside one transaction
   * per shard into a new file with no journal, the index is built after the load, and the finished file is switched
   * in. The ops above the coverage are then admitted normally by the caller.
   *
   * `equivocations` (actor to first seq at which two variants are known) are recorded as set-aside ranges. Returns
   * `ok: false`, changing nothing, when an equivocation lies at or below a coverage some part claims (the parts are
   * then unusable, as for `OpLog.fromCheckpoint`).
   */
  bulkLoad(parts: Uint8Array[], library: string, equivocations: Record<string, number> = {}): BulkLoadResult {
    if (this.path === ':memory:') throw new Error('bulkLoad needs a file database')
    return {...this.core.bulkLoad(parts, library, equivocations), parseMs: 0}
  }

  /**
   * After an equivocation the state must not contain the actor's ops from the first equivocated seq on, and a join
   * cannot take effects back. So the state tables are rebuilt: a new database gets the admission bookkeeping copied
   * over, then the state is replayed from [ops], which must give every op line of the segments (including the ones
   * below the checkpoint coverage; variants and set-aside ops are skipped), and the finished file is switched in.
   * Rare, so it favours being obviously right over being fast.
   */
  rebuild(ops: Iterable<string>, signers?: Iterable<string | null>): void {
    if (this.path === ':memory:') throw new Error('rebuild needs a file database')
    this.core.rebuild(ops, signers)
  }

  /** True when a database file exists at the path (a warm open is then possible). */
  static exists(path: string): boolean {
    return existsSync(path)
  }
}
