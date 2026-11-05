// The pieces every full-player style arranges. A style chooses where they go; none of them re-implements playback.
import {useEffect, useLayoutEffect, useRef, useState, type ReactNode} from 'react'
import type {LibraryItem} from '../../../shared/library'
import {Art} from '../../library/Art'
import {toggleFavorite, useLibItem} from '../../library/libStore'
import {IconButton, Segmented, Tabs} from '../../ui/controls'
import {Icon} from '../../ui/Icon'
import {Menu, type MenuItem} from '../../ui/Menu'
import {openCurrentExternally, hostMedia, itemHasPicture, parkMedia, usePlayer, useActiveMedia} from '../playerStore'
import {PlayerOptions, SeekBar, Transport, VolumeControl} from '../NowPlaying'
import {setVideoOn} from '../musicVideo'
import {qualityChip} from '../panels'
import {closePlayer} from '../transition'
import {layouts, usePlayerLayout} from './layouts'
import {LyricsPanel, pickSide, QueuePanel, useSide, type Side} from './lyricsQueue'

/** The header every style starts with: collapse, a label, the style button and the speed / equalizer options. `children` sit before them. */
export function PlayerHead({children, kicker = 'Now playing'}: {children?: ReactNode; kicker?: ReactNode}) {
  return (
    <header className="full-head">
      <IconButton className="full-close" icon="chevronDown" label="Collapse player" onClick={closePlayer} />
      <span className="full-kicker">{kicker}</span>
      <span className="full-head-gap" />
      {children}
      <PlayerStyleButton />
      <PlayerOptions />
    </header>
  )
}

/** "Player style": a menu of every style in the registry, the chosen one checked, each with its one-line summary. */
export function PlayerStyleButton() {
  const [current, choose] = usePlayerLayout()
  const items: MenuItem[] = layouts.map((layout) => ({label: layout.name, detail: layout.summary, checked: layout.id === current, onSelect: () => choose(layout.id)}))
  return <Menu label="Player style" icon="palette" items={items} width={340} />
}

export function FavoriteButton({item}: {item: LibraryItem}) {
  return (
    <IconButton
      icon={item.favorite ? 'heartFill' : 'heart'}
      className={`heart${item.favorite ? ' on' : ''}`}
      label={item.favorite ? 'Remove from favorites' : 'Add to favorites'}
      aria-pressed={!!item.favorite}
      onClick={() => void toggleFavorite(item.id)}
    />
  )
}

/** "Artist · Album · From a device": the line under a title. */
export function songLine(item: LibraryItem, from?: string): string {
  return [item.artist || item.albumArtist, item.album, from ? `From ${from}` : ''].filter(Boolean).join(' · ')
}

/** The line shown when the song cannot play. */
export function PlayError({item}: {item: LibraryItem}) {
  const error = usePlayer((s) => s.error)
  if (!error || error.id !== item.id) return null
  return (
    <div className="now-error" role="alert">
      <span>{error.message}</span>
      <button type="button" className="link-btn" onClick={openCurrentExternally}>
        Open in another app
      </button>
    </div>
  )
}

/** Previous / play / next with shuffle and repeat, and the seek bar under it. */
export function TransportBlock() {
  return (
    <div className="transport-block">
      <Transport large />
      <SeekBar />
    </div>
  )
}

export function FormatChip({item}: {item: LibraryItem}) {
  const quality = qualityChip(item.path)
  return quality ? (
    <span className="quality-chip" title="File format" aria-label={`File format ${quality}`}>
      {quality}
    </span>
  ) : null
}

/** "Next: Glass Harbour". */
export function NextUp() {
  const nextId = usePlayer((s) => s.queue[s.index + 1])
  const next = useLibItem(nextId)
  return next ? <span className="next-up">Next: {next.title}</span> : null
}

export {VolumeControl}

/**
 * Lyrics and Queue as two tabs over one panel (`idBase` keeps the tab ids apart). A song without lyrics opens on the queue, so there is
 * never an empty half screen; the Lyrics tab stays and shows its note only if the person picks it.
 */
export function SidePanel({item, first = 'lyrics', idBase = 'player'}: {item: LibraryItem; first?: Side; idBase?: string}) {
  const panel = useSide(item.id, first)
  const tabs: {value: Side; label: string}[] = first === 'lyrics' ? [{value: 'lyrics', label: 'Lyrics'}, {value: 'queue', label: 'Queue'}] : [{value: 'queue', label: 'Queue'}, {value: 'lyrics', label: 'Lyrics'}]
  return (
    <>
      <Tabs<Side> label="Player panel" idBase={idBase} value={panel} onChange={pickSide} tabs={tabs} />
      <div id={`${idBase}-panel`} role="tabpanel" aria-labelledby={`${idBase}-tab-${panel}`} className="full-panel">
        {panel === 'lyrics' ? <LyricsPanel id={item.id} /> : <QueuePanel />}
      </div>
    </>
  )
}
/**
 * The cover, or, while the picture is on, the picture in the cover's place: the music engine's own element moves into the frame (it
 * keeps playing, so nothing reloads) and goes back to its hidden holder when the frame goes away. A Song | Video pill sits on the
 * corner of songs that have a picture; the picture goes full screen (button or double-click) and Esc or "Back to the player" returns.
 * `shape` is the frame's: a square cover, or the wide 16:9 stage. `children` are drawn over the frame (Cinema's lyric line).
 */
export function CoverStage({item, shape = 'square', children}: {item: LibraryItem; shape?: 'square' | 'wide'; children?: ReactNode}) {
  const picture = usePlayer((s) => s.picture)
  const media = useActiveMedia()
  const frame = useRef<HTMLDivElement>(null)
  const slot = useRef<HTMLDivElement>(null)
  const [full, setFull] = useState(false)
  const [hud, setHud] = useState(true)
  const showing = picture && !!media

  // the element moves in the same task that mounts the frame, so it keeps playing (see playerStore.hostMedia)
  useLayoutEffect(() => {
    if (!showing || !slot.current || !media) return
    hostMedia(slot.current, media)
    return () => parkMedia(media)
  }, [showing, media])

  const leaveFull = () => {
    if (frame.current && document.fullscreenElement === frame.current) void document.exitFullscreen().catch(() => {})
  }
  const toggleFull = () => {
    if (!frame.current) return
    if (document.fullscreenElement === frame.current) leaveFull()
    else void frame.current.requestFullscreen?.().catch(() => {})
  }
  useEffect(() => {
    const element = frame.current
    const change = () => setFull(document.fullscreenElement === element)
    document.addEventListener('fullscreenchange', change)
    return () => {
      document.removeEventListener('fullscreenchange', change)
      if (element && document.fullscreenElement === element) void document.exitFullscreen().catch(() => {})
    }
  }, [])
  useEffect(() => {
    if (!showing) leaveFull()
  }, [showing])

  // full screen: the controls show while the pointer or keys move, and hide after a moment
  useEffect(() => {
    const element = frame.current
    if (!full || !element) return
    let timer: ReturnType<typeof setTimeout> | undefined
    const wake = () => {
      setHud(true)
      clearTimeout(timer)
      timer = setTimeout(() => setHud(false), 2800)
    }
    wake()
    element.addEventListener('pointermove', wake)
    element.addEventListener('keydown', wake)
    return () => {
      clearTimeout(timer)
      element.removeEventListener('pointermove', wake)
      element.removeEventListener('keydown', wake)
      setHud(true)
    }
  }, [full])

  return (
    <div
      ref={frame}
      className={`cover-stage cover-${shape}${showing ? ' is-picture' : ''}${full ? ' is-full' : ''}${full && !hud ? ' hud-off' : ''}`}
      onDoubleClick={(event) => {
        if (showing && !(event.target as HTMLElement).closest('button, input, .cover-pill, .cover-hud')) toggleFull()
      }}
    >
      {!showing && <Art id={item.id} hasArt={item.hasArt} kind="audio" label={item.title} className="cover-art" size={64} space={item.space} />}
      <div ref={slot} className="cover-media" hidden={!showing} />
      {children}
      {showing && !full && <IconButton className="cover-full" icon="fullscreen" label="Full screen" onClick={toggleFull} />}
      {itemHasPicture(item) && !full && (
        <div className="cover-pill">
          <Segmented<'song' | 'video'>
            label={`Song or video for ${item.title}`}
            size="sm"
            value={picture ? 'video' : 'song'}
            options={[
              {value: 'song', label: 'Song'},
              {value: 'video', label: 'Video'},
            ]}
            onChange={(value) => setVideoOn(value === 'video')}
          />
        </div>
      )}
      {full && (
        <div className="cover-hud">
          <div className="cover-hud-top">
            <button type="button" className="cover-back" onClick={leaveFull}>
              <Icon name="arrowLeft" size={16} /> Back to the player
            </button>
            <span className="cover-hud-title">{[item.title, item.artist || item.albumArtist].filter(Boolean).join(' · ')}</span>
          </div>
          <div className="cover-hud-bar">
            <Transport />
            <SeekBar />
          </div>
        </div>
      )}
    </div>
  )
}
