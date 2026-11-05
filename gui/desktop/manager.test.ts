import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import type {FormatRequest, PushEvent} from '../shared/contracts.js'
import {DownloadManager} from './manager.js'
import {
  FakeEngine,
  fakeMedia,
  finishImmediately,
  hangUntilAborted,
} from './fake-engine.js'
import {migrateJob} from './migrate.js'
import {EngineError} from './engine/ytdlp.js'

const video: FormatRequest = {type: 'video', codec: 'auto', container: 'mp4'}

async function setup(
  options: {progressInterval?: number; retryDelays?: number[]; now?: () => number} = {},
) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-manager-'))
  const engine = new FakeEngine()
  const store = path.join(dir, 'queue.json')
  const manager = new DownloadManager({
    store,
    defaultOutDir: path.join(dir, 'downloads'),
    engine,
    snapshotDelay: 5,
    progressInterval: options.progressInterval ?? 20,
    retryDelays: options.retryDelays ?? [10, 10, 10],
    now: options.now,
  })
  await manager.init()
  const events: PushEvent[] = []
  manager.subscribe((event) => events.push(event))
  return {
    dir,
    engine,
    store,
    manager,
    events,
    cleanup: async () => {
      await manager.close()
      await fs.rm(dir, {recursive: true, force: true})
    },
  }
}
type Fixture = Awaited<ReturnType<typeof setup>>

async function waitFor(check: () => boolean, label = 'condition') {
  for (let i = 0; i < 300; i++) {
    if (check()) return
    await delay(10)
  }
  throw new Error(`Timed out waiting for ${label}`)
}
const status = (f: Fixture, index = 0) =>
  f.manager.snapshot().jobs[index]?.status
async function add(f: Fixture, name: string) {
  const url = `https://example.com/${name}`
  f.engine.inspectResult = (u) => ({
    type: 'media',
    media: fakeMedia(u, {title: name}),
  })
  await f.manager.inspect(url)
  await f.manager.enqueue(url, video)
  return f.manager.snapshot().jobs.at(-1)!.id
}

test('runs at most `concurrency` jobs in parallel in FIFO order', async () => {
  const f = await setup()
  try {
    await f.manager.updateSettings({concurrency: 2})
    let active = 0
    let peak = 0
    const release: (() => void)[] = []
    f.engine.script = async (task, ctx, events) => {
      active++
      peak = Math.max(peak, active)
      events.onStage('downloading')
      await new Promise<void>((resolve) => release.push(resolve))
      active--
      return finishImmediately(task, ctx, events)
    }
    for (const name of ['a', 'b', 'c', 'd']) await add(f, name)
    await waitFor(() => release.length === 2, 'two workers')
    await delay(30)
    assert.equal(release.length, 2)
    assert.deepEqual(
      f.engine.downloads.map((d) => d.title),
      ['a', 'b'],
    )
    release.shift()!()
    await waitFor(() => release.length === 2, 'third job started')
    assert.deepEqual(
      f.engine.downloads.map((d) => d.title),
      ['a', 'b', 'c'],
    )
    while (
      release.length ||
      f.manager.snapshot().jobs.some((j) => j.status !== 'completed')
    ) {
      release.shift()?.()
      await delay(10)
    }
    assert.equal(peak, 2)
    assert.equal(
      f.manager
        .snapshot()
        .jobs.every((j) => j.fileSize === 5 && j.startedAt && j.completedAt),
      true,
    )
  } finally {
    await f.cleanup()
  }
})

test('pause keeps partial files; resume continues the same output stem', async () => {
  const f = await setup()
  try {
    f.engine.script = hangUntilAborted()
    const id = await add(f, 'clip')
    await waitFor(() => status(f) === 'downloading')
    await f.manager.pause(id)
    assert.equal(status(f), 'paused')
    await waitFor(
      () =>
        f.manager.snapshot().jobs[0].status === 'paused' &&
        f.engine.downloads.length === 1,
    )
    await delay(20)
    assert.equal(f.engine.discarded.length, 0)
    f.engine.script = finishImmediately
    await f.manager.resume(id)
    await waitFor(() => status(f) === 'completed')
    assert.equal(f.engine.downloads.length, 2)
    assert.equal(f.engine.downloads[0].stem, f.engine.downloads[1].stem)
    assert.equal(f.manager.snapshot().jobs[0].attempt, 2)
  } finally {
    await f.cleanup()
  }
})

test('cancel during processing discards partial files and can be retried', async () => {
  const f = await setup()
  try {
    f.engine.script = hangUntilAborted((events) =>
      events.onStage('processing', 'Merging'),
    )
    const id = await add(f, 'merge')
    await waitFor(() => status(f) === 'processing')
    assert.equal(f.manager.snapshot().jobs[0].phase, 'Merging')
    await f.manager.cancel(id)
    assert.equal(status(f), 'cancelled')
    await waitFor(() => f.engine.discarded.length === 1, 'discard')
    assert.equal(f.engine.discarded[0].stem, f.engine.downloads[0].stem)
    f.engine.script = finishImmediately
    await delay(20)
    await f.manager.retry(id)
    await waitFor(() => status(f) === 'completed')
  } finally {
    await f.cleanup()
  }
})

test('failed jobs keep a friendly error and retry resumes with an attempt counter', async () => {
  const f = await setup()
  try {
    f.engine.script = async () => {
      throw new Error('Sign in required: add cookies.')
    }
    const id = await add(f, 'private')
    await waitFor(() => status(f) === 'failed')
    assert.equal(
      f.manager.snapshot().jobs[0].error,
      'Sign in required: add cookies.',
    )
    f.engine.script = finishImmediately
    await f.manager.retry(id)
    await waitFor(() => status(f) === 'completed')
    const job = f.manager.snapshot().jobs[0]
    assert.equal(job.attempt, 2)
    assert.equal(job.error, undefined)
    assert.equal(f.engine.downloads[0].stem, f.engine.downloads[1].stem)
  } finally {
    await f.cleanup()
  }
})

test('removal rejects active jobs, clear keeps them, moveUp reorders waiting jobs', async () => {
  const f = await setup()
  try {
    await f.manager.updateSettings({concurrency: 1})
    f.engine.script = hangUntilAborted()
    const first = await add(f, 'one')
    const second = await add(f, 'two')
    const third = await add(f, 'three')
    await waitFor(() => status(f) === 'downloading')
    await assert.rejects(f.manager.remove(first), /Wait until/)
    await f.manager.moveUp(third)
    assert.deepEqual(
      f.manager.snapshot().jobs.map((j) => j.id),
      [first, third, second],
    )
    await f.manager.cancel(second)
    const snapshot = await f.manager.clear()
    assert.deepEqual(
      snapshot.jobs.map((j) => j.id),
      [first, third],
    )
  } finally {
    await f.cleanup()
  }
})

test('v1 queue.json history loads without loss and interrupted work is resumable', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-migrate-'))
  const store = path.join(dir, 'queue.json')
  const cookies = path.join(dir, 'cookies.txt')
  await fs.writeFile(cookies, '# Netscape HTTP Cookie File\n')
  await fs.writeFile(
    store,
    JSON.stringify({
      outDir: path.join(dir, 'legacy-out'),
      organization: 'flat',
      cookiesFile: cookies,
      jobs: [
        {
          id: 'done',
          url: 'https://youtube.com/watch?v=1',
          title: 'Finished',
          label: '1080p',
          choice: {
            kind: 'video',
            label: '1080p',
            args: [
              '-f',
              'bv*[height<=1080]+ba/b[height<=1080]',
              '--merge-output-format',
              'mp4',
            ],
          },
          extras: {thumbnail: true},
          status: 'completed',
          percent: 100,
          file: path.join(dir, 'a.mp4'),
          createdAt: 10,
        },
        {
          id: 'song',
          url: 'https://youtube.com/watch?v=2',
          title: 'Song',
          label: 'MP3',
          choice: {
            kind: 'audio',
            label: 'MP3',
            args: ['-x', '--audio-format', 'mp3'],
          },
          status: 'downloading',
          percent: 40,
          createdAt: 20,
        },
        {
          id: 'bad',
          url: 'https://youtube.com/watch?v=3',
          title: 'Oops',
          choice: {kind: 'video', args: []},
          status: 'failed',
          error: 'network',
          percent: 3,
          createdAt: 30,
        },
        {id: 'junk', url: 'file:///etc/passwd', choice: {}},
      ],
    }),
  )
  const manager = new DownloadManager({
    store,
    defaultOutDir: path.join(dir, 'x'),
    engine: new FakeEngine(),
  })
  try {
    await manager.init()
    const snapshot = manager.snapshot()
    assert.deepEqual(
      snapshot.jobs.map((j) => j.id),
      ['done', 'song', 'bad'],
    )
    assert.equal(snapshot.settings.outDir, path.join(dir, 'legacy-out'))
    assert.equal(snapshot.settings.organization, 'flat')
    assert.equal(snapshot.hasCookieFile, true)
    assert.equal(snapshot.settings.cookies.mode, 'file')
    const [done, song, bad] = snapshot.jobs
    assert.deepEqual(done.format, {
      type: 'video',
      maxHeight: 1080,
      codec: 'auto',
      container: 'mp4',
    })
    assert.equal(done.options.writeThumbnail, true)
    assert.equal(done.file, path.join(dir, 'a.mp4'))
    assert.equal(song.format.type, 'audio')
    assert.equal(song.status, 'paused')
    assert.equal(bad.status, 'failed')
    assert.equal(bad.error, 'network')
    await manager.close()
    const saved = JSON.parse(await fs.readFile(store, 'utf8'))
    assert.equal(saved.version, 2)
    assert.equal(saved.jobs.length, 3)
    assert.equal(migrateJob(saved.jobs[0])?.id, 'done')
  } finally {
    await manager.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('push events: debounced snapshots, throttled progress, no queue.json writes on ticks', async () => {
  const f = await setup({progressInterval: 50})
  try {
    let finish: (() => void) | undefined
    f.engine.script = async (task, ctx, events) => {
      events.onStage('downloading')
      for (let i = 1; i <= 100; i++) {
        events.onProgress({
          percent: i / 2,
          downloadedBytes: i,
          totalBytes: 200,
          speed: 10,
        })
        await delay(2)
      }
      await new Promise<void>((resolve) => (finish = resolve))
      return finishImmediately(task, ctx, events)
    }
    const started = Date.now()
    await add(f, 'busy')
    await waitFor(
      () => f.manager.snapshot().jobs[0]?.percent === 50,
      'progress 50',
    )
    const elapsed = Date.now() - started
    const progress = f.events.filter((e) => e.type === 'progress')
    assert.ok(progress.length > 0)
    assert.ok(
      progress.length <= Math.ceil(elapsed / 50) + 1,
      `${progress.length} progress events in ${elapsed}ms`,
    )
    const snapshots = f.events.filter((e) => e.type === 'snapshot')
    assert.ok(snapshots.length <= 6, `${snapshots.length} snapshots`)
    const onDisk = JSON.parse(await fs.readFile(f.store, 'utf8'))
    assert.ok(onDisk.jobs[0].percent < 50, 'progress must not be persisted')
    await waitFor(() => !!finish, 'script end')
    finish!()
    await waitFor(() => status(f) === 'completed')
    // Snapshots are debounced: the job is completed before the push that says so, so wait for that push.
    const lastStatus = () => {
      const last = f.events.filter((e) => e.type === 'snapshot').at(-1)
      return last?.type === 'snapshot' ? last.snapshot.jobs[0].status : undefined
    }
    await waitFor(() => lastStatus() === 'completed', 'completed snapshot push')
    assert.equal(lastStatus(), 'completed')
  } finally {
    await f.cleanup()
  }
})

test('engine status changes push a snapshot', async () => {
  const f = await setup()
  try {
    f.engine.setStatus({
      tools: [{name: 'yt-dlp', state: 'updating'}],
      busy: true,
    })
    await waitFor(() =>
      f.events.some((e) => e.type === 'snapshot' && e.snapshot.engine.busy),
    )
  } finally {
    await f.cleanup()
  }
})

test('inspection: cached for ten minutes and limited to two concurrent metadata calls', async () => {
  const f = await setup()
  try {
    f.engine.inspectDelay = 40
    await Promise.all(
      ['a', 'b', 'c', 'd', 'e'].map((n) =>
        f.manager.inspect(`https://example.com/${n}`),
      ),
    )
    assert.equal(f.engine.inspectPeak, 2)
    await f.manager.inspect('https://example.com/a')
    assert.equal(f.engine.inspectCalls.length, 5)
    await assert.rejects(
      f.manager.enqueue('https://example.com/never', video),
      /Inspect the link/,
    )
  } finally {
    await f.cleanup()
  }
})

test('cancelInspection aborts waiting and running lookups', async () => {
  const f = await setup()
  try {
    f.engine.inspectDelay = 500
    const pending = f.manager.inspect('https://example.com/slow', 'session-1')
    await delay(20)
    f.manager.cancelInspection('session-1')
    await assert.rejects(pending, /cancelled/)
  } finally {
    await f.cleanup()
  }
})

test('cookie changes fence in-flight inspection and discovery', async () => {
  const f = await setup()
  try {
    const cookies = path.join(f.dir, 'in.txt')
    await fs.writeFile(cookies, '# Netscape HTTP Cookie File\n')
    // long enough that importing the cookies (file work) finishes while the inspection is still running, even on a loaded machine
    f.engine.inspectDelay = 600
    const pending = f.manager.inspect('https://example.com/video')
    await delay(10)
    await f.manager.importCookies(cookies)
    await assert.rejects(pending, /Cookies changed/)
    assert.equal(f.manager.snapshot().hasCookieFile, true)
    assert.equal(f.manager.snapshot().settings.cookies.mode, 'file')
    f.engine.inspectDelay = 0
    f.engine.discoveryTotal = 50
    f.manager.startDiscovery('https://example.com/list', 'disc-1')
    await waitFor(
      () => f.manager.discoveryState('disc-1').state === 'completed',
    )
    await f.manager.clearCookies()
    const state = f.manager.discoveryState('disc-1')
    assert.equal(state.state, 'failed')
    assert.equal(state.entries.length, 0)
    assert.equal(f.manager.snapshot().hasCookieFile, false)
  } finally {
    await f.cleanup()
  }
})

test('discovery pages by 200, de-duplicates, enqueues many and guards duplicates', async () => {
  const f = await setup()
  try {
    f.engine.script = hangUntilAborted()
    await f.manager.updateSettings({concurrency: 1})
    const first = f.manager.startDiscovery(
      'https://music.youtube.com/playlist?list=PL1',
      'd1',
    )
    assert.equal(first.state, 'discovering')
    await waitFor(() => f.manager.discoveryState('d1').state === 'completed')
    let state = f.manager.discoveryState('d1')
    assert.equal(state.entries.length, 200)
    assert.equal(state.truncated, true)
    assert.equal(state.limit, 200)
    f.manager.discoveryMore('d1')
    await waitFor(() => f.manager.discoveryState('d1').state === 'completed')
    f.manager.discoveryMore('d1')
    await waitFor(() => f.manager.discoveryState('d1').state === 'completed')
    state = f.manager.discoveryState('d1')
    assert.equal(state.entries.length, 450)
    assert.equal(state.truncated, false)
    assert.deepEqual(f.engine.discoveryPages, [
      {start: 1, end: 200},
      {start: 201, end: 400},
      {start: 401, end: 600},
    ])
    assert.throws(() => f.manager.discoveryMore('d1'), /no more/)
    const urls = state.entries.slice(0, 3).map((e) => e.url)
    const snapshot = await f.manager.enqueueDiscovered('d1', urls, '720')
    assert.equal(snapshot.jobs.length, 3)
    assert.deepEqual(snapshot.jobs[0].format, {
      type: 'video',
      maxHeight: 720,
      codec: 'auto',
      container: 'mp4',
    })
    await assert.rejects(
      f.manager.enqueueDiscovered('d1', urls, '720'),
      /already queued/,
    )
    const more = await f.manager.enqueueDiscovered(
      'd1',
      [urls[0], state.entries[3].url],
      'audio-mp3',
    )
    assert.equal(more.jobs.length, 4)
    await assert.rejects(
      f.manager.enqueueDiscovered('d1', ['https://example.com/other'], '720'),
      /Inspect this collection again/,
    )
    assert.throws(() => f.manager.discoveryState('missing'), /expired/)
  } finally {
    await f.cleanup()
  }
})

test('cancelling discovery keeps partial entries', async () => {
  const f = await setup()
  try {
    f.engine.discover = async (_url, _page, ctx, onEntry) => {
      onEntry({url: 'https://example.com/x/1', position: 1})
      await new Promise((_, reject) =>
        ctx.signal?.addEventListener('abort', () => reject(ctx.signal!.reason)),
      )
      return {truncated: false}
    }
    f.manager.startDiscovery('https://example.com/x', 'd2')
    await waitFor(() => f.manager.discoveryState('d2').entries.length === 1)
    const state = f.manager.cancelDiscovery('d2')
    assert.equal(state?.state, 'cancelled')
    assert.equal(state?.entries.length, 1)
  } finally {
    await f.cleanup()
  }
})

test('deleteFile removes the media and the history entry but nothing else', async () => {
  const f = await setup()
  try {
    const id = await add(f, 'keepme')
    await waitFor(() => status(f) === 'completed')
    const job = f.manager.snapshot().jobs[0]
    const neighbour = path.join(path.dirname(job.file!), 'neighbour.mp4')
    await fs.writeFile(neighbour, 'x')
    await f.manager.deleteFile(id)
    assert.equal(await fs.stat(job.file!).catch(() => undefined), undefined)
    assert.equal((await fs.stat(neighbour)).size, 1)
    assert.equal(f.manager.snapshot().jobs.length, 0)
  } finally {
    await f.cleanup()
  }
})

test('settings are validated and persisted; unsafe values are rejected', async () => {
  const f = await setup()
  try {
    await f.manager.updateSettings({
      concurrency: 99,
      fragments: 0,
      proxy: 'socks5://127.0.0.1:9050',
    })
    const s = f.manager.snapshot().settings
    assert.equal(s.concurrency, 6)
    assert.equal(s.fragments, 1)
    await assert.rejects(f.manager.updateSettings({proxy: 'file:///c:/x'}))
    await assert.rejects(f.manager.updateSettings({outDir: 'relative'}))
    await f.manager.close()
    const again = new DownloadManager({
      store: f.store,
      defaultOutDir: path.join(f.dir, 'd'),
      engine: new FakeEngine(),
    })
    await again.init()
    assert.equal(again.snapshot().settings.concurrency, 6)
    await again.close()
  } finally {
    await f.cleanup()
  }
})

test('the default download folder applies only when nothing is saved', async () => {
  const f = await setup()
  try {
    // New install: no saved folder, so the default is used.
    assert.equal(f.manager.snapshot().settings.outDir, path.join(f.dir, 'downloads'))
    // Existing install: a saved folder (even an old-style one) is never replaced.
    const saved = path.join(f.dir, 'Old Downloads', 'LegacyName')
    await fs.mkdir(saved, {recursive: true})
    await f.manager.updateSettings({outDir: saved})
    await f.manager.close()
    const again = new DownloadManager({
      store: f.store,
      defaultOutDir: path.join(f.dir, 'Keepfin'),
      engine: new FakeEngine(),
    })
    await again.init()
    assert.equal(again.snapshot().settings.outDir, saved)
    await again.close()
  } finally {
    await f.cleanup()
  }
})

// ---------------------------------------------------------------- automatic retries

const networkDrop = () => {
  throw new Error('ERROR: unable to download video data: Connection reset by peer (ECONNRESET)')
}

test('a retryable failure retries up to three times, shows the wait, then recovers', async () => {
  const f = await setup({retryDelays: [150, 150, 150]})
  try {
    let calls = 0
    f.engine.script = async (task, ctx, events) => {
      if (++calls < 3) networkDrop()
      return finishImmediately(task, ctx, events)
    }
    await add(f, 'flaky')
    await waitFor(() => !!f.manager.snapshot().jobs[0].retry?.at, 'retry wait')
    const waiting = f.manager.snapshot().jobs[0]
    assert.equal(waiting.status, 'queued')
    assert.equal(waiting.retry?.n, 1)
    assert.equal(waiting.retry?.max, 3)
    assert.equal(waiting.error, undefined)
    await waitFor(() => status(f) === 'completed', 'completion')
    const job = f.manager.snapshot().jobs[0]
    assert.equal(calls, 3)
    assert.equal(job.attempt, 3)
    assert.equal(job.retry, undefined)
    assert.ok(!f.events.some((e) => e.type === 'snapshot' && e.snapshot.jobs[0]?.status === 'failed'))
    assert.equal(f.engine.downloads[0].stem, f.engine.downloads[2].stem)
  } finally {
    await f.cleanup()
  }
})

test('after the third automatic retry the job fails with the friendly error; manual retry resets the budget', async () => {
  const f = await setup()
  try {
    f.engine.script = async () => networkDrop()
    const id = await add(f, 'down')
    await waitFor(() => status(f) === 'failed', 'final failure')
    assert.equal(f.engine.downloads.length, 4)
    const job = f.manager.snapshot().jobs[0]
    assert.match(job.error!, /ECONNRESET/)
    assert.equal(job.retry, undefined)
    await f.manager.retry(id)
    await waitFor(() => f.engine.downloads.length === 8 && status(f) === 'failed', 'second budget')
  } finally {
    await f.cleanup()
  }
})

test('permanent errors fail at once without retrying', async () => {
  const f = await setup()
  try {
    f.engine.script = async () => {
      throw new Error('ERROR: [youtube] abc: Private video. Sign in if you have been granted access')
    }
    await add(f, 'private')
    await waitFor(() => status(f) === 'failed')
    await delay(60)
    assert.equal(f.engine.downloads.length, 1)
  } finally {
    await f.cleanup()
  }
})

test('pausing or cancelling during the wait stops automatic retries for good', async () => {
  const f = await setup({retryDelays: [80, 80, 80]})
  try {
    f.engine.script = async () => networkDrop()
    const a = await add(f, 'one')
    const b = await add(f, 'two')
    await waitFor(() => f.manager.snapshot().jobs.every((j) => !!j.retry?.at), 'both waiting')
    await f.manager.pause(a)
    await f.manager.cancel(b)
    const before = f.engine.downloads.length
    await delay(300)
    const jobs = f.manager.snapshot().jobs
    assert.equal(jobs.find((j) => j.id === a)!.status, 'paused')
    assert.equal(jobs.find((j) => j.id === b)!.status, 'cancelled')
    assert.equal(jobs[0].retry, undefined)
    assert.equal(f.engine.downloads.length, before)
  } finally {
    await f.cleanup()
  }
})

test('engine-related failures refresh the engine before the second retry, hourly at most, without blocking others', async () => {
  const f = await setup({retryDelays: [20, 120, 20]})
  try {
    f.engine.script = async (task, ctx, events) => {
      if (task.url.endsWith('/engine')) throw new Error('ERROR: [youtube] x: Unable to extract player response')
      return finishImmediately(task, ctx, events)
    }
    await add(f, 'engine')
    await add(f, 'fine')
    await waitFor(() => f.manager.snapshot().jobs[1].status === 'completed', 'other job done')
    await waitFor(() => f.manager.snapshot().jobs[0].retry?.n === 2, 'second retry pending')
    assert.equal(f.engine.updates, 1)
    await waitFor(() => status(f) === 'failed', 'final failure')
    assert.equal(f.engine.updates, 1)
    assert.equal(f.engine.downloads.filter((t) => t.url.endsWith('/engine')).length, 4)
  } finally {
    await f.cleanup()
  }
})

test('a recent engine check suppresses the refresh', async () => {
  const f = await setup({retryDelays: [10, 10, 10]})
  try {
    f.engine.status_ = {
      busy: false,
      tools: [{name: 'yt-dlp', state: 'ready', checkedAt: Date.now() - 60_000}],
    }
    f.engine.script = async () => {
      throw new Error('ERROR: Unable to extract uploader id')
    }
    await add(f, 'engine')
    await waitFor(() => status(f) === 'failed')
    assert.equal(f.engine.updates, 0)
  } finally {
    await f.cleanup()
  }
})

// ---------------------------------------------------------------- collection groups

async function discover(f: Fixture, id = 'g1', count = 4) {
  f.engine.discoveryTotal = count
  f.manager.startDiscovery('https://www.youtube.com/playlist?list=PL123', id)
  await waitFor(() => f.manager.discoveryState(id).state === 'completed', 'discovery')
  return f.manager.discoveryState(id).entries.map((e) => e.url)
}

test('kept-together enqueue shares one validated group, persisted across restart', async () => {
  const f = await setup()
  try {
    f.engine.script = hangUntilAborted()
    const urls = await discover(f)
    await f.manager.enqueueDiscovered('g1', urls.slice(0, 2), '720', undefined, true)
    await f.manager.enqueueDiscovered('g1', urls.slice(2), '720')
    const jobs = f.manager.snapshot().jobs
    const ids = new Set(jobs.map((j) => j.group?.id))
    assert.equal(ids.size, 1)
    assert.ok(jobs[0].group!.id)
    assert.equal(jobs[0].group!.title, 'Mix')
    assert.equal(jobs[0].group!.kind, 'playlist')
    assert.equal(jobs[0].group!.url, 'https://www.youtube.com/playlist?list=PL123')
    await f.manager.close()
    const raw = JSON.parse(await fs.readFile(f.store, 'utf8'))
    assert.equal(raw.jobs[0].group.title, 'Mix')
    const again = new DownloadManager({
      store: f.store,
      defaultOutDir: path.join(f.dir, 'downloads'),
      engine: new FakeEngine(),
    })
    await again.init()
    assert.equal(again.snapshot().jobs.filter((j) => j.group?.id === jobs[0].group!.id).length, 4)
    await again.close()
  } finally {
    await fs.rm(f.dir, {recursive: true, force: true})
  }
})

test('separate items enqueue without a group', async () => {
  const f = await setup()
  try {
    const urls = await discover(f)
    const snapshot = await f.manager.enqueueDiscovered('g1', urls, '720', undefined, false)
    assert.equal(snapshot.jobs.length, 4)
    assert.ok(snapshot.jobs.every((j) => j.group === undefined))
  } finally {
    await f.cleanup()
  }
})

test('migration tolerates a missing or malformed group', () => {
  const base = {
    id: 'a',
    url: 'https://example.com/a',
    format: video,
    status: 'completed',
    createdAt: 1,
  }
  assert.equal(migrateJob(base)!.group, undefined)
  assert.equal(migrateJob({...base, group: 'nope'})!.group, undefined)
  assert.equal(migrateJob({...base, group: {id: 'bad id!', title: 'x', url: 'https://x.test'}})!.group, undefined)
  assert.equal(migrateJob({...base, group: {id: 'ok', title: 'x', url: 'ftp://x.test'}})!.group, undefined)
  const good = migrateJob({
    ...base,
    group: {id: 'ok_1', title: '  List ', url: 'https://x.test/l', kind: 'bogus', thumbnail: 'file:///c:/x', extra: 1},
  })!.group
  assert.deepEqual(good, {id: 'ok_1', title: 'List', url: 'https://x.test/l'})
})

test('group actions: remove rejects active sets; pause, resume, cancel and retry apply to every item', async () => {
  const f = await setup()
  try {
    f.engine.script = hangUntilAborted()
    await f.manager.updateSettings({concurrency: 2})
    const urls = await discover(f)
    await f.manager.enqueueDiscovered('g1', urls, '720')
    const gid = f.manager.snapshot().jobs[0].group!.id
    await waitFor(() => f.manager.snapshot().jobs.filter((j) => j.status === 'downloading').length === 2)
    await assert.rejects(f.manager.removeGroup(gid), /Wait until/)
    await f.manager.pauseGroup(gid)
    await waitFor(() => f.manager.snapshot().jobs.every((j) => j.status === 'paused'), 'all paused')
    await f.manager.resumeGroup(gid)
    await waitFor(() => f.manager.snapshot().jobs.filter((j) => j.status === 'downloading').length === 2)
    await f.manager.cancelGroup(gid)
    await waitFor(() => f.manager.snapshot().jobs.every((j) => j.status === 'cancelled'), 'all cancelled')
    await assert.rejects(f.manager.pauseGroup('missing'), /not found/)
    f.engine.script = finishImmediately
    await f.manager.retryGroup(gid)
    await waitFor(() => f.manager.snapshot().jobs.every((j) => j.status === 'completed'), 'all done')
    await assert.rejects(f.manager.retryGroup(gid), /Nothing/)
    const files = f.manager.snapshot().jobs.map((j) => j.file!)
    await f.manager.removeGroup(gid)
    assert.equal(f.manager.snapshot().jobs.length, 0)
    for (const file of files) assert.equal(await fs.readFile(file, 'utf8'), 'saved')
  } finally {
    await f.cleanup()
  }
})

const active = (f: Fixture) =>
  f.manager.snapshot().jobs.filter((j) => ['preparing', 'downloading', 'processing'].includes(j.status)).length

test('YouTube collection batches use the full setting with a request pause; other sites are unaffected', async () => {
  const f = await setup()
  try {
    f.engine.script = hangUntilAborted()
    await f.manager.updateSettings({concurrency: 4})
    const urls = await discover(f, 'yt', 6)
    await f.manager.enqueueDiscovered('yt', urls, '720')
    await waitFor(() => f.engine.downloads.length === 4, 'four YouTube jobs')
    await delay(60)
    assert.equal(active(f), 4, 'no rate limit yet, so the full concurrency applies')
    assert.equal(f.engine.downloads.length, 4)
    assert.ok(f.engine.downloads.every((task) => task.sleepRequests === 0.75))
    await f.manager.cancelGroup(f.manager.snapshot().jobs[0].group!.id)
    await waitFor(() => active(f) === 0, 'cancelled')
    // A direct (not kept-together) download has no request pause.
    await add(f, 'single')
    await waitFor(() => f.engine.downloads.some((task) => task.url.endsWith('/single')), 'single download starts')
    assert.equal(f.engine.downloads.find((task) => task.url.endsWith('/single'))!.sleepRequests, undefined)
  } finally {
    await f.cleanup()
  }
})

test('after a YouTube rate limit grouped jobs are capped at two for 30 minutes, then the full setting returns', async () => {
  let clock = 1_000_000
  const f = await setup({retryDelays: [600_000, 600_000, 600_000], now: () => clock})
  try {
    let limited = false
    f.engine.script = async (task, ctx, events) => {
      if (!limited && task.url.endsWith('item-1')) {
        limited = true
        throw EngineError.from("ERROR: [youtube] abc: Video unavailable. This content isn't available, try again later.")
      }
      return hangUntilAborted()(task, ctx, events)
    }
    await f.manager.updateSettings({concurrency: 4})
    const urls = await discover(f, 'cap', 8)
    await f.manager.enqueueDiscovered('cap', urls, '720')
    await waitFor(() => f.manager.snapshot().jobs[0].retry?.n === 1, 'rate-limit wait')
    await waitFor(() => f.engine.downloads.length === 4, 'first four started before the limit hit')
    await delay(60)
    // item-1 waits; items 2-4 still run, which is already above the cap, so nothing new starts.
    assert.equal(f.engine.downloads.length, 4)
    const jobs = () => f.manager.snapshot().jobs
    const idOf = (n: number) => jobs().find((j) => j.url.endsWith('item-' + n))!.id
    await f.manager.cancel(idOf(2))
    await f.manager.cancel(idOf(3))
    await waitFor(() => f.engine.downloads.some((task) => task.url.endsWith('item-5')), 'item-5 fills the capped slot')
    await delay(60)
    assert.equal(active(f), 2, 'capped at two while the cooldown lasts')
    assert.equal(f.engine.downloads.length, 5)
    // Thirty minutes later the cap is gone: the next free slot refills to the full setting.
    clock += 31 * 60_000
    await f.manager.cancel(idOf(4))
    await waitFor(() => f.engine.downloads.length === 8, 'full concurrency restored')
    assert.equal(active(f), 4)
    await f.manager.cancelGroup(jobs()[0].group!.id)
  } finally {
    await f.cleanup()
  }
})

test('a rate-limited YouTube job waits without holding back the rest of its group', async () => {
  const f = await setup({retryDelays: [60_000, 60_000, 60_000]})
  try {
    let limited = false
    f.engine.script = async (task, ctx, events) => {
      if (!limited && task.url.endsWith('item-1')) {
        limited = true
        throw EngineError.from("ERROR: [youtube] abc: Video unavailable. This content isn't available, try again later.")
      }
      return hangUntilAborted()(task, ctx, events)
    }
    await f.manager.updateSettings({concurrency: 3})
    const urls = await discover(f, 'rl', 4)
    await f.manager.enqueueDiscovered('rl', urls, '720')
    await waitFor(() => f.manager.snapshot().jobs[0].retry?.n === 1, 'rate-limit wait')
    // item-1 waits out its backoff; its slot goes to item-3 straight away (YouTube batches still run two at a time).
    await waitFor(() => f.engine.downloads.some((task) => task.url.endsWith('item-3')), 'group keeps going')
    assert.equal(f.engine.downloads.filter((task) => task.url.endsWith('item-4')).length, 0)
    await f.manager.cancelGroup(f.manager.snapshot().jobs[0].group!.id)
  } finally {
    await f.cleanup()
  }
})

test('failed and retry-waiting set members can be removed one by one or all at once; files and other members stay', async () => {
  const f = await setup({retryDelays: [600_000, 600_000, 600_000]})
  try {
    f.engine.script = async (task, ctx, events) => {
      if (task.url.endsWith('item-1')) throw new Error('ERROR: [youtube] abc: Private video. Sign in if you have been granted access')
      if (task.url.endsWith('item-2')) throw EngineError.from("ERROR: [youtube] abc: Video unavailable. This content isn't available, try again later.")
      if (task.url.endsWith('item-3') || task.url.endsWith('item-4')) throw new Error('ERROR: [youtube] abc: Private video. Sign in if you have been granted access')
      return finishImmediately(task, ctx, events)
    }
    await f.manager.updateSettings({concurrency: 5})
    const urls = await discover(f, 'rm', 5)
    await f.manager.enqueueDiscovered('rm', urls, '720')
    const jobs = () => f.manager.snapshot().jobs
    const idOf = (n: number) => jobs().find((j) => j.url.endsWith('item-' + n))!.id
    await waitFor(() => jobs().find((j) => j.url.endsWith('item-5'))?.status === 'completed' && jobs().find((j) => j.url.endsWith('item-4'))?.status === 'failed' && !!jobs().find((j) => j.url.endsWith('item-2'))?.retry, 'mixed outcomes')
    const saved = jobs().find((j) => j.url.endsWith('item-5'))!
    // A failed member goes on its own.
    await f.manager.remove(idOf(1))
    assert.equal(jobs().length, 4)
    assert.equal(jobs().some((j) => j.url.endsWith('item-1')), false)
    // A member waiting for an automatic retry: the pending retry is cancelled first, then it is removed.
    const before = f.engine.downloads.length
    await f.manager.remove(idOf(2))
    assert.equal(jobs().some((j) => j.url.endsWith('item-2')), false)
    assert.equal(jobs().length, 3)
    // Remove failed: every failed member, nothing else, no files touched.
    const stillThere = jobs().find((j) => j.id === saved.id)!
    await f.manager.removeFailed(stillThere.group!.id)
    assert.deepEqual(jobs().map((j) => j.status), ['completed'])
    assert.equal(await fs.readFile(jobs()[0].file!, 'utf8'), 'saved')
    await assert.rejects(f.manager.removeFailed(jobs()[0].group!.id), /Nothing in this collection has failed/)
    await delay(40)
    assert.equal(f.engine.downloads.length, before, 'the removed retry never fires')
    // The queue file keeps only what is left.
    await f.manager.close()
    const raw = JSON.parse(await fs.readFile(f.store, 'utf8'))
    assert.equal(raw.jobs.length, 1)
  } finally {
    await f.cleanup()
  }
})

test('a bot check retries once, then fails with the sign-in message', async () => {
  const f = await setup({retryDelays: [20, 20, 20]})
  try {
    f.engine.script = async () => {
      throw EngineError.from("ERROR: [youtube] abc: Sign in to confirm you're not a bot. Use --cookies-from-browser")
    }
    await add(f, 'bot')
    await waitFor(() => status(f) === 'failed', 'failure')
    assert.equal(f.engine.downloads.length, 2)
    const job = f.manager.snapshot().jobs[0]
    assert.match(job.error!, /Website sign-in/)
    assert.match(job.errorDetail!, /not a bot/)
    assert.doesNotMatch(job.errorDetail!, /--cookies-from-browser.*yt-dlp/)
  } finally {
    await f.cleanup()
  }
})

test('finished songs get lyrics by default, before they are saved; videos, failures and the setting off never touch a job', async () => {
  const f = await setup()
  try {
    assert.equal(f.manager.getSettings().lyrics, true, 'on by default')
    const asked: {file: string; song: unknown}[] = []
    let answer = async () => true
    f.engine.addLyrics = async (file, song) => {
      asked.push({file, song})
      return answer()
    }
    const audio: FormatRequest = {type: 'audio', target: 'mp3', quality: 'best'}
    const song = async (name: string, title: string, uploader: string, format: FormatRequest = audio) => {
      const url = `https://example.com/${name}`
      f.engine.inspectResult = (u) => ({type: 'media', media: fakeMedia(u, {title, uploader, duration: 244})})
      await f.manager.inspect(url)
      await f.manager.enqueue(url, format)
      await waitFor(() => f.manager.snapshot().jobs.at(-1)?.status === 'completed', name)
    }
    await song('a', 'M83 - Midnight City (Official Video)', 'M83VEVO')
    await waitFor(() => asked.length === 1, 'lookup')
    assert.deepEqual(asked[0].song, {title: 'Midnight City', artist: 'M83', album: undefined, duration: 244})
    answer = async () => {
      throw new Error('network down')
    }
    await song('b', 'Wait', 'M83 - Topic')
    await waitFor(() => asked.length === 2, 'second lookup')
    assert.deepEqual(asked[1].song, {title: 'Wait', artist: 'M83', album: undefined, duration: 244})
    assert.equal(f.manager.snapshot().jobs.at(-1)?.status, 'completed', 'a failed lookup leaves the song complete')
    assert.equal(f.manager.snapshot().jobs.at(-1)?.error, undefined)
    await song('c', 'A clip', 'Someone', video)
    await f.manager.updateSettings({lyrics: false})
    await song('d', 'Outro', 'M83 - Topic')
    await delay(50)
    assert.equal(asked.length, 2, 'no lookups for videos or with the setting off')
  } finally {
    await f.cleanup()
  }
})

// ---------------------------------------------------------------- damaged file names (non-Latin titles)

const ARABIC = 'كرت 5090 يحتاج هوية! 😳 _ أخبارَ'
const DAMAGED_NAME = ' 5090  ! _ '

test('a finished job whose reported file name was damaged is saved under the real file', async () => {
  const f = await setup()
  try {
    f.engine.script = async (task) => {
      const real = path.join(task.folder, `${task.stem}.mp4`)
      await fs.mkdir(task.folder, {recursive: true})
      await fs.writeFile(real, 'saved')
      // What the engine used to print: every non-ASCII character gone.
      return {file: path.join(task.folder, `${task.stem.replace(/[^\x00-\x7f]/g, '')}.mp4`), files: []}
    }
    await add(f, ARABIC)
    await waitFor(() => ['completed', 'failed'].includes(status(f) ?? ''), 'finish')
    const job = f.manager.snapshot().jobs[0]
    assert.equal(job.status, 'completed', job.error ?? '')
    const saved = job.file ?? ''
    assert.ok(saved.includes('أخبار'), saved)
    assert.equal(await fs.readFile(saved, 'utf8'), 'saved')
  } finally {
    await f.cleanup()
  }
})

test('a finished job whose file cannot be found fails instead of saving a wrong path', async () => {
  const f = await setup()
  try {
    f.engine.script = async (task) => ({file: path.join(task.folder, `${DAMAGED_NAME}.mp4`), files: []})
    await add(f, ARABIC)
    await waitFor(() => ['completed', 'failed'].includes(status(f) ?? ''), 'finish')
    await waitFor(() => status(f) === 'failed', 'failure after retries')
    const job = f.manager.snapshot().jobs[0]
    assert.equal(job.file, undefined)
    assert.match(job.error ?? '', /could not be found/)
  } finally {
    await f.cleanup()
  }
})

test('a file taken by another finished job is never picked for a damaged name', async () => {
  const f = await setup()
  try {
    const folder = path.join(f.dir, 'shared')
    await fs.mkdir(folder, {recursive: true})
    const first = path.join(folder, `${ARABIC}.mp4`)
    await fs.writeFile(first, 'first')
    f.engine.script = async (task) => {
      // The first job saves under its real name; the second reports a damaged name that only matches the first job's file.
      const own = task.title === 'first' ? first : path.join(folder, `${DAMAGED_NAME}.mp4`)
      return {file: own, files: []}
    }
    await add(f, 'first')
    await waitFor(() => status(f) === 'completed', 'first job')
    await add(f, 'second')
    await waitFor(() => status(f, 1) === 'failed', 'second job fails')
    assert.equal(f.manager.snapshot().jobs[0].file, first)
    assert.equal(f.manager.snapshot().jobs[1].file, undefined)
  } finally {
    await f.cleanup()
  }
})

test('repairJobFile follows a damaged saved name found in history', async () => {
  const f = await setup()
  try {
    await add(f, ARABIC)
    await waitFor(() => status(f) === 'completed', 'finish')
    const job = f.manager.snapshot().jobs[0]
    const real = job.file!
    const damaged = path.join(path.dirname(real), `${DAMAGED_NAME}.mp4`)
    // Simulate a job saved by an older version: the file is real, the saved path is the damaged one.
    await f.manager.close()
    const stored = JSON.parse(await fs.readFile(f.store, 'utf8'))
    assert.equal(stored.jobs[0].status, 'completed')
    stored.jobs[0].file = damaged
    await fs.writeFile(f.store, JSON.stringify(stored))
    const reopened = new DownloadManager({store: f.store, defaultOutDir: path.join(f.dir, 'downloads'), engine: f.engine, snapshotDelay: 5})
    await reopened.init()
    try {
      assert.equal(reopened.snapshot().jobs[0].file, damaged)
      assert.equal(await reopened.repairJobFile(job.id), real)
      assert.equal(reopened.snapshot().jobs[0].file, real)
      assert.equal(await reopened.repairJobFile(job.id), undefined, 'nothing to repair any more')
    } finally {
      await reopened.close()
    }
  } finally {
    await fs.rm(f.dir, {recursive: true, force: true})
  }
})
