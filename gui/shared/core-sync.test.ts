// The shared core's sync adapter (gui/shared/core/sync.ts): ops made and admitted through the JavaScript build of
// the core, files round trips, clocks, order keys, and a timing run at library scale. The semantics themselves are
// proved in the core's own tests (core/src/commonTest/.../sync) on the JVM and Node.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {
  FORMAT, SEMANTICS, SyncLog, decodeCheckpoint, decodeSegment, encodeSegment, hlcReceive, hlcTick, isValidKey,
  keyBetween, makeOp, placeInTie, SyncOpError, type SyncJson,
} from './core/sync.js'

const LIBRARY = randomUUID()
const A = randomUUID()
const B = randomUUID()
const PLACE = randomUUID()

/** Makes ops for one actor with rising clocks. */
function writer(actor: string) {
  let seq = 0
  let last: string | null = null
  return (kind: string, body: Record<string, SyncJson>): string => {
    seq += 1
    last = hlcTick(last, 1_700_000_000_000, actor)
    return makeOp(actor, seq, last, kind, body)
  }
}

function sampleOps() {
  const a = writer(A)
  const b = writer(B)
  const item = randomUUID()
  const item2 = randomUUID()
  const playlist = randomUUID()
  const entry = randomUUID()
  const space = randomUUID()
  const ops: string[] = []
  ops.push(a('item.add', {item, fields: {media: 'audio', title: 'Café \u{1F600}'}}))
  ops.push(a('item.revise', {item, content: 'sha256:' + '1'.repeat(64), size: 10, parents: []}))
  ops.push(b('item.revise', {item, content: 'sha256:' + '2'.repeat(64), size: 20, parents: []})) // concurrent: a content conflict
  ops.push(a('item.add', {item: item2, fields: {media: 'video'}}))
  ops.push(b('item.set', {item: item2, field: 'title', value: 'Clip'}))
  ops.push(a('item.place', {item, place: PLACE, path: 'Music/a.mp3'}))
  ops.push(a('set.add', {set: 'favourites', element: item}))
  ops.push(b('playlist.add', {playlist, fields: {name: 'Mix', kind: 'playlist'}}))
  ops.push(b('entry.insert', {playlist, entry, item, key: keyBetween(null, null)}))
  ops.push(a('space.add', {space, fields: {name: 'Gym'}}))
  ops.push(b('profile.set', {field: 'name', value: 'Hisham'}))
  ops.push(a('item.set', {item: item2, field: `plays@${A}`, value: 3}))
  ops.push(b('item.set', {item: item2, field: `plays@${B}`, value: 4}))
  ops.push(b('future.thing', {x: 1}))
  return {ops, item, item2, playlist, entry, space}
}

function shuffled<T>(list: T[], seed: number): T[] {
  const out = [...list]
  let state = seed
  for (let i = out.length - 1; i > 0; i--) {
    state = (state * 1103515245 + 12345) % 2147483648
    const j = state % (i + 1)
    ;[out[i], out[j]] = [out[j], out[i]]
  }
  return out
}

test('core sync: the same ops in different orders give identical views', () => {
  const {ops, item, item2, playlist, entry, space} = sampleOps()
  const one = SyncLog.create()
  for (const op of ops) assert.ok(['admitted', 'kept'].includes(one.admit(op).kind))
  const two = SyncLog.create()
  for (const op of shuffled([...ops, ...ops.slice(0, 5)], 7)) two.admit(op)
  assert.equal(one.viewsJson(), two.viewsJson())
  assert.deepEqual(one.versionVector(), two.versionVector())
  const views = one.views()
  const first = views.items.find((i) => i.id === item)!
  assert.equal(first.status, 'present')
  assert.equal(first.contentConflict, true)
  assert.equal(first.heads.length, 2)
  assert.equal(first.fields.title, 'Café \u{1F600}')
  const second = views.items.find((i) => i.id === item2)!
  assert.equal(second.plays, 7)
  assert.equal(second.fields.title, 'Clip')
  assert.equal(views.playlists[0].id, playlist)
  assert.deepEqual(views.playlists[0].songs.map((s) => s.entry), [entry])
  assert.equal(views.spaces[0].id, space)
  assert.deepEqual(views.sets.favourites, [item])
  assert.equal(views.placements[item][PLACE], 'Music/a.mp3')
  assert.equal(views.profile.name, 'Hisham')
  assert.deepEqual(one.versionVector(), {[A]: 7, [B]: 7})
  assert.equal(one.admit(ops[0]).kind, 'duplicate')
})

test('core sync: admission codes cover invalid, kept and malformed ops', () => {
  const log = SyncLog.create()
  const a = writer(A)
  assert.equal(log.admit(a('future.thing', {x: 1})).kind, 'kept')
  // an invalid body cannot be made through makeOp, but arrives from other apps as text
  const sorted = '{"body":{"field":"f","item":"x","value":null},"hlc":"' + hlcTick(null, 1, A) + '","id":"' + A + ':2","kind":"item.set"}'
  const result = log.admit(sorted)
  assert.equal(result.kind, 'invalid')
  assert.equal(log.admit('not json').kind, 'malformed')
  assert.deepEqual(log.versionVector(), {[A]: 2})
  assert.ok(log.findings().includes(`invalid:${A}:2`))
  assert.throws(() => makeOp(A, 1, hlcTick(null, 1, A), 'item.set', {item: 'nope', field: 'f', value: 1}), SyncOpError)
  assert.throws(() => makeOp(A, 1, hlcTick(null, 1, A), 'entry.move', {entry: randomUUID(), key: 'a00'}), SyncOpError)
})

test('core sync: a segment round-trips and rejections come back as codes', () => {
  const {ops} = sampleOps()
  const aOps = ops.filter((o) => o.includes(`"id":"${A}:`))
  const bytes = encodeSegment(LIBRARY, A, aOps)
  assert.ok(bytes instanceof Uint8Array)
  assert.ok(new TextDecoder().decode(bytes).startsWith('KFSEG 1\n'))
  const decoded = decodeSegment(bytes, LIBRARY)
  assert.ok(decoded.ok)
  if (decoded.ok) {
    assert.deepEqual(decoded.ops, aOps)
    assert.deepEqual([decoded.actor, decoded.first, decoded.last], [A, 1, aOps.length])
  }
  const wrong = decodeSegment(bytes, randomUUID())
  assert.ok(!wrong.ok && wrong.error === 'wrongLibrary')
  const flipped = bytes.slice()
  flipped[flipped.length - 5] ^= 1
  const bad = decodeSegment(flipped, LIBRARY)
  assert.ok(!bad.ok && bad.error === 'checksum')
  const newer = new TextEncoder().encode('KFSEG 3\n{}\n')
  const n = decodeSegment(newer, LIBRARY)
  assert.ok(!n.ok && n.error === 'newerFormat')
  assert.throws(() => encodeSegment(LIBRARY, B, aOps))
})

test('core sync: a checkpoint round-trips through fromCheckpoint with the same views', () => {
  const {ops} = sampleOps()
  const full = SyncLog.create()
  for (const op of ops) full.admit(op)
  const bytes = full.checkpointBytes(LIBRARY)
  const cp = decodeCheckpoint(bytes, LIBRARY)
  assert.ok(cp.ok, String(cp.error))
  assert.deepEqual(cp.coverage(), full.versionVector())
  assert.equal(cp.maxHlc(), full.maxHlc())
  const fresh = SyncLog.fromCheckpoint(cp)
  assert.ok(fresh)
  assert.equal(fresh.viewsJson(), full.viewsJson())
  for (const op of ops) assert.equal(fresh.admit(op).kind, 'covered')
  assert.deepEqual(Array.from(fresh.checkpointBytes(LIBRARY)), Array.from(bytes))
  // an op above the coverage is applied on top
  const more = writer(randomUUID())('profile.set', {field: 'colour', value: 'blue'})
  assert.equal(fresh.admit(more).kind, 'admitted')
  assert.equal(fresh.views().profile.colour, 'blue')
  // an equivocation at or below the coverage makes the checkpoint unusable
  assert.equal(SyncLog.fromCheckpoint(cp, {[A]: 1}), null)
  assert.ok(SyncLog.fromCheckpoint(cp, {[A]: 100}))
  // failures
  assert.ok(!decodeCheckpoint(bytes, randomUUID()).ok)
  assert.equal(decodeCheckpoint(bytes, randomUUID()).error, 'wrongLibrary')
  assert.equal(SyncLog.fromCheckpoint(decodeCheckpoint(new Uint8Array(3), LIBRARY)), null)
})

test('core sync: clocks tick and receive', () => {
  const t1 = hlcTick(null, 100, A)
  assert.equal(t1, `0000000000100.000000.${A}`)
  const t2 = hlcTick(t1, 100, A)
  assert.equal(t2, `0000000000100.000001.${A}`)
  assert.equal(hlcTick(t2, 50, A), `0000000000100.000002.${A}`)
  assert.equal(hlcTick(t2, 200, A), `0000000000200.000000.${A}`)
  assert.equal(hlcReceive(t2, `0000000000100.000009.${B}`, 100, A), `0000000000100.000010.${A}`)
  assert.equal(hlcReceive(null, `0000000000299.000007.${B}`, 300, A), `0000000000300.000000.${A}`)
  assert.throws(() => hlcTick('garbage', 1, A))
  assert.throws(() => hlcTick(`9999999999999.999999.${A}`, 1, A))
  assert.deepEqual([FORMAT, SEMANTICS], [1, 1])
})

test('core sync: fractional keys follow the reference vectors', () => {
  const cases: [string | null, string | null, string][] = [
    [null, null, 'a0'], ['a0', null, 'a1'], [null, 'a0', 'Zz'], ['a0', 'a1', 'a0V'], ['a0', 'a0V', 'a0G'],
    [null, 'a0V', 'a0'], ['a0V', null, 'a1'], ['a1', 'a2', 'a1V'], ['Zz', 'a0', 'ZzV'], ['a0V', 'a1', 'a0l'], ['az', null, 'b00'],
    ['z'.repeat(27), null, 'z'.repeat(27) + 'V'],
  ]
  for (const [a, b, expected] of cases) assert.equal(keyBetween(a, b), expected, `keyBetween(${a}, ${b})`)
  assert.throws(() => keyBetween('a0', 'a0'))
  assert.throws(() => keyBetween('a1', 'a0'))
  assert.throws(() => keyBetween('a00', null))
  assert.equal(isValidKey('a10'), false)
  assert.equal(isValidKey('a0V'), true)
  const placement = placeInTie('a1', 'a2', ['t2', 't3'])
  assert.deepEqual(placement.moves.map((m) => m.entry), ['t2', 't3'])
  assert.ok('a1' < placement.newKey && placement.newKey < placement.moves[0].key && placement.moves[0].key < placement.moves[1].key && placement.moves[1].key < 'a2')
})

test('core sync: 20,000 items make, admit and project in time', () => {
  const started = Date.now()
  const count = 20_000
  const actor = randomUUID()
  const ops: string[] = new Array(count)
  let last: string | null = null
  for (let i = 0; i < count; i++) {
    last = hlcTick(last, 1_700_000_000_000 + i, actor)
    ops[i] = makeOp(actor, i + 1, last, 'item.add', {item: randomUUID(), fields: {media: 'audio', title: `Track ${i}`, artist: `Artist ${i % 500}`, album: `Album ${i % 2000}`, duration: 180 + (i % 100)}})
  }
  const made = Date.now()
  const log = SyncLog.create()
  for (const op of ops) {
    const result = log.admit(op)
    if (result.kind !== 'admitted') assert.fail(`unexpected ${result.kind}`)
  }
  const admitted = Date.now()
  const json = log.viewsJson()
  const projected = Date.now()
  const views = JSON.parse(json)
  assert.equal(views.items.length, count)
  const checkpoint = log.checkpointBytes(LIBRARY)
  const saved = Date.now()
  const reloaded = SyncLog.fromCheckpoint(decodeCheckpoint(checkpoint, LIBRARY))!
  assert.equal(reloaded.viewsJson(), json)
  const done = Date.now()
  console.log(
    `core sync timing for ${count} items: make ${made - started} ms, admit ${admitted - made} ms, viewsJson ${projected - admitted} ms (${(json.length / 1e6).toFixed(1)} MB), ` +
      `checkpoint ${saved - projected} ms (${(checkpoint.length / 1e6).toFixed(1)} MB), reload ${done - saved} ms, total ${done - started} ms`,
  )
  assert.ok(done - started < 15_000, `the whole run took ${done - started} ms`)
})

// ---- incremental reading and checkpoint parts ---------------------------------------------------------------------------

test('core sync: takeTouched and viewsFor report only what changed', () => {
  const a = writer(A)
  const item = randomUUID()
  const playlist = randomUUID()
  const entry = randomUUID()
  const log = SyncLog.create()
  for (const op of [
    a('item.add', {item, fields: {media: 'audio', title: 'One'}}),
    a('playlist.add', {playlist}),
    a('entry.insert', {playlist, entry, item, key: 'a0'}),
  ]) log.admit(op)
  let touched = log.takeTouched()
  assert.deepEqual([touched.all, touched.items, touched.playlists], [false, [item], [playlist]])
  let partial = log.viewsFor(touched)
  assert.equal(partial.items[0].id, item)
  assert.deepEqual(partial.playlists[0].songs.map((s) => s.item), [item])
  assert.deepEqual(partial.goneItems, [])
  // a field edit touches the item only
  log.admit(a('item.set', {item, field: 'title', value: 'Two'}))
  touched = log.takeTouched()
  assert.deepEqual([touched.items, touched.playlists], [[item], []])
  assert.equal(log.viewsFor(touched).items[0].fields.title, 'Two')
  // deleting the item changes the playlist's songs
  const observed = log.observed({items: [item]})
  assert.equal(observed.items[item].tags.length, 1)
  log.admit(a('item.delete', {item, observedTags: observed.items[item].tags, observedHeads: observed.items[item].heads}))
  touched = log.takeTouched()
  assert.deepEqual(touched.playlists, [playlist])
  partial = log.viewsFor(touched)
  assert.deepEqual(partial.playlists[0].songs, [])
  assert.equal(partial.items[0].status, 'deleted')
  assert.deepEqual(log.observed({items: [item]}).items[item].tags, [])
  // nothing changed: nothing touched
  assert.deepEqual(log.takeTouched().items, [])
  // everything after a checkpoint load
  const loaded = SyncLog.fromCheckpoint(decodeCheckpoint(log.checkpointBytes(LIBRARY), LIBRARY))!
  const all = loaded.takeTouched()
  assert.equal(all.all, true)
  assert.deepEqual(loaded.viewsFor(all).items.map((i) => i.id), [item])
})

test('core sync: checkpoint parts round trip, and stale parts plus replay equal a full replay', () => {
  const {ops} = sampleOps()
  const full = SyncLog.create()
  for (const op of ops) full.admit(op)
  const parts = full.checkpointParts(LIBRARY)
  assert.equal(parts.length, 17)
  assert.deepEqual(parts.map((p) => p.shard), ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', 'meta'])
  assert.deepEqual(full.checkpointParts(LIBRARY), []) // nothing changed since
  const restored = SyncLog.fromCheckpointParts(parts.map((p) => p.bytes), LIBRARY)!
  assert.equal(restored.viewsJson(), full.viewsJson())
  // a stale set: parts of the first half, a missing shard, then the whole replay
  const half = SyncLog.create()
  for (const op of ops.slice(0, Math.floor(ops.length / 2))) half.admit(op)
  const stale = half.checkpointParts(LIBRARY).filter((p) => p.shard !== 'meta')
  const log = SyncLog.fromCheckpointParts(stale.map((p) => p.bytes), LIBRARY)!
  for (const op of ops) log.admit(op)
  assert.equal(log.viewsJson(), full.viewsJson())
  // only a changed shard is written again
  const item = randomUUID()
  full.admit(makeOp(A, 1000, hlcTick(null, 1_700_000_000_000, A), 'item.add', {item, fields: {media: 'audio'}}))
  const dirty = full.checkpointParts(LIBRARY)
  assert.deepEqual(dirty.map((p) => p.shard), [item[0]])
  assert.throws(() => full.checkpointParts(LIBRARY, ['nope']))
  // a part is not a checkpoint
  assert.equal(decodeCheckpoint(parts[0].bytes, LIBRARY).ok, false)
})

test('core sync: the scale run (20,000 items; KEEPFIN_SCALE=200000 for the full library)', () => {
  const n = Number(process.env.KEEPFIN_SCALE ?? 20_000)
  const full = n >= 200_000
  const playlists = Math.max(1, Math.floor(n / 100))
  const actor = randomUUID()
  const ids = (i: number, kind: number) => ((i * 2654435761) % 4294967296).toString(16).padStart(8, '0') + '-' + kind.toString(16).padStart(4, '0') + '-4000-8000-000000000001'
  let seq = 0
  let last: string | null = null
  const make = (kind: string, body: Record<string, SyncJson>) => {
    seq += 1
    last = hlcTick(last, 1_700_000_000_000 + seq, actor)
    return makeOp(actor, seq, last, kind, body)
  }
  let ops: string[] | null = []
  for (let p = 0; p < playlists; p++) ops.push(make('playlist.add', {playlist: ids(p, 2), fields: {name: `Playlist ${p}`}}))
  for (let i = 0; i < n; i++) ops.push(make('item.add', {item: ids(i, 1), fields: {media: 'audio', title: `Track ${i}`, artist: `Artist ${i % 500}`, album: `Album ${i % 2000}`, duration: 180 + (i % 100)}}))
  for (let i = 0; i < n; i++) ops.push(make('entry.insert', {playlist: ids(i % playlists, 2), entry: ids(i, 3), item: ids(i, 1), key: 'a0'}))
  const edits: string[] = []
  for (let k = 0; k < 101; k++) edits.push(make('item.set', {item: ids((k * 7919) % n, 1), field: 'title', value: `Edited ${k}`}))

  const ms: Record<string, number> = {}
  const time = <T>(name: string, f: () => T): T => {
    const s = performance.now()
    const r = f()
    ms[name] = Math.round(performance.now() - s)
    return r
  }
  let log: SyncLog | null = SyncLog.create()
  const live = log
  const all = ops
  time('admit all', () => { for (const op of all) live.admit(op) })
  time('takeTouched (bulk)', () => live.takeTouched())
  for (let k = 0; k < 100; k++) live.admit(edits[k])
  const partial = time('takeTouched+viewsFor(100)', () => live.viewsFor(live.takeTouched()))
  assert.equal(partial.items.length, 100)
  const parts = time('checkpointParts all', () => live.checkpointParts(LIBRARY))
  const biggest = Math.max(...parts.map((p) => p.bytes.length))
  assert.ok(biggest < 64 * 1024 * 1024, `a part is ${biggest} bytes`)
  live.admit(edits[100])
  const dirty = time('checkpointParts 1 dirty', () => live.checkpointParts(LIBRARY))
  assert.equal(dirty.length, 1)
  const byShard = new Map(parts.map((p) => [p.shard, p.bytes]))
  byShard.set(dirty[0].shard, dirty[0].bytes)
  const files = [...byShard.values()]
  log = null
  ops = null
  const loaded = time('fromCheckpointParts', () => SyncLog.fromCheckpointParts(files, LIBRARY))
  assert.ok(loaded)
  console.log(
    `core sync parts/partial timing for ${n} items: ` + Object.entries(ms).map(([k, v]) => `${k} ${v} ms`).join(', ') +
      `; largest part ${(biggest / 1e6).toFixed(1)} MB`,
  )
  assert.ok(ms['takeTouched+viewsFor(100)'] < (full ? 25 : 50), 'viewsFor is too slow')
  assert.ok(ms['checkpointParts 1 dirty'] < (full ? 2500 : 1000), 'one dirty part is too slow')
  assert.ok(ms['fromCheckpointParts'] < (full ? 10_000 : 2000), 'reload from parts is too slow')
})
