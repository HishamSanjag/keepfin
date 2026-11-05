// gallery-dl: image posts, carousels and galleries (Instagram, X, Pinterest, Reddit,
// Imgur, Tumblr, DeviantArt, ArtStation, Flickr…).
import path from 'node:path'
import {validateUrl, type MediaInfo} from '../../shared/contracts.js'
import {friendlyError} from '../../shared/errors.js'
import {cookieBrowsers, validateProxy, type CookieSource, type NetworkSettings} from '../../shared/policy.js'
import {providerName} from '../../shared/providers.js'
import {run} from './process.js'
import {EngineError} from './ytdlp.js'

export const GALLERY_INSPECT_LIMIT = 200

export type GalleryContext = {
  binary: string
  env: NodeJS.ProcessEnv
  network: NetworkSettings
  cookies?: CookieSource
  signal?: AbortSignal
}

function galleryArgs(ctx: GalleryContext) {
  const args = ['--config-ignore', '--retries', '4', '--http-timeout', '30']
  const proxy = validateProxy(ctx.network.proxy)
  if (proxy) args.push('--proxy', proxy)
  if (ctx.network.rateLimit > 0) args.push('--limit-rate', String(Math.max(1024, Math.round(ctx.network.rateLimit))))
  if (ctx.cookies?.file) args.push('--cookies', ctx.cookies.file)
  else if (ctx.cookies?.browser && cookieBrowsers.includes(ctx.cookies.browser))
    args.push('--cookies-from-browser', ctx.cookies.browser)
  return args
}

const str = (value: unknown, max = 500) => (typeof value === 'string' && value.trim() ? value.trim().slice(0, max) : undefined)
const imageExt = /^(jpe?g|png|webp|gif|avif|bmp)$/i

/** Parses `gallery-dl -j` output (message tuples: 2 = directory, 3 = file URL). */
export function galleryInfo(data: unknown, url: string, limit = GALLERY_INSPECT_LIMIT): MediaInfo {
  if (!Array.isArray(data)) throw new EngineError('No images were found at this link.', 'tryAnother')
  let directory: Record<string, unknown> | undefined
  const files: {url: string; meta: Record<string, unknown>}[] = []
  let declared = 0
  let failure = ''
  for (const message of data) {
    if (!Array.isArray(message)) continue
    if (message[0] === 2 && message[1] && typeof message[1] === 'object') directory ??= message[1] as Record<string, unknown>
    if (message[0] === 3 && typeof message[1] === 'string') {
      const meta = (message[2] && typeof message[2] === 'object' ? message[2] : {}) as Record<string, unknown>
      files.push({url: message[1], meta})
      if (typeof meta.count === 'number' && meta.count > declared) declared = meta.count
    }
    // A failure arrives as [-1, {error, message}] while the engine still exits 0 (Instagram's login redirect).
    if (message[0] === -1 && message[1] && typeof message[1] === 'object') failure ||= str((message[1] as Record<string, unknown>).message, 500) ?? ''
  }
  if (!files.length) throw failure ? galleryError(`ERROR: ${failure}`) : new EngineError('No images were found at this link.', 'tryAnother')
  const first = files[0].meta
  const category = str(first.category, 60) ?? str(directory?.category, 60) ?? 'gallery'
  const album = (directory?.album ?? first.album) as Record<string, unknown> | undefined
  const title =
    str(album?.title) ??
    str(directory?.title) ??
    str(first.title) ??
    str(first.description, 120) ??
    str(first.content, 120) ??
    `${providerName(url)} post`
  const uploader =
    str((first.author as {name?: unknown})?.name, 200) ??
    str((first.user as {name?: unknown})?.name, 200) ??
    str(first.username, 200) ??
    str(first.uploader, 200) ??
    str(first.owner, 200)
  const previews = files
    .filter(({url: fileUrl, meta}) => /^https:\/\//.test(fileUrl) && imageExt.test(String(meta.extension ?? meta.ext ?? fileUrl.split(/[?#]/)[0].split('.').pop())))
    .slice(0, 12)
    .map(({url: fileUrl}) => fileUrl)
  const count = Math.max(files.length, declared)
  return {
    url,
    title,
    uploader,
    thumbnail: previews[0],
    extractor: `gallery-dl:${category}`,
    provider: providerName(url, category),
    engine: 'gallery-dl',
    video: [],
    audio: [],
    subtitles: [],
    chapters: 0,
    gallery: {count, previews, truncated: count > limit || files.length >= limit},
  }
}

export async function inspectGallery(ctx: GalleryContext, url: string): Promise<MediaInfo> {
  const result = await run(ctx.binary, [...galleryArgs(ctx), '-j', '--range', `1-${GALLERY_INSPECT_LIMIT}`, '--', validateUrl(url)], {
    env: ctx.env,
    signal: ctx.signal,
    collectStdout: true,
    maxStdout: 50_000_000,
    timeoutMs: 45_000,
  })
  let data: unknown
  try {
    data = JSON.parse(result.stdout)
  } catch {
    throw galleryError(result.stderr)
  }
  if (result.code !== 0 && (!Array.isArray(data) || !data.some((m) => Array.isArray(m) && m[0] === 3))) throw galleryError(result.stderr)
  return galleryInfo(data, url)
}

function galleryError(stderr: string) {
  if (/unsupported url/i.test(stderr) || /no suitable extractor/i.test(stderr))
    return new EngineError('This link has no downloadable video or images.', 'tryAnother', stderr)
  const friendly = friendlyError(stderr.replace(/^\[[\w.-]+\]\[error\]\s*/gim, 'ERROR: '))
  return new EngineError(friendly.message, friendly.action, stderr, friendly.retryable, friendly.engine)
}

export async function downloadGallery(
  ctx: GalleryContext,
  url: string,
  folder: string,
  expected: number | undefined,
  onProgress: (done: number, total?: number) => void,
): Promise<{files: string[]}> {
  const files: string[] = []
  const result = await run(ctx.binary, [...galleryArgs(ctx), '-D', folder, '--', validateUrl(url)], {
    env: ctx.env,
    signal: ctx.signal,
    onStdoutLine: (line) => {
      // Downloaded files are printed as paths; already-present files as "# path".
      const file = line.replace(/^#\s+/, '').trim()
      if (path.isAbsolute(file) && !files.includes(file)) {
        files.push(file)
        onProgress(files.length, expected && expected >= files.length ? expected : undefined)
      }
    },
  })
  if (result.code !== 0 && !files.length) throw galleryError(result.stderr)
  if (!files.length) throw new EngineError('No images were downloaded from this link.', 'tryAnother', result.stderr)
  return {files}
}
