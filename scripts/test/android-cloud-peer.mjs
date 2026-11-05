// The desktop's cloud providers for the Android interop and transport tests (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 7). It starts the
// desktop's fake Google Drive, OneDrive (Graph) and Dropbox, its in-memory WebDAV server, and the real published WebDAV server
// (webdav-server, a dev dependency), each on a local port, and a small plain-HTTP control port. The test points the Android client at
// them (it rewrites the providers' real hosts exactly as the desktop tests do), and asks the DESKTOP transport to read or write the same
// replica, which is how the phone's layout is checked against the one the desktop expects. Nothing here is part of the app.
//
//   node scripts/test/android-cloud-peer.mjs [--control 47399]      prints one line:  READY {json}   (ports are chosen by the system)
//   adb reverse tcp:<port> tcp:<port>                           for every port in the READY line, to reach it from an emulator
//
// Control (all JSON): GET /info; POST /fault {target,status,times,method?,path?,headers?,body?}; POST /reset; POST /option {target,name,value};
// POST /expire {target}; POST /desktop/scan {target,root}; POST /desktop/put {target,root,path,text?|bytes?}; POST /desktop/edit {target,root,path,text?|bytes?};
// POST /desktop/get {target,root,path};
// GET /log?target=; POST /quit.   target is one of drive, graph, dropbox, davfake, davreal.
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import http from 'node:http'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import {register} from 'tsx/esm/api'

register()
const {FakeDrive} = await import('../../gui/desktop/cloud/fakes/drive.ts')
const {FakeGraph} = await import('../../gui/desktop/cloud/fakes/graph.ts')
const {FakeDropbox} = await import('../../gui/desktop/cloud/fakes/dropbox.ts')
const {FakeWebDav} = await import('../../gui/desktop/cloud/fakes/webdav.ts')
const {FakeOAuth} = await import('../../gui/desktop/cloud/fakes/oauth.ts')
const {rig, contentIdOf, writeTemp, tmpDir} = await import('../../gui/desktop/cloud/testkit.ts')
const {v2: dav} = await import('webdav-server')

const args = Object.fromEntries(process.argv.slice(2).reduce((all, value, index, list) => (value.startsWith('--') ? [...all, [value.slice(2), list[index + 1] ?? 'true']] : all), []))

const freePort = () =>
  new Promise((resolve) => {
    const s = net.createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = s.address().port
      s.close(() => resolve(port))
    })
  })

const oauth = new FakeOAuth()
await oauth.server.start()
const fakes = {drive: new FakeDrive(), graph: new FakeGraph(), dropbox: new FakeDropbox(), davfake: new FakeWebDav(), oauth}
for (const f of Object.values(fakes)) await f.server.start()

// the real WebDAV server: it ignores If-Match and If-None-Match, honours Overwrite: F and Range
const realPort = await freePort()
const users = new dav.SimpleUserManager()
const davUser = users.addUser('keepfin', 'app-password', false)
const rights = new dav.SimplePathPrivilegeManager()
rights.setRights(davUser, '/', ['all'])
const davReal = new dav.WebDAVServer({port: realPort, httpAuthentication: new dav.HTTPBasicAuthentication(users, 'Keepfin'), privilegeManager: rights, requireAuthentification: true})
await davReal.startAsync(realPort)
const basic = 'Basic ' + Buffer.from('keepfin:app-password').toString('base64')

const origin = (f) => new URL(f.server.origin)
// (the fake authorization server has the same shape: a `server` with an origin and hosts)
const info = () => ({
  drive: {port: Number(origin(fakes.drive).port), hosts: fakes.drive.server.hosts},
  graph: {port: Number(origin(fakes.graph).port), hosts: fakes.graph.server.hosts},
  dropbox: {port: Number(origin(fakes.dropbox).port), hosts: fakes.dropbox.server.hosts},
  davfake: {port: Number(origin(fakes.davfake).port), base: fakes.davfake.base},
  oauth: {port: Number(origin(oauth).port), hosts: oauth.server.hosts},
  davreal: {port: realPort, base: `http://127.0.0.1:${realPort}`, user: 'keepfin', password: 'app-password'},
})

// the desktop's own transport, for reading what the phone wrote and for writing what the phone should read
const providerOf = {drive: 'drive', graph: 'graph', dropbox: 'dropbox', davfake: 'webdav', davreal: 'webdav'}
const desktopRigs = new Map()
async function desktop(target, root) {
  const key = `${target}|${root}`
  if (desktopRigs.has(key)) return desktopRigs.get(key)
  const provider = providerOf[target]
  const f = fakes[target]
  const r =
    target === 'davreal'
      ? rig('webdav', (u) => u, {base: `http://127.0.0.1:${realPort}`, auth: basic, rootPath: root})
      : target === 'davfake'
        ? rig('webdav', (u) => u, {base: fakes.davfake.base, auth: basic, rootPath: root})
        : rig(provider, f.rewrite, {rootPath: root})
  if (provider === 'webdav') await r.client.probeWebdav(`.keepfin-probe-${Date.now().toString(36)}`).catch(() => undefined)
  desktopRigs.set(key, r)
  return r
}

const send = (res, status, value) => {
  const text = JSON.stringify(value)
  res.writeHead(status, {'content-type': 'application/json', 'content-length': Buffer.byteLength(text)})
  res.end(text)
}
const readBody = (req) =>
  new Promise((resolve) => {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      try {
        resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {})
      } catch {
        resolve({})
      }
    })
  })

const control = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url ?? '/', 'http://x')
    const body = req.method === 'POST' ? await readBody(req) : {}
    if (url.pathname === '/info') return send(res, 200, info())
    if (url.pathname === '/fault') {
      const f = fakes[body.target]
      if (!f) return send(res, 400, {error: 'unknown target'})
      const re = body.path ? new RegExp(body.path) : null
      f.server.fail(
        (q) => (!body.method || q.method === body.method) && (!re || re.test(q.url.pathname + q.url.search)),
        {status: body.status ?? 500, headers: body.headers ?? {}, body: body.body ?? {}},
        body.times ?? 1,
      )
      return send(res, 200, {ok: true})
    }
    if (url.pathname === '/reset') {
      for (const f of Object.values(fakes)) f.server.clearFaults()
      return send(res, 200, {ok: true})
    }
    if (url.pathname === '/option') {
      const f = fakes[body.target]
      if (!f) return send(res, 400, {error: 'unknown target'})
      f[body.name] = body.value
      return send(res, 200, {ok: true})
    }
    if (url.pathname === '/oauth/approve') return send(res, 200, {callback: oauth.approve(body.authUrl, {deny: body.deny, state: body.state})})
    if (url.pathname === '/oauth/revoke-all') {
      oauth.revokeAll()
      return send(res, 200, {ok: true})
    }
    if (url.pathname === '/oauth/lifetime') {
      oauth.accessLifetimeS = Number(body.seconds)
      return send(res, 200, {ok: true})
    }
    if (url.pathname === '/oauth/state') return send(res, 200, {refreshCalls: oauth.refreshCalls, revoked: oauth.revoked, seen: oauth.seen.length, refreshTokens: oauth.refreshTokens.size})
    if (url.pathname === '/expire') {
      fakes[body.target]?.expireAllSessions?.()
      return send(res, 200, {ok: true})
    }
    if (url.pathname === '/log') {
      const f = fakes[url.searchParams.get('target')]
      if (!f) return send(res, 400, {error: 'unknown target'})
      return send(res, 200, f.server.log.map((l) => `${l.method} ${l.path}`))
    }
    if (url.pathname === '/desktop/scan') {
      const r = await desktop(body.target, body.root)
      const lines = []
      const result = await r.transport.scan('', (l) => lines.push(l))
      const dir = await tmpDir()
      const out = []
      try {
        for (const l of lines.sort((a, b) => a.path.localeCompare(b.path))) {
          let sha = null
          if (body.hash !== false) {
            const staged = path.join(dir, crypto.randomBytes(4).toString('hex'))
            sha = await r.transport.download(l.path, staged, '*')
            await fs.rm(staged, {force: true})
          }
          out.push({path: l.path, size: l.size, sha256: sha})
        }
      } finally {
        await fs.rm(dir, {recursive: true, force: true})
      }
      const generation = await r.transport.readGeneration().catch(() => null)
      const keepfin = {}
      for (const name of ['folder.json']) keepfin[name] = await r.transport.readSmall(`.keepfin/${name}`).catch(() => null)
      return send(res, 200, {complete: result.complete, missingRoot: result.missingRoot, collisions: result.collisions, files: out, generation, keepfin})
    }
    if (url.pathname === '/desktop/put') {
      const r = await desktop(body.target, body.root)
      const data = body.bytes ? Buffer.from(body.bytes, 'base64') : Buffer.from(body.text ?? '', 'utf8')
      const dir = await tmpDir()
      try {
        const file = await writeTemp(dir, 'src', data)
        const up = await r.transport.uploadNew(file, body.path, contentIdOf(data))
        return send(res, 200, {ok: true, revision: up.revision, contentId: contentIdOf(data)})
      } finally {
        await fs.rm(dir, {recursive: true, force: true})
      }
    }
    if (url.pathname === '/desktop/edit') {
      // an edit of a file that is already there: the desktop replaces it on the revision it sees now
      const r = await desktop(body.target, body.root)
      const data = body.bytes ? Buffer.from(body.bytes, 'base64') : Buffer.from(body.text ?? '', 'utf8')
      const dir = await tmpDir()
      try {
        const file = await writeTemp(dir, 'src', data)
        const now = await r.transport.stat(body.path)
        if (!now) return send(res, 404, {error: 'not there'})
        const up = await r.transport.replace(file, body.path, contentIdOf(data), now.revision)
        return send(res, 200, {ok: true, revision: up.revision, contentId: contentIdOf(data)})
      } finally {
        await fs.rm(dir, {recursive: true, force: true})
      }
    }
    if (url.pathname === '/desktop/get') {
      const r = await desktop(body.target, body.root)
      const dir = await tmpDir()
      try {
        const staged = path.join(dir, 'out')
        const id = await r.transport.download(body.path, staged, '*')
        const data = await fs.readFile(staged)
        return send(res, 200, {contentId: id, size: data.length, text: data.length < 100_000 ? data.toString('utf8') : null})
      } finally {
        await fs.rm(dir, {recursive: true, force: true})
      }
    }
    if (url.pathname === '/desktop/generation') {
      const r = await desktop(body.target, body.root)
      const appended = await r.transport.appendGeneration(Number(body.generation), String(body.replica ?? 'desktop-replica'))
      return send(res, 200, {appended, generation: await r.transport.readGeneration()})
    }
    if (url.pathname === '/desktop/lock') {
      const r = await desktop(body.target, body.root)
      const lock = await r.transport.acquireLock({device: body.device ?? 'desktop', run: body.run ?? 'r1'}, Date.now())
      return send(res, 200, {held: lock.held})
    }
    if (url.pathname === '/quit') {
      send(res, 200, {ok: true})
      setTimeout(() => process.exit(0), 50)
      return
    }
    send(res, 404, {error: 'not found'})
  } catch (error) {
    send(res, 500, {error: String(error?.stack ?? error)})
  }
})
await new Promise((resolve) => control.listen(Number(args.control ?? 0), '127.0.0.1', resolve))
const controlPort = control.address().port
console.log('READY ' + JSON.stringify({control: controlPort, ...info(), tmp: os.tmpdir()}))

const shutdown = async () => {
  for (const f of Object.values(fakes)) await f.server.stop().catch(() => undefined)
  await davReal.stopAsync().catch(() => undefined)
  process.exit(0)
}
process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)
