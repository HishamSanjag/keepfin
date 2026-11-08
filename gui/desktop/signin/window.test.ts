import assert from 'node:assert/strict'
import {EventEmitter} from 'node:events'
import {test} from 'node:test'
import {googleBlockedMessage, isGoogleSignIn} from '../../shared/core/signin.js'
import {handleSignIn} from './ops.js'
import {signInWindows, type ElectronParts, type SignInSession} from './window.js'
import type {SignInStore} from './store.js'

test('only Google sign-in pages are detected, also as the target of another site login', () => {
  assert.equal(isGoogleSignIn('https://accounts.google.com/ServiceLogin?service=youtube'), true)
  assert.equal(isGoogleSignIn('https://accounts.google.com/v3/signin/identifier?continue=https%3A%2F%2Fwww.youtube.com%2F'), true)
  assert.equal(isGoogleSignIn('https://www.youtube.com/'), false)
  assert.equal(isGoogleSignIn('https://www.google.com/search?q=a'), false)
  assert.equal(isGoogleSignIn('https://appleid.apple.com/auth/authorize'), false, 'other single sign-on pages are not affected')
  assert.equal(isGoogleSignIn('https://accounts.google.com.evil.test/signin'), false)
  assert.equal(isGoogleSignIn('not a url'), false)
})

function harness() {
  const contents = new EventEmitter() as EventEmitter & {setWindowOpenHandler(): void}
  contents.setWindowOpenHandler = () => {}
  const win = new EventEmitter() as EventEmitter & Record<string, unknown>
  let closes = 0
  let destroyed = false
  win.webContents = contents
  win.loadURL = async () => {}
  win.isDestroyed = () => destroyed
  win.close = () => {
    closes += 1
    destroyed = true
    win.emit('closed')
  }
  const cookies = Object.assign(new EventEmitter(), {
    get: async () => [],
    remove: async () => {},
    flushStore: async () => {},
    removeListener(this: EventEmitter, event: string, listener: () => void) {
      return EventEmitter.prototype.removeListener.call(this, event, listener)
    },
  })
  const ses = {cookies, setPermissionRequestHandler() {}, setPermissionCheckHandler() {}} as unknown as SignInSession
  const parts: ElectronParts = {BrowserWindow: function () { return win } as unknown as ElectronParts['BrowserWindow'], session: {fromPartition: () => ses}, parent: () => null}
  let saved = 0
  const store = {canKeep: true, save: async () => ((saved += 1), 'notSignedIn' as const), signedSites: async () => []} as unknown as SignInStore
  return {contents, win, windows: signInWindows(parts, store), closes: () => closes, saved: () => saved, store}
}

const tick = () => new Promise((resolve) => setImmediate(resolve))

test('a window that reaches Google sign-in is closed and reported as blocked, saving nothing', async () => {
  const h = harness()
  const done = h.windows.open('youtube')
  await tick()
  h.contents.emit('did-start-navigation', {url: 'https://accounts.google.com/ServiceLogin?service=youtube', isMainFrame: true})
  assert.deepEqual(await done, {signedIn: false, outcome: 'blockedByGoogle'})
  assert.equal(h.closes(), 1)
  assert.equal(h.saved(), 0)
})

test('a site login that redirects to Google is caught too (positional event arguments as well)', async () => {
  const h = harness()
  const done = h.windows.open('instagram')
  await tick()
  h.contents.emit('did-redirect-navigation', {}, 'https://accounts.google.com/o/oauth2/auth')
  assert.equal((await done).outcome, 'blockedByGoogle')
})

test('other pages, other single sign-on pages and sub-frames do not close the window', async () => {
  const h = harness()
  const done = h.windows.open('instagram')
  await tick()
  h.contents.emit('did-start-navigation', {url: 'https://www.instagram.com/accounts/login/', isMainFrame: true})
  h.contents.emit('did-redirect-navigation', {url: 'https://appleid.apple.com/auth/authorize', isMainFrame: true})
  h.contents.emit('did-start-navigation', {url: 'https://accounts.google.com/gsi/button', isMainFrame: false})
  assert.equal(h.closes(), 0)
  ;(h.win.close as () => void)()
  assert.equal((await done).outcome, 'notSignedIn')
})

test('the person is told in the main window, in plain words', async () => {
  const h = harness()
  const request = handleSignIn('signInOpen', {id: 'x', op: 'signInOpen', site: 'youtube'} as never, {store: h.store, open: async () => ({signedIn: false, outcome: 'blockedByGoogle'}), clear: async () => {}})
  const outcome = await request
  assert.equal('message' in outcome && outcome.message, "Google doesn't allow signing in from this window. Sign in to YouTube in Firefox, and Keepfin will use that sign-in automatically.")
  assert.equal(googleBlockedMessage(), 'message' in outcome ? outcome.message : '')
  const state = await handleSignIn('signInState', {id: 'y', op: 'signInState'} as never, {store: h.store, open: async () => ({signedIn: false, outcome: 'cancelled'}), clear: async () => {}})
  assert.equal('sites' in state && state.sites.find((s) => s.key === 'youtube')?.hint, 'Needs Firefox: Google blocks sign-in here')
})
