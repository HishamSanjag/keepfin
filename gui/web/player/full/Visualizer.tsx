import {useEffect, useRef} from 'react'
import {prefersReducedMotion} from '../../motion/spring'
import {useAnalyser} from '../equalizer'
import {usePlayer} from '../playerStore'

const BARS = 40
/** The still bars shown under reduced motion (and as the resting shape when nothing plays): heights as a share of the full height. */
const STILL = [0.3, 0.5, 0.75, 0.45, 0.65, 0.9, 0.55, 0.35, 0.7, 0.85, 0.6, 0.4, 0.75, 0.95, 0.65, 0.45, 0.3, 0.55, 0.8, 0.7, 0.5, 0.35, 0.65, 0.9, 0.75, 0.55, 0.4, 0.6, 0.85, 0.7, 0.45, 0.3, 0.5, 0.75, 0.65, 0.4, 0.55, 0.8, 0.6, 0.35]

/**
 * A row of bars that moves with the sound, read from the equalizer's audio graph (the analyser). Under reduced motion the bars stay
 * still; with no audio graph (this browser has no Web Audio) it shows nothing.
 */
export function Visualizer() {
  const analyser = useAnalyser()
  const playing = usePlayer((s) => s.playing)
  const canvas = useRef<HTMLCanvasElement>(null)
  const still = prefersReducedMotion()

  useEffect(() => {
    const element = canvas.current
    const context = element?.getContext('2d')
    if (!element || !context || !analyser) return
    const color = getComputedStyle(element).color
    const draw = (heights: ArrayLike<number>) => {
      const scale = window.devicePixelRatio || 1
      const width = Math.round(element.clientWidth * scale)
      const height = Math.round(element.clientHeight * scale)
      if (element.width !== width || element.height !== height) {
        element.width = width
        element.height = height
      }
      context.clearRect(0, 0, width, height)
      context.fillStyle = color
      const gap = 4 * scale
      const bar = (width - gap * (BARS - 1)) / BARS
      for (let i = 0; i < BARS; i++) {
        const h = Math.max(2 * scale, Math.min(1, heights[i]) * height)
        context.beginPath()
        context.roundRect(i * (bar + gap), height - h, bar, h, Math.min(bar / 2, 2 * scale))
        context.fill()
      }
    }
    if (still || !playing) {
      draw(STILL)
      return
    }
    const data = new Uint8Array(analyser.frequencyBinCount)
    const levels = new Array<number>(BARS).fill(0)
    let frame = 0
    const tick = () => {
      analyser.getByteFrequencyData(data)
      // the low and middle bins carry the music; map them across the bars
      for (let i = 0; i < BARS; i++) levels[i] = Math.pow(data[Math.min(data.length - 1, Math.floor((i / BARS) * data.length * 0.8))] / 255, 1.3)
      draw(levels)
      frame = requestAnimationFrame(tick)
    }
    tick()
    return () => cancelAnimationFrame(frame)
  }, [analyser, playing, still])

  if (!analyser) return null
  return <canvas ref={canvas} className="visualizer" aria-hidden="true" data-visualizer={still ? 'still' : playing ? 'live' : 'idle'} />
}
