import assert from 'node:assert/strict'
import test from 'node:test'
import {advanceSprings, motion, playPausePath, Spring, springEasing, stiffness} from './spring'

test('tokens match Android: stiffness is (2π / response)²', () => {
  assert.ok(Math.abs(stiffness(motion.snappy) - (2 * Math.PI / 0.34) ** 2) < 1e-9)
  assert.ok(Math.abs(stiffness(motion.quick) - 685.4) < 0.1)
})

test('a spring settles exactly on its target and stops', () => {
  const spring = new Spring(0, motion.snappy).to(100)
  advanceSprings(2)
  assert.equal(spring.value, 100)
  assert.ok(spring.resting)
})

test('snappy overshoots by at most a hair; truth never passes its target', () => {
  for (const [token, limit] of [[motion.snappy, 1.01], [motion.truth, 1]] as const) {
    const spring = new Spring(0, token, 0.0001).to(1)
    let peak = 0
    for (let i = 0; i < 240; i++) {
      spring.step(1 / 120)
      peak = Math.max(peak, spring.value)
    }
    assert.ok(peak <= limit + 1e-9, `${JSON.stringify(token)} peaked at ${peak}`)
  }
})

test('a redirected spring keeps its velocity; set places it directly', () => {
  const spring = new Spring(0, motion.smooth).to(10)
  spring.step(0.05)
  const moving = spring.velocity
  assert.ok(moving > 0)
  spring.to(-10)
  assert.equal(spring.velocity, moving)
  spring.set(3)
  assert.equal(spring.value, 3)
  assert.ok(spring.resting)
})

test('CSS curves start at 0, end at 1 and last about as long as the spring', () => {
  const {easing, duration} = springEasing(motion.sheet)
  assert.match(easing, /^linear\(0, .*, 1\)$/)
  assert.ok(duration > 300 && duration < 1200, String(duration))
  const truth = springEasing(motion.truth).easing.slice(7, -1).split(', ').map(Number)
  assert.ok(truth.every((v) => v <= 1), 'critically damped curves never overshoot')
})

test('play/pause path is two quads that fold between triangle and bars', () => {
  assert.equal(playPausePath(0), 'M7.00 5.00L12.00 7.92L12.00 16.08L7.00 19.00ZM12.00 7.92L19.00 12.00L19.00 12.00L12.00 16.08Z')
  assert.equal(playPausePath(1), 'M6.00 5.00L10.00 5.00L10.00 19.00L6.00 19.00ZM14.00 5.00L18.00 5.00L18.00 19.00L14.00 19.00Z')
})
