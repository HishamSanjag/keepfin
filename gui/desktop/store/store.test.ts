import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import {decodeSegment, encodeSegment, hlcTick, makeOp} from '../../shared/core/sync.js'
import {createKeepfinFolder, dataDir, type KeepfinFolder} from './keepfinFolder.js'
import type {OpDraft} from './mapping.js'
import {SqlState} from '../sqlstate/sqlstate.js'
import {KeepfinStore, segmentBytes, type StoreOptions} from './store.js'

type Env = {dir: string; folder: KeepfinFolder; device: (name: string) => string; open: (name: string, actor: string, extra?: Partial<StoreOptions>) => Promise<KeepfinStore>; cleanup: () => Promise<void>}

async function env(): Promise<Env> {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-store-'))
  const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
  let clock = 1_700_000_000_000
  const device = (name: string) => path.join(dir, name)
  return {
    dir,
    folder,
    device,
    open: (name, actor, extra = {}) =>
      KeepfinStore.open({
        root: folder.root,
        libraryId: folder.libraryId,
        actor,
        deviceDir: device(name),
        outboxDir: path.join(device(name), 'outbox'),
        newId: randomUUID,
        now: () => (clock += 1),
        flushIdleMs: 20,
        watch: false,
        ...extra,
      }),
    cleanup: () => fs.rm(dir, {recursive: true, force: true, maxRetries: 5}),
  }
}

const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const item = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const add = (n: number, title = `Item ${n}`): OpDraft => ({kind: 'item.add', body: {item: item(n), fields: {media: 'audio', title}}})
const set = (n: number, field: string, value: string | number): OpDraft => ({kind: 'item.set', body: {item: item(n), field, value}})
const titleOf = (store: KeepfinStore, n: number) => store.views().items.find((entry) => entry.id === item(n))?.fields.title
const segments = async (root: string, actor: string) => (await fs.readdir(path.join(dataDir(root), 'ops', actor)).catch(() => [] as string[])).filter((name) => name.endsWith('.seg')).sort()

async function until(check: () => boolean | Promise<boolean>, label: string, ms = 8000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (await check()) return
    await delay(25)
  }
  throw new Error(`Timed out waiting for ${label}`)
}

test('apply writes the outbox first; flush makes an immutable segment with 16-digit names and empties the outbox', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {flushIdleMs: 60_000})
    await store.apply([add(1), set(1, 'artist', 'Ana'), add(2)])
    const outbox = await fs.readFile(path.join(e.device('dev'), 'outbox', 'ops.jsonl'), 'utf8')
    assert.equal(outbox.trim().split('\n').length, 3)
    assert.equal(store.pendingOps, 3)
    assert.equal(titleOf(store, 1), 'Item 1')
    assert.deepEqual(await segments(e.folder.root, A), [])
    await store.flush()
    assert.deepEqual(await segments(e.folder.root, A), ['0000000000000001-0000000000000003.seg'])
    // The outbox keeps flushed operations until it is compacted (on close); open recognises them as already in a segment.
    assert.equal((await fs.readFile(path.join(e.device('dev'), 'outbox', 'ops.jsonl'), 'utf8')).trim().split('\n').length, 3)
    assert.equal(store.pendingOps, 0)
    await store.apply([set(2, 'title', 'Renamed')])
    await store.close()
    // Closing compacts: the outbox is empty and the counter is saved.
    assert.equal(await fs.readFile(path.join(e.device('dev'), 'outbox', 'ops.jsonl'), 'utf8'), '')
    assert.equal(JSON.parse(await fs.readFile(path.join(e.device('dev'), 'seq', `${A}.json`), 'utf8')).lastSeq, 4)
    assert.deepEqual(await segments(e.folder.root, A), ['0000000000000001-0000000000000003.seg', '0000000000000004-0000000000000004.seg'])
    const again = await e.open('dev', A)
    assert.equal(titleOf(again, 2), 'Renamed')
    assert.equal(again.stats.rotated, false)
    await again.close()
  } finally {
    await e.cleanup()
  }
})

test('an idle outbox flushes by itself, and 500 operations flush at once', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {flushIdleMs: 50, flushOps: 500})
    await store.apply([add(1)])
    await until(async () => (await segments(e.folder.root, A)).length === 1, 'idle flush')
    const many = Array.from({length: 500}, (_, i) => add(i + 10))
    await store.apply(many)
    await until(async () => (await segments(e.folder.root, A)).length === 2 && store.pendingOps === 0, 'size flush')
    await store.close()
  } finally {
    await e.cleanup()
  }
})

test('a big first import over the few operations written before it goes in one pass and equals a replay', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {flushIdleMs: 60_000})
    await store.apply([add(1, 'seed'), set(1, 'artist', 'Ana')])
    await store.flush()
    await store.apply([add(2, 'pending')])
    await store.apply(Array.from({length: 20_100}, (_, i) => add(i + 10)))
    assert.equal(store.lastBigApply, 'one pass')
    const check = (s: KeepfinStore) => {
      assert.equal(s.views().items.length, 20_102)
      assert.equal(titleOf(s, 1), 'seed')
      assert.equal(titleOf(s, 2), 'pending')
      assert.equal(titleOf(s, 20_109), 'Item 20109')
      assert.equal(s.views().items.find((entry) => entry.id === item(1))?.fields.artist, 'Ana')
    }
    check(store)
    assert.equal(store.pendingOps, 20_101)
    await store.apply([set(2, 'title', 'Renamed')])
    assert.equal(titleOf(store, 2), 'Renamed')
    await store.close()
    // The database is a cache of the segments: starting again from them gives the same library.
    await fs.rm(path.join(e.device('dev'), 'state'), {recursive: true, force: true})
    const again = await e.open('dev', A)
    assert.equal(again.views().items.length, 20_102)
    assert.equal(titleOf(again, 2), 'Renamed')
    assert.equal(titleOf(again, 20_109), 'Item 20109')
    await again.close()
  } finally {
    await e.cleanup()
  }
})

test('a big import into a library that holds another device’s operations is admitted normally', async () => {
  const e = await env()
  try {
    const other = await e.open('other', B, {flushIdleMs: 60_000})
    await other.apply([add(1, 'from B')])
    await other.close()
    const store = await e.open('dev', A, {flushIdleMs: 60_000})
    await store.apply(Array.from({length: 20_050}, (_, i) => add(i + 10)))
    assert.equal(store.lastBigApply, 'op by op')
    assert.equal(store.views().items.length, 20_051)
    assert.equal(titleOf(store, 1), 'from B')
    await store.close()
  } finally {
    await e.cleanup()
  }
})
test('invalid drafts change nothing', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A)
    await store.apply([add(1)])
    await assert.rejects(store.apply([set(1, 'title', 'ok'), {kind: 'item.add', body: {item: 'not-an-id'}}]))
    assert.equal(titleOf(store, 1), 'Item 1')
    assert.equal(store.pendingOps, 1)
    await store.apply([set(1, 'title', 'fine')])
    assert.equal(titleOf(store, 1), 'fine')
    await store.close()
    const again = await e.open('dev', A)
    assert.equal(titleOf(again, 1), 'fine')
    await again.close()
  } finally {
    await e.cleanup()
  }
})

test('two stores on one folder: edits meet through rescan, plays add up, a same-field race converges', async () => {
  const e = await env()
  try {
    const a = await e.open('devA', A)
    const b = await e.open('devB', B)
    await a.apply([add(1), add(2), set(1, 'plays@' + A, 0)])
    await a.flush()
    await b.rescan()
    assert.equal(titleOf(b, 1), 'Item 1')
    // concurrent edits, neither has seen the other
    await a.apply([set(1, `plays@${A}`, 1), set(2, 'title', 'from A')])
    await b.apply([set(1, `plays@${B}`, 2), set(2, 'title', 'from B')])
    await a.flush()
    await b.flush()
    await a.rescan()
    await b.rescan()
    for (const store of [a, b]) assert.equal(store.views().items.find((entry) => entry.id === item(1))?.plays, 3)
    assert.equal(titleOf(a, 2), titleOf(b, 2))
    assert.ok(['from A', 'from B'].includes(titleOf(a, 2) as string))
    // B's clock followed A's: an edit made after seeing a change wins over it.
    await b.apply([set(2, 'title', 'later')])
    await b.flush()
    await a.rescan()
    assert.equal(titleOf(a, 2), 'later')
    await a.close()
    await b.close()
  } finally {
    await e.cleanup()
  }
})

test('the watcher brings in another actor’s new segment and announces it', async () => {
  const e = await env()
  try {
    const a = await e.open('devA', A)
    const b = await e.open('devB', B, {watch: true, watchDebounceMs: 50})
    let changes = 0
    b.onChange((change) => {
      assert.equal(change.remote, true)
      changes++
    })
    await a.apply([add(7, 'Watched')])
    await a.flush()
    await until(() => titleOf(b, 7) === 'Watched', 'the watcher')
    assert.ok(changes >= 1)
    await a.close()
    await b.close()
  } finally {
    await e.cleanup()
  }
})

test('a partial or unreadable segment is retried and not reported as corruption at first', async () => {
  const e = await env()
  try {
    const a = await e.open('devA', A)
    await a.apply([add(1, 'Whole')])
    await a.flush()
    await a.close()
    const bytes = await fs.readFile(path.join(dataDir(e.folder.root), 'ops', A, '0000000000000001-0000000000000001.seg'))
    // B's segment arrives in pieces, as a cloud client writes it.
    const target = path.join(dataDir(e.folder.root), 'ops', B)
    await fs.mkdir(target, {recursive: true})
    const op = makeOp(B, 1, hlcTick(null, 1_700_000_100_000, B), 'item.add', {item: item(2), fields: {media: 'audio', title: 'Remote'}})
    const full = encodeSegment(e.folder.libraryId, B, [op])
    const name = path.join(target, '0000000000000001-0000000000000001.seg')
    await fs.writeFile(name, full.subarray(0, full.length - 20))
    const store = await e.open('devA', A, {retryMs: 30})
    assert.equal(titleOf(store, 1), 'Whole')
    assert.equal(titleOf(store, 2), undefined)
    assert.ok(!store.findings().some((finding) => finding.startsWith('unreadable-segment')))
    await store.rescan({force: true})
    assert.equal(titleOf(store, 2), undefined)
    assert.ok(!store.findings().some((finding) => finding.startsWith('unreadable-segment')))
    // The rest arrives; the retry timer picks it up on its own.
    await fs.writeFile(name, full)
    await until(() => titleOf(store, 2) === 'Remote', 'the retry')
    assert.ok(!store.findings().some((finding) => finding.startsWith('unreadable-segment')))
    assert.ok(bytes.length > 0)
    await store.close()
  } finally {
    await e.cleanup()
  }
})

test('crash after the outbox append and before the segment: nothing lost, no duplicate', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {flushIdleMs: 60_000})
    await store.apply([add(1), add(2)])
    await store.flush()
    await store.apply([set(1, 'title', 'Unflushed'), add(3)])
    store.abandon() // the process dies here: two operations only in the outbox
    assert.deepEqual(await segments(e.folder.root, A), ['0000000000000001-0000000000000002.seg'])
    const again = await e.open('dev', A, {flushIdleMs: 60_000})
    assert.equal(again.stats.outboxOps, 4, 'two flushed ones kept in the outbox, two unflushed')
    assert.equal(titleOf(again, 1), 'Unflushed')
    assert.equal(titleOf(again, 3), 'Item 3')
    assert.equal(again.pendingOps, 2)
    await again.apply([set(3, 'title', 'Next')])
    await again.close()
    assert.deepEqual(await segments(e.folder.root, A), ['0000000000000001-0000000000000002.seg', '0000000000000003-0000000000000005.seg'])
    const last = await e.open('dev', A)
    assert.equal(titleOf(last, 3), 'Next')
    assert.equal(last.stats.rotated, false)
    assert.deepEqual(last.findings(), [])
    assert.equal(last.views().items.length, 3)
    await last.close()
  } finally {
    await e.cleanup()
  }
})

test('crash after the segment and before the outbox is emptied: the repeated operations are not written twice', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {flushIdleMs: 60_000})
    await store.apply([add(1), add(2)])
    const outboxFile = path.join(e.device('dev'), 'outbox', 'ops.jsonl')
    const before = await fs.readFile(outboxFile, 'utf8')
    await store.flush()
    store.abandon()
    await fs.writeFile(outboxFile, before) // as if the truncation never happened
    const again = await e.open('dev', A, {flushIdleMs: 60_000})
    assert.equal(again.pendingOps, 0)
    assert.equal(again.views().items.length, 2)
    await again.apply([add(3)])
    await again.close()
    assert.deepEqual(await segments(e.folder.root, A), ['0000000000000001-0000000000000002.seg', '0000000000000003-0000000000000003.seg'])
    const last = await e.open('dev', A)
    assert.equal(last.views().items.length, 3)
    assert.deepEqual(last.findings(), [])
    await last.close()
  } finally {
    await e.cleanup()
  }
})

test('a torn final outbox line is ignored and the next append starts clean', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {flushIdleMs: 60_000})
    await store.apply([add(1)])
    store.abandon()
    const outboxFile = path.join(e.device('dev'), 'outbox', 'ops.jsonl')
    await fs.appendFile(outboxFile, '{"body":{"element":"x","set":"fav')
    const again = await e.open('dev', A, {flushIdleMs: 60_000})
    assert.equal(again.stats.outboxOps, 1)
    assert.equal(again.views().items.length, 1)
    await again.apply([add(2)])
    await again.close()
    const last = await e.open('dev', A)
    assert.equal(last.views().items.length, 2)
    assert.deepEqual(last.findings(), [])
    assert.deepEqual(await segments(e.folder.root, A), ['0000000000000001-0000000000000002.seg'])
    await last.close()
  } finally {
    await e.cleanup()
  }
})

test('a restored install retires its actor: no equivocation, the new actor starts at 1', async () => {
  const e = await env()
  try {
    const rotated: string[][] = []
    const store = await e.open('dev', A)
    await store.apply([add(1), add(2), add(3), add(4), add(5)])
    await store.close()
    // The device data comes back from an older backup: the counter is behind the folder.
    await fs.writeFile(path.join(e.device('dev'), 'seq', `${A}.json`), JSON.stringify({actor: A, lastSeq: 2}))
    const restored = await e.open('dev', A, {onRotate: (oldActor, newActor) => void rotated.push([oldActor, newActor])})
    assert.equal(restored.stats.rotated, true)
    assert.notEqual(restored.actorId, A)
    assert.deepEqual(rotated, [[A, restored.actorId]])
    assert.equal(restored.views().items.length, 5)
    await restored.apply([add(6)])
    await restored.close()
    assert.deepEqual(await segments(e.folder.root, restored.actorId), ['0000000000000001-0000000000000001.seg'])
    assert.deepEqual(await segments(e.folder.root, A), ['0000000000000001-0000000000000005.seg'])
    const last = await e.open('dev', restored.actorId)
    assert.equal(last.views().items.length, 6)
    assert.deepEqual(last.findings(), [])
    assert.equal(last.stats.rotated, false)
    await last.close()
    // No device data at all (a copied install or a fresh profile pointing at the same actor).
    await fs.rm(e.device('dev'), {recursive: true, force: true})
    const copy = await e.open('clone', A)
    assert.equal(copy.stats.rotated, true)
    assert.deepEqual(copy.findings(), [])
    await copy.close()
  } finally {
    await e.cleanup()
  }
})

test('an equivocation found while running rebuilds the state, and a restart keeps it', async () => {
  const e = await env()
  try {
    const X = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
    const dir = path.join(dataDir(e.folder.root), 'ops', X)
    const make = (seq: number, title: string) => makeOp(X, seq, hlcTick(null, 1_700_000_000_000 + seq, X), 'item.add', {item: item(seq), fields: {media: 'audio', title}})
    const store = await e.open('dev', A)
    await store.apply([add(10)])
    await fs.mkdir(dir, {recursive: true})
    await fs.writeFile(path.join(dir, '0000000000000001-0000000000000003.seg'), encodeSegment(e.folder.libraryId, X, [make(1, 'one'), make(2, 'two A'), make(3, 'three A')]))
    await store.rescan()
    assert.equal(titleOf(store, 2), 'two A')
    store.takeRemoteTouched()
    await fs.writeFile(path.join(dir, '0000000000000002-0000000000000004.seg'), encodeSegment(e.folder.libraryId, X, [make(2, 'two B'), make(3, 'three B'), make(4, 'four')]))
    await store.rescan()
    // From the first disagreement on the actor's operations are set aside; the rest of the library is untouched.
    assert.ok(store.findings().includes(`equivocation:${X}:2`))
    assert.equal(titleOf(store, 1), 'one')
    assert.equal(titleOf(store, 2), undefined)
    assert.equal(titleOf(store, 4), undefined)
    assert.equal(titleOf(store, 10), 'Item 10')
    assert.equal(store.takeRemoteTouched().all, true, 'readers are told to read everything again')
    await store.close()
    const again = await e.open('dev', A)
    assert.equal(again.stats.checkpoint, true, 'the database kept the state')
    assert.equal(titleOf(again, 2), undefined)
    assert.equal(titleOf(again, 1), 'one')
    assert.equal(titleOf(again, 10), 'Item 10')
    assert.ok(again.findings().includes(`equivocation:${X}:2`))
    await again.close()
  } finally {
    await e.cleanup()
  }
})

test('a damaged database, or one of another library, is replaced and the library is replayed from its segments', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {flushOps: 1})
    await store.apply([add(1), add(2), add(3)])
    await store.close()
    const dbFile = path.join(e.device('dev'), 'state', 'library-state.v1.db')
    // damaged
    await fs.writeFile(dbFile, 'this is not a database file at all')
    await fs.rm(`${dbFile}-wal`, {force: true, maxRetries: 5})
    await fs.rm(`${dbFile}-shm`, {force: true, maxRetries: 5})
    const repaired = await e.open('dev', A)
    assert.equal(repaired.stats.checkpoint, false)
    assert.equal(repaired.stats.opsAdmitted + repaired.stats.outboxOps >= 3, true)
    assert.equal(repaired.views().items.length, 3)
    await repaired.close()
    // another library's database (a profile pointed at a different folder)
    const foreign = new SqlState(dbFile)
    foreign.setMeta('library', randomUUID())
    foreign.close()
    const replaced = await e.open('dev', A)
    assert.equal(replaced.stats.checkpoint, false)
    assert.equal(replaced.views().items.length, 3)
    await replaced.close()
  } finally {
    await e.cleanup()
  }
})

test('overlapping segments of one actor that disagree are reported as an equivocation and the replay still works', async () => {
  const e = await env()
  try {
    const X = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
    const dir = path.join(dataDir(e.folder.root), 'ops', X)
    await fs.mkdir(dir, {recursive: true})
    const make = (seq: number, title: string) => makeOp(X, seq, hlcTick(null, 1_700_000_000_000 + seq, X), 'item.add', {item: item(seq), fields: {media: 'audio', title}})
    const one = [make(1, 'one'), make(2, 'two A'), make(3, 'three A')]
    const two = [make(2, 'two B'), make(3, 'three B'), make(4, 'four')]
    await fs.writeFile(path.join(dir, '0000000000000001-0000000000000003.seg'), encodeSegment(e.folder.libraryId, X, one))
    await fs.writeFile(path.join(dir, '0000000000000002-0000000000000004.seg'), encodeSegment(e.folder.libraryId, X, two))
    const store = await e.open('dev', A)
    assert.equal(store.stats.equivocations[X], 2)
    assert.ok(store.findings().includes(`equivocation:${X}:2`))
    // Only the ops before the first disagreement are applied.
    assert.equal(titleOf(store, 1), 'one')
    assert.equal(titleOf(store, 2), undefined)
    await store.close()
  } finally {
    await e.cleanup()
  }
})

test('a change made outside the library index (a copy run) is reported like a remote one; an ordinary one is not', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A)
    let remoteEvents = 0
    store.onChange((change) => {
      if (change.remote) remoteEvents++
    })
    await store.apply([add(1)])
    assert.deepEqual(store.takeRemoteTouched().items, [])
    assert.equal(remoteEvents, 0)
    await store.apply([add(2), set(1, 'title', 'from a copy')], {external: true})
    assert.deepEqual([...store.takeRemoteTouched().items].sort(), [item(1), item(2)].sort())
    assert.equal(remoteEvents, 1)
    await store.close()
  } finally {
    await e.cleanup()
  }
})

test('checkpoints every N operations, opened by the next start, equal to a full replay', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {checkpointEvery: 60, flushOps: 40, flushIdleMs: 20})
    for (let round = 0; round < 4; round++) {
      await store.apply(Array.from({length: 40}, (_, i) => add(round * 100 + i + 1)))
      await store.flush()
    }
    await store.apply([set(1, 'title', 'After the checkpoint')])
    await store.close()
    // Closing writes a due checkpoint as parts: checkpoints\\<shard>\\<hlc>-<actor>.ckpt, one per changed shard.
    const shards = await fs.readdir(path.join(dataDir(e.folder.root), 'checkpoints'))
    assert.ok(shards.includes('0'), shards.join())
    for (const shard of shards) {
      const names = await fs.readdir(path.join(dataDir(e.folder.root), 'checkpoints', shard))
      assert.equal(names.length, 1)
      assert.ok(names[0].endsWith(`-${A}.ckpt`) && /^\d{13}\.\d{6}\./.test(names[0]))
    }
    // A start with the device's database finds the state as it was left: nothing is admitted again.
    const warm = await e.open('dev', A)
    assert.equal(warm.stats.checkpoint, true)
    assert.equal(warm.stats.opsAdmitted, 0)
    const fastViews = JSON.stringify(warm.views())
    await warm.close()
    // A new device (no database) loads the checkpoint parts and admits only the operations above them.
    const stateDir = path.join(e.device('dev'), 'state')
    await fs.rm(stateDir, {recursive: true, force: true, maxRetries: 5})
    const fast = await e.open('dev', A)
    assert.equal(fast.stats.checkpoint, true)
    assert.ok(fast.stats.opsAdmitted < 161)
    assert.equal(JSON.stringify(fast.views()), fastViews)
    await fast.close()
    // Without database and parts everything is replayed.
    await fs.rm(stateDir, {recursive: true, force: true, maxRetries: 5})
    await fs.rm(path.join(dataDir(e.folder.root), 'checkpoints'), {recursive: true, force: true})
    const slow = await e.open('dev', A)
    assert.equal(slow.stats.checkpoint, false)
    assert.equal(slow.stats.opsAdmitted, 161)
    assert.equal(JSON.stringify(slow.views()), fastViews)
    assert.equal(titleOf(slow, 1), 'After the checkpoint')
    await slow.close()
  } finally {
    await e.cleanup()
  }
})

test('deletes carry what the core says was observed', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A)
    await store.apply([add(1), {kind: 'set.add', body: {set: 'favourites', element: item(1)}}])
    await store.close()
    const again = await e.open('dev', A)
    const seen = again.observed({items: [item(1)], setElements: [['favourites', item(1)]]})
    assert.equal(seen.items[item(1)].tags.length, 1)
    assert.equal(seen.setElements.favourites[item(1)].length, 1)
    await again.apply([{kind: 'item.delete', body: {item: item(1), observedTags: seen.items[item(1)].tags, observedHeads: seen.items[item(1)].heads}}])
    assert.equal(again.observed({items: [item(1)]}).items[item(1)]?.tags.length ?? 0, 0)
    assert.equal(again.views().items.find((entry) => entry.id === item(1))?.status, 'deleted')
    await again.close()
  } finally {
    await e.cleanup()
  }
})

test('remote changes are reported as touched keys, and partial views give just those', async () => {
  const e = await env()
  try {
    const a = await e.open('devA', A)
    const b = await e.open('devB', B)
    await a.apply(Array.from({length: 50}, (_, i) => add(i + 1)))
    await a.flush()
    await b.rescan()
    assert.equal(b.takeRemoteTouched().items.length, 50)
    // B's own writes are not reported back to it.
    await b.apply([set(1, 'title', 'mine')])
    assert.deepEqual(b.takeRemoteTouched().items, [])
    await a.apply([set(7, 'title', 'seven'), set(9, 'title', 'nine')])
    await a.flush()
    await b.rescan()
    const touched = b.takeRemoteTouched()
    assert.deepEqual([...touched.items].sort(), [item(7), item(9)])
    const part = b.viewsFor(touched)
    assert.deepEqual(part.items.map((entry) => entry.fields.title).sort(), ['nine', 'seven'])
    assert.deepEqual(b.takeRemoteTouched().items, [])
    await a.close()
    await b.close()
  } finally {
    await e.cleanup()
  }
})

test('checkpoint parts: only changed shards are rewritten, an open uses them, old own parts are deleted', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {checkpointEvery: 40, checkpointDelayMs: 10, flushOps: 20})
    await store.apply(Array.from({length: 60}, (_, i) => add(i + 1)))
    await store.flush()
    const partsOf = async () => {
      const out: Record<string, string[]> = {}
      for (const shard of await fs.readdir(path.join(dataDir(e.folder.root), 'checkpoints'))) out[shard] = await fs.readdir(path.join(dataDir(e.folder.root), 'checkpoints', shard))
      return out
    }
    await until(async () => Object.keys(await partsOf()).length > 1, 'the first parts')
    const first = await partsOf()
    assert.ok(Object.values(first).every((names) => names.length === 1))
    // A change that touches one shard (item ids 0000...: shard 0) rewrites that part and not the others.
    await store.apply(Array.from({length: 40}, (_, i) => set(1, 'title', 'edit ' + i)))
    await store.flush()
    await until(async () => (await partsOf())['0']?.[0] !== first['0']?.[0], 'the changed shard to be rewritten')
    const second = await partsOf()
    assert.notEqual(second['0'][0], first['0'][0])
    assert.equal(second['0'].length, 1, 'the superseded part was deleted')
    await store.close()
    const again = await e.open('dev', A)
    assert.equal(again.stats.checkpoint, true)
    assert.equal(titleOf(again, 1), 'edit 39')
    assert.equal(again.views().items.length, 60)
    await again.close()
  } finally {
    await e.cleanup()
  }
})

test('the store’s own segment writer produces exactly the core’s bytes, and the core decodes them', () => {
  const library = randomUUID()
  let hlc: string | null = null
  const ops: string[] = []
  const titles = ['plain', 'Beyoncé', '日本語 🎵', 'quote " and \\ backslash', 'tab\there', 'x'.repeat(3000)]
  for (let i = 1; i <= 120; i++) {
    hlc = hlcTick(hlc, 1_700_000_000_000 + i, A)
    ops.push(makeOp(A, 40 + i, hlc, i % 3 ? 'item.set' : 'item.add', i % 3 ? {item: item(i), field: 'title', value: titles[i % titles.length]} : {item: item(i), fields: {media: 'audio', title: titles[i % titles.length]}}))
  }
  for (const count of [1, 2, 17, 120]) {
    const slice = ops.slice(0, count)
    const mine = segmentBytes(library, A, slice, 41)
    const theirs = encodeSegment(library, A, slice)
    assert.equal(Buffer.compare(Buffer.from(mine), Buffer.from(theirs)), 0, `${count} ops`)
    const decoded = decodeSegment(mine, library)
    assert.equal(decoded.ok, true)
    if (decoded.ok) assert.deepEqual(decoded.ops, slice)
  }
})
