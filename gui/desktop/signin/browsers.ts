// Finds the sign-ins the user already has in a browser on this PC, for the site of a link. Only names, domains and
// expiry are read, never a cookie value; the shared core decides whether they hold a live sign-in.
//
// Firefox keeps its cookies in an ordinary database that can be read from a copy. Chrome, Edge, Brave, Opera, Vivaldi
// and Chromium encrypt theirs so that only the browser itself can open them (app-bound encryption since 2024), so they
// are only detected: the caller tells the user to sign in inside Keepfin instead.
import {DatabaseSync} from 'node:sqlite'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {signedIn, siteByKey, type SignInCookie, type SignInSource} from '../../shared/core/signin.js'

export type BrowserSource = Extract<SignInSource, {kind: 'browser'}> & {profile: string}
export type BrowserSignIns = {sources: BrowserSource[]; /** Installed browsers whose sign-ins Keepfin cannot read (engine browser ids). */ locked: string[]}

export type BrowserDeps = {
  appData: string
  localAppData: string
  tempDir: string
  nowSeconds: () => number
  fs: {
    readText(file: string): Promise<string>
    isFile(file: string): Promise<boolean>
    isDir(dir: string): Promise<boolean>
    makeTemp(parent: string): Promise<string>
    copy(from: string, to: string): Promise<void>
    remove(dir: string): Promise<void>
  }
  /** The session cookies (names, domains, expiry) of `domains` in a Firefox cookie database. */
  readCookies(database: string, domains: string[], names: string[]): Promise<SignInCookie[]>
}

const win = path.win32

/** Chromium-family browsers and where each keeps its `Local State` file (relative to %LOCALAPPDATA%, or %APPDATA% for Opera). */
const lockedBrowsers: {id: string; root: 'local' | 'roaming'; state: string}[] = [
  {id: 'chrome', root: 'local', state: 'Google\\Chrome\\User Data\\Local State'},
  {id: 'edge', root: 'local', state: 'Microsoft\\Edge\\User Data\\Local State'},
  {id: 'brave', root: 'local', state: 'BraveSoftware\\Brave-Browser\\User Data\\Local State'},
  {id: 'opera', root: 'roaming', state: 'Opera Software\\Opera Stable\\Local State'},
  {id: 'vivaldi', root: 'local', state: 'Vivaldi\\User Data\\Local State'},
  {id: 'chromium', root: 'local', state: 'Chromium\\User Data\\Local State'},
]

export function defaultDeps(): BrowserDeps {
  return {
    appData: process.env.APPDATA ?? win.join(os.homedir(), 'AppData', 'Roaming'),
    localAppData: process.env.LOCALAPPDATA ?? win.join(os.homedir(), 'AppData', 'Local'),
    tempDir: os.tmpdir(),
    nowSeconds: () => Math.floor(Date.now() / 1000),
    fs: {
      readText: (file) => fs.readFile(file, 'utf8'),
      isFile: (file) => fs.stat(file).then((s) => s.isFile(), () => false),
      isDir: (dir) => fs.stat(dir).then((s) => s.isDirectory(), () => false),
      makeTemp: (parent) => fs.mkdtemp(path.join(parent, 'keepfin-cookies-')),
      copy: (from, to) => fs.copyFile(from, to),
      remove: (dir) => fs.rm(dir, {recursive: true, force: true}),
    },
    readCookies: async (database, domains, names) => {
      const db = new DatabaseSync(database)
      try {
        const hosts = domains.flatMap((d) => [d, `.${d}`])
        const sql =
          `SELECT name, host, expiry FROM moz_cookies WHERE name IN (${names.map(() => '?').join(',')}) ` +
          `AND (host IN (${hosts.map(() => '?').join(',')}) OR ${domains.map(() => 'host LIKE ?').join(' OR ')})`
        const rows = db.prepare(sql).all(...names, ...hosts, ...domains.map((d) => `%.${d}`)) as {name: string; host: string; expiry: number}[]
        // Seconds in every Firefox so far; a millisecond value is read as such rather than as the far future.
        return rows.map((r) => ({name: r.name, domain: r.host, expiry: r.expiry > 1e11 ? Math.floor(r.expiry / 1000) : r.expiry}))
      } finally {
        db.close()
      }
    },
  }
}

type IniProfile = {path: string; relative: boolean; name: string}

/** profiles.ini: the install's default profile first (`[Install…] Default=`), then the rest in file order. */
export function parseProfilesIni(text: string, root: string): {path: string; name: string}[] {
  const profiles: IniProfile[] = []
  const installDefaults: string[] = []
  let kind: 'profile' | 'install' | undefined
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    const header = /^\[(.+)\]$/.exec(line)
    if (header) {
      kind = header[1].startsWith('Profile') ? 'profile' : header[1].startsWith('Install') ? 'install' : undefined
      if (kind === 'profile') profiles.push({path: '', relative: true, name: ''})
      continue
    }
    const eq = line.indexOf('=')
    if (!kind || eq < 1) continue
    const key = line.slice(0, eq)
    const value = line.slice(eq + 1)
    if (kind === 'install' && key === 'Default') installDefaults.push(value)
    if (kind === 'profile') {
      const current = profiles[profiles.length - 1]
      if (key === 'Path') current.path = value
      else if (key === 'IsRelative') current.relative = value !== '0'
      else if (key === 'Name') current.name = value
    }
  }
  const resolve = (p: IniProfile) => (p.relative ? win.join(root, p.path.replace(/\//g, '\\')) : win.normalize(p.path))
  const all = profiles.filter((p) => p.path).map((p) => ({path: resolve(p), name: p.name}))
  const preferred = installDefaults.map((d) => win.join(root, d.replace(/\//g, '\\')).toLowerCase())
  const key = (p: {path: string}) => preferred.indexOf(p.path.toLowerCase())
  const first = all.filter((p) => key(p) >= 0).sort((a, b) => key(a) - key(b))
  return [...first, ...all.filter((p) => key(p) < 0)].filter((p, i, list) => list.findIndex((q) => q.path.toLowerCase() === p.path.toLowerCase()) === i)
}

const firefoxRoot = (deps: BrowserDeps) => win.join(deps.appData, 'Mozilla', 'Firefox')

async function firefoxProfiles(deps: BrowserDeps) {
  try {
    return parseProfilesIni(await deps.fs.readText(win.join(firefoxRoot(deps), 'profiles.ini')), firefoxRoot(deps))
  } catch {
    return []
  }
}

/** Whether `folder` is one of Firefox's own profile folders on this PC: the only profile the engines may be pointed at. */
export async function isFirefoxProfile(folder: string, deps: BrowserDeps = defaultDeps()): Promise<boolean> {
  const wanted = win.normalize(folder).toLowerCase()
  const known = (await firefoxProfiles(deps)).some((p) => p.path.toLowerCase() === wanted)
  return known && deps.fs.isDir(folder)
}

/** True when this Firefox profile holds a live sign-in for the site: read from a copy, because Firefox locks the live files. */
async function profileSignedIn(profile: string, siteKey: string, deps: BrowserDeps): Promise<boolean> {
  const site = siteByKey(siteKey)
  const source = win.join(profile, 'cookies.sqlite')
  if (!site || !(await deps.fs.isFile(source))) return false
  const temp = await deps.fs.makeTemp(deps.tempDir)
  try {
    await deps.fs.copy(source, win.join(temp, 'cookies.sqlite'))
    if (await deps.fs.isFile(`${source}-wal`)) await deps.fs.copy(`${source}-wal`, win.join(temp, 'cookies.sqlite-wal'))
    const domains = site.domains.map((d) => d.replace(/^\./, ''))
    const cookies = await deps.readCookies(win.join(temp, 'cookies.sqlite'), domains, site.sessionCookies)
    return signedIn(siteKey, cookies, deps.nowSeconds())
  } finally {
    await deps.fs.remove(temp).catch(() => undefined)
  }
}

/**
 * The browser sign-ins on this PC for a site (a core key such as `instagram`): the Firefox profiles signed in, and the
 * installed browsers whose sign-ins are locked. A missing or broken profile is skipped, never an error.
 */
export async function findBrowserSignIns(siteKey: string, deps: BrowserDeps = defaultDeps()): Promise<BrowserSignIns> {
  const sources: BrowserSource[] = []
  const profiles = await firefoxProfiles(deps)
  for (const profile of profiles) {
    const ok = await profileSignedIn(profile.path, siteKey, deps).catch(() => false)
    if (ok) sources.push({kind: 'browser', browser: 'firefox', profile: profile.path, label: profiles.length > 1 && profile.name ? `Firefox (${profile.name})` : 'Firefox'})
  }
  const locked: string[] = []
  for (const b of lockedBrowsers) {
    const file = win.join(b.root === 'local' ? deps.localAppData : deps.appData, b.state)
    if (await deps.fs.isFile(file)) locked.push(b.id)
  }
  return {sources, locked}
}
