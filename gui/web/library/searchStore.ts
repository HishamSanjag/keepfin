// The Search page's query, kept outside React so the Home box can hand text over and the sidebar can return to it.
import {useSyncExternalStore} from 'react'

let query = ''
const listeners = new Set<() => void>()
export function setSearchQuery(next: string) {
  if (next === query) return
  query = next
  listeners.forEach((listener) => listener())
}
export const getSearchQuery = () => query
export const useSearchQuery = () =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    () => query,
  )

/** The YouTube results page for a text, which the download engine reads as a collection of videos. */
export const youtubeSearchLink = (text: string) => `https://www.youtube.com/results?search_query=${encodeURIComponent(text.trim())}`
