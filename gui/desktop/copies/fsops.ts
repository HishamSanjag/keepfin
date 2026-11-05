// The file operations of the executor: staged streaming copy with a pinned hash, and publishing with no-replace
// semantics (spec 5.1 steps 2 and 4).
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import type {FileHandle} from 'node:fs/promises'
import path from 'node:path'
import {renameWithRetry} from '../fsutil.js'
import {code, SkipError} from './util.js'

export interface FileStat {
  size: number
  mtimeMs: number
  fileId: string | null
}

export async function statOf(file: string): Promise<FileStat | null> {
  try {
    const st = await fs.stat(file, {bigint: true})
    return toStat(st)
  } catch (error) {
    if (code(error) === 'ENOENT' || code(error) === 'ENOTDIR') return null
    throw error
  }
}

export function toStat(st: {size: bigint; mtimeMs: bigint | number; ino: bigint; isFile?: () => boolean}): FileStat {
  return {size: Number(st.size), mtimeMs: Math.floor(Number(st.mtimeMs)), fileId: st.ino && st.ino !== 0n ? st.ino.toString() : null}
}

/** Limits a stream of bytes to a rate; resolves when the caller may continue. */
export class Throttle {
  private nextFree = 0
  constructor(private readonly bytesPerSecond: number | undefined) {}

  async take(bytes: number): Promise<void> {
    if (!this.bytesPerSecond) return
    const now = Date.now()
    const start = Math.max(now, this.nextFree)
    this.nextFree = start + (bytes / this.bytesPerSecond) * 1000
    const wait = start - now
    if (wait > 1) await new Promise((r) => setTimeout(r, wait))
  }
}

export interface StageOptions {
  /** The pinned content id: the staged bytes must hash to it. */
  expected: string
  throttle?: Throttle
  onBytes?: (n: number) => void
  maxBytes?: number
  /** Called after the source was opened and before the first byte is read (fault injection). */
  afterOpen?: () => void | Promise<void>
  signal?: AbortSignal
}

export interface Staged {
  stat: FileStat
  source: FileStat
}

/**
 * Streams [src] into the exclusively created [tmp] while hashing, verifies the hash equals the pinned content id, fsyncs.
 * A mismatch removes the staged file and skips the action: an unchanged source stat cannot exclude mixed bytes.
 */
export async function stageFile(src: string, tmp: string, options: StageOptions): Promise<Staged> {
  let input: FileHandle
  try {
    input = await fs.open(src, 'r')
  } catch (error) {
    if (code(error) === 'ENOENT' || code(error) === 'ENOTDIR') throw new SkipError('source-missing', `The source ${src} is gone.`)
    throw error
  }
  let output: FileHandle | null = null
  let ok = false
  try {
    const before = toStat(await input.stat({bigint: true}))
    if (options.maxBytes !== undefined && before.size > options.maxBytes) throw new SkipError('too-large', 'The file is larger than the copy can hold.')
    await options.afterOpen?.()
    await fs.mkdir(path.dirname(tmp), {recursive: true})
    output = await fs.open(tmp, 'wx')
    const hash = crypto.createHash('sha256')
    const buf = Buffer.allocUnsafe(1024 * 1024)
    for (;;) {
      if (options.signal?.aborted) throw new SkipError('paused')
      const {bytesRead} = await input.read(buf, 0, buf.length, null)
      if (bytesRead === 0) break
      const chunk = buf.subarray(0, bytesRead)
      hash.update(chunk)
      await output.write(chunk)
      options.onBytes?.(bytesRead)
      await options.throttle?.take(bytesRead)
    }
    await output.sync()
    const got = 'sha256:' + hash.digest('hex')
    if (got !== options.expected) throw new SkipError('hash-mismatch', `The source no longer matches its pinned content (${got.slice(0, 15)}).`)
    const stat = toStat(await output.stat({bigint: true}))
    ok = true
    return {stat, source: before}
  } finally {
    await input.close().catch(() => undefined)
    if (output) await output.close().catch(() => undefined)
    if (!ok && output) await fs.rm(tmp, {force: true}).catch(() => undefined)
  }
}

export class DestinationExistsError extends Error {
  constructor(readonly dest: string) {
    super(`${dest} already exists.`)
    this.name = 'DestinationExistsError'
  }
}

/**
 * Moves [from] to [to] without ever replacing an existing file: `link` then `unlink` where the file system has hard links,
 * otherwise an existence check and a rename (the caller holds the place lock, spec 5.1 step 4).
 */
export async function moveNoReplace(from: string, to: string): Promise<void> {
  await fs.mkdir(path.dirname(to), {recursive: true})
  let linked = false
  try {
    await fs.link(from, to)
    linked = true
  } catch (error) {
    const c = code(error)
    if (c === 'EEXIST') throw new DestinationExistsError(to)
    // no hard links here (FAT, exFAT, some network shares; Google Drive for desktop answers EISDIR): check, then rename
    if (c !== 'EPERM' && c !== 'ENOSYS' && c !== 'ENOTSUP' && c !== 'EOPNOTSUPP' && c !== 'EINVAL' && c !== 'EXDEV' && c !== 'EISDIR' && c !== 'UNKNOWN') throw error
  }
  if (linked) {
    // Published. A staging name that cannot be removed yet (a scanner holding it on Windows) is retried, then left for
    // recovery to clean up; it never turns a published file into a failure.
    for (let attempt = 0; attempt < 5; attempt++) {
      try {
        await fs.unlink(from)
        return
      } catch (error) {
        if (code(error) === 'ENOENT') return
        await new Promise((resolve) => setTimeout(resolve, 50 * (attempt + 1)))
      }
    }
    return
  }
  try {
    await fs.lstat(to)
    throw new DestinationExistsError(to)
  } catch (error) {
    if (error instanceof DestinationExistsError) throw error
    if (code(error) !== 'ENOENT') throw error
  }
  await renameWithRetry(from, to)
}
