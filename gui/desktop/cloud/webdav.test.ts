// WebDAV end to end: a real published WebDAV server (webdav-server, MIT, a dev dependency only) and an in-memory server whose
// conditional behaviour can be switched. The probe decides what the transport may do; a server that ignores If-Match gets
// no compare-and-swap rights and displaced bytes are preserved instead.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import net from 'node:net'
import path from 'node:path'
import {afterEach, beforeEach, describe, it} from 'node:test'
import {v2 as dav} from 'webdav-server'
import {CloudError} from './http.js'
import {FakeWebDav} from './fakes/webdav.js'
import {contentIdOf, rig, tmpDir, writeTemp} from './testkit.js'
import type {Rig} from './testkit.js'
import type {CloudScanResult} from './transport.js'
import type {TransportInventoryLine} from '../copies/transport.js'

async function freePort(): Promise<number> {
  return new Promise((resolve) => {
    const s = net.createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as net.AddressInfo).port
      s.close(() => resolve(port))
    })
  })
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

const basic = 'Basic ' + Buffer.from('keepfin:app-password').toString('base64')

interface Target {
  base: string
  stop(): Promise<void>
}

async function scanAll(r: Rig) {
  const lines: TransportInventoryLine[] = []
  const res = (await r.transport.scan('', (l) => lines.push(l))) as CloudScanResult
  return {lines: lines.sort((a, b) => a.path.localeCompare(b.path)), res}
}

describe('WebDAV against a real server (webdav-server)', () => {
  let target: Target
  let r: Rig
  let dir: string
  beforeEach(async () => {
    const port = await freePort()
    const users = new dav.SimpleUserManager()
    const user = users.addUser('keepfin', 'app-password', false)
    const rights = new dav.SimplePathPrivilegeManager()
    rights.setRights(user, '/', ['all'])
    const server = new dav.WebDAVServer({port, httpAuthentication: new dav.HTTPBasicAuthentication(users, 'Keepfin'), privilegeManager: rights, requireAuthentification: true})
    await server.startAsync(port)
    target = {base: `http://127.0.0.1:${port}`, stop: () => server.stopAsync().catch(() => undefined) as Promise<void>}
    r = rig('webdav', (u) => u, {base: target.base, auth: basic, rootPath: 'Keepfin'})
    dir = await tmpDir()
  })
  afterEach(async () => {
    await target.stop()
    await fs.rm(dir, {recursive: true, force: true})
  })

  it('probes the real server: it ignores If-Match and If-None-Match, so it gets no mutation rights', async () => {
    await r.client.createFolder({path: ''}, 'Keepfin').catch(() => undefined)
    const verdict = await r.client.probeWebdav('Keepfin/probe-1')
    assert.equal(verdict.cas, false)
    assert.equal(verdict.createOnly, false)
    assert.equal(verdict.noReplaceMove, true, 'Overwrite: F is honoured')
    assert.equal(verdict.range, true)
    const caps = r.transport.capabilities()
    assert.equal(caps.casMutation, false)
    assert.equal(caps.createOnly, false)
  })

  it('uploads, lists, downloads, replaces by preserving, and keeps locks and generations', async () => {
    await r.client.probeWebdav('probe-1').catch(() => undefined) // root level probe before the replica exists
    const data = bytes(2 * 1024 * 1024 + 5)
    const f = await writeTemp(dir, 'a', data)
    await r.transport.uploadNew(f, 'Music/Artist/a.flac', contentIdOf(data))
    const small = Buffer.from('tiny')
    await r.transport.uploadNew(await writeTemp(dir, 'b', small), 'Music/Caf\u00e9 \u266b.txt', contentIdOf(small))
    const {lines, res} = await scanAll(r)
    assert.equal(res.complete, true, JSON.stringify(res))
    assert.deepEqual(lines.map((l) => l.path), ['Music/Artist/a.flac', 'Music/Caf\u00e9 \u266b.txt'])
    assert.equal(lines[0]!.size, data.length)
    const out = path.join(dir, 'out')
    await r.transport.download('Music/Artist/a.flac', out, contentIdOf(data))
    assert.ok((await fs.readFile(out)).equals(data))
    // taken name: refused (the pre-check, since the server ignores If-None-Match)
    await assert.rejects(r.transport.uploadNew(f, 'Music/Artist/a.flac', contentIdOf(data)), (e: unknown) => e instanceof CloudError && e.kind === 'Conflict')
    // replace: no compare-and-swap, so the old bytes go to the trash
    const v2 = Buffer.from('second version')
    const res2 = await r.transport.replace(await writeTemp(dir, 'v2', v2), 'Music/Caf\u00e9 \u266b.txt', contentIdOf(v2), lines[1]!.revision)
    assert.ok(res2.preservedAs?.startsWith('.keepfin/trash/'))
    await r.transport.download('Music/Caf\u00e9 \u266b.txt', path.join(dir, 'v2out'), contentIdOf(v2))
    // lock and generations
    const a = await r.transport.acquireLock({device: 'A', run: '1'}, 1000)
    assert.equal(a.held, true)
    assert.equal((await r.transport.acquireLock({device: 'B', run: '1'}, 2000)).held, false)
    await a.release()
    assert.equal(await r.transport.appendGeneration(1, 'rep'), true)
    assert.equal(await r.transport.appendGeneration(1, 'rep'), false)
    assert.equal(await r.transport.readGeneration(), 1)
  })

  it('reports a missing root as such and quota as unknown when the server does not say', async () => {
    const res = (await r.transport.scan('', () => undefined)) as CloudScanResult
    assert.equal(res.missingRoot, true)
    const q = await r.client.quota()
    assert.equal(q.freeBytes, undefined)
  })
})

describe('WebDAV conditional behaviour (in-memory server with switches)', () => {
  let fake: FakeWebDav
  let r: Rig
  let dir: string
  beforeEach(async () => {
    fake = new FakeWebDav()
    await fake.server.start()
    dir = await tmpDir()
  })
  afterEach(async () => {
    await fake.server.stop()
    await fs.rm(dir, {recursive: true, force: true})
  })
  const make = () => {
    r = rig('webdav', (u) => u, {base: fake.base, auth: basic})
    return r
  }

  it('a conformant server passes the probe and gets compare-and-swap, create-only and quota', async () => {
    make()
    await r.client.createFolder({path: ''}, 'Keepfin')
    const verdict = await r.client.probeWebdav('Keepfin/probe')
    assert.deepEqual([verdict.cas, verdict.createOnly, verdict.noReplaceMove, verdict.range], [true, true, true, true])
    const caps = r.transport.capabilities()
    assert.equal(caps.casMutation, true)
    const v1 = Buffer.from('one')
    await r.transport.uploadNew(await writeTemp(dir, 'v1', v1), 'c.txt', contentIdOf(v1))
    const rev = (await scanAll(r)).lines[0]!.revision
    const v2 = Buffer.from('two')
    await r.transport.replace(await writeTemp(dir, 'v2', v2), 'c.txt', contentIdOf(v2), rev)
    const v3 = Buffer.from('three')
    await assert.rejects(r.transport.replace(await writeTemp(dir, 'v3', v3), 'c.txt', contentIdOf(v3), rev), (e: unknown) => e instanceof CloudError && e.kind === 'Conflict')
    await r.transport.download('c.txt', path.join(dir, 'now'), contentIdOf(v2))
    assert.deepEqual(await r.client.quota(), {usedBytes: 10, freeBytes: 5000, totalBytes: 5010})
  })

  it('a server that ignores If-Match loses mutation rights: nothing is overwritten in place', async () => {
    fake.ignoreIfMatch = true
    make()
    await r.client.createFolder({path: ''}, 'Keepfin')
    const verdict = await r.client.probeWebdav('Keepfin/probe')
    assert.equal(verdict.cas, false)
    assert.match(verdict.reason, /ignored a wrong If-Match/)
    assert.equal(r.transport.capabilities().casMutation, false)
    // the client refuses a conditional mutation outright
    await assert.rejects(r.client.replaceSmall({path: 'Keepfin/x'}, '"1"', 'x'), (e: unknown) => e instanceof CloudError && e.info.code === 'unsupported')
    await assert.rejects(r.client.deleteCas({path: 'Keepfin/x'}, '"1"'), (e: unknown) => e instanceof CloudError && e.info.code === 'unsupported')
    const v1 = Buffer.from('one')
    await r.transport.uploadNew(await writeTemp(dir, 'v1', v1), 'c.txt', contentIdOf(v1))
    const rev = (await scanAll(r)).lines[0]!.revision
    const v2 = Buffer.from('two')
    const res = await r.transport.replace(await writeTemp(dir, 'v2', v2), 'c.txt', contentIdOf(v2), rev)
    assert.ok(res.preservedAs?.startsWith('.keepfin/trash/'), 'the displaced bytes are in the trash')
    const trashed = fake.tree.resolve(['Keepfin', '.keepfin', 'trash'])!
    const kept = fake.tree.children(trashed.id)
    assert.equal(kept.length, 1)
    assert.equal(kept[0]!.data.toString(), 'one')
  })

  it('a server without a strong ETag gets no compare-and-swap either', async () => {
    fake.weakEtags = true
    make()
    await r.client.createFolder({path: ''}, 'Keepfin')
    const verdict = await r.client.probeWebdav('Keepfin/probe')
    assert.equal(verdict.cas, false)
  })

  it('a server that ignores If-None-Match and Overwrite still never overwrites through the transport pre-check', async () => {
    fake.ignoreIfNoneMatch = true
    fake.ignoreOverwrite = true
    make()
    await r.client.createFolder({path: ''}, 'Keepfin')
    const verdict = await r.client.probeWebdav('Keepfin/probe')
    assert.deepEqual([verdict.createOnly, verdict.noReplaceMove], [false, false])
    const v1 = Buffer.from('one')
    await r.transport.uploadNew(await writeTemp(dir, 'v1', v1), 'c.txt', contentIdOf(v1))
    await assert.rejects(r.transport.uploadNew(await writeTemp(dir, 'v1b', v1), 'c.txt', contentIdOf(v1)), (e: unknown) => e instanceof CloudError && e.kind === 'Conflict')
  })

  it('a listing that cannot be read to the end is incomplete; a 200 that ignored Range is not appended', async () => {
    make()
    await r.client.createFolder({path: ''}, 'Keepfin')
    await r.client.probeWebdav('Keepfin/probe')
    const data = bytes(1_500_000)
    await r.transport.uploadNew(await writeTemp(dir, 'v1', data), 'r.bin', contentIdOf(data))
    fake.ignoreRange = true
    const out = path.join(dir, 'partial')
    await fs.writeFile(out, data.subarray(0, 700_000))
    await r.transport.download('r.bin', out, contentIdOf(data))
    assert.ok((await fs.readFile(out)).equals(data))
    fake.server.fail((q) => q.method === 'PROPFIND', {status: 500, body: ''}, 99)
    const { res } = await scanAll(r)
    assert.equal(res.complete, false)
  })
})
