// Pairing when the other device is not in "Add a device" yet: the asking side waits and asks again instead of failing.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {World, until} from './testkit.js'
import {AskError, askToPair} from './initiator.js'

const FAST = {intervalMs: 25, deadlineMs: 4_000}

test('the other device is not in Add a device yet: pairing waits, then connects as soon as it opens', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {waitForPeer: FAST})
    const b = await w.node('B')
    // B is NOT open to new devices
    const done = a.service.ask(b.address)
    await until(() => a.service.state().pairing?.stage === 'waitingForPeer', 'the waiting stage')
    assert.equal(a.service.state().pairing?.role, 'asking')
    assert.equal(b.service.state().pairing, null)
    b.service.openPairing() // the person opens Add a device on the other device
    await until(() => a.service.state().pairing?.sas !== undefined && b.service.state().pairing?.sas !== undefined, 'both screens to show the numbers')
    const x = a.service.state().pairing!
    const y = b.service.state().pairing!
    assert.equal(x.sas, y.sas)
    a.service.decide(x.id, true)
    b.service.decide(y.id, true)
    await done
    assert.equal(a.service.state().pairing?.stage, 'done')
    assert.ok(a.service.peers.get(b.identity.deviceId))
  } finally {
    await w.cleanup()
  }
})

test('cancelling while waiting ends the retries cleanly and a new pairing can start', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {waitForPeer: FAST})
    const b = await w.node('B')
    const done = a.service.ask(b.address)
    await until(() => a.service.state().pairing?.stage === 'waitingForPeer', 'the waiting stage')
    a.service.dismissPairing() // Cancel
    assert.equal(a.service.state().pairing, null)
    await done // settles instead of looping
    // opening the other side later does not pair anything: nobody is asking any more
    b.service.openPairing()
    await new Promise((r) => setTimeout(r, 150))
    assert.equal(a.service.peers.list().length, 0)
    assert.equal(b.service.state().pairing, null)
    // and asking again works
    await w.pair(a, b)
    assert.ok(a.service.peers.get(b.identity.deviceId))
  } finally {
    await w.cleanup()
  }
})

test('the wait gives up after its deadline with a plain message naming the device', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {waitForPeer: {intervalMs: 20, deadlineMs: 120}})
    const b = await w.node('B')
    await a.service.ask(b.address)
    const view = a.service.state().pairing!
    assert.equal(view.stage, 'failed')
    assert.match(view.error ?? '', /Add a device was not opened on the other device in time\. Try again\./)
  } finally {
    await w.cleanup()
  }
})

test('askToPair with an injected clock: retries on not-pairing, then gives up at the deadline without real waiting', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {enable: false})
    const b = await w.node('B')
    let clock = 1_000_000
    const sleeps: number[] = []
    let waiting = 0
    const options = {
      identity: a.identity,
      host: '127.0.0.1',
      port: b.port,
      pin: null,
      token: null,
      libraries: [],
      decide: async () => false,
    }
    await assert.rejects(
      askToPair({
        ...options,
        waitForPeer: {
          peerLabel: 'Pixel 9',
          waiting: () => waiting++,
          intervalMs: 2_000,
          deadlineMs: 24_000,
          now: () => clock,
          sleep: async (ms) => {
            sleeps.push(ms)
            clock += ms // the deadline passes instantly
          },
        },
      }),
      (e: AskError) => e.code === 'not-opened' && e.message === 'Add a device was not opened on Pixel 9 in time. Try again.',
    )
    assert.equal(waiting, 1, 'the page is told once')
    assert.ok(sleeps.length >= 10 && sleeps.length <= 12, `one retry per interval until the deadline (got ${sleeps.length})`)
    // without waiting, the answer is still the old failure
    await assert.rejects(askToPair(options), (e: AskError) => e.code === 'not-pairing')
  } finally {
    await w.cleanup()
  }
})

test('askToPair stops at once when the signal aborts during the pause', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {enable: false})
    const b = await w.node('B')
    const controller = new AbortController()
    const run = askToPair({
      identity: a.identity,
      host: '127.0.0.1',
      port: b.port,
      pin: null,
      token: null,
      libraries: [],
      decide: async () => false,
      waitForPeer: {peerLabel: 'x', waiting: () => setTimeout(() => controller.abort(), 20), signal: controller.signal, intervalMs: 60_000, deadlineMs: 600_000},
    })
    await assert.rejects(run, (e: AskError) => e.code === 'cancelled')
  } finally {
    await w.cleanup()
  }
})
test('a pairing that is refused for another reason still fails at once', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {waitForPeer: FAST})
    await a.service.ask('127.0.0.1:1') // nothing listens there
    const view = a.service.state().pairing!
    assert.equal(view.stage, 'failed')
    assert.match(view.error ?? '', /Nothing is listening|did not answer/)
  } finally {
    await w.cleanup()
  }
})

test('both devices press Pair: the one that was waiting follows the pairing the other started, and the numbers match', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {waitForPeer: FAST})
    const b = await w.node('B', {waitForPeer: FAST})
    // A presses Pair: it opens its side, asks B, and waits (B is not open yet)
    a.service.openPairing()
    const aAsks = a.service.ask(b.address)
    await until(() => a.service.state().pairing?.stage === 'waitingForPeer', 'A to wait')
    // B presses Pair: it opens its side and asks A, which is open
    b.service.openPairing()
    const bAsks = b.service.ask(a.address).catch(() => undefined) // refused when A already reached B first: then B simply answers
    await until(() => a.service.state().pairing?.sas !== undefined && b.service.state().pairing?.sas !== undefined, 'both screens to show the numbers')
    assert.notEqual(a.service.state().pairing?.role, b.service.state().pairing?.role, 'one side asks and the other answers, never both ask')
    assert.equal(a.service.state().pairing?.sas, b.service.state().pairing?.sas)
    a.service.decide(a.service.state().pairing!.id, true)
    b.service.decide(b.service.state().pairing!.id, true)
    await bAsks
    await aAsks.catch(() => undefined)
    await until(() => !!a.service.peers.get(b.identity.deviceId) && !!b.service.peers.get(a.identity.deviceId), 'both to remember each other')
  } finally {
    await w.cleanup()
  }
})

test('a closed window costs no pairing attempts: many tries while closed, then open, still starts (not "rate")', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {enable: false})
    const b = await w.node('B')
    const base = {identity: a.identity, host: '127.0.0.1', port: b.port, pin: null, token: null, libraries: [], decide: async () => false}
    for (let i = 0; i < 12; i++) await assert.rejects(askToPair(base), (e: AskError) => e.code === 'not-pairing')
    b.service.openPairing()
    // the window is open now: the first real try is not refused as too many
    await assert.rejects(askToPair(base), (e: AskError) => e.code === 'declined')
    assert.equal(b.service.state().pairing?.role, 'answering')
  } finally {
    await w.cleanup()
  }
})
test('OK on the device that was asked closes the result there too', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {waitForPeer: FAST})
    const b = await w.node('B')
    b.service.openPairing()
    const done = a.service.ask(b.address)
    await until(() => a.service.state().pairing?.sas !== undefined && b.service.state().pairing?.sas !== undefined, 'both screens to show the numbers')
    a.service.decide(a.service.state().pairing!.id, true)
    b.service.decide(b.service.state().pairing!.id, true)
    await done
    await until(() => b.service.state().pairing?.stage === 'done', 'B to show the result')
    assert.equal(b.service.state().pairing?.role, 'answering')
    b.service.dismissPairing() // OK
    assert.equal(b.service.state().pairing, null)
    a.service.dismissPairing()
    assert.equal(a.service.state().pairing, null)
  } finally {
    await w.cleanup()
  }
})
