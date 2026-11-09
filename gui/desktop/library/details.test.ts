import assert from 'node:assert/strict'
import test from 'node:test'
import {technicalFacts} from '../../web/library/itemFacts'
import {detailsOf} from './details'

const reel = `Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'reel.mp4':
  Duration: 00:00:33.60, start: 0.000000, bitrate: 451 kb/s
  Stream #0:0[0x1](und): Video: vp9 (Profile 0) (vp09 / 0x39307076), yuv420p(tv, bt709), 720x728, 403 kb/s, 30 fps, 30 tbr, 15360 tbn (default)
  Stream #0:1[0x2](und): Audio: aac (HE-AAC) (mp4a / 0x6134706D), 44100 Hz, stereo, fltp, 43 kb/s (default)`

test('the details come from the converter report of the file', () => {
  assert.deepEqual(detailsOf(reel), {duration: 33.6, videoCodec: 'vp9', width: 720, height: 728, fps: 30, audioCodec: 'aac', bitrate: 451})
})

test('a video stored sideways is shown turned, and a fractional frame rate keeps two decimals', () => {
  const phone = `Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'p.mp4':
  Duration: 00:00:10.00, start: 0.000000, bitrate: 17000 kb/s
  Stream #0:0[0x1](und): Video: h264 (High), yuv420p, 1920x1080, 16000 kb/s, 29.97 fps, 30 tbr (default)
    Side data:
      displaymatrix: rotation of -90.00 degrees
  Stream #0:1[0x2](und): Audio: aac (LC), 48000 Hz, stereo, fltp, 128 kb/s (default)`
  const d = detailsOf(phone)
  assert.equal(d.width, 1080)
  assert.equal(d.height, 1920)
  assert.equal(d.fps, 29.97)
})

test('a song has no picture: codec and bitrate only', () => {
  const song = `Input #0, mp3, from 's.mp3':
  Duration: 00:03:20.10, start: 0.025057, bitrate: 320 kb/s
  Stream #0:0: Audio: mp3, 44100 Hz, stereo, fltp, 320 kb/s`
  assert.deepEqual(detailsOf(song), {duration: 200.1, audioCodec: 'mp3', bitrate: 320})
})

test('the Details rows say resolution, codecs, bitrate and container in words', () => {
  const rows = technicalFacts({kind: 'video'}, {...detailsOf(reel), container: 'MP4'})
  assert.deepEqual(rows, [
    ['Container', 'MP4'],
    ['Resolution', '720x728 · 30 fps'],
    ['Video codec', 'VP9'],
    ['Audio codec', 'AAC'],
    ['Bitrate', '451 kbit/s'],
  ])
})

test('before the file has answered the library own size stands in; a song shows no video rows', () => {
  assert.deepEqual(technicalFacts({kind: 'video', width: 1080, height: 1920}, undefined), [['Resolution', '1080x1920']])
  assert.deepEqual(technicalFacts({kind: 'audio'}, {videoCodec: 'mjpeg', audioCodec: 'mp3', bitrate: 320, container: 'MP3'}), [
    ['Container', 'MP3'],
    ['Audio codec', 'MP3'],
    ['Bitrate', '320 kbit/s'],
  ])
  assert.deepEqual(technicalFacts({kind: 'video'}, {bitrate: 8300}), [['Bitrate', '8.3 Mbit/s']])
})
