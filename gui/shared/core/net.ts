// Adapter between the shared core (core/, built to build/core-js by `npm run core:build`) and the network layer of
// dev-docs/plans/KEEPFIN-NETWORK-SPEC.md: device keys and signatures, the pairing math, the authorization matrix and the
// Download on... URL policy. This is the only file that imports the core's net package. The core performs no I/O: random
// nonces, DNS answers and the clock come from the caller. Bytes are Uint8Array here (Int8Array in the core).
import {
  netBuildPairQr,
  netCheckDownloadUrl,
  netCheckRedirect,
  netCheckResolved,
  netDeniedAddressClass,
  netDeviceId,
  netGenerateKey,
  netHmacSha256,
  netIsP256Spki,
  netPairCodeOf,
  netPairCommitment,
  netPairPin,
  netPairSas,
  netPairTranscript,
  netPairVerifyReveal,
  netParsePairCode,
  netParsePairQr,
  netPinMatches,
  netSign,
  netVerify,
  netAuthorize,
} from '../../../build/core-js/keepfin-core.mjs'

const toInt8 = (bytes: Uint8Array): Int8Array => new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)
const toUint8 = (bytes: Int8Array): Uint8Array => new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)
const fromBase64 = (text: string): Uint8Array => new Uint8Array(Buffer.from(text, 'base64'))

/** Signature domains: every signature is `domain 0x00 data`. */
export const DOMAIN_SEGMENT = 'keepfin-seg-v2'
export const DOMAIN_PAIR = 'keepfin-pair-v1'

// ---- device keys ---------------------------------------------------------------------------------------------------------

export interface DeviceKey {
  /** SubjectPublicKeyInfo DER (91 bytes, P-256). */
  spki: Uint8Array
  /** PKCS#8 DER. The desktop wraps it with safeStorage before it is stored; it is never synced. */
  pkcs8: Uint8Array
}

/** A fresh software key pair. */
export function generateDeviceKey(): DeviceKey {
  const k = JSON.parse(netGenerateKey()) as {spki: string; pkcs8: string}
  return {spki: fromBase64(k.spki), pkcs8: fromBase64(k.pkcs8)}
}

/** The device id: SHA-256 of the SPKI as lower-case hex. */
export function deviceIdOf(spki: Uint8Array): string {
  return netDeviceId(toInt8(spki))
}

/** True for a 91-byte SPKI of an uncompressed P-256 point: the only key shape Keepfin accepts. */
export function isDeviceKey(spki: Uint8Array): boolean {
  return netIsP256Spki(toInt8(spki))
}

export function signDomain(pkcs8: Uint8Array, domain: string, data: Uint8Array): Uint8Array {
  return toUint8(netSign(toInt8(pkcs8), domain, toInt8(data)))
}

/** False for any problem: a wrong key, a wrong domain, altered data or a malformed signature. Never throws. */
export function verifyDomain(spki: Uint8Array, domain: string, data: Uint8Array, signature: Uint8Array): boolean {
  return netVerify(toInt8(spki), domain, toInt8(data), toInt8(signature))
}

export function hmacSha256(key: Uint8Array, message: Uint8Array): Uint8Array {
  return toUint8(netHmacSha256(toInt8(key), toInt8(message)))
}

// ---- pairing (commitment-based numeric comparison; spec 9.1 #3) --------------------------------------------------------------

export const PAIR_NONCE_BYTES = 16

/** Cb = HMAC(Nb, SPKIb || SPKIa): what the responder (B) sends before it has seen Na. */
export function pairCommitment(nb: Uint8Array, spkiB: Uint8Array, spkiA: Uint8Array): Uint8Array {
  return toUint8(netPairCommitment(toInt8(nb), toInt8(spkiB), toInt8(spkiA)))
}

/** True when the revealed Nb is the one the commitment was made with. */
export function pairVerifyReveal(commitment: Uint8Array, nb: Uint8Array, spkiB: Uint8Array, spkiA: Uint8Array): boolean {
  return netPairVerifyReveal(toInt8(commitment), toInt8(nb), toInt8(spkiB), toInt8(spkiA))
}

/** The six digits both users compare. A is the initiator; the SPKIs are in role order. */
export function pairSas(na: Uint8Array, nb: Uint8Array, spkiA: Uint8Array, spkiB: Uint8Array): string {
  return netPairSas(toInt8(na), toInt8(nb), toInt8(spkiA), toInt8(spkiB))
}

/** The bytes the paired devices sign (domain keepfin-pair-v1) to confirm the session: both nonces and both keys. */
export function pairTranscript(na: Uint8Array, nb: Uint8Array, spkiA: Uint8Array, spkiB: Uint8Array): Uint8Array {
  return toUint8(netPairTranscript(toInt8(na), toInt8(nb), toInt8(spkiA), toInt8(spkiB)))
}

/** The pin a QR carries: SHA-256 of the SPKI. */
export function pairPin(spki: Uint8Array): Uint8Array {
  return toUint8(netPairPin(toInt8(spki)))
}

export interface PairQr {
  host: string
  port: number
  /** SHA-256 of the SPKI of the device that shows the QR. */
  pin: Uint8Array
  /** 128 random bits, one use. */
  token: Uint8Array
}

export function buildPairQr(qr: PairQr): string {
  return netBuildPairQr(qr.host, qr.port, toInt8(qr.pin), toInt8(qr.token))
}

/** Reads a scanned QR strictly; null when it is not exactly a Keepfin pairing code. */
export function parsePairQr(text: string): PairQr | null {
  const json = netParsePairQr(text)
  if (json == null) return null
  const q = JSON.parse(json) as {host: string; port: number; pin: string; token: string}
  return {host: q.host, port: q.port, pin: fromBase64(q.pin), token: fromBase64(q.token)}
}

// ---- the typed code (20-bit one-use token + the first 30 bits of the answering device's pin) -----------------------------------

export interface PairCode {
  /** 0 until 2^20. */
  token: number
  /** The first 30 bits of the pin of the device that shows the code. */
  pinBits: number
}

/** `XXXXX-XXXXX` for the code token and the answering device's pin. */
export function pairCodeOf(token: number, pin: Uint8Array): string {
  return netPairCodeOf(token, toInt8(pin))
}

/** Reads what a person typed (any case, spaces and dashes, O as 0, I and L as 1); null unless it is exactly ten valid symbols. */
export function parsePairCode(text: string): PairCode | null {
  const json = netParsePairCode(text)
  return json == null ? null : (JSON.parse(json) as PairCode)
}

/** True when the pin starts with the 30 bits a code carried. */
export function pinMatches(pin: Uint8Array, pinBits: number): boolean {
  return netPinMatches(toInt8(pin), pinBits)
}
// ---- authorization (spec 9.1 #5) ----------------------------------------------------------------------------------------------

export type NetAction =
  | 'metadata.read'
  | 'metadata.write'
  | 'inventory.read'
  | 'media.read'
  | 'copy.mutate'
  | 'jobs'
  | 'membership.admin'
  | 'hello'
  | 'pair'

export interface PeerGrants {
  deviceId: string
  revoked: boolean
  /** Per library, what this device lets the peer do. A library that is not listed is not shared with the peer. */
  libraries: Record<string, NetAction[]>
}

export interface AuthzContext {
  /** True only while this device is in explicit local pairing mode. */
  pairingMode: boolean
  /** The pinned peer, or null for a key that is not paired. */
  peer: PeerGrants | null
}

export interface Decision {
  allow: boolean
  /** A stable code: ok, not-pairing, unknown-peer, revoked, no-library, library, action, event. */
  reason: string
}

/** What a newly paired peer may do: read and write metadata of the shared libraries, nothing else. */
export const DEFAULT_GRANTS: NetAction[] = ['metadata.read', 'metadata.write']

/** Deny by default: allowed only when the peer is pinned, not revoked, and holds the action for the library. */
export function authorize(ctx: AuthzContext, action: NetAction, library: string | null): Decision {
  return JSON.parse(netAuthorize(JSON.stringify(ctx), action, library))
}

// ---- Download on... URL policy (spec 9.1 #6) ----------------------------------------------------------------------------------

export interface UrlDecision {
  allow: boolean
  /** The normalised link when allowed. */
  href: string | null
  host: string | null
  /** ok, bad-url, scheme, credentials, no-host, local-name, too-many-redirects, or address:<class> (loopback, private, link-local, multicast, unspecified, reserved, malformed, unresolved). */
  reason: string
}

/** The checks before DNS: scheme, credentials, local names and address literals. A name must then go through `checkResolved`. */
export function checkDownloadUrl(text: string): UrlDecision {
  return JSON.parse(netCheckDownloadUrl(text))
}

/** The check on the addresses a name resolved to: all of them must be public, and there must be one. Run it on every redirect too. */
export function checkResolved(addresses: string[]): UrlDecision {
  return JSON.parse(netCheckResolved(JSON.stringify(addresses)))
}

/** One redirect hop: `location` is resolved against `current`, checked, and `resolved` (the new host's addresses) must be public. */
export function checkRedirect(current: string, location: string, resolved: string[], hops: number): UrlDecision {
  return JSON.parse(netCheckRedirect(current, location, JSON.stringify(resolved), hops))
}

/** Why an address is not a public destination, or null when it is. */
export function deniedAddressClass(address: string): string | null {
  return netDeniedAddressClass(address) ?? null
}
