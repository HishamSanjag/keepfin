import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomBytes} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {collect, PeerClient, PeerHttpError} from './client.js'
import {PeerTransport, PeerTransportError} from './peerTransport.js'
import {World, until, type Node} from './testkit.js'

async function pairedPair() {
  const w = await World.create()
  const a = await w.node('A')
  const b = await w.node('B')
  await w.pair(b, a)
  const client = new PeerClient({identity: b.identity, host: '127.0.0.1', port: a.port, pin: a.identity.deviceId})
  return {w, a, b, client}
}

const grant = (a: Node, b: Node, actions: string[]) => a.service.setAccess(b.identity.deviceId, a.libraryId, actions as never)
const status = async (client: PeerClient, method: string, url: string, headers: Record<string, string> = {}) => {
  const r = await client.request(method, url, {headers})
  const body = await collect(r.res, 100_000_000)
  return {status: r.status, headers: r.headers, body}
}

test('authorization is deny by default and per action, and says why', async () => {
  const {w, a, b, client} = await pairedPair()
  try {
    const song = randomBytes(300_000)
    const {contentId} = await a.putFile('Music/Song.mp3', song)
    const hex = contentId.slice(7)
    const media = `/v1/revisions/${hex}?library=${a.libraryId}`
    // a freshly paired device may sync metadata, nothing else
    for (const url of [media, `/v1/libraries/${a.libraryId}/inventory`]) {
      const r = await status(client, 'GET', url)
      assert.equal(r.status, 403, url)
      assert.equal(JSON.parse(r.body.toString()).error, 'action')
    }
    assert.equal((await status(client, 'GET', `/v1/libraries/${a.libraryId}/segments?vector=`)).status, 200)
    assert.equal((await status(client, 'POST', '/v1/jobs')).status, 403, 'Download on... is not granted either')
    // a library that is not shared is refused for every action
    const other = await status(client, 'GET', `/v1/libraries/00000000-0000-4000-8000-0000000000aa/segments?vector=`)
    assert.equal(JSON.parse(other.body.toString()).error, 'library')
    // the owner of the files grants media reading: the same request now works, with real Range answers
    await grant(a, b, ['metadata.read', 'metadata.write', 'media.read'])
    const whole = await status(client, 'GET', media)
    assert.equal(whole.status, 200)
    assert.deepEqual(whole.body, song)
    const part = await status(client, 'GET', media, {range: 'bytes=1000-1999'})
    assert.equal(part.status, 206)
    assert.equal(part.headers['content-range'], `bytes 1000-1999/${song.length}`)
    assert.deepEqual(part.body, song.subarray(1000, 2000))
    const tail = await status(client, 'GET', media, {range: 'bytes=-500'})
    assert.deepEqual(tail.body, song.subarray(song.length - 500))
    assert.equal((await status(client, 'GET', media, {range: `bytes=${song.length + 5}-`})).status, 416)
    assert.equal((await status(client, 'GET', media, {range: 'bytes=abc'})).status, 416)
    assert.equal((await status(client, 'GET', `/v1/revisions/${'0'.repeat(64)}?library=${a.libraryId}`)).status, 404, 'a file that is not in the library')
    assert.equal((await status(client, 'GET', `/v1/revisions/zzz?library=${a.libraryId}`)).status, 400)
    // inventory is its own action
    assert.equal((await status(client, 'GET', `/v1/libraries/${a.libraryId}/inventory`)).status, 403)
    await grant(a, b, ['metadata.read', 'inventory.read'])
    const inventory = await status(client, 'GET', `/v1/libraries/${a.libraryId}/inventory`)
    assert.equal(inventory.status, 200)
    assert.equal(inventory.headers['x-keepfin-complete'], '1')
    assert.ok(inventory.body.toString().includes('Music/Song.mp3'))
    assert.equal((await status(client, 'GET', media)).status, 403, 'media.read was taken away again')
    // reading only: a push is refused
    assert.equal((await status(client, 'POST', `/v1/libraries/${a.libraryId}/segments`)).status, 403)
  } finally {
    client.close()
    await w.cleanup()
  }
})

test('a metadata-only device gets no media from the transport and sees no events of libraries it may not read', async () => {
  const {w, a, b, client} = await pairedPair()
  try {
    const {contentId} = await a.putFile('Music/x.bin', randomBytes(1000))
    const transport = new PeerTransport({client, libraryId: a.libraryId, device: 'B'})
    await assert.rejects(() => transport.download('Music/x.bin', path.join(b.dir, 'x.part'), contentId), (e: PeerTransportError) => e.kind === 'Denied')
    // events: nothing about a library the device may not read
    await grant(a, b, [])
    const seen: unknown[] = []
    const socket = client.events((m) => seen.push(m), () => undefined)
    await new Promise((resolve) => setTimeout(resolve, 400))
    await a.add(1)
    await a.store.flush()
    await new Promise((resolve) => setTimeout(resolve, 600))
    assert.deepEqual(seen, [], 'no notice for a library the device has no access to')
    await grant(a, b, ['metadata.read'])
    await a.add(2)
    await a.store.flush()
    await until(() => seen.some((m) => (m as {t?: string}).t === 'segments'), 'a change notice after access was granted')
    socket.terminate()
  } finally {
    client.close()
    await w.cleanup()
  }
})

test('a broken download resumes from the bytes it already has, and a wrong prefix is caught by the hash', async () => {
  const {w, a, b, client} = await pairedPair()
  try {
    await grant(a, b, ['metadata.read', 'metadata.write', 'media.read'])
    const song = randomBytes(2_000_000)
    const {contentId} = await a.putFile('Music/Big.flac', song)
    const transport = new PeerTransport({client, libraryId: a.libraryId, device: 'B'})
    const staged = path.join(b.dir, 'Big.part')
    await fs.writeFile(staged, song.subarray(0, 700_000)) // what arrived before the connection broke
    const got = await transport.download('Music/Big.flac', staged, contentId)
    assert.equal(got, contentId)
    assert.deepEqual(await fs.readFile(staged), song)
    // a partial file that is not a prefix of the real one ends in a hash mismatch and is removed
    await fs.writeFile(staged, randomBytes(700_000))
    await assert.rejects(() => transport.download('Music/Big.flac', staged, contentId), (e: PeerTransportError) => e.kind === 'Hash')
    await assert.rejects(() => fs.stat(staged), /ENOENT/)
    // and a file that really is a different one
    await assert.rejects(() => transport.download('x', staged, 'sha256:' + '1'.repeat(64)), (e: PeerTransportError) => e.kind === 'NotFound')
  } finally {
    client.close()
    await w.cleanup()
  }
})

test('the lookup by content id works against the real store (no match is a clean not-found)', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {withService: false})
    await a.add(1)
    assert.deepEqual(a.store.contentItems('sha256:' + 'a'.repeat(64)), [])
    assert.equal(await a.lib.findContent('sha256:' + 'a'.repeat(64)), null)
    void PeerHttpError
  } finally {
    await w.cleanup()
  }
})

test('the highest common version is chosen, and a device that is too old (or too new) is told so, in both directions', async () => {
  const {w, a, client} = await pairedPair()
  try {
    const both = JSON.parse((await status(client, 'GET', '/v1/hello?v=1,2,7')).body.toString())
    assert.equal(both.version, 1)
    assert.deepEqual(both.versions, [1])
    const newer = await status(client, 'GET', '/v1/hello?v=2,3')
    assert.equal(newer.status, 426)
    assert.equal(JSON.parse(newer.body.toString()).update, 'here', 'the peer is newer: this device is the one that must update')
    const older = await status(client, 'GET', '/v1/hello?v=0')
    assert.equal(older.status, 426)
    assert.equal(JSON.parse(older.body.toString()).update, 'peer', 'the peer is older: it must update')
    assert.match(JSON.parse(older.body.toString()).message, /needs to update/)
    // pairing says the same before anything is compared
    a.service.openPairing()
    const stranger = await w.node('Z', {withService: false})
    const sc = new PeerClient({identity: stranger.identity, host: '127.0.0.1', port: a.port, pin: null})
    const r = await sc.request('POST', '/v1/pair/start', {json: {versions: [5]}})
    assert.equal(r.status, 426)
    r.res.resume()
    sc.close()
  } finally {
    client.close()
    await w.cleanup()
  }
})