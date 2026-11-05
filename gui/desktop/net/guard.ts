// The network destination check for links that came from another device (spec 6 and 9.1 #6). A link is untrusted input: only http and
// https, no credentials, and no destination on a loopback, private, link-local or multicast address. The rules are the core's
// (UrlPolicy: `checkDownloadUrl`, `checkResolved`, `checkRedirect`); this file adds the I/O: it resolves each name, and follows the
// redirects one hop at a time, checking every hop's addresses before a byte is sent there, and connecting only to an address it checked
// (so a name that answers differently the second time, DNS rebinding, cannot move the connection somewhere else).
//
// What it cannot do: the download engine resolves names again by itself. The guard therefore runs right before the engine starts
// network work for such a job (`EngineContext.guard`), but a name that rebinds in the milliseconds between the two is not caught
// (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md 9.4).
import dns from 'node:dns'
import http from 'node:http'
import https from 'node:https'
import net from 'node:net'
import {checkDownloadUrl, checkRedirect, checkResolved} from '../../shared/core/net.js'

export class UrlRefused extends Error {
  constructor(
    readonly reason: string,
    message: string,
  ) {
    super(message)
    this.name = 'UrlRefused'
  }
}

const WORDS: Record<string, string> = {
  'bad-url': 'That is not a valid link.',
  scheme: 'Only http and https links can be downloaded.',
  credentials: 'Links with a name and password in them are not accepted.',
  'no-host': 'The link has no address.',
  'local-name': 'The link points to a name on a private network.',
  'too-many-redirects': 'The link redirects too many times.',
}

export function wordsFor(reason: string): string {
  if (reason.startsWith('address:')) return 'The link leads to an address on a private network, so it was not opened.'
  return WORDS[reason] ?? 'That link was not accepted.'
}

export interface GuardDeps {
  /** All addresses a name resolves to (DNS). Tests replace it. */
  resolve?: (host: string) => Promise<string[]>
  /** Looks at one hop: the status and the Location header of the answer to a request that was sent to [address]. Tests replace it. */
  hop?: (url: URL, address: string, signal?: AbortSignal) => Promise<{status: number; location?: string}>
  maxRedirects?: number
  timeoutMs?: number
}

const defaultResolve = async (host: string): Promise<string[]> => (await dns.promises.lookup(host, {all: true, verbatim: true})).map((a) => a.address)

/** One request, sent to the address that was checked, with the name kept for the certificate and the Host header. The body is never read. */
function defaultHop(url: URL, address: string, signal?: AbortSignal, timeoutMs = 10_000): Promise<{status: number; location?: string}> {
  return new Promise((resolve, reject) => {
    const mod = url.protocol === 'https:' ? https : http
    const request = mod.request(
      url,
      {
        method: 'GET',
        headers: {range: 'bytes=0-0', 'user-agent': 'Mozilla/5.0', accept: '*/*'},
        // the connection goes to the address that was checked, whatever the name says now
        lookup: ((_host: string, options: unknown, callback: (error: Error | null, address: string | dns.LookupAddress[], family?: number) => void) => {
          const family = net.isIP(address)
          if ((options as {all?: boolean})?.all) callback(null, [{address, family}])
          else callback(null, address, family)
        }) as never,
        signal,
        timeout: timeoutMs,
        rejectUnauthorized: true,
      },
      (response) => {
        const location = typeof response.headers.location === 'string' ? response.headers.location : undefined
        response.destroy()
        resolve({status: response.statusCode ?? 0, location})
      },
    )
    request.on('timeout', () => request.destroy(new Error('timeout')))
    request.on('error', reject)
    request.end()
  })
}

/**
 * Checks [text] and everything it redirects to. Resolves to the final link; throws UrlRefused with a plain reason. A hop that cannot
 * be reached is not a refusal (the link may simply be down): the check ends there with what it has, because the engine will fail the
 * same way, and nothing past that hop was ever contacted.
 */
export async function guardUrl(text: string, deps: GuardDeps = {}, signal?: AbortSignal): Promise<string> {
  const resolve = deps.resolve ?? defaultResolve
  const first = checkDownloadUrl(text)
  if (!first.allow || !first.href) throw new UrlRefused(first.reason, wordsFor(first.reason))
  let current = first.href
  const max = deps.maxRedirects ?? 8
  for (let hops = 0; ; hops++) {
    const url = new URL(current)
    const host = url.hostname.replace(/^\[|\]$/g, '')
    let addresses: string[]
    if (net.isIP(host)) addresses = [host]
    else {
      try {
        addresses = await resolve(host)
      } catch {
        throw new UrlRefused('address:unresolved', 'The link\'s address could not be found.')
      }
    }
    const resolved = checkResolved(addresses)
    if (!resolved.allow) throw new UrlRefused(resolved.reason, wordsFor(resolved.reason))
    let answer: {status: number; location?: string}
    try {
      answer = await (deps.hop ?? ((u, a, s) => defaultHop(u, a, s, deps.timeoutMs)))(url, addresses[0], signal)
    } catch {
      return current // unreachable: nothing beyond this hop was contacted
    }
    if (answer.status < 300 || answer.status >= 400 || !answer.location) return current
    // the next hop: its own addresses must be public too, before anything is sent there
    const next = checkDownloadUrl(new URL(answer.location, current).href)
    if (!next.allow || !next.href) throw new UrlRefused(next.reason, wordsFor(next.reason))
    const nextUrl = new URL(next.href)
    const nextHost = nextUrl.hostname.replace(/^\[|\]$/g, '')
    let nextAddresses: string[]
    if (net.isIP(nextHost)) nextAddresses = [nextHost]
    else {
      try {
        nextAddresses = await resolve(nextHost)
      } catch {
        throw new UrlRefused('address:unresolved', 'The link\'s address could not be found.')
      }
    }
    const redirect = checkRedirect(current, answer.location, nextAddresses, hops + 1)
    if (!redirect.allow || !redirect.href) throw new UrlRefused(redirect.reason, wordsFor(redirect.reason))
    if (hops + 1 > max) throw new UrlRefused('too-many-redirects', wordsFor('too-many-redirects'))
    current = redirect.href
  }
}
