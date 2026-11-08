// Exchanging segments with one peer (spec 4): pull what we lack by version vector, push what the peer lacks. Both directions stream
// framed KFSEG files; the receiver stores them as from any replica and the library store admits them, judging membership itself.
// A transfer that breaks halfway keeps what already arrived; the next pass starts from the new vector, so it resumes.
import {Readable} from 'node:stream'
import fs from 'node:fs/promises'
import {PeerClient, PeerHttpError} from './client.js'
import {formatVector, frame, frameReader, ingestSegment, listSegments, missingFor, parseVector, vectorOf, SEGMENT_MAX_BYTES} from './segments.js'
import type {NetLibrary} from './types.js'

export interface SyncReport {
  pulled: number
  pushed: number
  rejected: number
  /** The peer refused (it does not share this library with us in that direction). */
  denied?: 'read' | 'write'
}

/** Pulls the segments the peer has and we lack. Returns how many files arrived and the peer's vector (from the answer header). */
export async function pullSegments(client: PeerClient, lib: Pick<NetLibrary, 'root' | 'records' | 'libraryId' | 'flush' | 'rescan'>, signal?: AbortSignal, onStored?: (stored: number) => void): Promise<{stored: number; rejected: number; remote: Map<string, number> | null; denied: boolean}> {
  await lib.flush()
  const vector = vectorOf(await listSegments(lib.root, lib.records))
  const response = await client.request('GET', `/v1/libraries/${lib.libraryId}/segments?vector=${encodeURIComponent(formatVector(vector))}`, {timeoutMs: 120_000, signal})
  if (response.status === 403) {
    response.res.resume()
    return {stored: 0, rejected: 0, remote: null, denied: true}
  }
  if (response.status !== 200) {
    response.res.resume()
    throw new PeerHttpError(response.status, 'segments', `The device answered ${response.status}.`)
  }
  let stored = 0
  let rejected = 0
  const header = response.headers['x-keepfin-vector']
  const remote = typeof header === 'string' ? parseVector(header) : null
  const reader = frameReader(SEGMENT_MAX_BYTES, 1_000_000, async (bytes) => {
    signal?.throwIfAborted() // a stopped transfer stops taking files in, even those already buffered
    const outcome = await ingestSegment(lib.root, lib.libraryId, bytes, lib.records)
    if (outcome.status === 'stored') {
      stored++
      onStored?.(stored)
    } else if (outcome.status === 'rejected') rejected++
  })
  reader.on('data', () => undefined)
  try {
    await new Promise<void>((resolve, reject) => {
      response.res.on('error', reject)
      reader.on('error', reject)
      reader.on('end', resolve)
      response.res.pipe(reader)
    })
  } finally {
    if (stored) await lib.rescan() // whatever arrived is admitted, even when the stream broke
  }
  return {stored, rejected, remote, denied: false}
}

/** Pushes the segments [remote] (the peer's vector) lacks. */
export async function pushSegments(client: PeerClient, lib: NetLibrary, remote: ReadonlyMap<string, number>, signal?: AbortSignal): Promise<{pushed: number; denied: boolean}> {
  await lib.flush()
  // The lease: while files are listed, measured and sent, compaction removes none of them (a file that vanished would break the
  // declared length of the body).
  const release = (await lib.hold?.()) ?? (() => undefined)
  try {
    const missing = missingFor(await listSegments(lib.root, lib.records), remote)
    if (!missing.length) return {pushed: 0, denied: false}
    // the length is needed up front: read the sizes first (files are immutable once written); a file already gone is left out
    const sendable: {file: string; size: number}[] = []
    for (const segment of missing) {
      const stat = await fs.stat(segment.file).catch(() => undefined)
      if (stat && stat.size > 0 && stat.size <= SEGMENT_MAX_BYTES) sendable.push({file: segment.file, size: stat.size})
    }
    if (!sendable.length) return {pushed: 0, denied: false}
    async function* frames() {
      for (const entry of sendable) {
        const bytes = await fs.readFile(entry.file).catch(() => undefined)
        // the declared length counts this file: if it changed or vanished the body would be short, so the pass fails and the next one starts over
        if (!bytes || bytes.length !== entry.size) throw new Error('A segment changed while it was sent.')
        yield frame(bytes)
      }
    }
    const total = sendable.reduce((sum, entry) => sum + 4 + entry.size, 0)
    const response = await client.request('POST', `/v1/libraries/${lib.libraryId}/segments`, {body: Readable.from(frames()), contentLength: total, timeoutMs: 120_000, signal})
    const chunks: Buffer[] = []
    for await (const chunk of response.res) chunks.push(chunk as Buffer)
    if (response.status === 403) return {pushed: 0, denied: true}
    if (response.status !== 200) throw new PeerHttpError(response.status, 'push', `The device answered ${response.status}.`)
    const result = JSON.parse(Buffer.concat(chunks).toString('utf8')) as {stored?: number}
    return {pushed: result.stored ?? 0, denied: false}
  } finally {
    release()
  }
}

/** One full pass for one library: pull, then push what the peer lacks. */
export async function syncLibrary(client: PeerClient, lib: NetLibrary, signal?: AbortSignal): Promise<SyncReport> {
  const pulled = await pullSegments(client, lib, signal)
  const report: SyncReport = {pulled: pulled.stored, pushed: 0, rejected: pulled.rejected}
  if (pulled.denied) report.denied = 'read'
  if (pulled.remote) {
    // our vector may have grown by what we just took in; the peer's vector (before it answered) is what it lacks of ours
    const pushed = await pushSegments(client, lib, pulled.remote, signal)
    report.pushed = pushed.pushed
    if (pushed.denied && !report.denied) report.denied = 'write'
  }
  return report
}
