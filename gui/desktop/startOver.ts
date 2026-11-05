// Start over (Settings > About Keepfin): erase what this PC keeps, optionally with the Keepfin folder, then start fresh.
// Files are locked while Keepfin runs, so a small detached helper (this same executable in Node mode) waits for Keepfin to exit,
// deletes, writes an outcome file and opens Keepfin again. The next start reads the outcome and tells the person. Pure of Electron.
import {spawn} from 'node:child_process'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {StartOverOutcome} from '../shared/contracts.js'

export const RESULT_FILE = 'keepfin-start-over-result.json'
export const resultPath = (tmp = os.tmpdir()) => path.join(tmp, RESULT_FILE)

const sameOrInside = (parent: string, child: string) => {
  const rel = path.relative(parent, child)
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel))
}

/** True only for a folder that holds `.keepfin/folder.json`: a regular file with a JSON object in it. */
export async function isKeepfinFolder(dir: string): Promise<boolean> {
  try {
    const file = path.join(dir, '.keepfin', 'folder.json')
    const stat = await fs.stat(file)
    if (!stat.isFile() || stat.size > 1_000_000) return false
    const json = JSON.parse(await fs.readFile(file, 'utf8'))
    return !!json && typeof json === 'object' && !Array.isArray(json)
  } catch {
    return false
  }
}

export type StartOverPlan = {
  userData: string
  /** The Keepfin folder to delete, only when asked for and it really is one. */
  folder?: string
  deleteFolder: boolean
  /** Said in the outcome when the folder was asked for but was already gone. */
  notes: string[]
}

/**
 * What to delete. [profileFolder] is the current profile's folder from profiles.json. Throws (nothing has been touched) when a folder
 * deletion was asked for and the path is not safe: not absolute, a drive root, the home folder or something around the app data, or
 * not a Keepfin folder.
 */
export async function planStartOver(input: {userData: string; deleteFolder: boolean; profileFolder?: string; home?: string}): Promise<StartOverPlan> {
  const userData = path.resolve(input.userData)
  if (!path.isAbsolute(input.userData) || path.parse(userData).root === userData) throw new Error('Keepfin cannot tell where its own data is, so it will not erase anything.')
  const plan: StartOverPlan = {userData, deleteFolder: input.deleteFolder, notes: []}
  if (!input.deleteFolder) return plan
  const raw = input.profileFolder
  if (!raw || !path.isAbsolute(raw)) throw new Error('Keepfin cannot tell which folder is yours, so it will not delete one.')
  const folder = path.resolve(raw)
  if (path.parse(folder).root === folder) throw new Error('The Keepfin folder is a whole drive, so Keepfin will not delete it.')
  const home = input.home ? path.resolve(input.home) : undefined
  if (home && sameOrInside(folder, home)) throw new Error('The Keepfin folder contains your user folder, so Keepfin will not delete it.')
  if (sameOrInside(folder, userData)) throw new Error('The Keepfin folder contains the app’s own data, so Keepfin will not delete it.')
  const exists = await fs.stat(folder).then(
    (stat) => stat.isDirectory(),
    () => false,
  )
  if (!exists) {
    plan.notes.push('The Keepfin folder was already gone.')
    return plan
  }
  if (!(await isKeepfinFolder(folder))) throw new Error('That folder does not look like a Keepfin folder (it has no .keepfin/folder.json), so Keepfin will not delete it.')
  plan.folder = folder
  return plan
}

/**
 * Deletes [target] and everything in it, trying again for the errors Windows gives while something (a virus scanner, the indexer, a
 * process that is still closing) holds a file. Returns the last error message, or undefined when it is gone.
 * Self-contained on purpose: the helper script is built from this function's source.
 */
export async function removeTree(
  fsp: {rm: (target: string, options: {recursive: boolean; force: boolean}) => Promise<void>},
  target: string,
  attempts: number,
  delayMs: number,
): Promise<string | undefined> {
  let last: unknown
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      await fsp.rm(target, {recursive: true, force: true})
      return undefined
    } catch (error) {
      last = error
      const code = (error as {code?: string} | undefined)?.code
      if (code !== 'EBUSY' && code !== 'EPERM' && code !== 'ENOTEMPTY' && code !== 'EACCES' && code !== 'EMFILE') break
      await new Promise((resolve) => setTimeout(resolve, delayMs))
    }
  }
  return last instanceof Error ? last.message : String(last)
}

/** The helper program. It reads its plan from argv[2], and runs with ELECTRON_RUN_AS_NODE=1 under the app's own executable. */
export function helperSource(): string {
  return `'use strict'
const fs = require('node:fs/promises')
const fsSync = require('node:fs')
const path = require('node:path')
const { spawn } = require('node:child_process')
const removeTree = ${removeTree.toString()}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const exists = (target) => fs.access(target).then(() => true, () => false)
async function main() {
  const planFile = process.argv[2]
  const plan = JSON.parse(fsSync.readFileSync(planFile, 'utf8'))
  const outcome = { deleteFolder: plan.deleteFolder, folderDeleted: false, dataDeleted: false, problems: [] }
  let alive = true
  const deadline = Date.now() + plan.waitMs
  while (Date.now() < deadline) {
    try {
      process.kill(plan.pid, 0)
    } catch (error) {
      if (error && error.code === 'ESRCH') { alive = false; break }
    }
    await sleep(250)
  }
  if (alive) {
    outcome.problems.push('Keepfin did not close in time, so nothing was erased.')
  } else {
    await sleep(plan.settleMs)
    if (plan.folder) {
      const problem = await removeTree(fs, plan.folder, plan.attempts, plan.delayMs)
      outcome.folderDeleted = !(await exists(plan.folder))
      // the same as on the phone: a folder that is only partly gone leaves Keepfin as it was, so it can be tried again from Settings
      if (!outcome.folderDeleted) outcome.problems.push('The Keepfin folder could not be fully deleted' + (problem ? ' (' + problem + ')' : '') + '. Part of it may already be deleted. Keepfin was not reset; try again from Settings.')
    } else if (plan.deleteFolder) {
      outcome.folderDeleted = true
    }
    outcome.notes = plan.notes || []
    if (!plan.folder || outcome.folderDeleted) {
      const problem = await removeTree(fs, plan.userData, plan.attempts, plan.delayMs)
      outcome.dataDeleted = !(await exists(plan.userData))
      if (!outcome.dataDeleted) outcome.problems.push('Some of what this PC keeps could not be erased' + (problem ? ' (' + problem + ')' : '') + '.')
    }
  }
  await fs.mkdir(path.dirname(plan.resultFile), { recursive: true }).catch(() => {})
  const temp = plan.resultFile + '.tmp'
  await fs.writeFile(temp, JSON.stringify(outcome), 'utf8')
  await fs.rename(temp, plan.resultFile)
  await fs.rm(planFile, { force: true }).catch(() => {})
  // the helper leaves nothing behind in the temp folder (Node has read this script already)
  await fs.rm(__filename, { force: true }).catch(() => {})
  if (plan.relaunch) {
    const env = { ...process.env }
    delete env.ELECTRON_RUN_AS_NODE
    spawn(plan.relaunch, [], { detached: true, stdio: 'ignore', env }).unref()
  }
}
main().catch((error) => {
  try {
    const plan = JSON.parse(fsSync.readFileSync(process.argv[2], 'utf8'))
    fsSync.writeFileSync(plan.resultFile, JSON.stringify({ deleteFolder: plan.deleteFolder, folderDeleted: false, dataDeleted: false, problems: ['Start over stopped: ' + (error && error.message ? error.message : String(error))] }))
  } catch {}
})
`
}

export type HelperPlan = StartOverPlan & {
  pid: number
  resultFile: string
  /** The program to open again when it is done; absent in development. */
  relaunch?: string
  waitMs: number
  settleMs: number
  attempts: number
  delayMs: number
}

/** Writes the helper and its plan to [tmp] and starts it detached. Returns the plan file path. */
export async function launchHelper(input: {
  plan: StartOverPlan
  pid: number
  execPath: string
  relaunch?: string
  tmp?: string
  waitMs?: number
  settleMs?: number
  attempts?: number
  delayMs?: number
}): Promise<{script: string; planFile: string}> {
  const tmp = input.tmp ?? os.tmpdir()
  const id = `${process.pid}-${Date.now()}`
  const script = path.join(tmp, `keepfin-start-over-${id}.cjs`)
  const planFile = path.join(tmp, `keepfin-start-over-${id}.json`)
  const plan: HelperPlan = {
    ...input.plan,
    pid: input.pid,
    resultFile: resultPath(tmp),
    relaunch: input.relaunch,
    waitMs: input.waitMs ?? 60_000,
    settleMs: input.settleMs ?? 600,
    attempts: input.attempts ?? 12,
    delayMs: input.delayMs ?? 500,
  }
  await fs.rm(plan.resultFile, {force: true})
  await fs.writeFile(script, helperSource(), 'utf8')
  await fs.writeFile(planFile, JSON.stringify(plan), 'utf8')
  const child = spawn(input.execPath, [script, planFile], {
    detached: true,
    stdio: 'ignore',
    windowsHide: true,
    env: {...process.env, ELECTRON_RUN_AS_NODE: '1'},
  })
  child.unref()
  return {script, planFile}
}

/** The outcome the helper left, once: the file is removed as it is read. Undefined when there is none. */
export async function takeResult(tmp = os.tmpdir()): Promise<StartOverOutcome | undefined> {
  const file = resultPath(tmp)
  try {
    const raw = JSON.parse(await fs.readFile(file, 'utf8'))
    await fs.rm(file, {force: true})
    if (!raw || typeof raw !== 'object') return undefined
    return {
      deleteFolder: raw.deleteFolder === true,
      folderDeleted: raw.folderDeleted === true,
      dataDeleted: raw.dataDeleted === true,
      notes: Array.isArray(raw.notes) ? raw.notes.filter((item: unknown): item is string => typeof item === 'string').slice(0, 10) : [],
      problems: Array.isArray(raw.problems) ? raw.problems.filter((item: unknown): item is string => typeof item === 'string').slice(0, 10) : [],
    }
  } catch {
    return undefined
  }
}
