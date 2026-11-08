// The three requests of Keepfin's own sign-in: its state, the sign-in window for one site, and signing out of one.
// Electron's window and session arrive as dependencies so the rules (which sites, what is said) are tested without Electron.
import {allSites, siteByKey} from '../../shared/core/signin.js'
import type {Request, SignInOutcome, SignInState} from '../../shared/contracts.js'
import type {SignInStore} from './store.js'
import type {SignInResult} from './window.js'

export type SignInOpsDeps = {
  store: SignInStore
  /** Opens the sign-in window for a site key; resolves when the window is closed. */
  open: (siteKey: string) => Promise<SignInResult>
  /** Clears the sign-in window's own stored cookies for a site key. */
  clear: (siteKey: string) => Promise<void>
}

const NOT_KEPT = 'This PC cannot keep a sign-in safely (no password store is available), so Keepfin cannot save one.'

function siteKeyOf(request: Request): string {
  const key = request.site
  if (typeof key !== 'string' || !siteByKey(key)) throw new Error('Unknown website.')
  return key
}

export async function signInState(store: SignInStore): Promise<SignInState> {
  const signed = new Set(await store.signedSites())
  return {canKeep: store.canKeep, sites: allSites().map((s) => ({key: s.key, name: s.name, signedIn: signed.has(s.key)}))}
}

/** What to tell the person when the window closed without a sign-in being kept. */
function messageFor(site: string, result: SignInResult): string | undefined {
  if (result.signedIn) return undefined
  if (result.outcome === 'unavailable') return NOT_KEPT
  if (result.outcome === 'notSignedIn') return `Keepfin is not signed in to ${site}. The window was closed before the sign-in finished.`
  return `Keepfin could not finish signing in to ${site}.`
}

export async function handleSignIn(op: 'signInState' | 'signInOpen' | 'signInRemove', request: Request, deps: SignInOpsDeps): Promise<SignInState | SignInOutcome> {
  if (op === 'signInState') return signInState(deps.store)
  const key = siteKeyOf(request)
  const site = siteByKey(key)!
  if (op === 'signInRemove') {
    await deps.store.remove(key)
    await deps.clear(key)
    return signInState(deps.store)
  }
  if (!deps.store.canKeep) return {site: key, signedIn: false, message: NOT_KEPT, state: await signInState(deps.store)}
  const result = await deps.open(key)
  return {site: key, signedIn: result.signedIn, message: messageFor(site.name, result), state: await signInState(deps.store)}
}
