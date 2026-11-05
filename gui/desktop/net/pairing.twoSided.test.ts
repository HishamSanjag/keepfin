// Pairing confirms on both devices. Whichever person taps Codes match first sees "waiting for the other device" (the code stays on
// screen, only Cancel is left); the pairing completes when the second person taps too, and ends for both when one declines.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {World, until} from './testkit.js'

async function start(w: World) {
  const a = await w.node('A')
  const b = await w.node('B')
  a.service.openPairing()
  const done = b.service.ask(a.address)
  await until(() => b.service.state().pairing?.sas !== undefined && a.service.state().pairing?.sas !== undefined, 'both screens to show the code')
  return {a, b, done}
}

test('nothing is paired until both people tapped; the first tap waits (asking side first)', async () => {
  const w = await World.create()
  try {
    const {a, b, done} = await start(w)
    const asking = b.service.state().pairing!
    assert.equal(asking.stage, 'comparing')
    assert.equal(asking.sas, a.service.state().pairing!.sas, 'the same code on both screens')
    b.service.decide(asking.id, true)
    assert.equal(b.service.state().pairing?.stage, 'waiting', 'the first tap waits for the other device')
    assert.equal(b.service.state().pairing?.sas, asking.sas, 'the code stays on screen')
    assert.equal(a.service.state().pairing?.stage, 'comparing')
    assert.equal(a.service.state().pairing?.confirmed, undefined)
    assert.equal(a.service.peers.list().length + b.service.peers.list().length, 0)
    a.service.decide(a.service.state().pairing!.id, true)
    await done
    await until(() => !!a.service.peers.get(b.identity.deviceId) && !!b.service.peers.get(a.identity.deviceId), 'both to remember each other')
  } finally {
    await w.cleanup()
  }
})

test('the asked device tapping first shows it is waiting for the asking device', async () => {
  const w = await World.create()
  try {
    const {a, b, done} = await start(w)
    a.service.decide(a.service.state().pairing!.id, true)
    assert.equal(a.service.state().pairing?.stage, 'comparing')
    assert.equal(a.service.state().pairing?.confirmed, true, 'this device already confirmed: the dialog waits')
    assert.equal(b.service.state().pairing?.stage, 'comparing')
    assert.equal(a.service.peers.list().length + b.service.peers.list().length, 0)
    b.service.decide(b.service.state().pairing!.id, true)
    await done
    await until(() => !!a.service.peers.get(b.identity.deviceId) && !!b.service.peers.get(a.identity.deviceId), 'both to remember each other')
  } finally {
    await w.cleanup()
  }
})

test('when the other side declines after the first tap, this side says "<name> did not confirm" and pairs nothing', async () => {
  const w = await World.create()
  try {
    const {a, b, done} = await start(w)
    b.service.decide(b.service.state().pairing!.id, true)
    a.service.decide(a.service.state().pairing!.id, false)
    await done
    const view = b.service.state().pairing!
    assert.equal(view.stage, 'failed')
    assert.match(view.error ?? '', /did not confirm, so nothing was paired\./)
    assert.equal(a.service.peers.list().length + b.service.peers.list().length, 0)
  } finally {
    await w.cleanup()
  }
})

test('Cancel after the first tap declines to the other device', async () => {
  const w = await World.create()
  try {
    const {a, b} = await start(w)
    const id = b.service.state().pairing!.id
    b.service.decide(id, true)
    b.service.decide(id, false) // Cancel while waiting
    assert.equal(b.service.state().pairing, null, 'the dialog closes')
    await until(() => a.service.state().pairing?.stage === 'failed', 'the other device to learn it')
    assert.match(a.service.state().pairing!.error ?? '', /did not confirm, so nothing was paired\./)
    assert.equal(a.service.peers.list().length + b.service.peers.list().length, 0)
  } finally {
    await w.cleanup()
  }
})
