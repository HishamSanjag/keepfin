import {useSyncExternalStore} from 'react'

export type Toast = {
  id: number
  tone: 'success' | 'error' | 'info'
  message: string
  action?: {label: string; run: () => void}
  secondary?: {label: string; run: () => void}
  /** A new toast with the same key replaces the older one. */
  key?: string
  /** How long it stays, in ms. Hovering or focusing it pauses the clock. */
  duration?: number
  /** Runs once when the toast goes without its main button (Undo) being pressed: its time ran out, it was closed or replaced. */
  onGone?: () => void
}
let toasts: Toast[] = []
let seq = 0
const listeners = new Set<() => void>()
const timers = new Map<number, ReturnType<typeof setTimeout>>()
const lengths = new Map<number, number>()
const emit = () => listeners.forEach((listener) => listener())
const lengthOf = (input: Omit<Toast, 'id'>) => input.duration ?? (input.tone === 'error' ? 9000 : input.action ? 8000 : 5000)

export function toast(input: Omit<Toast, 'id'>) {
  const id = ++seq
  // The same message (or the same key) again replaces the older toast instead of stacking.
  const replaced = toasts.filter((item) => item.message === input.message || (input.key !== undefined && item.key === input.key))
  replaced.forEach((item) => stopClock(item.id))
  const next = [...toasts.filter((item) => !replaced.includes(item)), {...input, id}]
  // at most four: the oldest go, as if closed
  const dropped = next.slice(0, -4)
  toasts = next.slice(-4)
  for (const item of [...replaced, ...dropped]) {
    stopClock(item.id)
    lengths.delete(item.id)
  }
  emit()
  for (const item of [...replaced, ...dropped]) item.onGone?.()
  lengths.set(id, lengthOf(input))
  resumeToast(id)
  return id
}
function stopClock(id: number) {
  clearTimeout(timers.get(id))
  timers.delete(id)
}
/** Hovering or focusing a toast stops its clock so there is time to read it and press its buttons. */
export function holdToast(id: number) {
  stopClock(id)
}
export function resumeToast(id: number) {
  stopClock(id)
  if (!toasts.some((item) => item.id === id)) return
  timers.set(id, setTimeout(() => dismissToast(id), lengths.get(id) ?? 5000))
}
/** Removes a toast; [acted] says its main button was pressed (then [Toast.onGone] does not run). */
export function dismissToast(id: number, acted = false) {
  stopClock(id)
  lengths.delete(id)
  const item = toasts.find((entry) => entry.id === id)
  if (!item) return
  toasts = toasts.filter((entry) => entry.id !== id)
  emit()
  if (!acted) item.onGone?.()
}
export function useToasts() {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    () => toasts,
  )
}
