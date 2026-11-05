import {test} from 'node:test'
import assert from 'node:assert/strict'
import {
  isCollectionUrl,
  isGalleryHost,
  isShortMusicLink,
  musicLink,
  musicLinkUrl,
  platformFolder,
  playlistContext,
  isYouTubeMusic,
  prefersAudio,
  providerName,
} from './providers.js'

test('provider names: YouTube Music is distinct, extractor fallback, host fallback', () => {
  assert.equal(providerName('https://music.youtube.com/watch?v=x'), 'YouTube Music')
  assert.equal(providerName('https://m.youtube.com/watch?v=x'), 'YouTube')
  assert.equal(providerName('https://youtu.be/x'), 'YouTube')
  assert.equal(providerName('https://open.spotify.com/track/x'), 'Spotify')
  assert.equal(providerName('https://music.apple.com/us/album/x/1'), 'Apple Music')
  assert.equal(providerName('https://www.deezer.com/track/1'), 'Deezer')
  assert.equal(providerName('https://x.com/a/status/1'), 'X')
  assert.equal(providerName('https://vm.tiktok.com/abc'), 'TikTok')
  assert.equal(providerName('https://forss.bandcamp.com/track/x'), 'Bandcamp')
  assert.equal(providerName('https://www.pinterest.co.uk/pin/1'), 'Pinterest')
  assert.equal(providerName('https://example.org/video', 'Generic'), 'example.org')
  assert.equal(providerName('https://streams.example.org/v', 'Wistia'), 'Wistia')
})

test('platform folders keep v1 names and never trust lookalike hosts', () => {
  assert.equal(platformFolder('https://music.youtube.com/watch?v=x'), 'YouTube')
  assert.equal(platformFolder('https://www.reddit.com/r/x'), 'Reddit')
  assert.equal(platformFolder('https://threads.net/@a/post/1'), 'Threads')
  assert.equal(platformFolder('https://youtube.com.evil.test/video'), 'youtube.com.evil.test')
  assert.equal(platformFolder('https://soundcloud.com/a/b'), 'SoundCloud')
})

test('music links: Spotify (intl, embed), Apple Music (song, album ?i=, playlist), Deezer', () => {
  assert.deepEqual(musicLink('https://open.spotify.com/intl-de/track/69kOkLUCkxIZYexIgSG8rq?si=abc'), {
    service: 'spotify', type: 'track', id: '69kOkLUCkxIZYexIgSG8rq',
  })
  assert.deepEqual(musicLink('https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M'), {
    service: 'spotify', type: 'playlist', id: '37i9dQZF1DXcBWIGoYBM5M',
  })
  assert.equal(musicLink('https://open.spotify.com/artist/4tZwfgrHOc3mvqYlEYSvVi'), undefined)
  assert.deepEqual(musicLink('https://music.apple.com/us/album/random-access-memories/617154241?i=617154366'), {
    service: 'apple-music', type: 'track', id: '617154366', storefront: 'us',
  })
  assert.deepEqual(musicLink('https://music.apple.com/gb/song/get-lucky/617154366'), {
    service: 'apple-music', type: 'track', id: '617154366', storefront: 'gb',
  })
  assert.deepEqual(musicLink('https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb'), {
    service: 'apple-music', type: 'playlist', id: 'pl.f4d106fed2bd41149aaacabb233eb5eb', storefront: 'us',
  })
  assert.deepEqual(musicLink('https://www.deezer.com/fr/album/6575789'), {service: 'deezer', type: 'album', id: '6575789'})
  assert.equal(musicLinkUrl({service: 'apple-music', type: 'track', id: '1', storefront: 'gb'}), 'https://music.apple.com/gb/song/1')
  assert.ok(isShortMusicLink('https://spotify.link/abc'))
  assert.ok(isShortMusicLink('https://link.deezer.com/s/abc'))
  assert.ok(!isShortMusicLink('https://open.spotify.com/track/abc'))
})

test('collection-looking URLs route to discovery, single items do not', () => {
  const yes = [
    'https://www.youtube.com/playlist?list=PL123',
    'https://music.youtube.com/playlist?list=OLAK5uy_abc',
    'https://music.youtube.com/browse/MPREb_abc',
    'https://www.youtube.com/@mkbhd',
    'https://www.youtube.com/@mkbhd/videos',
    'https://www.youtube.com/channel/UC123/streams',
    'https://soundcloud.com/forss/sets/soulhack',
    'https://forss.bandcamp.com/album/x',
    'https://open.spotify.com/album/4m2880jivSbbyEGAKfITCa',
    'https://www.deezer.com/playlist/908622995',
  ]
  const no = [
    'https://www.youtube.com/watch?v=x&list=PL123',
    'https://www.youtube.com/shorts/abc',
    'https://soundcloud.com/forss/flickermood',
    'https://open.spotify.com/track/abc12345678',
    'https://vimeo.com/76979871',
  ]
  for (const url of yes) assert.ok(isCollectionUrl(url), url)
  for (const url of no) assert.ok(!isCollectionUrl(url), url)
})

test('playlist context and gallery hosts', () => {
  assert.deepEqual(playlistContext('https://music.youtube.com/watch?v=a&list=RDAMVMa'), {
    url: 'https://music.youtube.com/playlist?list=RDAMVMa',
  })
  assert.equal(playlistContext('https://www.youtube.com/watch?v=a'), undefined)
  assert.equal(playlistContext('https://www.youtube.com/watch?v=a&list=bad"id'), undefined)
  assert.ok(isGalleryHost('https://imgur.com/a/abc'))
  assert.ok(isGalleryHost('https://www.deviantart.com/x/art/y'))
  assert.ok(!isGalleryHost('https://www.instagram.com/p/abc'))
})

test('YouTube Music links route to audio by default; youtube.com stays video', () => {
  const music = [
    'https://music.youtube.com/watch?v=abc123',
    'https://music.youtube.com/playlist?list=PL123',
    'https://music.youtube.com/browse/MPREb_abc',
    'https://music.youtube.com/watch?v=abc&list=RDAMVMabc',
    'https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC',
  ]
  for (const url of music) assert.equal(prefersAudio(url), true, url)
  assert.equal(prefersAudio('https://www.youtube.com/watch?v=abc123'), false)
  assert.equal(prefersAudio('https://youtu.be/abc123'), false)
  assert.equal(prefersAudio('https://music.youtube.com.evil.example/watch?v=1'), false)
  assert.equal(isYouTubeMusic('https://music.youtube.com/browse/MPREb_abc'), true)
  assert.equal(isYouTubeMusic('not a url'), false)
  // The setting only affects YouTube Music; matched services always prefer audio.
  assert.equal(prefersAudio('https://music.youtube.com/watch?v=abc', 'video'), false)
  assert.equal(prefersAudio('https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC', 'video'), true)
})
