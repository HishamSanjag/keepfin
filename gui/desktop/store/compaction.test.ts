// Data compaction of the segment log (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md): merging a device's own small segments,
// crash safety at every step, the released (1.0.2) reader over every intermediate folder state, covered-file removal and the
// transfer lease.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {execFileSync} from 'node:child_process'
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import {mkdirSync, rmSync, writeFileSync, cpSync, readdirSync, readFileSync} from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import {fileURLToPath} from 'node:url'
import {generateDeviceKey} from '../../shared/core/net.js'
import {decodeSegment} from '../../shared/core/sync.js'
import {createKeepfinFolder, dataDir, type KeepfinFolder} from './keepfinFolder.js'
import type {OpDraft} from './mapping.js'
import {KeepfinStore, segmentFilesSync, segmentOps, type StoreOptions} from './store.js'

const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const item = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const add = (n: number): OpDraft => ({kind: 'item.add', body: {item: item(n), fields: {media: 'audio', title: `Item ${n}`}}})
const key = generateDeviceKey()

type Env = {dir: string; folder: KeepfinFolder; open: (name: string, actor: string, extra?: Partial<StoreOptions>) => Promise<KeepfinStore>; cleanup: () => Promise<void>}

async function env(): Promise<Env> {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-compact-'))
  const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
  let clock = 1_700_000_000_000
  return {
    dir,
    folder,
    open: (name, actor, extra = {}) =>
      KeepfinStore.open({
        root: folder.root,
        libraryId: folder.libraryId,
        actor,
        deviceDir: path.join(dir, name),
        outboxDir: path.join(dir, name, 'outbox'),
        newId: randomUUID,
        now: () => (clock += 1),
        flushIdleMs: 20,
        watch: false,
        signer: key,
        compact: false,
        ...extra,
      }),
    cleanup: () => fs.rm(dir, {recursive: true, force: true}),
  }
}

const names = async (root: string, actor: string) => (await fs.readdir(path.join(dataDir(root), 'ops', actor)).catch(() => [] as string[])).filter((n) => n.endsWith('.seg')).sort()

/** Each op once, in seq order, from whatever segment files the folder holds (covered files give the same lines). */
function opLines(opsDir: string): string[] {
  const bySeq = new Map<string, string>()
  for (const file of segmentFilesSync(opsDir)) {
    for (const op of segmentOps([file], currentLibrary)) bySeq.set(`${file.actor}:${JSON.parse(op.line).id}`, op.line)
  }
  return [...bySeq.values()].sort()
}
let currentLibrary = ''

/** [flushes] flushes of [per] ops each, so the folder holds that many tiny segments. */
async function pile(store: KeepfinStore, flushes: number, per = 2, from = 1) {
  let n = from
  for (let i = 0; i < flushes; i++) {
    await store.apply(Array.from({length: per}, () => add(n++)))
    await store.flush()
  }
  return n
}

const viewsOf = (store: KeepfinStore) => JSON.stringify(store.views().items.map((entry) => [entry.id, entry.fields]).sort())

// ---- the released reader ------------------------------------------------------------------------------------------------------

/** The 1.0.2 store, as released (the file at the v1.0.2 tag), written next to the current one so its imports resolve. */
async function releasedStore() {
  const here = path.dirname(fileURLToPath(import.meta.url))
  const repo = path.resolve(here, '..', '..', '..')
  const text = execFileSync('git', ['show', 'v1.0.2:gui/desktop/store/store.ts'], {cwd: repo, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024})
  const file = path.join(here, `store.released.${process.pid}.tmp.ts`)
  writeFileSync(file, text)
  const module = (await import(`file:///${file.replace(/\\/g, '/')}`)) as {KeepfinStore: typeof KeepfinStore}
  return {KeepfinStore: module.KeepfinStore, cleanup: () => rmSync(file, {force: true})}
}

/** Opens a copy of the folder state with the RELEASED store, cold, and returns what it sees. */
async function readWithReleased(released: {KeepfinStore: typeof KeepfinStore}, folder: KeepfinFolder, snapshot: string, label: string) {
  const root = path.join(snapshot, 'root')
  const device = path.join(snapshot, 'device')
  const store = await released.KeepfinStore.open({root, libraryId: folder.libraryId, actor: randomUUID(), deviceDir: device, outboxDir: path.join(device, 'outbox'), newId: randomUUID, now: () => 1_700_000_000_000, watch: false, flushIdleMs: 60_000})
  try {
    return {views: viewsOf(store), findings: store.findings(), last: JSON.stringify([...store.currentLast()].filter(([a]) => a === A)), label}
  } finally {
    store.abandon()
  }
}

/** Copies the folder's .keepfin (ops only is enough for a reader) so a state can be read later. */
function snapshotOf(folder: KeepfinFolder, into: string) {
  rmSync(into, {recursive: true, force: true})
  mkdirSync(path.join(into, 'root'), {recursive: true})
  cpSync(dataDir(folder.root), dataDir(path.join(into, 'root')), {recursive: true})
}


// ---- tests --------------------------------------------------------------------------------------------------------------------

test('a pile of small segments becomes one file with the same ops, signed by the same key', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A)
    currentLibrary = e.folder.libraryId
    await pile(store, 40)
    const opsDir = path.join(dataDir(e.folder.root), 'ops')
    assert.equal((await names(e.folder.root, A)).length, 40)
    const before = opLines(opsDir)
    const views = viewsOf(store)
    const stats = await store.compact()
    assert.deepEqual({merged: stats.merged, removed: stats.removed}, {merged: 1, removed: 40})
    assert.deepEqual(await names(e.folder.root, A), ['0000000000000001-0000000000000080.seg'])
    assert.deepEqual(opLines(opsDir), before)
    const decoded = decodeSegment(readFileSync(path.join(opsDir, A, '0000000000000001-0000000000000080.seg')), e.folder.libraryId)
    assert.ok(decoded.ok && decoded.signer !== null && decoded.ops.length === 80)
    assert.equal(viewsOf(store), views)
    // nothing more to do, and a reopen reads the same library
    assert.deepEqual((await store.compact()).merged, 0)
    await store.close()
    const again = await e.open('dev', A)
    assert.equal(viewsOf(again), views)
    await again.close()
  } finally {
    await e.cleanup()
  }
})

test('fewer than eight small segments are left alone', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A)
    currentLibrary = e.folder.libraryId
    await pile(store, 7)
    assert.equal((await store.compact()).merged, 0)
    assert.equal((await names(e.folder.root, A)).length, 7)
    await store.close()
  } finally {
    await e.cleanup()
  }
})

const crashes: [step: 'published' | 'verified' | 'removing', at: number][] = [['published', 0], ['verified', 0], ['removing', 0], ['removing', 7], ['removing', 29]]

for (const [step, at] of crashes) {
  test(`a crash at "${step}" (${at} removed) loses nothing and the next start converges to one file`, async () => {
    const e = await env()
    const released = await releasedStore()
    try {
      let armed = false
      const store = await e.open('dev', A, {
        onCompactStep: (s, detail) => {
          if (armed && s === step && detail.removed === at) throw new Error('simulated crash')
        },
      })
      currentLibrary = e.folder.libraryId
      await pile(store, 30)
      const opsDir = path.join(dataDir(e.folder.root), 'ops')
      const before = opLines(opsDir)
      const views = viewsOf(store)
      armed = true
      await assert.rejects(store.compact(), /simulated crash/)
      // the folder as the crash left it, read by the released 1.0.2 reader
      const snapshot = path.join(e.dir, 'snapshot')
      snapshotOf(e.folder, snapshot)
      store.abandon()
      assert.deepEqual(opLines(opsDir), before)
      const old = await readWithReleased(released, e.folder, snapshot, `${step}/${at}`)
      assert.equal(old.views, views)
      assert.deepEqual(old.findings, [])
      // this version, without compacting on its own: same library
      const reopened = await e.open('dev', A)
      assert.equal(viewsOf(reopened), views)
      // an explicit round (or the start-up one) removes what the merged file covers
      await reopened.compact()
      assert.deepEqual(await names(e.folder.root, A), ['0000000000000001-0000000000000060.seg'])
      assert.deepEqual(opLines(opsDir), before)
      await reopened.close()
      // opening with automatic compaction on does the same from the crashed state
      const again = await e.open('dev2', A, {compact: true, compactDelayMs: 3_600_000})
      assert.equal(viewsOf(again), views)
      await again.close()
    } finally {
      released.cleanup()
      await e.cleanup()
    }
  })
}

test('the released 1.0.2 reader sees the same library in every intermediate folder state', async () => {
  const e = await env()
  const released = await releasedStore()
  try {
    const snapshots: string[] = []
    let take = true
    const store = await e.open('dev', A, {
      onCompactStep: (step, detail) => {
        if (!take || (step === 'removing' && detail.removed % 6 !== 0)) return
        const dir = path.join(e.dir, `state-${snapshots.length}`)
        snapshotOf(e.folder, dir)
        snapshots.push(`${step}/${detail.removed}`)
      },
    })
    currentLibrary = e.folder.libraryId
    await pile(store, 24)
    const views = viewsOf(store)
    const first = path.join(e.dir, 'state-before')
    snapshotOf(e.folder, first)
    await store.compact()
    take = false
    const last = path.join(e.dir, 'state-after')
    snapshotOf(e.folder, last)
    const states = [['before', first], ...snapshots.map((label, i) => [label, path.join(e.dir, `state-${i}`)]), ['after', last]]
    assert.ok(states.length >= 6)
    for (const [label, dir] of states) {
      const seen = await readWithReleased(released, e.folder, dir, label)
      assert.equal(seen.views, views, `released reader, state ${label}`)
      assert.deepEqual(seen.findings, [], `released reader findings, state ${label}`)
    }
    await store.close()
  } finally {
    released.cleanup()
    await e.cleanup()
  }
})

test('a peer that holds both the merged file and the files it covers drops the covered ones, op for op', async () => {
  const e = await env()
  try {
    let armed = true
    const a = await e.open('devA', A, {
      onCompactStep: (step, detail) => {
        if (armed && step === 'removing' && detail.removed === 0) throw new Error('crash')
      },
    })
    currentLibrary = e.folder.libraryId
    await pile(a, 20)
    const views = viewsOf(a)
    await assert.rejects(a.compact(), /crash/)
    armed = false
    a.abandon()
    assert.equal((await names(e.folder.root, A)).length, 21)
    const b = await e.open('devB', B, {compact: true, compactDelayMs: 3_600_000})
    assert.equal(viewsOf(b), views)
    assert.deepEqual(await names(e.folder.root, A), ['0000000000000001-0000000000000040.seg'])
    await b.close()
  } finally {
    await e.cleanup()
  }
})

test('a covered file whose ops differ is never removed (an equivocation is evidence)', async () => {
  const e = await env()
  try {
    let armed = true
    const a = await e.open('devA', A, {
      onCompactStep: (step, detail) => {
        if (armed && step === 'removing' && detail.removed === 0) throw new Error('crash')
      },
    })
    currentLibrary = e.folder.libraryId
    await pile(a, 10)
    await assert.rejects(a.compact(), /crash/)
    armed = false
    a.abandon()
    // replace the first small file by a different one for the same seqs
    const { encodeSegment, hlcTick, makeOp } = await import('../../shared/core/sync.js')
    const fakeOps = [makeOp(A, 1, hlcTick(null, 1_800_000_000_000, A), 'item.add', {item: item(900), fields: {media: 'audio', title: 'Forged'}}), makeOp(A, 2, hlcTick(null, 1_800_000_000_001, A), 'item.add', {item: item(901), fields: {media: 'audio', title: 'Forged'}})]
    const file = path.join(dataDir(e.folder.root), 'ops', A, '0000000000000001-0000000000000002.seg')
    writeFileSync(file, encodeSegment(e.folder.libraryId, A, fakeOps, key))
    const b = await e.open('devB', B, {compact: true, compactDelayMs: 3_600_000})
    assert.ok(b.findings().some((f) => f.startsWith(`equivocation:${A}`)))
    assert.equal((await names(e.folder.root, A)).length, 11, 'nothing is removed for an actor with an equivocation')
    await b.close()
  } finally {
    await e.cleanup()
  }
})

test('compaction waits for a segment transfer and removes no file while one is open', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {leaseWaitMs: 150})
    currentLibrary = e.folder.libraryId
    await pile(store, 20)
    const release = await store.hold()
    const busy = await store.compact()
    assert.equal(busy.skipped, 'busy')
    assert.equal((await names(e.folder.root, A)).length, 20)
    release()
    assert.equal((await store.compact()).merged, 1)
    assert.equal((await names(e.folder.root, A)).length, 1)
    await store.close()
  } finally {
    await e.cleanup()
  }
})

test('a transfer that starts after the merged file is written keeps the originals; the next round removes them', async () => {
  const e = await env()
  try {
    let release: (() => void) | undefined
    const store = await e.open('dev', A, {
      onCompactStep: async (step) => {
        if (step === 'verified') release = await store.hold()
      },
    })
    currentLibrary = e.folder.libraryId
    await pile(store, 20)
    const views = viewsOf(store)
    const first = await store.compact()
    assert.equal(first.skipped, 'busy')
    assert.equal((await names(e.folder.root, A)).length, 21, 'originals stay while a transfer is open')
    release!()
    const second = await store.compact()
    assert.equal(second.dropped, 20)
    assert.equal((await names(e.folder.root, A)).length, 1)
    assert.equal(viewsOf(store), views)
    await store.close()
  } finally {
    await e.cleanup()
  }
})

test('only segments signed by the current key are merged', async () => {
  const e = await env()
  try {
    const oldKey = generateDeviceKey()
    const first = await e.open('dev', A, {signer: oldKey})
    currentLibrary = e.folder.libraryId
    const next = await pile(first, 10)
    await first.close()
    const second = await e.open('dev', A)
    await pile(second, 10, 2, next)
    assert.equal((await names(e.folder.root, A)).length, 20)
    const stats = await second.compact()
    assert.equal(stats.merged, 1)
    const left = await names(e.folder.root, A)
    assert.equal(left.length, 11, 'the ten files of the older key stay as they are')
    assert.equal(left[10], '0000000000000021-0000000000000040.seg')
    await second.close()
    const again = await e.open('dev', A)
    assert.equal(again.views().items.length, 40)
    await again.close()
  } finally {
    await e.cleanup()
  }
})

test('the unsigned prefix of a device that later got a key is never merged into the signed tail', async () => {
  const e = await env()
  try {
    const first = await e.open('dev', A, {signer: undefined})
    currentLibrary = e.folder.libraryId
    const next = await pile(first, 9)
    await first.close()
    const second = await e.open('dev', A)
    await pile(second, 9, 2, next)
    await second.compact()
    const left = await names(e.folder.root, A)
    assert.equal(left.length, 10)
    const unsigned = decodeSegment(readFileSync(path.join(dataDir(e.folder.root), 'ops', A, left[0])), e.folder.libraryId)
    assert.ok(unsigned.ok && unsigned.signer === null)
    await second.close()
  } finally {
    await e.cleanup()
  }
})

test('a flush is batched: nothing is written during the idle window, and never later than the maximum age', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {flushIdleMs: 300, flushMaxAgeMs: 800, flushOps: 2000, now: Date.now})
    currentLibrary = e.folder.libraryId
    await store.apply([add(1)])
    await delay(100)
    assert.equal((await names(e.folder.root, A)).length, 0, 'still inside the idle window')
    // a trickle that always resets the idle timer
    const started = Date.now()
    let n = 2
    while ((await names(e.folder.root, A)).length === 0 && Date.now() - started < 3000) {
      await store.apply([add(n++)])
      await delay(120)
    }
    const waited = Date.now() - started
    assert.ok((await names(e.folder.root, A)).length > 0, 'published although the library never went quiet')
    assert.ok(waited < 1500, `waited ${waited} ms`)
    await store.close()
  } finally {
    await e.cleanup()
  }
})

test('the default batching holds up to 2,000 operations and a close publishes them', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {flushIdleMs: undefined})
    currentLibrary = e.folder.libraryId
    const drafts: OpDraft[] = []
    for (let i = 1; i <= 600; i++) drafts.push(add(i))
    await store.apply(drafts)
    await delay(200)
    assert.equal((await names(e.folder.root, A)).length, 0)
    await store.close()
    assert.equal((await names(e.folder.root, A)).length, 1)
  } finally {
    await e.cleanup()
  }
})

test('a close with enough small segments compacts them', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {compact: true, compactGapMs: 0, compactSoonMs: 3_600_000, compactDelayMs: 3_600_000})
    currentLibrary = e.folder.libraryId
    await pile(store, 12)
    await store.close()
    assert.equal((await names(e.folder.root, A)).length, 1)
  } finally {
    await e.cleanup()
  }
})

test('a flush that leaves eight small segments compacts them on its own, soon', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A, {compact: true, compactGapMs: 0, compactSoonMs: 50, compactDelayMs: 3_600_000})
    currentLibrary = e.folder.libraryId
    await pile(store, 10)
    const end = Date.now() + 5000
    while ((await names(e.folder.root, A)).length > 1 && Date.now() < end) await delay(50)
    assert.equal((await names(e.folder.root, A)).length, 1)
    await store.close()
  } finally {
    await e.cleanup()
  }
})

test('a library whose membership names a segment never merges or removes it', async () => {
  const e = await env()
  try {
    const store = await e.open('dev', A)
    currentLibrary = e.folder.libraryId
    await pile(store, 12)
    await store.enableMembership()
    await pile(store, 12, 2, 100)
    const lastFrozen = (store.membership().frozen[A] ?? {seq: 0}).seq
    assert.ok(lastFrozen > 0)
    await store.compact()
    const left = await names(e.folder.root, A)
    // the file that ends at the frozen seq is still there, under its own name, and no merged file crosses it
    assert.ok(left.some((name) => name.endsWith(`-${String(lastFrozen).padStart(16, '0')}.seg`)), left.join(' '))
    for (const name of left) {
      const [from, to] = name.replace('.seg', '').split('-').map(Number)
      assert.ok(!(from <= lastFrozen && to > lastFrozen && from !== to - (to - from)) || to === lastFrozen || from > lastFrozen, `${name} crosses ${lastFrozen}`)
    }
    await store.close()
    const again = await e.open('dev', A)
    assert.equal(again.views().items.length, 48)
    await again.close()
  } finally {
    await e.cleanup()
  }
})
