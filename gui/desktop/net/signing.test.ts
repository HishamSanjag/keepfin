// The store side of network spec 9.1: signed segments, the membership root, and what an enforced library does with ops it must not apply.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {decodeSegment, hlcTick, makeOp} from '../../shared/core/sync.js'
import {KeepfinStore, segmentBytes} from '../store/store.js'
import {listSegments, opsDirOf} from './segments.js'
import {World} from './testkit.js'

const pad = (n: number) => String(n).padStart(16, '0')

test('an enforced library sets unsigned segments of its own members aside, keeps the files, and remembers that after a cold open', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {withService: false})
    const b = await w.node('B', {withService: false})
    await a.add(1, 'old history')
    await a.store.enableMembership({actor: b.lib.actor(), spkiBase64: Buffer.from(b.identity.spki).toString('base64'), perms: ['write']})
    assert.equal((await a.lib.membership()).active, true)
    assert.ok((await a.lib.membership()).members[b.lib.actor()])
    // B's actor writes a version 1 (unsigned) segment after the genesis, as an old build would
    const actor = b.lib.actor()
    const hlc = hlcTick(null, Date.now(), actor)
    const forged = makeOp(actor, 1, hlc, 'item.add', {item: a.item(77), fields: {media: 'audio', title: 'unsigned after genesis'}})
    const dir = path.join(opsDirOf(a.root), actor)
    await fs.mkdir(dir, {recursive: true})
    const file = path.join(dir, `${pad(1)}-${pad(1)}.seg`)
    await fs.writeFile(file, segmentBytes(w.libraryId, actor, [forged], 1))
    await a.store.rescan({force: true})
    assert.ok(!a.titles().includes('unsigned after genesis'), 'not applied')
    assert.ok(a.titles().includes('old history'), 'the frozen unsigned history stays valid')
    await fs.access(file) // kept, never deleted
    // the same verdicts after a close and a cold open (the state database is only a cache)
    await a.store.close()
    for (const suffix of ['', '-wal', '-shm']) await fs.rm(path.join(a.deviceDir, 'state', `library-state.v1.db${suffix}`), {force: true})
    const again = await KeepfinStore.open({
      root: a.root,
      libraryId: w.libraryId,
      actor: a.lib.actor(),
      deviceDir: a.deviceDir,
      outboxDir: path.join(a.deviceDir, 'outbox'),
      newId: randomUUID,
      now: Date.now,
      watch: false,
      signer: {pkcs8: a.identity.pkcs8, spki: a.identity.spki},
    })
    try {
      assert.equal(again.membership().active, true)
      assert.ok(!again.views().items.some((i) => i.fields.title === 'unsigned after genesis'))
      assert.ok(again.views().items.some((i) => i.fields.title === 'old history'))
    } finally {
      await again.close()
    }
  } finally {
    await w.cleanup()
  }
})

test('without a signing key the store behaves as before: version 1 segments', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {withService: false})
    await a.add(1)
    await a.store.flush()
    const [segment] = await listSegments(a.root)
    const decoded = decodeSegment(await fs.readFile(segment.file), w.libraryId)
    assert.ok(decoded.ok && decoded.signer === a.identity.deviceId, 'signed with the device key')
    const bare = await KeepfinStore.open({root: a.root, libraryId: w.libraryId, actor: randomUUID(), deviceDir: path.join(w.dir, 'plain'), outboxDir: path.join(w.dir, 'plain', 'outbox'), newId: randomUUID, now: Date.now, watch: false, flushIdleMs: 10})
    try {
      await bare.apply([{kind: 'item.add', body: {item: a.item(2), fields: {media: 'audio', title: 'plain'}}}])
      await bare.flush()
      const own = (await listSegments(a.root)).find((s) => s.actor === bare.actorId)!
      assert.equal(decodeSegment(await fs.readFile(own.file), w.libraryId).ok && (decodeSegment(await fs.readFile(own.file), w.libraryId) as {signer: string | null}).signer, null)
    } finally {
      await bare.close()
    }
  } finally {
    await w.cleanup()
  }
})

test('only an administrator device can grant or revoke', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {withService: false})
    const b = await w.node('B', {withService: false})
    await assert.rejects(() => a.store.grantMember(b.lib.actor(), Buffer.from(b.identity.spki).toString('base64'), ['write']), /not enabled/)
    await a.store.enableMembership({actor: b.lib.actor(), spkiBase64: Buffer.from(b.identity.spki).toString('base64'), perms: ['write']})
    await assert.rejects(() => b.store.revokeMember(a.lib.actor(), 0), /not enabled|administrator/)
    await a.store.revokeMember(b.lib.actor(), 0)
    assert.equal((await a.lib.membership()).members[b.lib.actor()].cutoff, 0)
  } finally {
    await w.cleanup()
  }
})
