// The views read from the SQL tables against the in-memory log: full views, partial views for random keys, the keys an
// admitted batch touched, what a delete must observe, and the checkpoint parts written from the tables (byte for byte).
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {mkdtempSync, rmSync} from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {CHECKPOINT_SHARDS, SyncLog, makeOp, type SyncPartialViews, type SyncTouched, type SyncViews} from '../../shared/core/sync.js'
import {history, prng, shuffled} from './history.js'
import {SqlState} from './sqlstate.js'

const LIBRARY = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'

const byId = <T extends {id: string}>(list: T[]) => [...list].sort((x, y) => (x.id < y.id ? -1 : 1))

function normalise<T extends Partial<SyncViews & SyncPartialViews>>(views: T): T {
  const out: Record<string, unknown> = {...views}
  for (const key of ['items', 'playlists', 'spaces'] as const) if (views[key]) out[key] = byId(views[key] as {id: string}[])
  if (views.findings) out.findings = [...views.findings].sort()
  if (views.goneItems) out.goneItems = [...views.goneItems].sort()
  return out as T
}

/** What a reader does with partial views: replaces the keys they carry. After every batch it must equal the full views. */
function applyPartial(model: SyncViews, part: SyncPartialViews, touched: SyncTouched): SyncViews {
  const next: SyncViews = JSON.parse(JSON.stringify(model))
  const replace = <T extends {id: string}>(list: T[], keys: string[], fresh: T[]) => byId([...list.filter((x) => !keys.includes(x.id)), ...fresh])
  next.items = replace(next.items, touched.items, part.items)
  next.playlists = replace(next.playlists, touched.playlists, part.playlists)
  next.spaces = replace(next.spaces, touched.spaces, part.spaces)
  for (const name of touched.sets) {
    if (part.sets[name]?.length) next.sets[name] = part.sets[name]
    else delete next.sets[name]
  }
  for (const id of touched.items) {
    if (part.placements[id]) next.placements[id] = part.placements[id]
    else delete next.placements[id]
  }
  for (const id of touched.places) {
    if (part.places[id]) next.places[id] = part.places[id]
    else delete next.places[id]
  }
  if (part.profile) next.profile = part.profile
  if (part.findings) next.findings = part.findings
  return next
}

/** Admits the ops in random batches into both, in the same random order, and compares what each batch touched. */
function feed(log: SyncLog, db: SqlState, ops: string[], rnd: () => number, compare: (touched: {log: SyncTouched; db: SyncTouched}) => void): void {
  const order = shuffled([...ops, ...ops.filter(() => rnd() < 0.15)], rnd)
  log.takeTouched()
  db.takeTouched()
  let at = 0
  while (at < order.length) {
    const batch = order.slice(at, at + 1 + Math.floor(rnd() * 12))
    at += batch.length
    for (const op of batch) log.admit(op)
    db.admit(batch)
    compare({log: log.takeTouched(), db: db.takeTouched()})
  }
}

test('sql views: full views, partial views and what a delete observes equal the in-memory log', () => {
  for (let seed = 201; seed <= 250; seed++) {
    const rnd = prng(seed * 17)
    const {ops, items, playlists} = history(seed, 120 + (seed % 5) * 40)
    const log = SyncLog.create()
    const db = new SqlState(':memory:')
    let model = normalise(db.views())
    feed(log, db, ops, rnd, ({log: expected, db: got}) => {
      // The SQL state reports the keys whose views changed (the log also names a playlist for an entry that lost a conflict),
      // gives the same views for them as the log, and a reader that applies them stays equal to the full views.
      for (const key of ['items', 'sets', 'spaces', 'places'] as const) {
        for (const id of expected[key]) assert.ok(got[key].includes(id), `seed ${seed}: ${key} ${id} was not reported as touched`)
      }
      assert.ok(got.profile || !expected.profile, `seed ${seed}: profile`)
      assert.ok(got.findings || !expected.findings, `seed ${seed}: findings`)
      const part = db.viewsFor(got)
      assert.deepEqual(normalise(part), normalise(log.viewsFor(got)), `seed ${seed}: partial views for the touched keys`)
      model = normalise(applyPartial(model, part, got))
      assert.deepEqual(model, normalise(db.views()), `seed ${seed}: the reader's copy fell behind the full views`)
    })
    assert.deepEqual(normalise(db.views()), normalise(log.views()), `seed ${seed}: full views`)

    // partial views for random keys of every kind
    const pick = (list: string[]) => list.filter(() => rnd() < 0.5)
    const views = log.views()
    const touched: SyncTouched = {
      all: false,
      items: pick([...items, '00000000-0000-4000-8000-00000000dead']),
      playlists: pick([...playlists, 'no-such-playlist']),
      spaces: pick(views.spaces.map((s) => s.id)),
      sets: pick([...Object.keys(views.sets), 'favourites', 'tags:x', 'nothing']),
      places: pick(Object.keys(views.places)),
      profile: rnd() < 0.5,
      findings: rnd() < 0.5,
    }
    assert.deepEqual(normalise(db.viewsFor(touched)), normalise(log.viewsFor(touched)), `seed ${seed}: partial views for random keys`)
    assert.deepEqual(normalise(db.viewsFor({...touched, all: true})), normalise(log.viewsFor({...touched, all: true})), `seed ${seed}: all`)

    // what a delete or remove must observe
    const query = {items: items.slice(0, 4), playlists, spaces: views.spaces.map((s) => s.id), setElements: [['favourites', 'e1'], ['tags:x', 'e2'], ['favourites', 'e9']] as [string, string][]}
    assert.deepEqual(db.observed(query), log.observed(query), `seed ${seed}: observed`)
    db.close()
  }
})

test('sql views: the checkpoint parts written from the tables are the bytes the in-memory log writes', async () => {
  let compared = 0
  for (let seed = 301; seed <= 330; seed++) {
    const rnd = prng(seed * 29)
    const {ops} = history(seed, 100 + (seed % 4) * 50)
    const log = SyncLog.create()
    const db = new SqlState(':memory:')
    feed(log, db, ops, rnd, () => undefined)
    const coverage = db.checkpointCoverage()
    assert.deepEqual(coverage, log.versionVector(), `seed ${seed}: coverage`)
    for (const shard of CHECKPOINT_SHARDS) {
      const [expected] = log.checkpointParts(LIBRARY, [shard])
      const got = await db.partBytes(LIBRARY, shard, coverage!)
      assert.ok(Buffer.compare(got, expected.bytes) === 0, `seed ${seed}: part ${shard}`)
      assert.ok(Buffer.compare(db.partBytesViaCore(LIBRARY, shard, coverage!), expected.bytes) === 0, `seed ${seed}: part ${shard} through the core`)
      compared++
    }
    db.close()
  }
  assert.equal(compared, 30 * CHECKPOINT_SHARDS.length)
})

test('sql views: after an equivocation the rebuilt state, its views and its parts equal the in-memory log', async () => {
  for (let seed = 401; seed <= 412; seed++) {
    const rnd = prng(seed * 13)
    const {ops, specs, items} = history(seed, 120)
    const spec = specs[40 + (seed % 20)]
    const variant = makeOp(spec.actor, spec.seq, spec.hlc, 'item.set', {item: items[0], field: 'title', value: 'a second version'})
    const everything = shuffled([...ops, variant], rnd)
    const log = SyncLog.create()
    for (const op of everything) log.admit(op)
    const dir = mkdtempSync(path.join(os.tmpdir(), 'keepfin-equiv-'))
    const db = new SqlState(path.join(dir, 'state.db'))
    try {
      db.admit(everything)
      assert.ok(db.rebuildRequired, `seed ${seed}: the second version was not noticed`)
      db.rebuild(everything)
      assert.deepEqual(db.setAside(), log.setAside(), `seed ${seed}: set-aside range`)
      assert.deepEqual(db.versionVector(), log.versionVector(), `seed ${seed}: version vector`)
      assert.deepEqual(normalise(db.views()), normalise(log.views()), `seed ${seed}: views`)
      assert.deepEqual(db.findings(), [...log.findings()].sort(), `seed ${seed}: findings`)
      // The header clock may differ (the log ignores the set-aside ops there, the tables do not), so the parts are compared
      // by the state a reader gets from them.
      const coverage = db.checkpointCoverage()
      const fromTables = SyncLog.fromCheckpointParts(await Promise.all(CHECKPOINT_SHARDS.map((shard) => db.partBytes(LIBRARY, shard, coverage!))), LIBRARY, db.setAside())
      const fromLog = SyncLog.fromCheckpointParts(log.checkpointParts(LIBRARY, [...CHECKPOINT_SHARDS]).map((part) => part.bytes), LIBRARY, log.setAside())
      assert.ok(fromTables && fromLog, `seed ${seed}: the parts were not usable`)
      assert.ok(Buffer.compare(fromTables!.stateBodyBytes(), fromLog!.stateBodyBytes()) === 0, `seed ${seed}: the state read from the parts`)
      assert.deepEqual(fromTables!.versionVector(), fromLog!.versionVector(), `seed ${seed}: coverage of the parts`)
    } finally {
      db.close()
      rmSync(dir, {recursive: true, force: true})
    }
  }
})

test('sql views: ops held above a gap hold the checkpoint back', () => {
  const {ops} = history(9, 120)
  const db = new SqlState(':memory:')
  // leave out every second op of the first actor: later ones wait above a gap
  const actor = ops[0].match(/"id":"([^:"]+):/)?.[1]
  const withGap = ops.filter((op, i) => !(i % 2 === 0 && actor && op.includes(`"id":"${actor}:`)))
  db.admit(withGap)
  assert.ok(Object.keys(db.pending()).length > 0)
  assert.equal(db.checkpointCoverage(), null)
  db.admit(ops)
  assert.deepEqual(db.pending(), {})
  assert.ok(db.checkpointCoverage())
  db.close()
})
