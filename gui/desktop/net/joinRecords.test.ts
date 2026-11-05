// Joining a library into a records root of an existing Keepfin folder (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 2), and the
// join-record-first rule that closes the crash window of the ordinary join.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {PeerClient} from './client.js'
import {JoinError, joinFile, readJoin, runJoin, type JoinRecord} from './join.js'
import {listSegments, vectorOf} from './segments.js'
import {recordsPath} from '../store/keepfinFolder.js'
import {writeJsonAtomic} from '../store/atomic.js'
import {World} from './testkit.js'

async function rig() {
  const w = await World.create()
  const host = await w.node('Host')
  const guest = await w.node('Guest', {libraryId: randomUUID()})
  for (let i = 1; i <= 4; i++) {
    await host.add(i)
    await host.store.flush()
  }
  await guest.add(100, 'Mine')
  await guest.store.flush()
  await w.pair(guest, host)
  await host.service.shareLibrary(guest.identity.deviceId, w.libraryId)
  const client = new PeerClient({identity: guest.identity, host: '127.0.0.1', port: host.port, pin: host.identity.deviceId})
  return {w, host, guest, client}
}

const tree = async (dir: string): Promise<string[]> => {
  const out: string[] = []
  const walk = async (d: string) => {
    for (const e of await fs.readdir(d, {withFileTypes: true}).catch(() => [])) {
      const p = path.join(d, e.name)
      if (e.isDirectory()) await walk(p)
      else out.push(path.relative(dir, p))
    }
  }
  await walk(dir)
  return out.sort()
}

test('joining into a records root leaves the media folder and the old records alone', async () => {
  const {w, host, guest, client} = await rig()
  try {
    const records = `libraries/${w.libraryId}`
    await fs.mkdir(path.join(guest.root, 'Music'), {recursive: true})
    await fs.writeFile(path.join(guest.root, 'Music', 'song.mp3'), 'media bytes')
    const oldRecords = await tree(path.join(guest.root, '.keepfin', 'ops'))
    const recordFile = path.join(guest.dir, 'merge-join.json')
    const done = await runJoin({client, peerId: host.identity.deviceId, libraryId: w.libraryId, name: 'Host', target: guest.root, records, recordFile})
    assert.equal(done.records, records)
    const have = vectorOf(await listSegments(guest.root, records))
    for (const [actor, seq] of vectorOf(await listSegments(host.root))) assert.ok((have.get(actor) ?? 0) >= seq, `segments of ${actor}`)
    assert.deepEqual(await tree(path.join(guest.root, '.keepfin', 'ops')), oldRecords, 'S records unchanged')
    assert.equal(await fs.readFile(path.join(guest.root, 'Music', 'song.mp3'), 'utf8'), 'media bytes')
    assert.equal((await readJoin(guest.root, records, recordFile))?.stage, 'ready')
    // the record is where it was told to be, not inside the records root
    assert.deepEqual(await fs.readdir(recordsPath(guest.root, records)), ['actors', 'art', 'checkpoints', 'ops', 'trash'])
    // joining it a second time here is refused: the records root now holds it
    await assert.rejects(
      () => runJoin({client, peerId: host.identity.deviceId, libraryId: w.libraryId, name: 'Host', target: guest.root, records, recordFile: path.join(guest.dir, 'other.json')}),
      (e: JoinError) => e.code === 'folder',
    )
  } finally {
    client.close()
    await w.cleanup()
  }
})

test('the media folder having files is no problem for a records join, but it must be a Keepfin folder', async () => {
  const {w, host, guest, client} = await rig()
  try {
    const bare = path.join(w.dir, 'NotKeepfin')
    await fs.mkdir(bare, {recursive: true})
    await assert.rejects(
      () => runJoin({client, peerId: host.identity.deviceId, libraryId: w.libraryId, name: 'Host', target: bare, records: `libraries/${w.libraryId}`, recordFile: path.join(w.dir, 'r.json')}),
      (e: JoinError) => e.code === 'folder',
    )
    await assert.rejects(
      () => runJoin({client, peerId: host.identity.deviceId, libraryId: w.libraryId, name: 'Host', target: guest.root, records: '../escape', recordFile: path.join(w.dir, 'r2.json')}),
      (e: JoinError) => e.code === 'folder',
    )
  } finally {
    client.close()
    await w.cleanup()
  }
})

test('crash window 1 (records join): the record exists and nothing else does; the same identity resumes', async () => {
  const {w, host, guest, client} = await rig()
  try {
    const records = `libraries/${w.libraryId}`
    const recordFile = path.join(guest.dir, 'merge-join.json')
    const stale: JoinRecord = {v: 1, libraryId: w.libraryId, from: host.identity.deviceId, replicaId: randomUUID(), actor: randomUUID(), startedAt: Date.now(), stage: 'joining', name: 'Host'}
    await writeJsonAtomic(recordFile, stale)
    assert.equal(await fs.stat(recordsPath(guest.root, records)).then(() => true, () => false), false)
    const done = await runJoin({client, peerId: host.identity.deviceId, libraryId: w.libraryId, name: 'Host', target: guest.root, records, recordFile})
    assert.equal(done.replicaId, stale.replicaId)
    assert.equal(done.actor, stale.actor)
    assert.ok((await listSegments(guest.root, records)).length > 0)
  } finally {
    client.close()
    await w.cleanup()
  }
})

test('crash window 2 (ordinary join): the record is written before the folder is created, and an interrupted start resumes', async () => {
  const {w, host, client} = await rig()
  try {
    const target = path.join(w.dir, 'Guest', 'Fresh')
    const stale: JoinRecord = {v: 1, libraryId: w.libraryId, from: host.identity.deviceId, replicaId: randomUUID(), actor: randomUUID(), startedAt: Date.now(), stage: 'joining', name: 'Host'}
    // what an interrupted start leaves: the record, no folder.json yet
    await writeJsonAtomic(joinFile(target), stale)
    const done = await runJoin({client, peerId: host.identity.deviceId, libraryId: w.libraryId, name: 'Host', target, hide: () => undefined})
    assert.equal(done.replicaId, stale.replicaId)
    const folderJson = JSON.parse(await fs.readFile(path.join(target, '.keepfin', 'folder.json'), 'utf8'))
    assert.equal(folderJson.replicaId, stale.replicaId)
    assert.equal(folderJson.format, 1, 'an ordinary join is still format 1')
  } finally {
    client.close()
    await w.cleanup()
  }
})

test('an ordinary join writes its record before it makes the folder', async () => {
  const {w, host, client} = await rig()
  try {
    const target = path.join(w.dir, 'Guest', 'Order')
    const seen: string[] = []
    await runJoin({
      client,
      peerId: host.identity.deviceId,
      libraryId: w.libraryId,
      name: 'Host',
      target,
      hide: async () => {
        // called while the folder is made: the record is already on disk
        seen.push((await readJoin(target)) ? 'record-first' : 'folder-first')
      },
    })
    assert.deepEqual(seen, ['record-first'])
  } finally {
    client.close()
    await w.cleanup()
  }
})
