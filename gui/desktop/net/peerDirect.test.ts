// A copy on a paired device runs directly over the connection: the runner plans from the device's streamed inventory, the device's own
// executor makes every change under its own lock (conditional on content), and this computer keeps no media of that copy.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {makeWorld, walk} from '../copies/testkit.js'
import {PeerClient} from './client.js'
import {PeerRemote} from './peerPlace.js'
import {PeerTransport} from './peerTransport.js'
import {World, until} from './testkit.js'

const idOf = (t: string) => 'sha256:' + createHash('sha256').update(t).digest('hex')

test('a copy on a device: upload, replace (old bytes kept by the device), rename, restore, and nothing of the media stays here', async () => {
  const net = await World.create()
  const cw = await makeWorld('kf-peer-direct-')
  try {
    const host = await net.node('Host')
    const guest = await net.node('Guest')
    await net.pair(guest, host)
    await host.service.setAccess(guest.identity.deviceId, net.libraryId, ['metadata.read', 'metadata.write', 'inventory.read', 'media.read', 'copy.mutate'])
    const client = new PeerClient({identity: guest.identity, host: '127.0.0.1', port: host.port, pin: host.identity.deviceId})
    const transport = new PeerTransport({client, libraryId: net.libraryId, device: 'Guest', heartbeatMs: 200})
    const lock = await transport.acquireLock({device: 'Guest', run: 'r1'}, Date.now())
    assert.ok(lock.held)
    const remote = new PeerRemote(transport)
    const run = (over: Parameters<typeof cw.run>[0] = {}) => cw.run({remote, previewConfirmed: true, mode: 'twoWay', ...over})
    const hostRead = (rel: string) => fs.readFile(path.join(host.root, ...rel.split('/')), 'utf8').catch(() => null)

    await cw.write('main', 'Music/a.mp3', 'AAA-1')
    await cw.write('main', 'Music/b.mp3', 'BBB')
    const first = await run()
    assert.equal(first.status, 'done', first.reason ?? '')
    assert.equal(first.remote?.uploaded, 2)
    assert.equal(await hostRead('Music/a.mp3'), 'AAA-1')
    assert.deepEqual(await walk(cw.copy), [], 'no media in the copy folder')
    assert.equal((await run()).exec, null, 'the second plan is empty')

    // an edit on this side replaces on the device, which keeps the old bytes in its trash
    await cw.write('main', 'Music/a.mp3', 'AAA-2 is longer', new Date('2021-01-01'))
    const edit = await run()
    assert.equal(edit.status, 'done', edit.reason ?? '')
    // the run's own report goes with a failure: it failed once under heavy load (2026-10-30) and could not be reproduced since
    assert.equal(await hostRead('Music/a.mp3'), 'AAA-2 is longer', `the edit reached the device; the run said ${JSON.stringify({status: edit.status, reason: edit.reason, exec: edit.exec, remote: edit.remote})}`)
    const trash = path.join(host.root, '.keepfin')
    const kept = (await walk(trash, false)).filter((f) => f.includes('trash/') || f.includes('versions/'))
    const texts = await Promise.all(kept.map((f) => fs.readFile(path.join(trash, ...f.split('/')), 'utf8').catch(() => '')))
    assert.ok(texts.includes('AAA-1'), 'the displaced bytes are kept by the device')
    assert.equal((await run()).exec, null)

    // a file only the device has comes into the library; a file main lost comes back from the device
    await fs.writeFile(path.join(host.root, 'Music', 'phone.mp3'), 'FROM-PHONE')
    await cw.remove('main', 'Music/b.mp3')
    // the device lists what its folder holds once its watcher has seen the new file
    await until(async () => {
      let seen = false
      await transport.scan('', (l) => void (seen ||= l.path === 'Music/phone.mp3'))
      return seen
    }, 'the device to list the new file')
    const both = await run({desiredFrom: [{path: 'Music/b.mp3', itemId: 'item:Music/b.mp3', revisionOp: 'rev1', contentId: idOf('BBB'), bytes: 3}, {path: 'Music/a.mp3', itemId: 'item:Music/a.mp3', revisionOp: 'rev1', contentId: idOf('AAA-2 is longer'), bytes: 15}]})
    assert.equal(both.status, 'done', both.reason ?? '')
    assert.equal(await cw.read('main', 'Music/b.mp3'), 'BBB')
    assert.equal(await cw.read('main', 'Music/phone.mp3'), 'FROM-PHONE')

    // a peer that goes away ends the run as unreachable, and nothing is lost
    await lock.release()
    client.close()
    await host.service.close()
    const down = await run()
    assert.notEqual(down.status, 'crashed')
    assert.equal(await cw.read('main', 'Music/a.mp3'), 'AAA-2 is longer')
  } finally {
    await cw.cleanup()
    await net.cleanup()
  }
})
