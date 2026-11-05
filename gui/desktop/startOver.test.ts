import {test} from 'node:test'
import assert from 'node:assert/strict'
import {spawn} from 'node:child_process'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {isKeepfinFolder, launchHelper, planStartOver, removeTree, resultPath, takeResult} from './startOver.js'

async function temp() {
  return fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-startover-'))
}
async function keepfinFolder(root: string) {
  await fs.mkdir(path.join(root, '.keepfin'), {recursive: true})
  await fs.writeFile(path.join(root, '.keepfin', 'folder.json'), JSON.stringify({format: 1, libraryId: 'lib'}))
  await fs.mkdir(path.join(root, 'Music'), {recursive: true})
  await fs.writeFile(path.join(root, 'Music', 'song.mp3'), 'x')
}
const exists = (target: string) => fs.access(target).then(() => true, () => false)

test('only a folder with .keepfin/folder.json is a Keepfin folder', async () => {
  const base = await temp()
  try {
    const good = path.join(base, 'good')
    await keepfinFolder(good)
    const plain = path.join(base, 'plain')
    await fs.mkdir(plain)
    const broken = path.join(base, 'broken')
    await fs.mkdir(path.join(broken, '.keepfin'), {recursive: true})
    await fs.writeFile(path.join(broken, '.keepfin', 'folder.json'), 'not json')
    assert.equal(await isKeepfinFolder(good), true)
    assert.equal(await isKeepfinFolder(plain), false)
    assert.equal(await isKeepfinFolder(broken), false)
    assert.equal(await isKeepfinFolder(path.join(base, 'missing')), false)
  } finally {
    await fs.rm(base, {recursive: true, force: true})
  }
})

test('a reset plans only the app data; delete everything also plans the folder', async () => {
  const base = await temp()
  try {
    const userData = path.join(base, 'AppData')
    const folder = path.join(base, 'Keepfin')
    await fs.mkdir(userData)
    await keepfinFolder(folder)
    const reset = await planStartOver({userData, deleteFolder: false, profileFolder: folder})
    assert.equal(reset.folder, undefined)
    const full = await planStartOver({userData, deleteFolder: true, profileFolder: folder, home: path.join(base, 'home')})
    assert.equal(full.folder, folder)
    assert.equal(full.userData, userData)
  } finally {
    await fs.rm(base, {recursive: true, force: true})
  }
})

test('delete everything refuses a folder that is not a Keepfin folder, a drive root, the home folder or around the app data', async () => {
  const base = await temp()
  try {
    const userData = path.join(base, 'AppData')
    await fs.mkdir(userData)
    const plain = path.join(base, 'Documents')
    await fs.mkdir(plain)
    await assert.rejects(planStartOver({userData, deleteFolder: true, profileFolder: plain}), /does not look like a Keepfin folder/)
    await assert.rejects(planStartOver({userData, deleteFolder: true, profileFolder: undefined}), /cannot tell which folder/)
    await assert.rejects(planStartOver({userData, deleteFolder: true, profileFolder: 'relative/folder'}), /cannot tell which folder/)
    await assert.rejects(planStartOver({userData, deleteFolder: true, profileFolder: path.parse(base).root}), /whole drive/)
    await keepfinFolder(base)
    await assert.rejects(planStartOver({userData, deleteFolder: true, profileFolder: base}), /contains the app/)
    await assert.rejects(planStartOver({userData: path.parse(base).root, deleteFolder: false}), /will not erase/)
    const home = path.join(base, 'home')
    const around = path.join(base, 'home', '..')
    await assert.rejects(planStartOver({userData: path.join(os.tmpdir(), 'x-elsewhere'), deleteFolder: true, profileFolder: around, home}), /contains your user folder|contains the app/)
  } finally {
    await fs.rm(base, {recursive: true, force: true})
  }
})

test('a folder that is already gone is a note, not a failure', async () => {
  const base = await temp()
  try {
    const plan = await planStartOver({userData: path.join(base, 'AppData'), deleteFolder: true, profileFolder: path.join(base, 'gone')})
    assert.equal(plan.folder, undefined)
    assert.deepEqual(plan.notes, ['The Keepfin folder was already gone.'])
  } finally {
    await fs.rm(base, {recursive: true, force: true})
  }
})

test('deleting retries the errors Windows gives for a held file, then gives up with the message', async () => {
  let calls = 0
  const flaky = {
    rm: async () => {
      calls++
      if (calls < 3) throw Object.assign(new Error('busy'), {code: 'EBUSY'})
    },
  }
  assert.equal(await removeTree(flaky, 'x', 5, 1), undefined)
  assert.equal(calls, 3)
  calls = 0
  const stuck = {
    rm: async () => {
      calls++
      throw Object.assign(new Error('still busy'), {code: 'EPERM'})
    },
  }
  assert.equal(await removeTree(stuck, 'x', 4, 1), 'still busy')
  assert.equal(calls, 4)
  calls = 0
  const other = {
    rm: async () => {
      calls++
      throw Object.assign(new Error('weird'), {code: 'EIO'})
    },
  }
  assert.equal(await removeTree(other, 'x', 4, 1), 'weird')
  assert.equal(calls, 1)
})

async function waitForResult(tmp: string) {
  for (let i = 0; i < 200; i++) {
    if (await exists(resultPath(tmp))) return
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  assert.fail('the helper left no result')
}

test('the helper waits for the process to exit, deletes the folder and the app data, and leaves the outcome', async () => {
  const base = await temp()
  try {
    const tmp = path.join(base, 'tmp')
    await fs.mkdir(tmp)
    const userData = path.join(base, 'AppData')
    const folder = path.join(base, 'Keepfin')
    await fs.mkdir(path.join(userData, 'cache'), {recursive: true})
    await fs.writeFile(path.join(userData, 'settings.json'), '{}')
    await keepfinFolder(folder)
    const other = path.join(base, 'Other')
    await fs.mkdir(other)
    await fs.writeFile(path.join(other, 'keep.txt'), 'x')
    // A stand-in for the running app that exits after a moment.
    const app = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 700)'], {stdio: 'ignore'})
    const plan = await planStartOver({userData, deleteFolder: true, profileFolder: folder, home: path.join(base, 'home')})
    await launchHelper({plan, pid: app.pid!, execPath: process.execPath, tmp, settleMs: 50, delayMs: 20})
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert.equal(await exists(path.join(userData, 'settings.json')), true, 'nothing is deleted while the app is still running')
    await waitForResult(tmp)
    const outcome = await takeResult(tmp)
    assert.deepEqual(outcome, {deleteFolder: true, folderDeleted: true, dataDeleted: true, notes: [], problems: []})
    assert.equal(await exists(userData), false)
    assert.equal(await exists(folder), false)
    assert.equal(await exists(path.join(other, 'keep.txt')), true, 'other folders are not touched')
    assert.equal(await takeResult(tmp), undefined, 'the outcome is read once')
  } finally {
    await fs.rm(base, {recursive: true, force: true})
  }
})

test('a reset leaves the Keepfin folder in place', async () => {
  const base = await temp()
  try {
    const tmp = path.join(base, 'tmp')
    await fs.mkdir(tmp)
    const userData = path.join(base, 'AppData')
    const folder = path.join(base, 'Keepfin')
    await fs.mkdir(userData)
    await fs.writeFile(path.join(userData, 'queue.json'), '[]')
    await keepfinFolder(folder)
    const app = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 200)'], {stdio: 'ignore'})
    const plan = await planStartOver({userData, deleteFolder: false, profileFolder: folder})
    await launchHelper({plan, pid: app.pid!, execPath: process.execPath, tmp, settleMs: 50, delayMs: 20})
    await waitForResult(tmp)
    const outcome = await takeResult(tmp)
    assert.deepEqual(outcome, {deleteFolder: false, folderDeleted: false, dataDeleted: true, notes: [], problems: []})
    assert.equal(await exists(userData), false)
    assert.equal(await exists(path.join(folder, 'Music', 'song.mp3')), true)
  } finally {
    await fs.rm(base, {recursive: true, force: true})
  }
})

test('if the app never exits the helper deletes nothing and says so', async () => {
  const base = await temp()
  try {
    const tmp = path.join(base, 'tmp')
    await fs.mkdir(tmp)
    const userData = path.join(base, 'AppData')
    await fs.mkdir(userData)
    const app = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 5000)'], {stdio: 'ignore'})
    try {
      const plan = await planStartOver({userData, deleteFolder: false})
      await launchHelper({plan, pid: app.pid!, execPath: process.execPath, tmp, waitMs: 600, settleMs: 10})
      await waitForResult(tmp)
      const outcome = await takeResult(tmp)
      assert.equal(outcome?.dataDeleted, false)
      assert.match(outcome?.problems[0] ?? '', /did not close in time/)
      assert.equal(await exists(userData), true)
    } finally {
      app.kill()
    }
  } finally {
    await fs.rm(base, {recursive: true, force: true})
  }
})
