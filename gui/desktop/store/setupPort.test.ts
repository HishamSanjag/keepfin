import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {SetupProgress} from '../../shared/storage.js'
import {createSetupPort, type SetupPortDeps} from './setupPort.js'
import {InjectedCrash} from './migration/journal.js'
import {copyHashing} from './migration/io.js'
import {makeWorld, type World} from './migration/fixtures.js'

function setup(world: World, overrides: Partial<SetupPortDeps> = {}) {
  let n = 0
  let clock = 1_000_000
  const progress: SetupProgress[] = []
  const saved: string[] = []
  const state = {commits: 0, paused: 0, resumed: 0}
  let finish: (value: SetupProgress) => void = () => undefined
  const finished = new Promise<SetupProgress>((resolve) => (finish = resolve))
  const port = createSetupPort({
    deviceDir: world.deviceDir,
    defaultTarget: world.target,
    newId: () => `id${++n}`,
    now: () => (clock += 300),
    trash: async (file) => fs.rm(file, {force: true}),
    pause: () => void state.paused++,
    resume: () => void state.resumed++,
    push: (item) => {
      progress.push(item)
      if (['done', 'failed', 'stoppedDiskFull'].includes(item.phase)) finish(item)
    },
    saveReportDialog: async (text) => {
      saved.push(text)
      return true
    },
    commit: async () => void state.commits++,
    ...overrides,
  })
  return {port, progress, saved, state, finished}
}

test('inventory lists one source per space folder with counts and a suggested choice', async () => {
  const world = await makeWorld()
  try {
    const {port} = setup(world)
    const inventory = await port.inventory()
    assert.deepEqual(inventory.sources.map((source) => source.id).sort(), ['downloads', 'music', 'videos'])
    const music = inventory.sources.find((source) => source.id === 'music')
    assert.equal(music?.name, 'Music')
    assert.equal(music?.path, world.music)
    assert.ok((music?.files ?? 0) >= 7 && (music?.bytes ?? 0) > 0)
    assert.equal(music?.suggested, 'move', 'same drive as the default target')
    assert.equal(music?.sameDrive, undefined)
    assert.equal(inventory.unindexed, 3)
    assert.equal(inventory.missing, 1)
    assert.equal(inventory.counts.playlists, 1)

    const apart = setup(world, {engine: {sameVolume: async () => false}})
    assert.ok((await apart.port.inventory()).sources.every((source) => source.suggested === 'copy'))
  } finally {
    await world.cleanup()
  }
})

test('plan writes the plan file and describes it in plain British English', async () => {
  const world = await makeWorld()
  try {
    const {port} = setup(world)
    await port.inventory()
    const plan = await port.plan(world.target, {music: 'move', downloads: 'copy', videos: 'leave'})
    assert.match(plan.runId, /^id\d+$/)
    assert.equal(plan.target, world.target)
    assert.ok(plan.fits)
    assert.ok(plan.freeBytes === undefined || plan.freeBytes > 0)
    assert.ok(plan.files > 10)
    assert.ok(plan.lines.some((line) => /^Move \d+ files from Music \(same drive, instant\)$/.test(line)), plan.lines.join('|'))
    assert.ok(plan.lines.some((line) => /^Copy \d+ files from Downloads \(\d+(\.\d)? (KB|MB|GB)\)$/.test(line)))
    assert.ok(plan.lines.includes('Leave Videos where it is (linked)'))
    assert.ok(plan.lines.some((line) => line === 'Bring over 1 playlist, 1 collection, 2 favourites and your play history'), plan.lines.join('|'))
    assert.ok(plan.bytesToCopy > 0, 'copies and art count')
    for (const line of plan.lines) assert.doesNotMatch(line, /yt-dlp|ffmpeg|gallery-dl|aria2/i)
    assert.ok(await fs.stat(path.join(world.deviceDir, 'migration', plan.runId, 'plan.json')))

    const apart = setup(world, {engine: {sameVolume: async () => false}})
    const crossing = await apart.port.plan(world.target, {music: 'move'})
    assert.ok(crossing.lines.some((line) => /Move \d+ files from Music \(.* (KB|MB|GB), copied then removed\)/.test(line)))
    assert.ok(crossing.bytesToCopy > plan.bytesToCopy - 1)
  } finally {
    await world.cleanup()
  }
})

test('start runs in the background, maps progress to phases, commits and reports done', async () => {
  const world = await makeWorld()
  try {
    const {port, progress, state, finished} = setup(world, {engine: {sameVolume: async () => false}})
    await port.inventory()
    const plan = await port.plan(world.target, {music: 'move', downloads: 'copy'})
    await port.start(plan.runId, false)
    await assert.rejects(port.start(plan.runId, false), /already running/)
    const last = await finished
    assert.equal(last.phase, 'done')
    assert.equal(last.runId, plan.runId)
    assert.equal(last.failed, 0)
    assert.equal(progress[0].phase, 'preparing')
    const phases = new Set(progress.map((item) => item.phase))
    for (const phase of ['preparing', 'copying', 'saving', 'cleaning', 'done']) assert.ok(phases.has(phase as never), `saw ${phase}`)
    assert.ok(progress.every((item) => !item.current || !item.current.includes(path.sep)), 'file names only')
    assert.equal(state.commits, 1)
    assert.equal(state.paused, 1)
    assert.equal(state.resumed, 1)
    // Nothing left to resume.
    assert.equal(await port.unfinishedRun(), undefined)

    const report = await port.report(plan.runId)
    assert.equal(report.runId, plan.runId)
    assert.equal(report.after.media, report.before.media)
    assert.deepEqual(report.attention.map((item) => item.name), ['Missing.mp3'])
    assert.match(report.text, /Everything is in your Keepfin folder|need your attention/)
  } finally {
    await world.cleanup()
  }
})

test('saveReport hands the plain text to the save dialog', async () => {
  const world = await makeWorld({loose: false})
  try {
    const {port, saved, finished} = setup(world)
    await port.inventory()
    const plan = await port.plan(world.target, {})
    await port.start(plan.runId, false)
    await finished
    assert.equal(await port.saveReport(plan.runId), true)
    assert.equal(saved.length, 1)
    assert.match(saved[0], /Songs and videos/)
    await assert.rejects(port.report('../escape'), /Invalid/)
  } finally {
    await world.cleanup()
  }
})

test('a full disk ends in stoppedDiskFull and the run can be resumed', async () => {
  const world = await makeWorld({loose: false})
  try {
    let calls = 0
    const first = setup(world, {
      engine: {
        sameVolume: async () => false,
        copyFile: async (source, dest, options) => {
          if (++calls === 3) throw Object.assign(new Error('full'), {code: 'ENOSPC'})
          return copyHashing(source, dest, options)
        },
      },
    })
    await first.port.inventory()
    const plan = await first.port.plan(world.target, {})
    await first.port.start(plan.runId, false)
    const stopped = await first.finished
    assert.equal(stopped.phase, 'stoppedDiskFull')
    assert.equal(first.state.commits, 0)
    assert.equal(await first.port.unfinishedRun(), plan.runId)

    const second = setup(world, {engine: {sameVolume: async () => false}})
    await second.port.start(plan.runId, false)
    assert.equal((await second.finished).phase, 'done')
    assert.equal(second.state.commits, 1)
  } finally {
    await world.cleanup()
  }
})

test('files that failed make the run end as failed until failures are accepted', async () => {
  const world = await makeWorld({loose: false})
  try {
    const first = setup(world, {engine: {sameVolume: async () => false}})
    await first.port.inventory()
    const plan = await first.port.plan(world.target, {})
    await fs.rm(path.join(world.music, 'Song 2.mp3'))
    await first.port.start(plan.runId, false)
    const result = await first.finished
    assert.equal(result.phase, 'failed')
    assert.equal(result.failed, 1)
    assert.equal(first.state.commits, 0)
    assert.equal((await first.port.report(plan.runId)).attention.some((item) => item.name === 'Song 2.mp3' && item.actions.includes('retry')), true)

    const second = setup(world, {engine: {sameVolume: async () => false}})
    await second.port.start(plan.runId, true)
    const done = await second.finished
    assert.equal(done.phase, 'done')
    assert.equal(done.failed, 1)
  } finally {
    await world.cleanup()
  }
})

test('unfinishedRun finds the newest started run after a simulated crash, and abandon clears it', async () => {
  const world = await makeWorld({loose: false})
  try {
    const crashing = setup(world, {engine: {crash: {after: 4}}})
    await crashing.port.inventory()
    const plan = await crashing.port.plan(world.target, {})
    assert.equal(await crashing.port.unfinishedRun(), undefined, 'a plan that was only reviewed is not a run to resume')
    await crashing.port.start(plan.runId, false)
    assert.equal((await crashing.finished).phase, 'failed', 'the injected crash surfaces as failed')
    assert.equal(await crashing.port.unfinishedRun(), plan.runId)

    const fresh = setup(world)
    await fresh.port.abandon(plan.runId)
    assert.equal(await fresh.port.unfinishedRun(), undefined)
    for (const [file] of world.originals) assert.ok(await fs.stat(file), 'originals are back or untouched')
    void InjectedCrash
  } finally {
    await world.cleanup()
  }
})
