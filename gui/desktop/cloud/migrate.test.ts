// A place made when copies kept a mirror: the first direct run settles what the mirror still holds, drops the mirror's media, and the
// place then runs directly. A push that cannot finish drops nothing.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {test} from 'node:test'
import {migrateLegacyMirror} from './migrate.js'
import {saveState, type PushReport} from './mirror.js'

const ok: PushReport = {uploaded: 1, replaced: 0, moved: 0, trashed: 0, bytes: 3, failed: [], stopped: null}

async function legacy() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'kf-migrate-'))
  const mirror = path.join(dir, 'mirror')
  const stateDir = path.join(dir, 'state')
  await fs.mkdir(path.join(mirror, 'Music'), {recursive: true})
  await fs.mkdir(path.join(mirror, '.keepfin', 'versions', 'item'), {recursive: true})
  await fs.mkdir(stateDir, {recursive: true})
  await fs.writeFile(path.join(mirror, 'Music', 'a.mp3'), 'AAA')
  await fs.writeFile(path.join(mirror, '.keepfin', 'folder.json'), '{}')
  await fs.writeFile(path.join(mirror, '.keepfin', 'versions', 'item', 'v1.mp3'), 'OLD')
  await fs.writeFile(path.join(mirror, '.keepfin', 'versions', 'catalogue.jsonl'), '{}\n')
  const stateFile = path.join(stateDir, 'mirror-state.json')
  await saveState(stateFile, {version: 1, files: {'Music/a.mp3': {size: 3, mtimeMs: 1, revision: 'r', contentId: 'sha256:x'}, '.keepfin/folder.json': {size: 2, mtimeMs: 1, revision: 'r', contentId: 'sha256:y'}}})
  return {dir, mirror, stateDir, stateFile}
}
const has = (p: string) => fs.stat(p).then(() => true, () => false)

test('the first run settles the mirror, drops its media and keeps the copy\'s own data; later runs do nothing', async () => {
  const l = await legacy()
  try {
    let flushed = 0
    const r = await migrateLegacyMirror({stateDir: l.stateDir, mirror: l.mirror, stateFile: l.stateFile, flush: async () => (flushed++, ok)})
    assert.equal(r.state, 'migrated')
    assert.equal(flushed, 1)
    assert.equal(await has(path.join(l.mirror, 'Music')), false)
    assert.equal(await has(path.join(l.mirror, '.keepfin', 'versions', 'item', 'v1.mp3')), false, 'archived bytes are on the replica')
    assert.equal(await has(path.join(l.mirror, '.keepfin', 'folder.json')), true)
    assert.equal(await has(path.join(l.mirror, '.keepfin', 'versions', 'catalogue.jsonl')), true)
    const again = await migrateLegacyMirror({stateDir: l.stateDir, mirror: l.mirror, stateFile: l.stateFile, flush: async () => (flushed++, ok)})
    assert.equal(again.state, 'direct')
    assert.equal(flushed, 1)
  } finally {
    await fs.rm(l.dir, {recursive: true, force: true})
  }
})

test('a push that cannot finish drops nothing and the place is tried again', async () => {
  const l = await legacy()
  try {
    const stuck = await migrateLegacyMirror({stateDir: l.stateDir, mirror: l.mirror, stateFile: l.stateFile, flush: async () => ({...ok, failed: [{kind: 'upload', path: 'Music/a.mp3', reason: 'x'}]})})
    assert.equal(stuck.state, 'blocked')
    assert.equal(await has(path.join(l.mirror, 'Music', 'a.mp3')), true)
    const thrown = await migrateLegacyMirror({stateDir: l.stateDir, mirror: l.mirror, stateFile: l.stateFile, flush: async () => Promise.reject(new Error('offline'))})
    assert.equal(thrown.state, 'blocked')
    assert.equal(await has(path.join(l.mirror, 'Music', 'a.mp3')), true)
    assert.equal((await migrateLegacyMirror({stateDir: l.stateDir, mirror: l.mirror, stateFile: l.stateFile, flush: async () => ok})).state, 'migrated')
  } finally {
    await fs.rm(l.dir, {recursive: true, force: true})
  }
})
