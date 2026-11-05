import assert from 'node:assert/strict'
import test from 'node:test'
import type {DownloadOptions} from '../../shared/contracts.js'
import {extrasChanged, optionsSummary, validateOptions} from './extrasModel.js'

const base: DownloadOptions = {embedThumbnail: true, embedMetadata: true, embedChapters: true}

test('nothing changed counts nothing', () => {
  assert.equal(extrasChanged({...base}, base), 0)
  assert.equal(extrasChanged({...base, sponsorBlock: 'off', subtitles: {enabled: false, languages: 'en', auto: false, embed: true}}, base), 0)
})

test('each changed group counts once, however many of its choices moved', () => {
  assert.equal(extrasChanged({...base, embedThumbnail: false, embedMetadata: false}, base), 1)
  assert.equal(extrasChanged({...base, writeThumbnail: true, writeDescription: true}, base), 1)
  assert.equal(extrasChanged({...base, sponsorBlock: 'remove', splitChapters: true, section: {start: 5}}, base), 3)
  assert.equal(extrasChanged({...base, subtitles: {enabled: true, languages: 'en,de', auto: false, embed: true}}, base), 1)
})

test('a collection queued separately counts as a change', () => {
  assert.equal(extrasChanged(base, base, true), 1)
})

test('trim and subtitle rules still reject bad input', () => {
  assert.match(validateOptions({subtitles: {enabled: true, languages: 'en us!', auto: false, embed: true}}) ?? '', /Subtitle languages/)
  assert.match(validateOptions({section: {start: 10, end: 5}}) ?? '', /Trim end must be after/)
  assert.equal(validateOptions({section: {start: 10, end: 20}}, 60), undefined)
})

test('the queue summary names what was asked', () => {
  assert.equal(optionsSummary({}), 'No extras')
  assert.match(optionsSummary({sponsorBlock: 'mark', splitChapters: true}), /marks segments · Split by chapters/)
})
