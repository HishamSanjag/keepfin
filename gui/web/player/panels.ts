// Two overlays of the bottom bar: the Now playing side panel (cover, source, Up next) and the full-width lyrics.
import {useSyncExternalStore} from 'react'
import {readPreference, savePreference} from '../prefs'

type Panels = {nowPlaying: boolean; lyrics: boolean}
let state: Panels = {nowPlaying: readPreference<boolean>('keepfin-now-panel', false, (v) => typeof v === 'boolean'), lyrics: false}
const listeners = new Set<() => void>()
function set(next: Panels) {
  state = next
  savePreference('keepfin-now-panel', next.nowPlaying)
  listeners.forEach((listener) => listener())
}
export const toggleNowPlaying = () => set({...state, nowPlaying: !state.nowPlaying})
export const toggleLyrics = () => set({...state, lyrics: !state.lyrics})
export const closeLyrics = () => state.lyrics && set({...state, lyrics: false})
export const closeNowPlaying = () => state.nowPlaying && set({...state, nowPlaying: false})
export const usePanels = () =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    () => state,
  )

/** "FLAC", "MP3", "M4A" from the file name: the codec and bitrate are not stored, so nothing more is claimed. */
export function qualityChip(path: string | undefined): string | undefined {
  const match = path && /\.([a-z0-9]{2,5})$/i.exec(path)
  return match ? match[1].toUpperCase() : undefined
}

const providers: Record<string, string> = {youtube: 'YouTube', spotify: 'Spotify', 'apple-music': 'Apple Music', deezer: 'Deezer'}
/** Where a song came from, when it has a source id. */
export function sourceWords(sourceId: string | undefined): string | undefined {
  const name = sourceId?.split(':')[0]
  return name && providers[name] ? `Saved from ${providers[name]}` : undefined
}
