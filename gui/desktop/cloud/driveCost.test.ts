// What a Drive copy costs in requests: 30 media files and 300 small data files pushed through the mirror, counted per kind, and
// the folder race (parallel uploads into a folder that does not exist yet must make it once).
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {afterEach, beforeEach, describe, it} from 'node:test'
import {FakeDrive} from './fakes/drive.js'
import {unhost} from './fakes/base.js'
import {pull, push} from './mirror.js'
import type {MirrorOptions} from './mirror.js'
import {contentIdOf, rig, tmpDir} from './testkit.js'
import type {Rig} from './testkit.js'
import {CloudTransport} from './transport.js'

describe('Drive request cost', () => {
  let fake: FakeDrive
  let r: Rig
  let dir: string
  let o: MirrorOptions
  beforeEach(async () => {
    fake = new FakeDrive()
    fake.pageSize = 1000
    await fake.server.start()
    r = rig('drive', fake.rewrite)
    dir = await tmpDir()
    o = {
      mirror: path.join(dir, 'mirror'),
      stateFile: path.join(dir, 'state.json'),
      staging: path.join(dir, 'staging'),
      media: r.transport,
      meta: new CloudTransport({client: r.client, rootPath: 'Keepfin/.keepfin'}),
    }
    await fs.mkdir(o.mirror, {recursive: true})
    await r.transport.ensureRoot()
  })
  afterEach(async () => {
    await fake.server.stop()
    await fs.rm(dir, {recursive: true, force: true})
  })

  const put = async (rel: string, text: string) => {
    const file = path.join(o.mirror, ...rel.split('/'))
    await fs.mkdir(path.dirname(file), {recursive: true})
    await fs.writeFile(file, text)
  }
  const kinds = () => {
    const out: Record<string, number> = {}
    for (const l of fake.server.log) {
      const p = unhost(new URL(l.path, 'http://x')).path
      const k = p.startsWith('/upload/') ? 'upload' : p === '/drive/v3/files' ? (l.method === 'GET' ? 'list' : 'create') : `${l.method} other`
      out[k] = (out[k] ?? 0) + 1
    }
    return out
  }

  it('a new Drive version with the same bytes downloads nothing (Drive bumps version for non-content changes)', {timeout: 60_000}, async () => {
    for (let i = 0; i < 12; i++) await put(`.keepfin/ops/actor-1/seg-${i}.seg`, `{"n":${i}}`)
    assert.equal((await push(o)).uploaded, 12)
    for (const n of fake.tree.nodes.values()) n.rev++ // same bytes, new version
    const before = JSON.parse(await fs.readFile(o.stateFile, 'utf8')) as {files: Record<string, {revision: string}>}
    const report = await pull(o)
    assert.equal(report.downloaded, 0)
    const after = JSON.parse(await fs.readFile(o.stateFile, 'utf8')) as {files: Record<string, {revision: string}>}
    for (const [rel, f] of Object.entries(after.files)) assert.notEqual(f.revision, before.files[rel]!.revision, rel)
    // the next push still writes nothing, and a real change on the replica is still downloaded
    assert.equal((await push(o)).uploaded, 0)
    const changed = [...fake.tree.nodes.values()].find((n) => n.name === 'seg-3.seg')!
    changed.data = Buffer.from('{"n":"changed"}')
    changed.rev++
    assert.equal((await pull(o)).downloaded, 1)
  })

  it('mirrors 30 media and 300 data files with few requests, and nothing is lost or doubled', {timeout: 120_000}, async () => {
    for (let i = 0; i < 30; i++) await put(`Music/Album ${i % 3}/track ${i}.mp3`, `media ${i} `.repeat(50))
    for (let i = 0; i < 300; i++) {
      const folder = i % 3 === 0 ? `ops/actor-${i % 4}` : i % 3 === 1 ? `places/p${i % 7}` : 'names'
      await put(`.keepfin/${folder}/seg-${i}.seg`, `{"n":${i}}`)
    }
    fake.server.log.length = 0
    const report = await push(o)
    const counts = kinds()
    const total = fake.server.log.length
    console.log(`DRIVE requests for 330 files: total ${total}, ${JSON.stringify(counts)}`)
    assert.equal(report.uploaded, 330)
    assert.deepEqual(report.failed, [])
    // no name exists twice on the account
    const seen = new Set<string>()
    for (const n of fake.tree.nodes.values()) {
      if (n.trashed || !n.parent) continue
      const key = `${n.parent}/${n.name}`
      assert.ok(!seen.has(key), `duplicate ${key}`)
      seen.add(key)
    }
    // a second run finds everything level
    const again = await push(o)
    assert.equal(again.uploaded, 0)
    const back = await pull({...o, mirror: path.join(dir, 'other'), stateFile: path.join(dir, 'other.json')})
    assert.equal(back.downloaded, 330)
    // before the shared listings: 704 listings and 1,401 requests; now one listing per folder plus one check per folder
    assert.ok((counts.list ?? 0) <= 100, `expected about one listing per folder, saw ${counts.list}`)
    assert.ok(total <= 800, `expected at most 800 requests, saw ${total}`)
  })

  it('still sends a racing duplicate of a batched upload to the trash, keeping the earliest object', async () => {
    const names = ['a', 'b', 'c']
    for (const n of names) await put(`Dir/${n}.txt`, n)
    r.transport.beginUploads(names.map((n) => `Dir/${n}.txt`))
    await r.transport.uploadNew(path.join(o.mirror, 'Dir', 'a.txt'), 'Dir/a.txt', contentIdOf(Buffer.from('a')))
    // another device puts a b.txt in the folder; ours is made to look later than theirs
    const dirNode = [...fake.tree.nodes.values()].find((n) => n.name === 'Dir')!
    const theirs = fake.tree.add(dirNode.id, 'b.txt', false, Buffer.from('b'))
    theirs.created -= 60_000
    await r.transport.uploadNew(path.join(o.mirror, 'Dir', 'b.txt'), 'Dir/b.txt', contentIdOf(Buffer.from('b')))
    const mine = [...fake.tree.nodes.values()].find((n) => n.name === 'b.txt' && n.id !== theirs.id)!
    mine.created = theirs.created + 120_000
    const lost = await r.transport.endUploads()
    assert.deepEqual(lost, ['Dir/b.txt'])
    assert.equal(mine.trashed, true)
    assert.equal(theirs.trashed, false)
  })

  it('makes a folder once when parallel uploads need it at the same time', async () => {
    for (let i = 0; i < 8; i++) await put(`Fresh/Deep/file-${i}.txt`, `x${i}`)
    const report = await push(o)
    assert.deepEqual(report.failed, [])
    assert.equal(report.uploaded, 8)
    const folders = [...fake.tree.nodes.values()].filter((n) => n.folder && !n.trashed && (n.name === 'Fresh' || n.name === 'Deep'))
    assert.deepEqual(folders.map((f) => f.name).sort(), ['Deep', 'Fresh'], 'each folder exists once')
  })
})
