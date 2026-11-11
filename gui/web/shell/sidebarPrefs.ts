// The sidebar's own choices, kept on this computer: floating or docked, the order and visibility of the main pages, the
// playlists hidden from it and what is pinned to its top. Hidden spaces are the library's own "show" switch, not stored here.
import {useSyncExternalStore} from 'react'
import {readPreference, savePreference} from '../prefs'
import type {Tab} from '../nav'

export type SidebarStyle = 'floating' | 'docked'
export type NavTab = Exclude<Tab, 'Settings'>
/** `pinned` and `hidden` hold keys such as `space:music`, `playlist:<id>` or `collection:<id>`. */
export type SidebarPrefs = {style: SidebarStyle; nav: NavTab[]; hiddenNav: NavTab[]; pinned: string[]; hidden: string[]}

export const NAV_TABS: readonly NavTab[] = ['Home', 'Search', 'Library', 'Downloads']
/** Downloads is the app's core and carries the active count, so it always stays. */
export const canHideTab = (tab: NavTab) => tab !== 'Downloads'
export const DEFAULT_PREFS: SidebarPrefs = {style: 'floating', nav: [...NAV_TABS], hiddenNav: [], pinned: [], hidden: []}

const strings = (value: unknown): string[] => (Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [])
const unique = <T>(list: T[]) => [...new Set(list)]

/** Whatever was saved, made whole: every page exactly once, known pages only, no duplicates. */
export function normalizePrefs(raw: unknown): SidebarPrefs {
  const saved = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  const known = (list: string[]) => list.filter((tab): tab is NavTab => (NAV_TABS as readonly string[]).includes(tab))
  const nav = unique(known(strings(saved.nav)))
  return {
    style: saved.style === 'docked' ? 'docked' : 'floating',
    nav: [...nav, ...NAV_TABS.filter((tab) => !nav.includes(tab))],
    hiddenNav: unique(known(strings(saved.hiddenNav))).filter(canHideTab),
    pinned: unique(strings(saved.pinned)),
    hidden: unique(strings(saved.hidden)),
  }
}

export const toggleIn = (list: readonly string[], key: string) => (list.includes(key) ? list.filter((entry) => entry !== key) : [...list, key])

/** `list` with `key` dropped before or after `target`. */
export function moveKey<T extends string>(list: readonly T[], key: T, target: T, after: boolean): T[] {
  if (key === target) return [...list]
  const rest = list.filter((entry) => entry !== key)
  const at = rest.indexOf(target)
  if (at < 0) return [...list]
  rest.splice(after ? at + 1 : at, 0, key)
  return rest
}

/** `list` with `key` one place earlier (-1) or later (1). */
export function stepKey<T extends string>(list: readonly T[], key: T, delta: -1 | 1): T[] {
  const at = list.indexOf(key)
  const to = at + delta
  if (at < 0 || to < 0 || to >= list.length) return [...list]
  const next = [...list]
  ;[next[at], next[to]] = [next[to], next[at]]
  return next
}

const KEY = 'keepfin-sidebar'
let prefs = normalizePrefs(readPreference<unknown>(KEY, {}))
const listeners = new Set<() => void>()

export function updateSidebarPrefs(change: (current: SidebarPrefs) => Partial<SidebarPrefs>) {
  prefs = normalizePrefs({...prefs, ...change(prefs)})
  savePreference(KEY, prefs)
  listeners.forEach((listener) => listener())
}
export const setSidebarStyle = (style: SidebarStyle) => updateSidebarPrefs(() => ({style}))
export const toggleTabHidden = (tab: NavTab) => updateSidebarPrefs((p) => ({hiddenNav: toggleIn(p.hiddenNav, tab) as NavTab[]}))
export const togglePinned = (key: string) => updateSidebarPrefs((p) => ({pinned: toggleIn(p.pinned, key)}))
export const toggleHidden = (key: string) => updateSidebarPrefs((p) => ({hidden: toggleIn(p.hidden, key)}))

export function useSidebarPrefs() {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    () => prefs,
  )
}
