// Songs that have a picture: a music video beside the song, or a video kept as a song. The picture plays in the cover's place in the
// full player (player/full/parts.tsx) and the engine (playerStore.ts) picks the file. This module only remembers the "Video" choice,
// which holds for the next songs that have a picture. Song | Video keeps one place: a switch goes on from the same second
// (the core's switchStart), so neither side keeps a place of its own.
import {readPreference, savePreference} from '../prefs'

const onKey = 'keepfin-music-video-on'

let on = readPreference<boolean>(onKey, false, (value) => typeof value === 'boolean')
const listeners = new Set<() => void>()
const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
/** True while "Video" is the remembered choice. */
export const isVideoOn = () => on
export function setVideoOn(value: boolean) {
  if (on === value) return
  on = value
  savePreference(onKey, value)
  listeners.forEach((listener) => listener())
}
/** Told whenever the choice changes (the engine follows it). */
export const onVideoChoice = subscribe
