// Which tab a link opens on (Capture). A YouTube link is a video even when the default is audio; a YouTube Music link is audio.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import type {MediaInfo} from '../../shared/contracts.js'
import {choiceForPreset, defaultChoice, formatRequest} from './choice.js'

const media = (url: string, patch: Partial<MediaInfo> = {}): MediaInfo => ({
  url,
  webpageUrl: url,
  title: 'A song',
  provider: 'YouTube',
  engine: 'yt-dlp',
  video: [{id: '1080p', height: 1080, hdr: false, codec: 'h264', ext: 'mp4', formatId: '137', hasAudio: false, sizeApprox: true}],
  audio: [{id: 'best', formatId: '140', codec: 'aac', ext: 'm4a', sizeApprox: true}],
  subtitles: [],
  chapters: 0,
  ...patch,
})

test('an audio default opens a YouTube link on Video and a YouTube Music link on Audio', () => {
  assert.equal(defaultChoice(media('https://www.youtube.com/watch?v=abcdefghijk'), 'audio-mp3').tab, 'video')
  assert.equal(defaultChoice(media('https://music.youtube.com/watch?v=abcdefghijk'), 'audio-mp3').tab, 'audio')
})

test('the audio default still applies to every other site', () => {
  const choice = defaultChoice(media('https://vimeo.com/123', {provider: 'Vimeo'}), 'audio-mp3')
  assert.equal(choice.tab, 'audio')
  assert.equal(choice.target, 'mp3')
})

test('the YouTube Music setting set to video opens its links on Video, with an audio default too', () => {
  assert.equal(defaultChoice(media('https://music.youtube.com/watch?v=abcdefghijk'), 'audio-mp3', 'video').tab, 'video')
})

test('without an audio default a YouTube link opens on Video and a YouTube Music link on Audio', () => {
  assert.equal(defaultChoice(media('https://www.youtube.com/watch?v=abcdefghijk'), '1080').tab, 'video')
  assert.equal(defaultChoice(media('https://music.youtube.com/watch?v=abcdefghijk'), '1080').tab, 'audio')
})

test('a preset for all links picks the video at or below its height, or the audio format', () => {
  const link = media('https://www.youtube.com/watch?v=abcdefghijk', {
    video: [
      {id: '2160p', height: 2160, hdr: false, codec: 'vp9', ext: 'webm', formatId: '315', hasAudio: false, sizeApprox: true},
      {id: '720p', height: 720, hdr: false, codec: 'h264', ext: 'mp4', formatId: '136', hasAudio: false, sizeApprox: true},
    ],
  })
  const base = defaultChoice(link, 'best')
  assert.equal(choiceForPreset(link, base, '1080').videoId, '720p')
  assert.equal(choiceForPreset(link, base, 'best').videoId, '2160p')
  const audio = choiceForPreset(link, base, 'audio-flac')
  assert.deepEqual([audio.tab, audio.target], ['audio', 'flac'])
})

test('a direct file has no height to read: the request asks for no height', () => {
  const file = media('https://example.com/clip.mp4', {
    provider: 'Link', audio: [],
    video: [{id: 'original', height: 0, hdr: false, codec: 'other', ext: 'mp4', formatId: 'mp4', hasAudio: true, sizeApprox: true}],
  })
  const request = formatRequest(file, defaultChoice(file, 'best'))
  assert.equal(request.type === 'video' ? request.maxHeight : 'not a video', undefined)
})

test('a link with nothing of the kind keeps its choice', () => {
  const photos = media('https://www.instagram.com/p/abc/', {video: [], audio: [], engine: 'gallery-dl', gallery: {count: 4, previews: [], truncated: false}})
  const base = defaultChoice(photos, 'best')
  assert.deepEqual(choiceForPreset(photos, base, 'audio-mp3'), base)
})
