import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {HashRequest, Side} from '../../shared/core/copies.js'
import {pool} from './util.js'

export const CONTENT_PREFIX = 'sha256:'

/** `sha256:<hex>` of the file, streamed (never read into memory whole). */
export async function hashFile(file: string, options: {signal?: AbortSignal} = {}): Promise<{contentId: string; size: number}> {
  const handle = await fs.open(file, 'r')
  try {
    const hash = crypto.createHash('sha256')
    const buf = Buffer.allocUnsafe(1024 * 1024)
    let size = 0
    for (;;) {
      if (options.signal?.aborted) throw new Error('aborted')
      const {bytesRead} = await handle.read(buf, 0, buf.length, null)
      if (bytesRead === 0) break
      hash.update(buf.subarray(0, bytesRead))
      size += bytesRead
    }
    return {contentId: CONTENT_PREFIX + hash.digest('hex'), size}
  } finally {
    await handle.close()
  }
}

/** The hashes a planning round asked for, kept by the stat they were computed at (a hash is valid for that stat). */
export class HashCache {
  private readonly map = new Map<string, {contentId: string; size: number; mtimeMs: number}>()

  private static key(side: Side, p: string): string {
    return `${side}|${p}`
  }

  set(side: Side, p: string, size: number, mtimeMs: number, contentId: string): void {
    this.map.set(HashCache.key(side, p), {contentId, size, mtimeMs})
  }

  get(side: Side, p: string, size: number, mtimeMs: number): string | undefined {
    const e = this.map.get(HashCache.key(side, p))
    return e && e.size === size && e.mtimeMs === mtimeMs ? e.contentId : undefined
  }

  lookup(side: Side, p: string): {contentId: string; size: number; mtimeMs: number} | undefined {
    return this.map.get(HashCache.key(side, p))
  }

  entries(side: Side): Iterable<[string, {contentId: string; size: number; mtimeMs: number}]> {
    const prefix = `${side}|`
    const out: [string, {contentId: string; size: number; mtimeMs: number}][] = []
    for (const [k, v] of this.map) if (k.startsWith(prefix)) out.push([k.slice(prefix.length), v])
    return out
  }

  get size(): number {
    return this.map.size
  }
}

export interface HashRoots {
  main: string
  copy: string
  /** Library path of the copy's file -> absolute path (applies the name map). */
  copyFile: (libPath: string) => string
  mainFile: (libPath: string) => string
  /** A copy that is not a folder (an account, a device): how to read the content id of one of its objects. */
  copyHash?: (libPath: string) => Promise<{contentId: string; size: number}>
}

/**
 * Hashes the requested files with bounded concurrency. A file that vanished is left unhashed (the next inventory sees
 * it); a file whose size changed since the inventory is hashed anyway and cached under the stat it had then, so the
 * planner will ask again if the stat no longer matches.
 */
export async function hashRequests(
  requests: HashRequest[],
  roots: HashRoots,
  cache: HashCache,
  options: {concurrency?: number; signal?: AbortSignal; onHashed?: (bytes: number) => void} = {},
): Promise<{hashed: number; missing: number}> {
  let hashed = 0
  let missing = 0
  await pool(requests, options.concurrency ?? 4, async (r) => {
    try {
      const {contentId, size} = r.side === 'Copy' && roots.copyHash ? await roots.copyHash(r.path) : await hashFile(r.side === 'Copy' ? roots.copyFile(r.path) : roots.mainFile(r.path), {signal: options.signal})
      cache.set(r.side, r.path, r.size, r.mtimeMs, contentId)
      options.onHashed?.(size)
      hashed++
    } catch (error) {
      const c = (error as NodeJS.ErrnoException).code
      if (c === 'ENOENT' || c === 'ENOTDIR') missing++
      else throw error
    }
  })
  return {hashed, missing}
}

export const dirOf = (file: string) => path.dirname(file)
