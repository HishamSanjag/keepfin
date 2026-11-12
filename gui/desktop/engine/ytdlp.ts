// yt-dlp process helpers: inspection (-J), streaming flat discovery and the download
// runner with machine-readable progress.
import {createHash} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {validateUrl, type PlaylistEntry} from '../../shared/contracts.js'
import {friendlyError} from '../../shared/errors.js'
import type {RawInfo} from '../../shared/formats.js'
import {
  networkArgs,
  toolArgs,
  type CookieSource,
  type NetworkSettings,
} from '../../shared/policy.js'
import {unavailableReason} from '../../shared/core/formats.js'
import {isYouTube, providerName} from '../../shared/providers.js'
import {cleanTitle} from '../../shared/title.js'
import {run} from './process.js'
import {ProgressParser, type RunnerProgress} from './progress.js'

export type YtDlpContext = {
  binary: string
  ffmpeg?: string
  jsRuntime?: string
  env: NodeJS.ProcessEnv
  cacheDir?: string
  network: NetworkSettings
  cookies?: CookieSource
  signal?: AbortSignal
}

/** Error carrying a friendly message plus the suggested follow-up action. */
export class EngineError extends Error {
  constructor(
    message: string,
    readonly action: ReturnType<typeof friendlyError>['action'],
    readonly stderr = '',
    /** Worth retrying automatically; defaults from the suggested action. */
    readonly retryable: boolean = action === 'retry' || action === 'updateEngine',
    /** The engine itself looks broken, so updating it may help. */
    readonly engine: boolean = action === 'updateEngine',
    /** Rate limit or bot check: changes retry pacing. */
    readonly kind?: 'rateLimited' | 'botCheck',
    /** Custom retry delays in ms; its length is the retry limit. */
    readonly backoff?: readonly number[],
  ) {
    super(message)
  }
  static from(stderr: string) {
    const friendly = friendlyError(stderr)
    return new EngineError(friendly.message, friendly.action, stderr, friendly.retryable, friendly.engine, friendly.kind, friendly.backoff)
  }
}

export function baseArgs(ctx: YtDlpContext) {
  return [
    ...toolArgs({ffmpeg: ctx.ffmpeg, jsRuntime: ctx.jsRuntime, cacheDir: ctx.cacheDir}),
    ...networkArgs(ctx.network, ctx.cookies),
  ]
}

// ---------------------------------------------------------------------------
// Info JSON cache (reused by downloads via --load-info-json)
// ---------------------------------------------------------------------------

export const INFO_MAX_AGE = 30 * 60_000

export class InfoCache {
  constructor(
    private dir: string,
    private now: () => number = Date.now,
  ) {}
  private file(url: string) {
    return path.join(this.dir, `info-${createHash('sha256').update(url).digest('hex').slice(0, 32)}.json`)
  }
  async put(url: string, raw: string): Promise<string> {
    await fs.mkdir(this.dir, {recursive: true})
    const file = this.file(url)
    await fs.writeFile(file + '.tmp', raw)
    await fs.rename(file + '.tmp', file)
    return file
  }
  /** Path of a fresh dump for this URL, if any. */
  async get(url: string, maxAge = INFO_MAX_AGE): Promise<string | undefined> {
    const file = this.file(url)
    try {
      const stat = await fs.stat(file)
      if (this.now() - stat.mtimeMs < maxAge) return file
      await fs.rm(file, {force: true})
    } catch {}
    return
  }
  async prune(maxAge = INFO_MAX_AGE) {
    const names = await fs.readdir(this.dir).catch(() => [] as string[])
    await Promise.all(
      names
        .filter((name) => /^info-[a-f0-9]{32}\.json(\.tmp)?$/.test(name))
        .map(async (name) => {
          const file = path.join(this.dir, name)
          const stat = await fs.stat(file).catch(() => undefined)
          if (stat && this.now() - stat.mtimeMs >= maxAge) await fs.rm(file, {force: true}).catch(() => {})
        }),
    )
  }
  async clear() {
    const names = await fs.readdir(this.dir).catch(() => [] as string[])
    await Promise.all(
      names.filter((name) => name.startsWith('info-')).map((name) => fs.rm(path.join(this.dir, name), {force: true})),
    )
  }
}

// ---------------------------------------------------------------------------
// Inspection
// ---------------------------------------------------------------------------

export async function dumpInfo(
  ctx: YtDlpContext,
  url: string,
  mode: 'single' | 'flat-probe',
): Promise<{info: RawInfo; raw: string}> {
  const args = [
    ...baseArgs(ctx),
    '-J',
    ...(mode === 'single' ? ['--no-playlist'] : ['--flat-playlist', '--playlist-items', '1']),
    '--',
    validateUrl(url),
  ]
  const result = await run(ctx.binary, args, {
    env: ctx.env,
    signal: ctx.signal,
    collectStdout: true,
    maxStdout: 80_000_000,
    timeoutMs: 90_000,
  })
  if (result.code !== 0 || !result.stdout.trim()) throw EngineError.from(result.stderr)
  try {
    const info = JSON.parse(result.stdout) as RawInfo
    if (!info || typeof info !== 'object') throw new Error()
    return {info, raw: result.stdout}
  } catch {
    throw new EngineError('The engine returned unreadable media information.', 'updateEngine', result.stderr)
  }
}

// ---------------------------------------------------------------------------
// Flat discovery
// ---------------------------------------------------------------------------

type FlatEntry = Record<string, unknown>

const text = (value: unknown, max = 1000) => (typeof value === 'string' && value.trim() ? value.slice(0, max) : undefined)

const youtubeId = /^[\w-]{11}$/

export function youtubeThumbnail(info: FlatEntry, url: string): string | undefined {
  let host = ''
  let fromUrl: string | null = null
  try {
    const parsed = new URL(url)
    host = parsed.hostname.replace(/^(www|m|music)\./, '')
    fromUrl = host === 'youtu.be' ? parsed.pathname.slice(1) : parsed.searchParams.get('v')
  } catch {}
  if (info.ie_key !== 'Youtube' && host !== 'youtube.com' && host !== 'youtu.be') return
  const id = typeof info.id === 'string' && youtubeId.test(info.id) ? info.id : fromUrl
  return id && youtubeId.test(id) ? `https://i.ytimg.com/vi/${id}/mqdefault.jpg` : undefined
}

/** Posts use their whole caption as the title: collection entries get the same clean title as single items. */
function entryTitle(info: FlatEntry, url: string): string | undefined {
  const raw = text(info.title, 2000)
  if (!raw) return undefined
  const extractor = typeof info.ie_key === 'string' ? info.ie_key : undefined
  return cleanTitle(raw, text(info.uploader, 300) ?? text(info.channel, 300), providerName(url, extractor))
}

/** Flat metadata is display data, never a format authorization or shell command. */
export function discoveryEntry(info: FlatEntry, position: number): PlaylistEntry | undefined {
  if (!info || typeof info !== 'object') return
  let input = text(info.webpage_url, 4096) ?? text(info.url, 4096)
  // Some extractors only give an id for YouTube entries.
  if (!input && info.ie_key === 'Youtube' && typeof info.id === 'string' && /^[\w-]{11}$/.test(info.id))
    input = `https://www.youtube.com/watch?v=${info.id}`
  if (!input || !/^https?:\/\//i.test(input)) return
  let url: string
  try {
    url = validateUrl(input)
  } catch {
    return
  }
  let thumbnail: string | undefined
  const thumbs = Array.isArray(info.thumbnails) ? (info.thumbnails as {url?: unknown}[]) : []
  for (const candidate of [info.thumbnail, ...thumbs.map((t) => t?.url).reverse()]) {
    try {
      thumbnail = validateUrl(candidate)
      break
    } catch {}
  }
  // Personal YouTube Music playlists (and some signed-in pages) list tracks without artwork;
  // every YouTube video has a stable thumbnail derived from its id.
  thumbnail ??= youtubeThumbnail(info, url)
  const duration = typeof info.duration === 'number' && Number.isFinite(info.duration) && info.duration > 0 ? info.duration : undefined
  const uploader = text(info.uploader) ?? text(info.channel)
  const unavailable = unavailableReason(text(info.title, 2000), uploader, duration, info.ie_key === 'Youtube' || isYouTube(url))
  return {
    url,
    position,
    // "[Private video]" is YouTube's placeholder, not a title
    title: unavailable ? undefined : entryTitle(info, url),
    uploader,
    thumbnail,
    duration,
    ...(unavailable ? {unavailable} : {}),
  }
}

export type DiscoveryPage = {
  /** Entries delivered this page (after de-duplication by the caller). */
  count: number
  truncated: boolean
  /** Set when the page ended early (deadline or engine error) but kept entries. */
  partialError?: string
}

export async function discoverPage(
  ctx: YtDlpContext,
  url: string,
  page: {start: number; end: number; deadlineMs?: number},
  onEntry: (entry: PlaylistEntry) => void,
  onMeta: (meta: {title?: string; uploader?: string}) => void,
): Promise<DiscoveryPage> {
  const deadline = new AbortController()
  const timer = setTimeout(() => deadline.abort(new Error('deadline')), page.deadlineMs ?? 60_000)
  const signal = ctx.signal ? AbortSignal.any([ctx.signal, deadline.signal]) : deadline.signal
  // Ask for one extra entry: receiving it proves more pages exist.
  const args = [
    ...baseArgs({...ctx, network: {...ctx.network, fragments: 1}}),
    '--flat-playlist',
    '--lazy-playlist',
    '-j',
    '--playlist-items',
    `${page.start}:${page.end + 1}`,
    '--extractor-retries',
    '2',
    '--',
    validateUrl(url),
  ]
  let index = 0
  let count = 0
  let truncated = false
  let metaSent = false
  let bytes = 0
  const onLine = (line: string) => {
    bytes += line.length
    if (!line.startsWith('{') || bytes > 64_000_000) return
    let info: FlatEntry
    try {
      info = JSON.parse(line)
    } catch {
      return
    }
    const position = page.start + index++
    if (position > page.end) {
      truncated = true
      return
    }
    if (!metaSent) {
      metaSent = true
      onMeta({
        title: text(info.playlist_title, 300) ?? text(info.playlist, 300),
        uploader: text(info.playlist_uploader, 300) ?? text(info.playlist_channel, 300),
      })
    }
    const entry = discoveryEntry(info, position)
    if (entry) {
      count++
      onEntry(entry)
    }
  }
  try {
    const result = await run(ctx.binary, args, {env: ctx.env, signal, onStdoutLine: onLine})
    if (result.code !== 0) {
      const error = EngineError.from(result.stderr)
      if (!count) throw error
      return {count, truncated, partialError: error.message}
    }
    return {count, truncated}
  } catch (error) {
    if (ctx.signal?.aborted) throw ctx.signal.reason
    if (deadline.signal.aborted) {
      if (!count) throw new EngineError('The collection took too long to load. Try again.', 'retry')
      // A page that hit the deadline may have more entries behind it.
      return {count, truncated: true, partialError: 'Stopped after 60 seconds; load more to continue.'}
    }
    throw error
  } finally {
    clearTimeout(timer)
  }
}

// ---------------------------------------------------------------------------
// Download runner
// ---------------------------------------------------------------------------

export {ProgressParser, type RunnerProgress} from './progress.js'

export type RunOutcome = {file?: string; files: string[]; stderr: string}

export async function runDownload(
  ctx: {binary: string; env: NodeJS.ProcessEnv; signal?: AbortSignal},
  args: string[],
  parser: ProgressParser,
  events: {onProgress: (progress: RunnerProgress) => void; onPhase: (phase: string) => void},
): Promise<RunOutcome> {
  const result = await run(ctx.binary, args, {
    env: ctx.env,
    signal: ctx.signal,
    onStdoutLine: (line) => {
      const change = parser.line(line)
      if (change === 'progress') events.onProgress(parser.progress())
      else if (change === 'phase' && parser.phase) events.onPhase(parser.phase)
    },
    onStderrLine: (line) => {
      // Post-processor messages occasionally arrive on stderr.
      const change = parser.line(line)
      if (change === 'phase' && parser.phase) events.onPhase(parser.phase)
    },
  })
  if (result.code !== 0) throw EngineError.from(result.stderr)
  if (!parser.file) throw new EngineError('The engine finished without reporting a file.', 'retry', result.stderr)
  return {file: parser.file, files: parser.files.filter((file) => file !== parser.file), stderr: result.stderr}
}

// ---------------------------------------------------------------------------
// Selection preview (used by Turbo to learn exactly what yt-dlp will download)
// ---------------------------------------------------------------------------

export type RequestedFormat = {
  formatId: string
  ext: string
  protocol: string
  url: string
  size?: number
  headers: Record<string, string>
  fragmented: boolean
}

export type Selection = {filename: string; formats: RequestedFormat[]}

export async function resolveSelection(
  ctx: {binary: string; env: NodeJS.ProcessEnv; signal?: AbortSignal},
  args: string[],
): Promise<Selection> {
  const result = await run(ctx.binary, [...args, '--simulate', '-j'], {
    env: ctx.env,
    signal: ctx.signal,
    collectStdout: true,
    maxStdout: 80_000_000,
    timeoutMs: 60_000,
  })
  if (result.code !== 0) throw EngineError.from(result.stderr)
  const info = JSON.parse(result.stdout.trim().split(/\r?\n/).at(-1) ?? '{}') as Record<string, unknown>
  const filename = text(info._filename, 4096) ?? text(info.filename, 4096)
  if (!filename) throw new Error('Could not resolve the output file name.')
  const requested = (Array.isArray(info.requested_formats) ? info.requested_formats : [info]) as Record<string, unknown>[]
  const formats = requested.map((f): RequestedFormat => {
    const headers: Record<string, string> = {}
    if (f.http_headers && typeof f.http_headers === 'object')
      for (const [key, value] of Object.entries(f.http_headers as Record<string, unknown>))
        if (typeof value === 'string' && /^[\w-]{1,100}$/.test(key) && !/[\r\n]/.test(value)) headers[key] = value
    const protocol = String(f.protocol ?? '')
    return {
      formatId: String(f.format_id ?? ''),
      ext: String(f.ext ?? ''),
      protocol,
      url: typeof f.url === 'string' ? f.url : '',
      // Only an exact size is trusted for range splitting; approximations are probed.
      size: typeof f.filesize === 'number' && Number.isFinite(f.filesize) && f.filesize > 0 ? f.filesize : undefined,
      headers,
      fragmented: Array.isArray(f.fragments) || !/^https?$/.test(protocol),
    }
  })
  return {filename, formats}
}

/**
 * yt-dlp names each stream of a merge `<stem>.f<format_id>.<ext>`; a single stream is
 * written directly to the final name.
 */
export function streamFileName(selection: Selection, format: RequestedFormat): string {
  if (selection.formats.length === 1) return selection.filename
  const stem = selection.filename.replace(/\.[^.\\/]+$/, '')
  return `${stem}.f${format.formatId}.${format.ext}`
}
