import {memo, useCallback, useEffect, useRef, useState, type MouseEvent} from 'react'
import type {LibraryItem} from '../../shared/library'
import {formatDuration} from '../format'
import {Art} from '../library/Art'
import {itemMenu} from '../library/itemMenu'
import {toggleFavorite, useLibItem} from '../library/libStore'
import {motion, prefersReducedMotion, useSpring} from '../motion/spring'
import {IconButton} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {Menu, type MenuItem} from '../ui/Menu'
import {EqualizerDialog} from './EqualizerDialog'
import {useEqualizer} from './equalizer'
import {PlayPauseIcon} from './PlayPauseIcon'
import {
  canPickOutput,
  cycleRepeat,
  itemHasPicture,
  next,
  openCurrentExternally,
  previous,
  seekTo,
  setOutputDevice,
  setSleep,
  setSpeed,
  setVolume,
  stopPlayback,
  toggleMute,
  togglePlay,
  toggleShuffle,
  usePlayer,
  useTime,
} from './playerStore'
import {qualityChip, toggleLyrics, toggleNowPlaying, usePanels} from './panels'
import {closePlayer, openPlayer, showPicture} from './transition'

export const speeds = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]

/** Volume change for one mouse-wheel notch (5 %); trackpads send many small deltas, so the sign is what counts. */
export const WHEEL_VOLUME_STEP = 0.05

/**
 * Mouse wheel over the element changes the volume: up is louder, down is quieter. The listener is not passive, so the wheel
 * adjusts the volume instead of scrolling the page or the player behind it.
 */
export function useWheelVolume(get: () => number, set: (volume: number) => void): (element: HTMLElement | null) => void {
  const latest = useRef({get, set})
  latest.current = {get, set}
  const detach = useRef<(() => void) | null>(null)
  // A callback ref, so the listener follows the element even when it mounts after the component (the video controls).
  return useCallback((element: HTMLElement | null) => {
    detach.current?.()
    detach.current = null
    if (!element) return
    const wheel = (event: WheelEvent) => {
      const delta = event.deltaY || event.deltaX
      if (!delta) return
      event.preventDefault()
      const {get, set} = latest.current
      const next = Math.round((get() + (delta < 0 ? WHEEL_VOLUME_STEP : -WHEEL_VOLUME_STEP)) * 100) / 100
      set(Math.max(0, Math.min(1, next)))
    }
    element.addEventListener('wheel', wheel, {passive: false})
    detach.current = () => element.removeEventListener('wheel', wheel)
  }, [])
}

export function SeekBar({compact}: {compact?: boolean}) {
  const {position, duration} = useTime()
  const [drag, setDrag] = useState<number>()
  const shown = drag ?? position
  const max = duration || 0
  const fill = `${max ? (shown / max) * 100 : 0}%`
  return (
    <div className={`seek${compact ? ' seek-compact' : ''}`}>
      <span className="seek-time tabular">{formatDuration(shown) || '0:00'}</span>
      <div className="seek-track" style={{['--fill' as string]: fill}}>
        {drag !== undefined && (
          <span className="seek-bubble tabular" aria-hidden="true">
            {formatDuration(shown) || '0:00'}
          </span>
        )}
        <input
          type="range"
          className="range"
          aria-label="Seek"
          aria-valuetext={`${formatDuration(shown) || '0:00'} of ${formatDuration(max) || '0:00'}`}
          min={0}
          max={max || 1}
          step={1}
          disabled={!max}
          value={Math.min(shown, max || 1)}
          style={{['--fill' as string]: fill}}
          onChange={(event) => setDrag(Number(event.target.value))}
          onPointerUp={() => {
            if (drag !== undefined) seekTo(drag)
            setDrag(undefined)
          }}
          onPointerCancel={() => {
            if (drag !== undefined) seekTo(drag)
            setDrag(undefined)
          }}
          onKeyUp={(event) => {
            if (drag !== undefined && event.key.startsWith('Arrow')) {
              seekTo(drag)
              setDrag(undefined)
            }
          }}
          onBlur={() => {
            if (drag !== undefined) seekTo(drag)
            setDrag(undefined)
          }}
        />
      </div>
      <span className="seek-time tabular">{formatDuration(max) || '0:00'}</span>
    </div>
  )
}

export function VolumeControl() {
  const volume = usePlayer((s) => s.volume)
  const muted = usePlayer((s) => s.muted)
  const shown = muted ? 0 : volume
  const wrapperRef = useRef<HTMLDivElement>(null)
  const cleanupRef = useRef<(() => void) | null>(null)
  const volumeRef = useWheelVolume(() => (muted ? 0 : volume), setVolume)

  const spring = useSpring(0, motion.snappy, (v) => {
    const wrapper = wrapperRef.current
    if (!wrapper) return
    if (Math.abs(v) < 0.001) {
      wrapper.style.transform = ''
      return
    }
    const width = wrapper.clientWidth || 88
    const scale = (width + Math.abs(v)) / width
    wrapper.style.transformOrigin = v > 0 ? 'left center' : 'right center'
    wrapper.style.transform = `scaleX(${scale})`
  })

  useEffect(() => {
    return () => {
      cleanupRef.current?.()
    }
  }, [])

  const handlePointerDown = (event: React.PointerEvent<HTMLInputElement>) => {
    if (prefersReducedMotion()) return
    const input = event.currentTarget
    const rect = input.getBoundingClientRect()
    const onPointerMove = (e: PointerEvent) => {
      const d = e.clientX < rect.left ? e.clientX - rect.left : e.clientX > rect.right ? e.clientX - rect.right : 0
      const stretch = Math.sign(d) * 18 * (1 - Math.exp(-Math.abs(d) / 60))
      spring.set(stretch)
    }
    const onPointerUp = () => {
      spring.to(0)
      window.removeEventListener('pointermove', onPointerMove)
      window.removeEventListener('pointerup', onPointerUp)
      window.removeEventListener('pointercancel', onPointerUp)
      cleanupRef.current = null
    }
    cleanupRef.current = () => {
      window.removeEventListener('pointermove', onPointerMove)
      window.removeEventListener('pointerup', onPointerUp)
      window.removeEventListener('pointercancel', onPointerUp)
    }
    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', onPointerUp)
    window.addEventListener('pointercancel', onPointerUp)
  }

  return (
    <div className="volume" ref={volumeRef} title="Scroll to change the volume">
      <IconButton icon={shown === 0 ? 'volumeMute' : 'volume'} label={muted ? 'Unmute' : 'Mute'} aria-pressed={muted} onClick={toggleMute} />
      <div className="volume-track" ref={wrapperRef}>
        <input
          type="range"
          className="range range-volume"
          aria-label="Volume"
          aria-valuetext={`${Math.round(shown * 100)}%`}
          min={0}
          max={1}
          step={0.01}
          value={shown}
          style={{['--fill' as string]: `${shown * 100}%`}}
          onChange={(event) => setVolume(Number(event.target.value))}
          onPointerDown={handlePointerDown}
        />
      </div>
    </div>
  )
}

/** Shuffle and repeat sit at the sides of previous/next in the expanded player and, with `modes`, in the mini bar. */
export function Transport({large, modes}: {large?: boolean; modes?: boolean}) {
  const playing = usePlayer((s) => s.playing)
  const loading = usePlayer((s) => s.loading)
  const shuffle = usePlayer((s) => s.shuffle)
  const repeat = usePlayer((s) => s.repeat)
  const size = large ? 'lg' : 'md'
  const sides = large || modes
  return (
    <div className={`transport transport-${size}`} role="group" aria-label="Playback controls">
      {sides && <IconButton icon="shuffle" label={shuffle ? 'Shuffle on' : 'Shuffle off'} aria-pressed={shuffle} className={shuffle ? 'on' : ''} onClick={toggleShuffle} />}
      <IconButton icon="skipPrev" label="Previous song" onClick={previous} />
      <button type="button" className="play-btn" aria-label={playing ? 'Pause' : 'Play'} title={playing ? 'Pause' : 'Play'} aria-busy={loading && !playing} onClick={togglePlay}>
        <PlayPauseIcon playing={playing} size={large ? 26 : 20} />
      </button>
      <IconButton icon="skipNext" label="Next song" onClick={next} />
      {sides && (
        <IconButton
          icon={repeat === 'one' ? 'repeatOne' : 'repeat'}
          label={repeat === 'off' ? 'Repeat off' : repeat === 'all' ? 'Repeat all' : 'Repeat one'}
          aria-pressed={repeat !== 'off'}
          className={repeat !== 'off' ? 'on' : ''}
          onClick={cycleRepeat}
        />
      )}
    </div>
  )
}

export function useOutputDevices() {
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([])
  useEffect(() => {
    if (!canPickOutput()) return
    let alive = true
    const read = () =>
      navigator.mediaDevices
        .enumerateDevices()
        .then((list) => alive && setDevices(list.filter((device) => device.kind === 'audiooutput')))
        .catch(() => {})
    void read()
    navigator.mediaDevices.addEventListener?.('devicechange', read)
    return () => {
      alive = false
      navigator.mediaDevices.removeEventListener?.('devicechange', read)
    }
  }, [])
  return devices
}

/** The menu entries for speed, sleep timer and output device, and the equalizer's state: shared by the full player's options and the bar's More. */
function useOptionItems() {
  const speed = usePlayer((s) => s.speed)
  const sleep = usePlayer((s) => s.sleep)
  const sinkId = usePlayer((s) => s.sinkId)
  const devices = useOutputDevices()
  const eq = useEqualizer()
  const items: MenuItem[] = [
    {heading: 'Playback speed'},
    {group: 'Speed', choices: speeds.map((value) => ({label: `${value}×`, name: value === 1 ? 'Normal' : undefined, checked: speed === value, onSelect: () => setSpeed(value)}))},
    {heading: 'Sleep timer'},
    {
      group: 'Sleep timer',
      choices: [
        {label: 'Off', checked: !sleep, onSelect: () => setSleep(undefined)},
        ...[15, 30, 45, 60].map((minutes) => ({label: `${minutes}m`, name: `${minutes} minutes`, checked: sleep?.kind === 'minutes' && sleep.minutes === minutes, onSelect: () => setSleep({kind: 'minutes', minutes})})),
        {label: 'End of song', checked: sleep?.kind === 'end', onSelect: () => setSleep({kind: 'end'})},
      ],
    },
  ]
  if (devices.length > 1) {
    items.push('separator', {heading: 'Play on'})
    for (const device of devices)
      items.push({label: device.label || 'Audio output', checked: (device.deviceId === 'default' && !sinkId) || device.deviceId === sinkId, onSelect: () => void setOutputDevice(device.deviceId === 'default' ? '' : device.deviceId)})
  }
  // the equalizer and bass boost (as on the phone) have their own button, lit while either is on
  const shaping = eq.enabled || eq.bass > 0
  const eqLabel = shaping ? `Equalizer (${eq.enabled ? eq.preset ?? 'Custom' : 'off'}${eq.bass > 0 ? `, bass +${eq.bass} %` : ''})` : 'Equalizer'
  return {items, shaping, eqLabel}
}

/** Equalizer button and Speed / sleep timer / output menu: the full player's header. */
export function PlayerOptions() {
  const {items, shaping, eqLabel} = useOptionItems()
  const [equalizer, setEqualizer] = useState(false)
  return (
    <>
      <IconButton icon="equalizer" label={eqLabel} className={`eq-button${shaping ? ' on' : ''}`} aria-pressed={shaping} onClick={() => setEqualizer(true)} />
      <Menu label="Speed, sleep timer and output" icon="speed" items={items} width={340} />
      {equalizer && <EqualizerDialog onClose={() => setEqualizer(false)} />}
    </>
  )
}

/**
 * The bar's More menu: the song's own actions, then what the bar itself no longer carries (equalizer, playback speed, sleep timer,
 * output device, the file format) and the way to expand the player.
 */
function BarMore({item}: {item: LibraryItem}) {
  const {items, eqLabel} = useOptionItems()
  const [equalizer, setEqualizer] = useState(false)
  const expanded = usePlayer((s) => s.expanded)
  const format = [qualityChip(item.path), item.kind === 'video' && item.height ? `${item.height}p` : ''].filter(Boolean).join(' · ')
  const menu: MenuItem[] = [
    ...itemMenu([item]),
    'separator',
    {label: eqLabel, icon: 'equalizer', onSelect: () => setEqualizer(true)},
    ...items,
    'separator',
    ...(format ? [{label: format, detail: 'File format', disabled: true, onSelect: () => {}}] : []),
    expanded ? {label: 'Collapse player', icon: 'chevronDown', onSelect: closePlayer} : {label: 'Expand player', icon: 'chevronUp', onSelect: openPlayer},
  ]
  return (
    <>
      <Menu label={`More actions for ${item.title}`} items={menu} width={340} />
      {equalizer && <EqualizerDialog onClose={() => setEqualizer(false)} />}
    </>
  )
}

/**
 * The persistent bar on every tab: a floating island. Left the cover (it opens the full player), title, artist and heart; in the middle
 * the transport with the seek bar under it; on the right only Video (songs that have a picture), Lyrics, Queue, volume, More and Close.
 */
export const NowPlayingBar = memo(function NowPlayingBar() {
  const id = usePlayer((s) => s.currentId)
  const error = usePlayer((s) => s.error)
  const from = usePlayer((s) => s.from)
  const sleep = usePlayer((s) => s.sleep)
  const expanded = usePlayer((s) => s.expanded)
  const item = useLibItem(id)
  const panels = usePanels()
  if (!id || !item) return null
  // Anywhere on the bar that is not a control opens the player; the cover and title button stay the keyboard way in.
  const openFromBar = (event: MouseEvent<HTMLElement>) => {
    if (expanded) return
    if ((event.target as HTMLElement).closest('button, a, input, select, [role="slider"], [role="menu"], .seek, .volume, .now-error')) return
    openPlayer()
  }
  return (
    <section className={`now-bar${expanded ? ' is-expanded' : ''}`} aria-label="Now playing" onClick={openFromBar}>
      <div className="now-left">
        <button type="button" className="now-info" onClick={openPlayer} aria-label={`Open the player, ${item.title}`}>
          <span className="now-cover">
            <Art id={item.id} hasArt={item.hasArt} kind="audio" label={item.title} className="now-art" size={18} space={item.space} />
            <span className="now-chevron" aria-hidden="true">
              <Icon name="chevronUp" size={12} />
            </span>
          </span>
          <span className="now-text">
            <span className="now-title" title={item.title}>
              {item.title}
            </span>
            <span className="now-artist">{error && error.id === id ? 'Can’t play this file' : [item.artist || item.albumArtist || item.album || '', from ? `From ${from}` : ''].filter(Boolean).join(' · ')}</span>
          </span>
        </button>
        <IconButton
          icon={item.favorite ? 'heartFill' : 'heart'}
          className={`heart${item.favorite ? ' on' : ''}`}
          label={item.favorite ? 'Remove from favorites' : 'Add to favorites'}
          aria-pressed={!!item.favorite}
          onClick={() => void toggleFavorite(item.id)}
        />
        {sleep && (
          <span className="sleep-chip" title="Sleep timer is on">
            <Icon name="clock" size={13} /> {sleep.kind === 'end' ? 'End of song' : `${sleep.minutes} min`}
          </span>
        )}
      </div>
      <div className="now-center">
        <Transport modes />
        <SeekBar compact />
      </div>
      <div className="now-right">
        {itemHasPicture(item) && (
          <button type="button" className="video-pill" aria-label="Show video" onClick={showPicture}>
            <Icon name="video" size={16} /> <span className="video-pill-text">Video</span>
          </button>
        )}
        <IconButton icon="lyrics" label="Lyrics" aria-pressed={panels.lyrics} className={panels.lyrics ? 'on' : ''} onClick={toggleLyrics} />
        <IconButton icon="queue" label="Queue and Now playing" aria-pressed={panels.nowPlaying} className={panels.nowPlaying ? 'on' : ''} onClick={toggleNowPlaying} />
        <VolumeControl />
        <BarMore item={item} />
        <IconButton icon="close" label="Close player" onClick={stopPlayback} />
      </div>
      {error && error.id === id && (
        <div className="now-error" role="alert">
          <span>{error.message}</span>
          <button type="button" className="link-btn" onClick={openCurrentExternally}>
            Open in another app
          </button>
        </div>
      )}
    </section>
  )
})