// The pure helpers of the copies host: names, the problem and preview shapes, and preparing a folder as a copy.
import fs from 'node:fs/promises'
import path from 'node:path'
import type {CopyPlanLine, CopyPreview, CopyProblem, CopyReviewItem} from '../../shared/copies.js'
import {problemGroups} from '../../shared/core/copies.js'
import type {RunSummary} from '../copies/placeRun.js'
import {writeJsonAtomic} from '../store/atomic.js'
import {hideOnWindows, openKeepfinFolder} from '../store/keepfinFolder.js'

const PREVIEW_DIFFERENCES = 20

const KIND_LINES: [string, string][] = [
  ['CopyTo', 'Put on the copy'],
  ['MoveOnCopy', 'Rename on the copy'],
  ['RemoveFromCopy', 'Remove from the copy'],
  ['ImportNew', 'Add to your library from the copy'],
  ['ReviseFromCopy', 'Update your library from the copy'],
  ['RestoreFromCopy', 'Bring back from the copy'],
  ['RestoreEditedDeleted', 'Restore an edited file for review'],
  ['DeleteItem', 'Delete from your library (you confirmed)'],
]

export const sameFolder = (a: string, b: string) => (process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b)
export const inside = (child: string, parent: string) => {
  const c = process.platform === 'win32' ? child.toLowerCase() : child
  const p = process.platform === 'win32' ? parent.toLowerCase() : parent
  return c === p || c.startsWith(p.endsWith(path.sep) ? p : p + path.sep)
}

export async function winDrives(): Promise<string[]> {
  if (process.platform !== 'win32') return []
  const out: string[] = []
  await Promise.all(
    'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('').map(async (letter) => {
      const root = `${letter}:\\`
      try {
        await fs.stat(root)
        out.push(root)
      } catch {
        // not there
      }
    }),
  )
  return out.sort()
}

export function itemOf(r: CopyReviewItem): string | undefined {
  return r.itemId
}

export function nameOf(libraryPath: string): string {
  const i = libraryPath.lastIndexOf('/')
  return i >= 0 ? libraryPath.slice(i + 1) : libraryPath
}

export function suggestName(target: string): string {
  const root = path.parse(target).root
  const base = path.basename(target)
  if (/^[A-Za-z]:\\?$/.test(target) || !base) return `Drive (${root.replace(/\\$/, '')})`
  // the Keepfin folder made inside a picked folder is named after that folder (F:\Keepfin is "Drive (F:)")
  if (base.toLowerCase() === 'keepfin' && path.dirname(target) !== target) return suggestName(path.dirname(target))
  return base
}

/** The files that could not be copied at the last sync, as the copy shows them: a plain reason each, never a code or a temporary name. */
export function problemOf(last: RunSummary): CopyProblem | undefined {
  const count = Math.max(last.failedCount ?? 0, last.failed.length)
  if (count === 0) return undefined
  return {count, reasons: problemGroups(last.failed.map((f) => f.reason)), files: last.failed.map((f) => f.path), more: Math.max(0, count - last.failed.length)}
}

/** The summary of a cloud run that did not start: the sign-in is refused or the account cannot be reached. */
export function cloudStopped(status: RunSummary['status'], message: string, state: NonNullable<RunSummary['cloud']>['state'], now: number): RunSummary {
  return {
    status,
    reason: message,
    rounds: 0,
    generation: {copy: null, relationship: null, state: 'equal', committedAt: null},
    summary: null,
    held: false,
    holdReason: null,
    additiveOnly: false,
    reviewCount: 0,
    review: [],
    committed: 0,
    skippedCount: 0,
    failedCount: 0,
    skipped: [],
    failed: [],
    bytes: 0,
    manifest: null,
    hashed: 0,
    finishedAt: now,
    importsToLibrary: 0,
    cloud: {state, message, downloaded: 0, uploaded: 0, replaced: 0, moved: 0, bytesDown: 0, bytesUp: 0, conflicts: 0, notes: []},
  }
}

export function emptyPreview(placeId: string, status: CopyPreview['status'], reason: string): CopyPreview {
  return {placeId, status, reason, additiveOnly: true, lines: [], copyFiles: 0, copyBytes: 0, destructive: 0, differences: [], differencesMore: 0, enough: null, importsToLibrary: 0}
}

export function previewFrom(placeId: string, s: RunSummary): CopyPreview {
  if (s.status === 'refused') return emptyPreview(placeId, 'refused', s.reason ?? 'This folder cannot be used.')
  if (s.status === 'disconnected') return emptyPreview(placeId, 'disconnected', s.reason ?? 'The drive is not connected.')
  if (s.status === 'busy') return emptyPreview(placeId, 'busy', s.reason ?? 'Another run holds this copy.')
  const counts = s.summary?.counts ?? {}
  const bytes = s.summary?.bytes ?? {}
  const lines: CopyPlanLine[] = []
  for (const [kind, label] of KIND_LINES) {
    const n = counts[kind as keyof typeof counts] ?? 0
    if (n > 0) lines.push({label, files: n, bytes: bytes[kind as keyof typeof bytes] ?? 0})
  }
  const diffs = s.review.map((r) => ({path: r.path, detail: r.detail || r.kind}))
  return {
    placeId,
    status: s.held ? 'held' : 'ready',
    ...(s.holdReason ? {reason: s.holdReason} : {}),
    additiveOnly: s.additiveOnly,
    lines,
    copyFiles: counts.CopyTo ?? 0,
    copyBytes: bytes.CopyTo ?? 0,
    destructive: s.summary?.destructive ?? 0,
    differences: diffs.slice(0, PREVIEW_DIFFERENCES),
    differencesMore: Math.max(0, s.reviewCount - PREVIEW_DIFFERENCES),
    ...(s.summary?.copySpace.free != null ? {freeBytes: s.summary.copySpace.free} : {}),
    enough: s.summary?.copySpace.enough ?? null,
    importsToLibrary: s.importsToLibrary,
  }
}

/** Makes a folder a Keepfin folder of this library (the copy's own replica id, generation 0) unless it already is one. */
export async function prepareCopyFolder(root: string, libraryId: string, newId: () => string, now: string): Promise<{replicaId: string; created: boolean; madeRoot?: boolean}> {
  const opened = await openKeepfinFolder(root)
  if (opened.ok) {
    if (opened.folder.libraryId !== libraryId) throw new Error('This folder belongs to a different Keepfin library.')
    return {replicaId: opened.folder.replicaId, created: false}
  }
  if (opened.reason !== 'not-a-keepfin-folder' && opened.reason !== 'missing') throw new Error('This folder cannot be used as a copy.')
  const data = path.join(root, '.keepfin')
  const madeRoot = opened.reason === 'missing'
  await fs.mkdir(root, {recursive: true})
  for (const sub of ['ops', 'checkpoints', 'art', 'versions', 'trash', 'places']) await fs.mkdir(path.join(data, sub), {recursive: true})
  const replicaId = newId()
  await writeJsonAtomic(path.join(data, 'folder.json'), {format: 1, libraryId, replicaId, createdAt: now, generation: 0})
  await fs.writeFile(path.join(data, 'README.txt'), "A copy of a Keepfin library's files. Do not edit; deleting it loses earlier versions.", 'utf8')
  await hideOnWindows(data).catch(() => undefined)
  return {replicaId, created: true, madeRoot}
}

/**
 * Removes `.keepfin` from a folder the wizard prepared, but only while it holds nothing but what the wizard put there; a
 * folder the wizard made itself ([madeRoot]) goes too while it is empty.
 */
export async function removeFreshCopyFolder(root: string, madeRoot = false): Promise<void> {
  const data = path.join(root, '.keepfin')
  const allowed = new Set(['ops', 'checkpoints', 'art', 'versions', 'trash', 'places', 'folder.json', 'README.txt', 'lock'])
  const names = await fs.readdir(data).catch(() => undefined)
  if (!names || names.some((n) => !allowed.has(n))) return
  for (const sub of ['ops', 'checkpoints', 'art', 'versions', 'trash', 'places']) {
    if ((await fs.readdir(path.join(data, sub)).catch(() => ['x'])).length > 0) return
  }
  await fs.rm(data, {recursive: true, force: true})
  if (madeRoot) await fs.rmdir(root).catch(() => undefined)
}
