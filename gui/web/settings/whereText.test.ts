import {test} from 'node:test'
import assert from 'node:assert/strict'
import type {WhereHolds, WherePlace, WhereSingles} from '../../shared/where'
import {agoWords, canKeepCopy, copyLine, holdsWords, itemWords, onePlaceWords, sizeWords, singlesWords, statusTone, statusWords} from './whereText'

const NOW = 1_800_000_000_000
const holds = (patch: Partial<WhereHolds> = {}): WhereHolds => ({all: false, spaces: [], playlists: [], favourites: false, ...patch})
const place = (status: WherePlace['status'], link?: WherePlace['link']): Pick<WherePlace, 'status' | 'link'> => ({status, ...(link ? {link} : {})})

test('what a place holds, in words', () => {
  assert.equal(holdsWords(holds({all: true})), 'Everything')
  assert.equal(holdsWords(holds({spaces: ['Music'], favourites: true})), 'Music, Favourites')
  assert.equal(holdsWords(holds({playlists: ['Road trip']})), 'Road trip')
  assert.equal(holdsWords(holds({playlists: ['Road trip', 'Gym', 'Focus']})), 'Road trip and 2 more')
  assert.equal(holdsWords(holds({spaces: ['Music', 'Videos'], playlists: ['Gym'], favourites: true})), 'Music and 3 more')
  assert.equal(holdsWords(holds()), 'Nothing yet')
  // a device keeps whatever it has: how many of the library's files
  assert.equal(holdsWords(holds({files: {held: 40, of: 1200}})), '40 of 1,200 files')
  assert.equal(holdsWords(holds({files: {held: 1200, of: 1200}})), 'Everything')
  assert.equal(holdsWords(holds({files: {held: 0, of: 1200}})), 'Nothing yet')
  assert.equal(holdsWords(holds({files: {held: 1, of: 1}})), 'Everything')
})

test('time ago', () => {
  assert.equal(agoWords(NOW - 10_000, NOW), 'just now')
  assert.equal(agoWords(NOW - 2 * 60_000, NOW), '2 min ago')
  assert.equal(agoWords(NOW - 3 * 3600_000, NOW), '3 h ago')
  assert.equal(agoWords(NOW - 30 * 3600_000, NOW), 'yesterday')
  assert.equal(agoWords(NOW - 3 * 86_400_000, NOW), '3 days ago')
  assert.equal(agoWords(NOW - 21 * 86_400_000, NOW), '3 weeks ago')
  assert.equal(agoWords(NOW - 120 * 86_400_000, NOW), '4 months ago')
  assert.equal(agoWords(NOW + 5000, NOW), 'just now')
})

test('states', () => {
  assert.equal(statusWords(place({state: 'upToDate'}), NOW), 'up to date')
  assert.equal(statusWords(place({state: 'upToDate', at: NOW - 2 * 60_000}), NOW), 'up to date 2 min ago')
  assert.equal(statusWords(place({state: 'copying', done: 40, total: 210}), NOW), 'copying · 40 of 210')
  assert.equal(statusWords(place({state: 'copying', done: 0, total: 0}), NOW), 'copying')
  assert.equal(statusWords(place({state: 'waiting', reason: 'Waiting for Wi-Fi.'}), NOW), 'waiting for Wi-Fi')
  assert.equal(statusWords(place({state: 'waiting', reason: 'Paused'}), NOW), 'paused')
  assert.equal(statusWords(place({state: 'waiting', reason: 'A large change is waiting for you and it will not start until you say so.'}), NOW), 'waiting')
  assert.equal(statusWords(place({state: 'waiting'}), NOW), 'waiting')
  assert.equal(statusWords(place({state: 'offline', at: NOW - 3 * 86_400_000, unplugged: true}, {to: 'copy', id: 'p'}), NOW), 'last copied 3 days ago · not plugged in')
  assert.equal(statusWords(place({state: 'offline', unplugged: true}, {to: 'copy', id: 'p'}), NOW), 'not plugged in')
  assert.equal(statusWords(place({state: 'offline', at: NOW - 3 * 3600_000}, {to: 'copy', id: 'p'}), NOW), 'last copied 3 h ago · offline')
  assert.equal(statusWords(place({state: 'offline', at: NOW - 5 * 60_000}, {to: 'device', id: 'd'}), NOW), 'offline · last seen 5 min ago')
  assert.equal(statusWords(place({state: 'offline'}, {to: 'device', id: 'd'}), NOW), 'offline')
  assert.equal(statusWords(place({state: 'needsDecision'}), NOW), 'needs a decision')
  assert.equal(statusWords(place({state: 'problem', count: 50}), NOW), '50 files not copied')
  assert.equal(statusWords(place({state: 'problem', count: 1}), NOW), '1 file not copied')
  assert.equal(statusTone({state: 'problem', count: 50}), 'warn')
  assert.deepEqual(
    (['upToDate', 'copying', 'waiting', 'offline', 'needsDecision'] as const).map((state) => statusTone(state === 'copying' ? {state, done: 1, total: 2} : state === 'waiting' ? {state} : state === 'offline' ? {state} : state === 'upToDate' ? {state} : {state})),
    ['good', 'busy', 'quiet', 'warn', 'warn'],
  )
})

test('size: bytes when known, else how many files so far, else nothing', () => {
  assert.equal(sizeWords({bytes: 6.2 * 1024 ** 3, items: 100}), '6.2 GB')
  assert.equal(sizeWords({bytes: 0, items: 40}), '40 files')
  assert.equal(sizeWords({bytes: 0, items: 1}), '1 file')
  assert.equal(sizeWords({bytes: 0, items: 0}), '')
})

test('files only one place holds', () => {
  const single = (patch: Partial<WhereSingles>): WhereSingles => ({kind: 'videos', count: 12, placeId: 'this', placeName: 'Desk PC', ...patch})
  assert.equal(singlesWords(single({}), 'this PC'), '12 videos are only on this PC')
  assert.equal(singlesWords(single({kind: 'songs', count: 1, placeId: 'dev-phone', placeName: 'Pixel 9'}), 'this PC'), '1 song is only on Pixel 9')
  assert.equal(singlesWords(single({kind: 'other', count: 3}), 'this PC'), '3 other files are only on this PC')
  assert.equal(singlesWords(single({count: 1200}), 'this PC'), '1,200 videos are only on this PC')
  assert.equal(canKeepCopy(single({})), true)
  assert.equal(canKeepCopy(single({placeId: 'dev-phone'})), false)
})

test('the library in one place only', () => {
  assert.equal(onePlaceWords('this PC'), 'Everything is on this PC only. Keep a copy on another device, a drive or the cloud.')
})

test('the per-file line', () => {
  assert.equal(itemWords([{name: 'Desk PC', thisDevice: true, offline: false}, {name: 'Pixel 9', offline: true}, {name: 'Google Drive', offline: false}]), 'On: this PC, Pixel 9 (offline), Google Drive')
  assert.equal(itemWords([{name: 'Desk PC', thisDevice: true, offline: false}]), 'Only on this PC')
  assert.equal(itemWords([{name: 'Pixel 9', offline: true}]), 'Only on Pixel 9 (offline)')
  assert.equal(itemWords([]), 'Not on any device yet')
})

test('the second line of a copy says what it holds, where it is and how much of it is there', () => {
  const h = holds({spaces: ['Music']})
  assert.equal(copyLine({holds: h, location: 'G:\\My Drive\\Media\\Keepfin', files: {kept: 0, of: 95}}), 'Music · G:\\My Drive\\Media\\Keepfin · 0 of 95 files')
  assert.equal(copyLine({holds: h, location: 'E:\\', files: {kept: 95, of: 95}}), 'Music · E:\\ · 95 files')
  assert.equal(copyLine({holds: h}), 'Music')
})
