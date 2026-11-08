// Keepfin's own sign-ins on this PC: the cookies of a site the person signed in to inside Keepfin's sign-in window.
// They are kept one file per site, encrypted with the operating system (Electron safeStorage; on Windows that is DPAPI,
// bound to this Windows user), and are decrypted only for the length of one engine run into a private temporary
// cookies file that is deleted afterwards. Nothing here ever reaches the renderer: it only learns which sites are signed in.
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {allSites, signedIn, siteByKey} from '../../shared/core/signin.js'
import type {SignInChoice, SignInFinder} from './auto.js'

/** One cookie of a site, as the sign-in window's session lists it. `expiry` is Unix seconds, 0 for a session cookie. */
export type StoredCookie = {name: string; value: string; domain: string; path: string; secure: boolean; httpOnly: boolean; expiry: number}

/** The operating system's encryption. `available` is false where it is not (no password store): then nothing is saved. */
export type Vault = {available(): boolean; encrypt(text: string): Buffer; decrypt(data: Buffer): string}

export type SaveOutcome = 'saved' | 'notSignedIn' | 'unavailable'

export type SignInStoreDeps = {dir: string; vault: Vault; tempDir?: string; now?: () => number}

const FILE_VERSION = 1
/** Engines read an expiry of 0 as already expired, so a session cookie is written with this much life for the one run. */
const SESSION_LIFE_SECONDS = 24 * 3600
const TEMP_PREFIX = 'keepfin-cookie-run-'
const KEY = /^[a-z0-9]+$/

const inDomain = (host: string, domain: string) => host === domain || host.endsWith(`.${domain}`)

/** The cookies as a Netscape cookies file, the format both engines read. Control characters never reach the file. */
export function netscapeText(cookies: StoredCookie[], nowSeconds: number): string {
  const clean = (value: string) => value.replace(/[\t\r\n\0]/g, '')
  const lines = ['# Netscape HTTP Cookie File']
  for (const c of cookies) {
    const expiry = c.expiry > 0 ? c.expiry : nowSeconds + SESSION_LIFE_SECONDS
    const domain = clean(c.domain)
    lines.push(
      [`${c.httpOnly ? '#HttpOnly_' : ''}${domain}`, domain.startsWith('.') ? 'TRUE' : 'FALSE', clean(c.path || '/'), c.secure ? 'TRUE' : 'FALSE', String(Math.floor(expiry)), clean(c.name), clean(c.value)].join('\t'),
    )
  }
  return `${lines.join('\n')}\n`
}

export class SignInStore {
  private readonly dir: string
  private readonly vault: Vault
  private readonly tempDir: string
  private readonly now: () => number

  constructor(deps: SignInStoreDeps) {
    this.dir = deps.dir
    this.vault = deps.vault
    this.tempDir = deps.tempDir ?? os.tmpdir()
    this.now = deps.now ?? (() => Math.floor(Date.now() / 1000))
  }

  private file(siteKey: string) {
    if (!KEY.test(siteKey) || !siteByKey(siteKey)) throw new Error('Unknown website.')
    return path.join(this.dir, `${siteKey}.cookies`)
  }

  /** Whether a sign-in can be kept at all on this PC. */
  get canKeep() {
    return this.vault.available()
  }

  /** Keeps the cookies if they hold a live sign-in for the site; otherwise keeps nothing (and leaves any earlier sign-in alone). */
  async save(siteKey: string, cookies: StoredCookie[]): Promise<SaveOutcome> {
    const file = this.file(siteKey)
    if (!signedIn(siteKey, cookies, this.now())) return 'notSignedIn'
    if (!this.vault.available()) return 'unavailable'
    const site = siteByKey(siteKey)!
    const own = cookies.filter((c) => site.domains.some((d) => inDomain(c.domain.replace(/^\./, ''), d.replace(/^\./, ''))))
    await fs.mkdir(this.dir, {recursive: true})
    const temp = `${file}.tmp`
    await fs.writeFile(temp, this.vault.encrypt(JSON.stringify({v: FILE_VERSION, cookies: own})), {mode: 0o600})
    await fs.rename(temp, file)
    return 'saved'
  }

  private async load(siteKey: string): Promise<StoredCookie[] | undefined> {
    try {
      const data = await fs.readFile(this.file(siteKey))
      if (!this.vault.available()) return undefined
      const parsed = JSON.parse(this.vault.decrypt(data)) as {v?: number; cookies?: StoredCookie[]}
      if (parsed.v !== FILE_VERSION || !Array.isArray(parsed.cookies)) return undefined
      return parsed.cookies.filter((c) => c && typeof c.name === 'string' && typeof c.value === 'string' && typeof c.domain === 'string')
    } catch {
      return undefined
    }
  }

  /** The keys of the sites with a live sign-in kept (an expired or unreadable one does not count). */
  async signedSites(): Promise<string[]> {
    const live: string[] = []
    for (const site of allSites()) {
      const cookies = await this.load(site.key)
      if (cookies && signedIn(site.key, cookies, this.now())) live.push(site.key)
    }
    return live
  }

  async remove(siteKey: string): Promise<void> {
    await fs.rm(this.file(siteKey), {force: true})
  }

  /** A private cookies file for the site that `release` deletes; undefined when there is no live sign-in. */
  async lease(siteKey: string): Promise<{file: string; release(): Promise<void>} | undefined> {
    const cookies = await this.load(siteKey)
    if (!cookies || !signedIn(siteKey, cookies, this.now())) return undefined
    const folder = await fs.mkdtemp(path.join(this.tempDir, TEMP_PREFIX))
    const file = path.join(folder, 'cookies.txt')
    try {
      await fs.writeFile(file, netscapeText(cookies, this.now()), {mode: 0o600})
    } catch (error) {
      await fs.rm(folder, {recursive: true, force: true})
      throw error
    }
    return {file, release: () => fs.rm(folder, {recursive: true, force: true})}
  }

  /** Deletes temporary cookie files an earlier run left behind (a crash between writing and deleting). Call once at start. */
  async sweepTemp(): Promise<void> {
    try {
      for (const name of await fs.readdir(this.tempDir)) if (name.startsWith(TEMP_PREFIX)) await fs.rm(path.join(this.tempDir, name), {recursive: true, force: true})
    } catch {
      /* the temp folder is not ours to worry about */
    }
  }

  /** The sign-in finder: Keepfin's own sign-in for the site (when there is one), then whatever `others` finds (browsers). */
  finder(others: SignInFinder): SignInFinder {
    return async (siteKey) => {
      const own: SignInChoice[] = (await this.signedSites()).includes(siteKey)
        ? [
            {
              source: {kind: 'keepfin'},
              cookies: {},
              open: async () => {
                const lease = await this.lease(siteKey)
                if (!lease) throw new Error('The sign-in is no longer available.')
                return {cookies: {file: lease.file}, release: lease.release}
              },
            },
          ]
        : []
      return [...own, ...(await others(siteKey))]
    }
  }
}
