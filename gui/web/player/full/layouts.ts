// The full player's styles. To add one: write a component in this folder (it arranges the shared parts in parts.tsx and never
// re-implements playback) and add one line to `layouts` below. The choice is per computer (a renderer preference) and is offered in
// the player's header and in Settings > Appearance.
import type {ComponentType} from 'react'
import {useSyncExternalStore} from 'react'
import type {LibraryItem} from '../../../shared/library'
import {readPreference, savePreference} from '../../prefs'
import {Afterglow} from './Afterglow'
import {Cinema} from './Cinema'
import {Stage} from './Stage'

/** What a style is given: the song on screen. The rest (playback, queue, lyrics) comes from the stores through the shared parts. */
export type LayoutProps = {item: LibraryItem}

export type PlayerLayout = {
  id: string
  name: string
  /** One plain line, shown in the style menu and in Settings. */
  summary: string
  component: ComponentType<LayoutProps>
}

/** In display order. */
export const layouts: PlayerLayout[] = [
  {id: 'stage', name: 'Stage', summary: 'A big cover on the left, large lyrics on the right, one control bar along the bottom.', component: Stage},
  {id: 'cinema', name: 'Cinema', summary: 'A wide stage with the current lyric over the picture, and a side panel for the queue and lyrics.', component: Cinema},
  {id: 'afterglow', name: 'Afterglow', summary: 'A glow from the cover’s colours, big karaoke lyrics, a visualizer and the next songs below.', component: Afterglow},
]

export const DEFAULT_LAYOUT = 'stage'
const KEY = 'keepfin-player-layout'
const known = (id: unknown): id is string => typeof id === 'string' && layouts.some((layout) => layout.id === id)

/** A saved id that is not a style (renamed, removed, hand-edited) falls back to the default. */
export const layoutById = (id: unknown): PlayerLayout => layouts.find((layout) => layout.id === id) ?? layouts.find((layout) => layout.id === DEFAULT_LAYOUT) ?? layouts[0]

let current = layoutById(readPreference<string>(KEY, DEFAULT_LAYOUT)).id
const listeners = new Set<() => void>()
const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function setPlayerLayout(id: string) {
  if (!known(id) || id === current) return
  current = id
  savePreference(KEY, id)
  listeners.forEach((listener) => listener())
}

/** The chosen style's id and a setter; the player and Settings share it. */
export function usePlayerLayout(): [string, (id: string) => void] {
  const id = useSyncExternalStore(subscribe, () => current)
  return [id, setPlayerLayout]
}
