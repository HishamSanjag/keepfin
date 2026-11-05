// Shared app-update state for the header indicator and the post-update "What's new" dialog.
import {useSyncExternalStore} from 'react'
import type {Operation} from '../shared/contracts'
import {compareReleases, type UpdateChannel, type UpdateState} from '../shared/updates'
import {messageOf, native, request} from './bridge'
import {readPreference, savePreference} from './prefs'

export type UpdateSnapshot = {
  state?: UpdateState
  error?: string
  pending: boolean
  whatsNew?: {version: string; notes: string}
  /** Highest version the user chose "Skip this version" for. Nothing is shown for it or anything older. */
  skipped?: string
  /** Highest version whose header pill the user closed. */
  hidden?: string
  /** First-run "Meet your library" is open; the update dialog waits for it. */
  introOpen: boolean
}

// Dismissals belong to a channel: skipping a version under Stable must not hide a newly applicable one under Beta.
// Stable keeps the original keys, so earlier choices carry over.
const skippedKey = (channel: UpdateChannel) => (channel === 'beta' ? 'keepfin-update-skipped-beta' : 'keepfin-update-skipped')
const hiddenKey = (channel: UpdateChannel) => (channel === 'beta' ? 'keepfin-update-hidden-beta' : 'keepfin-update-hidden')
const text = (value: unknown) => typeof value === 'string'
const dismissed = (channel: UpdateChannel) => ({
  skipped: readPreference<string | undefined>(skippedKey(channel), undefined, text),
  hidden: readPreference<string | undefined>(hiddenKey(channel), undefined, text),
})
let channelNow: UpdateChannel = 'stable'
let snapshot: UpdateSnapshot = {pending: false, introOpen: false, ...dismissed('stable')}
const listeners = new Set<() => void>()
const set = (change: Partial<UpdateSnapshot>) => {
  if (change.state && change.state.channel !== channelNow) {
    channelNow = change.state.channel === 'beta' ? 'beta' : 'stable'
    change = {...change, ...dismissed(channelNow)}
  }
  snapshot = {...snapshot, ...change}
  listeners.forEach((listener) => listener())
}
export const useUpdates = () =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    () => snapshot,
  )

const lastSeenKey = 'keepfin-last-seen-version'
const sixHours = 6 * 3_600_000

/** Semver comparison; a beta sorts before its final release. */
export const compareVersions = compareReleases

/** True when `version` is at or below the stored version, so a newer release asks again. */
export const covered = (version: string | undefined, stored: string | undefined) =>
  !!version && !!stored && compareVersions(version, stored) <= 0

/** "Skip this version": no dialog and no header pill for it; a newer version asks again. */
export function skipVersion(version: string) {
  savePreference(skippedKey(channelNow), version)
  set({skipped: version})
}
/** The pill's close button: hides the header pill for this version only. */
export function hidePill(version: string) {
  savePreference(hiddenKey(channelNow), version)
  set({hidden: version})
}
export const getUpdates = () => snapshot
export const setIntroOpen = (introOpen: boolean) => {
  if (snapshot.introOpen !== introOpen) set({introOpen})
}

export async function runUpdate(op: Operation) {
  set({pending: true, error: undefined})
  try {
    const next = await request<UpdateState>({op})
    if (next && typeof next === 'object') set({state: next})
    return next
  } catch (error) {
    set({error: messageOf(error)})
  } finally {
    set({pending: false})
  }
}

export async function loadNotes() {
  const next = await runUpdate('updateNotes')
  return next?.notes || ''
}

export function dismissWhatsNew() {
  set({whatsNew: undefined})
}
export async function openWhatsNew() {
  const state = snapshot.state
  if (!state) return
  const notes = await loadNotes()
  const latest = snapshot.state
  set({whatsNew: {version: latest?.notesVersion || state.current, notes}})
}

async function checkWhatsNew(state: UpdateState) {
  let seen: string | null = null
  try {
    seen = localStorage.getItem(lastSeenKey)
    localStorage.setItem(lastSeenKey, state.current)
  } catch {}
  // Fresh installs (nothing stored) and downgrades never show a dialog.
  if (!seen || compareVersions(state.current, seen) <= 0) return
  const next = await request<UpdateState>({op: 'updateNotes'}).catch(() => undefined)
  if (!next?.notes?.trim()) return
  // Only show notes that belong to the version now running.
  if (next.notesVersion && compareVersions(next.notesVersion, state.current) !== 0) return
  set({state: next, whatsNew: {version: state.current, notes: next.notes}})
}

let started = false
export function startUpdates() {
  if (started || !native) return
  started = true
  let timer: ReturnType<typeof setTimeout>
  const poll = async () => {
    try {
      const next = await request<UpdateState>({op: 'updateState'})
      if (next && typeof next === 'object') set({state: next})
    } catch {}
    const phase = snapshot.state?.phase
    timer = setTimeout(poll, phase === 'checking' || phase === 'downloading' ? 800 : 5000)
  }
  void (async () => {
    try {
      const first = await request<UpdateState>({op: 'updateState'})
      set({state: first})
      const checkable = first.mode === 'installed' || first.mode === 'portable' || first.mode === 'msi'
      if (checkable && first.phase !== 'checking' && (!first.checkedAt || Date.now() - first.checkedAt > sixHours))
        void request({op: 'updateCheck'}).catch(() => {})
      await checkWhatsNew(first)
    } catch (error) {
      set({error: messageOf(error)})
    }
    timer = setTimeout(poll, 3000)
  })()
  return () => clearTimeout(timer)
}
