import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {PlaylistEntry} from '../shared/contracts.js'
import {defaultLibrarySettings, entryKey} from '../shared/library.js'
import {FollowEngine, FOLLOW_LAUNCH_DELAY_MS, FOLLOW_TICK_MS, isDue, type FollowClock, type FollowHost, type FollowNotice} from './library/follow.js'
import {LibraryIndex} from './library/index.js'

const HOUR = 3_600_000
const id = (n: number) => `${String.fromCharCode(97 + (n % 26))}${String(n).padStart(10, '0')}`
const entry = (n: number): PlaylistEntry => ({url: `https://www.youtube.com/watch?v=${id(n)}`, title: `Song ${n}`, position: n + 1})
const key = (n: number) => entryKey(entry(n).url)

/** A clock the test moves by hand. */
class ManualClock implements FollowClock {
  time = 1_000_000_000
  timers: {at: number; every?: number; run: () => void; live: boolean}[] = []
  now() {
    return this.time
  }
  every(ms: number, run: () => void) {
    const timer = {at: this.time + ms, every: ms, run, live: true}
    this.timers.push(timer)
    return () => void (timer.live = false)
  }
  after(ms: number, run: () => void) {
    const timer = {at: this.time + ms, run, live: true}
    this.timers.push(timer)
    return () => void (timer.live = false)
  }
  /** Moves time forward, firing timers in order, and waits for the work they start. */
  async advance(ms: number) {
    const end = this.time + ms
    for (;;) {
      const next = this.timers.filter((timer) => timer.live && timer.at <= end).sort((a, b) => a.at - b.at)[0]
      if (!next) break
      this.time = next.at
      if (next.every) next.at += next.every
      else next.live = false
      next.run()
      await new Promise((resolve) => setTimeout(resolve, 15))
    }
    this.time = end
  }
}

class FakeHost implements FollowHost {
  metadataEpoch = 0
  listing: PlaylistEntry[] = []
  partial = false
  failure?: Error
  onDiscover?: () => void
  discovered: {url: string; cap: number}[] = []
  enqueued: {collectionId: string; entries: PlaylistEntry[]}[] = []
  constructor(private readonly index: LibraryIndex) {}
  async followDiscover(url: string, cap: number) {
    this.discovered.push({url, cap})
    this.onDiscover?.()
    if (this.failure) throw this.failure
    return {entries: [...this.listing], truncated: false, partial: this.partial}
  }
  async followEnqueue(collectionId: string, entries: PlaylistEntry[]) {
    this.enqueued.push({collectionId, entries})
    this.index.noteSeen(collectionId, entries.map((item) => entryKey(item.url)))
    return entries.length
  }
}

async function setup() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-follow-'))
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
  for (const space of settings.spaces.filter((item) => item.type === 'folder')) await fs.mkdir(space.folder, {recursive: true})
  const index = new LibraryIndex({file: path.join(dir, 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => undefined, saveDelay: 10, pushDelay: 5, watch: false, analyse: false})
  await index.init()
  index.configure(settings)
  const clock = new ManualClock()
  const host = new FakeHost(index)
  const notices: FollowNotice[] = []
  const engine = new FollowEngine({index, host, clock, notify: (notice) => notices.push(notice)})
  const url = 'https://www.youtube.com/playlist?list=PLfollow'
  const collection = index.ensureCollection({url, name: 'Gym Mix', kind: 'playlist', space: 'music', keepInSyncHours: 6})
  index.updateCollection(collection.id, (current) => (current.follow!.lastCheckedAt = clock.time - 7 * HOUR))
  return {dir, index, clock, host, notices, engine, collection, url, cleanup: async () => {
    engine.stop()
    await index.close()
    await fs.rm(dir, {recursive: true, force: true})
  }}
}

test('only ids that were never seen are downloaded: deselected and deleted items stay out', async () => {
  const f = await setup()
  try {
    // 0 was downloaded, 1 was deselected in the review, 2 was downloaded and then deleted by the user
    f.index.noteSeen(f.collection.id, [key(0), key(1), key(2)])
    f.host.listing = [0, 1, 2, 3, 4].map(entry)
    const result = await f.engine.check(f.collection.id)
    assert.deepEqual(f.host.enqueued.map((call) => call.entries.map((item) => item.title)), [['Song 3', 'Song 4']])
    assert.deepEqual([result.added, result.pending, result.error], [2, undefined, undefined])
    const collection = f.index.collection(f.collection.id)
    assert.deepEqual(collection.seen, [key(0), key(1), key(2), key(3), key(4)])
    assert.equal(collection.follow!.lastCheckedAt, f.clock.time)
    assert.equal(collection.follow!.lastError, undefined)
    // checking again finds nothing new and queues nothing
    const again = await f.engine.check(f.collection.id)
    assert.equal(again.added, 0)
    assert.equal(f.host.enqueued.length, 1)
    assert.equal(f.host.discovered[0].cap, 1000, 'discovery is capped at 1000 entries')
    assert.equal(f.host.discovered[0].url, 'https://www.youtube.com/playlist?list=PLfollow')
  } finally {
    await f.cleanup()
  }
})

test('more new items than confirmAbove wait for review with one notification; review downloads them', async () => {
  const f = await setup()
  try {
    f.host.listing = Array.from({length: 28}, (_, n) => entry(n))
    const held = await f.engine.check(f.collection.id)
    assert.deepEqual([held.added, held.pending], [0, 28])
    assert.equal(f.host.enqueued.length, 0)
    assert.equal(f.index.collection(f.collection.id).follow!.pending!.count, 28)
    assert.deepEqual(f.notices.map((notice) => [notice.collectionId, notice.body]), [[f.collection.id, '28 new in Gym Mix · Review']])
    assert.equal(f.index.collection(f.collection.id).seen.length, 0, 'held items are not marked seen')
    // the schedule does not nag while something waits, and a manual check does not notify twice
    await f.engine.check(f.collection.id)
    assert.equal(f.notices.length, 1)
    assert.equal(isDue(f.index.collection(f.collection.id), f.clock.time + 30 * HOUR), false)
    const reviewed = await f.engine.review(f.collection.id)
    assert.equal(reviewed.added, 28)
    assert.equal(f.host.enqueued[0].entries.length, 28)
    const after = f.index.collection(f.collection.id)
    assert.equal(after.follow!.pending, undefined)
    assert.equal(after.seen.length, 28)
    assert.throws(() => f.engine.review(f.collection.id), /Nothing is waiting/)
  } finally {
    await f.cleanup()
  }
})

test('confirmAbove is honoured exactly and notifications can be turned off per collection', async () => {
  const f = await setup()
  try {
    f.index.setFollow(f.collection.id, {confirmAbove: 3, notify: false})
    f.host.listing = [0, 1, 2].map(entry)
    assert.equal((await f.engine.check(f.collection.id)).added, 3, 'exactly confirmAbove goes straight through')
    f.host.listing = [0, 1, 2, 3, 4, 5, 6].map(entry)
    const held = await f.engine.check(f.collection.id)
    assert.equal(held.pending, 4)
    assert.equal(f.notices.length, 0, 'notify is off')
  } finally {
    await f.cleanup()
  }
})

test('items that left the source are recorded and their files stay; a partial listing never concludes anything', async () => {
  const f = await setup()
  try {
    const files: string[] = []
    for (const n of [0, 1, 2]) {
      const file = path.join(f.dir, 'Music', `Song ${n}.mp3`)
      await fs.writeFile(file, 'x')
      files.push(file)
      const job = {id: `j${n}`, url: entry(n).url, title: `Song ${n}`, status: 'completed', file, createdAt: 1, completedAt: 2, space: 'music', collectionId: f.collection.id, collectionIndex: n, kind: 'audio', format: {type: 'audio'}, options: {}, label: 'x', percent: 100}
      await f.index.adoptJob(job as never)
    }
    f.index.noteSeen(f.collection.id, [key(0), key(1), key(2)])
    f.host.listing = [0, 2].map(entry)
    f.host.partial = true
    assert.equal((await f.engine.check(f.collection.id)).removed, 0)
    assert.equal(f.index.collection(f.collection.id).removedItemIds, undefined, 'a cut-short listing proves nothing')
    f.host.partial = false
    const result = await f.engine.check(f.collection.id)
    assert.equal(result.removed, 1)
    const collection = f.index.collection(f.collection.id)
    const removed = collection.removedItemIds!.map((itemId) => f.index.item(itemId).title)
    assert.deepEqual(removed, ['Song 1'])
    assert.equal(collection.itemIds.length, 3, 'it is still a member')
    for (const file of files) assert.ok(await fs.stat(file), 'files are never deleted')
    // it comes back upstream: no longer marked removed
    f.host.listing = [0, 1, 2].map(entry)
    await f.engine.check(f.collection.id)
    assert.equal(f.index.collection(f.collection.id).removedItemIds, undefined)
  } finally {
    await f.cleanup()
  }
})

test('errors are recorded, friendly and not retried before the next interval', async () => {
  const f = await setup()
  try {
    f.host.failure = new Error('yt-dlp failed: HTTP Error 429 while ffmpeg ran')
    const result = await f.engine.check(f.collection.id)
    assert.ok(result.error)
    assert.doesNotMatch(result.error!, /yt-dlp|ffmpeg/i, 'no tool names reach the user')
    const failed = f.index.collection(f.collection.id)
    assert.equal(failed.follow!.lastError, result.error)
    assert.equal(failed.follow!.lastCheckedAt, f.clock.time)
    assert.equal(isDue(failed, f.clock.time + HOUR), false)
    assert.equal(isDue(failed, f.clock.time + 6 * HOUR), true)
    // a later success clears the message
    f.host.failure = undefined
    f.host.listing = [entry(0)]
    await f.engine.check(f.collection.id)
    assert.equal(f.index.collection(f.collection.id).follow!.lastError, undefined)
    // the queue refusing the items is also an error, and nothing is marked seen
    f.host.listing = [entry(0), entry(9)]
    f.host.followEnqueue = async () => {
      throw new Error('Queue is full. Wait for some downloads to finish.')
    }
    const full = await f.engine.check(f.collection.id)
    assert.match(full.error!, /Queue is full/)
    assert.ok(!f.index.collection(f.collection.id).seen.includes(key(9)))
  } finally {
    await f.cleanup()
  }
})

test('a cookie or proxy change during a check discards it and records nothing', async () => {
  const f = await setup()
  try {
    const before = JSON.stringify(f.index.collection(f.collection.id))
    f.host.listing = [0, 1].map(entry)
    f.host.onDiscover = () => void f.host.metadataEpoch++
    const result = await f.engine.check(f.collection.id)
    assert.equal(result.skipped, true)
    assert.equal(f.host.enqueued.length, 0)
    assert.equal(JSON.stringify(f.index.collection(f.collection.id)), before)
    // the discovery itself failing because the change aborted it is also not an error
    f.host.onDiscover = () => {
      f.host.metadataEpoch++
      f.host.failure = new Error('Cookies changed. Inspect the link again.')
    }
    assert.equal((await f.engine.check(f.collection.id)).skipped, true)
    assert.equal(f.index.collection(f.collection.id).follow!.lastError, undefined)
  } finally {
    await f.cleanup()
  }
})

test('the schedule: an overdue check shortly after launch, then every 15 minutes for what is due', async () => {
  const f = await setup()
  try {
    // due: auto, 7 h since the last check; not due: manual, off, an own list, a mix, a collection checked just now
    const other = (name: string, url: string, mode: 'auto' | 'manual' | 'off', ago: number) => {
      const made = f.index.ensureCollection({url, name, kind: 'playlist', space: 'music'})
      f.index.updateCollection(made.id, (current) => {
        current.follow = {...current.follow!, mode, lastCheckedAt: f.clock.time - ago}
      })
      return made.id
    }
    const manual = other('Manual', 'https://www.youtube.com/playlist?list=PLmanual', 'manual', 90 * HOUR)
    const off = other('Off', 'https://www.youtube.com/playlist?list=PLoff', 'off', 90 * HOUR)
    const fresh = other('Fresh', 'https://www.youtube.com/playlist?list=PLfresh', 'auto', 2 * HOUR)
    const own = f.index.saveCollection({name: 'Own'}).id
    const mix = f.index.ensureCollection({url: 'https://www.youtube.com/watch?v=aaaaaaaaaaa&list=RDaaaaaaaaaaa', name: 'Mix', kind: 'playlist', space: 'music'})
    f.index.updateCollection(mix.id, (current) => {
      current.follow = {...current.follow!, mode: 'auto', lastCheckedAt: 0}
    })
    f.host.listing = [entry(0)]
    f.engine.start()
    await f.clock.advance(FOLLOW_LAUNCH_DELAY_MS - 1000)
    assert.equal(f.host.discovered.length, 0, 'nothing runs before the launch delay')
    await f.clock.advance(2000)
    assert.deepEqual(f.host.discovered.map((call) => call.url), ['https://www.youtube.com/playlist?list=PLfollow'])
    assert.deepEqual(f.host.enqueued.map((call) => call.collectionId), [f.collection.id])
    // the tick finds nothing due until the intervals pass
    await f.clock.advance(FOLLOW_TICK_MS * 2)
    assert.equal(f.host.discovered.length, 1)
    await f.clock.advance(4 * HOUR)
    assert.equal(f.host.discovered.length, 2, 'the fresh collection became due after its 6 hours')
    assert.equal(f.host.discovered[1].url, 'https://www.youtube.com/playlist?list=PLfresh')
    for (const id of [manual, off, own, mix.id]) assert.ok(!f.host.enqueued.some((call) => call.collectionId === id))
    assert.ok(!f.host.discovered.some((call) => /RD|manual|PLoff/.test(call.url)))
    // stopping ends the schedule
    f.engine.stop()
    await f.clock.advance(100 * HOUR)
    assert.equal(f.host.discovered.length, 2)
  } finally {
    await f.cleanup()
  }
})

test('Check now works in any follow mode except for endless mixes and own lists, and runs one check at a time', async () => {
  const f = await setup()
  try {
    f.index.setFollow(f.collection.id, {mode: 'off'})
    f.host.listing = [entry(0)]
    const [one, two] = await Promise.all([f.engine.check(f.collection.id), f.engine.check(f.collection.id)])
    assert.equal(f.host.discovered.length, 1, 'a check already running is shared')
    assert.equal(one, two)
    assert.equal(one.added, 1)
    const own = f.index.saveCollection({name: 'Own'})
    assert.match((await f.engine.check(own.id)).error!, /downloaded from a link/)
    const mix = f.index.ensureCollection({url: 'https://www.youtube.com/watch?v=aaaaaaaaaaa&list=RDaaaaaaaaaaa', name: 'Mix', kind: 'playlist', space: 'music'})
    assert.match((await f.engine.check(mix.id)).error!, /never end/)
    assert.match((await f.engine.check('missing')).error!, /no longer exists/)
    assert.equal(f.host.discovered.length, 1)
    // checkDue with the library off does nothing
    f.index.configure({...defaultLibrarySettings({downloads: f.dir, music: f.dir, videos: f.dir}), enabled: false})
    assert.deepEqual(await f.engine.checkDue(), [])
  } finally {
    await f.cleanup()
  }
})

test('Activity hooks: a queued check reports once, a held check reports when its count changes', async () => {
  const f = await setup()
  try {
    const queued: unknown[] = []
    const held: unknown[] = []
    const engine = new FollowEngine({
      index: f.index,
      host: f.host,
      clock: f.clock,
      notify: () => {},
      onQueued: (item) => queued.push(item),
      onHeld: (item) => held.push(item),
    })
    f.host.listing = Array.from({length: 28}, (_, n) => entry(n))
    await engine.check(f.collection.id)
    await engine.check(f.collection.id)
    assert.deepEqual(held, [{id: f.collection.id, name: 'Gym Mix', count: 28, limit: 20, space: 'music'}])
    assert.equal(queued.length, 0)
    await engine.review(f.collection.id)
    assert.deepEqual(queued, [{id: f.collection.id, name: 'Gym Mix', added: 28, space: 'music'}])
    await engine.check(f.collection.id)
    assert.equal(queued.length, 1, 'a check that finds nothing reports nothing')
  } finally {
    await f.cleanup()
  }
})
