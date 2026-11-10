// Executes the core's request descriptions over fetch (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 4): retries with back-off and
// jitter that honour Retry-After, one token refresh on an expired access token, a per-account rate limiter that slows down on
// 429, and at most 4 parallel transfers per account. The core decides what a failure means (its error mapping); this file only
// waits and repeats.
import {cloud} from '../../shared/core/cloud.js'
import type {CloudErrorInfo, CloudProvider, CloudRequest, CloudResponse, Failure} from '../../shared/core/cloud.js'

export class CloudError extends Error {
  constructor(readonly info: CloudErrorInfo, readonly op = '') {
    super(`${info.kind}${info.code ? ' (' + info.code + ')' : ''}${info.message ? ': ' + info.message : ''}${op ? ' during ' + op : ''}`)
    this.name = 'CloudError'
  }
  get kind() {
    return this.info.kind
  }
}

/** The account a request runs for. Tokens are the caller's business; the executor only asks for the header. */
export interface CloudAccount {
  readonly id: string
  readonly provider: CloudProvider
  /** The Authorization header value, or null when there is none. */
  authorization(): Promise<string | null>
  /** Gets a new access token after a 401. True when a new token is available. Calls are serialised by the account. */
  refresh(): Promise<boolean>
}

export class Semaphore {
  private waiting: Array<() => void> = []
  private used = 0
  constructor(readonly limit: number) {}
  async run<T>(job: () => Promise<T>): Promise<T> {
    if (this.used >= this.limit) await new Promise<void>((resolve) => this.waiting.push(resolve))
    this.used++
    try {
      return await job()
    } finally {
      this.used--
      this.waiting.shift()?.()
    }
  }
}

/** Spaces requests at least `intervalMs` apart; a 429 doubles the spacing (up to a cap), successes ease it back. */
export class RateLimiter {
  private nextFree = 0
  private interval: number
  constructor(private readonly baseIntervalMs = 0, private readonly maxIntervalMs = 5000, private readonly now: () => number = Date.now, private readonly sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms))) {
    this.interval = baseIntervalMs
  }
  get currentIntervalMs() {
    return this.interval
  }
  async acquire(): Promise<void> {
    const t = this.now()
    const start = Math.max(t, this.nextFree)
    this.nextFree = start + this.interval
    if (start - t > 0) await this.sleep(start - t)
  }
  penalize(): void {
    this.interval = Math.min(this.maxIntervalMs, Math.max(50, this.interval * 2))
  }
  relax(): void {
    if (this.interval > this.baseIntervalMs) this.interval = Math.max(this.baseIntervalMs, Math.floor(this.interval * 0.9))
  }
}

export interface HttpOptions {
  fetch?: typeof fetch
  /** Maps a real provider URL to the URL to call (tests send everything to local fakes). */
  rewrite?: (url: string) => string
  sleep?: (ms: number) => Promise<void>
  random?: () => number
  now?: () => number
  maxAttempts?: number
  baseDelayMs?: number
  maxDelayMs?: number
  limiter?: RateLimiter
  transfers?: Semaphore
}

export interface SendIo {
  /** Reads the slice a request's `bodyRef` names. */
  readRange?: (offset: number, length: number) => Promise<Buffer>
  signal?: AbortSignal
  /** "upload" makes a 404 mean "the session is gone". */
  context?: string
}

export class CloudHttp {
  private readonly doFetch: typeof fetch
  private readonly sleep: (ms: number) => Promise<void>
  private readonly random: () => number
  readonly limiter: RateLimiter
  readonly transfers: Semaphore
  private readonly serial = new Semaphore(1)
  /** Number of requests actually sent, retries included (tests read it). */
  sent = 0
  private refreshing: Promise<boolean> | null = null

  constructor(readonly account: CloudAccount, private readonly options: HttpOptions = {}) {
    this.doFetch = options.fetch ?? fetch
    this.sleep = options.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)))
    this.random = options.random ?? Math.random
    this.limiter = options.limiter ?? new RateLimiter(0, 5000, options.now, this.sleep)
    this.transfers = options.transfers ?? new Semaphore(4)
  }

  /** Backs off for attempt n (0-based): full jitter over an exponential window. */
  backoffMs(attempt: number): number {
    const base = this.options.baseDelayMs ?? 400
    const cap = this.options.maxDelayMs ?? 30_000
    return Math.floor(this.random() * Math.min(cap, base * 2 ** attempt))
  }

  /** Runs [job] as one of at most 4 parallel transfers of this account. */
  transfer<T>(job: () => Promise<T>): Promise<T> {
    return this.transfers.run(job)
  }

  private async refreshOnce(): Promise<boolean> {
    this.refreshing ??= this.account.refresh().finally(() => {
      this.refreshing = null
    })
    return this.refreshing
  }

  private async attempt(req: CloudRequest, io: SendIo): Promise<Response> {
    const headers: Record<string, string> = {...req.headers}
    // The body's length is set by fetch from the body itself.
    for (const k of Object.keys(headers)) if (k.toLowerCase() === 'content-length') delete headers[k]
    if (req.auth) {
      const a = await this.account.authorization()
      if (a) headers['Authorization'] = a
    }
    let body: Buffer | string | undefined = req.body
    if (req.bodyRef) {
      if (!io.readRange) throw new Error('the request names a file slice but no reader was given')
      body = await io.readRange(req.bodyRef.offset, req.bodyRef.length)
    }
    const url = this.options.rewrite ? this.options.rewrite(req.url) : req.url
    this.sent++
    return this.doFetch(url, {method: req.method, headers, body: body as BodyInit | undefined, redirect: req.redirect === 'manual' ? 'manual' : 'follow', signal: io.signal})
  }

  /**
   * Sends [req] and returns the final response (any status below 400 is success to the caller; so are statuses the caller
   * lists in [accept]). Retries rate limits, transient failures and network errors; throws [CloudError] for the rest... but
   * the failure is returned, not thrown, once attempts run out or the failure is not retryable, so parsers can read it.
   */
  async send(req: CloudRequest, io: SendIo = {}, accept: number[] = []): Promise<CloudResponse> {
    return req.serial ? this.serial.run(() => this.sendNow(req, io, accept)) : this.sendNow(req, io, accept)
  }

  private async sendNow(req: CloudRequest, io: SendIo, accept: number[]): Promise<CloudResponse> {
    const max = this.options.maxAttempts ?? 6
    let refreshed = false
    for (let attempt = 0; ; attempt++) {
      await this.limiter.acquire()
      let res: Response
      let text = ''
      try {
        res = await this.attempt(req, io)
        text = req.redirect === 'manual' || res.status < 200 ? '' : await res.text()
      } catch (error) {
        if (io.signal?.aborted) throw error
        if (attempt + 1 >= max) throw new CloudError({kind: 'Transient', status: 0, code: 'network', message: (error as Error).message})
        await this.sleep(this.backoffMs(attempt))
        continue
      }
      const response: CloudResponse = {status: res.status, headers: lowerHeaders(res.headers), body: text}
      if (response.status < 400 || accept.includes(response.status)) {
        this.limiter.relax()
        return response
      }
      const failure = cloud<Failure>(this.account.provider, 'parseDone', {response, context: io.context ?? ''})
      const info = failure.ok === false ? failure.error : ({kind: 'Fatal', status: response.status} as CloudErrorInfo)
      if (info.kind === 'NeedsSignIn' && info.refresh && !refreshed) {
        refreshed = true
        if (await this.refreshOnce()) continue
      }
      const retryable = info.kind === 'RateLimited' || info.kind === 'Transient'
      if (info.kind === 'RateLimited') this.limiter.penalize()
      if (!retryable || attempt + 1 >= max) return response
      const wait = Math.max(info.retryAfterMs ?? 0, this.backoffMs(attempt))
      await this.sleep(wait)
    }
  }

  /** Like [send], but GETs the body as a stream (downloads). The caller must consume or cancel `res.body`. */
  async stream(req: CloudRequest, io: SendIo = {}): Promise<Response> {
    const max = this.options.maxAttempts ?? 6
    let refreshed = false
    for (let attempt = 0; ; attempt++) {
      await this.limiter.acquire()
      let res: Response
      try {
        res = await this.attempt(req, io)
      } catch (error) {
        if (io.signal?.aborted) throw error
        if (attempt + 1 >= max) throw new CloudError({kind: 'Transient', status: 0, code: 'network', message: (error as Error).message})
        await this.sleep(this.backoffMs(attempt))
        continue
      }
      if (res.status < 400) {
        this.limiter.relax()
        return res
      }
      const text = await res.text()
      const response: CloudResponse = {status: res.status, headers: lowerHeaders(res.headers), body: text}
      if (res.status === 416) return new Response(text, {status: 416, headers: res.headers})
      const failure = cloud<Failure>(this.account.provider, 'parseDone', {response, context: io.context ?? 'download'})
      const info = failure.ok === false ? failure.error : ({kind: 'Fatal', status: response.status} as CloudErrorInfo)
      if (info.kind === 'NeedsSignIn' && info.refresh && !refreshed) {
        refreshed = true
        if (await this.refreshOnce()) continue
      }
      if (info.kind === 'RateLimited') this.limiter.penalize()
      const retryable = info.kind === 'RateLimited' || info.kind === 'Transient'
      if (!retryable || attempt + 1 >= max) throw new CloudError(info, 'download')
      await this.sleep(Math.max(info.retryAfterMs ?? 0, this.backoffMs(attempt)))
    }
  }
}

export function lowerHeaders(h: Headers): Record<string, string> {
  const out: Record<string, string> = {}
  h.forEach((v, k) => {
    out[k.toLowerCase()] = v
  })
  return out
}
