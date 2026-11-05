// The token end of OAuth for cloud accounts (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 3 and 7.1 #5): the shared core describes the
// token and refresh requests and reads the answers; this file only sends them. Nothing here is logged or stored.
import {cloud} from '../../shared/core/cloud.js'
import type {CloudErrorInfo, CloudProvider, CloudRequest, CloudResponse, Failure} from '../../shared/core/cloud.js'
import {CloudError} from './http.js'

export type OAuthProvider = Extract<CloudProvider, 'drive' | 'graph' | 'dropbox'>

export const OAUTH_PROVIDERS: OAuthProvider[] = ['drive', 'graph', 'dropbox']

export interface TokenSet {
  accessToken: string
  refreshToken?: string
  /** From the provider's `expires_in`; null when it said nothing (refresh on the first 401). */
  expiresAtMs: number | null
  scope?: string
}

/** What the platform gives the sign-in and the refresh: tests send everything to a local fake authorization server. */
export interface OAuthEnv {
  fetch?: typeof fetch
  /** Maps a real provider URL to the URL to call. */
  rewrite?: (url: string) => string
  now?: () => number
}

export interface ProviderBuildConfig {
  clientId: string
  /** Google's desktop client secret: not confidential (spec 7.2 #1); PKCE protects the exchange. */
  clientSecret?: string
}
export type BuildConfig = Partial<Record<OAuthProvider, ProviderBuildConfig>>

export interface BuildVariables {
  KEEPFIN_GOOGLE_CLIENT_ID?: string
  KEEPFIN_GOOGLE_CLIENT_SECRET?: string
  KEEPFIN_MS_CLIENT_ID?: string
  KEEPFIN_DROPBOX_APP_KEY?: string
}

export function configFrom(v: BuildVariables): BuildConfig {
  const out: BuildConfig = {}
  const id = (s: string | undefined) => (typeof s === 'string' && s.trim() ? s.trim() : undefined)
  const drive = id(v.KEEPFIN_GOOGLE_CLIENT_ID)
  if (drive) out.drive = {clientId: drive, ...(id(v.KEEPFIN_GOOGLE_CLIENT_SECRET) ? {clientSecret: id(v.KEEPFIN_GOOGLE_CLIENT_SECRET)} : {})}
  const graph = id(v.KEEPFIN_MS_CLIENT_ID)
  if (graph) out.graph = {clientId: graph}
  const dropbox = id(v.KEEPFIN_DROPBOX_APP_KEY)
  if (dropbox) out.dropbox = {clientId: dropbox}
  return out
}

/**
 * The client ids this build was made with. The property reads are written out in full so that the bundler's `define` can replace
 * them at build time (gui/tsup.config.ts); unreplaced, they read the environment of a development run.
 */
export function buildConfig(): BuildConfig {
  return configFrom({
    KEEPFIN_GOOGLE_CLIENT_ID: process.env.KEEPFIN_GOOGLE_CLIENT_ID,
    KEEPFIN_GOOGLE_CLIENT_SECRET: process.env.KEEPFIN_GOOGLE_CLIENT_SECRET,
    KEEPFIN_MS_CLIENT_ID: process.env.KEEPFIN_MS_CLIENT_ID,
    KEEPFIN_DROPBOX_APP_KEY: process.env.KEEPFIN_DROPBOX_APP_KEY,
  })
}

function lower(h: Headers): Record<string, string> {
  const out: Record<string, string> = {}
  h.forEach((v, k) => {
    out[k.toLowerCase()] = v
  })
  return out
}

/** Sends one token-endpoint request and returns the tokens, or throws CloudError (kind NeedsSignIn for a refused grant). */
export async function tokenCall(provider: OAuthProvider, request: CloudRequest, env: OAuthEnv, previousRefreshToken?: string): Promise<TokenSet> {
  const doFetch = env.fetch ?? fetch
  const now = env.now ?? Date.now
  const url = env.rewrite ? env.rewrite(request.url) : request.url
  let response: CloudResponse
  try {
    const res = await doFetch(url, {method: request.method, headers: request.headers, body: request.body, signal: AbortSignal.timeout(30_000)})
    response = {status: res.status, headers: lower(res.headers), body: await res.text()}
  } catch (error) {
    throw new CloudError({kind: 'Transient', status: 0, code: 'network', message: error instanceof Error ? error.message : String(error)}, 'token')
  }
  const parsed = cloud<{ok: true; accessToken: string; refreshToken?: string; expiresAtMs?: number | null; scope?: string} | Failure>(provider, 'parseToken', {
    provider,
    response,
    nowMs: now(),
    ...(previousRefreshToken ? {previousRefreshToken} : {}),
  })
  if (!parsed.ok) throw new CloudError((parsed as Failure).error as CloudErrorInfo, 'token')
  const ok = parsed as {accessToken: string; refreshToken?: string; expiresAtMs?: number | null; scope?: string}
  return {accessToken: ok.accessToken, ...(ok.refreshToken ? {refreshToken: ok.refreshToken} : {}), expiresAtMs: ok.expiresAtMs ?? null, ...(ok.scope ? {scope: ok.scope} : {})}
}
