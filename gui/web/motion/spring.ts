// Springs shared with Android (ui/theme/Motion.kt). Tokens are (response in seconds, damping ratio);
// stiffness = (2π / response)², mass 1. See "Motion system" in DESIGN.md and dev-docs/design/mockups/motion.html.
import {useEffect, useRef} from 'react'

export type MotionToken = {response: number; damping: number}

export const motion = {
  /** Press feedback, content swaps, small controls. */
  quick: {response: 0.24, damping: 0.9},
  /** Morphs, covers, rows: a hair of overshoot at most. */
  snappy: {response: 0.34, damping: 0.86},
  /** Containers, lyrics, a space card opening into its page. */
  smooth: {response: 0.46, damping: 0.94},
  /** Player bar ↔ music player. */
  sheet: {response: 0.42, damping: 0.9},
  /** Front and back edges of tabs and switch knobs. */
  lead: {response: 0.2, damping: 0.92},
  trail: {response: 0.44, damping: 1},
  /** Progress: critically damped, so it never passes the engine's last reported value. */
  truth: {response: 0.32, damping: 1},
} satisfies Record<string, MotionToken>

export const stiffness = (token: MotionToken) => ((2 * Math.PI) / token.response) ** 2
const dampingCoefficient = (token: MotionToken) => 2 * token.damping * ((2 * Math.PI) / token.response)

// ---------------------------------------------------------------- reduced motion

const reduceQuery = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : undefined
/** Live: follows Windows "Animation effects" while the app runs. */
export const prefersReducedMotion = () => !!reduceQuery?.matches

// ---------------------------------------------------------------- spring

/**
 * One animated number. `to` aims it at a target (keeping its velocity, so it can be redirected mid-way); `set` places it
 * directly, for drags that follow the pointer. Every spring steps on one shared animation frame loop that stops when all rest.
 */
export class Spring {
  value: number
  target: number
  velocity = 0
  private readonly listeners = new Set<(value: number) => void>()

  constructor(
    value: number,
    public token: MotionToken = motion.snappy,
    /** Distance and speed (per second, ×6) under which the spring settles exactly on its target. */
    private readonly precision = 0.01,
  ) {
    this.value = value
    this.target = value
  }

  get resting() {
    return this.value === this.target && this.velocity === 0
  }

  to(target: number, velocity?: number) {
    this.target = target
    if (velocity !== undefined) this.velocity = velocity
    if (prefersReducedMotion()) this.settle()
    else schedule(this)
    return this
  }

  set(value: number, velocity = 0) {
    this.value = this.target = value
    this.velocity = velocity
    this.emit()
    return this
  }

  subscribe(listener: (value: number) => void) {
    this.listeners.add(listener)
    return () => void this.listeners.delete(listener)
  }

  /** Advances by `dt` seconds (semi-implicit Euler at ≥240 Hz substeps); returns whether it still moves. */
  step(dt: number) {
    if (this.resting) return false
    const k = stiffness(this.token)
    const c = dampingCoefficient(this.token)
    const n = Math.max(1, Math.ceil(dt * 240))
    const h = dt / n
    for (let i = 0; i < n; i++) {
      const acceleration = -k * (this.value - this.target) - c * this.velocity
      this.velocity += acceleration * h
      this.value += this.velocity * h
    }
    if (Math.abs(this.value - this.target) < this.precision && Math.abs(this.velocity) < this.precision * 6) {
      this.value = this.target
      this.velocity = 0
    }
    this.emit()
    return !this.resting
  }

  private settle() {
    this.value = this.target
    this.velocity = 0
    active.delete(this)
    this.emit()
  }

  private emit() {
    for (const listener of this.listeners) listener(this.value)
  }
}

const active = new Set<Spring>()
let frame = 0
let last = 0

function schedule(spring: Spring) {
  active.add(spring)
  if (frame || typeof requestAnimationFrame !== 'function') return
  last = performance.now()
  frame = requestAnimationFrame(tick)
}

function tick(now: number) {
  const dt = Math.min(0.05, (now - last) / 1000)
  last = now
  for (const spring of [...active]) if (!spring.step(dt)) active.delete(spring)
  frame = active.size ? requestAnimationFrame(tick) : 0
}

/** Steps every active spring by hand (tests and environments without animation frames). */
export function advanceSprings(seconds: number, fps = 60) {
  for (let t = 0; t < seconds && active.size; t += 1 / fps) for (const spring of [...active]) if (!spring.step(1 / fps)) active.delete(spring)
}

// ---------------------------------------------------------------- CSS curves

/**
 * The token as a CSS `linear()` easing plus duration, for transitions and the View Transitions API. Sampled from the
 * unit step response until it settles; a spring that overshoots keeps its overshoot.
 */
export function springEasing(token: MotionToken, samples = 40): {easing: string; duration: number} {
  const spring = new Spring(0, token, 0.001)
  spring.target = 1
  const dt = 1 / 240
  const values: number[] = [0]
  let time = 0
  while (!spring.resting && time < 3) {
    // a private stepper so CSS curves never touch the shared frame loop
    spring.step(dt)
    time += dt
    values.push(spring.value)
  }
  const duration = Math.round(time * 1000)
  const points: string[] = []
  for (let i = 0; i <= samples; i++) {
    const index = Math.round((i / samples) * (values.length - 1))
    points.push(String(Math.round(values[index] * 1000) / 1000))
  }
  return {easing: `linear(${points.join(', ')})`, duration}
}

// ---------------------------------------------------------------- React

/**
 * A spring owned by a component. Its value is applied with `apply` straight to the DOM (styles, attributes) on every
 * frame, so animation never re-renders React. Aim it with `spring.to(...)` from effects or handlers.
 */
export function useSpring(initial: number, token: MotionToken, apply: (value: number) => void, precision?: number) {
  const ref = useRef<Spring>(null)
  ref.current ??= new Spring(initial, token, precision)
  const applyRef = useRef(apply)
  applyRef.current = apply
  useEffect(() => {
    const spring = ref.current!
    applyRef.current(spring.value)
    return spring.subscribe((value) => applyRef.current(value))
  }, [])
  return ref.current
}

// ---------------------------------------------------------------- play / pause

const PAUSE = [
  [6, 5, 10, 5, 10, 19, 6, 19],
  [14, 5, 18, 5, 18, 19, 14, 19],
]
const PLAY = [
  [7, 5, 12, 7.92, 12, 16.08, 7, 19],
  [12, 7.92, 19, 12, 19, 12, 12, 16.08],
]

/** The play triangle (t = 0) folding into the two pause bars (t = 1), as two quads in a 24-unit box. */
export function playPausePath(t: number) {
  const k = Math.min(1.04, Math.max(-0.04, t))
  return [0, 1]
    .map((quad) => {
      const q = PLAY[quad].map((value, i) => (value + (PAUSE[quad][i] - value) * k).toFixed(2))
      return `M${q[0]} ${q[1]}L${q[2]} ${q[3]}L${q[4]} ${q[5]}L${q[6]} ${q[7]}Z`
    })
    .join('')
}
