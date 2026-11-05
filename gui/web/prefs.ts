import {useEffect, useState} from 'react'

export function readPreference<T>(key: string, fallback: T, valid?: (value: unknown) => boolean): T {
  try {
    const raw = localStorage.getItem(key)
    if (raw === null) return fallback
    const value = JSON.parse(raw)
    return !valid || valid(value) ? value : fallback
  } catch {
    return fallback
  }
}
export function savePreference(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* Storage can be blocked; the session keeps working without persistence. */
  }
}
/** Small per-viewer UI memory (collapsed panels, view modes). Never used for engine state. */
export function usePreference<T>(key: string, fallback: T, valid?: (value: unknown) => boolean) {
  const [value, setValue] = useState<T>(() => readPreference(key, fallback, valid))
  useEffect(() => savePreference(key, value), [key, value])
  return [value, setValue] as const
}

export type ThemeChoice = 'dark' | 'light' | 'system'
export function readTheme(): ThemeChoice {
  try {
    // Nothing saved yet (a new install) follows the system; a saved choice, including an explicit Dark, is kept as it is.
    const value = localStorage.getItem('keepfin-theme')
    return value === 'light' || value === 'dark' || value === 'system' ? value : 'system'
  } catch {
    return 'system'
  }
}
export function readScale(): number {
  try {
    const value = Number(localStorage.getItem('keepfin-scale') || 100)
    return Number.isFinite(value) && value >= 80 && value <= 140 ? value : 100
  } catch {
    return 100
  }
}
