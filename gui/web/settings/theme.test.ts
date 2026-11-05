import {test} from 'node:test'
import assert from 'node:assert/strict'
import {readTheme} from '../prefs.js'

// A new install follows the system; whatever the person saved (including an explicit Dark) is kept exactly as it was.
function withStorage(read: () => string | null, run: () => void) {
  const host = globalThis as unknown as {localStorage?: unknown}
  const before = host.localStorage
  host.localStorage = {getItem: read}
  try {
    run()
  } finally {
    host.localStorage = before
  }
}

test('theme: nothing saved yet follows the system', () => {
  withStorage(() => null, () => assert.equal(readTheme(), 'system'))
})

test('theme: a saved Dark, Light or System choice is kept', () => {
  for (const saved of ['dark', 'light', 'system']) withStorage(() => saved, () => assert.equal(readTheme(), saved))
})

test('theme: an unknown saved value and blocked storage fall back to the system', () => {
  withStorage(() => 'purple', () => assert.equal(readTheme(), 'system'))
  withStorage(() => {
    throw new Error('blocked')
  }, () => assert.equal(readTheme(), 'system'))
})
