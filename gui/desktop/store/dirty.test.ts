import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import path from 'node:path'
import type {LibraryEntry} from '../library/index.js'
import {ItemTracker} from './dirty.js'
import {ITEM_WIRE_NAMES} from './mapping.js'

const root = path.join(path.sep, 'k')

function make(n: number): LibraryEntry {
  return {
    id: randomUUID(),
    space: 'music',
    path: path.join(root, 'Music', `Track ${n}.mp3`),
    kind: 'audio',
    title: `Track ${n}`,
    artist: `Artist ${n % 7}`,
    duration: 100.5,
    size: 1000 + n,
    managed: true,
  } as LibraryEntry
}

function tracked(count: number) {
  const tracker = new ItemTracker()
  const items = Array.from({length: count}, (_, n) => make(n))
  tracker.rebuild(items)
  return {tracker, items}
}

const names = (indexes: readonly number[]) => indexes.map((i) => ITEM_WIRE_NAMES[i])

test('an unchanged library scans as unchanged, by id and in full', () => {
  const {tracker, items} = tracked(50)
  for (const scan of [tracker.scan(items), tracker.scanIds(items.map((e) => e.id), (id) => items.find((e) => e.id === id))]) {
    assert.deepEqual([scan.added.length, scan.synced.length, scan.device.length, scan.removed.length], [0, 0, 0, 0])
  }
})

test('only the changed fields are reported, with the old play count and flags', () => {
  const {tracker, items} = tracked(10)
  const e = items[3]
  e.title = 'Renamed'
  e.genre = 'Jazz'
  e.plays = 4
  e.favorite = true
  const scan = tracker.scan(items)
  assert.deepEqual(scan.synced, [e])
  const change = scan.changes.get(e.id)!
  assert.deepEqual(names(change.fields), ['title', 'genre'])
  assert.equal(change.playsWas, 0)
  assert.equal(change.favoriteWas, false)
  assert.equal(change.place, false)
  assert.equal(change.tagsWas, undefined)
  // The change was taken: a second scan sees nothing.
  assert.equal(tracker.scan(items).synced.length, 0)
  // A field that went away is reported (its new value is absent).
  delete (e as Partial<LibraryEntry>).genre
  assert.deepEqual(names(tracker.scan(items).changes.get(e.id)!.fields), ['genre'])
})

test('a moved file is a placement change, a device-only change is not a synced one', () => {
  const {tracker, items} = tracked(5)
  items[0].path = path.join(root, 'Music', 'Moved', 'a.mp3')
  items[1].missing = true
  items[2].size = 5
  const scan = tracker.scanIds(items.map((e) => e.id), (id) => items.find((e) => e.id === id))
  assert.deepEqual(scan.synced, [items[0]])
  assert.equal(scan.changes.get(items[0].id)!.place, true)
  assert.deepEqual(new Set(scan.device), new Set([items[1], items[2]]))
  assert.equal(tracker.summary().get('music')!.missing, 1)
  assert.equal(tracker.summary().get('music')!.bytes, 1000 + 1001 + 5 + 1003 + 1004)
})

test('user tags: the previous tags are reported only when the set changed', () => {
  const {tracker, items} = tracked(4)
  const e = items[1]
  e.userTags = ['b', 'a']
  let scan = tracker.scan(items)
  assert.deepEqual(scan.changes.get(e.id)!.tagsWas, [])
  e.userTags = ['a', 'b', 'a'] // a new array, the same set
  scan = tracker.scan(items)
  assert.equal(scan.synced.length, 0)
  e.userTags = ['a']
  scan = tracker.scan(items)
  assert.deepEqual(scan.changes.get(e.id)!.tagsWas, ['a', 'b'])
  assert.deepEqual(tracker.get(e.id, 'userTags'), ['a'])
})

test('added, removed and unmanaged entries', () => {
  const {tracker, items} = tracked(6)
  const extra = make(99)
  const gone = items[2]
  const rest = items.filter((e) => e !== gone)
  rest[0].managed = false
  const scan = tracker.scan([...rest, extra])
  assert.deepEqual(scan.added, [extra])
  assert.deepEqual(new Set(scan.removed), new Set([gone.id, rest[0].id]))
  assert.equal(tracker.has(gone.id), false)
  assert.equal(tracker.has(extra.id), true)
  // A freed slot is reused without mixing entries up.
  const again = make(100)
  assert.deepEqual(tracker.scan([...rest.slice(1), extra, again]).added, [again])
  assert.equal(tracker.size, rest.length - 1 + 2)
})

test('the hashes of 100,000 random single-field edits are all seen (no hidden change)', () => {
  const {tracker, items} = tracked(2000)
  let seen = 0
  for (let round = 0; round < 100_000; round++) {
    const e = items[round % items.length]
    e.title = `Title ${round} ${Math.imul(round, 2654435761) >>> 0}`
    const scan = tracker.scanIds([e.id], () => e)
    if (scan.changes.get(e.id)?.fields.length === 1) seen++
  }
  assert.equal(seen, 100_000)
})
