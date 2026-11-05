// The D-A3 benchmark (dev-docs/plans/KEEPFIN-PHASE0-RESULTS.md section 7).
//   node --import tsx bench-d-a3.ts gen <dir> [items]        builds <dir>/base.db (a library of <items> items) and op files
//   node --import tsx bench-d-a3.ts run <ts|kt> <dir> [reps]  copies base.db and times the three workloads, prints JSON
import {copyFileSync, mkdirSync, readFileSync, writeFileSync, existsSync, rmSync, renameSync} from 'node:fs'
import {join} from 'node:path'
import {randomUUID} from 'node:crypto'
import {SyncLog, hlcTick, makeOp, sqlDbOpen, type SyncJson} from '../../shared/core/sync.js'
import {SqlState} from './sqlstate.js'
import {DatabaseSync} from 'node:sqlite'

const LIBRARY = '00000000-0000-0000-0000-0000000000aa'
const ids = (i: number, kind: number) => ((i * 2654435761) % 4294967296).toString(16).padStart(8, '0') + '-' + kind.toString(16).padStart(4, '0') + '-4000-8000-000000000001'
const [mode, a, b, c] = process.argv.slice(2)

function gen(dir: string, n: number) {
  mkdirSync(dir, {recursive: true})
  const playlists = Math.max(1, Math.floor(n / 100))
  const actor = randomUUID()
  const remote = randomUUID()
  let seq = 0
  let last: string | null = null
  const make = (kind: string, body: Record<string, SyncJson>) => {
    seq += 1
    last = hlcTick(last, 1_700_000_000_000 + seq, actor)
    return makeOp(actor, seq, last, kind, body)
  }
  const base: string[] = []
  for (let p = 0; p < playlists; p++) base.push(make('playlist.add', {playlist: ids(p, 2), fields: {name: `Playlist ${p}`}}))
  for (let i = 0; i < n; i++) base.push(make('item.add', {item: ids(i, 1), fields: {media: 'audio', title: `Track ${i}`, artist: `Artist ${i % 500}`, album: `Album ${i % 2000}`, duration: 180 + (i % 100)}}))
  for (let i = 0; i < n; i++) base.push(make('entry.insert', {playlist: ids(i % playlists, 2), entry: ids(i, 3), item: ids(i, 1), key: 'a0'}))
  for (let p = 0; p < 10; p++) base.push(make('place.set', {place: ids(p, 4), field: 'name', value: `Place ${p}`}))
  const baseSeq = seq
  writeFileSync(join(dir, 'base-ops.txt'), base.join('\n'))
  let log: SyncLog | null = SyncLog.create()
  for (const op of base) log.admit(op)
  const parts = log.checkpointParts(LIBRARY)
  log = null
  const path = join(dir, 'base.db')
  for (const f of [path, path + '-wal', path + '-shm']) rmSync(f, {force: true})
  const db = new SqlState(path, {synchronous: 'NORMAL'})
  const t0 = performance.now()
  db.bulkLoad(parts.map((p) => p.bytes), LIBRARY)
  console.log('base built', n, 'items', Math.round(performance.now() - t0), 'ms')
  db.checkpoint('TRUNCATE')
  db.close()
  // remote batches: 5 x 500 ops from another actor (edits of existing items, new items, playlist entries)
  let rseq = 0
  let rlast: string | null = null
  const rmake = (kind: string, body: Record<string, SyncJson>) => {
    rseq += 1
    rlast = hlcTick(rlast, 1_700_100_000_000 + rseq, remote)
    return makeOp(remote, rseq, rlast, kind, body)
  }
  const remoteBatches: string[][] = []
  for (let k = 0; k < 5; k++) {
    const batch: string[] = []
    for (let j = 0; j < 400; j++) batch.push(rmake('item.set', {item: ids(((k * 400 + j) * 7919) % n, 1), field: 'rating', value: j % 5}))
    for (let j = 0; j < 50; j++) batch.push(rmake('item.add', {item: ids(n + k * 50 + j, 1), fields: {media: 'audio', title: `New ${k}-${j}`, artist: 'Remote'}}))
    for (let j = 0; j < 50; j++) batch.push(rmake('entry.insert', {playlist: ids((k * 50 + j) % playlists, 2), entry: ids(n + k * 50 + j, 3), item: ids(n + k * 50 + j, 1), key: 'a1'}))
    remoteBatches.push(batch)
  }
  // local edits made after the base (same actor, seq continues)
  const local: string[] = []
  for (let k = 0; k < 40; k++) local.push(make('item.set', {item: ids((k * 104729) % n, 1), field: 'title', value: `Edited ${k}`}))
  // the import: 20,000 ops into an empty database (a fresh actor)
  const imp = randomUUID()
  let iseq = 0
  let ilast: string | null = null
  const importOps: string[] = []
  for (let i = 0; i < 20000; i++) {
    iseq += 1
    ilast = hlcTick(ilast, 1_700_200_000_000 + iseq, imp)
    importOps.push(makeOp(imp, iseq, ilast, 'item.add', {item: ids(i + 5_000_000, 1), fields: {media: 'audio', title: `Imported ${i}`, artist: `Artist ${i % 300}`, duration: 200}}))
  }
  writeFileSync(join(dir, 'ops.json'), JSON.stringify({remoteBatches, local, importOps, baseSeq}))
}

type Engine = {admit(ops: string[]): string[]; close(): void}
function open(engine: string, path: string): Engine {
  if (engine === 'ts') {
    const db = new SqlState(path, {synchronous: 'NORMAL'})
    return {admit: (ops) => db.admit(ops), close: () => db.close()}
  }
  const db = sqlDbOpen({open: (f: string) => new DatabaseSync(f), remove: (f: string) => rmSync(f, {force: true}), rename: renameSync, residentMb: () => 0}, path, {synchronous: 'NORMAL'})
  return {admit: (ops) => db.admit(ops), close: () => db.close()}
}
const median = (xs: number[]) => [...xs].sort((x, y) => x - y)[Math.floor(xs.length / 2)]

function run(engine: string, dir: string, reps: number) {
  const ops = JSON.parse(readFileSync(join(dir, 'ops.json'), 'utf8')) as {remoteBatches: string[][]; local: string[]; importOps: string[]}
  const out: Record<string, number | string> = {engine}
  const results: Record<string, number[]> = {remote: [], local: [], import: []}
  for (let rep = 0; rep < reps; rep++) {
    const work = join(dir, `work-${engine}`)
    mkdirSync(work, {recursive: true})
    const path = join(work, 'state.db')
    for (const f of [path, path + '-wal', path + '-shm']) rmSync(f, {force: true})
    copyFileSync(join(dir, 'base.db'), path)
    let db = open(engine, path)
    // remote batches of 500 ops (the first reply also warms the statements; every batch is timed)
    for (const batch of ops.remoteBatches) {
      const t = performance.now()
      const codes = db.admit(batch)
      results.remote.push(performance.now() - t)
      if (codes.filter((x) => x === 'admitted').length !== batch.length) throw new Error('not all admitted: ' + [...new Set(codes)].join())
    }
    for (const op of ops.local) {
      const t = performance.now()
      const codes = db.admit([op])
      results.local.push(performance.now() - t)
      if (codes[0] !== 'admitted') throw new Error('local not admitted ' + codes[0])
    }
    db.close()
    // the import into an empty database, in batches of 5000
    const ipath = join(work, 'import.db')
    for (const f of [ipath, ipath + '-wal', ipath + '-shm']) rmSync(f, {force: true})
    db = open(engine, ipath)
    const t = performance.now()
    let admitted = 0
    for (let i = 0; i < ops.importOps.length; i += 5000) admitted += db.admit(ops.importOps.slice(i, i + 5000)).filter((x) => x === 'admitted').length
    results.import.push(performance.now() - t)
    if (admitted !== ops.importOps.length) throw new Error('import admitted ' + admitted)
    db.close()
    // a touched-key fingerprint of both databases so the engines can be compared
    if (rep === 0) {
      for (const [name, file] of [['state', path], ['import', ipath]] as const) {
        const raw = new DatabaseSync(file)
        const dump = (sql: string) => JSON.stringify(raw.prepare(sql).all())
        const sums = ['item_view', 'regs', 'tags', 'held', 'entries', 'present', 'revisions', 'vv', 'findings'].map((t2) => `${t2}:${(raw.prepare(`SELECT COUNT(*) AS c FROM ${t2}`).get() as {c: number}).c}`).join(' ')
        out[`rows-${name}`] = sums + ' ' + (raw.prepare("SELECT v FROM meta WHERE k='maxHlc'").get() as {v: string} | undefined)?.v
        out[`view-${name}`] = (raw.prepare('SELECT COUNT(*) AS c, SUM(LENGTH(fields)) AS f, SUM(plays) AS p FROM item_view').get() as object as Record<string, number>).f
        void dump
        raw.close()
      }
    }
  }
  out.remote500Ms = Math.round(median(results.remote) * 10) / 10
  out.localEditMs = Math.round(median(results.local) * 100) / 100
  out.import20kMs = Math.round(median(results.import))
  console.log(JSON.stringify(out))
}


async function parts(dir: string) {
  const {writePart} = await import('./parts.js')
  const path = join(dir, 'base.db')
  const db = new SqlState(path)
  const coverage = db.checkpointCoverage()!
  const maxHlc = db.maxHlc()
  const shards = [...'0123456789abcdef', 'meta']
  for (const label of (process.env.LABELS ?? 'ts,kt,ts,kt').split(',')) {
    let total = 0
    let worst = 0
    let bytes = 0
    for (const shard of shards) {
      const t = performance.now()
      const out = label === 'ts' ? await writePart(db.db, LIBRARY, shard, coverage, maxHlc, {yield: async () => undefined}) : await db.partBytes(LIBRARY, shard, coverage)
      const ms = performance.now() - t
      total += ms
      worst = Math.max(worst, ms)
      bytes += out.length
    }
    console.log(label, 'parts total', Math.round(total), 'ms, per part avg', Math.round(total / shards.length), 'ms, worst', Math.round(worst), 'ms,', Math.round(bytes / 1e6), 'MB')
  }
  db.close()
}

function full(engine: string, dir: string, batch: number) {
  const ops = readFileSync(join(dir, 'base-ops.txt'), 'utf8').split('\n')
  const path = join(dir, `full-${engine}.db`)
  for (const f of [path, path + '-wal', path + '-shm']) rmSync(f, {force: true})
  if (engine === 'ktf') {
    const raw = sqlDbOpen({open: (f: string) => new DatabaseSync(f), remove: (f: string) => rmSync(f, {force: true}), rename: renameSync, residentMb: () => 0}, path, {synchronous: 'NORMAL'})
    const t0 = performance.now()
    const codes = raw.importFresh(ops)!
    const ms = Math.round(performance.now() - t0)
    raw.close()
    console.log(JSON.stringify({engine, ops: ops.length, admitted: codes.filter((x) => x === 'admitted').length, fullMs: ms, closeMs: Math.round(performance.now() - t0) - ms}))
    return
  }
  const db = open(engine, path)
  const t = performance.now()
  let admitted = 0
  for (let i = 0; i < ops.length; i += batch) admitted += db.admit(ops.slice(i, i + batch)).filter((x) => x === 'admitted').length
  console.log(JSON.stringify({engine, ops: ops.length, admitted, batch, fullMs: Math.round(performance.now() - t)}))
  db.close()
}

if (mode === 'full') full(a, b, Number(c ?? 5000))
else if (mode === 'parts') await parts(a)
else if (mode === 'gen') gen(a, Number(b ?? 200000))
else if (mode === 'run') run(a, b, Number(c ?? 3))
void existsSync
