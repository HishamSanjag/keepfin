import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {DatabaseSync} from 'node:sqlite'
import {defaultDeps, findBrowserSignIns, isFirefoxProfile, parseProfilesIni, type BrowserDeps} from './browsers.js'
import type {SignInCookie} from '../../shared/core/signin.js'

const APPDATA = String.raw`C:\Users\a\AppData\Roaming`
const LOCAL = String.raw`C:\Users\a\AppData\Local`
const ROOT = String.raw`${APPDATA}\Mozilla\Firefox`
const NOW = 1_000

const ini = [
  '[Install4F96D1932A9F858E]',
  'Default=Profiles/work.default-release',
  'Locked=1',
  '',
  '[Profile1]',
  'Name=home',
  'IsRelative=1',
  'Path=Profiles/home.default',
  '',
  '[Profile0]',
  'Name=default-release',
  'IsRelative=1',
  'Path=Profiles/work.default-release',
  'Default=1',
  '',
  '[Profile2]',
  'Name=elsewhere',
  'IsRelative=0',
  String.raw`Path=D:\Profiles\other`,
  '',
].join('\r\n')

type Fake = {files: Map<string, string>; dirs: Set<string>; cookies: Map<string, SignInCookie[]>; copied: string[]; removed: string[]; broken: Set<string>}

function fake(files: Record<string, string>, cookies: Record<string, SignInCookie[]> = {}): {deps: BrowserDeps; state: Fake} {
  const state: Fake = {files: new Map(Object.entries(files)), dirs: new Set(), cookies: new Map(Object.entries(cookies)), copied: [], removed: [], broken: new Set()}
  for (const f of state.files.keys()) state.dirs.add(path.win32.dirname(f))
  const deps: BrowserDeps = {
    appData: APPDATA,
    localAppData: LOCAL,
    tempDir: String.raw`C:\tmp`,
    nowSeconds: () => NOW,
    fs: {
      readText: async (f) => {
        const text = state.files.get(f)
        if (text === undefined) throw new Error('missing')
        return text
      },
      isFile: async (f) => state.files.has(f),
      isDir: async (d) => state.dirs.has(d),
      makeTemp: async (parent) => path.win32.join(parent, `t${state.copied.length}`),
      copy: async (from, to) => {
        if (!state.files.has(from)) throw new Error('missing')
        state.copied.push(`${from} -> ${to}`)
        state.files.set(to, state.files.get(from)!)
      },
      remove: async (d) => void state.removed.push(d),
    },
    readCookies: async (database, domains, names) => {
      assert.ok(domains.every((d) => !d.startsWith('.')) && names.length > 0)
      const source = [...state.cookies.keys()].find((k) => state.files.get(database) === k)
      if (!source || state.broken.has(source)) throw new Error('not a database')
      return state.cookies.get(source)!
    },
  }
  return {deps, state}
}

const live = [{name: 'sessionid', domain: '.instagram.com', expiry: 5000}]

test('profiles.ini: the install default first, relative and absolute paths, no duplicates', () => {
  const found = parseProfilesIni(ini, ROOT)
  assert.deepEqual(found.map((p) => p.name), ['default-release', 'home', 'elsewhere'])
  assert.deepEqual(found.map((p) => p.path), [String.raw`${ROOT}\Profiles\work.default-release`, String.raw`${ROOT}\Profiles\home.default`, String.raw`D:\Profiles\other`])
  assert.deepEqual(parseProfilesIni('', ROOT), [])
  assert.deepEqual(parseProfilesIni('[Profile0]\nName=x\n', ROOT), [])
})

test('a signed-in Firefox profile is a source, copied (with its write-ahead log) to a temp folder that is deleted afterwards', async () => {
  const work = String.raw`${ROOT}\Profiles\work.default-release`
  const {deps, state} = fake(
    {[`${ROOT}\\profiles.ini`]: ini, [`${work}\\cookies.sqlite`]: 'db-work', [`${work}\\cookies.sqlite-wal`]: 'wal'},
    {'db-work': live},
  )
  const result = await findBrowserSignIns('instagram', deps)
  assert.deepEqual(result, {sources: [{kind: 'browser', browser: 'firefox', profile: work, label: 'Firefox (default-release)'}], locked: []})
  assert.deepEqual(state.copied, [
    String.raw`${work}\cookies.sqlite -> C:\tmp\t0\cookies.sqlite`,
    String.raw`${work}\cookies.sqlite-wal -> C:\tmp\t0\cookies.sqlite-wal`,
  ])
  assert.equal(state.removed.length, 1)
})

test('only tracking cookies, expired sessions, missing and broken profiles are not sources and never an error', async () => {
  const work = String.raw`${ROOT}\Profiles\work.default-release`
  const home = String.raw`${ROOT}\Profiles\home.default`
  const {deps, state} = fake(
    {[`${ROOT}\\profiles.ini`]: ini, [`${work}\\cookies.sqlite`]: 'db-work', [`${home}\\cookies.sqlite`]: 'db-home'},
    {'db-work': [{name: 'csrftoken', domain: '.instagram.com', expiry: 5000}, {name: 'sessionid', domain: '.instagram.com', expiry: 10}], 'db-home': live},
  )
  assert.deepEqual((await findBrowserSignIns('instagram', deps)).sources.map((s) => s.profile), [home])
  state.broken.add('db-home')
  assert.deepEqual((await findBrowserSignIns('instagram', deps)).sources, [])
  assert.equal(state.removed.length, 4, 'the temp folder is removed even when the database cannot be read')
})

test('no Firefox, an unknown site and an unreadable profiles.ini give an empty result', async () => {
  assert.deepEqual(await findBrowserSignIns('instagram', fake({}).deps), {sources: [], locked: []})
  assert.deepEqual(await findBrowserSignIns('nowhere', fake({[`${ROOT}\\profiles.ini`]: ini}).deps), {sources: [], locked: []})
})

test('Chromium-family browsers are only detected as locked, in a fixed order, and never opened', async () => {
  const {deps, state} = fake({
    [`${LOCAL}\\Google\\Chrome\\User Data\\Local State`]: '{}',
    [`${LOCAL}\\Microsoft\\Edge\\User Data\\Local State`]: '{}',
    [`${APPDATA}\\Opera Software\\Opera Stable\\Local State`]: '{}',
  })
  assert.deepEqual(await findBrowserSignIns('instagram', deps), {sources: [], locked: ['chrome', 'edge', 'opera']})
  assert.deepEqual(state.copied, [])
})

test('a profile is accepted for the engines only when profiles.ini lists it and the folder exists', async () => {
  const work = String.raw`${ROOT}\Profiles\work.default-release`
  const {deps} = fake({[`${ROOT}\\profiles.ini`]: ini, [`${work}\\cookies.sqlite`]: 'x'})
  assert.equal(await isFirefoxProfile(work, deps), true)
  assert.equal(await isFirefoxProfile(work.toUpperCase(), deps), false, 'a folder that does not exist is refused')
  assert.equal(await isFirefoxProfile(String.raw`${ROOT}\Profiles\unlisted`, deps), false)
  assert.equal(await isFirefoxProfile(String.raw`C:\Windows\System32`, deps), false)
  assert.equal(await isFirefoxProfile(`${work}\\..\\..\\..\\..`, deps), false)
})

test('the real reader finds the session cookie in a copied database with a write-ahead log, by name, domain and expiry only', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'keepfin-signin-test-'))
  try {
    const db = new DatabaseSync(path.join(dir, 'cookies.sqlite'))
    db.exec('CREATE TABLE moz_cookies (id INTEGER PRIMARY KEY, name TEXT, value TEXT, host TEXT, path TEXT, expiry INTEGER)')
    const add = db.prepare('INSERT INTO moz_cookies (name, value, host, path, expiry) VALUES (?, ?, ?, ?, ?)')
    add.run('sessionid', 'secret', '.instagram.com', '/', 5000)
    add.run('csrftoken', 'secret', '.instagram.com', '/', 5000)
    add.run('sessionid', 'secret', '.example.com', '/', 5000)
    add.run('sessionid', 'secret', '.notinstagram.com', '/', 5000)
    add.run('sessionid', 'secret', 'www.instagram.com', '/', 5_000_000_000_000)
    db.close()
    const rows = await defaultDeps().readCookies(path.join(dir, 'cookies.sqlite'), ['instagram.com'], ['sessionid'])
    assert.deepEqual(rows, [
      {name: 'sessionid', domain: '.instagram.com', expiry: 5000},
      {name: 'sessionid', domain: 'www.instagram.com', expiry: 5_000_000_000},
    ])
    assert.ok(rows.every((r) => !('value' in r)))
  } finally {
    fs.rmSync(dir, {recursive: true, force: true})
  }
})
