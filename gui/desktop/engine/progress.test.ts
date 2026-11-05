import {test} from 'node:test'
import assert from 'node:assert/strict'
import {FILE_PREFIX, PROGRESS_PREFIX} from '../../shared/policy.js'
import {PROGRESS_LINE_PREFIX} from '../../shared/core/progress.js'
import {ProgressParser} from './progress.js'

const progress = (id: string, status: string, done: number, total: number | string, speed: number | string = 'NA') =>
  `${PROGRESS_PREFIX}${id}|${status}|${done}|${total}|NA|${speed}|NA|NA|NA`

test('the core parses the same progress prefix the args policy puts in the template', () => {
  assert.equal(PROGRESS_LINE_PREFIX, PROGRESS_PREFIX)
})

test('phases are worded for the desktop and the audio target names the conversion', () => {
  const parser = new ProgressParser({audioTarget: 'mp3'})
  parser.line(progress('1', 'finished', 10, 10))
  assert.equal(parser.line('[Merger] Merging formats into "/x/a.mp4"'), 'phase')
  assert.equal(parser.phase, 'Merging')
  assert.equal(parser.processing, true)
  assert.equal(parser.line('[Merger] Merging formats into "/x/a.mp4"'), undefined)
  parser.line('[ExtractAudio] Destination: /x/a.mp3')
  assert.equal(parser.phase, 'Converting to MP3')
  parser.line('[EmbedThumbnail] ffmpeg: Adding thumbnail to "/x/a.mp3"')
  assert.equal(parser.phase, 'Embedding thumbnail')
  assert.equal(parser.line(progress('1', 'downloading', 1, 10, 5)), 'progress')
  assert.equal(parser.phase, undefined)
  assert.equal(parser.processing, false)
  assert.equal(new ProgressParser({audioTarget: 'original'}).line('[ExtractAudio] Destination: /x/a.m4a'), 'phase')
})

test('without a target the audio phase is just extracting', () => {
  const parser = new ProgressParser()
  parser.line('[ExtractAudio] Destination: /x/a.m4a')
  assert.equal(parser.phase, 'Extracting audio')
})

test('progress reads optional numbers and keeps the shape the runner reports', () => {
  const parser = new ProgressParser({formatSizes: {'18': 1000}})
  parser.line('[info] abc: Downloading 1 format(s): 18')
  parser.line(progress('18', 'downloading', 250, 'NA', 50))
  assert.deepEqual(parser.progress(), {percent: 25, downloadedBytes: 250, totalBytes: 1000, speed: 50, eta: 15})
  assert.equal(new ProgressParser().progress().downloadedBytes, undefined)
})

test('files: the final path and the sidecars the engine reports', () => {
  const parser = new ProgressParser()
  parser.line(`${FILE_PREFIX}/x/a.mp4`)
  parser.line(`${FILE_PREFIX}relative.mp4`)
  parser.line('[info] abc: Writing video subtitles to: /x/a.en.vtt')
  parser.line('[info] abc: Writing video subtitles to: /x/a.en.vtt')
  parser.line('[info] abc: Writing video thumbnail 0 to: relative.jpg')
  assert.equal(parser.file, '/x/a.mp4')
  assert.deepEqual(parser.files, ['/x/a.en.vtt'])
})
