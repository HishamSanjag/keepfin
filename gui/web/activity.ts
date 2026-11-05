// Activity centre state for the header bell and panel. Separate from the download store on purpose:
// entries change rarely, and nothing here is subscribed by a download row, so progress ticks never reach it.
import {useSyncExternalStore} from 'react'
import {awayMessage, unreadCount, type ActivityEntry, type ActivityList} from '../shared/activity'
import type {PushEvent} from '../shared/contracts'
import {native, onPush, request} from './bridge'
import {toast} from './toasts'

type State = {entries: ActivityEntry[]; unread: number; open: boolean}
let state: State = {entries: [], unread: 0, open: false}
const listeners = new Set<() => void>()
const set = (change: Partial<State>) => {
  state = {...state, ...change}
  if (change.entries) state.unread = unreadCount(state.entries)
  listeners.forEach((listener) => listener())
}
const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export const useActivityEntries = () => useSyncExternalStore(subscribe, () => state.entries)
export const useUnread = () => useSyncExternalStore(subscribe, () => state.unread)
export const useActivityOpen = () => useSyncExternalStore(subscribe, () => state.open)
export const getActivityEntries = () => state.entries

export const openActivity = () => set({open: true})
export const closeActivity = () => set({open: false})
export const toggleActivity = () => set({open: !state.open})

function apply(list: ActivityList | undefined) {
  if (list && Array.isArray(list.entries)) set({entries: list.entries})
}

/** Marks entries read in the main process; the push (or the returned list) updates the view. */
export async function markRead(ids: string[] | 'all') {
  if (ids !== 'all' && !ids.length) return
  try {
    apply(await request<ActivityList>({op: 'activityRead', read: ids}))
  } catch {
    /* Reading is best effort; the next push corrects the view. */
  }
}

export async function removeEntry(id: string) {
  try {
    apply(await request<ActivityList>({op: 'activityRemove', id}))
  } catch {
    /* The next push corrects the view. */
  }
}

let started = false
/** Loads the list, follows pushes, and says once per start how much happened while the app was closed. */
export function startActivity() {
  if (started || !native) return
  started = true
  onPush((event: PushEvent) => {
    if (event.type === 'activity') apply(event.activity)
  })
  request<ActivityList>({op: 'activityList'})
    .then((list) => {
      apply(list)
      if (list?.away > 0)
        toast({
          tone: 'info',
          message: awayMessage(list.away),
          key: 'activity-away',
          duration: 10_000,
          action: {label: 'Show', run: openActivity},
        })
    })
    .catch(() => {})
}
