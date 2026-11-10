// Which part of the Library tab is showing, kept outside React so "Show in Library" can aim it from anywhere.
import {useSyncExternalStore} from 'react'
import type {SmartId} from './model'
import type {PlaylistTarget} from './MusicViews'

/** An album, artist or playlist opened inside a space. It is part of the page, so Back closes it and the list is where it was. */
export type SpaceDetail = {type: 'album'; key: string} | {type: 'artist'; key: string} | {type: 'playlist'; target: PlaylistTarget}

export type LibView =
  | {type: 'home'}
  /** `open` is a collection of the space to show as soon as the space is on screen; `detail` is what is open inside the space. */
  | {type: 'space'; id: string; open?: string; detail?: SpaceDetail}
  | {type: 'spaces'}
  | {type: 'smart'; id: SmartId}
  /** A collection's detail; `from` is the space it was opened from, for the way back. */
  | {type: 'collection'; id: string; from?: string}
  /** One of the user's own playlists, an album or an artist, opened from the sidebar or a link. */
  | {type: 'playlist'; id: string}
  | {type: 'album'; key: string}
  | {type: 'artist'; key: string}
  /** What was deleted from the Keepfin folder and still waits to be restored or erased. */
  | {type: 'trash'}
  /** Every song's music video as a list (See all on the Library shelf). */
  | {type: 'musicVideos'}

let view: LibView = {type: 'home'}
const listeners = new Set<() => void>()
export function setLibView(next: LibView) {
  if (JSON.stringify(next) === JSON.stringify(view)) return
  view = next
  listeners.forEach((listener) => listener())
}
export const getLibView = () => view
export function onLibView(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
export const useLibView = () =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    () => view,
  )

// Edit mode of the spaces hub: shared by the sidebar and the hub so "New space" shows only while editing.
let editing = false
const editListeners = new Set<() => void>()
export function setSpacesEditing(on: boolean) {
  if (editing === on) return
  editing = on
  editListeners.forEach((listener) => listener())
}
export const useSpacesEditing = () =>
  useSyncExternalStore(
    (listener) => {
      editListeners.add(listener)
      return () => {
        editListeners.delete(listener)
      }
    },
    () => editing,
  )

/** Which kind the Library page lists. It lives here so "Back to Library" returns to the same filter. */
export type LibFilter = 'all' | 'spaces' | 'playlists' | 'collections' | 'albums' | 'artists' | 'videos'
export const libFilters: {id: LibFilter; label: string}[] = [
  {id: 'all', label: 'All'},
  {id: 'spaces', label: 'Spaces'},
  {id: 'playlists', label: 'Playlists'},
  {id: 'collections', label: 'Collections'},
  {id: 'albums', label: 'Albums'},
  {id: 'artists', label: 'Artists'},
  {id: 'videos', label: 'Videos'},
]
let filter: LibFilter = 'all'
const filterListeners = new Set<() => void>()
export function setLibFilter(next: LibFilter) {
  if (filter === next) return
  filter = next
  filterListeners.forEach((listener) => listener())
}
export const getLibFilter = () => filter
export function onLibFilter(listener: () => void) {
  filterListeners.add(listener)
  return () => {
    filterListeners.delete(listener)
  }
}
export const useLibFilter = () =>
  useSyncExternalStore(
    (listener) => {
      filterListeners.add(listener)
      return () => {
        filterListeners.delete(listener)
      }
    },
    () => filter,
  )

/** The artist list shows everyone in the library, or only the artists followed. Lives here so Manage who you follow can aim it. */
export type ArtistFilter = 'all' | 'following'
let artistFilter: ArtistFilter = 'all'
const artistFilterListeners = new Set<() => void>()
export function setArtistFilter(next: ArtistFilter) {
  if (artistFilter === next) return
  artistFilter = next
  artistFilterListeners.forEach((listener) => listener())
}
export const useArtistFilter = () =>
  useSyncExternalStore(
    (listener) => {
      artistFilterListeners.add(listener)
      return () => {
        artistFilterListeners.delete(listener)
      }
    },
    () => artistFilter,
  )
