// Download on...: the receiver treats a request from another device as untrusted input (links, idempotency, limits, progress), and
// the sender writes a request to disk before it is sent and follows it over the event stream.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {RemoteJobView} from '../manager.js'
import {PeerClient, PeerHttpError} from './client.js'
import {guardUrl, UrlRefused} from './guard.js'
import {JobsHost, payloadHash, type JobPort} from './jobs.js'
import {World, until, type Node} from './testkit.js'

class FakePort implements JobPort {
  created: {jobId: string; url: string; format: unknown; options?: unknown; space?: string}[] = []
  views = new Map<string, RemoteJobView>()
  listeners = new Set<() => void>()
  free = 50e9
  spaces() {
    return [{id: 'music', name: 'Music'}]
  }
  async create(input: {jobId: string; url: string; format: unknown; options?: unknown; space?: string}) {
    if (input.url.includes('bad-format')) throw new Error('Choose a download format.')
    if (this.views.has(input.jobId)) return
    this.created.push(input)
    this.views.set(input.jobId, {state: 'queued', percent: 0, label: 'Download'})
  }
  view(jobId: string) {
    return this.views.get(jobId)
  }
  subscribe(listener: () => void) {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }
  async freeBytes() {
    return this.free
  }
  set(jobId: string, view: RemoteJobView) {
    this.views.set(jobId, view)
    for (const l of this.listeners) l()
  }
}

/** A resolver and a hop function standing in for DNS and HTTP: public names, private names and redirects. */
const names: Record<string, string[]> = {
  'video.example': ['93.184.216.34'],
  'cdn.example': ['93.184.216.35'],
  'internal.example': ['10.0.0.5'],
  'rebind.example': ['93.184.216.36', '127.0.0.1'],
  'bounce.example': ['93.184.216.37'],
  'loop.example': ['93.184.216.38'],
}
const redirects: Record<string, string> = {
  'http://bounce.example/go': 'http://internal.example/secret',
  'http://video.example/short': 'http://cdn.example/real.mp4',
  'http://loop.example/a': 'http://loop.example/a',
}
const checkUrl = (url: string) =>
  guardUrl(url, {
    resolve: async (host) => {
      if (!names[host]) throw new Error('no such name')
      return names[host]
    },
    hop: async (u) => {
      const to = redirects[u.href]
      return to ? {status: 302, location: to} : {status: 200}
    },
  })

const video = {type: 'video', codec: 'auto', container: 'mp4', maxHeight: 1080, formatId: 'device-specific-id'}

async function rig(options: {allowJobs?: boolean} = {}) {
  const w = await World.create()
  const port = new FakePort()
  const host = await w.node('Host', {jobs: port, checkUrl})
  const guest = await w.node('Guest')
  await w.pair(guest, host)
  if (options.allowJobs !== false) await host.service.setAccess(guest.identity.deviceId, w.libraryId, ['metadata.read', 'metadata.write', 'jobs'])
  const client = new PeerClient({identity: guest.identity, host: '127.0.0.1', port: host.port, pin: host.identity.deviceId})
  const post = (body: Record<string, unknown>) => client.request('POST', '/v1/jobs', {json: body}).then(async (r) => {
    const chunks: Buffer[] = []
    for await (const c of r.res) chunks.push(c as Buffer)
    return {status: r.status, body: JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}') as Record<string, any>}
  })
  return {w, port, host, guest, client, post, cleanup: async () => (client.close(), w.cleanup())}
}

test('the same request sent three times makes one job; a changed payload under the same id is refused', async () => {
  const r = await rig()
  try {
    const requestId = randomUUID()
    const body = {requestId, url: 'http://video.example/watch?v=1', format: video, space: 'music'}
    const first = await r.post(body)
    const second = await r.post(body)
    const third = await r.post(body)
    assert.deepEqual([first.status, second.status, third.status], [201, 200, 200])
    assert.equal(r.port.created.length, 1, 'one job')
    assert.equal(first.body.view.state, 'queued')
    // exact format ids belong to the device that inspected the link: they never reach the queue
    assert.equal((r.port.created[0].format as Record<string, unknown>).formatId, undefined)
    assert.equal((r.port.created[0].format as Record<string, unknown>).maxHeight, 1080)
    const changed = await r.post({...body, url: 'http://video.example/watch?v=2'})
    assert.equal(changed.status, 409)
    assert.equal(changed.body.error, 'payload-changed')
    assert.equal(r.port.created.length, 1)
    // the same id from another device is another request
    assert.equal(payloadHash({url: 'a', format: {b: 1, a: 2}}), payloadHash({url: 'a', format: {a: 2, b: 1}}), 'the identity of a payload ignores key order')
  } finally {
    await r.cleanup()
  }
})

test('the request survives a restart of the receiver: the record is on disk and a retry returns the same job', async () => {
  const r = await rig()
  try {
    const requestId = randomUUID()
    const body = {requestId, url: 'http://video.example/watch?v=1', format: video}
    const first = await r.post(body)
    const file = path.join(r.host.deviceDir, 'net', 'jobs.json')
    const saved = JSON.parse(await fs.readFile(file, 'utf8'))
    assert.equal(saved.entries.length, 1)
    assert.equal(saved.entries[0].requestId, requestId)
    const again = await JobsHost.open({file, port: r.port, notify: () => undefined, checkUrl})
    try {
      assert.equal(again.originOf(saved.entries[0].jobId)?.device, r.guest.identity.name)
      const retry = await again.handle({deviceId: r.guest.identity.deviceId, name: r.guest.identity.name}, body)
      assert.equal(retry.status, 200)
      assert.equal((retry.body as any).requestId, requestId)
      assert.equal(r.port.created.length, 1)
      void first
    } finally {
      again.close()
    }
  } finally {
    await r.cleanup()
  }
})

test('links to private addresses are refused, including through a redirect and a name that also resolves to one', async () => {
  const r = await rig()
  try {
    const ask = (url: string) => r.post({requestId: randomUUID(), url, format: video})
    for (const url of ['http://192.168.1.10/a.mp4', 'http://localhost/a.mp4', 'http://127.0.0.1:8080/', 'http://[::1]/', 'http://169.254.169.254/latest/', 'http://internal.example/a', 'http://rebind.example/a', 'ftp://video.example/a', 'http://user:pass@video.example/a', 'file:///c:/secret']) {
      const answer = await ask(url)
      assert.equal(answer.status, 422, url)
      assert.equal(answer.body.error, 'url', url)
    }
    // a public link that redirects inward is refused before anything is sent there
    const redirected = await ask('http://bounce.example/go')
    assert.equal(redirected.status, 422)
    assert.match(String(redirected.body.message), /private network/)
    // a redirect between two public hosts is fine
    assert.equal((await ask('http://video.example/short')).status, 201)
    // a redirect that never ends is stopped
    const loop = await ask('http://loop.example/a')
    assert.equal(loop.status, 422)
    assert.equal(r.port.created.length, 1, 'only the good link became a job')
    // the refusal is remembered: asking again with the same id gets the same answer and still makes no job
    const requestId = randomUUID()
    const body = {requestId, url: 'http://internal.example/a', format: video}
    const a = await r.post(body)
    const b = await r.post(body)
    assert.deepEqual([a.status, b.status], [422, 422])
    assert.equal(r.port.created.length, 1)
  } finally {
    await r.cleanup()
  }
})

test('the capture rules, the space, the room and the queue limit are applied; a device without the permission is refused', async () => {
  const r = await rig()
  try {
    const refusedFormat = await r.post({requestId: randomUUID(), url: 'http://video.example/bad-format', format: video})
    assert.equal(refusedFormat.status, 422, 'the queue refused the format and the reason is told')
    assert.match(String(refusedFormat.body.message), /format/)
    const badSpace = await r.post({requestId: randomUUID(), url: 'http://video.example/a', format: video, space: 'secret'})
    assert.equal(badSpace.status, 422)
    assert.equal(badSpace.body.error, 'space')
    assert.equal((await r.post({requestId: 'not-a-uuid', url: 'http://video.example/a', format: video})).status, 400)
    assert.equal((await r.post({requestId: randomUUID(), url: 'http://video.example/a'})).status, 400)
    r.port.free = 100 * 1024 * 1024
    const full = await r.post({requestId: randomUUID(), url: 'http://video.example/a', format: video})
    assert.equal(full.status, 507, 'not enough room on the receiving device')
    r.port.free = 50e9
    // the answer to "no room" is not remembered: the same id works once there is room
    const retryId = randomUUID()
    r.port.free = 1
    assert.equal((await r.post({requestId: retryId, url: 'http://video.example/a', format: video})).status, 507)
    r.port.free = 50e9
    assert.equal((await r.post({requestId: retryId, url: 'http://video.example/a', format: video})).status, 201)
  } finally {
    await r.cleanup()
  }
  const denied = await rig({allowJobs: false})
  try {
    const answer = await denied.client.request('POST', '/v1/jobs', {json: {requestId: randomUUID(), url: 'http://video.example/a', format: video}})
    answer.res.resume()
    assert.equal(answer.status, 403, 'a peer that was not allowed jobs gets a refusal with a reason')
    assert.equal(denied.port.created.length, 0)
  } finally {
    await denied.cleanup()
  }
})

test('the per-device queue limit is a refusal that says so', async () => {
  const w = await World.create()
  const port = new FakePort()
  try {
    const host = await w.node('Host', {jobs: port, checkUrl})
    const guest = await w.node('Guest')
    await w.pair(guest, host)
    const jobs = await JobsHost.open({file: path.join(host.dir, 'limit.json'), port, notify: () => undefined, checkUrl, perPeerActive: 2})
    const caller = {deviceId: guest.identity.deviceId, name: 'Guest'}
    const send = () => jobs.handle(caller, {requestId: randomUUID(), url: 'http://video.example/a', format: video})
    assert.equal((await send()).status, 201)
    assert.equal((await send()).status, 201)
    assert.equal((await send()).status, 429)
    port.set([...port.views.keys()][0], {state: 'completed', percent: 100})
    assert.equal((await send()).status, 201, 'a finished job no longer counts')
    jobs.close()
  } finally {
    await w.cleanup()
  }
})

test('Download on...: the request is on disk before it is sent, the sender follows progress, and the job is not in the sender queue', async () => {
  const w = await World.create()
  const port = new FakePort()
  try {
    const host = await w.node('Host', {jobs: port, checkUrl})
    const guest = await w.node('Guest')
    await w.pair(guest, host)
    // the other device has not allowed jobs yet: the page does not offer it, and a request is refused with the reason
    await until(() => guest.service.state().paired[0].online, 'the link')
    assert.equal(guest.service.state().paired[0].jobs.allowed, false)
    await host.service.setAccess(guest.identity.deviceId, w.libraryId, ['metadata.read', 'metadata.write', 'jobs'])
    await until(() => guest.service.state().paired[0].jobs.allowed, 'the sender to learn it may send jobs')
    assert.deepEqual(guest.service.state().paired[0].jobs.spaces, [{id: 'music', name: 'Music'}])

    await guest.service.sendJob(host.identity.deviceId, {url: 'http://video.example/watch?v=7', format: video, space: 'music'})
    const row = guest.service.state().remoteJobs[0]
    assert.equal(row.deviceName, host.identity.name)
    const saved = JSON.parse(await fs.readFile(path.join(guest.deviceDir, 'net', 'outgoing-jobs.json'), 'utf8'))
    assert.equal(saved.jobs[0].requestId, row.requestId, 'persisted')
    await until(() => port.created.length === 1, 'the job to reach the receiver')
    const jobId = port.created[0].jobId
    await until(() => guest.service.state().remoteJobs[0].state === 'queued', 'the sender to see it queued')

    // progress travels over the event stream, to the sender only
    port.set(jobId, {state: 'downloading', percent: 41, title: 'A video', label: '1080p', totalBytes: 5_000_000})
    await until(() => guest.service.state().remoteJobs[0].percent === 41, 'progress')
    assert.equal(guest.service.state().remoteJobs[0].title, 'A video')
    port.set(jobId, {state: 'consent', percent: 0, title: 'A video', totalBytes: 3e9})
    await until(() => guest.service.state().remoteJobs[0].state === 'consent', 'the wait for the person on the other device')
    port.set(jobId, {state: 'completed', percent: 100, title: 'A video'})
    await until(() => guest.service.state().remoteJobs[0].state === 'completed', 'completion')
    await guest.service.dismissJob(row.requestId)
    assert.equal(guest.service.state().remoteJobs.length, 0)
    assert.equal(port.created.length, 1, 'one job')
  } finally {
    await w.cleanup()
  }
})

test('a request made while the other device is away is kept and delivered once, when it is back', async () => {
  const w = await World.create()
  const port = new FakePort()
  try {
    const host = await w.node('Host', {jobs: port, checkUrl})
    const guest = await w.node('Guest')
    await w.pair(guest, host)
    await host.service.setAccess(guest.identity.deviceId, w.libraryId, ['metadata.read', 'metadata.write', 'jobs'])
    await until(() => guest.service.state().paired[0].jobs.allowed, 'jobs allowed')
    guest.service.pauseLink(host.identity.deviceId) // the network is gone
    await guest.service.sendJob(host.identity.deviceId, {url: 'http://video.example/away', format: video})
    assert.equal(guest.service.state().remoteJobs[0].state, 'sending')
    assert.equal(port.created.length, 0)
    guest.service.resumeLink(host.identity.deviceId)
    await until(() => port.created.length === 1, 'delivery after reconnecting')
    await until(() => guest.service.state().remoteJobs[0].state === 'queued', 'queued')
    // reconnecting again does not make a second job: the same id comes back
    guest.service.pauseLink(host.identity.deviceId)
    guest.service.resumeLink(host.identity.deviceId)
    await new Promise((r) => setTimeout(r, 600))
    assert.equal(port.created.length, 1)
  } finally {
    await w.cleanup()
  }
})

test('a refused request is shown with the reason and is not resent', async () => {
  const w = await World.create()
  const port = new FakePort()
  try {
    const host = await w.node('Host', {jobs: port, checkUrl})
    const guest = await w.node('Guest')
    await w.pair(guest, host)
    await host.service.setAccess(guest.identity.deviceId, w.libraryId, ['metadata.read', 'metadata.write', 'jobs'])
    await until(() => guest.service.state().paired[0].jobs.allowed, 'jobs allowed')
    await guest.service.sendJob(host.identity.deviceId, {url: 'http://internal.example/secret', format: video})
    await until(() => guest.service.state().remoteJobs[0]?.state === 'refused', 'the refusal')
    assert.match(guest.service.state().remoteJobs[0].error ?? '', /private network/)
    assert.equal(port.created.length, 0)
  } finally {
    await w.cleanup()
  }
})

test('the guard follows redirects hop by hop, connects only to checked addresses and stops where nothing answers', async () => {
  const seen: string[] = []
  const final = await guardUrl('http://video.example/short', {
    resolve: async (h) => names[h],
    hop: async (u, address) => {
      seen.push(`${u.host}@${address}`)
      const to = redirects[u.href]
      return to ? {status: 302, location: to} : {status: 200}
    },
  })
  assert.equal(final, 'http://cdn.example/real.mp4')
  assert.deepEqual(seen, ['video.example@93.184.216.34', 'cdn.example@93.184.216.35'])
  // a hop that cannot be reached ends the check; nothing beyond it was contacted
  const stopped = await guardUrl('http://video.example/short', {resolve: async (h) => names[h], hop: async () => { throw new Error('down') }})
  assert.equal(stopped, 'http://video.example/short')
  await assert.rejects(() => guardUrl('http://nowhere.example/', {resolve: async () => { throw new Error('nx') }}), (e: UrlRefused) => e.reason === 'address:unresolved')
  // a name that resolves to a public address first and a private one on the next question (rebinding) is caught at the second question
  let asked = 0
  await assert.rejects(
    () =>
      guardUrl('http://video.example/short', {
        resolve: async (h) => (h === 'cdn.example' && ++asked ? ['10.1.2.3'] : names[h]),
        hop: async (u) => (redirects[u.href] ? {status: 302, location: redirects[u.href]} : {status: 200}),
      }),
    (e: UrlRefused) => e.reason.startsWith('address:'),
  )
})

void PeerHttpError
type _Unused = Node
