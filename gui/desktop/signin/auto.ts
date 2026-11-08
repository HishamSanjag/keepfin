// Automatic use of a sign-in: a request first goes without one; only when it fails with the sign-in action is a sign-in
// used, once. The decision (which failure, which source, off switch, manual override) is the shared core's
// (`retrySource`); this file finds the sources that exist on this PC and runs the second try.
import type {CookieBrowser} from '../../shared/contracts.js'
import {retrySource, siteOf, type SignInSource} from '../../shared/core/signin.js'
import type {CookieSource} from '../../shared/policy.js'
import {findBrowserSignIns} from './browsers.js'

/** A sign-in Keepfin can use for a site, and how the engines are told to use it. */
export type SignInChoice = {
  source: SignInSource
  cookies: CookieSource
  /** For a sign-in that needs a private file for the length of one run (Keepfin's own): replaces `cookies`; `release` runs afterwards. */
  open?: () => Promise<{cookies: CookieSource; release: () => Promise<void>}>
}

/** One try with the choice's cookies, cleaning up after it. */
async function runWith<T>(choice: SignInChoice | undefined, run: (cookies?: CookieSource) => Promise<T>): Promise<T> {
  if (!choice) return run()
  if (!choice.open) return run(choice.cookies)
  const lease = await choice.open()
  try {
    return await run(lease.cookies)
  } finally {
    await lease.release().catch(() => {})
  }
}

/** The usable sign-ins for a site (a core key such as `instagram`). Keepfin's own sign-in joins this list with P6. */
export type SignInFinder = (siteKey: string) => Promise<SignInChoice[]>

/** Firefox profiles signed in to the site; locked browsers are never a source. */
export const findSignIns: SignInFinder = async (siteKey) =>
  (await findBrowserSignIns(siteKey)).sources.map((source) => ({source, cookies: {browser: source.browser as CookieBrowser, profile: source.profile}}))

const same = (a: SignInSource, b: SignInSource) =>
  a.kind === b.kind && (a.kind !== 'browser' || (b.kind === 'browser' && a.browser === b.browser && a.profile === b.profile))

export type SignInRequest<T> = {
  url: string
  autoOn: boolean
  /** A manual cookie source is set: it is always used and this rule does nothing. */
  manual: boolean
  find: SignInFinder
  signal?: AbortSignal
  /** The sign-in a job or inspection already recorded: used from the first try (no failing first). */
  first?: SignInSource
  /** One try; `cookies` is the sign-in to use, absent for none. */
  run: (cookies?: CookieSource) => Promise<T>
}

/** Runs the request signed out (or with the recorded sign-in), and once more with a sign-in if it fails asking for one. `used` is the sign-in that worked. */
export async function withSignIn<T>(request: SignInRequest<T>): Promise<{value: T; used?: SignInSource}> {
  const {url, find, signal} = request
  const site = siteOf(url)
  let start: SignInChoice | undefined
  if (request.first && !request.manual && site && request.autoOn) start = (await find(site.key)).find((choice) => same(choice.source, request.first!))
  try {
    return {value: await runWith(start, request.run), used: start?.source}
  } catch (error) {
    if (signal?.aborted || !site || !request.autoOn || request.manual) throw error
    const available = await find(site.key)
    const next = retrySource(url, (error as {action?: string}).action, {autoOn: request.autoOn, manualOverride: request.manual}, available.map((choice) => choice.source), start ? [start.source] : [])
    const choice = next && available.find((item) => item.source === next)
    if (!choice) throw error
    return {value: await runWith(choice, request.run), used: choice.source}
  }
}
