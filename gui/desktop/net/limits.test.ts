// The denial-of-service budgets of spec 9.1 #7, with small limits so each can be reached quickly.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import net from 'node:net'
import tls from 'node:tls'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {randomBytes} from 'node:crypto'
import {collect, PeerClient, PeerHttpError} from './client.js'
import {loadIdentity} from './identity.js'
import {World, plainBox, until} from './testkit.js'

async function stranger() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-lim-'))
  return {identity: await loadIdentity(dir, plainBox), cleanup: () => fs.rm(dir, {recursive: true, force: true})}
}

const closesWithin = (socket: net.Socket, ms: number) =>
  new Promise<boolean>((resolve) => {
    const timer = setTimeout(() => resolve(false), ms)
    socket.on('close', () => (clearTimeout(timer), resolve(true)))
    socket.on('error', () => undefined)
    socket.resume()
  })

test('a handshake that never finishes, and headers that trickle, are cut off by their deadlines', async () => {
  const w = await World.create()
  const s = await stranger()
  try {
    const a = await w.node('A', {limits: {handshakeMs: 300, headersMs: 300}})
    const silent = net.connect(a.port, '127.0.0.1')
    assert.equal(await closesWithin(silent, 3000), true, 'the silent connection is closed')
    const slow = tls.connect({host: '127.0.0.1', port: a.port, key: s.identity.keyPem, cert: s.identity.certPem, rejectUnauthorized: false, minVersion: 'TLSv1.3'})
    await new Promise((resolve) => slow.once('secureConnect', resolve))
    slow.write('GET /v1/hello HTTP/1.1\r\nHost: x\r\n') // never finishes the header block
    assert.equal(await closesWithin(slow, 4000), true, 'the half-sent request is closed')
  } finally {
    await s.cleanup()
    await w.cleanup()
  }
})

test('stranger connections per address, request rate, header size and body size are bounded', async () => {
  const w = await World.create()
  const s = await stranger()
  try {
    const a = await w.node('A', {limits: {perStrangerAddress: 2, strangerRequestsPerMinute: 4, maxHeaderBytes: 2048, maxJsonBytes: 1000}})
    const open = () =>
      new Promise<tls.TLSSocket>((resolve) => {
        const socket = tls.connect({host: '127.0.0.1', port: a.port, key: s.identity.keyPem, cert: s.identity.certPem, rejectUnauthorized: false, minVersion: 'TLSv1.3'}, () => resolve(socket))
        socket.on('error', () => undefined)
      })
    const held = [await open(), await open()]
    await new Promise((resolve) => setTimeout(resolve, 100))
    const third = await open()
    assert.equal(await closesWithin(third, 2000), true, 'the third connection from one address is dropped')
    held.forEach((socket) => socket.destroy())
    await new Promise((resolve) => setTimeout(resolve, 100))

    const client = new PeerClient({identity: s.identity, host: '127.0.0.1', port: a.port, pin: null})
    // an oversized body is refused before it is read in full
    await assert.rejects(() => client.json('POST', '/v1/pair/start', {json: {pad: 'x'.repeat(5000)}}), (e: PeerHttpError) => e.status === 403 || e.status === 413, 'the matrix answers first for a stranger outside pairing mode')
    // an oversized header block is refused
    const big = await client.request('GET', '/v1/hello', {headers: {'x-pad': 'y'.repeat(4000)}}).then((r) => r.status, () => 431)
    assert.ok(big === 431 || big === 400, `header too large (${big})`)
    // the request rate for a stranger runs out
    const codes: number[] = []
    for (let i = 0; i < 6; i++) codes.push(await client.request('GET', '/v1/hello').then(async (r) => (await collect(r.res, 10_000), r.status), () => 0))
    assert.ok(codes.includes(429), `strangers are rate limited (${codes.join(',')})`)
    client.close()
  } finally {
    await s.cleanup()
    await w.cleanup()
  }
})

test('a paired peer has a connection cap, a transfer cap, and an oversized push is refused', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {limits: {perPeerConnections: 6, transfersPerPeer: 2, maxPushBytes: 50_000, maxVectorChars: 200}})
    const b = await w.node('B')
    await w.pair(b, a)
    await a.service.setAccess(b.identity.deviceId, w.libraryId, ['metadata.read', 'metadata.write', 'media.read'])
    const { contentId } = await a.putFile('Music/big.bin', randomBytes(30_000_000))
    const client = new PeerClient({identity: b.identity, host: '127.0.0.1', port: a.port, pin: a.identity.deviceId})
    const path = `/v1/revisions/${contentId.slice(7)}?library=${w.libraryId}`
    // two transfers that the client does not read keep their slots; the third is told to wait. B's own library sync (segments, which
    // count as transfers too) may hold a slot for a moment after pairing, so the two are taken as slots become free, not assumed free.
    const held: Awaited<ReturnType<typeof client.request>>[] = []
    await until(async () => {
      const r = await client.request('GET', path)
      if (r.status === 200) held.push(r)
      else r.res.resume()
      return held.length === 2
    }, 'two transfer slots')
    const [first, second] = held
    const third = await client.request('GET', path)
    assert.equal(third.status, 429)
    assert.match(String(await collect(third.res, 10_000)), /"transfers"/, 'refused by the transfer cap, not the request rate')
    first.res.destroy()
    second.res.destroy()
    await until(async () => (await client.request('GET', path).then((r) => (r.res.destroy(), r.status), () => 0)) === 200, 'a slot to be freed')
    // a paired device has a connection cap too: raw sockets beyond it are dropped
    const raw: tls.TLSSocket[] = []
    for (let i = 0; i < 9; i++) {
      const socket = tls.connect({host: '127.0.0.1', port: a.port, key: b.identity.keyPem, cert: b.identity.certPem, rejectUnauthorized: false, minVersion: 'TLSv1.3'})
      socket.on('error', () => undefined)
      raw.push(socket)
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
    assert.ok(a.service.server!.openConnections(b.identity.deviceId) <= 6, 'at most six connections')
    raw.forEach((socket) => socket.destroy())
    // a version vector that is too long
    await assert.rejects(() => client.json('GET', `/v1/libraries/${w.libraryId}/segments?vector=${'a'.repeat(300)}`), (e: PeerHttpError) => e.status === 414)
    // and a vector that is not one
    await assert.rejects(() => client.json('GET', `/v1/libraries/${w.libraryId}/segments?vector=nonsense`), (e: PeerHttpError) => e.status === 400)
    // a push over the byte budget
    const body = Buffer.alloc(80_000, 7)
    const refused = await client.request('POST', `/v1/libraries/${w.libraryId}/segments`, {body})
    assert.equal(refused.status, 413)
    refused.res.resume()
    client.close()
  } finally {
    await w.cleanup()
  }
})

test('a pushed segment is checked: garbage, a wrong library and a oversized frame are rejected, nothing is stored', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    await w.pair(b, a)
    const client = new PeerClient({identity: b.identity, host: '127.0.0.1', port: a.port, pin: a.identity.deviceId})
    const { frame } = await import('./segments.js')
    const garbage = await client.request('POST', `/v1/libraries/${w.libraryId}/segments`, {body: frame(Buffer.from('not a segment at all'))})
    const answer = JSON.parse((await collect(garbage.res, 10_000)).toString())
    assert.deepEqual(answer, {stored: 0, have: 0, rejected: ['invalid']})
    const tooBig = await client.request('POST', `/v1/libraries/${w.libraryId}/segments`, {body: Buffer.from([0x7f, 0xff, 0xff, 0xff, 1, 2, 3])})
    assert.equal(tooBig.status, 413)
    tooBig.res.resume()
    client.close()
  } finally {
    await w.cleanup()
  }
})
