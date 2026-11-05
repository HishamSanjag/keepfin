// Away from home (spec 7, desktop side): a device added by address is remembered, an unreachable device is retried with exponential
// back-off (never longer than 30 minutes) and at once on a network change, and the Devices page says when it was last seen and
// since when it has been unreachable.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {NetService} from './service.js'
import {backoffDelay} from './link.js'
import {World, plainBox, until} from './testkit.js'

test('the back-off doubles from two seconds, is jittered by a quarter and never passes thirty minutes', () => {
  const none = () => 0.5 // no jitter
  assert.deepEqual([0, 1, 2, 3, 4, 5].map((n) => backoffDelay(n, 2000, 30 * 60_000, none)), [2000, 4000, 8000, 16000, 32000, 64000])
  assert.equal(backoffDelay(9, 2000, 30 * 60_000, none), 1_024_000, 'about seventeen minutes after nine failures')
  assert.equal(backoffDelay(10, 2000, 30 * 60_000, none), 30 * 60_000, 'the tenth doubling would pass thirty minutes: it is held there')
  // the cap holds for any number of failures and any jitter
  for (const n of [11, 12, 20, 40, 1000]) for (const r of [0, 0.5, 0.999]) assert.ok(backoffDelay(n, 2000, 30 * 60_000, () => r) <= 30 * 60_000, `failure ${n} jitter ${r}`)
  // the jitter is plus or minus a quarter
  assert.equal(backoffDelay(3, 2000, 30 * 60_000, () => 0), 16000 * 0.75)
  assert.equal(backoffDelay(3, 2000, 30 * 60_000, () => 1), 16000 * 1.25)
  // from the first failure the wait grows, and an immediate retry (kick) restarts from the beginning
  const schedule = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11].map((n) => backoffDelay(n, 2000, 30 * 60_000, none))
  for (let i = 1; i < schedule.length; i++) assert.ok(schedule[i] >= schedule[i - 1])
  assert.equal(schedule[schedule.length - 1], 30 * 60_000, 'it settles at thirty minutes')
})

test('an unreachable device is shown as such, retried with growing waits, and found again when it is back', async () => {
  const w = await World.create()
  try {
    const port = 41000 + Math.floor(Math.random() * 2000)
    const host = await w.node('Host', {port})
    const guest = await w.node('Guest', {backoff: {minMs: 30, maxMs: 240}})
    await w.pair(guest, host)
    await until(() => guest.service.state().paired[0].online, 'the first connection')
    assert.equal(guest.service.state().paired[0].unreachableSince, null)
    const seen = guest.service.state().paired[0].lastSeen
    assert.ok(seen !== null, 'last seen is known')

    await host.service.disable() // the other device goes away
    await until(() => !guest.service.state().paired[0].online && guest.service.state().paired[0].unreachableSince !== null, 'the page to say it is unreachable')
    const since = guest.service.state().paired[0].unreachableSince!
    const peer = guest.service.peers.get(host.identity.deviceId)!
    assert.equal(peer.unreachableSince, since)
    assert.ok(peer.lastSeen !== null && peer.lastSeen >= seen!, 'last seen is kept')

    // retries keep coming, each wait longer than the one before until it reaches the cap
    const link = guest.service.links.get(host.identity.deviceId)!
    const waits: number[] = []
    for (let i = 0; i < 80 && waits.length < 5; i++) {
      await new Promise((r) => setTimeout(r, 20))
      if (link.nextDelayMs && waits.at(-1) !== link.nextDelayMs) waits.push(link.nextDelayMs)
    }
    assert.ok(waits.length >= 3, `several attempts were made (${waits.join(', ')})`)
    assert.ok(waits.every((ms) => ms <= 240), 'the cap holds')
    assert.ok(Math.max(...waits) > Math.min(...waits), 'the waits grow')
    assert.equal(guest.service.state().paired[0].unreachableSince, since, 'since when does not move while it stays away')

    // the other device returns at the same address: found again, and "unreachable" is cleared
    await host.service.enable()
    guest.service.kickAll() // what a network change does
    await until(() => guest.service.state().paired[0].online, 'the reconnection')
    await until(() => guest.service.state().paired[0].unreachableSince === null, 'unreachable to be cleared')
  } finally {
    await w.cleanup()
  }
})

test('a device added by address is remembered, with the name as typed, and found again after a restart without discovery', async () => {
  const w = await World.create()
  try {
    const host = await w.node('Host')
    const guest = await w.node('Guest')
    await w.pair(guest, host) // pairing by address: 127.0.0.1:<port>
    const peer = guest.service.peers.get(host.identity.deviceId)!
    assert.equal(peer.lastHost, '127.0.0.1')
    assert.equal(peer.lastPort, host.port)
    assert.equal(guest.service.state().paired[0].address, `127.0.0.1:${host.port}`)
    const saved = JSON.parse(await fs.readFile(path.join(guest.deviceDir, 'net', 'peers.json'), 'utf8'))
    assert.equal(saved.peers[0].lastHost, '127.0.0.1')
    await guest.service.close()
    const again = await NetService.create({deviceDir: guest.deviceDir, box: plainBox, libraries: () => [guest.lib], push: () => undefined, port: 0, bind: '127.0.0.1', discovery: false, backoff: {minMs: 30, maxMs: 200}})
    try {
      await until(() => again.state().paired[0]?.online === true, 'the saved address to be used')
    } finally {
      await again.close()
    }
  } finally {
    await w.cleanup()
  }
})
