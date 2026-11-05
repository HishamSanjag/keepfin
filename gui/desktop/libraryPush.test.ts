// The window gets library deltas, not the whole library (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md 10.2): a copy that
// applies every push equals snapshot() after every step, whatever the index did.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import {isDeepStrictEqual} from 'node:util'
import {applyLibraryDelta, snapshotFromBatches} from '../shared/libraryDelta.js'
import {defaultLibrarySettings, type LibrarySnapshot} from '../shared/library.js'
import type {LibraryBatchPush} from '../shared/contracts.js'
import {LibraryIndex, type LibraryPushEvent} from './library/index.js'

function prng(seed: number) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

async function setup(options: {pushBatch?: number} = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-push-'))
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
  for (const space of settings.spaces.filter((item) => item.type === 'folder')) await fs.mkdir(space.folder, {recursive: true})
  const index = new LibraryIndex({file: path.join(dir, 'data', 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => undefined, saveDelay: 5, pushDelay: 1, watch: false, analyse: false, ...options})
  await index.init()
  const mirror: {snap?: LibrarySnapshot; seq: number; gaps: number; pushes: string[]; batches: LibraryBatchPush[]} = {seq: -1, gaps: 0, pushes: [], batches: []}
  index.subscribePush((event: LibraryPushEvent) => {
    mirror.pushes.push(event.type)
    if (event.type === 'library') {
      mirror.snap = event.library
      mirror.seq = event.library.seq ?? -1
    } else if (event.type === 'libraryDelta') {
      if (!mirror.snap || event.base !== mirror.seq) mirror.gaps++
      else {
        mirror.snap = applyLibraryDelta(mirror.snap, event)
        mirror.seq = event.seq
      }
    } else {
      if (event.index === 0) mirror.batches = []
      mirror.batches.push(event)
      if (event.index === event.total - 1) {
        mirror.snap = snapshotFromBatches(mirror.batches)
        mirror.seq = event.seq
      }
    }
  })
  index.configure(settings)
  const write = async (name: string, text = 'x') => {
    const file = path.join(settings.spaces[1].folder, name)
    await fs.writeFile(file, text)
    return file
  }
  const normal = (snapshot: LibrarySnapshot) => JSON.parse(JSON.stringify({...snapshot, seq: undefined}))
  // the pushes that are due land, however busy the machine: a fixed wait failed under load (the mirror was one push behind)
  const settle = async () => {
    const end = Date.now() + 5_000
    await delay(25)
    while (Date.now() < end && !isDeepStrictEqual(normal(mirror.snap as LibrarySnapshot), normal(index.snapshot()))) await delay(10)
  }
  return {dir, settings, index, mirror, write, normal, settle, cleanup: async () => {
    await index.close()
    await fs.rm(dir, {recursive: true, force: true})
  }}
}

test('a copy that applies every push equals snapshot() after every step (seeded random operations)', async () => {
  for (const seed of [1, 2, 3, 4, 5]) {
    const rng = prng(seed)
    const f = await setup()
    try {
      const ids: string[] = []
      for (let step = 0; step < 40; step++) {
        const choice = Math.floor(rng() * 11)
        const pick = () => ids[Math.floor(rng() * ids.length)]
        switch (choice) {
          case 0:
          case 1: {
            const entry = await f.index.addFile(await f.write(`s${seed}-${step}.mp3`), {space: 'music', managed: true})
            if (entry) ids.push(entry.id)
            break
          }
          case 2:
            if (ids.length) f.index.setFavorite([pick()], rng() < 0.7)
            break
          case 3:
            if (ids.length) f.index.setUserTags([pick()], [`t${Math.floor(rng() * 4)}`], [])
            break
          case 4:
            if (ids.length) f.index.recordPlayback(pick(), 40 + step, false)
            break
          case 5:
            if (ids.length) f.index.saveCollection({name: `List ${step}`, itemIds: [pick(), pick()]})
            break
          case 6: {
            const list = f.index.collectionList()
            if (list.length && ids.length) f.index.addToCollection(list[0].id, [pick()])
            break
          }
          case 7: {
            const list = f.index.collectionList()
            if (list.length) f.index.reorderCollection(list[0].id, [...list[0].itemIds].reverse())
            break
          }
          case 8:
            if (ids.length > 1) {
              const gone = pick()
              f.index.drop([gone])
              ids.splice(ids.indexOf(gone), 1)
            }
            break
          case 9: {
            const operation = f.index.beginOperation('import', 3)
            operation.advance()
            operation.end()
            break
          }
          case 10: {
            const list = f.index.collectionList()
            if (list.length) f.index.deleteCollection(list[list.length - 1].id)
            break
          }
        }
        await f.settle()
        assert.deepEqual(f.normal(f.mirror.snap as LibrarySnapshot), f.normal(f.index.snapshot()), `seed ${seed} step ${step} (op ${choice})`)
      }
      assert.equal(f.mirror.gaps, 0)
      assert.ok(f.mirror.pushes.filter((type) => type === 'libraryDelta').length > 10, 'most changes went out as deltas')
    } finally {
      await f.cleanup()
    }
  }
})

test('a delta carries only what changed', async () => {
  const f = await setup()
  try {
    const ids: string[] = []
    for (let n = 0; n < 6; n++) ids.push((await f.index.addFile(await f.write(`d${n}.mp3`), {space: 'music', managed: true}))!.id)
    // wait until the pushes from the adds have stopped (a fixed short wait let a late one in under load), at most 10 s
    let last = Date.now()
    const quiet = f.index.subscribePush(() => (last = Date.now()))
    for (const end = Date.now() + 10_000; Date.now() - last < 300 && Date.now() < end; ) await f.settle()
    quiet()
    const seen: LibraryPushEvent[] = []
    f.index.subscribePush((event) => seen.push(event))
    f.index.setFavorite([ids[2]], true)
    await f.settle()
    assert.equal(seen.length, 1)
    const delta = seen[0]
    assert.equal(delta.type, 'libraryDelta')
    if (delta.type === 'libraryDelta') {
      assert.deepEqual(delta.items?.map((item) => item.id), [ids[2]])
      assert.equal(delta.collections, undefined, 'a favourite does not resend collections')
      assert.equal(delta.removedItems, undefined)
    }
  } finally {
    await f.cleanup()
  }
})

test('a big library is pushed in batches, and what changes meanwhile follows as a delta', async () => {
  const f = await setup({pushBatch: 7})
  try {
    for (let n = 0; n < 30; n++) await f.index.addFile(await f.write(`b${n}.mp3`), {space: 'music', managed: true})
    await f.settle()
    f.index.changed() // a change of unknown extent: the whole library goes out again, now in batches
    await delay(100)
    assert.ok(f.mirror.pushes.includes('libraryBatch'))
    assert.deepEqual(f.normal(f.mirror.snap as LibrarySnapshot), f.normal(f.index.snapshot()))
    const state = f.index.stateResponse()
    assert.equal(state.batched, true)
    assert.equal(state.items.length, 0)
    f.index.setFavorite([f.index.all()[3].id], true)
    await delay(80)
    assert.deepEqual(f.normal(f.mirror.snap as LibrarySnapshot), f.normal(f.index.snapshot()))
    assert.equal(f.mirror.gaps, 0)
  } finally {
    await f.cleanup()
  }
})

test('a small library answers libraryState whole, with the push sequence', async () => {
  const f = await setup()
  try {
    await f.index.addFile(await f.write('one.mp3'), {space: 'music', managed: true})
    await f.settle()
    const state = f.index.stateResponse()
    assert.equal(state.items.length, 1)
    assert.equal(state.seq, f.mirror.seq)
  } finally {
    await f.cleanup()
  }
})
