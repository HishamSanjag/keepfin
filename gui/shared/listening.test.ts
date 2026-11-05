import {test} from 'node:test'
import assert from 'node:assert/strict'
import {listeningRows, MIN_LISTENING_ROW} from './listening.js'
import type {LibraryItem} from './library.js'

const DAY = 86_400_000
const now = Date.UTC(2026, 9, 12)
let n = 0
const song = (extra: Partial<LibraryItem> = {}): LibraryItem => ({
  id: `s${n++}`,
  space: 'music',
  path: `C:/Music/s${n}.mp3`,
  folder: '',
  kind: 'audio',
  title: `Song ${n}`,
  size: 1,
  modifiedAt: now,
  addedAt: now - n * DAY,
  hasArt: false,
  ...extra,
})
const many = (count: number, extra: Partial<LibraryItem> = {}) => Array.from({length: count}, () => song(extra))
const ids = (rows: ReturnType<typeof listeningRows>, id: string) => rows.find((row) => row.id === id)?.items.map((item) => item.id)

test('rows with fewer than five songs are hidden', () => {
  const rows = listeningRows(many(MIN_LISTENING_ROW - 1, {plays: 3, lastPlayedAt: now - DAY}), now)
  assert.deepEqual(rows, [])
})

test('on repeat: played in the last 30 days, most plays first', () => {
  const recent = many(6, {lastPlayedAt: now - 2 * DAY})
  recent.forEach((item, index) => (item.plays = index + 1))
  const old = song({plays: 99, lastPlayedAt: now - 40 * DAY})
  const rows = listeningRows([...recent, old], now)
  const repeat = ids(rows, 'on-repeat')!
  assert.equal(repeat.length, 6)
  assert.equal(repeat[0], recent[5].id)
  assert.ok(!repeat.includes(old.id))
})

test('forgotten favorites: favorites or 5+ plays, not played for 60 days', () => {
  const faves = many(3, {favorite: true, lastPlayedAt: now - 90 * DAY})
  const loud = many(2, {plays: 8, lastPlayedAt: now - 70 * DAY})
  const fresh = song({favorite: true, lastPlayedAt: now - 5 * DAY})
  const rows = listeningRows([...faves, ...loud, fresh], now)
  const forgotten = ids(rows, 'forgotten')!
  assert.equal(forgotten.length, 5)
  assert.ok(!forgotten.includes(fresh.id))
})

test('never played and recently added come from the library, newest first; videos and missing files are ignored', () => {
  const unplayed = many(6)
  const video = song({kind: 'video'})
  const gone = song({missing: true})
  const rows = listeningRows([...unplayed, video, gone, song({plays: 2, lastPlayedAt: now})], now)
  const never = ids(rows, 'never')!
  assert.equal(never.length, 6)
  assert.ok(!never.includes(video.id) && !never.includes(gone.id))
  assert.deepEqual(never, unplayed.slice().sort((a, b) => b.addedAt - a.addedAt).map((item) => item.id))
  assert.ok(ids(rows, 'recent'))
})

test('rows are capped at 30 songs', () => {
  const rows = listeningRows(many(80), now)
  assert.equal(ids(rows, 'never')!.length, 30)
})
