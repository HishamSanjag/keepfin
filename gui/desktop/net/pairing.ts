// Pairing, the answering side (spec 3 and 9.1 #3 and #4): the state machine behind the `/v1/pair/*` endpoints.
//
// Wire protocol (all JSON, inside the mutual TLS session; "asking" is the device that opened the connection):
//   POST /v1/pair/start    asking -> {versions, name, platform, libraries:[{libraryId, actor}], token? | code?}
//                          answering -> {id, commitment, name, platform, libraries, expiresAt}   (Cb = HMAC(Nb, SPKIb || SPKIa))
//   POST /v1/pair/nonce    asking -> {id, na}          answering -> {nb}   (the asking side checks Nb against Cb)
//   POST /v1/pair/confirm  asking -> {id, sig}         answering -> {sig}  (answered only after the answering person decided)
// SAS = pairSas(Na, Nb, SPKIa, SPKIb): six digits on both screens. sig = ECDSA over pairTranscript(Na, Nb, SPKIa, SPKIb) under the
// domain keepfin-pair-v1, so a confirmation is bound to this exact pair of nonces and keys. A QR (`keepfin://pair?...`) carries the
// answering side's key pin and a one-use token: the asking side pins by the QR and sends the token in /start; the answering
// person approves instead of comparing numbers. A typed code (XXXXX-XXXXX: 20 bits of one-use code token + 30 bits of the key pin) does
// the same job without a camera: code (the 20-bit number) in /start is treated exactly like a valid QR token. The code is never logged.
// Limits: unknown keys reach these endpoints only inside the 2-minute pairing mode; 5 attempts per 10 minutes per peer key and per
// source address; a session expires 2 minutes after /start; tokens carry 128 random bits, are consumed on first sight, and are
// never logged.
import {randomBytes, randomInt, randomUUID, timingSafeEqual} from 'node:crypto'
import {DOMAIN_PAIR, deviceIdOf, pairCommitment, pairPin, pairSas, pairTranscript, PAIR_NONCE_BYTES, signDomain, verifyDomain, buildPairQr, pairCodeOf} from '../../shared/core/net.js'
import {fingerprintOf, type DeviceIdentity} from './identity.js'
import {AttemptLimiter, type Limits} from './limits.js'
import {isLocalOrTailscale} from './pairSource.js'

export const PAIR_SESSION_MS = 2 * 60 * 1000
export const PAIRING_MODE_MS = 2 * 60 * 1000
/** An unrequested comparison request (no window, no code) is open for one minute only. */
export const UNREQUESTED_SESSION_MS = 60 * 1000

export interface PairLibraryRef {
  libraryId: string
  actor: string
}

export interface PairPeerInfo {
  spki: Uint8Array
  deviceId: string
  name: string
  platform: string
  libraries: PairLibraryRef[]
  /** The address the asking device connected from (a hint for the first reconnect). */
  address?: string
}

export class PairError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status = 403,
  ) {
    super(message)
  }
}

export type PairStage = 'comparing' | 'approving' | 'done' | 'failed'

interface Session {
  id: string
  method: 'sas' | 'qr'
  peer: PairPeerInfo
  address: string
  nb: Uint8Array
  commitment: Uint8Array
  na?: Uint8Array
  sas?: string
  stage: PairStage
  error?: string
  expiresAt: number
  /** Started by a device on this network / Tailscale while the window was closed and with no code. */
  unrequested: boolean
  /** A scanned code approved it: no person on this device decides. */
  autoApproved: boolean
  /** The person on this device decided (true: the numbers match / approved). */
  decision?: boolean
  decide: (ok: boolean) => void
  decided: Promise<boolean>
  timer: NodeJS.Timeout
}

export interface PairingHostOptions {
  identity: () => DeviceIdentity
  limits: Limits
  now: () => number
  libraries: () => PairLibraryRef[]
  /** Called when both sides confirmed and the asking device proved it. The caller saves the pin. */
  onPaired: (peer: PairPeerInfo, method: 'sas' | 'qr') => Promise<void>
  /** Something about the pairing state changed (the page shows it). */
  changed: () => void
  /** Budgets for unrequested requests (tests shrink them). Defaults: 10 per 10 minutes; muted for 5 minutes after 3 declined or expired in 10. */
  unrequested?: {globalMax?: number; globalWindowMs?: number; muteAfter?: number; muteWindowMs?: number; muteMs?: number}
}

export class PairingHost {
  private until = 0
  private session: Session | undefined
  private readonly tokens = new Map<string, {expiresAt: number}>()
  /** The one typed code showing now (20 bits), with the wrong guesses made against it. */
  private codeEntry: {value: number; expiresAt: number; misses: number} | undefined
  private readonly byKey: AttemptLimiter
  private readonly byAddress: AttemptLimiter
  private readonly global: AttemptLimiter
  private readonly muteAfter: number
  private readonly muteWindowMs: number
  private readonly muteMs: number
  private mutedUntil = 0
  /** When unrequested sessions ended declined or expired (completed ones do not count). */
  private misses: number[] = []

  constructor(private readonly options: PairingHostOptions) {
    const u = options.unrequested ?? {}
    this.global = new AttemptLimiter(u.globalMax ?? 10, u.globalWindowMs ?? 10 * 60_000, options.now)
    this.muteAfter = u.muteAfter ?? 3
    this.muteWindowMs = u.muteWindowMs ?? 10 * 60_000
    this.muteMs = u.muteMs ?? 5 * 60_000
    this.byKey = new AttemptLimiter(options.limits.pairAttempts, options.limits.pairWindowMs, options.now)
    this.byAddress = new AttemptLimiter(options.limits.pairAttempts, options.limits.pairWindowMs, options.now)
  }

  // ---- the window ----------------------------------------------------------------------------------------------------------

  /** Opens the explicit pairing mode (this device answers pairing requests) for 2 minutes. */
  open(): number {
    this.until = this.options.now() + PAIRING_MODE_MS
    this.options.changed()
    return this.until
  }

  close(): void {
    this.until = 0
    this.tokens.clear()
    this.codeEntry = undefined
    this.endSession('failed', 'Pairing was closed.', true, false)
    this.options.changed()
  }

  get active(): boolean {
    return this.options.now() < this.until
  }

  get windowEnd(): number | null {
    return this.active ? this.until : null
  }

  // ---- QR ------------------------------------------------------------------------------------------------------------------

  /** A one-use token and the link that carries it, and the typed code for the same two minutes. They are returned only here; they are not kept anywhere readable. */
  makeQr(host: string, port: number): {link: string; expiresAt: number; code: string} {
    for (const [key, entry] of this.tokens) if (entry.expiresAt <= this.options.now()) this.tokens.delete(key)
    this.tokens.clear() // one QR at a time: a new one replaces the last
    const token = randomBytes(16)
    const expiresAt = this.options.now() + PAIR_SESSION_MS
    this.tokens.set(token.toString('hex'), {expiresAt})
    const pin = pairPin(this.options.identity().spki)
    const link = buildPairQr({host, port, pin, token})
    const value = randomInt(0, 1 << 20)
    this.codeEntry = {value, expiresAt, misses: 0}
    return {link, expiresAt, code: pairCodeOf(value, pin)}
  }

  /** True while a typed code is showing (the announcement says so; it never says what the code is). */
  get codeShowing(): boolean {
    const c = this.codeEntry
    return !!c && c.expiresAt > this.options.now()
  }

  /** Consumes a token. True only for a token that exists, is unexpired and has not been used: and it is gone either way. */
  private consume(token: Uint8Array): boolean {
    const key = Buffer.from(token).toString('hex')
    const entry = this.tokens.get(key)
    this.tokens.delete(key)
    return !!entry && entry.expiresAt > this.options.now()
  }

  /** Consumes a typed code. A right one is gone after this either way; five wrong guesses retire the showing code (the QR stays). */
  private consumeCode(value: number): boolean {
    const c = this.codeEntry
    if (!c || c.expiresAt <= this.options.now()) {
      this.codeEntry = undefined
      return false
    }
    if (c.value === value) {
      this.codeEntry = undefined
      return true
    }
    if (++c.misses >= 5) this.codeEntry = undefined
    return false
  }
  // ---- the view for the page ------------------------------------------------------------------------------------------------

  view(): {id: string; method: 'sas' | 'qr'; stage: PairStage; peerName: string; peerFingerprint: string; sas?: string; confirmed?: boolean; error?: string; expiresAt: number; unrequested: boolean; autoApproved?: boolean} | null {
    this.sweep(false)
    const s = this.session
    if (!s) return null
    return {id: s.id, method: s.method, stage: s.stage, peerName: s.peer.name, peerFingerprint: fingerprintOf(s.peer.deviceId), sas: s.method === 'sas' ? s.sas : undefined, ...(s.method === 'sas' && s.decision === true && s.stage === 'comparing' ? {confirmed: true} : {}), error: s.error, expiresAt: s.expiresAt, unrequested: s.unrequested, ...(s.autoApproved && s.stage === 'done' ? {autoApproved: true} : {})}
  }

  /** The person closed a finished pairing (paired, or why not): the page stops showing it. An open one is left alone. */
  dismiss(): void {
    const s = this.session
    if (!s || (s.stage !== 'done' && s.stage !== 'failed')) return
    this.session = undefined
    this.options.changed()
  }

  /** The person on this device answered: the numbers match / approved (true), or they do not / declined (false). */
  decide(id: string, ok: boolean): void {
    const s = this.session
    if (!s || s.id !== id) throw new PairError('gone', 'This pairing is no longer open.', 404)
    if (s.stage === 'done' || s.stage === 'failed') return
    s.decision = ok
    s.decide(ok)
    if (!ok) this.endSession('failed', 'You said the numbers do not match, so nothing was paired.')
  }

  // ---- the endpoints --------------------------------------------------------------------------------------------------------

  start(peer: PairPeerInfo, address: string, token: Uint8Array | null, code: number | null = null): {id: string; commitment: Uint8Array; expiresAt: number; name: string; platform: string; libraries: PairLibraryRef[]} {
    this.sweep(true)
    // a closed window with no code is an unrequested request: only from this network or Tailscale, and not while muted. A refusal
    // costs no attempt: the other device may keep asking until the person opens Add a device here
    const unrequested = !this.active && !token && code === null
    if (unrequested && (!isLocalOrTailscale(address) || this.now() < this.mutedUntil)) throw new PairError('not-pairing', 'This device is not accepting new devices right now.')
    // every try while open costs an attempt, whoever the peer is and whatever it sent
    const okKey = this.byKey.take(peer.deviceId)
    const okAddress = this.byAddress.take(address)
    if (!okKey || !okAddress) throw new PairError('rate', 'Too many pairing attempts. Try again in a few minutes.', 429)
    let method: 'sas' | 'qr' = 'sas'
    if (token) {
      if (!this.consume(token)) throw new PairError('token', 'This code was already used or has expired.')
      method = 'qr'
    } else if (code !== null) {
      // a typed code is a separate secret: it never matches a token and a token never matches it
      if (!this.consumeCode(code)) throw new PairError('token', 'This code was already used or has expired.')
      method = 'qr'
    }
    if (this.session && this.session.stage !== 'done' && this.session.stage !== 'failed') throw new PairError('busy', 'Another device is pairing right now.', 409)
    if (unrequested && !this.global.take('unrequested')) throw new PairError('rate', 'Too many pairing attempts. Try again in a few minutes.', 429)
    const identity = this.options.identity()
    const nb = randomBytes(PAIR_NONCE_BYTES)
    const commitment = pairCommitment(nb, identity.spki, peer.spki)
    let decide!: (ok: boolean) => void
    const decided = new Promise<boolean>((resolve) => (decide = resolve))
    const lifeMs = unrequested ? UNREQUESTED_SESSION_MS : PAIR_SESSION_MS
    const expiresAt = this.options.now() + lifeMs
    const session: Session = {
      id: randomUUID(),
      method,
      peer: {...peer, address},
      address,
      nb,
      commitment,
      stage: method === 'qr' ? 'approving' : 'comparing',
      expiresAt,
      unrequested,
      autoApproved: method === 'qr',
      decide,
      decided,
      timer: setTimeout(() => this.expire(session.id), lifeMs),
    }
    // the scanned code is the authorization: nobody on this device needs to approve
    if (method === 'qr') {
      session.decision = true
      decide(true)
    }
    session.timer.unref?.()
    this.session = session
    this.options.changed()
    return {id: session.id, commitment, expiresAt, name: identity.name, platform: 'windows', libraries: this.options.libraries()}
  }

  nonce(peerId: string, id: string, na: Uint8Array): Uint8Array {
    const s = this.live(peerId, id)
    if (s.na) throw new PairError('state', 'The first number was already sent.', 409)
    if (na.length !== PAIR_NONCE_BYTES) throw new PairError('nonce', 'The nonce has the wrong size.', 400)
    s.na = na
    s.sas = pairSas(na, s.nb, s.peer.spki, this.options.identity().spki)
    this.options.changed()
    return s.nb
  }

  /** The asking device's person said the numbers differ: this session ends at once. */
  cancel(peerId: string, id: string): void {
    const s = this.session
    if (!s || s.id !== id || s.peer.deviceId !== peerId) return
    this.endSession('failed', `${s.peer.name} did not confirm, so nothing was paired.`)
  }

  /** Answers only after the person here decided. Returns our signature when both sides confirmed. */
  async confirm(peerId: string, id: string, sig: Uint8Array): Promise<Uint8Array> {
    const s = this.live(peerId, id)
    if (!s.na || !s.sas) throw new PairError('state', 'The numbers were not exchanged yet.', 409)
    const identity = this.options.identity()
    const transcript = pairTranscript(s.na, s.nb, s.peer.spki, identity.spki)
    // the asking device signs the transcript with its own key, which is the key of this TLS session
    if (!verifyDomain(s.peer.spki, DOMAIN_PAIR, transcript, sig)) {
      this.endSession('failed', 'The other device could not prove it was the one you meant.')
      throw new PairError('signature', 'The confirmation does not match.')
    }
    const ok = await s.decided
    if (!ok || this.session !== s || s.stage === 'failed') throw new PairError('declined', 'The pairing was declined.')
    if (this.options.now() >= s.expiresAt) throw new PairError('expired', 'The pairing took too long.', 410)
    s.stage = 'done'
    clearTimeout(s.timer)
    try {
      await this.options.onPaired(s.peer, s.method)
    } catch (error) {
      this.endSession('failed', 'Pairing could not be saved.')
      throw new PairError('save', error instanceof Error ? error.message : 'Pairing could not be saved.', 500)
    }
    this.options.changed()
    return signDomain(identity.pkcs8, DOMAIN_PAIR, transcript)
  }

  // ---- internals ------------------------------------------------------------------------------------------------------------

  private live(peerId: string, id: string): Session {
    const s = this.session
    if (!s || s.id !== id || s.peer.deviceId !== peerId) throw new PairError('gone', 'This pairing is no longer open.', 404)
    if (s.stage === 'failed') throw new PairError('declined', s.error ?? 'The pairing was declined.')
    if (this.options.now() >= s.expiresAt) {
      this.endSession('failed', 'The pairing took too long.')
      throw new PairError('expired', 'The pairing took too long.', 410)
    }
    return s
  }

  private expire(id: string): void {
    if (this.session?.id === id && this.session.stage !== 'done') this.endSession('failed', 'The pairing took too long.')
  }

  /** Ends a session whose time is up even when no timer fired (the clock is injected in tests). */
  private sweep(notify: boolean): void {
    const s = this.session
    if (s && s.stage !== 'done' && s.stage !== 'failed' && this.options.now() >= s.expiresAt) this.endSession('failed', 'The pairing took too long.', notify, true)
  }

  private now(): number {
    return this.options.now()
  }

  private endSession(stage: 'failed', error: string, notify = true, counts = true): void {
    const s = this.session
    if (!s || s.stage === 'done' || s.stage === 'failed') return
    s.stage = stage
    s.error = error
    clearTimeout(s.timer)
    s.decide(false)
    if (s.unrequested && counts) this.miss()
    if (notify) this.options.changed()
  }

  /** An unrequested session ended declined or expired: three of them in ten minutes mute unrequested requests for five. */
  private miss(): void {
    const at = this.now()
    this.misses = this.misses.filter((t) => at - t < this.muteWindowMs)
    this.misses.push(at)
    if (this.misses.length >= this.muteAfter) {
      this.mutedUntil = at + this.muteMs
      this.misses = []
    }
  }
}

export function peerInfo(spki: Uint8Array, body: {name?: unknown; platform?: unknown; libraries?: unknown}): PairPeerInfo {
  const libraries: PairLibraryRef[] = []
  if (Array.isArray(body.libraries)) {
    for (const entry of body.libraries.slice(0, 16)) {
      const lib = entry as Partial<PairLibraryRef>
      if (typeof lib?.libraryId === 'string' && typeof lib.actor === 'string' && lib.libraryId.length <= 64 && lib.actor.length <= 64) libraries.push({libraryId: lib.libraryId, actor: lib.actor})
    }
  }
  const text = (value: unknown, fallback: string) => (typeof value === 'string' && value.trim() ? value.trim().slice(0, 40) : fallback)
  return {spki, deviceId: deviceIdOf(spki), name: text(body.name, 'Another device'), platform: text(body.platform, 'unknown'), libraries}
}

/** Constant-time equality of two pins. */
export function samePin(a: Uint8Array, b: Uint8Array): boolean {
  return a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b))
}
