// Per-viewer layout state kept outside React: the sidebar collapsed by hand, the sidebar drawer of a narrow window, and the Now playing
// panel width. The saved ones are remembered in localStorage (never required: every access is guarded).
import {useSyncExternalStore} from 'react'
import {getScale, useAppearance} from '../appearance'
import {readPreference, savePreference} from '../prefs'

/** `key: null` keeps the value for this session only. */
function makeStore<T>(key: string | null, fallback: T, valid: (value: unknown) => boolean) {
  let value = key ? readPreference<T>(key, fallback, valid) : fallback
  const listeners = new Set<() => void>()
  return {
    get: () => value,
    set(next: T) {
      if (next === value) return
      value = next
      if (key) savePreference(key, next)
      listeners.forEach((listener) => listener())
    },
    use: () =>
      useSyncExternalStore(
        (listener) => {
          listeners.add(listener)
          return () => {
            listeners.delete(listener)
          }
        },
        () => value,
      ),
  }
}

const rail = makeStore<boolean>('keepfin-rail-collapsed', false, (v) => typeof v === 'boolean')
const drawer = makeStore<boolean>(null, false, () => true)

/** Below this app width (window ÷ UI scale) the sidebar is the icon rail and opens over the page (dev-docs/plans/DESKTOP-WIDTHS.md). */
export const DOCK_MIN_WIDTH = 1100
const narrowQuery = (scale: number) => `(width < ${(DOCK_MIN_WIDTH * scale) / 100}px)`
const isNarrow = () => typeof matchMedia === 'function' && matchMedia(narrowQuery(getScale())).matches
function useNarrowWindow() {
  const {scale} = useAppearance()
  return useSyncExternalStore(
    (listener) => {
      const query = matchMedia(narrowQuery(scale))
      query.addEventListener('change', listener)
      return () => query.removeEventListener('change', listener)
    },
    () => matchMedia(narrowQuery(scale)).matches,
  )
}

export type RailMode = 'full' | 'collapsed' | 'drawer'
/** The one place that decides what the sidebar shows; CSS only draws `data-rail`. Wide: the saved choice. Narrow: the icon rail, or the
 *  full sidebar over the page while the drawer is open. */
export function useRailMode(): RailMode {
  const narrow = useNarrowWindow()
  const collapsed = rail.use()
  const open = drawer.use()
  if (narrow) return open ? 'drawer' : 'collapsed'
  return collapsed ? 'collapsed' : 'full'
}
/** The sidebar eases its own width for a moment after it is toggled (the grid has already settled, so the page lays out once); the flag says which way. */
const RAIL_ANIMATION_MS = 260
let railTimer: ReturnType<typeof setTimeout> | undefined
function animateRail(direction: 'expand' | 'collapse') {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  root.dataset.railAnim = direction
  clearTimeout(railTimer)
  railTimer = setTimeout(() => delete root.dataset.railAnim, RAIL_ANIMATION_MS)
}
export const setRailCollapsed = (collapsed: boolean) => {
  if (collapsed !== rail.get()) animateRail(collapsed ? 'collapse' : 'expand')
  rail.set(collapsed)
}
export function setDrawerOpen(open: boolean) {
  if (open !== drawer.get()) animateRail(open ? 'expand' : 'collapse')
  drawer.set(open)
}
export const closeDrawer = () => setDrawerOpen(false)
/** The sidebar button and Ctrl+B: dock or undock (and remember it) in a wide window, open or close the drawer in a narrow one. */
export const toggleRail = () => (isNarrow() ? setDrawerOpen(!drawer.get()) : setRailCollapsed(!rail.get()))

export const PANEL_DEFAULT = 320
export const PANEL_MIN = 280
export const PANEL_MAX = 520
const panel = makeStore<number>('keepfin-np-width', PANEL_DEFAULT, (v) => typeof v === 'number' && v >= PANEL_MIN && v <= PANEL_MAX)
export const usePanelWidth = panel.use
export const getPanelWidth = panel.get
export const setPanelWidth = (width: number) => panel.set(Math.round(Math.min(PANEL_MAX, Math.max(PANEL_MIN, width))))
export const resetPanelWidth = () => panel.set(PANEL_DEFAULT)
