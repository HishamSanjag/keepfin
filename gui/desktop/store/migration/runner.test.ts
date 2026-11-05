import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {takeInventory} from './inventory.js'
import {buildPlan} from './plan.js'
import {InjectedCrash, readJournal, journalFile} from './journal.js'
import {abandonRun, resumeRun, startRun, type CommitContext, type RunDeps} from './runner.js'
import {copyHashing, hashFile, toLongPath} from './io.js'
import type {Choice, Plan, Progress} from './types.js'
import {listFiles, makeWorld, sha, type World} from './fixtures.js'

type Harness = {
  deps: RunDeps
  log: {commits: number; trashed: string[]; paused: number; resumed: number; progress: Progress[]; commitContexts: CommitContext[]}
  /** Violations of "nothing is removed before commit" seen by the fake recycle bin. */
  violations: string[]
}

function harness(world: World, overrides: Partial<RunDeps> = {}, options: {crossVolume?: boolean; trashFails?: boolean; onCommit?: () => Promise<void>} = {}): Harness {
  const log: Harness['log'] = {commits: 0, trashed: [], paused: 0, resumed: 0, progress: [], commitContexts: []}
  const violations: string[] = []
  const deps: RunDeps = {
    commit: async (_plan, context) => {
      log.commits++
      log.commitContexts.push(context)
      await options.onCommit?.()
    },
    trash: async (file) => {
      // The fake recycle bin checks the journal: nothing may be sent here before the switch point.
      const text = await fs.readFile(journalFile(world.runDir), 'utf8').catch(() => '')
      if (!text.includes('"event":"committed"')) violations.push(file)
      log.trashed.push(file)
      if (options.trashFails) throw new Error('recycle bin unavailable')
      await fs.rm(file, {force: true})
    },
    pause: () => void log.paused++,
    resume: () => void log.resumed++,
    onProgress: (progress) => log.progress.push(progress),
    sameVolume: async () => !options.crossVolume,
    ...overrides,
  }
  return {deps, log, violations}
}

async function makePlan(world: World, choices: Record<string, Choice> = {music: 'move', downloads: 'copy'}): Promise<Plan> {
  const inventory = await takeInventory(world.deviceDir)
  let n = 0
  return buildPlan(inventory, {target: world.target, choices, newId: () => `uuid-${++n}`, runId: 'run1', now: () => 1, ignore: []})
}

async function assertFinalTree(world: World, plan: Plan, cross: boolean) {
  const kftmp = (await listFiles(world.root)).filter((file) => file.endsWith('.kftmp'))
  assert.deepEqual(kftmp, [], 'no temporary files are left')
  const targetFiles = await listFiles(world.target)
  for (const file of plan.files) {
    const present = targetFiles.filter((candidate) => candidate === file.dest)
    assert.equal(present.length, 1, `${file.rel} exists exactly once`)
    const original = world.originals.get(file.source)
    if (original) assert.equal(await hashFile(file.dest), original, `${file.rel} has the original content`)
    const sourceStays = file.action === 'copy' || file.kind === 'art'
    const stat = await fs.stat(file.source).then(() => true, () => false)
    assert.equal(stat, sourceStays, `${file.source} ${sourceStays ? 'is kept' : 'is gone'}`)
  }
  // Every original hash appears exactly once in the target (nothing duplicated, nothing lost).
  const hashes = new Map<string, number>()
  for (const file of targetFiles) {
    if (file.includes(`${path.sep}.keepfin${path.sep}`)) continue
    const hash = await hashFile(file)
    hashes.set(hash, (hashes.get(hash) ?? 0) + 1)
  }
  for (const [source, hash] of world.originals) if (plan.mapping.paths[source]) assert.equal(hashes.get(hash), 1, `${path.basename(source)} arrived once`)
  void cross
}

test('a full run moves by rename, verifies, commits, and leaves everything in place', async () => {
  const world = await makeWorld()
  try {
    const plan = await makePlan(world)
    const h = harness(world)
    const result = await startRun(world.runDir, plan, h.deps)
    assert.equal(result.status, 'completed')
    assert.equal(result.committed, true)
    assert.equal(h.log.commits, 1)
    assert.equal(h.log.paused, 1)
    assert.equal(h.log.resumed, 1)
    assert.deepEqual(h.log.trashed, [], 'a rename leaves nothing to remove')
    await assertFinalTree(world, plan, false)
    const fold = await readJournal(world.runDir)
    assert.ok([...fold.files.values()].every((file) => file.state === 'done'))
    // The backup holds the old library files, outside the Keepfin folder.
    assert.ok((await fs.readdir(path.join(world.runDir, 'backup'))).includes('library.json'))
    assert.ok((await fs.readdir(path.join(world.runDir, 'backup'))).includes('queue.json'))
    assert.equal((await listFiles(world.target)).some((file) => file.endsWith('queue.json')), false)
  } finally {
    await world.cleanup()
  }
})

test('a cross-volume move copies, hashes, commits, and only then sends originals to the Recycle Bin', async () => {
  const world = await makeWorld()
  try {
    const plan = await makePlan(world)
    const sourcesBeforeCommit: boolean[] = []
    const h = harness(world, {}, {
      crossVolume: true,
      onCommit: async () => {
        for (const file of plan.files) sourcesBeforeCommit.push(await fs.stat(file.source).then(() => true, () => false))
      },
    })
    const result = await startRun(world.runDir, plan, h.deps)
    assert.equal(result.status, 'completed')
    assert.ok(sourcesBeforeCommit.length > 0 && sourcesBeforeCommit.every(Boolean), 'every original still existed at the switch point')
    assert.deepEqual(h.violations, [])
    const moved = plan.files.filter((file) => file.action === 'move')
    assert.deepEqual(h.log.trashed.sort(), moved.map((file) => file.source).sort())
    await assertFinalTree(world, plan, true)
    const fold = await readJournal(world.runDir)
    const copied = [...fold.files.values()].find((file) => file.mode === 'copy')
    assert.match(copied?.srcHash ?? '', /^[0-9a-f]{64}$/)
    assert.equal(copied?.srcHash, copied?.destHash)
  } finally {
    await world.cleanup()
  }
})

for (const cross of [false, true]) {
  for (const when of ['after', 'before'] as const) {
    test(`fault injection: crash ${when} every journal write (${cross ? 'cross-volume copy' : 'same-volume rename'}), resume, nothing lost`, async () => {
      // Count the writes of a clean run first.
      const probe = await makeWorld({loose: false})
      let total = 0
      try {
        const plan = await makePlan(probe)
        const result = await startRun(probe.runDir, plan, harness(probe, {}, {crossVolume: cross}).deps)
        assert.equal(result.status, 'completed')
        total = (await fs.readFile(journalFile(probe.runDir), 'utf8')).split('\n').filter(Boolean).length
      } finally {
        await probe.cleanup()
      }
      assert.ok(total >= 10)
      let crashes = 0
      // With the batch writes, lines are fewer than writes: sweeping to 3x the line count covers every write.
      for (let n = 1; n <= total * 3; n++) {
        const world = await makeWorld({loose: false})
        try {
          const plan = await makePlan(world)
          const first = harness(world, {crash: {[when]: n}}, {crossVolume: cross})
          let crashed = false
          try {
            const result = await startRun(world.runDir, plan, first.deps)
            assert.equal(result.status, 'completed', 'a run that never reached write n simply finishes')
          } catch (error) {
            assert.ok(error instanceof InjectedCrash, `unexpected error at write ${n}: ${String(error)}`)
            crashed = true
            crashes++
          }
          assert.equal(first.log.resumed, 1, 'writers are resumed even after a crash')
          assert.deepEqual(first.violations, [])
          if (!crashed) {
            await assertFinalTree(world, plan, cross)
            break
          }
          // Nothing was removed before the switch point.
          const mid = await readJournal(world.runDir)
          if (cross && !mid.run.committed) {
            for (const file of plan.files) await fs.stat(file.source)
          }
          const second = harness(world, {}, {crossVolume: cross})
          const result = await resumeRun(world.runDir, second.deps)
          assert.equal(result.status, 'completed', `resume after write ${n}`)
          assert.equal(result.committed, true)
          assert.deepEqual(result.failed, [])
          assert.deepEqual(second.violations, [])
          await assertFinalTree(world, plan, cross)
        } finally {
          await world.cleanup()
        }
      }
      assert.ok(crashes >= 20, `swept ${crashes} crash points`)
    })
  }
}

test('rename recovery looks at both paths: renamed but not journaled, and intent recorded but not renamed', async () => {
  for (const scenario of ['renamed-not-journaled', 'intent-only']) {
    const world = await makeWorld({loose: false})
    try {
      const plan = await makePlan(world, {music: 'move', downloads: 'move'})
      const first = plan.files[0]
      // Write 1 is "started", write 2 is the intent, write 3 is "copied".
      const crash = scenario === 'renamed-not-journaled' ? {before: 3} : {after: 2}
      await assert.rejects(startRun(world.runDir, plan, harness(world, {crash}).deps), InjectedCrash)
      const sourceThere = await fs.stat(first.source).then(() => true, () => false)
      const destThere = await fs.stat(first.dest).then(() => true, () => false)
      assert.equal(sourceThere, scenario === 'intent-only')
      assert.equal(destThere, scenario === 'renamed-not-journaled')
      const result = await resumeRun(world.runDir, harness(world).deps)
      assert.equal(result.status, 'completed')
      await assertFinalTree(world, plan, false)
    } finally {
      await world.cleanup()
    }
  }
})

test('a rename found at both paths is a failure to review, never an overwrite', async () => {
  const world = await makeWorld({loose: false})
  try {
    const plan = await makePlan(world, {music: 'move', downloads: 'move'})
    const first = plan.files[0]
    await assert.rejects(startRun(world.runDir, plan, harness(world, {crash: {after: 2}}).deps), InjectedCrash)
    await fs.mkdir(path.dirname(first.dest), {recursive: true})
    await fs.writeFile(first.dest, 'somebody else')
    const result = await resumeRun(world.runDir, harness(world).deps)
    assert.equal(result.status, 'needs-decision')
    assert.equal(result.failed.length, 1)
    assert.equal(await fs.readFile(first.dest, 'utf8'), 'somebody else')
    assert.equal(sha(await fs.readFile(first.source)), world.originals.get(first.source))
  } finally {
    await world.cleanup()
  }
})

test('a source that changed after it was copied is never removed, and is reported', async () => {
  const world = await makeWorld({loose: false})
  try {
    const plan = await makePlan(world)
    const victim = plan.files.find((file) => file.rel === 'Music/Song 2.mp3')
    assert.ok(victim)
    const h = harness(world, {}, {
      crossVolume: true,
      onCommit: async () => {
        await fs.appendFile(victim.source, ' edited after the copy')
      },
    })
    const result = await startRun(world.runDir, plan, h.deps)
    assert.equal(result.status, 'completed')
    assert.equal(result.failed.length, 1)
    assert.equal(result.failed[0].source, victim.source)
    assert.match(result.failed[0].reason, /changed/)
    assert.equal(await fs.readFile(victim.source, 'utf8'), `${(await fs.readFile(victim.dest, 'utf8'))} edited after the copy`)
    assert.ok(!h.log.trashed.includes(victim.source))
  } finally {
    await world.cleanup()
  }
})

test('when the Recycle Bin refuses, an original is deleted only after a second full hash check', async () => {
  const world = await makeWorld({loose: false})
  try {
    // Unchanged originals: deleted after the hash check.
    const plan = await makePlan(world)
    const h = harness(world, {}, {crossVolume: true, trashFails: true})
    const result = await startRun(world.runDir, plan, h.deps)
    assert.equal(result.status, 'completed')
    assert.deepEqual(result.failed, [])
    for (const file of plan.files.filter((item) => item.action === 'move')) assert.equal(await fs.stat(file.source).then(() => true, () => false), false)
  } finally {
    await world.cleanup()
  }

  // An original whose content changed but whose size and modified time look the same is kept.
  const second = await makeWorld({loose: false})
  try {
    const plan = await makePlan(second)
    const victim = plan.files.find((file) => file.rel === 'Music/Song 2.mp3')
    assert.ok(victim)
    const h = harness(second, {}, {
      crossVolume: true,
      trashFails: true,
      onCommit: async () => {
        const before = await fs.stat(victim.source)
        const original = await fs.readFile(victim.source)
        original[0] = original[0] ^ 0xff
        await fs.writeFile(victim.source, original)
        await fs.utimes(victim.source, before.atime, before.mtime)
      },
    })
    const result = await startRun(second.runDir, plan, h.deps)
    assert.equal(result.failed.length, 1)
    assert.equal(result.failed[0].source, victim.source)
    assert.equal(await fs.stat(victim.source).then(() => true, () => false), true, 'the changed original stays')
  } finally {
    await second.cleanup()
  }
})

test('a full disk stops the run before commit with a typed result, and resuming continues', async () => {
  const world = await makeWorld({loose: false})
  try {
    const plan = await makePlan(world)
    let calls = 0
    const h = harness(world, {
      copyFile: async (source, dest, options) => {
        if (++calls === 3) throw Object.assign(new Error('no space left on device'), {code: 'ENOSPC'})
        return copyHashing(source, dest, options)
      },
    }, {crossVolume: true})
    const result = await startRun(world.runDir, plan, h.deps)
    assert.equal(result.status, 'no-space')
    assert.equal(result.committed, false)
    assert.equal(h.log.commits, 0)
    assert.equal(h.log.resumed, 1)
    assert.deepEqual(h.violations, [])
    assert.deepEqual((await listFiles(world.root)).filter((file) => file.endsWith('.kftmp')), [])
    for (const file of plan.files) assert.ok(await fs.stat(file.source), 'originals untouched')
    const fold = await readJournal(world.runDir)
    assert.equal(fold.run.stopped, 'no-space')
    assert.ok([...fold.files.values()].every((file) => file.state !== 'failed'), 'the file that hit the full disk is not marked failed')

    const resumed = await resumeRun(world.runDir, harness(world, {}, {crossVolume: true}).deps)
    assert.equal(resumed.status, 'completed')
    await assertFinalTree(world, plan, true)
  } finally {
    await world.cleanup()
  }
})

test('a failed file does not stop the others; commit waits for a decision unless failures are accepted', async () => {
  const world = await makeWorld({loose: false})
  try {
    const plan = await makePlan(world)
    const vanished = plan.files.find((file) => file.rel === 'Music/Song 2.mp3')
    assert.ok(vanished)
    await fs.rm(vanished.source)
    const first = harness(world, {}, {crossVolume: true})
    const result = await startRun(world.runDir, plan, first.deps)
    assert.equal(result.status, 'needs-decision')
    assert.equal(first.log.commits, 0, 'commit does not run while failures wait for a decision')
    assert.equal(result.failed.length, 1)
    assert.equal(result.failed[0].reason, 'The file is no longer there.')
    assert.deepEqual(first.violations, [])
    const fold = await readJournal(world.runDir)
    const verified = [...fold.files.values()].filter((file) => file.state === 'verified').length
    assert.equal(verified, plan.files.length - 1, 'the others carried on')

    const second = harness(world, {}, {crossVolume: true})
    const accepted = await resumeRun(world.runDir, second.deps, {acceptFailures: true})
    assert.equal(accepted.status, 'completed')
    assert.equal(second.log.commits, 1)
    assert.equal(second.log.commitContexts[0].failed.length, 1)
    assert.equal(second.log.commitContexts[0].states.get(vanished.id)?.state, 'failed')
    assert.equal(accepted.failed.length, 1)
  } finally {
    await world.cleanup()
  }
})

test('retrying a failed file brings it over once it is back', async () => {
  const world = await makeWorld({loose: false})
  try {
    const plan = await makePlan(world)
    const target = plan.files.find((file) => file.rel === 'Music/Song 2.mp3')
    assert.ok(target)
    const content = await fs.readFile(target.source)
    await fs.rm(target.source)
    const stalled = await startRun(world.runDir, plan, harness(world, {}, {crossVolume: true}).deps)
    assert.equal(stalled.status, 'needs-decision')
    await fs.writeFile(target.source, content)
    const stat = await fs.stat(target.source)
    await fs.utimes(target.source, stat.atime, new Date(target.mtimeMs))
    const done = await resumeRun(world.runDir, harness(world, {}, {crossVolume: true}).deps, {retryFailed: true})
    assert.equal(done.status, 'completed')
    assert.deepEqual(done.failed, [])
    await assertFinalTree(world, plan, true)
  } finally {
    await world.cleanup()
  }
})

test('a source that changed before it was copied is re-inventoried and recorded', async () => {
  const world = await makeWorld({loose: false})
  try {
    const plan = await makePlan(world)
    const edited = plan.files.find((file) => file.rel === 'Music/Song 2.mp3')
    assert.ok(edited)
    await fs.appendFile(edited.source, ' more')
    const result = await startRun(world.runDir, plan, harness(world, {}, {crossVolume: true}).deps)
    assert.equal(result.status, 'completed')
    const fold = await readJournal(world.runDir)
    assert.equal(fold.files.get(edited.id)?.reinventoried, true)
    assert.match(await fs.readFile(edited.dest, 'utf8'), / more$/)
  } finally {
    await world.cleanup()
  }
})

test('abandoning before commit leaves every original untouched and lists the copies', async () => {
  const world = await makeWorld({loose: false})
  try {
    const plan = await makePlan(world)
    // Crash well into the copying phase (cross-volume).
    await assert.rejects(startRun(world.runDir, plan, harness(world, {crash: {after: 6}}, {crossVolume: true}).deps), InjectedCrash)
    const h = harness(world, {}, {crossVolume: true})
    const result = await abandonRun(world.runDir, h.deps)
    assert.equal(result.status, 'abandoned')
    assert.ok(result.copies.length >= 2)
    assert.deepEqual(result.restored, [])
    assert.equal(h.log.resumed, 1)
    for (const [file, hash] of world.originals) assert.equal(await hashFile(file), hash, `${path.basename(file)} untouched`)
    for (const copy of result.copies) assert.ok(await fs.stat(copy.path), 'copies are listed, not deleted')
    assert.deepEqual((await listFiles(world.root)).filter((file) => file.endsWith('.kftmp')), [])
    assert.equal(h.log.trashed.length, 0)
    // A resumed run does not continue an abandoned one.
    const again = await resumeRun(world.runDir, harness(world).deps)
    assert.equal(again.status, 'abandoned')
    assert.equal(h.log.commits, 0)
  } finally {
    await world.cleanup()
  }
})

test('abandoning a run that moved by rename puts the files back, and abandoning after commit is refused', async () => {
  const world = await makeWorld({loose: false})
  try {
    const plan = await makePlan(world, {music: 'move', downloads: 'move'})
    await assert.rejects(startRun(world.runDir, plan, harness(world, {crash: {after: 8}}).deps), InjectedCrash)
    const moved = (await listFiles(world.target)).filter((file) => !file.includes('.keepfin'))
    assert.ok(moved.length > 0)
    const result = await abandonRun(world.runDir, harness(world).deps)
    assert.ok(result.restored.length > 0)
    assert.deepEqual(result.problems, [])
    for (const [file, hash] of world.originals) assert.equal(await hashFile(file), hash, `${path.basename(file)} is back`)
  } finally {
    await world.cleanup()
  }

  const committed = await makeWorld({loose: false})
  try {
    const plan = await makePlan(committed)
    await startRun(committed.runDir, plan, harness(committed).deps)
    await assert.rejects(abandonRun(committed.runDir, harness(committed).deps), /cannot be abandoned/)
  } finally {
    await committed.cleanup()
  }
})

test('writers are fenced around the run and always resumed, even when commit throws', async () => {
  const world = await makeWorld({loose: false})
  try {
    const plan = await makePlan(world)
    const h = harness(world, {
      commit: async () => {
        throw new Error('disk problem while saving the library')
      },
    })
    await assert.rejects(startRun(world.runDir, plan, h.deps), /disk problem/)
    assert.equal(h.log.paused, 1)
    assert.equal(h.log.resumed, 1)
    const fold = await readJournal(world.runDir)
    assert.equal(fold.run.committed, false, 'the old library stays authoritative')
  } finally {
    await world.cleanup()
  }
})

test('progress is throttled to 4 Hz', async () => {
  const world = await makeWorld({loose: false})
  try {
    const plan = await makePlan(world)
    const h = harness(world, {now: () => 5_000_000}, {crossVolume: true})
    await startRun(world.runDir, plan, h.deps)
    assert.equal(h.log.progress.filter((item) => item.phase === 'copying').length, 1, 'a frozen clock allows one update')
    const last = h.log.progress[h.log.progress.length - 1]
    assert.equal(last.phase, 'done')
    assert.equal(last.total, plan.files.length)

    const world2 = await makeWorld({loose: false})
    try {
      let time = 0
      const plan2 = await makePlan(world2)
      const h2 = harness(world2, {now: () => (time += 100)}, {crossVolume: true})
      await startRun(world2.runDir, plan2, h2.deps)
      const copying = h2.log.progress.filter((item) => item.phase === 'copying')
      for (let index = 1; index < copying.length; index++) assert.ok(copying.length < time / 250 + 2)
      assert.ok(copying.every((item) => item.bytesTotal > 0))
    } finally {
      await world2.cleanup()
    }
  } finally {
    await world.cleanup()
  }
})

test('long paths get the extended prefix on Windows only', () => {
  const long = `C:\\${'a'.repeat(150)}\\${'b'.repeat(120)}\\song.mp3`
  assert.ok(long.length >= 260)
  assert.equal(toLongPath(long, 'win32'), `\\\\?\\${long}`)
  assert.equal(toLongPath('C:\\short\\song.mp3', 'win32'), 'C:\\short\\song.mp3')
  assert.equal(toLongPath(`\\\\?\\${long}`, 'win32'), `\\\\?\\${long}`)
  assert.equal(toLongPath(`\\\\server\\share\\${'c'.repeat(260)}`, 'win32'), `\\\\?\\UNC\\server\\share\\${'c'.repeat(260)}`)
  assert.equal(toLongPath(long, 'linux'), long)
})
