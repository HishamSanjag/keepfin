// The immersive background of the full player and the lyrics view: a flat gradient from the current artwork's tint down to the
// canvas (no blur filter, so it is cheap to paint), under a canvas veil. The tint is darkened to a luminance cap (tint.ts) and
// the veil is fixed in CSS, so any artwork, even a white one, leaves AA text. It cross-fades when the track changes (the previous
// wash stays underneath while the new one fades in; with reduced motion the wash simply swaps).
import {useEffect, useRef, useState, type CSSProperties} from 'react'
import {useArtColor} from '../library/artColor'
import {prefersReducedMotion} from '../motion/spring'
import {darkenedTint} from './tint'
import {spaceById} from '../library/libStore'
import {spaceColorOf} from '../../shared/library'

type Layer = {key: number; id: string; hasArt: boolean; color: string}

function WashLayer({layer, entering}: {layer: Layer; entering: boolean}) {
  const tint = darkenedTint(useArtColor(layer.hasArt ? layer.id : undefined))
  return <div className={`art-wash-layer sp-${layer.color}${tint ? '' : ' is-plain'}${entering ? ' is-entering' : ''}`} style={tint ? ({['--tint' as string]: tint} as CSSProperties) : undefined} />
}

const FADE_MS = 900

export function ArtWash({id, hasArt, space}: {id: string; hasArt: boolean; space: string}) {
  const color = spaceColorOf(spaceById(space) ?? {id: space, color: undefined})
  const make = (key: number): Layer => ({key, id, hasArt, color})
  const counter = useRef(0)
  const [layers, setLayers] = useState<Layer[]>(() => [make(counter.current)])
  const current = layers[layers.length - 1]
  useEffect(() => {
    if (current.id === id) return
    counter.current += 1
    const next = make(counter.current)
    // The new wash fades in over the old one; the old one is dropped once it is covered (at once with reduced motion).
    setLayers((previous) => (prefersReducedMotion() ? [next] : [previous[previous.length - 1], next]))
    if (prefersReducedMotion()) return
    const timer = window.setTimeout(() => setLayers((now) => (now.length > 1 ? [now[now.length - 1]] : now)), FADE_MS + 100)
    return () => window.clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])
  return (
    <div className="art-wash" aria-hidden="true">
      {layers.map((layer, index) => (
        <WashLayer key={layer.key} layer={layer} entering={index > 0} />
      ))}
      <div className="art-wash-veil" />
    </div>
  )
}
