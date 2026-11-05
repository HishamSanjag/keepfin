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

const audio: FormatRequest = {type: 'audio', target: 'mp3', quality: 'best'}
const video: FormatRequest = {type: 'video', codec: 'auto', container: 'mp4'}

async function setup() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-manager-lib-'))
  const engine = new FakeEngine()
  const library = new LibraryIndex({
    file: path.join(dir, 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => undefined, saveDelay: 10, pushDelay: 5, watch: false, analyse: false,
  })
  await library.init()
  const manager = new DownloadManager({
    store: path.join(dir, 'queue.json'),
    defaultOutDir: path.join(dir, 'downloads'),
    libraryFolders: {music: path.join(dir, 'music'), videos: path.join(dir, 'videos')},
    engine,
    library,
    snapshotDelay: 5,
    progressInterval: 20,
  })
  await manager.init()
  return {dir, engine, manager, library, cleanup: async () => {
    await manager.close()
    await library.close()
    await fs.rm(dir, {recursive: true, force: true})
  }}
}
async function waitFor(check: () => boolean, label: string) {
  for (let i = 0; i < 300; i++) {
    if (check()) return
    await delay(10)
  }
  throw new Error(`Timed out waiting for ${label}`)
}
const job = (m: DownloadManager, at = 0) => m.snapshot().jobs[at]

test('jobs save into the routed space with its layout and naming', async () => {
  const f = await setup()
  try {
    f.engine.inspectResult = (url) => ({type: 'media', media: fakeMedia(url, {title: 'Song', music: {artist: 'Artist', track: 'Song', album: 'Album'}, duration: 200})})
    const musicUrl = 'https://music.youtube.com/watch?v=abc123DEF45'
    await f.manager.inspect(musicUrl)
    await f.manager.enqueue(musicUrl, audio)
    await waitFor(() => job(f.manager)?.status === 'completed', 'completion')
    const done = job(f.manager)
    assert.equal(done.space, 'music')
    assert.equal(path.dirname(done.file!), path.join(f.dir, 'music'))
    // The library adopts the file just after the job reports completed.
    await waitFor(() => f.library.snapshot().items.length > 0, 'library import')
    await f.library.settled()
    assert.equal(f.library.snapshot().items[0].sourceId, 'youtube:abc123DEF45')

    const other = 'https://vimeo.com/123'
    await f.manager.inspect(other)
    await f.manager.enqueue(other, video, undefined, 'videos')
    await waitFor(() => job(f.manager, 1)?.status === 'completed', 'second completion')
    assert.equal(path.dirname(job(f.manager, 1).file!), path.join(f.dir, 'videos'))
    await assert.rejects(f.manager.enqueue(other, video, undefined, 'nope'), /spaces/)
  } finally {
    await f.cleanup()
  }
})

test('library off keeps outDir, organization and file names exactly', async () => {
  const f = await setup()
  try {
    await f.manager.updateSettings({library: {enabled: false}})
    const url = 'https://vimeo.com/55'
    await f.manager.inspect(url)
    await f.manager.enqueue(url, video, undefined, 'music')
    await waitFor(() => job(f.manager)?.status === 'completed', 'completion')
    assert.equal(job(f.manager).space, undefined)
    assert.equal(path.dirname(job(f.manager).file!), path.join(f.dir, 'downloads', 'Vimeo'))
    assert.equal(f.library.snapshot().items.length, 0)
  } finally {
    await f.cleanup()
  }
})

test('a recording already in the space completes without downloading; download anyway re-runs it', async () => {
  const f = await setup()
  try {
    f.engine.inspectResult = (url) => ({type: 'media', media: fakeMedia(url, {title: 'Song', music: {artist: 'Artist', track: 'Song'}, duration: 200})})
    const first = 'https://music.youtube.com/watch?v=abc123DEF45'
    await f.manager.inspect(first)
    await f.manager.enqueue(first, audio)
    await waitFor(() => job(f.manager)?.status === 'completed', 'first download')
    assert.equal(f.engine.downloads.length, 1)

    // same video id → source tier
    await f.manager.enqueue(first, audio)
    await waitFor(() => job(f.manager, 1)?.status === 'completed', 'existing completion')
    const skipped = job(f.manager, 1)
    assert.equal(skipped.existing?.by, 'source')
    assert.equal(skipped.file, job(f.manager).file)
    assert.equal(f.engine.downloads.length, 1, 'no transfer started')

    // different upload, same artist/title/duration → metadata tier
    const second = 'https://music.youtube.com/watch?v=zzz123DEF45'
    await f.manager.inspect(second)
    await f.manager.enqueue(second, audio)
    await waitFor(() => job(f.manager, 2)?.status === 'completed', 'metadata match')
    assert.equal(job(f.manager, 2).existing?.by, 'metadata')

    // deleting a skipped job's file must not delete the original
    await f.manager.deleteFile(job(f.manager, 2).id)
    assert.ok(await fs.stat(job(f.manager).file!))

    await f.manager.downloadAnyway(job(f.manager, 1).id)
    await waitFor(() => job(f.manager, 1)?.status === 'completed' && !job(f.manager, 1).existing, 'forced download')
    assert.equal(f.engine.downloads.length, 2)
    assert.equal(job(f.manager, 1).force, true)
    await assert.rejects(f.manager.downloadAnyway(job(f.manager, 0).id), /already in your library/)

    const matches = f.manager.libraryMatch([first, 'https://music.youtube.com/watch?v=nope1234567'], 'music')
    assert.equal(Object.keys(matches).length, 1)
    assert.equal(matches[first].by, 'source')
  } finally {
    await f.cleanup()
  }
})

test('spaces without skipExisting never skip', async () => {
  const f = await setup()
  try {
    f.engine.inspectResult = (url) => ({type: 'media', media: fakeMedia(url, {title: 'Clip'})})
    const url = 'https://www.youtube.com/watch?v=abc123DEF45'
    await f.manager.inspect(url)
    await f.manager.enqueue(url, video)
    await waitFor(() => job(f.manager)?.status === 'completed', 'first')
    await f.manager.enqueue(url, video)
    await waitFor(() => job(f.manager, 1)?.status === 'completed', 'second')
    assert.equal(job(f.manager, 1).existing, undefined)
    assert.equal(f.engine.downloads.length, 2)
  } finally {
    await f.cleanup()
  }
})

test('completed members of an unfinished kept-together group reach the library as each one finishes', async () => {
  const f = await setup()
  try {
    f.engine.discoveryTotal = 4
    f.manager.startDiscovery('https://example.com/playlist?list=PL123', 'g1')
    await waitFor(() => f.manager.discoveryState('g1').state === 'completed', 'discovery')
    const urls = f.manager.discoveryState('g1').entries.map((entry) => entry.url)
    const finish = f.engine.script
    f.engine.script = async (task, ctx, events) => {
      if (task.url.endsWith('/item-2')) throw new Error('ERROR: [youtube] abc: Private video. Sign in if you have been granted access')
      if (task.url.endsWith('/item-4'))
        return new Promise((_, reject) => {
          ctx.signal?.addEventListener('abort', () => reject(ctx.signal!.reason ?? new Error('aborted')))
        })
      return finish(task, ctx, events)
    }
    await f.manager.updateSettings({concurrency: 3})
    await f.manager.enqueueDiscovered('g1', urls, '720')
    await waitFor(() => {
      const jobs = f.manager.snapshot().jobs
      return jobs.filter((j) => j.status === 'completed').length === 2 && jobs.some((j) => j.status === 'failed') && jobs.some((j) => j.status === 'preparing')
    }, 'partial group')
    // Each finished member is imported after its job completes; wait for both imports instead of racing them.
    await waitFor(() => f.library.snapshot().items.length >= 2, 'library imports')
    await f.library.settled()
    const jobs = f.manager.snapshot().jobs
    assert.ok(jobs.every((j) => j.group?.id === jobs[0].group!.id))
    const saved = jobs.filter((j) => j.status === 'completed')
    const items = f.library.snapshot().items
    assert.equal(items.length, 2, 'only saved members are listed, while the group is still unfinished')
    for (const j of saved) assert.ok(items.some((item) => item.jobId === j.id), `member ${j.id} is in the library`)
  } finally {
    await f.cleanup()
  }
})

// ---------------------------------------------------------------------------
// Collections from downloads, tags, smart spaces, following
// ---------------------------------------------------------------------------

async function discovered(f: Awaited<ReturnType<typeof setup>>, url: string, session: string, total = 30) {
  f.engine.discoveryTotal = total
  f.manager.startDiscovery(url, session)
  await waitFor(() => f.manager.discoveryState(session).state === 'completed', 'discovery')
  return f.manager.discoveryState(session)
}

test('a downloaded collection is created once per source link, with every discovered id seen', async () => {
  const f = await setup()
  try {
    const url = 'https://www.youtube.com/playlist?list=PLgym&si=tracking'
    const discovery = await discovered(f, url, 's1', 10)
    const pick = discovery.entries.slice(2, 5).map((entry) => entry.url)
    await f.manager.enqueueDiscovered('s1', pick, 'audio-mp3', undefined, true, 'music')
    const [collection] = f.library.snapshot().collections
    assert.deepEqual([collection.kind, collection.space, collection.name, collection.source!.url], ['playlist', 'music', 'Mix', 'https://www.youtube.com/playlist?list=PLgym'])
    assert.equal(collection.seen.length, 10, 'deselected entries count as seen too')
    assert.deepEqual(collection.follow!.mode, 'off', 'following is opt-in')
    const jobs = f.manager.snapshot().jobs
    assert.ok(jobs.every((entry) => entry.collectionId === collection.id))
    assert.deepEqual(jobs.map((entry) => entry.collectionIndex), [2, 3, 4], 'jobs carry their place in the source (0-based)')
    await waitFor(() => f.manager.snapshot().jobs.every((entry) => entry.status === 'completed'), 'completion')
    await f.library.settled()
    const members = f.library.collection(collection.id).itemIds.map((itemId) => f.library.item(itemId).title)
    assert.deepEqual(members, ['Item 3', 'Item 4', 'Item 5'])
    // the same link again reuses the collection; later selections from the session add to the same one
    await f.manager.enqueueDiscovered('s1', [discovery.entries[0].url], 'audio-mp3', undefined, true, 'music')
    const again = await discovered(f, 'https://music.youtube.com/playlist?list=PLgym', 's2', 10)
    await f.manager.enqueueDiscovered('s2', [again.entries[9].url], 'audio-mp3', undefined, true, 'music')
    assert.equal(f.library.snapshot().collections.length, 1)
    assert.equal(f.manager.snapshot().jobs.at(-1)!.collectionIndex, 9)
    // "Keep in sync" is structured, validated, and turns following on for the collection
    await assert.rejects(f.manager.enqueueDiscovered('s2', [again.entries[8].url], 'audio-mp3', undefined, true, 'music', {intervalHours: 5}), /how often/)
    await assert.rejects(f.manager.enqueueDiscovered('s2', [again.entries[8].url], 'audio-mp3', undefined, true, 'music', '12h'), /how often/)
    await f.manager.enqueueDiscovered('s2', [again.entries[8].url], 'audio-mp3', undefined, true, 'music', {intervalHours: 12})
    assert.deepEqual([f.library.collection(collection.id).follow!.mode, f.library.collection(collection.id).follow!.intervalHours], ['auto', 12])
    // history never exposes the engine-only hints
    assert.ok(f.manager.snapshot().jobs.every((entry) => !('tags' in entry) && !('followed' in entry)))
  } finally {
    await f.cleanup()
  }
})

test('smart spaces are never a place to save to', async () => {
  const f = await setup()
  try {
    await f.manager.saveSpace({id: 'lofi', name: 'Lo-fi', type: 'smart', rules: {match: 'any', rules: [{field: 'tag', values: ['lofi']}]}})
    assert.equal(f.manager.getSettings().library.spaces.find((space) => space.id === 'lofi')!.type, 'smart')
    const url = 'https://vimeo.com/321'
    await f.manager.inspect(url)
    await assert.rejects(f.manager.enqueue(url, video, undefined, 'lofi'), /Choose one of your spaces/)
    const discovery = await discovered(f, 'https://www.youtube.com/playlist?list=PLx', 's1', 4)
    await assert.rejects(f.manager.enqueueDiscovered('s1', [discovery.entries[0].url], 'best', undefined, true, 'lofi'), /Choose one of your spaces/)
    assert.deepEqual(f.library.snapshot().collections, [])
    await assert.rejects(f.manager.updateSettings({library: {routes: {other: 'lofi'}}}), /one of your spaces/)
    // reorder covers every space, built-ins included
    const ids = f.manager.getSettings().library.spaces.map((space) => space.id)
    await f.manager.reorderSpaces([...ids].reverse())
    assert.deepEqual(f.manager.getSettings().library.spaces.map((space) => space.id), [...ids].reverse())
    await assert.rejects(f.manager.reorderSpaces(['nope']), /out of date/)
  } finally {
    await f.cleanup()
  }
})

test('tags come from the inspected genre, tags and categories and stay on the saved item', async () => {
  const f = await setup()
  try {
    const url = 'https://www.youtube.com/watch?v=abc123DEF45'
    f.engine.inspectResult = (link) => ({type: 'media', media: fakeMedia(link, {title: 'Song', tags: ['lo-fi', 'chill']})})
    await f.manager.inspect(url)
    await f.manager.enqueue(url, audio, undefined, 'music')
    await waitFor(() => job(f.manager)?.status === 'completed', 'completion')
    await f.library.settled()
    assert.deepEqual(f.library.snapshot().items[0].tags, ['lo-fi', 'chill'])
    assert.ok(!('tags' in job(f.manager)))
  } finally {
    await f.cleanup()
  }
})

test('follow listings page up to the cap and say when they are partial', async () => {
  const f = await setup()
  try {
    f.engine.discoveryTotal = 1500
    const listing = await f.manager.followDiscover('https://www.youtube.com/playlist?list=PLbig', 1000)
    assert.equal(listing.entries.length, 1000)
    assert.equal(listing.truncated, true)
    assert.equal(listing.partial, true, 'cut at the cap, so nothing can be concluded about removals')
    assert.deepEqual(f.engine.discoveryPages.map((page) => [page.start, page.end]), [[1, 200], [201, 400], [401, 600], [601, 800], [801, 1000]])
    f.engine.discoveryTotal = 150
    const small = await f.manager.followDiscover('https://www.youtube.com/playlist?list=PLsmall', 1000)
    assert.deepEqual([small.entries.length, small.truncated, small.partial], [150, false, false])
    const before = f.manager.metadataEpoch
    await f.manager.clearCookies()
    assert.equal(f.manager.metadataEpoch, before + 1, 'a cookie change moves the fence')
  } finally {
    await f.cleanup()
  }
})

test('followed items reuse the last download format, continue the source order and are marked new', async () => {
  const f = await setup()
  try {
    const url = 'https://music.youtube.com/playlist?list=PLgym'
    const discovery = await discovered(f, url, 's1', 5)
    await f.manager.enqueueDiscovered('s1', discovery.entries.slice(0, 3).map((entry) => entry.url), 'audio-opus', undefined, true, 'music')
    await waitFor(() => f.manager.snapshot().jobs.every((entry) => entry.status === 'completed'), 'first batch')
    await f.library.settled()
    const [collection] = f.library.snapshot().collections
    const fresh = discovery.entries.slice(3)
    assert.equal(await f.manager.followEnqueue(collection.id, fresh), 2)
    await waitFor(() => f.manager.snapshot().jobs.every((entry) => entry.status === 'completed'), 'followed batch')
    await f.library.settled()
    // Followed items download in parallel, so the last import can land just after the batch reports done.
    await waitFor(() => f.library.collection(collection.id).itemIds.length === 5, 'followed items in the collection')
    const followed = f.manager.snapshot().jobs.slice(3)
    assert.deepEqual(followed.map((entry) => entry.collectionIndex), [3, 4])
    assert.deepEqual(followed.map((entry) => entry.format), [{type: 'audio', target: 'opus', quality: 'best'}, {type: 'audio', target: 'opus', quality: 'best'}])
    assert.ok(followed.every((entry) => entry.space === 'music' && entry.group?.title === collection.name))
    const after = f.library.collection(collection.id)
    assert.deepEqual(after.itemIds.map((itemId) => f.library.item(itemId).title), ['Item 1', 'Item 2', 'Item 3', 'Item 4', 'Item 5'])
    assert.equal(after.newItemIds!.length, 2, 'only what the check added is new')
    // a collection with no earlier job uses the space default: audio for music spaces, video otherwise
    const empty = f.library.ensureCollection({url: 'https://www.youtube.com/playlist?list=PLempty', name: 'Empty', kind: 'playlist', space: 'music'})
    const other = f.library.ensureCollection({url: 'https://www.youtube.com/playlist?list=PLvideos', name: 'Clips', kind: 'playlist', space: 'videos'})
    await f.manager.followEnqueue(empty.id, [fresh[0]])
    await f.manager.followEnqueue(other.id, [fresh[1]])
    const last = f.manager.snapshot().jobs.slice(-2)
    assert.equal(last[0].format.type, 'audio')
    assert.equal(last[1].format.type, 'video')
    // a deleted space is reported, not guessed
    f.library.updateCollection(other.id, (current) => (current.space = 'gone'))
    await assert.rejects(f.manager.followEnqueue(other.id, [fresh[0]]), /no longer exists/)
  } finally {
    await f.cleanup()
  }
})

test('Undo of removing a space: the same space comes back at its place, with the route that pointed to it', async () => {
  const f = await setup()
  try {
    const space = {...f.manager.getSettings().library.spaces.find((item) => item.id === 'videos')!, id: 'trips', name: 'Trips', folder: path.join(f.dir, 'trips')}
    await f.manager.saveSpace(space)
    await f.manager.updateSettings({library: {routes: {music: 'trips', other: 'downloads'}}})
    const before = f.manager.getSettings().library
    const position = before.spaces.findIndex((item) => item.id === 'trips')
    await f.manager.deleteSpace('trips')
    assert.equal(f.manager.getSettings().library.routes.music, 'music', 'the route fell back')
    await f.manager.restoreSpace(before.spaces[position], position, before.routes)
    const after = f.manager.getSettings().library
    assert.deepEqual(after.spaces.map((item) => item.id), before.spaces.map((item) => item.id), 'same place in the order')
    assert.equal(after.spaces[position].name, 'Trips')
    assert.equal(after.routes.music, 'trips')
    await assert.rejects(f.manager.restoreSpace(before.spaces[position], position, before.routes), /already back/)
    // a route the person changed meanwhile stays
    await f.manager.deleteSpace('trips')
    await f.manager.updateSettings({library: {routes: {music: 'videos', other: 'downloads'}}})
    await f.manager.restoreSpace(before.spaces[position], position, before.routes)
    assert.equal(f.manager.getSettings().library.routes.music, 'videos')
  } finally {
    await f.cleanup()
  }
})

test('the kept spaces (Downloads, Music, Videos, Favorites) can be renamed but never removed', async () => {
  const f = await setup()
  try {
    assert.ok(f.manager.getSettings().library.spaces.some((item) => item.id === 'favorites'), 'a new library has Favorites')
    for (const id of ['downloads', 'music', 'videos', 'favorites'])
      await assert.rejects(f.manager.deleteSpace(id), /Downloads, Music, Videos and Favorites can be renamed but not removed/)
    // a rename is fine, and a list without Favorites is refused
    const favorites = f.manager.getSettings().library.spaces.find((item) => item.id === 'favorites')!
    await f.manager.saveSpace({...favorites, name: 'Hearts'})
    assert.equal(f.manager.getSettings().library.spaces.find((item) => item.id === 'favorites')?.name, 'Hearts')
    await assert.rejects(f.manager.updateSettings({library: {spaces: f.manager.getSettings().library.spaces.filter((item) => item.id !== 'favorites')}}), /can be renamed but not removed/)
  } finally {
    await f.cleanup()
  }
})
test('Undo of Remove from history: the entries come back as they were, each where it stood, and file links follow a delete', async () => {
  const f = await setup()
  try {
    for (const n of [1, 2, 3, 4]) {
      const url = 'https://vimeo.com/' + n
      await f.manager.inspect(url)
      await f.manager.enqueue(url, video, undefined, 'videos')
      await waitFor(() => f.manager.snapshot().jobs.length === n && job(f.manager, n - 1)?.status === 'completed', 'completion ' + n)
    }
    const order = f.manager.snapshot().jobs.map((item) => item.id)
    const saved = f.manager.storedJobs()
    await f.manager.remove(order[1])
    await f.manager.remove(order[2])
    // a download added since stays
    const url = 'https://vimeo.com/5'
    await f.manager.inspect(url)
    await f.manager.enqueue(url, video, undefined, 'videos')
    await waitFor(() => f.manager.snapshot().jobs.length === 3 && job(f.manager, 2)?.status === 'completed', 'a later download')
    const later = job(f.manager, 2).id
    const count = await f.manager.restoreJobs(saved.filter((item) => [order[1], order[2]].includes(item.id)), order)
    assert.equal(count, 2)
    assert.deepEqual(f.manager.snapshot().jobs.map((item) => item.id), [order[0], order[1], order[2], order[3], later])
    assert.equal(await f.manager.restoreJobs(saved, order), 0, 'nothing is doubled')
    // file links: history forgets a deleted file and Undo points it at the file again
    const file = job(f.manager, 0).file!
    const refs = f.manager.fileRefs([file])
    assert.equal(refs.length, 1)
    f.manager.forgetFiles([file])
    assert.equal(job(f.manager, 0).file, undefined)
    f.manager.restoreFileRefs(refs)
    assert.equal(job(f.manager, 0).file, file)
  } finally {
    await f.cleanup()
  }
})