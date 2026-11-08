// What data compaction changes on the copy side (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md R4, R5, R6): three manifests per side,
// covered segment files never copied and pruned from a copy only when the main folder dropped them, the mirror's prune.
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {test} from 'node:test'
import {generateDeviceKey} from '../../shared/core/net.js'
import {encodeSegment, hlcTick, makeOp} from '../../shared/core/sync.js'
import {redundantIn} from '../cloud/mirror.js'
import {createKeepfinFolder} from '../store/keepfinFolder.js'
import {latestManifest, writeManifest} from './manifest.js'
import {copyMetadata} from './placeRun.js'
import {listOps, pruneCoveredSegments} from './segmentPrune.js'

const ACTOR = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const key = generateDeviceKey()

async function folders() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-copy-compact-'))
  const libraryId = randomUUID()
  const main = await createKeepfinFolder(path.join(dir, 'Main'), {libraryId, replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
  const copy = path.join(dir, 'Copy')
  await fs.mkdir(path.join(copy, '.keepfin'), {recursive: true})
  return {dir, libraryId, main: main.root, copy, cleanup: () => fs.rm(dir, {recursive: true, force: true})}
}

const ops = (from: number, to: number) => {
  const out: string[] = []
  for (let seq = from; seq <= to; seq++) out.push(makeOp(ACTOR, seq, hlcTick(null, 1_700_000_000_000 + seq, ACTOR), 'profile.set', {field: 'name', value: `v${seq}`}))
  return out
}
const segment = async (root: string, libraryId: string, lines: string[], from: number) => {
  const to = from + lines.length - 1
  const file = path.join(root, '.keepfin', 'ops', ACTOR, `${String(from).padStart(16, '0')}-${String(to).padStart(16, '0')}.seg`)
  await fs.mkdir(path.dirname(file), {recursive: true})
  await fs.writeFile(file, encodeSegment(libraryId, ACTOR, lines, key))
  return file
}
const names = async (root: string) => (await listOps(path.join(root, '.keepfin', 'ops'))).map((f) => f.name)

test('a place keeps its newest three manifests on each side and numbers on from the highest', async () => {
  const f = await folders()
  try {
    const state = path.join(f.dir, 'state')
    await fs.mkdir(state, {recursive: true})
    await fs.writeFile(path.join(state, 'baseline.jsonl'), '')
    const results = []
    for (let run = 0; run < 7; run++) {
      results.push(await writeManifest({stateDir: state, mainRoot: f.main, copyRoot: f.copy, placeId: 'p1', generation: run, holdsKey: 'h', optionsKey: 'o', now: 1000 + run}))
    }
    assert.deepEqual(results.map((r) => r.n), [1, 2, 3, 4, 5, 6, 7])
    for (const root of [f.main, f.copy]) {
      const kept = (await fs.readdir(path.join(root, '.keepfin', 'places', 'p1'))).sort()
      assert.deepEqual(kept, ['manifest-5.jsonl', 'manifest-6.jsonl', 'manifest-7.jsonl'])
    }
    assert.equal((await latestManifest(f.copy, 'p1'))!.header.n, 7)
  } finally {
    await f.cleanup()
  }
})

test('copyMetadata does not copy a segment that a larger main file covers, and prunes covered ones the main dropped', async () => {
  const f = await folders()
  try {
    const all = ops(1, 6)
    // main: the merged file and (not yet dropped) the small files it covers
    await segment(f.main, f.libraryId, all, 1)
    await segment(f.main, f.libraryId, all.slice(0, 2), 1)
    await segment(f.main, f.libraryId, all.slice(2, 4), 3)
    // the copy still holds an old pile that main dropped long ago: 7..8 and 9..10, and now the merged file for 7..10 arrives later
    const copied = await copyMetadata(f.main, f.copy)
    assert.equal(copied, 1, 'only the merged file is copied')
    assert.deepEqual(await names(f.copy), ['0000000000000001-0000000000000006.seg'])

    // main now holds 1..6 and 7..12 (merged); the copy has 7..8 and 9..10 from before
    const more = ops(7, 12)
    await fs.rm(path.join(f.main, '.keepfin', 'ops'), {recursive: true})
    await segment(f.main, f.libraryId, all, 1)
    await segment(f.main, f.libraryId, more, 7)
    await segment(f.copy, f.libraryId, more.slice(0, 2), 7)
    await segment(f.copy, f.libraryId, more.slice(2, 4), 9)
    await copyMetadata(f.main, f.copy)
    assert.deepEqual(await names(f.copy), ['0000000000000001-0000000000000006.seg', '0000000000000007-0000000000000012.seg'])
  } finally {
    await f.cleanup()
  }
})

test('a copy keeps a covered file that the main folder still lists', async () => {
  const f = await folders()
  try {
    const all = ops(1, 4)
    await segment(f.main, f.libraryId, all.slice(0, 2), 1)
    await segment(f.main, f.libraryId, all, 1)
    await segment(f.copy, f.libraryId, all.slice(0, 2), 1)
    await segment(f.copy, f.libraryId, all, 1)
    await copyMetadata(f.main, f.copy)
    assert.equal((await names(f.copy)).length, 2)
  } finally {
    await f.cleanup()
  }
})

test('a covered file whose ops differ from the covering file is not removed', async () => {
  const f = await folders()
  try {
    const all = ops(1, 4)
    const forged = [makeOp(ACTOR, 1, hlcTick(null, 1_900_000_000_000, ACTOR), 'profile.set', {field: 'name', value: 'forged'}), all[1]]
    await segment(f.copy, f.libraryId, all, 1)
    await segment(f.copy, f.libraryId, forged, 1)
    assert.equal(await pruneCoveredSegments(path.join(f.copy, '.keepfin', 'ops'), f.libraryId), 0)
    assert.equal((await names(f.copy)).length, 2)
  } finally {
    await f.cleanup()
  }
})

test('a file that does not decode leaves its actor alone', async () => {
  const f = await folders()
  try {
    const all = ops(1, 4)
    await segment(f.copy, f.libraryId, all, 1)
    const small = await segment(f.copy, f.libraryId, all.slice(0, 2), 1)
    await fs.writeFile(small, 'not a segment')
    assert.equal(await pruneCoveredSegments(path.join(f.copy, '.keepfin', 'ops'), f.libraryId), 0)
    assert.equal((await names(f.copy)).length, 2)
  } finally {
    await f.cleanup()
  }
})

test('the mirror lists only covered segments and old manifests as redundant', () => {
  const seg = (a: number, b: number) => `.keepfin/ops/${ACTOR}/${String(a).padStart(16, '0')}-${String(b).padStart(16, '0')}.seg`
  const held = [seg(1, 10), seg(11, 12), '.keepfin/places/p/manifest-7.jsonl', '.keepfin/places/p/manifest-6.jsonl', '.keepfin/places/p/manifest-5.jsonl', '.keepfin/places/p/manifest-4.jsonl']
  const candidates = [seg(1, 4), seg(5, 10), seg(11, 12), seg(11, 20), '.keepfin/places/p/manifest-3.jsonl', '.keepfin/places/p/manifest-4.jsonl', '.keepfin/places/p/manifest-6.jsonl', '.keepfin/folder.json', 'Music/a.mp3']
  assert.deepEqual([...redundantIn(candidates, held)].sort(), [seg(1, 4), seg(5, 10), '.keepfin/places/p/manifest-3.jsonl', '.keepfin/places/p/manifest-4.jsonl'].sort())
})
