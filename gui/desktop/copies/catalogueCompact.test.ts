// A copy's catalogues are append-only; past about 2,000 lines they are rewritten without their dead lines. The readers of this release
// and of 1.0.2 (a frozen copy of its reader in testdata/) must see exactly the same entries before and after, and a crash while
// rewriting must leave the original file whole.
import assert from 'node:assert/strict'
import {readFileSync, rmSync, writeFileSync} from 'node:fs'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {test} from 'node:test'
import {fileURLToPath} from 'node:url'
import {compactCatalogue, compactCatalogues, COMPACT_LINES} from './catalogueCompact.js'
import {catalogueFile, liveEntries, type CatalogueEntry, type Store} from './versions.js'

const entry = (n: number, store: Store, extra: Partial<CatalogueEntry> = {}): CatalogueEntry => ({
  id: `run-${n}`,
  store,
  itemId: store === 'versions' ? `item-${n % 7}` : null,
  revision: `rev-${n}`,
  path: `Music/song ${n}.mp3`,
  contentId: `sha256:${String(n).padStart(64, '0')}`,
  file: store === 'versions' ? `versions/item-${n % 7}/rev-${n}-abc.mp3` : `trash/2026-11-01/run-${n}/Music/song ${n}.mp3`,
  archivedAt: 1_700_000_000_000 + n,
  why: n % 3 === 0 ? 'deleted' : 'replaced',
  run: 'run',
  ...extra,
})

/** [live] entries nobody removed, [voided] entries whose move did not happen, [expired] entries that were cleaned up. */
async function build(root: string, store: Store, live: number, voided: number, expired: number) {
  const lines: string[] = []
  let n = 0
  for (let i = 0; i < live; i++) lines.push(JSON.stringify(entry(++n, store)))
  for (let i = 0; i < voided; i++) {
    const e = entry(++n, store)
    lines.push(JSON.stringify(e), JSON.stringify({void: e.id}))
  }
  for (let i = 0; i < expired; i++) {
    const e = entry(++n, store)
    lines.push(JSON.stringify(e), JSON.stringify({gone: e.id}))
  }
  const file = catalogueFile(root, store)
  await fs.mkdir(path.dirname(file), {recursive: true})
  await fs.writeFile(file, lines.join('\n') + '\n')
  return file
}

const tmp = () => fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-catalogue-'))
const seen = (root: string, store: Store) => JSON.stringify([...liveEntries(root, store)])
const leftovers = async (file: string) => (await fs.readdir(path.dirname(file))).filter((n) => n.includes('.compact-'))

async function released() {
  const here = path.dirname(fileURLToPath(import.meta.url))
  const file = path.join(here, `versions.released.${process.pid}.tmp.ts`)
  writeFileSync(file, readFileSync(path.join(here, 'testdata', 'versions-v1.0.2.ts.txt'), 'utf8'))
  const module = (await import(`file:///${file.replace(/\\/g, '/')}`)) as {liveEntries: typeof liveEntries}
  return {liveEntries: module.liveEntries, cleanup: () => rmSync(file, {force: true})}
}

for (const store of ['versions', 'trash'] as const) {
  test(`the ${store} catalogue loses its voided and expired lines past ${COMPACT_LINES} lines and its readers see the same entries`, async () => {
    const root = await tmp()
    const old = await released()
    try {
      const file = await build(root, store, 300, 900, 700) // 300 + 2 * 900 + 2 * 700 = 3,500 lines
      const before = seen(root, store)
      const oldBefore = JSON.stringify([...old.liveEntries(root, store)])
      assert.equal(JSON.parse(before).length, 300)

      const out = await compactCatalogue(root, store)
      assert.deepEqual(out, {before: 3500, after: 300})
      assert.equal(seen(root, store), before, 'this release reads the same entries')
      assert.equal(JSON.stringify([...old.liveEntries(root, store)]), oldBefore, '1.0.2 reads the same entries')
      assert.equal(oldBefore, before)
      assert.equal((await fs.readFile(file, 'utf8')).trimEnd().split('\n').length, 300)
      assert.deepEqual(await leftovers(file), [])

      // below the line count, or with nothing dead, the file is left alone
      assert.equal(await compactCatalogue(root, store), null)
    } finally {
      old.cleanup()
      await fs.rm(root, {recursive: true, force: true})
    }
  })
}

test('a file under the threshold is not touched, and a torn last line and a repeated line are tidied only when compacting', async () => {
  const root = await tmp()
  try {
    const file = await build(root, 'versions', 10, 5, 5)
    const bytes = await fs.readFile(file)
    assert.equal(await compactCatalogue(root, 'versions'), null)
    assert.ok((await fs.readFile(file)).equals(bytes))

    const small = await tmp()
    try {
      const f = await build(small, 'trash', 5, 1200, 0)
      const e = entry(1, 'trash')
      await fs.appendFile(f, JSON.stringify(e) + '\n' + JSON.stringify(e) + '\n{"id":"torn","fi')
      const ids = (all: CatalogueEntry[]) => all.map((x) => x.id).sort()
      const unique = ids([...liveEntries(small, 'trash')].filter((x, i, all) => all.findIndex((y) => y.id === x.id) === i))
      assert.deepEqual(await compactCatalogue(small, 'trash'), {before: 2400 + 5 + 3, after: 5})
      assert.deepEqual(ids([...liveEntries(small, 'trash')]), unique)
    } finally {
      await fs.rm(small, {recursive: true, force: true})
    }
  } finally {
    await fs.rm(root, {recursive: true, force: true})
  }
})

test('an id that was voided and written again stays dead, exactly as the readers treat it', async () => {
  const root = await tmp()
  const old = await released()
  try {
    const e = entry(1, 'versions')
    const file = catalogueFile(root, 'versions')
    await fs.mkdir(path.dirname(file), {recursive: true})
    const filler = Array.from({length: 2100}, (_, i) => JSON.stringify(entry(100 + i, 'versions')))
    await fs.writeFile(file, [JSON.stringify(e), JSON.stringify({void: e.id}), JSON.stringify({...e, revision: 'again'}), ...filler].join('\n') + '\n')
    const before = seen(root, 'versions')
    assert.equal(JSON.parse(before).length, 2100)
    assert.ok(await compactCatalogue(root, 'versions'))
    assert.equal(seen(root, 'versions'), before)
    assert.equal(JSON.stringify([...old.liveEntries(root, 'versions')]), before)
  } finally {
    old.cleanup()
    await fs.rm(root, {recursive: true, force: true})
  }
})

test('a crash while rewriting leaves the original whole, and the next run finishes the job and removes the leftover', async () => {
  const root = await tmp()
  try {
    const file = await build(root, 'trash', 100, 1000, 0)
    const bytes = await fs.readFile(file)
    const before = seen(root, 'trash')
    await assert.rejects(() => compactCatalogue(root, 'trash', {beforeRename: () => Promise.reject(new Error('power cut'))}), /power cut/)
    assert.ok((await fs.readFile(file)).equals(bytes), 'the original is untouched')
    assert.equal(seen(root, 'trash'), before)
    assert.deepEqual(await leftovers(file), [], 'the unfinished temporary file does not stay')

    // a temporary file left by a real crash (the process died, nothing cleaned up) is removed by the next run
    await fs.writeFile(path.join(path.dirname(file), 'index.jsonl.compact-deadbeef.tmp'), 'half')
    assert.ok(await compactCatalogue(root, 'trash'))
    assert.deepEqual(await leftovers(file), [])
    assert.equal(seen(root, 'trash'), before)
  } finally {
    await fs.rm(root, {recursive: true, force: true})
  }
})

test('a line appended while the file was being rewritten is not lost: the file is left for the next run', async () => {
  const root = await tmp()
  try {
    const file = await build(root, 'versions', 50, 1000, 0)
    const out = await compactCatalogue(root, 'versions', {beforeRename: () => fs.appendFile(file, JSON.stringify(entry(9999, 'versions')) + '\n')})
    assert.equal(out, null)
    assert.ok([...liveEntries(root, 'versions')].some((e) => e.id === 'run-9999'))
    assert.ok(await compactCatalogue(root, 'versions'))
    assert.ok([...liveEntries(root, 'versions')].some((e) => e.id === 'run-9999'))
  } finally {
    await fs.rm(root, {recursive: true, force: true})
  }
})

test('both catalogues of a copy are compacted together', async () => {
  const root = await tmp()
  try {
    await build(root, 'versions', 10, 1100, 0)
    await build(root, 'trash', 10, 1100, 0)
    assert.equal(await compactCatalogues(root), 2)
    assert.equal(await compactCatalogues(root), 0)
  } finally {
    await fs.rm(root, {recursive: true, force: true})
  }
})
