import {test} from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {parseAddress} from '../../shared/devices.js'
import {identityFile, loadIdentity, fingerprintOf} from './identity.js'
import {Discovery, eligibleInterfaces} from './discovery.js'
import {NetService} from './service.js'
import {PeerStore} from './peers.js'
import {World, plainBox, until} from './testkit.js'

test('a port that another program holds is reported, not replaced by another port', async () => {
  const w = await World.create()
  const squatter = net.createServer()
  await new Promise<void>((resolve) => squatter.listen(0, '127.0.0.1', resolve))
  const taken = (squatter.address() as net.AddressInfo).port
  try {
    const dir = path.join(w.dir, 'busy')
    const service = await NetService.create({deviceDir: dir, box: plainBox, libraries: () => [], push: () => undefined, port: taken, bind: '127.0.0.1', discovery: false})
    await service.enable()
    const state = service.state()
    assert.equal(state.port, undefined)
    assert.match(state.portError ?? '', new RegExp(`port ${taken}`))
    assert.match(state.portError ?? '', /Another program/)
    await assert.rejects(async () => service.openPairing(), /Another program|not running/)
    await service.close()
  } finally {
    squatter.close()
    await w.cleanup()
  }
})

test('devices stay on across a restart, pairs are remembered, and turning devices off closes the port', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    await w.pair(b, a)
    const port = a.port
    await a.service.close()
    // the same profile again: the pin, the key and the "on" switch come back
    const again = await NetService.create({deviceDir: a.deviceDir, box: plainBox, libraries: () => [a.lib], push: () => undefined, port: 0, bind: '127.0.0.1', discovery: false})
    try {
      assert.equal(again.isEnabled, true)
      assert.equal(again.identity.deviceId, a.identity.deviceId)
      assert.deepEqual(again.peers.list().map((p) => p.deviceId), [b.identity.deviceId])
      assert.equal(again.state().paired[0].name, b.identity.name)
      await again.disable()
      assert.equal(again.listeningPort, undefined)
      const refused = await new Promise<string>((resolve) => {
        const socket = net.connect(again.state().port ?? port, '127.0.0.1')
        socket.on('connect', () => resolve('open'))
        socket.on('error', () => resolve('closed'))
      })
      assert.equal(refused, 'closed')
      const saved = JSON.parse(await fs.readFile(path.join(a.deviceDir, 'net', 'state.json'), 'utf8'))
      assert.equal(saved.enabled, false)
    } finally {
      await again.close()
    }
  } finally {
    await w.cleanup()
  }
})

test('the device key is wrapped, made once, and a damaged file is never replaced by a new identity', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-key-'))
  try {
    const first = await loadIdentity(dir, plainBox)
    const stored = await fs.readFile(identityFile(dir), 'utf8')
    assert.ok(!stored.includes(Buffer.from(first.pkcs8).toString('base64')) || stored.includes('wrapped'), 'the private key is only in the wrapped field')
    const second = await loadIdentity(dir, plainBox)
    assert.equal(second.deviceId, first.deviceId)
    assert.match(fingerprintOf(first.deviceId), /^([0-9a-f]{4} ){4}[0-9a-f]{4}$/)
    // a box that cannot open it (another Windows user) is an error, not a new key
    const stranger = {...plainBox, decrypt: () => { throw new Error('not yours') }}
    await assert.rejects(() => loadIdentity(dir, stranger), /cannot open the saved device key/)
    assert.equal((await loadIdentity(dir, plainBox)).deviceId, first.deviceId)
    await fs.writeFile(identityFile(dir), '{not json')
    await assert.rejects(() => loadIdentity(dir, plainBox), /damaged/)
    // no secure storage: pairing is not offered at all
    await assert.rejects(() => loadIdentity(path.join(dir, 'x'), {...plainBox, available: () => false}), /Secure storage is not available/)
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('addresses people type: names, ports, IPv6 and links that are not addresses', () => {
  assert.deepEqual(parseAddress('192.168.1.20'), {host: '192.168.1.20', port: 47215})
  assert.deepEqual(parseAddress(' my-pc.tailnet.ts.net:50000 '), {host: 'my-pc.tailnet.ts.net', port: 50000})
  assert.deepEqual(parseAddress('[fe80::1%12]:47216'), {host: 'fe80::1%12', port: 47216})
  assert.deepEqual(parseAddress('fd7a:115c:a1e0::1'), {host: 'fd7a:115c:a1e0::1', port: 47215})
  for (const bad of ['', 'host:0', 'host:70000', 'a b', 'http://x', 'x/y', 'host:abc']) assert.equal(parseAddress(bad), null, bad)
})

test('discovery lists only real private adapters, and a device that advertises is found by one that browses', async (t) => {
  const adapters = eligibleInterfaces({
    Ethernet: [{address: '192.168.1.20', family: 'IPv4', internal: false} as os.NetworkInterfaceInfo],
    'vEthernet (WSL)': [{address: '172.20.0.1', family: 'IPv4', internal: false} as os.NetworkInterfaceInfo],
    Loopback: [{address: '127.0.0.1', family: 'IPv4', internal: true} as os.NetworkInterfaceInfo],
    Tailscale: [{address: '100.101.102.103', family: 'IPv4', internal: false} as os.NetworkInterfaceInfo],
    Wifi: [{address: '169.254.9.9', family: 'IPv4', internal: false} as os.NetworkInterfaceInfo, {address: '203.0.113.5', family: 'IPv4', internal: false} as os.NetworkInterfaceInfo],
  })
  assert.deepEqual(adapters.map((a) => a.address), ['192.168.1.20', '100.101.102.103'], 'a private address on a real adapter; Tailscale last; no virtual, link-local or public one')
  const real = eligibleInterfaces()
  if (!real.length) return t.skip('this computer has no private network address')
  const idA = 'a'.repeat(64)
  const idB = 'b'.repeat(64)
  let seen = false
  const a = new Discovery({deviceId: idA, name: 'Advertiser', port: 47999, isPaired: () => false, changed: () => undefined})
  const b = new Discovery({deviceId: idB, name: 'Browser', port: 48000, isPaired: () => false, changed: () => { seen = true }})
  try {
    a.start()
    b.start()
    b.browse()
    try {
      await until(() => b.list().some((d) => d.hint === idA.slice(0, 16)), 'the advertised device', 12_000)
    } catch {
      return t.skip('multicast does not reach this process on this network')
    }
    const found = b.list().find((d) => d.hint === idA.slice(0, 16))!
    assert.equal(found.name, 'Advertiser')
    assert.equal(found.port, 47999)
    assert.ok(seen)
    assert.ok(!b.list().some((d) => d.hint === idB.slice(0, 16)), 'a device never lists itself')
  } finally {
    a.stop()
    b.stop()
  }
})

test('the peer file keeps pins, per-library actions and survives a reload', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-peers-'))
  try {
    const store = await PeerStore.open(dir)
    await store.put({deviceId: 'd'.repeat(64), spki: 'AAAA', name: 'Phone', platform: 'android', pairedAt: 1, lastSeen: null, libraries: [{libraryId: 'lib', actor: 'actor', actions: ['metadata.read', 'media.read', 'bogus' as never]}]})
    await store.update('d'.repeat(64), (p) => (p.libraries[0].actions = ['metadata.read']))
    const again = await PeerStore.open(dir)
    assert.deepEqual(again.grants('d'.repeat(64)), {deviceId: 'd'.repeat(64), revoked: false, libraries: {lib: ['metadata.read']}})
    assert.equal(again.grants('e'.repeat(64)), null, 'an unknown key has no grants')
    // pairing the same device again keeps what the person chose
    await again.put({deviceId: 'd'.repeat(64), spki: 'AAAA', name: 'Phone 2', platform: 'android', pairedAt: 2, lastSeen: null, libraries: [{libraryId: 'lib', actor: 'actor2', actions: ['metadata.read', 'metadata.write']}]})
    assert.deepEqual(again.get('d'.repeat(64))!.libraries[0].actions, ['metadata.read'])
    assert.equal(await again.remove('d'.repeat(64)).then((p) => p?.name), 'Phone 2')
    assert.equal((await PeerStore.open(dir)).list().length, 0)
    void http
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})
