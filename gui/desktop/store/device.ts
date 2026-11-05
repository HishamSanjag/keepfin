// Device-only library data and the mapping between absolute paths and synced placements
// (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md sections 1 and 3).
//
// Synced: a placement is a '/'-separated path below the Keepfin folder (`Music/Album/a.mp3`). A space kept outside the
// Keepfin folder ("linked") has a virtual folder name instead, and its real folder lives only in linked.json.
// Device-only: flags and caches per item on this replica (placement-state.json) and linked.json.
import path from 'node:path'
import fs from 'node:fs/promises'
import {renameWithRetry} from '../fsutil.js'
import {readJson} from './atomic.js'
import {Slicer} from './slices.js'

// ---------------------------------------------------------------------------------------------------------------------
// Paths
// ---------------------------------------------------------------------------------------------------------------------

/** One folder space as the path mapping sees it. */
export type SpaceRoot = {
  id: string
  /** The synced folder: relative to the Keepfin folder, or the virtual name of a linked space. */
  rel: string
  /** The folder on this device ('' when unknown on this device). */
  abs: string
  linked: boolean
}

const fold = (file: string) => (process.platform === 'win32' ? path.normalize(file).toLowerCase() : path.normalize(file))

/** Whether `file` is below `folder` (equal is not inside). */
export function isBelow(folder: string, file: string): boolean {
  if (!folder) return false
  const relative = path.relative(fold(folder), fold(file))
  return !!relative && !relative.startsWith('..') && !path.isAbsolute(relative)
}

/** A relative path below `folder`, '/'-separated, or undefined when `file` is not below it. */
export function relativeBelow(folder: string, file: string): string | undefined {
  if (!isBelow(folder, file)) return undefined
  return path.relative(path.normalize(folder), path.normalize(file)).split(path.sep).join('/')
}

const MAX_PLACEMENT = 1024

/** Whether text is a legal placement (sync spec 4.1): relative, '/'-separated, no empty, `.` or `..` segment. */
export function validPlacement(value: string): boolean {
  if (!value || value.length > MAX_PLACEMENT || value.startsWith('/') || value.includes('\\')) return false
  return value.split('/').every((segment) => segment !== '' && segment !== '.' && segment !== '..')
}

/** The synced placement for an absolute path, or null when it is outside the Keepfin folder and every linked space. */
export function placementOf(abs: string, root: string, spaces: readonly SpaceRoot[]): string | null {
  let best: SpaceRoot | undefined
  for (const space of spaces) {
    if (!space.linked || !isBelow(space.abs, abs)) continue
    if (!best || space.abs.length > best.abs.length) best = space
  }
  const text = best ? `${best.rel}/${relativeBelow(best.abs, abs)}` : relativeBelow(root, abs)
  return text && validPlacement(text) ? text : null
}

/** The absolute path for a placement on this device, or undefined when it points into a linked space that is unknown here. */
export function resolvePlacement(placement: string, root: string, spaces: readonly SpaceRoot[]): string | undefined {
  if (!validPlacement(placement)) return undefined
  let best: SpaceRoot | undefined
  for (const space of spaces) {
    if (!space.linked) continue
    if (placement !== space.rel && !placement.startsWith(`${space.rel}/`)) continue
    if (!best || space.rel.length > best.rel.length) best = space
  }
  if (best) {
    if (!best.abs) return undefined
    return path.join(best.abs, ...placement.slice(best.rel.length + 1).split('/').filter(Boolean))
  }
  return path.join(root, ...placement.split('/'))
}

/** Where an item with no placement on this device is shown: a path that never exists. */
export function unplacedPath(root: string, id: string): string {
  return path.join(root, '.keepfin', 'unplaced', id)
}

// ---------------------------------------------------------------------------------------------------------------------
// Device-only data
// ---------------------------------------------------------------------------------------------------------------------

/** Per item on this replica: the flags and caches that never sync. */
export type DeviceItem = {
  missing?: boolean
  size?: number
  modifiedAt?: number
  jobId?: string
  /** `${size}:${modifiedAt}` the metadata was last read for. */
  sig?: string
  subPaths?: string[]
  lrc?: string
  infoJson?: string
  /** The song's music video file (Song.video.mp4) beside it. */
  videoPath?: string
  analysed?: boolean
  hash?: string
  colIndex?: number
  tagsPending?: boolean
}

/** The keys of a library entry that live in DeviceItem. */
export const DEVICE_ITEM_KEYS = ['missing', 'size', 'modifiedAt', 'jobId', 'sig', 'subPaths', 'lrc', 'infoJson', 'videoPath', 'analysed', 'hash', 'colIndex', 'tagsPending'] as const

/** Per collection on this replica: follow-check results. */
export type DeviceCollection = {lastCheckedAt?: number; lastError?: string}

export type FileCheck = {at: number; mismatched: number}

export type DeviceData = {
  /** Place id of this replica (folder.json). */
  replicaId: string
  /** The Keepfin folder on this device. */
  root: string
  /** Linked spaces: space id to its folder on this device. */
  linked: Record<string, string>
  /** This device's record of each item: a plain object keyed by id (tests, the worker) or a Map (the session, which holds 200,000). */
  items: Record<string, DeviceItem> | ReadonlyMap<string, DeviceItem>
  collections: Record<string, DeviceCollection>
  /** Ids that have saved art in .keepfin/art. */
  art: ReadonlySet<string>
  check?: FileCheck
}

/** One item's record from either kind of lookup. */
export const deviceRecord = (items: DeviceData['items'], id: string): DeviceItem | undefined => (items instanceof Map ? items.get(id) : (items as Record<string, DeviceItem>)[id])

const STATE_FILE = 'placement-state.json'
const SHARD_DIR = 'placement-state'
const LINKED_FILE = 'linked.json'

type StateFile = {version: 1 | 2; items?: Record<string, DeviceItem>; collections: Record<string, DeviceCollection>; check?: FileCheck}

const shardOf = (id: string) => (/^[0-9a-f]/.test(id) ? id[0] : '_')

async function writeCompact(file: string, value: unknown): Promise<void> {
  await fs.mkdir(path.dirname(file), {recursive: true})
  const temp = `${file}.${process.pid}.${Math.random().toString(36).slice(2)}.tmp`
  await fs.writeFile(temp, JSON.stringify(value))
  try {
    await renameWithRetry(temp, file)
  } catch (error) {
    await fs.rm(temp, {force: true}).catch(() => undefined)
    throw error
  }
}

/**
 * placement-state (per-item records in 17 shard files by the first character of the id), collections and the check
 * result in placement-state.json, and linked.json, in a profile's device folder. Records are updated one at a time; only
 * the shards that changed are rewritten, at most every `delay` ms (and on flush).
 */
export class DeviceFiles {
  /** Per item, by id. A Map: 200,000 records in a dictionary-mode object cost twice as much (and a second copy of every id). */
  items = new Map<string, DeviceItem>()
  collections: Record<string, DeviceCollection> = {}
  linked: Record<string, string> = {}
  check?: FileCheck
  private savedLinked = ''
  private readonly dirtyShards = new Set<string>()
  private metaDirty = false
  private timer?: NodeJS.Timeout
  private writing: Promise<void> = Promise.resolve()

  constructor(
    private readonly dir: string,
    private readonly delay = 2000,
  ) {}

  async load(): Promise<void> {
    const state = await readJson<StateFile>(path.join(this.dir, STATE_FILE)).catch(() => undefined)
    if (state && typeof state === 'object') {
      // A version 1 file held every item record; they move into shards the next time anything changes.
      if (state.items && typeof state.items === 'object') {
        for (const [id, record] of Object.entries(state.items)) {
          this.items.set(id, record)
          this.dirtyShards.add(shardOf(id))
        }
        this.metaDirty = true
      }
      this.collections = state.collections && typeof state.collections === 'object' ? state.collections : {}
      this.check = state.check
    }
    // The shard files are read at the same time, but parsed and merged one at a time (in name order) with the thread given back
    // in between: 200,000 records parsed in one turn of the event loop block it for a tenth of a second.
    const names = (await fs.readdir(path.join(this.dir, SHARD_DIR)).catch(() => [] as string[])).filter((name) => name.endsWith('.json')).sort()
    const texts = names.map((name) => fs.readFile(path.join(this.dir, SHARD_DIR, name), 'utf8').catch(() => undefined))
    const linkedLoad = readJson<Record<string, unknown>>(path.join(this.dir, LINKED_FILE)).catch(() => undefined)
    const slicer = new Slicer()
    for (const pending of texts) {
      const text = await pending
      let part: unknown
      try {
        part = text === undefined ? undefined : JSON.parse(text)
      } catch {
        part = undefined
      }
      if (text !== undefined) await slicer.tick()
      if (part && typeof part === 'object') {
        const entries = Object.entries(part as Record<string, DeviceItem>)
        for (let i = 0; i < entries.length; i++) {
          this.items.set(entries[i][0], entries[i][1])
          if ((i & 255) === 255) await slicer.tick()
        }
      }
    }
    const linked = await linkedLoad
    if (linked && typeof linked === 'object') {
      this.linked = {}
      for (const [id, folder] of Object.entries(linked)) if (typeof folder === 'string') this.linked[id] = folder
      this.savedLinked = JSON.stringify(this.linked)
    }
    if (this.dirtyShards.size) this.schedule()
  }
  /** Sets or removes (null) one item's record. Only a real change marks its shard. */
  setItem(id: string, record: DeviceItem | null) {
    const was = this.items.get(id)
    if (record === null || Object.keys(record).length === 0) {
      if (was === undefined) return
      this.items.delete(id)
    } else {
      if (was !== undefined && JSON.stringify(was) === JSON.stringify(record)) return
      this.items.set(id, record)
    }
    this.dirtyShards.add(shardOf(id))
    this.schedule()
  }

  setCollection(id: string, record: DeviceCollection | null) {
    const was = this.collections[id]
    if (record === null) {
      if (was === undefined) return
      delete this.collections[id]
    } else {
      if (was !== undefined && JSON.stringify(was) === JSON.stringify(record)) return
      this.collections[id] = record
    }
    this.metaDirty = true
    this.schedule()
  }

  setCheck(check: FileCheck) {
    this.check = check
    this.metaDirty = true
    this.schedule()
  }

  /** Replaces everything (a full diff after the spaces changed). */
  replaceAll(items: Map<string, DeviceItem>, collections: Record<string, DeviceCollection>) {
    for (const id of this.items.keys()) this.dirtyShards.add(shardOf(id))
    for (const id of items.keys()) this.dirtyShards.add(shardOf(id))
    this.items = items
    this.collections = collections
    this.metaDirty = true
    this.schedule()
  }

  private schedule() {
    if (this.timer) return
    this.timer = setTimeout(() => {
      this.timer = undefined
      void this.flush().catch((error) => console.error('Could not save device library data:', error))
    }, this.delay)
    this.timer.unref?.()
  }

  /** Writes the changed shards now (and waits for any write in flight). */
  flush(): Promise<void> {
    clearTimeout(this.timer)
    this.timer = undefined
    const run = this.writing.then(() => this.write())
    this.writing = run.catch(() => undefined)
    return run
  }

  private async write() {
    const shards = [...this.dirtyShards]
    this.dirtyShards.clear()
    const meta = this.metaDirty
    this.metaDirty = false
    try {
      if (shards.length) {
        const parts = new Map<string, Record<string, DeviceItem>>(shards.map((shard) => [shard, {}]))
        for (const [id, record] of this.items) {
          const part = parts.get(shardOf(id))
          if (part) part[id] = record
        }
        for (const [shard, part] of parts) await writeCompact(path.join(this.dir, SHARD_DIR, `${shard}.json`), part)
      }
      if (meta) await writeCompact(path.join(this.dir, STATE_FILE), {version: 2, collections: this.collections, ...(this.check ? {check: this.check} : {})} satisfies StateFile)
      const linked = JSON.stringify(this.linked)
      if (linked !== this.savedLinked) {
        await writeCompact(path.join(this.dir, LINKED_FILE), this.linked)
        this.savedLinked = linked
      }
    } catch (error) {
      for (const shard of shards) this.dirtyShards.add(shard)
      if (meta) this.metaDirty = true
      throw error
    }
  }

  /** Kept for callers that only changed `linked`. */
  save(): Promise<void> {
    return this.flush()
  }
}
