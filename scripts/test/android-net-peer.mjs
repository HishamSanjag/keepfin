// A desktop Devices peer for the Android interop test (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md, "wire compatibility"). It starts the real desktop
// network service (gui/desktop/net: server, pairing, sync, jobs, copies executor) on 127.0.0.1 with a temporary library, and a tiny
// plain-HTTP control port the test uses to read what the desktop sees and to answer what a person would (confirm the numbers, grant
// access, add an item). Nothing here is part of the app.
//
//   node scripts/test/android-net-peer.mjs --port 47216 --control 47217
//   adb reverse tcp:47216 tcp:47216 && adb reverse tcp:47217 tcp:47217
//   gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=app.keepfin.net.DesktopInteropTest" \
//       "-Pandroid.testInstrumentationRunnerArguments.interopPort=47216" "-Pandroid.testInstrumentationRunnerArguments.controlPort=47217"
//
// Control (all JSON): GET /state; POST /open-pairing; POST /decide {id, match}; POST /add {n, title}; POST /grant {actions};
// POST /job-view {state, percent}; POST /remove-peer; GET /folder (the media in the desktop's Keepfin folder with their SHA-256, its generation,
// whether its copy lock is held and what its trash holds: the evidence for a phone's copy kept on this desktop); POST /edit-file {path, text}; POST /quit.
import fs from 'node:fs/promises'
import http from 'node:http'
import path from 'node:path'
import {createHash} from 'node:crypto'
import {register} from 'tsx/esm/api'

register()
const {World} = await import('../../gui/desktop/net/testkit.ts')

const args = Object.fromEntries(process.argv.slice(2).reduce((all, value, index, list) => (value.startsWith('--') ? [...all, [value.slice(2), list[index + 1] ?? 'true']] : all), []))
const port = Number(args.port ?? 47216)
const controlPort = Number(args.control ?? 47217)

const world = await World.create()

/** The queue the desktop offers to "Download on...": it only records what the phone asked for. */
const jobs = new Map()
const listeners = new Set()
const jobPort = {
  spaces: () => [{id: 'music', name: 'Music'}],
  create: async ({jobId, url, format, space}) => {
    jobs.set(jobId, {jobId, url, format, space, view: {state: 'queued', percent: 0, title: 'Interop job', label: '720p'}})
    for (const l of listeners) l()
  },
  view: (jobId) => jobs.get(jobId)?.view,
  subscribe: (listener) => (listeners.add(listener), () => listeners.delete(listener)),
  freeBytes: async () => 50 * 1024 ** 3,
}

const node = await world.node('desktop', {port, jobs: jobPort, checkUrl: async (url) => url, leaseMs: 60_000})
const bytes = Buffer.alloc(300_000)
for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 7) % 251
const served = await node.putFile('Music/interop.bin', bytes)
for (let n = 1; n <= 3; n++) await node.add(n, `Desktop item ${n}`)
await node.store.flush?.()

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

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url ?? '/', 'http://x')
    const body = req.method === 'POST' ? await readBody(req) : {}
    const service = node.service
    if (url.pathname === '/state') {
      const state = service.state()
      const membership = await node.lib.membership()
      return send(res, 200, {
        deviceId: node.identity.deviceId,
        port: node.port,
        libraryId: node.libraryId,
        contentId: served.contentId,
        fileSize: bytes.length,
        fileSha256: createHash('sha256').update(bytes).digest('hex'),
        titles: node.titles(),
        pairing: state.pairing ? {id: state.pairing.id, stage: state.pairing.stage, sas: state.pairing.sas ?? null, role: state.pairing.role, peerName: state.pairing.peerName} : null,
        paired: state.paired.map((p) => ({deviceId: p.deviceId, name: p.name, online: p.online, libraries: p.libraries})),
        jobs: [...jobs.values()].map((j) => ({jobId: j.jobId, url: j.url, format: j.format, state: j.view.state})),
        // read from the library itself: the page's copy of this is only refreshed when this desktop's own link syncs
        signed: [{libraryId: node.libraryId, signed: membership.active, admin: !!membership.members[node.lib.actor()]?.perms.includes('admin')}],
      })
    }
    if (url.pathname === '/folder') {
      // what a phone's copy left in this desktop's Keepfin folder: the media with their hashes, and the executor's own records
      const out = []
      const walk = async (dir, rel) => {
        for (const entry of await fs.readdir(dir, {withFileTypes: true})) {
          const name = rel ? `${rel}/${entry.name}` : entry.name
          if (rel === '' && ['.keepfin', 'keepfin exports'].includes(entry.name.toLowerCase())) continue
          if (entry.isDirectory()) await walk(path.join(dir, entry.name), name)
          else out.push({path: name, size: (await fs.stat(path.join(dir, entry.name))).size, sha256: createHash('sha256').update(await fs.readFile(path.join(dir, entry.name))).digest('hex')})
        }
      }
      await walk(node.root, '')
      const keepfin = path.join(node.root, '.keepfin')
      const folderJson = JSON.parse(await fs.readFile(path.join(keepfin, 'folder.json'), 'utf8').catch(() => 'null'))
      const trash = []
      const trashDir = path.join(keepfin, 'trash', 'net')
      for (const name of await fs.readdir(trashDir).catch(() => [])) trash.push({name, sha256: createHash('sha256').update(await fs.readFile(path.join(trashDir, name))).digest('hex')})
      const lock = await fs.stat(path.join(keepfin, 'lock')).then(() => true, () => false)
      return send(res, 200, {files: out.sort((a, b) => a.path.localeCompare(b.path)), generation: folderJson?.generation ?? null, lock, trash})
    }
    if (url.pathname === '/edit-file') {
      // somebody edits a file in the desktop's folder (a phone's two-way copy brings it back)
      const file = path.join(node.root, ...String(body.path).split('/'))
      await fs.mkdir(path.dirname(file), {recursive: true})
      await fs.writeFile(file, String(body.text ?? ''), 'utf8')
      return send(res, 200, {ok: true})
    }
    if (url.pathname === '/open-pairing') return service.openPairing(), send(res, 200, {ok: true})
    if (url.pathname === '/decide') return service.decide(String(body.id), body.match === true), send(res, 200, {ok: true})
    if (url.pathname === '/add') return await node.add(Number(body.n), String(body.title ?? `Item ${body.n}`)), send(res, 200, {ok: true})
    if (url.pathname === '/grant') {
      const peer = service.peers.list()[0]
      if (!peer) return send(res, 409, {error: 'no peer'})
      await service.setAccess(peer.deviceId, node.libraryId, body.actions ?? [])
      return send(res, 200, {ok: true})
    }
    if (url.pathname === '/job-view') {
      const job = [...jobs.values()][0]
      if (!job) return send(res, 409, {error: 'no job'})
      job.view = {...job.view, state: String(body.state), percent: Number(body.percent ?? 0)}
      for (const l of listeners) l()
      return send(res, 200, {ok: true})
    }
    if (url.pathname === '/remove-peer') {
      const peer = service.peers.list()[0]
      if (!peer) return send(res, 409, {error: 'no peer'})
      return send(res, 200, await service.remove(peer.deviceId))
    }
    if (url.pathname === '/quit') {
      send(res, 200, {ok: true})
      setTimeout(() => process.exit(0), 50)
      return
    }
    send(res, 404, {error: 'not found'})
  } catch (error) {
    console.error(error)
    send(res, 500, {error: 'internal error (see the peer log)'})
  }
})
server.listen(controlPort, '127.0.0.1', () => {
  console.log(JSON.stringify({ready: true, port: node.port, control: controlPort, deviceId: node.identity.deviceId, libraryId: node.libraryId, contentId: served.contentId}))
})

const stop = async () => {
  server.close()
  await world.cleanup()
  process.exit(0)
}
process.on('SIGINT', stop)
process.on('SIGTERM', stop)
