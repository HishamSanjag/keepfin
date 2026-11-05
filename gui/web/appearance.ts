import {useSyncExternalStore} from 'react'
import {native, request} from './bridge'
import {readScale, readTheme, type ThemeChoice} from './prefs'

let state = {theme: readTheme(), scale: readScale()}
const listeners = new Set<() => void>()
const media = typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: dark)') : undefined

function apply() {
  const root = document.documentElement
  const resolved = state.theme === 'system' ? (media?.matches === false ? 'light' : 'dark') : state.theme
  root.dataset.theme = resolved
  root.style.colorScheme = resolved
  // CSS zoom keeps every px value proportional; layouts respond through container queries.
  root.style.zoom = String(state.scale / 100)
  root.style.setProperty('--zoom', String(state.scale / 100))
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', resolved === 'dark' ? '#10151e' : '#f5f3ee')
  // Main keeps the window wide enough for the smallest designed layout at this scale (dev-docs/plans/DESKTOP-WIDTHS.md).
  if (native && state.scale !== reportedScale) {
    reportedScale = state.scale
    request({op: 'windowScale', scale: state.scale}).catch(() => {})
  }
}
let reportedScale = 0
media?.addEventListener('change', () => state.theme === 'system' && apply())

export function initAppearance() {
  apply()
}
function set(next: Partial<typeof state>) {
  state = {...state, ...next}
  try {
    localStorage.setItem('keepfin-theme', state.theme)
    localStorage.setItem('keepfin-scale', String(state.scale))
  } catch {}
  apply()
  listeners.forEach((listener) => listener())
}
export const getScale = () => state.scale
export const setTheme = (theme: ThemeChoice) => set({theme})
export const setScale = (scale: number) => set({scale: Math.max(80, Math.min(140, Math.round(scale / 5) * 5))})
export function resolvedTheme() {
  return document.documentElement.dataset.theme === 'light' ? 'light' : 'dark'
}
export function useAppearance() {
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
