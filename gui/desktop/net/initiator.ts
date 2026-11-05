// Pairing, the asking side (spec 3 and 9.1 #3): connect, run the commitment exchange, show the six digits, wait for the person,
// and trade signatures. The protocol is documented in pairing.ts. Nothing is saved here: the caller pins the peer on success.
import {randomBytes} from 'node:crypto'
import {DOMAIN_PAIR, deviceIdOf, pairPin, pairSas, pairTranscript, pairVerifyReveal, PAIR_NONCE_BYTES, signDomain, verifyDomain} from '../../shared/core/net.js'
import {PeerClient, PeerHttpError, PinMismatch} from './client.js'
import {parseAnnouncement} from './scan.js'
import type {DeviceIdentity} from './identity.js'
import {fingerprintOf} from './identity.js'
import type {PairLibraryRef, PairPeerInfo} from './pairing.js'
import {PROTOCOL_VERSIONS} from './server.js'

export class AskError extends Error {
  constructor(
    message: string,
    readonly code: string,
  ) {
    super(message)
  }
}

export interface AskOptions {
  identity: DeviceIdentity
  host: string
  port: number
  /** Device id the peer must have (a QR carries it), or null to take the key it presents. */
  pin: string | null
  /** The QR's one-use token, or null for the number comparison. */
  token: Uint8Array | null
  /** A typed code: its 20-bit token and the 30 pin bits. The key must match the bits (checked before a request is written) and announce that a code is showing. */
  code?: {token: number; pinBits: number}
  libraries: PairLibraryRef[]
  /** Both nonces are known: show the digits (SAS) or the approval wait (QR) and resolve when the person here decides. Both people compare and confirm. */
  decide(info: {peerName: string; peerFingerprint: string; sas: string; method: 'sas' | 'qr'; expiresAt: number}): Promise<boolean>
  /** Reached the peer: its name and key, before anything is compared. */
  connected?(info: {peerName: string; peerFingerprint: string}): void
  connectTimeoutMs?: number
  /** The person here cancelled while waiting for the other device: the pairing is declined there and this ends as cancelled. */
  signal?: AbortSignal
  /** The other device is not in Add a device yet: keep asking (every [intervalMs], up to [deadlineMs]) instead of failing. Absent: fail at once. */
  waitForPeer?: WaitForPeer
}

export interface WaitForPeer {
  /** What the person sees in the failure message when the wait runs out. */
  peerLabel: string
  /** The first time the other device said it is not accepting devices yet. */
  waiting(): void
  signal?: AbortSignal
  intervalMs?: number
  deadlineMs?: number
  now?: () => number
  /** Resolves after [ms], or early (resolved) when [signal] aborts. Tests replace it. */
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>
}

export const WAIT_FOR_PEER_INTERVAL_MS = 2_500
export const WAIT_FOR_PEER_DEADLINE_MS = 2 * 60_000

const realSleep = (ms: number, signal?: AbortSignal) =>
  new Promise<void>((resolve) => {
    if (signal?.aborted) return resolve()
    const done = () => (clearTimeout(timer), signal?.removeEventListener('abort', done), resolve())
    const timer = setTimeout(done, ms)
    signal?.addEventListener('abort', done, {once: true})
  })

function friendly(error: unknown): AskError {
  if (error instanceof AskError) return error
  if (error instanceof PinMismatch) return new AskError(error.message, 'pin')
  if (error instanceof PeerHttpError) {
    const messages: Record<string, string> = {
      'not-pairing': 'That device is not accepting new devices. Open Add a device on it first.',
      rate: 'That device has seen too many tries. Wait a few minutes and try again.',
      busy: 'That device is pairing with someone else right now.',
      token: 'This code was already used or has expired. Show a new one.',
      version: 'One of the devices needs to update Keepfin before they can pair.',
      declined: 'The other device did not confirm, so nothing was paired.',
      expired: 'The pairing took too long. Start again.',
    }
    return new AskError(messages[error.code] ?? error.message, error.code)
  }
  const code = (error as NodeJS.ErrnoException)?.code
  if (code === 'ECONNREFUSED') return new AskError('Nothing is listening at that address. Is Keepfin open on the other device?', 'refused')
  if (code === 'ETIMEDOUT' || (error as Error)?.message === 'The device did not answer.') return new AskError('The device did not answer. Check that both are on the same Wi-Fi and Keepfin is open there. If it is a Windows PC, its network must be set to private.', 'timeout')
  if (code === 'ENOTFOUND') return new AskError('That name could not be found.', 'dns')
  return new AskError(error instanceof Error ? error.message : 'Pairing failed.', 'error')
}

type Started = {id: string; commitment: string; expiresAt: number; name: string; platform: string; libraries: PairLibraryRef[]}

/** The first request. When the other device answers "not accepting devices" and waiting is on, asks again until it opens, the time is up or the person cancels. */
async function startPairing(client: PeerClient, body: unknown, wait: WaitForPeer | undefined): Promise<Started> {
  const interval = wait?.intervalMs ?? WAIT_FOR_PEER_INTERVAL_MS
  const now = wait?.now ?? Date.now
  const sleep = wait?.sleep ?? realSleep
  const deadline = now() + (wait?.deadlineMs ?? WAIT_FOR_PEER_DEADLINE_MS)
  let announced = false
  for (;;) {
    try {
      return await client.json<Started>('POST', '/v1/pair/start', {json: body})
    } catch (error) {
      if (!wait || !(error instanceof PeerHttpError) || error.code !== 'not-pairing') throw error
    }
    if (wait.signal?.aborted) throw new AskError('Cancelled.', 'cancelled')
    if (!announced) {
      announced = true
      wait.waiting()
    }
    if (now() + interval > deadline) throw new AskError(`Add a device was not opened on ${wait.peerLabel} in time. Try again.`, 'not-opened')
    await sleep(interval, wait.signal)
    if (wait.signal?.aborted) throw new AskError('Cancelled.', 'cancelled')
  }
}

/** Runs the whole exchange. Resolves with the peer's identity once both people confirmed and both signatures verified. */
export async function askToPair(options: AskOptions): Promise<PairPeerInfo> {
  const {identity} = options
  const client = new PeerClient({identity, host: options.host, port: options.port, pin: options.pin, pinBits: options.code?.pinBits, connectTimeoutMs: options.connectTimeoutMs})
  try {
    if (options.code) {
      // one announce on the checked connection: only a device that says a code is showing gets /start (and so costs the attempt)
      const announced = parseAnnouncement(await client.json('GET', '/v1/announce', {timeoutMs: options.connectTimeoutMs ?? 5_000}))
      if (!announced?.code) throw new AskError('That device is not showing a code.', 'nocode')
    }
    const startBody = {versions: PROTOCOL_VERSIONS, name: identity.name, platform: 'windows', libraries: options.libraries, ...(options.token ? {token: Buffer.from(options.token).toString('base64')} : {}), ...(options.code ? {code: options.code.token} : {})}
    const started = await startPairing(client, startBody, options.waitForPeer)
    const spkiB = client.peerSpki!
    const peerId = deviceIdOf(spkiB)
    options.connected?.({peerName: String(started.name ?? 'Another device').slice(0, 40), peerFingerprint: fingerprintOf(peerId)})
    // a QR already named the key it expects; the client enforced it on the socket. Belt and braces:
    if (options.token && options.pin && Buffer.from(pairPin(spkiB)).toString('hex') !== options.pin) throw new PinMismatch()
    const na = randomBytes(PAIR_NONCE_BYTES)
    const revealed = await client.json<{nb: string}>('POST', '/v1/pair/nonce', {json: {id: started.id, na: na.toString('base64')}})
    const nb = new Uint8Array(Buffer.from(revealed.nb, 'base64'))
    if (nb.length !== PAIR_NONCE_BYTES || !pairVerifyReveal(new Uint8Array(Buffer.from(started.commitment, 'base64')), nb, spkiB, identity.spki)) {
      throw new AskError('The other device did not follow the pairing steps, so nothing was paired.', 'commitment')
    }
    const sas = pairSas(na, nb, identity.spki, spkiB)
    const agreed = await options.decide({peerName: String(started.name ?? 'Another device').slice(0, 40), peerFingerprint: fingerprintOf(peerId), sas, method: options.token || options.code ? 'qr' : 'sas', expiresAt: started.expiresAt})
    if (!agreed) {
      await client.json('POST', '/v1/pair/confirm', {json: {id: started.id, decline: true}}).catch(() => undefined)
      throw new AskError('You said the numbers do not match, so nothing was paired.', 'declined')
    }
    const transcript = pairTranscript(na, nb, identity.spki, spkiB)
    const sig = signDomain(identity.pkcs8, DOMAIN_PAIR, transcript)
    let theirs: {sig: string}
    try {
      theirs = await client.json<{sig: string}>('POST', '/v1/pair/confirm', {json: {id: started.id, sig: Buffer.from(sig).toString('base64')}, timeoutMs: 3 * 60_000, signal: options.signal})
    } catch (error) {
      if (options.signal?.aborted) {
        await client.json('POST', '/v1/pair/confirm', {json: {id: started.id, decline: true}, timeoutMs: 3_000}).catch(() => undefined)
        throw new AskError('Cancelled.', 'cancelled')
      }
      throw error
    }
    if (!verifyDomain(spkiB, DOMAIN_PAIR, transcript, new Uint8Array(Buffer.from(theirs.sig, 'base64')))) throw new AskError('The other device could not prove it was the one you meant.', 'signature')
    const libs = Array.isArray(started.libraries) ? started.libraries.filter((l) => typeof l?.libraryId === 'string' && typeof l.actor === 'string').slice(0, 16) : []
    return {spki: spkiB, deviceId: peerId, name: String(started.name ?? 'Another device').slice(0, 40), platform: String(started.platform ?? 'unknown').slice(0, 20), libraries: libs}
  } catch (error) {
    throw friendly(error)
  } finally {
    client.close()
  }
}
