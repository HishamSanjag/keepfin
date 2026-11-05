import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {expandShortLink, parseAppleMusic, parseDeezer, parseSpotifyEmbed, resolveMusic, type SourceTrack} from './music.js'
// The matching itself lives in the shared core; these tests exercise it through the desktop adapter.
import {bestMatch, coreTitle, parseFlatSearch, parseYtMusicSearch, pickBest, rankAlternates, scoreCandidate, searchQuery} from '../../shared/core/music.js'
import {musicLink} from '../../shared/providers.js'

const dir = path.join(path.dirname(fileURLToPath(import.meta.url)), '../fixtures/music')
const read = (name: string) => fs.readFileSync(path.join(dir, name), 'utf8')

test('Spotify embed: track with artists, duration, cover and year', () => {
  const source = parseSpotifyEmbed(read('spotify-embed-track.html'), musicLink('https://open.spotify.com/track/69kOkLUCkxIZYexIgSG8rq')!)
  assert.equal(source.kind, 'track')
  if (source.kind !== 'track') return
  assert.equal(source.track.title, 'Get Lucky (feat. Pharrell Williams and Nile Rodgers)')
  assert.deepEqual(source.track.artists, ['Daft Punk', 'Pharrell Williams', 'Nile Rodgers'])
  assert.equal(source.track.durationMs, 369626)
  assert.equal(source.track.releaseYear, 2013)
  assert.match(source.track.cover!, /^https:\/\/image-cdn/)
  assert.equal(source.track.url, 'https://open.spotify.com/track/69kOkLUCkxIZYexIgSG8rq')
})

test('Spotify embed: album numbers tracks and carries album + cover', () => {
  const source = parseSpotifyEmbed(read('spotify-embed-album.html'), musicLink('https://open.spotify.com/album/4m2880jivSbbyEGAKfITCa')!)
  assert.equal(source.kind, 'collection')
  if (source.kind !== 'collection') return
  const {collection} = source
  assert.equal(collection.title, 'Random Access Memories')
  assert.equal(collection.owner, 'Daft Punk')
  assert.equal(collection.tracks.length, 13)
  assert.equal(collection.total, 13)
  const lucky = collection.tracks[7]
  assert.match(lucky.title, /^Get Lucky/)
  assert.equal(lucky.trackNumber, 8)
  assert.equal(lucky.album, 'Random Access Memories')
  assert.match(lucky.url, /^https:\/\/open\.spotify\.com\/track\/[A-Za-z0-9]+$/)
})

test('Spotify embed: playlist entries keep per-track artists', () => {
  const source = parseSpotifyEmbed(read('spotify-embed-playlist.html'), musicLink('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M')!)
  assert.equal(source.kind, 'collection')
  if (source.kind !== 'collection') return
  assert.equal(source.collection.title, 'Today’s Top Hits')
  assert.equal(source.collection.tracks.length, 12)
  assert.ok(source.collection.tracks.every((t) => t.artists.length > 0 && t.durationMs! > 30_000))
  assert.equal(source.collection.tracks[0].trackNumber, undefined)
})

test('Spotify embed without data gives a clear error', () => {
  assert.throws(() => parseSpotifyEmbed('<html></html>', {service: 'spotify', type: 'track', id: 'x'}), /private or region-locked/)
})

test('Deezer API: track, album and playlist', () => {
  const track = parseDeezer(JSON.parse(read('deezer-track.json')), {service: 'deezer', type: 'track', id: '67238735'})
  assert.equal(track.kind, 'track')
  if (track.kind === 'track') {
    assert.match(track.track.title, /^Get Lucky/)
    assert.equal(track.track.album, 'Random Access Memories')
    assert.equal(track.track.durationMs, 367_000)
    assert.equal(track.track.trackNumber, 8)
    assert.ok(track.track.artists.includes('Daft Punk'))
  }
  const album = parseDeezer(JSON.parse(read('deezer-album.json')), {service: 'deezer', type: 'album', id: '6575789'})
  assert.equal(album.kind, 'collection')
  if (album.kind === 'collection') {
    assert.equal(album.collection.tracks.length, 13)
    assert.equal(album.collection.tracks[2].trackNumber, 3)
    assert.equal(album.collection.tracks[2].releaseYear, 2013)
    assert.match(album.collection.cover!, /1000x1000/)
  }
  const playlist = parseDeezer(JSON.parse(read('deezer-playlist.json')), {service: 'deezer', type: 'playlist', id: '908622995'})
  assert.equal(playlist.kind, 'collection')
  if (playlist.kind === 'collection') {
    assert.equal(playlist.collection.total, 50)
    assert.equal(playlist.collection.tracks.length, 10)
    assert.ok(playlist.collection.tracks[0].album)
  }
  assert.throws(() => parseDeezer({error: {message: 'no data'}}, {service: 'deezer', type: 'track', id: '1'}), /Deezer/)
})

test('Apple Music: song JSON-LD, album and playlist server data', () => {
  const song = parseAppleMusic(read('apple-song.html'), musicLink('https://music.apple.com/us/song/get-lucky/617154366')!)
  assert.equal(song.kind, 'track')
  if (song.kind === 'track') {
    assert.equal(song.track.title, 'Get Lucky')
    assert.deepEqual(song.track.artists, ['Daft Punk', 'Pharrell Williams', 'Nile Rodgers'])
    assert.equal(song.track.durationMs, 369_000)
    assert.equal(song.track.album, 'Random Access Memories')
  }
  const album = parseAppleMusic(read('apple-album.html'), musicLink('https://music.apple.com/us/album/random-access-memories/617154241')!)
  assert.equal(album.kind, 'collection')
  if (album.kind === 'collection') {
    assert.equal(album.collection.title, 'Random Access Memories')
    assert.equal(album.collection.owner, 'Daft Punk')
    assert.equal(album.collection.tracks.length, 13)
    const lucky = album.collection.tracks[7]
    assert.equal(lucky.title, 'Get Lucky')
    assert.deepEqual(lucky.artists, ['Daft Punk', 'Pharrell Williams', 'Nile Rodgers'])
    assert.equal(lucky.trackNumber, 8)
    assert.match(lucky.cover!, /1000x1000bb\.jpg$/)
  }
  const playlist = parseAppleMusic(read('apple-playlist.html'), musicLink('https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb')!)
  assert.equal(playlist.kind, 'collection')
  if (playlist.kind === 'collection') {
    assert.equal(playlist.collection.tracks.length, 8)
    assert.ok(playlist.collection.tracks.every((t) => t.artists.length && t.album))
  }
})

const getLucky: SourceTrack = {
  title: 'Get Lucky (feat. Pharrell Williams and Nile Rodgers)',
  artists: ['Daft Punk', 'Pharrell Williams', 'Nile Rodgers'],
  durationMs: 369_626,
  url: 'https://open.spotify.com/track/69kOkLUCkxIZYexIgSG8rq',
}

test('YouTube Music search parsing and matching picks the album recording', () => {
  const candidates = parseYtMusicSearch(JSON.parse(read('ytmusic-search-songs.json')))
  assert.ok(candidates.length >= 6)
  const first = candidates[0]
  assert.equal(first.url, 'https://music.youtube.com/watch?v=4D7u5KF7SP8')
  assert.deepEqual(first.artists, ['Daft Punk', 'Pharrell Williams', 'Nile Rodgers'])
  assert.equal(first.album, 'Random Access Memories')
  assert.equal(first.durationSec, 370)
  const match = bestMatch(getLucky, candidates)!
  assert.equal(match.candidate.url, first.url)
  assert.ok(match.confidence >= 0.9, String(match.confidence))
  const radio = candidates.find((c) => /Radio Edit/.test(c.title))!
  const live = candidates.find((c) => /Live/i.test(c.title))!
  assert.ok(scoreCandidate(getLucky, radio) < match.confidence - 0.2, 'duration mismatch is penalised')
  assert.ok(scoreCandidate(getLucky, live) < 0.5, 'live versions are penalised')
  // The radio edit itself still matches its own recording.
  const radioTrack = {...getLucky, title: 'Get Lucky (Radio Edit)', durationMs: 248_000}
  assert.equal(bestMatch(radioTrack, candidates)!.candidate.url, radio.url)
})

test('flat yt-dlp search fallback scores official uploads above lyric/fan uploads', () => {
  const candidates = parseFlatSearch(JSON.parse(read('ytsearch-flat.json')), 'youtube')
  assert.equal(candidates.length, 5)
  const match = bestMatch({...getLucky, durationMs: 248_000, title: 'Get Lucky'}, candidates)!
  assert.equal(match.candidate.channel, 'Daft Punk')
})

test('no match below the confidence floor', () => {
  assert.equal(
    bestMatch(getLucky, [{url: 'https://music.youtube.com/watch?v=aaaaaaaaaaa', title: 'Completely different', artists: ['Someone'], durationSec: 120, origin: 'youtube'}]),
    undefined,
  )
})

test('title cleanup and queries', () => {
  assert.equal(coreTitle('Get Lucky (feat. Pharrell Williams and Nile Rodgers)'), 'Get Lucky')
  assert.equal(coreTitle('Bohemian Rhapsody - Remastered 2011'), 'Bohemian Rhapsody')
  assert.equal(coreTitle('Song ft. Someone'), 'Song')
  assert.equal(searchQuery(getLucky), 'Daft Punk Pharrell Williams Get Lucky')
})

test('pickBest stops at a confident result and survives failing searches', async () => {
  const calls: string[] = []
  const candidates = parseYtMusicSearch(JSON.parse(read('ytmusic-search-songs.json')))
  const {best: result} = await pickBest(getLucky, [
    async () => {
      calls.push('broken')
      throw new Error('offline')
    },
    async () => {
      calls.push('ytm')
      return candidates
    },
    async () => {
      calls.push('youtube')
      return []
    },
  ])
  assert.deepEqual(calls, ['broken', 'ytm'])
  assert.equal(result?.candidate.url, 'https://music.youtube.com/watch?v=4D7u5KF7SP8')
})

test('resolveMusic fetches the embed page and expands short links', async () => {
  const requests: string[] = []
  const fakeFetch = (async (input: string | URL) => {
    const url = String(input)
    requests.push(url)
    if (url.startsWith('https://spotify.link/'))
      return Object.assign(new Response('<a href="https://open.spotify.com/track/69kOkLUCkxIZYexIgSG8rq?si=1&amp;x=2">open</a>'), {})
    return new Response(read('spotify-embed-track.html'))
  }) as typeof fetch
  assert.equal(await expandShortLink('https://spotify.link/abc', fakeFetch), 'https://open.spotify.com/track/69kOkLUCkxIZYexIgSG8rq?si=1&x=2')
  const source = await resolveMusic('https://spotify.link/abc', fakeFetch)
  assert.equal(source.kind, 'track')
  assert.equal(requests.at(-1), 'https://open.spotify.com/embed/track/69kOkLUCkxIZYexIgSG8rq')
})

test('watch page parsing: restricted Topic track, playable page and unknown pages', async () => {
  const {parseWatchPage, classifyProbe, youTubeVideoId, isMusicLike} = await import('./music.js')
  const restricted = parseWatchPage(
    '..."playabilityStatus":{"status":"UNPLAYABLE","reason":"Video unavailable","errorScreen":{}},"videoDetails":{"videoId":"jdOSfoV1njc","title":"First Flame","lengthSeconds":"290","keywords":["a"],"channelId":"x","author":"Glum Aleks - Topic"}',
  )
  assert.equal(restricted.status, 'UNPLAYABLE')
  assert.deepEqual(restricted.video, {id: 'jdOSfoV1njc', title: 'First Flame', author: 'Glum Aleks - Topic', lengthSeconds: 290})
  assert.equal(classifyProbe(restricted), 'restricted')
  assert.equal(classifyProbe(parseWatchPage('"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"jdOSfoV1njc","title":"x","lengthSeconds":"3"')), 'transient')
  assert.equal(classifyProbe(parseWatchPage('"playabilityStatus":{"status":"UNPLAYABLE","reason":"Private video"}')), 'unknown')
  assert.equal(classifyProbe({}), 'unknown')
  assert.equal(youTubeVideoId('https://music.youtube.com/watch?v=jdOSfoV1njc&list=x'), 'jdOSfoV1njc')
  assert.equal(youTubeVideoId('https://youtu.be/jdOSfoV1njc'), 'jdOSfoV1njc')
  assert.equal(isMusicLike({url: 'https://www.youtube.com/watch?v=aaaaaaaaaaa', author: 'Glum Aleks - Topic'}), true)
  assert.equal(isMusicLike({url: 'https://www.youtube.com/watch?v=aaaaaaaaaaa', groupUrl: 'https://music.youtube.com/playlist?list=x'}), true)
  assert.equal(isMusicLike({url: 'https://www.youtube.com/watch?v=aaaaaaaaaaa', author: 'Some Vlogger'}), false)
})

test('alternate ranking excludes the original, tried ids, low confidence and mismatched durations', async () => {
  const track = {title: 'First Flame', artists: ['Glum Aleks'], durationMs: 290_000, url: 'u'}
  const c = (id: string, title: string, durationSec: number, origin: 'ytm-song' | 'youtube' = 'ytm-song') => ({
    url: `https://music.youtube.com/watch?v=${id}`,
    title,
    artists: ['Glum Aleks'],
    durationSec,
    origin,
  })
  const ranked = rankAlternates(track, [
    c('jdOSfoV1njc', 'First Flame', 290),
    c('j3SiMcrObkU', 'First Flame', 289),
    c('triedtried1', 'First Flame', 290),
    c('wrongdur123', 'First Flame', 330),
    c('othersong12', 'Completely Different', 290, 'youtube'),
    c('j3SiMcrObkU', 'First Flame', 289),
  ], {exclude: ['jdOSfoV1njc', 'triedtried1']})
  assert.deepEqual(ranked.map((r) => r.candidate.url.slice(-11)), ['j3SiMcrObkU'])
  assert.ok(ranked[0].confidence >= 0.6)
})
