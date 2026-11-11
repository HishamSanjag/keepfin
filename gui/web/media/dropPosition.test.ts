import {test} from 'node:test'
import assert from 'node:assert/strict'
import {dropsAfter} from './dropPosition.js'

test('a drop in the upper half lands before the section, the lower half after it', () => {
  const box = {top: 100, height: 80}
  assert.equal(dropsAfter(120, box), false)
  assert.equal(dropsAfter(140, box), false)
  assert.equal(dropsAfter(141, box), true)
})
