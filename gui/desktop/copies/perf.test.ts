// A measurement, not a pass/fail rule: a local copy run over 30 media files and 300 small data-sized files. It counts the
// durable writes (fsync) and times the run at several parallelism levels. Run with KEEPFIN_PERF=1 to print the numbers.
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {afterEach, describe, it} from 'node:test'
import type {DesiredEntry} from '../../shared/core/copies.js'
import {makeWorld, type World} from './testkit.js'

let w: World | null = null
afterEach(async () => {
  await w?.cleanup()
  w = null
})

async function build(world: World): Promise<DesiredEntry[]> {
  const when = new Date('2022-02-02')
  const out: DesiredEntry[] = []
  const add = async (rel: string, body: Buffer) => {
    const file = path.join(world.main, ...rel.split('/'))
    await fs.mkdir(path.dirname(file), {recursive: true})
    await fs.writeFile(file, body)
    await fs.utimes(file, when, when)
    out.push({path: rel, itemId: `i:${rel}`, revisionOp: 'r1', contentId: 'sha256:' + crypto.createHash('sha256').update(body).digest('hex'), bytes: body.length})
  }
  for (let i = 0; i < 30; i++) await add(`Music/Album ${i % 5}/track ${i}.mp3`, crypto.randomBytes(1024 * 1024))
  for (let i = 0; i < 300; i++) await add(`Data/${i % 10}/part-${i}.seg`, Buffer.from(`{"n":${i}}\n`.repeat(40)))
  return out
}

describe('local copy cost (measurement)', () => {
  for (const parallelism of [2, 4, 8]) {
    it(`copies 330 files at parallelism ${parallelism}`, {timeout: 300_000}, async () => {
      w = await makeWorld('kf-perf-')
      const desired = await build(w)
      const probe = await fs.open(path.join(w.root, 'probe'), 'w')
      const proto = Object.getPrototypeOf(probe) as {sync: () => Promise<void>}
      const original = proto.sync
      let syncs = 0
      proto.sync = function (this: unknown) {
        syncs++
        return original.call(this)
      }
      let report
      const t0 = Date.now()
      try {
        report = await w.run({desiredFrom: desired, previewConfirmed: true, parallelism})
      } finally {
        proto.sync = original
        await probe.close()
      }
      const ms = Date.now() - t0
      assert.equal(report.status, 'done')
      assert.equal(report.exec?.byKind.CopyTo, 330)
      if (process.env.KEEPFIN_PERF) console.log(`LOCAL parallelism ${parallelism}: ${ms} ms, ${syncs} fsyncs (${(syncs / 330).toFixed(2)}/file), timings ${JSON.stringify(report.timings)}`)
    })
  }
})
