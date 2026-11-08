// Automatic use of a sign-in (decisions D1 and D7): a request goes signed out first; only a sign-in failure is retried, once,
// with a sign-in Keepfin can use; the job carries the source that worked and its row says so.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {FormatRequest, InspectResult} from '../shared/contracts.js'
import type {CookieSource} from '../shared/policy.js'
import {DownloadManager} from './manager.js'
import {FakeEngine, fakeMedia, finishImmediately} from './fake-engine.js'
import {migrateJob} from './migrate.js'
import {EngineError} from './engine/ytdlp.js'
import type {SignInChoice} from './signin/auto.js'

const POST = 'https://www.instagram.com/p/DfW56Yoim48/'
const gallery: FormatRequest = {type: 'gallery'}
const firefox: SignInChoice = {
  source: {kind: 'browser', browser: 'firefox', profile: 'C:\\Profiles\\abc.default', label: 'Firefox'},
  cookies: {browser: 'firefox', profile: 'C:\\Profiles\\abc.default'},
}
const signInError = () => new EngineError('Sign-in required.', 'signIn')

async function setup(choices: SignInChoice[] = [firefox]) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-signin-'))
  const engine = new FakeEngine()
  const asked: string[] = []
  const manager = new DownloadManager({
    store: path.join(dir, 'queue.json'),
    defaultOutDir: path.join(dir, 'downloads'),
    engine,
    snapshotDelay: 5,
    retryDelays: [10, 10, 10],
    signIns: async (site) => {
      asked.push(site)
      return choices
    },
  })
  await manager.init()
  const seen: (CookieSource | undefined)[] = []
  const inspectWith = (rule: (cookies?: CookieSource) => InspectResult) => {
    engine.inspect = async (_url, ctx) => {
      seen.push(ctx.cookies)
      return rule(ctx.cookies)
    }
  }
  return {dir, engine, manager, asked, seen, inspectWith, cleanup: async () => (await manager.close(), fs.rm(dir, {recursive: true, force: true}))}
}
const needsSignIn = (cookies?: CookieSource): InspectResult => {
  if (!cookies) throw signInError()
  return {type: 'media', media: fakeMedia(POST, {title: 'Harbour photos', engine: 'gallery-dl'})}
}

test('inspection fails signed out, retries once with Firefox, and the job carries that sign-in', async () => {
  const f = await setup()
  try {
    f.inspectWith(needsSignIn)
    const result = await f.manager.inspect(POST)
    assert.equal(result.type, 'media')
    assert.deepEqual(f.seen, [undefined, firefox.cookies])
    f.engine.script = async (task, ctx, events) => {
      f.seen.push(ctx.cookies)
      return finishImmediately(task, ctx, events)
    }
    await f.manager.enqueue(POST, gallery)
    const job = f.manager.snapshot().jobs[0]
    assert.equal(job.signedIn, 'Signed in with Firefox')
    assert.equal('signIn' in job, false, 'the source itself never reaches the renderer')
    for (let i = 0; i < 100 && f.manager.snapshot().jobs[0].status !== 'completed'; i++) await new Promise((r) => setTimeout(r, 10))
    assert.deepEqual(f.seen.slice(2), [firefox.cookies], 'the download uses the sign-in directly, no failing first')
    assert.equal(f.manager.snapshot().jobs[0].signedIn, 'Signed in with Firefox')
  } finally {
    await f.cleanup()
  }
})

test('the off switch keeps every request signed out', async () => {
  const f = await setup()
  try {
    await f.manager.updateSettings({autoSignIn: false})
    f.inspectWith(needsSignIn)
    await assert.rejects(f.manager.inspect(POST), /Sign-in required/)
    assert.deepEqual(f.seen, [undefined])
    assert.deepEqual(f.asked, [])
  } finally {
    await f.cleanup()
  }
})

test('a manual cookie source stays the explicit override: the automatic rule does nothing', async () => {
  const f = await setup()
  try {
    await f.manager.updateSettings({cookies: {mode: 'browser', browser: 'chrome'}})
    f.inspectWith(() => {
      throw signInError()
    })
    await assert.rejects(f.manager.inspect(POST), /Sign-in required/)
    assert.deepEqual(f.seen, [{browser: 'chrome'}])
  } finally {
    await f.cleanup()
  }
})

test('only a sign-in failure is retried, a second sign-in failure stops, and an unknown site never asks', async () => {
  const f = await setup()
  try {
    f.inspectWith(() => {
      throw new EngineError('This video is unavailable.', 'tryAnother')
    })
    await assert.rejects(f.manager.inspect(POST), /unavailable/)
    assert.equal(f.seen.length, 1)
    f.seen.length = 0
    f.inspectWith(() => {
      throw signInError()
    })
    await assert.rejects(f.manager.inspect('https://www.instagram.com/p/second/'), /Sign-in required/)
    assert.equal(f.seen.length, 2, 'one retry, no more')
    f.asked.length = 0
    await assert.rejects(f.manager.inspect('https://example.com/video'), /Sign-in required/)
    assert.deepEqual(f.asked, [])
  } finally {
    await f.cleanup()
  }
})

test('a download that hits the sign-in failure only at download time gets the same single retry', async () => {
  const f = await setup()
  try {
    f.engine.inspectResult = (url) => ({type: 'media', media: fakeMedia(url, {title: 'Reel', engine: 'yt-dlp'})})
    await f.manager.inspect(POST)
    const seen: (CookieSource | undefined)[] = []
    f.engine.script = async (task, ctx, events) => {
      seen.push(ctx.cookies)
      if (!ctx.cookies) throw signInError()
      return finishImmediately(task, ctx, events)
    }
    await f.manager.enqueue(POST, {type: 'video', codec: 'auto', container: 'mp4'})
    for (let i = 0; i < 100 && f.manager.snapshot().jobs[0].status !== 'completed'; i++) await new Promise((r) => setTimeout(r, 10))
    assert.deepEqual(seen, [undefined, firefox.cookies])
    assert.equal(f.manager.snapshot().jobs[0].signedIn, 'Signed in with Firefox')
  } finally {
    await f.cleanup()
  }
})

test('a stored sign-in survives the queue file; a damaged one is dropped', () => {
  const base = {id: 'a', url: POST, format: gallery, status: 'completed', title: 't'}
  assert.deepEqual(migrateJob({...base, signIn: firefox.source})?.signIn, firefox.source)
  assert.deepEqual(migrateJob({...base, signIn: {kind: 'keepfin'}})?.signIn, {kind: 'keepfin'})
  assert.equal(migrateJob({...base, signIn: {kind: 'browser'}})?.signIn, undefined)
  assert.equal(migrateJob(base)?.signIn, undefined)
})
