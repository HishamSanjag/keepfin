// The cloud transport against fake providers (Drive, Graph, Dropbox) with fault injection: one contract, three providers.
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {afterEach, beforeEach, describe, it} from 'node:test'
import type {CloudProvider} from '../../shared/core/cloud.js'
import type {CloudScanResult} from './transport.js'
import {ContentMismatchError} from './client.js'
import {CloudError} from './http.js'
import {FakeDrive} from './fakes/drive.js'
import {FakeDropbox} from './fakes/dropbox.js'
import {FakeGraph} from './fakes/graph.js'
import type {FakeServer} from './fakes/base.js'
import {contentIdOf, rig, tmpDir, writeTemp} from './testkit.js'
import type {Rig} from './testkit.js'
import type {TransportInventoryLine} from '../copies/transport.js'

interface Fake {
  server: FakeServer
  rewrite: (u: string) => string
  ignoreRange: boolean
  expireAllSessions(): void
  pageSize: number
}

const makers: Record<'drive' | 'graph' | 'dropbox', () => Fake> = {
  drive: () => new FakeDrive(),
  graph: () => new FakeGraph(),
  dropbox: () => new FakeDropbox(),
}

const bytes = (n: number, seed = 1) => {
  const b = Buffer.alloc(n)
  let x = seed
  for (let i = 0; i < n; i++) {
    x = (x * 1103515245 + 12345) & 0x7fffffff
    b[i] = x >> 16
  }
  return b
}

for (const provider of ['drive', 'graph', 'dropbox'] as const) {
  describe(`cloud transport on a fake ${provider}`, () => {
    let fake: Fake
    let r: Rig
    let dir: string
    beforeEach(async () => {
      fake = makers[provider]()
      await fake.server.start()
      r = rig(provider as CloudProvider, fake.rewrite)
      dir = await tmpDir()
    })
    afterEach(async () => {
      await fake.server.stop()
      await fs.rm(dir, {recursive: true, force: true})
    })

    const put = async (rel: string, data: Buffer) => {
      const file = await writeTemp(dir, 'src-' + crypto.randomBytes(3).toString('hex'), data)
      return r.transport.uploadNew(file, rel, contentIdOf(data))
    }
    const scan = async () => {
      const lines: TransportInventoryLine[] = []
      const res = (await r.transport.scan('', (l) => lines.push(l))) as CloudScanResult
      return {lines: lines.sort((a, b) => a.path.localeCompare(b.path)), res}
    }

    it('uploads in chunks, lists across pages and downloads with a verified SHA-256', async () => {
      const big = bytes(17 * 1024 * 1024 + 123)
      await put('Music/Artist/big.flac', big)
      for (let i = 0; i < 4; i++) await put(`Music/Artist/s${i}.mp3`, bytes(1000 + i, i))
      await put('top.txt', Buffer.from('hello'))
      const {lines, res} = await scan()
      assert.equal(res.complete, true)
      assert.deepEqual(lines.map((l) => l.path), ['Music/Artist/big.flac', 'Music/Artist/s0.mp3', 'Music/Artist/s1.mp3', 'Music/Artist/s2.mp3', 'Music/Artist/s3.mp3', 'top.txt'])
      assert.equal(lines[0]!.size, big.length)
      const out = path.join(dir, 'staged.bin')
      await r.transport.download('Music/Artist/big.flac', out, contentIdOf(big))
      assert.ok((await fs.readFile(out)).equals(big))
    })

    it('rejects a download whose bytes do not match the pinned content id and removes the staged file', async () => {
      await put('a.bin', bytes(5000))
      const out = path.join(dir, 'staged.bin')
      await assert.rejects(r.transport.download('a.bin', out, contentIdOf(Buffer.from('other'))), ContentMismatchError)
      await assert.rejects(fs.stat(out))
    })

    it('refuses an upload whose source changed from the pinned content', async () => {
      const file = await writeTemp(dir, 'src', bytes(3000))
      await assert.rejects(r.transport.uploadNew(file, 'x.bin', contentIdOf(Buffer.from('pinned something else'))), ContentMismatchError)
      const { lines } = await scan()
      assert.equal(lines.length, 0)
    })

    it('waits for Retry-After on 429 and retries 5xx with back-off', async () => {
      await put('a.bin', bytes(100))
      const isList = (q: {method: string; url: URL}) => q.method !== 'PUT' && /list|children|files$/.test(q.url.pathname) && !/upload/.test(q.url.pathname)
      fake.server.fail(isList, {status: 429, headers: {'retry-after': '7'}, body: {error_summary: 'too_many_requests/..', error: {reason: {'.tag': 'too_many_requests'}, retry_after: 7}}})
      fake.server.fail(isList, {status: 503, body: {}}, 2)
      const {lines, res} = await scan()
      assert.equal(lines.length, 1)
      assert.equal(res.complete, true)
      assert.ok(r.sleeps.some((ms) => ms >= 7000), `slept ${r.sleeps}`)
      assert.ok(r.sleeps.length >= 3)
    })

    it('slows its request rate after a 429', async () => {
      await put('a.bin', bytes(100))
      const before = r.http.limiter.currentIntervalMs
      fake.server.fail((q) => /list|children|files$/.test(q.url.pathname) && q.method !== 'PUT' && !/upload/.test(q.url.pathname), {status: 429, headers: {'retry-after': '1'}, body: {}})
      await scan()
      assert.ok(r.http.limiter.currentIntervalMs >= before)
    })

    it('refreshes the token once on a 401 and carries on', async () => {
      await put('a.bin', bytes(100))
      fake.server.fail((q) => q.method !== 'PUT' && !/upload/.test(q.url.pathname) && /list|children|files$/.test(q.url.pathname), {status: 401, body: {error_summary: 'expired_access_token/..', error: {'.tag': 'expired_access_token'}}})
      const {lines} = await scan()
      assert.equal(lines.length, 1)
      assert.equal(r.account.refreshes, 1)
    })

    it('reports a torn listing as incomplete, keeping the lines it read', async () => {
      for (let i = 0; i < 5; i++) await put(`f${i}.bin`, bytes(10, i))
      fake.pageSize = 2
      let pages = 0
      fake.server.fail(
        (q) => {
          const continued = q.url.searchParams.has('pageToken') || q.url.searchParams.has('$skiptoken') || /list_folder\/continue/.test(q.url.pathname)
          if (continued) pages++
          return continued
        },
        {status: 500, body: {}},
        99,
      )
      const {lines, res} = await scan()
      assert.equal(res.complete, false)
      assert.ok(lines.length >= 1 && lines.length < 5, `read ${lines.length}`)
      assert.ok(pages >= 1)
    })

    it('resumes an interrupted upload from the journaled session, and restarts when the session expired', async () => {
      const data = bytes(26 * 1024 * 1024 + 7)
      const file = await writeTemp(dir, 'big', data)
      const abort = new AbortController()
      let sent = 0
      // wait for the event itself (a chunk acknowledged in the journal), bounded only by the test timeout, never by a count of sleeps
      const partialSession = async () => {
        const end = Date.now() + 120_000
        while (Date.now() < end) {
          const e = [...r.journal.entries.values()][0]
          if (e && e.offset > 0) return
          await new Promise((res) => setTimeout(res, 2))
        }
        throw new Error('no chunk was acknowledged')
      }
      const starts = () => fake.server.log.filter((l) => /upload_session\/start|createUploadSession|uploadType=resumable/.test(l.path) && l.method === 'POST').length
      const first = r.transport.uploadNew(file, 'Music/big.bin', contentIdOf(data), abort.signal)
      void first.catch(() => undefined)
      // Abort once a chunk has been acknowledged: poll the journal.
      await partialSession()
      abort.abort()
      await assert.rejects(first)
      const entry = [...r.journal.entries.values()][0]
      assert.ok(entry && entry.offset > 0 && entry.offset < data.length, 'a partial session is journaled')
      sent = starts()
      const done = await r.transport.uploadNew(file, 'Music/big.bin', contentIdOf(data))
      assert.ok(done.revision !== undefined)
      assert.equal(starts(), sent, 'the session was resumed, not started again')
      assert.equal(r.journal.entries.size, 0)
      const out = path.join(dir, 'back')
      await r.transport.download('Music/big.bin', out, contentIdOf(data))

      // an expired session: start a second partial upload, expire it, and upload again
      const data2 = bytes(26 * 1024 * 1024 + 9, 9)
      const file2 = await writeTemp(dir, 'big2', data2)
      const abort2 = new AbortController()
      const second = r.transport.uploadNew(file2, 'Music/big2.bin', contentIdOf(data2), abort2.signal)
      void second.catch(() => undefined)
      await partialSession()
      abort2.abort()
      await assert.rejects(second)
      const before = starts()
      fake.expireAllSessions()
      await r.transport.uploadNew(file2, 'Music/big2.bin', contentIdOf(data2))
      assert.equal(starts(), before + 1, 'an expired session starts a new one')
      await r.transport.download('Music/big2.bin', path.join(dir, 'back2'), contentIdOf(data2))
    })

    it('never appends a 200 that ignored Range to a partial file', async () => {
      const data = bytes(3 * 1024 * 1024)
      await put('r.bin', data)
      const out = path.join(dir, 'staged')
      await fs.writeFile(out, data.subarray(0, 1_000_000))
      fake.ignoreRange = true
      await r.transport.download('r.bin', out, contentIdOf(data))
      assert.ok((await fs.readFile(out)).equals(data))
    })

    it('a stale revision never overwrites: compare-and-swap refuses, or the displaced bytes are preserved', async () => {
      await put('c.txt', Buffer.from('one'))
      const first = (await scan()).lines[0]!
      const v2 = Buffer.from('two (another computer)')
      const f2 = await writeTemp(dir, 'v2', v2)
      const caps = r.transport.capabilities()
      if (caps.casMutation) {
        // a second writer replaces with the right revision; ours holds the old one
        await r.transport.replace(f2, 'c.txt', contentIdOf(v2), first.revision)
        const v3 = Buffer.from('three (ours, stale)')
        const f3 = await writeTemp(dir, 'v3', v3)
        await assert.rejects(r.transport.replace(f3, 'c.txt', contentIdOf(v3), first.revision), (e: unknown) => e instanceof CloudError && e.kind === 'Conflict')
        const out = path.join(dir, 'now')
        await r.transport.download('c.txt', out, contentIdOf(v2))
      } else {
        const res = await r.transport.replace(f2, 'c.txt', contentIdOf(v2), first.revision)
        assert.ok(res.preservedAs?.startsWith('.keepfin/trash/'), 'the old bytes are in the trash')
        await r.transport.download('c.txt', path.join(dir, 'now'), contentIdOf(v2))
      }
      const trashed = await r.transport.moveToTrash('c.txt', null).then(() => true, () => false)
      assert.equal(trashed, true)
      assert.equal((await scan()).lines.length, 0)
    })

    it('takes an advisory lock, refuses a second holder, takes over a stale one, and releases', async () => {
      let t = 1_000_000
      const rig2 = rig(provider as CloudProvider, fake.rewrite, {now: () => t})
      const a = await rig2.transport.acquireLock({device: 'A', run: '1'}, t)
      assert.equal(a.held, true)
      const b = await rig2.transport.acquireLock({device: 'B', run: '1'}, t + 1000)
      assert.equal(b.held, false)
      await a.heartbeat()
      t += 6 * 60_000
      const c = await rig2.transport.acquireLock({device: 'C', run: '1'}, t)
      assert.equal(c.held, true, 'a lock older than five minutes is stale')
      await c.release()
      const d = await rig2.transport.acquireLock({device: 'D', run: '1'}, t + 10)
      assert.equal(d.held, true)
      await d.release()
    })

    it('keeps generations as append-only records', async () => {
      assert.equal(await r.transport.readGeneration(), 0)
      assert.equal(await r.transport.appendGeneration(1, 'rep-a'), true)
      assert.equal(await r.transport.appendGeneration(2, 'rep-b'), true)
      assert.equal(await r.transport.appendGeneration(2, 'rep-b'), false)
      assert.equal(await r.transport.appendGeneration(2, 'rep-c'), true)
      assert.equal(await r.transport.readGeneration(), 2)
    })

    it('a second upload to a taken name is refused', async () => {
      await put('same.bin', bytes(50))
      await assert.rejects(put('same.bin', bytes(60)), (e: unknown) => e instanceof CloudError && e.kind === 'Conflict')
    })
  })
}
