// Inventories (spec section 3): the sorted stream of (relativePath, size, mtimeMs, fileId?) for one root, written as JSON
// lines on disk in the order the planner needs ((name key, path)). A directory walk does not come out in that order, so
// the lines go through a bounded-memory external sort. The complete marker is written only when every folder was read.
import fs from 'node:fs/promises'
import path from 'node:path'
import type {NameRulesId} from '../../shared/core/copies.js'
import {fastKey} from '../../shared/core/copies.js'
import {ExternalSorter, LineWriter} from './lines.js'
import type {NameMap} from './names.js'
import {toStat} from './fsops.js'
import {code, DisconnectedError, rootGone} from './util.js'

const SKIPPED_ROOT_DIRS = new Set(['.keepfin', 'keepfin exports'])
const SKIPPED_FILES = new Set(['desktop.ini', 'thumbs.db', '.ds_store'])

/** Temporary files, partial downloads and system files are not part of a library (spec 3). */
export function skippedFile(name: string): boolean {
  const lower = name.toLowerCase()
  return lower.includes('.kftmp') || lower.endsWith('.part') || name.startsWith('~$') || SKIPPED_FILES.has(lower)
}

export interface InventoryResult {
  file: string
  count: number
  /** Every folder was read. Anything else is principle 5: no destructive action for this place. */
  complete: boolean
  /** Folders or files that could not be read. */
  unreadable: string[]
  startedAtMs: number
}

export interface ScanOptions {
  rules: NameRulesId
  /** Scratch space for the sort runs. */
  tmpDir: string
  /** Translates physical names back to library paths (the copy side). */
  names?: NameMap
  /** Called at each file; return false to leave it out (derived exports etc.). */
  filter?: (relative: string) => boolean
  signal?: AbortSignal
  chunk?: number
  statConcurrency?: number
}

/** Scans [root] into the sorted JSON-lines file [outFile]. A root that cannot be read at all is a disconnect. */
export async function scanInventory(root: string, outFile: string, options: ScanOptions): Promise<InventoryResult> {
  const startedAtMs = Date.now()
  const unreadable: string[] = []
  const sorter = new ExternalSorter(options.tmpDir, options.chunk ?? 50_000, 'inv')
  let seq = 0
  const stack: string[] = ['']
  while (stack.length > 0) {
    if (options.signal?.aborted) throw new Error('aborted')
    const rel = stack.pop()!
    const abs = rel ? path.join(root, ...rel.split('/')) : root
    let entries: import('node:fs').Dirent[]
    try {
      entries = await fs.readdir(abs, {withFileTypes: true})
    } catch (error) {
      if (rel === '' && (code(error) === 'ENOENT' || code(error) === 'EIO' || code(error) === 'ENOTDIR')) throw new DisconnectedError(root, error)
      if (rel === '' && (await rootGone(root))) throw new DisconnectedError(root, error)
      unreadable.push(rel || '.')
      continue
    }
    const files: string[] = []
    for (const e of entries) {
      const name = e.name
      if (e.isSymbolicLink()) continue
      if (e.isDirectory()) {
        if (rel === '' && SKIPPED_ROOT_DIRS.has(name.toLowerCase())) continue
        stack.push(rel ? `${rel}/${name}` : name)
      } else if (e.isFile()) {
        if (skippedFile(name)) continue
        files.push(name)
      }
    }
    const width = options.statConcurrency ?? 64
    for (let i = 0; i < files.length; i += width) {
      const slice = files.slice(i, i + width)
      const stats = await Promise.all(
        slice.map(async (name) => {
          try {
            return toStat(await fs.lstat(path.join(abs, name), {bigint: true}))
          } catch (error) {
            if (code(error) !== 'ENOENT') unreadable.push(rel ? `${rel}/${name}` : name)
            return null
          }
        }),
      )
      for (let j = 0; j < slice.length; j++) {
        const st = stats[j]
        if (!st) continue
        const physical = rel ? `${rel}/${slice[j]}` : slice[j]
        const libPath = options.names ? options.names.library(physical) : physical
        if (options.filter && !options.filter(libPath)) continue
        const text = JSON.stringify(st.fileId ? {path: libPath, size: st.size, mtimeMs: st.mtimeMs, fileId: st.fileId} : {path: libPath, size: st.size, mtimeMs: st.mtimeMs})
        await sorter.add({key: fastKey(options.rules, libPath), path: libPath, seq: seq++, text})
      }
    }
  }
  const tmp = `${outFile}.${process.pid}.tmp`
  const writer = await LineWriter.create(tmp)
  let count = 0
  for await (const rec of sorter.sorted()) {
    await writer.write(rec.text)
    count++
  }
  await writer.close()
  await fs.rename(tmp, outFile)
  const complete = unreadable.length === 0
  if (complete) await fs.writeFile(`${outFile}.complete`, String(count), 'utf8')
  else await fs.rm(`${outFile}.complete`, {force: true})
  return {file: outFile, count, complete, unreadable, startedAtMs}
}

/**
 * Probes the name-equivalence rule of the volume holding [dir] once: `exact` when `A` and `a` are different files,
 * `ascii` when they are the same file (NTFS, FAT and exFAT by default, APFS case-insensitive). Unreachable probes are
 * treated conservatively as `ascii`. Unicode equivalence is not probed (NFC is used only to detect collisions, spec 3).
 */
export async function probeNameRules(dir: string): Promise<NameRulesId> {
  const base = path.join(dir, '.keepfin')
  const upper = path.join(base, `kf-probe-${process.pid}-${Date.now()}-A.tmp`)
  const lower = upper.replace(/-A\.tmp$/, '-a.tmp')
  try {
    await fs.mkdir(base, {recursive: true})
    await fs.writeFile(upper, '')
    try {
      await fs.lstat(lower)
      return 'ascii'
    } catch {
      return 'exact'
    }
  } catch {
    return 'ascii'
  } finally {
    await fs.rm(upper, {force: true}).catch(() => undefined)
  }
}

/** The complete marker of an inventory file, as written by [scanInventory]. */
export async function inventoryComplete(file: string): Promise<boolean> {
  try {
    await fs.access(`${file}.complete`)
    return true
  } catch {
    return false
  }
}
