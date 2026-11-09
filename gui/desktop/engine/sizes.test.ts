import {test} from 'node:test'
import assert from 'node:assert/strict'
import {fillSizes, type Fetch} from './sizes.js'

const format = (id: string) => ({
  format_id: id,
  protocol: 'https',
  vcodec: id === 'a' ? 'none' : 'avc1',
  acodec: id === 'a' ? 'mp4a' : 'none',
  height: id === 'a' ? undefined : 540,
  tbr: 300,
  url: `https://cdn.example/${id}.mp4`,
  http_headers: {'User-Agent': 'UA'},
})
const infoOf = () => ({formats: [format('a'), format('v1'), format('v2')]}) as {formats: Record<string, unknown>[]}
const sizes = (info: {formats: Record<string, unknown>[]}) => info.formats.map((f) => f.filesize)

test('fills filesize from Content-Length and sends the format headers', async () => {
  const seen: {url: string; method?: string; ua?: string}[] = []
  const fetcher: Fetch = async (url, init) => {
    seen.push({url, method: init.method, ua: (init.headers as Record<string, string>)['User-Agent']})
    return new Response(null, {status: 200, headers: {'content-length': url.includes('v1') ? '100970' : '187162'}})
  }
  const info = infoOf()
  await fillSizes(info, fetcher)
  assert.deepEqual(sizes(info), [187162, 100970, 187162])
  assert.ok(seen.every((s) => s.method === 'HEAD' && s.ua === 'UA'))
})

test('a failure leaves the size unknown', async () => {
  const fetcher: Fetch = async (url) => {
    if (url.includes('/a.')) throw new Error('network')
    if (url.includes('v1')) return new Response(null, {status: 404})
    return new Response(null, {status: 200}) // no length
  }
  const info = infoOf()
  await fillSizes(info, fetcher)
  assert.deepEqual(sizes(info), [undefined, undefined, undefined])
})

test('a refused HEAD falls back to a one-byte ranged GET', async () => {
  const methods: string[] = []
  const fetcher: Fetch = async (_url, init) => {
    methods.push(init.method ?? '')
    if (init.method === 'HEAD') return new Response(null, {status: 405})
    assert.equal((init.headers as Record<string, string>).Range, 'bytes=0-0')
    return new Response(null, {status: 206, headers: {'content-range': 'bytes 0-0/4321'}})
  }
  const info = infoOf()
  await fillSizes(info, fetcher)
  assert.deepEqual(sizes(info), [4321, 4321, 4321])
  assert.equal(methods.filter((m) => m === 'GET').length, 3)
})

test('a request that never answers is cut off and leaves the size unknown', async () => {
  const fetcher: Fetch = (_url, init) =>
    new Promise((_resolve, reject) => init.signal?.addEventListener('abort', () => reject(new Error('aborted'))))
  const info = infoOf()
  await fillSizes(info, fetcher, 50)
  assert.deepEqual(sizes(info), [undefined, undefined, undefined])
})

test('formats that already have a size are not requested', async () => {
  let calls = 0
  const info = {formats: [{...format('v1'), filesize: 5}]}
  await fillSizes(info, async () => {
    calls++
    return new Response(null, {status: 200})
  })
  assert.equal(calls, 0)
})
