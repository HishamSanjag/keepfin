// Earlier versions and the trash of a copy (spec 5.5). Nothing is deleted outright: displaced bytes move to
// `.keepfin/versions/<itemId>/<revisionOp>-<contentId prefix>.<ext>` (backups) or `.keepfin/trash/<date>/<run>-<n>/<path>`
// (mirrors and two-way copies, kept 30 days), each with a catalogue line written BEFORE the move.
import fs from 'node:fs/promises'
import path from 'node:path'
import {appendLineDurable} from '../store/atomic.js'
import {lineReader} from './lines.js'
import {moveNoReplace} from './fsops.js'
import {safeComponent} from './names.js'
import {code, exists, splitExt, withCounter} from './util.js'

export type Store = 'versions' | 'trash'
export const TRASH_DAYS = 30
const DAY = 86_400_000

export interface CatalogueEntry {
  id: string
  store: Store
  itemId: string | null
  revision: string | null
  /** The library path the bytes had. */
  path: string
  contentId: string
  /** Relative to the copy's `.keepfin`. */
  file: string
  archivedAt: number
  /** replaced, restored, edited, removed, deleted */
  why: string
  run: string
}

const keepfin = (copyRoot: string) => path.join(copyRoot, '.keepfin')
export const catalogueFile = (copyRoot: string, store: Store) =>
  store === 'versions' ? path.join(keepfin(copyRoot), 'versions', 'catalogue.jsonl') : path.join(keepfin(copyRoot), 'trash', 'index.jsonl')

export interface ArchiveInfo {
  itemId: string | null
  revision: string | null
  contentId: string
  /** library path */
  path: string
  /** physical relative path on the copy (used for the trash layout) */
  physical: string
  run: string
  n: number
  at: number
  why: string
}

/** Chooses the archive file (never an existing one) and the catalogue entry for it. Nothing is written yet. */
export async function planArchive(copyRoot: string, store: Store, info: ArchiveInfo): Promise<{entry: CatalogueEntry; absolute: string}> {
  let rel: string
  if (store === 'versions') {
    const {ext} = splitExt(info.physical)
    const prefix = info.contentId.replace(/^sha256:/, '').slice(0, 12)
    const dir = safeComponent(info.itemId ?? '_unknown')
    rel = `versions/${dir}/${safeComponent(info.revision ?? 'copy')}-${prefix}${ext}`
  } else {
    const day = new Date(info.at).toISOString().slice(0, 10)
    rel = `trash/${day}/${info.run}-${info.n}/${info.physical}`
  }
  let n = 1
  let candidate = rel
  while (await exists(path.join(keepfin(copyRoot), ...candidate.split('/')))) {
    n++
    candidate = withCounter(rel, n)
  }
  const entry: CatalogueEntry = {
    id: `${info.run}-${info.n}`,
    store,
    itemId: info.itemId,
    revision: info.revision,
    path: info.path,
    contentId: info.contentId,
    file: candidate,
    archivedAt: info.at,
    why: info.why,
    run: info.run,
  }
  return {entry, absolute: path.join(keepfin(copyRoot), ...candidate.split('/'))}
}

/** The durable catalogue line (written before the bytes move). */
export function appendCatalogue(copyRoot: string, entry: CatalogueEntry): Promise<void> {
  return appendLineDurable(catalogueFile(copyRoot, entry.store), JSON.stringify(entry))
}

/** Marks a catalogue entry as never having happened (the move did not take place). */
export function voidCatalogue(copyRoot: string, store: Store, id: string): Promise<void> {
  return appendLineDurable(catalogueFile(copyRoot, store), JSON.stringify({void: id}))
}

/** Moves displaced bytes into the archive, never replacing anything. */
export async function moveToArchive(from: string, to: string): Promise<void> {
  await moveNoReplace(from, to)
}

/** The live entries of a catalogue (voided and expired ones removed), streamed. */
export function* liveEntries(copyRoot: string, store: Store): Generator<CatalogueEntry> {
  const read = lineReader(catalogueFile(copyRoot, store))
  const dead = new Set<string>()
  const all: CatalogueEntry[] = []
  try {
    for (let line = read(); line !== null; line = read()) {
      try {
        const v = JSON.parse(line) as Partial<CatalogueEntry> & {void?: string; gone?: string}
        if (v.void) dead.add(v.void)
        else if (v.gone) dead.add(v.gone)
        else if (v.id && v.file) all.push(v as CatalogueEntry)
      } catch {
        // torn tail
      }
    }
  } finally {
    read.close()
  }
  for (const e of all) if (!dead.has(e.id)) yield e
}

async function expire(copyRoot: string, store: Store, due: (e: CatalogueEntry) => boolean): Promise<number> {
  let count = 0
  const gone: string[] = []
  for (const e of liveEntries(copyRoot, store)) {
    if (!due(e)) continue
    try {
      await fs.rm(path.join(keepfin(copyRoot), ...e.file.split('/')), {force: true})
    } catch (error) {
      if (code(error) !== 'ENOENT') continue
    }
    gone.push(e.id)
    count++
  }
  for (const id of gone) await appendLineDurable(catalogueFile(copyRoot, store), JSON.stringify({gone: id}))
  return count
}

/** The one place bytes are removed for good: trash older than 30 days. */
export function purgeTrash(copyRoot: string, now: number, days = TRASH_DAYS): Promise<number> {
  return expire(copyRoot, 'trash', (e) => now - e.archivedAt >= days * DAY)
}

/**
 * Replaced versions older than [keepDays] (default 90). Deleted items are kept until an explicit Clean up, and
 * unresolved conflicts are never expired.
 */
export function pruneVersions(copyRoot: string, now: number, keepDays = 90): Promise<number> {
  return expire(copyRoot, 'versions', (e) => e.why === 'replaced' && now - e.archivedAt >= keepDays * DAY)
}

/** An explicit Clean up of everything archived for items that were deleted. */
export function cleanUpDeleted(copyRoot: string): Promise<number> {
  return expire(copyRoot, 'versions', (e) => e.why === 'deleted' || e.why === 'removed')
}
