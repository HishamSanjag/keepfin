// Joining a library over the network (spec 9.4): a paired device that does not hold a library its peer shares with it makes a Keepfin folder
// of its own for that library (the same library id, a new replica id), takes in the peer's segments, and becomes a replica. The steps:
//   1. the folder: new or empty; `.keepfin/join.json` records the replica id and the actor before anything else, so an interrupted
//      join is resumed with the same identity instead of becoming a second replica;
//   2. `POST /v1/libraries/<id>/join` names the actor this device will write as; the peer, if it administers the library, grants it
//      (and writes the membership root first when the library was not signed yet);
//   3. the segments are pulled by version vector (a broken transfer keeps what arrived: the next pull starts from the new vector);
//   4. `join.json` is marked ready. The host (keepfinBoot) then adds the folder as a library profile of this device and opens it;
//      the library store replays the segments with the membership rules, exactly as for any folder (checkpoint parts are not
//      transferred: a signed library is replayed anyway, spec 9.3).
// Media is not part of this: it arrives through the place's holds (copies) or on demand ("Play from").
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {readJson, writeJsonAtomic} from '../store/atomic.js'
import {createKeepfinFolder, ensureRecordsTree, openKeepfinFolder, recordsPath, validRecords} from '../store/keepfinFolder.js'
import {PeerClient, PeerHttpError} from './client.js'
import {listSegments, parseVector, vectorOf} from './segments.js'
import {pullSegments} from './sync.js'

export interface JoinRecord {
  v: 1
  libraryId: string
  /** The device id of the peer that offered the library. */
  from: string
  replicaId: string
  actor: string
  startedAt: number
  stage: 'joining' | 'ready'
  /** The peer's actor and the name it gave the library, kept for the profile. */
  hostActor?: string
  name: string
}

export interface JoinProgress {
  stage: 'folder' | 'asking' | 'pulling' | 'done'
  segments: number
}

export class JoinError extends Error {
  constructor(
    readonly code: 'folder' | 'denied' | 'not-admin' | 'version' | 'network' | 'incomplete' | 'cancelled' | 'other',
    message: string,
  ) {
    super(message)
    this.name = 'JoinError'
  }
}

export const joinFile = (root: string, records?: string) => path.join(recordsPath(root, records), 'join.json')

export interface JoinOptions {
  client: PeerClient
  /** The device id of the peer (pinned by [client]). */
  peerId: string
  libraryId: string
  name: string
  target: string
  /**
   * Join into a records root of an existing Keepfin folder ([target]) instead of making a folder of its own: libraries/<id> below
   * .keepfin/ (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 2). Then the emptiness check is about that records root, never about
   * the media folder.
   */
  records?: string
  /** Where the join record is kept (default: in the records root). It is written before anything else is created. */
  recordFile?: string
  newId?: () => string
  now?: () => number
  hide?: (target: string) => Promise<void> | void
  onProgress?: (progress: JoinProgress) => void
  signal?: AbortSignal
}

export interface Joined {
  root: string
  records?: string
  libraryId: string
  replicaId: string
  actor: string
  name: string
  hostActor: string
  segments: number
}

/** True when [root] holds an unfinished join of [libraryId] from [peerId] (it may be resumed). */
export async function readJoin(root: string, records?: string, recordFile?: string): Promise<JoinRecord | undefined> {
  return readJson<JoinRecord>(recordFile ?? joinFile(root, records)).catch(() => undefined)
}

export async function runJoin(options: JoinOptions): Promise<Joined> {
  const newId = options.newId ?? randomUUID
  const now = options.now ?? Date.now
  const {target, libraryId} = options
  const progress = (stage: JoinProgress['stage'], segments = 0) => options.onProgress?.({stage, segments})
  progress('folder')

  const {records} = options
  if (records !== undefined && (records === '' || !validRecords(records))) throw new JoinError('folder', 'This library cannot be kept there.')
  const recordFile = options.recordFile ?? joinFile(target, records)
  const treeDir = recordsPath(target, records)
  let record = await readJoin(target, records, recordFile)
  if (record && (record.libraryId !== libraryId || record.from !== options.peerId)) throw new JoinError('folder', 'This folder is already being used to join a different library.')
  if (!record) {
    const stat = await fs.stat(target).catch(() => undefined)
    if (stat && !stat.isDirectory()) throw new JoinError('folder', 'Choose a folder, not a file.')
    if (records === undefined) {
      if (stat && (await fs.readdir(target)).length > 0) throw new JoinError('folder', 'Choose a new or an empty folder for this library.')
    } else {
      // the media folder keeps its files; the records root for this library must not exist or be empty
      const opened = await openKeepfinFolder(target)
      if (!opened.ok) throw new JoinError('folder', 'This is not a Keepfin folder.')
      const there = await fs.readdir(treeDir).catch(() => [] as string[])
      if (there.length > 0) throw new JoinError('folder', 'This device already holds that library here.')
    }
    const replicaId = newId()
    record = {v: 1, libraryId, from: options.peerId, replicaId, actor: newId(), startedAt: now(), stage: 'joining', name: options.name}
    // the record first: a crash after this resumes the same identity, a crash before it leaves nothing behind
    await writeJsonAtomic(recordFile, record)
    if (records === undefined) await createKeepfinFolder(target, {libraryId, replicaId, now: new Date(now()).toISOString(), hide: options.hide})
    else await ensureRecordsTree(treeDir)
  } else if (records === undefined) {
    const opened = await openKeepfinFolder(target)
    if (!opened.ok && (opened.reason === 'missing' || opened.reason === 'not-a-keepfin-folder')) {
      // the record was written and the process stopped before the folder was made
      await createKeepfinFolder(target, {libraryId, replicaId: record.replicaId, now: new Date(record.startedAt).toISOString(), hide: options.hide})
    } else if (!opened.ok || opened.folder.libraryId !== libraryId) throw new JoinError('folder', 'This folder cannot be used to continue the join.')
  } else {
    await ensureRecordsTree(treeDir)
  }
  // 2. the peer grants the actor (idempotent: the same actor asking again gets the same answer)
  progress('asking')
  let hostActor = record.hostActor ?? ''
  if (record.stage !== 'ready' || !hostActor) {
    try {
      const answer = await options.client.json<{ok: boolean; actor?: string}>('POST', `/v1/libraries/${libraryId}/join`, {json: {actor: record.actor}, signal: options.signal})
      hostActor = answer.actor ?? ''
    } catch (error) {
      throw mapJoinError(error, options.signal)
    }
    record = {...record, hostActor}
    await writeJsonAtomic(recordFile, record)
  }

  // 3. the segments, until what the peer reported is here (a pass that stores nothing and reaches its vector is the end)
  let total = (await listSegments(target, records)).length
  progress('pulling', total)
  const lib = {root: target, records, libraryId, flush: async () => undefined, rescan: async () => undefined}
  for (let pass = 0; pass < 50; pass++) {
    if (options.signal?.aborted) throw new JoinError('cancelled', 'The join was stopped.')
    let result
    try {
      result = await pullSegments(options.client, lib, options.signal, () => progress('pulling', ++total))
    } catch (error) {
      throw mapJoinError(error, options.signal)
    }
    if (result.denied) throw new JoinError('denied', 'The other device does not let this device read the library.')
    const have = vectorOf(await listSegments(target, records))
    const remote = result.remote ?? parseVector('') ?? new Map()
    const behind = [...remote].some(([actor, seq]) => (have.get(actor) ?? 0) < seq)
    if (!behind) break
    if (result.stored === 0) throw new JoinError('incomplete', 'The library arrived only in part. Try again.')
  }

  record = {...record, stage: 'ready'}
  await writeJsonAtomic(recordFile, record)
  progress('done', total)
  return {root: target, ...(records !== undefined ? {records} : {}), libraryId, replicaId: record.replicaId, actor: record.actor, name: record.name, hostActor, segments: total}
}

function mapJoinError(error: unknown, signal?: AbortSignal): JoinError {
  if (error instanceof JoinError) return error
  if (signal?.aborted) return new JoinError('cancelled', 'The join was stopped.')
  if (error instanceof PeerHttpError) {
    if (error.status === 409 && error.code === 'not-admin') return new JoinError('not-admin', error.message)
    if (error.status === 403) return new JoinError('denied', 'The other device does not share this library with this device. Ask it to share the library first.')
    if (error.status === 426) return new JoinError('version', error.message)
    return new JoinError('other', error.message)
  }
  return new JoinError('network', error instanceof Error ? error.message : 'The other device could not be reached.')
}
