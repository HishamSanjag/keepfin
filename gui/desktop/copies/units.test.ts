import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {afterEach, describe, it} from 'node:test'
import {compareNames, fastKey, nameKey} from '../../shared/core/copies.js'
import {ExternalSorter, lineReader, tempDir} from './lines.js'
import {safeComponent} from './names.js'
import {appendCatalogue, liveEntries, planArchive, pruneVersions, purgeTrash} from './versions.js'
import {withCounter, uniqueName} from './util.js'

const dirs: string[] = []
afterEach(async () => {
  while (dirs.length) await fs.rm(dirs.pop()!, {recursive: true, force: true})
})
const tmp = async () => {
  const d = await tempDir('kf-units-')
  dirs.push(d)
  return d
}

describe('names and sorting', () => {
  it('the local key equals the core key, and the order is (key, path)', () => {
    for (const p of ['Music/A.mp3', 'ünï/Ab', 'x/CON', 'a b/Z']) {
      assert.equal(fastKey('ascii', p), nameKey('ascii', p))
      assert.equal(fastKey('exact', p), nameKey('exact', p))
    }
    assert.ok(compareNames('ascii', 'a.mp3', 'B.mp3') < 0)
    assert.ok(compareNames('ascii', 'A.mp3', 'a.mp3') < 0)
  })

  it('adds counters to taken names', async () => {
    assert.equal(withCounter('dir/song.mp3', 2), 'dir/song (2).mp3')
    const taken = new Set(['x.mp3', 'x (2).mp3'])
    assert.equal(await uniqueName('x.mp3', async (c) => taken.has(c)), 'x (3).mp3')
  })

  it('spells reserved names, rejected characters and trailing dots', () => {
    assert.equal(safeComponent('CON.mp3'), '_CON.mp3')
    assert.equal(safeComponent('a:b?c'), 'a_b_c')
    assert.equal(safeComponent('end. '), 'end__')
    assert.equal(safeComponent('fine.mp3'), 'fine.mp3')
    assert.ok(safeComponent('x'.repeat(400) + '.mp3').length <= 200)
  })

  it('the external sorter merges runs in order with bounded chunks', async () => {
    const dir = await tmp()
    const s = new ExternalSorter(dir, 7, 't')
    const paths = Array.from({length: 100}, (_, i) => `f${(i * 37) % 100}`)
    let seq = 0
    for (const p of paths) await s.add({key: p, path: p, seq: seq++, text: p})
    const out: string[] = []
    for await (const r of s.sorted()) out.push(r.text)
    assert.deepEqual(out, [...paths].sort())
    assert.deepEqual((await fs.readdir(dir)).filter((f) => f.endsWith('.sorted')), [])
  })

  it('reads lines across chunk boundaries, including multi-byte characters', async () => {
    const dir = await tmp()
    const file = path.join(dir, 'x.jsonl')
    const lines = Array.from({length: 5000}, (_, i) => `{"p":"ünïcode-${i}-日本語"}`)
    await fs.writeFile(file, lines.join('\n') + '\n')
    const r = lineReader(file)
    const got: string[] = []
    for (let l = r(); l !== null; l = r()) got.push(l)
    assert.deepEqual(got, lines)
    assert.equal(lineReader(path.join(dir, 'missing'))(), null)
  })
})

describe('versions and trash', () => {
  it('lists catalogue entries, prunes replaced versions after their days, keeps deleted ones, and purges trash after 30 days', async () => {
    const copy = await tmp()
    await fs.mkdir(path.join(copy, '.keepfin'), {recursive: true})
    const day = 86_400_000
    const t0 = Date.parse('2026-01-01T00:00:00Z')
    const mk = async (store: 'versions' | 'trash', n: number, why: string, at: number) => {
      const { entry, absolute } = await planArchive(copy, store, {itemId: 'it', revision: 'r', contentId: `sha256:${'ab'.repeat(32)}`, path: `f${n}.mp3`, physical: `f${n}.mp3`, run: 'run', n, at, why})
      await fs.mkdir(path.dirname(absolute), {recursive: true})
      await fs.writeFile(absolute, `bytes ${n}`)
      await appendCatalogue(copy, entry)
      return absolute
    }
    const replaced = await mk('versions', 1, 'replaced', t0)
    const deleted = await mk('versions', 2, 'deleted', t0)
    const recent = await mk('versions', 3, 'replaced', t0 + 80 * day)
    const oldTrash = await mk('trash', 4, 'removed', t0)
    const newTrash = await mk('trash', 5, 'removed', t0 + 20 * day)
    assert.equal([...liveEntries(copy, 'versions')].length, 3)
    assert.equal(await pruneVersions(copy, t0 + 100 * day, 90), 1)
    assert.equal(await purgeTrash(copy, t0 + 40 * day), 1)
    const exists = async (f: string) => fs.access(f).then(() => true, () => false)
    assert.deepEqual([await exists(replaced), await exists(deleted), await exists(recent)], [false, true, true])
    assert.deepEqual([await exists(oldTrash), await exists(newTrash)], [false, true])
  })

  it('never reuses an archive name', async () => {
    const copy = await tmp()
    const info = {itemId: 'it', revision: 'r', contentId: `sha256:${'cd'.repeat(32)}`, path: 'a.mp3', physical: 'a.mp3', run: 'run', n: 1, at: 0, why: 'replaced'}
    const first = await planArchive(copy, 'versions', info)
    await fs.mkdir(path.dirname(first.absolute), {recursive: true})
    await fs.writeFile(first.absolute, 'x')
    const second = await planArchive(copy, 'versions', {...info, n: 2})
    assert.notEqual(first.absolute, second.absolute)
  })
})
