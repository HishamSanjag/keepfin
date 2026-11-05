import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {spawn} from 'node:child_process'
import {appendLineDurable, CorruptJsonError, readJson, writeJsonAtomic} from './atomic.js'
import {
  README_TEXT,
  acquireFolderLock,
  checkReplica,
  createKeepfinFolder,
  defaultFolderPath,
  inspectFolderChoice,
  openKeepfinFolder,
  reassignReplica,
} from './keepfinFolder.js'

const tmp = () => fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-folder-'))
const ids = {libraryId: 'lib1', replicaId: 'rep1', now: '2026-10-13T10:00:00Z'}

test('atomic helpers: write, read, corrupt, missing, append', async () => {
  const dir = await tmp()
  const file = path.join(dir, 'sub', 'a.json')
  await writeJsonAtomic(file, {a: 1})
  await writeJsonAtomic(file, {a: 2})
  assert.deepEqual(await readJson(file), {a: 2})
  assert.deepEqual(await fs.readdir(path.dirname(file)), ['a.json'])
  assert.equal(await readJson(path.join(dir, 'none.json')), undefined)
  await fs.writeFile(file, '{oops')
  await assert.rejects(readJson(file), CorruptJsonError)
  const log = path.join(dir, 'log.txt')
  await appendLineDurable(log, 'one')
  await appendLineDurable(log, 'two')
  assert.equal(await fs.readFile(log, 'utf8'), 'one\ntwo\n')
})

test('createKeepfinFolder builds the tree, hides it and opens cleanly', async () => {
  const root = path.join(await tmp(), 'Keepfin')
  const hidden: string[] = []
  const folder = await createKeepfinFolder(root, {...ids, hide: (p) => void hidden.push(p)})
  assert.deepEqual(hidden, [path.join(root, '.keepfin')])
  assert.deepEqual((await fs.readdir(root)).sort(), ['.keepfin', 'Downloads', 'Music', 'Videos'])
  assert.deepEqual((await fs.readdir(path.join(root, '.keepfin'))).sort(), ['README.txt', 'actors', 'art', 'checkpoints', 'folder.json', 'ops', 'trash'])
  assert.equal(await fs.readFile(path.join(root, '.keepfin', 'README.txt'), 'utf8'), README_TEXT)
  assert.deepEqual(await readJson(path.join(root, '.keepfin', 'folder.json')), {format: 1, libraryId: 'lib1', replicaId: 'rep1', createdAt: ids.now})
  assert.deepEqual(await openKeepfinFolder(root), {ok: true, folder})
  await assert.rejects(createKeepfinFolder(root, ids), /already/)
})

test('a failing hide does not fail creation', async () => {
  const root = path.join(await tmp(), 'K')
  await createKeepfinFolder(root, {...ids, hide: () => { throw new Error('attrib failed') }})
  assert.equal((await openKeepfinFolder(root)).ok, true)
})

test('openKeepfinFolder reports every typed failure', async () => {
  const dir = await tmp()
  assert.deepEqual(await openKeepfinFolder(path.join(dir, 'nope')), {ok: false, reason: 'missing'})
  assert.deepEqual(await openKeepfinFolder(dir), {ok: false, reason: 'not-a-keepfin-folder'})
  const write = async (value: string) => {
    await fs.mkdir(path.join(dir, '.keepfin'), {recursive: true})
    await fs.writeFile(path.join(dir, '.keepfin', 'folder.json'), value)
  }
  await write('{bad')
  assert.deepEqual(await openKeepfinFolder(dir), {ok: false, reason: 'corrupt'})
  await write(JSON.stringify({format: 1, libraryId: 'l'}))
  assert.deepEqual(await openKeepfinFolder(dir), {ok: false, reason: 'corrupt'})
  await write(JSON.stringify({format: 3, libraryId: 'l', replicaId: 'r', createdAt: 't'}))
  assert.deepEqual(await openKeepfinFolder(dir), {ok: false, reason: 'newer-format'})
})

test('defaultFolderPath uses the user folder', () => {
  assert.equal(defaultFolderPath({USERPROFILE: path.join('C:', 'Users', 'Ana')}), path.join('C:', 'Users', 'Ana', 'Keepfin'))
  assert.throws(() => defaultFolderPath({}))
})

test('inspectFolderChoice: missing, empty, populated, Keepfin folder', async () => {
  const dir = await tmp()
  const env = {}
  const missing = await inspectFolderChoice(path.join(dir, 'new', 'deep'), {env})
  assert.deepEqual([missing.exists, missing.empty, missing.isKeepfinFolder, missing.writable, missing.insideCloudApp], [false, false, false, true, null])
  assert.ok(typeof missing.freeBytes === 'number' && missing.freeBytes > 0)
  const empty = await inspectFolderChoice(dir, {env})
  assert.deepEqual([empty.exists, empty.empty], [true, true])
  await fs.writeFile(path.join(dir, 'x.txt'), 'x')
  assert.equal((await inspectFolderChoice(dir, {env})).empty, false)
  assert.deepEqual(await fs.readdir(dir), ['x.txt'])
  await createKeepfinFolder(path.join(dir, 'K'), {...ids, hide: () => undefined})
  assert.equal((await inspectFolderChoice(path.join(dir, 'K'), {env})).isKeepfinFolder, true)
  const stub = await inspectFolderChoice(dir, {env, statfs: async () => ({bavail: 10, bsize: 4096})})
  assert.equal(stub.freeBytes, 40960)
  const noStat = await inspectFolderChoice(dir, {env, statfs: async () => { throw new Error('x') }})
  assert.equal(noStat.freeBytes, null)
})

test('inspectFolderChoice detects cloud apps by path heuristics', async () => {
  const base = await tmp()
  const win = {platform: 'win32' as const}
  const od = path.join(base, 'OneDrive')
  assert.equal((await inspectFolderChoice(path.join(od, 'Keepfin'), {...win, env: {OneDrive: od}})).insideCloudApp, 'onedrive')
  assert.equal((await inspectFolderChoice(path.join(base, 'Other'), {...win, env: {OneDrive: od}})).insideCloudApp, null)
  assert.equal((await inspectFolderChoice(path.join(base, 'OneDrive2', 'K'), {...win, env: {OneDrive: od}})).insideCloudApp, null)

  const appdata = path.join(base, 'Roaming')
  const dbx = path.join(base, 'Dropbox')
  await fs.mkdir(path.join(appdata, 'Dropbox'), {recursive: true})
  await fs.writeFile(path.join(appdata, 'Dropbox', 'info.json'), JSON.stringify({personal: {path: dbx}}))
  assert.equal((await inspectFolderChoice(path.join(dbx, 'K'), {...win, env: {APPDATA: appdata}})).insideCloudApp, 'dropbox')

  assert.equal((await inspectFolderChoice(path.join(base, 'G', 'My Drive', 'K'), {...win, env: {}})).insideCloudApp, 'google-drive')
  assert.equal((await inspectFolderChoice(path.join(base, 'Users', 'iCloudDrive', 'K'), {...win, env: {}})).insideCloudApp, 'icloud')
  const win32Drive = await inspectFolderChoice('C:\\Users\\Ana\\Documents', {...win, env: {}})
  assert.equal(win32Drive.insideCloudApp, null)
})

test('lock: acquire, refuse while held by a live process, release, take over a dead pid', async () => {
  const dev = await tmp()
  const first = await acquireFolderLock(dev, 'rep1')
  assert.ok(first.ok)
  const file = path.join(dev, 'locks', 'rep1.lock')
  const body = await readJson<{pid: number; startedAt: string}>(file)
  assert.equal(body!.pid, process.pid)
  assert.ok(body!.startedAt)

  // A live process other than the caller holds it: use the parent process.
  const livePid = process.ppid
  await fs.writeFile(file, JSON.stringify({pid: livePid, startedAt: 'x'}))
  assert.deepEqual(await acquireFolderLock(dev, 'rep1'), {ok: false, reason: 'held', pid: livePid})
  await fs.rm(file)

  // A dead pid: spawn a short process, wait for it to exit, use its pid.
  const child = spawn(process.execPath, ['-e', '0'])
  const deadPid = child.pid!
  await new Promise((resolve) => child.on('exit', resolve))
  await fs.writeFile(file, JSON.stringify({pid: deadPid, startedAt: 'x'}))
  const taken = await acquireFolderLock(dev, 'rep1')
  assert.ok(taken.ok)
  assert.equal((await readJson<{pid: number}>(file))!.pid, process.pid)
  if (taken.ok) await taken.release()
  await assert.rejects(fs.stat(file))

  // Garbage in the lock file is treated as stale.
  await fs.writeFile(file, 'garbage')
  const again = await acquireFolderLock(dev, 'rep1')
  assert.ok(again.ok)
  if (again.ok) await again.release()
})

test('clone detection is case-insensitive on Windows and reassign writes a note', async () => {
  const root = path.join(await tmp(), 'Keepfin')
  await createKeepfinFolder(root, {...ids, hide: () => undefined})
  assert.equal(await checkReplica(root, {}), 'ok')
  assert.equal(await checkReplica(root, {rep1: root}), 'ok')
  assert.equal(await checkReplica(root, {other: '/somewhere'}), 'ok')
  assert.equal(await checkReplica(root, {rep1: path.join(await tmp(), 'Keepfin')}), 'cloned')
  assert.equal(await checkReplica(root, {rep1: root.toUpperCase() + path.sep}, 'win32'), 'ok')
  assert.equal(await checkReplica(root, {rep1: root.toUpperCase()}, 'linux'), 'cloned')

  const updated = await reassignReplica(root, 'rep2', '2026-10-14T00:00:00Z')
  assert.equal(updated.replicaId, 'rep2')
  const reopened = await openKeepfinFolder(root)
  assert.ok(reopened.ok && reopened.folder.replicaId === 'rep2' && reopened.folder.libraryId === 'lib1')
  assert.deepEqual(await readJson(path.join(root, '.keepfin', 'actors', 'clone-rep2.json')), {previousReplicaId: 'rep1', at: '2026-10-14T00:00:00Z'})
  assert.ok(!(await fs.readdir(path.join(root, '.keepfin'))).some((f) => f.endsWith('.tmp')))
  await assert.rejects(reassignReplica(path.join(root, 'nope'), 'x', 't'), /missing/)
})
