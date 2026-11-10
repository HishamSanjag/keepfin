// Followed artists and their releases, for Home card and the Follow actions. A small external store: the main process pushes {type: 'following'}
// and every answer to a follow action carries the new state.
import {useEffect, useSyncExternalStore} from 'react'
import {request, messageOf, native} from '../bridge'
import {toast} from '../toasts'
import {artistKey} from '../../shared/core/home'
import {noFollowing, type FollowingState} from '../../shared/following'

let state: FollowingState = noFollowing
let loaded = false
let loading = false
const listeners = new Set<() => void>()

export const getFollowing = () => (void load(), state)

export function setFollowing(next: FollowingState) {
  state = next
  loaded = true
  listeners.forEach((listener) => listener())
}

async function load() {
  if (!native || loaded || loading) return
  loading = true
  try {
    setFollowing(await request<FollowingState>({op: 'followingState'}))
  } catch {
    loaded = true
  } finally {
    loading = false
  }
}

const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => void listeners.delete(listener)
}

/** The followed artists and releases; the first user loads them. */
export function useFollowing(): FollowingState {
  useEffect(() => void load(), [])
  return useSyncExternalStore(subscribe, () => state)
}

export const isFollowing = (current: FollowingState, name: string | undefined) => {
  const key = artistKey(name)
  return !!key && current.artists.some((artist) => artist.key === key)
}

export async function followArtist(name: string) {
  try {
    setFollowing(await request<FollowingState>({op: 'followArtist', text: name}))
    toast({tone: 'success', message: `Following ${name}. Keepfin looks for new releases and tells you on Home.`})
  } catch (error) {
    toast({message: messageOf(error), tone: 'error'})
  }
}

export async function unfollowArtist(id: string, name: string) {
  try {
    setFollowing(await request<FollowingState>({op: 'unfollowArtist', id}))
    toast({tone: 'info', message: `Stopped following ${name}.`})
  } catch (error) {
    toast({message: messageOf(error), tone: 'error'})
  }
}

/** Checks one artist, or every followed artist, now. */
export async function checkFollowing(id?: string) {
  try {
    setFollowing(await request<FollowingState>({op: 'followingCheck', id}))
  } catch (error) {
    toast({message: messageOf(error), tone: 'error'})
  }
}

/** The x on Home card and Mark all seen: the releases listed now count as seen, so the card returns only for newer ones. */
export async function markReleasesSeen() {
  try {
    setFollowing(await request<FollowingState>({op: 'followingSeen'}))
  } catch (error) {
    toast({message: messageOf(error), tone: 'error'})
  }
}

/** "Keep it": downloads the release with the person's defaults. */
export async function keepRelease(id: string, title: string) {
  try {
    const answer = await request<{queued: number; state: FollowingState}>({op: 'keepRelease', id})
    setFollowing(answer.state)
    toast({tone: 'success', message: `Keeping ${title}. It is in Downloads.`})
  } catch (error) {
    toast({message: messageOf(error), tone: 'error'})
  }
}

/** The menu entry of an artist page or a song: follow, or unfollow when already followed. */
export function followMenuItem(current: FollowingState, name: string | undefined) {
  const found = (() => {
    const key = artistKey(name)
    return key ? current.artists.find((artist) => artist.key === key) : undefined
  })()
  if (!name || !artistKey(name)) return {label: 'Follow artist', icon: 'user', disabled: true, detail: 'No artist tag', onSelect: () => {}}
  return found
    ? {label: `Unfollow ${found.name}`, icon: 'user', onSelect: () => void unfollowArtist(found.id, found.name)}
    : {label: `Follow ${name}`, icon: 'user', onSelect: () => void followArtist(name)}
}
