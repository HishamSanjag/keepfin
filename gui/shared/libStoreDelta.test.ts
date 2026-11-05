// The renderer's library store applies deltas in place of the whole library (spec 10.2).
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {applyLibraryBatch, applyLibraryDelta, getLibrary, setLibrary} from '../web/library/libStore.js'
import {applyLibraryDelta as reference} from './libraryDelta.js'
import type {LibraryBatchPush, LibraryDeltaPush} from './contracts.js'
import type {Collection, LibraryItem, LibrarySnapshot} from './library.js'

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

const item = (id: string, space = 'music', extra: Partial<LibraryItem> = {}): LibraryItem => ({id, space, path: `C:\\${id}.mp3`, folder: '', kind: 'audio', title: id, size: 1, modifiedAt: 1, addedAt: 1, hasArt: false, ...extra})
const list = (id: string, itemIds: string[], kind: Collection['kind'] = 'own'): Collection => ({id, name: id, kind, space: 'music', itemIds, seen: [], createdAt: 1, updatedAt: 1})
const base = (seq: number, items: LibraryItem[], collections: Collection[] = []): LibrarySnapshot => ({seq, items, collections, scanning: false, problems: []})

/** What derive() would build from a snapshot: the lists the UI reads. */
function expectDerived(snapshot: LibrarySnapshot) {
  const data = getLibrary()
  assert.deepEqual(data.snapshot.items.map((entry) => entry.id), snapshot.items.map((entry) => entry.id))
  assert.deepEqual(JSON.parse(JSON.stringify(data.snapshot.items)), JSON.parse(JSON.stringify(snapshot.items)))
  assert.deepEqual(JSON.parse(JSON.stringify(data.snapshot.collections)), JSON.parse(JSON.stringify(snapshot.collections)))
  assert.equal(data.byId.size, snapshot.items.length)
  for (const entry of snapshot.items) assert.equal(data.byId.get(entry.id)?.title, entry.title)
  const spaces = new Set(snapshot.items.map((entry) => entry.space))
  for (const space of spaces) assert.deepEqual(data.bySpace.get(space)?.map((entry) => entry.id), snapshot.items.filter((entry) => entry.space === space).map((entry) => entry.id))
  for (const space of data.bySpace.keys()) assert.ok(spaces.has(space), `no empty space list for ${space}`)
  const jobs = new Map<string, string[]>()
  for (const entry of snapshot.items) if (entry.jobId) jobs.set(entry.jobId, [...(jobs.get(entry.jobId) ?? []), entry.id])
  assert.deepEqual([...data.byJob].map(([job, entries]) => [job, entries.map((entry) => entry.id)]).sort(), [...jobs].sort())
  assert.deepEqual(data.playlists.map((c) => c.id), snapshot.collections.filter((c) => c.kind === 'own').map((c) => c.id))
  assert.deepEqual(data.downloaded.map((c) => c.id), snapshot.collections.filter((c) => c.kind !== 'own').map((c) => c.id))
  const collected = new Set(snapshot.collections.filter((c) => c.kind !== 'own').flatMap((c) => c.itemIds))
  assert.deepEqual([...data.collectedIds].sort(), [...collected].sort())
}

test('deltas apply in the order a full snapshot has, and every derived list follows (seeded random deltas)', () => {
  for (const seed of [1, 2, 3]) {
    const rng = prng(seed)
    let truth = base(1, Array.from({length: 12}, (_, n) => item(`i${n}`, n % 3 === 0 ? 'videos' : 'music', n % 4 === 0 ? {jobId: `job${n % 2}`} : {})), [list('own1', ['i1', 'i2']), list('dl1', ['i3', 'i4'], 'playlist')])
    setLibrary(truth)
    let nextId = 100
    for (let step = 0; step < 60; step++) {
      const delta: LibraryDeltaPush = {type: 'libraryDelta', seq: truth.seq! + 1, base: truth.seq!}
      const roll = rng()
      if (roll < 0.35) {
        const target = truth.items[Math.floor(rng() * truth.items.length)]
        if (target) delta.items = [{...target, title: `edited ${step}`, favorite: rng() < 0.5}]
      } else if (roll < 0.55) delta.items = [item(`n${nextId++}`, rng() < 0.5 ? 'music' : 'videos', rng() < 0.3 ? {jobId: 'job0'} : {})]
      else if (roll < 0.7 && truth.items.length > 3) delta.removedItems = [truth.items[Math.floor(rng() * truth.items.length)].id]
      else if (roll < 0.8) {
        const target = truth.items[Math.floor(rng() * truth.items.length)]
        if (target) delta.items = [{...target, space: target.space === 'music' ? 'videos' : 'music'}]
      } else if (roll < 0.9) delta.collections = [list(`c${step}`, truth.items.slice(0, 2).map((entry) => entry.id), rng() < 0.5 ? 'own' : 'album')]
      else if (truth.collections.length) delta.removedCollections = [truth.collections[0].id]
      if (rng() < 0.3) delta.meta = {scanning: rng() < 0.5, problems: [], operation: rng() < 0.5 ? {kind: 'move', done: step, total: 60} : undefined}
      truth = reference(truth, delta)
      applyLibraryDelta(delta)
      expectDerived(truth)
      assert.equal(getLibrary().snapshot.scanning, truth.scanning)
    }
  }
})

test('entries keep their identity and only the touched ones are replaced', () => {
  const items = Array.from({length: 5}, (_, n) => item(`k${n}`))
  setLibrary(base(7, items))
  const before = new Map(getLibrary().snapshot.items.map((entry) => [entry.id, entry]))
  applyLibraryDelta({type: 'libraryDelta', seq: 8, base: 7, items: [{...items[2], title: 'changed'}]})
  const after = getLibrary().snapshot.items
  assert.equal(after[2].title, 'changed')
  for (const at of [0, 1, 3, 4]) assert.equal(after[at], before.get(items[at].id), 'untouched entries are the same objects')
})

test('only the changed items’ own subscribers hear about a delta', async () => {
  const {useLibItem} = await import('../web/library/libStore.js')
  void useLibItem
  const items = Array.from({length: 4}, (_, n) => item(`s${n}`))
  setLibrary(base(20, items))
  // The per-item listeners are internal; the observable guarantee is that unchanged entries are the same objects and the
  // data object changes once per delta.
  const data = getLibrary()
  applyLibraryDelta({type: 'libraryDelta', seq: 21, base: 20, items: [{...items[1], favorite: true}]})
  assert.notEqual(getLibrary(), data)
  assert.equal(getLibrary().byId.get('s0'), items[0])
  assert.equal(getLibrary().byId.get('s1')?.favorite, true)
})

test('a delta that does not follow the one before is dropped, never applied', () => {
  const items = [item('g0'), item('g1')]
  setLibrary(base(30, items))
  applyLibraryDelta({type: 'libraryDelta', seq: 32, base: 31, items: [item('late')]})
  assert.deepEqual(getLibrary().snapshot.items.map((entry) => entry.id), ['g0', 'g1'])
  assert.equal(getLibrary().snapshot.seq, 30)
  // The next one that does follow still applies.
  applyLibraryDelta({type: 'libraryDelta', seq: 31, base: 30, items: [item('ok')]})
  assert.deepEqual(getLibrary().snapshot.items.map((entry) => entry.id), ['g0', 'g1', 'ok'])
})

test('batches are put together and shown when the last one has arrived', () => {
  const all = Array.from({length: 23}, (_, n) => item(`b${n}`))
  const batches: LibraryBatchPush[] = []
  for (let at = 0; at < all.length; at += 10) {
    const index = at / 10
    batches.push({type: 'libraryBatch', seq: 50, index, total: 3, items: all.slice(at, at + 10), ...(index === 0 ? {collections: [list('bl', ['b1'])], meta: {scanning: true, problems: []}} : {})})
  }
  applyLibraryBatch(batches[0])
  applyLibraryBatch(batches[1])
  assert.notEqual(getLibrary().snapshot.items.length, 23, 'nothing is shown half-way')
  applyLibraryBatch(batches[2])
  assert.equal(getLibrary().snapshot.items.length, 23)
  assert.equal(getLibrary().snapshot.seq, 50)
  assert.equal(getLibrary().snapshot.scanning, true)
  assert.equal(getLibrary().playlists.length, 1)
  // Deltas continue from the batch's sequence.
  applyLibraryDelta({type: 'libraryDelta', seq: 51, base: 50, removedItems: ['b0']})
  assert.equal(getLibrary().byId.has('b0'), false)
})
