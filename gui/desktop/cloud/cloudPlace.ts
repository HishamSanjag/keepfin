// One sync of a cloud copy (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md 7.3, KEEPFIN-COPIES-SPEC.md section 9): the same runner as for a folder, run
// directly over the account. The planner reads the account's streamed listing, and the executor's actions go through CloudTransport
// (an upload from the pinned local revision, a compare-and-swap or a move for what is displaced, a download to staging before an
// import), so this computer holds no copy of the replica: only the baseline, the journal, the inventory of the run and the one file in
// flight. In order:
//   1. the advisory lock on the replica (never the safety mechanism: the authority is append-only, spec 7.1 #1);
//   2. an older place that still has a mirror is settled once and the mirror is dropped (migrate.ts);
//   3. the copy's own .keepfin data is brought level with the replica (a small sync, mirror.ts with metaOnly);
//   4. the runner: inventory, plan, execute, baseline, manifest;
//   5. the copy's own data is sent, the new generation is appended as a record, and the lock is released.
// A sign-in that was refused, an account that cannot be reached, a full account and a listing that is not complete each end the run
// with a plain cloud.state and without touching anything on the replica that it cannot undo.
import path from 'node:path'
import {readGeneration, setGeneration} from '../copies/generation.js'
import type {Progress} from '../copies/executor.js'
import {runPlace, type CloudRunInfo, type PlaceRunJob, type RunSummary} from '../copies/placeRun.js'
import {readJson} from '../store/atomic.js'
import {CloudClient} from './client.js'
import {CloudHttp} from './http.js'
import type {CloudAccount, HttpOptions} from './http.js'
import {CloudError} from './http.js'
import {FileJournal} from './journal.js'
import {CloudRemote} from './cloudRemote.js'
import {friendlyCloudError, pull, push} from './mirror.js'
import {migrateLegacyMirror} from './migrate.js'
import type {MirrorOptions, MirrorProgress, PullReport, PushReport} from './mirror.js'
import {CloudTransport, KEEPFIN_DIR} from './transport.js'
import type {CloudProvider} from '../../shared/core/cloud.js'

/** Where a cloud copy lives; the account itself is a separate, device-local thing. */
export interface CloudPlaceSpec {
  provider: CloudProvider
  accountId: string
  /** WebDAV: the account's root collection URL. */
  base?: string
  /** The replica's folder on the account: `Keepfin/<name>` (WebDAV: the folder the user named). */
  rootPath: string
}

export interface CloudRunDeps {
  account: CloudAccount
  http?: HttpOptions
  /** Upload-session journal file (resumable uploads). */
  journalFile?: string
  /** A lock whose heartbeat is older than this is stale. */
  staleLockMs?: number
  heartbeatMs?: number
}

export interface Rig {
  http: CloudHttp
  client: CloudClient
  media: CloudTransport
  meta: CloudTransport
}

export function openRig(spec: CloudPlaceSpec, deps: CloudRunDeps): Rig {
  const http = new CloudHttp(deps.account, deps.http)
  const client = new CloudClient(http, {base: spec.base}, deps.journalFile ? new FileJournal(deps.journalFile) : undefined)
  const media = new CloudTransport({client, rootPath: spec.rootPath, staleLockMs: deps.staleLockMs})
  const meta = new CloudTransport({client, rootPath: `${spec.rootPath}/${KEEPFIN_DIR}`})
  return {http, client, media, meta}
}

const emptySummary = (status: RunSummary['status'], reason: string, cloud: CloudRunInfo, now: number): RunSummary => ({
  status,
  reason,
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
  cloud,
})

const info = (state: CloudRunInfo['state'], message: string, extra: Partial<CloudRunInfo> = {}): CloudRunInfo => ({state, message, downloaded: 0, uploaded: 0, replaced: 0, moved: 0, bytesDown: 0, bytesUp: 0, conflicts: 0, notes: [], ...extra})

/** What the user should be told for an error that ended the run, without the run having done any harm. */
export function describeFailure(error: unknown): {state: CloudRunInfo['state']; message: string} {
  if (error instanceof CloudError) {
    if (error.kind === 'NeedsSignIn') return {state: 'needsSignIn', message: 'The sign-in for this account has expired or was withdrawn. Sign in again to continue.'}
    if (error.kind === 'QuotaFull') return {state: 'quota', message: 'The account is full. Free some space on it or choose a different account.'}
    if (error.kind === 'Transient' || error.kind === 'RateLimited') return {state: 'offline', message: 'The account could not be reached. Keepfin will try again.'}
    if (error.info.code === 'duplicate-names') return {state: 'incomplete', message: friendlyCloudError(error)}
  }
  return {state: 'offline', message: error instanceof Error ? error.message : String(error)}
}

/** The mirror only carries Keepfin's own data (the media go straight over the account), so its file counts are never the person's files. */
function toProgress(p: MirrorProgress): Progress {
  return {doneActions: 0, totalActions: 0, doneBytes: p.doneBytes, totalBytes: p.totalBytes, current: null, data: true}
}

/** The staging folder of a place's runs: the one file in flight per worker, emptied after every action. */
export const stagingDir = (stateDir: string) => path.join(stateDir, 'staging')

/**
 * Syncs one cloud place, directly over the account (no mirror of the media on this computer). [job.copyRoot] holds only the copy's own
 * `.keepfin` data and [job.stateDir] the place's device-local state (baseline, journal, staging); both are made by the host. A preview
 * (`dryRun`) reads the account and plans, and writes nothing to it.
 */
export async function runCloudPlace(job: PlaceRunJob, spec: CloudPlaceSpec, deps: CloudRunDeps, host: Parameters<typeof runPlace>[1], onProgress?: (p: Progress) => void, signal?: AbortSignal): Promise<RunSummary> {
  const now = job.now ?? Date.now
  const rig = openRig(spec, deps)
  const remote = new CloudRemote(rig.media)
  const dry = job.dryRun === true
  const stateFile = path.join(job.stateDir, 'mirror-state.json')
  const common: Omit<MirrorOptions, 'onProgress'> = {
    mirror: job.copyRoot,
    stateFile,
    staging: path.join(job.stateDir, 'mirror-staging'),
    media: rig.media,
    meta: rig.meta,
    signal,
    now,
    // segments and checkpoint parts the main folder already holds are placed from it, not downloaded again
    localSource: (rel) => path.join(job.mainRoot, ...rel.split('/')),
  }
  const metaOptions: MirrorOptions = {...common, metaOnly: true, onProgress: onProgress ? (p) => onProgress(toProgress(p)) : undefined}
  let lock: Awaited<ReturnType<CloudTransport['acquireLock']>> | undefined
  let beat: NodeJS.Timeout | undefined
  try {
    if (!dry) {
      await rig.media.ensureRoot()
      if (spec.provider === 'webdav') await rig.client.probeWebdav(`${spec.rootPath}/${KEEPFIN_DIR}/probe-${now().toString(36)}`)
      lock = await rig.media.acquireLock({device: job.device, run: `r${now().toString(36)}`}, now())
      if (!lock.held) return emptySummary('busy', 'Another device is syncing this copy.', info('busy', 'Another device is syncing this copy. Keepfin will try again.'), now())
      beat = setInterval(() => void lock?.heartbeat().catch(() => undefined), deps.heartbeatMs ?? 30_000)
      beat.unref?.()

      // a place made when copies still kept a mirror: the mirror is settled once, then dropped, and the place runs directly
      const migration = await migrateLegacyMirror({
        stateDir: job.stateDir,
        mirror: job.copyRoot,
        stateFile,
        flush: () => push({...common, onProgress: metaOptions.onProgress}),
      })
      if (migration.state === 'blocked') {
        const d = migration.error ? describeFailure(migration.error) : migration.stopped === 'signin' ? describeFailure(new CloudError({kind: 'NeedsSignIn', status: 401}, 'push')) : migration.stopped === 'quota' ? describeFailure(new CloudError({kind: 'QuotaFull', status: 507}, 'push')) : {state: 'offline' as const, message: 'This copy changed how it is stored on this computer, and what it still had to send could not be sent. Keepfin will try again.'}
        return emptySummary(d.state === 'needsSignIn' ? 'refused' : 'disconnected', d.message, info(d.state, d.message), now())
      }
    }

    const pulled: PullReport = await pull(metaOptions)
    if (!pulled.complete || pulled.suspicious) {
      const message = pulled.suspicious ? 'The account listing looks wrong (it shows far fewer files than before), so nothing was changed. Try again later.' : 'The account could not be listed completely, so nothing was changed. Try again.'
      return emptySummary('disconnected', message, info('incomplete', message, {notes: pulled.unreadable.slice(0, 5)}), now())
    }
    if (signal?.aborted) return emptySummary('paused', 'Paused.', info('ok', 'Paused.'), now())

    // another device's generation records count: a higher generation makes this run additive only (copies spec 7)
    const remoteGeneration = await rig.media.readGeneration().catch(() => 0)
    const mirrorGeneration = await readGeneration(job.copyRoot)
    if (!dry && mirrorGeneration !== null && remoteGeneration > mirrorGeneration) await setGeneration(job.copyRoot, remoteGeneration)

    // room: the account's free space (the one file in flight is all this computer holds)
    const quota = await rig.client.quota().catch(() => ({} as {freeBytes?: number}))
    const summary = await runPlace({...job, remote, staging: stagingDir(job.stateDir), ...(typeof quota.freeBytes === 'number' ? {freeCopy: quota.freeBytes} : {})}, host, onProgress, signal)
    const tally = summary.remote
    const out = info('ok', 'Up to date.', {
      downloaded: tally?.downloaded ?? 0,
      bytesDown: tally?.bytesDown ?? 0,
      uploaded: tally?.uploaded ?? 0,
      replaced: tally?.replaced ?? 0,
      moved: tally?.moved ?? 0,
      bytesUp: tally?.bytesUp ?? 0,
      notes: (summary.collisions ?? []).map((c) => `Same name on the account: ${c}`),
    })
    summary.cloud = out
    if (summary.remoteStop) {
      Object.assign(out, {state: summary.remoteStop.state, message: summary.remoteStop.message})
      if (summary.remoteStop.state === 'needsSignIn') summary.status = 'refused'
    } else if (summary.failedCount > 0) {
      out.message = `${summary.failedCount} file${summary.failedCount === 1 ? '' : 's'} could not be sent. Keepfin will try them again.`
    }
    if (dry || summary.status === 'refused' || summary.status === 'disconnected' || summary.status === 'busy' || summary.status === 'crashed') return summary

    // the copy's own data (catalogues, manifests, ops) goes to the replica; a stopped run still sends what it recorded
    try {
      const pushed = await push(metaOptions)
      if (pushed.stopped === 'signin') Object.assign(out, describeFailure(new CloudError({kind: 'NeedsSignIn', status: 401}, 'push')))
      else if (pushed.stopped === 'quota') Object.assign(out, describeFailure(new CloudError({kind: 'QuotaFull', status: 507}, 'push')))
      if (pushed.failed.length) {
        summary.failedCount += pushed.failed.length
        for (const f of pushed.failed.slice(0, 50 - summary.failed.length)) summary.failed.push({kind: 'CloudPush', path: f.path, reason: f.reason})
        if (out.state === 'ok') out.message = `${pushed.failed.length} file${pushed.failed.length === 1 ? '' : 's'} could not be sent. Keepfin will try them again.`
      }
      if (pushed.failed.length || pushed.stopped) return summary
    } catch (error) {
      const d = describeFailure(error)
      summary.cloud = {...out, ...d}
      return summary
    }

    // the new generation as an append-only record, once the replica holds what produced it
    const generation = await readGeneration(job.copyRoot)
    if (generation !== null && generation > remoteGeneration && summary.failedCount === 0) {
      const folder = await readJson<{replicaId?: string}>(path.join(job.copyRoot, '.keepfin', 'folder.json')).catch(() => undefined)
      if (folder?.replicaId) await rig.media.appendGeneration(generation, folder.replicaId).catch(() => false)
    }
    return summary
  } catch (error) {
    if (signal?.aborted) return emptySummary('paused', 'Paused.', info('ok', 'Paused.'), now())
    const d = describeFailure(error)
    return emptySummary(d.state === 'needsSignIn' ? 'refused' : 'disconnected', d.message, info(d.state, d.message), now())
  } finally {
    if (beat) clearInterval(beat)
    await lock?.release().catch(() => undefined)
  }
}

/** A replica that an account already holds: found by a second device signing in to the same account. */
export interface FoundReplica {
  /** The folder name under `Keepfin` (or the WebDAV folder). */
  name: string
  rootPath: string
  libraryId: string
  replicaId: string
}

/**
 * Looks for Keepfin replicas of [libraryId] on the account: each folder under `Keepfin` (OAuth providers) that has a
 * `.keepfin/folder.json`, or the named folder itself (WebDAV). Reads at most [limit] folders.
 */
export async function findReplicas(client: CloudClient, provider: CloudProvider, libraryId: string, webdavFolder?: string, limit = 30): Promise<FoundReplica[]> {
  const out: FoundReplica[] = []
  const check = async (rootPath: string, name: string) => {
    const t = new CloudTransport({client, rootPath})
    const text = await t.readSmall(`${KEEPFIN_DIR}/folder.json`).catch(() => null)
    if (!text) return
    try {
      const json = JSON.parse(text) as {libraryId?: unknown; replicaId?: unknown}
      if (json.libraryId === libraryId && typeof json.replicaId === 'string') out.push({name, rootPath, libraryId, replicaId: json.replicaId})
    } catch {
      // not ours
    }
  }
  if (provider === 'webdav') {
    if (webdavFolder) await check(webdavFolder, webdavFolder.split('/').pop() ?? webdavFolder)
    return out
  }
  const names = (await new CloudTransport({client, rootPath: 'Keepfin'}).listChildFolders('').catch(() => null)) ?? []
  for (const name of names.slice(0, limit)) await check(`Keepfin/${name}`, name)
  return out
}

/** A folder name for a place's name: letters, digits, spaces and a few marks; never empty. */
export function folderNameFor(name: string): string {
  const cleaned = name.normalize('NFC').replace(/[^\p{L}\p{N} ._()-]/gu, '').replace(/\s+/g, ' ').trim().replace(/^\.+/, '').slice(0, 60).trim()
  return cleaned || 'Library'
}
