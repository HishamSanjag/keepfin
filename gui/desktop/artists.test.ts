import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {PlaylistEntry} from '../shared/contracts.js'
import {ArtistFollowing, ARTIST_CHECK_EVERY_MS, releaseLink, type ArtistHost} from './library/artists.js'

const HOUR = 3_600_000
const entry = (n: number, title = `Album - Release ${n}`): PlaylistEntry => ({url: `https://www.youtube.com/playlist?list=OLAK5uy_${n}`, title, position: n})

class Host implements ArtistHost {
  channel: string | undefined = 'UC1'
  searchFails = false
  listing: PlaylistEntry[] = [entry(1), entry(2)]
  discoverFails = false
  kept: string[] = []
  keepFails = false
  async resolveChannel() {
    if (this.searchFails) throw new Error('offline')
    return this.channel
  }
  async discover() {
    if (this.discoverFails) throw new Error('yt-dlp failed')
    return {entries: this.listing}
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
    assert.equal(state.artists[0].url, 'https://www.youtube.com/channel/UC1/releases')
    assert.equal(state.releases.length, 0)
    f.host.listing = [entry(3, 'Single - Night Ferry'), entry(1), entry(2)]
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
    f.host.listing = [entry(5)]
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
    f.host.discoverFails = true
    state = await follows.check(undefined, true)
    assert.ok(state.artists[0].error)
    assert.doesNotMatch(state.artists[0].error ?? '', /yt-dlp/i)
    f.host.discoverFails = false
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
    f.host.listing = [entry(9), entry(1), entry(2)]
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

test('album links stay music links and other links are left alone', () => {
  assert.equal(releaseLink('https://www.youtube.com/playlist?list=OLAK5uy_x'), 'https://music.youtube.com/playlist?list=OLAK5uy_x')
  assert.equal(releaseLink('https://www.youtube.com/watch?v=abc'), 'https://www.youtube.com/watch?v=abc')
})
