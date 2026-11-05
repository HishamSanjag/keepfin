import {test} from 'node:test'
import assert from 'node:assert/strict'
import {darkenedTint, luminance, parseRgb} from './tint.js'

test('luminance follows WCAG: black 0, white 1', () => {
  assert.equal(luminance(0, 0, 0), 0)
  assert.ok(Math.abs(luminance(255, 255, 255) - 1) < 1e-9)
})

test('a bright artwork colour is scaled down to the cap, keeping its hue', () => {
  const tint = darkenedTint('250, 200, 90', 0.12)!
  const [r, g, b] = parseRgb(tint)!
  assert.ok(luminance(r, g, b) <= 0.12 + 1e-3)
  assert.ok(r > g && g > b, 'hue order is kept')
})

test('a dark colour is left alone and bad input gives nothing', () => {
  assert.equal(darkenedTint('20, 30, 40', 0.12), '20, 30, 40')
  assert.equal(darkenedTint(undefined), undefined)
  assert.equal(darkenedTint('red'), undefined)
  assert.equal(darkenedTint('300, 1, 1'), undefined)
})

test('even pure white ends up under the cap', () => {
  const [r, g, b] = parseRgb(darkenedTint('255, 255, 255', 0.1)!)!
  assert.ok(luminance(r, g, b) <= 0.1 + 1e-3)
})
