import {test} from 'node:test'
import assert from 'node:assert/strict'
import {
  ACTIVITY_MAX_AGE_MS,
  ACTIVITY_MAX_ENTRIES,
  activityActions,
  awayCount,
  awayMessage,
  batchDoneDetail,
  dayLabel,
  filterEntries,
  groupByDay,
  mergeSyncFound,
  noun,
  problemCount,
  pruneActivity,
  sanitizeEntry,
  settleSyncFound,
  syncFoundText,
  timeLabel,
  unreadCount,
  type ActivityEntry,
} from './activity.js'

const NOW = new Date(2026, 9, 10, 14, 30).getTime()
const HOUR = 3_600_000
const DAY = 86_400_000
const entry = (id: string, patch: Partial<ActivityEntry> = {}): ActivityEntry => ({id, kind: 'batch-done', at: NOW - HOUR, title: id, read: false, ...patch})

test('prunes to the newest 200 entries and nothing older than 30 days', () => {
  const many = Array.from({length: 230}, (_, n) => entry(`e${n}`, {at: NOW - n * 1000}))
  const kept = pruneActivity(many, NOW)
  assert.equal(kept.length, ACTIVITY_MAX_ENTRIES)
  assert.equal(kept[0].id, 'e0')
  assert.equal(kept.at(-1)!.id, 'e199')
  const aged = pruneActivity([entry('old', {at: NOW - ACTIVITY_MAX_AGE_MS - 1}), entry('edge', {at: NOW - ACTIVITY_MAX_AGE_MS}), entry('new')], NOW)
  assert.deepEqual(aged.map((item) => item.id), ['new', 'edge'])
})

test('day labels: Today, Yesterday, weekdays for the last week, then dates', () => {
  const at = (days: number, hours = 9) => new Date(2026, 9, 10 - days, hours).getTime()
  assert.equal(dayLabel(at(0, 0), NOW), 'Today')
  assert.equal(dayLabel(at(1, 23), NOW), 'Yesterday')
  assert.equal(dayLabel(at(2), NOW), 'Thursday')
  assert.equal(dayLabel(at(6), NOW), 'Sunday')
  assert.equal(dayLabel(at(7), NOW), '3 Oct')
  assert.equal(dayLabel(new Date(2025, 11, 25).getTime(), NOW), '25 Dec 2025')
})

test('groups newest first by day label', () => {
  const entries = [
    entry('a', {at: new Date(2026, 9, 9, 8).getTime()}),
    entry('b', {at: new Date(2026, 9, 10, 9).getTime()}),
    entry('c', {at: new Date(2026, 9, 10, 13).getTime()}),
    entry('d', {at: new Date(2026, 9, 1, 13).getTime()}),
  ]
  const days = groupByDay(entries, NOW)
  assert.deepEqual(days.map((day) => [day.label, day.entries.map((item) => item.id)]), [
    ['Today', ['c', 'b']],
    ['Yesterday', ['a']],
    ['1 Oct', ['d']],
  ])
})

test('counts unread and problems, and filters', () => {
  const entries = [
    entry('a'),
    entry('b', {read: true, kind: 'failed'}),
    entry('c', {kind: 'sign-in'}),
    entry('d', {read: true}),
  ]
  assert.equal(unreadCount(entries), 2)
  assert.equal(problemCount(entries), 2)
  assert.deepEqual(filterEntries(entries, 'unread').map((item) => item.id), ['a', 'c'])
  assert.deepEqual(filterEntries(entries, 'problems').map((item) => item.id), ['b', 'c'])
  assert.equal(filterEntries(entries, 'all').length, 4)
})

test('actions come from kind and target', () => {
  const id = (kind: ActivityEntry['kind'], target?: ActivityEntry['target']) => activityActions({kind, target}).map((a) => a.id)
  assert.deepEqual(id('sync-found', {collectionId: 'c'}), ['open'])
  assert.deepEqual(id('sync-found'), [])
  assert.deepEqual(id('sync-review', {collectionId: 'c'}), ['review'])
  assert.deepEqual(id('batch-done', {groupId: 'g'}), ['open'])
  assert.deepEqual(id('failed', {jobIds: ['j']}), ['retry', 'details'])
  assert.deepEqual(id('failed'), [])
  assert.deepEqual(id('app-updated', {version: '1'}), ['whats-new'])
  assert.deepEqual(id('update-ready', {version: '1'}), ['install'])
  assert.deepEqual(id('sign-in', {provider: 'YouTube'}), ['sign-in'])
})

test('away count is unread entries created after the app closed; never when nothing is new', () => {
  const entries = [entry('a', {at: 100}), entry('b', {at: 300}), entry('c', {at: 400, read: true}), entry('d', {at: 500})]
  assert.equal(awayCount(entries, 200), 2)
  assert.equal(awayCount(entries, 600), 0)
  assert.equal(awayCount(entries, undefined), 0)
  assert.equal(awayMessage(1), '1 thing happened while you were away')
  assert.equal(awayMessage(3), '3 things happened while you were away')
})

test('sync-found grows while its batch runs, then settles in place', () => {
  const first = syncFoundText('Gym Mix', 3, true, 'Workout')
  assert.equal(first.title, 'Gym Mix got 3 new songs')
  assert.equal(first.detail, 'Downloading to Workout')
  const base = entry('s', {kind: 'sync-found', ...first, count: 3})
  const merged = mergeSyncFound({...base, read: true}, 2, 'Gym Mix', true, 'Workout', NOW)
  assert.equal(merged.id, 's')
  assert.equal(merged.title, 'Gym Mix got 5 new songs')
  assert.equal(merged.count, 5)
  assert.equal(merged.read, false)
  assert.equal(settleSyncFound(merged, 5, 0).detail, 'All 5 saved')
  assert.equal(settleSyncFound(merged, 4, 1).detail, '4 saved, 1 failed')
  assert.equal(syncFoundText('Daily', 1, false).title, 'Daily got 1 new item')
})

test('wording uses songs for music and items otherwise', () => {
  assert.equal(noun(1, true), 'song')
  assert.equal(noun(24, true), 'songs')
  assert.equal(noun(2, false), 'items')
  assert.equal(batchDoneDetail(24, 24, true, 'Music'), '24 of 24 songs saved to Music')
  assert.equal(batchDoneDetail(2, 3, false), '2 of 3 items saved')
})

test('time labels', () => {
  assert.equal(timeLabel(NOW - 10_000, NOW), 'now')
  assert.equal(timeLabel(NOW - 5 * 60_000, NOW), '5 min')
  assert.equal(timeLabel(new Date(2026, 9, 10, 9, 5).getTime(), NOW), '9:05 AM')
  assert.equal(timeLabel(NOW - 2 * DAY, NOW).includes('M'), true)
})

test('stored entries are validated', () => {
  assert.equal(sanitizeEntry(null), undefined)
  assert.equal(sanitizeEntry({id: 'x', kind: 'nope', at: 1, title: 't'}), undefined)
  assert.equal(sanitizeEntry({id: 'x', kind: 'failed', at: 'soon', title: 't'}), undefined)
  const ok = sanitizeEntry({id: 'x', kind: 'failed', at: 5, title: ' t ', read: 1, target: {jobIds: ['a', 7, 'b'], junk: true}, extra: 1})
  assert.deepEqual(ok, {id: 'x', kind: 'failed', at: 5, title: 't', read: false, target: {jobIds: ['a', 'b']}})
})
