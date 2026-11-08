// Download arguments around the shared core's plan. The core (gui/shared/core/args.ts) validates the structured intent and
// builds the format selection and the option arguments; this file adds what only the desktop knows: tool paths, the
// JS runtime, network settings, cookies, proxy and the progress/output flags.
import type {CookieBrowser, DownloadOptions, FormatRequest, Settings} from './contracts.js'
import {formatArgs, metadataArgs} from './core/args.js'

export {
  formatArgs,
  formatLabel,
  jobQuote,
  metadataArgs,
  metadataLiteral,
  presetFormat,
  validateFormatRequest,
  validateOptions,
  type FormatArgsExtra,
} from './core/args.js'

export const PROGRESS_PREFIX = 'KEEPFIN-P|'
export const FILE_PREFIX = 'KEEPFIN-F|'

export const cookieBrowsers: CookieBrowser[] = [
  'firefox',
  'chrome',
  'edge',
  'brave',
  'opera',
  'vivaldi',
  'chromium',
]

export function validateProxy(value: unknown): string {
  if (value === undefined || value === null || value === '') return ''
  if (typeof value !== 'string' || value.length > 2048) throw new Error('Enter a valid proxy URL.')
  let url: URL
  try {
    url = new URL(value.trim())
  } catch {
    throw new Error('Enter a proxy such as http://host:8080 or socks5://host:1080.')
  }
  if (!['http:', 'https:', 'socks5:', 'socks5h:'].includes(url.protocol) || !url.hostname)
    throw new Error('Proxy must use http, https, socks5 or socks5h.')
  if (url.pathname !== '/' && url.pathname !== '') throw new Error('Proxy URLs cannot contain a path.')
  return url.href.replace(/\/$/, '')
}

/** A cookies file, or a browser's cookies; `profile` (Firefox only) is the absolute path of one profile folder. */
export type CookieSource = {file?: string; browser?: CookieBrowser; profile?: string}

export type NetworkSettings = Pick<Settings, 'fragments' | 'rateLimit' | 'proxy'>

export function networkArgs(network: NetworkSettings, cookies?: CookieSource): string[] {
  const args = [
    '--socket-timeout',
    '20',
    '--retries',
    '10',
    '--fragment-retries',
    '10',
    '--retry-sleep',
    'http:exp=1:20',
    '--retry-sleep',
    'fragment:exp=1:20',
    '--concurrent-fragments',
    String(Math.min(16, Math.max(1, Math.round(network.fragments || 1)))),
  ]
  if (network.rateLimit > 0) args.push('--limit-rate', String(Math.max(1024, Math.round(network.rateLimit))))
  const proxy = validateProxy(network.proxy)
  if (proxy) args.push('--proxy', proxy)
  args.push(...cookieArgs(cookies))
  return args
}

const oneOf = <T extends string>(value: unknown, allowed: readonly T[], message: string): T => {
  if (typeof value === 'string' && (allowed as readonly string[]).includes(value)) return value as T
  throw new Error(message)
}

/**
 * The `--cookies-from-browser` value both engines read: `BROWSER` or `firefox:<profile folder>`. The profile must be an
 * absolute path (a Windows drive path or a POSIX path) without control characters or the engines' `::` container separator.
 */
export function cookieBrowserSpec(cookies?: CookieSource): string | undefined {
  if (!cookies?.browser) return undefined
  const browser = oneOf(cookies.browser, cookieBrowsers, 'Choose a supported browser.')
  const profile = cookies.profile
  if (profile === undefined) return browser
  const absolute = /^[A-Za-z]:[\\/]/.test(profile) || profile.startsWith('/')
  if (browser !== 'firefox' || !absolute || profile.length > 1024 || profile.includes('::') || /[\u0000-\u001f]/.test(profile))
    throw new Error('Choose a supported browser profile.')
  return `${browser}:${profile}`
}

export function cookieArgs(cookies?: CookieSource): string[] {
  if (cookies?.file) return ['--cookies', cookies.file]
  const spec = cookieBrowserSpec(cookies)
  return spec ? ['--cookies-from-browser', spec] : []
}

export type ToolArgs = {ffmpeg?: string; jsRuntime?: string; cacheDir?: string}

/**
 * The download engine writes its output (the saved path, titles) in the console's code page when it is not a terminal,
 * dropping every character that page cannot hold. Environment variables do not change that on Windows; this flag does.
 */
export const ENCODING_ARGS = ['--encoding', 'utf-8']

export function toolArgs(tools: ToolArgs): string[] {
  const args = ['--ignore-config', ...ENCODING_ARGS]
  if (tools.ffmpeg) args.push('--ffmpeg-location', tools.ffmpeg)
  if (tools.jsRuntime) args.push('--js-runtimes', `node:${tools.jsRuntime}`)
  if (tools.cacheDir) args.push('--cache-dir', tools.cacheDir)
  return args
}

export const progressTemplate =
  `download:${PROGRESS_PREFIX}%(info.format_id)s|%(progress.status)s|%(progress.downloaded_bytes)s|` +
  '%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|' +
  '%(progress.fragment_index)s|%(progress.fragment_count)s'

export type DownloadArgsInput = {
  /** Either the page URL or a cached `-J` dump for instant starts. */
  source: {url: string} | {infoJson: string}
  format: FormatRequest
  options: DownloadOptions
  /** Absolute output template ending in `.%(ext)s`. */
  template: string
  chapterTemplate?: string
  network: NetworkSettings
  cookies?: CookieSource
  tools: ToolArgs
  /** Selection preview for Turbo: same choices, no progress/print flags (add --simulate -j). */
  preview?: boolean
  /** The source is YouTube / YouTube Music: audio covers are cropped square. */
  youtube?: boolean
  /** Seconds to pause between metadata requests (gentle YouTube batches). */
  sleepRequests?: number
  /** The clean job title, embedded as the title tag when metadata embedding is on. */
  title?: string
}

export function buildDownloadArgs(input: DownloadArgsInput): string[] {
  const {format} = input
  if (format.type === 'gallery') throw new Error('Gallery downloads use gallery-dl.')
  const reporting = input.preview
    ? []
    : [
        '--newline',
        '--no-quiet',
        '--progress',
        '--progress-delta',
        '0.25',
        '--progress-template',
        progressTemplate,
        '--print',
        `after_move:${FILE_PREFIX}%(filepath)s`,
        '--no-simulate',
      ]
  return [
    ...toolArgs(input.tools),
    ...networkArgs(input.network, input.cookies),
    ...formatArgs(format, input.options, {squareCover: input.youtube, chapterTemplate: input.chapterTemplate}),
    ...(input.options.embedMetadata && !input.preview ? metadataArgs(input.title) : []),
    ...(input.sleepRequests && input.sleepRequests > 0 ? ['--sleep-requests', String(input.sleepRequests)] : []),
    '--no-playlist',
    '--no-mtime',
    '--continue',
    ...reporting,
    '-o',
    input.template,
    ...('infoJson' in input.source ? ['--load-info-json', input.source.infoJson] : ['--', input.source.url]),
  ]
}
