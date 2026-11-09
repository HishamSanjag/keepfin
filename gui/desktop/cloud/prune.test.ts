// A merged segment makes the small ones it covers redundant (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md R6): the mirror removes them from
// the account for good, without Keepfin trash entries, and another device's mirror takes the removal without calling it a mass removal.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {afterEach, beforeEach, describe, it} from 'node:test'
import {FakeGraph} from './fakes/graph.js'
import {pull, push} from './mirror.js'
import type {MirrorOptions, MirrorProgress} from './mirror.js'
import {rig, tmpDir} from './testkit.js'
import type {Rig} from './testkit.js'
import {CloudTransport} from './transport.js'

const ACTOR = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const seg = (a: number, b: number) => `.keepfin/ops/${ACTOR}/${String(a).padStart(16, '0')}-${String(b).padStart(16, '0')}.seg`

describe('pruning redundant segments on a fake Graph account', () => {
  let fake: FakeGraph
  let r: Rig
  let dir: string
  let a: MirrorOptions
  let b: MirrorOptions
  beforeEach(async () => {
    fake = new FakeGraph()
    await fake.server.start()
    r = rig('graph', fake.rewrite)
    dir = await tmpDir()
    const options = (name: string): MirrorOptions => ({
      mirror: path.join(dir, name, 'mirror'),
      stateFile: path.join(dir, name, 'state.json'),
      staging: path.join(dir, name, 'staging'),
      media: r.transport,
      meta: new CloudTransport({client: r.client, rootPath: 'Keepfin/.keepfin'}),
      metaOnly: true,
    })
    a = options('a')
    b = options('b')
    await fs.mkdir(a.mirror, {recursive: true})
    await fs.mkdir(b.mirror, {recursive: true})
    await r.transport.ensureRoot()
  })
  afterEach(async () => {
    await fake.server.stop()
    await fs.rm(dir, {recursive: true, force: true})
  })

  const put = async (o: MirrorOptions, rel: string, text: string) => {
    const file = path.join(o.mirror, ...rel.split('/'))
    await fs.mkdir(path.dirname(file), {recursive: true})
    await fs.writeFile(file, text)
  }
  const remote = async () => {
    const out: string[] = []
    await r.transport.scan('', (l) => out.push(l.path))
    const meta = new CloudTransport({client: r.client, rootPath: 'Keepfin/.keepfin'})
    await meta.scan('', (l) => out.push('.keepfin/' + l.path))
    return out.sort()
  }

  it('prunes for good, without trash entries, and a second mirror follows without a mass-removal stop', async () => {
    const small = 120
    for (let i = 1; i <= small; i++) await put(a, seg(i, i), `small ${i}`)
    await put(a, seg(1, small), 'merged')
    await put(a, '.keepfin/folder.json', '{}')
    const first = await push(a)
    assert.equal(first.uploaded, small + 2)
    const pulled = await pull(b)
    assert.equal(pulled.downloaded, small + 2)

    // device A merged them: the small files are gone from its mirror
    for (let i = 1; i <= small; i++) await fs.rm(path.join(a.mirror, ...seg(i, i).split('/')))
    const second = await push(a)
    assert.equal(second.pruned, small)
    assert.equal(second.trashed, 0)
    assert.deepEqual(second.failed, [])
    const left = await remote()
    assert.deepEqual(left.filter((p) => p.includes('/ops/')), [seg(1, small)])
    assert.ok(!left.some((p) => p.includes('trash')), 'no Keepfin trash entries')

    const followed = await pull(b)
    assert.equal(followed.suspicious, false)
    assert.equal(followed.removed, small)
    const names = await fs.readdir(path.join(b.mirror, '.keepfin', 'ops', ACTOR))
    assert.deepEqual(names, [seg(1, small).split('/').pop()])
  })

  it('says how far the clean-up is: tidying ticks count the removals, and four run at once', async () => {
    const small = 40
    for (let i = 1; i <= small; i++) await put(a, seg(i, i), `small ${i}`)
    await put(a, seg(1, small), 'merged')
    await push(a)
    for (let i = 1; i <= small; i++) await fs.rm(path.join(a.mirror, ...seg(i, i).split('/')))
    const ticks: MirrorProgress[] = []
    const out = await push({...a, onProgress: (p) => ticks.push(p)})
    assert.equal(out.pruned, small)
    const tidy = ticks.filter((p) => p.phase === 'tidying')
    assert.equal(tidy.length, small)
    assert.ok(tidy.every((p) => p.total === small && p.done >= 0 && p.done < small))
    assert.equal(Math.max(...tidy.map((p) => p.done)) >= small - 4, true, 'the last ticks are near the end')
  })

  it('a removal that nothing covers still goes to the trash', async () => {
    await put(a, seg(1, 2), 'one')
    await put(a, '.keepfin/names.json', '{}')
    await push(a)
    await fs.rm(path.join(a.mirror, ...seg(1, 2).split('/')))
    await fs.rm(path.join(a.mirror, '.keepfin', 'names.json'))
    const out = await push(a)
    assert.equal(out.pruned, 0)
    assert.equal(out.trashed, 2)
  })

  it('uploads the covering file first, then prunes what it covers', async () => {
    await put(a, seg(1, 2), 'one')
    await push(a)
    // the merged file exists locally but was never uploaded; the small one is removed
    await put(a, seg(1, 4), 'merged')
    await fs.rm(path.join(a.mirror, ...seg(1, 2).split('/')))
    const second = await push(a)
    assert.equal(second.pruned, 1, 'the merged file is uploaded in the same push, before the removal')
    const lines = await remote()
    assert.ok(lines.includes(seg(1, 4)))
  })
})
