// The whole upgrade path with the real setup engine: an old-style profile is moved/copied into a new Keepfin folder, the app
// starts on it, and the first download lands inside the folder (settings, outDir and linked.json agree with the store).
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import type {FormatRequest} from '../../shared/contracts.js'
import type {SetupProgress} from '../../shared/storage.js'
import {bootKeepfinFolder, type KeepfinBootDeps} from '../keepfinBoot.js'
import {DownloadManager} from '../manager.js'
import {FakeEngine, fakeMedia} from '../fake-engine.js'
import {LibraryIndex} from '../library/index.js'
import {makeWorld} from './migration/fixtures.js'
import {loadProfiles, profileDir} from './profiles.js'

const video: FormatRequest = {type: 'video', codec: 'auto', container: 'mp4'}
const inside = (root: string, file: string) => {
  const relative = path.relative(root, file)
  return !!relative && !relative.startsWith('..') && !path.isAbsolute(relative)
}

function deps(deviceDir: string) {
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
      if (event.type === 'setup' && ['done', 'failed', 'stoppedDiskFull'].includes(event.progress.phase)) finish(event.progress)
    },
    pause: () => undefined,
    resume: () => undefined,
    relaunch: () => undefined,
  }
  return {value, finished}
}

test('upgrade: setup (move/copy) then the first live start downloads into the new Keepfin folder', async () => {
  const world = await makeWorld({loose: false})
  try {
    const first = deps(world.deviceDir)
    const setup = await bootKeepfinFolder(first.value)
    const plan = (await setup.handle('setupPlan', {path: world.target, choices: {downloads: 'move', music: 'move', videos: 'copy'}})) as {runId: string}
    await setup.handle('setupStart', {runId: plan.runId})
    assert.equal((await first.finished).phase, 'done')
    await setup.close()
    assert.ok(inside(world.root, world.downloads) && !inside(world.target, world.downloads), 'the old folders are outside the new one')

    // the first live start, as main.ts does it
    const boot = await bootKeepfinFolder(deps(world.deviceDir).value)
    assert.equal(boot.live, true)
    const index = new LibraryIndex({
      file: path.join(world.deviceDir, 'cache', 'library.json'), artDir: path.join(world.deviceDir, 'library-art'), ffmpeg: () => undefined,
      persistence: boot.persistence, saveDelay: 10, pushDelay: 5, watch: false, analyse: false,
    })
    await index.init()
    const engine = new FakeEngine()
    const manager = new DownloadManager({
      store: path.join(world.deviceDir, 'queue.json'),
      defaultOutDir: world.downloads,
      libraryFolders: {music: world.music, videos: world.videos},
      engine,
      library: index,
      snapshotDelay: 5,
      progressInterval: 20,
    })
    try {
      await manager.init()
      const root = path.resolve(world.target)
      const settings = manager.getSettings()
      const folders = settings.library.spaces.filter((space) => space.type !== 'smart')
      assert.ok(folders.length >= 3)
      for (const space of folders) assert.ok(space.folder === root || inside(root, space.folder), `${space.id} is inside the Keepfin folder: ${space.folder}`)
      assert.ok(inside(root, settings.outDir) || settings.outDir === root, `outDir is inside the Keepfin folder: ${settings.outDir}`)

      // nothing was left linked
      const profiles = await loadProfiles(world.deviceDir)
      const linked = await fs.readFile(path.join(profileDir(world.deviceDir, profiles!.current), 'linked.json'), 'utf8').then((text) => JSON.parse(text) as Record<string, unknown>, () => ({}))
      assert.deepEqual(Object.keys((linked as {linked?: object}).linked ?? linked), [], 'linked.json has no entries')

      // a new download is written inside the folder
      const url = 'https://vimeo.com/123'
      engine.inspectResult = (u) => ({type: 'media', media: fakeMedia(u, {title: 'After setup'})})
      await manager.inspect(url)
      await manager.enqueue(url, video, undefined, 'videos')
      for (let i = 0; i < 300 && manager.snapshot().jobs.at(-1)?.status !== 'completed'; i++) await delay(10)
      const job = manager.snapshot().jobs.at(-1)!
      assert.equal(job.status, 'completed')
      assert.ok(inside(root, job.file!), `the new download is inside the Keepfin folder: ${job.file}`)
    } finally {
      await manager.close()
      await index.close()
      await boot.close()
    }
  } finally {
    await world.cleanup()
  }
})
