// Sections 5.3 to 5.5: the runner. Copies or renames each file with a journal line before and after every step,
// calls the injected commit at the switch point, removes originals only afterwards, and resumes or abandons from the journal.
import fs from 'node:fs/promises'
import path from 'node:path'
import {renameWithRetry} from '../../fsutil.js'
import {Journal, InjectedCrash, type FileFold} from './journal.js'
import {copyHashing, exists, hashFile, lp, pathKey, sameVolume as defaultSameVolume, statOrUndefined, type CopyOptions} from './io.js'
import {readPlan, writePlan} from './plan.js'
import type {FailedFile, FileState, Plan, PlanFile, Progress, RunResult} from './types.js'

export type CommitContext = {
  runDir: string
  /** The journal's view of every file at the switch point. */
  states: ReadonlyMap<string, {state: FileState; mode?: 'copy' | 'rename'; reason?: string}>
  /** Files that did not arrive (only present when the caller accepted failures). */
  failed: FailedFile[]
}

export type RunDeps = {
  /**
   * Turns the library into the new store at the switch point. Must be safe to call again: a crash right after it returns
   * means it runs once more on resume.
   */
  commit(plan: Plan, context: CommitContext): Promise<void>
  /** Sends a file to the Recycle Bin. */
  trash(file: string): Promise<void>
  /** Fences writers (queue, follow checks, tag edits, watcher). Always followed by resume(). */
  pause?(): void | Promise<void>
  resume?(): void | Promise<void>
  onProgress?(progress: Progress): void
  /** Replaces the volume check (tests). */
  sameVolume?(source: string, destination: string): Promise<boolean>
  /** Replaces the streaming copy (tests simulate a full disk). Must write dest and return the source hash. */
  copyFile?(source: string, dest: string, options: CopyOptions): Promise<{hash: string; bytes: number}>
  now?: () => number
  /** Fault injection: throw after (or just before) the n-th journal write. */
  crash?: {after?: number; before?: number}
}

export type RunOptions = {
  /** Commit even though some files failed (they stay where they were and are reported). */
  acceptFailures?: boolean
  signal?: AbortSignal
  /** Put failed files back so this run tries them again. */
  retryFailed?: boolean
}

export type AbandonResult = {
  status: 'abandoned'
  /** Files that had been moved by rename and were put back. */
  restored: string[]
  /** Copies in the new folder, for the caller to offer for removal. Originals were never touched. */
  copies: {path: string; size: number}[]
  problems: {path: string; reason: string}[]
}

class NoSpace extends Error {}
class SourceGone extends Error {}

const sourceChanged = 'The original changed after it was copied, so it was left where it is.'

function describe(error: unknown): {reason: string; code?: string} {
  const code = (error as NodeJS.ErrnoException)?.code
  switch (code) {
    case 'ENOENT':
    case 'ENOTDIR':
      return {reason: 'The file is no longer there.', code}
    case 'EBUSY':
    case 'EPERM':
    case 'EACCES':
    case 'EAGAIN':
      return {reason: 'The file is in use, or Keepfin does not have access to it.', code}
    case 'EROFS':
      return {reason: 'The destination cannot be written to.', code}
    case 'ENAMETOOLONG':
      return {reason: 'The file name is too long for the destination.', code}
    default:
      return {reason: 'The file could not be brought over.', code}
  }
}

const sameInstant = (a: number, b: number) => Math.abs(a - b) < 1

export async function makeBackup(deviceDir: string, runDir: string): Promise<void> {
  const backup = path.join(runDir, 'backup')
  await fs.mkdir(backup, {recursive: true})
  for (const name of ['library.json', 'queue.json']) {
    const target = path.join(backup, name)
    if (await exists(target)) continue
    await fs.copyFile(lp(path.join(deviceDir, name)), lp(target)).catch((error) => {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
    })
  }
  // The art index (names and sizes), not the images: the originals stay in place.
  const index = path.join(backup, 'library-art-index.json')
  if (!(await exists(index))) {
    const entries = await fs.readdir(lp(path.join(deviceDir, 'library-art')), {withFileTypes: true}).catch(() => [])
    const listing = []
    for (const entry of entries) {
      if (!entry.isFile()) continue
      const stat = await fs.stat(lp(path.join(deviceDir, 'library-art', entry.name))).catch(() => undefined)
      if (stat) listing.push({name: entry.name, size: stat.size, mtimeMs: stat.mtimeMs})
    }
    await fs.writeFile(index, JSON.stringify(listing, null, 2))
  }
}

/** Starts a run: the plan is written and flushed first, then the backup, then the files. Calling it again for a started run resumes. */
export async function startRun(runDir: string, plan: Plan, deps: RunDeps, options: RunOptions = {}): Promise<RunResult> {
  await fs.mkdir(runDir, {recursive: true})
  await writePlan(runDir, plan)
  await makeBackup(plan.inventory.deviceDir, runDir)
  return execute(runDir, plan, deps, options)
}

/** Continues an unfinished run from its journal. */
export async function resumeRun(runDir: string, deps: RunDeps, options: RunOptions = {}): Promise<RunResult> {
  const plan = await readPlan(runDir)
  if (!plan) throw new Error('This setup has no plan to continue.')
  return execute(runDir, plan, deps, options)
}

async function execute(runDir: string, plan: Plan, deps: RunDeps, options: RunOptions): Promise<RunResult> {
  await deps.pause?.()
  try {
    const journal = await Journal.open(runDir, deps.crash, deps.now)
    if (journal.fold.run.abandoned || journal.fold.run.abandoning) {
      await abandonWith(runDir, plan, journal, deps)
      return {status: 'abandoned', failed: [], committed: false}
    }
    return await new Runner(runDir, plan, deps, options, journal).go()
  } finally {
    await deps.resume?.()
  }
}

class Runner {
  private readonly fold: Map<string, FileFold>
  private bytesDone = 0
  private readonly bytesTotal: number
  private lastEmit = 0
  private doneCount = 0

  constructor(
    private readonly runDir: string,
    private readonly plan: Plan,
    private readonly deps: RunDeps,
    private readonly options: RunOptions,
    private readonly journal: Journal,
  ) {
    this.fold = journal.fold.files
    this.bytesTotal = plan.files.reduce((sum, file) => sum + file.size, 0)
  }

  private state(file: PlanFile): FileFold {
    return this.fold.get(file.id) ?? {state: 'planned'}
  }

  private emit(phase: Progress['phase'], current?: string, force = false) {
    if (!this.deps.onProgress) return
    const now = (this.deps.now ?? Date.now)()
    if (!force && now - this.lastEmit < 250) return
    this.lastEmit = now
    this.deps.onProgress({phase, done: this.doneCount, total: this.plan.files.length, bytesDone: this.bytesDone, bytesTotal: this.bytesTotal, current})
  }

  private failedList(): FailedFile[] {
    const result: FailedFile[] = []
    for (const file of this.plan.files) {
      const state = this.state(file)
      if (state.state === 'failed')
        result.push({id: file.id, source: file.source, kind: file.kind, reason: state.reason ?? 'The file could not be brought over.', code: state.code, state: state.failedFrom ?? 'planned'})
    }
    return result
  }

  async go(): Promise<RunResult> {
    const {journal, plan, options} = this
    try {
      if (!journal.fold.run.started) await journal.append({k: 'run', event: 'started'})
      if (options.retryFailed) {
        for (const file of plan.files) if (this.state(file).state === 'failed') await journal.append({k: 'retry', id: file.id})
      }
      if (!journal.fold.run.committed) {
        this.doneCount = plan.files.filter((file) => ['verified', 'failed'].includes(this.state(file).state)).length
        for (const file of plan.files) {
          if (options.signal?.aborted) return {status: 'paused', failed: this.failedList(), committed: false}
          await this.place(file)
          this.emit('copying', file.source)
        }
        const failed = this.failedList()
        if (failed.length && !options.acceptFailures) return {status: 'needs-decision', failed, committed: false}
        this.emit('committing', undefined, true)
        const states = new Map<string, {state: FileState; mode?: 'copy' | 'rename'; reason?: string}>()
        for (const file of plan.files) {
          const s = this.state(file)
          states.set(file.id, {state: s.state, mode: s.mode, reason: s.reason})
        }
        await this.deps.commit(plan, {runDir: this.runDir, states, failed})
        // The switch point: from here the new library is authoritative.
        await journal.append({k: 'run', event: 'committed'})
      }
      await this.markCommitted()
      await this.cleanup()
      if (!journal.fold.run.done) await journal.append({k: 'run', event: 'done'})
      this.emit('done', undefined, true)
      return {status: 'completed', failed: this.failedList(), committed: true}
    } catch (error) {
      if (error instanceof NoSpace) {
        await journal.append({k: 'run', event: 'stopped', reason: 'no-space'})
        return {status: 'no-space', failed: this.failedList(), committed: journal.fold.run.committed}
      }
      if (isAbort(error)) return {status: 'paused', failed: this.failedList(), committed: journal.fold.run.committed}
      throw error
    }
  }

  /** Every verified file becomes committed in one durable write. */
  private async markCommitted() {
    const records = this.plan.files.filter((file) => this.state(file).state === 'verified').map((file) => ({k: 'file' as const, id: file.id, state: 'committed' as const}))
    if (records.length) await this.journal.append(records)
  }

  // ---- before commit -------------------------------------------------------------------------------------------

  private async fail(file: PlanFile, reason: string, code?: string) {
    if (this.state(file).state === 'failed') return
    await this.journal.append({k: 'file', id: file.id, state: 'failed', reason, code})
  }

  /** Brings one file to verified (or failed). Safe to call again from any journal state. */
  private async place(file: PlanFile) {
    for (let guard = 0; guard < 4; guard++) {
      const current = this.state(file).state
      try {
        if (current === 'planned') await this.copyOrRename(file)
        else if (current === 'copied') await this.verify(file)
        else break
      } catch (error) {
        if (error instanceof InjectedCrash || error instanceof NoSpace || isAbort(error)) throw error
        if ((error as NodeJS.ErrnoException).code === 'ENOSPC') throw new NoSpace()
        if (error instanceof SourceGone) {
          await this.fail(file, 'The file is no longer there.', 'ENOENT')
        } else {
          const {reason, code} = describe(error)
          await this.fail(file, reason, code)
        }
        break
      }
    }
    if (['verified', 'failed'].includes(this.state(file).state)) {
      this.doneCount++
    }
  }

  private async volumeIsShared(file: PlanFile): Promise<boolean> {
    return (this.deps.sameVolume ?? defaultSameVolume)(file.source, file.dest)
  }

  private async copyOrRename(file: PlanFile) {
    const fold = this.state(file)
    let op: 'rename' | 'copy' = 'copy'
    if (file.action === 'move') {
      if (fold.intent) op = fold.intent.op
      else {
        if (!(await statOrUndefined(file.source))) throw new SourceGone()
        op = (await this.volumeIsShared(file)) ? 'rename' : 'copy'
      }
    }
    if (op === 'rename') await this.rename(file)
    else await this.copy(file)
  }

  private async rename(file: PlanFile) {
    const fold = this.state(file)
    const inPlace = pathKey(file.source) === pathKey(file.dest)
    const source = await statOrUndefined(file.source)
    const dest = inPlace ? source : await statOrUndefined(file.dest)
    let size = fold.intent?.size ?? file.size
    let mtimeMs = fold.intent?.mtimeMs ?? file.mtimeMs
    let reinventoried = false
    if (fold.intent && !inPlace) {
      // Recovery: the file is at one of the two recorded paths.
      if (dest && !source) {
        await this.journal.append({k: 'file', id: file.id, state: 'copied', mode: 'rename', size, mtimeMs})
        this.bytesDone += size
        return
      }
      if (source && dest) return this.fail(file, 'A file with the same name already appeared at the new place.')
      if (!source && !dest) throw new SourceGone()
    } else {
      if (!source) throw new SourceGone()
      if (dest && !inPlace) return this.fail(file, 'A file with the same name already appeared at the new place.')
      if (source.size !== file.size || !sameInstant(source.mtimeMs, file.mtimeMs)) reinventoried = true
      size = source.size
      mtimeMs = source.mtimeMs
      if (!fold.intent) await this.journal.append({k: 'intent', id: file.id, op: 'rename', from: file.source, to: file.dest, size, mtimeMs})
    }
    if (!inPlace) {
      await fs.mkdir(lp(path.dirname(file.dest)), {recursive: true})
      try {
        await renameWithRetry(lp(file.source), lp(file.dest))
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'EXDEV') throw error
        // The volume check was wrong: copy instead, and record that.
        await this.journal.append({k: 'intent', id: file.id, op: 'copy', from: file.source, to: file.dest, size, mtimeMs})
        return this.copy(file)
      }
    }
    await this.journal.append({k: 'file', id: file.id, state: 'copied', mode: 'rename', size, mtimeMs, reinventoried: reinventoried || undefined})
    this.bytesDone += size
  }

  private async copy(file: PlanFile) {
    const temp = `${file.dest}.kftmp`
    await fs.rm(lp(temp), {force: true})
    const source = await statOrUndefined(file.source)
    if (!source) throw new SourceGone()
    const existing = await statOrUndefined(file.dest)
    if (existing) {
      // A previous attempt may have renamed the copy into place just before a crash: adopt it only if it is identical.
      const [srcHash, destHash] = await Promise.all([hashFile(file.source, this.options.signal), hashFile(file.dest, this.options.signal)])
      if (srcHash !== destHash || existing.size !== source.size) return this.fail(file, 'A different file already appeared at the new place.')
      await this.journal.append({k: 'file', id: file.id, state: 'copied', mode: 'copy', srcHash, destHash, size: source.size, mtimeMs: source.mtimeMs})
      this.bytesDone += source.size
      return
    }
    const reinventoried = source.size !== file.size || !sameInstant(source.mtimeMs, file.mtimeMs)
    await fs.mkdir(lp(path.dirname(file.dest)), {recursive: true})
    let counted = 0
    try {
      const copier = this.deps.copyFile ?? copyHashing
      const result = await copier(file.source, temp, {
        signal: this.options.signal,
        onBytes: (bytes) => {
          counted += bytes
          this.bytesDone += bytes
          this.emit('copying', file.source)
        },
      })
      const after = await statOrUndefined(file.source)
      if (!after || after.size !== source.size || !sameInstant(after.mtimeMs, source.mtimeMs)) {
        await fs.rm(lp(temp), {force: true})
        this.bytesDone -= counted
        return this.fail(file, 'The file changed while it was being copied.')
      }
      const destHash = await hashFile(temp, this.options.signal)
      if (destHash !== result.hash) {
        await fs.rm(lp(temp), {force: true})
        this.bytesDone -= counted
        return this.fail(file, 'The copy did not match the original, so it was discarded.')
      }
      await fs.utimes(lp(temp), source.atime, source.mtime)
      if (await exists(file.dest)) {
        await fs.rm(lp(temp), {force: true})
        return this.fail(file, 'A different file already appeared at the new place.')
      }
      await renameWithRetry(lp(temp), lp(file.dest))
      await this.journal.append({
        k: 'file', id: file.id, state: 'copied', mode: 'copy', srcHash: result.hash, destHash, size: source.size, mtimeMs: source.mtimeMs,
        reinventoried: reinventoried || undefined,
      })
    } catch (error) {
      if (!(error instanceof InjectedCrash)) {
        this.bytesDone -= counted
        await fs.rm(lp(temp), {force: true}).catch(() => undefined)
      }
      throw error
    }
  }

  private async verify(file: PlanFile) {
    const fold = this.state(file)
    const dest = await statOrUndefined(file.dest)
    if (!dest) return this.fail(file, 'The file is missing from the new place.', 'ENOENT')
    if (fold.mode === 'copy') {
      if (!fold.srcHash || fold.srcHash !== fold.destHash || dest.size !== fold.size) return this.fail(file, 'The copy did not match the original.')
    } else if (dest.size !== fold.size || !sameInstant(dest.mtimeMs, fold.mtimeMs ?? 0)) {
      return this.fail(file, 'The moved file does not match what was listed.')
    }
    await this.journal.append({k: 'file', id: file.id, state: 'verified'})
  }

  // ---- after commit --------------------------------------------------------------------------------------------

  /** Originals of files that crossed volumes are removed only now, and only if they are still exactly what was copied. */
  private async cleanup() {
    this.doneCount = 0
    const settle: {k: 'file'; id: string; state: 'done'}[] = []
    for (const file of this.plan.files) {
      const fold = this.state(file)
      if (fold.state === 'committed' && !(file.action === 'move' && fold.mode === 'copy')) settle.push({k: 'file', id: file.id, state: 'done'})
    }
    if (settle.length) await this.journal.append(settle)
    for (const file of this.plan.files) {
      const fold = this.state(file)
      if (file.action !== 'move' || fold.mode !== 'copy' || (fold.state !== 'committed' && fold.state !== 'removable')) {
        if (fold.state === 'done') this.doneCount++
        continue
      }
      if (this.options.signal?.aborted) throw new DOMException('Aborted', 'AbortError')
      try {
        await this.removeOriginal(file, fold)
      } catch (error) {
        if (error instanceof InjectedCrash || isAbort(error)) throw error
        const {reason, code} = describe(error)
        await this.fail(file, reason, code)
      }
      this.doneCount++
      this.emit('cleaning', file.source)
    }
  }

  private async removeOriginal(file: PlanFile, fold: FileFold) {
    const source = await statOrUndefined(file.source)
    if (!source) return this.journal.append({k: 'file', id: file.id, state: 'done'}).then(() => undefined)
    if (source.size !== fold.size || !sameInstant(source.mtimeMs, fold.mtimeMs ?? 0)) {
      await this.fail(file, sourceChanged, 'source-changed')
      return
    }
    if (fold.state === 'committed') await this.journal.append({k: 'file', id: file.id, state: 'removable'})
    try {
      await this.deps.trash(file.source)
    } catch (error) {
      if (error instanceof InjectedCrash) throw error
      // The Recycle Bin refused (some drives): delete only if the original is still byte for byte what was copied.
      if (await exists(file.source)) {
        if ((await hashFile(file.source, this.options.signal)) !== fold.srcHash) {
          await this.fail(file, sourceChanged, 'source-changed')
          return
        }
        await fs.rm(lp(file.source), {force: true})
      }
    }
    await this.journal.append({k: 'file', id: file.id, state: 'done'})
  }
}

const isAbort = (error: unknown) => (error as {name?: string})?.name === 'AbortError'

/** Abandon is only allowed before the switch point. The old library stays authoritative; originals are never deleted. */
export async function abandonRun(runDir: string, deps: RunDeps): Promise<AbandonResult> {
  const plan = await readPlan(runDir)
  if (!plan) throw new Error('This setup has no plan to abandon.')
  await deps.pause?.()
  try {
    const journal = await Journal.open(runDir, deps.crash, deps.now)
    if (journal.fold.run.committed) throw new Error('This setup has already switched to the new library and cannot be abandoned.')
    return await abandonWith(runDir, plan, journal, deps)
  } finally {
    await deps.resume?.()
  }
}

async function abandonWith(_runDir: string, plan: Plan, journal: Journal, _deps: RunDeps): Promise<AbandonResult> {
  if (journal.fold.run.committed) throw new Error('This setup has already switched to the new library and cannot be abandoned.')
  if (!journal.fold.run.abandoning && !journal.fold.run.abandoned) await journal.append({k: 'run', event: 'abandoning'})
  const restored: string[] = []
  const copies: AbandonResult['copies'] = []
  const problems: AbandonResult['problems'] = []
  for (const file of plan.files) {
    const fold = journal.fold.files.get(file.id)
    await fs.rm(lp(`${file.dest}.kftmp`), {force: true}).catch(() => undefined)
    if (pathKey(file.source) === pathKey(file.dest)) continue
    const renamed = fold?.intent?.op === 'rename' && fold.mode !== 'copy'
    if (renamed) {
      // A move by rename: the file itself is the original, so it goes back where the old library expects it.
      const [source, dest] = [await statOrUndefined(file.source), await statOrUndefined(file.dest)]
      if (dest && !source) {
        try {
          await fs.mkdir(lp(path.dirname(file.source)), {recursive: true})
          await renameWithRetry(lp(file.dest), lp(file.source))
          restored.push(file.source)
        } catch (error) {
          problems.push({path: file.source, reason: describe(error).reason})
        }
      } else if (dest && source) {
        problems.push({path: file.dest, reason: 'A file with the same name exists at both places, so nothing was moved.'})
      }
      continue
    }
    const dest = await statOrUndefined(file.dest)
    if (!dest?.isFile()) continue
    // A copy made just before a crash may not be journaled yet: count a same-sized file whose original is still there.
    if (fold?.mode === 'copy' || fold?.intent?.op === 'copy' || (dest.size === file.size && (await statOrUndefined(file.source))))
      copies.push({path: file.dest, size: dest.size})
  }
  if (!journal.fold.run.abandoned) await journal.append({k: 'run', event: 'abandoned'})
  return {status: 'abandoned', restored, copies, problems}
}
