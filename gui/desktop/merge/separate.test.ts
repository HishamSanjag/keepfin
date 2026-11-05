// Separate again: A reachable or not, Keep or Remove what A holds, S back with what was added since combining, B leaves T.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {until} from '../net/testkit.js'
import {folderJsonPath} from '../store/keepfinFolder.js'
import {loadProfiles} from '../store/profiles.js'
import {mergeEntityId} from '../../shared/core/merge.js'
import {mediaTree, mergeRig, uuidN, WIRE_MUSIC, type MRig} from './testkit.js'

const song = (n: number) => Buffer.from(`audio bytes of song ${n} `.repeat(40))

/** A combined: A has songs 1 and 2; B had 11, 12 (the same file as A's 2) and 13 (the same file as 11). B is on T after a restart. */
async function combined() {
  const rig = await mergeRig()
  const {a, b} = rig
  await a.put(1, 'Music/a-own.mp3', song(1))
  await a.put(2, 'Music/shared.mp3', song(2))
  await b.put(11, 'Music/b-own.mp3', song(11))
  await b.put(12, 'Music/b-copy-of-shared.mp3', song(2))
  await b.put(13, 'Music/b-own-twice.mp3', song(11))
  const before = await mediaTree(b.root)
  const state = await rig.engine.start(a.identity.deviceId, 'theirs')
  assert.equal(state.phase, 'done')
  await rig.restart().recover()
  const live = await rig.openActive()
  rig.useActive(live.store, live)
  const engine = rig.restart()
  rig.useActive(live.store, live) // restart() made new deps; the live store is shared through the rig
  const mergeId = (await engine.current())!.mergeId
  /** The id an item of B has in T (an item that came across as new content gets a new id). */
  const tOf = (n: number) => mergeEntityId(mergeId, 'item', uuidN(n))
  return {rig, live, before, engine, tOf}
}

async function syncUp(rig: MRig, live: Awaited<ReturnType<MRig['openActive']>>['store'], title: string) {
  rig.b.service.syncNow(rig.a.identity.deviceId)
  await until(() => rig.a.titles().includes(title), `A to see ${title}`, 15_000)
  void live
}

test('Separate again with A reachable: Keep what A holds; S is back with what was added since; B leaves T; nothing in B\'s folder is deleted', async () => {
  const {rig, live, before, engine, tOf} = await combined()
  try {
    // A copied one of the songs B brought (it holds a file of 11) and B added a song since combining
    await syncUp(rig, live.store, 'Song 13')
    await rig.a.store.apply([{kind: 'item.place', body: {item: tOf(11), place: rig.a.replicaId, path: 'Music/b-own.mp3'}}] as never)
    await rig.a.store.flush()
    rig.a.service.syncNow(rig.b.identity.deviceId)
    await until(() => Object.keys(live.store.views().placements[tOf(11)] ?? {}).length > 1, 'B to learn that A holds song 11', 15_000)
    await live.store.apply([
      {kind: 'item.add', body: {item: uuidN(21), fields: {media: 'audio', title: 'Song 21', space: WIRE_MUSIC, addedAt: 0}}},
      {kind: 'item.revise', body: {item: uuidN(21), content: `sha256:${'b'.repeat(64)}`, size: 5, parents: []}},
    ] as never)
    await fs.writeFile(path.join(rig.b.root, 'Music', 'since.mp3'), 'since combining')
    const bytes = Buffer.from('since combining')
    const { createHash } = await import('node:crypto')
    await live.store.apply([
      {kind: 'item.add', body: {item: uuidN(22), fields: {media: 'audio', title: 'Song 22', space: WIRE_MUSIC, addedAt: 0}}},
      {kind: 'item.revise', body: {item: uuidN(22), content: `sha256:${createHash('sha256').update(bytes).digest('hex')}`, size: bytes.length, parents: []}},
      {kind: 'item.place', body: {item: uuidN(22), place: (await readReplica(rig)), path: 'Music/since.mp3'}},
    ] as never)
    await live.store.flush()

    const preview = await engine.separatePreview()
    assert.equal(preview.separate!.brought, 2, 'song 11 and its twin 13 came across; 2 was already there')
    assert.deepEqual(preview.separate!.heldElsewhere.map((h) => h.id), [tOf(11)], 'A holds a file of song 11')
    assert.equal(preview.separate!.addedSince, 2)
    assert.equal(preview.separate!.unreachable, false)

    const done = await engine.separate({keepOnPeer: 'all'})
    assert.equal(done.phase, 'separating')
    assert.equal(rig.relaunches, 2, 'the app restarts onto S')
    await live.store.close()
    const restarted = rig.restart()
    await restarted.recover()

    const folder = JSON.parse(await fs.readFile(folderJsonPath(rig.b.root), 'utf8'))
    assert.equal(folder.libraryId, rig.sId)
    assert.equal(folder.records, '')
    assert.deepEqual(folder.libraries.map((l: {libraryId: string; role: string}) => [l.libraryId === rig.sId, l.role]).sort(), [[false, 'left'], [true, 'active']])
    assert.equal((await loadProfiles(rig.b.deviceDir))!.profiles[0].libraryId, rig.sId)

    const s = await rig.openActive()
    const titles = s.store.views().items.filter((i) => i.status !== 'deleted').map((i) => String(i.fields.title)).sort()
    assert.deepEqual(titles, ['Song 11', 'Song 12', 'Song 13', 'Song 21', 'Song 22'], 'S as it was, and the two songs added since combining (21 has no file here: it still comes back, as not on this device)')

    // A: song 13 (nobody else holds it) is forgotten, song 11 stays because A holds it, song 2 remains; B's placements are cleared
    await until(() => !rig.a.titles().includes('Song 13'), 'A to forget song 13', 15_000)
    const aItems = rig.a.store.views().items.filter((i) => i.status !== 'deleted')
    assert.ok(aItems.some((i) => i.id === tOf(11)))
    assert.ok(aItems.some((i) => i.id === uuidN(2)))
    const placed = rig.a.store.views().placements[uuidN(2)] ?? {}
    assert.ok(!Object.values(placed).includes('Music/b-copy-of-shared.mp3'), 'B no longer places A\'s song 2')
    // B left T: A revoked its actor
    const j = (await restarted.current())
    assert.equal(j, undefined, 'the merge is finished')
    const membership = await rig.a.store.membership()
    const bActor = (await readJournalActor(rig))
    assert.ok(membership.members[bActor]?.cutoff !== null, 'B\'s actor was revoked in T')
    assert.deepEqual(await mediaTree(rig.b.root), { ...before, 'Music/since.mp3': (await mediaTree(rig.b.root))['Music/since.mp3'] }, 'every media file is exactly where and what it was')
  } finally {
    await rig.cleanup()
  }
})

test('Separate again with Remove: the song A holds is forgotten too', async () => {
  const {rig, live, engine, tOf} = await combined()
  try {
    await syncUp(rig, live.store, 'Song 13')
    await rig.a.store.apply([{kind: 'item.place', body: {item: tOf(11), place: rig.a.replicaId, path: 'Music/b-own.mp3'}}] as never)
    await rig.a.store.flush()
    rig.a.service.syncNow(rig.b.identity.deviceId)
    await until(() => Object.keys(live.store.views().placements[tOf(11)] ?? {}).length > 1, 'B to learn that A holds song 11', 15_000)
    await engine.separate({keepOnPeer: 'none'})
    await live.store.close()
    await rig.restart().recover()
    await until(() => !rig.a.titles().includes('Song 11') && !rig.a.titles().includes('Song 13'), 'A to forget what B brought', 15_000)
    assert.deepEqual(rig.a.titles(), ['Song 1', 'Song 2'])
    assert.ok(await fs.stat(path.join(rig.a.root, 'Music', 'b-own.mp3')).then(() => false, () => true), 'A\'s files are not touched by a forget')
  } finally {
    await rig.cleanup()
  }
})

test('Separate again with A unreachable: refused until the person agrees; then nothing is published and A keeps everything', async () => {
  const {rig, live, engine, tOf} = await combined()
  try {
    await syncUp(rig, live.store, 'Song 13')
    await rig.a.service.close()
    await until(() => !rig.b.service.reachableFor(rig.tId), 'B to notice A is gone', 15_000)
    const preview = await engine.separatePreview()
    assert.equal(preview.separate!.unreachable, true)
    await assert.rejects(() => engine.separate({keepOnPeer: 'all'}), /not reachable/)
    const seqBefore = (live.store.currentLast().get(live.store.actorId) ?? 0)
    await engine.separate({keepOnPeer: 'all', anyway: true})
    assert.equal((live.store.currentLast().get(live.store.actorId) ?? 0), seqBefore, 'nothing was written into T')
    await live.store.close()
    await rig.restart().recover()
    const folder = JSON.parse(await fs.readFile(folderJsonPath(rig.b.root), 'utf8'))
    assert.equal(folder.libraryId, rig.sId)
    const s = await rig.openActive()
    assert.deepEqual(s.store.views().items.map((i) => String(i.fields.title)).sort(), ['Song 11', 'Song 12', 'Song 13'])
    assert.ok(rig.a.titles().includes('Song 13'), 'A keeps everything B brought')
  } finally {
    await rig.cleanup()
  }
})

test('Keep combined ends the days: S\'s frozen records are removed, the media and T\'s records stay', async () => {
  const {rig, live, before, engine} = await combined()
  try {
    const sOps = path.join(rig.b.root, '.keepfin', 'ops')
    assert.ok((await fs.readdir(sOps)).length > 0)
    await engine.keepCombined()
    assert.equal(await fs.stat(sOps).then(() => true, () => false), false)
    const folder = JSON.parse(await fs.readFile(folderJsonPath(rig.b.root), 'utf8'))
    assert.equal(folder.format, 2)
    assert.deepEqual(folder.libraries.map((l: {role: string}) => l.role).sort(), ['active', 'left'])
    assert.ok((await fs.readdir(path.join(rig.b.root, '.keepfin', 'libraries', rig.tId, 'ops'))).length > 0)
    assert.deepEqual(await mediaTree(rig.b.root), before)
    await assert.rejects(() => engine.separatePreview(), /no combined library/)
    void live
  } finally {
    await rig.cleanup()
  }
})

async function readReplica(rig: MRig): Promise<string> {
  const folder = JSON.parse(await fs.readFile(folderJsonPath(rig.b.root), 'utf8'))
  return folder.replicaId as string
}

async function readJournalActor(rig: MRig): Promise<string> {
  const names = await fs.readdir(path.join(rig.b.deviceDir, 'merges'))
  const file = names.find((n) => n.endsWith('.json'))!
  return JSON.parse(await fs.readFile(path.join(rig.b.deviceDir, 'merges', file), 'utf8')).t.actor as string
}
