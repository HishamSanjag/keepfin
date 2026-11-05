// Music player: queue + transport state in an external store, and the audio engine behind it.
// Two native media elements (<video>, which plays audio files the same way): the idle one preloads the next song, so the handover at the
// end of a song is a few milliseconds (honestly "near-gapless"), and a crossfade (settings) overlaps them. Volume normalisation is
// attenuation through element.volume, so Windows media controls keep tracking the element. For a song that has a picture the full
// player moves the active element into the cover's place (player/full/parts.tsx), so the picture never reloads.
import {useSyncExternalStore} from 'react'
import {switchStart, type LibraryItem, type PrepareResult} from '../../shared/library'
import {messageOf, native, request} from '../bridge'
import {getLibItem, getLibrary, isVideoSong, libraryAct, libraryRequest, librarySettings, notePlayback} from '../library/libStore'
import {playableKind} from '../library/model'
import {artworkSrc, itemUrl, musicVideoUrl} from '../media'
import {relativeTime} from '../format'
import {readPreference, savePreference} from '../prefs'
import {toast} from '../toasts'
import {attachEqualizer, resumeEqualizer, setGraphSink} from './equalizer'
import {bindSession, clearSession} from './mediaSession'
import {isVideoOn, onVideoChoice} from './musicVideo'

export type Repeat = 'off' | 'all' | 'one'
export type PlayerError = {id: string; message: string}
export type Sleep = {kind: 'minutes' | 'end'; minutes?: number; endsAt?: number}
export type PlayerState = {
  /** Playback order (shuffled when shuffle is on). */
  queue: string[]
  /** The order the songs were queued in; shuffle off returns to it. */
  order: string[]
  index: number
  currentId?: string
  playing: boolean
  loading: boolean
  shuffle: boolean
  repeat: Repeat
  speed: number
  volume: number
  muted: boolean
  sleep?: Sleep
  error?: PlayerError
  /** The paired device the current song plays from, when its file is not on this computer. */
  from?: string
  expanded: boolean
  /** The full player shows the current song's picture in the cover's place (its music video, or the video kept as a song). */
  picture: boolean
  sinkId: string
}

const storageKey = 'keepfin-player-v1'
const initial = (): PlayerState => ({
  queue: [],
  order: [],
  index: -1,
  playing: false,
  loading: false,
  shuffle: false,
  repeat: 'off',
  speed: 1,
  volume: 1,
  muted: false,
  expanded: false,
  picture: false,
  sinkId: '',
})
let state: PlayerState = initial()
const listeners = new Set<() => void>()
function set(change: Partial<PlayerState>) {
  const next = {...state, ...change}
  if (change.queue || change.index !== undefined) next.currentId = next.queue[next.index]
  state = next
  listeners.forEach((listener) => listener())
  scheduleSave()
}
const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
export const getPlayer = () => state
/** Select a primitive (or a stable reference) so a component re-renders only when its slice changes. */
export function usePlayer<T>(selector: (state: PlayerState) => T): T {
  return useSyncExternalStore(subscribe, () => selector(state))
}
export const usePlayerState = () => useSyncExternalStore(subscribe, () => state)

// ---- time (its own store: only the seek bar and lyrics listen) ---------------------------------------

export type PlayerTime = {position: number; duration: number}
let time: PlayerTime = {position: 0, duration: 0}
const timeListeners = new Set<() => void>()
function setTime(position: number, duration: number) {
  if (time.position === position && time.duration === duration) return
  time = {position, duration}
  timeListeners.forEach((listener) => listener())
}
const subscribeTime = (listener: () => void) => {
  timeListeners.add(listener)
  return () => {
    timeListeners.delete(listener)
  }
}
export const useTime = () => useSyncExternalStore(subscribeTime, () => time)
export const getTime = () => time

// ---- persistence ---------------------------------------------------------------------------------------

let saveTimer: ReturnType<typeof setTimeout> | undefined
function scheduleSave() {
  if (saveTimer) return
  saveTimer = setTimeout(() => {
    saveTimer = undefined
    saveNow()
  }, 400)
}
function saveNow() {
  const {queue, order, index, shuffle, repeat, speed, volume, muted, sinkId} = state
  savePreference(storageKey, {queue, order, index, shuffle, repeat, speed, volume, muted, sinkId, position: time.position})
}
if (typeof window !== 'undefined') window.addEventListener('beforeunload', saveNow)

// ---- engine -------------------------------------------------------------------------------------------

type Prepared = {url: string; playable: boolean; message?: string; from?: string}
const prepared = new Map<string, Prepared>()
/** The other device has the file but cannot be reached: plain words, with when it was last seen. Not remembered, so playing again tries again. */
export const unreachableMessage = (gone: {device: string; lastSeen: number | null}) => `${gone.device} is not reachable.${gone.lastSeen ? ` It was last seen ${relativeTime(gone.lastSeen)}.` : ''}`
export async function prepareItem(item: LibraryItem): Promise<Prepared> {
  const key = `${item.id}:${item.modifiedAt}`
  const known = prepared.get(key)
  if (known) return known
  let result: Prepared = {url: itemUrl(item.id), playable: true}
  if (native) {
    try {
      const raw = await libraryRequest<PrepareResult>('libraryPrepare', item.id)
      if (raw && 'unsupported' in raw) result = {url: result.url, playable: false, message: raw.reason}
      else if (raw && 'unreachable' in raw) return {url: result.url, playable: false, message: unreachableMessage(raw.unreachable)}
      else if (raw && typeof raw.url === 'string' && raw.url) result = {url: raw.url, playable: true, from: raw.from}
    } catch (error) {
      result = {url: result.url, playable: false, message: messageOf(error)}
    }
  }
  if (prepared.size > 200) prepared.clear()
  prepared.set(key, result)
  return result
}

type Media = HTMLVideoElement
let a: Media | undefined
let b: Media | undefined
let active: Media
let idle: Media
let holder: HTMLElement | undefined
let idleFor: string | undefined
/** The active / idle element plays the song's music video file, not the song's own file. */
let activeVideoFile = false
let idleVideoFile = false
/** The side (video file or song file) a load in flight will install; undefined when none is pending. */
let loadingVideoFile: boolean | undefined
let token = 0
let pendingStart = 0
let fade: {raf: number; startedAt: number; seconds: number; toId: string} | undefined
let ticker: ReturnType<typeof setInterval> | undefined
let lastReport = 0
let sleepTimer: ReturnType<typeof setTimeout> | undefined
let sleepAtEnd = false

// ---- the active element, for the cover's place ----------------------------------------------------------

const mediaListeners = new Set<() => void>()
const subscribeMedia = (listener: () => void) => {
  mediaListeners.add(listener)
  return () => {
    mediaListeners.delete(listener)
  }
}
const notifyMedia = () => mediaListeners.forEach((listener) => listener())
/** The element that plays now; it changes at each handover. Undefined until the first song loads. */
export const useActiveMedia = (): HTMLVideoElement | undefined => useSyncExternalStore(subscribeMedia, () => (a ? active : undefined))

/** Moves a media element without pausing it (`moveBefore` keeps its state; a plain append is the fallback). */
function moveMedia(parent: HTMLElement, el: Media) {
  if (el.parentElement === parent) return
  const target = parent as HTMLElement & {moveBefore?: (node: Node, child: Node | null) => void}
  if (typeof target.moveBefore === 'function' && el.isConnected) {
    try {
      target.moveBefore(el, null)
      return
    } catch {
      // not movable that way: append below
    }
  }
  const was = !el.paused
  parent.append(el)
  if (was && el.paused) void el.play().catch(() => {})
}
/** The picture's frame takes the element (it keeps playing)... */
export const hostMedia = (frame: HTMLElement, el: Media) => moveMedia(frame, el)
/** ...and gives it back to the hidden holder when the frame goes away. */
export const parkMedia = (el: Media) => holder && moveMedia(holder, el)

function ensureEngine() {
  if (a) return
  holder = document.createElement('div')
  holder.className = 'player-audio'
  holder.setAttribute('aria-hidden', 'true')
  holder.hidden = true
  a = document.createElement('video')
  b = document.createElement('video')
  for (const el of [a, b]) {
    el.playsInline = true
    el.preload = 'auto'
    // CORS mode from the start (the media protocol allows it): the equalizer can only shape audio it was allowed to read
    el.crossOrigin = 'anonymous'
    el.setAttribute('data-role', 'music')
    el.addEventListener('play', resumeEqualizer)
    holder.append(el)
    el.addEventListener('timeupdate', () => el === active && onTime())
    el.addEventListener('durationchange', () => el === active && onTime())
    el.addEventListener('ended', () => el === active && onEnded())
    el.addEventListener('error', () => el === active && onError())
    el.addEventListener('playing', () => el === active && onPlaying())
    el.addEventListener('pause', () => el === active && !el.ended && onPaused())
    el.addEventListener('waiting', () => el === active && set({loading: true}))
    el.addEventListener('seeked', () => el === active && (updateSession(), onTime()))
    el.addEventListener('ratechange', () => el === active && updateSession())
  }
  document.body.append(holder)
  active = a
  idle = b
  attachEqualizer([a, b])
  if (state.sinkId) void applySink(state.sinkId)
  window.__keepfinTest && (window.__keepfinTest.audio = () => ({active, idle}))
  notifyMedia()
}

// ---- the picture -------------------------------------------------------------------------------------------

/** Songs that have a picture: a music video beside the song, or a video kept as a song. */
export const itemHasPicture = (item?: LibraryItem): boolean => !!item && (!!item.musicVideo || isVideoSong(item))
/** The picture shows only in the expanded player, and only while "Video" is the remembered choice. */
const wantsPicture = (item?: LibraryItem) => itemHasPicture(item) && state.expanded && isVideoOn()
/**
 * The song plays from its music video file (a video kept as a song is the same file either way). Collapsing the player keeps the video
 * playing, so the file is only given up when the choice goes back to Song, never by the picture parking.
 */
const usesVideoFile = (item?: LibraryItem) => !!item?.musicVideo && isVideoOn() && (state.expanded || activeVideoFile)

/**
 * Follows the expanded player and the Video choice: the picture turns on or off, and a song with a music video swaps its file
 * on the active element. Song | Video goes on from the same second, stopped at the end of the shorter side (the core's switchStart).
 */
function syncPicture() {
  const item = state.currentId ? getLibItem(state.currentId) : undefined
  const want = wantsPicture(item)
  const needFile = usesVideoFile(item)
  if (want !== state.picture) set({picture: want})
  // a pending load counts as the side already chosen, so a quick Song then Video is not mistaken for no change
  const current = loadingVideoFile ?? activeVideoFile
  if (!a || !item || !active.getAttribute('src') || needFile === current) return
  const wasPlaying = !active.paused
  const here = active.currentTime
  report(false)
  void loadAndPlay(state.index, switchStart(here, needFile ? item.videoDuration : item.duration), wasPlaying)
}
onVideoChoice(syncPicture)

function gainFactor(id: string | undefined) {
  const item = id ? getLibItem(id) : undefined
  if (!item || item.gain === undefined || !librarySettings()?.normalize) return 1
  return Math.min(1, Math.pow(10, item.gain / 20))
}
function applyVolume(el: Media, id: string | undefined, scale = 1) {
  el.volume = Math.max(0, Math.min(1, state.volume * gainFactor(id) * scale))
  el.muted = state.muted
  el.playbackRate = state.speed
  el.preservesPitch = true
}

function startsAt(item: LibraryItem) {
  // A music video starts at its beginning (a switch passes its own place); music restarts; long audio (podcasts, mixes, audiobooks) resumes where it stopped.
  if (usesVideoFile(item)) return 0
  return item.kind === 'audio' && (item.duration || 0) > 1200 ? item.position || 0 : 0
}

function onTime() {
  if (!active) return
  const duration = Number.isFinite(active.duration) ? active.duration : getLibItem(state.currentId || '')?.duration || 0
  setTime(active.currentTime, duration)
  maybeCrossfade()
  if (state.playing && Date.now() - lastReport > 10_000) report(false)
}

function report(finished: boolean) {
  const id = state.currentId
  if (!id || !active) return
  lastReport = Date.now()
  const position = finished ? 0 : active.currentTime
  // switching to the music video counts no second play of the song
  if (activeVideoFile) return
  notePlayback(id, position, finished)
  if (native) void request({op: 'libraryPlayback', id, ids: [id], position, finished}).catch(() => {})
}

function onPlaying() {
  set({playing: true, loading: false, error: undefined})
  updateSession()
  startTicker()
}
function onPaused() {
  if (fade) return
  set({playing: false, loading: false})
  report(false)
  updateSession()
  stopTicker()
}
function startTicker() {
  stopTicker()
  ticker = setInterval(onTime, state.expanded ? 120 : 250)
}
function stopTicker() {
  if (ticker) clearInterval(ticker)
  ticker = undefined
}

function onError() {
  const id = state.currentId
  if (!id) return
  const item = getLibItem(id)
  set({playing: false, loading: false, error: {id, message: `Keepfin can’t play “${item?.title || 'this file'}” here. Its format isn’t supported by the built-in player.`}})
  stopTicker()
}

/** The next index after `from` for the current repeat mode, skipping songs that left the library. */
function nextIndex(from = state.index, wrap = state.repeat === 'all'): number {
  for (let i = from + 1; i < state.queue.length; i++) if (getLibItem(state.queue[i])) return i
  if (wrap) for (let i = 0; i <= from && i < state.queue.length; i++) if (getLibItem(state.queue[i])) return i
  return -1
}
function previousIndex(): number {
  for (let i = state.index - 1; i >= 0; i--) if (getLibItem(state.queue[i])) return i
  if (state.repeat === 'all') for (let i = state.queue.length - 1; i > state.index; i--) if (getLibItem(state.queue[i])) return i
  return -1
}

async function preloadNext() {
  const at = state.repeat === 'one' ? -1 : nextIndex()
  const id = at >= 0 ? state.queue[at] : undefined
  const item = id ? getLibItem(id) : undefined
  if (!id || !item) return
  const videoFile = usesVideoFile(item)
  if (id === idleFor && videoFile === idleVideoFile) return
  const mine = token
  let url: string
  if (videoFile) url = musicVideoUrl(id)
  else {
    const result = await prepareItem(item)
    if (mine !== token || !result.playable) return
    url = result.url
  }
  idleFor = id
  idleVideoFile = videoFile
  idle.src = url
  idle.load()
}

/** Repeat one loops natively, except while the end-of-song sleep is armed: a looping element never ends. */
const loops = () => state.repeat === 'one' && !sleepAtEnd

function endOfSleep() {
  sleepAtEnd = false
  if (active) active.loop = loops()
  if (sleepTimer) clearTimeout(sleepTimer)
  sleepTimer = undefined
  set({sleep: undefined})
}

function onEnded() {
  report(true)
  if (sleepAtEnd) {
    endOfSleep()
    set({playing: false})
    setTime(0, time.duration)
    toast({tone: 'info', message: 'Sleep timer: playback stopped at the end of the song.'})
    return
  }
  if (state.repeat === 'one') {
    active.currentTime = 0
    void active.play().catch(() => {})
    return
  }
  const at = nextIndex()
  if (at < 0) {
    set({playing: false})
    stopTicker()
    active.currentTime = 0
    setTime(0, time.duration)
    updateSession()
    return
  }
  handOver(at)
}

/** Switches to the preloaded element right away; loads from scratch if the preload is not ready. */
function handOver(at: number) {
  const id = state.queue[at]
  const item = getLibItem(id)
  const videoFile = usesVideoFile(item)
  if (idleFor === id && idleVideoFile === videoFile && idle.readyState >= 2) {
    cancelFade()
    const old = active
    active = idle
    idle = old
    idleFor = undefined
    activeVideoFile = videoFile
    idleVideoFile = false
    old.pause()
    old.removeAttribute('src')
    old.load()
    token++
    loadingVideoFile = undefined
    set({index: at, error: undefined, picture: wantsPicture(item)})
    notifyMedia()
    applyVolume(active, id)
    active.currentTime = 0
    void active.play().catch(() => {})
    onTime()
    updateSession()
    void preloadNext()
  } else void loadAndPlay(at, 0)
}

function maybeCrossfade() {
  const seconds = librarySettings()?.crossfade || 0
  if (!seconds || fade || !state.playing || state.repeat === 'one' || sleepAtEnd) return
  const duration = active.duration
  if (!Number.isFinite(duration) || duration < seconds * 2 + 1) return
  const remaining = duration - active.currentTime
  if (remaining > seconds || remaining < 0.25) return
  const at = nextIndex()
  const id = at >= 0 ? state.queue[at] : undefined
  // a music video hands over at its end instead of fading
  if (!id || idleFor !== id || idle.readyState < 2 || activeVideoFile || idleVideoFile) return
  const outgoing = active
  const incoming = idle
  applyVolume(incoming, id, 0)
  void incoming.play().catch(() => {})
  const startedAt = performance.now()
  const run = (now: number) => {
    if (!fade) return
    const t = Math.min(1, (now - startedAt) / (seconds * 1000))
    applyVolume(outgoing, state.currentId, Math.cos((t * Math.PI) / 2))
    applyVolume(incoming, id, Math.sin((t * Math.PI) / 2))
    if (t < 1) fade.raf = requestAnimationFrame(run)
    else {
      fade = undefined
      outgoing.pause()
      outgoing.removeAttribute('src')
      outgoing.load()
      active = incoming
      idle = outgoing
      idleFor = undefined
      token++
      loadingVideoFile = undefined
      report(true)
      set({index: at, error: undefined, picture: wantsPicture(getLibItem(id))})
      notifyMedia()
      applyVolume(active, id)
      onTime()
      updateSession()
      void preloadNext()
    }
  }
  fade = {raf: requestAnimationFrame(run), startedAt, seconds, toId: id}
}
function cancelFade() {
  if (!fade) return
  cancelAnimationFrame(fade.raf)
  fade = undefined
  if (idle) {
    idle.pause()
    idle.currentTime = 0
  }
  if (active) applyVolume(active, state.currentId)
}

async function loadAndPlay(at: number, start: number, autoplay = true) {
  ensureEngine()
  cancelFade()
  const id = state.queue[at]
  const item = id ? getLibItem(id) : undefined
  if (!item) {
    set({index: at})
    return
  }
  const mine = ++token
  if (state.currentId && state.currentId !== id && active.src) report(false)
  loadingVideoFile = usesVideoFile(item)
  // a song with a music video, shown as a picture: its own file
  const videoFile = usesVideoFile(item)
  const begin = start
  set({index: at, loading: true, error: undefined, playing: false, from: undefined, picture: wantsPicture(item)})
  setTime(begin, item.duration || 0)
  let url: string
  if (videoFile) url = musicVideoUrl(id)
  else {
    const result = await prepareItem(item)
    if (mine !== token) return
    if (!result.playable) {
      loadingVideoFile = undefined
      set({loading: false, error: {id, message: result.message || `Keepfin can’t play “${item.title}” here. Its format isn’t supported by the built-in player.`}})
      return
    }
    set({from: result.from})
    url = result.url
  }
  if (idleFor === id && idleVideoFile === videoFile && idle.readyState >= 2) {
    const old = active
    active = idle
    idle = old
    old.pause()
    idleFor = undefined
  } else {
    idleFor = idleFor === id ? undefined : idleFor
    active.src = url
  }
  loadingVideoFile = undefined
  activeVideoFile = videoFile
  idleVideoFile = false
  notifyMedia()
  applyVolume(active, id)
  active.loop = loops()
  const seek = () => {
    // the shorter side ends the switch: a place past its end lands just before it (Songs.switchStart in the core)
    if (mine === token && begin > 0) active.currentTime = switchStart(begin, active.duration)
  }
  if (active.readyState >= 1) seek()
  else active.addEventListener('loadedmetadata', seek, {once: true})
  updateSession()
  if (autoplay) {
    try {
      await active.play()
    } catch (error) {
      if (mine === token && (error as DOMException)?.name !== 'AbortError') onError()
    }
  } else set({loading: false})
  if (mine === token) void preloadNext()
}

// ---- public actions -----------------------------------------------------------------------------------

function shuffled(ids: string[], first?: string) {
  const rest = ids.filter((id) => id !== first)
  for (let i = rest.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[rest[i], rest[j]] = [rest[j], rest[i]]
  }
  return first ? [first, ...rest] : rest
}

/** Plays `ids` as the queue. With `shuffle`, the whole queue is shuffled (starting song = startId if given). */
export function playQueue(ids: string[], startId?: string, options: {shuffle?: boolean; /** Open the full player with it. */ expanded?: boolean} = {}) {
  const list = ids.filter((id) => {
    const item = getLibItem(id)
    return item && playableKind(item)
  })
  if (!list.length) return
  ensureEngine()
  const shuffle = options.shuffle ?? state.shuffle
  const first = startId && list.includes(startId) ? startId : shuffle ? list[Math.floor(Math.random() * list.length)] : list[0]
  const queue = shuffle ? shuffled(list, first) : list
  set({queue, order: list, shuffle, expanded: options.expanded ?? state.expanded})
  idleFor = undefined
  const item = getLibItem(first)!
  void loadAndPlay(queue.indexOf(first), item ? startsAt(item) : 0)
}
export function playNext(ids: string[]) {
  const list = ids.filter((id) => playableKind(getLibItem(id)))
  if (!list.length) return
  if (!state.queue.length) return playQueue(list)
  const at = state.index + 1
  set({queue: [...state.queue.slice(0, at), ...list, ...state.queue.slice(at)], order: [...state.order, ...list]})
  idleFor = undefined
  void preloadNext()
  toast({tone: 'success', message: list.length === 1 ? 'Playing next.' : `${list.length} songs will play next.`})
}
export function addToQueue(ids: string[]) {
  const list = ids.filter((id) => playableKind(getLibItem(id)))
  if (!list.length) return
  if (!state.queue.length) return playQueue(list)
  set({queue: [...state.queue, ...list], order: [...state.order, ...list]})
  void preloadNext()
  toast({tone: 'success', message: list.length === 1 ? 'Added to the queue.' : `${list.length} songs added to the queue.`})
}
export function jumpTo(at: number) {
  if (at < 0 || at >= state.queue.length) return
  const item = getLibItem(state.queue[at])
  if (item) void loadAndPlay(at, startsAt(item))
}
export function removeFromQueue(at: number) {
  if (at < 0 || at >= state.queue.length) return
  const id = state.queue[at]
  const queue = state.queue.filter((_, i) => i !== at)
  const order = state.order.includes(id) && !queue.includes(id) ? state.order.filter((value) => value !== id) : state.order
  if (at === state.index) {
    if (!queue.length) return stopAndClear()
    set({queue, order})
    idleFor = undefined
    const next = Math.min(at, queue.length - 1)
    set({index: next})
    const item = getLibItem(queue[next])
    if (item) void loadAndPlay(next, startsAt(item), state.playing)
    return
  }
  set({queue, order, index: at < state.index ? state.index - 1 : state.index})
  idleFor = undefined
  void preloadNext()
}
export function moveInQueue(from: number, to: number) {
  if (from === to || from < 0 || to < 0 || from >= state.queue.length || to >= state.queue.length) return
  const queue = state.queue.slice()
  const [moved] = queue.splice(from, 1)
  queue.splice(to, 0, moved)
  const current = state.currentId
  set({queue, index: current ? queue.indexOf(current) : state.index, order: state.shuffle ? state.order : queue})
  idleFor = undefined
  void preloadNext()
}
/** Clears everything that has not played yet; the current song keeps playing. */
export function clearQueue() {
  const id = state.currentId
  if (!id) return
  set({queue: [id], order: [id], index: 0})
  idleFor = undefined
}
function stopAndClear() {
  token++
  loadingVideoFile = undefined
  cancelFade()
  if (active) {
    active.pause()
    active.removeAttribute('src')
    active.load()
  }
  stopTicker()
  setTime(0, 0)
  activeVideoFile = false
  set({queue: [], order: [], index: -1, playing: false, loading: false, error: undefined, expanded: false, picture: false})
  clearSession('music')
}
export const stopPlayback = stopAndClear

export function togglePlay() {
  if (!state.currentId) return
  ensureEngine()
  if (state.error) return jumpTo(state.index)
  if (!active.getAttribute('src')) {
    const item = getLibItem(state.currentId)
    const start = pendingStart || (item ? startsAt(item) : 0)
    pendingStart = 0
    return void loadAndPlay(state.index, start)
  }
  if (active.paused) {
    if (fade) cancelFade()
    if (active.ended) active.currentTime = 0
    void active.play().catch(() => {})
  } else {
    cancelFade()
    active.pause()
  }
}
export function pause() {
  if (active && !active.paused) {
    cancelFade()
    active.pause()
  }
}
export function next() {
  const at = nextIndex(state.index, true)
  if (at < 0) return
  const id = state.queue[at]
  if (idleFor === id && idle.readyState >= 2 && state.playing) handOver(at)
  else {
    const item = getLibItem(id)
    if (item) void loadAndPlay(at, startsAt(item))
  }
}
export function previous() {
  if (active && active.currentTime > 3) return seekTo(0)
  const at = previousIndex()
  if (at < 0) return seekTo(0)
  const item = getLibItem(state.queue[at])
  if (item) void loadAndPlay(at, startsAt(item))
}
export function seekTo(seconds: number) {
  if (!state.currentId) return
  ensureEngine()
  cancelFade()
  const duration = Number.isFinite(active.duration) ? active.duration : time.duration
  const target = Math.max(0, Math.min(duration || seconds, seconds))
  if (active.src) active.currentTime = target
  else pendingStart = target
  setTime(target, duration)
  updateSession()
}
export function skipBy(delta: number) {
  seekTo((active?.currentTime ?? time.position) + delta)
}
export function setVolume(volume: number) {
  const next = Math.max(0, Math.min(1, volume))
  set({volume: next, muted: next === 0 ? state.muted : false})
  if (active) {
    applyVolume(active, state.currentId)
    if (!fade && idle) applyVolume(idle, idleFor)
  }
}
export function toggleMute() {
  set({muted: !state.muted})
  if (active) active.muted = state.muted
  if (idle) idle.muted = state.muted
}
export function setSpeed(speed: number) {
  set({speed})
  if (active) active.playbackRate = speed
  if (idle) idle.playbackRate = speed
}
export function setRepeat(repeat: Repeat) {
  set({repeat})
  if (active) active.loop = loops()
  idleFor = undefined
  void preloadNext()
}
export function cycleRepeat() {
  setRepeat(state.repeat === 'off' ? 'all' : state.repeat === 'all' ? 'one' : 'off')
}
export function toggleShuffle() {
  const current = state.currentId
  if (!state.shuffle) {
    const queue = shuffled(state.queue, current)
    set({shuffle: true, queue, index: current ? 0 : -1})
  } else {
    const order = state.order.filter((id) => state.queue.includes(id))
    const missing = state.queue.filter((id) => !order.includes(id))
    const queue = [...order, ...missing]
    set({shuffle: false, queue, index: current ? queue.indexOf(current) : -1})
  }
  idleFor = undefined
  void preloadNext()
}
export function setExpanded(expanded: boolean) {
  set({expanded})
  syncPicture()
  if (state.playing) startTicker()
}
export function setSleep(sleep: Sleep | undefined) {
  if (sleepTimer) clearTimeout(sleepTimer)
  sleepTimer = undefined
  sleepAtEnd = false
  if (active) active.loop = loops()
  if (!sleep) return set({sleep: undefined})
  if (sleep.kind === 'end') {
    sleepAtEnd = true
    if (active) active.loop = false
    set({sleep})
  } else {
    const endsAt = Date.now() + (sleep.minutes || 0) * 60_000
    set({sleep: {...sleep, endsAt}})
    sleepTimer = setTimeout(() => {
      sleepTimer = undefined
      set({sleep: undefined})
      pause()
      toast({tone: 'info', message: 'Sleep timer: playback paused.'})
    }, endsAt - Date.now())
  }
}
export async function applySink(sinkId: string) {
  ensureEngine()
  for (const el of [a, b]) {
    const media = el as Media & {setSinkId?: (id: string) => Promise<void>}
    if (media?.setSinkId) await media.setSinkId(sinkId)
  }
  // with the equalizer on, the sound leaves through its graph: that follows the device too
  await setGraphSink(sinkId)
}
export async function setOutputDevice(sinkId: string) {
  try {
    await applySink(sinkId)
    set({sinkId})
  } catch (error) {
    toast({tone: 'error', message: `Couldn’t switch the output device. ${messageOf(error)}`})
  }
}
export function canPickOutput() {
  return typeof HTMLMediaElement !== 'undefined' && 'setSinkId' in HTMLMediaElement.prototype && !!navigator.mediaDevices?.enumerateDevices
}

/** "Open in another app" for the song that cannot play. */
export function openCurrentExternally() {
  const id = state.error?.id || state.currentId
  if (id) void libraryAct('libraryOpen', id)
}

// ---- OS media session ---------------------------------------------------------------------------------

function updateSession() {
  const id = state.currentId
  const item = id ? getLibItem(id) : undefined
  if (!item || !active) return clearSession('music')
  bindSession('music', {
    title: item.title,
    artist: item.artist || item.albumArtist || '',
    album: item.album || '',
    art: item.hasArt ? artworkSrc(item.id) : undefined,
    playing: state.playing,
    position: active.currentTime,
    duration: Number.isFinite(active.duration) ? active.duration : item.duration || 0,
    rate: active.playbackRate,
    handlers: {
      play: () => (active.paused ? togglePlay() : undefined),
      pause: () => pause(),
      previoustrack: () => previous(),
      nexttrack: () => next(),
      seekto: (details) => details.seekTime !== undefined && seekTo(details.seekTime),
      seekbackward: (details) => skipBy(-(details.seekOffset || 10)),
      seekforward: (details) => skipBy(details.seekOffset || 10),
      stop: () => pause(),
    },
  })
}

// ---- restore --------------------------------------------------------------------------------------------

let restored = false
/** Restores the last queue paused at its position once the library is known. Never starts playback. */
export function restorePlayer() {
  if (restored) return
  restored = true
  const saved = readPreference<Partial<PlayerState> & {position?: number}>(storageKey, {}, (value) => !!value && typeof value === 'object')
  const known = (id: unknown): id is string => typeof id === 'string' && !!getLibItem(id)
  const queue = Array.isArray(saved.queue) ? saved.queue.filter(known) : []
  const currentId = Array.isArray(saved.queue) && typeof saved.index === 'number' ? saved.queue[saved.index] : undefined
  const settings: Partial<PlayerState> = {
    shuffle: !!saved.shuffle,
    repeat: saved.repeat === 'all' || saved.repeat === 'one' ? saved.repeat : 'off',
    speed: typeof saved.speed === 'number' && saved.speed >= 0.25 && saved.speed <= 2 ? saved.speed : 1,
    volume: typeof saved.volume === 'number' ? Math.max(0, Math.min(1, saved.volume)) : 1,
    muted: !!saved.muted,
    sinkId: typeof saved.sinkId === 'string' ? saved.sinkId : '',
  }
  if (!queue.length) return set(settings)
  const index = currentId && queue.includes(currentId) ? queue.indexOf(currentId) : 0
  set({...settings, queue, order: Array.isArray(saved.order) ? saved.order.filter(known) : queue, index})
  const item = getLibItem(queue[index])
  pendingStart = typeof saved.position === 'number' ? saved.position : 0
  setTime(pendingStart, item?.duration || 0)
  if (settings.sinkId) {
    ensureEngine()
    void applySink(settings.sinkId as string).catch(() => {})
  }
}

/** Library content changed: drop queue entries whose files are gone. */
export function pruneQueue() {
  if (!state.queue.length) return
  const present = state.queue.filter((id) => getLibrary().byId.has(id))
  if (present.length === state.queue.length) return
  if (!present.includes(state.currentId || '')) return stopAndClear()
  set({queue: present, order: state.order.filter((id) => present.includes(id)), index: present.indexOf(state.currentId!)})
}

/** An item's id changed (rename); the queue keeps playing the same file under the new id. */
export function remapQueueId(from: string, to: string) {
  if (!state.queue.includes(from)) return
  const swap = (ids: string[]) => ids.map((id) => (id === from ? to : id))
  state = {...state, queue: swap(state.queue), order: swap(state.order), currentId: state.currentId === from ? to : state.currentId}
  listeners.forEach((listener) => listener())
  scheduleSave()
}
