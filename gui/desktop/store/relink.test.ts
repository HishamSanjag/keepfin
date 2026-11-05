// The owner's case, in store mode: an item imported with a damaged name has no placement in the Keepfin folder (its old
// path is outside it), so it opens as missing, while the real file sits in the space folder. Opening the library finds
// it again from the job that made it, and the repair is saved.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {defaultLibrarySettings, type LibrarySettings} from '../../shared/library.js'
import {LibraryIndex, type PersistedLibrary} from '../library/index.js'
import type {StoredJob} from '../migrate.js'
import {createKeepfinFolder, dataDir} from './keepfinFolder.js'
import {StoreLibrary} from './persistence.js'

const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const FULL = 'كرت 5090 يحتاج هوية! وجوجل تصنع ألعابا من الكلام 😳 _ أخبارَ الباشا 📰'
const DAMAGED = ' 5090  !       _   '

async function open(dir: string, folder: Awaited<ReturnType<typeof createKeepfinFolder>>, jobs: Partial<StoredJob>[]) {
  const library = new StoreLibrary({deviceDir: path.join(dir, 'AppData'), newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}})
  await library.open(folder, A)
  const index = new LibraryIndex({
    file: path.join(dir, 'unused', 'library.json'),
    artDir: path.join(dataDir(folder.root), 'art'),
    ffmpeg: () => undefined,
    persistence: library.persistence,
    newId: randomUUID,
    saveDelay: 10,
    pushDelay: 5,
    watch: false,
    analyse: false,
    jobs: () => jobs as StoredJob[],
  })
  await index.init()
  const loaded = await library.persistence.reload()
  const settings: LibrarySettings = {...defaultLibrarySettings({downloads: '', music: '', videos: ''}), spaces: loaded.spaces!, routes: loaded.routes!}
  index.configure(settings)
  return {library, index, settings, close: async () => { await index.close(); await library.close() }}
}

test('store mode: an item with a damaged, unplaced name is relinked when the library opens and the repair persists', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-store-relink-'))
  try {
    const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
    // The old library: an item whose saved path is the damaged name in the old downloads folder (outside the Keepfin folder).
    const seed = new StoreLibrary({deviceDir: path.join(dir, 'AppData'), newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}})
    await seed.create(folder, A)
    await seed.open(folder, A)
    const first = await seed.persistence.reload()
    const downloads = first.spaces!.find((space) => space.id === 'downloads')!.folder
    const oldFolder = path.join(dir, 'OldDownloads', 'Keepfin', 'YouTube')
    const entry = {
      id: randomUUID(), space: 'downloads', path: path.join(oldFolder, `${DAMAGED}.mp4`), folder: '', kind: 'video', title: FULL, size: 5, modifiedAt: 1, addedAt: 1,
      hasArt: false, managed: true, jobId: 'job1',
    } as unknown as PersistedLibrary['items'][number]
    await seed.persistence.save({items: [entry], collections: [], spaces: first.spaces!, routes: first.routes!})
    await seed.persistence.idle()
    await seed.close()
    // The migration moved the real file into the space folder under its full name.
    const real = path.join(downloads, 'YouTube', `${FULL}.mp4`)
    await fs.mkdir(path.dirname(real), {recursive: true})
    await fs.writeFile(real, 'video')
    const jobs: Partial<StoredJob>[] = [{
      id: 'job1', status: 'completed', url: 'https://www.youtube.com/watch?v=N-t7sKcJxes', format: {type: 'video', codec: 'auto', container: 'mp4'},
      file: path.join(oldFolder, `${DAMAGED}.mp4`), outputTemplate: path.join(oldFolder, `${FULL}.%(ext)s`),
    }]

    const started = await open(dir, folder, jobs)
    try {
      const before = started.index.all()
      assert.equal(before.length, 1)
      await started.index.scan()
      await started.index.settled()
      const item = started.index.all()[0]
      assert.equal(item.path, real)
      assert.ok(!item.missing)
      await started.index.flush()
    } finally {
      await started.close()
    }

    // After a restart the item is where the repair put it, with no further help from the job.
    const again = await open(dir, folder, [])
    try {
      const item = again.index.all()[0]
      assert.equal(item.path, real)
      assert.ok(!item.missing)
      assert.equal(item.title, FULL)
    } finally {
      await again.close()
    }
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})
