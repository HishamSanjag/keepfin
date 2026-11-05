import assert from 'node:assert/strict'
import test from 'node:test'
import type {CombinePreview, CombineState} from '../../shared/combine'
import {canSeparate, combineLine, combineQuestion, defaultKeep, reviewEntry, reviewFacts, separateFacts} from './devices/combineLogic'

const preview: CombinePreview & {otherItems?: number} = {deviceId: 'd', deviceName: 'Desk PC', libraryName: 'Main', items: 210, added: 1, linked: 0, keptDuplicates: 2, missingFiles: 0, differing: 0, playlists: 1, spaces: 0, review: [], reviewTotal: 0, separateDays: 30}

test('the default library to keep is the one with more items, this PC when the other size is unknown', () => {
  assert.equal(defaultKeep(undefined), 'mine')
  assert.equal(defaultKeep(preview), 'mine')
  assert.equal(defaultKeep({...preview, otherItems: 1326}), 'theirs')
  assert.equal(defaultKeep({...preview, otherItems: 40}), 'mine')
  assert.equal(defaultKeep({...preview, otherItems: 210}), 'theirs')
})

test('the question uses the real counts and plain plurals', () => {
  assert.equal(combineQuestion({...preview, otherItems: 1}, 'Desk PC'), 'Desk PC has 1 item. This PC has 210 items. Combine them into one library that both keep up to date?')
  assert.match(combineQuestion(preview, 'Desk PC'), /^Desk PC and this PC each have their own library\. This PC has 210 items\./)
})

test('the review leaves out lines that are zero and says singular and plural plainly', () => {
  const facts = reviewFacts(preview, 'Desk PC')
  assert.deepEqual(facts, ['1 item is added', '2 items are the same file twice here: both are kept', '1 playlist comes along', 'Favourites and play counts come along'])
  assert.equal(reviewEntry({kind: 'differingField', s: 'a', field: 'title', title: 'Night Ferry'}, 'Desk PC'), '“Night Ferry”: the title is different. Desk PC’s stays.')
  // never an id: without a title the entry says what it is
  assert.equal(reviewEntry({kind: 'differingField', s: 'a', field: 'title'}, 'Desk PC'), 'One item: the title is different. Desk PC’s stays.')
  assert.equal(reviewEntry({kind: 'sameNamePlaylist', s: 'p'}, 'Desk PC'), 'A playlist with the same name is on both. Both are kept.')
})

test('the card line follows the phase', () => {
  const base: CombineState = {available: true, phase: 'applying', progress: {done: 1230, total: 1450}}
  assert.equal(combineLine(base), 'Combining libraries · 1,230 of 1,450')
  assert.equal(combineLine({available: true, phase: 'paused', paused: 'Offline.'}), 'Combining paused · Offline.')
  const soon = new Date(Date.now() + 5 * 86400000).toISOString()
  const done: CombineState = {available: true, phase: 'done', combined: {since: '', until: soon, libraryName: 'x', canSeparate: true}}
  assert.match(combineLine(done), /^Combined · Separate again until /)
  assert.equal(canSeparate(done), true)
  assert.equal(canSeparate({...done, combined: {...done.combined!, canSeparate: false}}), false)
  assert.equal(canSeparate({...done, combined: {...done.combined!, until: new Date(Date.now() - 1000).toISOString()}}), false)
  assert.equal(combineLine({...done, combined: {...done.combined!, canSeparate: false}}), 'Combined')
})

test('the Separate dialog sentences, including the unreachable case', () => {
  const state: CombineState = {available: true, phase: 'done', separate: {brought: 182, heldElsewhere: [], heldElsewhereTotal: 40, addedSince: 0, deviceName: 'Desk PC', unreachable: true}}
  const facts = separateFacts(state)!
  assert.equal(facts.held, 'Desk PC has copies of 40 items this PC brought.')
  assert.equal(facts.addedSince, null)
  assert.equal(facts.unreachable, 'Desk PC is not reachable. Separate anyway: Desk PC keeps everything this PC brought.')
})
