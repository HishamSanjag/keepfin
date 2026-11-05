// Joining a library over the network: a paired device that does not hold the library gets its own Keepfin folder for it, takes in the
// peer's segments (an interrupted join continues), and opens as a replica that converges with the peer afterwards.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {Joined} from './join.js'
import {JoinError, joinFile, readJoin, runJoin} from './join.js'
import {PeerClient} from './client.js'
import {listSegments, vectorOf} from './segments.js'
import {KeepfinStore} from '../store/store.js'
import {World, until, type Node} from './testkit.js'

async function openJoined(w: World, guest: Node, joined: Joined) {
  return KeepfinStore.open({
    root: joined.root,
    libraryId: w.libraryId,
    actor: joined.actor,
    deviceDir: path.join(guest.dir, 'JoinedDevice'),
    outboxDir: path.join(guest.dir, 'JoinedDevice', 'outbox'),
    newId: randomUUID,
    now: () => Date.now(),
    flushIdleMs: 20,
    watch: false,
    signer: {pkcs8: guest.identity.pkcs8, spki: guest.identity.spki},
  })
}

async function rig(items = 6) {
  const w = await World.create()
  const adopted: Joined[] = []
  const host = await w.node('Host')
  const guest = await w.node('Guest', {libraryId: randomUUID(), adoptJoined: async (j) => void adopted.push(j)})
  // several segments, so an interrupted pull has something to resume
  for (let i = 1; i <= items; i++) {
    await host.add(i)
    await host.store.flush()
  }
  await w.pair(guest, host)
  return {w, host, guest, adopted}
}

test('a device that does not hold a library is offered it once the other device shares it, and joins it', async () => {
  const {w, host, guest, adopted} = await rig()
  try {
    assert.deepEqual(guest.service.state().offers, [], 'nothing is offered before the other device shares the library')
    await host.service.shareLibrary(guest.identity.deviceId, w.libraryId)
    await until(() => guest.service.state().offers.length === 1, 'the offer to reach the other device')
    const offer = guest.service.state().offers[0]
    assert.equal(offer.libraryId, w.libraryId)
    assert.equal(offer.deviceName, host.identity.name)

    const folder = path.join(w.dir, 'Guest', 'Joined')
    const joined = await guest.service.joinLibrary(host.identity.deviceId, w.libraryId, folder)
    assert.equal(guest.service.state().join?.stage, 'done')
    assert.equal(adopted.length, 1, 'the app is asked to make it a library profile')
    assert.equal(joined.libraryId, w.libraryId)
    // the folder: the same library id, a new replica id of its own
    const folderJson = JSON.parse(await fs.readFile(path.join(folder, '.keepfin', 'folder.json'), 'utf8'))
    assert.equal(folderJson.libraryId, w.libraryId)
    assert.notEqual(folderJson.replicaId, JSON.parse(await fs.readFile(path.join(host.root, '.keepfin', 'folder.json'), 'utf8')).replicaId)
    // every segment of the host is here
    const have = vectorOf(await listSegments(folder))
    for (const [actor, seq] of vectorOf(await listSegments(host.root))) assert.ok((have.get(actor) ?? 0) >= seq, `segments of ${actor}`)

    // opened as a replica: the content is there, signed history is enforced, and this device is a member
    const store = await openJoined(w, guest, joined)
    try {
      assert.deepEqual(store.views().items.map((i) => String(i.fields.title)).sort(), host.titles())
      const membership = await store.membership()
      assert.equal(membership.active, true)
      assert.ok(membership.members[joined.actor], 'the joining device is a member under its own actor')
    } finally {
      await store.close()
    }
    // the host recorded the actor the guest will write as
    assert.equal(host.service.peers.get(guest.identity.deviceId)?.libraries.find((l) => l.libraryId === w.libraryId)?.actor, joined.actor)
  } finally {
    await w.cleanup()
  }
})

test('a device that does not administer the library cannot add another, and a library that was not shared is refused', async () => {
  const w = await World.create()
  try {
    const host = await w.node('Host')
    const guest = await w.node('Guest', {libraryId: randomUUID()})
    await host.add(1)
    await w.pair(guest, host)
    const client = new PeerClient({identity: guest.identity, host: '127.0.0.1', port: host.port, pin: host.identity.deviceId})
    try {
      // not shared yet: the library is not even visible
      await assert.rejects(() => runJoin({client, peerId: host.identity.deviceId, libraryId: w.libraryId, name: 'Host', target: path.join(w.dir, 'Guest', 'NotShared')}), (e: JoinError) => e.code === 'denied')
      // once the other device shares it, a join works
      await host.service.shareLibrary(guest.identity.deviceId, w.libraryId)
      const done = await runJoin({client, peerId: host.identity.deviceId, libraryId: w.libraryId, name: 'Host', target: path.join(w.dir, 'Guest', 'Shared')})
      assert.equal(done.libraryId, w.libraryId)
      // a folder with other things in it is not used
      const busy = path.join(w.dir, 'Guest', 'Busy')
      await fs.mkdir(busy, {recursive: true})
      await fs.writeFile(path.join(busy, 'photo.jpg'), 'x')
      await assert.rejects(() => runJoin({client, peerId: host.identity.deviceId, libraryId: w.libraryId, name: 'Host', target: busy}), (e: JoinError) => e.code === 'folder')
    } finally {
      client.close()
    }
  } finally {
    await w.cleanup()
  }
})

test('an interrupted join continues with the same identity and ends with every segment', async () => {
  const {w, host, guest} = await rig(8)
  try {
    await host.service.shareLibrary(guest.identity.deviceId, w.libraryId)
    const client = new PeerClient({identity: guest.identity, host: '127.0.0.1', port: host.port, pin: host.identity.deviceId})
    try {
      const target = path.join(w.dir, 'Guest', 'Resumed')
      const stop = new AbortController()
      await assert.rejects(
        () =>
          runJoin({
            client,
            peerId: host.identity.deviceId,
            libraryId: w.libraryId,
            name: 'Host',
            target,
            signal: stop.signal,
            onProgress: (p) => {
              if (p.stage === 'pulling' && p.segments >= 3) stop.abort() // the network goes away after a few segments
            },
          }),
        (e: JoinError) => e.code === 'cancelled',
      )
      const partial = await readJoin(target)
      assert.equal(partial?.stage, 'joining')
      const before = (await listSegments(target)).length
      const hostCount = (await listSegments(host.root)).length
      assert.ok(before >= 1 && before < hostCount, `part of the library arrived (${before} of ${hostCount})`)
      // choosing the same folder again picks up where it stopped: same replica, same actor, no second grant
      const done = await runJoin({client, peerId: host.identity.deviceId, libraryId: w.libraryId, name: 'Host', target})
      assert.equal(done.replicaId, partial!.replicaId)
      assert.equal(done.actor, partial!.actor)
      assert.equal((await listSegments(target)).length, hostCount)
      assert.equal((await readJoin(target))?.stage, 'ready')
      assert.ok(await fs.stat(joinFile(target)))
    } finally {
      client.close()
    }
  } finally {
    await w.cleanup()
  }
})
