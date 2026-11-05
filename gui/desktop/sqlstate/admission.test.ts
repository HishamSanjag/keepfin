// SQL-backed admission against the in-memory OpLog (sync spec section 4): the same admission codes, version vector,
// pending seqs, set-aside ranges, findings, clock and state bytes for random histories with duplicates, equivocations,
// invalid, unknown and malformed ops, before and after a bulk load from checkpoint parts.
// Not covered: Android's run of the same SQL (phase 6).
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {mkdtempSync, rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {SyncLog, makeOp, sqlStateBytes, type AdmitResult} from '../../shared/core/sync.js'
import {history, prng, shuffled, type OpSpec} from './history.js'
import {SqlState} from './sqlstate.js'

const LIBRARY = '00000000-0000-0000-0000-0000000000aa'

const codeOf = (r: AdmitResult): string => (r.kind === 'invalid' ? `invalid:${r.reason}` : r.kind === 'malformed' ? `malformed:${r.problem}` : r.kind)

/** The same op id with other bytes (a later clock): an equivocation. */
function variantOf(spec: OpSpec): string {
  const [wall, counter, actor] = [spec.hlc.slice(0, 13), Number(spec.hlc.slice(14, 20)), spec.hlc.slice(21)]
  return makeOp(spec.actor, spec.seq, `${wall}.${String(counter + 7).padStart(6, '0')}.${actor}`, spec.kind, spec.body)
}

/** Lines the log must judge invalid or malformed. */
function badLines(actor: string, other: string, base: number, sample: string): string[] {
  const hlc = (a: string) => `1700000000000.000000.${a}`
  return [
    `{"body":{"item":5},"hlc":"${hlc(actor)}","id":"${actor}:${base}","kind":"item.set"}`, // wrong types: invalid
    `{"body":{"field":"n","value":1},"hlc":"${hlc(other)}","id":"${actor}:${base + 1}","kind":"profile.set"}`, // clock of another actor: invalid
    `{"body":{},"hlc":"${hlc(actor)}","id":"${actor}:${base + 2}","kind":"future.thing"}`, // unknown kind: kept
    'not json at all',
    sample.replace('{"body":', '{ "body":'), // not canonical
    '{"body":{}}',
  ]
}

interface Delivery {
  ops: string[]
  all: string[]
}

/** Every op once, shuffled, with repeats, equivocating variants and bad lines mixed in. */
function delivery(seed: number, rnd: () => number, count: number): Delivery & {actors: string[]} {
  const h = history(seed, count)
  const actors = [...new Set(h.specs.map((s) => s.actor))]
  const variants = h.specs.filter(() => rnd() < 0.1).map(variantOf)
  const bad = badLines(actors[0], actors[1] ?? actors[0], 5000, h.ops[0])
  const all = [...h.ops, ...variants]
  const ops = shuffled([...h.ops, ...h.ops.filter(() => rnd() < 0.2), ...variants, ...bad], rnd)
  return {ops, all: [...all, ...bad], actors}
}

function batches<T>(list: T[], rnd: () => number): T[][] {
  const out: T[][] = []
  for (let i = 0; i < list.length; ) {
    const n = 1 + Math.floor(rnd() * 25)
    out.push(list.slice(i, i + n))
    i += n
  }
  return out
}

/** Everything the log and the SQL state both expose must agree. */
function assertSame(log: SyncLog, db: SqlState, all: string[], label: string): void {
  if (db.rebuildRequired) db.rebuild(all)
  assert.deepEqual(db.versionVector(), log.versionVector(), `${label}: version vector`)
  assert.deepEqual(db.pending(), log.pending(), `${label}: pending`)
  assert.deepEqual(db.setAside(), log.setAside(), `${label}: set-aside`)
  assert.deepEqual(db.findings(), [...log.findings()].sort(), `${label}: findings`)
  assert.equal(db.maxHlc(), log.maxHlc(), `${label}: max clock`)
  const sql = Buffer.from(sqlStateBytes(db.readRows())).toString()
  const mem = Buffer.from(log.stateBodyBytes()).toString()
  if (sql !== mem) {
    // say where the two states differ
    const a = JSON.parse(sql) as Record<string, Record<string, unknown>>
    const b = JSON.parse(mem) as Record<string, Record<string, unknown>>
    for (const section of Object.keys(b)) {
      for (const key of new Set([...Object.keys(a[section] ?? {}), ...Object.keys(b[section] ?? {})])) {
        const x = JSON.stringify((a[section] as Record<string, unknown>)?.[key])
        const y = JSON.stringify((b[section] as Record<string, unknown>)?.[key])
        if (x !== y) assert.fail(`${label}: state differs in ${section}.${key}\n  sql ${x}\n  log ${y}`)
      }
    }
    assert.fail(`${label}: state bytes differ`)
  }
}

function withDir<T>(f: (dir: string) => T): T {
  const dir = mkdtempSync(join(tmpdir(), 'keepfin-admission-'))
  try {
    return f(dir)
  } finally {
    try {
      rmSync(dir, {recursive: true, force: true})
    } catch {
      // files still open after a failed assertion: the real error matters more
    }
  }
}

test('sql admission: codes, vector, pending, findings and state equal the in-memory log (equivocations included)', () => {
  let equivocations = 0
  let seen = new Set<string>()
  withDir((dir) => {
    for (let seed = 1; seed <= 40; seed++) {
      const rnd = prng(seed * 977)
      const d = delivery(seed, rnd, 70 + (seed % 5) * 30)
      const log = SyncLog.create()
      const db = new SqlState(join(dir, `a${seed}.db`))
      const expected = d.ops.map((op) => codeOf(log.admit(op)))
      const got: string[] = []
      for (const batch of batches(d.ops, rnd)) got.push(...db.admit(batch))
      assert.deepEqual(got, expected, `seed ${seed}: admission codes`)
      for (const c of expected) seen.add(c.split(':')[0])
      equivocations += expected.filter((c) => c === 'equivocation').length
      assertSame(log, db, d.all, `seed ${seed}`)
      db.close()
    }
  })
  assert.ok(equivocations > 50, `only ${equivocations} equivocations were exercised`)
  for (const code of ['admitted', 'duplicate', 'kept', 'invalid', 'equivocation', 'setAside', 'malformed']) assert.ok(seen.has(code), `no ${code} in the histories`)
})

test('sql admission after a bulk load from checkpoint parts, with equivocations known before and found after', () => {
  let usable = 0
  let discarded = 0
  withDir((dir) => {
    for (let seed = 1; seed <= 30; seed++) {
      const rnd = prng(seed * 1511)
      const h = history(seed + 500, 120 + (seed % 4) * 30)
      const cut = 20 + Math.floor(rnd() * (h.ops.length - 40))
      const early = SyncLog.create()
      for (const op of h.ops.slice(0, cut)) early.admit(op)
      const parts = early.checkpointParts(LIBRARY)
      const files = parts.map((p) => p.bytes)
      const actors = [...new Set(h.specs.map((s) => s.actor))]
      const coverage = early.versionVector()
      // known equivocations: one above every coverage (usable), one at the coverage (the parts are unusable)
      const above = {[actors[0]]: (coverage[actors[0]] ?? 0) + 1 + Math.floor(rnd() * 3)}
      const below = {[actors[0]]: Math.max(1, coverage[actors[0]] ?? 1)}
      for (const known of [{}, above, below]) {
        const log = SyncLog.fromCheckpointParts(files, LIBRARY, known)
        const db = new SqlState(join(dir, `b${seed}-${Object.keys(known).length}-${Object.values(known)[0] ?? 0}.db`))
        const loaded = db.bulkLoad(files, LIBRARY, known)
        assert.equal(loaded.ok, log !== null, `seed ${seed}: the loader and the log disagree on whether the parts are usable`)
        if (!log) {
          discarded++
          db.close()
          continue
        }
        usable++
        const variants = h.specs.filter(() => rnd() < 0.1).map(variantOf)
        const ops = shuffled([...h.ops, ...h.ops.filter(() => rnd() < 0.2), ...variants], rnd)
        // a variant below the coverage would make a real checkpoint unusable, so segments never hold one: only the log and the SQL state are shown it
        const covered = (v: string) => { const m = /"id":"([^:]+):(\d+)"/.exec(v)!; return Number(m[2]) <= (coverage[m[1]] ?? 0) }
        const all = [...h.ops, ...variants.filter((v) => !covered(v))]
        const expected = ops.map((op) => codeOf(log.admit(op)))
        const got: string[] = []
        for (const batch of batches(ops, rnd)) got.push(...db.admit(batch))
        assert.deepEqual(got, expected, `seed ${seed} (known ${JSON.stringify(known)}): admission codes after the load`)
        assert.ok(expected.includes('covered'), 'ops below the coverage must be covered')
        assertSame(log, db, all, `seed ${seed} after a load, known ${JSON.stringify(known)}`)
        db.close()
      }
    }
  })
  assert.ok(usable > 30 && discarded > 5, `usable ${usable}, discarded ${discarded}`)
})

test('sql admission: a rebuild after an equivocation removes the set-aside ops and keeps the rest', () => {
  withDir((dir) => {
    const actor = 'a1000000-0000-4000-8000-000000000001'
    const item = 'b1000000-0000-4000-8000-000000000001'
    const wall = '1700000000000'
    const mk = (seq: number, kind: string, body: Record<string, string | number>, counter = seq) =>
      makeOp(actor, seq, `${wall}.${String(counter).padStart(6, '0')}.${actor}`, kind, body)
    const ops = [mk(1, 'item.add', {item}), mk(2, 'item.set', {item, field: 'title', value: 'one'}), mk(3, 'item.set', {item, field: 'title', value: 'two'}), mk(4, 'item.set', {item, field: 'artist', value: 'a'})]
    const variant = mk(3, 'item.set', {item, field: 'title', value: 'other'}, 99)
    const log = SyncLog.create()
    const db = new SqlState(join(dir, 'r.db'))
    for (const op of [...ops, variant]) {
      assert.equal(db.admit([op])[0], codeOf(log.admit(op)))
    }
    assert.equal(db.rebuildRequired, true)
    // before the rebuild the SQL state still holds ops 3 and 4; after it, as the log does, neither
    db.rebuild([...ops, variant])
    assert.deepEqual(db.setAside(), {[actor]: 3})
    assert.ok(Buffer.compare(sqlStateBytes(db.readRows()), log.stateBodyBytes()) === 0)
    const item0 = db.readItems()[0]
    assert.equal(item0.fields.title, 'one')
    assert.equal(item0.fields.artist, undefined)
    // ops from the set-aside range keep being set aside
    assert.equal(db.admit([mk(5, 'item.set', {item, field: 'artist', value: 'late'})])[0], 'setAside')
    db.close()
  })
})

test('sql import: the first import gives the codes and the state of admitting op by op, and starts only on an empty database', () => {
  withDir((dir) => {
    for (let seed = 1; seed <= 20; seed++) {
      const rnd = prng(seed * 3571)
      const d = delivery(seed, rnd, 70 + (seed % 5) * 30)
      const log = SyncLog.create()
      const db = new SqlState(join(dir, `i${seed}.db`))
      const expected = d.ops.map((op) => codeOf(log.admit(op)))
      assert.deepEqual(db.importFresh(d.ops), expected, `seed ${seed}: import codes`)
      assertSame(log, db, d.all, `seed ${seed} after the import`)
      assert.equal(db.importFresh(d.ops), null, `seed ${seed}: a second import must refuse`)
      db.close()
    }
  })
})