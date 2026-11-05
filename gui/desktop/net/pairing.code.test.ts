// The typed pairing code (dev-docs/plans/DEVICES-REDESIGN.md, Phase 2): the answering side (one use, expiry, separate from tokens, wrong
// guesses, the announce flag) and the asking side (it only talks to a device whose key starts with the code's 30 pin bits).
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import {pairCodeOf, pairPin, parsePairCode, parsePairQr, pinMatches} from '../../shared/core/net.js'
import {PeerClient} from './client.js'
import {loadIdentity, type DeviceIdentity} from './identity.js'
import {DEFAULT_LIMITS} from './limits.js'
import {PAIR_SESSION_MS, PairError, PairingHost, peerInfo} from './pairing.js'
import {World, plainBox, until} from './testkit.js'

async function identities(count: number): Promise<{list: DeviceIdentity[]; cleanup: () => Promise<void>}> {
  const dirs: string[] = []
  const list: DeviceIdentity[] = []
  for (let i = 0; i < count; i++) {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-code-'))
    dirs.push(dir)
    list.push(await loadIdentity(dir, plainBox))
  }
  return {list, cleanup: async () => void (await Promise.all(dirs.map((d) => fs.rm(d, {recursive: true, force: true}))))}
}

function makeHost(self: DeviceIdentity, clock: {now: number}, pairAttempts = 1000) {
  return new PairingHost({identity: () => self, limits: {...DEFAULT_LIMITS, pairAttempts}, now: () => clock.now, libraries: () => [], onPaired: async () => undefined, changed: () => undefined})
}

const ask = (host: PairingHost, who: DeviceIdentity, address: string, token: Uint8Array | null, codeValue: number | null = null) => host.start(peerInfo(who.spki, {name: 'Laptop'}), address, token, codeValue)
const errorOf = (fn: () => unknown) => {
  try {
    fn()
  } catch (error) {
    return error instanceof PairError ? error.code : String(error)
  }
  return 'ok'
}
const tokenOf = (code: string) => parsePairCode(code)!.token

test('a code is made with the QR, carries this device\'s pin bits, renews with the QR and expires with it', async () => {
  const ids = await identities(1)
  try {
    const clock = {now: 1_700_000_000_000}
    const host = makeHost(ids.list[0], clock)
    assert.equal(host.codeShowing, false)
    const first = host.makeQr('192.168.1.50', 47215)
    assert.match(first.code, /^[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}$/)
    const parsed = parsePairCode(first.code)!
    assert.equal(pinMatches(pairPin(ids.list[0].spki), parsed.pinBits), true)
    assert.equal(pairCodeOf(parsed.token, pairPin(ids.list[0].spki)), first.code)
    assert.equal(host.codeShowing, true)
    let renewed = false
    for (let i = 0; i < 5 && !renewed; i++) renewed = host.makeQr('192.168.1.50', 47215).code !== first.code
    assert.equal(renewed, true, 'each QR brings a new code')
    clock.now += PAIR_SESSION_MS + 1
    assert.equal(host.codeShowing, false, 'the code lives as long as the QR')
    host.makeQr('192.168.1.50', 47215)
    assert.equal(host.codeShowing, true)
    host.close()
    assert.equal(host.codeShowing, false, 'closing pairing withdraws it')
  } finally {
    await ids.cleanup()
  }
})

test('a typed code is a scanned code: accepted with the window closed from anywhere, approved by itself, one use', async () => {
  const ids = await identities(4)
  try {
    const clock = {now: 1_700_000_000_000}
    const host = makeHost(ids.list[0], clock)
    const {code} = host.makeQr('192.168.1.50', 47215)
    const started = ask(host, ids.list[1], '8.8.8.8', null, tokenOf(code))
    assert.ok(started.id)
    const view = host.view()!
    assert.equal(view.method, 'qr')
    assert.equal(view.stage, 'approving')
    assert.equal(view.unrequested, false)
    host.decide(started.id, true)
    assert.equal(host.codeShowing, false, 'consumed')
    assert.equal(errorOf(() => ask(host, ids.list[2], '8.8.8.8', null, tokenOf(code))), 'token', 'a second use is refused')
  } finally {
    await ids.cleanup()
  }
})

test('an expired code is refused, and so is a code nobody made', async () => {
  const ids = await identities(3)
  try {
    const clock = {now: 1_700_000_000_000}
    const host = makeHost(ids.list[0], clock)
    assert.equal(errorOf(() => ask(host, ids.list[1], '192.168.1.5', null, 12345)), 'token', 'no code is showing')
    const {code} = host.makeQr('192.168.1.50', 47215)
    clock.now += PAIR_SESSION_MS + 1
    assert.equal(errorOf(() => ask(host, ids.list[2], '192.168.1.6', null, tokenOf(code))), 'token')
  } finally {
    await ids.cleanup()
  }
})

test('codes and tokens are separate secrets: neither opens the other, and using one leaves the other', async () => {
  const ids = await identities(4)
  try {
    const clock = {now: 1_700_000_000_000}
    const host = makeHost(ids.list[0], clock)
    const {link, code} = host.makeQr('192.168.1.50', 47215)
    const token = parsePairQr(link)!.token
    // the token's own bits are not a code, and the code's number is not a token
    const asNumber = ((token[0] << 12) | (token[1] << 4) | (token[2] >> 4)) & 0xfffff
    if (asNumber !== tokenOf(code)) assert.equal(errorOf(() => ask(host, ids.list[1], '192.168.1.5', null, asNumber)), 'token')
    const padded = new Uint8Array(16)
    padded[0] = tokenOf(code) >> 12
    padded[1] = (tokenOf(code) >> 4) & 255
    padded[2] = (tokenOf(code) & 15) << 4
    assert.equal(errorOf(() => ask(host, ids.list[1], '192.168.1.5', padded)), 'token', 'the code is not a token')
    // using the token leaves the code, and the other way round
    assert.ok(ask(host, ids.list[2], '192.168.1.6', token).id)
    host.decide(host.view()!.id, false)
    assert.equal(host.codeShowing, true)
    assert.ok(ask(host, ids.list[3], '192.168.1.7', null, tokenOf(code)).id)
  } finally {
    await ids.cleanup()
  }
})

test('a wrong code costs an attempt like a wrong token, and five wrong guesses withdraw the code', async () => {
  const ids = await identities(8)
  try {
    const clock = {now: 1_700_000_000_000}
    const limited = makeHost(ids.list[0], clock, 2)
    const a = limited.makeQr('192.168.1.50', 47215)
    const wrong = (tokenOf(a.code) + 1) & 0xfffff
    assert.equal(errorOf(() => ask(limited, ids.list[1], '192.168.1.5', null, wrong)), 'token')
    assert.equal(errorOf(() => ask(limited, ids.list[1], '192.168.1.5', null, wrong)), 'token')
    assert.equal(errorOf(() => ask(limited, ids.list[1], '192.168.1.5', null, tokenOf(a.code))), 'rate', 'the third try from the same key is refused, even with the right code')
    // five wrong guesses from different keys retire the code (the QR stays)
    const host = makeHost(ids.list[0], clock)
    const b = host.makeQr('192.168.1.50', 47215)
    const token = parsePairQr(b.link)!.token
    for (let i = 1; i <= 5; i++) assert.equal(errorOf(() => ask(host, ids.list[i], `192.168.1.${i}`, null, (tokenOf(b.code) + i) & 0xfffff)), 'token')
    assert.equal(host.codeShowing, false)
    assert.equal(errorOf(() => ask(host, ids.list[6], '192.168.1.9', null, tokenOf(b.code))), 'token')
    assert.ok(ask(host, ids.list[7], '192.168.1.10', token).id, 'the QR still works')
  } finally {
    await ids.cleanup()
  }
})

test('the start request reads a code only as a whole number below 2^20, and never together with a token', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B', {enable: false, withService: true})
    await a.service.showQr()
    const body = (extra: Record<string, unknown>) => ({versions: [1], name: 'B', platform: 'windows', libraries: [], ...extra})
    for (const bad of [-1, 1048576, 1.5, '12', null, [1], {}]) {
      const client = new PeerClient({identity: b.identity, host: '127.0.0.1', port: a.port, pin: null, connectTimeoutMs: 3000})
      await assert.rejects(client.json('POST', '/v1/pair/start', {json: body({code: bad})}), (e: {status: number; code: string}) => e.code === 'token', JSON.stringify(bad))
      client.close()
    }
    const client = new PeerClient({identity: b.identity, host: '127.0.0.1', port: a.port, pin: null, connectTimeoutMs: 3000})
    await assert.rejects(client.json('POST', '/v1/pair/start', {json: body({code: 5, token: Buffer.alloc(16).toString('base64')})}), (e: {code: string}) => e.code === 'token')
    client.close()
  } finally {
    await w.cleanup()
  }
})

test('the announcement says a code is showing while it is, and never what it is', async () => {
  const w = await World.create()
  let now = 1_700_000_000_000
  try {
    const a = await w.node('A', {now: () => now})
    const b = await w.node('B', {enable: false, withService: true})
    const announce = async () => {
      const client = new PeerClient({identity: b.identity, host: '127.0.0.1', port: a.port, pin: null, connectTimeoutMs: 3000})
      try {
        return await client.json<Record<string, unknown>>('GET', '/v1/announce')
      } finally {
        client.close()
      }
    }
    assert.deepEqual(Object.keys(await announce()).sort(), ['id', 'n', 'p', 'v'], 'no code, no flag')
    await a.service.showQr()
    const shown = await announce()
    assert.deepEqual(Object.keys(shown).sort(), ['c', 'id', 'n', 'p', 'v'])
    assert.equal(shown.c, 1)
    const code = a.service.state().qr!.code!
    assert.equal(JSON.stringify(shown).includes(code.replace('-', '').slice(0, 4)), false, 'not even a part of the code')
    now += PAIR_SESSION_MS + 1
    assert.deepEqual(Object.keys(await announce()).sort(), ['id', 'n', 'p', 'v'], 'expired: the flag is gone')
    await a.service.showQr()
    a.service.closePairing()
    assert.deepEqual(Object.keys(await announce()).sort(), ['id', 'n', 'p', 'v'], 'closed: the flag is gone')
  } finally {
    await w.cleanup()
  }
})

// ---- the asking side ------------------------------------------------------------------------------------------------------

const freePort = () =>
  new Promise<number>((resolve, reject) => {
    const s = net.createServer()
    s.once('error', reject)
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as net.AddressInfo).port
      s.close(() => resolve(port))
    })
  })

const iface = (address: string) => ({name: 'Ethernet', address, netmask: '255.255.255.0'})

test('a typed code finds the device that shows it by its key, skips the other, and pairs like a scanned code', async () => {
  const w = await World.create()
  try {
    const port = await freePort()
    const b = await w.node('B', {bind: '127.0.0.7', port})
    const c = await w.node('C', {bind: '127.0.0.8', port})
    const a = await w.node('A', {scan: {interfaces: () => [iface('127.0.0.1')], port, everyMs: 60_000}, tailscale: {}})
    // both show a code; only B's key starts with the typed pin bits
    await b.service.showQr()
    await c.service.showQr()
    let cStarts = 0
    const realStart = c.service.pairing.start.bind(c.service.pairing)
    c.service.pairing.start = ((...args: Parameters<typeof realStart>) => (cStarts++, realStart(...args))) as typeof realStart
    const typed = b.service.state().qr!.code!.toLowerCase().replace('-', ' ')
    await a.service.ask(typed)
    assert.equal(a.service.state().pairing?.stage, 'done', String(a.service.state().pairing?.error))
    assert.equal(cStarts, 0, 'the device with another key got no /v1/pair/start')
    assert.equal(c.service.state().pairing, null)
    await until(() => b.service.state().pairing?.stage === 'done', 'B to see it done')
    assert.equal(b.service.state().pairing!.autoApproved, true)
    assert.ok(a.service.peers.get(b.identity.deviceId) && b.service.peers.get(a.identity.deviceId))
    assert.equal(a.service.peers.get(c.identity.deviceId), undefined)
    assert.equal(b.service.state().qr, undefined, 'the used QR is gone')
  } finally {
    await w.cleanup()
  }
})

test('a code no device is showing, or one whose key nobody has, says so and sends nothing', async () => {
  const w = await World.create()
  try {
    const port = await freePort()
    const b = await w.node('B', {bind: '127.0.0.7', port})
    const a = await w.node('A', {scan: {interfaces: () => [iface('127.0.0.1')], port, everyMs: 60_000}, tailscale: {}})
    let starts = 0
    const realStart = b.service.pairing.start.bind(b.service.pairing)
    b.service.pairing.start = ((...args: Parameters<typeof realStart>) => (starts++, realStart(...args))) as typeof realStart
    const none = 'No device showing this code was found. Check the code, and that both devices are on the same network or Tailscale.'
    // B's key is right, but B shows no code
    await a.service.ask(pairCodeOf(77, pairPin(b.identity.spki)))
    assert.equal(a.service.state().pairing?.error, none)
    a.service.dismissPairing()
    // B shows a code, but the typed one carries another key
    await b.service.showQr()
    await a.service.ask(pairCodeOf(tokenOf(b.service.state().qr!.code!), pairPin(a.identity.spki)))
    assert.equal(a.service.state().pairing?.error, none)
    assert.equal(starts, 0)
    assert.equal(b.service.state().pairing, null)
  } finally {
    await w.cleanup()
  }
})

test('a wrong code is one try on that device, then "not right"; nothing is retried', async () => {
  const w = await World.create()
  try {
    const port = await freePort()
    const b = await w.node('B', {bind: '127.0.0.7', port})
    const a = await w.node('A', {scan: {interfaces: () => [iface('127.0.0.1')], port, everyMs: 60_000}, tailscale: {}})
    await b.service.showQr()
    let starts = 0
    const realStart = b.service.pairing.start.bind(b.service.pairing)
    b.service.pairing.start = ((...args: Parameters<typeof realStart>) => (starts++, realStart(...args))) as typeof realStart
    const shown = b.service.state().qr!.code!
    await a.service.ask(pairCodeOf((tokenOf(shown) + 1) & 0xfffff, pairPin(b.identity.spki)))
    assert.equal(a.service.state().pairing?.stage, 'failed')
    assert.equal(a.service.state().pairing?.error, 'That code is not right or has expired.')
    assert.equal(starts, 1)
    assert.equal(a.service.peers.get(b.identity.deviceId), undefined)
    // the right code still works afterwards (one wrong guess does not withdraw it)
    a.service.dismissPairing()
    await a.service.ask(shown)
    assert.equal(a.service.state().pairing?.stage, 'done', String(a.service.state().pairing?.error))
  } finally {
    await w.cleanup()
  }
})

test('the client refuses a key that does not start with the pin bits before it writes any request', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B', {enable: false, withService: true})
    let requests = 0
    const realStart = a.service.pairing.start.bind(a.service.pairing)
    a.service.pairing.start = ((...args: Parameters<typeof realStart>) => (requests++, realStart(...args))) as typeof realStart
    const bad = new PeerClient({identity: b.identity, host: '127.0.0.1', port: a.port, pin: null, pinBits: (parsePairCode(pairCodeOf(1, pairPin(a.identity.spki)))!.pinBits + 1) & 0x3fffffff, connectTimeoutMs: 3000})
    await assert.rejects(bad.json('GET', '/v1/announce'), /different/)
    bad.close()
    const good = new PeerClient({identity: b.identity, host: '127.0.0.1', port: a.port, pin: null, pinBits: parsePairCode(pairCodeOf(1, pairPin(a.identity.spki)))!.pinBits, connectTimeoutMs: 3000})
    assert.equal((await good.json<{v: number}>('GET', '/v1/announce')).v, 1)
    good.close()
    assert.equal(requests, 0)
  } finally {
    await w.cleanup()
  }
})