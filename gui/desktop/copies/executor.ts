// The desktop executor of copies (spec section 5). It runs a plan from the shared-core planner against a main Keepfin
// folder and a copy folder. Every action is journaled in separate durable steps (intent, staged, preserved, published,
// committed), checks its preconditions right before it mutates, and never overwrites or deletes anything outright:
// displaced bytes go to the copy's versions or trash first. A precondition that does not hold skips the action and
// records it; the next plan decides again.
import fs from 'node:fs/promises'
import path from 'node:path'
import type {BaselineEntry, CopyMode, NameRulesId, PlanAction} from '../../shared/core/copies.js'
import {fastKey} from '../../shared/core/copies.js'
import {renameWithRetry} from '../fsutil.js'
import {hashFile} from './hash.js'
import {DestinationExistsError, moveNoReplace, stageFile, statOf, Throttle, type FileStat} from './fsops.js'
import {CrashError, DisconnectedError, RemoteStopError, SkipError, code, exists, rootGone, uniqueName, under, withCounter} from './util.js'
import {hashRemote, type RemoteErrorKind, type RemoteMedia, type RemoteStopState} from './remote.js'
import {Journal, type BaselineOp, type FaultHook} from './journal.js'
import type {NameMap} from './names.js'
import {appendCatalogue, moveToArchive, planArchive, voidCatalogue, type Store} from './versions.js'

/** What the library layer does on the main side. The executor stages verified bytes; the host turns them into library ops. */
export interface MainHost {
  /** A file only the copy has becomes an item (or, in a conflict, another revision of [action.itemId]) at [destPath]. */
  importNew?(req: {action: PlanAction; stagedPath: string; destPath: string}): Promise<{itemId: string; revisionOp?: string | null; path: string}>
  /** The copy's bytes become a new revision of the item. */
  reviseFromCopy?(req: {action: PlanAction; stagedPath: string}): Promise<{revisionOp?: string | null}>
  /** The copy edited what the library deleted: restore it as a revision, for review. */
  restoreEditedDeleted?(req: {action: PlanAction; stagedPath: string}): Promise<{revisionOp?: string | null}>
  /** A user-confirmed deletion on the copy becomes `item.delete`. */
  deleteItem?(req: {action: PlanAction}): Promise<void>
}

export interface Progress {
  doneActions: number
  totalActions: number
  doneBytes: number
  totalBytes: number
  current: string | null
  /** The copy's own Keepfin data is moving (not the person's files): the counts stay zero so they are never shown as files. */
  data?: boolean
}

export interface ExecConfig {
  mainRoot: string
  copyRoot: string
  mode: CopyMode
  rules: NameRulesId
  runId: string
  /** The generation this run commits at. */
  generation: number
  names: NameMap
  journal: Journal
  host?: MainHost
  /** 2 on an HDD, 4 on an SSD (spec 5.6). */
  parallelism?: number
  /** An optional speed limit, bytes per second. */
  speedLimit?: number
  /** Files larger than this are skipped and reported (4 GiB - 1 on FAT32). */
  maxFileBytes?: number
  now?: () => number
  fault?: FaultHook
  /** Pause: the current actions finish and the run stops. */
  signal?: AbortSignal
  onProgress?: (p: Progress) => void
  /** The copy is an account or a device, not a folder: its media go through this, and only the copy's own .keepfin data is under [copyRoot]. */
  remote?: RemoteMedia
  /** Where the one file in flight per worker is staged (remote copies); emptied after every action. */
  staging?: string
  /** The content id of a copy file that this run already hashed at that size and time, so a decision is not paid for twice with a download. */
  knownCopyHash?: (path: string, size: number, mtimeMs: number) => string | undefined
}

/** What a remote copy did, for the words the person reads. */
export interface RemoteTally {
  uploaded: number
  replaced: number
  moved: number
  trashed: number
  downloaded: number
  bytesUp: number
  bytesDown: number
}

export interface SkippedAction {
  n: number
  kind: string
  path: string
  reason: string
}

export interface ExecReport {
  status: 'done' | 'paused' | 'disconnected' | 'crashed'
  committed: number
  skipped: SkippedAction[]
  skippedCount: number
  failed: SkippedAction[]
  failedCount: number
  bytes: number
  /** At least one action was skipped because a precondition no longer held: plan again. */
  needsReplan: boolean
  byKind: Record<string, number>
  /** A remote copy only. */
  remote?: RemoteTally
  /** A remote copy only: why the run ended before it was done (sign-in refused, account full, not reachable). */
  remoteStop?: {state: RemoteStopState; message: string}
}

type Outcome = {ops: BaselineOp[]}

const ZERO: FileStat = {size: 0, mtimeMs: 0, fileId: null}
const MAX_LISTED = 1000
/** Skip reasons that mean "the world changed, plan again". */
const REPLAN = new Set(['destination-not-absent', 'destination-missing', 'unexpected-destination', 'source-missing', 'hash-mismatch', 'destination-appeared', 'destination-occupied', 'copy-file-missing', 'busy', 'earlier-step'])

export async function executePlan(cfg: ExecConfig, actions: Iterable<PlanAction>, totals: {actions: number; bytes: number}): Promise<ExecReport> {
  const runner = new Runner(cfg, totals)
  return runner.run(actions)
}

class Runner {
  private readonly now: () => number
  private readonly throttle: Throttle
  private readonly report: ExecReport = {status: 'done', committed: 0, skipped: [], skippedCount: 0, failed: [], failedCount: 0, bytes: 0, needsReplan: false, byKind: {}}
  private doneActions = 0
  private doneBytes = 0
  private lastProgress = 0
  private current: string | null = null
  private crashed = false

  constructor(private readonly cfg: ExecConfig, private readonly totals: {actions: number; bytes: number}) {
    this.now = cfg.now ?? Date.now
    this.throttle = new Throttle(cfg.speedLimit)
    if (cfg.remote) this.report.remote = {uploaded: 0, replaced: 0, moved: 0, trashed: 0, downloaded: 0, bytesUp: 0, bytesDown: 0}
  }

  // ---- paths ----

  private mainAbs(lib: string): string {
    return under(this.cfg.mainRoot, lib)
  }

  private copyAbsPhysical(physical: string): string {
    return under(this.cfg.copyRoot, physical)
  }

  private copyExisting(lib: string): string {
    return this.copyAbsPhysical(this.cfg.names.physical(lib))
  }

  /** The destination on the copy for [lib], with names the file system cannot take mapped (durably) first. */
  private async copyForWrite(lib: string): Promise<{physical: string; abs: string}> {
    const physical = await this.cfg.names.resolve(lib)
    return {physical, abs: this.copyAbsPhysical(physical)}
  }

  // ---- running ----

  async run(actions: Iterable<PlanAction>): Promise<ExecReport> {
    const limit = Math.max(1, this.cfg.parallelism ?? 2)
    const running = new Set<Promise<void>>()
    let failure: unknown
    let failed = false
    try {
      for (const group of groups(actions, this.cfg.rules)) {
        if (this.cfg.signal?.aborted) {
          this.report.status = 'paused'
          break
        }
        if (failed) break
        const p: Promise<void> = this.runGroup(group.start, group.actions)
          .catch((error) => {
            if (!failed) {
              failed = true
              failure = error
            }
          })
          .finally(() => running.delete(p))
        running.add(p)
        if (running.size >= limit) await Promise.race(running)
      }
      await Promise.all(running)
    } finally {
      this.emitProgress(true)
    }
    if (failed) {
      if (failure instanceof CrashError) {
        this.report.status = 'crashed'
      } else if (failure instanceof DisconnectedError) {
        this.report.status = 'disconnected'
      } else if (failure instanceof RemoteStopError) {
        this.report.status = 'disconnected'
        this.report.remoteStop = {state: failure.state, message: failure.message}
      } else {
        throw failure
      }
    }
    if (this.report.status === 'done' && this.cfg.signal?.aborted) this.report.status = 'paused'
    this.report.needsReplan = this.report.skipped.some((s) => REPLAN.has(s.reason)) || this.report.skippedCount > this.report.skipped.length
    return this.report
  }

  private async runGroup(start: number, group: PlanAction[]): Promise<void> {
    let broken: string | null = null
    for (let i = 0; i < group.length; i++) {
      const a = group[i]
      const n = start + i
      if (broken) {
        await this.record(n, a, 'skipped', 'earlier-step')
        continue
      }
      if (this.cfg.signal?.aborted && i > 0) {
        // the group's remaining steps stay unrun; the next plan resumes them
        await this.record(n, a, 'skipped', 'paused')
        broken = 'paused'
        continue
      }
      this.current = a.path
      try {
        const out = await this.runAction(n, a)
        await this.cfg.journal.append({t: 'committed', run: this.cfg.runId, n, ops: out.ops}, false)
        await this.fault('committed', n, a)
        this.report.committed++
        this.report.byKind[a.kind] = (this.report.byKind[a.kind] ?? 0) + 1
        this.doneBytes += a.bytes
      } catch (error) {
        if (error instanceof CrashError) {
          this.crashed = true
          this.cfg.journal.kill()
          throw error
        }
        if (this.cfg.journal.isDead) throw new CrashError('journal-dead')
        if (error instanceof DisconnectedError || error instanceof RemoteStopError) throw error
        if (error instanceof SkipError) {
          await this.record(n, a, 'skipped', error.reason)
          broken = error.reason
        } else {
          if ((await this.copyGone(error)) ) throw new DisconnectedError(this.cfg.copyRoot, error)
          await this.record(n, a, 'failed', error instanceof Error ? error.message : String(error))
          broken = 'failed'
        }
      }
      this.doneActions++
      this.emitProgress(false)
    }
  }

  private async copyGone(error: unknown): Promise<boolean> {
    const c = code(error)
    if (c === 'EIO') return true
    if (c === 'ENOENT' || c === 'ENOTDIR' || c === 'ENXIO' || c === 'ENODEV') return rootGone(this.cfg.copyRoot)
    return false
  }

  private async record(n: number, a: PlanAction, t: 'skipped' | 'failed', reason: string): Promise<void> {
    const rec = {n, kind: a.kind, path: a.path, reason}
    if (t === 'skipped') {
      this.report.skippedCount++
      if (this.report.skipped.length < MAX_LISTED) this.report.skipped.push(rec)
    } else {
      this.report.failedCount++
      if (this.report.failed.length < MAX_LISTED) this.report.failed.push(rec)
    }
    try {
      await this.cfg.journal.append({t, run: this.cfg.runId, n, reason}, false)
    } catch (error) {
      if (!(error instanceof CrashError)) throw error
    }
  }

  private emitProgress(force: boolean): void {
    if (!this.cfg.onProgress) return
    const t = this.now()
    if (!force && t - this.lastProgress < 250) return
    this.lastProgress = t
    this.cfg.onProgress({doneActions: this.doneActions, totalActions: this.totals.actions, doneBytes: this.doneBytes, totalBytes: this.totals.bytes, current: this.current})
  }

  private async fault(step: string, n: number, action: PlanAction): Promise<void> {
    await this.cfg.fault?.(step, {n, action})
  }

  private onBytes = (bytes: number) => {
    this.report.bytes += bytes
  }

  // ---- one action ----

  private async runAction(n: number, a: PlanAction): Promise<Outcome> {
    const remote = this.cfg.remote !== undefined
    switch (a.kind) {
      case 'CopyTo':
        return remote ? this.copyToRemote(n, a) : this.copyTo(n, a)
      case 'MoveOnCopy':
        return remote ? this.moveOnCopyRemote(n, a) : this.moveOnCopy(n, a)
      case 'RemoveFromCopy':
        return remote ? this.removeFromCopyRemote(n, a) : this.removeFromCopy(n, a)
      case 'Converge':
        return remote ? this.convergeRemote(n, a) : this.converge(n, a)
      case 'ForgetBaseline':
        await this.intent(n, a, null, null)
        return {ops: [{path: a.path, entry: null}]}
      case 'RestoreFromCopy':
        return this.restoreFromCopy(n, a)
      case 'ImportNew':
      case 'ReviseFromCopy':
      case 'RestoreEditedDeleted':
        return this.viaHost(n, a)
      case 'DeleteItem': {
        await this.intent(n, a, null, null)
        if (!this.cfg.host?.deleteItem) throw new SkipError('needs-library', 'No library host to record the deletion.')
        await this.cfg.host.deleteItem({action: a})
        return {ops: []}
      }
    }
  }

  private async intent(n: number, a: PlanAction, tmp: string | null, dest: string | null): Promise<void> {
    await this.cfg.journal.append({t: 'intent', run: this.cfg.runId, n, action: a, tmp, dest}, true)
    await this.fault('intent', n, a)
    // a vanished copy root must not be recreated by a mkdir further down: that is a disconnect, not an absence
    if (await rootGone(this.cfg.copyRoot)) throw new DisconnectedError(this.cfg.copyRoot)
  }

  private entry(a: PlanAction, lib: string, contentId: string, main: FileStat, copy: FileStat, itemId?: string | null, revisionOp?: string | null): BaselineEntry {
    return {
      path: lib,
      itemId: itemId ?? a.itemId ?? '',
      revisionOp: revisionOp === undefined ? a.revisionOp : revisionOp,
      contentId,
      main: stat(main),
      copy: stat(copy),
      scannedAtMs: this.now(),
      generation: this.cfg.generation,
    }
  }

  /** The destination's own state checked against the plan's expectation, before anything is staged. */
  private async checkDestination(a: PlanAction, dest: string): Promise<FileStat | null> {
    const st = await statOf(dest)
    const expect = a.expect
    if (!expect || expect.contentId === null) {
      if (st) throw new SkipError('destination-not-absent', `${a.path} is already on the copy.`)
      return null
    }
    if (!st) throw new SkipError('destination-missing', `${a.path} is no longer on the copy.`)
    if (expect.fileId && st.fileId && expect.fileId !== st.fileId) throw new SkipError('unexpected-destination', `${a.path} on the copy is a different file than planned.`)
    return st
  }

  // ---- CopyTo: stage, verify, preserve, publish ----

  private async copyTo(n: number, a: PlanAction): Promise<Outcome> {
    if (!a.contentId) throw new Error('A copy needs a pinned content id.')
    const {physical, abs: dest} = await this.copyForWrite(a.path)
    const tmp = `${dest}.kftmp-${this.cfg.runId}-${n}`
    await this.intent(n, a, tmp, dest)
    const src = this.mainAbs(a.path)
    const existing = await this.checkDestination(a, dest)
    const staged = await this.stage(n, a, src, tmp, a.contentId)
    // keep the source's modification time on the copy: a freshly written time would look "just transferred" at the next scan
    await fs.utimes(tmp, new Date(), new Date(staged.source.mtimeMs)).catch(() => undefined)
    let archived: {store: Store; archive: string; id: string} | null = null
    try {
      if (existing) {
        const now = await hashFile(dest)
        if (now.contentId !== a.expect!.contentId) throw new SkipError('unexpected-destination', `${a.path} on the copy changed since it was planned.`)
        archived = await this.preserve(n, a, dest, physical, a.archive ?? 'Trash', a.expect!.contentId!, 'replaced')
      }
      await this.fault('before-publish', n, a)
      try {
        await moveNoReplace(tmp, dest)
      } catch (error) {
        if (error instanceof DestinationExistsError) throw new SkipError('destination-appeared', `${a.path} appeared on the copy.`)
        throw error
      }
    } catch (error) {
      if (error instanceof CrashError) throw error
      await fs.rm(tmp, {force: true}).catch(() => undefined)
      if (archived && !(await exists(dest))) await this.restoreArchived(archived, dest)
      throw error
    }
    await this.cfg.journal.append({t: 'published', run: this.cfg.runId, n}, false)
    await this.fault('published', n, a)
    const copyStat = (await statOf(dest)) ?? staged.stat
    return {ops: [{path: a.path, entry: this.entry(a, a.path, a.contentId, staged.source, copyStat)}]}
  }

  private async stage(n: number, a: PlanAction, src: string, tmp: string, expected: string) {
    const staged = await stageFile(src, tmp, {
      expected,
      throttle: this.throttle,
      onBytes: this.onBytes,
      maxBytes: this.cfg.maxFileBytes,
      signal: this.cfg.signal,
      afterOpen: () => this.fault('transfer', n, a),
    })
    await this.cfg.journal.append({t: 'staged', run: this.cfg.runId, n, tmp, contentId: expected}, false)
    await this.fault('staged', n, a)
    return staged
  }

  /** Journals the catalogue entry first, then moves the displaced bytes aside (spec 5.1 step 3). */
  private async preserve(n: number, a: PlanAction, dest: string, physical: string, store: 'Versions' | 'Trash', contentId: string, why: string): Promise<{store: Store; archive: string; id: string}> {
    const kind: Store = store === 'Versions' ? 'versions' : 'trash'
    const {entry, absolute} = await planArchive(this.cfg.copyRoot, kind, {
      itemId: a.itemId,
      revision: null,
      contentId,
      path: a.path,
      physical,
      run: this.cfg.runId,
      n,
      at: this.now(),
      why,
    })
    await appendCatalogue(this.cfg.copyRoot, entry)
    await this.cfg.journal.append({t: 'preserved', run: this.cfg.runId, n, from: dest, archive: absolute, catalogueId: entry.id, store: kind}, true)
    await this.fault('preserve-before-move', n, a)
    try {
      await moveToArchive(dest, absolute)
    } catch (error) {
      await voidCatalogue(this.cfg.copyRoot, kind, entry.id).catch(() => undefined)
      if (error instanceof DestinationExistsError) throw new SkipError('busy', 'The archive name was taken.')
      const c = code(error)
      if (c === 'EBUSY' || c === 'EPERM' || c === 'EACCES') throw new SkipError('busy', `${a.path} is in use on the copy.`)
      throw error
    }
    await this.fault('preserved', n, a)
    return {store: kind, archive: absolute, id: entry.id}
  }

  private async restoreArchived(archived: {store: Store; archive: string; id: string}, dest: string): Promise<void> {
    try {
      await moveNoReplace(archived.archive, dest)
      await voidCatalogue(this.cfg.copyRoot, archived.store, archived.id)
    } catch {
      // the bytes stay in the archive, which is safe; recovery or the user can restore them
    }
  }

  // ---- RemoveFromCopy: to the trash, never deleted ----

  private async removeFromCopy(n: number, a: PlanAction): Promise<Outcome> {
    const physical = this.cfg.names.physical(a.path)
    const dest = this.copyAbsPhysical(physical)
    await this.intent(n, a, null, dest)
    const st = await statOf(dest)
    if (!st) return {ops: [{path: a.path, entry: null}]} // already gone: only the baseline entry remains
    if (a.expect?.fileId && st.fileId && a.expect.fileId !== st.fileId) throw new SkipError('unexpected-destination', `${a.path} on the copy is a different file than planned.`)
    const now = await hashFile(dest)
    if (a.expect?.contentId && now.contentId !== a.expect.contentId) throw new SkipError('unexpected-destination', `${a.path} on the copy changed since it was planned.`)
    await this.preserve(n, a, dest, physical, a.archive ?? 'Trash', now.contentId, a.reason === 'ItemDeleted' ? 'deleted' : 'removed')
    await this.cfg.journal.append({t: 'published', run: this.cfg.runId, n}, false)
    return {ops: [{path: a.path, entry: null}]}
  }

  // ---- MoveOnCopy: a rename, hash-checked ----

  private async moveOnCopy(n: number, a: PlanAction): Promise<Outcome> {
    if (!a.toPath) throw new Error('A move needs a destination.')
    const from = this.copyExisting(a.path)
    const to = (await this.copyForWrite(a.toPath)).abs
    await this.intent(n, a, null, to)
    const st = await statOf(from)
    if (!st) throw new SkipError('copy-file-missing', `${a.path} is no longer on the copy.`)
    if (a.expect?.fileId && st.fileId && a.expect.fileId !== st.fileId) throw new SkipError('unexpected-destination', `${a.path} on the copy is a different file than planned.`)
    const h = await hashFile(from)
    if (a.expect?.contentId && h.contentId !== a.expect.contentId) throw new SkipError('unexpected-destination', `${a.path} on the copy changed since it was planned.`)
    try {
      if (fastKey(this.cfg.rules, a.path) === fastKey(this.cfg.rules, a.toPath)) await renameWithRetry(from, to)
      else await moveNoReplace(from, to)
    } catch (error) {
      if (error instanceof DestinationExistsError) throw new SkipError('destination-occupied', `${a.toPath} is taken on the copy.`)
      throw error
    }
    await this.cfg.journal.append({t: 'published', run: this.cfg.runId, n}, false)
    await this.fault('published', n, a)
    const copyStat = (await statOf(to)) ?? st
    const mainStat = (await statOf(this.mainAbs(a.toPath))) ?? ZERO
    return {
      ops: [
        {path: a.path, entry: null},
        {path: a.toPath, entry: this.entry(a, a.toPath, h.contentId, mainStat, copyStat, a.itemId, null)},
      ],
    }
  }

  // ---- Converge: only the baseline is written, after the copy's bytes are proven ----

  private async converge(n: number, a: PlanAction): Promise<Outcome> {
    if (!a.contentId) throw new Error('A converge needs a content id.')
    const dest = this.copyExisting(a.path)
    await this.intent(n, a, null, dest)
    const st = await statOf(dest)
    if (!st) throw new SkipError('copy-file-missing', `${a.path} is no longer on the copy.`)
    const h = await hashFile(dest)
    if (h.contentId !== a.contentId) throw new SkipError('unexpected-destination', `${a.path} on the copy changed since it was planned.`)
    const mainStat = (await statOf(this.mainAbs(a.path))) ?? ZERO
    return {ops: [{path: a.path, entry: this.entry(a, a.path, a.contentId, mainStat, st)}]}
  }

  // ---- RestoreFromCopy: main lost the file; the copy's verified bytes go back to the same path ----

  private async restoreFromCopy(n: number, a: PlanAction): Promise<Outcome> {
    if (!a.contentId) throw new Error('A restore needs a content id.')
    const dest = this.mainAbs(a.path)
    const tmp = `${dest}.kftmp-${this.cfg.runId}-${n}`
    await this.intent(n, a, tmp, dest)
    if (await exists(dest)) throw new SkipError('destination-not-absent', `${a.path} is on main again.`)
    const staged = await this.stageFromCopy(n, a, a.path, tmp, a.contentId)
    try {
      await moveNoReplace(tmp, dest)
    } catch (error) {
      await fs.rm(tmp, {force: true}).catch(() => undefined)
      if (error instanceof DestinationExistsError) throw new SkipError('destination-appeared', `${a.path} appeared on main.`)
      throw error
    }
    await this.cfg.journal.append({t: 'published', run: this.cfg.runId, n}, false)
    const mainStat = (await statOf(dest)) ?? staged.stat
    return {ops: [{path: a.path, entry: this.entry(a, a.path, a.contentId, mainStat, staged.source)}]}
  }

  // ---- a copy on an account or a device: the same actions over the transport (copies spec 9; cloud spec 7.3) ----
  //
  // The only bytes written on this computer are the staged file of the action in flight (verified against its pinned hash before it
  // is sent) and, for an import, the file that goes to the library. Every precondition is the one a folder copy has: the object must
  // still be the version the planner saw (its identity: id and revision, else its revision), a missing identity is settled by content,
  // a name that is taken is never replaced, and what a replacement displaces is kept before the new bytes are sent.

  private get remote(): RemoteMedia {
    return this.cfg.remote!
  }

  private stagingFile(n: number): string {
    return path.join(this.cfg.staging ?? path.join(this.cfg.copyRoot, '.keepfin', 'staging'), `${this.cfg.runId}-${n}.kftmp`)
  }

  /** Runs a transport call: an error that ends the run becomes the run's stop, the ones that mean "the world changed" become skips. */
  private async rx<T>(call: () => Promise<T>, map: Partial<Record<RemoteErrorKind, SkipError>> = {}): Promise<T> {
    try {
      return await call()
    } catch (error) {
      if (error instanceof SkipError || error instanceof CrashError || error instanceof RemoteStopError) throw error
      if (this.cfg.signal?.aborted) throw new SkipError('paused')
      const stop = this.remote.stop(error)
      if (stop) throw new RemoteStopError(stop.state, stop.message, error)
      const kind = this.remote.classify(error)
      const skip = kind ? map[kind] : undefined
      if (skip) throw skip
      throw error
    }
  }

  private async rstat(lib: string): Promise<(FileStat & {revision: string | null}) | null> {
    const line = await this.rx(() => this.remote.stat(lib))
    if (!line) return null
    return {size: line.size, mtimeMs: Math.floor(line.mtimeMs), fileId: this.remote.identity(line), revision: line.revision}
  }

  /** The copy's file now: from the folder or from the transport. */
  private async statCopy(lib: string): Promise<FileStat | null> {
    return this.cfg.remote ? this.rstat(lib) : statOf(this.copyExisting(lib))
  }

  private bytesDown(n: number): void {
    if (this.report.remote) {
      this.report.remote.downloaded++
      this.report.remote.bytesDown += n
    }
  }

  /** The content of an object whose version cannot be told apart by identity: read from the transport (or from this run's own hash of it). */
  private async remoteContent(lib: string, st: FileStat): Promise<string> {
    const known = this.cfg.knownCopyHash?.(lib, st.size, st.mtimeMs)
    if (known) return known
    const staging = this.cfg.staging ?? path.join(this.cfg.copyRoot, '.keepfin', 'staging')
    const got = await this.rx(() => hashRemote(this.remote, lib, staging, this.cfg.signal), {gone: new SkipError('copy-file-missing', `${lib} is no longer on the copy.`)})
    this.bytesDown(got.size)
    return got.contentId
  }

  /** The destination's own state checked against the plan's expectation, before anything is staged. */
  private async checkRemoteDestination(a: PlanAction): Promise<(FileStat & {revision: string | null}) | null> {
    const st = await this.rstat(a.path)
    const expect = a.expect
    if (!expect || expect.contentId === null) {
      if (st) throw new SkipError('destination-not-absent', `${a.path} is already on the copy.`)
      return null
    }
    if (!st) throw new SkipError('destination-missing', `${a.path} is no longer on the copy.`)
    if (expect.fileId && st.fileId) {
      if (expect.fileId !== st.fileId) throw new SkipError('unexpected-destination', `${a.path} on the copy is a different file than planned.`)
    } else if ((await this.remoteContent(a.path, st)) !== expect.contentId) {
      throw new SkipError('unexpected-destination', `${a.path} on the copy changed since it was planned.`)
    }
    return st
  }

  /** Brings a copy file to a local file (the library's staging, or main's restore target), verified against its pinned content id. */
  private async stageFromCopy(n: number, a: PlanAction, lib: string, tmp: string, expected: string): Promise<{stat: FileStat; source: FileStat}> {
    if (!this.cfg.remote) return this.stage(n, a, this.copyExisting(lib), tmp, expected)
    const source = await this.rstat(lib)
    if (!source) throw new SkipError('source-missing', `The source ${lib} is gone.`)
    if (this.cfg.maxFileBytes !== undefined && source.size > this.cfg.maxFileBytes) throw new SkipError('too-large', 'The file is larger than the copy can hold.')
    await this.fault('transfer', n, a)
    await fs.mkdir(path.dirname(tmp), {recursive: true})
    await fs.rm(tmp, {force: true})
    try {
      await this.rx(() => this.remote.download(lib, tmp, expected, this.cfg.signal), {
        gone: new SkipError('source-missing', `The source ${lib} is gone.`),
        mismatch: new SkipError('hash-mismatch', `The source no longer matches its pinned content.`),
      })
    } catch (error) {
      await fs.rm(tmp, {force: true}).catch(() => undefined)
      throw error
    }
    const st = (await statOf(tmp))!
    this.onBytes(st.size)
    this.bytesDown(st.size)
    await this.cfg.journal.append({t: 'staged', run: this.cfg.runId, n, tmp, contentId: expected}, false)
    await this.fault('staged', n, a)
    return {stat: st, source}
  }

  /** The copy's own record of what it displaced (versions or trash), written before the object moves, and the move itself. */
  private async preserveRemote(n: number, a: PlanAction, st: FileStat & {revision: string | null}, store: 'Versions' | 'Trash', contentId: string, why: string): Promise<{store: Store; archive: string; id: string}> {
    const kind: Store = store === 'Versions' ? 'versions' : 'trash'
    let attempt = 1
    for (;;) {
      const {entry, absolute} = await planArchive(this.cfg.copyRoot, kind, {
        itemId: a.itemId,
        revision: null,
        contentId,
        path: a.path,
        physical: attempt === 1 ? a.path : withCounter(a.path, attempt),
        run: this.cfg.runId,
        n,
        at: this.now(),
        why,
      })
      const file = attempt === 1 ? entry.file : withCounter(entry.file, attempt)
      const target = `.keepfin/${file}`
      await appendCatalogue(this.cfg.copyRoot, {...entry, file})
      await this.cfg.journal.append({t: 'preserved', run: this.cfg.runId, n, from: a.path, archive: absolute, catalogueId: entry.id, store: kind}, true)
      await this.fault('preserve-before-move', n, a)
      try {
        await this.rx(() => this.remote.moveTo(a.path, target, {revision: st.revision, contentId: a.expect?.contentId ?? null}), {
          changed: new SkipError('unexpected-destination', `${a.path} on the copy changed since it was planned.`),
          gone: new SkipError('copy-file-missing', `${a.path} is no longer on the copy.`),
        })
      } catch (error) {
        await voidCatalogue(this.cfg.copyRoot, kind, entry.id).catch(() => undefined)
        // the archive name is taken on the copy (the folder here holds no archived bytes to see it): try the next counter
        if (!(error instanceof SkipError) && this.remote.classify(error) === 'exists' && attempt < 20) {
          attempt++
          continue
        }
        throw error
      }
      await this.fault('preserved', n, a)
      return {store: kind, archive: target, id: entry.id}
    }
  }

  private async restoreArchivedRemote(archived: {store: Store; archive: string; id: string}, lib: string): Promise<void> {
    try {
      await this.remote.moveTo(archived.archive, lib, {revision: null, contentId: null})
      await voidCatalogue(this.cfg.copyRoot, archived.store, archived.id)
    } catch {
      // the bytes stay in the archive, which is safe; recovery or the person can restore them
    }
  }

  private async copyToRemote(n: number, a: PlanAction): Promise<Outcome> {
    if (!a.contentId) throw new Error('A copy needs a pinned content id.')
    const remote = this.remote
    const tmp = this.stagingFile(n)
    await this.intent(n, a, tmp, null)
    const src = this.mainAbs(a.path)
    const existing = await this.checkRemoteDestination(a)
    let archived: {store: Store; archive: string; id: string} | null = null
    let published = false
    let crashed = false
    try {
      const staged = await this.stage(n, a, src, tmp, a.contentId)
      if (existing) {
        if (remote.archive === 'move') archived = await this.preserveRemote(n, a, existing, a.archive ?? 'Trash', a.expect!.contentId!, 'replaced')
        await this.fault('before-publish', n, a)
        if (remote.archive === 'move') {
          await this.rx(() => remote.uploadNew(tmp, a.path, a.contentId!, this.cfg.signal), {exists: new SkipError('destination-appeared', `${a.path} appeared on the copy.`)})
          if (this.report.remote) this.report.remote.replaced++
        } else {
          await this.rx(() => remote.replace(tmp, a.path, a.contentId!, {revision: existing.revision, contentId: a.expect!.contentId}, this.cfg.signal), {
            changed: new SkipError('unexpected-destination', `${a.path} on the copy changed since it was planned.`),
            gone: new SkipError('destination-missing', `${a.path} is no longer on the copy.`),
          })
          if (this.report.remote) this.report.remote.replaced++
        }
      } else {
        await this.fault('before-publish', n, a)
        await this.rx(() => remote.uploadNew(tmp, a.path, a.contentId!, this.cfg.signal), {exists: new SkipError('destination-appeared', `${a.path} appeared on the copy.`)})
        if (this.report.remote) this.report.remote.uploaded++
      }
      published = true
      if (this.report.remote) this.report.remote.bytesUp += staged.stat.size
      await this.cfg.journal.append({t: 'published', run: this.cfg.runId, n}, false)
      await this.fault('published', n, a)
      const copyStat = (await this.rstat(a.path)) ?? {size: staged.stat.size, mtimeMs: 0, fileId: null}
      return {ops: [{path: a.path, entry: this.entry(a, a.path, a.contentId, staged.source, copyStat)}]}
    } catch (error) {
      if (error instanceof CrashError) {
        crashed = true
        throw error
      }
      if (archived && !published) await this.restoreArchivedRemote(archived, a.path)
      throw error
    } finally {
      if (!crashed) await fs.rm(tmp, {force: true}).catch(() => undefined)
    }
  }

  private async removeFromCopyRemote(n: number, a: PlanAction): Promise<Outcome> {
    const remote = this.remote
    await this.intent(n, a, null, null)
    const st = await this.rstat(a.path)
    if (!st) return {ops: [{path: a.path, entry: null}]} // already gone: only the baseline entry remains
    let contentId = a.expect?.contentId ?? null
    if (a.expect?.fileId && st.fileId) {
      if (a.expect.fileId !== st.fileId) throw new SkipError('unexpected-destination', `${a.path} on the copy is a different file than planned.`)
    } else if (contentId !== null || remote.archive === 'move') {
      contentId = await this.remoteContent(a.path, st)
      if (a.expect?.contentId && contentId !== a.expect.contentId) throw new SkipError('unexpected-destination', `${a.path} on the copy changed since it was planned.`)
    }
    const why = a.reason === 'ItemDeleted' ? 'deleted' : 'removed'
    if (remote.archive === 'move') {
      await this.preserveRemote(n, a, st, a.archive ?? 'Trash', contentId ?? a.expect?.contentId ?? '', why)
      if (this.report.remote) this.report.remote.moved++
    } else {
      try {
        await this.rx(() => remote.moveToTrash(a.path, {revision: st.revision, contentId: a.expect?.contentId ?? null}), {
          changed: new SkipError('unexpected-destination', `${a.path} on the copy changed since it was planned.`),
        })
        if (this.report.remote) this.report.remote.trashed++
      } catch (error) {
        if (!(error instanceof SkipError) && remote.classify(error) === 'gone') return {ops: [{path: a.path, entry: null}]}
        throw error
      }
    }
    await this.cfg.journal.append({t: 'published', run: this.cfg.runId, n}, false)
    return {ops: [{path: a.path, entry: null}]}
  }

  private async moveOnCopyRemote(n: number, a: PlanAction): Promise<Outcome> {
    if (!a.toPath) throw new Error('A move needs a destination.')
    const remote = this.remote
    await this.intent(n, a, null, null)
    const st = await this.rstat(a.path)
    if (!st) throw new SkipError('copy-file-missing', `${a.path} is no longer on the copy.`)
    let contentId = a.expect?.contentId ?? a.contentId ?? null
    if (a.expect?.fileId && st.fileId) {
      if (a.expect.fileId !== st.fileId) throw new SkipError('unexpected-destination', `${a.path} on the copy is a different file than planned.`)
    } else {
      contentId = await this.remoteContent(a.path, st)
      if (a.expect?.contentId && contentId !== a.expect.contentId) throw new SkipError('unexpected-destination', `${a.path} on the copy changed since it was planned.`)
    }
    if (contentId === null) contentId = await this.remoteContent(a.path, st)
    await this.rx(() => remote.moveTo(a.path, a.toPath!, {revision: st.revision, contentId: a.expect?.contentId ?? contentId}), {
      exists: new SkipError('destination-occupied', `${a.toPath} is taken on the copy.`),
      gone: new SkipError('copy-file-missing', `${a.path} is no longer on the copy.`),
      changed: new SkipError('unexpected-destination', `${a.path} on the copy changed since it was planned.`),
    })
    if (this.report.remote) this.report.remote.moved++
    await this.cfg.journal.append({t: 'published', run: this.cfg.runId, n}, false)
    await this.fault('published', n, a)
    const copyStat = (await this.rstat(a.toPath)) ?? st
    const mainStat = (await statOf(this.mainAbs(a.toPath))) ?? ZERO
    return {
      ops: [
        {path: a.path, entry: null},
        {path: a.toPath, entry: this.entry(a, a.toPath, contentId, mainStat, copyStat, a.itemId, null)},
      ],
    }
  }

  private async convergeRemote(n: number, a: PlanAction): Promise<Outcome> {
    if (!a.contentId) throw new Error('A converge needs a content id.')
    await this.intent(n, a, null, null)
    const st = await this.rstat(a.path)
    if (!st) throw new SkipError('copy-file-missing', `${a.path} is no longer on the copy.`)
    if ((await this.remoteContent(a.path, st)) !== a.contentId) throw new SkipError('unexpected-destination', `${a.path} on the copy changed since it was planned.`)
    const mainStat = (await statOf(this.mainAbs(a.path))) ?? ZERO
    return {ops: [{path: a.path, entry: this.entry(a, a.path, a.contentId, mainStat, st)}]}
  }

  // ---- actions that become library operations: the executor stages verified bytes, the host places them ----

  private async viaHost(n: number, a: PlanAction): Promise<Outcome> {
    if (!a.contentId) throw new Error('This action needs a content id.')
    const host = this.cfg.host
    const method = a.kind === 'ImportNew' ? host?.importNew : a.kind === 'ReviseFromCopy' ? host?.reviseFromCopy : host?.restoreEditedDeleted
    const stagedPath = path.join(this.cfg.mainRoot, '.keepfin', 'staging', `${this.cfg.runId}-${n}.kftmp`)
    await this.intent(n, a, stagedPath, null)
    if (!method) throw new SkipError('needs-library', 'No library host to record this change.')
    const staged = await this.stageFromCopy(n, a, a.path, stagedPath, a.contentId)
    try {
      let result: {itemId?: string; revisionOp?: string | null; path?: string} = {}
      let finalPath = a.path
      if (a.kind === 'ImportNew') {
        const wanted = a.toPath ?? a.path
        const destPath = await uniqueName(wanted, (p) => exists(this.mainAbs(p)))
        finalPath = destPath
        result = await host!.importNew!({action: a, stagedPath, destPath})
        await this.cfg.journal.append({t: 'published', run: this.cfg.runId, n}, false)
        // a new item under the copy's own name converges at once; any other name is copied later
        if (a.toPath || destPath !== a.path || !result.itemId) return {ops: []}
      } else if (a.kind === 'ReviseFromCopy') {
        result = await host!.reviseFromCopy!({action: a, stagedPath})
      } else {
        result = await host!.restoreEditedDeleted!({action: a, stagedPath})
      }
      await this.cfg.journal.append({t: 'published', run: this.cfg.runId, n}, false)
      const mainStat = (await statOf(this.mainAbs(finalPath))) ?? ZERO
      const copyStat = (await this.statCopy(a.path)) ?? staged.stat
      const itemId = result.itemId ?? a.itemId
      if (!itemId) return {ops: []}
      return {ops: [{path: a.path, entry: this.entry(a, a.path, a.contentId, mainStat, copyStat, itemId, result.revisionOp ?? null)}]}
    } finally {
      await fs.rm(stagedPath, {force: true}).catch(() => undefined)
    }
  }
}

function stat(s: FileStat): {size: number; mtimeMs: number; fileId?: string | null} {
  return s.fileId ? {size: s.size, mtimeMs: s.mtimeMs, fileId: s.fileId} : {size: s.size, mtimeMs: s.mtimeMs}
}

/**
 * Splits the plan into groups that must run in order: a conflict (import, then replace), a move followed by a copy. Actions
 * of one group touch the same name; different groups can run in parallel. [start] is the index of the group's first action.
 */
export function* groups(actions: Iterable<PlanAction>, rules: NameRulesId): Generator<{start: number; actions: PlanAction[]}> {
  let start = 0
  let n = 0
  let group: PlanAction[] = []
  let keys = new Set<string>()
  const keysOf = (a: PlanAction): string[] => [fastKey(rules, a.path), ...(a.toPath ? [fastKey(rules, a.toPath)] : [])]
  for (const a of actions) {
    const ks = keysOf(a)
    if (group.length > 0 && !ks.some((k) => keys.has(k))) {
      yield {start, actions: group}
      group = []
      keys = new Set()
      start = n
    }
    group.push(a)
    // only the name an action works on joins the group: the destination of a move and the name a copy lands on
    for (const k of ks) keys.add(k)
    n++
  }
  if (group.length > 0) yield {start, actions: group}
}
