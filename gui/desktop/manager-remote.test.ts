// Downloads another device asked for: the queue checks the link before any network work (inspection and download), takes the
// capture rules as for its own downloads, and asks the person first when the download is large or the connection is metered.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import type {FormatRequest} from '../shared/contracts.js'
import {StudioEngine, type EngineContext} from './engine/index.js'
import type {ToolManager} from './engine/tools.js'
import type {InfoCache} from './engine/ytdlp.js'
import {FakeEngine, fakeMedia} from './fake-engine.js'
import {DownloadManager, type RemoteOptions} from './manager.js'
import {LibraryIndex} from './library/index.js'

const video: FormatRequest = {type: 'video', codec: 'auto', container: 'mp4', maxHeight: 1080}

/** Like the real engine: the guard is asked before any network work for the link. */
class GuardedEngine extends FakeEngine {
  guarded: string[] = []
  async inspect(url: string, ctx: EngineContext) {
    await ctx.guard?.(url, ctx.signal)
    if (ctx.guard) this.guarded.push(`inspect ${url}`)
    return super.inspect(url, ctx)
  }
  async download(task: Parameters<FakeEngine['download']>[0], ctx: EngineContext, events: Parameters<FakeEngine['download']>[2]) {
    await ctx.guard?.(task.url, ctx.signal)
    if (ctx.guard) this.guarded.push(`download ${task.url}`)
    return super.download(task, ctx, events)
  }
}

async function setup(options: Partial<RemoteOptions> = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-remote-'))
  const engine = new GuardedEngine()
  const origins = new Set<string>()
  const remote: RemoteOptions = {
    guard: async (url) => {
      if (url.includes('private')) throw new Error('The link leads to an address on a private network, so it was not opened.')
    },
    consentBytes: 1_000_000,
    metered: async () => false,
    originOf: (id) => (origins.has(id) ? {device: 'Pixel'} : undefined),
    ...options,
  }
  const manager = new DownloadManager({store: path.join(dir, 'queue.json'), defaultOutDir: path.join(dir, 'downloads'), engine, snapshotDelay: 5, progressInterval: 20, retryDelays: [10, 10, 10], remote})
  await manager.init()
  const ask = async (id: string, url: string, format: unknown = video) => {
    origins.add(id)
    await manager.enqueueRemote({id, url, format})
  }
  const wait = async (check: () => boolean, label: string) => {
    for (let i = 0; i < 300; i++) {
      if (check()) return
      await delay(10)
    }
    throw new Error(`Timed out waiting for ${label}`)
  }
  return {dir, engine, manager, ask, wait, cleanup: async () => (await manager.close(), fs.rm(dir, {recursive: true, force: true}))}
}

test('a remote job is checked, inspected and downloaded, with the guard asked before both, and it is marked in the snapshot', async () => {
  const f = await setup()
  try {
    f.engine.inspectResult = (url) => ({type: 'media', media: fakeMedia(url, {title: 'From the phone'})})
    await f.ask('job-1', 'https://example.com/clip')
    await f.wait(() => f.manager.snapshot().jobs[0]?.status === 'completed', 'the download')
    const job = f.manager.snapshot().jobs[0]
    assert.equal(job.title, 'From the phone', 'the title comes from the inspected link, not the sender')
    assert.equal(job.remote?.device, 'Pixel')
    assert.deepEqual(f.engine.guarded, ['inspect https://example.com/clip', 'download https://example.com/clip'])
    assert.equal(f.manager.remoteView('job-1')?.state, 'completed')
    assert.equal(f.manager.isRemote('job-1'), true)
    // an own capture never passes through the guard
    f.engine.guarded.length = 0
    await f.manager.inspect('https://example.com/own')
    assert.deepEqual(f.engine.guarded, [])
  } finally {
    await f.cleanup()
  }
})

test('a link the guard refuses fails the job with the reason and nothing is downloaded', async () => {
  const f = await setup()
  try {
    await f.ask('job-2', 'https://example.com/private-thing')
    await f.wait(() => f.manager.snapshot().jobs[0]?.status === 'failed', 'the refusal')
    assert.match(f.manager.remoteView('job-2')?.error ?? '', /private network/)
    assert.equal(f.engine.downloads.length, 0)
    assert.equal(f.engine.inspectCalls.length, 0, 'not even inspected')
  } finally {
    await f.cleanup()
  }
})

test('the capture rules apply: a link that is not http(s), a format that is not valid; the same id is one job', async () => {
  const f = await setup()
  try {
    await assert.rejects(() => f.manager.enqueueRemote({id: 'a', url: 'file:///c:/x', format: video}), /HTTP|link/i)
    await assert.rejects(() => f.manager.enqueueRemote({id: 'b', url: 'https://example.com/a', format: {type: 'video', codec: 'nope'}}))
    assert.equal(f.manager.snapshot().jobs.length, 0)
    // the same id again is the same job
    await f.ask('same', 'https://example.com/a')
    await f.manager.enqueueRemote({id: 'same', url: 'https://example.com/a', format: video})
    assert.equal(f.manager.snapshot().jobs.length, 1)
  } finally {
    await f.cleanup()
  }
})

test('a large download waits for the person here, who can allow or decline it', async () => {
  const f = await setup({consentBytes: 1_000_000})
  try {
    const big = (url: string) => ({
      type: 'media' as const,
      media: fakeMedia(url, {title: 'Big', duration: 100, video: [{id: '1080p', height: 1080, hdr: false, codec: 'h264' as const, ext: 'mp4', formatId: '137', hasAudio: true, size: 5_000_000, sizeApprox: false}]}),
    })
    f.engine.inspectResult = big
    await f.ask('big-1', 'https://example.com/big1')
    await f.wait(() => f.manager.remoteView('big-1')?.state === 'consent', 'the wait for consent')
    const waiting = f.manager.snapshot().jobs[0]
    assert.equal(waiting.status, 'paused')
    assert.equal(waiting.remote?.consent?.reason, 'size')
    assert.ok((waiting.remote?.consent?.bytes ?? 0) >= 5_000_000)
    assert.equal(f.engine.downloads.length, 0, 'nothing downloads before the person allows it')
    await f.manager.allowRemote('big-1')
    await f.wait(() => f.manager.snapshot().jobs[0]?.status === 'completed', 'the download after allowing')
    assert.equal(f.manager.snapshot().jobs[0].remote?.consent, undefined)

    await f.ask('big-2', 'https://example.com/big2')
    await f.wait(() => f.manager.remoteView('big-2')?.state === 'consent', 'the second wait')
    await f.manager.declineRemote('big-2')
    const declined = f.manager.remoteView('big-2')
    assert.equal(declined?.state, 'cancelled')
    assert.match(declined?.error ?? '', /Declined/)
    assert.equal(f.engine.downloads.length, 1, 'only the allowed one downloaded')
    await assert.rejects(() => f.manager.allowRemote('nope'), /not found/i)
  } finally {
    await f.cleanup()
  }
})

test('on a metered connection every remote job waits for the person, whatever its size', async () => {
  let metered = true
  const f = await setup({metered: async () => metered})
  try {
    await f.ask('m-1', 'https://example.com/small')
    await f.wait(() => f.manager.remoteView('m-1')?.state === 'consent', 'consent on a metered connection')
    assert.equal(f.manager.snapshot().jobs[0].remote?.consent?.reason, 'metered')
    metered = false
    await f.manager.allowRemote('m-1')
    await f.wait(() => f.manager.snapshot().jobs[0]?.status === 'completed', 'the download')
    // unmetered again: a small one goes straight through
    await f.ask('m-2', 'https://example.com/small2')
    await f.wait(() => f.manager.snapshot().jobs[1]?.status === 'completed', 'a small one on an unmetered connection')
  } finally {
    await f.cleanup()
  }
})

test('the real engine asks the guard before any network work', async () => {
  const engine = new StudioEngine({} as ToolManager, {} as InfoCache)
  const guard = async () => {
    throw new Error('refused by the guard')
  }
  await assert.rejects(() => engine.inspect('https://example.com/a', {settings: {} as never, guard}), /refused by the guard/)
  await assert.rejects(() => engine.download({url: 'https://example.com/a', title: 'a', format: video, options: {}, folder: os.tmpdir(), stem: 'x'}, {settings: {} as never, guard}, {onStage: () => undefined, onProgress: () => undefined}), /refused by the guard/)
  await assert.rejects(() => engine.discover('https://example.com/a', {start: 1, end: 10}, {settings: {} as never, guard}, () => undefined, () => undefined), /refused by the guard/)
})

test('a received audio request with musicVideo true and the library on looks for the song video; every other case does not', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-remote-video-'))
  const library = new LibraryIndex({file: path.join(dir, 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => undefined, saveDelay: 10, pushDelay: 5, watch: false, analyse: false})
  await library.init()
  const remote: RemoteOptions = {guard: async () => undefined, consentBytes: 1_000_000, metered: async () => false, originOf: () => ({device: 'Pixel'})}
  const open = async (withLibrary: boolean, folder: string) => {
    const manager = new DownloadManager({
      store: path.join(dir, `${folder}.json`), defaultOutDir: path.join(dir, 'downloads'), libraryFolders: {music: path.join(dir, 'music'), videos: path.join(dir, 'videos')},
      engine: new FakeEngine(), ...(withLibrary ? {library} : {}), snapshotDelay: 5, progressInterval: 20, remote,
    })
    await manager.init()
    return manager
  }
  const audio: FormatRequest = {type: 'audio', target: 'mp3', quality: 'best'}
  const ask = async (manager: DownloadManager, id: string, format: FormatRequest, musicVideo?: boolean) => {
    await manager.enqueueRemote({id, url: `https://music.youtube.com/watch?v=${id}`, format, ...(musicVideo === undefined ? {} : {musicVideo})})
    return manager.snapshot().jobs.find((job) => job.id === id)?.findVideo
  }
  const on = await open(true, 'on')
  const off = await open(false, 'off')
  try {
    assert.equal(await ask(on, 'aaaaaaaaaaa', audio, true), true, 'audio, library on, video asked')
    assert.equal(await ask(on, 'bbbbbbbbbbb', audio), undefined, 'the field absent')
    assert.equal(await ask(on, 'ccccccccccc', audio, false), undefined, 'false')
    assert.equal(await ask(on, 'ddddddddddd', video, true), undefined, 'a video format has no song video')
    assert.equal(await ask(off, 'eeeeeeeeeee', audio, true), undefined, 'no library on this device')
  } finally {
    await on.close()
    await off.close()
    await library.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})
