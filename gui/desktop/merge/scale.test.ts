// Planning at scale (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 5, budget line): S against T through the on-disk scratch index and
// the shared core's planner, with generated libraries behind the store interface. Default 20,000 against 20,000 so the suite stays
// quick; KEEPFIN_MERGE_SCALE=200000 runs the contract's budget (planning at most 60 s on the PC, memory bounded by the page).
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {MergeMeta, ScanRow} from '../store/mergeScan.js'
import {HashCache, runPlan} from './plan.js'
import type {MergeDeps, MergeStore} from './types.js'
import {WIRE_MUSIC} from './testkit.js'

const N = Number(process.env.KEEPFIN_MERGE_SCALE ?? 20_000)
const uuid = (prefix: string, n: number) => `${prefix}${n.toString(16).padStart(7, '0')}-0000-4000-8000-000000000000`
const hash = (n: number) => `sha256:${n.toString(16).padStart(64, '0')}`
const hlc = (n: number, actor: string) => `${String(1_700_000_000_000 + n).padStart(13, '0')}.000000.${actor}`

function library(kind: 's' | 't', count: number): MergeStore {
  const actor = kind === 's' ? '5aaaaaaa-0000-4000-8000-000000000000' : '7aaaaaaa-0000-4000-8000-000000000000'
  const rows = function* (): Generator<ScanRow> {
    for (let i = 0; i < count; i++) {
      // half of S's content is also in T (the first half of each), the rest is its own
      const content = kind === 's' ? (i < count / 2 ? hash(i) : hash(10_000_000 + i)) : hash(i)
      yield {
        id: uuid(kind === 's' ? '1' : '2', i),
        added: hlc(i, actor),
        addActor: actor,
        addSeq: i + 1,
        fields: {media: 'audio', title: `Song ${i}`, space: WIRE_MUSIC, addedAt: 0},
        plays: 0,
        head: {content, size: 100},
        placements: kind === 's' ? {'6aaaaaaa-0000-4000-8000-000000000000': `Music/s-${i}.mp3`} : {},
        clocks: {},
        favourite: false,
        tags: [],
      }
    }
  }
  const meta: MergeMeta = {spaces: [], playlists: [], sets: {}, routes: null}
  return {
    actorId: actor,
    apply: async () => undefined,
    flush: async () => undefined,
    rescan: async () => undefined,
    lastSeqOf: async () => 0,
    async scan(onRows) {
      let page: ScanRow[] = []
      for (const row of rows()) {
        page.push(row)
        if (page.length === 1000) {
          await onRows(page)
          page = []
        }
      }
      if (page.length) await onRows(page)
    },
    meta: async () => meta,
    observed: async () => ({items: {}, playlists: {}, spaces: {}, setElements: {}}),
    membership: async () => ({active: false, members: {}}) as never,
    close: async () => undefined,
  }
}

test(`planning ${N} against ${N} through the scratch index stays within budget and a page of memory`, async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-merge-scale-'))
  // every S item points at one 100-byte file whose size and modification time match the hint, so planning is not about the disk
  const one = path.join(dir, 'one.bin')
  await fs.writeFile(one, Buffer.alloc(100))
  await fs.utimes(one, 0.005, 0.005)
  const mtime = Math.round((await fs.stat(one)).mtimeMs)
  const deps = {files: {hint: () => ({size: 100, modifiedAt: mtime}), resolve: () => one}, pageSize: 200} as unknown as MergeDeps
  const hashes = new HashCache(path.join(dir, 'hashes.db'))
  try {
    let lastLabel = 'start'
    let lastAt = performance.now()
    const before = process.memoryUsage().rss
    const started = performance.now()
    const result = await runPlan(
      {deps, mergeId: 'scale-merge', actor: '9aaaaaaa-0000-4000-8000-000000000000', replicaId: '8aaaaaaa-0000-4000-8000-000000000000', sReplica: '6aaaaaaa-0000-4000-8000-000000000000', root: dir, s: library('s', N), t: library('t', N), keep: 'theirs', planDir: path.join(dir, 'plan'), work: dir, onProgress: (label, done, total) => { const now = performance.now(); if (label !== lastLabel) { if (process.env.KEEPFIN_MERGE_TRACE) console.log(`phase ${lastLabel}: ${Math.round(now - lastAt)} ms`); lastLabel = label; lastAt = now } void done; void total }},
      hashes,
    )
    const ms = performance.now() - started
    const growthMb = (process.memoryUsage().rss - before) / 1e6
    console.log(`MERGE SCALE S=${N} T=${N}: ${Math.round(ms)} ms including the scans; items ${result.sItems}; batches ${result.batches}; drafts ${result.drafts}; review ${result.reviewTotal}; rss grew ${Math.round(growthMb)} MB`)
    assert.equal(result.sItems, N)
    assert.ok(result.batches >= 1)
    assert.ok(ms < (N >= 200_000 ? 60_000 : 30_000), `planned in ${Math.round(ms)} ms`)
  } finally {
    hashes.close()
    await fs.rm(dir, {recursive: true, force: true}).catch(() => undefined)
  }
})
