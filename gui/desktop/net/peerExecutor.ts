// The peer side of a copy over the network (spec 5, "Mutations on the peer are requests that the peer's own executor runs under its
// own lock. A device never writes into another device's folder directly."). The asking device streams bytes and names an action;
// this device stages them beside the target, checks the pinned SHA-256, and publishes with the same primitives the folder copies use:
// the place lock, no-replace publish, displaced bytes kept in the trash, generations. Nothing outside the folder can be named.
import crypto from 'node:crypto'
import {createReadStream, createWriteStream} from 'node:fs'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {Readable} from 'node:stream'
import {pipeline} from 'node:stream/promises'
import {Transform} from 'node:stream'
import {renameWithRetry} from '../fsutil.js'
import {bumpGeneration, readGeneration} from '../copies/generation.js'
import {DestinationExistsError, moveNoReplace} from '../copies/fsops.js'
import {skippedFile} from '../copies/inventory.js'
import {acquireCopyLock, type LockResult} from '../copies/lock.js'
import {recordsPath} from '../store/keepfinFolder.js'
import {HttpError} from './httpError.js'

export const LEASE_MS = 60_000
const RESERVE_BYTES = 64 * 1024 * 1024

export interface PutRequest {
  token: string
  path: string
  contentId: string
  mode: 'new' | 'replace'
  revision: string | null
  /** The content id the file at the destination must have right now (the HTTP If-Match of a replace). Wins over [revision]. */
  ifMatch?: string | null
  size: number
}

type Lease = {owner: string; token: string; held: Extract<LockResult, {ok: true}>; expires: number; timer: NodeJS.Timeout}

/** `size-mtime`: changes whenever the file is edited; it is the precondition of a replace. */
export const revisionOf = (st: {size: number; mtimeMs: number}) => `${st.size}-${Math.floor(st.mtimeMs)}`

export function validRelative(rel: string): boolean {
  if (!rel || rel.length > 1024 || rel.startsWith('/') || /[\\:*?"<>|\0]/.test(rel)) return false
  const segments = rel.split('/')
  if (segments.some((s) => !s || s === '.' || s === '..' || s.endsWith('.') || s.endsWith(' ') || s.length > 255)) return false
  if (segments[0].toLowerCase() === '.keepfin' || segments[0].toLowerCase() === 'keepfin exports') return false
  return !skippedFile(segments[segments.length - 1])
}

export class PeerExecutor {
  private lease?: Lease

  constructor(
    private readonly options: {root: string; /** The records root below .keepfin (a combined folder): the trash lives there. */ records?: string; now: () => number; leaseMs?: number; device?: string},
  ) {}

  private abs(rel: string): string {
    if (!validRelative(rel)) throw new HttpError(400, 'path', 'That name cannot be used in a copy.')
    return path.join(this.options.root, ...rel.split('/'))
  }

  /** The target must resolve below the real root (a link in the way is refused). */
  private async checkInside(file: string): Promise<void> {
    const root = await fs.realpath(this.options.root)
    let dir = path.dirname(file)
    for (;;) {
      try {
        const real = await fs.realpath(dir)
        const rel = path.relative(root, real)
        if (rel.startsWith('..') || path.isAbsolute(rel)) throw new HttpError(400, 'path', 'That name cannot be used in a copy.')
        return
      } catch (error) {
        if (error instanceof HttpError) throw error
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
        const up = path.dirname(dir)
        if (up === dir) return
        dir = up
      }
    }
  }

  async state(): Promise<{generation: number | null; locked: boolean}> {
    return {generation: await readGeneration(this.options.root), locked: !!this.lease}
  }

  private mine(owner: string, token: string): Lease {
    const lease = this.lease
    if (!lease || lease.owner !== owner || lease.token !== token) throw new HttpError(409, 'lock', 'The copy is not locked by this device.')
    return lease
  }

  async lock(owner: string, device: string, run: string, now: number): Promise<{ok: boolean; token?: string; holder?: string}> {
    if (this.lease) {
      if (this.lease.owner === owner) {
        // the same device asking again (a retry) takes over its own lease
        await this.drop(this.lease)
      } else return {ok: false, holder: 'another device'}
    }
    const token = crypto.randomUUID()
    const held = await acquireCopyLock(this.options.root, {device: `${this.options.device ?? 'peer'}:${device.slice(0, 40)}`, run: `net-${token}`, now: () => now || this.options.now()})
    if (!held.ok) return {ok: false, holder: held.holder?.device ?? 'another run'}
    const lease: Lease = {owner, token, held, expires: this.options.now() + (this.options.leaseMs ?? LEASE_MS), timer: setTimeout(() => void this.expire(token), this.options.leaseMs ?? LEASE_MS)}
    lease.timer.unref?.()
    this.lease = lease
    return {ok: true, token}
  }

  private async expire(token: string): Promise<void> {
    if (this.lease?.token === token) await this.drop(this.lease)
  }

  private async drop(lease: Lease): Promise<void> {
    clearTimeout(lease.timer)
    if (this.lease === lease) this.lease = undefined
    await lease.held.release().catch(() => undefined)
  }

  async heartbeat(owner: string, token: string): Promise<{ok: boolean}> {
    const lease = this.mine(owner, token)
    clearTimeout(lease.timer)
    lease.expires = this.options.now() + (this.options.leaseMs ?? LEASE_MS)
    lease.timer = setTimeout(() => void this.expire(token), this.options.leaseMs ?? LEASE_MS)
    lease.timer.unref?.()
    return {ok: true}
  }

  async unlock(owner: string, token: string): Promise<{ok: boolean}> {
    const lease = this.mine(owner, token)
    await this.drop(lease)
    return {ok: true}
  }

  /** The device lost its link: whatever it held is let go. */
  async release(owner: string): Promise<void> {
    if (this.lease?.owner === owner) await this.drop(this.lease)
  }

  async appendGeneration(owner: string, token: string, generation: number, replica: string): Promise<{ok: boolean; generation: number | null}> {
    this.mine(owner, token)
    if (!Number.isSafeInteger(generation) || !replica) throw new HttpError(400, 'generation', 'The generation is not valid.')
    const current = await readGeneration(this.options.root)
    if (current === null) return {ok: false, generation: null}
    if (current >= generation) return {ok: false, generation: current}
    return {ok: true, generation: await bumpGeneration(this.options.root, token)}
  }

  /** Receives a file: staged, hashed against the pinned id, then published (new) or swapped in (replace, only at [revision]). */
  async put(owner: string, request: PutRequest, body: Readable): Promise<{revision: string; preservedAs: string | null}> {
    this.mine(owner, request.token)
    if (!/^sha256:[0-9a-f]{64}$/.test(request.contentId)) throw new HttpError(400, 'content-id', 'The content id is not valid.')
    const dest = this.abs(request.path)
    await this.checkInside(dest)
    const free = await fs.statfs(this.options.root).then((s) => Number(s.bavail) * Number(s.bsize)).catch(() => Infinity)
    if (free < request.size + RESERVE_BYTES) throw new HttpError(507, 'space', 'There is not enough room on this device.')
    const stagingDir = path.join(this.options.root, '.keepfin', 'staging')
    await fs.mkdir(stagingDir, {recursive: true})
    const staged = path.join(stagingDir, `net-${crypto.randomUUID()}.kftmp`)
    const hash = crypto.createHash('sha256')
    let received = 0
    const guard = new Transform({
      transform(chunk: Buffer, _enc, done) {
        received += chunk.length
        if (received > request.size) return done(new HttpError(413, 'size', 'More bytes arrived than were announced.'))
        hash.update(chunk)
        done(null, chunk)
      },
    })
    try {
      await pipeline(body, guard, createWriteStream(staged, {flags: 'wx'}))
      if (received !== request.size) throw new HttpError(400, 'size', 'The file arrived short.')
      if (`sha256:${hash.digest('hex')}` !== request.contentId) throw new HttpError(422, 'hash', 'The bytes do not match the content id.')
      const handle = await fs.open(staged, 'r+')
      await handle.sync().finally(() => handle.close())
      await fs.mkdir(path.dirname(dest), {recursive: true})
      if (request.mode === 'new') {
        try {
          await moveNoReplace(staged, dest)
        } catch (error) {
          if (error instanceof DestinationExistsError) throw new HttpError(409, 'exists', 'A file with that name is already there.')
          throw error
        }
        return {revision: revisionOf(await stat(dest)), preservedAs: null}
      }
      const now = await fs.stat(dest).catch(() => undefined)
      if (!now) throw new HttpError(412, 'changed', 'The file is gone.')
      await this.precondition(dest, now, request.revision, request.ifMatch)
      const preservedAs = await this.preserve(dest)
      try {
        await renameWithRetry(staged, dest)
      } catch (error) {
        await renameWithRetry(path.join(recordsPath(this.options.root, this.options.records), 'trash', 'net', preservedAs), dest).catch(() => undefined)
        throw error
      }
      return {revision: revisionOf(await stat(dest)), preservedAs}
    } finally {
      await fs.rm(staged, {force: true}).catch(() => undefined)
    }
  }

  /**
   * The precondition of changing a file that exists (HTTP If-Match): its current content id must be the one the caller saw. The size and
   * modification time are only a fallback for callers that know nothing better; a content id, when given, decides (a file edited
   * and saved back at the same size and time is still caught, and a touched file whose bytes are the same is not a conflict).
   */
  private async precondition(file: string, st: {size: number; mtimeMs: number}, revision: string | null, ifMatch: string | null | undefined): Promise<void> {
    if (ifMatch) {
      if (!/^sha256:[0-9a-f]{64}$/.test(ifMatch)) throw new HttpError(400, 'if-match', 'The content id is not valid.')
      if ((await contentIdOf(file)) !== ifMatch) throw new HttpError(412, 'changed', 'The file was changed in the meantime.')
      return
    }
    if (revision === null || revisionOf(st) !== revision) throw new HttpError(412, 'changed', 'The file was changed in the meantime.')
  }

  async trash(owner: string, token: string, rel: string, revision: string | null, ifMatch?: string | null): Promise<{trashedAs: string}> {
    this.mine(owner, token)
    const file = this.abs(rel)
    await this.checkInside(file)
    const now = await fs.stat(file).catch(() => undefined)
    if (!now) throw new HttpError(404, 'gone', 'The file is already gone.')
    await this.precondition(file, now, revision, ifMatch)
    return {trashedAs: await this.preserve(file)}
  }

  /** Renames a file inside the folder (never over an existing one), only while it still has the content the caller saw. */
  async move(owner: string, token: string, from: string, to: string, revision: string | null, ifMatch?: string | null): Promise<{revision: string}> {
    this.mine(owner, token)
    const source = this.abs(from)
    const dest = this.abs(to)
    await this.checkInside(source)
    await this.checkInside(dest)
    const now = await fs.stat(source).catch(() => undefined)
    if (!now?.isFile()) throw new HttpError(404, 'gone', 'The file is already gone.')
    await this.precondition(source, now, revision, ifMatch)
    await fs.mkdir(path.dirname(dest), {recursive: true})
    try {
      await moveNoReplace(source, dest)
    } catch (error) {
      if (error instanceof DestinationExistsError) throw new HttpError(409, 'exists', 'A file with that name is already there.')
      throw error
    }
    return {revision: revisionOf(await stat(dest))}
  }

  /** A file of the folder to be read (a copy being pulled): the path is checked like every other. */
  async open(rel: string): Promise<{file: string; size: number; mtimeMs: number}> {
    const file = this.abs(rel)
    await this.checkInside(file)
    const st = await fs.stat(file).catch(() => undefined)
    if (!st?.isFile()) throw new HttpError(404, 'gone', 'That file is not here.')
    return {file, size: st.size, mtimeMs: st.mtimeMs}
  }

  /** Moves a file into `.keepfin/trash/net/` under a name that cannot collide; never deletes. */
  private async preserve(file: string): Promise<string> {
    const dir = path.join(recordsPath(this.options.root, this.options.records), 'trash', 'net')
    await fs.mkdir(dir, {recursive: true})
    const name = `${this.options.now()}-${crypto.randomBytes(4).toString('hex')}-${path.basename(file)}`
    await renameWithRetry(file, path.join(dir, name))
    return name
  }
}

async function stat(file: string) {
  return fs.stat(file)
}

export async function contentIdOf(file: string): Promise<string> {
  const hash = crypto.createHash('sha256')
  for await (const chunk of createReadStream(file)) hash.update(chunk as Buffer)
  return `sha256:${hash.digest('hex')}`
}
