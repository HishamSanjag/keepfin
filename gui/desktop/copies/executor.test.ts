import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {afterEach, describe, it} from 'node:test'
import type {DesiredEntry} from '../../shared/core/copies.js'
import {baselineFile, readBaseline} from './baseline.js'
import {CrashError} from './util.js'
import {readGeneration, writeRelationship, setGeneration} from './generation.js'
import {hashFile} from './hash.js'
import {latestManifest} from './manifest.js'
import {archived, desiredFromMain, leftovers, makeWorld, type World} from './testkit.js'

const worlds: World[] = []
const world = async () => {
  const w = await makeWorld()
  worlds.push(w)
  return w
}
afterEach(async () => {
  while (worlds.length) await worlds.pop()!.cleanup()
})

const count = (r: {exec: {byKind: Record<string, number>} | null}, kind: string) => r.exec?.byKind[kind] ?? 0

describe('a backup run', () => {
  it('copies a new relationship additively, then plans nothing, and writes the manifest and the baseline', async () => {
    const w = await world()
    await w.write('main', 'Music/a.mp3', 'alpha')
    await w.write('main', 'Music/Sub/b.mp3', 'bravo')
    await w.write('main', 'Videos/c.mp4', 'charlie')
    const first = await w.run()
    assert.equal(first.status, 'done')
    assert.equal(count(first, 'CopyTo'), 3)
    assert.equal(first.additiveOnly, true)
    assert.equal(await w.read('copy', 'Music/Sub/b.mp3'), 'bravo')
    assert.deepEqual(await leftovers(w.root), [])
    assert.equal(first.generation.committedAt, 1)
    assert.equal(await readGeneration(w.copy), 1)
    assert.equal(first.manifest?.files, 3)
    const second = await w.run()
    assert.equal(second.status, 'done')
    assert.equal(second.summary?.destructive, 0)
    assert.equal(second.exec, null, 'an up-to-date copy plans no actions')
    assert.equal(await readGeneration(w.copy), 1, 'a run that changes nothing does not bump the generation')
    const manifest = await latestManifest(w.copy, 'place-1')
    assert.ok(manifest)
    assert.equal(manifest.header.generation, 1)
    assert.equal([...manifest.lines()].length, 3)
    const entries = [...readBaseline(w.state)]
    assert.equal(entries.length, 3)
    assert.ok(entries.every((e) => e.copy.size > 0 && e.generation === 1))
  })

  it('replaces a changed file after moving the old bytes to versions, and never deletes anything outright', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'one')
    await w.run({previewConfirmed: true})
    await w.write('main', 'a.mp3', 'two!!', new Date('2021-01-01'))
    const r = await w.run({previewConfirmed: true})
    assert.equal(count(r, 'CopyTo'), 1)
    assert.equal(await w.read('copy', 'a.mp3'), 'two!!')
    const kept = await archived(w.copy)
    assert.deepEqual([...kept.values()], ['one'])
    assert.ok([...kept.keys()][0].startsWith('versions/item_a.mp3/'))
    const again = await w.run({previewConfirmed: true})
    assert.equal(again.exec, null)
  })

  it('keeps what main deleted on a backup, and the mirror moves it to trash', async () => {
    const w = await world()
    await w.write('main', 'keep.mp3', 'k')
    await w.write('main', 'gone.mp3', 'g')
    await w.run({previewConfirmed: true, mode: 'mirror'})
    await w.remove('main', 'gone.mp3')
    const r = await w.run({previewConfirmed: true, mode: 'mirror', deleted: ['item:gone.mp3'], deleteGuardPercent: 100})
    assert.equal(count(r, 'RemoveFromCopy'), 1)
    assert.equal(await w.read('copy', 'gone.mp3'), null)
    const kept = await archived(w.copy)
    assert.deepEqual([...kept.entries()].map(([k, v]) => [k.split('/')[0], v]), [['trash', 'g']])
    // a backup keeps it where it is
    const b = await world()
    await b.write('main', 'gone.mp3', 'g')
    await b.write('main', 'keep.mp3', 'k')
    await b.run({previewConfirmed: true})
    await b.remove('main', 'gone.mp3')
    const br = await b.run({previewConfirmed: true, deleted: ['item:gone.mp3']})
    assert.equal(br.exec, null)
    assert.equal(await b.read('copy', 'gone.mp3'), 'g')
    assert.ok(br.review.some((x) => x.kind === 'KeptOnBackup'))
  })

  it('follows a library rename with a move on the copy', async () => {
    const w = await world()
    await w.write('main', 'old name.mp3', 'song')
    await w.run({previewConfirmed: true})
    await fs.rename(path.join(w.main, 'old name.mp3'), path.join(w.main, 'new name.mp3'))
    const r = await w.run({
      previewConfirmed: true,
      desiredFrom: (await desiredFromMain(w.main, () => 'item:old name.mp3')) as DesiredEntry[],
      placementMoves: [{itemId: 'item:old name.mp3', from: 'old name.mp3', to: 'new name.mp3'}],
    })
    assert.equal(count(r, 'MoveOnCopy'), 1)
    assert.equal(await w.read('copy', 'new name.mp3'), 'song')
    assert.equal(await w.read('copy', 'old name.mp3'), null)
    assert.equal(count(r, 'CopyTo'), 0, 'the bytes were not copied again')
  })

  it('converges a file both sides already hold, and forgets a baseline entry for a deleted item', async () => {
    const w = await world()
    await w.write('main', 'same.mp3', 'identical')
    await w.write('copy', 'same.mp3', 'identical')
    const r = await w.run({previewConfirmed: true})
    assert.equal(count(r, 'Converge'), 1)
    assert.equal(count(r, 'CopyTo'), 0)
    assert.equal([...readBaseline(w.state)].length, 1)
    // the item is deleted and gone from the copy: only the baseline entry is dropped
    await w.remove('main', 'same.mp3')
    await w.remove('copy', 'same.mp3')
    const f = await w.run({previewConfirmed: true, deleted: ['item:same.mp3']})
    assert.equal(count(f, 'ForgetBaseline'), 1)
    assert.equal([...readBaseline(w.state)].length, 0)
  })
})

describe('a two-way run', () => {
  it('restores a file main lost from the copy, imports a new copy-side file and revises an edit', async () => {
    const w = await world()
    await w.write('main', 'lost.mp3', 'lost bytes')
    await w.write('main', 'edit.mp3', 'v1')
    await w.run({previewConfirmed: true, mode: 'twoWay'})
    const desired = await desiredFromMain(w.main)
    await w.remove('main', 'lost.mp3')
    await w.write('copy', 'edit.mp3', 'v2 edited', new Date('2022-02-02'))
    await w.write('copy', 'new.mp3', 'only on the copy', new Date('2022-02-02'))
    const r = await w.run({previewConfirmed: true, mode: 'twoWay', desiredFrom: desired})
    assert.equal(count(r, 'RestoreFromCopy'), 1)
    assert.equal(count(r, 'ReviseFromCopy'), 1)
    assert.equal(count(r, 'ImportNew'), 1)
    assert.equal(await w.read('main', 'lost.mp3'), 'lost bytes')
    assert.equal(await w.read('main', 'edit.mp3'), 'v2 edited')
    assert.equal(await w.read('main', 'new.mp3'), 'only on the copy')
    assert.deepEqual(w.hostLog.sort(), ['import new.mp3 -> new.mp3', 'revise edit.mp3'])
    const settled = await w.run({previewConfirmed: true, mode: 'twoWay'})
    assert.equal(settled.exec, null)
  })

  it('keeps both versions of an edit/edit conflict and takes a counter when the conflict name is taken', async () => {
    const w = await world()
    await w.write('main', 'both.mp3', 'base')
    await w.run({previewConfirmed: true, mode: 'twoWay'})
    await w.write('main', 'both.mp3', 'main side edit', new Date('2022-03-03'))
    await w.write('copy', 'both.mp3', 'copy side edit', new Date('2022-03-04'))
    const copyHash = (await hashFile(path.join(w.copy, 'both.mp3'))).contentId
    const tag = copyHash.slice('sha256:'.length, 'sha256:'.length + 8)
    const taken = `both (conflict ${tag}).mp3`
    await w.write('main', taken, 'somebody else holds this name')
    const r = await w.run({previewConfirmed: true, mode: 'twoWay', desiredFrom: (await desiredFromMain(w.main)).filter((d) => d.path === 'both.mp3') })
    assert.equal(count(r, 'ImportNew'), 1)
    assert.equal(await w.read('main', taken), 'somebody else holds this name', 'an existing file is never overwritten')
    assert.equal(await w.read('main', `both (conflict ${tag}) (2).mp3`), 'copy side edit')
    assert.equal(await w.read('copy', 'both.mp3'), 'main side edit')
    const trash = await archived(w.copy)
    assert.deepEqual([...trash.values()], ['copy side edit'], 'the replaced copy bytes are also in the copy trash')
  })

  it('restores an edit of a deleted item for review and turns a confirmed copy deletion into item.delete', async () => {
    const w = await world()
    await w.write('main', 'deleted.mp3', 'orig')
    await w.write('main', 'confirmed.mp3', 'conf')
    await w.run({previewConfirmed: true, mode: 'twoWay'})
    await w.remove('main', 'deleted.mp3')
    await w.write('copy', 'deleted.mp3', 'edited after delete', new Date('2022-05-05'))
    await w.remove('copy', 'confirmed.mp3')
    const r = await w.run({previewConfirmed: true, mode: 'twoWay', deleted: ['item:deleted.mp3'], confirmedDeletes: ['confirmed.mp3']})
    assert.equal(count(r, 'RestoreEditedDeleted'), 1)
    assert.equal(count(r, 'DeleteItem'), 1)
    assert.equal(await w.read('main', 'deleted.mp3'), 'edited after delete')
    assert.ok(w.hostLog.includes('delete confirmed.mp3'))
    assert.ok(w.hostLog.includes('restoreEdited deleted.mp3'))
  })

  it('skips library-side actions when there is no host, touching nothing', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'a')
    await w.run({previewConfirmed: true, mode: 'twoWay'})
    await w.write('copy', 'extra.mp3', 'extra', new Date('2022-02-02'))
    const r = await w.run({previewConfirmed: true, mode: 'twoWay', host: undefined})
    assert.equal(r.exec?.skipped[0]?.reason, 'needs-library')
    assert.equal(await w.read('copy', 'extra.mp3'), 'extra')
    assert.equal(await w.read('main', 'extra.mp3'), null)
  })
})

describe('preconditions', () => {
  it('skips an action whose destination changed after planning and never overwrites it, then the replan handles it', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'one')
    await w.run({previewConfirmed: true})
    await w.write('main', 'a.mp3', 'two', new Date('2023-01-01'))
    let tampered = false
    const r = await w.run({
      previewConfirmed: true,
      maxRounds: 1,
      fault: async (step) => {
        if (step === 'intent' && !tampered) {
          tampered = true
          await w.write('copy', 'a.mp3', 'someone edited the copy meanwhile', new Date('2023-02-02'))
        }
      },
    })
    assert.equal(r.exec?.skipped[0]?.reason, 'unexpected-destination')
    assert.equal(r.exec?.needsReplan, true)
    assert.equal(await w.read('copy', 'a.mp3'), 'someone edited the copy meanwhile', 'nothing was overwritten')
    assert.deepEqual(await leftovers(w.root), [])
    // the next plan sees the copy's own edit and replaces it, preserving it in versions
    const next = await w.run({previewConfirmed: true})
    assert.equal(await w.read('copy', 'a.mp3'), 'two')
    const kept = [...(await archived(w.copy)).values()]
    assert.ok(kept.includes('someone edited the copy meanwhile'))
    assert.equal(count(next, 'CopyTo'), 1)
  })

  it('rejects mixed bytes: a source that changes while it is copied no longer matches its pinned hash', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'original bytes')
    const desired = await desiredFromMain(w.main)
    await w.write('main', 'a.mp3', 'DIFFERENT bytes', new Date('2024-01-01'))
    const r = await w.run({previewConfirmed: true, desiredFrom: desired, maxRounds: 1})
    assert.equal(r.exec?.skipped[0]?.reason, 'hash-mismatch')
    assert.equal(await w.read('copy', 'a.mp3'), null)
    assert.deepEqual(await leftovers(w.root), [])
  })
})

describe('disconnects', () => {
  it('a source that vanishes is an absence, not a disconnect: the action is skipped and the rest continues', async () => {
    const w = await world()
    for (const n of ['a', 'b', 'c']) await w.write('main', `${n}.mp3`, `content ${n}`)
    const r = await w.run({
      previewConfirmed: true,
      maxRounds: 1,
      parallelism: 1,
      fault: async (step, {action}) => {
        if (step === 'intent' && action.path === 'b.mp3') await w.remove('main', 'b.mp3')
      },
    })
    assert.equal(r.status, 'done')
    assert.deepEqual(r.exec?.skipped.map((s) => [s.path, s.reason]), [['b.mp3', 'source-missing']])
    assert.equal(await w.read('copy', 'a.mp3'), 'content a')
    assert.equal(await w.read('copy', 'c.mp3'), 'content c')
    assert.equal(await w.read('copy', 'b.mp3'), null)
    assert.deepEqual(await leftovers(w.root), [])
  })

  it('an unplugged copy pauses the run as disconnected and the next run resumes', async () => {
    const w = await world()
    for (const n of ['a', 'b', 'c', 'd']) await w.write('main', `${n}.mp3`, `content ${n}`)
    const away = `${w.copy}-unplugged`
    const r = await w.run({
      previewConfirmed: true,
      maxRounds: 1,
      parallelism: 1,
      fault: async (step, {action}) => {
        if (step === 'intent' && action.path === 'c.mp3') await fs.rename(w.copy, away)
      },
    })
    assert.equal(r.status, 'disconnected')
    await fs.rename(away, w.copy)
    const again = await w.run({previewConfirmed: true})
    assert.equal(again.status, 'done')
    for (const n of ['a', 'b', 'c', 'd']) assert.equal(await w.read('copy', `${n}.mp3`), `content ${n}`)
    assert.deepEqual(await leftovers(w.root), [])
    assert.equal((await w.run({previewConfirmed: true})).exec, null)
  })

  it('a copy that is not there at the start is disconnected, not an error', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'a')
    await fs.rm(w.copy, {recursive: true})
    assert.equal((await w.run()).status, 'disconnected')
  })
})

describe('crash and resume', () => {
  const steps = ['intent', 'transfer', 'staged', 'preserve-before-move', 'preserved', 'before-publish', 'published', 'committed']

  for (const step of steps) {
    it(`a crash at "${step}" while replacing a file loses no bytes and the next run finishes the job`, async () => {
      const w = await world()
      await w.write('main', 'a.mp3', 'version one')
      await w.write('main', 'b.mp3', 'bee')
      await w.run({previewConfirmed: true})
      await w.write('main', 'a.mp3', 'version two', new Date('2024-04-04'))
      await w.write('main', 'c.mp3', 'sea')
      let hit = false
      const crashed = await w.run({
        previewConfirmed: true,
        parallelism: 1,
        fault: (s, {action}) => {
          if (s === step && !hit && action.path === 'a.mp3') {
            hit = true
            throw new CrashError(s)
          }
        },
      })
      if (hit) assert.equal(crashed.status, 'crashed')
      // nothing is half-written where a reader would see it
      const nowCopy = await w.read('copy', 'a.mp3')
      assert.ok(nowCopy === null || nowCopy === 'version one' || nowCopy === 'version two', `copy has ${nowCopy}`)
      const resumed = await w.run({previewConfirmed: true})
      assert.equal(resumed.status, 'done')
      assert.ok(resumed.recovery === null || resumed.recovery.unresolved >= 0)
      assert.equal(await w.read('copy', 'a.mp3'), 'version two')
      assert.equal(await w.read('copy', 'b.mp3'), 'bee')
      assert.equal(await w.read('copy', 'c.mp3'), 'sea')
      assert.deepEqual(await leftovers(w.root), [])
      const kept = [...(await archived(w.copy)).values()]
      assert.ok(kept.includes('version one'), 'the replaced bytes survive in versions')
      assert.equal((await w.run({previewConfirmed: true})).exec, null, 'and the plan is empty afterwards')
    })
  }

  for (const step of ['intent', 'staged', 'published', 'committed']) {
    it(`a crash at "${step}" while copying a new file leaves nothing half-written`, async () => {
      const w = await world()
      await w.write('main', 'new.mp3', 'brand new')
      let hit = false
      const crashed = await w.run({
        previewConfirmed: true,
        parallelism: 1,
        fault: (s) => {
          if (s === step && !hit) {
            hit = true
            throw new CrashError(s)
          }
        },
      })
      assert.equal(crashed.status, 'crashed')
      const mid = await w.read('copy', 'new.mp3')
      assert.ok(mid === null || mid === 'brand new')
      const resumed = await w.run({previewConfirmed: true})
      assert.equal(resumed.status, 'done')
      assert.equal(await w.read('copy', 'new.mp3'), 'brand new')
      assert.deepEqual(await leftovers(w.root), [])
      assert.equal((await w.run({previewConfirmed: true})).exec, null)
    })
  }

  for (const step of ['intent', 'preserve-before-move', 'preserved', 'committed']) {
    it(`a crash at "${step}" while removing to trash never loses the removed bytes`, async () => {
      const w = await world()
      await w.write('main', 'gone.mp3', 'precious')
      await w.write('main', 'stay.mp3', 'stay')
      await w.run({previewConfirmed: true, mode: 'mirror'})
      await w.remove('main', 'gone.mp3')
      let hit = false
      const fault = (s: string) => {
        if (s === step && !hit) {
          hit = true
          throw new CrashError(s)
        }
      }
      const opts = {previewConfirmed: true, mode: 'mirror' as const, deleted: ['item:gone.mp3'], deleteGuardPercent: 100}
      const crashed = await w.run({...opts, fault})
      assert.equal(crashed.status, 'crashed')
      const resumed = await w.run(opts)
      assert.equal(resumed.status, 'done')
      const everywhere = [...(await archived(w.copy)).values(), (await w.read('copy', 'gone.mp3')) ?? '']
      assert.ok(everywhere.includes('precious'), 'the bytes are on the copy or in its trash')
      assert.equal((await w.run(opts)).exec, null)
    })
  }

  it('a torn last line in the journal is ignored', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'a')
    let hit = false
    await w.run({
      previewConfirmed: true,
      fault: (s) => {
        if (s === 'staged' && !hit) {
          hit = true
          throw new CrashError(s)
        }
      },
    })
    await fs.appendFile(path.join(w.state, 'journal.jsonl'), '{"t":"committed","run":"x","n":0,"ops":[{"pa')
    const resumed = await w.run({previewConfirmed: true})
    assert.equal(resumed.status, 'done')
    assert.equal(await w.read('copy', 'a.mp3'), 'a')
  })
})

describe('lock and generation', () => {
  it('a fresh lock held by another run refuses, and a stale lock is taken over', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'a')
    const lock = path.join(w.copy, '.keepfin', 'lock')
    await fs.writeFile(lock, JSON.stringify({device: 'other computer', run: 'other', heartbeatAt: w.clock.t + 5 * 60_000 - 1000}))
    const busy = await w.run({previewConfirmed: true})
    assert.equal(busy.status, 'busy')
    assert.equal(await w.read('copy', 'a.mp3'), null)
    await fs.writeFile(lock, JSON.stringify({device: 'other computer', run: 'other', heartbeatAt: w.clock.t - 10 * 60_000}))
    const ok = await w.run({previewConfirmed: true})
    assert.equal(ok.status, 'done')
    assert.equal(await w.read('copy', 'a.mp3'), 'a')
    await assert.rejects(fs.access(lock), 'the lock is released')
  })

  it('a lower generation (a restored drive) suspends every destructive action', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'a')
    await w.write('main', 'b.mp3', 'b')
    await w.run({previewConfirmed: true, mode: 'mirror'})
    await w.run({previewConfirmed: true, mode: 'mirror'})
    await w.remove('main', 'b.mp3')
    await writeRelationship(w.state, {generation: 5, previewConfirmed: true}) // the relationship has seen more than the copy shows
    const r = await w.run({previewConfirmed: true, mode: 'mirror', deleted: ['item:b.mp3'], deleteGuardPercent: 100})
    assert.equal(r.generation.state, 'lower')
    assert.equal(await w.read('copy', 'b.mp3'), 'b', 'nothing was removed')
    assert.ok(r.review.some((x) => x.kind === 'Suspended'))
    assert.equal(r.manifest, null, 'and nothing is claimed as verified')
  })

  it('a higher generation (another computer synced) plans additively only', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'one')
    await w.run({previewConfirmed: true})
    await setGeneration(w.copy, 9)
    await w.write('main', 'a.mp3', 'two', new Date('2025-05-05'))
    await w.write('main', 'new.mp3', 'new')
    const r = await w.run({previewConfirmed: true})
    assert.equal(r.generation.state, 'higher')
    assert.equal(r.additiveOnly, true)
    assert.equal(await w.read('copy', 'a.mp3'), 'one', 'the replacement waits')
    assert.equal(await w.read('copy', 'new.mp3'), 'new', 'the addition happens')
  })

  it('refuses a copy nested in the Keepfin folder', async () => {
    const w = await world()
    const inner = path.join(w.main, 'inner-copy')
    await fs.mkdir(path.join(inner, '.keepfin'), {recursive: true})
    await fs.writeFile(path.join(inner, '.keepfin', 'folder.json'), '{}')
    const r = await w.run({copyRoot: inner})
    assert.equal(r.status, 'refused')
  })
})

describe('names', () => {
  it('maps names the file system cannot take, durably, and the inventory reads them back', async () => {
    const w = await world()
    await w.write('main', 'Music/CON.mp3', 'reserved')
    await w.write('main', 'Music/ends with dot.', 'dot')
    const first = await w.run({previewConfirmed: true})
    assert.equal(first.status, 'done')
    assert.equal(await w.read('copy', 'Music/_CON.mp3'), 'reserved')
    const map = await fs.readFile(path.join(w.copy, '.keepfin', 'names.v1.jsonl'), 'utf8')
    assert.match(map, /"lib":"Music\/CON.mp3"/)
    const second = await w.run({previewConfirmed: true})
    assert.equal(second.exec, null, 'the copy is recognised through the map')
  })
})

describe('baseline', () => {
  it('has one entry per name and survives a rerun of the same journal', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'a')
    await w.run({previewConfirmed: true, sortChunk: 1})
    const before = await fs.readFile(baselineFile(w.state), 'utf8')
    const { foldCommits } = await import('./baseline.js')
    await fs.copyFile(path.join(w.state, 'journal.prev.jsonl'), path.join(w.state, 'journal.jsonl'))
    await foldCommits({stateDir: w.state, journalFile: path.join(w.state, 'journal.jsonl'), rules: 'ascii', tmpDir: w.root})
    assert.equal(await fs.readFile(baselineFile(w.state), 'utf8'), before)
  })
})
