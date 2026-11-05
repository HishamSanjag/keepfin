// Addresses of a paired device (dev-docs/plans/DEVICES-DISCOVERY.md D3): kept in the pin store (migration, the cap of six, newest first), and the order
// in which they are tried when reconnecting.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {reconnectOrder} from './link.js'
import {cleanAddresses, MAX_ADDRESSES, PeerStore, type Peer} from './peers.js'
import {until, World} from './testkit.js'

const peer = (deviceId: string, extra: Partial<Peer> = {}): Peer => ({deviceId, spki: 'AAAA', name: 'Phone', platform: 'android', pairedAt: 1, lastSeen: null, libraries: [], ...extra})

async function withDir<T>(work: (dir: string) => Promise<T>): Promise<T> {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-peers-'))
  try {
    return await work(dir)
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
}

test('an existing store without addresses opens with none (the field is missing, not broken)', async () => {
  await withDir(async (dir) => {
    await fs.mkdir(path.join(dir, 'net'), {recursive: true})
    // a store written before 1.0 beta addresses existed
    await fs.writeFile(path.join(dir, 'net', 'peers.json'), JSON.stringify({v: 1, peers: [peer('a'.repeat(64), {lastHost: '192.168.1.40', lastPort: 47215})]}))
    const store = await PeerStore.open(dir)
    assert.deepEqual(store.get('a'.repeat(64))?.addresses, [])
    assert.equal(store.get('a'.repeat(64))?.lastHost, '192.168.1.40', 'nothing else changed')
  })
})

test('a store with hostile or too many addresses opens with the clean ones, at most six', async () => {
  await withDir(async (dir) => {
    await fs.mkdir(path.join(dir, 'net'), {recursive: true})
    const addresses = ['10.0.0.1', 'bad host', '10.0.0.2', 7, null, '10.0.0.1', 'x'.repeat(300), 'a"b', '10.0.0.3', '10.0.0.4', '10.0.0.5', '10.0.0.6', '10.0.0.7', '10.0.0.8']
    await fs.writeFile(path.join(dir, 'net', 'peers.json'), JSON.stringify({v: 1, peers: [{...peer('b'.repeat(64)), addresses}, {...peer('c'.repeat(64)), addresses: 'not a list'}]}))
    const store = await PeerStore.open(dir)
    assert.deepEqual(store.get('b'.repeat(64))?.addresses, ['10.0.0.1', '10.0.0.2', '10.0.0.3', '10.0.0.4', '10.0.0.5', '10.0.0.6'])
    assert.deepEqual(store.get('c'.repeat(64))?.addresses, [])
  })
})

test('told addresses go first, older ones follow, duplicates are dropped, the list is capped at six and written only when it changed', async () => {
  await withDir(async (dir) => {
    const store = await PeerStore.open(dir)
    await store.put(peer('d'.repeat(64)))
    store.rememberAddresses('d'.repeat(64), ['192.168.1.40', '100.101.1.2', 'pixel.tail1234.ts.net'])
    assert.deepEqual(store.get('d'.repeat(64))?.addresses, ['192.168.1.40', '100.101.1.2', 'pixel.tail1234.ts.net'])
    store.rememberAddresses('d'.repeat(64), ['10.5.5.5', '192.168.1.40'])
    assert.deepEqual(store.get('d'.repeat(64))?.addresses, ['10.5.5.5', '192.168.1.40', '100.101.1.2', 'pixel.tail1234.ts.net'], 'the new network first')
    store.rememberAddresses('d'.repeat(64), ['10.6.6.1', '10.6.6.2', '10.6.6.3', '10.6.6.4'])
    assert.equal(store.get('d'.repeat(64))?.addresses?.length, MAX_ADDRESSES)
    assert.deepEqual(store.get('d'.repeat(64))?.addresses?.slice(0, 4), ['10.6.6.1', '10.6.6.2', '10.6.6.3', '10.6.6.4'])
    const before = store.get('d'.repeat(64))?.addresses
    store.rememberAddresses('d'.repeat(64), 'garbage')
    store.rememberAddresses('d'.repeat(64), [])
    assert.deepEqual(store.get('d'.repeat(64))?.addresses, before, 'an older device that tells nothing changes nothing')
    store.rememberAddresses('unknown', ['1.2.3.4']) // a device that is not paired: ignored
    await store.settled() // a fixed wait was flaky under load
    const reopened = await PeerStore.open(dir)
    assert.deepEqual(reopened.get('d'.repeat(64))?.addresses, before, 'saved')
  })
})

test('cleanAddresses keeps hosts only: addresses and names made of address characters', () => {
  assert.deepEqual(cleanAddresses(['192.168.1.2', 'desk-pc.tail1234.ts.net', 'fd7a:115c:a1e0::1', ' 10.0.0.9 ']), ['192.168.1.2', 'desk-pc.tail1234.ts.net', 'fd7a:115c:a1e0::1', '10.0.0.9'])
  assert.deepEqual(cleanAddresses(['a b', 'x/y', 'http://x', '', '--', '"']), ['--'])
  assert.deepEqual(cleanAddresses(undefined), [])
})

test('reconnect order: what was seen now, the address that worked last, home-network addresses, then Tailscale addresses and the name', () => {
  const order = reconnectOrder({
    discovered: [{host: '192.168.1.99', port: 47215}],
    lastHost: '192.168.1.40',
    lastPort: 47215,
    addresses: ['100.101.1.2', '192.168.1.40', 'pixel.tail1234.ts.net', '10.0.0.8'],
  })
  assert.deepEqual(
    order.map((h) => h.host),
    ['192.168.1.99', '192.168.1.40', '10.0.0.8', '100.101.1.2', 'pixel.tail1234.ts.net'],
  )
  assert.ok(order.every((h) => h.port === 47215))
})

test('reconnect order: duplicates are tried once, a stored address uses the port that worked, and nothing known is an empty list', () => {
  const order = reconnectOrder({discovered: [{host: '192.168.1.40', port: 5000}], lastHost: '192.168.1.40', lastPort: 5000, addresses: ['192.168.1.40']})
  assert.deepEqual(order, [{host: '192.168.1.40', port: 5000}])
  assert.deepEqual(reconnectOrder({discovered: []}), [])
  assert.deepEqual(reconnectOrder({discovered: [], addresses: ['10.0.0.8']}), [{host: '10.0.0.8', port: 47215}], 'the default port when none was kept')
})

test('a paired device whose last address is gone is reached at a stored address; the hello then updates the list', async () => {
  const w = await World.create()
  try {
    const lo = [{name: 'Ethernet', address: '127.0.0.1', netmask: '255.255.255.0'}]
    const b = await w.node('B', {scan: {interfaces: () => lo}, tailscale: {}})
    const a = await w.node('A', {backoff: {minMs: 20, maxMs: 100}})
    await w.pair(a, b)
    await until(() => a.service.links.get(b.identity.deviceId)?.online === true, 'the first connection')
    // B "moved": the address A knew first no longer answers, but one it was told in hello does
    a.service.pauseLink(b.identity.deviceId)
    await a.service.peers.update(b.identity.deviceId, (p) => {
      p.lastHost = '127.0.0.2'
      p.lastPort = b.port
      p.addresses = ['127.0.0.1']
    })
    a.service.resumeLink(b.identity.deviceId)
    await until(() => a.service.links.get(b.identity.deviceId)?.online === true, 'the reconnection through the stored address', 15_000)
    assert.equal(a.service.links.get(b.identity.deviceId)?.lastAddress?.host, '127.0.0.1')
    assert.equal(a.service.peers.get(b.identity.deviceId)?.lastHost, '127.0.0.1')
    assert.equal(a.service.state().paired[0].route, 'lan')
  } finally {
    await w.cleanup()
  }
})

test('when nothing answers, one scan is made (at most one a minute) and the device is reached where the scan saw it', async () => {
  const w = await World.create()
  try {
    // B listens on 127.0.0.7; A knows only a dead address. A's scan walks the 127.0.0.0/24 network on B's port and finds it.
    const b = await w.node('B', {bind: '127.0.0.7'})
    const a2 = await w.node('A2', {backoff: {minMs: 20, maxMs: 100}, scan: {interfaces: () => [{name: 'Ethernet', address: '127.0.0.1', netmask: '255.255.255.0'}], port: b.port, everyMs: 60_000}, tailscale: {}, lookupEveryMs: 60_000})
    await w.pair(a2, b)
    await until(() => a2.service.links.get(b.identity.deviceId)?.online === true, 'A2 connected')
    a2.service.pauseLink(b.identity.deviceId)
    await a2.service.peers.update(b.identity.deviceId, (p) => {
      p.lastHost = '127.0.0.9'
      p.lastPort = b.port
      p.addresses = []
    })
    a2.service.resumeLink(b.identity.deviceId)
    await until(() => a2.service.links.get(b.identity.deviceId)?.online === true, 'the device found by the scan', 20_000)
    assert.equal(a2.service.links.get(b.identity.deviceId)?.lastAddress?.host, '127.0.0.7')
  } finally {
    await w.cleanup()
  }
})
