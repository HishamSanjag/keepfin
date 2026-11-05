// Storage › Change location: the folder move that runs before the library opens (dev-docs/plans/KEEPFIN-FOLDER-MOVE.md, D2).
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {finishMove, moveProblem, readMove, readPendingBackup, requestMove, runMove} from './folderMove.js'
import {createFirstProfile, loadProfiles} from './profiles.js'

async function world() {
  const base = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-move-'))
  const deviceDir = path.join(base, 'device')
  const from = path.join(base, 'Keepfin')
  await fs.mkdir(path.join(from, '.keepfin'), {recursive: true})
  await fs.mkdir(path.join(from, 'Music'), {recursive: true})
  await fs.writeFile(path.join(from, '.keepfin', 'folder.json'), '{"format":1}')
  await fs.writeFile(path.join(from, 'Music', 'song.mp3'), 'SONG')
  await fs.mkdir(deviceDir, {recursive: true})
  await createFirstProfile(deviceDir, {id: 'p1', name: 'me', color: '#2f6df6', folder: from, libraryId: 'lib', actorId: 'actor', now: new Date().toISOString()})
  const trashed: string[] = []
  const deps = {deviceDir, profileId: 'p1', push: () => {}, trash: async (target: string) => void trashed.push(target)}
  return {base, deviceDir, from, deps, trashed, folder: async () => (await loadProfiles(deviceDir))!.profiles[0].folder}
}

test('a new place inside the folder, above it, or with files in it is refused in plain words', async () => {
  const w = await world()
  assert.match((await moveProblem(w.from, path.join(w.from, 'Music', 'here')))!, /inside your Keepfin folder/)
  assert.match((await moveProblem(w.from, w.base))!, /not above your Keepfin folder/)
  const busy = path.join(w.base, 'busy')
  await fs.mkdir(busy)
  await fs.writeFile(path.join(busy, 'x.txt'), 'x')
  assert.match((await moveProblem(w.from, busy))!, /empty folder/)
  assert.equal(await moveProblem(w.from, path.join(w.base, 'New', 'Keepfin')), undefined, 'a folder that does not exist yet is fine')
  await assert.rejects(requestMove(w.deviceDir, w.from, busy), /empty folder/)
  assert.equal(await readMove(w.deviceDir), undefined, 'a refused move saves nothing')
})

test('on the same drive the folder is renamed, the profile switches and nothing goes to the Recycle Bin', async () => {
  const w = await world()
  const to = path.join(w.base, 'Elsewhere', 'Keepfin')
  await requestMove(w.deviceDir, w.from, to)
  await runMove((await readMove(w.deviceDir))!, w.deps)
  assert.equal(await fs.readFile(path.join(to, 'Music', 'song.mp3'), 'utf8'), 'SONG')
  assert.equal(await fs.stat(w.from).catch(() => undefined), undefined, 'the old folder is gone')
  assert.equal(await w.folder(), to)
  assert.equal(await readMove(w.deviceDir), undefined, 'move.json is removed')
  assert.deepEqual(w.trashed, [])
})

test('a copy stopped halfway picks up where it was, and the old folder goes to the Recycle Bin after the switch', async () => {
  const w = await world()
  const to = path.join(w.base, 'Copy', 'Keepfin')
  // what a crash left behind: one file already copied (a rename into a folder with files in it falls back to copying)
  await fs.mkdir(path.join(to, 'Music'), {recursive: true})
  await fs.writeFile(path.join(to, 'Music', 'song.mp3'), 'SONG')
  await runMove({from: w.from, to}, w.deps)
  assert.equal(await fs.readFile(path.join(to, '.keepfin', 'folder.json'), 'utf8'), '{"format":1}')
  assert.equal(await w.folder(), to)
  assert.deepEqual(w.trashed, [w.from])
})

test('a crash right after the rename only switches the profile', async () => {
  const w = await world()
  const to = path.join(w.base, 'Renamed')
  await fs.rename(w.from, to)
  await runMove({from: w.from, to}, w.deps)
  assert.equal(await w.folder(), to)
})

test('when another program holds the old folder, the move is still done and the next start tries the tidy-up again', async () => {
  const w = await world()
  const notes: (string | undefined)[] = []
  const to = path.join(w.base, 'New')
  await requestMove(w.deviceDir, w.from, to)
  await finishMove({from: w.from, to}, {deviceDir: w.deviceDir, push: (p) => notes.push(p.note), trash: async () => Promise.reject(new Error('in use'))})
  assert.match(notes[0]!, /still at .*had it open.*tries again each time it starts/)
  assert.ok(await fs.stat(w.from), 'the old folder stays where it was')
  assert.deepEqual(await readMove(w.deviceDir), {from: w.from, to}, 'move.json stays, so the next start tries again')
  await finishMove({from: w.from, to}, w.deps)
  assert.deepEqual(w.trashed, [w.from])
  assert.equal(await readMove(w.deviceDir), undefined)
})

test('keeping the old place copies even on the same drive; only its library records leave, and it is queued to become a backup', async () => {
  const w = await world()
  const to = path.join(w.base, 'Kept', 'Keepfin')
  await requestMove(w.deviceDir, w.from, to, true)
  await runMove((await readMove(w.deviceDir))!, w.deps)
  assert.equal(await fs.readFile(path.join(to, 'Music', 'song.mp3'), 'utf8'), 'SONG', 'the new place has the files')
  assert.equal(await fs.readFile(path.join(w.from, 'Music', 'song.mp3'), 'utf8'), 'SONG', 'the old place keeps them')
  assert.deepEqual(w.trashed, [path.join(w.from, '.keepfin')], 'only the old library records go to the Recycle Bin')
  assert.equal(await w.folder(), to)
  assert.equal(await readPendingBackup(w.deviceDir), w.from)
  assert.equal(await readMove(w.deviceDir), undefined)
})
