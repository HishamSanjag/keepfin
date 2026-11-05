// This device's identity (spec section 1): a P-256 key made once per install, its private half wrapped with the operating
// system's secure storage (Electron safeStorage, DPAPI on Windows), and a self-signed certificate renewed yearly with the same
// key. The device id is the SHA-256 of the SPKI. Same-user malware can use the key (spec 9.1 #9); this file says so on purpose.
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {deviceIdOf, generateDeviceKey} from '../../shared/core/net.js'
import {readJson, writeJsonAtomic} from '../store/atomic.js'
import {buildCertificate, CERT_RENEW_BEFORE_MS, certExpiry} from './cert.js'

/** The secure storage the key is wrapped with (Electron safeStorage; a test box is plain). */
export interface KeyBox {
  available(): boolean
  encrypt(plain: string): Buffer
  decrypt(blob: Buffer): string
}

export interface DeviceIdentity {
  deviceId: string
  spki: Uint8Array
  pkcs8: Uint8Array
  /** PEM of the private key for TLS. */
  keyPem: string
  certPem: string
  name: string
}

type StoredIdentity = {v: 1; wrapped: string; certPem: string; name: string; createdAt: number}

export const identityFile = (deviceDir: string) => path.join(deviceDir, 'net', 'device.json')

export function defaultDeviceName(): string {
  return os.hostname().slice(0, 40) || 'Windows PC'
}

/** The short form people compare: the first 20 hex digits of the device id in groups of four. */
export function fingerprintOf(deviceId: string): string {
  return (deviceId.slice(0, 20).match(/.{4}/g) ?? []).join(' ')
}

/**
 * Loads the identity of this install, making it the first time. A key that cannot be unwrapped (another user, a restored
 * profile) is never replaced silently: a new identity is a new device, so it is made only when the file is missing.
 */
export async function loadIdentity(deviceDir: string, box: KeyBox, now: () => number = Date.now): Promise<DeviceIdentity> {
  if (!box.available()) throw new Error('Secure storage is not available on this computer, so devices cannot be paired.')
  const file = identityFile(deviceDir)
  let stored = await readJson<StoredIdentity>(file) // a damaged file throws: a new identity is never made behind the user's back
  if (stored && (stored.v !== 1 || typeof stored.wrapped !== 'string')) stored = undefined
  let pkcs8: Uint8Array
  let spki: Uint8Array
  if (stored) {
    let plain: {pkcs8: string; spki: string}
    try {
      plain = JSON.parse(box.decrypt(Buffer.from(stored.wrapped, 'base64')))
    } catch {
      throw new Error('This computer cannot open the saved device key. Remove the devices file in the Keepfin data folder to start again as a new device.')
    }
    pkcs8 = new Uint8Array(Buffer.from(plain.pkcs8, 'base64'))
    spki = new Uint8Array(Buffer.from(plain.spki, 'base64'))
  } else {
    const key = generateDeviceKey()
    pkcs8 = key.pkcs8
    spki = key.spki
  }
  const deviceId = deviceIdOf(spki)
  let certPem = stored?.certPem ?? ''
  const name = stored?.name || defaultDeviceName()
  if (!stored || certExpiry(certPem) - now() < CERT_RENEW_BEFORE_MS) {
    certPem = buildCertificate(spki, pkcs8, now(), `Keepfin ${deviceId.slice(0, 16)}`)
    const wrapped = stored?.wrapped ?? box.encrypt(JSON.stringify({pkcs8: Buffer.from(pkcs8).toString('base64'), spki: Buffer.from(spki).toString('base64')})).toString('base64')
    await fs.mkdir(path.dirname(file), {recursive: true})
    await writeJsonAtomic(file, {v: 1, wrapped, certPem, name, createdAt: stored?.createdAt ?? now()} satisfies StoredIdentity)
  }
  const keyPem = crypto.createPrivateKey({key: Buffer.from(pkcs8), format: 'der', type: 'pkcs8'}).export({type: 'pkcs8', format: 'pem'}) as string
  return {deviceId, spki, pkcs8, keyPem, certPem, name}
}

export async function renameDevice(deviceDir: string, name: string): Promise<void> {
  const file = identityFile(deviceDir)
  const stored = await readJson<StoredIdentity>(file)
  if (!stored) throw new Error('This device has no identity yet.')
  await writeJsonAtomic(file, {...stored, name: name.trim().slice(0, 40) || defaultDeviceName()})
}
