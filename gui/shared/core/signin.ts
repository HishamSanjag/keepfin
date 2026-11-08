// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the desktop's sign-in code. This is the only file that imports the core's signin package.
//
// The core owns the site table (links, cookie domains, session cookie names) and the decisions; it never sees cookie values.
import {signInIsSignedIn, signInNotice, signInOrder, signInRetry, signInSite, signInSiteKeys, signInSiteOf, JsSignInSource} from '../../../build/core-js/keepfin-core.mjs'

export type SignInSite = {key: string; name: string; domains: string[]; sessionCookies: string[]; loginUrl: string}

/** A cookie as a store lists it, without its value. `expiry` is Unix seconds; 0 is a session cookie. */
export type SignInCookie = {name: string; domain: string; expiry: number}

/** Keepfin's own sign-in, or a browser's (`browser` is the engine's browser id, `profile` its profile folder). */
export type SignInSource = {kind: 'keepfin'} | {kind: 'browser'; browser: string; profile?: string; label: string}

type Site = NonNullable<ReturnType<typeof signInSite>>

const site = (s: Site): SignInSite => ({key: s.key, name: s.name, domains: Array.from(s.domains), sessionCookies: Array.from(s.sessionCookies), loginUrl: s.loginUrl})

const toCore = (s: SignInSource) => (s.kind === 'keepfin' ? new JsSignInSource('keepfin', null, null, null) : new JsSignInSource('browser', s.browser, s.profile ?? null, s.label))

/** The site a link belongs to, or undefined for any other site. */
export function siteOf(url: string): SignInSite | undefined {
  const s = signInSiteOf(url)
  return s ? site(s) : undefined
}

export function siteByKey(key: string): SignInSite | undefined {
  const s = signInSite(key)
  return s ? site(s) : undefined
}

/** Every site Keepfin can sign in to, in display order. */
export function allSites(): SignInSite[] {
  return Array.from(signInSiteKeys(), (key) => siteByKey(key)!)
}

/** Whether the cookies hold a live sign-in for the site `key` at `nowSeconds`. */
export function signedIn(key: string, cookies: SignInCookie[], nowSeconds: number): boolean {
  return signInIsSignedIn(key, cookies.map((c) => c.name), cookies.map((c) => c.domain), cookies.map((c) => c.expiry), nowSeconds)
}

/** The sources in the order they are tried: Keepfin's own first, then browsers (Firefox first). */
export function orderSources<T extends SignInSource>(sources: T[]): T[] {
  return Array.from(signInOrder(sources.map(toCore)), (i) => sources[i])
}

/** The row text for a source: "Signed in" or "Signed in with Firefox". */
export function sourceNotice(source: SignInSource): string {
  return signInNotice(toCore(source))
}

/**
 * The sign-in to retry a failed request with, or undefined for none: only after a sign-in failure (`errorAction` 'signIn'),
 * while the automatic use is on and no manual sign-in is set, on a known site, each source once. `available` holds the
 * sources with a live sign-in for the link's site.
 */
export function retrySource<T extends SignInSource>(
  url: string,
  errorAction: string | undefined,
  rule: {autoOn: boolean; manualOverride: boolean},
  available: T[],
  tried: SignInSource[],
): T | undefined {
  const index = signInRetry(url, errorAction ?? null, rule.autoOn, rule.manualOverride, available.map(toCore), tried.map(toCore))
  return index < 0 ? undefined : available[index]
}
