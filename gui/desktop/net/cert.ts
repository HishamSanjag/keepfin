// The device certificate (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md section 1): a self-signed X.509 v3 certificate that only carries
// the device key to the TLS handshake. Nobody checks its name, issuer or chain: the peer pins the SPKI, so the certificate
// can be renewed yearly with the same key without anyone noticing. Node has no API to make one and a small fixed layout
// does not need a library, so the DER is written here (ecdsa-with-SHA256, no extensions) and checked by tls and by
// crypto.X509Certificate in cert.test.ts.
import crypto from 'node:crypto'

const OID_ECDSA_SHA256 = Buffer.from('06082a8648ce3d040302', 'hex')
const OID_COMMON_NAME = Buffer.from('0603550403', 'hex')

function len(n: number): Buffer {
  if (n < 0x80) return Buffer.from([n])
  if (n < 0x100) return Buffer.from([0x81, n])
  return Buffer.from([0x82, n >> 8, n & 0xff])
}

const der = (tag: number, ...parts: Buffer[]): Buffer => {
  const body = Buffer.concat(parts)
  return Buffer.concat([Buffer.from([tag]), len(body.length), body])
}

function utcTime(ms: number): Buffer {
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  const text = `${p(d.getUTCFullYear() % 100)}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}${p(d.getUTCHours())}${p(d.getUTCMinutes())}${p(d.getUTCSeconds())}Z`
  return der(0x17, Buffer.from(text, 'ascii'))
}

export const CERT_YEAR_MS = 365 * 24 * 60 * 60 * 1000
/** A certificate is replaced (same key) when less than this is left. */
export const CERT_RENEW_BEFORE_MS = 30 * 24 * 60 * 60 * 1000

/** A self-signed certificate for [spki] / [pkcs8], valid from a day before [nowMs] for one year, as PEM. */
export function buildCertificate(spki: Uint8Array, pkcs8: Uint8Array, nowMs: number, commonName: string): string {
  const name = der(0x30, der(0x31, der(0x30, OID_COMMON_NAME, der(0x0c, Buffer.from(commonName, 'utf8')))))
  const serial = crypto.randomBytes(16)
  serial[0] &= 0x7f
  serial[0] |= 0x01 // positive, no leading zero needed
  const algorithm = der(0x30, OID_ECDSA_SHA256)
  const tbs = der(
    0x30,
    der(0xa0, der(0x02, Buffer.from([2]))), // version v3
    der(0x02, serial),
    algorithm,
    name,
    der(0x30, utcTime(nowMs - 24 * 60 * 60 * 1000), utcTime(nowMs + CERT_YEAR_MS)),
    name,
    Buffer.from(spki),
  )
  const key = crypto.createPrivateKey({key: Buffer.from(pkcs8), format: 'der', type: 'pkcs8'})
  const signature = crypto.sign('sha256', tbs, {key, dsaEncoding: 'der'})
  const certificate = der(0x30, tbs, algorithm, der(0x03, Buffer.concat([Buffer.from([0]), signature])))
  const b64 = certificate.toString('base64').match(/.{1,64}/g)!.join('\n')
  return `-----BEGIN CERTIFICATE-----\n${b64}\n-----END CERTIFICATE-----\n`
}

/** Milliseconds since the epoch at which the PEM certificate expires, or 0 when it cannot be read. */
export function certExpiry(pem: string): number {
  try {
    return new crypto.X509Certificate(pem).validToDate.getTime()
  } catch {
    return 0
  }
}

/** The SubjectPublicKeyInfo DER of a certificate (what peers pin). */
export function spkiOfCertificate(der: Uint8Array): Buffer {
  return new crypto.X509Certificate(Buffer.from(der)).publicKey.export({type: 'spki', format: 'der'}) as Buffer
}
