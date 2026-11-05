import {createHash} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'

/** On Windows, paths of 260 characters or more need the \\?\ prefix. */
export function toLongPath(file: string, platform: string = process.platform): string {
  if (platform !== 'win32' || file.length < 260 || file.startsWith('\\\\?\\')) return file
  const full = path.win32.resolve(file)
  if (full.startsWith('\\\\')) return `\\\\?\\UNC\\${full.slice(2)}`
  return `\\\\?\\${full}`
}

export const lp = (file: string) => toLongPath(file)

const missingCodes = new Set(['ENOENT', 'ENOTDIR'])

export async function statOrUndefined(file: string) {
  try {
    return await fs.stat(lp(file))
  } catch (error) {
    if (missingCodes.has((error as NodeJS.ErrnoException).code ?? '')) return undefined
    throw error
  }
}

export async function exists(file: string): Promise<boolean> {
  try {
    await fs.lstat(lp(file))
    return true
  } catch (error) {
    if (missingCodes.has((error as NodeJS.ErrnoException).code ?? '')) return false
    throw error
  }
}

/** SHA-256 of a whole file, streamed. */
export async function hashFile(file: string, signal?: AbortSignal): Promise<string> {
  const hash = createHash('sha256')
  const handle = await fs.open(lp(file), 'r')
  try {
    const buffer = Buffer.allocUnsafe(1024 * 1024)
    for (;;) {
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
      const {bytesRead} = await handle.read(buffer, 0, buffer.length, null)
      if (!bytesRead) break
      hash.update(buffer.subarray(0, bytesRead))
    }
  } finally {
    await handle.close()
  }
  return hash.digest('hex')
}

export type CopyOptions = {signal?: AbortSignal; onBytes?: (bytes: number) => void}

/** Streams src to dest (created new), hashing the source while reading, then fsyncs. Returns the source hash. */
export async function copyHashing(src: string, dest: string, options: CopyOptions = {}): Promise<{hash: string; bytes: number}> {
  const hash = createHash('sha256')
  const input = await fs.open(lp(src), 'r')
  let output: Awaited<ReturnType<typeof fs.open>> | undefined
  let bytes = 0
  try {
    output = await fs.open(lp(dest), 'w')
    const buffer = Buffer.allocUnsafe(1024 * 1024)
    for (;;) {
      if (options.signal?.aborted) throw new DOMException('Aborted', 'AbortError')
      const {bytesRead} = await input.read(buffer, 0, buffer.length, null)
      if (!bytesRead) break
      const chunk = buffer.subarray(0, bytesRead)
      hash.update(chunk)
      let written = 0
      while (written < chunk.length) written += (await output.write(chunk, written, chunk.length - written)).bytesWritten
      bytes += bytesRead
      options.onBytes?.(bytesRead)
    }
    await output.sync()
  } finally {
    await input.close()
    await output?.close()
  }
  return {hash: hash.digest('hex'), bytes}
}

/** True when both paths are on the same volume (so a rename is a move, not a copy). */
export async function sameVolume(source: string, destination: string): Promise<boolean> {
  const a = await fs.stat(lp(source))
  let probe = path.dirname(destination)
  for (;;) {
    const b = await statOrUndefined(probe)
    if (b) return a.dev === b.dev
    const parent = path.dirname(probe)
    if (parent === probe) return false
    probe = parent
  }
}

export const toSlash = (value: string) => value.split(path.sep).join('/')
export const fromSlash = (value: string) => value.split('/').join(path.sep)

export const pathKey = (file: string) => (process.platform === 'win32' ? path.normalize(file).toLowerCase() : path.normalize(file))
