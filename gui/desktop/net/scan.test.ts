// The scan (dev-docs/plans/DEVICES-DISCOVERY.md D2): which hosts are asked, how many at once, what is skipped, how results merge with DNS-SD, and
// that it works against real servers (two Keepfin servers on loopback, one finds the other through GET /v1/announce).
import {test} from 'node:test'
import assert from 'node:assert/strict'
import net from 'node:net'
import type {DiscoveredDevice} from '../../shared/devices.js'
import {hostsToScan, mergeNearby, NearbyScanner, parseAnnouncement, pool, realScanLayer, scanNetworks, type ScanLayer} from './scan.js'
import {until, World} from './testkit.js'

const iface = (address: string, netmask = '255.255.255.0') => ({name: 'Ethernet', address, netmask})
const SELF = 'aaaaaaaaaaaaaaaa'

test('the hosts of the /24 around each real address, without this device and without Tailscale', () => {
  const hosts = hostsToScan([iface('192.168.1.20'), iface('100.101.102.103')])
  assert.equal(hosts.length, 253, '254 hosts minus this one')
  assert.ok(!hosts.includes('192.168.1.20') && !hosts.includes('192.168.1.0') && !hosts.includes('192.168.1.255'))
  assert.ok(hosts.includes('192.168.1.1') && hosts.includes('192.168.1.254'))
  assert.ok(!hosts.some((h) => h.startsWith('100.')), 'Tailscale (100.64.0.0/10) is never walked')
})

test('a bigger subnet is still only the /24 around the address; a smaller one only its own hosts; one network is walked once', () => {
  assert.equal(hostsToScan([iface('10.0.0.5', '255.255.0.0')]).length, 253)
  const small = hostsToScan([iface('192.168.7.130', '255.255.255.192')])
  assert.deepEqual([small[0], small[small.length - 1], small.length], ['192.168.7.129', '192.168.7.190', 61])
  assert.equal(hostsToScan([iface('192.168.1.20'), iface('192.168.1.21')]).length, 252, 'two adapters on one network, both addresses skipped')
  assert.deepEqual(hostsToScan([{name: 'x', address: 'garbage'}]), [])
  assert.equal(hostsToScan([iface('10.1.1.1'), iface('10.1.2.1'), iface('10.1.3.1'), iface('10.1.4.1'), iface('10.1.5.1')]).length, 4 * 253, 'at most four networks')
})

test('the pool never runs more than the limit at once and visits every item', async () => {
  let running = 0
  let peak = 0
  const seen: number[] = []
  await pool(
    Array.from({length: 200}, (_v, i) => i),
    48,
    async (i) => {
      running++
      peak = Math.max(peak, running)
      await new Promise((r) => setTimeout(r, 2))
      seen.push(i)
      running--
    },
  )
  assert.equal(seen.length, 200)
  assert.ok(peak <= 48 && peak > 1, `peak ${peak}`)
})

test('a scan asks only hosts whose port is open, skips itself, and 48 connects run at most at once', async () => {
  const connected: string[] = []
  const announced: string[] = []
  let running = 0
  let peak = 0
  const layer: ScanLayer = {
    connect: async (host, port, ms) => {
      assert.equal(port, 47215)
      assert.equal(ms, 400, 'the connect budget of the plan')
      running++
      peak = Math.max(peak, running)
      await new Promise((r) => setTimeout(r, 1))
      running--
      connected.push(host)
      return host === '192.168.1.40' || host === '192.168.1.77' || host === '192.168.1.99'
    },
    announce: async (host, _port, ms) => {
      assert.equal(ms, 1500, 'the announce budget of the plan')
      announced.push(host)
      if (host === '192.168.1.40') return {id: 'b1b2b3b4b5b6b7b8', name: 'Pixel 9', platform: 'android'}
      if (host === '192.168.1.99') return {id: SELF, name: 'This PC', platform: 'windows'} // this device through another address
      return undefined // port open, but not Keepfin
    },
  }
  const found = await scanNetworks({interfaces: [iface('192.168.1.20')], selfHint: SELF, port: 47215, layer})
  assert.equal(connected.length, 253)
  assert.ok(!connected.includes('192.168.1.20'), 'its own address is not asked')
  assert.deepEqual(announced.sort(), ['192.168.1.40', '192.168.1.77', '192.168.1.99'], 'only hosts with the port open are asked who they are')
  assert.deepEqual(found, [{hint: 'b1b2b3b4b5b6b7b8', name: 'Pixel 9', host: '192.168.1.40', port: 47215, platform: 'android', route: 'lan'}])
  assert.ok(peak <= 48, `peak ${peak}`)
})

test('an announcement is accepted only in its exact shape', () => {
  assert.deepEqual(parseAnnouncement({v: 1, id: 'AB12CD34EF567890', n: ' Pixel ', p: 'android'}), {id: 'ab12cd34ef567890', name: 'Pixel', platform: 'android'})
  for (const bad of [null, [], 'x', {}, {v: 2, id: 'ab12cd34ef567890', n: 'x', p: 'android'}, {v: 1, id: 'short', n: 'x'}, {v: 1, id: 'ab12cd34ef567890', n: ''}, {v: 1, id: 'zz12cd34ef567890', n: 'x'}]) {
    assert.equal(parseAnnouncement(bad), undefined, JSON.stringify(bad))
  }
  assert.equal(parseAnnouncement({v: 1, id: 'ab12cd34ef567890', n: 'x', p: 'toaster'})?.platform, '', 'an unknown platform is not repeated')
})

test('results merge with DNS-SD by hint: the DNS-SD entry wins, a home-network entry beats a Tailscale one', () => {
  const dns: DiscoveredDevice[] = [{hint: 'a'.repeat(16), name: 'Phone (mDNS)', host: '192.168.1.5', port: 47215, paired: false, route: 'lan'}]
  const scanned = [
    {hint: 'a'.repeat(16), name: 'Phone (scan)', host: '192.168.1.9', port: 47215, platform: 'android', route: 'lan' as const},
    {hint: 'b'.repeat(16), name: 'Laptop', host: '100.64.0.7', port: 47215, platform: 'windows', route: 'tailscale' as const},
    {hint: 'b'.repeat(16), name: 'Laptop', host: '192.168.1.60', port: 47215, platform: 'windows', route: 'lan' as const},
    {hint: 'c'.repeat(16), name: 'Away PC', host: '100.64.0.9', port: 47215, platform: 'windows', route: 'tailscale' as const},
  ]
  const merged = mergeNearby(dns, scanned)
  assert.deepEqual(
    merged.map((d) => [d.hint[0], d.name, d.host, d.route]),
    [
      ['a', 'Phone (mDNS)', '192.168.1.5', 'lan'],
      ['b', 'Laptop', '192.168.1.60', 'lan'],
      ['c', 'Away PC', '100.64.0.9', 'tailscale'],
    ],
  )
})

test('a scanner keeps the last round, reads Tailscale, probes only its online peers, and says when the list changed', async () => {
  const probed: string[] = []
  let changed = 0
  const layer: ScanLayer = {
    connect: async (host) => {
      probed.push(host)
      return host === '192.168.1.40' || host === '100.64.0.2'
    },
    announce: async (host) => (host === '192.168.1.40' ? {id: 'b1b2b3b4b5b6b7b8', name: 'Pixel 9', platform: 'android'} : host === '100.64.0.2' ? {id: 'c1c2c3c4c5c6c7c8', name: 'Away PC', platform: 'windows'} : undefined),
  }
  const scanner = new NearbyScanner({
    interfaces: () => [iface('192.168.1.20')],
    selfHint: SELF,
    port: 47215,
    layer,
    everyMs: 60_000,
    changed: () => changed++,
    adapters: () => ({'Tailscale': [{address: '100.64.0.1', family: 'IPv4', internal: false} as never]}),
    tailscaleStatus: async () => ({
      running: true,
      name: 'desk-pc.tail1234.ts.net',
      address: '100.64.0.1',
      peers: [
        {name: 'away-pc.tail1234.ts.net', address: '100.64.0.2', online: true},
        {name: 'old-phone.tail1234.ts.net', address: '100.64.0.3', online: false},
        {name: 'desk-pc.tail1234.ts.net', address: '100.64.0.1', online: true},
      ],
    }),
  })
  scanner.start()
  await until(() => scanner.list().length === 2, 'both devices')
  assert.deepEqual(
    scanner.list().map((d) => [d.name, d.route]),
    [
      ['Pixel 9', 'lan'],
      ['Away PC', 'tailscale'],
    ],
  )
  assert.ok(probed.includes('100.64.0.2'), 'an online Tailscale peer is asked')
  assert.ok(!probed.includes('100.64.0.3'), 'an offline one is not')
  assert.ok(!probed.includes('100.64.0.1'), 'this PC is not')
  assert.deepEqual(scanner.tailscale(), {state: 'on', name: 'desk-pc.tail1234.ts.net', address: '100.64.0.1'})
  assert.equal(scanner.magicName(), 'desk-pc.tail1234.ts.net')
  assert.ok(changed >= 1)
  scanner.stop()
  assert.deepEqual(scanner.list(), [], 'stopping forgets what was found')
})

test('without an adapter or a status a PC is simply not on Tailscale', () => {
  const scanner = new NearbyScanner({interfaces: () => [], selfHint: SELF, port: 1, layer: {connect: async () => false, announce: async () => undefined}, changed: () => undefined, adapters: () => ({Ethernet: [{address: '192.168.1.2', family: 'IPv4', internal: false} as never]})})
  assert.deepEqual(scanner.tailscale(), {state: 'off'})
})

// ---- the real network layer ---------------------------------------------------------------------------------------------------

test('the real connect answers true for an open port, false for a closed one and false within its budget for a host that never answers', async () => {
  const server = net.createServer((socket) => socket.on('error', () => undefined))
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const port = (server.address() as net.AddressInfo).port
  try {
    const layer = realScanLayer(() => ({keyPem: '', certPem: ''}))
    assert.equal(await layer.connect('127.0.0.1', port, 400), true)
    server.close()
    assert.equal(await layer.connect('127.0.0.1', port, 400), false, 'closed')
    const started = Date.now()
    assert.equal(await layer.connect('192.0.2.1', 47215, 150), false, 'TEST-NET never answers')
    assert.ok(Date.now() - started < 1500, 'the timeout is honoured')
  } finally {
    server.close()
  }
})

test('the real announce gives up on a server that accepts the connection and never speaks, within its budget', async () => {
  const w = await World.create()
  const silent = net.createServer((socket) => socket.on('error', () => undefined)) // accepts TCP, never answers TLS
  await new Promise<void>((resolve) => silent.listen(0, '127.0.0.1', resolve))
  try {
    const a = await w.node('A')
    const layer = realScanLayer(() => a.identity)
    const started = Date.now()
    assert.equal(await layer.announce('127.0.0.1', (silent.address() as net.AddressInfo).port, 250), undefined)
    assert.ok(Date.now() - started < 2000, `gave up after ${Date.now() - started} ms`)
  } finally {
    silent.close()
    await w.cleanup()
  }
})

test('loopback: one real Keepfin server finds another through GET /v1/announce, skipping itself and the silent ones', async () => {
  const w = await World.create()
  try {
    // B listens on 127.0.0.7 only. A (127.0.0.1) walks the /24 around its own address on B's port: that is 253 real connects, one real
    // TLS handshake and one real GET /v1/announce; A's own address is not asked.
    const b = await w.node('B', {bind: '127.0.0.7'})
    const a = await w.node('A')
    const found = await scanNetworks({interfaces: [iface('127.0.0.1')], selfHint: a.identity.deviceId.slice(0, 16), port: b.port, layer: realScanLayer(() => a.identity)})
    assert.deepEqual(found, [{hint: b.identity.deviceId.slice(0, 16), name: b.identity.name, host: '127.0.0.7', port: b.port, platform: 'windows', route: 'lan'}])
    // a device that finds its own announcement through another address ignores it
    const self = await scanNetworks({interfaces: [iface('127.0.0.1')], selfHint: b.identity.deviceId.slice(0, 16), port: b.port, layer: realScanLayer(() => a.identity)})
    assert.deepEqual(self, [])
  } finally {
    await w.cleanup()
  }
})

test('loopback: a service shows a device found by the scan in Nearby, pairs with it, and then stops listing it', async () => {
  const w = await World.create()
  try {
    const b = await w.node('B', {bind: '127.0.0.7'})
    const a = await w.node('A', {scan: {interfaces: () => [iface('127.0.0.1')], port: b.port, everyMs: 60_000}, tailscale: {}})
    assert.equal(a.service.state().scanning, false, 'nothing is looked for until someone looks')
    a.service.watchNearby()
    assert.equal(a.service.state().scanning, true)
    await until(() => a.service.state().discovered.length === 1, 'the scan to find B', 15_000)
    const [found] = a.service.state().discovered
    assert.equal(found.name, b.identity.name)
    assert.equal(found.host, '127.0.0.7')
    assert.equal(found.route, 'lan')
    assert.equal(found.paired, false)
    b.service.openPairing()
    const done = a.service.ask(`${found.host}:${found.port}`)
    await until(() => a.service.state().pairing?.sas !== undefined && b.service.state().pairing?.sas !== undefined, 'the numbers')
    a.service.decide(a.service.state().pairing!.id, true)
    b.service.decide(b.service.state().pairing!.id, true)
    await done
    await until(() => a.service.state().paired.length === 1, 'the pairing to be kept')
    assert.deepEqual(a.service.state().discovered, [], 'a paired device is no longer offered')
    a.service.unwatchNearby()
    assert.equal(a.service.state().scanning, false)
    assert.deepEqual(a.service.state().discovered, [], 'when nobody looks, nothing is listed')
  } finally {
    await w.cleanup()
  }
})
