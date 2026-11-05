import {execFile} from 'node:child_process'
import fs from 'node:fs/promises'
import path from 'node:path'
import {readJson, writeJsonAtomic, CorruptJsonError} from './atomic.js'

/** The newest folder.json format this build reads. A folder is written as format 1 until a combine makes it format 2 (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 1). */
export const FOLDER_FORMAT = 2
export const PLAIN_FOLDER_FORMAT = 1
export const README_TEXT = "Keepfin's library data. Do not edit; deleting it loses playlists and history."
export const DEFAULT_SPACE_FOLDERS = ['Music', 'Videos', 'Downloads'] as const
const DATA_DIR = '.keepfin'
const DATA_SUBDIRS = ['actors', 'ops', 'checkpoints', 'art', 'trash'] as const

/** One library the folder knows (format 2). ecords is relative to .keepfin/; '' is .keepfin/ itself. */
export interface FolderLibrary {
  libraryId: string
  replicaId: string
  records: string
  /** ctive is in charge; previous is frozen until keepUntil; left was separated away from. */
  role: 'active' | 'previous' | 'left'
  since?: string
  keepUntil?: string
  mergeId?: string
}

export interface FolderJson {
  format: number
  /** Always the active library, so identity readers keep working in both formats. */
  libraryId: string
  replicaId: string
  createdAt: string
  /** Format 2 only: the active library's records root, relative to .keepfin/. */
  records?: string
  libraries?: FolderLibrary[]
}

export interface KeepfinFolder extends FolderJson {
  root: string
}

/** The records root of a folder: where actors/, ops/, checkpoints/, art/, trash/, places/ and membership live. */
export function recordsDir(folder: {root: string; records?: string}): string {
  return recordsPath(folder.root, folder.records)
}

/** The same from a media root and a records prefix ('' or undefined: .keepfin/ itself). */
export function recordsPath(root: string, records?: string): string {
  return records ? path.join(root, DATA_DIR, ...records.split('/')) : path.join(root, DATA_DIR)
}

/** The folders every records root holds (created empty; a library store creates the rest when it needs it). */
export async function ensureRecordsTree(dir: string): Promise<void> {
  await fs.mkdir(dir, {recursive: true})
  for (const sub of DATA_SUBDIRS) await fs.mkdir(path.join(dir, sub), {recursive: true})
}

/** A records prefix is relative, '/'-separated and never leaves .keepfin/. */
export function validRecords(value: unknown): value is string {
  if (typeof value !== 'string') return false
  if (value === '') return true
  return !value.startsWith('/') && !value.includes('\\') && value.split('/').every((s) => s !== '' && s !== '.' && s !== '..')
}

export type OpenResult = {ok: true; folder: KeepfinFolder} | {ok: false; reason: 'missing' | 'not-a-keepfin-folder' | 'newer-format' | 'corrupt'}

export const dataDir = (root: string) => path.join(root, DATA_DIR)
export const folderJsonPath = (root: string) => path.join(dataDir(root), 'folder.json')

/** Hides a path on Windows (attrib +h). A no-op elsewhere. */
export function hideOnWindows(target: string): Promise<void> {
  if (process.platform !== 'win32') return Promise.resolve()
  return new Promise((resolve, reject) => {
    execFile('attrib', ['+h', target], {windowsHide: true}, (error) => (error ? reject(error) : resolve()))
  })
}

export interface CreateOptions {
  libraryId: string
  replicaId: string
  now: string
  hide?: (target: string) => Promise<void> | void
}

export async function createKeepfinFolder(root: string, options: CreateOptions): Promise<KeepfinFolder> {
  const existing = await readJson<unknown>(folderJsonPath(root)).catch(() => 'corrupt')
  if (existing !== undefined) throw new Error('This folder is already a Keepfin folder.')
  await fs.mkdir(root, {recursive: true})
  const data = dataDir(root)
  await fs.mkdir(data, {recursive: true})
  for (const sub of DATA_SUBDIRS) await fs.mkdir(path.join(data, sub), {recursive: true})
  const json: FolderJson = {format: PLAIN_FOLDER_FORMAT, libraryId: options.libraryId, replicaId: options.replicaId, createdAt: options.now}
  await writeJsonAtomic(folderJsonPath(root), json)
  await fs.writeFile(path.join(data, 'README.txt'), README_TEXT, 'utf8')
  for (const space of DEFAULT_SPACE_FOLDERS) await fs.mkdir(path.join(root, space), {recursive: true})
  try {
    await (options.hide ?? hideOnWindows)(data)
  } catch {
    // Hiding is cosmetic; the folder works without it.
  }
  return {root, ...json}
}

/** [maxFormat] lets a test stand in for an older app (format 1 only reads format 1). */
export async function openKeepfinFolder(root: string, options: {maxFormat?: number} = {}): Promise<OpenResult> {
  const stat = await fs.stat(root).catch(() => undefined)
  if (!stat || !stat.isDirectory()) return {ok: false, reason: 'missing'}
  let raw: unknown
  try {
    raw = await readJson<unknown>(folderJsonPath(root))
  } catch (error) {
    if (error instanceof CorruptJsonError) return {ok: false, reason: 'corrupt'}
    return {ok: false, reason: 'corrupt'}
  }
  if (raw === undefined) return {ok: false, reason: 'not-a-keepfin-folder'}
  if (!raw || typeof raw !== 'object') return {ok: false, reason: 'corrupt'}
  const r = raw as Record<string, unknown>
  if (typeof r.format !== 'number' || !Number.isInteger(r.format) || r.format < 1) return {ok: false, reason: 'corrupt'}
  if (r.format > (options.maxFormat ?? FOLDER_FORMAT)) return {ok: false, reason: 'newer-format'}
  const text = (v: unknown) => typeof v === 'string' && v.length > 0
  if (!text(r.libraryId) || !text(r.replicaId) || !text(r.createdAt)) return {ok: false, reason: 'corrupt'}
  const folder: KeepfinFolder = {root, format: r.format, libraryId: r.libraryId as string, replicaId: r.replicaId as string, createdAt: r.createdAt as string}
  if (r.format >= 2) {
    // Format 2 names the active library's records root; a damaged pointer is never guessed at.
    if (r.records !== undefined && !validRecords(r.records)) return {ok: false, reason: 'corrupt'}
    if (r.records !== undefined) folder.records = r.records
    if (r.libraries !== undefined) {
      const list = readLibraries(r.libraries)
      if (!list) return {ok: false, reason: 'corrupt'}
      folder.libraries = list
    }
  }
  return {ok: true, folder}
}

function readLibraries(value: unknown): FolderLibrary[] | undefined {
  if (!Array.isArray(value)) return undefined
  const out: FolderLibrary[] = []
  for (const entry of value) {
    if (!entry || typeof entry !== 'object') return undefined
    const e = entry as Record<string, unknown>
    if (typeof e.libraryId !== 'string' || !e.libraryId || typeof e.replicaId !== 'string' || !e.replicaId || !validRecords(e.records)) return undefined
    if (e.role !== 'active' && e.role !== 'previous' && e.role !== 'left') return undefined
    const row: FolderLibrary = {libraryId: e.libraryId, replicaId: e.replicaId, records: e.records, role: e.role}
    for (const key of ['since', 'keepUntil', 'mergeId'] as const) if (typeof e[key] === 'string') row[key] = e[key] as string
    out.push(row)
  }
  return out
}

/** Writes folder.json (atomic: a crash leaves the old pointer or the new one). Only a combine writes format 2. */
export async function writeFolderJson(root: string, json: FolderJson): Promise<void> {
  await writeJsonAtomic(folderJsonPath(root), json)
}

export function defaultFolderPath(env: {USERPROFILE?: string; HOME?: string} = process.env): string {
  const home = env.USERPROFILE || env.HOME
  if (!home) throw new Error('Cannot find the user folder.')
  return path.join(home, 'Keepfin')
}

// ---------------------------------------------------------------- folder choice

export type CloudApp = 'google-drive' | 'onedrive' | 'dropbox' | 'icloud'

export interface FolderChoice {
  exists: boolean
  empty: boolean
  isKeepfinFolder: boolean
  writable: boolean
  freeBytes: number | null
  insideCloudApp: CloudApp | null
}

export interface ChoiceEnv {
  env?: Record<string, string | undefined>
  platform?: NodeJS.Platform
  statfs?: (target: string) => Promise<{bavail: number | bigint; bsize: number | bigint}>
}

export function normalizePath(p: string, platform: NodeJS.Platform = process.platform): string {
  let out = (platform === 'win32' ? path.win32 : path.posix).resolve(p)
  const sep = platform === 'win32' ? '\\' : '/'
  const root = (platform === 'win32' ? path.win32 : path.posix).parse(out).root
  while (out.length > root.length && out.endsWith(sep)) out = out.slice(0, -1)
  return platform === 'win32' ? out.toLowerCase() : out
}

function isInside(child: string, parent: string, platform: NodeJS.Platform): boolean {
  const c = normalizePath(child, platform)
  const p = normalizePath(parent, platform)
  const sep = platform === 'win32' ? '\\' : '/'
  return c === p || c.startsWith(p.endsWith(sep) ? p : p + sep)
}

async function exists(p: string): Promise<boolean> {
  return fs.stat(p).then(() => true, () => false)
}

async function dropboxRoots(env: Record<string, string | undefined>): Promise<string[]> {
  const roots: string[] = []
  for (const base of [env.APPDATA, env.LOCALAPPDATA]) {
    if (!base) continue
    try {
      const info = JSON.parse(await fs.readFile(path.join(base, 'Dropbox', 'info.json'), 'utf8')) as Record<string, unknown>
      for (const account of Object.values(info)) {
        const p = (account as {path?: unknown} | null)?.path
        if (typeof p === 'string' && p) roots.push(p)
      }
    } catch {
      // not installed or unreadable
    }
  }
  return roots
}

export async function detectCloudApp(target: string, options: ChoiceEnv = {}): Promise<CloudApp | null> {
  const env = options.env ?? process.env
  const platform = options.platform ?? process.platform
  for (const key of ['OneDrive', 'OneDriveConsumer', 'OneDriveCommercial']) {
    const root = env[key]
    if (root && isInside(target, root, platform)) return 'onedrive'
  }
  for (const root of await dropboxRoots(env)) if (isInside(target, root, platform)) return 'dropbox'
  const segments = normalizePath(target, platform).split(platform === 'win32' ? '\\' : '/')
  if (segments.some((s) => s === 'my drive')) return 'google-drive'
  if (env.LOCALAPPDATA && (await exists(path.join(env.LOCALAPPDATA, 'Google', 'DriveFS')))) {
    const root = (platform === 'win32' ? path.win32 : path.posix).parse(path.resolve(target)).root
    if (root && (await exists(path.join(root, 'My Drive')))) return 'google-drive'
  }
  if (segments.some((s) => s === 'iclouddrive')) return 'icloud'
  return null
}

async function nearestExisting(target: string): Promise<string | undefined> {
  let current = path.resolve(target)
  for (;;) {
    if (await exists(current)) return current
    const parent = path.dirname(current)
    if (parent === current) return undefined
    current = parent
  }
}

export async function inspectFolderChoice(target: string, options: ChoiceEnv = {}): Promise<FolderChoice> {
  const stat = await fs.stat(target).catch(() => undefined)
  const isDir = !!stat?.isDirectory()
  let empty = false
  let isKeepfinFolder = false
  if (isDir) {
    empty = (await fs.readdir(target).catch(() => ['x'])).length === 0
    isKeepfinFolder = await exists(folderJsonPath(target))
  }
  const probe = isDir ? target : await nearestExisting(target)
  let writable = false
  let freeBytes: number | null = null
  if (probe) {
    const temp = path.join(probe, `.keepfin-write-test-${process.pid}-${Math.random().toString(36).slice(2)}`)
    try {
      await fs.writeFile(temp, 'x')
      writable = true
    } catch {
      writable = false
    } finally {
      await fs.rm(temp, {force: true}).catch(() => undefined)
    }
    try {
      const s = await (options.statfs ?? ((p: string) => fs.statfs(p)))(probe)
      freeBytes = Number(s.bavail) * Number(s.bsize)
    } catch {
      freeBytes = null
    }
  }
  return {exists: isDir, empty, isKeepfinFolder, writable, freeBytes, insideCloudApp: await detectCloudApp(target, options)}
}

// ---------------------------------------------------------------- local lock

export type LockResult = {ok: true; release: () => Promise<void>} | {ok: false; reason: 'held'; pid: number}

function pidAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch (error) {
    return (error as NodeJS.ErrnoException).code !== 'ESRCH'
  }
}

export async function acquireFolderLock(deviceDir: string, replicaId: string, options: {pid?: number; now?: () => string} = {}): Promise<LockResult> {
  const dir = path.join(deviceDir, 'locks')
  const file = path.join(dir, `${replicaId}.lock`)
  await fs.mkdir(dir, {recursive: true})
  const pid = options.pid ?? process.pid
  const body = JSON.stringify({pid, startedAt: (options.now ?? (() => new Date().toISOString()))()})
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      await fs.writeFile(file, body, {flag: 'wx'})
      return {
        ok: true,
        release: async () => {
          // Only remove a lock that is still ours.
          const current = await readJson<{pid?: number}>(file).catch(() => undefined)
          if (current?.pid === pid) await fs.rm(file, {force: true})
        },
      }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error
    }
    const held = await readJson<{pid?: unknown}>(file).catch(() => undefined)
    const heldPid = typeof held?.pid === 'number' ? held.pid : undefined
    if (heldPid !== undefined && heldPid !== pid && pidAlive(heldPid)) return {ok: false, reason: 'held', pid: heldPid}
    // Dead holder, unreadable file or our own stale lock: take it over.
    await fs.rm(file, {force: true})
  }
  const last = await readJson<{pid?: number}>(file).catch(() => undefined)
  return {ok: false, reason: 'held', pid: last?.pid ?? -1}
}

// ---------------------------------------------------------------- cloned replica

export async function checkReplica(root: string, knownReplicas: Record<string, string>, platform: NodeJS.Platform = process.platform): Promise<'ok' | 'cloned'> {
  const opened = await openKeepfinFolder(root)
  if (!opened.ok) throw new Error(`Cannot check the replica: ${opened.reason}.`)
  const known = knownReplicas[opened.folder.replicaId]
  if (known === undefined) return 'ok'
  return normalizePath(known, platform) === normalizePath(root, platform) ? 'ok' : 'cloned'
}

export async function reassignReplica(root: string, newReplicaId: string, now: string): Promise<KeepfinFolder> {
  const opened = await openKeepfinFolder(root)
  if (!opened.ok) throw new Error(`Cannot reassign the replica: ${opened.reason}.`)
  const previousReplicaId = opened.folder.replicaId
  const {root: _root, ...json} = opened.folder
  const libraries = json.libraries?.map((l) => (l.role === 'active' ? {...l, replicaId: newReplicaId} : l))
  await writeJsonAtomic(folderJsonPath(root), {...json, replicaId: newReplicaId, ...(libraries ? {libraries} : {})})
  await writeJsonAtomic(path.join(recordsDir(opened.folder), 'actors', `clone-${newReplicaId}.json`), {previousReplicaId, at: now})
  return {...opened.folder, replicaId: newReplicaId}
}
