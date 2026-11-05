import {test} from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import https from 'node:https'
import tls from 'node:tls'
import {pairPin} from '../../shared/core/net.js'
import {buildCertificate, certExpiry, spkiOfCertificate} from './cert.js'
import {PeerClient, PinMismatch} from './client.js'
import {loadIdentity} from './identity.js'
import {World, plainBox, until} from './testkit.js'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

test('the device certificate is a valid self-signed X.509 certificate for the device key, renewed with the same key', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-id-'))
  try {
    const a = await loadIdentity(dir, plainBox, () => 1_700_000_000_000)
    const cert = new crypto.X509Certificate(a.certPem)
    assert.deepEqual(new Uint8Array(spkiOfCertificate(cert.raw)), a.spki)
    assert.equal(cert.verify(cert.publicKey), true, 'self-signed')
    assert.ok(certExpiry(a.certPem) > 1_700_000_000_000 + 360 * 86_400_000)
    // 11 months later the certificate is renewed; the key and the device id are the same
    const b = await loadIdentity(dir, plainBox, () => 1_700_000_000_000 + 340 * 86_400_000)
    assert.equal(b.deviceId, a.deviceId)
    assert.notEqual(b.certPem, a.certPem)
    assert.deepEqual(b.spki, a.spki)
    // a certificate built directly for the same key
    assert.ok(new crypto.X509Certificate(buildCertificate(a.spki, a.pkcs8, Date.now(), 'x')).validTo)
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('the server speaks TLS 1.3 only and does not resume sessions', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const old = await new Promise<string>((resolve) => {
      const socket = tls.connect({host: '127.0.0.1', port: a.port, maxVersion: 'TLSv1.2', rejectUnauthorized: false}, () => resolve('connected'))
      socket.on('error', () => resolve('refused'))
    })
    assert.equal(old, 'refused', 'TLS 1.2 is refused')
    const id = await loadIdentity(w.dir, plainBox)
    const first = await new Promise<tls.TLSSocket>((resolve) => {
      const socket = tls.connect({host: '127.0.0.1', port: a.port, key: id.keyPem, cert: id.certPem, rejectUnauthorized: false, minVersion: 'TLSv1.3'}, () => resolve(socket))
    })
    assert.equal(first.getProtocol(), 'TLSv1.3')
    let session: Buffer | undefined
    first.on('session', (s) => (session = s))
    first.resume()
    first.write('GET /v1/hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n')
    await new Promise((resolve) => first.on('close', resolve))
    const second = await new Promise<tls.TLSSocket>((resolve) => {
      const socket = tls.connect({host: '127.0.0.1', port: a.port, key: id.keyPem, cert: id.certPem, rejectUnauthorized: false, minVersion: 'TLSv1.3', session}, () => resolve(socket))
    })
    assert.equal(second.isSessionReused(), false, 'a ticket is never honoured')
    second.destroy()
  } finally {
    await w.cleanup()
  }
})

test('two devices pair by comparing the same six digits, then know each other by key', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    await w.pair(b, a)
    const peerOfA = a.service.peers.get(b.identity.deviceId)!
    assert.equal(peerOfA.name, b.identity.name)
    assert.deepEqual(peerOfA.libraries.map((l) => l.actions), [['metadata.read', 'metadata.write']], 'the default is to sync only')
    assert.equal(peerOfA.libraries[0].libraryId, w.libraryId)
    // the pin is the key: the paired device now gets a hello, a stranger does not
    const client = new PeerClient({identity: b.identity, host: '127.0.0.1', port: a.port, pin: a.identity.deviceId})
    const hello = await client.json<{name: string; libraries: {libraryId: string}[]}>('GET', '/v1/hello?v=1')
    assert.equal(hello.libraries[0].libraryId, w.libraryId)
    client.close()
    const stranger = await w.node('S', {withService: false})
    const strangerClient = new PeerClient({identity: await loadIdentity(stranger.deviceDir, plainBox), host: '127.0.0.1', port: a.port, pin: null})
    await assert.rejects(() => strangerClient.json('GET', '/v1/hello?v=1'), /unknown-peer/)
    strangerClient.close()
    // a client that expects another key never sends a byte
    const wrong = new PeerClient({identity: b.identity, host: '127.0.0.1', port: a.port, pin: stranger.identity.deviceId})
    await assert.rejects(() => wrong.json('GET', '/v1/hello?v=1'), PinMismatch)
    // the library has a membership root now, with the other device granted
    await until(async () => (await a.lib.membership()).active || (await b.lib.membership()).active, 'a genesis')
    assert.ok(pairPin(a.identity.spki).length === 32)
  } finally {
    await w.cleanup()
  }
})
