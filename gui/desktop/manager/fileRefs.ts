// The library's bookkeeping of job files: where history points, what moved, what the library deleted. Moved out of manager.ts;
// everything it needs from the queue comes through `QueueCore`.
import fs from 'node:fs/promises'
import path from 'node:path'
import {isInFolder, movedPath, restoreOrder, type Space} from '../../shared/library.js'
import {locateFile} from '../engine/outputs.js'
import type {StoredJob} from '../migrate.js'
import {markHistoryMoved, pendingMoves, rehomeHistory} from '../store/migration/history.js'
import {sizeOf, type QueueCore} from './core.js'

export class FileRefs {
  constructor(private readonly core: QueueCore) {}

  /** Finished jobs with a saved file (the library adopts history from these). */
  completedJobs(): StoredJob[] {
    return this.core.jobs().filter((job) => job.status === 'completed' && !!job.file)
  }

  /** True while the download is queued, running, paused or retrying (not finished, failed or cancelled). */
  isPending(id: string): boolean {
    const status = this.core.jobs().find((job) => job.id === id)?.status
    return !!status && status !== 'completed' && status !== 'failed' && status !== 'cancelled'
  }

  /** The library renamed or moved a file: history follows it. */
  relocateFile(from: string, to: string) {
    const same = (a: string | undefined, b: string) =>
      !!a && (process.platform === 'win32' ? path.normalize(a).toLowerCase() === path.normalize(b).toLowerCase() : path.normalize(a) === path.normalize(b))
    let changed = false
    for (const job of this.core.jobs()) {
      if (same(job.file, from)) {
        job.file = to
        changed = true
      }
      if (job.files?.some((file) => same(file, from))) {
        job.files = job.files.map((file) => (same(file, from) ? to : file))
        changed = true
      }
    }
    if (changed) void this.core.commit().catch(() => {})
  }

  /** The library deleted these files: history keeps the entry but no longer points at them. */
  forgetFiles(paths: string[]) {
    const gone = new Set(paths.map((file) => (process.platform === 'win32' ? path.normalize(file).toLowerCase() : path.normalize(file))))
    const hit = (file: string | undefined) =>
      !!file && gone.has(process.platform === 'win32' ? path.normalize(file).toLowerCase() : path.normalize(file))
    let changed = false
    for (const job of this.core.jobs()) {
      if (hit(job.file)) {
        job.file = undefined
        job.fileSize = undefined
        changed = true
      }
      if (job.files?.some((file) => hit(file))) {
        job.files = job.files.filter((file) => !hit(file))
        if (!job.files.length) job.files = undefined
        changed = true
      }
    }
    if (changed) void this.core.commit().catch(() => {})
  }

  /** What history holds for these files, taken before the library deletes them so Undo can point history at them again. */
  fileRefs(paths: string[]): {jobId: string; file?: string; files?: string[]; fileSize?: number}[] {
    const key = (file: string) => (process.platform === 'win32' ? path.normalize(file).toLowerCase() : path.normalize(file))
    const wanted = new Set(paths.map(key))
    const refs: {jobId: string; file?: string; files?: string[]; fileSize?: number}[] = []
    for (const job of this.core.jobs())
      if ((job.file && wanted.has(key(job.file))) || job.files?.some((file) => wanted.has(key(file))))
        refs.push({jobId: job.id, file: job.file, files: job.files && [...job.files], fileSize: job.fileSize})
    return refs
  }

  /** Points history at the files again (Undo of a library delete). A job whose record has changed or gone since is left alone. */
  restoreFileRefs(refs: {jobId: string; file?: string; files?: string[]; fileSize?: number}[]) {
    let changed = false
    for (const ref of refs) {
      const job = this.core.jobs().find((item) => item.id === ref.jobId)
      if (!job) continue
      if (ref.file && !job.file) {
        job.file = ref.file
        job.fileSize = ref.fileSize
        changed = true
      }
      if (ref.files?.length) {
        const missing = ref.files.filter((file) => !job.files?.includes(file))
        if (missing.length) {
          job.files = [...(job.files ?? []), ...missing]
          changed = true
        }
      }
    }
    if (changed) void this.core.commit().catch(() => {})
  }

  /** Copies of the jobs in history, in order (taken before "Remove from history" so Undo can bring them back as they were). */
  storedJobs(): StoredJob[] {
    return this.core.jobs().map((job) => structuredClone(job))
  }

  /**
   * Brings removed history entries back (Undo): each where it stood among the entries that are still there, with anything added or
   * removed since left as it is. Returns how many came back.
   */
  async restoreJobs(saved: StoredJob[], order: readonly string[]): Promise<number> {
    const present = new Set(this.core.jobs().map((job) => job.id))
    const back = saved.filter((job) => !present.has(job.id))
    if (!back.length) return 0
    const byId = new Map<string, StoredJob>([...this.core.jobs(), ...back.map((job) => structuredClone(job))].map((job) => [job.id, job]))
    const ids = restoreOrder(order, this.core.jobs().map((job) => job.id), new Set(back.map((job) => job.id)))
    this.core.setJobs(ids.map((id) => byId.get(id)!).filter(Boolean))
    await this.core.commit()
    return back.length
  }

  /**
   * Opening or showing a finished download whose file is not where the job says: a name damaged by an older version is
   * found again from the job's output name or by a unique damage-tolerant match, and the job follows it. Undefined
   * when the file is there or cannot be found with certainty.
   */
  /** After the move into the Keepfin folder, finished downloads point at where their files went (store/migration/history.ts). */
  async followMovedFiles(deviceDir: string): Promise<number> {
    const {moves, runs} = await pendingMoves(deviceDir)
    if (!runs.length) return 0
    const isFile = async (file: string) => !!(await fs.stat(file).catch(() => undefined))?.isFile()
    const changed = await rehomeHistory(this.core.jobs(), moves, isFile)
    if (changed) {
      this.core.touch()
      await this.core.save()
      console.warn(`[history] ${changed} finished downloads now point at their files in the Keepfin folder`)
    }
    await markHistoryMoved(runs, this.core.now())
    return changed
  }

  /**
   * After the Keepfin folder (or a space) moved, finished downloads point at their files in the new place. A path follows only
   * when the file is there, so a space pointed elsewhere with its files left behind keeps its history as it was.
   */
  async followSpaces(before: readonly Space[], after: readonly Space[]) {
    const moves = after.flatMap((space) => {
      const from = before.find((old) => old.id === space.id)?.folder
      return from && space.folder && !(isInFolder(from, space.folder) && isInFolder(space.folder, from)) ? [{from, to: space.folder}] : []
    })
    if (!moves.length) return
    const follow = async (file: string) => {
      for (const move of moves) {
        const moved = movedPath(move.from, move.to, file)
        if (moved && (await fs.stat(moved).catch(() => undefined))) return moved
      }
      return file
    }
    let changed = 0
    for (const job of this.core.jobs()) {
      if (job.status !== 'completed' || !job.file) continue
      const file = await follow(job.file)
      const files = job.files && (await Promise.all(job.files.map(follow)))
      if (file === job.file && (!files || files.every((name, i) => name === job.files![i]))) continue
      if (file !== job.file && job.outputTemplate) job.outputTemplate = path.join(path.dirname(file), path.basename(job.outputTemplate))
      job.file = file
      job.files = files
      changed++
    }
    if (changed) {
      await this.core.save()
      console.warn(`[history] ${changed} finished downloads follow the moved folders`)
    }
  }

  /** Whether a file is the saved file of another job (a match must never take it). */
  ownedByOther(job: StoredJob, file: string): boolean {
    const key = (value: string) => (process.platform === 'win32' ? path.normalize(value).toLowerCase() : path.normalize(value))
    const wanted = key(file)
    return this.core.jobs().some((other) => other !== job && ((!!other.file && key(other.file) === wanted) || !!other.files?.some((name) => key(name) === wanted)))
  }

  async repairJobFile(id: string): Promise<string | undefined> {
    const job = this.core.jobs().find((item) => item.id === id)
    if (!job || job.status !== 'completed' || !job.file || (await fs.stat(job.file).catch(() => undefined))?.isFile()) return undefined
    const found = await locateFile(job.file, {
      template: job.format.type === 'gallery' ? undefined : job.outputTemplate,
      owned: (file) => this.ownedByOther(job, file),
      log: (message) => console.warn(message),
    })
    if (!found) return undefined
    job.file = found
    job.fileSize = await sizeOf([found])
    this.core.touch()
    await this.core.save().catch(() => {})
    return found
  }
}
