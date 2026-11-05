import {test} from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import {takeInventory} from './inventory.js'
import {makeWorld, put} from './fixtures.js'

test('inventory of a generated library of about 200 items', async () => {
  const world = await makeWorld({tracks: 130, downloads: 60})
  try {
    await put(path.join(world.music, 'Artist A', 'cover.jpg'), 'cover')
    const inventory = await takeInventory(world.deviceDir)

    assert.equal(inventory.library.items.length, 4 + 130 + 60 + 1)
    assert.equal(inventory.spaces.length, 4)
    assert.ok(inventory.library.present && inventory.queuePresent)

    // Unknown fields are kept verbatim, at every level.
    const first = inventory.library.items.find((item) => item.id === 'id1')
    assert.deepEqual(first?.extra, {futureField: {nested: [1, 2, {a: 'b'}]}})
    assert.equal(first?.raw.favorite, true)
    assert.deepEqual(inventory.library.extra, {topLevelFuture: {keep: 'me'}})
    assert.deepEqual(inventory.library.collections.find((item) => item.id === 'pl1')?.extra, {someFutureThing: 7})

    // Media: every indexed file plus the three loose ones.
    const indexed = inventory.media.filter((file) => file.indexed)
    assert.equal(indexed.length, 4 + 130 + 60)
    assert.equal(inventory.media.length, indexed.length + 3)
    assert.equal(inventory.unindexed.length, 3)
    assert.ok(inventory.unindexed.some((file) => file.endsWith('Loose 2.m4a')))
    for (const file of inventory.media) {
      assert.ok(file.size > 0 && file.mtimeMs > 0)
    }

    // Missing is listed, not an error.
    assert.deepEqual(inventory.missing.map((item) => item.itemId), ['gone'])

    // Sidecars are linked to their media; a folder cover is shared.
    const owner = (name: string) => inventory.sidecars.find((file) => file.path.endsWith(name))?.owner
    assert.ok(owner('Song 1.lrc')?.endsWith('Song 1.mp3'))
    assert.ok(owner('Song 3.jpg')?.endsWith('Song 3.flac'))
    assert.ok(owner('Clip.en.srt')?.endsWith('Clip.mp4'))
    assert.ok(owner('Clip.info.json')?.endsWith('Clip.mp4'))
    const cover = inventory.sidecars.find((file) => file.path.endsWith('cover.jpg'))
    assert.ok(cover?.shared && cover.owner?.endsWith('Song 1.mp3'))
    assert.equal(inventory.media.some((file) => file.path.endsWith('Song 3.jpg')), false, 'a cover is not a media file')

    // Art: linked to library entries, the orphan is separate.
    assert.ok(inventory.art.some((file) => file.itemId === 'id1'))
    assert.equal(inventory.art.length, 1 + Math.ceil(130 / 11))
    assert.equal(inventory.orphanArt.length, 1)

    // Unfinished downloads and unmanaged files are left alone.
    assert.ok(inventory.skipped.some((file) => file.endsWith('.part')))
    assert.ok(inventory.other.some((file) => file.endsWith('notes.txt')))

    // Jobs: only those with a file.
    assert.deepEqual(inventory.jobs.map((job) => [job.id, job.exists]), [['job1', true], ['job2', false]])

    assert.equal(inventory.counts.collections, 1)
    assert.equal(inventory.counts.playlists, 1)
    assert.equal(inventory.counts.history, 3)
    assert.equal(inventory.counts.userTags, 3 + 26)
    assert.equal(inventory.counts.favourites, 2 + 26)
  } finally {
    await world.cleanup()
  }
})

test('an empty device folder gives an empty inventory', async () => {
  const world = await makeWorld({loose: false})
  try {
    const inventory = await takeInventory(path.join(world.root, 'nothing-here'))
    assert.equal(inventory.library.present, false)
    assert.equal(inventory.media.length, 0)
    assert.equal(inventory.counts.media, 0)
  } finally {
    await world.cleanup()
  }
})
