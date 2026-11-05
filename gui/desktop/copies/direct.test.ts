// A copy that is not a folder here (an account, a device) runs the same runner over the transport: the plan is made from the streamed
// listing, every action goes through the transport, and the only media bytes on this computer are the one file in flight, staged and
// emptied. The transport here is a folder behind the same interface (folderRemote.ts), so every case is exact and fast.
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {afterEach, describe, it} from 'node:test'
import type {DesiredEntry} from '../../shared/core/copies.js'
import {FolderRemote, FolderRemoteError} from './folderRemote.js'
import {archived, makeWorld, walk, type World} from './testkit.js'
import {folderBytes} from './remote.js'

let w: World | null = null
afterEach(async () => {
  await w?.cleanup()
  w = null
})

const idOf = (text: string | Buffer) => 'sha256:' + crypto.createHash('sha256').update(text).digest('hex')

async function setup() {
  w = await makeWorld('kf-direct-')
  const replica = path.join(w.root, 'replica')
  await fs.mkdir(replica, {recursive: true})
  const remote = new FolderRemote(replica)
  const rread = (rel: string) => fs.readFile(path.join(replica, ...rel.split('/')), 'utf8').catch(() => null)
  const rwrite = async (rel: string, text: string) => {
    const file = path.join(replica, ...rel.split('/'))
    await fs.mkdir(path.dirname(file), {recursive: true})
    await fs.writeFile(file, text)
    await fs.utimes(file, new Date('2020-02-02'), new Date('2020-02-02'))
  }
  const run = (over: Parameters<World['run']>[0] = {}) => w!.run({remote, previewConfirmed: true, ...over})
  /** Every file on this computer outside main that holds the bytes of one of [ids] (the copy folder's `.keepfin` data and the state folder). */
  const strayMedia = async (ids: string[]) => {
    const found: string[] = []
    for (const root of [w!.copy, w!.state]) {
      for (const rel of await walk(root, false)) {
        const bytes = await fs.readFile(path.join(root, ...rel.split('/')))
        if (ids.includes(idOf(bytes))) found.push(`${path.basename(root)}/${rel}`)
      }
    }
    return found
  }
  return {w: w!, replica, remote, rread, rwrite, run, strayMedia}
}

describe('a copy behind a transport', () => {
  it('copies new files, plans nothing the second time, and keeps no media on this computer', async () => {
    const t = await setup()
    await t.w.write('main', 'Music/a.mp3', 'AAA')
    await t.w.write('main', 'Music/b.mp3', 'BBB')
    const first = await t.run()
    assert.equal(first.status, 'done')
    assert.equal(first.exec?.byKind.CopyTo, 2)
    assert.equal(first.remote?.uploaded, 2)
    assert.equal(await t.rread('Music/a.mp3'), 'AAA')
    assert.deepEqual(await walk(t.w.copy), [], 'the copy folder holds no media, only its own .keepfin data')
    assert.deepEqual(await t.strayMedia([idOf('AAA'), idOf('BBB')]), [])
    assert.equal(await folderBytes(path.join(t.w.state, 'staging')), 0, 'the staging folder is emptied')
    const second = await t.run()
    assert.equal(second.exec, null, 'the second plan is empty: what was sent is what the next listing shows')
    assert.equal(t.remote.calls.filter((c) => c.startsWith('download')).length, 0, 'nothing was downloaded to decide')
  })

  it('replaces a changed file after moving the old bytes to versions, and the new bytes are what the copy lists next time', async () => {
    const t = await setup()
    await t.w.write('main', 'Music/a.mp3', 'AAA-1')
    await t.run()
    await t.w.write('main', 'Music/a.mp3', 'AAA-2 longer', new Date('2021-01-01'))
    const r = await t.run()
    assert.equal(r.status, 'done')
    assert.equal(await t.rread('Music/a.mp3'), 'AAA-2 longer')
    const aside = [...(await walk(path.join(t.replica, '.keepfin'), false))].filter((f) => f.startsWith('versions/'))
    assert.equal(aside.length, 1)
    assert.equal(await t.rread(`.keepfin/${aside[0]!}`), 'AAA-1')
    assert.equal(r.remote?.replaced, 1)
    assert.equal((await t.run()).exec, null)
    const catalogue = await fs.readFile(path.join(t.w.copy, '.keepfin', 'versions', 'catalogue.jsonl'), 'utf8')
    assert.match(catalogue, /"why":"replaced"/)
  })

  it('moves what main deleted to the trash of a mirror, never deleting it, and follows a rename with a move', async () => {
    const t = await setup()
    await t.w.write('main', 'Music/gone.mp3', 'GONE')
    await t.w.write('main', 'Music/old.mp3', 'OLD')
    await t.run({mode: 'mirror'})
    await t.w.remove('main', 'Music/gone.mp3')
    const r = await t.run({mode: 'mirror', deleted: ['item:Music/gone.mp3'], deleteGuardPercent: 100})
    assert.equal(r.status, 'done', r.reason ?? '')
    assert.equal(await t.rread('Music/gone.mp3'), null)
    const kept = await archived(t.w.copy)
    assert.deepEqual([...kept.keys()].length, 0, 'the archived bytes are on the replica, not here')
    const trashed = await walk(path.join(t.replica, '.keepfin'), false)
    assert.ok(trashed.some((f) => f.startsWith('trash/') && !f.endsWith('.jsonl')))

    // a rename in the library
    await fs.rename(path.join(t.w.main, 'Music', 'old.mp3'), path.join(t.w.main, 'Music', 'new.mp3'))
    const renamed = await t.run({mode: 'mirror', desiredFrom: [{path: 'Music/new.mp3', itemId: 'item:Music/old.mp3', revisionOp: 'rev1', contentId: idOf('OLD'), bytes: 3}]})
    assert.equal(renamed.status, 'done', renamed.reason ?? '')
    assert.equal(await t.rread('Music/new.mp3'), 'OLD')
    assert.equal(await t.rread('Music/old.mp3'), null)
    assert.equal(renamed.remote?.uploaded, 0, 'a rename sends no bytes')
    assert.ok(t.remote.calls.some((c) => c.startsWith('move Music/old.mp3 -> Music/new.mp3')))
  })

  it('brings a file only the copy has into the library, and restores one main lost from the copy', async () => {
    const t = await setup()
    await t.w.write('main', 'Music/keep.mp3', 'KEEP')
    await t.run({mode: 'twoWay'})
    // restore: main loses a file the library still wants
    await t.w.remove('main', 'Music/keep.mp3')
    const restore = await t.run({mode: 'twoWay', desiredFrom: [{path: 'Music/keep.mp3', itemId: 'item:Music/keep.mp3', revisionOp: 'rev1', contentId: idOf('KEEP'), bytes: 4}]})
    assert.equal(restore.status, 'done', restore.reason ?? '')
    assert.equal(await t.w.read('main', 'Music/keep.mp3'), 'KEEP')
    assert.equal(restore.remote?.downloaded, 1)
    // import: another device put a new file on the copy
    await t.rwrite('Music/phone.mp3', 'FROM-PHONE')
    const imp = await t.run({mode: 'twoWay'})
    assert.equal(imp.status, 'done', imp.reason ?? '')
    assert.equal(await t.w.read('main', 'Music/phone.mp3'), 'FROM-PHONE')
    assert.ok(t.w.hostLog.some((l) => l.startsWith('import Music/phone.mp3')))
    assert.deepEqual(await t.strayMedia([idOf('KEEP'), idOf('FROM-PHONE')]), [])
  })

  it('refuses to overwrite a file that changed on the copy after the plan: nothing is lost and the next plan decides', async () => {
    const t = await setup()
    await t.w.write('main', 'Music/a.mp3', 'AAA-1')
    await t.run()
    await t.w.write('main', 'Music/a.mp3', 'AAA-2', new Date('2021-01-01'))
    // between the plan and the move aside, somebody edits the object on the account
    let edited = false
    t.remote.fail = (op) => {
      if (op === 'move' && !edited) {
        edited = true
        void fs.writeFile(path.join(t.replica, 'Music', 'a.mp3'), 'EDITED-ON-THE-ACCOUNT')
      }
      return undefined
    }
    await new Promise((r) => setTimeout(r, 0))
    const r = await t.run({mode: 'twoWay'})
    assert.ok(r.status === 'done' || r.status === 'held', r.reason ?? '')
    const texts = new Set<string>()
    for (const rel of await walk(t.replica, false)) texts.add(await fs.readFile(path.join(t.replica, ...rel.split('/')), 'utf8'))
    assert.ok(texts.has('EDITED-ON-THE-ACCOUNT') || [...texts].some((x) => x.includes('EDITED')), 'the edit made on the account is still there')
  })

  it('does nothing when the listing is not complete, and when a listing that always had files shows none', async () => {
    const t = await setup()
    await t.w.write('main', 'Music/a.mp3', 'AAA')
    await t.w.write('main', 'Music/b.mp3', 'BBB')
    await t.run()
    t.remote.calls.length = 0
    t.remote.tearAfter = 1
    const torn = await t.run({mode: 'mirror'})
    assert.equal(torn.status, 'disconnected')
    assert.equal(torn.remoteStop?.state, 'incomplete')
    assert.deepEqual(t.remote.calls.filter((c) => /^(upload|replace|trash|move)/.test(c)), [])
    t.remote.tearAfter = null
    // the account shows nothing at all although it did: that is not "everything was deleted"
    await fs.rm(path.join(t.replica, 'Music'), {recursive: true})
    const empty = await t.run({mode: 'twoWay'})
    assert.equal(empty.status, 'disconnected')
    assert.equal(empty.remoteStop?.state, 'incomplete')
    assert.equal(await t.w.read('main', 'Music/a.mp3'), 'AAA', 'main is untouched')
  })

  it('ends the run when the copy cannot be reached, keeps what was done, and the next run finishes', async () => {
    const t = await setup()
    for (const n of ['a', 'b', 'c', 'd']) await t.w.write('main', `Music/${n}.mp3`, `content ${n}`)
    let uploads = 0
    t.remote.fail = (op) => (op === 'upload' && ++uploads === 3 ? new FolderRemoteError('down') : undefined)
    const first = await t.run({parallelism: 1})
    assert.equal(first.status, 'disconnected')
    assert.equal(first.remoteStop?.state, 'offline')
    assert.equal(first.exec?.byKind.CopyTo, 2, 'what was sent before is committed')
    t.remote.fail = () => undefined
    const second = await t.run({parallelism: 1})
    assert.equal(second.status, 'done', second.reason ?? '')
    assert.equal(second.exec?.byKind.CopyTo, 2)
    assert.equal(second.remote?.uploaded, 2)
    assert.equal((await t.run()).exec, null)
  })

  it('undoes the move aside when the new bytes cannot be sent, so the old file is back where it was', async () => {
    const t = await setup()
    await t.w.write('main', 'Music/a.mp3', 'AAA-1')
    await t.run()
    await t.w.write('main', 'Music/a.mp3', 'AAA-2', new Date('2021-01-01'))
    t.remote.fail = (op) => (op === 'upload' ? new FolderRemoteError('down') : undefined)
    const r = await t.run()
    assert.equal(r.remoteStop?.state, 'offline')
    assert.equal(await t.rread('Music/a.mp3'), 'AAA-1', 'the old bytes were put back')
    t.remote.fail = () => undefined
    assert.equal((await t.run()).status, 'done')
    assert.equal(await t.rread('Music/a.mp3'), 'AAA-2')
  })

  it('a preview plans from the listing and writes nothing to the copy', async () => {
    const t = await setup()
    await t.w.write('main', 'Music/a.mp3', 'AAA')
    const r = await t.run({dryRun: true, previewConfirmed: false})
    assert.equal(r.status, 'preview')
    assert.equal(r.summary?.counts.CopyTo, 1)
    assert.deepEqual(t.remote.calls.filter((c) => /^(upload|replace|trash|move)/.test(c)), [])
  })

  it('disk use: a 2,000 file copy leaves only bookkeeping on this computer and never more than the files in flight', async () => {
    const t = await setup()
    const N = 2000
    const when = new Date('2022-02-02')
    const desired: DesiredEntry[] = []
    const ids: string[] = []
    let mediaBytes = 0
    for (let i = 0; i < N; i++) {
      const dir = `Music/Artist ${i % 100}/Album ${i % 17}`
      await fs.mkdir(path.join(t.w.main, ...dir.split('/')), {recursive: true})
      const rel = `${dir}/track ${String(i).padStart(5, '0')}.mp3`
      const body = Buffer.alloc(8192, i % 251)
      body.writeUInt32LE(i, 0)
      await fs.writeFile(path.join(t.w.main, ...rel.split('/')), body)
      await fs.utimes(path.join(t.w.main, ...rel.split('/')), when, when)
      desired.push({path: rel, itemId: `item-${i}`, revisionOp: 'r1', contentId: idOf(body), bytes: body.length})
      ids.push(idOf(body))
      mediaBytes += body.length
    }
    const staging = path.join(t.w.state, 'staging')
    let peakStaging = 0
    let uploads = 0
    t.remote.fail = (op) => {
      if (op === 'upload') {
        uploads++
        if (uploads % 200 === 0) void folderBytes(staging).then((n) => (peakStaging = Math.max(peakStaging, n)))
      }
      return undefined
    }
    const first = await t.w.run({remote: t.remote, desiredFrom: desired, previewConfirmed: true, parallelism: 4})
    assert.equal(first.status, 'done', first.reason ?? '')
    assert.equal(first.exec?.byKind.CopyTo, N)
    assert.equal(first.exec?.failedCount, 0)
    const second = await t.w.run({remote: t.remote, desiredFrom: desired, previewConfirmed: true, parallelism: 4})
    assert.equal(second.exec, null)
    const local = (await folderBytes(t.w.copy)) + (await folderBytes(t.w.state))
    assert.equal(await folderBytes(staging), 0)
    assert.ok(peakStaging <= 4 * 8192 * 2, `staging held ${peakStaging} bytes at once`)
    assert.deepEqual((await t.strayMedia(ids)).length, 0, 'no media file on this computer outside main')
    // the whole local footprint of the place is bookkeeping: about 2 KB per file (baseline, desired set, the last journal and two manifests), whatever the file's size
    console.log(`direct copy of ${N} files (${mediaBytes} media bytes): ${local} bytes of bookkeeping on this computer (${((local / mediaBytes) * 100).toFixed(1)}%), staging peak ${peakStaging} bytes`)
    assert.ok(local < N * 3000, `bookkeeping ${local} bytes for ${N} files`)
  })
})
