// A paired device as a copy transport: inventory, bytes by content id, and mutations that the peer's own executor runs under its lock.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash, randomBytes} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {TransportInventoryLine} from '../copies/transport.js'
import {PeerClient} from './client.js'
import {PeerTransport, PeerTransportError} from './peerTransport.js'
import {World, type Node} from './testkit.js'

const idOf = (bytes: Buffer) => 'sha256:' + createHash('sha256').update(bytes).digest('hex')

// The host keeps the app's own lease, so a stalled CI runner cannot lose the lock mid-test; the expiring-lease test asks for a short one.
async function rig(leaseMs?: number) {
  const w = await World.create()
  const host = await w.node('Host', {leaseMs})
  const guest = await w.node('Guest')
  await w.pair(guest, host)
  await host.service.setAccess(guest.identity.deviceId, w.libraryId, ['metadata.read', 'metadata.write', 'inventory.read', 'media.read', 'copy.mutate'])
  const client = new PeerClient({identity: guest.identity, host: '127.0.0.1', port: host.port, pin: host.identity.deviceId})
  const transport = new PeerTransport({client, libraryId: w.libraryId, device: 'Guest', heartbeatMs: 100})
  const stage = async (bytes: Buffer, name = 'stage.bin') => {
    const file = path.join(guest.dir, name)
    await fs.writeFile(file, bytes)
    return file
  }
  return {w, host, guest, client, transport, stage}
}

const exists = (file: string) => fs.stat(file).then(() => true, () => false)

test('the peer folder is listed as a complete inventory with revisions', async () => {
  const r = await rig()
  try {
    await r.host.putFile('Music/a.mp3', randomBytes(100))
    await r.host.putFile('Videos/b.mkv', randomBytes(2000))
    const lines: TransportInventoryLine[] = []
    const result = await r.transport.scan('', (line) => lines.push(line))
    assert.equal(result.complete, true)
    assert.deepEqual(lines.map((l) => l.path).sort(), ['Music/a.mp3', 'Videos/b.mkv'], 'the .keepfin folder is never listed')
    assert.ok(lines.every((l) => l.revision && /^\d+-\d+$/.test(l.revision)))
    assert.equal(r.transport.capabilities().casMutation, true)
    assert.equal(r.transport.capabilities().serverHash, 'sha256')
  } finally {
    r.client.close()
    await r.w.cleanup()
  }
})

test('upload, replace, trash and generations need the peer lock, and each is checked by the peer itself', async () => {
  const r = await rig()
  try {
    const bytes = randomBytes(5000)
    const file = await r.stage(bytes)
    // nothing may change without the lock
    await assert.rejects(() => r.transport.uploadNew(file, 'Music/new.mp3', idOf(bytes)), (e: PeerTransportError) => e.kind === 'Locked')
    const lock = await r.transport.acquireLock({device: 'Guest', run: 'r1'}, Date.now())
    assert.equal(lock.held, true)
    // a second device does not get the lock while the first holds it
    const other = new PeerTransport({client: r.client, libraryId: r.w.libraryId, device: 'Guest'})
    assert.equal((await other.acquireLock({device: 'Guest', run: 'r2'}, Date.now())).held, true, 'the same device taking its own lock again takes over (a retry)')
    const lock2 = await r.transport.acquireLock({device: 'Guest', run: 'r1'}, Date.now())
    assert.equal(lock2.held, true)

    const created = await r.transport.uploadNew(file, 'Music/new.mp3', idOf(bytes))
    assert.ok(created.revision)
    assert.deepEqual(await fs.readFile(path.join(r.host.root, 'Music', 'new.mp3')), bytes)
    // create never replaces
    await assert.rejects(() => r.transport.uploadNew(file, 'Music/new.mp3', idOf(bytes)), (e: PeerTransportError) => e.kind === 'Conflict')
    // bytes that are not the pinned content are refused by the peer, and nothing is left behind
    await assert.rejects(() => r.transport.uploadNew(file, 'Music/wrong.mp3', idOf(randomBytes(10))), (e: PeerTransportError) => e.kind === 'Hash')
    assert.equal(await exists(path.join(r.host.root, 'Music', 'wrong.mp3')), false)
    assert.deepEqual(await fs.readdir(path.join(r.host.root, '.keepfin', 'staging')), [], 'staging is clean')

    // replace only at the revision we saw; the displaced bytes go to the trash first
    const next = randomBytes(7000)
    const nextFile = await r.stage(next, 'next.bin')
    await assert.rejects(() => r.transport.replace(nextFile, 'Music/new.mp3', idOf(next), '1-1'), (e: PeerTransportError) => e.kind === 'Changed')
    assert.deepEqual(await fs.readFile(path.join(r.host.root, 'Music', 'new.mp3')), bytes, 'a stale revision changes nothing')
    const replaced = await r.transport.replace(nextFile, 'Music/new.mp3', idOf(next), created.revision)
    assert.ok(replaced.preservedAs)
    assert.deepEqual(await fs.readFile(path.join(r.host.root, 'Music', 'new.mp3')), next)
    assert.deepEqual(await fs.readFile(path.join(r.host.root, '.keepfin', 'trash', 'net', replaced.preservedAs!)), bytes, 'the old bytes are kept')

    // trash moves, never deletes
    const trashed = await r.transport.moveToTrash('Music/new.mp3', replaced.revision)
    assert.equal(await exists(path.join(r.host.root, 'Music', 'new.mp3')), false)
    assert.deepEqual(await fs.readFile(path.join(r.host.root, '.keepfin', 'trash', 'net', trashed.trashedAs)), next)

    // generations: append-only, only above the current one, only under the lock
    assert.equal(await r.transport.readGeneration(), 0)
    assert.equal(await r.transport.appendGeneration(1, 'replica'), true)
    assert.equal(await r.transport.readGeneration(), 1)
    assert.equal(await r.transport.appendGeneration(1, 'replica'), false)

    await lock2.release()
    await assert.rejects(() => r.transport.moveToTrash('Music/x', null), (e: PeerTransportError) => e.kind === 'Locked')
  } finally {
    r.client.close()
    await r.w.cleanup()
  }
})

test('a lease that is not kept alive is released by the peer, and a device without copy.mutate cannot lock', async () => {
  const r = await rig(400)
  try {
    const quiet = new PeerTransport({client: r.client, libraryId: r.w.libraryId, device: 'Guest', heartbeatMs: 60_000})
    const lock = await quiet.acquireLock({device: 'Guest', run: 'x'}, Date.now())
    assert.equal(lock.held, true)
    await new Promise((resolve) => setTimeout(resolve, 700)) // the host's lease is 400 ms
    await assert.rejects(() => quiet.moveToTrash('Music/none.mp3', null), (e: PeerTransportError) => e.kind === 'Locked')
    await r.host.service.setAccess(r.guest.identity.deviceId, r.w.libraryId, ['metadata.read', 'metadata.write'])
    await assert.rejects(() => quiet.acquireLock({device: 'Guest', run: 'y'}, Date.now()), (e: PeerTransportError) => e.kind === 'Denied')
  } finally {
    r.client.close()
    await r.w.cleanup()
  }
})

test('names that would leave the copy are refused by the peer', async () => {
  const r = await rig()
  try {
    const lock = await r.transport.acquireLock({device: 'Guest', run: 'r1'}, Date.now())
    const bytes = randomBytes(20)
    const file = await r.stage(bytes)
    for (const bad of ['../outside.bin', 'Music/../../outside.bin', '/abs.bin', '.keepfin/ops/x.bin', 'C:/x.bin', 'a\\b.bin', 'Music//x.bin', 'Music/x.bin.part', 'Music/..']) {
      await assert.rejects(() => r.transport.uploadNew(file, bad, idOf(bytes)), (e: PeerTransportError) => e.status === 400, bad)
    }
    assert.equal(await exists(path.join(path.dirname(r.host.root), 'outside.bin')), false)
    await lock.release()
  } finally {
    r.client.close()
    await r.w.cleanup()
  }
})

export type {Node}
