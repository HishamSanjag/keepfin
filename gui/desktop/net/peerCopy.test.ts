// A copy on a paired device: the peer's own executor changes its folder only when the file still has the content the caller saw
// (HTTP If-Match on the content id; size and modification time play no part), files can be read by name, and moved.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash, randomBytes} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {DeviceAction} from '../../shared/devices.js'
import {PeerClient} from './client.js'
import {PeerMedia} from './peerPlace.js'
import {PeerTransport, PeerTransportError} from './peerTransport.js'
import {World} from './testkit.js'

const idOf = (bytes: Buffer) => 'sha256:' + createHash('sha256').update(bytes).digest('hex')

async function rig(actions: DeviceAction[] = ['metadata.read', 'metadata.write', 'inventory.read', 'media.read', 'copy.mutate']) {
  const w = await World.create()
  const host = await w.node('Host', {leaseMs: 2000})
  const guest = await w.node('Guest')
  await w.pair(guest, host)
  await host.service.setAccess(guest.identity.deviceId, w.libraryId, [...actions])
  const client = new PeerClient({identity: guest.identity, host: '127.0.0.1', port: host.port, pin: host.identity.deviceId})
  const transport = new PeerTransport({client, libraryId: w.libraryId, device: 'Guest', heartbeatMs: 100})
  const stage = async (bytes: Buffer, name = 'stage.bin') => {
    const file = path.join(guest.dir, name)
    await fs.writeFile(file, bytes)
    return file
  }
  return {w, host, guest, client, transport, stage, cleanup: async () => (client.close(), w.cleanup())}
}

test('a replacement is refused when the content on the peer is not the one the caller saw, even at the same size and time', async () => {
  const r = await rig()
  try {
    const a = randomBytes(4000)
    const { file } = await r.host.putFile('Music/a.mp3', a)
    await r.transport.acquireLock({device: 'Guest', run: 'r1'}, Date.now())
    const next = randomBytes(5000)
    const nextFile = await r.stage(next)

    // somebody edits the file on the peer without changing its size, and puts the modification time back
    const stamp = new Date(Math.floor(Date.now() / 1000) * 1000 - 100_000)
    await fs.utimes(file, stamp, stamp)
    const before = await fs.stat(file)
    const b = randomBytes(4000)
    await fs.writeFile(file, b)
    await fs.utimes(file, stamp, stamp)
    const same = await fs.stat(file)
    assert.equal(same.size, before.size)
    assert.equal(Math.floor(same.mtimeMs), Math.floor(before.mtimeMs), 'size and time did not change: only the content did')

    await assert.rejects(() => r.transport.replace(nextFile, 'Music/a.mp3', idOf(next), idOf(a)), (e: PeerTransportError) => e.kind === 'Changed')
    assert.ok((await fs.readFile(file)).equals(b), 'the edit on the peer is untouched')

    // the caller that knows the current content succeeds, and the displaced bytes are kept in the peer's trash
    const done = await r.transport.replace(nextFile, 'Music/a.mp3', idOf(next), idOf(b))
    assert.ok(done.preservedAs)
    assert.ok((await fs.readFile(file)).equals(next))
    assert.ok((await fs.readFile(path.join(r.host.root, '.keepfin', 'trash', 'net', done.preservedAs!))).equals(b))

    // a file that was only touched (same bytes, new time) is not a conflict
    const later = new Date(Date.now() + 60_000)
    await fs.utimes(file, later, later)
    const third = randomBytes(1000)
    const thirdFile = await r.stage(third, 'third.bin')
    await r.transport.replace(thirdFile, 'Music/a.mp3', idOf(third), idOf(next))
    assert.ok((await fs.readFile(file)).equals(third))

    // the old size-and-time precondition still works for callers that know nothing better
    const st = await fs.stat(file)
    await assert.rejects(() => r.transport.replace(nextFile, 'Music/a.mp3', idOf(next), '1-1'), (e: PeerTransportError) => e.kind === 'Changed')
    await r.transport.replace(nextFile, 'Music/a.mp3', idOf(next), `${st.size}-${Math.floor(st.mtimeMs)}`)
  } finally {
    await r.cleanup()
  }
})

test('trash and move are conditional on the content too, never overwrite, and stay inside the folder', async () => {
  const r = await rig()
  try {
    const a = randomBytes(1500)
    await r.host.putFile('Music/a.mp3', a)
    await r.host.putFile('Music/taken.mp3', randomBytes(10))
    await r.transport.acquireLock({device: 'Guest', run: 'r1'}, Date.now())
    await assert.rejects(() => r.transport.moveTo('Music/a.mp3', 'Music/b.mp3', idOf(randomBytes(5))), (e: PeerTransportError) => e.kind === 'Changed')
    await assert.rejects(() => r.transport.moveTo('Music/a.mp3', 'Music/taken.mp3', idOf(a)), (e: PeerTransportError) => e.kind === 'Conflict')
    await assert.rejects(() => r.transport.moveTo('Music/a.mp3', '../outside.mp3', idOf(a)), /cannot be used/)
    await assert.rejects(() => r.transport.moveTo('Music/a.mp3', '.keepfin/ops/x', idOf(a)), /cannot be used/)
    const moved = await r.transport.moveTo('Music/a.mp3', 'Music/Sub/b.mp3', idOf(a))
    assert.ok(moved.revision)
    assert.ok((await fs.readFile(path.join(r.host.root, 'Music', 'Sub', 'b.mp3'))).equals(a))
    await assert.rejects(() => r.transport.moveToTrash('Music/Sub/b.mp3', idOf(randomBytes(3))), (e: PeerTransportError) => e.kind === 'Changed')
    await r.transport.moveToTrash('Music/Sub/b.mp3', idOf(a))
    assert.equal(await fs.stat(path.join(r.host.root, 'Music', 'Sub', 'b.mp3')).then(() => true, () => false), false)
    await assert.rejects(() => r.transport.moveToTrash('Music/Sub/b.mp3', idOf(a)), (e: PeerTransportError) => e.kind === 'NotFound')
  } finally {
    await r.cleanup()
  }
})

test('a file is read by name with Range, hashed on arrival, and only with the media permission', async () => {
  const r = await rig()
  try {
    const bytes = randomBytes(70_000)
    await r.host.putFile('Videos/clip.mkv', bytes)
    const staged = path.join(r.guest.dir, 'part.bin')
    assert.equal(await r.transport.download('Videos/clip.mkv', staged, '*'), idOf(bytes), 'the caller learns the content id')
    // a broken transfer resumes from where the partial file ends
    await fs.writeFile(staged, bytes.subarray(0, 30_000))
    assert.equal(await r.transport.download('Videos/clip.mkv', staged, '*'), idOf(bytes))
    assert.ok((await fs.readFile(staged)).equals(bytes))
    // names that climb out of the folder or into the library's own data are not files
    await assert.rejects(() => r.transport.download('../secret', path.join(r.guest.dir, 'x'), '*'), /cannot be used|answered/)
    await assert.rejects(() => r.transport.download('.keepfin/folder.json', path.join(r.guest.dir, 'x'), '*'), /cannot be used|answered/)
    await assert.rejects(() => r.transport.download('Videos/missing.mkv', path.join(r.guest.dir, 'x'), '*'), (e: PeerTransportError) => e.kind === 'NotFound')
  } finally {
    await r.cleanup()
  }
  const noMedia = await rig(['metadata.read', 'metadata.write', 'inventory.read', 'copy.mutate'])
  try {
    await noMedia.host.putFile('Videos/clip.mkv', randomBytes(100))
    await assert.rejects(() => noMedia.transport.download('Videos/clip.mkv', path.join(noMedia.guest.dir, 'p'), '*'), (e: PeerTransportError) => e.kind === 'Denied')
  } finally {
    await noMedia.cleanup()
  }
})

test('a file the runner archives in the mirror is replaced on the peer when written again, and goes to the peer trash when it is not', async () => {
  const r = await rig()
  try {
    const a = randomBytes(800)
    const b = randomBytes(900)
    await r.host.putFile('Music/a.mp3', a)
    await r.host.putFile('Music/b.mp3', b)
    await r.transport.acquireLock({device: 'Guest', run: 'r1'}, Date.now())
    const known = new Map([['Music/a.mp3', idOf(a)], ['Music/b.mp3', idOf(b)]])
    const media = new PeerMedia(r.transport, async (rel) => known.get(rel))
    // the runner keeps the old bytes of both files next to the copy (a move into the bookkeeping folder); nothing happens on the peer yet
    await media.moveTo('Music/a.mp3', '.keepfin/versions/a-1', null)
    await media.moveTo('Music/b.mp3', '.keepfin/versions/b-1', null)
    assert.ok((await fs.readFile(path.join(r.host.root, 'Music', 'a.mp3'))).equals(a))
    // a.mp3 is written again with new content: on the peer that is a replacement at the old content
    const next = randomBytes(1000)
    const out = await media.uploadNew(await r.stage(next), 'Music/a.mp3', idOf(next))
    assert.ok(out.revision)
    assert.ok((await fs.readFile(path.join(r.host.root, 'Music', 'a.mp3'))).equals(next))
    // b.mp3 is not written again: it is moved to the peer's trash, bytes kept
    assert.deepEqual(await media.settleRetired(), [])
    assert.equal(await fs.stat(path.join(r.host.root, 'Music', 'b.mp3')).then(() => true, () => false), false)
    const trash = path.join(r.host.root, '.keepfin', 'trash', 'net')
    const kept = await Promise.all((await fs.readdir(trash)).map((n) => fs.readFile(path.join(trash, n))))
    assert.ok(kept.some((k) => k.equals(a)) && kept.some((k) => k.equals(b)), 'both old files are kept on the peer')
    // if the peer's file was edited meanwhile, the replacement is refused and the edit stays
    const c = randomBytes(300)
    const { file } = await r.host.putFile('Music/c.mp3', c)
    known.set('Music/c.mp3', idOf(c))
    await media.moveTo('Music/c.mp3', '.keepfin/versions/c-1', null)
    const edited = randomBytes(300)
    await fs.writeFile(file, edited)
    const replacement = randomBytes(310)
    const replacementFile = await r.stage(replacement, 'c-new.bin')
    await assert.rejects(() => media.uploadNew(replacementFile, 'Music/c.mp3', idOf(replacement)), (e: PeerTransportError) => e.kind === 'Changed')
    const failed = await media.settleRetired()
    assert.equal(failed.length, 1, 'trashing the old content is refused too: the peer no longer holds it')
    assert.ok((await fs.readFile(file)).equals(edited), 'the edit made on the peer is untouched')
  } finally {
    await r.cleanup()
  }
})

test('the copy never replaces a file whose content it has not seen: the mirror asks the peer media with the content id it knows', async () => {
  const r = await rig()
  try {
    const a = randomBytes(2000)
    const { file } = await r.host.putFile('Music/a.mp3', a)
    const lock = await r.transport.acquireLock({device: 'Guest', run: 'r1'}, Date.now())
    assert.ok(lock.held)
    let known: string | undefined
    const media = new PeerMedia(r.transport, async () => known)
    const next = randomBytes(2500)
    const nextFile = await r.stage(next)
    // nothing known about the file: it is not touched
    await assert.rejects(() => media.replace(nextFile, 'Music/a.mp3', idOf(next), '2000-1'), (e: PeerTransportError) => e.kind === 'Changed')
    await assert.rejects(() => media.moveToTrash('Music/a.mp3', '2000-1'), (e: PeerTransportError) => e.kind === 'Changed')
    assert.ok((await fs.readFile(file)).equals(a))
    // known, but the peer's file is no longer that content: refused by the peer
    known = idOf(randomBytes(9))
    await assert.rejects(() => media.replace(nextFile, 'Music/a.mp3', idOf(next), '2000-1'), (e: PeerTransportError) => e.kind === 'Changed')
    // known and current: replaced; what was just sent is what the next change is conditional on
    known = idOf(a)
    await media.replace(nextFile, 'Music/a.mp3', idOf(next), '2000-1')
    assert.ok((await fs.readFile(file)).equals(next))
    const again = randomBytes(300)
    const againFile = await r.stage(again, 'again.bin')
    known = undefined // the state file only gets it at the end of a run; the media remembers what it sent
    await media.replace(againFile, 'Music/a.mp3', idOf(again), '2500-1')
    assert.ok((await fs.readFile(file)).equals(again))
    // the bookkeeping folder of the copy is not on the peer: moving in or out of it is a new file on the peer's side
    await assert.rejects(() => media.moveTo('.keepfin/x', 'Music/y', null), (e: Error) => e.name === 'CloudError')
  } finally {
    await r.cleanup()
  }
})
