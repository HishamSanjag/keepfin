// Storage › Change location through the real boot: request while live, the next start runs the move with the library closed,
// and the start after that opens the folder from the new place with every space and the download folder following it.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {MoveProgress, SetupProgress, StorageState} from '../../shared/storage.js'
import {bootKeepfinFolder, type KeepfinBootDeps} from '../keepfinBoot.js'
import {DownloadManager} from '../manager.js'
import {FakeEngine} from '../fake-engine.js'
import {LibraryIndex} from '../library/index.js'
import {makeWorld} from './migration/fixtures.js'
import {loadProfiles} from './profiles.js'

function deps(deviceDir: string) {
  const events: {setup?: SetupProgress; move?: MoveProgress} = {}
  let relaunched = 0
  const value: KeepfinBootDeps = {
    deviceDir,
    userMusicDir: path.join(deviceDir, 'UserMusic'),
    appVersion: '0.0.0-test',
    pickFolder: async () => undefined,
    openPath: async () => undefined,
    shortcuts: {writeShortcutLink: () => true, readShortcutLink: () => ({target: ''})},
    trash: (target) => fs.rm(target, {recursive: true, force: true}),
    saveReportDialog: async () => true,
    push: (event) => {
      if (event.type === 'setup') events.setup = event.progress
      if (event.type === 'storageMove') events.move = event.progress
    },
    pause: () => undefined,
    resume: () => undefined,
    relaunch: () => void relaunched++,
  }
  return {value, events, relaunches: () => relaunched}
}
// up to 20 s: a copy run asks Windows for the drive format once per process, which takes seconds on slow CI machines
const until = async (check: () => boolean) => {
  for (let i = 0; i < 2000 && !check(); i++) await new Promise((resolve) => setTimeout(resolve, 10))
  assert.ok(check(), 'timed out')
}

test('change location: request while live, move before the library opens, then open from the new place', async () => {
  const world = await makeWorld({loose: false})
  try {
    const first = deps(world.deviceDir)
    const setup = await bootKeepfinFolder(first.value)
    const plan = (await setup.handle('setupPlan', {path: world.target, choices: {downloads: 'move', music: 'move', videos: 'move'}})) as {runId: string}
    await setup.handle('setupStart', {runId: plan.runId})
    await until(() => first.events.setup?.phase === 'done')
    await setup.close()

    // live: refused places, then the request (Keepfin restarts)
    const live = deps(world.deviceDir)
    const before = await bootKeepfinFolder(live.value)
    assert.equal(before.live, true)
    const root = path.resolve(world.target)
    const moved = path.join(path.dirname(root), 'Moved', 'Keepfin')
    await assert.rejects(before.handle('storageMoveRequest', {path: path.join(root, 'Music', 'inside')}), /inside your Keepfin folder/)
    await assert.rejects(before.handle('storageMoveRequest', {path: ''}), /Choose a folder/)
    await before.handle('storageMoveRequest', {path: moved})
    await until(() => live.relaunches() === 1)
    await before.close()

    // the next start: the library stays closed and the move window runs the move
    const window = deps(world.deviceDir)
    const moving = await bootKeepfinFolder(window.value)
    assert.equal(moving.live, false)
    assert.equal(((await moving.handle('storageState', {})) as StorageState).phase, 'moveFolder')
    await moving.handle('storageMoveStart', {})
    await until(() => window.events.move?.phase === 'done' || window.events.move?.phase === 'failed')
    assert.equal(window.events.move?.phase, 'done', window.events.move?.error ?? 'the move finished')
    assert.equal(((await moving.handle('storageState', {})) as StorageState).phase, 'ready')
    await moving.close()
    assert.equal((await loadProfiles(world.deviceDir))!.profiles[0].folder, moved)
    assert.equal(await fs.stat(root).catch(() => undefined), undefined, 'the old folder is gone')

    // and the start after that opens from the new place; downloads follow it
    const after = await bootKeepfinFolder(deps(world.deviceDir).value)
    assert.equal(after.live, true)
    assert.equal(after.root, moved)
    const index = new LibraryIndex({
      file: path.join(world.deviceDir, 'cache', 'library.json'), artDir: path.join(world.deviceDir, 'library-art'), ffmpeg: () => undefined,
      persistence: after.persistence, saveDelay: 10, pushDelay: 5, watch: false, analyse: false,
    })
    await index.init()
    const manager = new DownloadManager({store: path.join(world.deviceDir, 'queue.json'), defaultOutDir: world.downloads, engine: new FakeEngine(), library: index, snapshotDelay: 5, progressInterval: 20})
    try {
      await manager.init()
      const settings = manager.getSettings()
      for (const space of settings.library.spaces.filter((entry) => entry.type !== 'smart')) assert.ok(space.folder.startsWith(moved), `${space.id} follows the move: ${space.folder}`)
      assert.ok(settings.outDir.startsWith(moved), `the download folder follows the move: ${settings.outDir}`)
      assert.ok(index.snapshot().items.length > 0 && index.snapshot().items.every((item) => item.path.startsWith(moved)), 'every item is in the new place')
    } finally {
      await manager.close()
      await index.close()
      await after.close()
    }
  } finally {
    await world.cleanup()
  }
})

/** The library and downloads as main.ts opens them: the index adopts finished downloads it does not have yet. */
async function openLibrary(deviceDir: string, boot: Awaited<ReturnType<typeof bootKeepfinFolder>>, downloads: string) {
  let manager: DownloadManager | undefined
  const index = new LibraryIndex({
    file: path.join(deviceDir, 'cache', 'library.json'), artDir: path.join(deviceDir, 'library-art'), ffmpeg: () => undefined,
    persistence: boot.persistence, saveDelay: 10, pushDelay: 5, watch: false, analyse: false, jobs: () => manager?.completedJobs() ?? [],
  })
  await index.init()
  manager = new DownloadManager({store: path.join(deviceDir, 'queue.json'), defaultOutDir: downloads, engine: new FakeEngine(), library: index, snapshotDelay: 5, progressInterval: 20})
  await manager.init()
  await manager.followMovedFiles(deviceDir)
  await index.scan()
  const job1 = () => manager!.completedJobs().find((job) => job.id === 'job1')?.file
  return {index, job1, close: async () => (await manager!.close(), await index.close(), await boot.close())}
}

test('a move that keeps the old place: download history follows, so the next start does not add every file a second time', async () => {
  const world = await makeWorld({loose: false})
  try {
    const first = deps(world.deviceDir)
    const setup = await bootKeepfinFolder(first.value)
    const plan = (await setup.handle('setupPlan', {path: world.target, choices: {downloads: 'move', music: 'move', videos: 'move'}})) as {runId: string}
    await setup.handle('setupStart', {runId: plan.runId})
    await until(() => first.events.setup?.phase === 'done')
    await setup.close()
    const root = path.resolve(world.target)
    const moved = path.join(path.dirname(root), 'Moved', 'Keepfin')
    // the fixture's finished download, complete enough for the download manager to keep
    const queueFile = path.join(world.deviceDir, 'queue.json')
    const queue = JSON.parse(await fs.readFile(queueFile, 'utf8')) as {jobs: Record<string, unknown>[]}
    Object.assign(queue.jobs[0], {url: 'https://example.invalid/v', format: {type: 'audio', target: 'mp3', quality: 'best'}})
    await fs.writeFile(queueFile, JSON.stringify(queue))

    const before = await openLibrary(world.deviceDir, await bootKeepfinFolder(deps(world.deviceDir).value), world.downloads)
    const count = before.index.snapshot().items.length
    assert.ok(before.job1()?.startsWith(root), `history points into the folder: ${before.job1()}`)
    await before.close()

    const run = deps(world.deviceDir)
    run.value.closeLibrary = async () => undefined
    const boot = await bootKeepfinFolder(run.value)
    await boot.handle('storageMoveRequest', {path: moved, keepOld: true})
    await boot.handle('storageMoveStart', {})
    await until(() => run.events.move?.phase === 'done' || run.events.move?.phase === 'failed')
    assert.equal(run.events.move?.phase, 'done', run.events.move?.error ?? 'the move finished')
    await boot.close()

    const after = await openLibrary(world.deviceDir, await bootKeepfinFolder(deps(world.deviceDir).value), world.downloads)
    try {
      const items = after.index.snapshot().items
      assert.equal(items.length, count, `no file was added twice: ${items.map((item) => item.path).join(', ')}`)
      assert.ok(items.every((item) => item.path.startsWith(moved)), 'every item is in the new place')
      assert.ok(after.job1()?.startsWith(moved), `history follows the move: ${after.job1()}`)
    } finally {
      await after.close()
    }
  } finally {
    await world.cleanup()
  }
})

test('change location in one run:the library closes in place, the move runs, one restart; the old place becomes a backup copy', async () => {
  const world = await makeWorld({loose: false})
  try {
    const first = deps(world.deviceDir)
    const setup = await bootKeepfinFolder(first.value)
    const plan = (await setup.handle('setupPlan', {path: world.target, choices: {downloads: 'move', music: 'move', videos: 'move'}})) as {runId: string}
    await setup.handle('setupStart', {runId: plan.runId})
    await until(() => first.events.setup?.phase === 'done')
    await setup.close()

    const run = deps(world.deviceDir)
    let closed = 0
    run.value.closeLibrary = async () => void closed++
    const states: StorageState[] = []
    const push = run.value.push
    run.value.push = (event) => (event.type === 'storage' && states.push(event.state), push(event))
    const boot = await bootKeepfinFolder(run.value)
    assert.equal(boot.live, true)
    const root = path.resolve(world.target)
    const moved = path.join(path.dirname(root), 'Moved', 'Keepfin')
    await boot.handle('storageMoveRequest', {path: moved, keepOld: true})
    assert.equal(closed, 1, 'main closed downloads and the index')
    assert.equal(run.relaunches(), 0, 'no restart before the move')
    assert.equal(states.at(-1)?.phase, 'moveFolder', 'the window switches to the move')
    await boot.handle('storageMoveStart', {})
    await until(() => run.events.move?.phase === 'done' || run.events.move?.phase === 'failed')
    assert.equal(run.events.move?.phase, 'done', run.events.move?.error ?? 'the move finished')
    assert.equal(((await boot.handle('storageState', {})) as StorageState).phase, 'ready')
    await until(() => run.relaunches() === 1)
    await boot.close()
    assert.equal((await loadProfiles(world.deviceDir))!.profiles[0].folder, moved)
    assert.equal(await fs.stat(path.join(root, '.keepfin')).catch(() => undefined), undefined, 'the old place has no library records')
    assert.ok((await fs.readdir(root)).length > 0, 'the old place keeps its files')

    // the restart: the library opens from the new place and the old place is added under Copies as a backup
    const after = await bootKeepfinFolder(deps(world.deviceDir).value)
    try {
      assert.equal(after.root, moved)
      let places: {name: string; mode: string}[] = []
      for (let i = 0; i < 300 && !places.some((p) => p.name === 'Backup (old place)'); i++) {
        places = (((await after.handleCopies('copiesState', {})) as {places: {name: string; mode: string}[]}).places) ?? []
        await new Promise((resolve) => setTimeout(resolve, 20))
      }
      const backup = places.find((p) => p.name === 'Backup (old place)')
      assert.ok(backup, `the old place is a copy: ${JSON.stringify(places.map((p) => p.name))}`)
      assert.equal(backup!.mode, 'backup')
    } finally {
      await after.close()
    }
  } finally {
    await world.cleanup()
  }
})
