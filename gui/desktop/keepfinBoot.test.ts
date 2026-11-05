import {test} from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import type {SetupProgress, StorageState} from '../shared/storage.js'
import {bootKeepfinFolder, type KeepfinBootDeps} from './keepfinBoot.js'
import {makeWorld} from './store/migration/fixtures.js'

function deps(deviceDir: string, extra: Partial<KeepfinBootDeps> = {}) {
  const log = {relaunches: 0, pushes: [] as string[], progress: [] as SetupProgress[]}
  let finish: (value: SetupProgress) => void = () => undefined
  const finished = new Promise<SetupProgress>((resolve) => (finish = resolve))
  const value: KeepfinBootDeps = {
    deviceDir,
    userMusicDir: path.join(deviceDir, 'UserMusic'),
    appVersion: '0.0.0-test',
    pickFolder: async () => undefined,
    openPath: async () => undefined,
    shortcuts: {writeShortcutLink: () => true, readShortcutLink: () => ({target: ''})},
    trash: async () => undefined,
    saveReportDialog: async () => true,
    push: (event) => {
      log.pushes.push(event.type)
      if (event.type === 'setup') {
        log.progress.push(event.progress)
        if (['done', 'failed', 'stoppedDiskFull'].includes(event.progress.phase)) finish(event.progress)
      }
    },
    pause: () => undefined,
    resume: () => undefined,
    relaunch: () => void log.relaunches++,
    ...extra,
  }
  return {value, log, finished}
}

test('an old library is set up through the boot wiring, then the next start opens it', async () => {
  const world = await makeWorld()
  try {
    const first = deps(world.deviceDir)
    const boot = await bootKeepfinFolder(first.value)
    assert.equal(boot.live, false)
    assert.equal(((await boot.handle('storageState', {})) as StorageState).phase, 'needsSetup')
    const plan = (await boot.handle('setupPlan', {path: world.target, choices: {music: 'copy', downloads: 'copy', videos: 'copy'}})) as {runId: string}
    await boot.handle('setupStart', {runId: plan.runId})
    const done = await first.finished
    assert.equal(done.phase, 'done')
    assert.equal(first.log.relaunches, 0)
    // Opening the gate again after the switch restarts the app once, onto the new library.
    assert.equal(((await boot.handle('storageState', {})) as StorageState).phase, 'ready')
    await new Promise((resolve) => setTimeout(resolve, 600))
    assert.equal(first.log.relaunches, 1)
    await boot.close()

    const second = await bootKeepfinFolder(deps(world.deviceDir).value)
    assert.equal(second.live, true)
    const overview = (await second.handle('storageOverview', {})) as {path: string}
    assert.equal(overview.path, path.resolve(world.target))
    assert.ok(second.persistence)
    const loaded = await second.persistence.load()
    assert.ok(loaded.items.length > 0, 'the old items came over')
    await second.close()
  } finally {
    await world.cleanup()
  }
})

test('first start creates a folder and the next boot opens it', async () => {
  const world = await makeWorld()
  try {
    const empty = path.join(world.root, 'Fresh')
    const device = path.join(world.root, 'FreshDevice')
    const boot = await bootKeepfinFolder(deps(device).value)
    assert.equal(((await boot.handle('storageState', {})) as StorageState).phase, 'firstStart')
    const created = (await boot.handle('storageCreate', {path: path.join(empty, 'Keepfin'), musicShortcut: false})) as StorageState
    assert.equal(created.phase, 'ready')
    await boot.close()
    const again = await bootKeepfinFolder(deps(device).value)
    assert.equal(again.live, true)
    await again.close()
  } finally {
    await world.cleanup()
  }
})
