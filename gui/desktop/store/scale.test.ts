// The performance budgets of dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md section 10, as tests.
// Opt-in while the budgets are being reached (they are timing tests and slow): KEEPFIN_SCALE=20000 for the quick size,
// KEEPFIN_SCALE=200000 for the full size. Budgets that depend on the library size are scaled by items/200,000;
// per-operation budgets are not scaled. Once every budget passes, this runs by default.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash, randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import inspector from 'node:inspector'
import {monitorEventLoopDelay} from 'node:perf_hooks'
import v8 from 'node:v8'
import vm from 'node:vm'
import {defaultLibrarySettings, type Collection, type Space} from '../../shared/library.js'
import {LibraryIndex, type LibraryEntry, type LibraryPushEvent, type PersistedLibrary} from '../library/index.js'
import {applyLibraryDelta, getLibrary, setLibrary} from '../../web/library/libStore.js'
import {createKeepfinFolder, dataDir, openKeepfinFolder} from './keepfinFolder.js'
import {StoreLibrary} from './persistence.js'

const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const N = Number(process.env.KEEPFIN_SCALE ?? 20_000)
const SIZE = N / 200_000

/** A UUID-shaped id that is spread over the hex digits like random ones (checkpoint shards follow the first digit). */
function spread(kind: string, n: number): string {
  const hex = createHash('md5').update(`${kind}:${n}`).digest('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`
}

function bigLibrary(root: string, count: number): LibraryEntry[] {
  const items: LibraryEntry[] = []
  for (let n = 0; n < count; n++) {
    const id = spread('item', n)
    const album = `Album ${n % 400}`
    const entry: Record<string, unknown> = {
      id,
      space: 'music',
      path: path.join(root, 'Music', album, `Track ${n}.mp3`),
      folder: album,
      kind: 'audio',
      title: `Track ${n}`,
      artist: `Artist ${n % 900}`,
      album,
      albumArtist: `Artist ${n % 900}`,
      track: (n % 15) + 1,
      disc: 1,
      year: 1990 + (n % 35),
      genre: ['Rock', 'Jazz', 'Pop'][n % 3],
      duration: 120 + (n % 300) + 0.123,
      gain: -((n % 90) / 10),
      tags: ['rock', 'live'],
      size: 4_000_000 + n,
      modifiedAt: 1_700_000_000_000 + n,
      addedAt: 1_700_000_000_000 + n,
      sourceId: `youtube:abc${n}`,
      sourceUrl: `https://music.youtube.com/watch?v=abc${n}`,
      hasArt: false,
      managed: true,
    }
    if (n % 20 === 0) entry.favorite = true
    if (n % 10 === 0) entry.userTags = ['mine']
    if (n % 5 === 0) entry.plays = 1 + (n % 7)
    items.push(entry as LibraryEntry)
  }
  return items
}

function playlists(items: LibraryEntry[], count: number): Collection[] {
  const out: Collection[] = []
  const per = Math.max(5, Math.floor(items.length / Math.max(1, count) / 2))
  for (let n = 0; n < count; n++) {
    const ids: string[] = []
    for (let k = 0; k < per; k++) ids.push(items[(n * 97 + k * 13) % items.length].id)
    out.push({id: spread('list', n), name: `List ${n}`, kind: 'own', space: 'music', itemIds: [...new Set(ids)], seen: [], createdAt: 1, updatedAt: 1})
  }
  return out
}

const median = (values: number[]) => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)]
const ms = (n: number) => Math.round(n * 10) / 10

const B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

/**
 * With KEEPFIN_PROFILE_BLOCKS=1: samples the main thread while `work` runs and prints, for the longest gaps between two turns of the
 * event loop, the functions that were running (for finding what makes a block long).
 */
async function sampledGaps(work: () => Promise<unknown>, label: string) {
  const session = new inspector.Session()
  session.connect()
  const post = (method: string, params?: object) => new Promise<any>((resolve, reject) => session.post(method, params as never, (error, result) => (error ? reject(error) : resolve(result))))
  await post('Profiler.enable')
  await post('Profiler.setSamplingInterval', {interval: 250})
  await post('Profiler.start')
  const origin = performance.now()
  const gaps: {from: number; to: number}[] = []
  let last = origin
  let live = true
  const beat = () => {
    const now = performance.now()
    gaps.push({from: last, to: now})
    last = now
    if (live) setImmediate(beat)
  }
  setImmediate(beat)
  await work()
  live = false
  await new Promise((resolve) => setImmediate(resolve))
  const {profile} = await post('Profiler.stop')
  session.disconnect()
  const byId = new Map<number, any>(profile.nodes.map((n: any) => [n.id, n]))
  const times: number[] = []
  let t = 0
  for (const delta of profile.timeDeltas as number[]) times.push((t += delta) / 1000)
  const top = [...gaps].sort((a, b) => b.to - b.from - (a.to - a.from)).slice(0, 4)
  for (const gap of top) {
    const self = new Map<string, number>()
    for (let i = 0; i < times.length; i++) {
      const at = origin + times[i]
      if (at < gap.from || at > gap.to) continue
      const node = byId.get(profile.samples[i])
      const key = `${node.callFrame.functionName || '(anon)'} ${String(node.callFrame.url).split(/[\\/]/).pop()}:${node.callFrame.lineNumber}`
      self.set(key, (self.get(key) ?? 0) + 0.25)
    }
    const best = [...self].sort((a, b) => b[1] - a[1]).slice(0, 5).map(([k, v]) => `${Math.round(v)}ms ${k}`)
    console.error(`[blocks ${label}] gap ${Math.round(gap.to - gap.from)} ms at +${Math.round(gap.from - origin)}: ${best.join(' | ')}`)
  }
}

/** Runs `work` while watching the event loop: the longest single block, in ms. */
async function blocking<T>(work: () => Promise<T>): Promise<{value: T; maxBlock: number; ms: number}> {
  const monitor = monitorEventLoopDelay({resolution: 5})
  monitor.enable()
  const started = performance.now()
  let value!: T
  if (process.env.KEEPFIN_PROFILE_BLOCKS) await sampledGaps(async () => void (value = await work()), 'blocking')
  else value = await work()
  const ms = performance.now() - started
  await new Promise((resolve) => setImmediate(resolve))
  monitor.disable()
  return {value, maxBlock: monitor.max / 1e6, ms}
}

async function until(check: () => boolean, label: string, ms = 120_000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (check()) return
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
  throw new Error(`Timed out waiting for ${label}`)
}

test(`scale: every budget of desktop spec section 10 at ${N} items`, {skip: !process.env.KEEPFIN_SCALE && 'set KEEPFIN_SCALE to run'}, async () => {
  // KEEPFIN_SCALE_KEEP=<folder>: the imported library is kept there (once), and later runs start from a copy of it instead of importing again.
  const keep = process.env.KEEPFIN_SCALE_KEEP ? path.join(process.env.KEEPFIN_SCALE_KEEP, String(N)) : undefined
  const dir = keep ? path.join(keep, 'run') : await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-scale-'))
  const reuse = !!keep && (await fs.stat(path.join(keep, 'pristine', 'DONE')).then(() => true, () => false))
  if (keep) {
    await fs.rm(dir, {recursive: true, force: true})
    if (reuse) await fs.cp(path.join(keep, 'pristine'), dir, {recursive: true})
  }
  const rows: {verdict: string; path: string; budget: string; measured: string}[] = []
  const over: string[] = []
  const row = (name: string, budget: string, measured: string, verdict: string) => rows.push({verdict, path: name, budget, measured})
  const check = (name: string, budgetMs: number, measured: number, note = '') => {
    const ok = measured <= budgetMs
    row(name, `${ms(budgetMs)} ms`, `${ms(measured)} ms${note ? ` (${note})` : ''}`, ok ? 'ok' : 'OVER')
    if (!ok) over.push(`${name}: ${ms(measured)} ms is over ${ms(budgetMs)} ms`)
  }
  const deps = (deviceName: string, extra: Record<string, unknown> = {}, store: Record<string, unknown> = {}) =>
    new StoreLibrary({deviceDir: path.join(dir, deviceName), newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 3_600_000, ...store}, ...extra})
  const settingsFor = (loaded: {spaces?: Space[]; routes?: {music: string; other: string}}) => ({...defaultLibrarySettings({downloads: '', music: '', videos: ''}), spaces: loaded.spaces!, routes: loaded.routes!})
  try {
    const folder = reuse
      ? await openKeepfinFolder(path.join(dir, 'Keepfin')).then((opened) => {
          if (!opened.ok) throw new Error('the kept library cannot be opened')
          return opened.folder
        })
      : await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
    // The import runs in a function of its own, so nothing of it stays reachable when the memory of the later open is measured.
    const importLibrary = async () => {
      const first = deps('AppData')
      await first.create(folder, A)
      await first.open(folder, A)
      const session0 = first.persistence
      const seed = await session0.reload()
      const items = bigLibrary(folder.root, N)
      const collections = playlists(items, Math.round(2000 * SIZE))
      let begun = Date.now()
      await session0.save({items, collections, spaces: seed.spaces, routes: seed.routes})
      row('import (initial save)', '-', `${Date.now() - begun} ms`, 'info')
      begun = Date.now()
      await first.close()
      row('close: flush, checkpoint parts, cache', '-', `${Date.now() - begun} ms`, 'info')
    }
    if (reuse) row('import (initial save)', '-', 'reused from KEEPFIN_SCALE_KEEP', 'info')
    else {
      await importLibrary()
      if (keep) {
        await fs.rm(path.join(keep, 'pristine'), {recursive: true, force: true})
        await fs.cp(dir, path.join(keep, 'pristine'), {recursive: true})
        await fs.writeFile(path.join(keep, 'pristine', 'DONE'), String(N))
      }
    }
    let started = Date.now()

    // ---- warm open: the cache matches the store
    const collect = () => {
      try {
        v8.setFlagsFromString('--expose-gc')
        const gc = vm.runInNewContext('gc') as () => void
        gc()
        gc()
      } catch {
        // without a collection the figure includes garbage
      }
    }
    // This machine's speed varies by up to 2x between runs (every step scales together), so the warm open is measured
    // three times and judged by its median; its longest block is the worst of the three.
    const warmOpenOnce = async () => {
      const lib = deps('AppData', {deferStore: true})
      let idx!: LibraryIndex
      const run = await blocking(async () => {
        await lib.open(folder, A)
        const persistence = lib.persistence
        let loaded: PersistedLibrary | undefined
        const load = persistence.load.bind(persistence)
        persistence.load = async () => (loaded = await load())
        idx = new LibraryIndex({file: path.join(dir, 'unused.json'), artDir: path.join(dataDir(folder.root), 'art'), ffmpeg: () => undefined, persistence, newId: randomUUID, saveDelay: 3_600_000, pushDelay: 3_600_000, watch: false, analyse: false})
        await idx.init()
        idx.configure(settingsFor(loaded!))
      })
      await idx.close()
      await lib.close()
      return run
    }
    collect()
    // The baseline comes before the extra opens: anything they leave behind counts against the budget, never for it.
    const heapBefore = process.memoryUsage()
    const extraWarm = [await warmOpenOnce(), await warmOpenOnce()]
    const progress: string[] = []
    const checkpoints: {parts: number; buildMs: number; bytes: number}[] = []
    const warmLibrary = deps(
      'AppData',
      {deferStore: true, onProgress: (p: {phase: string}) => progress.includes(p.phase) || progress.push(p.phase)},
      {checkpointEvery: 400, checkpointDelayMs: 50, flushOps: 100_000, onCheckpoint: (info: {parts: number; buildMs: number; bytes: number}) => checkpoints.push(info)},
    )
    let index!: LibraryIndex
    const warmSteps: Record<string, number> = {}
    const warm = await blocking(async () => {
      const lap = performance.now()
      await warmLibrary.open(folder, A)
      warmSteps.open = Math.round(performance.now() - lap)
      const persistence = warmLibrary.persistence
      let loaded: PersistedLibrary | undefined
      const load = persistence.load.bind(persistence)
      persistence.load = async () => (loaded = await load())
      index = new LibraryIndex({file: path.join(dir, 'unused.json'), artDir: path.join(dataDir(folder.root), 'art'), ffmpeg: () => undefined, persistence, newId: randomUUID, saveDelay: 3_600_000, pushDelay: 3_600_000, watch: false, analyse: false})
      const initLap = performance.now()
      await index.init()
      warmSteps.indexInit = Math.round(performance.now() - initLap)
      const configLap = performance.now()
      index.configure(settingsFor(loaded!))
      warmSteps.configure = Math.round(performance.now() - configLap)
    })
    assert.ok(progress.includes('cache'), 'the warm open read the cache')
    assert.equal(index.all().length, N)
    const warmTimes = [...extraWarm.map((run) => run.ms), warm.ms].sort((a, b) => a - b)
    check('open, warm: to the first snapshot (median of 3)', 1500 * SIZE, warmTimes[1])
    check('open, warm: longest event-loop block (worst of 3)', 100, Math.max(warm.maxBlock, ...extraWarm.map((run) => run.maxBlock)))
    row('open, warm: the three runs', '-', [...extraWarm, warm].map((run) => `${ms(run.ms)} ms (block ${ms(run.maxBlock)})`).join(', '), 'info')
    row('open, warm: steps', '-', JSON.stringify({...warmSteps, ...warmLibrary.persistence.timings}), 'info')

    if (process.env.KEEPFIN_SCALE_ONLY === 'warm') {
      // (for working on the warm open: the rest of the table is not measured)
      await index.close()
      await warmLibrary.close()
      return
    }

    // The store opens in the background: this is where the core's checkpoint load blocks the thread.
    const session = warmLibrary.persistence
    const background = await blocking(() => session.ready)
    row('store opening in the background (core)', '-', `${ms(background.ms)} ms, longest block ${ms(background.maxBlock)} ms`, 'info')
    collect()
    if (process.env.KEEPFIN_SNAP) v8.writeHeapSnapshot(process.env.KEEPFIN_SNAP)
    {
      const usage = process.memoryUsage()
      row('memory: main thread right after the open (MB)', '-', `${ms((usage.heapUsed + usage.external - heapBefore.heapUsed - heapBefore.external) / 1024 / 1024)} MB`, 'info')
    }

    // The startup scan (the generated files do not exist, so every entry is marked missing, once).
    started = Date.now()
    await index.settled(600_000)
    await index.flush()
    row('startup scan + its save (files absent)', '-', `${Date.now() - started} ms`, 'info')

    // Memory is read when everything that follows an open has settled (the background work of the open included), after a collection.
    collect()
    const heapAfter = process.memoryUsage()
    if (process.env.KEEPFIN_SNAP_FINAL) v8.writeHeapSnapshot(process.env.KEEPFIN_SNAP_FINAL)
    const mainMb = (heapAfter.heapUsed + heapAfter.external - heapBefore.heapUsed - heapBefore.external) / 1024 / 1024
    const worker = await session.store.memory()
    check('memory: main thread, library and trackers (MB, shown in the ms column)', 300, mainMb, 'MB')
    check('memory: store thread, heap and external (MB, shown in the ms column)', 700, (worker.heapUsed + worker.external) / 1024 / 1024, 'MB')
    // (SQLite's page cache and the mapped file are native memory outside these two figures; the cache is capped at 64 MB.)
    row('memory: store thread, goal of the SQL state (heap 150 MB)', '-', `${ms((worker.heapUsed + worker.external) / 1024 / 1024)} MB (${(worker.heapUsed + worker.external) / 1024 / 1024 <= 150 ? 'goal met' : 'goal not met'})`, 'info')

    // ---- local edits through the index
    const monitor = monitorEventLoopDelay({resolution: 5})
    monitor.enable()
    const ids = index.all().map((entry) => entry.id)
    const at = (i: number) => ids[(i * 7919) % ids.length]
    const sample = async (name: string, edit: (i: number) => void, rounds = 15) => {
      const sync: number[] = []
      const total: number[] = []
      for (let i = 0; i < rounds; i++) {
        const t0 = performance.now()
        edit(i)
        const pending = index.flush()
        sync.push(performance.now() - t0)
        await pending
        total.push(performance.now() - t0)
        await new Promise((resolve) => setImmediate(resolve))
      }
      check(`local edit: ${name} (median, blocking part)`, 30 * SIZE, median(sync), `max ${ms(Math.max(...sync))}, whole save median ${ms(median(total))}`)
    }
    const entry = (i: number) => index.lookup(at(i)) as LibraryEntry
    await sample('rename', (i) => {
      entry(i).title = `Renamed ${i}`
      index.changed(true, [at(i)])
    })
    await sample('favourite', (i) => index.setFavorite([at(i)], !entry(i).favorite))
    await sample('tag', (i) => index.setUserTags([at(i)], [`t${i}`], []))
    await sample('play', (i) => index.recordPlayback(at(i), 100, true))
    const lists = index.collectionList()
    await sample('reorder', (i) => {
      const list = lists[i % lists.length]
      index.reorderCollection(list.id, [...list.itemIds.slice(1), list.itemIds[0]])
    })

    // ---- what the window is sent for a one-item change (spec 10.2): the push on the main thread, then the renderer applying it
    const pushed: LibraryPushEvent[] = []
    index.subscribePush((event) => pushed.push(event))
    const internals = index as unknown as {pushAll: boolean; flushPush(): void}
    internals.pushAll = false // the window already has the library (below), so the next push is a delta
    internals.flushPush() // drains the ids the earlier edits left; their push is not what is measured
    pushed.length = 0
    // (the window's first full load is set up outside the measured time: it is not an edit)
    const maxBefore = monitor.max
    monitor.disable()
    setLibrary({...index.snapshot(), seq: 1})
    monitor.reset()
    monitor.enable()
    ;(index as unknown as {pushSeq: number}).pushSeq = 1
    const pushMs: number[] = []
    const applyMs: number[] = []
    for (let i = 0; i < 15; i++) {
      index.setFavorite([at(5000 + i)], !entry(5000 + i).favorite)
      const t0 = performance.now()
      internals.flushPush()
      pushMs.push(performance.now() - t0)
      const delta = pushed[pushed.length - 1]
      assert.equal(delta.type, 'libraryDelta')
      if (delta.type !== 'libraryDelta') throw new Error('not a delta')
      assert.equal(delta.items?.length, 1)
      const t1 = performance.now()
      applyLibraryDelta(delta)
      applyMs.push(performance.now() - t1)
    }
    assert.equal(getLibrary().snapshot.items.length, N)
    check('library push: one-item change, main thread (median)', 1, median(pushMs), `max ${ms(Math.max(...pushMs))}`)
    check('library push: renderer applies it (median)', 5, median(applyMs), `max ${ms(Math.max(...applyMs))}`)

    // ---- flush to a segment, device data, checkpoint parts
    for (let i = 0; i < 500; i++) entry(1000 + i).title = `Batch ${i}`
    index.changed(true, Array.from({length: 500}, (_, i) => at(1000 + i)))
    await index.flush()
    const pendingOps = await session.store.pendingOps()
    started = performance.now()
    await session.store.flush()
    check(`flush ${pendingOps} ops to a segment`, 20 * (pendingOps / 500), performance.now() - started)
    started = performance.now()
    await session.device.flush()
    row('device data write (changed shards)', '-', `${ms(performance.now() - started)} ms`, 'info')
    await until(() => checkpoints.length > 0, 'a checkpoint after the batch')
    const built = checkpoints[0]
    check('checkpoint: build time per changed part', 1000, built.buildMs / Math.max(1, built.parts), `${built.parts} parts, ${ms(built.bytes / 1024 / 1024)} MB; the build blocks the thread`)
    monitor.disable()
    await new Promise((resolve) => setImmediate(resolve))
    check('local edits: longest event-loop block (max)', 100, Math.max(maxBefore, monitor.max) / 1e6)

    // ---- changes from another device
    const other = deps('DevB', {}, {})
    await other.open(folder, B)
    const changed = 100
    await other.persistence.store.apply(Array.from({length: changed}, (_, i) => ({kind: 'item.set', body: {item: at(2000 + i), field: 'title', value: `Remote ${i}`}})))
    await other.persistence.store.flush()
    let reloadMs = 0
    // The main-thread part of a remote change is applyRemote (the fetch happens on the store thread).
    const applyRemote = session.applyRemote.bind(session)
    session.applyRemote = (fetched) => {
      const t0 = performance.now()
      const out = applyRemote(fetched)
      reloadMs = performance.now() - t0
      return out
    }
    const remote = await blocking(async () => {
      const t0 = performance.now()
      await session.store.rescan()
      await until(() => index.lookup(at(2000))?.title === 'Remote 0' && index.lookup(at(2099))?.title === 'Remote 99', 'the remote changes')
      return performance.now() - t0
    })
    check(`remote changes: ${changed} items, main-thread projection of the keys touched`, 50 * (changed / 100), reloadMs)
    check('remote changes: longest event-loop block', 100, remote.maxBlock)
    row('remote changes: whole path (rescan, project, apply)', '-', `${ms(remote.value)} ms, longest block ${ms(remote.maxBlock)} ms`, 'info')
    await other.close()
    await index.close()
    await warmLibrary.close()

    // ---- cold open: no cache
    await fs.rm(path.join(dir, 'AppData', 'actors', A, 'cache'), {recursive: true, force: true})
    const coldProgress: string[] = []
    const cold = await blocking(async () => {
      const library = deps('AppData', {onProgress: (p: {phase: string}) => coldProgress.includes(p.phase) || coldProgress.push(p.phase)})
      await library.open(folder, A)
      return library
    })
    check('open, cold: store and a full projection', 15_000 * SIZE, cold.ms, `phases ${coldProgress.join(' > ')}`)
    row('open, cold: longest event-loop block', '100 ms (core views call)', `${ms(cold.maxBlock)} ms`, cold.maxBlock <= 100 ? 'ok' : 'PENDING')
    row('open, cold: steps', '-', `${JSON.stringify(cold.value.persistence.timings)} store ${cold.value.persistence.store.stats.ms}`, 'info')
    assert.equal((await cold.value.persistence.model()).items.length, N)
    await cold.value.close()
    assert.deepEqual(over, [], 'budgets exceeded')
  } catch (error) {
    console.log('FAILED', error)
    throw error
  } finally {
    console.log(`\nscale ${N} items\n` + rows.map((r) => `  ${r.verdict.padEnd(7)} ${r.path.padEnd(62)} budget ${r.budget.padEnd(24)} measured ${r.measured}`).join('\n'))
    await fs.rm(dir, {recursive: true, force: true})
  }
})
