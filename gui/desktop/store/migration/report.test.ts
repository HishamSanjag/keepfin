import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { takeInventory } from './inventory.js'
import { buildPlan } from './plan.js'
import { readJournal } from './journal.js'
import { startRun } from './runner.js'
import { buildReport, renderReport } from './report.js'
import { makeWorld } from './fixtures.js'

async function runWorld(vanish: boolean) {
  const world = await makeWorld({tracks: 20, downloads: 8})
  const inventory = await takeInventory(world.deviceDir)
  let n = 0
  const plan = await buildPlan(inventory, {target: world.target, choices: {music: 'move', downloads: 'copy'}, newId: () => `u${++n}`, runId: 'run1', now: () => 1})
  if (vanish) await fs.rm(plan.files.find((file) => file.rel === 'Music/Song 2.mp3')!.source)
  const result = await startRun(world.runDir, plan, {
    commit: async () => undefined,
    trash: async (file) => fs.rm(file),
    sameVolume: async () => false,
  }, {acceptFailures: true})
  return {world, plan, result, fold: await readJournal(world.runDir)}
}

test('the report counts before and after, with nothing lost when everything arrives', async () => {
  const {world, plan, fold} = await runWorld(false)
  try {
    const report = buildReport(plan, fold)
    assert.equal(report.status, 'attention', 'the library has a missing file that needs attention')
    assert.deepEqual(report.attention.map((item) => item.kind), ['missing'])
    assert.deepEqual(report.attention[0].actions, ['locate', 'leave'])
    assert.deepEqual(report.after, report.before)
    assert.equal(report.before.media, 4 + 20 + 8 + 3)
    assert.ok(report.before.sidecars > 4 && report.before.art > 1)
    assert.equal(report.before.favourites, 2 + 4)
  } finally {
    await world.cleanup()
  }
})

test('a file that did not arrive lowers the after count and is listed with its options', async () => {
  const {world, plan, fold, result} = await runWorld(true)
  try {
    assert.equal(result.failed.length, 1)
    const report = buildReport(plan, fold)
    assert.equal(report.after.media, report.before.media - 1)
    const failed = report.attention.find((item) => item.path.endsWith('Song 2.mp3'))
    assert.deepEqual(failed?.actions, ['retry', 'locate', 'leave'])
    const text = renderReport(report)
    assert.match(text, /A few files need your attention/)
    assert.match(text, /Song 2\.mp3/)
    assert.match(text, /Retry, Locate, Leave it/)
    assert.doesNotMatch(text, /yt-dlp|ffmpeg|gallery-dl|aria2/i)
  } finally {
    await world.cleanup()
  }
})

test('before the switch the report says nothing has changed', async () => {
  const world = await makeWorld({loose: false})
  try {
    const inventory = await takeInventory(world.deviceDir)
    let n = 0
    const plan = await buildPlan(inventory, {target: world.target, choices: {}, newId: () => `u${++n}`, runId: 'r', now: () => 1})
    const report = buildReport(plan, await readJournal(world.runDir))
    assert.equal(report.status, 'unfinished')
    assert.equal(report.after.media, 0)
    assert.match(renderReport(report), /old library still works/)
  } finally {
    await world.cleanup()
  }
})
