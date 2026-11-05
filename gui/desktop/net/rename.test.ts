// A device renamed after pairing: the paired device shows the new name at once while connected, and keeps it after a restart.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {World, until} from './testkit.js'

test('a rename reaches the paired device and is kept', async () => {
  const w = await World.create()
  try {
    const host = await w.node('Host')
    const guest = await w.node('Guest')
    await w.pair(guest, host)
    await until(() => guest.service.state().paired[0]?.online === true, 'the link')
    assert.notEqual(guest.service.state().paired[0].name, 'Living room PC')

    await host.service.rename('Living room PC')
    await until(() => guest.service.state().paired[0]?.name === 'Living room PC', 'the new name on the other device')
    // a blank name in a hello keeps the one that was there
    guest.service.peers.rememberName(host.identity.deviceId, '   ')
    assert.equal(guest.service.state().paired[0].name, 'Living room PC')
  } finally {
    await w.cleanup()
  }
})
