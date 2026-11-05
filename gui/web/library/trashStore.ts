// The Keepfin trash as the Trash page and Settings › Storage & copies see it: the list the main process reads from the trash folders, kept
// outside React so the card's count and the page agree without each fetching on its own.
import {useSyncExternalStore} from 'react'
import type {TrashList} from '../../shared/library'
import {native} from '../bridge'
import {libraryRequest} from './libStore'

export type TrashState = {loaded: boolean} & TrashList

let state: TrashState = {loaded: false, hasFolder: true, entries: []}
const listeners = new Set<() => void>()
let inflight: Promise<void> | undefined
let again = false

/** Reads the trash again. A call while one is running asks for one more read afterwards, so the last answer is always the newest. */
export function refreshTrash(): Promise<void> {
  if (!native) return Promise.resolve()
  if (inflight) {
    again = true
    return inflight
  }
  inflight = libraryRequest<TrashList>('libraryTrashList', undefined)
    .then((list) => {
      if (list && Array.isArray(list.entries)) {
        state = {loaded: true, hasFolder: list.hasFolder !== false, entries: list.entries}
        listeners.forEach((listener) => listener())
      }
    })
    .catch(() => undefined)
    .finally(() => {
      inflight = undefined
      if (again) {
        again = false
        void refreshTrash()
      }
    })
  return inflight
}

export const useTrash = (): TrashState =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    () => state,
  )

/** "Deleted today", "Deleted yesterday", "Deleted 12 days ago". */
export function deletedWords(deletedAt: number, now = Date.now()): string {
  const days = Math.max(0, Math.floor((now - deletedAt) / 86_400_000))
  return days === 0 ? 'Deleted today' : days === 1 ? 'Deleted yesterday' : `Deleted ${days} days ago`
}

export const daysLeftWords = (daysLeft: number) => `${daysLeft} ${daysLeft === 1 ? 'day' : 'days'} left`
