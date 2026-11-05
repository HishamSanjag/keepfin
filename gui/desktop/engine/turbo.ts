// Turbo transfer: parallel HTTP range requests for direct (non-fragmented) streams.
// Each stream is written to `<target>.turbo` with a resumable segment map, then renamed
// to exactly the per-format file name yt-dlp expects, so a following yt-dlp run with
// --load-info-json reports "already downloaded" and only merges/post-processes.
//
// The temporary name deliberately differs from yt-dlp's `.part`: yt-dlp resumes a
// `.part` by appending from its size, which would corrupt a sparse turbo file.
import fs from 'node:fs/promises'

export type TurboStream = {
  url: string
  headers: Record<string, string>
  /** Exact byte size when known; otherwise probed with a 1-byte range request. */
  size?: number
  /** Final per-format path yt-dlp will look for. */
  target: string
}

export type TurboProgress = {downloadedBytes: number; totalBytes: number; speed?: number; eta?: number}

export type TurboOptions = {
  connections: number
  /** Segment size in bytes; clamped to 4–10 MB unless overridden for tests. */
  segmentSize?: number
  signal?: AbortSignal
  fetch?: typeof fetch
  onProgress?: (progress: TurboProgress) => void
  /** Attempts per segment before giving up (and falling back to yt-dlp). */
  attempts?: number
  /** Base backoff in ms; doubled per attempt. */
  backoffMs?: number
  /** Abort a segment when no bytes arrive for this long. */
  idleTimeoutMs?: number
}

/** Any turbo failure: the caller discards turbo files and runs plain yt-dlp. */
export class TurboError extends Error {}

type SegmentMap = {version: 1; size: number; segment: number; done: number[]}

type Segment = {stream: StreamState; index: number; start: number; end: number}

type StreamState = {
  spec: TurboStream
  size: number
  segment: number
  done: Set<number>
  handle: fs.FileHandle
  temp: string
  mapFile: string
  dirty: boolean
}

const MB = 1024 * 1024

export function segmentSizeFor(size: number, connections: number): number {
  // Aim for ~3 segments per connection so slow connections can be outpaced, within 4–10 MB.
  const ideal = Math.ceil(size / Math.max(1, connections * 3))
  return Math.min(10 * MB, Math.max(4 * MB, ideal))
}

export function turboFiles(target: string) {
  return {temp: `${target}.turbo`, map: `${target}.turbo.json`}
}

async function probeSize(stream: TurboStream, fetcher: typeof fetch, signal?: AbortSignal): Promise<number> {
  const response = await fetcher(stream.url, {
    headers: {...stream.headers, Range: 'bytes=0-0'},
    signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(20_000)]) : AbortSignal.timeout(20_000),
  })
  await response.body?.cancel().catch(() => {})
  if (response.status !== 206) throw new TurboError(`Range requests are not supported (HTTP ${response.status}).`)
  const total = Number(response.headers.get('content-range')?.match(/\/(\d+)$/)?.[1])
  if (!Number.isFinite(total) || total <= 0) throw new TurboError('The server did not report a size.')
  return total
}

async function readMap(file: string): Promise<SegmentMap | undefined> {
  try {
    const map = JSON.parse(await fs.readFile(file, 'utf8')) as SegmentMap
    if (map.version === 1 && Array.isArray(map.done) && map.size > 0 && map.segment > 0) return map
  } catch {}
  return
}

async function open(spec: TurboStream, size: number, connections: number, override?: number): Promise<StreamState> {
  const {temp, map: mapFile} = turboFiles(spec.target)
  const previous = await readMap(mapFile)
  const tempStat = await fs.stat(temp).catch(() => undefined)
  const resumable = previous && previous.size === size && tempStat?.size === size
  const segment = resumable ? previous.segment : override ?? segmentSizeFor(size, connections)
  const handle = await fs.open(temp, resumable ? 'r+' : 'w+')
  if (!resumable) await handle.truncate(size)
  return {
    spec,
    size,
    segment,
    done: new Set(resumable ? previous.done.filter((n) => Number.isInteger(n) && n >= 0) : []),
    handle,
    temp,
    mapFile,
    dirty: false,
  }
}

async function saveMap(stream: StreamState) {
  if (!stream.dirty) return
  stream.dirty = false
  const map: SegmentMap = {version: 1, size: stream.size, segment: stream.segment, done: [...stream.done].sort((a, b) => a - b)}
  await fs.writeFile(stream.mapFile + '.tmp', JSON.stringify(map))
  await fs.rename(stream.mapFile + '.tmp', stream.mapFile)
}

const sleep = (ms: number, signal?: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    const timer = setTimeout(resolve, ms)
    signal?.addEventListener(
      'abort',
      () => {
        clearTimeout(timer)
        reject(signal.reason)
      },
      {once: true},
    )
  })

export async function turboDownload(streams: TurboStream[], options: TurboOptions): Promise<void> {
  const fetcher = options.fetch ?? fetch
  const connections = Math.min(16, Math.max(1, Math.round(options.connections)))
  const attempts = options.attempts ?? 5
  const backoff = options.backoffMs ?? 500
  const idleTimeout = options.idleTimeoutMs ?? 20_000
  const signal = options.signal
  signal?.throwIfAborted()

  const states: StreamState[] = []
  try {
    for (const spec of streams) {
      const size = spec.size ?? (await probeSize(spec, fetcher, signal))
      states.push(await open(spec, size, connections, options.segmentSize))
    }
    const segments: Segment[] = []
    for (const stream of states) {
      const count = Math.ceil(stream.size / stream.segment)
      for (let index = 0; index < count; index++)
        if (!stream.done.has(index))
          segments.push({stream, index, start: index * stream.segment, end: Math.min(stream.size, (index + 1) * stream.segment) - 1})
    }
    const total = states.reduce((sum, s) => sum + s.size, 0)
    let completed = states.reduce(
      (sum, s) => sum + [...s.done].reduce((acc, i) => acc + Math.min(s.segment, s.size - i * s.segment), 0),
      0,
    )
    let inFlight = 0
    const samples: [number, number][] = []
    let lastReport = 0
    const report = (force = false) => {
      const now = performance.now()
      if (!force && now - lastReport < 200) return
      lastReport = now
      const downloaded = completed + inFlight
      samples.push([now, downloaded])
      while (samples.length > 2 && now - samples[0][0] > 3000) samples.shift()
      const [t0, b0] = samples[0]
      const speed = now - t0 > 250 ? ((downloaded - b0) * 1000) / (now - t0) : undefined
      options.onProgress?.({
        downloadedBytes: downloaded,
        totalBytes: total,
        speed,
        eta: speed ? (total - downloaded) / speed : undefined,
      })
    }
    const mapTimer = setInterval(() => {
      for (const stream of states) void saveMap(stream).catch(() => {})
    }, 1000)
    let fatal: Error | undefined
    const failFast = new AbortController()
    const combined = signal ? AbortSignal.any([signal, failFast.signal]) : failFast.signal

    const fetchSegment = async (segment: Segment) => {
      const {stream} = segment
      const expected = segment.end - segment.start + 1
      for (let attempt = 1; ; attempt++) {
        let received = 0
        const idle = new AbortController()
        let idleTimer = setTimeout(() => idle.abort(new Error('stalled')), idleTimeout)
        try {
          const response = await fetcher(stream.spec.url, {
            headers: {...stream.spec.headers, Range: `bytes=${segment.start}-${segment.end}`},
            signal: AbortSignal.any([combined, idle.signal]),
          })
          if (response.status !== 206) {
            await response.body?.cancel().catch(() => {})
            const permanent = [200, 401, 404, 410, 416].includes(response.status) || (response.status === 403 && attempt >= 2)
            const error = new TurboError(`Range request failed (HTTP ${response.status}).`)
            if (permanent || attempt >= attempts) throw error
            throw Object.assign(error, {retry: true})
          }
          const range = response.headers.get('content-range')?.match(/^bytes (\d+)-(\d+)\/(\d+|\*)$/)
          if (!range || Number(range[1]) !== segment.start || Number(range[2]) !== segment.end)
            throw new TurboError('The server returned an unexpected byte range.')
          if (range[3] !== '*' && Number(range[3]) !== stream.size)
            throw new TurboError('The stream size changed during the transfer.')
          if (!response.body) throw new TurboError('Empty response body.')
          let position = segment.start
          for await (const chunk of response.body as unknown as AsyncIterable<Uint8Array>) {
            clearTimeout(idleTimer)
            idleTimer = setTimeout(() => idle.abort(new Error('stalled')), idleTimeout)
            if (received + chunk.byteLength > expected) throw new TurboError('The server sent more data than requested.')
            await stream.handle.write(chunk, 0, chunk.byteLength, position)
            position += chunk.byteLength
            received += chunk.byteLength
            inFlight += chunk.byteLength
            report()
          }
          if (received !== expected) throw Object.assign(new TurboError('Segment ended early.'), {retry: true})
          inFlight -= received
          completed += received
          stream.done.add(segment.index)
          stream.dirty = true
          report()
          return
        } catch (error) {
          inFlight -= received
          if (signal?.aborted) throw signal.reason
          if (fatal) throw fatal
          const retryable =
            !(error instanceof TurboError) || (error as {retry?: boolean}).retry === true
          if (!retryable || attempt >= attempts)
            throw error instanceof TurboError ? error : new TurboError(`Segment failed: ${(error as Error).message}`)
          await sleep(backoff * 2 ** (attempt - 1), combined)
        } finally {
          clearTimeout(idleTimer)
        }
      }
    }

    let next = 0
    const worker = async () => {
      while (!fatal && next < segments.length) {
        const segment = segments[next++]
        try {
          await fetchSegment(segment)
        } catch (error) {
          if (!fatal) {
            fatal = error as Error
            failFast.abort(fatal)
          }
          return
        }
      }
    }
    try {
      await Promise.all(Array.from({length: Math.min(connections, Math.max(1, segments.length))}, worker))
    } finally {
      clearInterval(mapTimer)
      for (const stream of states) await saveMap(stream).catch(() => {})
    }
    if (signal?.aborted) throw signal.reason
    if (fatal) throw fatal
    report(true)
    for (const stream of states) {
      await stream.handle.close()
      const stat = await fs.stat(stream.temp)
      if (stat.size !== stream.size) throw new TurboError('Downloaded size does not match the expected size.')
      await fs.rm(stream.spec.target, {force: true})
      await fs.rename(stream.temp, stream.spec.target)
      await fs.rm(stream.mapFile, {force: true})
    }
  } finally {
    for (const stream of states) await stream.handle.close().catch(() => {})
  }
}

/** Removes turbo temporaries (after a fallback or cancellation). */
export async function discardTurbo(targets: string[]) {
  for (const target of targets) {
    const {temp, map} = turboFiles(target)
    await fs.rm(temp, {force: true}).catch(() => {})
    await fs.rm(map, {force: true}).catch(() => {})
    await fs.rm(map + '.tmp', {force: true}).catch(() => {})
  }
}
