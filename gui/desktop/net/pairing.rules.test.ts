// Pairing rules for requests nobody invited (dev-docs/plans/DEVICES-REDESIGN.md, Phase 1): unrequested comparisons from this network or
// Tailscale, scanned codes that approve themselves, the global limit and the mute. The clock is injected; nothing sleeps.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {parsePairQr} from '../../shared/core/net.js'
import {loadIdentity, type DeviceIdentity} from './identity.js'
import {DEFAULT_LIMITS} from './limits.js'
import {isLocalOrTailscale} from './pairSource.js'
import {PAIR_SESSION_MS, PairError, PairingHost, peerInfo, UNREQUESTED_SESSION_MS} from './pairing.js'
import {World, plainBox, until} from './testkit.js'

async function identities(count: number): Promise<{list: DeviceIdentity[]; cleanup: () => Promise<void>}> {
  const dirs: string[] = []
  const list: DeviceIdentity[] = []
  for (let i = 0; i < count; i++) {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-rules-'))
    dirs.push(dir)
    list.push(await loadIdentity(dir, plainBox))
  }
  return {list, cleanup: async () => void (await Promise.all(dirs.map((d) => fs.rm(d, {recursive: true, force: true}))))}
}

function makeHost(self: DeviceIdentity, clock: {now: number}, extra: Partial<ConstructorParameters<typeof PairingHost>[0]> = {}) {
  return new PairingHost({identity: () => self, limits: {...DEFAULT_LIMITS, pairAttempts: 1000}, now: () => clock.now, libraries: () => [], onPaired: async () => undefined, changed: () => undefined, ...extra})
}

const ask = (host: PairingHost, who: DeviceIdentity, address: string, token: Uint8Array | null = null) => host.start(peerInfo(who.spki, {name: 'Laptop'}), address, token)
const code = (fn: () => unknown) => {
  try {
    fn()
  } catch (error) {
    return error instanceof PairError ? error.code : String(error)
  }
  return 'ok'
}

test('addresses: private, link-local and Tailscale are local; loopback and public are not', () => {
  for (const ok of ['10.0.0.5', '172.16.0.1', '172.31.255.255', '192.168.1.20', '169.254.9.9', '100.64.0.1', '100.127.255.255', 'fe80::1', 'fe80::1%eth0', 'fc00::1', 'fd12::1', 'fd7a:115c:a1e0::1', '::ffff:192.168.1.20', '::ffff:100.101.102.103', '::ffff:c0a8:0114']) {
    assert.equal(isLocalOrTailscale(ok), true, ok)
  }
  for (const bad of ['8.8.8.8', '127.0.0.1', '::1', '172.32.0.1', '100.128.0.1', '192.169.1.1', '2001:db8::1', '::ffff:8.8.8.8', '::ffff:127.0.0.1', 'fec0::1', 'garbage', '', '300.1.1.1']) {
    assert.equal(isLocalOrTailscale(bad), false, bad)
  }
})

test('an unrequested start with the window closed is accepted from this network and Tailscale, refused from elsewhere', async () => {
  const ids = await identities(6)
  try {
    const clock = {now: 1_700_000_000_000}
    for (const address of ['192.168.1.20', '100.101.102.103', 'fd7a:115c:a1e0::1']) {
      const host = makeHost(ids.list[0], clock)
      const started = ask(host, ids.list[1], address)
      assert.ok(started.id)
      assert.equal(host.view()?.unrequested, true, address)
      assert.equal(host.view()?.stage, 'comparing')
      host.close()
    }
    const host = makeHost(ids.list[0], clock)
    assert.equal(code(() => ask(host, ids.list[2], '8.8.8.8')), 'not-pairing')
    assert.equal(code(() => ask(host, ids.list[3], '127.0.0.1')), 'not-pairing')
    assert.equal(host.view(), null, 'a refusal opens nothing')
  } finally {
    await ids.cleanup()
  }
})

test('an unrequested session lasts 60 seconds; a window session still lasts two minutes', async () => {
  const ids = await identities(3)
  try {
    const clock = {now: 1_700_000_000_000}
    const host = makeHost(ids.list[0], clock)
    const started = ask(host, ids.list[1], '192.168.1.20')
    assert.equal(started.expiresAt, clock.now + 60_000)
    assert.equal(UNREQUESTED_SESSION_MS, 60_000)
    clock.now += 59_000
    assert.equal(host.view()?.stage, 'comparing')
    clock.now += 2_000
    assert.equal(host.view()?.stage, 'failed', 'expired at 60 s')
    // the window case keeps two minutes
    host.open()
    const windowed = ask(host, ids.list[2], '8.8.8.8')
    assert.equal(windowed.expiresAt, clock.now + PAIR_SESSION_MS)
    assert.equal(host.view()?.unrequested, false)
    clock.now += 100_000
    assert.equal(host.view()?.stage, 'comparing')
  } finally {
    await ids.cleanup()
  }
})

test('the 11th unrequested start in 10 minutes is refused with rate, whoever sends it', async () => {
  const ids = await identities(13)
  try {
    const clock = {now: 1_700_000_000_000}
    // the mute is lifted out of the way so only the global limit is under test
    const host = makeHost(ids.list[0], clock, {unrequested: {muteAfter: 1000}})
    for (let i = 1; i <= 10; i++) host.decide(ask(host, ids.list[i], `192.168.1.${i}`).id, false)
    clock.now += 1_000
    assert.equal(code(() => ask(host, ids.list[11], '192.168.1.99')), 'rate')
    clock.now = 1_700_000_000_000 + 10 * 60_000 + 1_000
    assert.equal(code(() => ask(host, ids.list[12], '192.168.1.98')), 'ok', 'the oldest start left the window')
  } finally {
    await ids.cleanup()
  }
})

test('three declined or expired unrequested sessions mute unrequested requests for five minutes', async () => {
  const ids = await identities(8)
  try {
    const clock = {now: 1_700_000_000_000}
    const host = makeHost(ids.list[0], clock)
    const first = ask(host, ids.list[1], '192.168.1.1')
    host.decide(first.id, false)
    const second = ask(host, ids.list[2], '192.168.1.2')
    clock.now += 61_000 // expired
    assert.equal(host.view()?.stage, 'failed')
    void second
    const third = ask(host, ids.list[3], '192.168.1.3')
    host.decide(third.id, false)
    assert.equal(code(() => ask(host, ids.list[4], '192.168.1.4')), 'not-pairing', 'muted')
    // window and code starts are never muted
    host.open()
    assert.equal(code(() => ask(host, ids.list[5], '192.168.1.5')), 'ok', 'the window works while muted')
    host.close()
    clock.now += 4 * 60_000
    assert.equal(code(() => ask(host, ids.list[6], '192.168.1.6')), 'not-pairing', 'still muted after 4 minutes')
    clock.now += 61_000
    assert.equal(code(() => ask(host, ids.list[7], '192.168.1.7')), 'ok', 'lifted after 5 minutes')
  } finally {
    await ids.cleanup()
  }
})

test('declines of window sessions do not mute, and a mute needs three inside ten minutes', async () => {
  const ids = await identities(8)
  try {
    const clock = {now: 1_700_000_000_000}
    const host = makeHost(ids.list[0], clock)
    host.open()
    for (let i = 1; i <= 4; i++) host.decide(ask(host, ids.list[i], '192.168.1.1').id, false)
    host.close()
    assert.equal(code(() => ask(host, ids.list[5], '192.168.1.5')), 'ok', 'window declines never count')
    host.close()
    // two misses, then a long gap: the first leaves the ten-minute window
    host.decide(ask(host, ids.list[6], '192.168.1.6').id, false)
    clock.now += 11 * 60_000
    host.decide(ask(host, ids.list[7], '192.168.1.7').id, false)
    host.decide(ask(host, ids.list[1], '192.168.1.8').id, false)
    assert.equal(code(() => ask(host, ids.list[2], '192.168.1.9')), 'ok', 'two inside ten minutes do not mute')
  } finally {
    await ids.cleanup()
  }
})

test('a scanned code is accepted with the window closed and pairs without anyone deciding', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    await a.service.showQr()
    assert.equal(a.service.pairing.active, false, 'showing a code no longer opens the window')
    assert.equal(a.service.state().pairingUntil, null)
    const link = a.service.state().qr!.link
    await b.service.ask(link) // nobody calls decide() on A
    assert.equal(b.service.state().pairing?.stage, 'done')
    await until(() => a.service.state().pairing?.stage === 'done', 'the code owner to see it done')
    const view = a.service.state().pairing!
    assert.equal(view.autoApproved, true)
    assert.equal(view.method, 'qr')
    assert.ok(a.service.peers.get(b.identity.deviceId) && b.service.peers.get(a.identity.deviceId))
    // Undo forgets the device again
    await a.service.undoPair(b.identity.deviceId)
    assert.equal(a.service.peers.get(b.identity.deviceId), undefined)
  } finally {
    await w.cleanup()
  }
})

test('a used or expired code is still refused with the window closed', async () => {
  const w = await World.create()
  let now = 1_700_000_000_000
  try {
    const a = await w.node('A', {now: () => now})
    const b = await w.node('B')
    const c = await w.node('C', {waitForPeer: {intervalMs: 20, deadlineMs: 100}})
    await a.service.showQr()
    const link = a.service.state().qr!.link
    await b.service.ask(link)
    assert.equal(b.service.state().pairing?.stage, 'done')
    await c.service.ask(link)
    assert.match(c.service.state().pairing?.error ?? '', /already used|expired/)
    assert.equal(a.service.peers.get(c.identity.deviceId), undefined)
    await a.service.showQr()
    const second = a.service.state().qr!.link
    now += 121_000
    await c.service.ask(second)
    assert.match(c.service.state().pairing?.error ?? '', /already used|expired|was not opened/)
    assert.equal(a.service.peers.get(c.identity.deviceId), undefined)
  } finally {
    await w.cleanup()
  }
})

test('a code start is never muted and does not count toward the global limit', async () => {
  const ids = await identities(8)
  try {
    const clock = {now: 1_700_000_000_000}
    const host = makeHost(ids.list[0], clock, {unrequested: {globalMax: 1, muteAfter: 1}})
    host.decide(ask(host, ids.list[1], '192.168.1.1').id, false) // one miss: muted at once
    assert.equal(code(() => ask(host, ids.list[2], '192.168.1.2')), 'not-pairing')
    const {link} = host.makeQr('192.168.1.50', 47215)
    const started = ask(host, ids.list[3], '8.8.8.8', parsePairQr(link)!.token)
    assert.ok(started.id)
    assert.equal(host.view()?.unrequested, false)
    assert.equal(host.view()?.stage, 'approving')
  } finally {
    await ids.cleanup()
  }
})
