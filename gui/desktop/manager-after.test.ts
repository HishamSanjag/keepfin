// What happens when a download finishes (dev-docs/plans/AFTER-DOWNLOAD.md): a library save that fails shows on the job and can be run again;
// lyrics come before the save.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import type {FormatRequest} from '../shared/contracts.js'
import {DownloadManager} from './manager.js'
import {FakeEngine, fakeMedia} from './fake-engine.js'
import {LibraryIndex} from './library/index.js'

const video: FormatRequest = {type: 'video', codec: 'auto', container: 'mp4'}
const audio: FormatRequest = {type: 'audio', target: 'mp3', quality: 'best'}

async function setup() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-after-'))
  const library = new LibraryIndex({
    file: path.join(dir, 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => undefined, saveDelay: 10, pushDelay: 5, watch: false, analyse: false,
  })
  await library.init()
  const engine = new FakeEngine()
  const manager = new DownloadManager({
    store: path.join(dir, 'queue.json'), defaultOutDir: path.join(dir, 'downloads'),
    libraryFolders: {music: path.join(dir, 'music'), videos: path.join(dir, 'videos')}, engine, library, snapshotDelay: 5, progressInterval: 20,
  })
  await manager.init()
  return {dir, library, engine, manager, cleanup: async () => { await manager.close(); await library.close(); await fs.rm(dir, {recursive: true, force: true}) }}
}
async function waitFor(check: () => boolean, label: string) {
  for (let i = 0; i < 300; i++) {
    if (check()) return
    await delay(10)
  }
  throw new Error(`Timed out waiting for ${label}`)
}

test('a library save that fails shows on the job as not saved; Save again adds it and clears the message', async () => {
  const f = await setup()
  try {
    const add = f.library.addFile.bind(f.library)
    f.library.addFile = async () => {
      throw new Error('The library is read-only.')
    }
    const url = 'https://vimeo.com/201'
    await f.manager.inspect(url)
    await f.manager.enqueue(url, video, undefined, 'videos')
    await waitFor(() => f.manager.snapshot().jobs[0]?.status === 'completed', 'completion')
    const failed = f.manager.snapshot().jobs[0]
    assert.equal(failed.saveError, 'The library is read-only.')
    assert.equal(f.library.snapshot().items.length, 0, 'nothing was added')
    assert.ok(failed.file, 'the downloaded file is still there')

    f.library.addFile = add
    await f.manager.saveAgain(failed.id)
    assert.equal(f.manager.snapshot().jobs[0].saveError, undefined)
    assert.equal(f.library.snapshot().items.length, 1)
    await assert.rejects(f.manager.saveAgain(failed.id), /could not be saved/, 'nothing left to save again')
  } finally {
    await f.cleanup()
  }
})

test('lyrics are looked up before the song is saved, while the job is still being finished', async () => {
  const f = await setup()
  try {
    const seen: {status: string; inLibrary: number}[] = []
    f.engine.addLyrics = async () => {
      seen.push({status: f.manager.snapshot().jobs[0].status, inLibrary: f.library.snapshot().items.length})
      return true
    }
    const url = 'https://example.com/song'
    f.engine.inspectResult = (u) => ({type: 'media', media: fakeMedia(u, {title: 'Midnight City', uploader: 'M83 - Topic', duration: 244})})
    await f.manager.inspect(url)
    await f.manager.enqueue(url, audio, undefined, 'music')
    await waitFor(() => f.manager.snapshot().jobs[0]?.status === 'completed', 'completion')
    assert.deepEqual(seen, [{status: 'processing', inLibrary: 0}])
    await f.library.settled()
    assert.equal(f.library.snapshot().items.length, 1, 'and the song is saved afterwards')
  } finally {
    await f.cleanup()
  }
})
