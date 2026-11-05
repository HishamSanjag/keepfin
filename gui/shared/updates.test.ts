import {test} from 'node:test'
import assert from 'node:assert/strict'
import {betaStableNote, compareReleases, isPrerelease, pickRelease, releaseHighlights} from './updates.js'

const notes = ['## Windows', '- Adds **Spotify playlist** matching.', '- Fixes [resuming](https://x.test) downloads.', '- Third change.', '- Fourth change.', '', '## Android', '- Faster share sheet.'].join('\n')

test('release highlights use the platform section, plain text, at most three', () => {
  assert.deepEqual(releaseHighlights(notes), ['Adds Spotify playlist matching.', 'Fixes resuming downloads.', 'Third change.'])
  assert.deepEqual(releaseHighlights(notes, 'android'), ['Faster share sheet.'])
  assert.deepEqual(releaseHighlights(notes, 'windows', 2).length, 2)
})

test('release highlights tolerate unsectioned, empty and long notes', () => {
  assert.deepEqual(releaseHighlights('- one\n* two\ntext'), ['one', 'two'])
  assert.deepEqual(releaseHighlights(''), [])
  assert.deepEqual(releaseHighlights(undefined), [])
  assert.deepEqual(releaseHighlights('Just prose.'), [])
  assert.ok(releaseHighlights(`- ${'word '.repeat(60)}`)[0].length <= 140)
})

test('release order: 0.4.2 < 0.5.0-beta.1 < 0.5.0-beta.2 < 0.5.0', () => {
  const order = ['0.4.2', '0.5.0-beta.1', '0.5.0-beta.2', '0.5.0-beta.10', '0.5.0', '0.5.1']
  for (let i = 0; i < order.length; i++)
    for (let j = 0; j < order.length; j++) assert.equal(Math.sign(compareReleases(order[i], order[j])), Math.sign(i - j), `${order[i]} vs ${order[j]}`)
  assert.equal(compareReleases('v0.5.0', '0.5.0'), 0)
  assert.ok(isPrerelease('0.5.0-beta.1') && !isPrerelease('0.5.0'))
})

test('Stable offers only stable releases, Beta the newest of both, and neither downgrades', () => {
  const tags = ['v0.4.2', 'v0.5.0-beta.1', 'v0.5.0-beta.2', 'not-a-version']
  assert.equal(pickRelease(tags, 'stable', '0.4.1'), 'v0.4.2')
  assert.equal(pickRelease(tags, 'beta', '0.4.2'), 'v0.5.0-beta.2')
  assert.equal(pickRelease(tags, 'beta', '0.5.0-beta.2'), undefined)
  assert.equal(pickRelease([...tags, 'v0.5.0'], 'beta', '0.5.0-beta.2'), 'v0.5.0')
  // On a beta with Stable selected and no newer stable: nothing, never the older stable.
  assert.equal(pickRelease(tags, 'stable', '0.5.0-beta.1'), undefined)
  assert.equal(pickRelease([...tags, 'v0.5.0'], 'stable', '0.5.0-beta.1'), 'v0.5.0')
})

test('beta build on Stable explains itself only when it is current', () => {
  const state = {current: '0.5.0-beta.1', channel: 'stable' as const, phase: 'current' as const}
  assert.match(betaStableNote(state)!, /You.re on beta 0\.5\.0-beta\.1\. You.ll move to the next stable version automatically when it.s released\. Your library and settings stay\./)
  assert.equal(betaStableNote({...state, channel: 'beta'}), undefined)
  assert.equal(betaStableNote({...state, phase: 'available'}), undefined)
  assert.equal(betaStableNote({...state, current: '0.5.0'}), undefined)
})
