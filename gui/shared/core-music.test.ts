// What fixtures.test.ts does not cover for the shared core's music adapter (gui/shared/core/music.ts): the request
// and search plan shape, the thresholds, searchSongs error handling, and the search loops.
// The fixture cases themselves run through the adapter in fixtures.test.ts, and in the core's own tests on the JVM and Node.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {
  ALTERNATE_MAX_DELTA_SEC, ALTERNATE_MIN_CONFIDENCE, CONFIDENT, MAX_ALTERNATES, MIN_CONFIDENCE,
  collectAlternates, pickBest, searchPlan, songsApiRequest,
  type MatchCandidate,
} from './core/music.js'
import * as desktop from '../desktop/engine/music.js'

test('core music: the songs request is the documented WEB_REMIX search', () => {
  const request = songsApiRequest(`Daft Punk ${'x'.repeat(400)}`)
  assert.equal(request.url, 'https://music.youtube.com/youtubei/v1/search?prettyPrint=false')
  const body = JSON.parse(request.body)
  assert.equal(body.context.client.clientName, 'WEB_REMIX')
  assert.equal(body.query.length, 300)
})

test('core music: the search plan is songs API, then music flat, then YouTube flat', () => {
  const plan = searchPlan({title: 'Song (feat. Other)', artists: ['A', 'B', 'C']})
  assert.deepEqual(plan.map((step) => step.kind), ['songsApi', 'flat', 'flat'])
})

test('core music: the thresholds are the ones the original desktop code uses', () => {
  assert.deepEqual([MIN_CONFIDENCE, CONFIDENT, ALTERNATE_MIN_CONFIDENCE, ALTERNATE_MAX_DELTA_SEC, MAX_ALTERNATES], [0.45, 0.8, 0.6, 7, 3])
})

test('core music: searchSongs surfaces HTTP failures and honours abort', async () => {
  const failing = (async () => new Response('', {status: 503})) as unknown as typeof fetch
  await assert.rejects(desktop.searchSongs('x', failing), /YouTube Music search failed \(HTTP 503\)/)
  const controller = new AbortController()
  controller.abort()
  await assert.rejects(desktop.searchSongs('x', (async (_u: string, init: RequestInit) => { init.signal?.throwIfAborted(); return new Response('{}') }) as unknown as typeof fetch, controller.signal))
})

const candidate = (id: string, title: string, durationSec = 200): MatchCandidate => ({
  url: `https://music.youtube.com/watch?v=${id.padEnd(11, '_')}`, title, artists: ['Artist'], durationSec, origin: 'ytm-song',
})
const track = {title: 'Song', artists: ['Artist'], durationMs: 200_000}

test('core music: pickBest stops at the first confident result and skips failing searches', async () => {
  const ran: number[] = []
  const searches = [
    async () => { ran.push(0); throw new Error('down') },
    async () => { ran.push(1); return [candidate('a', 'Song')] },
    async () => { ran.push(2); return [candidate('b', 'Song')] },
  ]
  const {best, searchesRun} = await pickBest(track, searches)
  assert.equal(searchesRun, 2)
  assert.deepEqual(ran, [0, 1])
  assert.ok(best && best.confidence >= CONFIDENT)
  const none = await pickBest(track, [async () => { throw new Error('x') }])
  assert.deepEqual(none, {best: undefined, searchesRun: 1})
})

test('core music: pickBest keeps the best over all searches when none is confident; errors can abort', async () => {
  const weak = candidate('a', 'Song (Live)', 260)
  const weaker = candidate('b', 'Other Thing', 200)
  const {best, searchesRun} = await pickBest(track, [async () => [weaker], async () => [weak]])
  assert.equal(searchesRun, 2)
  assert.ok(best)
  await assert.rejects(pickBest(track, [async () => { throw new Error('abort') }], (e) => { throw e }), /abort/)
})

test('core music: collectAlternates stops at MAX_ALTERNATES, skips failures and honours exclusions', async () => {
  const many = ['a', 'b', 'c', 'd'].map((id) => candidate(id, 'Song'))
  const ran: number[] = []
  const ranked = await collectAlternates(track, [
    async () => { ran.push(0); throw new Error('down') },
    async () => { ran.push(1); return many },
    async () => { ran.push(2); return [] },
  ], [many[0]!.url.slice(-11)])
  assert.deepEqual(ran, [0, 1])
  assert.equal(ranked.length, MAX_ALTERNATES)
  assert.ok(!ranked.some((r) => r.candidate.url === many[0]!.url))
})

