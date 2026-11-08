// Keepfin's own website sign-ins as the renderer sees them: which sites are signed in, never any cookie data.
// The sign-in itself happens in a window of its own, opened by the main process.
import {useEffect, useSyncExternalStore} from 'react'
import type {SignInOutcome, SignInState} from '../shared/contracts'
import {messageOf, native, request} from './bridge'

let state: SignInState | undefined
const listeners = new Set<() => void>()
const set = (next: SignInState) => {
  state = next
  listeners.forEach((listener) => listener())
}

async function load() {
  try {
    set(await request<SignInState>({op: 'signInState'}))
  } catch {
    /* the list stays empty */
  }
}

/** The sites Keepfin can sign in to and which are signed in (undefined until the first answer, and in the browser preview). */
export function useSignIns(): SignInState | undefined {
  useEffect(() => {
    if (native && !state) void load()
  }, [])
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => state,
  )
}

/** Opens the sign-in window for a site (a core site key). Resolves when the person closes it; `signedIn` says whether it worked. */
export async function signInTo(site: string): Promise<SignInOutcome> {
  try {
    const outcome = await request<SignInOutcome>({op: 'signInOpen', site})
    set(outcome.state)
    return outcome
  } catch (error) {
    return {site, signedIn: false, message: messageOf(error), state: state ?? {canKeep: true, sites: []}}
  }
}

export async function signOutOf(site: string): Promise<void> {
  set(await request<SignInState>({op: 'signInRemove', site}))
}
