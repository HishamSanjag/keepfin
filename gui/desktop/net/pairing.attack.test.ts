// Pairing under attack: a machine in the middle, replayed and expired codes, attempt limits, a refusal.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import https from 'node:https'
import {randomBytes, X509Certificate} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {buildPairQr, deviceIdOf, pairPin, parsePairQr} from '../../shared/core/net.js'
import {collect, PeerClient, PeerHttpError} from './client.js'
import {loadIdentity, type DeviceIdentity} from './identity.js'
import {askToPair} from './initiator.js'
import {DEFAULT_LIMITS} from './limits.js'
import {PairingHost, peerInfo} from './pairing.js'
import {World, plainBox, until} from './testkit.js'

/** A proxy that terminates TLS with its own key on both sides and relays every request (the classic machine in the middle). */
async function startProxy(target: {host: string; port: number}, identity: DeviceIdentity) {
  const upstream = new PeerClient({identity, host: target.host, port: target.port, pin: null})
  const server = https.createServer({key: identity.keyPem, cert: identity.certPem, requestCert: true, rejectUnauthorized: false, minVersion: 'TLSv1.3'}, async (req, res) => {
    const body = await collect(req, 1_000_000)
    const answer = await upstream.request(req.method ?? 'GET', req.url ?? '/', {body: body.length ? body : undefined, headers: body.length ? {'content-type': 'application/json'} : {}, timeoutMs: 200_000})
    const bytes = await collect(answer.res, 1_000_000)
    res.writeHead(answer.status, {'content-type': 'application/json', 'content-length': bytes.length})
    res.end(bytes)
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  return {port: (server.address() as {port: number}).port, close: () => new Promise<void>((resolve) => (upstream.close(), server.close(() => resolve())))}
}

async function strangerIdentity() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-stranger-'))
  return {identity: await loadIdentity(dir, plainBox), dir}
}

/**
 * An active machine in the middle: it answers the asking device as a pairing host with its own key and nonce, and asks the answering
 * device as a pairing device with its own key and nonce. Each leg is a correct run of the protocol, so only the two people comparing
 * the numbers can catch it.
 */
async function startActiveProxy(target: {host: string; port: number}, identity: DeviceIdentity) {
  const host = new PairingHost({identity: () => identity, limits: DEFAULT_LIMITS, now: Date.now, libraries: () => [], onPaired: async () => undefined, changed: () => undefined})
  host.open()
  let legToA: Promise<unknown> | undefined
  const server = https.createServer({key: identity.keyPem, cert: identity.certPem, requestCert: true, rejectUnauthorized: false, minVersion: 'TLSv1.3'}, async (req, res) => {
    const body = JSON.parse((await collect(req, 100_000)).toString() || '{}') as Record<string, string>
    const socket = req.socket as import('node:tls').TLSSocket
    const spki = new X509Certificate(socket.getPeerCertificate().raw).publicKey.export({type: 'spki', format: 'der'}) as Buffer
    const b64 = (s: string) => new Uint8Array(Buffer.from(s, 'base64'))
    const send = (value: unknown) => {
      const text = JSON.stringify(value)
      res.writeHead(200, {'content-type': 'application/json', 'content-length': Buffer.byteLength(text)})
      res.end(text)
    }
    try {
      if (req.url === '/v1/pair/start') {
        const started = host.start(peerInfo(new Uint8Array(spki), body), '127.0.0.1', null)
        host.decide(started.id, true) // the attacker agrees with itself
        // the other leg: the attacker asks the real device, with its own key
        legToA = askToPair({identity, host: target.host, port: target.port, pin: null, token: null, libraries: [], decide: async () => true}).catch(() => undefined)
        return send({id: started.id, commitment: Buffer.from(started.commitment).toString('base64'), expiresAt: started.expiresAt, name: 'Laptop', platform: 'windows', libraries: [], versions: [1]})
      }
      if (req.url === '/v1/pair/nonce') return send({nb: Buffer.from(host.nonce(deviceIdOf(new Uint8Array(spki)), body.id, b64(body.na))).toString('base64')})
      if (req.url === '/v1/pair/confirm') return send({sig: Buffer.from(await host.confirm(deviceIdOf(new Uint8Array(spki)), body.id, b64(body.sig))).toString('base64')})
    } catch (error) {
      res.writeHead(403, {'content-type': 'application/json'})
      return void res.end(JSON.stringify({error: (error as {code?: string}).code ?? 'error', message: (error as Error).message}))
    }
    res.writeHead(404).end()
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  return {port: (server.address() as {port: number}).port, done: () => legToA, close: () => new Promise<void>((resolve) => (host.close(), server.close(() => resolve())))}
}

test('a relay that cannot re-commit is caught by the commitment before any number is shown', async () => {
  const w = await World.create()
  const m = await strangerIdentity()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    const proxy = await startProxy({host: '127.0.0.1', port: a.port}, m.identity)
    try {
      a.service.openPairing()
      await b.service.ask(`127.0.0.1:${proxy.port}`)
      assert.equal(b.service.state().pairing?.stage, 'failed')
      assert.match(b.service.state().pairing?.error ?? '', /did not follow the pairing steps/)
      assert.equal(b.service.peers.list().length + a.service.peers.list().length, 0)
    } finally {
      await proxy.close()
    }
  } finally {
    await fs.rm(m.dir, {recursive: true, force: true})
    await w.cleanup()
  }
})

test('an active machine in the middle makes the two screens show different numbers', async () => {
  const w = await World.create()
  const m = await strangerIdentity()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    const proxy = await startActiveProxy({host: '127.0.0.1', port: a.port}, m.identity)
    try {
      a.service.openPairing()
      const done = b.service.ask(`127.0.0.1:${proxy.port}`)
      await until(() => b.service.state().pairing?.sas !== undefined && a.service.state().pairing?.sas !== undefined, 'both screens')
      const shownOnB = b.service.state().pairing!
      const shownOnA = a.service.state().pairing!
      assert.notEqual(shownOnA.sas, shownOnB.sas, 'the numbers differ, so the people stop')
      // the people compare and say they do not match: nothing is paired anywhere
      b.service.decide(shownOnB.id, false)
      a.service.decide(shownOnA.id, false)
      await done
      assert.equal(a.service.peers.list().length + b.service.peers.list().length, 0)
      void proxy.done()
    } finally {
      await proxy.close()
    }
  } finally {
    await fs.rm(m.dir, {recursive: true, force: true})
    await w.cleanup()
  }
})
test('saying the numbers differ ends the pairing on both sides without a pin', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    a.service.openPairing()
    const done = b.service.ask(a.address)
    await until(() => b.service.state().pairing?.sas !== undefined && a.service.state().pairing?.sas !== undefined, 'both screens')
    b.service.decide(b.service.state().pairing!.id, false)
    await done
    await until(() => a.service.state().pairing?.stage === 'failed', 'the other device to learn it')
    assert.match(a.service.state().pairing!.error ?? '', /did not confirm/)
    assert.equal(a.service.peers.list().length + b.service.peers.list().length, 0)
  } finally {
    await w.cleanup()
  }
})

test('a device that is not in pairing mode answers nobody new', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B', {waitForPeer: {intervalMs: 20, deadlineMs: 100}})
    await b.service.ask(a.address)
    assert.match(b.service.state().pairing?.error ?? '', /was not opened on the other device in time/)
    assert.equal(a.service.peers.list().length, 0)
  } finally {
    await w.cleanup()
  }
})

test('a QR code pairs once without anyone deciding, and replaying it is refused', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    const c = await w.node('C')
    await a.service.showQr()
    const link = a.service.state().qr!.link
    assert.ok(link.startsWith('keepfin://pair?'))
    assert.ok(a.service.state().qr!.image.startsWith('data:image/svg+xml;base64,'))
    // the scanned code is the authorization: nobody on A decides (and showing it did not open the window)
    assert.equal(a.service.state().pairingUntil, null)
    await b.service.ask(link)
    assert.equal(a.service.state().pairing!.sas, undefined, 'the code replaces the numbers')
    assert.equal(b.service.state().pairing?.stage, 'done')
    assert.ok(a.service.peers.get(b.identity.deviceId) && b.service.peers.get(a.identity.deviceId))
    // the same code again, from another device: gone
    await c.service.ask(link)
    assert.match(c.service.state().pairing?.error ?? '', /already used|expired/)
    assert.equal(a.service.peers.get(c.identity.deviceId), undefined)
  } finally {
    await w.cleanup()
  }
})

test('a QR code for another key is refused by the key it names', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    await a.service.showQr()
    const real = parsePairQr(a.service.state().qr!.link)!
    const forged = buildPairQr({...real, pin: pairPin(b.identity.spki)})
    await b.service.ask(forged)
    assert.match(b.service.state().pairing?.error ?? '', /key is different/)
    assert.equal(a.service.peers.list().length, 0)
  } finally {
    await w.cleanup()
  }
})

test('codes and sessions expire after two minutes', async () => {
  const w = await World.create()
  let now = 1_700_000_000_000
  try {
    const a = await w.node('A', {now: () => now})
    const b = await w.node('B', {waitForPeer: {intervalMs: 20, deadlineMs: 100}})
    await a.service.showQr()
    const link = a.service.state().qr!.link
    now += 121_000
    await b.service.ask(link)
    assert.match(b.service.state().pairing?.error ?? '', /was not opened|expired/)
    // a numbers session that sits for two minutes cannot be confirmed
    a.service.openPairing()
    const done = b.service.ask(a.address)
    await until(() => b.service.state().pairing?.sas !== undefined && a.service.state().pairing?.sas !== undefined, 'both screens')
    now += 121_000
    b.service.decide(b.service.state().pairing!.id, true)
    a.service.decide(a.service.state().pairing!.id, true)
    await done
    assert.equal(b.service.state().pairing?.stage, 'failed')
    assert.equal(a.service.peers.list().length, 0)
  } finally {
    await w.cleanup()
  }
})

test('five attempts per key and per address, then no more, and never a global lockout', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    a.service.openPairing()
    const statuses: number[] = []
    const keys = [await strangerIdentity(), await strangerIdentity()]
    for (let i = 0; i < 6; i++) {
      const client = new PeerClient({identity: keys[0].identity, host: '127.0.0.1', port: a.port, pin: null})
      try {
        await client.json('POST', '/v1/pair/start', {json: {versions: [1], name: 'x', platform: 'windows', libraries: []}})
        statuses.push(200)
      } catch (error) {
        statuses.push((error as PeerHttpError).status)
      }
      client.close()
    }
    assert.deepEqual(statuses, [200, 409, 409, 409, 409, 429], 'the sixth try from one key is refused')
    // a different key from the same address shares the per-address budget, which is also spent
    const other = new PeerClient({identity: keys[1].identity, host: '127.0.0.1', port: a.port, pin: null})
    await assert.rejects(() => other.json('POST', '/v1/pair/start', {json: {versions: [1], name: 'y', platform: 'windows', libraries: []}}), (e: PeerHttpError) => e.status === 429)
    other.close()
    for (const k of keys) await fs.rm(k.dir, {recursive: true, force: true})
    void randomBytes
  } finally {
    await w.cleanup()
  }
})
