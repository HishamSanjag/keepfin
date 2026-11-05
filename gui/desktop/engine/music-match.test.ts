import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {StudioEngine} from './index.js'
import {MusicChoices, validChoice} from './choices.js'
import type {SourceTrack} from './music.js'

const samples = path.join(path.dirname(fileURLToPath(import.meta.url)), '../../../fixtures/engine/music/samples')
const sample = (name: string) => fs.readFileSync(path.join(samples, name), 'utf8')

// Spotify's Random Access Memories (the embed's track list: title, artists, milliseconds), album of 13.
const ram: [string, string[], number][] = [
  ['Give Life Back to Music', ['Daft Punk'], 275386],
  ['The Game of Love', ['Daft Punk'], 322146],
  ['Giorgio by Moroder', ['Daft Punk'], 544626],
  ['Within', ['Daft Punk'], 228506],
  ['Instant Crush (feat. Julian Casablancas)', ['Daft Punk', 'Julian Casablancas'], 337560],
  ['Lose Yourself to Dance (feat. Pharrell Williams)', ['Daft Punk', 'Pharrell Williams'], 353893],
  ['Touch (feat. Paul Williams)', ['Daft Punk', 'Paul Williams'], 498960],
  ['Get Lucky (feat. Pharrell Williams and Nile Rodgers)', ['Daft Punk', 'Pharrell Williams', 'Nile Rodgers'], 369626],
  ['Beyond', ['Daft Punk'], 290240],
  ['Motherboard', ['Daft Punk'], 341653],
  ['Fragments of Time (feat. Todd Edwards)', ['Daft Punk', 'Todd Edwards'], 279773],
  ["Doin' it Right (feat. Panda Bear)", ['Daft Punk', 'Panda Bear'], 251293],
  ['Contact', ['Daft Punk'], 383626],
]
const tracks = (extra: Partial<SourceTrack> = {}): SourceTrack[] =>
  ram.map(([title, artists, durationMs], index) => ({
    title,
    artists,
    durationMs,
    url: `https://open.spotify.com/track/${index}`,
    album: 'Random Access Memories',
    trackNumber: index + 1,
    trackCount: 13,
    ...extra,
  }))

/** Serves the captured YouTube Music responses and counts the requests. */
function music() {
  const calls: {kind: 'albums' | 'page' | 'songs'; body: Record<string, unknown>}[] = []
  const fetcher = (async (_url: string | URL, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body)) as Record<string, unknown>
    if (typeof body.browseId === 'string') {
      calls.push({kind: 'page', body})
      const file = ({MPREb_K8qWMWVqXGi: 'ytmusic-album-ram.json', MPREb_OgeEnoHTsCm: 'ytmusic-album-ram-drumless.json'} as Record<string, string>)[body.browseId]
      return file ? new Response(sample(file)) : new Response('{}', {status: 404})
    }
    if (body.params === 'EgWKAQIYAWoKEAkQBRAKEAMQBA==') {
      calls.push({kind: 'albums', body})
      return new Response(sample('ytmusic-search-albums-ram.json'))
    }
    calls.push({kind: 'songs', body})
    return new Response(sample('ytmusic-search-songs.json'))
  }) as typeof fetch
  return {calls, fetcher}
}

function engineWith(fetcher: typeof fetch, choicesFile?: string) {
  const engine = new StudioEngine({} as never, {} as never, {fetch: fetcher, choicesFile})
  ;(engine as unknown as {ytdlp: () => Promise<unknown>}).ytdlp = async () => ({binary: 'none', env: {}})
  return engine as unknown as {matchTrack(track: SourceTrack, url: string, ctx: unknown, yt: unknown): Promise<any>}
}

const ctx = {settings: {}}

test('a whole album is matched through one albums search and one album page', async () => {
  const {calls, fetcher} = music()
  const engine = engineWith(fetcher)
  // Parallel workers match the tracks at the same time.
  const picks = await Promise.all(tracks().map((track) => engine.matchTrack(track, track.url, ctx, {})))
  assert.deepEqual(
    picks.map((pick) => pick?.method),
    Array(13).fill('album'),
  )
  assert.deepEqual(
    calls.map((call) => call.kind),
    ['albums', 'page'],
  )
  assert.equal(picks[1].candidate.url, 'https://music.youtube.com/watch?v=ajGKWk0auOc')
  assert.equal(picks[1].position, 2)
  assert.equal(picks[1].album, 'Random Access Memories')
  // A later track of the same album still needs no request.
  await engine.matchTrack(tracks()[0], 'https://open.spotify.com/track/again', ctx, {})
  assert.equal(calls.length, 2)
})

test('a failed album page is not kept, so the next track tries again', async () => {
  let fail = true
  const {calls, fetcher} = music()
  const flaky = (async (url: string | URL, init?: RequestInit) => {
    if (fail && typeof JSON.parse(String(init?.body)).browseId === 'string') {
      calls.push({kind: 'page', body: {}})
      throw new Error('offline')
    }
    return fetcher(url, init)
  }) as typeof fetch
  const engine = engineWith(flaky)
  // The album route fails, then the songs search runs (the sample answers every other request).
  const first = await engine.matchTrack(tracks()[7], 'https://open.spotify.com/track/7', ctx, {}).catch(() => undefined)
  assert.notEqual(first?.method, 'album')
  fail = false
  const second = await engine.matchTrack(tracks()[7], 'https://open.spotify.com/track/7', ctx, {})
  assert.equal(second.method, 'album')
})

test('a remembered choice wins and is validated on load', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'keepfin-choices-'))
  const file = path.join(dir, 'music-choices.json')
  const {calls, fetcher} = music()
  const engine = new StudioEngine({} as never, {} as never, {fetch: fetcher, choicesFile: file})
  await engine.rememberMusicChoice('https://open.spotify.com/track/3ctALmweZBapfBdFiIVpji?si=1', {matchedUrl: 'https://music.youtube.com/watch?v=AAAAAAAAAAA', title: 'Picked', durationSec: 200})
  await assert.rejects(engine.rememberMusicChoice('https://example.com/x', {matchedUrl: 'https://music.youtube.com/watch?v=AAAAAAAAAAA'}))
  await assert.rejects(engine.rememberMusicChoice('https://open.spotify.com/track/3ctALmweZBapfBdFiIVpji', {matchedUrl: 'https://example.com/watch'}))
  const reopened = engineWith(fetcher, file)
  const pick = await reopened.matchTrack(tracks()[1], 'https://open.spotify.com/track/3ctALmweZBapfBdFiIVpji', ctx, {})
  assert.equal(pick.method, 'chosen')
  assert.equal(pick.candidate.url, 'https://music.youtube.com/watch?v=AAAAAAAAAAA')
  assert.equal(calls.length, 0, 'a remembered choice needs no request')

  // Damaged or hostile files never throw and never load bad entries.
  fs.writeFileSync(file, JSON.stringify({choices: {'https://open.spotify.com/track/0dEIca2nhcxDUV8C5QkPYb': {videoId: 'BBBBBBBBBBB'}, 'https://open.spotify.com/track/2cGxRwrMyEAp8dEbuZaVv6': {videoId: 'short'}, 'javascript:alert(1)': {videoId: 'CCCCCCCCCCC'}}}))
  const store = new MusicChoices(file)
  assert.equal((await store.get('https://open.spotify.com/track/0dEIca2nhcxDUV8C5QkPYb'))?.videoId, 'BBBBBBBBBBB')
  assert.equal(await store.get('https://open.spotify.com/track/2cGxRwrMyEAp8dEbuZaVv6'), undefined)
  assert.equal(await store.get('javascript:alert(1)'), undefined)
  fs.writeFileSync(file, '{not json')
  assert.equal(await new MusicChoices(file).get('https://open.spotify.com/track/0dEIca2nhcxDUV8C5QkPYb'), undefined)
  assert.equal(validChoice({videoId: 'AAAAAAAAAAA', durationSec: -5})?.durationSec, undefined)
})
