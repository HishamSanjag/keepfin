import {
  MediaControlBar,
  MediaController,
  MediaFullscreenButton,
  MediaLoadingIndicator,
  MediaMuteButton,
  MediaPipButton,
  MediaPlayButton,
  MediaSeekBackwardButton,
  MediaSeekForwardButton,
  MediaTimeDisplay,
  MediaTimeRange,
  MediaVolumeRange,
} from 'media-chrome/react'
import {memo, useCallback, useEffect, useMemo, useRef, useState} from 'react'
import {request} from '../bridge'
import {getLibItem, getLibrary, notePlayback, useLibItem} from '../library/libStore'
import {itemUrl, musicVideoUrl, subtitleUrl, artworkSrc, artUrl} from '../media'
import {readPreference, savePreference} from '../prefs'
import {toast} from '../toasts'
import {Button, IconButton, InlineAlert, Spinner} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {Menu, type MenuItem} from '../ui/Menu'
import {closeVideo, openExternally, setVideoId, useOverlay} from './play'
import {bindSession, clearSession} from './mediaSession'
import {prepareItem} from './playerStore'
import {useWheelVolume} from './NowPlaying'
import {VideoHud, type HudHandle} from './VideoHud'
import {VideoShortcuts} from './VideoShortcuts'
import {FullscreenGlyph, PauseGlyph, PipGlyph, PlayGlyph, SeekGlyph, VolumeGlyph} from './PlayerIcons'
import {useFrameThumbs} from './useFrameThumbs'
import {adjacentId, chapterAt, folderVideoIds, sleepRemaining, type Chapter} from './videoLogic'
import {chapterTarget as coreChapterTarget, clock as coreClock, playbackList, resumeFrom, speeds as videoSpeeds} from '../../shared/core/playback'

type Aspect = 'fit' | 'fill' | 'zoom' | '16:9' | '4:3'
const aspects: {value: Aspect; label: string}[] = [
  {value: 'fit', label: 'Fit'},
  {value: 'fill', label: 'Fill'},
  {value: 'zoom', label: 'Zoom'},
  {value: '16:9', label: '16:9'},
  {value: '4:3', label: '4:3'},
]

type Adjust = {brightness: number; contrast: number; saturation: number}
const flat: Adjust = {brightness: 100, contrast: 100, saturation: 100}
const langKey = 'keepfin-sub-lang'
const autoplayKey = 'keepfin-video-autoplay'
const volumeKey = 'keepfin-video-volume'
const noChapters: Chapter[] = []
/** The speed chosen in this session carries to the next video (D6); it is gone when the app restarts. */
let sessionRate = 1
type Sleep = {kind: 'minutes'; minutes: number; endsAt: number} | {kind: 'end'}
// the time label every Keepfin player uses (shared core)
const clock = coreClock

function editable(target: EventTarget | null) {
  const el = target as HTMLElement | null
  return !!el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName))
}

export function VideoPlayer() {
  const {video} = useOverlay()
  if (!video) return null
  return <VideoSurface key={`${video.id}${video.musicVideo ? '#video' : ''}`} id={video.id} context={video.context} musicVideo={video.musicVideo} />
}

/** The hover preview in the seek bar's `preview` slot: frame, chapter title and time. It re-renders alone. */
const SeekPreview = memo(function SeekPreview({request, duration, chapters}: {request: (time: number, duration: number, deliver: (frame: HTMLCanvasElement | undefined) => void) => void; duration: number; chapters: Chapter[]}) {
  const host = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const [time, setTime] = useState<number | null>(null)
  const [framed, setFramed] = useState(false)
  // media-chrome puts `mediapreviewtime` on the seek bar while the pointer is over it
  useEffect(() => {
    const range = host.current?.parentElement
    if (!range) return
    const read = () => {
      const raw = range.getAttribute('mediapreviewtime')
      setTime(raw === null || !Number.isFinite(Number(raw)) ? null : Number(raw))
    }
    const watch = new MutationObserver(read)
    watch.observe(range, {attributes: true, attributeFilter: ['mediapreviewtime']})
    read()
    return () => watch.disconnect()
  }, [])
  const second = time === null ? null : Math.floor(time)
  useEffect(() => {
    if (second === null) return
    let live = true
    request(second, duration, (frame) => {
      const target = canvas.current
      if (!live || !target) return
      if (frame) {
        target.width = frame.width
        target.height = frame.height
        target.getContext('2d')?.drawImage(frame, 0, 0)
      }
      setFramed(!!frame)
    })
    return () => {
      live = false
    }
  }, [second, duration])
  const title = time === null ? undefined : chapterAt(chapters, time)?.title
  return (
    <div slot="preview" ref={host} className="video-preview">
      <canvas ref={canvas} className="video-preview-frame" data-framed={framed || undefined} aria-hidden="true" />
      {title && <span className="video-preview-chapter">{title}</span>}
      <span className="video-preview-time">{clock(time ?? 0)}</span>
    </div>
  )
})

/** [musicVideo]: `id` is a song and the picture is its music video file, played alone: from its start, with no play counted for the song. */
function VideoSurface({id, context, musicVideo = false}: {id: string; context: string[]; musicVideo?: boolean}) {
  const item = useLibItem(id)
  const ref = useRef<HTMLVideoElement>(null)
  const hud = useRef<HudHandle>(null)
  const controller = useRef<HTMLElement>(null)
  const volumeRef = useWheelVolume(
    () => (ref.current && !ref.current.muted ? ref.current.volume : 0),
    (volume) => {
      const video = ref.current
      if (!video) return
      video.volume = volume
      video.muted = volume === 0
    },
  )
  const root = useRef<HTMLDivElement>(null)
  const [source, setSource] = useState<{url: string; playable: boolean; message?: string}>()
  const [playing, setPlaying] = useState(false)
  const [time, setTime] = useState({position: 0, duration: (musicVideo ? item?.videoDuration : undefined) || item?.duration || 0})
  const [aspect, setAspect] = useState<Aspect>('fit')
  const [adjust, setAdjust] = useState<Adjust>(flat)
  const [rate, setRate] = useState(sessionRate)
  const [loop, setLoop] = useState<{a?: number; b?: number}>({})
  const [delay, setDelay] = useState(0)
  const [sub, setSub] = useState(-1)
  const [audioTracks, setAudioTracks] = useState<{index: number; label: string; enabled: boolean}[]>([])
  const [autoplay, setAutoplay] = useState(() => readPreference<boolean>(autoplayKey, true, (v) => typeof v === 'boolean'))
  // the end card: `left` counts down from 8; null (Autoplay off) waits for the viewer
  const [upNext, setUpNext] = useState<{left: number | null}>()
  const [resumed, setResumed] = useState<number>()
  const [shortcuts, setShortcuts] = useState(false)
  const [sleep, setSleep] = useState<Sleep>()
  const [sleepNow, setSleepNow] = useState(() => Date.now())
  const [mediaError, setMediaError] = useState('')
  const [panel, setPanel] = useState<'adjust' | undefined>()
  const [openMenus, setOpenMenus] = useState(0)
  const [focusInBar, setFocusInBar] = useState(false)
  const lastReport = useRef(0)
  const unmounting = useRef(false)
  // what the saved position was when the video opened (the library entry changes as we play)
  const startedWith = useRef({position: musicVideo ? undefined : item?.position, watched: musicVideo ? undefined : item?.watched})
  const resumeDone = useRef(false)
  const lastVolume = useRef<{volume: number; muted: boolean} | undefined>(undefined)
  const holdTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const holdFrom = useRef<number | undefined>(undefined)
  const swallowClick = useRef(false)

  // Next/previous: the list the video was opened from; opened alone, its folder in natural title order (D5).
  const folderIds = useMemo(() => (item && !musicVideo && context.length <= 1 ? folderVideoIds(getLibrary().bySpace.get(item.space) || [], item) : undefined), [id, item?.space, item?.folder, context.length])
  const list = useMemo(() => playbackList(context, folderIds), [context, folderIds])
  const {nextId, prevId} = useMemo(() => {
    const exists = (other: string) => !!getLibItem(other)
    return {nextId: adjacentId(list, id, 1, exists), prevId: adjacentId(list, id, -1, exists)}
  }, [list, id])
  const at = list.indexOf(id)
  const subtitles = (!musicVideo && item?.subtitles) || []
  const chapters = (!musicVideo && item?.chapters) || noChapters

  // Prepare: the main process decides whether the file plays as is, needs a lossless remux, or cannot play.
  useEffect(() => {
    let alive = true
    if (!item) return
    if (musicVideo) return setSource({url: musicVideoUrl(item.id), playable: true})
    void prepareItem(item).then((result) => {
      if (!alive) return
      setSource({url: result.url || itemUrl(item.id), playable: result.playable, message: result.message})
    })
    return () => {
      alive = false
    }
  }, [id])

  const report = useCallback(
    (finished: boolean) => {
      const video = ref.current
      if (!video || !item || musicVideo) return
      lastReport.current = Date.now()
      const position = finished ? 0 : video.currentTime
      notePlayback(item.id, position, finished)
      void request({op: 'libraryPlayback', id: item.id, ids: [item.id], position, finished} as never).catch(() => {})
    },
    [item?.id],
  )

  const close = useCallback(() => {
    if (document.fullscreenElement) void document.exitFullscreen().catch(() => {})
    report(false)
    closeVideo()
  }, [report])

  // Chapters as a real chapters track: media-chrome then draws YouTube's segmented bar (gaps, the pointed part grows).
  // Made in code, not from a URL, so the page's media policy stays as strict as it is.
  useEffect(() => {
    const video = ref.current
    if (!video || !source?.playable || !chapters.length || Array.from(video.textTracks).some((track) => track.kind === 'chapters')) return
    const track = video.addTextTrack('chapters', 'Chapters')
    track.mode = 'hidden'
    const end = item?.duration || chapters[chapters.length - 1].start + 1
    chapters.forEach((chapter, index) => {
      const until = chapters[index + 1]?.start ?? end
      if (until > chapter.start) track.addCue(new VTTCue(chapter.start, until, chapter.title || clock(chapter.start)))
    })
  }, [source, chapters])

  // Playback starts as soon as the video is ready; a saved spot is picked up when the length is known.
  useEffect(() => {
    const video = ref.current
    if (!video || !source?.playable) return
    void video.play().catch(() => {})
  }, [source])

  // "Resumed from 12:34 · Start over" stays for 7 seconds (D2)
  useEffect(() => {
    if (resumed === undefined) return
    const timer = setTimeout(() => setResumed(undefined), 7000)
    return () => clearTimeout(timer)
  }, [resumed])
  const startOver = () => {
    const video = ref.current
    if (video) video.currentTime = 0
    setResumed(undefined)
    root.current?.focus()
  }

  // Subtitle tracks: exactly one is showing; delay shifts the cue times.
  const originals = useRef(new WeakMap<VTTCue, [number, number]>())
  const applySub = useCallback(
    (index: number, offset: number) => {
      const video = ref.current
      if (!video) return
      for (let i = 0; i < video.textTracks.length; i++) {
        const track = video.textTracks[i]
        if (track.kind === 'metadata' || track.kind === 'chapters') continue
        const want = i === index
        track.mode = want ? 'showing' : 'disabled'
        if (want && track.cues)
          for (const cue of Array.from(track.cues) as VTTCue[]) {
            let base = originals.current.get(cue)
            if (!base) originals.current.set(cue, (base = [cue.startTime, cue.endTime]))
            cue.startTime = Math.max(0, base[0] + offset)
            cue.endTime = Math.max(0.01, base[1] + offset)
          }
      }
    },
    [],
  )
  useEffect(() => applySub(sub, delay), [sub, delay, applySub])
  // Preferred language: pick the remembered track once the tracks exist.
  const preferred = useRef(false)
  useEffect(() => {
    if (preferred.current || !subtitles.length) return
    preferred.current = true
    const want = readPreference<string>(langKey, '', (v) => typeof v === 'string')
    const found = want ? subtitles.findIndex((label) => label === want) : -1
    if (found >= 0) setSub(found)
  }, [subtitles.length])
  const pickSub = (index: number) => {
    setSub(index)
    savePreference(langKey, index >= 0 ? subtitles[index] : '')
  }
  // Subtitle size, 60–200 % in steps of 10 (as on the phone), remembered for every video
  const [cueScale, setCueScale] = useState(() => readPreference<number>('keepfin-sub-scale', 1, (v) => typeof v === 'number' && v >= 0.6 && v <= 2))
  const scaleCues = (next: number) => {
    const value = Math.round(Math.min(2, Math.max(0.6, next)) * 10) / 10
    setCueScale(value)
    savePreference('keepfin-sub-scale', value)
    hud.current?.pill(`Captions ${Math.round(value * 100)} %`)
  }

  const refreshAudioTracks = () => {
    const list = (ref.current as unknown as {audioTracks?: {length: number; [i: number]: {label: string; language: string; enabled: boolean}}})?.audioTracks
    if (!list || list.length < 2) return setAudioTracks([])
    setAudioTracks(Array.from({length: list.length}, (_, index) => ({index, label: list[index].label || list[index].language || `Track ${index + 1}`, enabled: list[index].enabled})))
  }
  const pickAudio = (index: number) => {
    const list = (ref.current as unknown as {audioTracks?: {length: number; [i: number]: {enabled: boolean}}})?.audioTracks
    if (!list) return
    for (let i = 0; i < list.length; i++) list[i].enabled = i === index
    refreshAudioTracks()
  }

  const step = (delta: number) => {
    const video = ref.current
    if (video) video.currentTime = Math.max(0, Math.min(video.duration || Infinity, video.currentTime + delta))
  }
  // a skip the viewer asked for: it also says so on screen, adding up when repeated
  const skip = (delta: number) => {
    step(delta)
    hud.current?.skip(delta)
  }
  const changeRate = (value: number) => {
    const video = ref.current
    const next = Math.max(0.25, Math.min(2, value))
    sessionRate = next
    setRate(next)
    if (video && holdFrom.current === undefined) {
      video.playbackRate = next
      video.preservesPitch = true
    }
    hud.current?.pill(`Speed ${next === 1 ? 'Normal' : `${next}×`}`)
  }
  const chooseAspect = (value: Aspect) => {
    setAspect(value)
    hud.current?.pill(`Picture: ${aspects.find((entry) => entry.value === value)?.label}`)
  }
  const toggleCaptions = () => {
    if (!subtitles.length) return toast({tone: 'info', message: 'This video has no subtitles.'})
    hud.current?.pill(sub >= 0 ? 'Captions off' : 'Captions on')
    pickSub(sub >= 0 ? -1 : 0)
  }
  const toggleFullscreen = () => {
    if (document.fullscreenElement) void document.exitFullscreen()
    else void root.current?.requestFullscreen?.().catch(() => {})
  }
  const toggle = () => {
    const video = ref.current
    if (!video) return
    hud.current?.flash(video.paused ? 'play' : 'pause')
    if (video.paused) void video.play().catch(() => {})
    else video.pause()
  }
  const goNext = () => nextId && (report(false), setVideoId(nextId))
  const goPrev = () => prevId && (report(false), setVideoId(prevId))
  const toggleAutoplay = () => {
    const next = !autoplay
    setAutoplay(next)
    savePreference(autoplayKey, next)
    hud.current?.pill(`Autoplay ${next ? 'on' : 'off'}`)
    if (!next) setUpNext((card) => card && {left: null})
  }
  const closeShortcuts = () => {
    setShortcuts(false)
    root.current?.focus()
  }

  async function screenshot() {
    const video = ref.current
    if (!video || !video.videoWidth) return
    const canvas = document.createElement('canvas')
    canvas.width = video.videoWidth
    canvas.height = video.videoHeight
    const g = canvas.getContext('2d')
    if (!g) return
    g.filter = `brightness(${adjust.brightness}%) contrast(${adjust.contrast}%) saturate(${adjust.saturation}%)`
    g.drawImage(video, 0, 0)
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
    if (!blob) return toast({tone: 'error', message: 'Couldn’t capture this frame.'})
    const name = `${(item?.title || 'frame').replace(/[\\/:*?"<>|]+/g, ' ').trim()} ${clock(video.currentTime).replace(/:/g, '-')}.png`
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = name
    link.click()
    setTimeout(() => URL.revokeObjectURL(link.href), 4000)
    // the clipboard is only written when asked (as on the phone)
    const copy = async () => {
      try {
        await navigator.clipboard.write([new ClipboardItem({'image/png': blob})])
        toast({tone: 'success', message: 'Frame copied'})
      } catch {
        toast({tone: 'error', message: 'Couldn’t copy the frame. It is still saved in your downloads folder.'})
      }
    }
    toast({tone: 'success', message: 'Frame saved to your downloads folder', action: {label: 'Copy image', run: () => void copy()}})
  }

  // Keyboard shortcuts.
  const keys = useRef<(event: KeyboardEvent) => void>(() => {})
  keys.current = (event) => {
    if (editable(event.target) || event.metaKey || event.altKey) return
    const key = event.key
    // Ctrl+←/→ are the only Ctrl shortcuts: previous and next chapter
    const byChapter = event.ctrlKey && (key === 'ArrowLeft' || key === 'ArrowRight')
    if (event.ctrlKey && !byChapter) return
    const handled = () => {
      event.preventDefault()
      event.stopPropagation()
    }
    // the shortcuts sheet takes Esc (and ?) so the player itself stays open
    if (shortcuts) {
      if (key === 'Escape' || key === '?') (handled(), closeShortcuts())
      return
    }
    if (document.querySelector('.menu, dialog[open]')) return
    const video = ref.current
    if (!video) return
    if (key === 'Escape') {
      if (document.fullscreenElement) return
      handled()
      close()
    } else if (byChapter) {
      handled()
      const target = coreChapterTarget(chapters.map((chapter) => chapter.start), video.currentTime, key === 'ArrowRight' ? 1 : -1)
      if (target !== undefined) {
        video.currentTime = target
        hud.current?.pill(chapterAt(chapters, target)?.title || clock(target))
      }
    } else if (key === ' ' || key.toLowerCase() === 'k') {
      if ((event.target as HTMLElement).closest?.('button, media-chrome-button') && key === ' ' && (event.target as HTMLElement) !== root.current) {
        // A focused control handles its own Space.
        return
      }
      handled()
      toggle()
    } else if (key.toLowerCase() === 'j') (handled(), skip(-10))
    else if (key.toLowerCase() === 'l') (handled(), skip(10))
    else if (key === 'ArrowLeft') (handled(), skip(-5))
    else if (key === 'ArrowRight') (handled(), skip(5))
    else if (key === 'ArrowUp') (handled(), (video.volume = Math.min(1, video.volume + 0.05), (video.muted = false)))
    else if (key === 'ArrowDown') (handled(), (video.volume = Math.max(0, video.volume - 0.05)))
    else if (key.toLowerCase() === 'f') (handled(), toggleFullscreen())
    else if (key.toLowerCase() === 'm') (handled(), (video.muted = !video.muted))
    else if (key.toLowerCase() === 'c') (handled(), toggleCaptions())
    else if (key === ',' || key === '.') {
      if (video.paused) {
        handled()
        step(key === ',' ? -1 / 30 : 1 / 30)
        hud.current?.pill(clock(video.currentTime))
      }
    } else if (key === '<') (handled(), changeRate(rate - 0.25))
    else if (key === '>') (handled(), changeRate(rate + 0.25))
    else if (key.toLowerCase() === 'n') (handled(), goNext())
    else if (key.toLowerCase() === 'p') (handled(), goPrev())
    else if (/^[0-9]$/.test(key) && Number.isFinite(video.duration)) {
      handled()
      const target = (video.duration * Number(key)) / 10
      video.currentTime = target
      hud.current?.pill(`${Number(key) * 10} % · ${clock(target)}`)
    } else if (key === 'Home' || key === 'End') {
      handled()
      const target = key === 'Home' ? 0 : Number.isFinite(video.duration) ? video.duration : 0
      video.currentTime = target
      hud.current?.pill(key === 'Home' ? 'Start' : 'End')
    } else if (key === '+' || key === '=') {
      handled()
      if (subtitles.length) scaleCues(cueScale + 0.1)
      else hud.current?.pill('No subtitles')
    } else if (key === '-') {
      handled()
      if (subtitles.length) scaleCues(cueScale - 0.1)
      else hud.current?.pill('No subtitles')
    } else if (key === '?') (handled(), setShortcuts(true))
  }
  useEffect(() => {
    const handler = (event: KeyboardEvent) => keys.current(event)
    window.addEventListener('keydown', handler, true)
    return () => window.removeEventListener('keydown', handler, true)
  }, [])

  // Focus the player so keys work immediately and focus returns to the library on close.
  useEffect(() => {
    const returnTo = document.activeElement as HTMLElement | null
    root.current?.focus()
    return () => {
      unmounting.current = true
      clearTimeout(holdTimer.current)
      returnTo?.focus?.()
      clearSession('video')
    }
  }, [])

  // End card countdown (8 s). Autoplay off: no countdown, the card just offers "Play next".
  useEffect(() => {
    if (!upNext || upNext.left === null) return
    if (upNext.left <= 0) {
      goNext()
      return
    }
    const timer = setTimeout(() => setUpNext({left: upNext.left! - 1}), 1000)
    return () => clearTimeout(timer)
  }, [upNext?.left])

  // Sleep timer (D9): minutes pause the video; "End of video" pauses at the end and stops Autoplay.
  useEffect(() => {
    if (sleep?.kind !== 'minutes') return
    setSleepNow(Date.now())
    const fire = setTimeout(() => {
      ref.current?.pause()
      setSleep(undefined)
      toast({tone: 'info', message: 'Sleep timer: playback paused.'})
    }, Math.max(0, sleep.endsAt - Date.now()))
    const tick = setInterval(() => setSleepNow(Date.now()), 15_000)
    return () => {
      clearTimeout(fire)
      clearInterval(tick)
    }
  }, [sleep])

  // OS media session for the video.
  useEffect(() => {
    if (!item) return
    bindSession('video', {
      title: item.title,
      artist: item.artist || '',
      album: '',
      art: item.hasArt ? artworkSrc(item.id) : undefined,
      playing,
      position: time.position,
      duration: time.duration,
      rate,
      handlers: {
        play: () => void ref.current?.play(),
        pause: () => ref.current?.pause(),
        seekto: (details) => details.seekTime !== undefined && ref.current && (ref.current.currentTime = details.seekTime),
        seekbackward: (details) => step(-(details.seekOffset || 10)),
        seekforward: (details) => step(details.seekOffset || 10),
        previoustrack: prevId ? goPrev : undefined,
        nexttrack: nextId ? goNext : undefined,
      },
    })
  }, [item?.id, playing, Math.round(time.position / 5), rate, !!nextId, !!prevId])

  // Controls stay while a menu, panel, sheet or card is open and while keyboard focus is in the bar.
  const keepControls = openMenus > 0 || panel === 'adjust' || shortcuts || !!upNext || resumed !== undefined || focusInBar
  // media-chrome starts out "inactive" (controls hidden) and only wakes on input. Wake it when the video appears (the controls
  // show for a moment, as on YouTube) and whenever keeping them changes (kept: shown; released: its own countdown starts again).
  useEffect(() => {
    controller.current?.dispatchEvent(new PointerEvent('pointermove', {bubbles: true, pointerType: 'mouse'}))
  }, [keepControls, source?.playable])
  const menuOpen = useCallback((open: boolean) => setOpenMenus((count) => Math.max(0, count + (open ? 1 : -1))), [])
  const requestFrame = useFrameThumbs(source?.url)
  const frameRef = useRef(requestFrame)
  frameRef.current = requestFrame
  const stableFrame = useCallback((moment: number, duration: number, deliver: (frame: HTMLCanvasElement | undefined) => void) => frameRef.current(moment, duration, deliver), [])

  const filter = `brightness(${adjust.brightness}%) contrast(${adjust.contrast}%) saturate(${adjust.saturation}%)`
  const adjusted = adjust.brightness !== 100 || adjust.contrast !== 100 || adjust.saturation !== 100
  const chapterNow = chapterAt(chapters, time.position)

  const endHold = () => {
    clearTimeout(holdTimer.current)
    const video = ref.current
    if (holdFrom.current === undefined) return
    if (video) video.playbackRate = holdFrom.current
    holdFrom.current = undefined
    hud.current?.release()
    // the click that follows letting go must not toggle play
    swallowClick.current = true
    setTimeout(() => (swallowClick.current = false), 80)
  }

  const more: MenuItem[] = useMemo(() => {
    const items: MenuItem[] = []
    // speed and picture are rows of chips side by side (YouTube), not one long list
    items.push({heading: 'Speed'}, {group: 'Speed', columns: 4, choices: videoSpeeds.map((value) => ({label: value === 1 ? 'Normal' : `${value}×`, checked: rate === value, onSelect: () => changeRate(value)}))})
    items.push({heading: 'Picture'}, {group: 'Picture', choices: aspects.map((entry) => ({label: entry.label, checked: aspect === entry.value, onSelect: () => chooseAspect(entry.value)}))})
    items.push('separator', {label: 'Picture adjustments…', icon: 'sparkle', onSelect: () => setPanel(panel ? undefined : 'adjust')})
    items.push('separator', {heading: 'Loop section (A–B)'})
    items.push({label: loop.a === undefined ? 'Set start (A) here' : `Start (A) at ${clock(loop.a)}. Set end (B) here`, onSelect: () => {
      const now = ref.current?.currentTime ?? 0
      if (loop.a === undefined || loop.b !== undefined) setLoop({a: now})
      else if (now > loop.a + 0.5) setLoop({a: loop.a, b: now})
      else toast({tone: 'info', message: 'Pick an end point after the start.'})
    }})
    if (loop.a !== undefined) items.push({label: 'Clear loop', icon: 'close', onSelect: () => setLoop({})})
    items.push(
      'separator',
      {heading: 'Sleep timer'},
      {
        group: 'Sleep timer',
        choices: [
          {label: 'Off', checked: !sleep, onSelect: () => setSleep(undefined)},
          ...[15, 30, 45, 60].map((minutes) => ({label: `${minutes}m`, name: `${minutes} minutes`, checked: sleep?.kind === 'minutes' && sleep.minutes === minutes, onSelect: () => setSleep({kind: 'minutes', minutes, endsAt: Date.now() + minutes * 60_000})})),
          {label: 'End of video', checked: sleep?.kind === 'end', onSelect: () => setSleep({kind: 'end'})},
        ],
      },
    )
    items.push('separator', {label: 'Keyboard shortcuts', icon: 'help', onSelect: () => setShortcuts(true)}, {label: 'Take screenshot', icon: 'camera', onSelect: () => void screenshot()}, {label: 'Open in another app', icon: 'external', onSelect: () => item && openExternally([item.id])})
    return items
  }, [rate, aspect, loop, panel, sleep, item?.id])

  const chapterItems: MenuItem[] = chapters.map((chapter) => ({label: chapter.title || clock(chapter.start), detail: clock(chapter.start), checked: chapterNow === chapter, onSelect: () => ref.current && (ref.current.currentTime = chapter.start)}))

  const captions: MenuItem[] = [
    {label: 'Off', checked: sub < 0, onSelect: () => pickSub(-1)},
    ...subtitles.map((label, index) => ({label, checked: sub === index, onSelect: () => pickSub(index)})),
  ]
  if (subtitles.length) captions.push('separator', {heading: 'Subtitle delay'}, {label: `Later (+50 ms)`, icon: 'plus', onSelect: () => setDelay((value) => value + 0.05)}, {label: `Earlier (−50 ms)`, icon: 'minus', onSelect: () => setDelay((value) => value - 0.05)}, {label: delay ? `Reset delay (${delay > 0 ? '+' : ''}${Math.round(delay * 1000)} ms)` : 'No delay', disabled: !delay, onSelect: () => setDelay(0)})
  if (subtitles.length)
    captions.push(
      'separator',
      {heading: `Subtitle size · ${Math.round(cueScale * 100)} %`},
      {label: 'Larger', icon: 'plus', disabled: cueScale >= 2, onSelect: () => scaleCues(cueScale + 0.1)},
      {label: 'Smaller', icon: 'minus', disabled: cueScale <= 0.6, onSelect: () => scaleCues(cueScale - 0.1)},
      {label: cueScale === 1 ? 'Normal size' : 'Normal size (100 %)', disabled: cueScale === 1, onSelect: () => scaleCues(1)},
    )

  if (!item)
    return (
      <div className="video-view" ref={root} role="dialog" aria-label="Video player" tabIndex={-1}>
        <div className="video-fallback">
          <InlineAlert tone="warning" title="This video is no longer in your library" actions={<Button size="sm" onClick={closeVideo}>Close</Button>}>
            It was moved or deleted.
          </InlineAlert>
        </div>
      </div>
    )

  // The top bar. While the video plays it is part of media-chrome's controller (its top-chrome slot), like everything else the
  // player draws over the video: one area decides "the viewer is here" and one rule shows and hides it all (never while paused).
  const topBar = (slot?: string) => (
    <header className="video-top" slot={slot}>
      <IconButton icon="arrowLeft" label="Close video (Esc)" onClick={close} />
      <div className="video-title">
        <strong title={item.title}>{item.title}</strong>
        <span>{[chapterNow?.title, list.length > 1 ? `${at + 1} of ${list.length}` : ''].filter(Boolean).join(' · ')}</span>
      </div>
      {adjusted && <span className="video-flag" title="Picture adjustments are on">Adjusted</span>}
    </header>
  )
  const ready = !!source?.playable && !mediaError

  return (
    <div className="video-view" ref={root} role="dialog" aria-label={`Playing ${item.title}`} tabIndex={-1}>
      {!ready && topBar()}
      {!source ? (
        <div className="video-fallback" aria-busy="true"><Spinner size={22} label="Preparing video" /><span>Preparing video…</span></div>
      ) : !source.playable || mediaError ? (
        <div className="video-fallback" role="alert">
          <Icon name="warning" size={28} />
          <strong>Keepfin can’t play this video here</strong>
          <p>{source.message || mediaError || 'Its video format isn’t supported by the built-in player.'}</p>
          <div className="empty-actions">
            <Button variant="primary" icon="external" onClick={() => openExternally([item.id], musicVideo)}>Open in another app</Button>
            <Button variant="ghost" onClick={close}>Close</Button>
          </div>
        </div>
      ) : (
        <MediaController ref={controller as never} className="video-controller" noHotkeys gesturesDisabled autohide={keepControls ? '-1' : '3'} data-aspect={aspect}>
            {topBar('top-chrome')}
            {/* the size chosen in Subtitles › Subtitle size */}
            {cueScale !== 1 && <style>{`.video-el::cue { font-size: ${Math.round(cueScale * 100)}%; }`}</style>}
            <video
              ref={ref}
              slot="media"
              className={`video-el aspect-${aspect.replace(':', 'x')}`}
              src={source.url}
              poster={item.hasArt ? artUrl(item.id) : undefined}
              playsInline
              preload="auto"
              crossOrigin="anonymous"
              style={{filter: adjusted ? filter : undefined}}
              onClick={() => {
                if (swallowClick.current) swallowClick.current = false
                else toggle()
              }}
              onDoubleClick={toggleFullscreen}
              // press and hold the video for 2× while held (D4); the rate we return to is the exact earlier one
              onPointerDown={(event) => {
                if (event.button !== 0) return
                clearTimeout(holdTimer.current)
                holdTimer.current = setTimeout(() => {
                  const video = ref.current
                  if (!video || video.paused || holdFrom.current !== undefined) return
                  holdFrom.current = video.playbackRate
                  video.playbackRate = 2
                  hud.current?.hold('2×')
                }, 400)
              }}
              onPointerUp={endHold}
              onPointerCancel={endHold}
              onPointerLeave={endHold}
              onPlay={() => setPlaying(true)}
              onPause={() => {
                setPlaying(false)
                if (!unmounting.current) report(false)
              }}
              onEnded={() => {
                report(true)
                if (sleep?.kind === 'end') {
                  setSleep(undefined)
                  toast({tone: 'info', message: 'Sleep timer: playback paused.'})
                } else if (nextId) setUpNext({left: autoplay ? 8 : null})
              }}
              onVolumeChange={(event) => {
                const video = event.currentTarget
                const now = {volume: video.volume, muted: video.muted}
                savePreference(volumeKey, now)
                const before = lastVolume.current
                lastVolume.current = now
                if (!before) return
                if (now.muted) {
                  if (!before.muted) hud.current?.pill('Muted')
                } else if (before.muted || before.volume !== now.volume) hud.current?.pill(`Volume ${Math.round(now.volume * 100)} %`)
              }}
              onError={() => setMediaError('The file couldn’t be decoded. Its video format isn’t supported by the built-in player.')}
              onLoadedMetadata={(event) => {
                const video = event.currentTarget
                setTime({position: video.currentTime, duration: video.duration})
                video.playbackRate = rate
                video.preservesPitch = true
                // volume and mute are remembered across videos and sessions (D6)
                const saved = readPreference(volumeKey, {volume: 1, muted: false}, (v) => !!v && typeof (v as {volume: unknown}).volume === 'number' && typeof (v as {muted: unknown}).muted === 'boolean')
                lastVolume.current = saved
                video.volume = Math.min(1, Math.max(0, saved.volume))
                video.muted = saved.muted
                refreshAudioTracks()
                applySub(sub, delay)
                // pick up where we stopped: no question asked (D2)
                if (!resumeDone.current) {
                  resumeDone.current = true
                  const from = resumeFrom(startedWith.current.position, Number.isFinite(video.duration) ? video.duration : item.duration, startedWith.current.watched)
                  if (from !== undefined && (!Number.isFinite(video.duration) || from < video.duration - 1)) {
                    video.currentTime = from
                    setResumed(from)
                  }
                }
              }}
              onTimeUpdate={(event) => {
                const video = event.currentTarget
                setTime({position: video.currentTime, duration: Number.isFinite(video.duration) ? video.duration : time.duration})
                if (loop.a !== undefined && loop.b !== undefined && video.currentTime >= loop.b) video.currentTime = loop.a
                if (!video.paused && Date.now() - lastReport.current > 10_000) report(false)
              }}
            >
              {subtitles.map((label, index) => (
                <track key={index} kind="subtitles" label={label} src={subtitleUrl(item.id, index)} />
              ))}
            </video>
          <MediaLoadingIndicator slot="centered-chrome" noAutohide />
          <div
            className="video-bar"
            onFocus={(event) => (event.target as HTMLElement).matches?.(':focus-visible') && setFocusInBar(true)}
            onBlur={(event) => !event.currentTarget.contains(event.relatedTarget as Node | null) && setFocusInBar(false)}
          >
            <div className="range-wrap">
              <MediaTimeRange className="video-range">
                <SeekPreview request={stableFrame} duration={time.duration} chapters={chapters} />
              </MediaTimeRange>
              {time.duration > 0 && loop.a !== undefined && (
                <i className="loop-span" aria-hidden="true" style={{left: `${(loop.a / time.duration) * 100}%`, width: loop.b !== undefined ? `${((loop.b - loop.a) / time.duration) * 100}%` : '2px'}} />
              )}
            </div>
            <MediaControlBar className="video-controls">
              <MediaPlayButton>
                <PlayGlyph slot="play" />
                <PauseGlyph slot="pause" />
              </MediaPlayButton>
              <MediaSeekBackwardButton seekOffset={10} onClick={() => hud.current?.skip(-10)}>
                <SeekGlyph slot="icon" dir={-1} />
              </MediaSeekBackwardButton>
              <MediaSeekForwardButton seekOffset={10} onClick={() => hud.current?.skip(10)}>
                <SeekGlyph slot="icon" dir={1} />
              </MediaSeekForwardButton>
              <span className="vc-volume" ref={volumeRef}>
                <MediaMuteButton>
                  <VolumeGlyph slot="off" level="off" />
                  <VolumeGlyph slot="low" level="low" />
                  <VolumeGlyph slot="medium" level="low" />
                  <VolumeGlyph slot="high" level="high" />
                </MediaMuteButton>
                <MediaVolumeRange />
              </span>
              <MediaTimeDisplay showDuration />
              {chapters.length > 0 && (
                <Menu label={`Chapters: ${chapterNow?.title || 'choose'}`} items={chapterItems} align="start" width={288} triggerClassName="vc-btn vc-chapter" onOpenChange={menuOpen} trigger={<span className="vc-chapter-text">{chapterNow?.title || 'Chapters'} ›</span>} />
              )}
              <span className="bar-spacer" />
              {list.length > 1 && (
                <button type="button" role="switch" aria-checked={autoplay} aria-label="Autoplay" title={`Play next automatically: ${autoplay ? 'on' : 'off'}`} className="vc-btn vc-switch" onClick={toggleAutoplay}>
                  <span className="vc-switch-track" aria-hidden="true"><i /></span>
                </button>
              )}
              {prevId && <button type="button" className="vc-btn" aria-label="Previous video (P)" title="Previous video (P)" onClick={goPrev}><Icon name="skipPrev" size={18} /></button>}
              {nextId && <button type="button" className="vc-btn" aria-label="Next video (N)" title="Next video (N)" onClick={goNext}><Icon name="skipNext" size={18} /></button>}
              <Menu label="Subtitles" icon="subtitles" items={captions} align="start" triggerClassName="vc-btn" onOpenChange={menuOpen} trigger={<Icon name="subtitles" size={18} />} />
              {audioTracks.length > 1 && (
                <Menu label="Audio track" icon="music" items={audioTracks.map((track) => ({label: track.label, checked: track.enabled, onSelect: () => pickAudio(track.index)}))} triggerClassName="vc-btn" onOpenChange={menuOpen} trigger={<Icon name="music" size={18} />} />
              )}
              <Menu label="More: speed, picture, loop, sleep timer, screenshot" items={more} triggerClassName="vc-btn" onOpenChange={menuOpen} trigger={<Icon name="more" size={18} />} width={300} />
              <MediaPipButton>
                <PipGlyph slot="enter" />
                <PipGlyph slot="exit" />
              </MediaPipButton>
              <MediaFullscreenButton>
                <FullscreenGlyph slot="enter" />
                <FullscreenGlyph slot="exit" exit />
              </MediaFullscreenButton>
            </MediaControlBar>
          </div>
          {/* Everything else drawn over the video lives in the controller too (its dialog layer), so pointing at it never
              counts as leaving the player. noautohide: each piece shows and hides on its own terms. */}
          <div slot="dialog" className="video-overlays" {...{noautohide: ''}}>
            <VideoHud ref={hud} />
            {panel === 'adjust' && (
              <div className="adjust-panel" role="group" aria-label="Picture adjustments">
                {(['brightness', 'contrast', 'saturation'] as const).map((key) => (
                  <label key={key}>
                    <span>{key[0].toUpperCase() + key.slice(1)}</span>
                    <input type="range" className="range" min={50} max={150} value={adjust[key]} style={{['--fill' as string]: `${adjust[key] - 50}%`}} aria-valuetext={`${adjust[key]}%`} onChange={(event) => setAdjust({...adjust, [key]: Number(event.target.value)})} />
                    <span className="tabular">{adjust[key]}%</span>
                  </label>
                ))}
                <div className="adjust-actions">
                  <Button size="sm" variant="ghost" disabled={!adjusted} onClick={() => setAdjust(flat)}>Reset</Button>
                  <Button size="sm" onClick={() => setPanel(undefined)}>Done</Button>
                </div>
              </div>
            )}
            {(delay !== 0 || loop.a !== undefined || rate !== 1 || sleep) && (
              <div className="video-status" role="status">
                {rate !== 1 && <span>{rate}×</span>}
                {loop.a !== undefined && <span>Loop {clock(loop.a)}{loop.b !== undefined ? `–${clock(loop.b)}` : ' → set end'}</span>}
                {delay !== 0 && <span>Subtitles {delay > 0 ? '+' : ''}{Math.round(delay * 1000)} ms</span>}
                {sleep && <span>{sleep.kind === 'end' ? 'Sleep: end of video' : `Sleep ${sleepRemaining(sleep.endsAt - sleepNow)}`}</span>}
              </div>
            )}
            {resumed !== undefined && (
              <div className="resume-pill" role="group" aria-label="Resumed">
                <span>Resumed from {clock(resumed)}</span>
                <button type="button" onClick={startOver}>Start over</button>
                {/* keep watching from here, without waiting for the pill to go */}
                <button type="button" className="resume-dismiss" aria-label="Dismiss" title="Dismiss" onClick={() => (setResumed(undefined), root.current?.focus())}>
                  <Icon name="close" size={14} />
                </button>
              </div>
            )}
            {upNext && nextId && (
              <div className="up-next" role="alertdialog" aria-label="Up next">
                <span className="up-next-kicker">{upNext.left === null ? 'Up next' : `Up next in ${upNext.left}`}</span>
                <strong>{getLibItem(nextId)?.title}</strong>
                <div className="resume-actions">
                  <Button variant="primary" size="sm" autoFocus onClick={goNext}>{upNext.left === null ? 'Play next' : 'Play now'}</Button>
                  <Button size="sm" onClick={() => setUpNext(undefined)}>{upNext.left === null ? 'Close' : 'Cancel'}</Button>
                </div>
              </div>
            )}
            {shortcuts && <VideoShortcuts onClose={closeShortcuts} />}
          </div>
        </MediaController>
      )}
    </div>
  )
}
