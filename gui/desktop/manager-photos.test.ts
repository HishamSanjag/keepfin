// Photo posts on the PC (issue #14): a photo post sent from another device keeps the photos chosen there, "Save again" adds only the
// files that did not reach the library, and a post that is already saved (every file still there) is not downloaded twice.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import type {MediaInfo} from '../shared/contracts.js'
import {DownloadManager, type RemoteOptions} from './manager.js'
import {FakeEngine, fakeMedia, type DownloadScript} from './fake-engine.js'
import {LibraryIndex} from './library/index.js'

const photos = (url: string, count: number): MediaInfo =>
  fakeMedia(url, {
    title: 'Trip',
    engine: 'gallery-dl',
    extractor: 'gallery-dl:instagram',
    gallery: {count, previews: [], truncated: false, items: Array.from({length: count}, (_, i) => ({position: i + 1, url: `https://cdn.example/${i + 1}.jpg`, photo: true}))},
  })

/** Writes one jpg per chosen photo (or `count` of them) into `<folder>/<stem>/`, like the gallery engine. */
const writePhotos =
  (count: number): DownloadScript =>
  async (task) => {
    const folder = path.join(task.folder, task.stem)
    await fs.mkdir(folder, {recursive: true})
    const chosen = task.format.type === 'gallery' && task.format.items ? task.format.items : Array.from({length: count}, (_, i) => i + 1)
    const files: string[] = []
    for (const n of chosen) {
      const file = path.join(folder, `${n}.jpg`)
      await fs.writeFile(file, `photo ${n}`)
      files.push(file)
    }
    return {file: files[0], files}
  }

async function setup(remote?: Partial<RemoteOptions>) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-photos-'))
  const engine = new FakeEngine()
  const library = new LibraryIndex({file: path.join(dir, 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => undefined, saveDelay: 10, pushDelay: 5, watch: false, analyse: false})
  await library.init()
  const origins = new Set<string>()
  const manager = new DownloadManager({
    store: path.join(dir, 'queue.json'),
    defaultOutDir: path.join(dir, 'downloads'),
    libraryFolders: {music: path.join(dir, 'music'), videos: path.join(dir, 'videos')},
    engine,
    library,
    snapshotDelay: 5,
    progressInterval: 20,
    remote: {guard: async () => undefined, consentBytes: 1_000_000, metered: async () => false, originOf: (id) => (origins.has(id) ? {device: 'Pixel'} : undefined), ...remote},
  })
  await manager.init()
  const wait = async (check: () => boolean, label: string) => {
    for (let i = 0; i < 300; i++) {
      if (check()) return
      await delay(10)
    }
    throw new Error(`Timed out waiting for ${label}`)
  }
  return {dir, engine, manager, library, origins, wait, cleanup: async () => (await manager.close(), await library.close(), fs.rm(dir, {recursive: true, force: true}))}
}

test('a photo post sent from another device keeps the photos chosen there and labels the job', async () => {
  const f = await setup()
  try {
    f.engine.inspectResult = (url) => ({type: 'media', media: photos(url, 6)})
    f.engine.script = writePhotos(6)
    f.origins.add('r1')
    await f.manager.enqueueRemote({id: 'r1', url: 'https://www.instagram.com/p/AAA/', format: {type: 'gallery', items: [2, 4, 5]}})
    await f.wait(() => f.manager.snapshot().jobs[0]?.status === 'completed', 'the download')
    const job = f.manager.snapshot().jobs[0]
    assert.deepEqual(job.format, {type: 'gallery', items: [2, 4, 5]})
    assert.equal(job.label, '3 photos')
    assert.deepEqual(f.engine.downloads[0].format, {type: 'gallery', items: [2, 4, 5]})
    assert.equal(f.manager.remoteView('r1')?.label, '3 photos')
  } finally {
    await f.cleanup()
  }
})

test('a remote photo request names only photos of the post read here', async () => {
  const f = await setup()
  try {
    f.engine.inspectResult = (url) => ({type: 'media', media: photos(url, 3)})
    f.origins.add('r2')
    await f.manager.enqueueRemote({id: 'r2', url: 'https://www.instagram.com/p/BBB/', format: {type: 'gallery', items: [2, 9]}})
    await f.wait(() => f.manager.snapshot().jobs[0]?.status === 'failed', 'the refusal')
    assert.match(f.manager.remoteView('r2')?.error ?? '', /not in this post/)
    assert.equal(f.engine.downloads.length, 0)
    // a malformed list never even queues
    f.origins.add('r3')
    await assert.rejects(f.manager.enqueueRemote({id: 'r3', url: 'https://www.instagram.com/p/BBB/', format: {type: 'gallery', items: ['a']}}))
    await assert.rejects(f.manager.enqueueRemote({id: 'r4', url: 'https://www.instagram.com/p/BBB/', format: {type: 'gallery', items: []}}))
  } finally {
    await f.cleanup()
  }
})

test('a photo request for a link that is not a photo post fails honestly', async () => {
  const f = await setup()
  try {
    f.engine.inspectResult = (url) => ({type: 'media', media: fakeMedia(url, {title: 'A video'})})
    f.origins.add('r5')
    await f.manager.enqueueRemote({id: 'r5', url: 'https://example.com/clip', format: {type: 'gallery'}})
    await f.wait(() => f.manager.snapshot().jobs[0]?.status === 'failed', 'the refusal')
    assert.match(f.manager.remoteView('r5')?.error ?? '', /no photos/)
  } finally {
    await f.cleanup()
  }
})

test('Save again adds only the photos that did not reach the library and the label goes from 4 of 6 to 6 photos', async () => {
  const f = await setup()
  try {
    const url = 'https://www.instagram.com/p/CCC/'
    f.engine.inspectResult = (u) => ({type: 'media', media: photos(u, 6)})
    f.engine.script = writePhotos(6)
    // the library refuses two of the files once
    const original = f.library.addFile.bind(f.library)
    let broken = true
    f.library.addFile = async (file, options) => {
      if (broken && /[56]\.jpg$/.test(file)) throw new Error('The folder is full.')
      return original(file, options)
    }
    await f.manager.inspect(url)
    await f.manager.enqueue(url, {type: 'gallery'})
    await f.wait(() => f.manager.snapshot().jobs[0]?.status === 'completed', 'the download')
    let job = f.manager.snapshot().jobs[0]
    assert.match(job.saveError ?? '', /^Saved 4 of 6 files/)
    assert.equal(job.label, '4 of 6 photos')
    assert.equal(f.library.snapshot().items.length, 4, 'the four that could be saved are in the library')

    broken = false
    await f.manager.saveAgain(job.id)
    job = f.manager.snapshot().jobs[0]
    assert.equal(job.saveError, undefined)
    assert.equal(job.label, '6 photos')
    assert.equal(f.library.snapshot().items.length, 6, 'no photo twice')
  } finally {
    await f.cleanup()
  }
})

test('a photo post already saved is not downloaded again, unless a file is gone or the person says download anyway', async () => {
  const f = await setup()
  try {
    const url = 'https://www.instagram.com/p/DDD/'
    f.engine.inspectResult = (u) => ({type: 'media', media: photos(u, 3)})
    f.engine.script = writePhotos(3)
    await f.manager.inspect(url)
    await f.manager.enqueue(url, {type: 'gallery'})
    await f.wait(() => f.manager.snapshot().jobs[0]?.status === 'completed', 'the first download')
    assert.equal(f.engine.downloads.length, 1)

    // the same post (tracking parameters and www aside) and the same photos: already there
    await f.manager.inspect('https://instagram.com/p/DDD?igsh=abc')
    await f.manager.enqueue('https://instagram.com/p/DDD?igsh=abc', {type: 'gallery'})
    await f.wait(() => f.manager.snapshot().jobs[1]?.status === 'completed', 'the repeat')
    const repeat = f.manager.snapshot().jobs[1]
    assert.equal(repeat.existing?.by, 'source')
    assert.equal(repeat.existing?.path, f.manager.snapshot().jobs[0].file)
    assert.equal(f.engine.downloads.length, 1, 'nothing was downloaded for the repeat')

    // fewer photos of the same post are covered too
    await f.manager.enqueue(url, {type: 'gallery', items: [1, 3]})
    await f.wait(() => f.manager.snapshot().jobs[2]?.status === 'completed', 'the subset')
    assert.ok(f.manager.snapshot().jobs[2].existing)
    assert.equal(f.engine.downloads.length, 1)

    // Download anyway
    await f.manager.downloadAnyway(repeat.id)
    await f.wait(() => f.manager.snapshot().jobs[1]?.status === 'completed' && !f.manager.snapshot().jobs[1].existing, 'the forced download')
    assert.equal(f.engine.downloads.length, 2)

    // a file removed from disk (of both saves): the post is no longer there, so it downloads again
    for (const copy of [0, 1]) await fs.rm(f.manager.snapshot().jobs[copy].files![0])
    await f.manager.enqueue(url, {type: 'gallery'})
    await f.wait(() => f.manager.snapshot().jobs[3]?.status === 'completed', 'the download after a removal')
    assert.equal(f.manager.snapshot().jobs[3].existing, undefined)
    assert.equal(f.engine.downloads.length, 3)
  } finally {
    await f.cleanup()
  }
})

test('a different post, or one that lost a photo while saving, is never reported as already there', async () => {
  const f = await setup()
  try {
    f.engine.inspectResult = (u) => ({type: 'media', media: photos(u, 2)})
    f.engine.script = writePhotos(2)
    await f.manager.inspect('https://www.instagram.com/p/EEE/')
    await f.manager.enqueue('https://www.instagram.com/p/EEE/', {type: 'gallery'})
    await f.wait(() => f.manager.snapshot().jobs[0]?.status === 'completed', 'the first')
    await f.manager.inspect('https://www.instagram.com/p/FFF/')
    await f.manager.enqueue('https://www.instagram.com/p/FFF/', {type: 'gallery'})
    await f.wait(() => f.manager.snapshot().jobs[1]?.status === 'completed', 'the second')
    assert.equal(f.manager.snapshot().jobs[1].existing, undefined)
    assert.equal(f.engine.downloads.length, 2)
  } finally {
    await f.cleanup()
  }
})

test('the preview fetches the picture of every item of a post: photos and video covers, nothing else', async () => {
  const f = await setup()
  try {
    const url = 'https://www.instagram.com/p/MIX/'
    f.engine.inspectResult = (u) => ({
      type: 'media',
      media: fakeMedia(u, {
        title: 'Mixed',
        engine: 'gallery-dl',
        gallery: {count: 2, previews: ['https://cdn.example/1.jpg'], truncated: false, items: [
          {position: 1, url: 'https://cdn.example/1.jpg', photo: true},
          {position: 2, url: 'https://cdn.example/2.mp4', photo: false, cover: 'https://cdn.example/2-cover.jpg'},
        ]},
      }),
    })
    await f.manager.inspect(url)
    assert.deepEqual(f.manager.thumbnailSource(undefined, 'https://cdn.example/1.jpg'), {url: 'https://cdn.example/1.jpg'})
    assert.deepEqual(f.manager.thumbnailSource(undefined, 'https://cdn.example/2-cover.jpg'), {url: 'https://cdn.example/2-cover.jpg'})
    assert.throws(() => f.manager.thumbnailSource(undefined, 'https://elsewhere.example/x.jpg'), /not part of an inspected link/)
  } finally {
    await f.cleanup()
  }
})
