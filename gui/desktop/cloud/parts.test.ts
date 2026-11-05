// Pieces of the cloud executor on their own: the transfer limit, the rate limiter, back-off, the upload journal and the hashes.
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {describe, it} from 'node:test'
import {cloud, dropboxCombine, quickXorHasher} from '../../shared/core/cloud.js'
import {ProviderHasher} from './client.js'
import {CloudHttp, RateLimiter, Semaphore} from './http.js'
import {FileJournal} from './journal.js'
import {StaticAccount, tmpDir} from './testkit.js'

describe('cloud executor parts', () => {
  it('runs at most 4 transfers at once', async () => {
    const s = new Semaphore(4)
    let running = 0
    let peak = 0
    await Promise.all(
      Array.from({length: 20}, () =>
        s.run(async () => {
          running++
          peak = Math.max(peak, running)
          await new Promise((r) => setTimeout(r, 5))
          running--
        }),
      ),
    )
    assert.equal(peak, 4)
    assert.equal(new CloudHttp(new StaticAccount('drive')).transfers.limit, 4)
  })

  it('spaces requests, doubles the spacing after a 429 and eases back', async () => {
    let t = 0
    const slept: number[] = []
    const rl = new RateLimiter(100, 1000, () => t, async (ms) => void (slept.push(ms), (t += ms)))
    await rl.acquire()
    await rl.acquire()
    assert.deepEqual(slept, [100])
    rl.penalize()
    assert.equal(rl.currentIntervalMs, 200)
    rl.penalize()
    rl.penalize()
    rl.penalize()
    rl.penalize()
    assert.equal(rl.currentIntervalMs, 1000)
    rl.relax()
    assert.equal(rl.currentIntervalMs, 900)
  })

  it('backs off with full jitter inside an exponential window capped at 30 s', () => {
    const http = new CloudHttp(new StaticAccount('graph'), {random: () => 0.999, baseDelayMs: 400})
    assert.ok(http.backoffMs(0) < 400)
    assert.ok(http.backoffMs(3) < 3200 && http.backoffMs(3) > 3000)
    assert.ok(http.backoffMs(20) <= 30_000)
    assert.equal(new CloudHttp(new StaticAccount('graph'), {random: () => 0}).backoffMs(5), 0)
  })

  it('keeps upload sessions in a file that survives a restart, and writes it atomically', async () => {
    const dir = await tmpDir()
    const file = path.join(dir, 'sub', 'uploads.json')
    const a = new FileJournal(file)
    await a.put('k', {session: {kind: 'drive', url: 'https://www.googleapis.com/upload/x?upload_id=1'}, offset: 0, total: 100, contentId: 'sha256:00', createdAtMs: 1})
    await a.update('k', 40)
    const b = new FileJournal(file)
    assert.equal((await b.get('k'))?.offset, 40)
    assert.ok(!JSON.stringify(await b.get('k')).toLowerCase().includes('bearer'))
    await b.remove('k')
    assert.equal(await new FileJournal(file).get('k'), null)
    assert.deepEqual((await fs.readdir(path.dirname(file))).filter((n) => n.includes('.tmp-')), [])
    await fs.rm(dir, {recursive: true})
  })

  it('computes the providers hashes the way the core does, chunk by chunk', () => {
    const data = crypto.randomBytes(9 * 1024 * 1024 + 17)
    // Dropbox: SHA-256 of the SHA-256 digests of each 4 MiB block, computed here independently.
    const blocks: Buffer[] = []
    for (let at = 0; at < data.length; at += 4 * 1024 * 1024) blocks.push(crypto.createHash('sha256').update(data.subarray(at, at + 4 * 1024 * 1024)).digest())
    const expected = crypto.createHash('sha256').update(Buffer.concat(blocks)).digest('hex')
    const h = new ProviderHasher('dropbox')
    for (let at = 0; at < data.length; at += 1_000_003) h.update(data.subarray(at, at + 1_000_003))
    assert.equal(h.providerHash(), expected)
    assert.equal(dropboxCombine(blocks.map((b) => b.toString('hex'))), expected)
    // quickXorHash: streaming equals one shot, the empty hash is twenty zero bytes
    assert.equal(quickXorHasher().digest(), 'AAAAAAAAAAAAAAAAAAAAAAAAAAA=')
    const q1 = quickXorHasher()
    q1.update(data)
    const q2 = quickXorHasher()
    for (let at = 0; at < data.length; at += 65_537) q2.update(data.subarray(at, at + 65_537))
    assert.equal(q1.digest(), q2.digest())
    assert.equal(cloud<{hash: string}>('graph', 'quickXorHash', {hex: data.subarray(0, 5000).toString('hex')}).hash, (() => { const q = quickXorHasher(); q.update(data.subarray(0, 5000)); return q.digest() })())
  })

  it('the core refuses what the providers refuse', () => {
    assert.throws(() => cloud('drive', 'uploadAppend', {session: {url: 'https://www.googleapis.com/upload/x'}, offset: 0, length: 1000, total: 5_000_000}))
    assert.throws(() => cloud('graph', 'uploadAppend', {session: {url: 'https://up/x'}, offset: 0, length: 1000, total: 5_000_000}))
    assert.throws(() => cloud('drive', 'uploadAppend', {session: {url: 'https://evil.example/x'}, offset: 0, length: 262144, total: 262144}), /authorization header only goes to/)
  })
})
