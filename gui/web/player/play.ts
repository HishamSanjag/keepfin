// Entry points every "Play" button uses. They respect Settings › Library › Open media with.
import {useSyncExternalStore} from 'react'
import {notOnThisPc, type LibraryItem} from '../../shared/library'
import {native, request} from '../bridge'
import {getLibItem, isSong, libraryAct, librarySettings, setResumePosition} from '../library/libStore'
import {playableKind} from '../library/model'
import {toast} from '../toasts'
import {setVideoOn} from './musicVideo'
import {pause, playQueue, getPlayer, setExpanded} from './playerStore'

/** [video]: the song's music video file instead of the song. */
export function openExternally(ids: string[], video = false) {
  if (ids.length) void libraryAct('libraryOpen', ids, video ? {video} : {})
}
export const revealInFolder = (id: string) => void libraryAct('libraryReveal', id)

type Overlay = {video?: {id: string; context: string[]; start?: number; musicVideo?: boolean}; image?: {id: string; context: string[]}}
let overlay: Overlay = {}
const listeners = new Set<() => void>()
const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
function setOverlay(next: Overlay) {
  overlay = next
  listeners.forEach((listener) => listener())
}
export const useOverlay = () => useSyncExternalStore(subscribe, () => overlay)
export const getOverlay = () => overlay

export function openVideo(id: string, context: string[] = [id], start?: number) {
  pause()
  setOverlay({video: {id, context: context.includes(id) ? context : [id, ...context], ...(start !== undefined ? {start} : {})}})
}
/** A song's music video, alone in the video player from its start: not the song queue, and no play counted for the song. */
export function openMusicVideo(songId: string) {
  pause()
  setOverlay({video: {id: songId, context: [songId], musicVideo: true}})
}
export const closeVideo = () => setOverlay({...overlay, video: undefined})
// the next video starts from its own saved place: a handed-over start belongs to the video it was given for
export const setVideoId = (id: string) => overlay.video && setOverlay({...overlay, video: {id, context: overlay.video.context}})
export const openImage = (id: string, context: string[] = [id]) => setOverlay({...overlay, image: {id, context: context.includes(id) ? context : [id, ...context]}})
export const closeImage = () => setOverlay({...overlay, image: undefined})
export const setImageId = (id: string) => overlay.image && setOverlay({...overlay, image: {...overlay.image, id}})

/**
 * Plays one item. Songs queue the surrounding list (`context`); videos open the video player with the list as
 * its next/previous set; images open the viewer; anything else opens in the default app.
 */
export function playItem(item: LibraryItem, context: LibraryItem[] = [item]) {
  // an item of a shared library that is not on this PC plays from the device that has it (the engine says when that is not possible)
  if (item.missing && !notOnThisPc(item)) return toast({tone: 'error', message: `“${item.title}” is no longer at its saved location. Remove it from the library or restore the file.`})
  if (librarySettings()?.openWith === 'system' || item.kind === 'other') return openExternally([item.id])
  // a song, or a video kept in a music space: the music player, with the songs around it as the queue
  if (isSong(item)) {
    const songs = context.filter(isSong).map((entry) => entry.id)
    return playQueue(songs.includes(item.id) ? songs : [item.id], item.id)
  }
  if (item.kind === 'video') return openVideo(item.id, context.filter((entry) => entry.kind === 'video').map((entry) => entry.id))
  if (item.kind === 'image') return openImage(item.id, context.filter((entry) => entry.kind === 'image').map((entry) => entry.id))
}
export function playById(id: string, context?: LibraryItem[]) {
  const item = getLibItem(id)
  if (item) playItem(item, context)
}
/** Play all (in order) or shuffle a list: songs and videos play in the music player (a video as its sound). */
export function playAll(items: LibraryItem[], shuffle = false) {
  const songs = items.filter(playableKind)
  if (librarySettings()?.openWith === 'system') return openExternally(songs.slice(0, 1).map((item) => item.id))
  if (songs.length > 1 || isSong(songs[0])) playQueue(songs.map((item) => item.id), undefined, {shuffle})
  else if (items[0]) playItem(items[0], items)
}
/**
 * Watch video on a song that has a picture (a music video, or a video kept as a song): the full player opens with the picture in the
 * cover's place. A song that is already playing carries on from where it is; otherwise it starts, playing.
 */
export function watchVideoSong(item: LibraryItem) {
  setVideoOn(true)
  if (getPlayer().currentId === item.id) setExpanded(true)
  else playQueue([item.id], item.id, {expanded: true})
}
/** A video's sound in the music player. */
export const playAsAudio = (item: LibraryItem) => playQueue([item.id])
export const isPlayingAnything = () => getPlayer().playing

const savePosition = (id: string, position: number) => {
  setResumePosition(id, position)
  if (native) void request({op: 'libraryPlayback', id, ids: [id], position, finished: false} as never).catch(() => {})
}
/** Takes an item off Continue watching by forgetting where it stopped (it starts from the beginning next time); Undo puts it back. */
export function removeFromContinue(item: LibraryItem) {
  const was = item.position ?? 0
  savePosition(item.id, 0)
  toast({tone: 'success', message: `Removed “${item.title}” from Continue watching.`, action: {label: 'Undo', run: () => savePosition(item.id, was)}})
}
