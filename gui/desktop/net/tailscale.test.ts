// Tailscale (dev-docs/plans/DEVICES-DISCOVERY.md D3): detection from the adapters, the status JSON, and what the service shows.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {detectTailscaleAddress, isTailscaleAddress, isTailscaleHost, parseTailscaleStatus, tailscaleProgram, windowsStatusReader} from './tailscale.js'
import {until, World} from './testkit.js'

const STATUS = {
  Version: '1.80.0',
  BackendState: 'Running',
  MagicDNSSuffix: 'tail1234.ts.net',
  Self: {ID: 'n1', HostName: 'DESK-PC', DNSName: 'desk-pc.tail1234.ts.net.', TailscaleIPs: ['100.101.102.103', 'fd7a:115c:a1e0::1'], Online: true, OS: 'windows'},
  Peer: {
    'nodekey:1': {ID: 'n2', HostName: 'pixel-9', DNSName: 'pixel-9.tail1234.ts.net.', TailscaleIPs: ['100.64.0.2', 'fd7a:115c:a1e0::2'], Online: true, OS: 'android'},
    'nodekey:2': {ID: 'n3', HostName: 'old-laptop', DNSName: 'old-laptop.tail1234.ts.net.', TailscaleIPs: ['100.64.0.3'], Online: false, OS: 'windows'},
    'nodekey:3': {ID: 'n4', HostName: 'no-address', DNSName: 'no-address.tail1234.ts.net.', TailscaleIPs: ['fd7a:115c:a1e0::4'], Online: true},
  },
}

test('the Tailscale range is 100.64.0.0/10 and a name ends in .ts.net', () => {
  for (const yes of ['100.64.0.0', '100.101.102.103', '100.127.255.255']) assert.equal(isTailscaleAddress(yes), true, yes)
  for (const no of ['100.63.255.255', '100.128.0.0', '10.0.0.1', '192.168.1.5', '101.64.0.1', 'garbage', '']) assert.equal(isTailscaleAddress(no), false, no)
  assert.equal(isTailscaleHost('desk-pc.tail1234.ts.net'), true)
  assert.equal(isTailscaleHost('desk-pc.tail1234.ts.net.'), true)
  assert.equal(isTailscaleHost('100.101.1.1'), true)
  assert.equal(isTailscaleHost('desk-pc.local'), false)
})

test('an adapter with an address in the range means this PC is on a tailnet; loopback and IPv6 do not count', () => {
  const adapter = (address: string, family = 'IPv4', internal = false) => ({address, family, internal}) as never
  assert.equal(detectTailscaleAddress({Ethernet: [adapter('192.168.1.5')], Tailscale: [adapter('100.101.102.103')]}), '100.101.102.103')
  assert.equal(detectTailscaleAddress({Ethernet: [adapter('192.168.1.5')]}), undefined)
  assert.equal(detectTailscaleAddress({lo: [adapter('100.101.102.103', 'IPv4', true)]}), undefined)
  assert.equal(detectTailscaleAddress({Tailscale: [adapter('fd7a:115c:a1e0::1', 'IPv6')]}), undefined)
  assert.equal(detectTailscaleAddress({}), undefined)
})

test('the status JSON gives this PC its MagicDNS name and address, and the peers that have an IPv4 address', () => {
  const status = parseTailscaleStatus(JSON.stringify(STATUS))!
  assert.equal(status.running, true)
  assert.equal(status.name, 'desk-pc.tail1234.ts.net', 'the trailing dot goes')
  assert.equal(status.address, '100.101.102.103')
  assert.deepEqual(status.peers, [
    {name: 'pixel-9.tail1234.ts.net', address: '100.64.0.2', online: true},
    {name: 'old-laptop.tail1234.ts.net', address: '100.64.0.3', online: false},
  ])
})

test('a stopped Tailscale is not running; garbage is nothing; hostile names are dropped', () => {
  assert.equal(parseTailscaleStatus(JSON.stringify({...STATUS, BackendState: 'Stopped'}))?.running, false)
  assert.equal(parseTailscaleStatus(JSON.stringify({BackendState: 'NeedsLogin'}))?.running, false)
  for (const bad of ['', 'nope', 'null', '[]', '42', '{}', '{"Peer": ']) assert.equal(parseTailscaleStatus(bad), undefined, bad)
  const hostile = parseTailscaleStatus(JSON.stringify({...STATUS, Self: {...STATUS.Self, DNSName: 'a b;rm -rf'}, Peer: {x: {DNSName: 'evil name', HostName: 'x"y', TailscaleIPs: ['100.64.0.9'], Online: true}}}))!
  assert.equal(hostile.name, 'DESK-PC', 'an unusable DNS name falls back to the host name')
  assert.deepEqual(hostile.peers, [])
  const many = {BackendState: 'Running', Peer: Object.fromEntries(Array.from({length: 400}, (_v, i) => [`k${i}`, {HostName: `h${i}`, TailscaleIPs: ['100.64.1.1'], Online: true}]))}
  assert.equal(parseTailscaleStatus(JSON.stringify(many))!.peers.length, 256, 'at most 256 peers are read')
})

test('the status reader runs only the Tailscale program in Program Files, without a shell, and only on Windows when it exists', async () => {
  assert.equal(tailscaleProgram({ProgramFiles: 'D:\\Programs'} as never), 'D:\\Programs\\Tailscale\\tailscale.exe')
  assert.equal(tailscaleProgram({} as never), 'C:\\Program Files\\Tailscale\\tailscale.exe')
  assert.equal(await windowsStatusReader({platform: 'linux', exists: () => true})(), undefined)
  assert.equal(await windowsStatusReader({platform: 'win32', exists: () => false})(), undefined, 'not installed')
  assert.equal(await windowsStatusReader({platform: 'win32', exists: () => true, program: 'C:\\no\\such\\tailscale.exe', timeoutMs: 500})(), undefined, 'a program that cannot start')
})

test('the page state says Tailscale is on (with the name once the status was read) or off', async () => {
  const w = await World.create()
  try {
    const adapters = {current: {Ethernet: [{address: '192.168.1.5', family: 'IPv4', internal: false}]} as never}
    const a = await w.node('A', {
      scan: {interfaces: () => [{name: 'Ethernet', address: '192.168.1.5', netmask: '255.255.255.0'}], layer: {connect: async () => false, announce: async () => undefined}, everyMs: 60_000},
      tailscale: {adapters: () => adapters.current, status: async () => parseTailscaleStatus(JSON.stringify(STATUS))},
    })
    assert.deepEqual(a.service.state().tailscale, {state: 'off'})
    assert.equal(a.service.state().lan, true)
    adapters.current = {Ethernet: [{address: '192.168.1.5', family: 'IPv4', internal: false}], Tailscale: [{address: '100.101.102.103', family: 'IPv4', internal: false}]} as never
    await until(() => a.service.state().tailscale.state === 'on' && a.service.state().tailscale.name === 'desk-pc.tail1234.ts.net', 'Tailscale to show as on with its name')
    assert.deepEqual(a.service.state().tailscale, {state: 'on', name: 'desk-pc.tail1234.ts.net', address: '100.101.102.103'})
  } finally {
    await w.cleanup()
  }
})
