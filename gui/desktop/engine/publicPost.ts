// Public Instagram posts read without a sign-in: the download engine prints the post's pages, the shared core reads them.
import {validateUrl, type MediaInfo} from '../../shared/contracts.js'
import {publicPostSupported, readPublicPost, type GalleryResult} from '../../shared/core/gallery.js'
import {run} from './process.js'
import {baseArgs, type YtDlpContext} from './ytdlp.js'

const PUBLIC_POST_LIMIT = 200

export {publicPostSupported}

/** The arguments that make the engine print the post's pages without looking for formats; never with cookies. */
export function publicPostArgs(ctx: YtDlpContext, url: string): string[] {
  const {cookies: _signedIn, ...signedOut} = ctx
  return [...baseArgs(signedOut), '--dump-pages', '--skip-download', '--ignore-no-formats-error', '--no-warnings', '--', validateUrl(url)]
}

/** Reads the post from the engine's printed output: the media, or undefined for the usual route. */
export function publicPostFromOutput(output: string, url: string): MediaInfo | undefined {
  const result: GalleryResult = readPublicPost(output, url, PUBLIC_POST_LIMIT)
  return result.kind === 'post' ? result.media : undefined
}

/** The post's photos and videos as direct links, or undefined whenever anything fails (only a cancel throws). */
export async function inspectPublicPost(ctx: YtDlpContext, url: string): Promise<MediaInfo | undefined> {
  try {
    const result = await run(ctx.binary, publicPostArgs(ctx, url), {
      env: ctx.env,
      signal: ctx.signal,
      collectStdout: true,
      maxStdout: 50_000_000,
      timeoutMs: 45_000,
    })
    return publicPostFromOutput(`${result.stdout}\n${result.stderr}`, url)
  } catch (error) {
    if (ctx.signal?.aborted) throw error
    return undefined
  }
}

/** Direct links of the chosen positions (all when undefined), read again because the links expire; undefined for the usual route. */
export async function publicPostLinks(ctx: YtDlpContext, url: string, chosen?: number[], expected?: number): Promise<string[] | undefined> {
  const items = (await inspectPublicPost(ctx, url))?.gallery?.items
  const links = items?.filter((item) => !chosen || chosen.includes(item.position)).map((item) => item.url)
  // A post that changed since its preview would save other files than the ones chosen.
  const wanted = chosen?.length ?? expected
  return links?.length && (wanted === undefined || links.length === wanted) ? links : undefined
}
