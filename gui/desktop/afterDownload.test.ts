import {test} from 'node:test'
import assert from 'node:assert/strict'
import {runAfterDownload, SAVING, type AfterDownloadSteps} from './afterDownload.js'
import type {AfterFacts, AfterKind} from '../shared/core/after.js'

// What the desktop does when a download finishes (dev-docs/plans/AFTER-DOWNLOAD.md): the core's plan run through the step interface, on an in-memory adapter.

/** Records every step the runner asks for; `failing` lists the steps that throw, `saved` and `attached` what the save and the attach return. */
function memory(options: {failing?: AfterKind[]; saved?: string[]; attached?: string[] | undefined} = {}) {
  const calls: string[] = []
  const step = async <T>(kind: AfterKind, value?: T): Promise<T | undefined> => {
    calls.push(kind)
    if (options.failing?.includes(kind)) throw new Error(`${kind} failed`)
    return value
  }
  const steps: AfterDownloadSteps = {
    lyrics: async () => void (await step('lyrics')),
    save: async () => (await step('save', options.saved ?? ['item-1']))!,
    attachVideo: async () => await step('attachVideo', 'attached' in options ? options.attached : ['song-1']),
    waitForSong: async () => void (await step('waitForSong')),
    joinPlaylist: async (items) => void calls.push(`joinPlaylist(${items.join(',')})`),
    cover: async () => void (await step('cover')),
    notifyDone: async () => void (await step('notify')),
    notifyExisting: async () => void (await step('notifyExisting')),
    settleVideos: async () => void (await step('settleVideos')),
    markNotSaved: async (error) => void calls.push(`notSaved(${(error as Error).message})`),
  }
  return {calls, steps}
}

const run = async (facts: AfterFacts, m: ReturnType<typeof memory>, options?: Parameters<typeof runAfterDownload>[2]) => {
  await runAfterDownload(facts, m.steps, options)
  return m.calls
}

test('a song runs in order: lyrics first, then save, join, cover, notify', async () => {
  const calls = await run({audio: true, lyrics: true, playlist: true}, memory())
  assert.deepEqual(calls, ['lyrics', 'save', 'joinPlaylist(item-1)', 'cover', 'notify', 'settleVideos'])
})

test('a failing save marks the job and the rest still runs', async () => {
  const calls = await run({audio: true, playlist: true}, memory({failing: ['save']}))
  assert.deepEqual(calls, ['save', 'notSaved(save failed)', 'joinPlaylist()', 'cover', 'notify', 'settleVideos'])
})

test('a failing lyrics step does not stop the save', async () => {
  const calls = await run({audio: true, lyrics: true}, memory({failing: ['lyrics']}))
  assert.deepEqual(calls, ['lyrics', 'save', 'cover', 'notify', 'settleVideos'])
})

test('a recording that was already there joins the playlist and says so', async () => {
  const calls = await run({audio: true, existing: true, hasFile: false, playlist: true}, memory(), {items: ['had-it']})
  assert.deepEqual(calls, ['joinPlaylist(had-it)', 'notifyExisting', 'settleVideos'])
})

test('a music video kept beside its song is not saved on its own', async () => {
  const calls = await run({video: 'attach', playlist: true}, memory())
  assert.deepEqual(calls, ['attachVideo', 'joinPlaylist(song-1)', 'cover', 'notify', 'settleVideos'])
})

test('a song that cannot take the video makes it an ordinary download', async () => {
  assert.deepEqual(await run({video: 'attach'}, memory({attached: undefined})), ['attachVideo', 'save', 'cover', 'notify', 'settleVideos'])
})

test('a failed attach also falls back to an ordinary save without marking the job', async () => {
  const calls = await run({video: 'attach', playlist: true}, memory({failing: ['attachVideo']}))
  assert.deepEqual(calls, ['attachVideo', 'save', 'joinPlaylist(item-1)', 'cover', 'notify', 'settleVideos'])
})

test('an ordinary save that fails after a failed attach marks the job', async () => {
  const calls = await run({video: 'attach'}, memory({failing: ['attachVideo', 'save']}))
  assert.deepEqual(calls, ['attachVideo', 'save', 'notSaved(save failed)', 'cover', 'notify', 'settleVideos'])
})

test('a music video before its song waits', async () => {
  assert.deepEqual(await run({video: 'wait', playlist: true}, memory()), ['waitForSong', 'cover', 'notify', 'settleVideos'])
})

test('saving again runs only the saving steps', async () => {
  const calls = await run({audio: true, lyrics: true, playlist: true}, memory(), {only: SAVING})
  assert.deepEqual(calls, ['save', 'joinPlaylist(item-1)'])
})

test('a cancelled download stops before the next step', async () => {
  const controller = new AbortController()
  const m = memory()
  const steps = {...m.steps, lyrics: async () => void controller.abort(new Error('cancelled'))}
  await assert.rejects(runAfterDownload({audio: true, lyrics: true}, steps, {signal: controller.signal}), /cancelled/)
  assert.deepEqual(m.calls, [])
})
