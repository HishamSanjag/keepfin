// The desktop's own helpers. The shared decisions (skips, resume, chapter targets, next item, title order, the time
// label) are tested once for both apps by fixtures/engine/playback through gui/shared/fixtures.test.ts.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import type {LibraryItem} from '../../shared/library.js'
import {adjacentId, chapterAt, folderVideoIds, Lru, sleepRemaining, thumbBucket} from './videoLogic.js'

const video = (id: string, title: string, extra: Partial<LibraryItem> = {}) => ({id, title, space: 'videos', folder: 'Show', kind: 'video', ...extra}) as LibraryItem

test('a folder is its videos still on disk, in the shared title order; other folders, spaces and kinds left out', () => {
  const items = [
    video('a', 'Episode 10'),
    video('b', 'Episode 2'),
    video('c', 'episode 1'),
    video('d', 'Elsewhere', {folder: 'Other'}),
    video('e', 'Song', {kind: 'audio'}),
    video('f', 'Gone', {missing: true}),
    video('g', 'Episode 3', {space: 'downloads'}),
  ]
  assert.deepEqual(folderVideoIds(items, items[0]), ['c', 'b', 'a'])
})

test('next and previous skip ids that are gone', () => {
  const list = ['a', 'b', 'c', 'd']
  const exists = (id: string) => id !== 'c'
  assert.equal(adjacentId(list, 'b', 1, exists), 'd')
  assert.equal(adjacentId(list, 'd', -1, exists), 'b')
  assert.equal(adjacentId(list, 'a', -1, exists), undefined)
  assert.equal(adjacentId(list, 'd', 1, exists), undefined)
  assert.equal(adjacentId(list, 'zz', 1, exists), undefined)
})

test('chapter at a time returns the chapter itself', () => {
  const chapters = [{start: 0, title: 'Intro'}, {start: 60, title: 'Fjord'}, {start: 120, title: 'Aurora'}]
  assert.equal(chapterAt(chapters, 75)?.title, 'Fjord')
  assert.equal(chapterAt([{start: 5, title: 'Late'}], 1), undefined)
})

test('sleep chip rounds up to whole minutes', () => {
  assert.equal(sleepRemaining(29 * 60000 - 1), '29m')
  assert.equal(sleepRemaining(1000), '1m')
})

test('thumbnail buckets are 2 s and the cache drops the least recently used', () => {
  assert.equal(thumbBucket(0), 0)
  assert.equal(thumbBucket(3.9), 1)
  assert.equal(thumbBucket(4), 2)
  const evicted: number[] = []
  const lru = new Lru<number, number>(3, (value) => evicted.push(value))
  lru.set(1, 10)
  lru.set(2, 20)
  lru.set(3, 30)
  lru.get(1)
  lru.set(4, 40)
  assert.deepEqual(evicted, [20])
  assert.equal(lru.size, 3)
  assert.equal(lru.has(2), false)
  assert.equal(lru.get(1), 10)
})
