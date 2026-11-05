import assert from 'node:assert/strict'
import {test} from 'node:test'
import type {CopyPlaceView, CopyPreview, CopyRow} from '../../shared/copies'
import {copyOnDevice, copyStatusLine, defaultChoices, draftFromChoices, nothingChosen, setupBlocker, setupRows, summarisePreview} from './devices/setupLogic'

const rows: CopyRow[] = [
  {key: 'space:music', kind: 'space', id: 'music', name: 'Music'},
  {key: 'space:videos', kind: 'space', id: 'videos', name: 'Videos'},
  {key: 'space:downloads', kind: 'space', id: 'downloads', name: 'Downloads'},
  {key: 'favourites', kind: 'favourites', id: 'favourites', name: 'Favourites'},
  {key: 'playlist:road', kind: 'playlist', id: 'road', name: 'Road trip'},
]

test('the setup offers spaces and Favourites, with Music and Videos on to begin with', () => {
  const offered = setupRows(rows)
  assert.deepEqual(offered.map((r) => r.key), ['space:music', 'space:videos', 'space:downloads', 'favourites'])
  const choices = defaultChoices(offered)
  assert.deepEqual(choices, {spaces: ['music', 'videos'], favourites: false, direction: 'both', auto: true})
  assert.equal(nothingChosen(choices), false)
  assert.equal(nothingChosen({...choices, spaces: []}), true)
  assert.equal(nothingChosen({...choices, spaces: [], favourites: true}), false)
})

test('the draft: chosen spaces only, both ways is two-way, only to the device is a backup, no mirror, no path', () => {
  const both = draftFromChoices({deviceId: 'dev-laptop', name: 'Laptop'}, {spaces: ['music'], favourites: true, direction: 'both', auto: true})
  assert.deepEqual(both, {
    path: '',
    name: 'Laptop',
    mode: 'twoWay',
    holds: {all: false, spaces: ['music'], playlists: [], favourites: true},
    schedule: {everyHours: null, auto: true},
    device: {deviceId: 'dev-laptop'},
  })
  const only = draftFromChoices({deviceId: 'dev-laptop', name: 'Laptop'}, {spaces: ['videos'], favourites: false, direction: 'only', auto: false})
  assert.equal(only.mode, 'backup')
  assert.equal(only.schedule.auto, false)
})

test('a device that does not allow a copy says why and how to fix it on the other device', () => {
  assert.equal(setupBlocker({deviceId: 'a', name: 'Laptop', online: true, allowed: true}, 'Laptop'), null)
  assert.match(setupBlocker({deviceId: 'a', name: 'Laptop', online: true, allowed: false}, 'Laptop') ?? '', /On Laptop, open Devices, open this PC and turn on Keep a copy here\./)
  assert.equal(setupBlocker({deviceId: 'a', name: 'Laptop', online: false, allowed: false, why: 'Not connected right now.'}, 'Laptop'), 'Not connected right now.')
  assert.match(setupBlocker(undefined, 'Laptop') ?? '', /Laptop is not ready/)
})

const preview = (patch: Partial<CopyPreview> = {}): CopyPreview => ({
  placeId: 'p1',
  status: 'ready',
  additiveOnly: true,
  lines: [],
  copyFiles: 800,
  copyBytes: 2.5 * 1024 ** 3,
  destructive: 0,
  differences: [],
  differencesMore: 0,
  enough: true,
  freeBytes: 40 * 1024 ** 3,
  importsToLibrary: 0,
  ...patch,
})

test('the review: files and size to the device, files that come here only for both ways, space, and the first sync only adds', () => {
  const both = summarisePreview(preview({importsToLibrary: 36}), 'Laptop', 'both')
  assert.equal(both.toDevice, '800 files · 2.5 GB will go to Laptop.')
  assert.equal(both.toHere, '36 files on Laptop will come here.')
  assert.equal(both.space.text, 'There is enough space on Laptop (40.0 GB free).')
  assert.equal(both.additive, 'Nothing is removed or replaced.')
  assert.equal(both.canStart, true)
  assert.equal(both.differences, null)
  const only = summarisePreview(preview({importsToLibrary: 36}), 'Laptop', 'only')
  assert.equal(only.toHere, null, 'nothing comes back when the copy is one way')
})

test('not enough space blocks Start and says how much is free; an unknown amount does not block', () => {
  const full = summarisePreview(preview({enough: false, freeBytes: 2 * 1024 ** 3}), 'Laptop', 'both')
  assert.equal(full.canStart, false)
  assert.match(full.space.text, /not enough space on Laptop \(2.0 GB free\)/)
  const unknown = summarisePreview(preview({enough: null, freeBytes: undefined}), 'Laptop', 'both')
  assert.equal(unknown.canStart, true)
  assert.match(unknown.space.text, /could not check the free space/)
})

test('differences are listed only when there are any: up to five, then "and N more"', () => {
  const many = summarisePreview(preview({differences: [1, 2, 3, 4, 5, 6].map((i) => ({path: `Music/Track ${i}.mp3`, detail: 'x'})), differencesMore: 2}), 'Laptop', 'both')
  assert.deepEqual(many.differences?.names, ['Track 1.mp3', 'Track 2.mp3', 'Track 3.mp3', 'Track 4.mp3', 'Track 5.mp3'])
  assert.equal(many.differences?.more, 3)
  const few = summarisePreview(preview({differences: [{path: 'Music/A.mp3', detail: 'x'}]}), 'Laptop', 'both')
  assert.deepEqual(few.differences, {names: ['A.mp3'], more: 0})
})

test('held, refused, disconnected and busy are each explained in one sentence; only held can still start', () => {
  const held = summarisePreview(preview({status: 'held', reason: 'A big change.'}), 'Laptop', 'both')
  assert.equal(held.canStart, true)
  assert.match(held.note ?? '', /A big change\. Keepfin will ask you before it starts\./)
  for (const status of ['refused', 'disconnected', 'busy'] as const) {
    const summary = summarisePreview(preview({status, reason: 'No.'}), 'Laptop', 'both')
    assert.equal(summary.canStart, false, status)
    assert.ok(summary.problem && summary.problem.length > 10, status)
  }
  assert.match(summarisePreview(preview({status: 'disconnected'}), 'Laptop', 'both').problem ?? '', /Laptop is not connected right now/)
})

const place = (patch: Partial<CopyPlaceView> = {}): CopyPlaceView => ({
  id: 'p',
  name: 'Laptop',
  path: '',
  mode: 'twoWay',
  holds: {all: false, spaces: ['music'], playlists: [], favourites: false},
  schedule: {everyHours: null, auto: true},
  connected: true,
  status: 'upToDate',
  kept: 10,
  waiting: 0,
  review: 0,
  previewPending: false,
  cells: {},
  device: {deviceId: 'dev-laptop', deviceName: 'Laptop', online: true},
  ...patch,
})

test('the card sentence: copying with progress, up to date with the time, waiting for the device, files that need a decision', () => {
  const progress = {doneActions: 120, totalActions: 800, doneBytes: 1 * 1024 ** 3, totalBytes: 3.1 * 1024 ** 3, current: null}
  assert.equal(copyStatusLine(place({status: 'syncing', progress}), 'Laptop'), 'Copying 120 of 800 files · 2.1 GB left')
  assert.equal(copyStatusLine(place({status: 'syncing'}), 'Laptop'), 'Copying…')
  const at = new Date(2026, 9, 21, 21, 4).getTime()
  assert.match(copyStatusLine(place({lastVerifiedAt: at}), 'Laptop'), /^Up to date · checked \d{1,2}[:.]04/)
  assert.equal(copyStatusLine(place({lastVerifiedAt: undefined}), 'Laptop'), 'Up to date')
  assert.equal(copyStatusLine(place({device: {deviceId: 'dev-laptop', deviceName: 'Laptop', online: false}}), 'Laptop'), 'Waiting for Laptop')
  assert.equal(copyStatusLine(place({status: 'disconnected', device: undefined}), 'Laptop'), 'Waiting for Laptop')
  assert.equal(copyStatusLine(place({status: 'review', review: 3}), 'Laptop'), '3 files need a decision')
  assert.equal(copyStatusLine(place({status: 'review', review: 1}), 'Laptop'), '1 file needs a decision')
  assert.equal(copyStatusLine(place({status: 'paused'}), 'Laptop'), 'Paused')
})

test('the copy on a device is found by the device id, and only that one', () => {
  const copies = {available: true, places: [place({id: 'a', device: undefined}), place({id: 'b'})], rows: [], review: [], reviewMore: 0, syncing: false}
  assert.equal(copyOnDevice(copies, 'dev-laptop')?.id, 'b')
  assert.equal(copyOnDevice(copies, 'dev-phone'), undefined)
  assert.equal(copyOnDevice(undefined, 'dev-laptop'), undefined)
})
