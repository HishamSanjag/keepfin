// One sync of one place as the app runs it: the desired set from the library's SQL state, the executor, and the work around a
// finished run (earlier versions pruned, the library's metadata copied to the copy). Runs in the copies worker thread; the
// end-to-end test calls it directly with a host over the same temp folders.
import crypto from 'node:crypto'
import {DatabaseSync} from 'node:sqlite'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {PlanSummary, ReviewItem} from '../../shared/core/copies.js'
import {deletedItems, desiredEntries, holdsKey, optionsKey, placeMusicVideos, type PlaceConfig} from '../sqlstate/desired.js'
import {heldSmart} from '../sqlstate/smart.js'
import {readJson, writeJsonAtomic} from '../store/atomic.js'
import {coveredByName, listOps, pruneCoveredSegments} from './segmentPrune.js'
import type {MainHost, Progress, RemoteTally} from './executor.js'
import type {RemoteMedia, RemoteStopState} from './remote.js'
import {runCopy, type RunReport} from './run.js'
import {compactCatalogues} from './catalogueCompact.js'
import {purgeTrash, pruneVersions} from './versions.js'
import {exportsFile, syncExports, type ExportsReport} from './exports.js'
import {exists} from './util.js'

export interface PlaceRunJob {
  dbPath: string
  mainRoot: string
  copyRoot: string
  stateDir: string
  place: PlaceConfig
  mainReplica: string
  device: string
  dryRun?: boolean
  previewConfirmed?: boolean
  confirmHeld?: boolean
  audit?: boolean
  confirmedDeletes?: string[]
  parallelism?: number
  /** Free bytes on the copy side for the plan's space check, when the volume under copyRoot is not the whole story (a cloud account). */
  freeCopy?: number
  /** The audio converter for MP3 exports (the engine's), when there is one. */
  ffmpeg?: string
  /** The copy is an account or a device: [copyRoot] then holds only the copy's own .keepfin data and the media go through this. */
  remote?: RemoteMedia
  /** Where a remote copy stages the one file in flight (default: staging in [stateDir]). */
  staging?: string
  now?: () => number
}

/** What a cloud copy adds to a run's summary: what the cloud side did, and the one thing the user may need to do about it. */
export interface CloudRunInfo {
  /** 
eedsSignIn: the provider refused the refresh. offline: the account could not be reached. quota: the account is full. */
  state: 'ok' | 'needsSignIn' | 'offline' | 'quota' | 'incomplete' | 'busy' | 'unavailable'
  message: string
  downloaded: number
  uploaded: number
  replaced: number
  moved: number
  bytesDown: number
  bytesUp: number
  conflicts: number
  /** Remote names this computer cannot hold as files, name collisions on the account, unlisted folders. */
  notes: string[]
}

/** What the host keeps of a run: small, JSON, no inventories. */
export interface RunSummary {
  status: RunReport['status']
  reason?: string
  rounds: number
  generation: RunReport['generation']
  summary: PlanSummary | null
  held: boolean
  holdReason: string | null
  additiveOnly: boolean
  reviewCount: number
  review: ReviewItem[]
  committed: number
  skippedCount: number
  failedCount: number
  skipped: {kind: string; path: string; reason: string}[]
  failed: {kind: string; path: string; reason: string}[]
  bytes: number
  manifest: {n: number; sha256: string; files: number} | null
  hashed: number
  finishedAt: number
  /** Importing to the library from a preview: files only the copy has. */
  importsToLibrary: number
  /** Cloud copies only. */
  cloud?: CloudRunInfo
  /** Derived exports made after the run (folder copies). */
  exports?: ExportsReport
  /** Copies on an account or a device: what the run did over the transport, and why it ended early. */
  remote?: RemoteTally
  remoteStop?: {state: RemoteStopState; message: string}
  /** Copies on an account or a device: names the provider cannot tell apart. */
  collisions?: string[]
}

export function summarise(report: RunReport, now: number): RunSummary {
  const counts = report.summary?.counts ?? {}
  return {
    status: report.status,
    reason: report.reason,
    rounds: report.rounds,
    generation: report.generation,
    summary: report.summary,
    held: report.held,
    holdReason: report.holdReason,
    additiveOnly: report.additiveOnly,
    reviewCount: report.reviewCount,
    review: report.review,
    committed: report.exec?.committed ?? 0,
    skippedCount: report.exec?.skippedCount ?? 0,
    failedCount: report.exec?.failedCount ?? 0,
    skipped: (report.exec?.skipped ?? []).slice(0, 50).map((s) => ({kind: s.kind, path: s.path, reason: s.reason})),
    failed: (report.exec?.failed ?? []).slice(0, 50).map((s) => ({kind: s.kind, path: s.path, reason: s.reason})),
    bytes: report.exec?.bytes ?? 0,
    manifest: report.manifest ? {n: report.manifest.n, sha256: report.manifest.sha256, files: report.manifest.files} : null,
    hashed: report.hashed,
    finishedAt: now,
    importsToLibrary: (counts.ImportNew ?? 0) + (counts.RestoreEditedDeleted ?? 0),
    ...(report.remote ? {remote: report.remote} : {}),
    ...(report.remoteStop ? {remoteStop: report.remoteStop} : {}),
    ...(report.collisions?.length ? {collisions: report.collisions.slice(0, 50)} : {}),
  }
}

/**
 * Copies the library's data folders (`ops`, `checkpoints`, `art`) to the copy, files it lacks or holds shorter. A segment file that a
 * larger file of the main folder covers is not copied. Nothing is deleted from the copy except segment files that a larger file on the
 * copy covers (op for op) and that the main folder no longer lists (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md R5, R6).
 */
export async function copyMetadata(mainRoot: string, copyRoot: string): Promise<number> {
  let copied = 0
  const mainOps = await listOps(path.join(mainRoot, '.keepfin', 'ops'))
  const covered = new Set(coveredByName(mainOps).map((entry) => entry.covered.file))
  for (const dir of ['ops', 'checkpoints', 'art']) {
    const from = path.join(mainRoot, '.keepfin', dir)
    if (!(await exists(from))) continue
    const stack = ['']
    while (stack.length) {
      const rel = stack.pop()!
      const entries = await fs.readdir(path.join(from, rel), {withFileTypes: true}).catch(() => [])
      for (const e of entries) {
        const r = rel ? `${rel}/${e.name}` : e.name
        if (e.isDirectory()) {
          stack.push(r)
          continue
        }
        if (e.name.endsWith('.tmp') || e.name.includes('.kftmp')) continue
        const src = path.join(from, ...r.split('/'))
        if (dir === 'ops' && covered.has(src)) continue
        const dst = path.join(copyRoot, '.keepfin', dir, ...r.split('/'))
        const [a, b] = await Promise.all([fs.stat(src).catch(() => null), fs.stat(dst).catch(() => null)])
        if (!a) continue
        if (b && b.size >= a.size) continue
        await fs.mkdir(path.dirname(dst), {recursive: true})
        const tmp = `${dst}.${process.pid}.${crypto.randomBytes(3).toString('hex')}.tmp`
        await fs.copyFile(src, tmp)
        await fs.rename(tmp, dst)
        copied++
      }
    }
  }
  // the copy's own pile: files a merged file on the copy covers, once the main folder has dropped them too
  const folder = await readJson<{libraryId?: string}>(path.join(mainRoot, '.keepfin', 'folder.json')).catch(() => undefined)
  if (folder?.libraryId) {
    const listed = new Set(mainOps.map((file) => `${file.actor}/${file.name}`))
    await pruneCoveredSegments(path.join(copyRoot, '.keepfin', 'ops'), folder.libraryId, (file) => listed.has(`${file.actor}/${file.name}`)).catch(() => 0)
  }
  return copied
}

export async function runPlace(job: PlaceRunJob, host: MainHost | undefined, onProgress?: (p: Progress) => void, signal?: AbortSignal): Promise<RunSummary> {
  const db = new DatabaseSync(job.dbPath, {readOnly: true})
  try {
    db.exec('PRAGMA query_only = 1')
    const now = job.now ?? Date.now
    const place = job.place
    // what the smart spaces this place holds gather, read once for the run
    const smart = heldSmart(db, place.holds)
    const report = await runCopy({
      mainRoot: job.mainRoot,
      copyRoot: job.copyRoot,
      stateDir: job.stateDir,
      placeId: place.id,
      mode: place.mode,
      device: job.device,
      desired: desiredEntries(db, job.mainReplica, place.holds, place.options.companions, placeMusicVideos(place), smart),
      deleted: deletedItems(db),
      confirmedDeletes: job.confirmedDeletes,
      holdsKey: holdsKey(place.holds),
      optionsKey: optionsKey(place.options),
      deleteGuardPercent: place.options.deleteGuardPercent,
      companions: place.options.companions,
      musicVideos: placeMusicVideos(place),
      previewConfirmed: job.previewConfirmed,
      confirmHeld: job.confirmHeld,
      dryRun: job.dryRun,
      audit: job.audit,
      host,
      // network latency, not the disk, is the wait on an account or a device; a local folder stays at 2 (spec 5.6: 2 on an HDD)
      parallelism: job.parallelism ?? (job.remote ? 4 : 2),
      freeCopy: job.freeCopy,
      onProgress,
      signal,
      now: job.now,
      remote: job.remote,
      staging: job.staging,
    })
    if (report.status === 'done' && !job.dryRun) {
      // earlier versions older than the retention are pruned; the trash is emptied after its 30 days
      // an account keeps what it displaced until the person cleans it up: nothing is deleted there by age
      if (!job.remote) {
        if (place.mode === 'backup') await pruneVersions(job.copyRoot, now(), place.options.versionsKeepDays).catch(() => 0)
        else await purgeTrash(job.copyRoot, now()).catch(() => 0)
        await compactCatalogues(job.copyRoot).catch(() => 0)
      }
      await copyMetadata(job.mainRoot, job.copyRoot).catch(() => 0)
    }
    const summary = summarise(report, now())
    if (report.status === 'done' && !job.dryRun && !job.remote && (place.options.playlistFiles || place.options.flat || place.options.mp3 || (await exists(exportsFile(job.copyRoot))))) {
      const made = await syncExports({db, copyRoot: job.copyRoot, place, entries: desiredEntries(db, job.mainReplica, place.holds, false, false, smart), ffmpeg: job.ffmpeg, signal}).catch((error) => ({written: 0, converted: 0, removed: 0, moved: 0, skipped: [{path: '', reason: error instanceof Error ? error.message : String(error)}]}))
      summary.exports = made
    }
    if (!job.dryRun) await writeJsonAtomic(path.join(job.stateDir, 'last-run.json'), summary).catch(() => undefined)
    return summary
  } finally {
    db.close()
  }
}
