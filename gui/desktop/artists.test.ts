import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {PlaylistEntry} from '../shared/contracts.js'
import type {ArtistReleaseHit} from '../shared/core/music.js'
import {fetchArtistReleases} from './engine/music.js'
import {ArtistFollowing, ARTIST_CHECK_EVERY_MS, artistHost, type ArtistHost, type ReleaseQueue} from './library/artists.js'

const HOUR = 3_600_000
const entry = (n: number): PlaylistEntry => ({url: `https://music.youtube.com/playlist?list=OLAK5uy_${n}`, title: `Release ${n}`, position: n})
const hit = (n: number, title = `Release ${n}`, kind = 'Album'): ArtistReleaseHit => ({link: `https://music.youtube.com/playlist?list=OLAK5uy_${n}`, title, kind})

class Host implements ArtistHost {
  channel: string | undefined = 'UC0076UMUgEng8HORUw_MYHA'
  searchFails = false
  listing: ArtistReleaseHit[] = [hit(1), hit(2)]
  listFails: string | undefined
  listed: string[] = []
  kept: string[] = []
  keepFails = false
  resolveChannel: ArtistHost['resolveChannel'] = async () => {
    if (this.searchFails) throw new Error('offline')
    return this.channel
  }
  async listReleases(channelId: string) {
    this.listed.push(channelId)
    if (this.listFails) throw new Error(this.listFails)
    return this.listing
  }
  async keep(url: string) {
    if (this.keepFails) throw new Error('queue full')
    this.kept.push(url)
    return 2
  }
}

async function fixture() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-artists-'))
  const file = path.join(dir, 'following.json')
  let time = 1_800_000_000_000
  const host = new Host()
  const make = () => new ArtistFollowing(file, host, () => {}, () => time)
  return {dir, file, host, make, advance: (ms: number) => (time += ms), clean: () => fs.rm(dir, {recursive: true, force: true})}
}

test('the first check records the catalogue and feeds nothing; later checks add only what is new, dated when found', async () => {
  const f = await fixture()
  try {
    const follows = f.make()
    await follows.follow('Pale Cassette')
    let state = await follows.check(undefined, true)
    assert.equal(state.artists[0].checkedOnce, true)
    assert.equal(state.artists[0].url, 'https://music.youtube.com/channel/UC0076UMUgEng8HORUw_MYHA')
    assert.ok(f.host.listed.length >= 1 && f.host.listed.every((id) => id === 'UC0076UMUgEng8HORUw_MYHA'))
    assert.equal(state.releases.length, 0)
    f.host.listing = [hit(3, 'Night Ferry', 'Single'), hit(1), hit(2)]
    f.advance(HOUR)
    state = await follows.check(undefined, true)
    assert.equal(state.releases.length, 1)
    assert.deepEqual([state.releases[0].kind, state.releases[0].title], ['Single', 'Night Ferry'])
    assert.equal(state.releases[0].url, 'https://music.youtube.com/playlist?list=OLAK5uy_3')
    // the same check again finds nothing twice
    state = await follows.check(undefined, true)
    assert.equal(state.releases.length, 1)
  } finally {
    await f.clean()
  }
})

test('one follow per artist whatever the spelling; unfollowing drops its releases; the state survives a restart', async () => {
  const f = await fixture()
  try {
    const follows = f.make()
    await follows.follow('Pale Cassette')
    await follows.follow('pale  cassette feat. Oren Vale')
    assert.equal((await follows.current()).artists.length, 1)
    await follows.check(undefined, true)
    f.host.listing = [hit(5)]
    await follows.check(undefined, true)
    assert.equal(f.make().snapshot().artists.length, 0, 'a new instance reads the file only after load')
    const again = f.make()
    const loaded = await again.current()
    assert.equal(loaded.artists.length, 1)
    assert.equal(loaded.releases.length, 1)
    const state = await again.unfollow(loaded.artists[0].id)
    assert.deepEqual([state.artists.length, state.releases.length], [0, 0])
    await assert.rejects(again.follow(''), /no artist/)
  } finally {
    await f.clean()
  }
})

test('only due artists are checked automatically; Check now ignores the schedule', async () => {
  const f = await fixture()
  try {
    const follows = f.make()
    await follows.follow('Pale Cassette')
    await follows.check(undefined, true)
    const at = (await follows.current()).artists[0].checkedAt!
    f.advance(HOUR)
    await follows.check(undefined, false)
    assert.equal((await follows.current()).artists[0].checkedAt, at, 'not due yet')
    f.advance(ARTIST_CHECK_EVERY_MS)
    await follows.check(undefined, false)
    assert.ok((await follows.current()).artists[0].checkedAt! > at)
  } finally {
    await f.clean()
  }
})

test('an artist that cannot be found or read records a plain message and is retried later; no guess is followed', async () => {
  const f = await fixture()
  try {
    const follows = f.make()
    await follows.follow('Nobody Known')
    f.host.channel = undefined
    let state = await follows.check(undefined, true)
    assert.match(state.artists[0].error ?? '', /could not find Nobody Known/)
    f.host.channel = 'UC2'
    f.host.searchFails = true
    state = await follows.check(undefined, true)
    assert.match(state.artists[0].error ?? '', /could not reach/)
    f.host.searchFails = false
    f.host.listFails = 'The download engine stopped: UC2: This channel does not have a releases tab (yt-dlp)'
    state = await follows.check(undefined, true)
    assert.equal(state.artists[0].error, "Keepfin couldn't check this artist right now. It will try again later.")
    f.host.listFails = undefined
    state = await follows.check(undefined, true)
    assert.equal(state.artists[0].error, undefined)
  } finally {
    await f.clean()
  }
})

test('keeping a release downloads it and marks it kept; a failed download leaves it in the feed', async () => {
  const f = await fixture()
  try {
    const follows = f.make()
    await follows.follow('Pale Cassette')
    await follows.check(undefined, true)
    f.host.listing = [hit(9), hit(1), hit(2)]
    const found = await follows.check(undefined, true)
    const id = found.releases[0].id
    f.host.keepFails = true
    await assert.rejects(follows.keep(id), /queue full/)
    assert.equal((await follows.current()).releases[0].kept, false)
    f.host.keepFails = false
    const kept = await follows.keep(id)
    assert.equal(kept.queued, 2)
    assert.deepEqual(f.host.kept, [id])
    assert.equal(kept.state.releases[0].kept, true)
    await assert.rejects(follows.keep('nothing'), /no longer/)
  } finally {
    await f.clean()
  }
})

const sample = (name: string) => fs.readFile(new URL(`../../fixtures/engine/music/samples/ytmusic-artist-${name}.json`, import.meta.url), 'utf8')
const answering = (body: string, calls: {url: string; body: string}[] = []) =>
  (async (url: string, init: RequestInit) => {
    calls.push({url, body: String(init.body)})
    return new Response(body)
  }) as unknown as typeof fetch

test('the release check reads an artist page: albums then singles, and the uploads of a channel without any', async () => {
  const calls: {url: string; body: string}[] = []
  const albums = await fetchArtistReleases('UC0076UMUgEng8HORUw_MYHA', answering(await sample('albums-and-singles'), calls))
  assert.equal(calls[0].url, 'https://music.youtube.com/youtubei/v1/browse?prettyPrint=false')
  assert.match(calls[0].body, /"browseId":"UC0076UMUgEng8HORUw_MYHA"/)
  assert.deepEqual(albums.map((r) => r.kind), ['Album', 'Album', 'Album', 'Single', 'Single', 'Single'])
  assert.equal(albums[0].title, 'petal')
  assert.equal(albums[0].link, 'https://music.youtube.com/playlist?list=OLAK5uy_mtyCSl5kALK2CkurHfhbSbHRAeY4WPmew')
  const videos = await fetchArtistReleases('UC8CX0LD98EDXl4UYX1MDCXg', answering(await sample('channel-videos-only')))
  assert.equal(videos.length, 3)
  assert.equal(videos[0].link, 'https://www.youtube.com/watch?v=69sKYwsxUM8')
  assert.equal(videos[0].kind, 'Video')
  await assert.rejects(fetchArtistReleases('not-a-channel', answering('{}')), /channel id/)
  await assert.rejects(fetchArtistReleases('UC0076UMUgEng8HORUw_MYHA', (async () => new Response('', {status: 500})) as unknown as typeof fetch), /HTTP 500/)
})

test('one artist that fails does not stop the others, and a failure never shows a channel id or engine text', async () => {
  const f = await fixture()
  try {
    const follows = f.make()
    const channels = new Map([['Pale Cassette', 'UC0000000001'], ['Glum Aleks', 'UC0000000002']])
    f.host.resolveChannel = async (name: string) => channels.get(name)
    f.host.listReleases = async (channelId) => {
      if (channelId === 'UC0000000001') throw new Error('UC0000000001: This channel does not have a releases tab')
      return [hit(7)]
    }
    await follows.follow('Pale Cassette')
    await follows.follow('Glum Aleks')
    const state = await follows.check(undefined, true)
    const [first, second] = ['Pale Cassette', 'Glum Aleks'].map((name) => state.artists.find((artist) => artist.name === name)!)
    assert.equal(first.error, "Keepfin couldn't check this artist right now. It will try again later.")
    assert.doesNotMatch(first.error ?? '', /UC0|engine|tab/)
    assert.equal(second.error, undefined)
    assert.equal(second.checkedOnce, true)
  } finally {
    await f.clean()
  }
})

test('an artist stored by the first release check (a releases-tab address) is checked afresh through its channel id, and its old error is dropped', async () => {
  const f = await fixture()
  try {
    const old = {
      v: 1,
      releases: [],
      artists: [{id: 'a1', name: 'Ariana Grande', key: 'ariana grande', seen: [], checkedOnce: false, checkedAt: 1_799_999_999_000, error: 'The download engine stopped: UC0076UMUgEng8HORUw_MYHA: This channel does not have a releases tab', url: 'https://www.youtube.com/channel/UC0076UMUgEng8HORUw_MYHA/releases'}],
    }
    await fs.writeFile(f.file, JSON.stringify(old))
    const follows = f.make()
    const loaded = await follows.current()
    assert.equal(loaded.artists[0].error, undefined)
    assert.equal(loaded.artists[0].checkedAt, undefined)
    const state = await follows.check(undefined, false)
    assert.equal(state.artists[0].checkedOnce, true)
    assert.deepEqual(f.host.listed, ['UC0076UMUgEng8HORUw_MYHA'])
    assert.equal(state.artists[0].url, 'https://music.youtube.com/channel/UC0076UMUgEng8HORUw_MYHA')
  } finally {
    await f.clean()
  }
})

test('a kept release asks for the music videos only when the setting, the library and an audio download all allow it', async () => {
  const run = async (settings: {defaultPreset: string; musicVideos?: boolean; library?: {enabled: boolean}}, music = true) => {
    const calls: unknown[][] = []
    const queue: ReleaseQueue = {
      startDiscovery: () => undefined,
      discoveryState: () => ({state: 'done', entries: [entry(1), entry(2)]}),
      cancelDiscovery: () => undefined,
      enqueueDiscovered: async (...args: unknown[]) => void calls.push(args),
      getSettings: () => settings,
    }
    await artistHost(queue, async () => undefined, async () => [], () => music).keep('https://music.youtube.com/playlist?list=OLAK5uy_1')
    return calls[0]
  }
  const on = {defaultPreset: 'audio-mp3', musicVideos: true, library: {enabled: true}}
  assert.equal((await run(on))[7], true)
  assert.equal((await run({...on, musicVideos: false}))[7], undefined)
  assert.equal((await run({...on, library: {enabled: false}}))[7], undefined)
  assert.equal((await run(on, false))[7], undefined)
  // a video preset becomes mp3 for a music link, so it can carry the video; a non-music link never does
  assert.equal((await run({...on, defaultPreset: 'best'}))[2], 'audio-mp3')
  assert.equal((await run({...on, defaultPreset: 'best'}))[7], true)
  assert.equal((await run({...on, defaultPreset: 'best'}, false))[7], undefined)
})
