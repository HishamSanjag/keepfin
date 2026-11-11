import {test} from 'node:test'
import assert from 'node:assert/strict'
import {DEFAULT_PREFS, moveKey, normalizePrefs, stepKey, toggleIn} from './sidebarPrefs.js'

test('sidebar prefs: nothing saved gives the floating sidebar with every page in the usual order', () => {
  assert.deepEqual(normalizePrefs(undefined), DEFAULT_PREFS)
  assert.deepEqual(normalizePrefs('broken'), DEFAULT_PREFS)
})

test('sidebar prefs: a saved order keeps its pages once each, drops unknown ones and adds missing ones at the end', () => {
  const prefs = normalizePrefs({nav: ['Downloads', 'Home', 'Downloads', 'Settings', 'Nope'], style: 'docked'})
  assert.deepEqual(prefs.nav, ['Downloads', 'Home', 'Search', 'Library'])
  assert.equal(prefs.style, 'docked')
})

test('sidebar prefs: Downloads can never be hidden and lists lose duplicates', () => {
  const prefs = normalizePrefs({hiddenNav: ['Downloads', 'Search', 'Search'], pinned: ['space:music', 'space:music', 3], hidden: ['playlist:a']})
  assert.deepEqual(prefs.hiddenNav, ['Search'])
  assert.deepEqual(prefs.pinned, ['space:music'])
  assert.deepEqual(prefs.hidden, ['playlist:a'])
})

test('sidebar prefs: toggling, dropping and stepping keys', () => {
  assert.deepEqual(toggleIn(['a'], 'b'), ['a', 'b'])
  assert.deepEqual(toggleIn(['a', 'b'], 'a'), ['b'])
  assert.deepEqual(moveKey(['a', 'b', 'c'], 'a', 'c', true), ['b', 'c', 'a'])
  assert.deepEqual(moveKey(['a', 'b', 'c'], 'c', 'a', false), ['c', 'a', 'b'])
  assert.deepEqual(moveKey(['a', 'b'], 'a', 'missing', true), ['a', 'b'])
  assert.deepEqual(stepKey(['a', 'b', 'c'], 'b', -1), ['b', 'a', 'c'])
  assert.deepEqual(stepKey(['a', 'b', 'c'], 'c', 1), ['a', 'b', 'c'])
})
