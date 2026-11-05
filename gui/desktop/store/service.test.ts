import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {test} from 'node:test'
import type {StorageState} from '../../shared/storage.js'
import {createKeepfinFolder} from './keepfinFolder.js'
import {loadProfiles} from './profiles.js'
import {StorageService, type LibraryPort} from './service.js'

async function setup() {
  const base = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-service-'))
  const deviceDir = path.join(base, 'appdata')
  await fs.mkdir(deviceDir, {recursive: true})
  const calls: string[] = []
  const pushed: StorageState[] = []
  let n = 0
  const library: LibraryPort = {
    async create(folder, actorId) {
      calls.push(`create ${path.basename(folder.root)} ${actorId}`)
      // The library is built before profiles.json exists.
      assert.equal(await loadProfiles(deviceDir), undefined)
    },
    async open(folder, actorId) {
      calls.push(`open ${path.basename(folder.root)} ${actorId}`)
    },
    async overview() {
      return {spaces: [{id: 's1', name: 'Music', files: 2, bytes: 10}], bytes: 10, missing: 0, checking: false}
    },
    async checkFiles() {
      calls.push('check')
    },
  }
  const links = new Map<string, string>()
  const service = new StorageService({
    deviceDir,
    userMusicDir: path.join(base, 'UserMusic'),
    env: {env: {USERPROFILE: path.join(base, 'home')}, platform: 'win32'},
    pickFolder: async () => path.join(base, 'picked'),
    openPath: async (target) => void calls.push(`openPath ${path.basename(target)}`),
    shortcuts: {
      writeShortcutLink(file, _op, details) {
        links.set(file, details.target)
        return true
      },
      readShortcutLink(file) {
        const target = links.get(file)
        if (!target) throw new Error('missing')
        return {target}
      },
    },
    newId: () => `id-${++n}`,
    now: () => Date.UTC(2026, 9, 13),
    push: (state) => void pushed.push(state),
    library,
  })
  return {base, deviceDir, service, calls, pushed}
}

test('state: first start, then needs setup when an earlier library exists', async () => {
  const {deviceDir, service, base} = await setup()
  const first = (await service.handle('storageState', {})) as StorageState
  assert.equal(first.phase, 'firstStart')
  assert.equal(first.defaultPath, path.join(base, 'home', 'Keepfin'))
  await fs.writeFile(path.join(deviceDir, 'library.json'), '{}')
  assert.equal(((await service.handle('storageState', {})) as StorageState).phase, 'needsSetup')
})

test('create: makes the folder, builds the library, writes the profile last, pushes ready', async () => {
  const {base, deviceDir, service, calls, pushed} = await setup()
  const target = path.join(base, 'Keepfin')
  const state = (await service.handle('storageCreate', {path: target, musicShortcut: true})) as StorageState
  assert.equal(state.phase, 'ready')
  assert.equal(state.folder?.path, target)
  assert.match(calls[0], /^create Keepfin id-\d+$/)
  const profiles = await loadProfiles(deviceDir)
  assert.equal(profiles?.profiles.length, 1)
  assert.equal(profiles?.profiles[0].folder, target)
  assert.equal(pushed.at(-1)?.phase, 'ready')
  await assert.rejects(service.handle('storageCreate', {path: path.join(base, 'Other')}), /already has a folder/)
})

test('create refuses an existing Keepfin folder; open adopts it', async () => {
  const {base, service, calls} = await setup()
  const target = path.join(base, 'Existing')
  await createKeepfinFolder(target, {libraryId: 'lib', replicaId: 'rep', now: '2026-10-13T00:00:00.000Z', hide: () => undefined})
  const choice = (await service.handle('storageInspectFolder', {path: target})) as {isKeepfinFolder: boolean}
  assert.equal(choice.isKeepfinFolder, true)
  await assert.rejects(service.handle('storageCreate', {path: target}), /Open it instead/)
  const state = (await service.handle('storageOpenExisting', {path: target})) as StorageState
  assert.equal(state.phase, 'ready')
  assert.equal(state.folder?.libraryId, 'lib')
  assert.ok(calls.some((call) => call.startsWith('open Existing')))
})

test('open refuses a newer format and a folder without Keepfin data', async () => {
  const {base, service} = await setup()
  const newer = path.join(base, 'Newer')
  await createKeepfinFolder(newer, {libraryId: 'lib', replicaId: 'rep', now: 'x', hide: () => undefined})
  const file = path.join(newer, '.keepfin', 'folder.json')
  await fs.writeFile(file, JSON.stringify({...JSON.parse(await fs.readFile(file, 'utf8')), format: 99}))
  await assert.rejects(service.handle('storageOpenExisting', {path: newer}), /newer version/)
  const plain = path.join(base, 'Plain')
  await fs.mkdir(plain)
  await assert.rejects(service.handle('storageOpenExisting', {path: plain}), /no Keepfin folder/)
})

test('requests are validated', async () => {
  const {service} = await setup()
  await assert.rejects(service.handle('storageInspectFolder', {path: 'relative\\path'}), /Invalid folder/)
  await assert.rejects(service.handle('storageInspectFolder', {}), /Invalid folder/)
  await assert.rejects(service.handle('storageSetMusicShortcut', {}), /Invalid request/)
  await assert.rejects(service.handle('setupReport', {runId: '../x'}), /Invalid request|not available/)
  await assert.rejects(service.handle('nope', {}), /Unknown storage request/)
})

test('overview and the music shortcut switch', async () => {
  const {base, service, calls} = await setup()
  await service.handle('storageCreate', {path: path.join(base, 'Keepfin'), musicShortcut: true})
  const overview = (await service.handle('storageOverview', {})) as {musicShortcut: boolean; spaces: unknown[]; path: string}
  assert.equal(overview.path, path.join(base, 'Keepfin'))
  assert.equal(overview.spaces.length, 1)
  if (process.platform === 'win32') assert.equal(overview.musicShortcut, true)
  const off = (await service.handle('storageSetMusicShortcut', {enabled: false})) as {musicShortcut: boolean}
  assert.equal(off.musicShortcut, false)
  await service.handle('storageOpenFolder', {})
  assert.ok(calls.includes('openPath Keepfin'))
})
