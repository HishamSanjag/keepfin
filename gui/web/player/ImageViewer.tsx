import {useEffect, useRef, useState, type PointerEvent, type WheelEvent} from 'react'
import {fileName} from '../format'
import {getLibItem, useLibItem} from '../library/libStore'
import {itemUrl} from '../media'
import {IconButton, InlineAlert} from '../ui/controls'
import {closeImage, openExternally, setImageId, useOverlay} from './play'

export function ImageViewer() {
  const {image} = useOverlay()
  if (!image) return null
  return <Viewer key="viewer" id={image.id} context={image.context} />
}

function Viewer({id, context}: {id: string; context: string[]}) {
  const item = useLibItem(id)
  const root = useRef<HTMLDivElement>(null)
  const [zoom, setZoom] = useState(1)
  const [pan, setPan] = useState({x: 0, y: 0})
  const [failed, setFailed] = useState(false)
  const drag = useRef<{x: number; y: number; px: number; py: number} | null>(null)
  const list = context.filter((other) => getLibItem(other))
  const at = list.indexOf(id)
  const reset = () => {
    setZoom(1)
    setPan({x: 0, y: 0})
  }
  const go = (delta: number) => {
    const next = list[at + delta]
    if (next) {
      reset()
      setFailed(false)
      setImageId(next)
    }
  }
  const zoomTo = (value: number) => {
    const next = Math.max(1, Math.min(8, value))
    setZoom(next)
    if (next === 1) setPan({x: 0, y: 0})
  }
  useEffect(() => {
    const returnTo = document.activeElement as HTMLElement | null
    root.current?.focus()
    return () => returnTo?.focus?.()
  }, [])
  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if (document.querySelector('dialog[open], .menu')) return
      if (event.key === 'Escape') (event.preventDefault(), event.stopPropagation(), closeImage())
      else if (event.key === 'ArrowLeft') (event.preventDefault(), go(-1))
      else if (event.key === 'ArrowRight') (event.preventDefault(), go(1))
      else if (event.key === '+' || event.key === '=') (event.preventDefault(), zoomTo(zoom * 1.25))
      else if (event.key === '-') (event.preventDefault(), zoomTo(zoom / 1.25))
      else if (event.key === '0') (event.preventDefault(), reset())
    }
    window.addEventListener('keydown', key, true)
    return () => window.removeEventListener('keydown', key, true)
  })
  if (!item) return null
  const onWheel = (event: WheelEvent) => zoomTo(zoom * (event.deltaY < 0 ? 1.15 : 1 / 1.15))
  const down = (event: PointerEvent) => {
    if (zoom <= 1) return
    ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
    drag.current = {x: event.clientX, y: event.clientY, px: pan.x, py: pan.y}
  }
  const move = (event: PointerEvent) => {
    const d = drag.current
    if (d) setPan({x: d.px + event.clientX - d.x, y: d.py + event.clientY - d.y})
  }
  return (
    <div className="image-view" ref={root} role="dialog" aria-label={`Viewing ${item.title}`} tabIndex={-1}>
      <header className="video-top">
        <IconButton icon="arrowLeft" label="Close image (Esc)" onClick={closeImage} />
        <div className="video-title">
          <strong title={fileName(item.path)}>{item.title}</strong>
          <span>{[list.length > 1 ? `${at + 1} of ${list.length}` : '', item.width ? `${item.width}×${item.height}` : ''].filter(Boolean).join(' · ')}</span>
        </div>
        <IconButton icon="zoomOut" label="Zoom out (−)" disabled={zoom <= 1} onClick={() => zoomTo(zoom / 1.25)} />
        <span className="tabular zoom-read" aria-live="polite">{Math.round(zoom * 100)}%</span>
        <IconButton icon="zoomIn" label="Zoom in (+)" disabled={zoom >= 8} onClick={() => zoomTo(zoom * 1.25)} />
        <IconButton icon="maximize" label="Fit to window (0)" disabled={zoom === 1 && !pan.x && !pan.y} onClick={reset} />
        <IconButton icon="external" label="Open in another app" onClick={() => openExternally([item.id])} />
      </header>
      <div className={`image-stage${zoom > 1 ? ' zoomed' : ''}`} onWheel={onWheel} onPointerDown={down} onPointerMove={move} onPointerUp={() => (drag.current = null)} onDoubleClick={() => (zoom > 1 ? reset() : zoomTo(2.5))}>
        {failed ? (
          <InlineAlert title="Keepfin can’t show this image">Its format isn’t supported by the built-in viewer. Open it in another app.</InlineAlert>
        ) : (
          <img src={itemUrl(item.id)} alt={item.title} draggable={false} onError={() => setFailed(true)} style={{transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`}} />
        )}
      </div>
      {at > 0 && <IconButton className="nav-arrow left" icon="chevronLeft" label="Previous image" onClick={() => go(-1)} />}
      {at >= 0 && at < list.length - 1 && <IconButton className="nav-arrow right" icon="chevron" label="Next image" onClick={() => go(1)} />}
    </div>
  )
}
