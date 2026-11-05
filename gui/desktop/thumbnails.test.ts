import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import {spawnSync} from 'node:child_process'
import ffmpeg from 'ffmpeg-static'
import {ThumbnailStore, type ThumbnailImage} from './thumbnails.js'
import {thumbnailFrame} from './thumbnail-frame.js'
import {boundedThumbnail, thumbnailDimensions} from './thumbnail-image.js'

const image: ThumbnailImage = {
  mime: 'image/png',
  bytes: Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jKqkAAAAASUVORK5CYII=',
    'base64',
  ),
}
const response = () =>
  new Response(Uint8Array.from(image.bytes), {
    headers: {'content-type': image.mime},
  })
async function directory(t: test.TestContext) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-artwork-test-'))
  t.after(() => fs.rm(dir, {recursive: true, force: true}))
  return dir
}

test('inspection artwork becomes durable offline library artwork across restart and temporary eviction', async (t) => {
  const dir = await directory(t)
  let calls = 0
  const fetcher: typeof fetch = async () => {
    calls++
    return response()
  }
  const store = new ThumbnailStore(dir, {fetch: fetcher})
  const url = 'https://provider.example/expiring-artwork.jpg'
  const preview = await store.get({url})
  await store.retain(['saved-video'])
  assert.equal(await store.get({url}, 'saved-video'), preview)
  assert.equal(calls, 1, 'Promotion must reuse the inspected artwork')
  const offline = new ThumbnailStore(dir, {
    fetch: async () => {
      throw new Error('offline')
    },
    temporaryLimitBytes: 220,
    temporaryTargetBytes: 100,
  })
  await offline.retain(['saved-video'])
  assert.equal(await offline.get({url}, 'saved-video'), preview)
  const pressure = new ThumbnailStore(dir, {
    fetch: fetcher,
    temporaryLimitBytes: 220,
    temporaryTargetBytes: 100,
  })
  for (let i = 0; i < 5; i++)
    await pressure.get({url: `https://provider.example/${i}.png`})
  const files = await fs.readdir(dir)
  assert.ok(files.some((file) => file.startsWith('job-')))
  const temporary = await Promise.all(
    files
      .filter((file) => file.startsWith('url-'))
      .map((file) => fs.stat(path.join(dir, file))),
  )
  assert.ok(temporary.reduce((total, item) => total + item.size, 0) <= 220)
  assert.equal(
    await offline.get({url}, 'saved-video'),
    preview,
    'Temporary pressure must never evict history artwork',
  )
})

test('removing history and retaining live jobs deletes only their artwork, including in-flight removal', async (t) => {
  const dir = await directory(t)
  const mediaFile = path.join(dir, 'download.mp4')
  await fs.writeFile(mediaFile, 'downloaded media')
  const store = new ThumbnailStore(dir, {frame: async () => image})
  await store.retain(['keep', 'remove'])
  await store.get({file: mediaFile}, 'keep')
  await store.get({file: mediaFile}, 'remove')
  await store.retain(['keep'])
  assert.equal(
    (await fs.readdir(dir)).filter((file) => file.startsWith('job-')).length,
    1,
  )
  assert.ok(await store.get({file: mediaFile}, 'keep'))
  assert.equal(await store.get({file: mediaFile}, 'remove'), undefined)
  assert.equal(await fs.readFile(mediaFile, 'utf8'), 'downloaded media')
  let release!: (image: ThumbnailImage) => void
  const slow = new ThumbnailStore(dir, {
    frame: () =>
      new Promise((resolve) => {
        release = resolve
      }),
  })
  await slow.retain(['racing'])
  const request = slow.get({file: mediaFile}, 'racing')
  while (!release) await new Promise((resolve) => setTimeout(resolve, 1))
  await slow.remove('racing')
  release(image)
  assert.equal(await request, undefined)
  assert.equal(
    (await fs.readdir(dir)).filter((file) => file.endsWith('.tmp')).length,
    0,
  )
  assert.equal(
    (await fs.readdir(dir)).filter((file) => file.startsWith('job-')).length,
    0,
  )
})

test('coalesced artwork requests retain a strict two-worker limit through repeated slot handoffs', async (t) => {
  const dir = await directory(t)
  let inFlight = 0,
    peak = 0,
    calls = 0
  const store = new ThumbnailStore(dir, {
    frame: async () => {
      calls++
      inFlight++
      peak = Math.max(peak, inFlight)
      await new Promise((resolve) => setTimeout(resolve, 15))
      inFlight--
      return image
    },
  })
  const requests: Promise<string | undefined>[] = []
  for (let i = 0; i < 12; i++) {
    requests.push(store.get({file: '/validated/media.mp4'}, `job-${i}`))
    requests.push(store.get({file: '/validated/media.mp4'}, `job-${i}`))
  }
  const results = await Promise.all(requests)
  assert.equal(calls, 12, 'Duplicate requests should join the same job load')
  assert.equal(
    peak,
    2,
    'A released worker slot must be reserved for its queued waiter',
  )
  assert.ok(results.every((result) => result === results[0]))
})

test('invalid schemes, credentials, image types and oversized streaming bodies never persist', async (t) => {
  const dir = await directory(t)
  let calls = 0,
    cancelled = false
  const store = new ThumbnailStore(dir, {
    fetch: async () => {
      calls++
      return new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(new Uint8Array(1_000_001))
            controller.enqueue(new Uint8Array(1_000_001))
          },
          cancel() {
            cancelled = true
          },
        }),
        {headers: {'content-type': 'image/jpeg'}},
      )
    },
  })
  for (const url of [
    'file:///private/file.jpg',
    'data:image/png;base64,aA==',
    'https://user:password@example.com/image.jpg',
  ])
    assert.equal(await store.get({url}), undefined)
  assert.equal(calls, 0)
  assert.equal(
    await store.get({url: 'https://provider.example/large.jpg'}),
    undefined,
  )
  assert.ok(cancelled, 'Oversized response must stop reading the remote body')
  assert.equal((await fs.readdir(dir).catch(() => [])).length, 0)
  const invalidType = new ThumbnailStore(dir, {
    fetch: async () =>
      new Response('<svg/>', {headers: {'content-type': 'image/svg+xml'}}),
  })
  assert.equal(
    await invalidType.get({url: 'https://provider.example/image.svg'}),
    undefined,
  )
  const undecodable = new ThumbnailStore(dir, {
    fetch: async () => response(),
    resize: () => undefined,
  })
  assert.equal(
    await undecodable.get({url: 'https://provider.example/broken.png'}),
    undefined,
  )
})

test('local video fallback extracts a bounded real JPEG frame without a provider connection', async () => {
  const result = await thumbnailFrame(
    path.resolve('android/app/src/androidTest/assets/fixture.mp4'),
  )
  assert.ok(result, 'Bundled ffmpeg should extract a real local video preview')
  assert.equal(result.mime, 'image/jpeg')
  assert.equal(result.bytes.readUInt16BE(0), 0xffd8)
  assert.ok(result.bytes.length <= 1_000_000)
  const dimensions = thumbnailDimensions(result.bytes)!
  assert.ok(dimensions.width <= 480 && dimensions.height <= 270)
  assert.equal(
    await thumbnailFrame('https://provider.example/video.mp4'),
    undefined,
  )
  assert.equal(await thumbnailFrame(path.resolve('package.json')), undefined)
})

test('a saved video gives the cover inside its file, not a frame of the video', async () => {
  // green frames throughout, a red cover embedded: a YouTube video's own picture versus a scene from it
  const result = await thumbnailFrame(
    path.resolve('android/app/src/androidTest/assets/green-video-red-cover.mp4'),
  )
  assert.ok(result)
  const pixel = spawnSync(
    ffmpeg as unknown as string,
    ['-hide_banner', '-loglevel', 'error', '-i', 'pipe:0', '-vf', 'scale=1:1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', 'pipe:1'],
    {input: result.bytes},
  ).stdout
  assert.ok(pixel[0] > 180 && pixel[1] < 100, `red cover expected, got rgb(${[...pixel].join(', ')})`)
})

test('compressed image dimensions are bounded before native bitmap allocation', () => {
  assert.deepEqual(thumbnailDimensions(image.bytes), {width: 1, height: 1})
  assert.ok(boundedThumbnail(image.bytes))
  const oversized = Buffer.from(image.bytes)
  oversized.writeUInt32BE(100_000, 16)
  assert.equal(boundedThumbnail(oversized), false)
  assert.equal(boundedThumbnail(Buffer.from('not an image')), false)
  const webp = Buffer.alloc(30)
  webp.write('RIFF', 0)
  webp.write('WEBP', 8)
  webp.write('VP8X', 12)
  webp.writeUIntLE(479, 24, 3)
  webp.writeUIntLE(269, 27, 3)
  assert.deepEqual(thumbnailDimensions(webp), {width: 480, height: 270})
})

test('a library request joins an in-flight inspected thumbnail before promoting it', async (t) => {
  const dir = await directory(t)
  let calls = 0
  const store = new ThumbnailStore(dir, {
    fetch: async () => {
      calls++
      await new Promise((resolve) => setTimeout(resolve, 20))
      return response()
    },
  })
  const source = {url: 'https://provider.example/shared-artwork.png'}
  const [inspection, library] = await Promise.all([
    store.get(source),
    store.get(source, 'newly-enqueued'),
  ])
  assert.ok(inspection)
  assert.equal(library, inspection)
  assert.equal(
    calls,
    1,
    'The same source should not be downloaded twice during enqueue',
  )
})

test('an unavailable cache still returns usable artwork and leaves unrelated files untouched', async (t) => {
  const dir = await directory(t)
  const blockedCache = path.join(dir, 'cache')
  await fs.writeFile(blockedCache, 'existing unrelated file')
  const store = new ThumbnailStore(blockedCache, {frame: async () => image})
  assert.match(
    (await store.get({file: '/validated/video.mp4'}, 'job'))!,
    /^data:image\/png;base64,/,
  )
  assert.equal(
    await fs.readFile(blockedCache, 'utf8'),
    'existing unrelated file',
  )
})

test('removed history keeps its artwork for the Undo window, and lets it go after it or keeps it when Undo is pressed', async (t) => {
  const dir = await directory(t)
  const mediaFile = path.join(dir, 'download.mp4')
  await fs.writeFile(mediaFile, 'downloaded media')
  const store = new ThumbnailStore(dir, {frame: async () => image})
  const jobFiles = async () => (await fs.readdir(dir)).filter((file) => file.startsWith('job-')).length
  await store.retain(['undone', 'expired'])
  await store.get({file: mediaFile}, 'undone')
  await store.get({file: mediaFile}, 'expired')
  assert.equal(await jobFiles(), 2)
  // both leave the history, but their artwork waits
  store.defer(['undone', 'expired'], 60)
  await store.retain([])
  assert.equal(await jobFiles(), 2, 'the next cleanup of removed history leaves them alone')
  // Undo of one: it stays for good, even through the cleanup that follows
  store.keep(['undone'])
  await new Promise((resolve) => setTimeout(resolve, 120))
  await store.retain(['undone'])
  assert.equal(await jobFiles(), 1, 'the one Undo did not bring back is gone')
  assert.ok(await store.get({file: mediaFile}, 'undone'))
  assert.equal(await fs.readFile(mediaFile, 'utf8'), 'downloaded media', 'media files are never touched')
})