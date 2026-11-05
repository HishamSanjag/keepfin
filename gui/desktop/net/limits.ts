// The denial-of-service budgets of spec 9.1 #7. Every number is a default that tests shrink through NetServerOptions.limits.

export interface Limits {
  /** Open connections, all peers and strangers together. */
  maxConnections: number
  /** Open connections per paired peer. */
  perPeerConnections: number
  /** Open connections per source address for keys that are not paired. */
  perStrangerAddress: number
  /** TLS handshake deadline. */
  handshakeMs: number
  /** Request line and headers deadline. */
  headersMs: number
  /** A whole request (headers and body) may take at most this long for the small endpoints. */
  requestMs: number
  /** An idle keep-alive connection is closed after this. */
  idleMs: number
  maxHeaderBytes: number
  /** JSON bodies (pairing, mutations). */
  maxJsonBytes: number
  /** One pushed segment file. */
  maxSegmentBytes: number
  /** Frames in one push. */
  maxPushSegments: number
  /** Bytes in one push. */
  maxPushBytes: number
  /** Longest version vector accepted, in characters. */
  maxVectorChars: number
  /** The slowest body a peer may send: below this for a few seconds it is cut off. */
  minBodyBytesPerSecond: number
  /** Parallel transfers (segments, revisions, uploads) per peer. */
  transfersPerPeer: number
  /** Requests per peer per minute (a paired peer). */
  requestsPerMinute: number
  /** Requests per source address per minute from keys that are not paired. */
  strangerRequestsPerMinute: number
  /** GET /v1/announce per source address per minute (anyone may ask; the scan of a whole network asks once per host). */
  announcePerMinute: number
  /** Signature verifications (segments pushed) per peer per minute. */
  verifiesPerMinute: number
  /** Pairing attempts per peer key and per source address inside [pairWindowMs]. */
  pairAttempts: number
  pairWindowMs: number
  /** Event sockets per peer, and bytes queued on one before it is dropped. */
  eventSocketsPerPeer: number
  eventBackpressureBytes: number
  /** The biggest Range a single request may ask for. */
  maxRangeBytes: number
}

export const DEFAULT_LIMITS: Limits = {
  maxConnections: 64,
  perPeerConnections: 8,
  perStrangerAddress: 3,
  handshakeMs: 10_000,
  headersMs: 10_000,
  requestMs: 5 * 60_000,
  idleMs: 60_000,
  maxHeaderBytes: 16 * 1024,
  maxJsonBytes: 256 * 1024,
  maxSegmentBytes: 16 * 1024 * 1024,
  maxPushSegments: 256,
  maxPushBytes: 256 * 1024 * 1024,
  maxVectorChars: 32 * 1024,
  minBodyBytesPerSecond: 1024,
  transfersPerPeer: 4,
  requestsPerMinute: 1200,
  strangerRequestsPerMinute: 30,
  announcePerMinute: 120,
  verifiesPerMinute: 4000,
  pairAttempts: 5,
  pairWindowMs: 10 * 60_000,
  eventSocketsPerPeer: 2,
  eventBackpressureBytes: 1024 * 1024,
  maxRangeBytes: 1024 * 1024 * 1024,
}

/** At most [max] events inside [windowMs]; each key has its own window. Memory is bounded by [maxKeys]. */
export class AttemptLimiter {
  private readonly hits = new Map<string, number[]>()

  constructor(
    private readonly max: number,
    private readonly windowMs: number,
    private readonly now: () => number,
    private readonly maxKeys = 4096,
  ) {}

  /** Records an attempt and answers whether it is allowed. */
  take(key: string): boolean {
    const at = this.now()
    const list = (this.hits.get(key) ?? []).filter((t) => at - t < this.windowMs)
    if (list.length >= this.max) {
      this.hits.set(key, list)
      return false
    }
    list.push(at)
    this.hits.set(key, list)
    if (this.hits.size > this.maxKeys) this.sweep(at)
    return true
  }

  /** How many are left for [key] without recording one. */
  left(key: string): number {
    const at = this.now()
    return Math.max(0, this.max - (this.hits.get(key) ?? []).filter((t) => at - t < this.windowMs).length)
  }

  private sweep(at: number): void {
    for (const [key, list] of this.hits) if (!list.some((t) => at - t < this.windowMs)) this.hits.delete(key)
    // still over: drop the oldest keys, a window that long is not worth the memory
    while (this.hits.size > this.maxKeys) this.hits.delete(this.hits.keys().next().value as string)
  }
}
