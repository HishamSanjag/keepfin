// GET /v1/announce (dev-docs/plans/DEVICES-DISCOVERY.md D2): anyone who completes the handshake may ask who a device is, and learns exactly the facts of
// the DNS-SD record. It has its own budget per address, and hello tells paired devices how this device can be reached (D3).
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {PeerClient, PeerHttpError} from './client.js'
import {World, until} from './testkit.js'

const stranger = (w: World, from: {identity: {keyPem: string; certPem: string}}, to: {port: number}) => new PeerClient({identity: from.identity, host: '127.0.0.1', port: to.port, pin: null, connectTimeoutMs: 3000})

test('a stranger may announce-ask a device: three facts, nothing else', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B', {enable: false, withService: true})
    const client = stranger(w, b, a)
    const body = await client.json<Record<string, unknown>>('GET', '/v1/announce')
    client.close()
    assert.deepEqual(Object.keys(body).sort(), ['id', 'n', 'p', 'v'])
    assert.deepEqual(body, {v: 1, id: a.identity.deviceId.slice(0, 16), n: a.identity.name, p: 'windows'})
    assert.match(String(body.id), /^[0-9a-f]{16}$/)
  } finally {
    await w.cleanup()
  }
})

test('announce reveals no libraries, peers or addresses, and a stranger still gets nothing else', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    const c = await w.node('C')
    await w.pair(a, b)
    const client = stranger(w, c, a)
    const text = JSON.stringify(await client.json('GET', '/v1/announce'))
    assert.ok(!text.includes(a.libraryId) && !text.includes(b.identity.deviceId) && !text.includes('127.0.0.1'), text)
    await assert.rejects(client.json('GET', '/v1/hello?v=1'), (e: PeerHttpError) => e.status === 403)
    await assert.rejects(client.json('GET', `/v1/libraries/${a.libraryId}/inventory`), (e: PeerHttpError) => e.status === 403)
    client.close()
  } finally {
    await w.cleanup()
  }
})

test('announce has its own budget per address, apart from the stranger budget', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {limits: {announcePerMinute: 5, strangerRequestsPerMinute: 2}})
    const b = await w.node('B', {enable: false, withService: true})
    for (let i = 0; i < 5; i++) {
      const client = stranger(w, b, a)
      await client.json('GET', '/v1/announce')
      client.close()
    }
    const client = stranger(w, b, a)
    await assert.rejects(client.json('GET', '/v1/announce'), (e: PeerHttpError) => e.status === 429)
    client.close()
    // the stranger budget (2 a minute) was never touched by the five announces: a different request is still answered (with a refusal, not a 429)
    const other = stranger(w, b, a)
    await assert.rejects(other.json('GET', '/v1/hello?v=1'), (e: PeerHttpError) => e.status === 403)
    other.close()
  } finally {
    await w.cleanup()
  }
})

test('hello tells a paired device how this one can be reached (at most six addresses); the pin keeps them newest first', async () => {
  const w = await World.create()
  try {
    const lo = [{name: 'Ethernet', address: '127.0.0.1', netmask: '255.255.255.0'}]
    const a = await w.node('A', {scan: {interfaces: () => lo}, tailscale: {}})
    const b = await w.node('B', {backoff: {minMs: 20, maxMs: 100}})
    await w.pair(b, a)
    await until(() => (b.service.peers.get(a.identity.deviceId)?.addresses ?? []).includes('127.0.0.1'), 'the hello addresses to be kept')
    assert.deepEqual(b.service.peers.get(a.identity.deviceId)?.addresses, ['127.0.0.1'])
    const hello = b.service.links.get(a.identity.deviceId)?.lastHello
    assert.deepEqual(hello?.addresses, ['127.0.0.1'])
    // a device with eight adapters tells six
    const many = Array.from({length: 8}, (_v, i) => ({name: `n${i}`, address: `10.0.${i}.5`, netmask: '255.255.255.0'}))
    const c = await w.node('C', {scan: {interfaces: () => many}, tailscale: {}})
    await w.pair(b, c)
    await until(() => (b.service.peers.get(c.identity.deviceId)?.addresses ?? []).length > 0, 'the addresses of C')
    assert.equal(b.service.peers.get(c.identity.deviceId)?.addresses?.length, 6)
  } finally {
    await w.cleanup()
  }
})
