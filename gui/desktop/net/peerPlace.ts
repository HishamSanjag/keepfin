// A copy on a paired device (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md 5; KEEPFIN-COPIES-SPEC.md section 9). The same design as a cloud copy
// (cloud/cloudPlace.ts): the runner plans over the device's streamed inventory and its actions go through PeerTransport, so the peer's own
// executor, under the peer's own place lock, makes every change in its folder, and this computer holds no copy of the other folder. In order:
//   1. the advisory lock on the peer's folder (never the safety mechanism: the authority is append-only);
//   2. an older place that still has a mirror is settled once and the mirror is dropped (cloud/migrate.ts);
//   3. the runner: inventory, plan, execute, baseline, manifest;
//   4. the new generation is appended as a record, and the lock is released.
// The peer's .keepfin folder is its own library data and is never touched: the copy's bookkeeping stays on this device.
//
// Every change to a file that already exists on the peer (a replacement, a move, a trash) carries the content id the file had when this
// device last saw it (HTTP If-Match). The peer checks the content id of the file it holds now: size and modification time play no part.
import fsp from 'node:fs/promises'
import path from 'node:path'
import {readGeneration, setGeneration} from '../copies/generation.js'
import type {Progress} from '../copies/executor.js'
import {runPlace, type CloudRunInfo, type PlaceRunJob, type RunSummary} from '../copies/placeRun.js'
import {CloudError} from '../cloud/http.js'
import {push, readState, type MirrorOptions, type MirrorProgress, type MirrorTransport} from '../cloud/mirror.js'
import {migrateLegacyMirror} from '../cloud/migrate.js'
import type {RemoteBase, RemoteErrorKind, RemoteMedia, RemoteScan, RemoteStopState} from '../copies/remote.js'
import type {TransportInventoryLine} from '../copies/transport.js'
import type {CloudScanResult} from '../cloud/transport.js'
import {readJson} from '../store/atomic.js'
import {PeerHttpError, PinMismatch, type PeerClient} from './client.js'
import {PeerTransport, PeerTransportError} from './peerTransport.js'

/** Where a copy on a device lives. */
export interface PeerPlaceSpec {
  deviceId: string
  libraryId: string
}

export interface PeerRunDeps {
  client: PeerClient
  heartbeatMs?: number
}

/** What a copies worker needs to reach a paired device: where it is, the key it must have, and this device's key and certificate. */
export interface PeerConnection {
  host: string
  port: number
  /** The device id the other side must prove (SHA-256 of its key). */
  pin: string
  keyPem: string
  certPem: string
}

/** What the copies screen asks of the network service (the service is made after the copies host, so this is bound late). */
export interface PeerBridge {
  /** The paired devices, with what each lets this device do with this library. */
  devices(): {deviceId: string; name: string; online: boolean; allowed: boolean; why?: string}[]
  name(deviceId: string): string | undefined
  online(deviceId: string): boolean
  /** How to reach the device right now, or undefined when it is not reachable. */
  connection(deviceId: string): PeerConnection | undefined
}

export type PeerJobPayload = {spec: PeerPlaceSpec; connection: PeerConnection}

const META = '.keepfin'

/** The library folder of the peer, as the mirror sees it. Changes to existing files are conditional on the content id last seen. */
export class PeerMedia implements MirrorTransport {
  /** Content ids this run learned (what it uploaded), by path: the state file only gets them at the end of the run. */
  private readonly learned = new Map<string, string>()

  constructor(
    private readonly transport: PeerTransport,
    private readonly seen: (rel: string) => Promise<string | undefined>,
  ) {}

  async scan(root: string, onLine: Parameters<PeerTransport['scan']>[1], signal?: AbortSignal): Promise<CloudScanResult> {
    const result = await this.transport.scan(root, onLine, signal)
    return {...result, collisions: [], missingRoot: false}
  }

  download(remotePath: string, stagedFile: string, expectedContentId: string, signal?: AbortSignal) {
    return this.transport.download(remotePath, stagedFile, expectedContentId, signal)
  }

  async uploadNew(sourceFile: string, remotePath: string, expectedContentId: string, signal?: AbortSignal) {
    // a file the runner displaced (archived in the mirror) and then wrote again: on the peer that is a replacement of the old content
    const old = this.retired.get(remotePath)
    if (old) {
      const out = await this.transport.replace(sourceFile, remotePath, expectedContentId, old, signal)
      this.retired.delete(remotePath)
      this.learned.set(remotePath, expectedContentId)
      return {revision: out.revision}
    }
    const out = await this.transport.uploadNew(sourceFile, remotePath, expectedContentId, signal)
    this.learned.set(remotePath, expectedContentId)
    return out
  }

  /** Paths whose file was archived in the mirror (moved into the copy's own `.keepfin`), with the content id the peer's file had. */
  private readonly retired = new Map<string, string>()

  /**
   * After a push: a file the runner removed from the mirror (archived, and not written again) is moved into the peer's trash, at the content
   * it had; the peer keeps the bytes, as it does for every displaced file. Returns what could not be done.
   */
  async settleRetired(): Promise<{path: string; reason: string}[]> {
    const failed: {path: string; reason: string}[] = []
    for (const [rel, id] of [...this.retired]) {
      try {
        await this.transport.moveToTrash(rel, id)
        this.retired.delete(rel)
      } catch (error) {
        if (error instanceof PeerTransportError && error.kind === 'NotFound') this.retired.delete(rel)
        else failed.push({path: rel, reason: error instanceof Error ? error.message : String(error)})
      }
    }
    return failed
  }

  private async current(rel: string): Promise<string> {
    const id = this.learned.get(rel) ?? (await this.seen(rel))
    if (!id) throw new PeerTransportError('Changed', 'This device has not seen that file on the other device, so it is not changed.')
    return id
  }

  async replace(sourceFile: string, remotePath: string, expectedContentId: string, _revision: string | null, signal?: AbortSignal) {
    const out = await this.transport.replace(sourceFile, remotePath, expectedContentId, await this.current(remotePath), signal)
    this.learned.set(remotePath, expectedContentId)
    return out
  }

  async moveToTrash(remotePath: string, _revision: string | null) {
    const out = await this.transport.moveToTrash(remotePath, await this.current(remotePath))
    this.learned.delete(remotePath)
    return out
  }

  async moveTo(from: string, to: string, _revision: string | null) {
    // the bookkeeping folder is not on the peer. A file archived into it (the runner keeps the old bytes of an edited or removed file there)
    // stays where it is on the peer until the new content replaces it, or settleRetired() trashes it.
    if (to.startsWith(`${META}/`) && !from.startsWith(`${META}/`)) {
      const id = this.learned.get(from) ?? (await this.seen(from))
      if (!id) throw new CloudError({kind: 'NotFound', status: 404, code: 'unseen'}, 'move')
      this.retired.set(from, id)
      this.learned.delete(from)
      return {revision: 'local'}
    }
    // a file coming out of it, or moving inside it, is a new file on the peer's side (or nothing at all)
    if (from.startsWith(`${META}/`) || to.startsWith(`${META}/`)) throw new CloudError({kind: 'NotFound', status: 404, code: 'local-only'}, 'move')
    try {
      const out = await this.transport.moveTo(from, to, await this.current(from))
      const id = this.learned.get(from) ?? (await this.seen(from))
      this.learned.delete(from)
      if (id) this.learned.set(to, id)
      return out
    } catch (error) {
      if (error instanceof PeerTransportError && error.kind === 'NotFound') throw new CloudError({kind: 'NotFound', status: 404, code: 'gone'}, 'move')
      throw error
    }
  }
}

/**
 * The copy's `.keepfin` folder: it lives in the mirror only. Sending it anywhere is a no-op; listing it gives back what the mirror's
 * own state file says is there (revision `local`), so the pull neither fetches it nor takes it for something the replica lost.
 */
export class LocalMeta implements MirrorTransport {
  constructor(private readonly stateFile: string) {}
  async scan(_root: string, onLine: Parameters<PeerTransport['scan']>[1]): Promise<CloudScanResult> {
    const state = await readState(this.stateFile)
    let count = 0
    for (const [rel, file] of Object.entries(state.files)) {
      if (!rel.startsWith(`${META}/`)) continue
      onLine({path: rel.slice(META.length + 1), size: file.size, mtimeMs: file.mtimeMs, fileId: null, revision: 'local'})
      count++
    }
    return {count, complete: true, unreadable: [], collisions: [], missingRoot: false}
  }
  async download(): Promise<string> {
    throw new Error('The copy keeps its own data on this device.')
  }
  async uploadNew() {
    return {revision: 'local'}
  }
  async replace() {
    return {revision: 'local', preservedAs: null}
  }
  async moveToTrash() {
    return {trashedAs: ''}
  }
  async moveTo(): Promise<{revision: string | null}> {
    return {revision: 'local'}
  }
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

/** What the person should be told when a run ended without changing anything it cannot undo. */
export function describePeerFailure(error: unknown): {state: Exclude<CloudRunInfo['state'], 'ok'>; message: string} {
  if (error instanceof PinMismatch) return {state: 'unavailable', message: 'This is not the device that was paired (its key is different), so nothing was sent.'}
  if (error instanceof PeerTransportError) {
    if (error.kind === 'Denied') return {state: 'unavailable', message: 'That device does not let this device keep a copy on it. Ask it to allow "Keep a copy on this device".'}
    if (error.kind === 'Locked') return {state: 'busy', message: 'That device is busy with another copy. Keepfin will try again.'}
    if (error.kind === 'Space') return {state: 'quota', message: 'There is not enough room on that device.'}
    if (error.kind === 'Changed') return {state: 'incomplete', message: 'A file changed on that device in the meantime, so it was left alone.'}
  }
  if (error instanceof PeerHttpError && error.status === 403) return {state: 'unavailable', message: 'That device does not let this device keep a copy on it. Ask it to allow "Keep a copy on this device".'}
  return {state: 'offline', message: 'That device could not be reached. Keepfin will try again.'}
}

function toProgress(p: MirrorProgress): Progress {
  const verb = p.phase === 'downloading' ? 'Reading the other device' : p.phase === 'uploading' ? 'Sending' : p.phase === 'listing' ? 'Reading the other device' : 'Finishing'
  return {doneActions: p.done, totalActions: p.total, doneBytes: p.doneBytes, totalBytes: p.totalBytes, current: p.current ?? verb}
}

const NETWORK = new Set(['ECONNRESET', 'ECONNREFUSED', 'ETIMEDOUT', 'EPIPE', 'ENOTFOUND', 'EHOSTUNREACH', 'ENETUNREACH', 'ECONNABORTED', 'EAI_AGAIN'])

/** An error that ends the run (the device is not the one paired, refuses, is busy, is full, or cannot be reached), with the words for the person. */
export function peerStop(error: unknown): {state: RemoteStopState; message: string} | null {
  if (error instanceof PinMismatch) return describePeerFailure(error)
  if (error instanceof PeerTransportError) {
    if (error.kind === 'Denied' || error.kind === 'Locked' || error.kind === 'Space') return describePeerFailure(error)
    if (error.kind === 'Other' && error.status >= 500) return describePeerFailure(error)
    return null
  }
  if (error instanceof PeerHttpError) return error.status === 403 || error.status >= 500 ? describePeerFailure(error) : null
  const code = (error as NodeJS.ErrnoException | undefined)?.code
  if ((code && NETWORK.has(code)) || /socket hang up|timed out|timeout/i.test(error instanceof Error ? error.message : '')) return describePeerFailure(error)
  return null
}

/**
 * A paired device's folder as the media of a copy (copies/remote.ts). The device's own executor, under its own place lock, makes every
 * change and keeps what it displaces in its own trash; this device never writes into the other folder. Every change to an existing
 * file is conditional on the content id this device last saw (HTTP If-Match, hashed by the peer).
 */
export class PeerRemote implements RemoteMedia {
  readonly kind = 'device'
  readonly archive = 'builtin' as const

  constructor(readonly transport: PeerTransport) {}

  identity(line: TransportInventoryLine): string | null {
    return line.revision
  }

  async scan(onLine: (line: TransportInventoryLine) => void, signal?: AbortSignal): Promise<RemoteScan> {
    const result = await this.transport.scan('', onLine, signal)
    return {count: result.count, complete: result.complete, unreadable: result.unreadable, collisions: [], missingRoot: false}
  }

  stat(remotePath: string) {
    return this.transport.stat(remotePath)
  }

  /** By name (the device's folder is a copy, its files are not all in the library's content index); the hash is checked here. */
  async download(remotePath: string, stagedFile: string, expectedContentId: string, signal?: AbortSignal) {
    const got = await this.transport.download(remotePath, stagedFile, '*', signal)
    if (expectedContentId !== '*' && got !== expectedContentId) {
      await fsp.rm(stagedFile, {force: true})
      throw new PeerTransportError('Hash', 'The file that arrived does not match its content id.')
    }
    return got
  }

  uploadNew(sourceFile: string, remotePath: string, contentId: string, signal?: AbortSignal) {
    return this.transport.uploadNew(sourceFile, remotePath, contentId, signal)
  }

  private known(base: RemoteBase): string {
    if (!base.contentId) throw new PeerTransportError('Changed', 'This device has not seen that file on the other device, so it is not changed.')
    return base.contentId
  }

  async replace(sourceFile: string, remotePath: string, contentId: string, base: RemoteBase, signal?: AbortSignal) {
    const out = await this.transport.replace(sourceFile, remotePath, contentId, this.known(base), signal)
    return {revision: out.revision}
  }

  async moveToTrash(remotePath: string, base: RemoteBase): Promise<void> {
    await this.transport.moveToTrash(remotePath, this.known(base))
  }

  moveTo(from: string, to: string, base: RemoteBase) {
    return this.transport.moveTo(from, to, this.known(base))
  }

  classify(error: unknown): RemoteErrorKind | null {
    if (!(error instanceof PeerTransportError)) return null
    switch (error.kind) {
      case 'NotFound':
        return 'gone'
      case 'Conflict':
        return 'exists'
      case 'Changed':
        return 'changed'
      case 'Hash':
        return 'mismatch'
      default:
        return null
    }
  }

  stop(error: unknown) {
    return peerStop(error)
  }
}

/** The staging folder of a place's runs: the one file in flight per worker, emptied after every action. */
export const stagingDir = (stateDir: string) => path.join(stateDir, 'staging')

/**
 * Syncs one copy on a device, directly over the connection (no mirror of the device's folder on this computer). [job.copyRoot] holds only
 * the copy's own bookkeeping (`.keepfin`, which stays on this device) and [job.stateDir] the place's device-local state; both are made by
 * the host. A preview (`dryRun`) reads the peer and plans, and writes nothing to it.
 */
export async function runPeerPlace(job: PlaceRunJob, spec: PeerPlaceSpec, deps: PeerRunDeps, host: Parameters<typeof runPlace>[1], onProgress?: (p: Progress) => void, signal?: AbortSignal): Promise<RunSummary> {
  const now = job.now ?? Date.now
  const dry = job.dryRun === true
  const transport = new PeerTransport({client: deps.client, libraryId: spec.libraryId, device: job.device, heartbeatMs: deps.heartbeatMs})
  const remote = new PeerRemote(transport)
  const stateFile = path.join(job.stateDir, 'mirror-state.json')
  let lock: Awaited<ReturnType<PeerTransport['acquireLock']>> | undefined
  try {
    if (!dry) {
      lock = await transport.acquireLock({device: job.device, run: `r${now().toString(36)}`}, now())
      if (!lock.held) return emptySummary('busy', 'Another device is syncing this copy.', info('busy', 'That device is busy with another copy. Keepfin will try again.'), now())

      // a place made when copies still kept a mirror: the mirror is settled once, then dropped, and the place runs directly
      const migration = await migrateLegacyMirror({
        stateDir: job.stateDir,
        mirror: job.copyRoot,
        stateFile,
        flush: async () => {
          let cached: {mtime: number; contentIds: Map<string, string>} | undefined
          const seen = async (rel: string): Promise<string | undefined> => {
            const st = await fsp.stat(stateFile).catch(() => undefined)
            if (!st) return undefined
            if (!cached || cached.mtime !== st.mtimeMs) {
              const state = await readState(stateFile)
              cached = {mtime: st.mtimeMs, contentIds: new Map(Object.entries(state.files).map(([p, f]) => [p, f.contentId]))}
            }
            return cached.contentIds.get(rel)
          }
          const media = new PeerMedia(transport, seen)
          const options: MirrorOptions = {mirror: job.copyRoot, stateFile, staging: path.join(job.stateDir, 'mirror-staging'), media, meta: new LocalMeta(stateFile), signal, now}
          const pushed = await push(options)
          for (const f of await media.settleRetired()) pushed.failed.push({kind: 'trash', path: f.path, reason: f.reason})
          return pushed
        },
      })
      if (migration.state === 'blocked') {
        const d = migration.error ? describePeerFailure(migration.error) : {state: 'offline' as const, message: 'This copy changed how it is stored on this computer, and what it still had to send could not be sent. Keepfin will try again.'}
        return emptySummary(d.state === 'unavailable' ? 'refused' : 'disconnected', d.message, info(d.state, d.message), now())
      }
    }

    // another device's generation records count: a higher generation makes this run additive only (copies spec 7)
    const remoteGeneration = await transport.readGeneration().catch(() => 0)
    const localGeneration = await readGeneration(job.copyRoot)
    if (!dry && localGeneration !== null && remoteGeneration > localGeneration) await setGeneration(job.copyRoot, remoteGeneration)

    const summary = await runPlace({...job, remote, staging: stagingDir(job.stateDir)}, host, onProgress, signal)
    const tally = summary.remote
    const out = info('ok', 'Up to date.', {
      downloaded: tally?.downloaded ?? 0,
      bytesDown: tally?.bytesDown ?? 0,
      uploaded: tally?.uploaded ?? 0,
      replaced: tally?.replaced ?? 0,
      moved: (tally?.moved ?? 0) + (tally?.trashed ?? 0),
      bytesUp: tally?.bytesUp ?? 0,
    })
    summary.cloud = out
    if (summary.remoteStop) {
      Object.assign(out, {state: summary.remoteStop.state, message: summary.remoteStop.message})
      if (summary.remoteStop.state === 'unavailable') summary.status = 'refused'
    } else if (summary.failedCount > 0) {
      out.message = `${summary.failedCount} file${summary.failedCount === 1 ? '' : 's'} could not be sent. Keepfin will try them again.`
    }
    if (dry || summary.status === 'refused' || summary.status === 'disconnected' || summary.status === 'busy' || summary.status === 'crashed') return summary

    // the new generation as an append-only record, once the peer holds what produced it
    const generation = await readGeneration(job.copyRoot)
    if (generation !== null && generation > remoteGeneration && summary.failedCount === 0) {
      const folder = await readJson<{replicaId?: string}>(path.join(job.copyRoot, '.keepfin', 'folder.json')).catch(() => undefined)
      if (folder?.replicaId) await transport.appendGeneration(generation, folder.replicaId).catch(() => false)
    }
    return summary
  } catch (error) {
    if (signal?.aborted) return emptySummary('paused', 'Paused.', info('ok', 'Paused.'), now())
    const d = describePeerFailure(error)
    return emptySummary(d.state === 'unavailable' ? 'refused' : 'disconnected', d.message, info(d.state, d.message), now())
  } finally {
    await lock?.release().catch(() => undefined)
  }
}
