// Combine scenarios: duplicates, missing and changed files, metadata, T edited on A meanwhile, empty libraries, refusals.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {mergeEntityId} from '../../shared/core/merge.js'
import {until} from '../net/testkit.js'
import {openKeepfinFolder} from '../store/keepfinFolder.js'
import {readJsonLines} from './lines.js'
import {mapFileOf} from './engine.js'
import {contentId, mediaTree, mergeRig, uuidN, type MRig} from './testkit.js'

const song = (n: number) => Buffer.from(`audio bytes of song ${n} `.repeat(40))

async function finish(rig: MRig) {
  const state = await rig.engine.start(rig.a.identity.deviceId, 'theirs')
  assert.equal(state.phase, 'done')
  await rig.restart().recover()
  const mergeId = (await rig.restart().current())!.mergeId
  const live = await rig.openActive()
  const tOf = (n: number) => mergeEntityId(mergeId, 'item', uuidN(n))
  return {live, tOf, mergeId}
}

test('duplicates, a missing file and a file that changed since it was recorded: each item is kept, listed, and nothing is lost', async () => {
  const rig = await mergeRig()
  try {
    await rig.a.put(2, 'Music/shared.mp3', song(2))
    await rig.b.put(31, 'Music/gone.mp3', song(31))
    await rig.b.put(32, 'Music/changed.mp3', song(32))
    await rig.b.put(34, 'Music/same-as-a-1.mp3', song(2))
    await rig.b.put(35, 'Music/same-as-a-2.mp3', song(2))
    await fs.rm(path.join(rig.b.root, 'Music', 'gone.mp3'))
    const newBytes = Buffer.from('this file was replaced after Keepfin recorded it')
    await fs.writeFile(path.join(rig.b.root, 'Music', 'changed.mp3'), newBytes)
    const before = await mediaTree(rig.b.root)

    const review = await rig.engine.preview(rig.a.identity.deviceId)
    assert.deepEqual([review.preview!.items, review.preview!.added, review.preview!.linked, review.preview!.keptDuplicates, review.preview!.missingFiles], [4, 2, 1, 1, 1])
    assert.ok(review.preview!.review.some((r) => r.kind === 'missingFile' && r.s === uuidN(31)))
    assert.ok(review.preview!.review.some((r) => r.kind === 'duplicateInS' && r.s === uuidN(35)))

    const {live, tOf, mergeId} = await finish(rig)
    try {
      const views = live.store.views()
      const item = (id: string) => views.items.find((i) => i.id === id)
      // the changed file is described by what it is now, not by the old record
      assert.equal(item(tOf(32))!.heads[0].content, contentId(newBytes))
      // the missing one is still an item, with no placement of this device
      assert.ok(item(tOf(31)))
      assert.deepEqual(Object.values(views.placements[tOf(31)] ?? {}), [])
      // the linked and the kept duplicate
      const map = await readJsonLines<{s: string; t: string; how: string}>(mapFileOf(rig.b.root, `libraries/${rig.tId}`, mergeId))
      assert.equal(map.find((m) => m.s === uuidN(34))!.t, uuidN(2))
      assert.equal(map.find((m) => m.s === uuidN(35))!.how, 'kept-duplicate')
      const j = (await rig.restart().current())!
      assert.equal(j.verified!.missing, 1, 'the missing file was listed, not silently dropped')
    } finally {
      await live.store.close()
    }
    assert.deepEqual(await mediaTree(rig.b.root), before)
  } finally {
    await rig.cleanup()
  }
})

test('favourites, tags and play counts come across once; a linked item adds its plays to the other library\'s', async () => {
  const rig = await mergeRig()
  try {
    await rig.a.put(2, 'Music/shared.mp3', song(2))
    await rig.a.store.apply([{kind: 'item.set', body: {item: uuidN(2), field: `plays@${rig.a.store.actorId}`, value: 5}}] as never)
    await rig.a.store.flush()
    await rig.b.put(11, 'Music/b-own.mp3', song(11))
    await rig.b.put(12, 'Music/b-copy-of-shared.mp3', song(2))
    const bActor = rig.b.store.actorId
    await rig.b.store.apply([
      {kind: 'set.add', body: {set: 'favourites', element: uuidN(11)}},
      {kind: 'set.add', body: {set: `tags:${uuidN(11)}`, element: 'rock'}},
      {kind: 'item.set', body: {item: uuidN(11), field: `plays@${bActor}`, value: 7}},
      {kind: 'item.set', body: {item: uuidN(12), field: `plays@${bActor}`, value: 3}},
    ] as never)
    await rig.b.store.flush()
    const {live, tOf} = await finish(rig)
    try {
      const views = live.store.views()
      const plays = (id: string) => views.items.find((i) => i.id === id)!.plays
      assert.equal(plays(tOf(11)), 7)
      assert.equal(plays(uuidN(2)), 8, 'A\'s 5 and the 3 played on the phone')
      assert.ok(views.sets.favourites.includes(tOf(11)))
      assert.deepEqual(views.sets[`tags:${tOf(11)}`], ['rock'])
    } finally {
      await live.store.close()
    }
  } finally {
    await rig.cleanup()
  }
})

test('"Keep my settings": the other library\'s routes follow this library\'s', async () => {
  const rig = await mergeRig()
  try {
    await rig.a.put(1, 'Music/a.mp3', song(1))
    await rig.b.put(11, 'Music/b.mp3', song(11))
    const music = uuidN(901)
    const other = uuidN(902)
    await rig.b.store.apply([{kind: 'profile.set', body: {field: 'routes', value: {music, other}}}] as never)
    await rig.b.store.flush()
    await rig.engine.preview(rig.a.identity.deviceId, 'mine')
    const done = await rig.engine.start(rig.a.identity.deviceId, 'mine')
    assert.equal(done.phase, 'done')
    await rig.restart().recover()
    const mergeId = (await rig.restart().current())!.mergeId
    const live = await rig.openActive()
    try {
      assert.deepEqual(live.store.views().profile.routes, {music: mergeEntityId(mergeId, 'space', music), other: mergeEntityId(mergeId, 'space', other)})
    } finally {
      await live.store.close()
    }
  } finally {
    await rig.cleanup()
  }
})

test('T edited on A while B combines: nothing is lost and A\'s edit reaches B once it syncs', async () => {
  const rig = await mergeRig()
  try {
    await rig.a.put(1, 'Music/a.mp3', song(1))
    await rig.b.put(11, 'Music/b.mp3', song(11))
    await rig.engine.preview(rig.a.identity.deviceId)
    // after the review, A adds a song (the same file B has) and renames one
    await rig.a.put(5, 'Music/a-new.mp3', song(11))
    await rig.a.store.apply([{kind: 'item.set', body: {item: uuidN(1), field: 'title', value: 'Renamed on A'}}] as never)
    await rig.a.store.flush()
    const {live, tOf} = await finish(rig)
    try {
      rig.useActive(live.store, live)
      rig.b.service.syncNow(rig.a.identity.deviceId)
      await until(() => live.store.views().items.some((i) => String(i.fields.title) === 'Song 5'), 'A\'s new song to reach B', 15_000)
      await until(() => live.store.views().items.some((i) => String(i.fields.title) === 'Renamed on A'), 'A\'s rename to reach B', 15_000)
      const titles = live.store.views().items.map((i) => String(i.fields.title)).sort()
      assert.deepEqual(titles, ['Renamed on A', 'Song 11', 'Song 5'], 'B\'s song once, A\'s two')
      assert.ok(live.store.views().items.some((i) => i.id === tOf(11)))
      // and B's songs reach A
      await until(() => rig.a.titles().includes('Song 11'), 'B\'s song to reach A', 15_000)
    } finally {
      await live.store.close()
    }
  } finally {
    await rig.cleanup()
  }
})

test('empty S and empty T combine cleanly', async () => {
  for (const [a, b] of [[0, 2], [2, 0], [0, 0]] as const) {
    const rig = await mergeRig()
    try {
      for (let i = 0; i < a; i++) await rig.a.put(i + 1, `Music/a${i}.mp3`, song(i + 1))
      for (let i = 0; i < b; i++) await rig.b.put(i + 11, `Music/b${i}.mp3`, song(i + 11))
      const {live} = await finish(rig)
      try {
        assert.equal(live.store.views().items.filter((i) => i.status !== 'deleted').length, a + b, `A has ${a}, B has ${b}`)
      } finally {
        await live.store.close()
      }
    } finally {
      await rig.cleanup()
    }
  }
})

test('refused: a copy is running or a review is open; this device already has the library; an older app cannot open the folder', async () => {
  const rig = await mergeRig()
  try {
    await rig.b.put(11, 'Music/b.mp3', song(11))
    rig.blockers.push('A copy is running on this device. Wait for it to finish.')
    await assert.rejects(() => rig.engine.preview(rig.a.identity.deviceId), /A copy is running/)
    rig.blockers.length = 0
    rig.held.push({libraryId: rig.tId, name: 'Desk PC'})
    await assert.rejects(() => rig.engine.preview(rig.a.identity.deviceId), /This device already has Desk PC/)
    rig.held.length = 0
    assert.equal(await rig.engine.current(), undefined, 'a refusal leaves no journal')
    await finish(rig)
    assert.deepEqual(await openKeepfinFolder(rig.b.root, {maxFormat: 1}), {ok: false, reason: 'newer-format'})
    assert.ok((await openKeepfinFolder(rig.b.root)).ok)
  } finally {
    await rig.cleanup()
  }
})

test('Cancel before Combine removes the staged records and leaves the folder as it was', async () => {
  const rig = await mergeRig()
  try {
    await rig.b.put(11, 'Music/b.mp3', song(11))
    const before = await mediaTree(rig.b.root)
    await rig.engine.preview(rig.a.identity.deviceId)
    assert.ok(await fs.stat(path.join(rig.b.root, '.keepfin', 'libraries', rig.tId)))
    await rig.engine.cancel()
    assert.equal(await fs.stat(path.join(rig.b.root, '.keepfin', 'libraries')).then(() => true, () => false) && (await fs.readdir(path.join(rig.b.root, '.keepfin', 'libraries'))).length > 0, false)
    assert.equal(await rig.engine.current(), undefined)
    assert.equal(JSON.parse(await fs.readFile(path.join(rig.b.root, '.keepfin', 'folder.json'), 'utf8')).format, 1)
    assert.deepEqual(await mediaTree(rig.b.root), before)
  } finally {
    await rig.cleanup()
  }
})
