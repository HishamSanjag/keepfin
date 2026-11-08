// Keepfin's own sign-in window. It opens the site's own login page in a window of its own, on its own storage partition,
// and lets the site's page do the whole login (including pages of other companies it redirects to). Keepfin never sees a
// password: it only reads, once the window is closed, the site's cookies from that partition.
//
// The window gets no Keepfin code and no Keepfin IPC: sandboxed, context isolation on, no Node, no preload. Pop-ups the
// login page opens (single sign-on) stay on the same partition with the same restrictions.
//
// Only main.ts imports Electron (scripts/check/check-boundaries.mjs), so the pieces used here arrive as `parts`, typed by
// the small structural shapes below.
import {siteByKey, signedIn, isGoogleSignIn, type SignInSite} from '../../shared/core/signin.js'
import type {SaveOutcome, SignInStore, StoredCookie} from './store.js'

export const SIGN_IN_PARTITION = 'persist:keepfin-signin'

/** `blockedByGoogle`: the window reached the Google sign-in page, which refuses app windows, and was closed (ops.ts tells the person). */
export type SignInResult = {signedIn: boolean; outcome: SaveOutcome | 'cancelled' | 'blockedByGoogle'}

type ElectronCookie = {name: string; value: string; domain?: string; path?: string; secure?: boolean; httpOnly?: boolean; session?: boolean; expirationDate?: number}
type Contents = {
  setWindowOpenHandler(handler: (details: {url: string}) => unknown): void
  on(event: string, listener: (...args: any[]) => void): unknown
}
type Win = {
  webContents: Contents
  on(event: string, listener: (...args: any[]) => void): unknown
  loadURL(url: string): Promise<void>
  close(): void
  isDestroyed(): boolean
}
export type SignInSession = {
  cookies: {
    get(filter: {domain?: string}): Promise<ElectronCookie[]>
    remove(url: string, name: string): Promise<void>
    flushStore(): Promise<void>
    on(event: 'changed', listener: () => void): unknown
    removeListener(event: 'changed', listener: () => void): unknown
  }
  setPermissionRequestHandler(handler: (contents: unknown, permission: string, callback: (allow: boolean) => void) => void): void
  setPermissionCheckHandler(handler: () => boolean): void
}
/** What main.ts hands over: Electron's `BrowserWindow` class and `session` module, plus the main window the sign-in window belongs to. */
export type ElectronParts = {
  BrowserWindow: new (options: Record<string, unknown>) => Win
  session: {fromPartition(name: string): SignInSession}
  parent: () => unknown
}

const secureWeb = {sandbox: true, contextIsolation: true, nodeIntegration: false, webSecurity: true, partition: SIGN_IN_PARTITION} as const

const isWeb = (url: string) => {
  try {
    const protocol = new URL(url).protocol
    return protocol === 'https:' || protocol === 'http:'
  } catch {
    return false
  }
}

/** Applies the window rules to a page and, recursively, to the pop-ups it opens. */
function harden(contents: Contents, onGoogle: () => void) {
  contents.setWindowOpenHandler(({url}) =>
    isWeb(url) ? {action: 'allow', overrideBrowserWindowOptions: {width: 480, height: 680, autoHideMenuBar: true, webPreferences: {...secureWeb}}} : {action: 'deny'},
  )
  contents.on('did-create-window', (child: {webContents: Contents}) => harden(child.webContents, onGoogle))
  // The Google sign-in page refuses app windows. Watching the address needs nothing inside the site's page (the window has no preload).
  const watch = (event: {url?: string; isMainFrame?: boolean}, url?: unknown) => {
    const target = typeof url === 'string' ? url : event.url
    if (event.isMainFrame !== false && typeof target === 'string' && isGoogleSignIn(target)) onGoogle()
  }
  for (const name of ['did-start-navigation', 'did-redirect-navigation', 'did-navigate']) contents.on(name, watch)
  const onlyWeb = (event: {preventDefault(): void}, url: string) => {
    if (!isWeb(url)) event.preventDefault()
  }
  contents.on('will-navigate', onlyWeb)
  contents.on('will-redirect', onlyWeb)
}

/** The cookies of a site in the sign-in partition, as the store keeps them. */
async function readSiteCookies(ses: SignInSession, site: SignInSite): Promise<StoredCookie[]> {
  const found = new Map<string, ElectronCookie>()
  for (const domain of site.domains) {
    for (const cookie of await ses.cookies.get({domain: domain.replace(/^\./, '')})) found.set(`${cookie.domain}|${cookie.path}|${cookie.name}`, cookie)
  }
  return [...found.values()].map((c) => ({
    name: c.name,
    value: c.value,
    domain: c.domain ?? '',
    path: c.path ?? '/',
    secure: !!c.secure,
    httpOnly: !!c.httpOnly,
    expiry: c.session || !c.expirationDate ? 0 : Math.floor(c.expirationDate),
  }))
}

const now = () => Math.floor(Date.now() / 1000)

export type SignInWindows = {
  /** Opens the sign-in window for a site key and resolves when it is closed, after keeping the sign-in if there is one. */
  open(siteKey: string): Promise<SignInResult>
  /** Clears the site's cookies from the sign-in partition (the store removes its own copy). */
  clear(siteKey: string): Promise<void>
}

export function signInWindows(parts: ElectronParts, store: SignInStore): SignInWindows {
  const partition = () => {
    const ses = parts.session.fromPartition(SIGN_IN_PARTITION)
    // A login page asks for nothing it needs: no camera, microphone, location or notifications.
    ses.setPermissionRequestHandler((_contents, _permission, callback) => callback(false))
    ses.setPermissionCheckHandler(() => false)
    return ses
  }
  return {
    async clear(siteKey) {
      const site = siteByKey(siteKey)
      if (!site) throw new Error('Unknown website.')
      const ses = partition()
      for (const cookie of await readSiteCookies(ses, site)) {
        const host = cookie.domain.replace(/^\./, '')
        await ses.cookies.remove(`http${cookie.secure ? 's' : ''}://${host}${cookie.path}`, cookie.name).catch(() => {})
      }
      await ses.cookies.flushStore()
    },
    open(siteKey) {
      const site = siteByKey(siteKey)
      if (!site) return Promise.reject(new Error('Unknown website.'))
      const ses = partition()
      return new Promise((resolve) => {
        const win = new parts.BrowserWindow({
          width: 520,
          height: 740,
          title: `Sign in to ${site.name}`,
          parent: parts.parent() ?? undefined,
          autoHideMenuBar: true,
          show: true,
          webPreferences: {...secureWeb},
        })
        let blocked = false
        harden(win.webContents, () => {
          if (blocked || win.isDestroyed()) return
          blocked = true
          win.close()
        })
        // The title stays Keepfin's: a login page cannot rename the window that says whose sign-in this is.
        win.on('page-title-updated', (event: {preventDefault(): void}) => event.preventDefault())
        let autoClose: ReturnType<typeof setTimeout> | undefined
        let signedAtOpen = false
        const live = async () => signedIn(site.key, await readSiteCookies(ses, site), now())
        // When the site's session cookie shows up, the person has signed in: close shortly after, so the page can finish its own steps first.
        const onChange = () => {
          if (signedAtOpen || autoClose) return
          void live().then((ok) => {
            if (ok && !autoClose) autoClose = setTimeout(() => !win.isDestroyed() && win.close(), 2500)
          })
        }
        ses.cookies.on('changed', onChange)
        win.on('closed', () => {
          if (autoClose) clearTimeout(autoClose)
          ses.cookies.removeListener('changed', onChange)
          void (async () => {
            try {
              if (blocked) return resolve({signedIn: false, outcome: 'blockedByGoogle'})
              await ses.cookies.flushStore()
              const outcome = await store.save(site.key, await readSiteCookies(ses, site))
              resolve({signedIn: outcome === 'saved', outcome})
            } catch {
              resolve({signedIn: false, outcome: 'cancelled'})
            }
          })()
        })
        void live()
          .then((ok) => {
            signedAtOpen = ok
          })
          .finally(() => void win.loadURL(site.loginUrl).catch(() => {}))
      })
    },
  }
}
