import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import {
  DesktopNotifications,
  type NotificationPayload,
} from './notifications.js'
import {DownloadManager} from './manager.js'
import {FakeEngine} from './fake-engine.js'
import {
  notificationDefaults,
  notificationPreferences,
} from '../shared/notifications.js'
import type {Job, Snapshot} from '../shared/contracts.js'
import {defaultSettings} from './settings.js'

const job = (id: string, status: Job['status']): Job => ({
  id,
  status,
  title: `Clip ${id}`,
  url: 'https://example.com/video',
  kind: 'video',
  format: {type: 'video', codec: 'auto', container: 'mp4'},
  options: {},
  label: 'Video',
  percent: status === 'completed' ? 100 : 35,
  createdAt: 1,
})
const snapshot = (jobs: Job[]): Snapshot => ({
  jobs,
  platform: 'desktop',
  settings: defaultSettings('fixture'),
  engine: {tools: [], busy: false},
  hasCookieFile: false,
})
async function fixture(jobs: Job[] = []) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-notifications-'))
  const alerts: NotificationPayload[] = [],
    progress: [number, boolean][] = []
  let fail: (message: string) => void = () => {}
  const options: ConstructorParameters<typeof DesktopNotifications>[0] = {
    store: path.join(dir, 'notifications.json'),
    initial: snapshot(jobs),
    supported: true,
    show: (
      payload: NotificationPayload,
      failure: (message: string) => void,
    ) => {
      alerts.push(payload)
      fail = failure
    },
    progress: (value: number, indeterminate: boolean) => {
      progress.push([value, indeterminate])
    },
  }
  const owner = new DesktopNotifications(options)
  await owner.init()
  return {
    owner,
    alerts,
    progress,
    dir,
    options,
    fail: (message: string) => fail(message),
    cleanup: () => fs.rm(dir, {recursive: true, force: true}),
  }
}
test('preferences accept booleans only and ignore executable/unknown input', () => {
  assert.deepEqual(
    notificationPreferences({enabled: false, sound: 'false', cmd: 'exec'}),
    {...notificationDefaults, enabled: false},
  )
  assert.deepEqual(notificationPreferences(null), notificationDefaults)
})
test('startup history is quiet; completion is deduplicated; retries can notify again; cancellations do not', async () => {
  const f = await fixture([
    job('old', 'completed'),
    job('interrupted', 'failed'),
  ])
  try {
    f.owner.consume(
      snapshot([job('old', 'completed'), job('interrupted', 'failed')]),
    )
    assert.equal(f.alerts.length, 0)
    f.owner.consume(snapshot([job('fresh', 'queued')]))
    f.owner.consume(snapshot([job('fresh', 'completed')]))
    f.owner.consume(snapshot([job('fresh', 'completed')]))
    assert.equal(f.alerts.length, 1)
    assert.equal(f.alerts[0].job?.id, 'fresh')
    assert.equal(f.alerts[0].silent, true)
    f.owner.consume(snapshot([job('fresh', 'queued')]))
    f.owner.consume(snapshot([job('fresh', 'failed')]))
    assert.equal(f.alerts.length, 2)
    assert.equal(f.alerts[1].title, 'Download needs attention')
    f.owner.consume(snapshot([job('fresh', 'queued')]))
    f.owner.consume(snapshot([job('fresh', 'cancelled')]))
    assert.equal(f.alerts.length, 2)
  } finally {
    await f.cleanup()
  }
})
test('a recording that was already in the library says so; a download that could not be saved needs attention', async () => {
  const f = await fixture()
  try {
    f.owner.consume(snapshot([job('there', 'processing')]))
    f.owner.consume(snapshot([{...job('there', 'completed'), existing: {path: 'C:/Music/Song.mp3', space: 'music', by: 'source'}}]))
    assert.equal(f.alerts.length, 1)
    assert.equal(f.alerts[0].title, 'Already in your library')
    f.owner.consume(snapshot([job('lost', 'processing')]))
    f.owner.consume(snapshot([{...job('lost', 'completed'), saveError: 'The library is read-only.'}]))
    assert.equal(f.alerts.length, 2)
    assert.equal(f.alerts[1].title, 'Download not saved')
    assert.match(f.alerts[1].body, /save it again/)
  } finally {
    await f.cleanup()
  }
})
test('master, categories, sound, progress and asynchronous delivery failure are controlled independently', async () => {
  const f = await fixture()
  try {
    await f.owner.configure({
      ...notificationDefaults,
      completed: false,
      sound: true,
    })
    f.owner.consume(snapshot([job('one', 'downloading')]))
    assert.deepEqual(f.progress.at(-1), [0.35, false])
    f.owner.consume(snapshot([job('one', 'processing')]))
    assert.deepEqual(f.progress.at(-1), [2, true])
    f.owner.consume(snapshot([job('one', 'completed')]))
    assert.equal(f.alerts.length, 0)
    f.owner.consume(snapshot([job('two', 'queued')]))
    f.owner.consume(snapshot([job('two', 'failed')]))
    assert.equal(f.alerts[0].silent, false)
    f.fail('System denied notification')
    assert.equal(f.owner.snapshot().lastError, 'System denied notification')
    await f.owner.configure({...notificationDefaults, enabled: false})
    assert.deepEqual(f.progress.at(-1), [-1, false])
    assert.throws(() => f.owner.test(), /Turn on/)
    f.owner.consume(snapshot([job('three', 'queued')]))
    f.owner.consume(snapshot([job('three', 'completed')]))
    assert.equal(f.alerts.length, 1)
  } finally {
    await f.cleanup()
  }
})
test('ordered preference writes persist across restart without replaying past alerts', async () => {
  const f = await fixture()
  try {
    await Promise.all([
      f.owner.configure({...notificationDefaults, completed: false}),
      f.owner.configure({...notificationDefaults, failed: false, sound: true}),
    ])
    const restored = new DesktopNotifications(f.options)
    await restored.init()
    assert.deepEqual(restored.snapshot().preferences, {
      ...notificationDefaults,
      failed: false,
      sound: true,
    })
  } finally {
    await f.cleanup()
  }
})
test('real queue owner emits terminal transitions without renderer polling, including retry', async () => {
  const f = await fixture()
  let refuse = true
  const engine = new FakeEngine()
  const ok = engine.script
  engine.script = async (task, ctx, events) => {
    if (refuse) throw new Error('ERROR: Private video. Sign in if you have access')
    return ok(task, ctx, events)
  }
  const manager = new DownloadManager({
    store: path.join(f.dir, 'queue.json'),
    defaultOutDir: path.join(f.dir, 'downloads'),
    engine,
    snapshotDelay: 5,
  })
  try {
    await manager.init()
    const unsubscribe = manager.subscribe((event) => {
      if (event.type === 'snapshot') f.owner.consume(event.snapshot)
    })
    await manager.inspect('https://example.com/video')
    await manager.enqueue('https://example.com/video', {
      type: 'video',
      codec: 'auto',
      container: 'mp4',
    })
    for (let i = 0; i < 200 && !f.alerts.length; i++) await delay(10)
    assert.equal(f.alerts[0].job?.status, 'failed')
    refuse = false
    await manager.retry(manager.snapshot().jobs[0].id)
    for (let i = 0; i < 200 && f.alerts.length < 2; i++) await delay(10)
    assert.equal(f.alerts[1].job?.status, 'completed')
    assert.equal(await fs.readFile(f.alerts[1].job!.file!, 'utf8'), 'saved')
    unsubscribe()
  } finally {
    await manager.close()
    await f.cleanup()
  }
})
test('progress ticks move the aggregate taskbar value without alerts', async () => {
  const f = await fixture()
  try {
    f.owner.consume(
      snapshot([job('a', 'downloading'), job('b', 'downloading')]),
    )
    f.owner.consumeProgress([{id: 'a', status: 'downloading', percent: 60}])
    assert.deepEqual(f.progress.at(-1), [0.475, false])
    assert.equal(f.alerts.length, 0)
  } finally {
    await f.cleanup()
  }
})

const grouped = (id: string, status: Job['status'], group = 'g1'): Job => ({
  ...job(id, status),
  group: {id: group, title: 'Road Trip', url: 'https://example.com/playlist?list=1'},
})
test('a kept-together group notifies once, only when every item has settled', async () => {
  const f = await fixture()
  try {
    const items = (statuses: Job['status'][]) =>
      snapshot(statuses.map((status, i) => grouped(`i${i}`, status)))
    f.owner.consume(items(['queued', 'queued', 'queued', 'queued', 'queued']))
    f.owner.consume(items(['completed', 'downloading', 'queued', 'queued', 'queued']))
    f.owner.consume(items(['completed', 'completed', 'downloading', 'queued', 'queued']))
    f.owner.consume(items(['completed', 'completed', 'completed', 'failed', 'downloading']))
    assert.equal(f.alerts.length, 0)
    f.owner.consume(items(['completed', 'completed', 'completed', 'failed', 'completed']))
    f.owner.consume(items(['completed', 'completed', 'completed', 'failed', 'completed']))
    assert.equal(f.alerts.length, 1)
    assert.equal(f.alerts[0].title, 'Road Trip downloaded')
    assert.match(f.alerts[0].body, /^4 of 5 saved, 1 failed/)
    assert.equal(f.alerts[0].group?.id, 'g1')
    assert.equal(f.alerts[0].tab, 'Library')
  } finally {
    await f.cleanup()
  }
})
test('a group waiting on an automatic retry does not notify until the final outcome', async () => {
  const f = await fixture()
  try {
    f.owner.consume(snapshot([grouped('a', 'queued'), grouped('b', 'queued')]))
    f.owner.consume(snapshot([grouped('a', 'completed'), grouped('b', 'queued')]))
    assert.equal(f.alerts.length, 0)
    f.owner.consume(snapshot([grouped('a', 'completed'), grouped('b', 'failed')]))
    assert.equal(f.alerts.length, 1)
    assert.match(f.alerts[0].body, /^1 of 2 saved, 1 failed/)
  } finally {
    await f.cleanup()
  }
})
test('separate downloads finishing close together collapse into one summary', async () => {
  const f = await fixture()
  f.options.batchMs = 40
  const owner = new DesktopNotifications(f.options)
  await owner.init()
  try {
    owner.consume(snapshot(['a', 'b', 'c', 'd'].map((id) => job(id, 'queued'))))
    owner.consume(snapshot(['a', 'b'].map((id) => job(id, 'completed')).concat(['c', 'd'].map((id) => job(id, 'queued')))))
    owner.consume(snapshot(['a', 'b', 'c'].map((id) => job(id, 'completed')).concat([job('d', 'failed')])))
    assert.equal(f.alerts.length, 0)
    await delay(90)
    assert.equal(f.alerts.length, 1)
    assert.equal(f.alerts[0].title, '4 downloads finished')
    assert.match(f.alerts[0].body, /3 saved, 1 failed/)
    owner.consume(snapshot([job('e', 'queued')]))
    owner.consume(snapshot([job('e', 'completed')]))
    await delay(90)
    assert.equal(f.alerts.length, 2)
    assert.equal(f.alerts[1].job?.id, 'e')
  } finally {
    await f.cleanup()
  }
})
