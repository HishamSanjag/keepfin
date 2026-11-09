// Renames that travel as moves (#29) and the safety checks of the desktop executor (#30): names that look the same, files only in the
// cloud, the 4 GiB limit of FAT, a changed clock and the audit that follows it.
import assert from 'node:assert/strict'
import {execFileSync} from 'node:child_process'
import fs from 'node:fs/promises'
import path from 'node:path'
import {afterEach, describe, it} from 'node:test'
import {ClockShift, auditDue, fastKey, fileSizeLimit, isOnlineOnly, nameKey, type DesiredEntry} from '../../shared/core/copies.js'
import {readBaseline} from './baseline.js'
import {readRelationship} from './generation.js'
import {desiredFromMain, makeWorld, type World} from './testkit.js'
import {scanOnlineOnly, volumeFormat} from './winfacts.js'

const worlds: World[] = []
const world = async () => {
  const w = await makeWorld('kf-safety-')
  worlds.push(w)
  return w
}
afterEach(async () => {
  while (worlds.length) await worlds.pop()!.cleanup()
})

const count = (r: {exec: {byKind: Record<string, number>} | null}, kind: string) => r.exec?.byKind[kind] ?? 0
const stable = (rel: string) => `item:${rel}`

describe('renames travel as moves (#29)', () => {
  /** The library keeps an item's id when it renames or moves the file: the desired set names each file by the id it had. */
  const renamed = async (w: World, from: string, to: string, text?: string) => {
    await fs.mkdir(path.dirname(path.join(w.main, ...to.split('/'))), {recursive: true})
    await fs.rename(path.join(w.main, ...from.split('/')), path.join(w.main, ...to.split('/')))
    if (text !== undefined) await w.write('main', to, text, new Date('2024-05-05'))
    return desiredFromMain(w.main, (p) => stable(p === to ? from : p))
  }

  it('a rename is a move on the copy, in every mode, without copying the bytes again', async () => {
    for (const mode of ['backup', 'mirror', 'twoWay'] as const) {
      const w = await world()
      await w.write('main', 'old name.mp3', 'song')
      await w.run({previewConfirmed: true, mode})
      const r = await w.run({previewConfirmed: true, mode, desiredFrom: await renamed(w, 'old name.mp3', 'new name.mp3')})
      assert.equal(count(r, 'MoveOnCopy'), 1, mode)
      assert.equal(count(r, 'CopyTo'), 0, mode)
      assert.equal(await w.read('copy', 'new name.mp3'), 'song')
      assert.equal(await w.read('copy', 'old name.mp3'), null)
    }
  })

  it('a move to another space folder is a move', async () => {
    const w = await world()
    await w.write('main', 'Music/a.mp3', 'tune')
    await w.run({previewConfirmed: true})
    const r = await w.run({previewConfirmed: true, desiredFrom: await renamed(w, 'Music/a.mp3', 'Favourites/a.mp3')})
    assert.equal(count(r, 'MoveOnCopy'), 1)
    assert.equal(await w.read('copy', 'Favourites/a.mp3'), 'tune')
    assert.equal(await w.read('copy', 'Music/a.mp3'), null)
  })

  it('a rename and an edit is a move, then a copy of the new bytes, and the old bytes are kept', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'version one')
    await w.run({previewConfirmed: true})
    const r = await w.run({previewConfirmed: true, desiredFrom: await renamed(w, 'a.mp3', 'b.mp3', 'version two!')})
    assert.equal(count(r, 'MoveOnCopy'), 1)
    assert.equal(count(r, 'CopyTo'), 1)
    assert.equal(await w.read('copy', 'b.mp3'), 'version two!')
    assert.equal(await w.read('copy', 'a.mp3'), null)
  })

  it('two renames in a row, before the copy was told, are one move', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'tune')
    await w.run({previewConfirmed: true})
    await fs.rename(path.join(w.main, 'a.mp3'), path.join(w.main, 'b.mp3'))
    const r = await w.run({previewConfirmed: true, desiredFrom: await renamed(w, 'b.mp3', 'c.mp3').then((d) => d.map((e) => ({...e, itemId: stable('a.mp3')})))})
    assert.equal(count(r, 'MoveOnCopy'), 1)
    assert.equal(await w.read('copy', 'c.mp3'), 'tune')
    assert.deepEqual(await w.list('copy'), ['c.mp3'])
  })

  it('a copy that holds a different file under the new name is not moved onto', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'tune')
    await w.run({previewConfirmed: true})
    await w.write('copy', 'b.mp3', 'somebody elses', new Date('2023-03-03'))
    const r = await w.run({previewConfirmed: true, desiredFrom: await renamed(w, 'a.mp3', 'b.mp3')})
    assert.equal(count(r, 'MoveOnCopy'), 0)
    assert.equal(await w.read('copy', 'b.mp3') !== null, true)
  })
})

describe('names that look the same', () => {
  it('the fast key equals the core key for composed and decomposed names', () => {
    const names = ['Café/Song.mp3', 'Café/SONG.mp3', 'Δια/x', 'Привет.mp3', 'plain/Name', 'İstanbul']
    for (const p of names) for (const rules of ['exact', 'ascii', 'nfc', 'unicode'] as const) assert.equal(fastKey(rules, p), nameKey(rules, p), `${rules} ${p}`)
    assert.equal(fastKey('unicode', 'Café.mp3'), fastKey('unicode', 'CAFÉ.MP3'))
  })

  it('a copy never gets a second, visually identical name', async () => {
    const w = await world()
    await w.write('main', 'Café.mp3', 'bytes')
    await w.write('copy', 'Café.mp3', 'bytes') // the same song, spelled the other way
    const r = await w.run({previewConfirmed: true})
    assert.equal(count(r, 'CopyTo'), 0, 'no second file next to the one that is already there')
    assert.deepEqual((await w.list('copy')).length, 1)
  })

  it('a baseline written under another name rule is sorted again, and the rule is recorded', async () => {
    const w = await world()
    for (const n of ['B.mp3', 'a.mp3', 'École.mp3', 'zed.mp3']) await w.write('main', n, n)
    await w.run({previewConfirmed: true})
    const file = path.join(w.state, 'relationship.json')
    const rel = JSON.parse(await fs.readFile(file, 'utf8'))
    assert.ok(typeof rel.rules === 'string')
    delete rel.rules // written before the rule was recorded
    await fs.writeFile(file, JSON.stringify(rel))
    const again = await w.run({previewConfirmed: true})
    assert.equal(again.exec, null)
    assert.ok((await readRelationship(w.state)).rules)
    assert.equal([...readBaseline(w.state)].length, 4)
  })
})

describe('files only in the cloud (#30)', () => {
  it('the attribute bits come from the core', () => {
    assert.equal(isOnlineOnly(0x400000), true)
    assert.equal(isOnlineOnly(0x20), false)
  })

  it('a main file that is only in the cloud is never read or copied, and the run adds nothing destructive', async () => {
    const w = await world()
    await w.write('main', 'here.mp3', 'local bytes')
    await w.write('main', 'cloud.mp3', 'cloud bytes')
    const r = await w.run({previewConfirmed: true, onlineOnlyFiles: async (root) => (root === w.main ? ['cloud.mp3'] : [])})
    assert.equal(await w.read('copy', 'here.mp3'), 'local bytes')
    assert.equal(await w.read('copy', 'cloud.mp3'), null, 'the placeholder was not read')
    assert.equal(r.onlineOnly?.main, 1)
    assert.ok(r.review.some((x) => x.kind === 'OnlineOnly' && x.path === 'cloud.mp3'))
    assert.equal(r.main?.complete, false)
    // later, when it is on the disk, it is copied
    const later = await w.run({previewConfirmed: true})
    assert.equal(await w.read('copy', 'cloud.mp3'), 'cloud bytes')
    assert.equal(count(later, 'CopyTo'), 1)
  })

  it('a copy file that is only in the cloud is never replaced or removed', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'one')
    await w.run({previewConfirmed: true})
    await w.write('main', 'a.mp3', 'two two', new Date('2025-01-01'))
    const r = await w.run({previewConfirmed: true, onlineOnlyFiles: async (root) => (root === w.copy ? ['a.mp3'] : [])})
    assert.equal(await w.read('copy', 'a.mp3'), 'one', 'the placeholder on the copy was left as it is')
    assert.equal(r.onlineOnly?.copy, 1)
    assert.equal(count(r, 'RemoveFromCopy'), 0)
    assert.ok((r.exec?.skipped ?? []).every((s) => s.reason === 'online-only' || s.reason === 'earlier-step'))
  })

  it('Windows: a file with the offline attribute is found by the real scan, and a drive reports its format', {skip: process.platform !== 'win32'}, async () => {
    const w = await world()
    await w.write('main', 'sub dir/here.txt', 'x')
    await w.write('main', 'sub dir/cloud only.txt', 'y')
    execFileSync('attrib', ['+O', path.join(w.main, 'sub dir', 'cloud only.txt')])
    assert.deepEqual(await scanOnlineOnly(w.main), ['sub dir/cloud only.txt'])
    assert.match((await volumeFormat(w.main)) ?? '', /^(NTFS|ReFS|exFAT|FAT32|FAT)$/)
  })

  it('a folder that cannot be checked is treated as not fully known', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'one')
    const r = await w.run({previewConfirmed: true, onlineOnlyFiles: async () => null})
    assert.equal(r.main?.complete, false)
    assert.equal(r.copy?.complete, false)
    assert.equal(await w.read('copy', 'a.mp3'), 'one', 'additive work still runs')
  })
})

describe('the 4 GiB limit of FAT (#30)', () => {
  it('only FAT has it', () => {
    assert.equal(fileSizeLimit('FAT32'), 4_294_967_295)
    assert.equal(fileSizeLimit('exFAT'), null)
    assert.equal(fileSizeLimit('NTFS'), null)
    assert.equal(fileSizeLimit(null), null)
  })

  it('a file the drive cannot hold is not attempted, and the person is told why', async () => {
    const w = await world()
    await w.write('main', 'small.mp3', 'ok')
    await w.write('main', 'Film.mkv', 'x'.repeat(40))
    const r = await w.run({previewConfirmed: true, volumeFormat: async () => 'FAT32', maxFileBytes: 20})
    assert.equal(r.copyFormat, 'FAT32')
    assert.equal(await w.read('copy', 'small.mp3'), 'ok')
    assert.equal(await w.read('copy', 'Film.mkv'), null)
    const note = r.review.find((x) => x.kind === 'TooLarge')
    assert.ok(note && note.path === 'Film.mkv' && /FAT32/.test(note.detail) && /not copied/.test(note.detail))
    assert.equal(r.exec?.failedCount, 0, 'nothing failed: it was skipped before any bytes moved')
  })
})

describe('a changed clock and the background audit (#30)', () => {
  it('the core sees a shift of whole hours across many files', () => {
    const s = new ClockShift()
    for (let i = 0; i < 6; i++) s.add(1_000_000 * i, 1_000_000 * i + 3_600_000)
    assert.equal(s.detected, true)
    assert.equal(s.hours, 1)
    const edits = new ClockShift()
    for (let i = 0; i < 6; i++) edits.add(1_000_000 * i, 1_000_000 * i + 12_345)
    assert.equal(edits.detected, false)
  })

  it('every mtime moved by an hour makes the next run hash everything', async () => {
    const w = await world()
    for (let i = 0; i < 6; i++) await w.write('main', `s${i}.mp3`, `song ${i}`)
    await w.run({previewConfirmed: true})
    const calm = await w.run({previewConfirmed: true})
    assert.equal(calm.audit, undefined)
    assert.equal(calm.hashed, 0)
    // the drive reports every time an hour later (a time zone change on a drive that keeps local time)
    for (let i = 0; i < 6; i++) {
      const file = path.join(w.copy, `s${i}.mp3`)
      const st = await fs.stat(file)
      const later = new Date(st.mtimeMs + 3_600_000)
      await fs.utimes(file, later, later)
    }
    const shifted = await w.run({previewConfirmed: true})
    assert.equal(shifted.audit?.reason, 'clock')
    assert.equal(shifted.audit?.hours, 1)
    assert.ok(shifted.hashed >= 6, 'every file was hashed')
    assert.equal(count(shifted, 'CopyTo'), 0, 'the files were the same: nothing was copied')
    assert.ok((await readRelationship(w.state)).auditedAt)
  })

  it('an audit is due after thirty days', () => {
    const day = 86_400_000
    assert.equal(auditDue(null, 10 * day), false)
    assert.equal(auditDue(0, 29 * day), false)
    assert.equal(auditDue(0, 30 * day), true)
  })

  it('the first completed run counts as an audit, and an asked-for audit renews it', async () => {
    const w = await world()
    await w.write('main', 'a.mp3', 'a')
    await w.run({previewConfirmed: true})
    const first = (await readRelationship(w.state)).auditedAt
    assert.ok(first)
    const r = await w.run({previewConfirmed: true, audit: true})
    assert.equal(r.audit?.reason, 'asked')
    assert.ok(((await readRelationship(w.state)).auditedAt ?? 0) > first)
  })
})

export type {DesiredEntry}
