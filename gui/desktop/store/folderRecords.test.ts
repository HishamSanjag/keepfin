// Format 2 of folder.json (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 1): one pointer decides which records root a folder holds.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {readJson} from './atomic.js'
import {createKeepfinFolder, FOLDER_FORMAT, openKeepfinFolder, recordsDir, recordsPath, validRecords, writeFolderJson, reassignReplica, folderJsonPath} from './keepfinFolder.js'
import {KeepfinStore, listingLast} from './store.js'

const tmp = () => fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-records-'))
const ids = {libraryId: 'lib-s', replicaId: 'rep-s', now: '2026-10-21T10:00:00Z'}

test('a folder that was never combined stays format 1 and its records root is .keepfin itself', async () => {
  const root = path.join(await tmp(), 'K')
  const folder = await createKeepfinFolder(root, {...ids, hide: () => undefined})
  assert.equal(folder.format, 1)
  assert.equal(FOLDER_FORMAT, 2, 'this build reads format 2')
  assert.equal(recordsDir(folder), path.join(root, '.keepfin'))
  assert.deepEqual(await readJson(folderJsonPath(root)), {format: 1, libraryId: 'lib-s', replicaId: 'rep-s', createdAt: ids.now})
})

test('format 2 names the active records root; a format 1 app refuses it and cannot misread it', async () => {
  const root = path.join(await tmp(), 'K')
  await createKeepfinFolder(root, {...ids, hide: () => undefined})
  const t = randomUUID()
  await writeFolderJson(root, {
    format: 2,
    libraryId: t,
    replicaId: 'rep-t',
    createdAt: ids.now,
    records: `libraries/${t}`,
    libraries: [
      {libraryId: 'lib-s', replicaId: 'rep-s', records: '', role: 'previous', keepUntil: '2026-11-20T00:00:00Z', mergeId: 'm1'},
      {libraryId: t, replicaId: 'rep-t', records: `libraries/${t}`, role: 'active', since: ids.now, mergeId: 'm1'},
    ],
  })
  const opened = await openKeepfinFolder(root)
  assert.ok(opened.ok)
  if (!opened.ok) return
  assert.equal(opened.folder.libraryId, t, 'the identity fields describe the active library')
  assert.equal(opened.folder.records, `libraries/${t}`)
  assert.equal(opened.folder.libraries?.length, 2)
  assert.equal(recordsDir(opened.folder), path.join(root, '.keepfin', 'libraries', t))
  // an older app: the same code path with its highest known format
  assert.deepEqual(await openKeepfinFolder(root, {maxFormat: 1}), {ok: false, reason: 'newer-format'})
})

test('a damaged records pointer is corrupt, never guessed at', async () => {
  const root = path.join(await tmp(), 'K')
  await createKeepfinFolder(root, {...ids, hide: () => undefined})
  for (const records of ['../x', '/abs', 'a//b', 'a\\b', 'libraries/./t']) {
    await fs.writeFile(folderJsonPath(root), JSON.stringify({format: 2, libraryId: 'l', replicaId: 'r', createdAt: 't', records}))
    assert.deepEqual(await openKeepfinFolder(root), {ok: false, reason: 'corrupt'}, records)
  }
  await fs.writeFile(folderJsonPath(root), JSON.stringify({format: 2, libraryId: 'l', replicaId: 'r', createdAt: 't', libraries: [{libraryId: 'x'}]}))
  assert.deepEqual(await openKeepfinFolder(root), {ok: false, reason: 'corrupt'})
  assert.ok(validRecords('') && validRecords('libraries/a'))
  assert.equal(recordsPath('/m', undefined), path.join('/m', '.keepfin'))
})

test('a store on a records root keeps ops, checkpoints and notes there, and leaves .keepfin alone', async () => {
  const root = path.join(await tmp(), 'K')
  const deviceDir = path.join(path.dirname(root), 'dev')
  await createKeepfinFolder(root, {...ids, hide: () => undefined})
  const t = randomUUID()
  const records = `libraries/${t}`
  const actor = randomUUID()
  const store = await KeepfinStore.open({root, records, libraryId: t, actor, deviceDir, outboxDir: path.join(deviceDir, 'outbox'), newId: randomUUID, now: () => 1_700_000_000_000, flushIdleMs: 20, watch: false})
  await store.apply([{kind: 'item.add', body: {item: randomUUID(), fields: {media: 'audio', title: 'x'}}}])
  await store.flush()
  await store.close()
  const there = path.join(root, '.keepfin', 'libraries', t)
  assert.deepEqual(await fs.readdir(path.join(there, 'ops')), [actor])
  assert.ok((await fs.readdir(path.join(there, 'actors'))).includes(`${actor}.json`))
  assert.deepEqual(await fs.readdir(path.join(root, '.keepfin', 'ops')), [], 'the old records root has no new ops')
  assert.equal((await listingLast(root, path.join(deviceDir, 'outbox'), records)).get(actor), 1)
  assert.equal((await listingLast(root, path.join(deviceDir, 'outbox'))).get(actor), undefined)
})

test('reassigning the replica of a combined folder updates the active library and writes the note in its records root', async () => {
  const root = path.join(await tmp(), 'K')
  await createKeepfinFolder(root, {...ids, hide: () => undefined})
  const t = randomUUID()
  await writeFolderJson(root, {format: 2, libraryId: t, replicaId: 'rep-t', createdAt: ids.now, records: `libraries/${t}`, libraries: [{libraryId: t, replicaId: 'rep-t', records: `libraries/${t}`, role: 'active'}]})
  await reassignReplica(root, 'rep-new', '2026-10-22T00:00:00Z')
  const json = await readJson<{replicaId: string; libraries: {replicaId: string}[]}>(folderJsonPath(root))
  assert.equal(json!.replicaId, 'rep-new')
  assert.equal(json!.libraries[0].replicaId, 'rep-new')
  assert.ok(await fs.stat(path.join(root, '.keepfin', 'libraries', t, 'actors', 'clone-rep-new.json')))
})
