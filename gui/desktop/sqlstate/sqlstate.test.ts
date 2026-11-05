// The SQL merge state against the in-memory one: random histories joined through SQL must read back as exactly the
// state the Kotlin OpLog holds (compared as canonical bytes), and the projection and songs must equal the Kotlin views.
// Not covered: the JVM/Android run of the same statements (phase 6), and equivocation (the log sets such ops aside; the
// SQL slice has no log, only the join of applied ops).
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {SyncLog, sqlCatalog, sqlStateBytes} from '../../shared/core/sync.js'
import {history, prng, shuffled} from './history.js'
import {SqlState} from './sqlstate.js'

/** Applies ops in random batches (and some repeated), in a random order. */
function applyScrambled(db: SqlState, ops: string[], rnd: () => number): void {
  const withRepeats = [...ops, ...ops.filter(() => rnd() < 0.2)]
  const order = shuffled(withRepeats, rnd)
  let i = 0
  while (i < order.length) {
    const n = 1 + Math.floor(rnd() * 15)
    db.applyOps(order.slice(i, i + n))
    i += n
  }
}

const sameBytes = (a: Uint8Array, b: Uint8Array) => Buffer.compare(a, b) === 0

test('sql state: random histories read back as exactly the in-memory state', () => {
  let applied = 0
  for (let seed = 1; seed <= 60; seed++) {
    const rnd = prng(seed * 7919)
    const {ops} = history(seed, 80 + (seed % 7) * 25)
    const log = SyncLog.create()
    for (const op of ops) log.admit(op)
    const expected = log.stateBodyBytes()

    // permutations, duplicates, batches
    const one = new SqlState(':memory:')
    applyScrambled(one, ops, rnd)
    const got = sqlStateBytes(one.readRows())
    assert.ok(sameBytes(got, expected), `seed ${seed}: the SQL state differs from the in-memory state`)

    // a partition: two replicas each see part of the history, then each receives the rest in its own order
    const left = ops.filter(() => rnd() < 0.6)
    const right = ops.filter(() => rnd() < 0.6)
    const a = new SqlState(':memory:')
    const b = new SqlState(':memory:')
    applyScrambled(a, left, rnd)
    applyScrambled(b, right, rnd)
    applyScrambled(a, ops, rnd)
    applyScrambled(b, ops, rnd)
    assert.ok(sameBytes(sqlStateBytes(a.readRows()), expected), `seed ${seed}: partition replica A differs`)
    assert.ok(sameBytes(sqlStateBytes(b.readRows()), expected), `seed ${seed}: partition replica B differs`)
    assert.deepEqual(a.readRows().findings.sort(), b.readRows().findings.sort())
    for (const db of [one, a, b]) db.close()
    applied += ops.length
  }
  assert.ok(applied > 5000)
})

test('sql state: the projection and the songs equal the in-memory views', () => {
  for (let seed = 101; seed <= 140; seed++) {
    const rnd = prng(seed * 31)
    const {ops, items, playlists} = history(seed, 150)
    const log = SyncLog.create()
    for (const op of ops) log.admit(op)
    const db = new SqlState(':memory:')
    applyScrambled(db, ops, rnd)
    const views = log.views()

    const byId = (list: {id: string}[]) => [...list].sort((x, y) => (x.id < y.id ? -1 : 1))
    assert.deepEqual(byId(db.readItems()), byId(views.items), `seed ${seed}: items`)

    // views for random keys, as a reader asks for them after a batch of remote changes
    const keys = items.filter(() => rnd() < 0.5)
    const partial = log.viewsFor({all: false, items: keys, playlists: [], spaces: [], sets: [], places: [], profile: false, findings: false})
    assert.deepEqual(byId(db.readSome(keys)), byId(partial.items), `seed ${seed}: items for random keys`)

    for (const playlist of playlists) {
      const expected = views.playlists.find((p) => p.id === playlist)?.songs ?? []
      assert.deepEqual(db.songs(playlist), expected, `seed ${seed}: songs of ${playlist}`)
    }
    db.close()
  }
})

test('sql state: a batch is one transaction, and a failing batch leaves nothing behind', () => {
  const {ops} = history(5, 60)
  const db = new SqlState(':memory:')
  db.applyOps(ops.slice(0, 30))
  const before = JSON.stringify(db.readRows())
  assert.throws(() => db.applyOps([...ops.slice(30), 'not an op at all'].map((o, i) => (i === 3 ? (undefined as unknown as string) : o))))
  assert.equal(JSON.stringify(db.readRows()), before)
  db.close()
})

test('sql state: the comparison catches a wrong join (each statement of the catalog matters)', () => {
  const catalog = sqlCatalog()
  const mutate = (id: number, from: string, to: string): Record<number, string> => {
    assert.ok(catalog[id].includes(from), `statement ${id} has no "${from}"`)
    return {[id]: catalog[id].replace(from, to)}
  }
  const mutations: [string, Record<number, string>][] = [
    ['register order reversed', mutate(2, 'WHERE (excluded.wall, excluded.counter, excluded.hactor, excluded.oactor, excluded.oseq) >', 'WHERE (excluded.wall, excluded.counter, excluded.hactor, excluded.oactor, excluded.oseq) <')],
    ['register ignores the op id', mutate(2, 'excluded.hactor, excluded.oactor, excluded.oseq) >', 'excluded.hactor, excluded.hactor, excluded.hactor) >')],
    ['entry keeps the larger op', mutate(6, '(excluded.actor, excluded.seq) <', '(excluded.actor, excluded.seq) >')],
    ['entry conflict not reported', {5: 'SELECT 1 WHERE 0'}],
  ]
  for (const [name, override] of mutations) {
    let caught = false
    for (let seed = 1; seed <= 40 && !caught; seed++) {
      const rnd = prng(seed * 7919)
      const {ops} = history(seed, 150)
      const log = SyncLog.create()
      for (const op of ops) log.admit(op)
      const db = new SqlState(':memory:', {catalogOverride: override})
      try {
        applyScrambled(db, ops, rnd)
        if (!sameBytes(sqlStateBytes(db.readRows()), log.stateBodyBytes())) caught = true
      } catch {
        caught = true
      }
      db.close()
    }
    assert.ok(caught, `the comparison did not notice: ${name}`)
  }
})
