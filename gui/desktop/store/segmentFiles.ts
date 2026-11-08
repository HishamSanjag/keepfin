// The segment files of a Keepfin folder (sync spec section 9): their bytes, names, listing and reading. File helpers only, no store state.
import {createHash} from 'node:crypto'
import {readFileSync, readdirSync} from 'node:fs'
import fs from 'node:fs/promises'
import path from 'node:path'
import {decodeSegment} from '../../shared/core/sync.js'
import {DOMAIN_SEGMENT, deviceIdOf, signDomain} from '../../shared/core/net.js'
import {renameWithRetry} from '../fsutil.js'
import {recordsPath} from './keepfinFolder.js'

/**
 * A segment file (sync spec section 9) built here instead of by the core: the same bytes, with the checksum from the
 * platform's SHA-256 (about 1 ms for 500 operations instead of 30 ms). The ops are the store's own canonical lines, in
 * contiguous order; store.test.ts proves the output equals the core's encodeSegment and decodes with it.
 */
export function segmentBytes(library: string, actor: string, ops: readonly string[], first: number): Uint8Array {
  const body = Buffer.from(ops.map((op) => `${op}\n`).join(''), 'utf8')
  const header = `{"actor":"${actor}","count":${ops.length},"first":${first},"last":${first + ops.length - 1},"library":"${library}","sha256":"${createHash('sha256').update(body).digest('hex')}"}`
  return Buffer.concat([Buffer.from(`KFSEG 1\n${header}\n`, 'utf8'), body])
}

/**
 * The same file as `KFSEG 2` (network spec 9.2): the v1 layout under the magic `KFSEG 2`, then one trailer line
 * `KFSIG {"alg","device","key","sig"}` whose signature covers every byte before it under the domain `keepfin-seg-v2`.
 * [unsigned] returns the bytes before the trailer (what a re-flush after a crash is compared against: ECDSA signatures differ
 * from run to run, the signed content does not).
 */
export function signedSegmentBytes(library: string, actor: string, ops: readonly string[], first: number, signer: {pkcs8: Uint8Array; spki: Uint8Array}): {unsigned: Buffer; bytes: Buffer} {
  const v1 = segmentBytes(library, actor, ops, first)
  const unsigned = Buffer.concat([Buffer.from('KFSEG 2\n', 'utf8'), v1.subarray(8)])
  const sig = signDomain(signer.pkcs8, DOMAIN_SEGMENT, unsigned)
  const trailer = `KFSIG {"alg":"ES256","device":"${deviceIdOf(signer.spki)}","key":"${Buffer.from(signer.spki).toString('base64')}","sig":"${Buffer.from(sig).toString('base64')}"}\n`
  return {unsigned, bytes: Buffer.concat([unsigned, Buffer.from(trailer, 'utf8')])}
}

export const pad = (n: number) => String(n).padStart(16, '0')
export const segmentName = (first: number, last: number) => `${pad(first)}-${pad(last)}.seg`
export const SEGMENT = /^(\d{16})-(\d{16})\.seg$/

export async function writeBytesDurable(file: string, bytes: Uint8Array): Promise<void> {
  await fs.mkdir(path.dirname(file), {recursive: true})
  const temp = `${file}.${process.pid}.${Math.random().toString(36).slice(2)}.tmp`
  const handle = await fs.open(temp, 'w')
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
}

async function readDirs(dir: string): Promise<string[]> {
  const entries = await fs.readdir(dir, {withFileTypes: true}).catch(() => [])
  return entries.filter((entry) => entry.isDirectory()).map((entry) => entry.name)
}

export type SegmentFile = {actor: string; first: number; last: number; name: string; file: string}

export async function listSegments(opsDir: string): Promise<SegmentFile[]> {
  const out: SegmentFile[] = []
  for (const actor of await readDirs(opsDir)) {
    for (const name of await fs.readdir(path.join(opsDir, actor)).catch(() => [] as string[])) {
      const match = SEGMENT.exec(name)
      if (match) out.push({actor, first: Number(match[1]), last: Number(match[2]), name, file: path.join(opsDir, actor, name)})
    }
  }
  return out.sort((a, b) => (a.actor < b.actor ? -1 : a.actor > b.actor ? 1 : a.first - b.first || a.last - b.last))
}

/**
 * The device id in a segment's `KFSIG` trailer without decoding the file: null for a v1 (unsigned) file, undefined when the
 * file cannot be read or is no segment. The signature itself is checked when the file is decoded for a merge.
 */
export async function peekSegmentSigner(file: string): Promise<string | null | undefined> {
  let handle: fs.FileHandle | undefined
  try {
    handle = await fs.open(file, 'r')
    const {size} = await handle.stat()
    const head = Buffer.alloc(8)
    await handle.read(head, 0, 8, 0)
    const magic = head.toString('latin1')
    if (magic === 'KFSEG 1\n') return null
    if (magic !== 'KFSEG 2\n') return undefined
    const length = Math.min(size, 2048)
    const tail = Buffer.alloc(length)
    await handle.read(tail, 0, length, size - length)
    const text = tail.toString('utf8')
    const at = text.lastIndexOf('\nKFSIG ')
    if (at < 0 || !text.endsWith('\n')) return undefined
    const device = (JSON.parse(text.slice(at + 7, -1)) as {device?: unknown}).device
    return typeof device === 'string' ? device : undefined
  } catch {
    return undefined
  } finally {
    await handle?.close().catch(() => undefined)
  }
}

/** An ordering of "actor to highest seq" pairs as one string: what a projection cache says it reflects. */
export function keyString(last: ReadonlyMap<string, number>): string {
  return [...last].filter(([, seq]) => seq > 0).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([actor, seq]) => `${actor}:${seq}`).join(',')
}

/**
 * The highest seq per actor that the folder (segment file names) and this device's outbox hold, without opening the
 * store: cheap enough to decide at startup whether a projection cache is current.
 */
export async function listingLast(root: string, outboxDir: string, records?: string): Promise<Map<string, number>> {
  const last = new Map<string, number>()
  for (const segment of await listSegments(path.join(recordsPath(root, records), 'ops'))) last.set(segment.actor, Math.max(last.get(segment.actor) ?? 0, segment.last))
  const outbox = await fs.readFile(path.join(outboxDir, 'ops.jsonl'), 'utf8').catch(() => '')
  const lines = outbox.split('\n')
  lines.pop() // a torn last line
  for (const line of lines) {
    const at = line.lastIndexOf('"id":"')
    if (at < 0) continue
    const end = line.indexOf('"', at + 6)
    const id = line.slice(at + 6, end)
    const colon = id.lastIndexOf(':')
    const seq = Number(id.slice(colon + 1))
    if (colon > 0 && Number.isSafeInteger(seq)) last.set(id.slice(0, colon), Math.max(last.get(id.slice(0, colon)) ?? 0, seq))
  }
  return last
}

export type OpRef = {line: string; signer: string | null}

/** The segment files of [opsDir] as one fixed list (so two passes over it read the same files). */
export function segmentFilesSync(opsDir: string): {actor: string; first: number; last: number; file: string}[] {
  const segments: {actor: string; first: number; last: number; file: string}[] = []
  for (const entry of readdirSync(opsDir, {withFileTypes: true})) {
    if (!entry.isDirectory()) continue
    for (const name of readdirSync(path.join(opsDir, entry.name))) {
      const match = SEGMENT.exec(name)
      if (match) segments.push({actor: entry.name, first: Number(match[1]), last: Number(match[2]), file: path.join(opsDir, entry.name, name)})
    }
  }
  return segments.sort((a, b) => (a.actor < b.actor ? -1 : a.actor > b.actor ? 1 : a.first - b.first || a.last - b.last))
}

/** Every op the segments hold with the device id that signed its segment (null: a v1 file), read synchronously; an unreadable segment is skipped. */
export function* segmentOps(files: readonly {file: string}[], libraryId: string): Generator<OpRef> {
  for (const segment of files) {
    let value: ReturnType<typeof decodeSegment>
    try {
      value = decodeSegment(readFileSync(segment.file), libraryId)
    } catch {
      continue
    }
    if (value.ok) for (const line of value.ops) yield {line, signer: value.signer}
  }
}

/** The outbox lines (a torn last line dropped), each admitted under [ownSigner] when it is this actor's. */
export function outboxOps(outboxFile: string, ownActor: string, ownSigner: string | null): OpRef[] {
  let outbox = ''
  try {
    outbox = readFileSync(outboxFile, 'utf8')
  } catch {
    // none
  }
  const lines = outbox.split('\n')
  lines.pop() // a torn last line
  const out: OpRef[] = []
  for (const line of lines) {
    if (!line) continue
    out.push({line, signer: ownSigner && line.includes(`"id":"${ownActor}:`) ? ownSigner : null})
  }
  return out
}
