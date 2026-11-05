import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import type {Job, Snapshot} from '../shared/contracts.js'
import {ACTIVITY_MAX_ENTRIES} from '../shared/activity.js'
import {ActivityRecorder, ActivityStore} from './activity.js'
import {DownloadManager} from './manager.js'
import {FakeEngine, fakeMedia, finishImmediately} from './fake-engine.js'

async function waitFor(check: () => boolean, label = 'condition') {
  for (let i = 0; i < 400; i++) {
    if (check()) return
    await delay(10)
  }
  throw new Error(`Timed out waiting for ${label}`)
}

async function setup(options: {focused?: boolean; retryDelays?: number[]} = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-activity-'))
  const engine = new FakeEngine()
  const manager = new DownloadManager({
    store: path.join(dir, 'queue.json'),
    defaultOutDir: path.join(dir, 'downloads'),
    engine,
    snapshotDelay: 5,
    progressInterval: 20,
    retryDelays: options.retryDelays ?? [10, 10, 10],
  })
  await manager.init()
  const store = new ActivityStore({file: path.join(dir, 'activity.json'), delay: 5})
  await store.init()
  const state = {focused: options.focused ?? true}
  const recorder = new ActivityRecorder({store, focused: () => state.focused})
  recorder.seed(manager.snapshot())
  manager.subscribe((event) => {
    if (event.type === 'snapshot') recorder.consume(event.snapshot)
  })
  return {
    dir,
    engine,
    manager,
    store,
    recorder,
    state,
    cleanup: async () => {
      await manager.close()
      await store.close()
      await fs.rm(dir, {recursive: true, force: true})
    },
  }
}
type Fixture = Awaited<ReturnType<typeof setup>>

const settled = (f: Fixture) => f.manager.snapshot().jobs.every((job) => ['completed', 'failed', 'cancelled'].includes(job.status))
async function discover(f: Fixture, id = 'g1', count = 4) {
  f.engine.discoveryTotal = count
  f.manager.startDiscovery('https://www.youtube.com/playlist?list=PL123', id)
  await waitFor(() => f.manager.discoveryState(id).state === 'completed', 'discovery')
  return f.manager.discoveryState(id).entries.map((entry) => entry.url)
}
async function single(f: Fixture, name: string) {
  const url = `https://example.com/${name}`
  f.engine.inspectResult = (u) => ({type: 'media', media: fakeMedia(u, {title: name})})
  await f.manager.inspect(url)
  await f.manager.enqueue(url, {type: 'video', codec: 'auto', container: 'mp4'})
}
const networkDrop = () => {
  throw new Error('ERROR: unable to download video data: Connection reset by peer (ECONNRESET)')
}

test('a finished batch is recorded once, never per item', async () => {
  const f = await setup()
  try {
    const urls = await discover(f, 'g1', 4)
    await f.manager.enqueueDiscovered('g1', urls, '720', undefined, true)
    await waitFor(() => settled(f) && f.store.all().length > 0, 'batch entry')
    await delay(60)
    const entries = f.store.all()
    assert.equal(entries.length, 1)
    assert.equal(entries[0].kind, 'batch-done')
    assert.equal(entries[0].title, 'Mix finished')
    assert.match(entries[0].detail!, /^4 of 4 items saved/)
    assert.ok(entries[0].target?.groupId)
    assert.equal(entries[0].read, false)
  } finally {
    await f.cleanup()
  }
})

test('a single download that finishes in front of the user is not recorded; unfocused it is', async () => {
  const f = await setup({focused: true})
  try {
    await single(f, 'watched')
    await waitFor(() => settled(f), 'download')
    await delay(60)
    assert.equal(f.store.all().length, 0)
    f.state.focused = false
    await single(f, 'unwatched')
    await waitFor(() => f.store.all().length === 1, 'unfocused entry')
    assert.equal(f.store.all()[0].title, 'unwatched saved')
  } finally {
    await f.cleanup()
  }
})

test('a failure is recorded once, after the automatic retries are used up, not while retrying', async () => {
  const f = await setup({retryDelays: [40, 40, 40]})
  try {
    f.engine.script = async () => networkDrop()
    await single(f, 'down')
    await waitFor(() => !!f.manager.snapshot().jobs[0].retry?.at, 'retry wait')
    assert.equal(f.store.all().length, 0)
    await waitFor(() => f.manager.snapshot().jobs[0].status === 'failed', 'final failure')
    await waitFor(() => f.store.all().length === 1, 'failed entry')
    await delay(80)
    const entries = f.store.all()
    assert.equal(entries.length, 1)
    assert.equal(entries[0].kind, 'failed')
    assert.equal(entries[0].title, 'down failed')
    assert.deepEqual(entries[0].target?.jobIds, [f.manager.snapshot().jobs[0].id])
  } finally {
    await f.cleanup()
  }
})

test('failed items of a batch make one failed entry plus the finished batch', async () => {
  const f = await setup({retryDelays: [5, 5, 5]})
  try {
    let n = 0
    f.engine.script = async (task, ctx, events) => {
      if (task.title.endsWith('2') || task.title.endsWith('3')) networkDrop()
      n++
      return finishImmediately(task, ctx, events)
    }
    const urls = await discover(f, 'g1', 4)
    await f.manager.enqueueDiscovered('g1', urls, '720', undefined, true)
    await waitFor(() => settled(f) && f.store.all().length >= 2, 'batch entries')
    await delay(60)
    const kinds = f.store.all().map((entry) => entry.kind).sort()
    assert.deepEqual(kinds, ['batch-done', 'failed'])
    const failed = f.store.all().find((entry) => entry.kind === 'failed')!
    assert.equal(failed.count, 2)
    assert.match(failed.title, /^2 items from Mix failed$/)
    assert.equal(failed.target?.jobIds?.length, 2)
    assert.match(f.store.all().find((entry) => entry.kind === 'batch-done')!.detail!, /^2 of 4 items saved/)
  } finally {
    await f.cleanup()
  }
})

test('a sign-in failure records one sign-in entry per service while it is unread', async () => {
  const f = await setup({retryDelays: [5, 5, 5]})
  try {
    f.engine.script = async () => {
      throw new Error('ERROR: [youtube] abc: Private video. Sign in if you have been granted access')
    }
    await single(f, 'private-one')
    await single(f, 'private-two')
    await waitFor(() => f.manager.snapshot().jobs.every((job) => job.status === 'failed'), 'both failed')
    await delay(80)
    const signIns = f.store.all().filter((entry) => entry.kind === 'sign-in')
    assert.equal(signIns.length, 1)
    assert.match(signIns[0].title, /^Sign in to .+ again$/)
    assert.equal(f.store.all().filter((entry) => entry.kind === 'failed').length, 0)
  } finally {
    await f.cleanup()
  }
})

// ---------------------------------------------------------------- followed collections (hand-made snapshots)

function job(patch: Partial<Job> & {id: string}): Job {
  return {
    url: `https://example.com/${patch.id}`,
    title: patch.id,
    label: 'MP3',
    kind: 'audio',
    format: {type: 'audio', target: 'mp3', quality: '320'} as Job['format'],
    options: {} as Job['options'],
    status: 'queued',
    percent: 0,
    createdAt: 1,
    ...patch,
  }
}
function snapshotOf(jobs: Job[]): Snapshot {
  return {
    jobs,
    settings: {library: {spaces: [{id: 'workout', name: 'Workout', kind: 'music'}]}} as unknown as Snapshot['settings'],
    engine: {tools: [], busy: false},
    platform: 'desktop',
    hasCookieFile: false,
  }
}
const group = {id: 'grp-1', title: 'Gym Mix', url: 'https://example.com/list'}

test('sync-found updates in place: it grows while the batch runs and settles when it ends', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-activity-'))
  try {
    const store = new ActivityStore({file: path.join(dir, 'activity.json'), delay: 5})
    await store.init()
    const recorder = new ActivityRecorder({store, focused: () => true})
    recorder.seed(snapshotOf([]))
    const first = [1, 2, 3].map((n) => job({id: `a${n}`, group, collectionId: 'col', space: 'workout', createdAt: 10 + n}))
    recorder.syncFound({id: 'col', name: 'Gym Mix', added: 3}, snapshotOf(first))
    recorder.consume(snapshotOf(first))
    let entries = store.all()
    assert.equal(entries.length, 1)
    assert.equal(entries[0].kind, 'sync-found')
    assert.equal(entries[0].title, 'Gym Mix got 3 new songs')
    assert.equal(entries[0].detail, 'Downloading to Workout')
    const id = entries[0].id

    // A second check queues two more while the first batch still runs.
    const group2 = {...group, id: 'grp-2'}
    const second = [4, 5].map((n) => job({id: `a${n}`, group: group2, collectionId: 'col', space: 'workout', createdAt: 100 + n}))
    recorder.syncFound({id: 'col', name: 'Gym Mix', added: 2}, snapshotOf([...first, ...second]))
    entries = store.all()
    assert.equal(entries.length, 1)
    assert.equal(entries[0].id, id)
    assert.equal(entries[0].title, 'Gym Mix got 5 new songs')

    const done = (jobs: Job[], failed = 0) => jobs.map((item, n) => ({...item, status: (n < failed ? 'failed' : 'completed') as Job['status']}))
    recorder.consume(snapshotOf([...done(first), ...second]))
    assert.equal(store.get(id)!.detail, 'Downloading to Workout')
    recorder.consume(snapshotOf([...done(first), ...done(second, 1)]))
    entries = store.all()
    assert.equal(store.get(id)!.detail, '4 saved, 1 failed')
    // Its batches never add a separate "finished" entry; the failure is its own entry.
    assert.deepEqual(entries.map((entry) => entry.kind).sort(), ['failed', 'sync-found'])
    await store.close()
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('items held for review make one sync-review entry that the review replaces', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-activity-'))
  try {
    const store = new ActivityStore({file: path.join(dir, 'activity.json'), delay: 5})
    await store.init()
    const recorder = new ActivityRecorder({store, focused: () => true})
    recorder.seed(snapshotOf([]))
    recorder.syncHeld({id: 'col', name: 'Lo-fi Study', count: 31, limit: 20, space: 'workout'}, snapshotOf([]))
    recorder.syncHeld({id: 'col', name: 'Lo-fi Study', count: 33, limit: 20, space: 'workout'}, snapshotOf([]))
    let entries = store.all()
    assert.equal(entries.length, 1)
    assert.equal(entries[0].kind, 'sync-review')
    assert.equal(entries[0].title, 'Lo-fi Study has 33 new songs waiting')
    assert.equal(entries[0].detail, 'More than 20, so they wait for you')
    const items = [job({id: 'b1', group, collectionId: 'col', space: 'workout'})]
    recorder.syncFound({id: 'col', name: 'Lo-fi Study', added: 33}, snapshotOf(items))
    entries = store.all()
    assert.deepEqual(entries.map((entry) => entry.kind), ['sync-found'])
    await store.close()
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('app-updated is recorded on the first start of a new version only; update-ready once per version', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-activity-'))
  const file = path.join(dir, 'activity.json')
  try {
    let store = new ActivityStore({file, delay: 5})
    await store.init()
    let recorder = new ActivityRecorder({store, focused: () => true})
    recorder.appStarted('0.5.0-beta.3')
    assert.equal(store.all().length, 0, 'a first ever start only remembers the version')
    await store.close()

    store = new ActivityStore({file, delay: 5})
    await store.init()
    recorder = new ActivityRecorder({store, focused: () => true})
    recorder.appStarted('0.5.0-beta.4')
    recorder.updateReady('0.5.0')
    recorder.updateReady('0.5.0')
    assert.deepEqual(store.all().map((entry) => entry.kind).sort(), ['app-updated', 'update-ready'])
    assert.equal(store.all().find((entry) => entry.kind === 'app-updated')!.title, 'Keepfin updated to 0.5.0-beta.4')
    await store.close()

    store = new ActivityStore({file, delay: 5})
    await store.init()
    recorder = new ActivityRecorder({store, focused: () => true})
    recorder.appStarted('0.5.0-beta.4')
    assert.equal(store.all().length, 2, 'the same version again adds nothing')
    await store.close()
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

// ---------------------------------------------------------------- the store

test('the store persists, marks read, removes, prunes and counts away once', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-activity-'))
  const file = path.join(dir, 'activity.json')
  try {
    let clock = 1_000_000
    const changes: number[] = []
    let store = new ActivityStore({file, delay: 5, now: () => clock, onChange: (list) => changes.push(list.entries.length)})
    await store.init()
    const a = store.record({kind: 'batch-done', title: 'A'})
    const b = store.record({kind: 'failed', title: 'B', target: {jobIds: ['x']}})
    assert.deepEqual(changes, [1, 2])
    store.markRead([a.id])
    assert.equal(store.get(a.id)!.read, true)
    store.markRead('all')
    assert.ok(store.all().every((entry) => entry.read))
    store.remove(b.id)
    assert.equal(store.all().length, 1)
    await store.close()
    assert.equal((await fs.readdir(dir)).some((name) => name.endsWith('.tmp')), false)
    const stored = JSON.parse(await fs.readFile(file, 'utf8'))
    assert.equal(stored.entries.length, 1)
    assert.equal(stored.closedAt, clock)

    // A session that records news nobody opened...
    clock += 5_000
    store = new ActivityStore({file, delay: 5, now: () => clock})
    await store.init()
    store.list()
    clock += 1_000
    store.record({kind: 'batch-done', title: 'While the window was in the background'})
    await store.close()
    // ...is announced once at the next start; "Keepfin updated" has its own What's new and does not count.
    clock += 60_000
    store = new ActivityStore({file, delay: 5, now: () => clock})
    await store.init()
    store.record({kind: 'app-updated', title: 'Updated'})
    assert.equal(store.list().away, 1)
    assert.equal(store.list().away, 0, 'handed out once per start')
    await store.close()
    clock += 60_000
    store = new ActivityStore({file, delay: 5, now: () => clock})
    await store.init()
    assert.equal(store.list().away, 0, 'never announced twice')

    for (let n = 0; n < ACTIVITY_MAX_ENTRIES + 20; n++) {
      clock += 1
      store.record({kind: 'batch-done', title: `E${n}`})
    }
    assert.equal(store.all().length, ACTIVITY_MAX_ENTRIES)
    clock += 31 * 86_400_000
    store.record({kind: 'batch-done', title: 'Late'})
    assert.deepEqual(store.all().map((entry) => entry.title), ['Late'])
    await store.close()
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('a corrupt file is kept aside and the store starts empty', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-activity-'))
  const file = path.join(dir, 'activity.json')
  try {
    await fs.writeFile(file, '{nope')
    const store = new ActivityStore({file, delay: 5})
    await store.init()
    assert.equal(store.all().length, 0)
    assert.ok((await fs.readdir(dir)).some((name) => name.includes('.corrupt-')))
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})
