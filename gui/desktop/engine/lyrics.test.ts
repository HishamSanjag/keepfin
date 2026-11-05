import assert from 'node:assert/strict'
import {test} from 'node:test'
import {findLyricsText, lookupLyrics} from './lyrics.js'

const song = {title: 'Midnight City', artist: 'M83', album: 'Hurry Up', duration: 244}
const record = {trackName: 'Midnight City', artistName: 'M83', duration: 244, syncedLyrics: '[00:01.00] a', plainLyrics: null}
const busy = () => new Response('{"message":"The server is busy, please retry in a moment"}', {status: 503})
const ok = (body: unknown) => new Response(JSON.stringify(body), {status: 200})

/** A fetch that answers with `answers` in turn and remembers what it was asked; waits are recorded, never slept. */
function service(...answers: (() => Response | Promise<Response>)[]) {
  const urls: string[] = []
  const waits: number[] = []
  return {
    urls,
    waits,
    fetch: (async (url: string) => {
      urls.push(url)
      const answer = answers[Math.min(urls.length, answers.length) - 1]
      return answer()
    }) as unknown as typeof fetch,
    wait: async (ms: number) => void waits.push(ms),
  }
}

test('plain words from the exact match give way to timed lyrics from the search', async () => {
  const plain = {...record, syncedLyrics: null, plainLyrics: 'a'}
  const api = service(() => ok(plain), () => ok([record]))
  assert.equal(await lookupLyrics(song, api), '[00:01.00] a')
  assert.equal(api.urls.length, 2)
})

test('plain words are kept when nothing timed exists', async () => {
  const plain = {...record, syncedLyrics: null, plainLyrics: 'a'}
  assert.equal(await lookupLyrics(song, service(() => ok(plain), () => ok([plain]))), 'a')
})

test('a timed exact match needs no search', async () => {
  const api = service(() => ok(record))
  assert.equal(await lookupLyrics(song, api), '[00:01.00] a')
  assert.equal(api.urls.length, 1)
})

test('a busy service is asked again, after 2 s and then 6 s', async () => {
  const api = service(busy, busy, () => ok(record))
  assert.equal(await lookupLyrics(song, api), '[00:01.00] a')
  assert.equal(api.urls.length, 3)
  assert.ok(api.urls.every((url) => url.includes('/api/get?')))
  assert.deepEqual(api.waits, [2000, 6000])
})

test('a service that stays busy gives up after three tries per request', async () => {
  const api = service(busy)
  assert.equal(await lookupLyrics(song, api), undefined)
  assert.equal(api.urls.length, 6, 'three for the exact request, three for the search')
})

test('the search is tried again too', async () => {
  const api = service(() => new Response('', {status: 404}), busy, () => ok([record]))
  assert.equal(await lookupLyrics(song, api), '[00:01.00] a')
  assert.deepEqual(api.urls.map((url) => url.includes('/api/search?')), [false, true, true])
  assert.deepEqual(api.waits, [2000])
})

test('a network failure is asked again', async () => {
  const api = service(
    () => Promise.reject(new TypeError('fetch failed')),
    () => ok(record),
  )
  assert.equal(await lookupLyrics(song, api), '[00:01.00] a')
  assert.deepEqual(api.waits, [2000])
})

test('"not found" is final: one request for each lookup, no waiting', async () => {
  const api = service(() => new Response('', {status: 404}))
  assert.equal(await lookupLyrics(song, api), undefined)
  assert.equal(api.urls.length, 2, 'the exact request once, the search once')
  assert.deepEqual(api.waits, [])
})

test('another refusal is final too', async () => {
  const api = service(() => new Response('', {status: 400}))
  assert.equal(await lookupLyrics({...song, duration: undefined}, api), undefined)
  assert.equal(api.urls.length, 1, 'no length: only the search is asked')
  assert.deepEqual(api.waits, [])
})

test('closing Keepfin stops the waiting at once', async () => {
  const controller = new AbortController()
  const api = service(busy)
  const started = Date.now()
  const lookup = lookupLyrics(song, {fetch: api.fetch, signal: controller.signal})
  setTimeout(() => controller.abort(new Error('Keepfin is closing.')), 50)
  assert.equal(await lookup, undefined)
  assert.ok(Date.now() - started < 1500, 'did not sit out the 2 s wait')
  assert.equal(api.urls.length, 1)
})

// ---- Find lyrics: what a search came to ----

test('a search says "found" when lyrics came, even after a busy try', async () => {
  const api = service(busy, () => ok(record))
  assert.deepEqual(await findLyricsText(song, api), {text: '[00:01.00] a', outcome: 'found'})
})

test('a song with no lyrics reads "none": every answer was a real "not found"', async () => {
  const api = service(() => new Response('', {status: 404}))
  assert.deepEqual(await findLyricsText(song, api), {text: undefined, outcome: 'none'})
  assert.equal(api.urls.length, 2)
})

test('a service that never answers reads "busy", not "none"', async () => {
  const api = service(busy)
  assert.deepEqual(await findLyricsText(song, api), {text: undefined, outcome: 'busy'})
})

test('busy on the exact request and "not found" on the search is still "busy": it could not be sure', async () => {
  const api = service(busy, busy, busy, () => new Response('', {status: 404}))
  assert.equal((await findLyricsText(song, api)).outcome, 'busy')
})

test('a network that never answers reads "busy"', async () => {
  const api = service(() => Promise.reject(new TypeError('fetch failed')))
  assert.equal((await findLyricsText(song, api)).outcome, 'busy')
})
