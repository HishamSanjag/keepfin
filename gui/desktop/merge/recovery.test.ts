// Crash safety of a combine (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md sections 3 and 8): the run is stopped at every durable write, the
// app starts again, and every effect has happened exactly once with the right library in charge.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {folderJsonPath, openKeepfinFolder} from '../store/keepfinFolder.js'
import {loadProfiles} from '../store/profiles.js'
import {mapFileOf} from './engine.js'
import {readJsonLines} from './lines.js'
import {Crash, mediaTree, mergeRig, uuidN, type MRig} from './testkit.js'

const song = (n: number) => Buffer.from(`audio bytes of song ${n} `.repeat(40))

async function seed(rig: MRig, extra = 0) {
  await rig.a.put(1, 'Music/a-own.mp3', song(1))
  await rig.a.put(2, 'Music/shared.mp3', song(2))
  await rig.b.put(11, 'Music/b-own.mp3', song(11))
  await rig.b.put(12, 'Music/b-copy-of-shared.mp3', song(2))
  await rig.b.put(13, 'Music/b-own-twice.mp3', song(11))
  for (let i = 0; i < extra; i++) await rig.b.put(100 + i, `Music/extra-${i}.mp3`, song(100 + i))
}

/** What a finished combine must look like, whatever happened on the way. */
async function assertCombined(rig: MRig, expectedTitles: string[], mediaBefore: Record<string, string>) {
  const folder = await openKeepfinFolder(rig.b.root)
  assert.ok(folder.ok)
  if (!folder.ok) return
  assert.equal(folder.folder.libraryId, rig.tId, 'T is in charge')
  assert.equal(folder.folder.records, `libraries/${rig.tId}`)
  assert.equal((await loadProfiles(rig.b.deviceDir))!.profiles[0].libraryId, rig.tId, 'the profile names T')
  const live = await rig.openActive()
  try {
    const items = live.store.views().items.filter((i) => i.status !== 'deleted')
    assert.deepEqual(items.map((i) => String(i.fields.title)).sort(), expectedTitles, 'every item exactly once')
    assert.equal(new Set(items.map((i) => i.id)).size, items.length)
    const plays = items.reduce((n, i) => n + i.plays, 0)
    assert.equal(plays, 0)
  } finally {
    await live.store.close()
  }
  const j = (await rig.restart().current())!
  const map = await readJsonLines<{s: string}>(mapFileOf(rig.b.root, `libraries/${rig.tId}`, j.mergeId))
  assert.equal(new Set(map.map((m) => m.s)).size, map.length, 'one map line per item')
  assert.deepEqual(await mediaTree(rig.b.root), mediaBefore, 'media paths and bytes are unchanged')
}

/** Starts, crashes at the armed point, restarts like the app, and finishes what the person or the app would. */
async function crashAndRecover(rig: MRig, at: number, partial?: number) {
  rig.fault.armed = at
  rig.fault.partial = partial
  let crashed = false
  try {
    await rig.engine.preview(rig.a.identity.deviceId)
    await rig.engine.start(rig.a.identity.deviceId, 'theirs')
  } catch (error) {
    if (!(error instanceof Crash)) throw error
    crashed = true
  }
  // the app starts again: recovery first, then the person (or the resume button) carries on
  const engine = rig.restart()
  await engine.recover()
  const current = await engine.current()
  // before the journal exists nothing started; otherwise the person presses Combine again (resume)
  if (!current || current.stage !== 'active') {
    await engine.start(rig.a.identity.deviceId, 'theirs')
    await engine.recover()
  }
  return crashed
}

test('the journal points of a normal run, recorded once', async () => {
  const rig = await mergeRig()
  try {
    await seed(rig)
    await rig.engine.preview(rig.a.identity.deviceId)
    await rig.engine.start(rig.a.identity.deviceId, 'theirs')
    const recovered = rig.restart({noFaults: false})
    await recovered.recover()
    const points = rig.fault.hits
    for (const stage of ['joined', 'describing', 'verified', 'activating', 'active']) assert.ok(points.includes(`after:stage:${stage}`), stage)
    assert.ok(points.includes('before:create') && points.includes('after:create'))
    assert.ok(points.some((p) => p.startsWith('batch:apply:')))
    assert.ok(points.includes('activate:folderjson:before') && points.includes('activate:folderjson:after'))
    assert.ok(points.includes('remap:device') && points.includes('remap:profile'))
    ;(globalThis as {__points?: string[]}).__points = [...points]
  } finally {
    await rig.cleanup()
  }
})

test('crash at every durable write of a combine: the effects happen once and the right library is in charge', async () => {
  // count the points of a clean run first
  const probe = await mergeRig()
  let total = 0
  try {
    await seed(probe)
    await probe.engine.preview(probe.a.identity.deviceId)
    await probe.engine.start(probe.a.identity.deviceId, 'theirs')
    total = probe.fault.hits.length
  } finally {
    await probe.cleanup()
  }
  assert.ok(total > 25, `a combine has many durable writes (${total})`)
  const names: string[] = []
  for (let at = 0; at < total; at++) {
    const rig = await mergeRig()
    try {
      await seed(rig)
      const before = await mediaTree(rig.b.root)
      const crashed = await crashAndRecover(rig, at)
      assert.ok(crashed, `point ${at} was reached`)
      names.push(rig.fault.hits[at] ?? '?')
      await assertCombined(rig, ['Song 1', 'Song 11', 'Song 13', 'Song 2'], before)
    } catch (error) {
      throw new Error(`after a crash at write ${at} (${rig.fault.hits[at]}): ${error instanceof Error ? error.message : String(error)}`, {cause: error})
    } finally {
      await rig.cleanup()
    }
  }
  assert.ok(names.includes('before:receipt:0') && names.includes('after:receipt:0') && names.includes('batch:applied:0'))
})

test('crash inside a batch after any draft: the receipt tells how many landed and the rest are applied once', async () => {
  // the first batch of the seeded library holds 3 drafts for each new item and 1 for the linked one
  for (const partial of [0, 1, 2, 3, 4, 5]) {
    const rig = await mergeRig()
    try {
      await seed(rig)
      const before = await mediaTree(rig.b.root)
      // arm at the first batch's apply point: find its index from a probe of names is not needed, the partial hook fires inside apply
      rig.fault.partial = partial
      let crashed = false
      try {
        await rig.engine.preview(rig.a.identity.deviceId)
        await rig.engine.start(rig.a.identity.deviceId, 'theirs')
      } catch (error) {
        if (!(error instanceof Crash)) throw error
        crashed = true
      }
      assert.ok(crashed, `a batch of more than ${partial} drafts was cut`)
      const engine = rig.restart()
      await engine.recover()
      assert.ok((await engine.current())!.stage === 'active')
      await assertCombined(rig, ['Song 1', 'Song 11', 'Song 13', 'Song 2'], before)
    } catch (error) {
      throw new Error(`with ${partial} drafts landed: ${error instanceof Error ? error.message : String(error)}`, {cause: error})
    } finally {
      await rig.cleanup()
    }
  }
})

test('more than one batch: a crash between batches and inside the second continues from the receipt', async () => {
  // 170 new items are 510 drafts: a batch of 500 and one of 10
  for (const partial of [undefined, 0, 4]) {
    const rig = await mergeRig({pageSize: 50})
    try {
      await seed(rig, 170)
      const before = await mediaTree(rig.b.root)
      const titles = ['Song 1', 'Song 11', 'Song 13', 'Song 2', ...Array.from({length: 170}, (_, i) => `Song ${100 + i}`)].sort()
      if (partial === undefined) {
        // stop after the first batch is fully applied and before the second starts
        rig.fault.armed = -1
        let seen = 0
        const original = rig.deps.faults!
        rig.deps.faults = (point) => {
          original(point)
          if (point === 'after:batch:0' && !seen++) {
            rig.fault.crashed = true
            throw new Crash(point)
          }
        }
      } else {
        // let the first batch through, cut the second
        let applies = 0
        const original = rig.deps.faults!
        rig.deps.faults = (point) => {
          original(point)
          if (point === 'batch:apply:1' && !applies++) rig.fault.partial = partial
        }
      }
      let crashed = false
      try {
        await rig.engine.preview(rig.a.identity.deviceId)
        await rig.engine.start(rig.a.identity.deviceId, 'theirs')
      } catch (error) {
        if (!(error instanceof Crash)) throw error
        crashed = true
      }
      assert.ok(crashed)
      const engine = rig.restart()
      await engine.recover()
      await assertCombined(rig, titles, before)
    } finally {
      await rig.cleanup()
    }
  }
})

test('crash during the remap and the profile switch at the next start: another start finishes it', async () => {
  const probe = await mergeRig()
  let recoverPoints = 0
  try {
    await seed(probe)
    await probe.engine.start(probe.a.identity.deviceId, 'theirs')
    const e = probe.restart({noFaults: false})
    await e.recover()
    recoverPoints = probe.fault.hits.length
  } finally {
    await probe.cleanup()
  }
  assert.ok(recoverPoints >= 4)
  for (let at = 0; at < recoverPoints; at++) {
    const rig = await mergeRig()
    try {
      await seed(rig)
      const before = await mediaTree(rig.b.root)
      await rig.engine.start(rig.a.identity.deviceId, 'theirs')
      // the first start after the switch crashes at write [at] of its own recovery
      rig.fault.hits.length = 0
      rig.fault.armed = at
      const first = rig.restart({noFaults: false})
      rig.fault.armed = at
      await first.recover().catch(() => undefined)
      const second = rig.restart()
      await second.recover()
      await assertCombined(rig, ['Song 1', 'Song 11', 'Song 13', 'Song 2'], before)
    } catch (error) {
      throw new Error(`after a crash at write ${at} of the recovery: ${error instanceof Error ? error.message : String(error)}`, {cause: error})
    } finally {
      await rig.cleanup()
    }
  }
})

test('between the folder.json write and the journal saying active: the pointer is read and the journal catches up', async () => {
  const rig = await mergeRig()
  try {
    await seed(rig)
    const before = await mediaTree(rig.b.root)
    await rig.engine.preview(rig.a.identity.deviceId)
    rig.fault.hits.length = 0
    rig.fault.armed = 0
    const original = rig.deps.faults!
    rig.deps.faults = (point) => {
      if (point === 'activate:folderjson:after') {
        rig.fault.crashed = true
        throw new Crash(point)
      }
      original(point)
    }
    rig.fault.armed = -1
    await assert.rejects(() => rig.engine.start(rig.a.identity.deviceId, 'theirs'), Crash)
    assert.equal((await openKeepfinFolder(rig.b.root) as {folder: {libraryId: string}}).folder.libraryId, rig.tId, 'the pointer was already switched')
    assert.equal((await rig.restart().current())!.stage, 'activating', 'the journal still says activating')
    await rig.restart().recover()
    await assertCombined(rig, ['Song 1', 'Song 11', 'Song 13', 'Song 2'], before)
  } finally {
    await rig.cleanup()
  }
})

test('lost folder access pauses, never rolls back; the combine continues when the folder is back', async () => {
  const rig = await mergeRig()
  try {
    await seed(rig)
    const before = await mediaTree(rig.b.root)
    await rig.engine.preview(rig.a.identity.deviceId)
    const hidden = path.join(rig.dir, 'folder.json.away')
    await fs.rename(folderJsonPath(rig.b.root), hidden)
    const paused = await rig.engine.start(rig.a.identity.deviceId, 'theirs')
    assert.equal(paused.phase, 'paused')
    assert.match(paused.paused ?? '', /Waiting for the Keepfin folder/)
    assert.ok(await fs.stat(path.join(rig.b.root, '.keepfin', 'libraries', rig.tId)), 'nothing was removed')
    await fs.rename(hidden, folderJsonPath(rig.b.root))
    const engine = rig.restart()
    const resumed = await engine.resume()
    assert.equal(resumed.phase, 'done')
    await engine.recover()
    await assertCombined(rig, ['Song 1', 'Song 11', 'Song 13', 'Song 2'], before)
  } finally {
    await rig.cleanup()
  }
})
