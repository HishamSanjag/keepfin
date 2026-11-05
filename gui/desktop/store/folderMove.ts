// Storage › Change location: the Keepfin folder moves while the library is closed (dev-docs/plans/KEEPFIN-FOLDER-MOVE.md, D2).
// The request is saved in move.json and Keepfin restarts into the move window. The old folder stays in charge until every file
// is in the new place (a rename on the same drive, otherwise copied and checked); then the profile switches in one write and
// the old folder goes to the Recycle Bin. A crash before the switch repeats the copy; after it, only the tidy-up is left.
import fs from 'node:fs/promises'
import path from 'node:path'
import {folderMoveProblem} from '../../shared/library.js'
import type {FolderMove, MoveProgress} from '../../shared/storage.js'
import {copyVerified} from '../library/files.js'
import {readJson, writeJsonAtomic} from './atomic.js'
import {setProfileFolder} from './profiles.js'

const moveFile = (deviceDir: string) => path.join(deviceDir, 'move.json')
const backupFile = (deviceDir: string) => path.join(deviceDir, 'backup-pending.json')

export const readMove = (deviceDir: string) => readJson<FolderMove>(moveFile(deviceDir)).catch(() => undefined)
export const forgetMove = (deviceDir: string) => fs.rm(moveFile(deviceDir), {force: true})

/** "Keep the old place as a backup copy": the folder to add under Storage › Copies at the next live start, until it is added. */
export const readPendingBackup = (deviceDir: string) => readJson<{path: string}>(backupFile(deviceDir)).then((value) => value?.path, () => undefined)
export const forgetPendingBackup = (deviceDir: string) => fs.rm(backupFile(deviceDir), {force: true})

/** Why `to` cannot take the folder at `from`, in plain words, or undefined when it can. Where the two are is the shared core's rule. */
export async function moveProblem(from: string, to: string): Promise<string | undefined> {
  if (!path.isAbsolute(to)) return 'Choose a folder.'
  const placed = folderMoveProblem(from, to)
  if (placed) return placed
  const names = await fs.readdir(to).catch((error: NodeJS.ErrnoException) => (error.code === 'ENOENT' ? [] : undefined))
  if (!names) return 'Keepfin cannot open this folder. Pick another one.'
  if (names.length) return 'Choose an empty folder, or a new one.'
}

export async function requestMove(deviceDir: string, from: string, to: string, keepOld = false): Promise<void> {
  const problem = await moveProblem(from, to)
  if (problem) throw new Error(problem)
  await writeJsonAtomic(moveFile(deviceDir), {from, to, ...(keepOld ? {keepOld} : {})} satisfies FolderMove)
}

async function filesIn(root: string): Promise<{rel: string; size: number}[]> {
  const out: {rel: string; size: number}[] = []
  const walk = async (dir: string) => {
    for (const entry of await fs.readdir(dir, {withFileTypes: true})) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) await walk(full)
      else if (entry.isFile()) out.push({rel: path.relative(root, full), size: (await fs.stat(full)).size})
    }
  }
  await walk(root)
  return out
}

export type MoveDeps = {
  deviceDir: string
  profileId: string
  push(progress: MoveProgress): void
  /** The old folder after a copy: to the Recycle Bin. */
  trash(target: string): Promise<void>
  /** Stop: checked between files, never in the middle of one. */
  signal?: AbortSignal
}

/** Runs (or resumes) the move. The profile points at `move.to` once it returns. */
export async function runMove(move: FolderMove, deps: MoveDeps): Promise<void> {
  const {from, to} = move
  // a rename that finished just before a crash: only the switch is left
  const renamed = !(await fs.stat(from).catch(() => undefined)) && !!(await fs.stat(path.join(to, '.keepfin')).catch(() => undefined))
  // keeping the old place as a backup copy needs the files in both places, so it is always a copy
  let copy = !renamed && !!move.keepOld
  if (!renamed && !copy) {
    try {
      await fs.mkdir(path.dirname(to), {recursive: true})
      await fs.rmdir(to).catch(() => undefined) // an empty folder the person picked; a rename needs the name free
      // a virus scanner or the search indexer often holds a folder for a moment: try the rename a few times before copying
      for (let attempt = 0; ; attempt++) {
        try {
          await fs.rename(from, to)
          break
        } catch (error) {
          const code = (error as NodeJS.ErrnoException).code
          if (attempt >= 4 || (code !== 'EPERM' && code !== 'EBUSY' && code !== 'EACCES')) throw error
          await new Promise((resolve) => setTimeout(resolve, 500))
        }
      }
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code
      if (code !== 'EXDEV' && code !== 'EPERM' && code !== 'ENOTEMPTY' && code !== 'EEXIST') throw error
      copy = true
    }
  }
  if (copy) {
    const files = await filesIn(from)
    const bytesTotal = files.reduce((sum, file) => sum + file.size, 0)
    let bytesDone = 0
    for (const [index, file] of files.entries()) {
      if (deps.signal?.aborted) throw new Error('The move was stopped.')
      // the library's own records (.keepfin) have names that mean nothing to people
      const current = file.rel.split(path.sep)[0] === '.keepfin' ? 'Your library records' : file.rel
      deps.push({phase: 'copying', done: index, total: files.length, bytesDone, bytesTotal, current})
      const target = path.join(to, file.rel)
      await fs.mkdir(path.dirname(target), {recursive: true})
      // a copy that finished before a crash is not copied again
      const there = await fs.stat(target).catch(() => undefined)
      if (there?.size !== file.size) await copyVerified(path.join(from, file.rel), target)
      bytesDone += file.size
    }
  }
  deps.push({phase: 'switching', done: 0, total: 0, bytesDone: 0, bytesTotal: 0})
  await setProfileFolder(deps.deviceDir, deps.profileId, to)
  await finishMove(move, deps)
}

/**
 * After the switch, safe to repeat (every start runs it while move.json is there). Kept as a backup: only the old library
 * records go to the Recycle Bin (so the old place can never open as a second library) and the folder is added under Copies
 * at the next start. Otherwise the old folder goes to the Recycle Bin; when another program holds it, the next start tries again.
 */
export async function finishMove(move: FolderMove, deps: Pick<MoveDeps, 'deviceDir' | 'push' | 'trash'>): Promise<void> {
  let note: string | undefined
  const left = await fs.stat(move.from).catch(() => undefined)
  const records = path.join(move.from, '.keepfin')
  try {
    if (left && move.keepOld) {
      if (await fs.stat(records).catch(() => undefined)) await deps.trash(records)
      await writeJsonAtomic(backupFile(deps.deviceDir), {path: move.from})
    } else if (left) await deps.trash(move.from)
    await forgetMove(deps.deviceDir)
  } catch {
    note = `The old folder is still at ${move.from}, because another program (often File Explorer) had it open. Everything is in the new place and was checked. Close that program; Keepfin tries again each time it starts.`
  }
  deps.push({phase: 'done', done: 0, total: 0, bytesDone: 0, bytesTotal: 0, note})
}
