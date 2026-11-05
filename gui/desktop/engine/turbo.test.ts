import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createServer, type IncomingMessage, type ServerResponse} from 'node:http'
import {randomBytes} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {TurboError, discardTurbo, segmentSizeFor, turboDownload, turboFiles} from './turbo.js'

const body = randomBytes(1024 * 1024 + 12_345)

type Hooks = {
  onRange?: (start: number, end: number, req: IncomingMessage, res: ServerResponse) => boolean | void
  noRanges?: boolean
  reportedSize?: number
}

async function server(hooks: Hooks = {}) {
  const ranges: [number, number][] = []
  const http = createServer((req, res) => {
    if (req.headers['x-token'] !== 'secret') {
      res.writeHead(401).end()
      return
    }
    const match = req.headers.range?.match(/^bytes=(\d+)-(\d+)$/)
    if (!match || hooks.noRanges) {
      res.writeHead(200, {'Content-Length': body.length}).end(body)
      return
    }
    const start = Number(match[1])
    const end = Math.min(Number(match[2]), body.length - 1)
    ranges.push([start, end])
    if (hooks.onRange?.(start, end, req, res)) return
    res.writeHead(206, {
      'Content-Range': `bytes ${start}-${end}/${hooks.reportedSize ?? body.length}`,
      'Content-Length': end - start + 1,
    })
    res.end(body.subarray(start, end + 1))
  })
  await new Promise<void>((resolve) => http.listen(0, '127.0.0.1', resolve))
  const url = `http://127.0.0.1:${(http.address() as {port: number}).port}/media`
  return {url, ranges, close: () => new Promise<void>((resolve) => http.close(() => resolve()))}
}

async function tempDir() {
  return fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-turbo-'))
}

const headers = {'X-Token': 'secret'}
const segment = 64 * 1024

test('parallel segments reassemble byte-identical output and honour headers', async () => {
  const s = await server()
  const dir = await tempDir()
  const target = path.join(dir, 'Clip.f299.mp4')
  const progress: number[] = []
  try {
    await turboDownload([{url: s.url, headers, target}], {
      connections: 6,
      segmentSize: segment,
      onProgress: (p) => progress.push(p.downloadedBytes),
    })
    assert.deepEqual(await fs.readFile(target), body)
    assert.equal(s.ranges.length, Math.ceil(body.length / segment) + 1, 'one size probe plus every segment')
    assert.equal(progress.at(-1), body.length)
    await assert.rejects(fs.stat(turboFiles(target).map), 'segment map removed after success')
    await assert.rejects(fs.stat(turboFiles(target).temp))
  } finally {
    await s.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('interrupted transfers resume from the segment map without refetching finished segments', async () => {
  let served = 0
  let interrupting = true
  const controller = new AbortController()
  const s = await server({
    onRange: (start, end, _req, res) => {
      if (start === 0 && end === 0) return false
      if (interrupting && ++served > 6) {
        controller.abort(new Error('paused'))
        res.destroy()
        return true
      }
      return false
    },
  })
  const dir = await tempDir()
  const target = path.join(dir, 'Clip.f140.m4a')
  try {
    await assert.rejects(
      turboDownload([{url: s.url, headers, target, size: body.length}], {connections: 2, segmentSize: segment, signal: controller.signal}),
    )
    const map = JSON.parse(await fs.readFile(turboFiles(target).map, 'utf8'))
    assert.ok(map.done.length >= 4, 'finished segments were recorded')
    interrupting = false
    const before = s.ranges.length
    await turboDownload([{url: s.url, headers, target, size: body.length}], {connections: 4, segmentSize: segment})
    assert.deepEqual(await fs.readFile(target), body)
    const refetched = s.ranges.length - before
    assert.equal(refetched, Math.ceil(body.length / segment) - map.done.length)
  } finally {
    await s.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('transient errors and short segments are retried with backoff', async () => {
  const failed = new Set<number>()
  const s = await server({
    onRange: (start, end, _req, res) => {
      if (start === 0 && end === 0) return false
      if (!failed.has(start) && start % (segment * 3) === 0) {
        failed.add(start)
        if (start === 0) {
          res.writeHead(503).end()
          return true
        }
        // Short body: advertise the full range but send half.
        res.writeHead(206, {'Content-Range': `bytes ${start}-${end}/${body.length}`})
        res.end(body.subarray(start, start + 100))
        return true
      }
      return false
    },
  })
  const dir = await tempDir()
  const target = path.join(dir, 'Clip.mp3')
  try {
    await turboDownload([{url: s.url, headers, target}], {connections: 4, segmentSize: segment, backoffMs: 5})
    assert.deepEqual(await fs.readFile(target), body)
    assert.ok(failed.size >= 2)
  } finally {
    await s.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('size mismatch, missing range support and 403 raise TurboError for fallback', async () => {
  const dir = await tempDir()
  try {
    const wrong = await server()
    await assert.rejects(
      turboDownload([{url: wrong.url, headers, target: path.join(dir, 'a.mp4'), size: body.length + 1}], {connections: 2, segmentSize: segment}),
      TurboError,
    )
    await wrong.close()
    const plain = await server({noRanges: true})
    await assert.rejects(turboDownload([{url: plain.url, headers, target: path.join(dir, 'b.mp4')}], {connections: 2}), TurboError)
    await plain.close()
    const forbidden = await server({
      onRange: (start, end, _req, res) => {
        if (start === 0 && end === 0) return false
        res.writeHead(403).end()
        return true
      },
    })
    await assert.rejects(
      turboDownload([{url: forbidden.url, headers, target: path.join(dir, 'c.mp4')}], {connections: 2, segmentSize: segment, backoffMs: 1}),
      (error: Error) => error instanceof TurboError && /403/.test(error.message),
    )
    await forbidden.close()
    const unauthorized = await server()
    await assert.rejects(
      turboDownload([{url: unauthorized.url, headers: {}, target: path.join(dir, 'd.mp4')}], {connections: 2}),
      TurboError,
    )
    await unauthorized.close()
    await discardTurbo([path.join(dir, 'a.mp4'), path.join(dir, 'b.mp4'), path.join(dir, 'c.mp4')])
    assert.deepEqual(await fs.readdir(dir), [])
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('several streams share one connection pool', async () => {
  const s = await server()
  const dir = await tempDir()
  try {
    const targets = [path.join(dir, 'Clip.f299.mp4'), path.join(dir, 'Clip.f140.m4a')]
    await turboDownload(
      targets.map((target) => ({url: s.url, headers, target, size: body.length})),
      {connections: 8, segmentSize: segment},
    )
    for (const target of targets) assert.deepEqual(await fs.readFile(target), body)
  } finally {
    await s.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('segment sizing stays within 4–10 MB', () => {
  const MB = 1024 * 1024
  assert.equal(segmentSizeFor(10 * MB, 8), 4 * MB)
  assert.equal(segmentSizeFor(2000 * MB, 8), 10 * MB)
  assert.equal(segmentSizeFor(150 * MB, 8), Math.ceil((150 * MB) / 24))
})
