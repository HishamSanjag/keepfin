// Sign-in to a cloud account on the desktop (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 3 and 7.1 #4): OAuth 2.0 authorization code
// with PKCE (S256) in the system browser (RFC 8252), never an embedded web view. A one-shot listener on the loopback address
// receives the redirect; `state` is checked before anything else happens. Google and Microsoft use a random port; Dropbox needs
// a registered redirect, so it uses the fixed port 53682 and falls back to 53683, 43682 and 33682 (Windows reserves blocks of
// the 49152+ dynamic range for Hyper-V, WSL and Docker, often covering both 5368x ports at once). The code is exchanged for tokens with the
// verifier; the tokens go back to the caller, which stores them (accounts.ts). Nothing here writes or logs a token.
import crypto from 'node:crypto'
import http from 'node:http'
import type {AddressInfo} from 'node:net'
import {cloud} from '../../shared/core/cloud.js'
import type {CloudRequest} from '../../shared/core/cloud.js'
import {tokenCall} from './oauth.js'
import type {OAuthEnv, OAuthProvider, ProviderBuildConfig, TokenSet} from './oauth.js'

export const DROPBOX_PORTS = [53682, 53683, 43682, 33682]
export const SIGN_IN_TIMEOUT_MS = 5 * 60_000

export type SignInFailure = 'unavailable' | 'cancelled' | 'timeout' | 'state-mismatch' | 'denied' | 'port-busy' | 'no-code' | 'failed'

export class SignInError extends Error {
  constructor(readonly code: SignInFailure, message: string) {
    super(message)
    this.name = 'SignInError'
  }
}

export interface SignInOptions {
  provider: OAuthProvider
  /** From the build configuration; a provider without a client id is unavailable. */
  config: ProviderBuildConfig | undefined
  openExternal(url: string): Promise<void>
  env?: OAuthEnv
  timeoutMs?: number
  signal?: AbortSignal
  dropboxPorts?: number[]
  randomBytes?: (n: number) => Buffer
  /** Microsoft: the tenant segment (default `consumers`, personal accounts). */
  tenant?: string
  /** Called with the authorization URL just before it is opened (tests read it). */
  onUrl?: (url: string) => void
}

const PAGE = (title: string, body: string) =>
  `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Keepfin</title>` +
  `<style>body{font:16px/1.5 system-ui,sans-serif;display:grid;place-items:center;min-height:100vh;margin:0;background:#101318;color:#e8ecf2}` +
  `main{max-width:26rem;padding:2rem}h1{font-size:1.25rem;margin:0 0 .5rem}p{margin:0;color:#aab3c2}</style></head>` +
  `<body><main><h1>${title}</h1><p>${body}</p></main></body></html>`

const SUCCESS = PAGE('You are signed in', 'You can close this tab and go back to Keepfin.')
const FAILURE = (why: string) => PAGE('Keepfin could not sign you in', `${why} You can close this tab and try again in Keepfin.`)

type Callback = {ok: true; code: string} | {ok: false; reason: string; error?: string; description?: string}

/** Listens once on the loopback address. Resolves with the callback's verdict (the core checks `state`). */
async function listen(
  expectedState: string,
  provider: OAuthProvider,
  ports: number[],
  provide: (redirectUri: string) => void,
): Promise<{result: Promise<Callback>; close(): void}> {
  let settle!: (cb: Callback) => void
  const result = new Promise<Callback>((resolve) => (settle = resolve))
  let done = false
  const handler: http.RequestListener = (req, res) => {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1')
    if (req.method !== 'GET' || url.pathname !== '/callback') {
      res.writeHead(404, {'content-type': 'text/plain'})
      res.end('Not found')
      return
    }
    const verdict = cloud<Callback>(provider, 'parseCallback', {url: req.url ?? '', expectedState})
    const html = verdict.ok ? SUCCESS : FAILURE(verdict.reason === 'state-mismatch' ? 'This sign-in did not start in Keepfin.' : verdict.reason === 'denied' ? 'Access was not allowed.' : 'The sign-in did not finish.')
    res.writeHead(verdict.ok ? 200 : 400, {'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'referrer-policy': 'no-referrer'})
    res.end(html)
    // The first callback decides: later requests are ignored.
    if (!done) {
      done = true
      settle(verdict)
    }
  }
  const servers: http.Server[] = []
  const open = (port: number) =>
    new Promise<http.Server>((resolve, reject) => {
      const s = http.createServer(handler)
      s.once('error', reject)
      s.listen(port, '127.0.0.1', () => resolve(s))
    })
  let main: http.Server | undefined
  let lastError: unknown
  for (const port of ports) {
    try {
      main = await open(port)
      break
    } catch (error) {
      lastError = error
      if ((error as NodeJS.ErrnoException).code !== 'EADDRINUSE' && (error as NodeJS.ErrnoException).code !== 'EACCES') break
    }
  }
  if (!main) throw new SignInError('port-busy', `Keepfin could not listen for the sign-in on this computer${lastError ? ` (${(lastError as NodeJS.ErrnoException).code ?? 'error'})` : ''}.`)
  servers.push(main)
  const port = (main.address() as AddressInfo).port
  // Microsoft registers loopback redirects as `http://localhost`; the browser may resolve that name to ::1, so listen there too.
  if (provider === 'graph') {
    try {
      const v6 = http.createServer(handler)
      await new Promise<void>((resolve, reject) => {
        v6.once('error', reject)
        v6.listen(port, '::1', () => resolve())
      })
      servers.push(v6)
    } catch {
      // no IPv6 loopback: the IPv4 listener is enough
    }
  }
  provide(`http://${provider === 'graph' ? 'localhost' : '127.0.0.1'}:${port}/callback`)
  return {
    result,
    close() {
      for (const s of servers) {
        s.closeAllConnections?.()
        s.close()
      }
    },
  }
}

/** Runs the whole sign-in and returns the tokens. Throws SignInError for every way it can end without them. */
export async function signIn(options: SignInOptions): Promise<TokenSet> {
  const {provider, config} = options
  if (!config?.clientId) throw new SignInError('unavailable', 'This provider is not available in this build of Keepfin.')
  if (options.signal?.aborted) throw new SignInError('cancelled', 'Sign-in was cancelled.')
  const random = options.randomBytes ?? ((n: number) => crypto.randomBytes(n))
  const tenant = options.tenant ?? 'consumers'
  const {verifier, challenge} = cloud<{verifier: string; challenge: string}>(provider, 'pkce', {provider, randomHex: random(48).toString('hex')})
  const state = random(24).toString('base64url')
  let redirectUri = ''
  const ports = provider === 'dropbox' ? options.dropboxPorts ?? DROPBOX_PORTS : [0]
  const listener = await listen(state, provider, ports, (uri) => (redirectUri = uri))
  let timer: NodeJS.Timeout | undefined
  let onAbort: (() => void) | undefined
  try {
    const url = cloud<{url: string}>(provider, 'authUrl', {provider, clientId: config.clientId, redirectUri, state, codeChallenge: challenge, tenant}).url
    options.onUrl?.(url)
    const ended = new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new SignInError('timeout', 'Sign-in took too long. Try again.')), options.timeoutMs ?? SIGN_IN_TIMEOUT_MS)
      onAbort = () => reject(new SignInError('cancelled', 'Sign-in was cancelled.'))
      options.signal?.addEventListener('abort', onAbort, {once: true})
    })
    ended.catch(() => undefined)
    try {
      await options.openExternal(url)
    } catch (error) {
      throw new SignInError('failed', `Keepfin could not open your browser${error instanceof Error && error.message ? ` (${error.message})` : ''}.`)
    }
    const callback = await Promise.race([listener.result, ended])
    if (!callback.ok) {
      if (callback.reason === 'state-mismatch') throw new SignInError('state-mismatch', 'The sign-in answer did not belong to this request, so it was ignored.')
      if (callback.reason === 'denied') throw new SignInError('denied', 'Access was not allowed in the browser.')
      throw new SignInError('no-code', 'The sign-in did not finish.')
    }
    const built = cloud<{request: CloudRequest}>(provider, 'tokenRequest', {
      provider,
      code: callback.code,
      redirectUri,
      clientId: config.clientId,
      verifier,
      tenant,
      ...(config.clientSecret ? {clientSecret: config.clientSecret} : {}),
    })
    return await tokenCall(provider, built.request, options.env ?? {})
  } finally {
    if (timer) clearTimeout(timer)
    if (onAbort) options.signal?.removeEventListener('abort', onAbort)
    listener.close()
  }
}
