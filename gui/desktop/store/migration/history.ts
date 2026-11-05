// Download history after the move into the Keepfin folder.
//
// The setup window moves files into the Keepfin folder and brings the library over, but queue.json keeps the paths the
// downloads had before: Open and Show in folder would then fail for every finished download. After a committed run, the
// history follows the plan's moves once (a marker in the run folder records that). A saved path that an older version
// damaged (every Arabic letter and emoji dropped) is matched by the name the job asked for, then by a unique
// damage-tolerant match among the files moved out of the same folder.
import fs from 'node:fs/promises'
import path from 'node:path'
import {fromTemplate, matchDamaged} from '../../engine/outputs.js'
import {pathKey} from './io.js'
import {readJournal} from './journal.js'
import type {Plan} from './types.js'

export type Move = {source: string; dest: string}

/** The part of a job this repair reads and rewrites. */
export type HistoryJob = {id: string; file?: string; files?: string[]; outputTemplate?: string}

const MARKER = 'history.json'

/** Moves of every committed run whose history has not been brought along yet, and the run folders to mark afterwards. */
export async function pendingMoves(deviceDir: string): Promise<{moves: Move[]; runs: string[]}> {
  const migrationDir = path.join(deviceDir, 'migration')
  const names = await fs.readdir(migrationDir).catch(() => [] as string[])
  const moves: Move[] = []
  const runs: string[] = []
  for (const name of names) {
    const runDir = path.join(migrationDir, name)
    if (await fs.stat(path.join(runDir, MARKER)).catch(() => undefined)) continue
    const plan = await fs
      .readFile(path.join(runDir, 'plan.json'), 'utf8')
      .then((text) => JSON.parse(text) as Plan)
      .catch(() => undefined)
    if (!plan || !Array.isArray(plan.files)) continue
    if (!(await readJournal(runDir).catch(() => undefined))?.run.committed) continue
    for (const file of plan.files) if (file.kind !== 'art' && file.source && file.dest) moves.push({source: file.source, dest: file.dest})
    runs.push(runDir)
  }
  return {moves, runs}
}

export async function markHistoryMoved(runs: readonly string[], now: number): Promise<void> {
  for (const runDir of runs) await fs.writeFile(path.join(runDir, MARKER), JSON.stringify({version: 1, at: now})).catch(() => undefined)
}

const stemOf = (file: string) => path.basename(file, path.extname(file))

/**
 * Points jobs at where the moves took their files. Only paths that no longer exist are changed (`exists`), and a moved file
 * is never given to two jobs. Returns the number of jobs that changed.
 */
export async function rehomeHistory(jobs: HistoryJob[], moves: readonly Move[], exists: (file: string) => Promise<boolean>): Promise<number> {
  if (!moves.length) return 0
  const bySource = new Map<string, string>()
  const byFolder = new Map<string, Move[]>()
  const folderDest = new Map<string, string | null>()
  for (const move of moves) {
    bySource.set(pathKey(move.source), move.dest)
    const folder = pathKey(path.dirname(move.source))
    byFolder.set(folder, [...(byFolder.get(folder) ?? []), move])
    // A folder whose files all went to one folder; a template there follows it.
    const dest = path.dirname(move.dest)
    const known = folderDest.get(folder)
    if (known === undefined) folderDest.set(folder, dest)
    else if (known !== null && pathKey(known) !== pathKey(dest)) folderDest.set(folder, null)
  }
  const taken = new Set<string>()
  for (const job of jobs) for (const file of [job.file, ...(job.files ?? [])]) if (file && !bySource.has(pathKey(file))) taken.add(pathKey(file))

  // Exact paths and the names jobs asked for come first, so a damage-tolerant guess can never take a file a job really had.
  const resolve = (file: string, template: string | undefined, guess: boolean): string | undefined => {
    if (!guess) {
      const exact = bySource.get(pathKey(file))
      if (exact) return exact
      const named = fromTemplate(template, path.extname(file))
      return named ? bySource.get(pathKey(named)) : undefined
    }
    const candidates = byFolder.get(pathKey(path.dirname(file))) ?? []
    const stems = [template ? path.basename(template).replace(/\.%\(ext\)s$/, '') : undefined, stemOf(file)].filter((value): value is string => !!value)
    const name = matchDamaged({stems, ext: path.extname(file)}, candidates.map((move) => path.basename(move.source)))
    return name ? candidates.find((move) => path.basename(move.source) === name)?.dest : undefined
  }

  const touched = new Set<HistoryJob>()
  for (const guess of [false, true]) {
    for (const job of jobs) {
      const follow = async (file: string, template: string | undefined) => {
        if (await exists(file)) return file
        const dest = resolve(file, template, guess)
        // Jobs that really had the same path (a repeat download that found the file) share it; a guess must be unique.
        if (!dest || (guess && taken.has(pathKey(dest))) || !(await exists(dest))) return file
        taken.add(pathKey(dest))
        touched.add(job)
        return dest
      }
      if (job.file) job.file = await follow(job.file, job.outputTemplate)
      if (job.files?.length) {
        const files: string[] = []
        for (const file of job.files) files.push(await follow(file, undefined))
        job.files = files
      }
    }
  }
  for (const job of jobs) {
    if (job.outputTemplate) {
      const dest = folderDest.get(pathKey(path.dirname(job.outputTemplate)))
      if (dest) {
        const moved = path.join(dest, path.basename(job.outputTemplate))
        if (moved !== job.outputTemplate) {
          job.outputTemplate = moved
          touched.add(job)
        }
      }
    }
  }
  return touched.size
}
