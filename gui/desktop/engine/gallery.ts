// gallery-dl: image posts, carousels and galleries (Instagram, X, Pinterest, Reddit,
// Imgur, Tumblr, DeviantArt, ArtStation, Flickr…).
import path from 'node:path'
import {validateUrl, type MediaInfo} from '../../shared/contracts.js'
import {friendlyError} from '../../shared/errors.js'
import {cookieArgs, validateProxy, type CookieSource, type NetworkSettings} from '../../shared/policy.js'
import {chosenRange, readGallery} from '../../shared/core/gallery.js'
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

/**
 * The engine prints the paths it saved in the console's code page, whatever PYTHONUTF8 says: a non-Latin title becomes `????` and no
 * path matches a file (issue #49). This makes it print UTF-8, which the pipe reader decodes.
 */
export const UTF8_STDOUT = ['-o', 'output.stdout=utf-8']

export function galleryArgs(ctx: GalleryContext) {
  const args = ['--config-ignore', '--retries', '4', '--http-timeout', '30']
  const proxy = validateProxy(ctx.network.proxy)
  if (proxy) args.push('--proxy', proxy)
  if (ctx.network.rateLimit > 0) args.push('--limit-rate', String(Math.max(1024, Math.round(ctx.network.rateLimit))))
  args.push(...cookieArgs(ctx.cookies))
  return args
}

/** Parses `gallery-dl -j` output (message tuples: 2 = directory, 3 = file URL); the reading itself is the shared core's. */
export function galleryInfo(data: unknown, url: string, limit = GALLERY_INSPECT_LIMIT): MediaInfo {
  const result = readGallery(data, url, limit)
  if (result.kind === 'post') return result.media
  // A failure arrives as [-1, {error, message}] while the engine still exits 0 (Instagram's login redirect).
  if (result.kind === 'failed') throw galleryError(`ERROR: ${result.message}`)
  throw new EngineError('No images were found at this link.', 'tryAnother')
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

/** The arguments that save a post (or the chosen items of it) into `folder`. */
export function galleryDownloadArgs(ctx: GalleryContext, url: string, folder: string, items?: number[]) {
  const range = items && chosenRange(items)
  return [...galleryArgs(ctx), ...UTF8_STDOUT, '-D', folder, ...(range ? ['--range', range] : []), '--', validateUrl(url)]
}

export async function downloadGallery(
  ctx: GalleryContext,
  url: string,
  folder: string,
  expected: number | undefined,
  items: number[] | undefined,
  onProgress: (done: number, total?: number) => void,
): Promise<{files: string[]}> {
  return runGalleryDownload(ctx, galleryDownloadArgs(ctx, url, folder, items), expected, onProgress)
}

/** The arguments that save direct file links into `folder`, named by the link's own file name; never with cookies. */
export function galleryLinksArgs(ctx: GalleryContext, links: string[], folder: string) {
  const {cookies: _signedIn, ...signedOut} = ctx
  return [...galleryArgs(signedOut), ...UTF8_STDOUT, '-f', '{filename}.{extension}', '-D', folder, '--', ...links.map(validateUrl)]
}

/** Saves direct links (a signed-out post's photos) into `folder`. */
export async function downloadLinks(
  ctx: GalleryContext,
  links: string[],
  folder: string,
  onProgress: (done: number, total?: number) => void,
): Promise<{files: string[]}> {
  return runGalleryDownload(ctx, galleryLinksArgs(ctx, links, folder), links.length, onProgress)
}

async function runGalleryDownload(
  ctx: GalleryContext,
  args: string[],
  expected: number | undefined,
  onProgress: (done: number, total?: number) => void,
): Promise<{files: string[]}> {
  const files: string[] = []
  const result = await run(ctx.binary, args, {
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