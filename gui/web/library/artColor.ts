// The colour band behind a collection header: the dominant colour of the artwork, sampled from the image that is
// already loaded, on a tiny canvas. Never blocks rendering; falls back to the space colour when sampling is not
// possible (no artwork, the image taints the canvas, or it fails to load).
import {useEffect, useState} from 'react'
import {artUrl} from '../media'

const cache = new Map<string, string | null>()

/** The most colourful middle tones of a small RGBA sample, as `r, g, b`. Greys, near-black and near-white are ignored. */
export function dominantColor(data: ArrayLike<number>): string | null {
  let r = 0
  let g = 0
  let b = 0
  let weight = 0
  for (let i = 0; i + 3 < data.length; i += 4) {
    if (data[i + 3] < 200) continue
    const max = Math.max(data[i], data[i + 1], data[i + 2])
    const min = Math.min(data[i], data[i + 1], data[i + 2])
    const light = (max + min) / 510
    const sat = max === min ? 0 : (max - min) / (255 - Math.abs(max + min - 255))
    if (light < 0.12 || light > 0.92) continue
    const w = sat * sat + 0.02
    r += data[i] * w
    g += data[i + 1] * w
    b += data[i + 2] * w
    weight += w
  }
  if (!weight) return null
  return `${Math.round(r / weight)}, ${Math.round(g / weight)}, ${Math.round(b / weight)}`
}

function sample(id: string): Promise<string | null> {
  return new Promise((resolve) => {
    const image = new Image()
    image.crossOrigin = 'anonymous'
    image.decoding = 'async'
    image.onload = () => {
      try {
        const canvas = document.createElement('canvas')
        canvas.width = canvas.height = 24
        const context = canvas.getContext('2d', {willReadFrequently: true})
        if (!context) return resolve(null)
        context.drawImage(image, 0, 0, 24, 24)
        resolve(dominantColor(context.getImageData(0, 0, 24, 24).data))
      } catch {
        resolve(null)
      }
    }
    image.onerror = () => resolve(null)
    image.src = artUrl(id)
  })
}

/** `r, g, b` of the artwork's dominant colour, or undefined until known (and when it cannot be sampled). */
export function useArtColor(id: string | undefined): string | undefined {
  const [color, setColor] = useState<string | null | undefined>(id ? cache.get(id) : null)
  useEffect(() => {
    if (!id) return setColor(null)
    if (cache.has(id)) return setColor(cache.get(id))
    let alive = true
    setColor(undefined)
    void sample(id).then((found) => {
      cache.set(id, found)
      if (alive) setColor(found)
    })
    return () => {
      alive = false
    }
  }, [id])
  return color ?? undefined
}
