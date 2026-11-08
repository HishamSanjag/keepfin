// The Keepfin store: this device's view of the sync log in `<Keepfin folder>\.keepfin`
// (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md section 4, dev-docs/plans/KEEPFIN-SYNC-SPEC.md sections 6, 9 and 10).
//
//   apply(drafts)  makes operations, appends them to the outbox (fsync) and only then admits them
//   flush()        writes the outbox as an immutable segment ops\<actor>\<first>-<last>.seg, then empties the outbox
//   checkpoints    every 5,000 operations, right after a flush, as checkpoints\<maxHlc>-<actor>.ckpt
//   open           merges the best checkpoint, admits every segment operation above it and the outbox
//   watch/rescan   admits other actors' new segments; a partial segment is retried, never treated as corruption at once
import {createHash} from 'node:crypto'
import {rmSync, watch as fsWatch, writeFileSync, type FSWatcher} from 'node:fs'
import fs from 'node:fs/promises'
import path from 'node:path'
import {
  CHECKPOINT_SHARDS,
  FORMAT,
  SEMANTICS,
  decodeSegment,
  hlcReceive,
  hlcTick,
  makeOp,
  type AdmitResult,
  type SyncJson,
  type SyncMembership,
  type SyncObserved,
  type SyncObservedQuery,
  type SyncPartialViews,
  type SyncSong,
  type SyncTouched,
  type SyncViews,
} from '../../shared/core/sync.js'
import {deviceIdOf} from '../../shared/core/net.js'
import {SqlState} from '../sqlstate/sqlstate.js'
import {whereCounts, whereItem, type CopyCover, type WhereCounts, type WhereItemFacts} from './where.js'
import {Compactor, type CompactionHost} from './compaction.js'
import {
  listSegments,
  outboxOps,
  segmentBytes,
  segmentFilesSync,
  segmentName,
  segmentOps,
  signedSegmentBytes,
  writeBytesDurable,
  type OpRef,
  type SegmentFile,
} from './segmentFiles.js'
import {readJson, writeJsonAtomic} from './atomic.js'
import {recordsPath} from './keepfinFolder.js'
import type {OpDraft} from './mapping.js'

export {keyString, listingLast, peekSegmentSigner, segmentBytes, segmentFilesSync, segmentOps, signedSegmentBytes, type OpRef} from './segmentFiles.js'

/** The version of the device-local database layout. A database of another version is rebuilt from the segments. */
const STATE_FORMAT = '1'

/** The admission code of the SQL state as the structured result the store works with. */
function parseCode(code: string): AdmitResult {
  if (code.startsWith('invalid:')) return {kind: 'invalid', reason: code.slice('invalid:'.length)}
  if (code.startsWith('malformed:')) return {kind: 'malformed', problem: code.slice('malformed:'.length)}
  return {kind: code as 'admitted'}
}

export interface StoreOptions {
  /** The Keepfin folder. */
  root: string
  /** The records root below .keepfin/ ('' or undefined: .keepfin/ itself, a folder that was never combined). */
  records?: string
  libraryId: string
  actor: string
  /** %APPDATA%\Keepfin: the sequence counter lives in `<deviceDir>\seq\<actor>.json`. */
  deviceDir: string
  /** The folder of the outbox (`<profile>\outbox`); holds ops.jsonl. */
  outboxDir: string
  /** The device-local merge-state database (default `<profile>\state\library-state.v1.db`, next to the outbox). It is a cache: it can be deleted. */
  statePath?: string
  newId(): string
  now(): number
  /** Idle time before the outbox is written as a segment (default 30 s). */
  flushIdleMs?: number
  /** The longest an operation waits in the outbox before it is published, however busy the library is (default 5 min). */
  flushMaxAgeMs?: number
  /** Operations in the outbox that flush at once (default 2,000). */
  flushOps?: number
  /** Compact on its own: merge this device's small segments and drop covered files (default true; false: only on an explicit compact(); dev-docs/plans/KEEPFIN-DATA-COMPACTION.md). */
  compact?: boolean
  /** Delay before the first compaction after the folder is opened (default 60 s). */
  compactDelayMs?: number
  /** The least time between two compactions (default 10 min). */
  compactGapMs?: number
  /** Delay between a flush that leaves enough small segments and the compaction (default 5 s). */
  compactSoonMs?: number
  /** How long a compaction waits for segment transfers to finish before it skips the round (default 10 s). */
  leaseWaitMs?: number
  /** Test hook, called at each compaction step; throwing simulates a crash there. */
  onCompactStep?(step: 'published' | 'verified' | 'removing', detail: {first: number; last: number; removed: number}): void | Promise<void>
  /** Reports each finished compaction round. */
  onCompact?(info: CompactStats): void
  /** Operations between checkpoints (default 5,000). */
  checkpointEvery?: number
  /** Delay before a due checkpoint is written, off the save path (default 1,500 ms). */
  checkpointDelayMs?: number
  /** Largest segment this store writes (default 5,000 operations; the format allows 10,000). */
  segmentMaxOps?: number
  /** Watch `.keepfin\ops` for other actors' segments (default true). */
  watch?: boolean
  watchDebounceMs?: number
  /** The first retry delay for a segment that cannot be decoded yet (default 500 ms, doubling up to 30 s). */
  retryMs?: number
  /** The actor was retired (a restored or cloned install was detected): the profile must record the new id. */
  onRotate?(oldActor: string, newActor: string): Promise<void> | void
  /** For the note in `.keepfin\actors\<actor>.json`. */
  appVersion?: string
  deviceName?: string
  /**
   * This device's key (network spec section 1): when present, segments are written as `KFSEG 2` with a signature trailer and
   * this device's own operations are admitted under its device id, so a library whose membership is enforced accepts them.
   */
  signer?: {pkcs8: Uint8Array; spki: Uint8Array}
  /** Reports each checkpoint written: how many parts, the time the core needed to build them (blocking), and their size. */
  onCheckpoint?(info: {parts: number; buildMs: number; bytes: number}): void
  /** Progress of a long open (a replay without checkpoint parts). */
  onProgress?(progress: {phase: 'replaying'; done: number; total: number}): void
}

export type StoreChange = {remote: boolean}

/** What one compaction round did. `skipped`: why nothing was done. */
export type CompactStats = {merged: number; removed: number; dropped: number; skipped?: 'busy' | 'blocked' | 'closed'}

export type OpenStats = {
  ms: number
  checkpoint: boolean
  segmentsRead: number
  opsAdmitted: number
  outboxOps: number
  rotated: boolean
  equivocations: Record<string, number>
}

type Pending = {actor: string; seq: number; text: string}

const emptyTouched = (): SyncTouched => ({all: false, items: [], playlists: [], spaces: [], sets: [], places: [], profile: false, findings: false})

function mergeTouched(into: SyncTouched, more: SyncTouched) {
  into.all ||= more.all
  into.profile ||= more.profile
  into.findings ||= more.findings
  for (const key of ['items', 'playlists', 'spaces', 'sets', 'places'] as const) {
    if (!more[key].length) continue
    const have = new Set(into[key])
    for (const value of more[key]) if (!have.has(value)) into[key].push(value)
  }
}

/** The shard names that the bits of [mask] stand for (bit i is `CHECKPOINT_SHARDS[i]`). */
function shardsOf(mask: number): string[] {
  return CHECKPOINT_SHARDS.filter((_, i) => (mask >> i) & 1)
}

export class KeepfinStore {
  private sql!: SqlState
  private actor: string
  private hlc: string | null = null
  private nextSeq = 1
  private pending: Pending[] = []
  private readonly queue: {tail: Promise<unknown>} = {tail: Promise.resolve()}
  private readonly listeners = new Set<(change: StoreChange) => void>()
  private readonly processed = new Set<string>()
  private readonly retries = new Map<string, {attempts: number; at: number}>()
  private readonly problems = new Map<string, string>()
  private equivocations: Record<string, number> = {}
  private flushedLast = new Map<string, number>()
  private opsSinceCheckpoint = 0
  private version = 0
  private viewsCache?: {version: number; views: SyncViews}
  private flushTimer?: NodeJS.Timeout
  private retryTimer?: NodeJS.Timeout
  private watchTimer?: NodeJS.Timeout
  private watcher?: FSWatcher
  private closed = false
  private outboxBytes = 0
  private checkpointTimer?: NodeJS.Timeout
  /** When the oldest operation still in the outbox was written (the batching window never exceeds flushMaxAgeMs). */
  private oldestPendingAt = 0
  private compactTimer?: NodeJS.Timeout
  private compactAt = 0
  private readonly compactor: Compactor
  stats!: OpenStats

  /** This device's id as a signer (SHA-256 of its SPKI), or null when segments are not signed. */
  private readonly deviceId: string | null

  private constructor(private readonly options: StoreOptions) {
    this.actor = options.actor
    this.deviceId = options.signer ? deviceIdOf(options.signer.spki) : null
    const store = this
    const host: CompactionHost = {
      get closed() {
        return store.closed
      },
      options,
      get opsDir() {
        return store.opsDir
      },
      actor: this.actor,
      deviceId: this.deviceId,
      processed: this.processed,
      problems: this.problems,
      retries: this.retries,
      get equivocations() {
        return store.equivocations
      },
      get sql() {
        return store.sql
      },
      flush: () => this.flushNow(),
      scheduleCompact: (delay) => this.scheduleCompact(delay),
    }
    this.compactor = new Compactor(host)
  }

  // ------------------------------------------------------------------ paths

  private get recordsRoot() {
    return recordsPath(this.options.root, this.options.records)
  }

  private get opsDir() {
    return path.join(this.recordsRoot, 'ops')
  }
  /** Written once a library has a membership genesis: a hint that a cold open must replay instead of loading checkpoint parts. */
  private get enforcedMarker() {
    return path.join(this.recordsRoot, 'membership')
  }
  private get ckptDir() {
    return path.join(this.recordsRoot, 'checkpoints')
  }
  private get outboxFile() {
    return path.join(this.options.outboxDir, 'ops.jsonl')
  }
  private seqFile(actor = this.actor) {
    return path.join(this.options.deviceDir, 'seq', `${actor}.json`)
  }

  get actorId() {
    return this.actor
  }
  /** The ops folder of this store's records root (the merge scan reads the segments from it). */
  get opsPath() {
    return this.opsDir
  }
  get libraryId() {
    return this.options.libraryId
  }

  // ------------------------------------------------------------------ open

  static async open(options: StoreOptions): Promise<KeepfinStore> {
    const store = new KeepfinStore(options)
    await store.init()
    return store
  }

  private async init() {
    const started = Date.now()
    const {libraryId} = this.options
    await fs.mkdir(this.opsDir, {recursive: true})
    await fs.mkdir(this.ckptDir, {recursive: true})
    await fs.mkdir(this.options.outboxDir, {recursive: true})
    const segments = await listSegments(this.opsDir)
    for (const segment of segments) this.flushedLast.set(segment.actor, Math.max(this.flushedLast.get(segment.actor) ?? 0, segment.last))

    // Overlapping segment files of one actor must agree byte for byte; a difference is an equivocation.
    const decoded = new Map<string, ReturnType<typeof decodeSegment>>()
    const decodeFile = async (segment: SegmentFile) => {
      let value = decoded.get(segment.file)
      if (!value) {
        value = decodeSegment(await fs.readFile(segment.file), libraryId)
        decoded.set(segment.file, value)
      }
      return value
    }
    const equivocations: Record<string, number> = {}
    const byActor = new Map<string, SegmentFile[]>()
    for (const segment of segments) byActor.set(segment.actor, [...(byActor.get(segment.actor) ?? []), segment])
    for (const [actor, files] of byActor) {
      const active: SegmentFile[] = []
      for (const file of files) {
        for (let i = active.length - 1; i >= 0; i--) if (active[i].last < file.first) active.splice(i, 1)
        for (const other of active) {
          const [a, b] = await Promise.all([decodeFile(other).catch(() => undefined), decodeFile(file).catch(() => undefined)])
          if (!a?.ok || !b?.ok) continue
          const from = Math.max(other.first, file.first)
          const to = Math.min(other.last, file.last)
          for (let seq = from; seq <= to; seq++) {
            if (a.ops[seq - other.first] !== b.ops[seq - file.first]) {
              equivocations[actor] = Math.min(equivocations[actor] ?? Infinity, seq)
              break
            }
          }
        }
        active.push(file)
      }
    }
    this.equivocations = equivocations

    // The merge state is a device-local SQLite database (a cache of the segments). A usable one is kept from the last run
    // and only the segments above its version vector are admitted. Without one, checkpoint parts (sync spec 10.4) are
    // bulk-loaded; without parts (or with an unusable set) everything is replayed.
    const dbPath = this.statePath
    await fs.mkdir(path.dirname(dbPath), {recursive: true})
    this.sql = this.openState(dbPath)
    let usedCheckpoint = this.sql.getMeta('format') !== null
    if (!usedCheckpoint) {
      // Membership is not in checkpoint parts: a folder with an enforced membership is replayed, so every verdict is recomputed.
      const partFiles = (await fs.stat(this.enforcedMarker).then(() => true, () => false)) ? [] : await this.readParts()
      if (partFiles.length) {
        try {
          const loaded = this.sql.bulkLoad(partFiles, libraryId, equivocations)
          usedCheckpoint = loaded.ok && Object.keys(this.sql.versionVector()).length > 0
        } catch (error) {
          console.error('The checkpoint parts could not be loaded; the library is replayed from its segments:', error)
        }
      }
      this.markStateReady()
    }
    const coverage: Record<string, number> = this.sql.versionVector()
    this.options.onProgress?.({phase: 'replaying', done: 0, total: segments.length})

    let segmentsRead = 0
    let admitted = 0
    let segmentsDone = 0
    for (const segment of segments) {
      segmentsDone++
      if (segment.last <= (coverage[segment.actor] ?? 0)) {
        this.processed.add(`${segment.actor}/${segment.name}`)
        continue
      }
      let value: ReturnType<typeof decodeSegment>
      try {
        value = await decodeFile(segment)
      } catch {
        this.scheduleRetry(`${segment.actor}/${segment.name}`)
        continue
      }
      if (!value.ok) {
        this.noteUnreadable(`${segment.actor}/${segment.name}`, value.error)
        continue
      }
      segmentsRead++
      this.processed.add(`${segment.actor}/${segment.name}`)
      const above = coverage[segment.actor] ?? 0
      // A long replay hands the thread back between batches, so the window never freezes.
      for (let from = Math.max(0, above - value.first + 1); from < value.ops.length; from += 1500) {
        for (const result of this.admitMany(value.ops.slice(from, from + 1500), value.signer)) if (result.kind === 'admitted' || result.kind === 'kept') admitted++
        await new Promise((resolve) => setImmediate(resolve))
      }
      this.options.onProgress?.({phase: 'replaying', done: segmentsDone, total: segments.length})
    }
    // Each variant of an equivocated op is admitted so the state knows to set the actor's suffix aside.
    for (const [actor, files] of byActor) {
      if (!(actor in equivocations)) continue
      for (const file of files) {
        const value = await decodeFile(file).catch(() => undefined)
        if (value?.ok) this.admitMany(value.ops, value.signer)
      }
    }

    // The outbox: operations written before a crash. A torn last line is ignored.
    let outboxOps = 0
    const own: number[] = []
    const outbox = await fs.readFile(this.outboxFile, 'utf8').catch(() => '')
    this.outboxBytes = outbox.length
    const lines = outbox.split('\n')
    const tail = lines.pop()
    // A torn last line (a crash in the middle of an append) is dropped for good, so the next append starts on a clean line.
    if (tail) await fs.writeFile(this.outboxFile, lines.map((line) => `${line}\n`).join(''))
    const records: Pending[] = []
    for (const line of lines) {
      if (!line) continue
      let meta: {id?: unknown}
      try {
        meta = JSON.parse(line)
      } catch {
        continue
      }
      const id = typeof meta.id === 'string' ? meta.id : ''
      const at = id.lastIndexOf(':')
      const actor = id.slice(0, at)
      const seq = Number(id.slice(at + 1))
      if (!actor || !Number.isSafeInteger(seq)) continue
      records.push({actor, seq, text: line})
    }
    for (let from = 0; from < records.length; from += 1500) {
      const batch = records.slice(from, from + 1500)
      const results = this.admitMany(batch.map((record) => record.text), this.deviceId) // the outbox holds this device's own operations
      batch.forEach((record, i) => {
        if (results[i].kind === 'malformed') return
        outboxOps++
        if (record.actor === this.actor) own.push(record.seq)
        if (record.seq > (this.flushedLast.get(record.actor) ?? 0)) this.pending.push(record)
      })
      if (records.length > 3000) await new Promise((resolve) => setImmediate(resolve))
    }
    this.pending.sort((a, b) => (a.actor < b.actor ? -1 : a.actor > b.actor ? 1 : a.seq - b.seq))
    this.rebuildIfNeeded()

    // A restored or cloned install: the folder holds ops of this actor beyond what this device ever wrote.
    const seqState = await readJson<{lastSeq?: number}>(this.seqFile()).catch(() => undefined)
    const localLast = Math.max(typeof seqState?.lastSeq === 'number' ? seqState.lastSeq : 0, ...own, 0)
    const folderLast = Math.max(this.flushedLast.get(this.actor) ?? 0, coverage[this.actor] ?? 0)
    let rotated = false
    if (folderLast > localLast) {
      const retired = this.actor
      this.actor = this.options.newId()
      rotated = true
      await this.options.onRotate?.(retired, this.actor)
    }
    this.nextSeq = rotated ? 1 : localLast + 1

    const max = this.sql.maxHlc()
    this.hlc = max ? hlcReceive(null, max, this.options.now(), this.actor) : null
    this.opsSinceCheckpoint = admitted
    await this.writeActorNote().catch(() => undefined)
    this.sql.takeTouched() // everything so far is the baseline; later calls report what changed after it
    this.stats = {ms: Date.now() - started, checkpoint: usedCheckpoint, segmentsRead, opsAdmitted: admitted, outboxOps, rotated, equivocations}
    // Files another file of the same actor already holds (a peer that received both, a file-sync tool that brought back an old
    // pile) are dropped now, and the pile is merged a little later (both off the open path's critical work).
    if (this.options.compact !== false) await this.compactor.dropSubsumed(segments).catch((error) => console.error('Could not drop covered library segments:', error))
    if (this.options.watch !== false) this.startWatching()
    if (this.pending.length) this.scheduleFlush(0)
    if (this.retries.size) this.armRetry()
    this.scheduleCompact(this.options.compactDelayMs ?? 60_000)
  }

  private async writeActorNote() {
    const note = {name: this.options.deviceName ?? 'Windows', platform: 'windows', appVersion: this.options.appVersion ?? '', lastSeenAt: new Date(this.options.now()).toISOString()}
    await writeJsonAtomic(path.join(this.recordsRoot, 'actors', `${this.actor}.json`), note)
  }

  // ------------------------------------------------------------------ reading

  /** The merge state, for readers that stream (the worker's full projection). */
  get state(): SqlState {
    return this.sql
  }

  /** The device-local database file. */
  private get statePath() {
    return this.options.statePath ?? path.join(path.dirname(this.options.outboxDir), 'state', 'library-state.v1.db')
  }

  /**
   * Opens the database at [dbPath]. A file of another layout, semantics or library, or one that is not a usable database,
   * is deleted and started again: the segments are the source of truth.
   */
  private openState(dbPath: string): SqlState {
    const fresh = () => {
      for (const suffix of ['', '-wal', '-shm', '-journal']) rmSync(dbPath + suffix, {force: true})
      return new SqlState(dbPath, {synchronous: 'NORMAL'})
    }
    let sql: SqlState
    try {
      sql = new SqlState(dbPath, {synchronous: 'NORMAL'})
      const format = sql.getMeta('format')
      if (format !== null && (format !== STATE_FORMAT || sql.getMeta('library') !== this.options.libraryId || sql.getMeta('semantics') !== `${SEMANTICS}.${FORMAT}`)) {
        sql.close()
        sql = fresh()
      }
    } catch {
      sql = fresh()
    }
    return sql
  }

  private markStateReady() {
    this.sql.setMeta('format', STATE_FORMAT)
    this.sql.setMeta('library', this.options.libraryId)
    this.sql.setMeta('semantics', `${SEMANTICS}.${FORMAT}`)
  }

  /**
   * The first big import into a library that holds only the ops this device wrote since it was created (a few: the spaces and
   * the profile). The database is not empty, so the one-pass import refuses it; since the database is a cache of those ops, it
   * is started again and everything goes in in one pass, the earlier ops first. Returns the codes of [texts], or null when the
   * library holds anything else (segments, other devices, problems), in which case nothing is changed.
   */
  private importOverFirstOps(texts: readonly string[]): string[] | null {
    if (this.problems.size || Object.keys(this.equivocations).length || this.pending.length > 1000) return null
    if (this.pending.some((record) => record.actor !== this.actor)) return null
    for (const [actor, last] of this.flushedLast) if (actor !== this.actor || last > 1000) return null
    // The ops written so far: this device's segments (read before anything is changed), then the ones not yet in a segment.
    let earlierOps: OpRef[]
    try {
      earlierOps = [...segmentOps(segmentFilesSync(this.opsDir), this.options.libraryId), ...this.pending.map((record) => ({line: record.text, signer: this.deviceId}))]
    } catch {
      return null
    }
    const earlier = earlierOps.map((op) => op.line)
    if (earlier.length !== (this.flushedLast.get(this.actor) ?? 0) + this.pending.length) return null
    const dbPath = this.statePath
    const reopen = () => {
      try {
        this.sql.close()
      } catch {
        // already closed
      }
      for (const suffix of ['', '-wal', '-shm', '-journal']) rmSync(dbPath + suffix, {force: true})
      this.sql = new SqlState(dbPath, {synchronous: 'NORMAL'})
      this.markStateReady()
    }
    try {
      reopen()
      const codes = this.sql.importFresh([...earlier, ...texts], [...earlierOps.map((op) => op.signer), ...texts.map(() => this.deviceId)])
      if (codes) return codes.slice(earlier.length)
      throw new Error('the library database was not empty')
    } catch (error) {
      // Back to what it was: a new database with the earlier ops (a cache of them), and the caller admits the new ones op by op.
      console.error('The first import could not be made in one pass; it is made op by op:', error)
      reopen()
      for (const op of earlierOps) this.admitMany([op.line], op.signer)
      return null
    }
  }

  /** Admits op lines in one transaction, all from the segment signed by [signer] (null: unsigned). */
  private admitMany(ops: string[], signer: string | null = null): AdmitResult[] {
    if (!ops.length) return []
    const results = this.sql.admit(ops, signer).map(parseCode)
    if (!this.enforcedNoted && ops.some((op) => op.includes('"kind":"member.genesis"'))) this.noteEnforced()
    // The database log grows with every commit (commits never fold it into the file themselves). A big import folds it
    // here; ordinary edits leave it to the idle timer.
    this.opsSinceFold += ops.length
    if (this.opsSinceFold >= 20_000) this.foldLog()
    else this.scheduleFold()
    return results
  }

  private enforcedNoted = false

  /** A genesis was admitted: remember it for cold opens (a hint file; membership itself lives in the state database). */
  private noteEnforced() {
    this.enforcedNoted = true
    try {
      writeFileSync(this.enforcedMarker, 'membership is enforced for this library\n', {flag: 'w'})
    } catch (error) {
      console.error('Could not write the membership marker:', error)
    }
  }

  /** How the last big apply (20,000 operations or more) was admitted: for tests and the performance report. */
  lastBigApply: 'one pass' | 'op by op' | undefined
  private opsSinceFold = 0
  private foldTimer?: NodeJS.Timeout

  private foldLog(mode: 'PASSIVE' | 'TRUNCATE' = 'PASSIVE') {
    clearTimeout(this.foldTimer)
    this.foldTimer = undefined
    this.opsSinceFold = 0
    try {
      this.sql.checkpoint(mode)
    } catch {
      // a reader holds the database; the next trigger tries again
    }
  }

  private scheduleFold() {
    if (this.closed || this.foldTimer) return
    this.foldTimer = setTimeout(() => {
      this.foldTimer = undefined
      // A synchronous call between two transactions: it cannot meet a write, so it does not wait in the queue.
      if (!this.closed) this.foldLog()
    }, 3000)
    this.foldTimer.unref?.()
  }

  /** An equivocation moved a set-aside range: the state is rebuilt from the segments (rare, so it is simple and blocking). */
  private rebuildIfNeeded(): boolean {
    if (!this.sql.rebuildRequired) return false
    const libraryId = this.options.libraryId
    // two passes over one fixed list of files: the ops and the device that signed each one's segment, in step
    const files = segmentFilesSync(this.opsDir)
    const outbox = outboxOps(this.outboxFile, this.actor, this.deviceId)
    const lines = {*[Symbol.iterator]() {
      for (const op of segmentOps(files, libraryId)) yield op.line
      for (const op of outbox) yield op.line
    }}
    const signers = {*[Symbol.iterator]() {
      for (const op of segmentOps(files, libraryId)) yield op.signer
      for (const op of outbox) yield op.signer
    }}
    this.sql.rebuild(lines, signers)
    return true
  }

  /** The current views (cached until the state changes). */
  views(): SyncViews {
    if (this.viewsCache?.version !== this.version) this.viewsCache = {version: this.version, views: this.sql.views()}
    return this.viewsCache.views
  }

  /** The highest seq per actor that this store holds (admitted or in a segment), as the folder would list it. */
  currentLast(): Map<string, number> {
    const last = new Map(this.flushedLast)
    for (const item of this.pending) last.set(item.actor, Math.max(last.get(item.actor) ?? 0, item.seq))
    last.set(this.actor, Math.max(last.get(this.actor) ?? 0, this.nextSeq - 1))
    return last
  }

  /** Increases whenever the state changes (own or remote operations). */
  get revision() {
    return this.version
  }

  findings(): string[] {
    const found = [...this.sql.findings()]
    for (const [actor, seq] of Object.entries(this.equivocations)) found.push(`equivocation:${actor}:${seq}`)
    for (const [file, problem] of this.problems) found.push(`unreadable-segment:${file}:${problem}`)
    return found
  }

  /** Operations written but not yet part of a segment. */
  get pendingOps(): number {
    return this.pending.length
  }

  onChange(listener: (change: StoreChange) => void): () => void {
    this.listeners.add(listener)
    return () => void this.listeners.delete(listener)
  }

  private emit(change: StoreChange) {
    for (const listener of this.listeners) {
      try {
        listener(change)
      } catch (error) {
        console.error('Store observer failed:', error)
      }
    }
  }

  /** What a delete or remove must observe, read from the state (exists-tags, revision heads, set element tags). */
  observed(query: SyncObservedQuery): SyncObserved {
    return this.sql.observed(query)
  }

  /** The current songs of a playlist (entry ids with their keys). */
  songs(playlist: string): SyncSong[] {
    return this.sql.songs(playlist)
  }

  /** Partial views for some keys: the cost is those keys, never the library. */
  viewsFor(touched: SyncTouched): SyncPartialViews {
    return this.sql.viewsFor(touched)
  }

  /** The keys other devices changed since the last call (the store's own writes are not in it). */
  takeRemoteTouched(): SyncTouched {
    const out = this.remoteTouched
    this.remoteTouched = emptyTouched()
    return out
  }

  private remoteTouched: SyncTouched = emptyTouched()

  // ------------------------------------------------------------------ writing

  private enqueue<T>(work: () => Promise<T>): Promise<T> {
    const run = this.queue.tail.then(work, work)
    this.queue.tail = run.catch(() => undefined)
    return run
  }

  /**
   * Turns drafts into operations. They are appended to the outbox and fsynced before they are admitted, so a change
   * that returned is never lost. Nothing is changed when any draft is invalid.
   */
  /**
   * [external]: the change comes from outside the library index (a copy run), so the index does not have it yet; its keys
   * are reported like another device's, and the index takes them in through its normal remote path.
   */
  apply(drafts: readonly OpDraft[], options: {external?: boolean} = {}): Promise<void> {
    if (!drafts.length) return Promise.resolve()
    return this.enqueue(() => this.applyNow(drafts, options))
  }

  /** The body of [apply], for callers that already hold the write queue. */
  private async applyNow(drafts: readonly OpDraft[], options: {external?: boolean} = {}): Promise<void> {
    {
      if (this.closed) throw new Error('The library store is closed.')
      let hlc = this.hlc
      let seq = this.nextSeq
      const texts: string[] = []
      const now = this.options.now()
      for (const draft of drafts) {
        hlc = hlcTick(hlc, now, this.actor)
        texts.push(makeOp(this.actor, seq, hlc, draft.kind, draft.body as Record<string, SyncJson>))
        seq++
      }
      await fs.mkdir(this.options.outboxDir, {recursive: true})
      const handle = await fs.open(this.outboxFile, 'a')
      try {
        const chunk = texts.map((text) => `${text}\n`).join('')
        await handle.appendFile(chunk, 'utf8')
        this.outboxBytes += chunk.length
        await handle.sync()
      } finally {
        await handle.close()
      }
      let first = this.nextSeq
      // The first import into an empty database is built in one pass (the same codes as op by op, proven equal in the core).
      const fresh = texts.length >= 20_000 ? (this.sql.importFresh(texts, texts.map(() => this.deviceId)) ?? this.importOverFirstOps(texts)) : null
      if (texts.length >= 20_000) this.lastBigApply = fresh ? 'one pass' : 'op by op'
      if (fresh) {
        for (const code of fresh) {
          const result = parseCode(code)
          if (result.kind !== 'admitted' && result.kind !== 'kept') throw new Error(`The library rejected its own change (${result.kind}).`)
        }
        this.opsSinceFold += texts.length
        this.foldLog()
      }
      // Otherwise a big change is admitted in slices that give the thread back, so reads are answered in between.
      for (let from = 0; !fresh && from < texts.length; from += 2000) {
        for (const result of this.admitMany(texts.slice(from, from + 2000), this.deviceId)) if (result.kind !== 'admitted' && result.kind !== 'kept') throw new Error(`The library rejected its own change (${result.kind}).`)
        if (texts.length > 2000) await new Promise((resolve) => setImmediate(resolve))
      }
      if (!this.pending.length) this.oldestPendingAt = this.options.now()
      for (const text of texts) this.pending.push({actor: this.actor, seq: first++, text})
      this.nextSeq = seq
      this.hlc = hlc
      if (options.external) mergeTouched(this.remoteTouched, this.sql.takeTouched())
      else this.sql.takeTouched() // the caller's model already has its own changes
      this.opsSinceCheckpoint += texts.length
      this.version++
      if (options.external) this.emit({remote: true})
      // Publishing is batched (a segment is a publication, not a safety event: every op is already fsynced in the outbox): wait
      // for a quiet moment, but never longer than the maximum age of the oldest unpublished op.
      const age = this.options.now() - this.oldestPendingAt
      const wait = Math.min(this.options.flushIdleMs ?? 30_000, Math.max(0, (this.options.flushMaxAgeMs ?? 300_000) - age))
      this.scheduleFlush(this.pending.length >= (this.options.flushOps ?? 2000) ? 0 : wait)
    }
  }

  private scheduleFlush(delay: number) {
    if (this.closed) return
    clearTimeout(this.flushTimer)
    this.flushTimer = setTimeout(() => {
      this.flushTimer = undefined
      void this.flush().catch((error) => console.error('Could not write a library segment:', error))
    }, delay)
    this.flushTimer.unref?.()
  }

  /** Writes the outbox as segments (and a checkpoint when due), then empties it. */
  flush(): Promise<void> {
    clearTimeout(this.flushTimer)
    this.flushTimer = undefined
    return this.enqueue(() => this.flushNow())
  }

  private async flushNow(compact = false) {
    if (!this.pending.length) {
      if (compact && this.outboxBytes > 0) await this.compactOutbox()
      return
    }
    const maxOps = Math.min(this.options.segmentMaxOps ?? 5000, 10_000)
    const batch = this.pending
    const runs: Pending[][] = []
    let runBytes = 0
    for (const item of batch) {
      const run = runs[runs.length - 1]
      const last = run?.[run.length - 1]
      if (last && last.actor === item.actor && last.seq + 1 === item.seq && run.length < maxOps && runBytes < 4_000_000) {
        run.push(item)
        runBytes += item.text.length
      } else {
        runs.push([item])
        runBytes = item.text.length
      }
    }
    for (const run of runs) {
      const {actor} = run[0]
      const first = run[0].seq
      const last = run[run.length - 1].seq
      const texts = run.map((entry) => entry.text)
      // this device's own segments are signed; a peer's ops that sit in our outbox are written as they came (unsigned)
      const signed = this.options.signer && actor === this.actor ? signedSegmentBytes(this.options.libraryId, actor, texts, first, this.options.signer) : undefined
      const bytes = signed?.bytes ?? segmentBytes(this.options.libraryId, actor, texts, first)
      const file = path.join(this.opsDir, actor, segmentName(first, last))
      const existing = await fs.readFile(file).catch(() => undefined)
      if (existing) {
        // the same content: byte for byte for v1; for v2 the signed part (a signature differs from run to run)
        const same = Buffer.compare(Buffer.from(existing), Buffer.from(bytes)) === 0 || (signed ? Buffer.from(existing).subarray(0, signed.unsigned.length).equals(signed.unsigned) || Buffer.compare(Buffer.from(existing), segmentBytes(this.options.libraryId, actor, texts, first)) === 0 : false)
        if (!same) throw new Error(`A different segment already exists at ${file}.`)
      }
      if (!existing) await writeBytesDurable(file, bytes)
      if (actor === this.actor) this.compactor.filesSinceCompact++
      this.processed.add(`${actor}/${segmentName(first, last)}`)
      this.flushedLast.set(actor, Math.max(this.flushedLast.get(actor) ?? 0, last))
    }
    const flushed = new Set(batch)
    this.pending = this.pending.filter((item) => !flushed.has(item))
    // The outbox keeps the flushed operations (at open they are recognised as already in a segment) and is emptied only
    // when it has grown or on close: one fsync per flush instead of four.
    if (compact || this.outboxBytes > 4_000_000) await this.compactOutbox()
    if (this.opsSinceCheckpoint >= (this.options.checkpointEvery ?? 5000)) this.scheduleCheckpoint()
    if (this.compactor.filesSinceCompact >= 8) this.scheduleCompact(this.options.compactSoonMs ?? 5000)
  }

  /** The counter is saved before the outbox is emptied, so a restored install is told from a crash. */
  private async compactOutbox() {
    await writeJsonAtomic(this.seqFile(), {actor: this.actor, lastSeq: this.nextSeq - 1})
    const rest = this.pending.map((item) => `${item.text}\n`).join('')
    await fs.writeFile(this.outboxFile, rest)
    this.outboxBytes = Buffer.byteLength(rest)
  }

  /** Reads every checkpoint part file (checkpoints\\<shard>\\*.ckpt). Unreadable ones are only a cache miss. */
  private async readParts(): Promise<Uint8Array[]> {
    const out: Uint8Array[] = []
    for (const shard of CHECKPOINT_SHARDS) {
      for (const name of await fs.readdir(path.join(this.ckptDir, shard)).catch(() => [] as string[])) {
        if (!name.endsWith('.ckpt')) continue
        try {
          out.push(await fs.readFile(path.join(this.ckptDir, shard, name)))
        } catch {
          // skipped
        }
      }
    }
    return out
  }

  /** Writes checkpoints off the save path: the parts of the shards that changed, one at a time, yielding in between. */
  private scheduleCheckpoint() {
    if (this.closed || this.checkpointTimer) return
    this.checkpointTimer = setTimeout(() => {
      this.checkpointTimer = undefined
      void this.enqueue(() => this.writeParts()).catch((error) => console.error('Could not write a library checkpoint:', error))
    }, this.options.checkpointDelayMs ?? 1500)
    this.checkpointTimer.unref?.()
  }

  private async writeParts() {
    if (this.closed || this.opsSinceCheckpoint < (this.options.checkpointEvery ?? 5000)) return
    // A checkpoint never holds an operation that is not in a segment yet.
    if (this.pending.length) await this.flushNow()
    // Ops held above a gap are in the tables but must not be in a checkpoint: try again at the next trigger.
    const coverage = this.sql.checkpointCoverage()
    if (!coverage) return
    this.opsSinceCheckpoint = 0
    const name = `${this.sql.maxHlc() ?? '0000000000000.000000'}-${this.actor}.ckpt`
    // Only the shards whose keys changed since their part was written, one part at a time with the thread handed back
    // in between: building a part blocks it for as long as it takes to read that shard's rows and encode them.
    const shards = shardsOf(this.sql.dirtyShards)
    let built = 0
    let bytes = 0
    for (const shard of shards) {
      const started = performance.now()
      const part = await this.sql.partBytes(this.options.libraryId, shard, coverage, {yield: () => new Promise((resolve) => setImmediate(resolve))})
      built += performance.now() - started
      this.sql.markShardsClean(1 << CHECKPOINT_SHARDS.indexOf(shard))
      bytes += part.length
      await writeBytesDurable(path.join(this.ckptDir, shard, name), part)
      // Parts are caches: this actor keeps its newest of each shard (sync spec 10.4).
      for (const old of await fs.readdir(path.join(this.ckptDir, shard)).catch(() => [] as string[])) {
        if (old !== name && old.endsWith(`-${this.actor}.ckpt`)) await fs.rm(path.join(this.ckptDir, shard, old), {force: true}).catch(() => undefined)
      }
      await new Promise((resolve) => setImmediate(resolve))
    }
    this.options.onCheckpoint?.({parts: shards.length, buildMs: built, bytes})
  }

  // ------------------------------------------------------------------ compaction (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md)
  //
  // Nothing is ever dropped, only repackaged: this device's own small segments are merged into larger ones (same ops, same
  // key), the merged file is read back and compared op for op, and only then are the inputs removed. A crash at any step
  // leaves a folder every reader handles (originals only, originals plus the merged file, a subset, the merged file alone).

  private scheduleCompact(delay: number) {
    if (this.closed || this.options.compact === false) return
    const wait = Math.max(delay, this.compactor.lastCompactAt + (this.options.compactGapMs ?? 600_000) - Date.now())
    const at = Date.now() + wait
    if (this.compactTimer && this.compactAt <= at) return // already due sooner
    clearTimeout(this.compactTimer)
    this.compactAt = at
    this.compactTimer = setTimeout(() => {
      this.compactTimer = undefined
      void this.compact().catch((error) => console.error('Could not compact the library segments:', error))
    }, wait)
    this.compactTimer.unref?.()
  }

  /** One compaction round, in the store's queue: drops covered files, then merges this device's small segments. */
  compact(): Promise<CompactStats> {
    return this.enqueue(() => this.compactor.compactRound())
  }
  /** Starts a segment transfer (a peer is being sent files, or pushed to): compaction removes no file while one is open. */
  beginTransfer(): Promise<string> {
    return this.compactor.beginTransfer()
  }

  /** [beginTransfer] as a function: call the result when the transfer is over. */
  async hold(): Promise<() => void> {
    const token = await this.beginTransfer()
    return () => this.endTransfer(token)
  }

  endTransfer(token: string): void {
    this.compactor.endTransfer(token)
  }


  // ------------------------------------------------------------------ membership (network spec 9.1 #1 and #2)

  /** Who may write, as the member ops admitted so far define it. */
  membership(): SyncMembership {
    return this.sql.membership()
  }

  /** The first signed op of an enforced library. Signs the freezing of unsigned history. A no-op when one is held. */
  enableMembership(grant?: {actor: string; spkiBase64: string; perms: string[]}): Promise<void> {
    return this.enqueue(async () => {
      if (!this.options.signer || !this.deviceId) throw new Error('This device has no key to sign with.')
      if (this.sql.membership().active) return
      await this.flushNow() // every op so far is in a segment, so each actor's last segment can be named
      const last = new Map<string, {seq: number; file: string}>()
      for (const segment of await listSegments(this.opsDir)) {
        const known = last.get(segment.actor)
        if (!known || segment.last > known.seq) last.set(segment.actor, {seq: segment.last, file: segment.file})
      }
      const actors: SyncJson[] = []
      for (const [actor, entry] of [...last].sort(([a], [b]) => (a < b ? -1 : 1))) {
        const hash = createHash('sha256').update(await fs.readFile(entry.file)).digest('hex')
        actors.push({actor, seq: entry.seq, lastSegmentHash: hash})
      }
      // the genesis and the first grant go into one segment, so no peer ever holds an enforced library that does not know it yet
      const drafts: OpDraft[] = [{kind: 'member.genesis', body: {deviceKey: Buffer.from(this.options.signer.spki).toString('base64'), actors}}]
      if (grant) drafts.push({kind: 'member.grant', body: {actor: grant.actor, deviceKey: grant.spkiBase64, perms: grant.perms, epoch: 1}})
      await this.applyNow(drafts)
      await this.flushNow()
    })
  }

  private requireAdmin() {
    const info = this.sql.membership()
    if (!info.active) throw new Error('Devices are not enabled for this library yet.')
    if (!info.members[this.actor]?.perms.includes('admin')) throw new Error('Only an administrator device can add or remove devices.')
    return info
  }

  /** `member.grant`: lets [actor] write with the key [spkiBase64]. Needs admin. */
  grantMember(actor: string, spkiBase64: string, perms: string[]): Promise<void> {
    return this.enqueue(async () => {
      const info = this.requireAdmin()
      const epoch = (info.members[actor]?.epoch ?? 0) + 1
      await this.applyNow([{kind: 'member.grant', body: {actor, deviceKey: spkiBase64, perms, epoch}}])
      await this.flushNow()
    })
  }

  /** `member.revoke`: [actor]'s ops above [cutoffSeq] are set aside, never applied. Needs admin. */
  revokeMember(actor: string, cutoffSeq: number): Promise<void> {
    return this.enqueue(async () => {
      this.requireAdmin()
      await this.applyNow([{kind: 'member.revoke', body: {actor, cutoff: {seq: cutoffSeq, segmentHash: ''}}}])
      await this.flushNow()
    })
  }

  /** The items whose current revision has [contentId], with where each is placed (for serving a file to a peer). */
  contentItems(contentId: string): {id: string; placements: Record<string, string | null>}[] {
    const rows = this.sql.prep('SELECT id FROM item_view WHERE heads LIKE ? LIMIT 4').all(`%${contentId.replace(/[%_\\]/g, '')}%`) as {id: string}[]
    const out: {id: string; placements: Record<string, string | null>}[] = []
    for (const row of rows) {
      const touched = emptyTouched()
      touched.items.push(row.id)
      out.push({id: row.id, placements: this.sql.viewsFor(touched).placements[row.id] ?? {}})
    }
    return out
  }

  /** The bytes of the music videos the library records beside its songs (`companion:.video.*`); only items that record one are parsed. */
  musicVideoBytes(): number {
    let bytes = 0
    for (const row of this.sql.prep("SELECT fields FROM item_view WHERE status = 'present' AND instr(fields, '\"companion:.video.') > 0").iterate() as Iterable<{fields: string}>)
      for (const [name, value] of Object.entries(JSON.parse(row.fields) as Record<string, {size?: unknown} | null>))
        if (name.startsWith('companion:.video.') && typeof value?.size === 'number' && value.size > 0) bytes += value.size
    return bytes
  }

  /** Where the library's files are: per replica, kind and space (see where.ts). [completeCopies] are the copies that hold all they should. */
  whereCounts(mainReplica: string, completeCopies: readonly CopyCover[] = []): WhereCounts {
    return whereCounts(this.sql, mainReplica, completeCopies)
  }

  /** Who has a file for [itemId], and what a copy would select it by. */
  whereItem(itemId: string): WhereItemFacts {
    return whereItem(this.sql, itemId)
  }
  // ------------------------------------------------------------------ other actors

  private startWatching() {
    try {
      this.watcher = fsWatch(this.opsDir, {recursive: true, persistent: false}, () => {
        clearTimeout(this.watchTimer)
        this.watchTimer = setTimeout(() => {
          this.watchTimer = undefined
          void this.rescan().catch(() => undefined)
        }, this.options.watchDebounceMs ?? 250)
        this.watchTimer.unref?.()
      })
      this.watcher.on('error', () => {
        this.watcher?.close()
        this.watcher = undefined
      })
    } catch {
      // The folder may be on a volume that cannot be watched; rescan() can be called by the app.
    }
  }

  private scheduleRetry(key: string) {
    const known = this.retries.get(key)
    const attempts = (known?.attempts ?? 0) + 1
    const delay = Math.min((this.options.retryMs ?? 500) * 2 ** (attempts - 1), 30_000)
    this.retries.set(key, {attempts, at: Date.now() + delay})
  }

  private noteUnreadable(key: string, reason: string) {
    // The first tries are a cloud client still writing the file; only a persistent failure is reported.
    this.scheduleRetry(key)
    if ((this.retries.get(key)?.attempts ?? 0) >= 6) this.problems.set(key, reason)
  }

  private armRetry() {
    if (this.closed || this.retryTimer || !this.retries.size) return
    const next = Math.min(...[...this.retries.values()].map((entry) => entry.at))
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined
      void this.rescan().catch(() => undefined)
    }, Math.max(20, next - Date.now()))
    this.retryTimer.unref?.()
  }

  /** Reads segments that arrived since the last look and admits their operations. */
  rescan(options: {force?: boolean} = {}): Promise<void> {
    return this.enqueue(async () => {
      if (this.closed) return
      let changed = false
      let fresh = false
      const listed = await listSegments(this.opsDir)
      for (const segment of listed) {
        const key = `${segment.actor}/${segment.name}`
        if (this.processed.has(key)) continue
        const retry = this.retries.get(key)
        if (retry && retry.at > Date.now() && !options.force) continue
        let value: ReturnType<typeof decodeSegment>
        try {
          value = decodeSegment(await fs.readFile(segment.file), this.options.libraryId)
        } catch {
          this.scheduleRetry(key)
          continue
        }
        if (!value.ok) {
          this.noteUnreadable(key, value.error)
          continue
        }
        this.processed.add(key)
        fresh = true
        this.retries.delete(key)
        this.problems.delete(key)
        this.flushedLast.set(segment.actor, Math.max(this.flushedLast.get(segment.actor) ?? 0, segment.last))
        for (const result of this.admitMany(value.ops, value.signer)) {
          if (result.kind === 'admitted' || result.kind === 'kept') {
            changed = true
            this.opsSinceCheckpoint++
          } else if (result.kind === 'equivocation') changed = true
        }
      }
      if (changed) {
        this.rebuildIfNeeded()
        mergeTouched(this.remoteTouched, this.sql.takeTouched())
        const max = this.sql.maxHlc()
        if (max) this.hlc = hlcReceive(this.hlc, max, this.options.now(), this.actor)
        this.version++
        this.emit({remote: true})
      }
      // a new file may cover older ones (a peer that sent both, a sync tool that brought an old pile back)
      if (fresh && this.options.compact !== false) await this.compactor.dropSubsumed(listed).catch((error) => console.error('Could not drop covered library segments:', error))
      this.armRetry()
    })
  }

  // ------------------------------------------------------------------ end

  async close(): Promise<void> {
    if (this.closed) return
    clearTimeout(this.flushTimer)
    clearTimeout(this.retryTimer)
    clearTimeout(this.watchTimer)
    clearTimeout(this.checkpointTimer)
    clearTimeout(this.compactTimer)
    this.compactTimer = undefined
    this.watcher?.close()
    this.watcher = undefined
    await this.enqueue(async () => {
      await this.flushNow(true)
      await this.writeParts() // a due checkpoint is written now, so a new device opens fast
      // enough small files and no compaction for a while: do it now rather than carry them to the next start
      if (this.options.compact !== false && this.compactor.filesSinceCompact >= 8 && Date.now() - this.compactor.lastCompactAt >= (this.options.compactGapMs ?? 600_000)) await this.compactor.compactRound().catch((error) => console.error('Could not compact the library segments:', error))
    })
    this.closed = true
    this.closeState(true)
  }

  private closeState(fold: boolean) {
    clearTimeout(this.foldTimer)
    this.foldTimer = undefined
    try {
      if (fold) this.sql.checkpoint('TRUNCATE')
      this.sql.close()
    } catch {
      // already closed
    }
  }

  /** Stops timers and watchers without writing anything (tests that simulate a crash). */
  abandon(): void {
    this.closed = true
    clearTimeout(this.flushTimer)
    clearTimeout(this.retryTimer)
    clearTimeout(this.watchTimer)
    clearTimeout(this.checkpointTimer)
    clearTimeout(this.compactTimer)
    this.watcher?.close()
    this.watcher = undefined
    this.closeState(false)
  }
}
