// Crash recovery (spec 5.1): reconciles the actual files against the journal. It only ever moves things back to a state the
// planner understands, or forward when the bytes are proven; it never overwrites. Baseline entries are never invented here:
// after recovery the next plan sees the real files and converges (an interrupted copy that did publish is a "converge").
import fs from 'node:fs/promises'
import path from 'node:path'
import type {PlanAction} from '../../shared/core/copies.js'
import {hashFile} from './hash.js'
import {iterateJournal, type JournalRecord} from './journal.js'
import {moveNoReplace, DestinationExistsError} from './fsops.js'
import {exists} from './util.js'
import {voidCatalogue, type Store} from './versions.js'
import type {RemoteMedia} from './remote.js'
import {RemoteStopError} from './util.js'

export interface RecoveryReport {
  /** Actions that had an intent but no outcome. */
  unresolved: number
  rolledForward: number
  rolledBack: number
  /** Staging files removed. */
  cleaned: number
  /** Things recovery could not decide alone: nothing was touched. */
  findings: string[]
  /** The runs the journal recorded, with the generation each one bumped the copy to. */
  runs: {run: string; generation: number}[]
}

interface Open {
  action: PlanAction
  tmp: string | null
  dest: string | null
  preserved?: {from: string; archive: string; catalogueId: string; store: Store}
  done: boolean
}

/** Finds the actions without an outcome, streaming the journal (only the open ones are kept). */
function openActions(file: string): Map<string, Open> {
  const open = new Map<string, Open>()
  const key = (r: {run: string; n: number}) => `${r.run}#${r.n}`
  for (const r of iterateJournal(file) as Iterable<JournalRecord>) {
    switch (r.t) {
      case 'intent':
        open.set(key(r), {action: r.action, tmp: r.tmp, dest: r.dest, done: false})
        break
      case 'preserved': {
        const o = open.get(key(r))
        if (o) o.preserved = {from: r.from, archive: r.archive, catalogueId: r.catalogueId, store: r.store}
        break
      }
      case 'committed':
      case 'skipped':
      case 'failed':
        open.delete(key(r))
        break
      default:
        break
    }
  }
  return open
}

export async function recoverRun(options: {journalFile: string; copyRoot: string}): Promise<RecoveryReport> {
  const report: RecoveryReport = {unresolved: 0, rolledForward: 0, rolledBack: 0, cleaned: 0, findings: [], runs: []}
  const open = openActions(options.journalFile)
  for (const r of iterateJournal(options.journalFile)) if (r.t === 'run') report.runs.push({run: r.run, generation: r.generation})
  report.unresolved = open.size
  for (const o of open.values()) {
    const a = o.action
    const tmpExists = o.tmp ? await exists(o.tmp) : false
    const destExists = o.dest ? await exists(o.dest) : false
    if (o.preserved) {
      const p = o.preserved
      const archiveExists = await exists(p.archive)
      if (archiveExists && !destExists) {
        // the displaced bytes are aside and nothing sits at the destination
        const staged = tmpExists && o.tmp && o.dest && a.kind === 'CopyTo' && a.contentId ? (await hashFile(o.tmp)).contentId === a.contentId : false
        if (staged) {
          try {
            await moveNoReplace(o.tmp!, o.dest!)
            report.rolledForward++
          } catch (error) {
            if (!(error instanceof DestinationExistsError)) throw error
          }
        } else if (a.kind === 'RemoveFromCopy') {
          report.rolledForward++ // the removal completed: the bytes are in the trash
        } else {
          try {
            await moveNoReplace(p.archive, o.dest!)
            await voidCatalogue(options.copyRoot, p.store, p.catalogueId)
            report.rolledBack++
          } catch (error) {
            if (!(error instanceof DestinationExistsError)) throw error
            report.findings.push(`${a.path}: the displaced bytes are in ${p.archive}`)
          }
        }
      } else if (archiveExists && destExists) {
        // published already (the old bytes stay in the archive), or something new appeared: leave both
        if (a.kind === 'CopyTo' && a.contentId && o.dest && (await hashFile(o.dest)).contentId === a.contentId) report.rolledForward++
        else report.findings.push(`${a.path}: both the archived bytes and a file at the destination exist; nothing was touched`)
      } else if (!archiveExists && destExists) {
        await voidCatalogue(options.copyRoot, p.store, p.catalogueId) // the move never happened
        report.rolledBack++
      } else {
        report.findings.push(`${a.path}: neither the archived bytes nor the destination exist`)
      }
    } else if (tmpExists && o.tmp && o.dest && !destExists && a.kind === 'CopyTo' && a.contentId && (await hashFile(o.tmp)).contentId === a.contentId) {
      try {
        await moveNoReplace(o.tmp, o.dest)
        report.rolledForward++
      } catch (error) {
        if (!(error instanceof DestinationExistsError)) throw error
      }
    }
    // a staging file is our own temporary: it never holds the only copy of anything
    if (o.tmp && (await exists(o.tmp))) {
      await fs.rm(o.tmp, {force: true})
      report.cleaned++
    }
  }
  return report
}

/**
 * Crash recovery for a copy on an account or a device. The only local files an action leaves are its staged bytes (our own temporary:
 * removed). What can be half done is the move of displaced bytes to the copy's versions or trash path: if the object is aside and nothing
 * sits at its name, a replacement did not complete and the object goes back; a removal that completed stays. Nothing is ever overwritten.
 */
export async function recoverRemoteRun(options: {journalFile: string; copyRoot: string; remote: RemoteMedia; staging: string}): Promise<RecoveryReport> {
  const report: RecoveryReport = {unresolved: 0, rolledForward: 0, rolledBack: 0, cleaned: 0, findings: [], runs: []}
  const open = openActions(options.journalFile)
  for (const r of iterateJournal(options.journalFile)) if (r.t === 'run') report.runs.push({run: r.run, generation: r.generation})
  report.unresolved = open.size
  const keepfin = path.join(options.copyRoot, '.keepfin') + path.sep
  const call = async <T>(fn: () => Promise<T>): Promise<T> => {
    try {
      return await fn()
    } catch (error) {
      const stop = options.remote.stop(error)
      if (stop) throw new RemoteStopError(stop.state, stop.message, error)
      throw error
    }
  }
  for (const o of open.values()) {
    const a = o.action
    if (o.preserved && o.preserved.archive.startsWith(keepfin)) {
      const p = o.preserved
      const aside = `.keepfin/${p.archive.slice(keepfin.length).split(path.sep).join('/')}`
      const archiveExists = (await call(() => options.remote.stat(aside))) !== null
      const destExists = (await call(() => options.remote.stat(a.path))) !== null
      if (archiveExists && !destExists) {
        if (a.kind === 'RemoveFromCopy') {
          report.rolledForward++ // the removal completed: the bytes are in the trash
        } else {
          try {
            await call(() => options.remote.moveTo(aside, a.path, {revision: null, contentId: null}))
            await voidCatalogue(options.copyRoot, p.store, p.catalogueId)
            report.rolledBack++
          } catch (error) {
            if (error instanceof RemoteStopError) throw error
            report.findings.push(`${a.path}: the displaced bytes are in ${aside}`)
          }
        }
      } else if (archiveExists && destExists) {
        report.findings.push(`${a.path}: both the archived bytes and an object at the destination exist; nothing was touched`)
      } else if (!archiveExists && destExists) {
        await voidCatalogue(options.copyRoot, p.store, p.catalogueId) // the move never happened
        report.rolledBack++
      } else {
        report.findings.push(`${a.path}: neither the archived bytes nor the destination exist`)
      }
    }
    if (o.tmp && (await exists(o.tmp))) {
      await fs.rm(o.tmp, {force: true})
      report.cleaned++
    }
  }
  return report
}

/** Keeps the finished journal as `journal.prev.jsonl` (one generation) so the next run starts clean. */
export async function archiveJournal(journalFile: string): Promise<void> {
  const prev = path.join(path.dirname(journalFile), 'journal.prev.jsonl')
  try {
    await fs.rm(prev, {force: true})
    await fs.rename(journalFile, prev)
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
  }
}
