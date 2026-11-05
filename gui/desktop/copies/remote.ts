// A copy that is not a folder on this computer (dev-docs/plans/KEEPFIN-COPIES-SPEC.md section 9, KEEPFIN-CLOUD-SPEC.md 7.3): a cloud account or a
// paired device. The runner plans over the transport's streamed inventory and the executor's actions go through the transport, so no media
// bytes are kept on this computer except the one file in flight per worker, in a staging folder that is emptied after every action.
//
// What stays on disk per place is small bookkeeping only: the inventory cache of a run, the baseline, the journal, and the copy's own
// `.keepfin` data (folder.json, names, catalogues, manifests), which is synced with the replica as it always was.
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {NameRulesId} from '../../shared/core/copies.js'
import {fastKey} from '../../shared/core/copies.js'
import {skippedFile, type InventoryResult} from './inventory.js'
import {ExternalSorter, LineWriter} from './lines.js'
import type {TransportInventoryLine} from './transport.js'

export type RemoteErrorKind = 'exists' | 'gone' | 'changed' | 'busy' | 'mismatch'

/** What this device last saw of an object it is about to change: the provider's revision and the content id the planner pinned. */
export interface RemoteBase {
  revision: string | null
  contentId: string | null
}

export interface RemoteScan {
  count: number
  complete: boolean
  unreadable: string[]
  /** Names the provider cannot tell apart: left to the planner (they sort next to each other) and noted for the person. */
  collisions: string[]
  /** The replica's folder does not exist. */
  missingRoot: boolean
}

export type RemoteStopState = 'needsSignIn' | 'offline' | 'quota' | 'incomplete' | 'busy' | 'unavailable'

/** The media of a copy as the runner sees it. Library paths in, library paths out; nothing here knows about folders. */
export interface RemoteMedia {
  readonly kind: string
  /**
   * `move`: before an object is replaced or removed, it is moved to the copy's own versions or trash path (the same layout a folder copy
   * uses, so earlier versions look alike everywhere). `builtin`: the other side keeps what it displaces (a device does, under its own lock).
   */
  readonly archive: 'move' | 'builtin'
  /** What tells one version of an object from the next, from an inventory line or a stat: stable ids with revisions, else the revision. */
  identity(line: TransportInventoryLine): string | null
  scan(onLine: (line: TransportInventoryLine) => void, signal?: AbortSignal): Promise<RemoteScan>
  /** The object now, or null when it is not there. */
  stat(remotePath: string): Promise<TransportInventoryLine | null>
  /** Downloads into [stagedFile]; the bytes are hashed on arrival and must equal [expectedContentId] (`*` pins nothing). Returns the content id. */
  download(remotePath: string, stagedFile: string, expectedContentId: string, signal?: AbortSignal): Promise<string>
  /** Creates the object under a name that is free; refuses when it is taken. */
  uploadNew(sourceFile: string, remotePath: string, contentId: string, signal?: AbortSignal): Promise<{revision: string | null}>
  /** Replaces the object only if it is still what [base] says; never overwrites a version it has not seen. The displaced bytes are kept. */
  replace(sourceFile: string, remotePath: string, contentId: string, base: RemoteBase, signal?: AbortSignal): Promise<{revision: string | null}>
  /** Moves the object to the other side's trash (never deletes). */
  moveToTrash(remotePath: string, base: RemoteBase): Promise<void>
  /** Renames or moves without sending bytes; never replaces an object at the target. */
  moveTo(from: string, to: string, base: RemoteBase): Promise<{revision: string | null}>
  /** The meaning of an error for the executor's preconditions, or null when it is something else. */
  classify(error: unknown): RemoteErrorKind | null
  /** An error that ends the run (sign-in refused, account full, not reachable), with the words for the person; null for anything else. */
  stop(error: unknown): {state: RemoteStopState; message: string} | null
}

/** Library paths as the runner compares them for a remote copy: case-insensitive, the conservative reading of every provider. */
export const REMOTE_RULES: NameRulesId = 'ascii'

export interface RemoteInventoryResult extends InventoryResult {
  collisions: string[]
  missingRoot: boolean
}

/**
 * Streams the transport's listing into the sorted inventory file the planner reads, the same shape a folder scan writes. A listing that
 * was not read to the end is incomplete (principle 5: nothing is removed from it). So is one that suddenly shows nothing although
 * [priorCount] files were there at the last run, (a replica folder that is gone): the planner would otherwise read it as "everything was deleted".
 */
export async function scanRemoteInventory(
  remote: RemoteMedia,
  outFile: string,
  options: {rules: NameRulesId; tmpDir: string; priorCount?: number; signal?: AbortSignal; chunk?: number},
): Promise<RemoteInventoryResult> {
  const startedAtMs = Date.now()
  const sorter = new ExternalSorter(options.tmpDir, options.chunk ?? 50_000, 'inv')
  let seq = 0
  let pending = Promise.resolve()
  let failure: unknown
  const scan = await remote.scan((line) => {
    if (failure !== undefined) return
    const parts = line.path.split('/')
    if (parts[0]!.toLowerCase() === '.keepfin' || parts[0]!.toLowerCase() === 'keepfin exports' || skippedFile(parts[parts.length - 1]!)) return
    const id = remote.identity(line)
    const text = JSON.stringify(id ? {path: line.path, size: line.size, mtimeMs: Math.floor(line.mtimeMs), fileId: id} : {path: line.path, size: line.size, mtimeMs: Math.floor(line.mtimeMs)})
    const rec = {key: fastKey(options.rules, line.path), path: line.path, seq: seq++, text}
    pending = pending.then(() => sorter.add(rec)).catch((error) => void (failure ??= error))
  }, options.signal)
  await pending
  if (failure !== undefined) throw failure
  const tmp = `${outFile}.${process.pid}.tmp`
  const writer = await LineWriter.create(tmp)
  let count = 0
  for await (const rec of sorter.sorted()) {
    await writer.write(rec.text)
    count++
  }
  await writer.close()
  await fs.rename(tmp, outFile)
  const unreadable = [...scan.unreadable]
  let complete = scan.complete // a replica folder that is not there yet is simply empty; one that vanished shows up as an empty listing below
  if (complete && count === 0 && (options.priorCount ?? 0) > 0) {
    complete = false
    unreadable.push('The listing is empty although files were there before.')
  }
  if (complete) await fs.writeFile(`${outFile}.complete`, String(count), 'utf8')
  else await fs.rm(`${outFile}.complete`, {force: true})
  return {file: outFile, count, complete, unreadable, startedAtMs, collisions: scan.collisions, missingRoot: scan.missingRoot}
}

/** The content id of an object: downloaded into staging, hashed, and removed again. */
export async function hashRemote(remote: RemoteMedia, remotePath: string, staging: string, signal?: AbortSignal): Promise<{contentId: string; size: number}> {
  await fs.mkdir(staging, {recursive: true})
  const part = path.join(staging, `h-${crypto.randomBytes(6).toString('hex')}.kftmp`)
  try {
    const contentId = await remote.download(remotePath, part, '*', signal)
    const st = await fs.stat(part)
    return {contentId, size: st.size}
  } finally {
    await fs.rm(part, {force: true}).catch(() => undefined)
  }
}

/** Empties the staging folder: files left by a run that died. They never hold the only copy of anything. */
export async function clearStaging(staging: string): Promise<number> {
  let removed = 0
  for (const name of await fs.readdir(staging).catch(() => [])) {
    await fs.rm(path.join(staging, name), {recursive: true, force: true}).catch(() => undefined)
    removed++
  }
  return removed
}

/** Total bytes of the files under [dir] (for the disk-use checks: what a run leaves on this computer). */
export async function folderBytes(dir: string): Promise<number> {
  let total = 0
  const stack = [dir]
  while (stack.length) {
    const d = stack.pop()!
    for (const e of await fs.readdir(d, {withFileTypes: true}).catch(() => [])) {
      const p = path.join(d, e.name)
      if (e.isDirectory()) stack.push(p)
      else if (e.isFile()) total += (await fs.stat(p).catch(() => null))?.size ?? 0
    }
  }
  return total
}
