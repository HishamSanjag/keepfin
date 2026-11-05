// A scale run of the whole pipeline on real files: inventory both sides from disk, plan, copy everything, plan again (empty).
// 20,000 files by default; KEEPFIN_SCALE=200000 for the full budget run.
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {afterEach, describe, it} from 'node:test'
import type {DesiredEntry} from '../../shared/core/copies.js'
import {makeWorld, type World} from './testkit.js'

const N = Number(process.env.KEEPFIN_SCALE || 2_000)
let w: World | null = null
afterEach(async () => {
  await w?.cleanup()
  w = null
})

describe(`scale (${N} files)`, () => {
  it('copies, then plans nothing, with the inventories streamed from disk', {timeout: 3_600_000}, async () => {
    w = await makeWorld('kf-scale-')
    const when = new Date('2022-02-02')
    const desired: DesiredEntry[] = []
    const t0 = Date.now()
    const dirs = new Set<string>()
    for (let i = 0; i < N; i++) {
      const dir = `Music/Artist ${i % 200}/Album ${i % 37}`
      if (!dirs.has(dir)) {
        await fs.mkdir(path.join(w.main, ...dir.split('/')), {recursive: true})
        dirs.add(dir)
      }
      const rel = `${dir}/track ${String(i).padStart(6, '0')}.mp3`
      const body = `track number ${i} ${'x'.repeat(i % 50)}`
      const file = path.join(w.main, ...rel.split('/'))
      await fs.writeFile(file, body)
      await fs.utimes(file, when, when)
      desired.push({path: rel, itemId: `item-${i}`, revisionOp: 'r1', contentId: 'sha256:' + crypto.createHash('sha256').update(body).digest('hex'), bytes: body.length})
    }
    const createMs = Date.now() - t0

    const rss0 = process.memoryUsage().rss
    let peak = 0
    const sampler = setInterval(() => (peak = Math.max(peak, process.memoryUsage().rss)), 200)
    const t1 = Date.now()
    const first = await w.run({desiredFrom: desired, previewConfirmed: true, parallelism: 4})
    const firstMs = Date.now() - t1
    const t2 = Date.now()
    const second = await w.run({desiredFrom: desired, previewConfirmed: true, parallelism: 4})
    const secondMs = Date.now() - t2
    clearInterval(sampler)
    peak = Math.max(peak, process.memoryUsage().rss)

    assert.equal(first.status, 'done')
    assert.equal(first.exec?.byKind.CopyTo, N)
    assert.equal(first.exec?.failedCount, 0)
    assert.equal(first.exec?.skippedCount, 0)
    assert.equal(second.exec, null, 'the second plan is empty')
    const copied = (await w.list('copy')).length
    assert.equal(copied, N)
    console.log(
      `scale ${N}: create ${createMs} ms; first run ${firstMs} ms (${JSON.stringify(first.timings)}); ` +
        `second run ${secondMs} ms (${JSON.stringify(second.timings)}); peak RSS ${(peak / 1048576).toFixed(0)} MB (start ${(rss0 / 1048576).toFixed(0)} MB); max RSS ${((process.resourceUsage().maxRSS) / 1024).toFixed(0)} MB`,
    )
  })
})
