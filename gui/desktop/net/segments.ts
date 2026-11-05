// Segment files on the wire (spec section 4): the receiver stores a peer's `KFSEG` files in the folder like any replica's,
// and the library store admits them (with the signer the file proves). Nothing here decides what is trusted: a segment is
// decoded and checksummed, then written under the name its own header gives; membership is admission's job.
import {createHash, randomBytes} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {Transform} from 'node:stream'
import {decodeSegment} from '../../shared/core/sync.js'
import {renameWithRetry} from '../fsutil.js'
import {recordsPath} from '../store/keepfinFolder.js'

export const SEGMENT_MAX_BYTES = 16 * 1024 * 1024
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const NAME = /^(\d{16})-(\d{16})\.seg$/
const pad = (n: number) => String(n).padStart(16, '0')

/** The ops folder of the library whose records root is [records] below the Keepfin folder [root] (''/undefined: .keepfin/ itself). */
export const opsDirOf = (root: string, records?: string) => path.join(recordsPath(root, records), 'ops')
export const isActorId = (value: string) => UUID.test(value)

export interface SegInfo {
  actor: string
  first: number
  last: number
  name: string
  file: string
}

/** Every segment file in the folder, by actor then first seq. */
export async function listSegments(root: string, records?: string): Promise<SegInfo[]> {
  const ops = opsDirOf(root, records)
  const out: SegInfo[] = []
  const actors = await fs.readdir(ops, {withFileTypes: true}).catch(() => [])
  for (const entry of actors) {
    if (!entry.isDirectory() || !isActorId(entry.name)) continue
    for (const name of await fs.readdir(path.join(ops, entry.name)).catch(() => [] as string[])) {
      const m = NAME.exec(name)
      if (m) out.push({actor: entry.name, first: Number(m[1]), last: Number(m[2]), name, file: path.join(ops, entry.name, name)})
    }
  }
  return out.sort((a, b) => (a.actor < b.actor ? -1 : a.actor > b.actor ? 1 : a.first - b.first || a.last - b.last))
}

export function vectorOf(list: readonly SegInfo[]): Map<string, number> {
  const out = new Map<string, number>()
  for (const s of list) out.set(s.actor, Math.max(out.get(s.actor) ?? 0, s.last))
  return out
}

export function formatVector(vector: ReadonlyMap<string, number>): string {
  return [...vector].filter(([, seq]) => seq > 0).sort(([a], [b]) => (a < b ? -1 : 1)).map(([actor, seq]) => `${actor}:${seq}`).join(',')
}

/** Reads `actor:seq,actor:seq`. Null for anything else (a bad vector is a bad request, never a guess). */
export function parseVector(text: string, maxEntries = 512): Map<string, number> | null {
  const out = new Map<string, number>()
  if (text === '') return out
  const parts = text.split(',')
  if (parts.length > maxEntries) return null
  for (const part of parts) {
    const at = part.lastIndexOf(':')
    const actor = part.slice(0, at)
    const seq = Number(part.slice(at + 1))
    if (at < 0 || !isActorId(actor) || !Number.isSafeInteger(seq) || seq < 0 || out.has(actor)) return null
    out.set(actor, seq)
  }
  return out
}

/** The segments a peer holding [vector] lacks, in the order they should arrive. */
export function missingFor(list: readonly SegInfo[], vector: ReadonlyMap<string, number>): SegInfo[] {
  return list.filter((s) => s.last > (vector.get(s.actor) ?? 0))
}

export type Ingest = {status: 'stored' | 'have'; actor: string; first: number; last: number} | {status: 'rejected'; reason: string}

/** Checks a received file and stores it under `ops/<actor>/<first>-<last>.seg` (never overwriting). */
export async function ingestSegment(root: string, libraryId: string, bytes: Uint8Array, records?: string): Promise<Ingest> {
  if (bytes.length === 0 || bytes.length > SEGMENT_MAX_BYTES) return {status: 'rejected', reason: 'size'}
  const decoded = decodeSegment(bytes, libraryId)
  if (!decoded.ok) return {status: 'rejected', reason: 'invalid'}
  if (!isActorId(decoded.actor)) return {status: 'rejected', reason: 'actor'}
  const dir = path.join(opsDirOf(root, records), decoded.actor)
  const file = path.join(dir, `${pad(decoded.first)}-${pad(decoded.last)}.seg`)
  const info = {actor: decoded.actor, first: decoded.first, last: decoded.last}
  try {
    await fs.access(file)
    return {status: 'have', ...info}
  } catch {
    // not here yet
  }
  await fs.mkdir(dir, {recursive: true})
  const temp = `${file}.${randomBytes(6).toString('hex')}.tmp`
  const handle = await fs.open(temp, 'wx')
  try {
    await handle.writeFile(bytes)
    await handle.sync()
  } finally {
    await handle.close()
  }
  try {
    await renameWithRetry(temp, file)
  } catch (error) {
    await fs.rm(temp, {force: true}).catch(() => undefined)
    throw error
  }
  return {status: 'stored', ...info}
}

export const sha256Hex = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex')

// ---- framing: `u32be length` then the file's bytes, repeated ----------------------------------------------------------------

export function frame(bytes: Uint8Array): Buffer {
  const head = Buffer.alloc(4)
  head.writeUInt32BE(bytes.length)
  return Buffer.concat([head, bytes])
}

export class FrameError extends Error {}

/** Splits a byte stream into frames; a frame over [maxFrame] bytes or a count over [maxFrames] ends the stream with an error. */
export function frameReader(maxFrame: number, maxFrames: number, onFrame: (bytes: Buffer) => Promise<void> | void): Transform {
  let pending: Buffer = Buffer.alloc(0)
  let count = 0
  return new Transform({
    async transform(chunk: Buffer, _encoding, done) {
      try {
        pending = pending.length ? Buffer.concat([pending, chunk]) : chunk
        for (;;) {
          if (pending.length < 4) break
          const size = pending.readUInt32BE(0)
          if (size === 0 || size > maxFrame) throw new FrameError('a frame is too large')
          if (pending.length < 4 + size) break
          if (++count > maxFrames) throw new FrameError('too many frames')
          const piece = Buffer.from(pending.subarray(4, 4 + size))
          pending = pending.subarray(4 + size)
          await onFrame(piece)
        }
        done()
      } catch (error) {
        done(error as Error)
      }
    },
    flush(done) {
      done(pending.length ? new FrameError('the stream ended inside a frame') : undefined)
    },
  })
}
