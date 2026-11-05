import {useSyncExternalStore} from 'react'
import {resolveSection, type SettingsCategory, type SettingsGroup} from './settings/sections'

export type {SettingsCategory, SettingsGroup}
export type Tab = 'Home' | 'Search' | 'Library' | 'Downloads' | 'Settings'

/** [anchor] is the setting to scroll to and highlight; [focus] counts navigations that carry one, so the same anchor can be asked for again. */
let state: {tab: Tab; group: SettingsCategory; anchor?: string; focus: number} = {tab: 'Downloads', group: 'downloads', focus: 0}
const listeners = new Set<() => void>()

/** Opens a page. For Settings, [group] may be a category or an id an earlier version used: it is redirected to its category (and group). */
export function navigate(tab: Tab, group?: SettingsGroup, anchor?: string) {
  let next = state.group
  let nextAnchor: string | undefined
  if (group) {
    const target = resolveSection(group, anchor)
    next = target.category
    nextAnchor = target.anchor
  }
  if (tab === state.tab && next === state.group && !nextAnchor && !state.anchor) return
  state = {tab, group: next, anchor: nextAnchor, focus: nextAnchor ? state.focus + 1 : state.focus}
  listeners.forEach((listener) => listener())
}
export const getNav = () => state
/** Calls [listener] after every page change; returns the unsubscribe function. */
export function onNav(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
export function useNav() {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    () => state,
  )
}

const LAST_TAB = 'keepfin-last-tab'
/** Remembers the last working page for "Where I left off". Settings never becomes the start page. */
export function rememberTab(tab: Tab) {
  if (tab === 'Settings') return
  try {
    localStorage.setItem(LAST_TAB, tab)
  } catch {
    /* Storage can be blocked; the start page then falls back to Downloads. */
  }
}
/** Applies the "Open Keepfin on" setting once, at startup. */
/** Pages that belong to the library; downloader-only mode never shows them. */
export const libraryTabs: readonly Tab[] = ['Home', 'Search', 'Library']
export function applyStartPage(page: 'downloads' | 'library' | 'last', fullMode = true) {
  let tab: Tab = 'Downloads'
  if (fullMode) {
    if (page === 'library') tab = 'Home'
    else if (page === 'last') {
      try {
        const last = localStorage.getItem(LAST_TAB)
        if (last === 'Library' || last === 'Home' || last === 'Search') tab = last
      } catch {
        /* Falls back to Downloads. */
      }
    }
  }
  if (tab !== 'Downloads') navigate(tab)
}
