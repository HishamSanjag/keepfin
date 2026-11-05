import {useEffect, useRef, type KeyboardEvent as ReactKeyEvent, type PointerEvent} from 'react'
import {Art} from '../library/Art'
import {useLibItem} from '../library/libStore'
import {IconButton} from '../ui/controls'
import {LyricsPanel, QueuePanel} from './full/lyricsQueue'
import {closeLyrics, closeNowPlaying, qualityChip, sourceWords, usePanels} from './panels'
import {usePlayer} from './playerStore'
import {ArtWash} from './Wash'
import {getPanelWidth, PANEL_DEFAULT, PANEL_MAX, PANEL_MIN, resetPanelWidth, setPanelWidth, usePanelWidth} from '../shell/layoutState'

/** The widest the panel may be so the main column keeps room: the rail (up to 216) and about 440 px stay for the page. */
const maxWidth = () => {
  const zoom = Number(getComputedStyle(document.documentElement).getPropertyValue('--zoom')) || 1
  return Math.max(PANEL_MIN, Math.min(PANEL_MAX, Math.floor(window.innerWidth / zoom - 216 - 440)))
}

/** Drag or arrow-key the panel's left edge. Double-click resets. The width is remembered per viewer. */
function ResizeHandle() {
  const width = usePanelWidth()
  const drag = useRef<{x: number; width: number} | null>(null)
  const zoom = () => Number(getComputedStyle(document.documentElement).getPropertyValue('--zoom')) || 1
  const clamp = (value: number) => Math.min(maxWidth(), Math.max(PANEL_MIN, value))
  const onDown = (event: PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return
    event.currentTarget.setPointerCapture(event.pointerId)
    drag.current = {x: event.clientX, width: getPanelWidth()}
  }
  const onMove = (event: PointerEvent<HTMLDivElement>) => {
    if (drag.current) setPanelWidth(clamp(drag.current.width + (drag.current.x - event.clientX) / zoom()))
  }
  const onKey = (event: ReactKeyEvent<HTMLDivElement>) => {
    const step = event.shiftKey ? 64 : 16
    if (event.key === 'ArrowLeft') setPanelWidth(clamp(getPanelWidth() + step))
    else if (event.key === 'ArrowRight') setPanelWidth(clamp(getPanelWidth() - step))
    else if (event.key === 'Home') setPanelWidth(PANEL_MIN)
    else if (event.key === 'End') setPanelWidth(maxWidth())
    else if (event.key === 'Enter') resetPanelWidth()
    else return
    event.preventDefault()
  }
  return (
    <div
      className="np-resize"
      role="separator"
      aria-orientation="vertical"
      aria-label="Resize Now playing panel"
      aria-valuemin={PANEL_MIN}
      aria-valuemax={PANEL_MAX}
      aria-valuenow={width}
      tabIndex={0}
      title="Drag to resize, double-click to reset"
      onPointerDown={onDown}
      onPointerMove={onMove}
      onPointerUp={() => (drag.current = null)}
      onPointerCancel={() => (drag.current = null)}
      onDoubleClick={resetPanelWidth}
      onKeyDown={onKey}
    />
  )
}

/** Right panel: cover, title, where it came from, and Up next. Toggled from the bar; the video player is untouched. */
export function NowPlayingPanel() {
  const width = usePanelWidth()
  // The grid reads the width from a root variable (and falls back to the default when the panel is closed).
  useEffect(() => {
    document.documentElement.style.setProperty('--np-w', `${width}px`)
  }, [width])
  const id = usePlayer((s) => s.currentId)
  const item = useLibItem(id)
  if (!id || !item) return null
  const source = sourceWords(item.sourceId)
  const quality = qualityChip(item.path)
  return (
    <>
      <aside className="np-panel" aria-label="Now playing panel">
        <header className="np-head">
          <strong>Now playing</strong>
          <IconButton icon="close" size="sm" label="Close Now playing panel" onClick={closeNowPlaying} />
        </header>
        <div className="np-cover">
          <Art id={item.id} hasArt={item.hasArt} kind="audio" label={item.title} size={48} />
        </div>
        <div className="np-info">
          <h3 className="np-title">{item.title}</h3>
          <p className="np-artist">{item.artist || item.albumArtist || ''}</p>
          <p className="muted small">{[item.album, source, quality].filter(Boolean).join(' · ')}</p>
        </div>
        <QueuePanel />
      </aside>
      <ResizeHandle />
    </>
  )
}

/** Lyrics over the whole main area. Escape closes it. */
export function LyricsOverlay() {
  const {lyrics} = usePanels()
  const id = usePlayer((s) => s.currentId)
  const item = useLibItem(id)
  useEffect(() => {
    if (!lyrics) return
    const key = (event: KeyboardEvent) => event.key === 'Escape' && closeLyrics()
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [lyrics])
  if (!lyrics || !id || !item) return null
  return (
    <section className="lyrics-overlay has-wash" aria-label="Lyrics">
      <ArtWash id={id} hasArt={item.hasArt} space={item.space} />
      <div className="lyrics-overlay-body">
        <header className="np-head">
          <div>
            <strong>{item.title}</strong>
            <span className="muted small"> {item.artist || ''}</span>
          </div>
          <IconButton icon="close" label="Close lyrics" onClick={closeLyrics} />
        </header>
        <LyricsPanel id={id} />
      </div>
    </section>
  )
}
