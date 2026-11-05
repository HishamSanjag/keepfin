// keepfin-media:// request handling. Pure Node: the renderer sends opaque ids, main resolves them to files,
// and byte ranges are served here because media elements need real 206 responses to seek.
import {createReadStream} from 'node:fs'
import fs from 'node:fs/promises'
import {Readable} from 'node:stream'
import {extensionOf, mediaKindOf, MEDIA_SCHEME} from '../../shared/library.js'
import {toVtt} from './subtitles.js'

export type MediaTarget =
  | {kind: 'item' | 'art' | 'job' | 'prepared' | 'peer' | 'video'; id: string}
  | {kind: 'sub'; id: string; index: number}

const idPattern = /^[A-Za-z0-9_-]{1,100}$/

/** keepfin-media://<kind>/<id>[/<n>] → a target; anything else is undefined (and answered with 404). */
export function parseMediaUrl(input: string): MediaTarget | undefined {
  let url: URL
  try {
    url = new URL(input)
  } catch {
    return
  }
  if (url.protocol !== `${MEDIA_SCHEME}:` || url.username || url.password) return
  let parts: string[]
  try {
    parts = url.pathname.split('/').filter(Boolean).map(decodeURIComponent)
  } catch {
    return
  }
  const kind = url.hostname
  const [id, rest, ...extra] = parts
  if (!id || !idPattern.test(id) || extra.length) return
  if (kind === 'sub') {
    if (rest === undefined || !/^\d{1,3}$/.test(rest)) return
    return {kind, id, index: Number(rest)}
  }
  if (rest !== undefined) return
  return kind === 'item' || kind === 'art' || kind === 'job' || kind === 'prepared' || kind === 'peer' || kind === 'video' ? {kind, id} : undefined
}

export type ByteRange = {start: number; end: number}

/**
 * Parses a single `Range: bytes=…` header against the file size. Returns undefined when the header should be
 * ignored (absent, malformed, several ranges) and 'unsatisfiable' when it lies outside the file.
 */
export function parseRange(header: string | null | undefined, size: number): ByteRange | 'unsatisfiable' | undefined {
  if (!header) return
  const match = /^bytes=(\d*)-(\d*)$/i.exec(header.trim())
  if (!match || (match[1] === '' && match[2] === '')) return
  if (size === 0) return 'unsatisfiable'
  if (match[1] === '') {
    const suffix = Number(match[2])
    if (!Number.isSafeInteger(suffix) || suffix === 0) return 'unsatisfiable'
    return {start: Math.max(0, size - suffix), end: size - 1}
  }
  const start = Number(match[1])
  const end = match[2] === '' ? size - 1 : Number(match[2])
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end)) return
  if (start >= size) return 'unsatisfiable'
  if (end < start) return
  return {start, end: Math.min(end, size - 1)}
}

const types: Record<string, string> = {
  mp4: 'video/mp4', m4v: 'video/mp4', mov: 'video/quicktime', webm: 'video/webm', mkv: 'video/x-matroska',
  avi: 'video/x-msvideo', flv: 'video/x-flv', ts: 'video/mp2t', mpg: 'video/mpeg', mpeg: 'video/mpeg', wmv: 'video/x-ms-wmv', '3gp': 'video/3gpp',
  mp3: 'audio/mpeg', m4a: 'audio/mp4', aac: 'audio/aac', flac: 'audio/flac', ogg: 'audio/ogg', oga: 'audio/ogg', opus: 'audio/ogg',
  wav: 'audio/wav', wma: 'audio/x-ms-wma', aiff: 'audio/aiff', aif: 'audio/aiff', mka: 'audio/x-matroska',
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp', avif: 'image/avif',
}
export const contentTypeOf = (file: string) => types[extensionOf(file)] ?? 'application/octet-stream'

/** Where ids lead. Each resolver returns an absolute path from main-process state, or undefined. */
export type MediaResolver = {
  item(id: string): Promise<string | undefined> | string | undefined
  art(id: string): Promise<string | undefined> | string | undefined
  sub(id: string, index: number): Promise<string | undefined> | string | undefined
  job(id: string): Promise<string | undefined> | string | undefined
  prepared(id: string): Promise<string | undefined> | string | undefined
  /** A song's music video file, by the song's id. */
  video?(id: string): Promise<string | undefined> | string | undefined
  /** A file that is on a paired device only: its bytes are read from there, with the same Range rules (nothing is saved here). */
  peer?(id: string): PeerMediaSource | undefined
}

/** A file on a paired device, read through the network service (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md 6, "Play from this device"). */
export type PeerMediaSource = {
  size: number
  name: string
  open(range?: ByteRange): Promise<{status: number; stream: Readable}>
}

export type MediaRequest = {url: string; method: string; headers: {get(name: string): string | null}}

const baseHeaders = {
  'Accept-Ranges': 'bytes',
  'Access-Control-Allow-Origin': '*',
  'Cross-Origin-Resource-Policy': 'cross-origin',
  'Cache-Control': 'no-cache',
  'X-Content-Type-Options': 'nosniff',
}

/** A media element's request for a file on a paired device: the same 200/206/416 answers as for a local file, the bytes fetched from the device. */
async function respondFromPeer(request: MediaRequest, source: PeerMediaSource | undefined): Promise<Response> {
  if (!source || !mediaKindOf(source.name)) return notFound()
  const type = contentTypeOf(source.name)
  const range = parseRange(request.headers.get('range'), source.size)
  if (range === 'unsatisfiable') return new Response(null, {status: 416, headers: {...baseHeaders, 'Content-Type': type, 'Content-Range': `bytes */${source.size}`}})
  const start = range?.start ?? 0
  const end = range?.end ?? source.size - 1
  const length = source.size === 0 ? 0 : end - start + 1
  const headers: Record<string, string> = {...baseHeaders, 'Content-Type': type, 'Content-Length': String(length)}
  if (range) headers['Content-Range'] = `bytes ${start}-${end}/${source.size}`
  if (request.method === 'HEAD' || length === 0) return new Response(null, {status: range ? 206 : 200, headers})
  let opened: Awaited<ReturnType<PeerMediaSource['open']>>
  try {
    opened = await source.open(range ? {start, end} : undefined)
  } catch {
    return new Response(null, {status: 502, headers: {'Access-Control-Allow-Origin': '*'}})
  }
  if (opened.status !== 200 && opened.status !== 206) {
    opened.stream.resume()
    return new Response(null, {status: opened.status === 404 ? 404 : 502, headers: {'Access-Control-Allow-Origin': '*'}})
  }
  return new Response(Readable.toWeb(opened.stream) as unknown as ReadableStream, {status: range ? 206 : 200, headers})
}

const notFound = () => new Response(null, {status: 404, headers: {'Access-Control-Allow-Origin': '*'}})

export async function respondToMedia(request: MediaRequest, resolver: MediaResolver): Promise<Response> {
  if (request.method !== 'GET' && request.method !== 'HEAD')
    return new Response(null, {status: 405, headers: {Allow: 'GET, HEAD', 'Access-Control-Allow-Origin': '*'}})
  const target = parseMediaUrl(request.url)
  if (!target) return notFound()
  if (target.kind === 'peer') return respondFromPeer(request, resolver.peer?.(target.id))
  const file = await (target.kind === 'sub' ? resolver.sub(target.id, target.index) : target.kind === 'video' ? resolver.video?.(target.id) : resolver[target.kind](target.id))
  if (!file) return notFound()
  const stat = await fs.stat(file).catch(() => undefined)
  if (!stat?.isFile()) return notFound()
  // Whatever a resolver returns, only media, art and subtitle files are ever served.
  const ext = extensionOf(file)
  const servable = target.kind === 'sub' ? ['vtt', 'srt', 'ass', 'ssa'].includes(ext) : target.kind === 'art' ? !!types[ext] : !!mediaKindOf(file)
  if (!servable) return notFound()
  if (target.kind === 'sub') {
    if (stat.size > 5_000_000) return notFound()
    const body = Buffer.from(toVtt(file, await fs.readFile(file, 'utf8')), 'utf8')
    return new Response(request.method === 'HEAD' ? null : body, {
      status: 200,
      headers: {...baseHeaders, 'Content-Type': 'text/vtt; charset=utf-8', 'Content-Length': String(body.length)},
    })
  }
  const type = contentTypeOf(file)
  const range = parseRange(request.headers.get('range'), stat.size)
  if (range === 'unsatisfiable')
    return new Response(null, {status: 416, headers: {...baseHeaders, 'Content-Type': type, 'Content-Range': `bytes */${stat.size}`}})
  const start = range?.start ?? 0
  const end = range?.end ?? stat.size - 1
  const length = stat.size === 0 ? 0 : end - start + 1
  const headers: Record<string, string> = {...baseHeaders, 'Content-Type': type, 'Content-Length': String(length)}
  if (range) headers['Content-Range'] = `bytes ${start}-${end}/${stat.size}`
  if (request.method === 'HEAD' || length === 0) return new Response(null, {status: range ? 206 : 200, headers})
  const stream = Readable.toWeb(createReadStream(file, {start, end})) as unknown as ReadableStream
  return new Response(stream, {status: range ? 206 : 200, headers})
}
