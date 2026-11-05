// Items saved by an older version under a damaged file name (every Arabic letter and emoji dropped) are relinked to the
// real file when exactly one file in the folder fits; anything unclear keeps today's "no longer at its saved location".
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {defaultLibrarySettings} from '../shared/library.js'
import {LibraryIndex} from './library/index.js'
import {handleLibraryRequest, type LibraryDeps} from './library/ops.js'
import type {StoredJob} from './migrate.js'

const FULL = 'كرت 5090 يحتاج هوية! وجوجل 😳 _ أخبارَ الباشا 📰'
const DAMAGED = ' 5090  !   _   '

const item = (id: string, file: string, extra: Record<string, unknown> = {}) => ({
  id, path: file, space: 'downloads', folder: '', kind: 'video', title: FULL, size: 5, modifiedAt: 1, addedAt: 1, hasArt: false, managed: true, ...extra,
})

async function setup(items: unknown[], jobs: Partial<StoredJob>[] = []) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-relink-'))
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
  for (const space of settings.spaces.filter((item) => item.type === 'folder')) await fs.mkdir(space.folder, {recursive: true})
  const write = async (file: string, text = 'video') => {
    await fs.mkdir(path.dirname(file), {recursive: true})
    await fs.writeFile(file, text)
    return file
  }
  const file = path.join(dir, 'data', 'library.json')
  await write(file, JSON.stringify({version: 1, items: items.map((make) => (typeof make === 'function' ? make(dir, settings.spaces[0].folder) : make)), collections: []}))
  const relocated: [string, string][] = []
  const index = new LibraryIndex({
    file, artDir: path.join(dir, 'art'), ffmpeg: () => undefined, saveDelay: 10, pushDelay: 5, watch: false, analyse: false,
    jobs: () => jobs as StoredJob[],
  })
  index.hooks = {relocated: (from, to) => void relocated.push([from, to]), forgotten() {}}
  await index.init()
  const folder = settings.spaces[0].folder
  return {dir, folder, settings, index, write, relocated, cleanup: async () => { await index.close(); await fs.rm(dir, {recursive: true, force: true}) }}
}
const start = async (f: Awaited<ReturnType<typeof setup>>) => {
  f.index.configure(f.settings)
  await f.index.scan()
  await f.index.settled()
}

test('opening the library relinks an item to its real file when exactly one file fits', async () => {
  const f = await setup([(_dir: string, folder: string) => item('a', path.join(folder, `${DAMAGED}.mp4`))])
  try {
    const real = await f.write(path.join(f.folder, `${FULL}.mp4`))
    await f.write(path.join(f.folder, 'Unrelated.mp4'))
    await start(f)
    const entry = f.index.all().find((candidate) => candidate.title === FULL)!
    assert.equal(entry.path, real)
    assert.ok(!entry.missing)
    assert.deepEqual(f.relocated.map(([, to]) => to), [real], 'job history is told')
  } finally {
    await f.cleanup()
  }
})

test('two files that fit are ambiguous: the item stays missing', async () => {
  const f = await setup([(_dir: string, folder: string) => item('a', path.join(folder, `${DAMAGED}.mp4`))])
  try {
    await f.write(path.join(f.folder, `${FULL}.mp4`))
    await f.write(path.join(f.folder, `${FULL} 2😳.mp4`.replace(' 2', '')))
    await start(f)
    const entry = f.index.all()[0]
    assert.equal(entry.path, path.join(f.folder, `${DAMAGED}.mp4`))
    assert.equal(entry.missing, true)
  } finally {
    await f.cleanup()
  }
})

test('a file that already belongs to another item is not taken', async () => {
  const f = await setup([
    (_dir: string, folder: string) => item('a', path.join(folder, `${DAMAGED}.mp4`)),
    (_dir: string, folder: string) => item('b', path.join(folder, `${FULL}.mp4`), {title: 'Other'}),
  ])
  try {
    const owned = await f.write(path.join(f.folder, `${FULL}.mp4`))
    await start(f)
    const byId = new Map(f.index.all().map((entry) => [entry.title, entry]))
    assert.equal(byId.get('Other')!.path, owned)
    assert.equal(byId.get(FULL)!.missing, true)
  } finally {
    await f.cleanup()
  }
})

test('a file with a different extension does not match', async () => {
  const f = await setup([(_dir: string, folder: string) => item('a', path.join(folder, `${DAMAGED}.mp4`))])
  try {
    await f.write(path.join(f.folder, `${FULL}.webm`))
    await start(f)
    assert.equal(f.index.all()[0].missing, true)
  } finally {
    await f.cleanup()
  }
})

test('two missing items that fit the same single file are not guessed', async () => {
  const f = await setup([
    (_dir: string, folder: string) => item('a', path.join(folder, `${DAMAGED}.mp4`)),
    (_dir: string, folder: string) => item('b', path.join(folder, ` 5090 !_ .mp4`), {title: 'Twin'}),
  ])
  try {
    await f.write(path.join(f.folder, `${FULL}.mp4`))
    await start(f)
    assert.ok(f.index.all().every((entry) => entry.missing))
  } finally {
    await f.cleanup()
  }
})

test('opening a missing item looks for its file first; a real miss keeps the message', async () => {
  const f = await setup([(_dir: string, folder: string) => item('a', path.join(folder, `${DAMAGED}.mp4`))])
  try {
    f.index.configure(f.settings)
    const opened: string[] = []
    const deps = {index: f.index, openPath: async (file: string) => (opened.push(file), ''), showInFolder: (file: string) => void opened.push(`show:${file}`)} as unknown as LibraryDeps
    await assert.rejects(() => handleLibraryRequest({op: 'libraryReveal', id: f.index.all()[0].id} as never, deps), /no longer at its saved location/)
    const real = await f.write(path.join(f.folder, `${FULL}.mp4`))
    await handleLibraryRequest({op: 'libraryReveal', id: f.index.all()[0].id} as never, deps)
    assert.deepEqual(opened, [`show:${real}`])
  } finally {
    await f.cleanup()
  }
})

test('the job that made the item points to its folder when the item lost its name (a moved library)', async () => {
  // The item has no usable path (store mode shows it under .keepfin/unplaced); the job still knows the name it asked for.
  const jobs: Partial<StoredJob>[] = [
    {id: 'job1', status: 'completed', url: 'https://www.youtube.com/watch?v=N-t7sKcJxes', format: {type: 'video', codec: 'auto', container: 'mp4'}, file: `C:\\Users\\x\\Downloads\\Keepfin\\YouTube\\${DAMAGED}.mp4`, outputTemplate: `C:\\Users\\x\\Downloads\\Keepfin\\YouTube\\${FULL}.%(ext)s`},
  ]
  const f = await setup([(dir: string) => item('a', path.join(dir, '.keepfin', 'unplaced', 'a'), {jobId: 'job1'})], jobs)
  try {
    const real = await f.write(path.join(f.folder, 'YouTube', `${FULL}.mp4`))
    await start(f)
    const entry = f.index.all()[0]
    assert.equal(entry.path, real)
    assert.ok(!entry.missing)
    assert.ok(f.relocated.some(([from, to]) => from.endsWith(`${DAMAGED}.mp4`) && to === real), 'the job history follows')
  } finally {
    await f.cleanup()
  }
})
