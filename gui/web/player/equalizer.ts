// Equalizer and bass boost for the music player, as on the phone: five bands of ±15 dB (60 Hz to 14 kHz), the same ten presets
// (Android's default values) and a bass boost of 0–100 %. The two players' audio goes through Web Audio the first time either is
// turned on (until then nothing is routed), and stays routed: an element given to Web Audio cannot be taken back. Settings are
// remembered on this device.
import {useEffect, useState, useSyncExternalStore} from 'react'
import {readPreference, savePreference} from '../prefs'

export const bands = [60, 230, 910, 3600, 14000] as const
export const BAND_DB = 15

/** Android's built-in presets (in dB), in its order. */
export const presets: {name: string; levels: number[]}[] = [
  {name: 'Normal', levels: [3, 0, 0, 0, 3]},
  {name: 'Classical', levels: [5, 3, -2, 4, 4]},
  {name: 'Dance', levels: [6, 0, 2, 4, 1]},
  {name: 'Flat', levels: [0, 0, 0, 0, 0]},
  {name: 'Folk', levels: [3, 0, 0, 2, -1]},
  {name: 'Heavy metal', levels: [4, 1, 9, 3, 0]},
  {name: 'Hip hop', levels: [5, 3, 0, 1, 3]},
  {name: 'Jazz', levels: [4, 2, -2, 2, 5]},
  {name: 'Pop', levels: [-1, 2, 5, 1, -2]},
  {name: 'Rock', levels: [5, 3, -1, 3, 5]},
]

export type EqState = {
  enabled: boolean
  /** dB per band, −15…15. */
  levels: number[]
  /** The preset the levels came from; null once a band is moved by hand. */
  preset: string | null
  /** Bass boost strength, 0–100 %. */
  bass: number
}

const KEY = 'keepfin-equalizer'
const initial: EqState = {enabled: false, levels: [0, 0, 0, 0, 0], preset: 'Flat', bass: 0}
const valid = (v: unknown): v is EqState => {
  const s = v as EqState
  return !!s && typeof s.enabled === 'boolean' && Array.isArray(s.levels) && s.levels.length === bands.length && s.levels.every((x) => typeof x === 'number' && Math.abs(x) <= BAND_DB) && typeof s.bass === 'number' && s.bass >= 0 && s.bass <= 100
}

let state: EqState = readPreference<EqState>(KEY, initial, valid)
const listeners = new Set<() => void>()

function update(next: EqState) {
  state = next
  savePreference(KEY, next)
  listeners.forEach((listener) => listener())
  apply()
}

export const getEqualizer = () => state
export const useEqualizer = () =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    () => state,
  )

export const setEqualizerOn = (enabled: boolean) => update({...state, enabled})
export function setBand(band: number, db: number) {
  const levels = state.levels.slice()
  levels[band] = Math.round(Math.max(-BAND_DB, Math.min(BAND_DB, db)))
  update({...state, enabled: true, levels, preset: null})
}
export function choosePreset(name: string) {
  const preset = presets.find((entry) => entry.name === name)
  if (preset) update({...state, enabled: true, levels: preset.levels.slice(), preset: preset.name})
}
export const setBassBoost = (percent: number) => update({...state, bass: Math.round(Math.max(0, Math.min(100, percent)))})
export const resetEqualizer = () => update({...initial, enabled: state.enabled})

// ---- the Web Audio graph ----------------------------------------------------------------------------------------------------

/** Bass boost at 100 %: a low shelf this many dB up (Android's strength 1000 is about that). */
const BASS_DB = 12

let ctx: AudioContext | undefined
let input: GainNode | undefined
let filters: BiquadFilterNode[] = []
let bassNode: BiquadFilterNode | undefined
let analyser: AnalyserNode | undefined
let elements: HTMLMediaElement[] = []
const routed = new WeakSet<HTMLMediaElement>()
let sink = ''

/** How many visualizers are on screen: they need the sound routed through the graph too. */
let watchers = 0
const needed = () => state.enabled || state.bass > 0 || watchers > 0

/** The player's audio elements (set `crossOrigin` before they load anything). They are routed when the equalizer is first used. */
export function attachEqualizer(els: HTMLMediaElement[]) {
  elements = els
  if (needed()) apply()
}

function build() {
  if (ctx || typeof AudioContext === 'undefined') return
  ctx = new AudioContext()
  input = ctx.createGain()
  filters = bands.map((frequency, index) => {
    const filter = ctx!.createBiquadFilter()
    filter.type = index === 0 ? 'lowshelf' : index === bands.length - 1 ? 'highshelf' : 'peaking'
    filter.frequency.value = frequency
    filter.Q.value = 1
    return filter
  })
  bassNode = ctx.createBiquadFilter()
  bassNode.type = 'lowshelf'
  bassNode.frequency.value = 100
  let node: AudioNode = input
  for (const next of [...filters, bassNode]) {
    node.connect(next)
    node = next
  }
  analyser = ctx.createAnalyser()
  analyser.fftSize = 128
  analyser.smoothingTimeConstant = 0.8
  node.connect(analyser)
  analyser.connect(ctx.destination)
  if (sink) void setGraphSink(sink)
}

function apply() {
  if (!needed() && !ctx) return
  build()
  if (!ctx || !input || !bassNode) return
  for (const el of elements) {
    if (routed.has(el)) continue
    try {
      ctx.createMediaElementSource(el).connect(input)
      routed.add(el)
    } catch {
      // already captured elsewhere: leave it as it plays
    }
  }
  const levels = state.enabled ? state.levels : bands.map(() => 0)
  filters.forEach((filter, index) => filter.gain.setTargetAtTime(levels[index], ctx!.currentTime, 0.02))
  bassNode.gain.setTargetAtTime((state.bass / 100) * BASS_DB, ctx.currentTime, 0.02)
  // headroom so boosted bands do not clip: half of the biggest boost comes off the input
  const boost = Math.max(0, ...levels, (state.bass / 100) * BASS_DB)
  input.gain.setTargetAtTime(Math.pow(10, -boost / 2 / 20), ctx.currentTime, 0.02)
  if (ctx.state === 'suspended') void ctx.resume().catch(() => undefined)
}

/** A paused context resumes when playback starts (browsers start one suspended until a gesture). */
export function resumeEqualizer() {
  if (ctx?.state === 'suspended') void ctx.resume().catch(() => undefined)
}

/** The output device: once routed, the sound leaves through the graph, so it follows the device the player was told to use. */
export async function setGraphSink(sinkId: string) {
  sink = sinkId
  const audio = ctx as (AudioContext & {setSinkId?: (id: string) => Promise<void>}) | undefined
  if (audio?.setSinkId) await audio.setSinkId(sinkId)
}

/**
 * The graph's analyser, for a visualizer: routes the sound through the graph while the caller is mounted (the equalizer stays flat unless it is on).
 * Undefined when this browser has no Web Audio, so the visualizer shows nothing.
 */
export function useAnalyser(): AnalyserNode | undefined {
  const [node, setNode] = useState<AnalyserNode | undefined>(analyser)
  useEffect(() => {
    watchers++
    apply()
    setNode(analyser)
    return () => {
      watchers--
    }
  }, [])
  return node
}
