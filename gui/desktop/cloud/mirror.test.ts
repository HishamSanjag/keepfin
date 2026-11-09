// The mirror of a cloud copy: pull brings the local image level with the replica, push sends what a run changed. Against a fake
// Microsoft Graph: new, changed and moved files, a removal that is only believed from a complete listing, a change on both
// sides that keeps both, names that cannot be stored here, and a listing that tears.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {afterEach, beforeEach, describe, it} from 'node:test'
import {FakeGraph} from './fakes/graph.js'
import {pull, push, readState, safeRelative} from './mirror.js'
import type {MirrorOptions} from './mirror.js'
import {contentIdOf, rig, tmpDir} from './testkit.js'
import type {Rig} from './testkit.js'
import {CloudTransport} from './transport.js'

describe('mirror (pull and push) against a fake Graph', () => {
  let fake: FakeGraph
  let r: Rig
  let dir: string
  let o: MirrorOptions
  beforeEach(async () => {
    fake = new FakeGraph()
    await fake.server.start()
    r = rig('graph', fake.rewrite)
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
  const read = (rel: string) => fs.readFile(path.join(o.mirror, ...rel.split('/')), 'utf8')
  const remoteLines = async () => {
    const out = new Map<string, number>()
    await o.media.scan('', (l) => out.set(l.path, l.size))
    await o.meta.scan('', (l) => out.set('.keepfin/' + l.path, l.size))
    return out
  }

  it('pushes new files, replaces changed ones, and sends a moved file as a move, not as bytes', async () => {
    await put('Music/a.mp3', 'AAAA')
    await put('Music/b.mp3', 'BBBB')
    await put('.keepfin/folder.json', '{"generation":0}')
    const first = await push(o)
    assert.equal(first.uploaded, 3)
    assert.deepEqual(first.failed, [])
    assert.deepEqual([...(await remoteLines()).keys()].sort(), ['.keepfin/folder.json', 'Music/a.mp3', 'Music/b.mp3'])

    // b.mp3 is archived (moved) into versions and a new b.mp3 arrives; a.mp3 changes
    await fs.mkdir(path.join(o.mirror, '.keepfin', 'versions'), {recursive: true})
    await fs.rename(path.join(o.mirror, 'Music', 'b.mp3'), path.join(o.mirror, '.keepfin', 'versions', 'v1'))
    await put('Music/b.mp3', 'BBBB-new')
    await put('Music/a.mp3', 'AAAA-2')
    const second = await push(o)
    assert.equal(second.moved, 1, 'the archived file is a move')
    assert.equal(second.uploaded, 1, 'only the new b.mp3 is sent')
    assert.equal(second.replaced, 1)
    assert.deepEqual(second.failed, [])
    const lines = await remoteLines()
    assert.equal(lines.get('.keepfin/versions/v1'), 4)
    assert.equal(lines.get('Music/b.mp3'), 8)
    const state = await readState(o.stateFile)
    assert.equal(state.files['Music/a.mp3']!.contentId, contentIdOf(Buffer.from('AAAA-2')))
    // nothing is left to do
    const third = await push(o)
    assert.deepEqual([third.uploaded, third.replaced, third.moved, third.trashed], [0, 0, 0, 0])
  })

  it('a file removed from the mirror with no copy of it elsewhere goes to the account trash, never deleted in place', async () => {
    await put('Music/gone.mp3', 'GONE')
    await push(o)
    await fs.rm(path.join(o.mirror, 'Music', 'gone.mp3'))
    const out = await push(o)
    assert.equal(out.trashed, 1)
    const lines = await remoteLines()
    assert.ok(!lines.has('Music/gone.mp3'))
    assert.ok([...lines.keys()].some((k) => k.startsWith('.keepfin/trash/') && k.endsWith('gone.mp3')))
  })

  it('pull brings a new remote file, replaces a changed one, and removes a deleted one only from a complete listing', async () => {
    await put('Music/a.mp3', 'AAAA')
    await put('Music/b.mp3', 'BBBB')
    await put('Music/c.mp3', 'CCCC')
    await push(o)
    // another device: changes a, deletes c, adds d
    const other = path.join(dir, 'other')
    await fs.mkdir(other)
    await fs.writeFile(path.join(other, 'a'), 'AAAA-by-other')
    await r.transport.replace(path.join(other, 'a'), 'Music/a.mp3', contentIdOf(Buffer.from('AAAA-by-other')), (await remoteRevision('Music/a.mp3')))
    await r.transport.moveToTrash('Music/c.mp3', null)
    await fs.writeFile(path.join(other, 'd'), 'DDDD')
    await r.transport.uploadNew(path.join(other, 'd'), 'Music/d.mp3', contentIdOf(Buffer.from('DDDD')))

    const report = await pull(o)
    assert.equal(report.complete, true)
    assert.equal(await read('Music/a.mp3'), 'AAAA-by-other')
    assert.equal(await read('Music/d.mp3'), 'DDDD')
    assert.equal(await read('Music/b.mp3'), 'BBBB')
    await assert.rejects(read('Music/c.mp3'))
    assert.equal(report.removed, 1)
    assert.equal(report.downloaded, 2)
    // and the mirror is level: nothing to push
    const again = await push(o)
    assert.deepEqual([again.uploaded, again.replaced, again.moved, again.trashed], [0, 0, 0, 0])
  })

  it('a new device mirror places segments the main folder holds instead of downloading them, and downloads only what it lacks', async () => {
    const seg = (n: number) => `.keepfin/ops/actor1/${String(n).padStart(16, '0')}-${String(n + 9).padStart(16, '0')}.seg`
    // the account holds three segments (another device sent them) and a checkpoint part
    const other = path.join(dir, 'other')
    await fs.mkdir(other)
    const names = [seg(1), seg(11), seg(21), '.keepfin/checkpoints/0/x-actor1.ckpt']
    for (const rel of names) {
      await fs.writeFile(path.join(other, 'f'), 'bytes of ' + rel)
      await o.meta.uploadNew(path.join(other, 'f'), rel.slice('.keepfin/'.length), contentIdOf(Buffer.from('bytes of ' + rel)))
    }
    // this device's main folder holds the first two segments and the part, not the third
    const main = path.join(dir, 'main')
    for (const rel of [names[0]!, names[1]!, names[3]!]) {
      await fs.mkdir(path.dirname(path.join(main, ...rel.split('/'))), {recursive: true})
      await fs.writeFile(path.join(main, ...rel.split('/')), 'bytes of ' + rel)
    }
    const downloadsOf = () => fake.server.log.filter((l) => l.method === 'GET' && l.host !== undefined && /content/i.test(l.path + l.host)).length
    const metaOnly: MirrorOptions = {...o, metaOnly: true, localSource: (rel) => path.join(main, ...rel.split('/'))}

    const before = downloadsOf()
    const eager = await pull({...metaOnly, localSource: undefined, mirror: path.join(dir, 'eager'), stateFile: path.join(dir, 'eager.json')})
    const eagerRequests = downloadsOf() - before
    assert.equal(eager.downloaded, 4)

    const mid = downloadsOf()
    const lazy = await pull(metaOnly)
    const lazyRequests = downloadsOf() - mid
    assert.equal(lazy.fromMain, 3)
    assert.equal(lazy.downloaded, 1)
    assert.ok(lazyRequests < eagerRequests, `download requests ${eagerRequests} -> ${lazyRequests}`)
    console.log('mirror download requests, eager vs lazy:', eagerRequests, lazyRequests)
    for (const rel of names) assert.equal(await read(rel), 'bytes of ' + rel)
    // the mirror is level: nothing to send, nothing trashed
    const pushed = await push(metaOnly)
    assert.deepEqual([pushed.uploaded, pushed.replaced, pushed.moved, pushed.trashed, pushed.pruned], [0, 0, 0, 0, 0])
    // a main file of another size is not trusted: it is downloaded
    const again = path.join(dir, 'again')
    await fs.mkdir(path.join(again, '.keepfin', 'ops', 'actor1'), {recursive: true})
    await fs.writeFile(path.join(again, ...names[0]!.split('/')), 'shorter')
    const third = await pull({...metaOnly, mirror: path.join(dir, 'third'), stateFile: path.join(dir, 'third.json'), localSource: (rel) => path.join(again, ...rel.split('/'))})
    assert.equal(third.fromMain, 0)
    assert.equal(third.downloaded, 4)
  })

  async function remoteRevision(rel: string): Promise<string | null> {
    let rev: string | null = null
    await o.media.scan('', (l) => {
      if (l.path === rel) rev = l.revision
    })
    return rev
  }

  it('a listing that tears changes nothing and removes nothing', async () => {
    await put('Music/a.mp3', 'AAAA')
    await put('Music/b.mp3', 'BBBB')
    await push(o)
    fake.server.fail((req) => req.method === 'GET' && /children/.test(req.url.pathname), {status: 500, body: {error: {code: 'generalException'}}}, 1000)
    const report = await pull(o)
    assert.equal(report.complete, false)
    assert.equal(report.removed, 0)
    assert.equal(await read('Music/a.mp3'), 'AAAA')
    assert.equal(await read('Music/b.mp3'), 'BBBB')
    fake.server.clearFaults()
  })

  it('an empty listing after many files is not believed', async () => {
    for (let i = 0; i < 5; i++) await put(`Music/${i}.mp3`, `T${i}`)
    await push(o)
    // everything vanished from the account at once
    for (let i = 0; i < 5; i++) await r.transport.moveToTrash(`Music/${i}.mp3`, null)
    const report = await pull(o)
    assert.equal(report.suspicious, true)
    assert.equal(await read('Music/0.mp3'), 'T0', 'the files stay in the mirror')
  })

  it('a file changed on both sides keeps both: the local bytes go to .keepfin/conflicts and travel on', async () => {
    await put('Music/a.mp3', 'AAAA')
    await push(o)
    const other = path.join(dir, 'other')
    await fs.mkdir(other)
    await fs.writeFile(path.join(other, 'a'), 'REMOTE-EDIT')
    await r.transport.replace(path.join(other, 'a'), 'Music/a.mp3', contentIdOf(Buffer.from('REMOTE-EDIT')), await remoteRevision('Music/a.mp3'))
    await put('Music/a.mp3', 'LOCAL-EDIT-LONGER')
    const report = await pull(o)
    assert.equal(report.conflicts, 1)
    assert.equal(await read('Music/a.mp3'), 'REMOTE-EDIT')
    const kept = (await fs.readdir(path.join(o.mirror, '.keepfin', 'conflicts')))[0]!
    assert.equal(await read(`.keepfin/conflicts/${kept}`), 'LOCAL-EDIT-LONGER')
    const out = await push(o)
    assert.equal(out.uploaded, 1, 'the kept bytes reach the account too')
  })

  it('names that cannot be stored here are left alone, and nothing climbs out of the mirror', () => {
    assert.equal(safeRelative('Music/a.mp3'), true)
    assert.equal(safeRelative('../x'), false)
    assert.equal(safeRelative('Music/../../x'), false)
    assert.equal(safeRelative('Music//a'), false)
    assert.equal(safeRelative(''), false)
    if (process.platform === 'win32') {
      assert.equal(safeRelative('Music/a:b.mp3'), false)
      assert.equal(safeRelative('Music/CON.mp3'), false)
      assert.equal(safeRelative('Music/trailing.'), false)
    }
  })
})
