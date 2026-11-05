// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`) and the
// Keepfin sync log: operations, admission, the merged state, segments, checkpoints, clocks and order keys
// (dev-docs/plans/KEEPFIN-SYNC-SPEC.md). This is the only file that imports the core's sync package.
// The core is string- and JSON-based: ops cross as canonical text, files as bytes (Int8Array there, Uint8Array here).
import {
  JsSyncCheckpoint,
  JsSyncLog,
  syncDecodeCheckpoint,
  syncDecodeSegment,
  syncEncodeSegment,
  syncFormat,
  syncHlcReceive,
  syncHlcTick,
  syncIsValidKey,
  syncKeyBetween,
  syncLogCreate,
  syncLogFromCheckpoint,
  syncMakeOp,
  syncPartsCreate,
  syncPlaceInTie,
  syncSemantics,
  sqlStateBodyFromRows,
  sqlStateAdmit,
  sqlStateCatalog,
  sqlStateLoader,
  sqlStateProject,
  sqlStateQueries,
  sqlStateSessionSchema,
  sqlStateOp,
  sqlStatePartFromRows,
  sqlStateSchema,
  sqlStateDbOpen,
  sqlStateViewQueries,
  sqlStateShardQueries,
} from '../../../build/core-js/keepfin-core.mjs'

/** The envelope version (segment and checkpoint layouts, canonical JSON). */
export const FORMAT: number = syncFormat()
/** How ops are interpreted (kinds, bodies, deltas). */
export const SEMANTICS: number = syncSemantics()

/** JSON values in the sync format: integers only, within +-(2^53-1). */
export type SyncJson = null | boolean | number | string | SyncJson[] | {[key: string]: SyncJson}

const toInt8 = (bytes: Uint8Array): Int8Array => new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)
const toUint8 = (bytes: Int8Array): Uint8Array => new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)

// ---- views -----------------------------------------------------------------------------------------------------------

export type SyncItemStatus = 'present' | 'deleted' | 'deleteConflict'

export interface SyncHead {
  /** The `item.revise` op that made this revision, as `actor:seq`. */
  op: string
  /** `sha256:<hex>` of the file bytes. */
  content: string
  size: number
}

export interface SyncItem {
  id: string
  status: SyncItemStatus
  fields: Record<string, SyncJson>
  heads: SyncHead[]
  contentConflict: boolean
  deleteConflictHeads: string[]
  plays: number
}

export interface SyncSong {
  entry: string
  item: string
  key: string
}

export interface SyncPlaylist {
  id: string
  present: boolean
  fields: Record<string, SyncJson>
  songs: SyncSong[]
}

export interface SyncSpace {
  id: string
  present: boolean
  fields: Record<string, SyncJson>
}

/** What the desktop library projects from: unknown items are left out, deleted ones are included with their status. */
export interface SyncViews {
  items: SyncItem[]
  playlists: SyncPlaylist[]
  spaces: SyncSpace[]
  sets: Record<string, string[]>
  placements: Record<string, Record<string, string | null>>
  places: Record<string, Record<string, SyncJson>>
  profile: Record<string, SyncJson>
  findings: string[]
}

/** Keys whose views may have changed since the last takeTouched(). `all` after a rebuild (equivocation, checkpoint load). */
export interface SyncTouched {
  all: boolean
  /** Item ids: exists, fields, revisions, deletedHeads or placements changed. */
  items: string[]
  /** Playlist ids: exists or fields changed, OR any of its entries inserted/moved/removed, OR the presence of an item in it changed. */
  playlists: string[]
  spaces: string[]
  /** Set names whose membership changed. */
  sets: string[]
  places: string[]
  profile: boolean
  findings: boolean
}

/**
 * Partial views: the same shapes as SyncViews, only for the listed keys. Items that are now Unknown are listed in
 * `goneItems`. A listed set comes with its full membership (an empty list when it is now empty). With `touched.all`
 * it is everything, as `views()` gives it.
 */
export interface SyncPartialViews {
  items: SyncItem[]
  goneItems: string[]
  /** Each with its full song list. */
  playlists: SyncPlaylist[]
  spaces: SyncSpace[]
  sets: Record<string, string[]>
  /** For the listed items. */
  placements: Record<string, Record<string, string | null>>
  places: Record<string, Record<string, SyncJson>>
  profile?: Record<string, SyncJson>
  findings?: string[]
}

/** What a remove/delete needs to observe, read from the state. */
export interface SyncObserved {
  /** Live exists-tags and current revision heads (op ids, `actor:seq`). */
  items: Record<string, {tags: string[]; heads: string[]}>
  playlists: Record<string, string[]>
  spaces: Record<string, string[]>
  /** Set -> element -> live add-tags. */
  setElements: Record<string, Record<string, string[]>>
}

export interface SyncObservedQuery {
  items?: string[]
  playlists?: string[]
  spaces?: string[]
  setElements?: [string, string][]
}

/** The 17 shards of a checkpoint (sync spec 10.4): the first hex digit of an id, or `meta`. */
export const CHECKPOINT_SHARDS: readonly string[] = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', 'meta']

// ---- admission ---------------------------------------------------------------------------------------------------------

export type AdmitResult =
  | {kind: 'admitted'}
  | {kind: 'duplicate'}
  | {kind: 'kept'}
  | {kind: 'invalid'; reason: string}
  | {kind: 'equivocation'}
  /** A range of an equivocating actor (no reason), or an op the library's membership refuses: reason is unsigned, unauthorized or revoked. */
  | {kind: 'setAside'; reason?: 'unsigned' | 'unauthorized' | 'revoked'}
  | {kind: 'covered'}
  | {kind: 'malformed'; problem: string}

function parseAdmit(code: string): AdmitResult {
  if (code.startsWith('invalid:')) return {kind: 'invalid', reason: code.slice('invalid:'.length)}
  if (code.startsWith('malformed:')) return {kind: 'malformed', problem: code.slice('malformed:'.length)}
  if (code.startsWith('setAside:')) return {kind: 'setAside', reason: code.slice('setAside:'.length) as 'unsigned' | 'unauthorized' | 'revoked'}
  switch (code) {
    case 'admitted':
    case 'duplicate':
    case 'kept':
    case 'equivocation':
    case 'setAside':
    case 'covered':
      return {kind: code}
    default:
      throw new Error(`Unknown admission code from the core: ${code}`)
  }
}

// ---- checkpoints -----------------------------------------------------------------------------------------------------

/** A decoded checkpoint file. When `ok` is false, `error` says why it cannot be used (for example `otherSemantics`). */
export class SyncCheckpoint {
  /** @internal */
  constructor(readonly handle: JsSyncCheckpoint) {}
  get ok(): boolean {
    return this.handle.ok
  }
  get error(): string | null {
    return this.handle.error ?? null
  }
  /** Actor to the highest seq the checkpoint covers. */
  coverage(): Record<string, number> {
    return JSON.parse(this.handle.coverageJson())
  }
  maxHlc(): string | null {
    return this.handle.maxHlc() ?? null
  }
}

export function decodeCheckpoint(bytes: Uint8Array, library: string): SyncCheckpoint {
  return new SyncCheckpoint(syncDecodeCheckpoint(toInt8(bytes), library))
}

// ---- the log -----------------------------------------------------------------------------------------------------------

export class SyncLog {
  private constructor(private readonly handle: JsSyncLog) {}

  static create(): SyncLog {
    return new SyncLog(syncLogCreate())
  }

  /**
   * Starts a log from a checkpoint. `equivocations` maps an actor to the first seq at which two variants are known.
   * Returns null when the checkpoint is unusable (not ok, another semantics, or an equivocation at or below its coverage).
   */
  static fromCheckpoint(checkpoint: SyncCheckpoint, equivocations: Record<string, number> = {}): SyncLog | null {
    const handle = syncLogFromCheckpoint(checkpoint.handle, JSON.stringify(equivocations))
    return handle ? new SyncLog(handle) : null
  }

  /** Admits one canonical op line (a segment line without its LF). */
  admit(opText: string, signer: string | null = null): AdmitResult {
    return parseAdmit(this.handle.admit(opText, signer))
  }

  admitBytes(bytes: Uint8Array, signer: string | null = null): AdmitResult {
    return parseAdmit(this.handle.admitBytes(toInt8(bytes), signer))
  }

  /** Who may write, as the member ops admitted so far define it. */
  membership(): SyncMembership {
    return JSON.parse(this.handle.membershipJson())
  }

  versionVector(): Record<string, number> {
    return JSON.parse(this.handle.versionVectorJson())
  }

  /** Seqs held above a gap, per actor. */
  pending(): Record<string, number[]> {
    return JSON.parse(this.handle.pendingJson())
  }

  /** Per equivocating actor, the first seq from which its ops are set aside. */
  setAside(): Record<string, number> {
    return JSON.parse(this.handle.setAsideJson())
  }

  findings(): string[] {
    return JSON.parse(this.handle.findingsJson())
  }

  /** The largest clock held, for the writer's `hlcReceive` duty. */
  maxHlc(): string | null {
    return this.handle.maxHlc() ?? null
  }

  /** The canonical text held for an op id (`actor:seq`), for re-sending. */
  encoded(opId: string): string | null {
    return this.handle.encoded(opId) ?? null
  }

  /** A checkpoint file over the contiguous prefixes held (never the ops above a gap). */
  checkpointBytes(library: string): Uint8Array {
    return toUint8(this.handle.checkpointBytes(library))
  }

  viewsJson(): string {
    return this.handle.viewsJson()
  }

  /** The canonical bytes of the merged state (the checkpoint body line), to compare states exactly. */
  stateBodyBytes(): Uint8Array {
    return toUint8(this.handle.stateBodyBytes())
  }

  /** The keys touched since the last call (cheap: proportional to the keys touched). `all` after a rebuild or a checkpoint load. */
  takeTouched(): SyncTouched {
    return JSON.parse(this.handle.takeTouchedJson())
  }

  /** The views of the touched keys only; cost is the touched keys and their songs, never the library. `all` gives everything. */
  viewsFor(touched: SyncTouched): SyncPartialViews {
    return JSON.parse(this.handle.viewsForJson(JSON.stringify(touched)))
  }

  /** What a remove or delete must observe for the listed keys (keys the state lacks come back empty). */
  observed(query: SyncObservedQuery): SyncObserved {
    return JSON.parse(this.handle.observedJson(JSON.stringify(query)))
  }

  /**
   * Checkpoint parts (sync spec 10.4). `shards` omitted: only the shards changed since their part was last written
   * (every shard the first time). Each part is a checkpoint file for one shard; the file name is up to the store.
   */
  checkpointParts(library: string, shards?: string[]): {shard: string; bytes: Uint8Array}[] {
    const names: string[] = shards ?? JSON.parse(this.handle.dirtyShardsJson())
    return names.map((shard) => {
      const bytes = this.handle.partBytes(library, shard)
      if (!bytes) throw new Error(`Not a checkpoint shard: ${shard}`)
      return {shard, bytes: toUint8(bytes)}
    })
  }

  /**
   * Starts a log from checkpoint parts: per shard the readable parts are merged, the library's coverage is the minimum
   * over the 17 shards (a missing shard counts as 0). Unreadable parts are ignored. Returns null when the result is
   * unusable (an equivocation at or below a coverage some part claims).
   */
  static fromCheckpointParts(parts: Uint8Array[], library: string, equivocations: Record<string, number> = {}): SyncLog | null {
    const collector = syncPartsCreate(library)
    for (const part of parts) collector.add(toInt8(part))
    const handle = collector.build(JSON.stringify(equivocations))
    return handle ? new SyncLog(handle) : null
  }

  views(): SyncViews {
    return JSON.parse(this.handle.viewsJson())
  }
}

// ---- files ---------------------------------------------------------------------------------------------------------------

/**
 * A decoded segment. signer (the device id) and key (the SPKI, Base64) are set for a KFSEG v2 file whose signature verified,
 * null for a v1 file. Whether the actor may use that key is for admission to decide: pass signer to admit.
 */
export type SegmentDecode =
  | {ok: true; actor: string; first: number; last: number; ops: string[]; signer: string | null; key: string | null}
  | {ok: false; error: string; line: number}

/** Decodes a segment file. Op bodies are judged later, by `SyncLog.admit`, so unknown and invalid ops still decode. */
export function decodeSegment(bytes: Uint8Array, library: string): SegmentDecode {
  return JSON.parse(syncDecodeSegment(toInt8(bytes), library))
}

/** Writes a segment file for one actor's contiguous ops (canonical op texts). Throws if they are not. */
export function encodeSegment(library: string, actor: string, ops: string[], key?: {pkcs8: Uint8Array; spki: Uint8Array}): Uint8Array {
  return toUint8(syncEncodeSegment(library, actor, JSON.stringify(ops), key ? toInt8(key.pkcs8) : undefined, key ? toInt8(key.spki) : undefined))
}

// ---- ops, clocks, keys ---------------------------------------------------------------------------------------------------

export class SyncOpError extends Error {}

/**
 * Builds one canonical op line. Known kinds are checked with the rules admission applies; an invalid body throws
 * SyncOpError. Unknown kinds are allowed.
 */
export function makeOp(actor: string, seq: number, hlc: string, kind: string, body: Record<string, SyncJson>): string {
  const result = JSON.parse(syncMakeOp(actor, seq, hlc, kind, JSON.stringify(body))) as {ok: true; text: string} | {ok: false; error: string}
  if (!result.ok) throw new SyncOpError(result.error)
  return result.text
}

/** The clock for a local write (wire form). Throws if the clock is unparseable or the wall time is exhausted. */
export function hlcTick(last: string | null, nowMs: number, actor: string): string {
  return syncHlcTick(last, nowMs, actor)
}

/** The clock after admitting a remote op (wire form). */
export function hlcReceive(last: string | null, remote: string, nowMs: number, actor: string): string {
  return syncHlcReceive(last, remote, nowMs, actor)
}

/** A fractional key strictly between `a` and `b` (null is open). Throws on misordered or malformed keys. */
export function keyBetween(a: string | null, b: string | null): string {
  return syncKeyBetween(a, b)
}

export function isValidKey(key: string): boolean {
  return syncIsValidKey(key)
}

export interface TiePlacement {
  newKey: string
  /** `entry.move` ops to issue for the tied entries that follow the new one. */
  moves: {entry: string; key: string}[]
}

export function placeInTie(tieKey: string, next: string | null, followers: string[]): TiePlacement {
  return JSON.parse(syncPlaceInTie(tieKey, next, JSON.stringify(followers)))
}

// ---- the SQL merge state (decision D-T3; gui/desktop/sqlstate runs these on node:sqlite) --------------------------------

/** The CREATE statements of the SQL merge state. */
export function sqlSchema(): string[] {
  return JSON.parse(sqlStateSchema())
}

/** The fixed catalog of parameterized statements; a statement id from `sqlOp` indexes it. */
export function sqlCatalog(): string[] {
  return JSON.parse(sqlStateCatalog())
}

/** One statement of an op: its catalog id and its parameters. */
export type SqlCall = [number, ...(string | number | null)[]]

/** The statements that join one canonical op line into the tables, or null when the op does not change the state. */
export function sqlOp(opText: string): {items: string[]; statements: SqlCall[]} | null {
  const text = sqlStateOp(opText)
  if (text === '') return null
  const parsed = JSON.parse(text) as {i: string[]; s: SqlCall[]}
  return {items: parsed.i, statements: parsed.s}
}

/** Table rows as the host reads them (column order of the schema), for rebuilding the state in tests. */
export interface SqlRows {
  present: unknown[][]
  tags: unknown[][]
  regs: unknown[][]
  revisions: unknown[][]
  revParents: unknown[][]
  entries: unknown[][]
  removedEntries: unknown[][]
  findings: unknown[][]
}

/** The canonical bytes of the state the rows describe: equal to `SyncLog.stateBodyBytes()` when the SQL join is exact. */
export function sqlStateBytes(rows: SqlRows): Uint8Array {
  return toUint8(sqlStateBodyFromRows(JSON.stringify(rows)))
}

/**
 * The part file (sync spec 10.4) of one shard from the table rows that belong to it (every row of the shard's keys, in the
 * column order of the schema). `coverage` is the library's contiguous version vector. Null for a name that is not a shard.
 */
export function sqlPartBytes(rows: SqlRows, library: string, shard: string, coverage: Record<string, number>, maxHlc: string | null): Uint8Array | null {
  const bytes = sqlStatePartFromRows(JSON.stringify(rows), library, shard, JSON.stringify(coverage), maxHlc)
  return bytes ? toUint8(bytes) : null
}

/** The per-connection helper tables of the SQL merge state. */
export function sqlSessionSchema(): string[] {
  return JSON.parse(sqlStateSessionSchema())
}

/** The named statements of the SQL merge state (admission bookkeeping, projection inputs, songs). */
export function sqlQueries(): Record<string, string> {
  return JSON.parse(sqlStateQueries())
}

/** An op line judged for admission, with no state. `h` is only present when the host must not hash the text itself. */
export type SqlAdmit =
  | {malformed: string}
  | {actor: string; seq: number; kind: 0 | 1 | 2; reason?: string; size: number; hlc: string; hash?: string; items: string[]; statements: SqlCall[]}

export function sqlAdmit(opText: string): SqlAdmit {
  const r = JSON.parse(sqlStateAdmit(opText)) as {m?: string; a: string; q: number; k: 0 | 1 | 2; r?: string; z: number; w: string; h?: string; i?: string[]; s?: SqlCall[]}
  if (r.m !== undefined) return {malformed: r.m}
  return {actor: r.a, seq: r.q, kind: r.k, reason: r.r, size: r.z, hlc: r.w, hash: r.h, items: r.i ?? [], statements: r.s ?? []}
}

/** One projected item row: `[id, status or null (unknown), fields JSON, heads JSON, plays, conflict, delete-conflict heads JSON, cycle]`. */
export type SqlProjectedRow = [string, string | null, string, string, number, number, string, number]

/** The shared item projection, fed the rows of the touched items (as the `proj*` queries give them). */
export function sqlProject(input: {ids: string[]; tags: unknown[][]; fields: unknown[][]; revisions: unknown[][]; parents: unknown[][]}): SqlProjectedRow[] {
  return JSON.parse(sqlStateProject(JSON.stringify(input)))
}

/** Rows of checkpoint parts, one shard at a time (the bulk loader's input). */
export interface SqlLoader {
  /** Keeps a part (reads only its header); returns its shard, or null when it is not a part of this library. */
  add(bytes: Uint8Array): string | null
  plan(): {coverage: Record<string, number>; highest: Record<string, number>; maxHlc: string | null; shards: string[]}
  /** The table rows of one shard in primary-key order, or null when a part of it is unreadable. */
  shardRows(shard: string): Record<'present' | 'tags' | 'regs' | 'revisions' | 'revParents' | 'entries' | 'removedEntries' | 'findings' | 'itemView' | 'cycles', unknown[][]> | null
  /** Milliseconds spent so far in the core (decoding parts, making rows) and in parsing its JSON, for measurements. */
  readonly spent: {coreMs: number; parseMs: number}
}

export function sqlLoader(library: string): SqlLoader {
  const handle = sqlStateLoader(library)
  const spent = {coreMs: 0, parseMs: 0}
  return {
    add: (bytes) => handle.add(toInt8(bytes)) ?? null,
    plan: () => JSON.parse(handle.planJson()),
    shardRows: (shard) => {
      const started = performance.now()
      const text = handle.shardRows(shard)
      const made = performance.now()
      const rows = text == null ? null : JSON.parse(text)
      spent.coreMs += made - started
      spent.parseMs += performance.now() - made
      return rows
    },
    spent,
  }
}

// ---- the shared merge state over SQLite (decision D-A3): admission, bulk load, rebuild and parts, in common Kotlin ------------

/** The files the merge state runs on; the desktop adapter (gui/desktop/sqlstate) opens `node:sqlite` databases. */
export interface SqlStateHost {
  /** Opens (creating it if needed) the database at the path and gives its `DatabaseSync`. */
  open(path: string): unknown
  remove(path: string): void
  rename(from: string, to: string): void
  residentMb(): number
}

export interface SqlDbOptions {
  synchronous?: 'FULL' | 'NORMAL'
  catalogOverride?: Record<number, string>
  cacheMb?: number
  mmapMb?: number
  bulkPageSize?: number
}

export interface SqlTouched {
  all: boolean
  items: string[]
  playlists: string[]
  spaces: string[]
  sets: string[]
  places: string[]
  profile: boolean
  findings: boolean
}

export interface SqlBulkResult {
  ok: boolean
  rows: number
  ms: number
  peakRssMb: number
  coreMs: number
  insertMs: number
}

/** A part being written: call `step` until it returns true, then take `bytes`. */
export interface SqlPartJob {
  step(): boolean
  bytes(): Uint8Array
}

/** The members of a library (network spec 9.1): `active` once a genesis op is held; before that nothing is enforced. */
export interface SyncMembership {
  active: boolean
  frozen: Record<string, {seq: number; lastSegmentHash: string}>
  members: Record<string, {cutoff: number | null; epoch: number; perms: string[]; keys: {device: string; key: string; until: number | null}[]}>
}

/** The merge state of one library database (see core `SqlStateDb`). Plain data crosses as JSON. */
export interface SqlDb {
  /** `signer` is the device id of the verified signature of the segment the ops came in (null: unsigned). */
  admit(ops: string[], signer?: string | null): string[]
  /** Who may write, as the admitted member ops define it. */
  membership(): SyncMembership
  refreshItems(ids: string[]): void
  getMeta(key: string): string | null
  setMeta(key: string, value: string): void
  checkpoint(truncate: boolean): number
  rebuildRequired(): boolean
  dirtyShards(): number
  markShardsClean(mask: number): void
  close(): void
  takeTouched(): SqlTouched
  versionVector(): Record<string, number>
  pending(): Record<string, number[]>
  setAside(): Record<string, number>
  maxHlc(): string | null
  findings(): string[]
  checkpointCoverage(): Record<string, number> | null
  partJob(library: string, shard: string, coverage: Record<string, number>): SqlPartJob
  observed(query: SyncObservedQuery): SyncObserved
  bulkLoad(parts: Uint8Array[], library: string, equivocations: Record<string, number>): SqlBulkResult
  /** signers runs in step with ops (the device id that signed each op's segment, or null); omit it when none is signed. */
  rebuild(ops: Iterable<string>, signers?: Iterable<string | null>): void
  /** The first import of a library into an empty database; null (nothing done) when it is not empty. */
  importFresh(ops: Iterable<string>, signers?: Iterable<string | null>): string[] | null
}

export function sqlDbOpen(host: SqlStateHost, path: string, options: SqlDbOptions = {}): SqlDb {
  const h = sqlStateDbOpen(host, path, options.synchronous ?? 'NORMAL', options.cacheMb ?? 0, options.mmapMb ?? 1024, options.bulkPageSize ?? 16384, options.catalogOverride ? JSON.stringify(options.catalogOverride) : '')
  return {
    admit: (ops, signer) => Array.from(h.admit(ops, signer ?? null)),
    membership: () => JSON.parse(h.membershipJson()),
    refreshItems: (ids) => h.refreshItems(ids),
    getMeta: (key) => h.getMeta(key) ?? null,
    setMeta: (key, value) => h.setMeta(key, value),
    checkpoint: (truncate) => h.checkpoint(truncate),
    rebuildRequired: () => h.rebuildRequired(),
    dirtyShards: () => h.dirtyShards(),
    markShardsClean: (mask) => h.markShardsClean(mask),
    close: () => h.close(),
    takeTouched: () => JSON.parse(h.takeTouched()),
    versionVector: () => JSON.parse(h.versionVector()),
    pending: () => JSON.parse(h.pending()),
    setAside: () => JSON.parse(h.setAside()),
    maxHlc: () => h.maxHlc() ?? null,
    findings: () => Array.from(h.findings()),
    checkpointCoverage: () => {
      const text = h.checkpointCoverage()
      return text == null ? null : JSON.parse(text)
    },
    partJob: (library, shard, coverage) => {
      const job = h.partJob(library, shard, JSON.stringify(coverage))
      return {step: () => job.step(), bytes: () => toUint8(job.bytes())}
    },
    observed: (query) => JSON.parse(h.observed(JSON.stringify(query))),
    bulkLoad: (parts, library, equivocations) => {
      const r = JSON.parse(h.bulkLoad(parts.map(toInt8), library, JSON.stringify(equivocations)))
      return {...r, peakRssMb: r.peakRssMb / 10}
    },
    rebuild: (ops, signers) => h.rebuild(ops, signers),
    importFresh: (ops, signers) => {
      const codes = h.importFresh(ops, signers)
      return codes == null ? null : Array.from(codes)
    },
  }
}

/** The SQL texts of the readers (item projection rows, containers, sets, registers, profile). */
export interface SqlViewQueries {
  itemViews: string
  itemViewOne: string
  itemViewsWithPlacements: string
  containers: string
  containerFields: string
  membersAll: string
  membersNamed: string
  membersRange: string
  registersAll: string
  registersOf: string
  profile: string
  stateRows: Record<string, string>
}

export const sqlViewQueries = (): SqlViewQueries => JSON.parse(sqlStateViewQueries())

/** The SQL of the rows of one checkpoint shard, by table (present, tags, regs, revisions, revParents, entries, removedEntries, findings). */
export const sqlShardQueries = (shard: string): Record<string, string> => JSON.parse(sqlStateShardQueries(shard))