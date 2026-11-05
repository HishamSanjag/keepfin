import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {CorruptJsonError} from './atomic.js'
import {PROFILE_COLORS, ProfilesError, createFirstProfile, defaultProfileName, ensureProfileDir, loadProfiles, pickColor, profileDir, rotateActor} from './profiles.js'

const tmp = () => fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-profiles-'))
const input = {id: 'p1', name: 'Ana', color: '#2f6df6', folder: 'C:\\Users\\Ana\\Keepfin', libraryId: 'lib1', actorId: 'act1', now: '2026-10-13T10:00:00Z'}

test('missing file loads as undefined; first profile round-trips and leaves no temp file', async () => {
  const dir = await tmp()
  assert.equal(await loadProfiles(dir), undefined)
  await createFirstProfile(dir, input)
  const loaded = await loadProfiles(dir)
  assert.deepEqual(loaded, {format: 1, current: 'p1', profiles: [{id: 'p1', name: 'Ana', color: '#2f6df6', folder: input.folder, libraryId: 'lib1', actorId: 'act1', createdAt: input.now}]})
  assert.deepEqual(await fs.readdir(dir), ['profiles.json'])
})

test('creating again fails and keeps the original', async () => {
  const dir = await tmp()
  await createFirstProfile(dir, input)
  await assert.rejects(createFirstProfile(dir, {...input, name: 'Other'}), (e) => e instanceof ProfilesError && e.code === 'exists')
  assert.equal((await loadProfiles(dir))!.profiles[0].name, 'Ana')
})

test('corrupt or invalid data throws and is never reset', async () => {
  const dir = await tmp()
  const file = path.join(dir, 'profiles.json')
  await fs.writeFile(file, '{ not json')
  await assert.rejects(loadProfiles(dir), CorruptJsonError)
  await fs.writeFile(file, JSON.stringify({format: 1, current: 'zzz', profiles: [{...input, createdAt: input.now}]}))
  await assert.rejects(loadProfiles(dir), (e) => e instanceof ProfilesError && e.code === 'corrupt')
  await fs.writeFile(file, JSON.stringify({format: 2, current: 'p1', profiles: []}))
  await assert.rejects(loadProfiles(dir), ProfilesError)
  assert.equal(await fs.readFile(file, 'utf8'), JSON.stringify({format: 2, current: 'p1', profiles: []}))
})

test('a pin lock is validated and preserved', async () => {
  const dir = await tmp()
  const file = path.join(dir, 'profiles.json')
  const profile = {id: 'p1', name: 'Ana', color: '#fff', folder: 'f', libraryId: 'l', actorId: 'a', createdAt: 't'}
  await fs.writeFile(file, JSON.stringify({format: 1, current: 'p1', profiles: [{...profile, lock: {kind: 'pin', salt: 's', hash: 'h'}}]}))
  assert.deepEqual((await loadProfiles(dir))!.profiles[0].lock, {kind: 'pin', salt: 's', hash: 'h'})
  await fs.writeFile(file, JSON.stringify({format: 1, current: 'p1', profiles: [{...profile, lock: {kind: 'pin'}}]}))
  await assert.rejects(loadProfiles(dir), ProfilesError)
})

test('profile folders are created and ids cannot escape', async () => {
  const dir = await tmp()
  assert.equal(profileDir(dir, 'p1'), path.join(dir, 'profiles', 'p1'))
  const made = await ensureProfileDir(dir, 'p1')
  assert.deepEqual((await fs.readdir(made)).sort(), ['cache', 'outbox'])
  assert.throws(() => profileDir(dir, '..\\x'), ProfilesError)
})

test('rotateActor replaces the actor id atomically and rejects unknown profiles', async () => {
  const dir = await tmp()
  await createFirstProfile(dir, input)
  await rotateActor(dir, 'p1', 'act2')
  assert.equal((await loadProfiles(dir))!.profiles[0].actorId, 'act2')
  assert.deepEqual(await fs.readdir(dir), ['profiles.json'])
  await assert.rejects(rotateActor(dir, 'nope', 'x'), (e) => e instanceof ProfilesError && e.code === 'unknown-profile')
  const empty = await tmp()
  await assert.rejects(rotateActor(empty, 'p1', 'x'), ProfilesError)
})

test('default name trims and falls back; colours wrap', () => {
  assert.equal(defaultProfileName({username: () => '  Hisham  '}), 'Hisham')
  assert.equal(defaultProfileName({username: () => '   '}), 'Me')
  assert.equal(defaultProfileName({username: () => { throw new Error('no user') }}), 'Me')
  assert.equal(PROFILE_COLORS.length, 8)
  assert.ok(PROFILE_COLORS.every((c) => /^#[0-9a-f]{6}$/i.test(c)))
  assert.equal(pickColor(0), PROFILE_COLORS[0])
  assert.equal(pickColor(9), PROFILE_COLORS[1])
  assert.equal(pickColor(-1), PROFILE_COLORS[7])
})
