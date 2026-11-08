import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {Request} from '../../shared/contracts.js'
import type {CookieSource} from '../../shared/policy.js'
import {withSignIn, type SignInChoice} from './auto.js'
import {handleSignIn} from './ops.js'
import {netscapeText, SignInStore, type StoredCookie, type Vault} from './store.js'

const NOW = 1_800_000_000
const SECRET = 'cookie-value-that-must-never-be-on-disk-in-the-clear'

// A reversible stand-in for the operating system's encryption: the stored bytes never hold the plain text.
const vault = (available = true): Vault => ({
  available: () => available,
  encrypt: (text) => Buffer.from(Buffer.from(text).map((b) => b ^ 0x5a)),
  decrypt: (data) => Buffer.from(Buffer.from(data).map((b) => b ^ 0x5a)).toString(),
})

const cookie = (over: Partial<StoredCookie> = {}): StoredCookie => ({name: 'sessionid', value: SECRET, domain: '.instagram.com', path: '/', secure: true, httpOnly: true, expiry: NOW + 86_400, ...over})

async function setup(available = true) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-signin-test-'))
  const store = new SignInStore({dir: path.join(root, 'signin'), tempDir: path.join(root, 'tmp'), vault: vault(available), now: () => NOW})
  await fs.mkdir(path.join(root, 'tmp'))
  return {root, store, cleanup: () => fs.rm(root, {recursive: true, force: true})}
}

test('a live sign-in is kept encrypted: the file on disk never holds the cookie value', async () => {
  const {root, store, cleanup} = await setup()
  try {
    assert.equal(await store.save('instagram', [cookie(), cookie({name: 'csrftoken', value: 'abc'})]), 'saved')
    const bytes = await fs.readFile(path.join(root, 'signin', 'instagram.cookies'))
    assert.equal(bytes.includes(Buffer.from(SECRET)), false)
    assert.equal(bytes.includes(Buffer.from('sessionid')), false, 'not even the names are readable')
    assert.deepEqual(await store.signedSites(), ['instagram'])
  } finally {
    await cleanup()
  }
})

test('cookies without the site\'s session cookie are not kept, and an earlier sign-in stays', async () => {
  const {store, cleanup} = await setup()
  try {
    assert.equal(await store.save('instagram', [cookie({name: 'csrftoken'})]), 'notSignedIn')
    assert.deepEqual(await store.signedSites(), [])
    await store.save('instagram', [cookie()])
    assert.equal(await store.save('instagram', [cookie({name: 'csrftoken'})]), 'notSignedIn')
    assert.deepEqual(await store.signedSites(), ['instagram'])
    assert.equal(await store.save('instagram', [cookie({domain: '.example.org'})]), 'notSignedIn', 'a cookie of another domain is no sign-in')
  } finally {
    await cleanup()
  }
})

test('without an operating-system store nothing is saved and nothing is listed', async () => {
  const {root, store, cleanup} = await setup(false)
  try {
    assert.equal(store.canKeep, false)
    assert.equal(await store.save('instagram', [cookie()]), 'unavailable')
    await assert.rejects(fs.readdir(path.join(root, 'signin')))
  } finally {
    await cleanup()
  }
})

test('an expired sign-in does not count; sign out removes the file; a bad site key is refused', async () => {
  const {store, cleanup} = await setup()
  try {
    await store.save('instagram', [cookie()])
    const later = new SignInStore({dir: (store as unknown as {dir: string}).dir, vault: vault(), now: () => NOW + 2 * 86_400})
    assert.deepEqual(await later.signedSites(), [], 'the session cookie has expired')
    await store.remove('instagram')
    assert.deepEqual(await store.signedSites(), [])
    await assert.rejects(store.remove('../evil'), /Unknown website/)
    await assert.rejects(store.save('nope', [cookie()]), /Unknown website/)
  } finally {
    await cleanup()
  }
})

test('a lease is a private Netscape file that release deletes; sweepTemp clears what a crash left', async () => {
  const {root, store, cleanup} = await setup()
  try {
    await store.save('instagram', [cookie(), cookie({name: 'ig_did', value: 'x\ty\nz', expiry: 0, httpOnly: false, secure: false, domain: 'www.instagram.com'})])
    const lease = (await store.lease('instagram'))!
    const text = await fs.readFile(lease.file, 'utf8')
    assert.match(text, /^# Netscape HTTP Cookie File/)
    assert.match(text, /#HttpOnly_\.instagram\.com\tTRUE\t\/\tTRUE\t\d+\tsessionid\t/)
    assert.match(text, /www\.instagram\.com\tFALSE\t\/\tFALSE\t\d+\tig_did\txyz\n/, 'tabs and newlines in a value cannot split a line')
    assert.equal(text.split('\n').filter((line) => line.endsWith('xyz')).length, 1)
    assert.ok(Number(/ig_did/.exec(text) && text.split('\n').find((l) => l.includes('ig_did'))!.split('\t')[4]) > NOW, 'a session cookie gets life for the run')
    await lease.release()
    await assert.rejects(fs.stat(lease.file))
    assert.deepEqual(await fs.readdir(path.join(root, 'tmp')), [])
    const again = (await store.lease('instagram'))!
    await store.sweepTemp()
    await assert.rejects(fs.stat(again.file))
    assert.equal(await store.lease('facebook'), undefined)
  } finally {
    await cleanup()
  }
})

test('netscapeText writes one line per cookie', () => {
  assert.equal(netscapeText([cookie()], NOW).trim().split('\n').length, 2)
})

test('the finder puts Keepfin\'s own sign-in first and runs the request with a file that is deleted afterwards', async () => {
  const {root, store, cleanup} = await setup()
  try {
    await store.save('instagram', [cookie()])
    const firefox: SignInChoice = {source: {kind: 'browser', browser: 'firefox', label: 'Firefox'}, cookies: {browser: 'firefox'}}
    const find = store.finder(async () => [firefox])
    const choices = await find('instagram')
    assert.deepEqual(choices.map((c) => c.source.kind), ['keepfin', 'browser'])
    assert.deepEqual((await store.finder(async () => [firefox])('reddit')).map((c) => c.source.kind), ['browser'], 'no own sign-in for a site not signed in')
    const seen: (CookieSource | undefined)[] = []
    let during: string | undefined
    const outcome = await withSignIn({
      url: 'https://www.instagram.com/p/abc/',
      autoOn: true,
      manual: false,
      find,
      run: async (cookies) => {
        seen.push(cookies)
        if (!cookies) throw Object.assign(new Error('Sign-in required.'), {action: 'signIn'})
        during = await fs.readFile(cookies.file!, 'utf8')
        return 'ok'
      },
    })
    assert.equal(outcome.value, 'ok')
    assert.deepEqual(outcome.used, {kind: 'keepfin'}, 'Keepfin\'s own sign-in is tried before a browser')
    assert.match(during!, /sessionid/)
    assert.deepEqual(await fs.readdir(path.join(root, 'tmp')), [], 'the temporary file is gone after the run')
  } finally {
    await cleanup()
  }
})

test('the file is deleted even when the run fails', async () => {
  const {root, store, cleanup} = await setup()
  try {
    await store.save('instagram', [cookie()])
    await assert.rejects(
      withSignIn({
        url: 'https://www.instagram.com/p/abc/',
        autoOn: true,
        manual: false,
        find: store.finder(async () => []),
        run: async (cookies) => {
          throw Object.assign(new Error(cookies ? 'boom' : 'Sign-in required.'), {action: cookies ? undefined : 'signIn'})
        },
      }),
      /boom/,
    )
    assert.deepEqual(await fs.readdir(path.join(root, 'tmp')), [])
  } finally {
    await cleanup()
  }
})

test('the requests: state lists every site without cookie data; open reports the outcome; remove signs out; bad keys are refused', async () => {
  const {root, store, cleanup} = await setup()
  try {
    const cleared: string[] = []
    const opened: string[] = []
    const deps = {
      store,
      open: async (key: string) => {
        opened.push(key)
        const outcome = await store.save(key, [cookie()])
        return {signedIn: outcome === 'saved', outcome}
      },
      clear: async (key: string) => void cleared.push(key),
    }
    const state = (await handleSignIn('signInState', {op: 'signInState'} as Request, deps)) as {canKeep: boolean; sites: {key: string; name: string; signedIn: boolean}[]}
    assert.equal(state.canKeep, true)
    assert.ok(state.sites.length >= 9 && state.sites.every((s) => ['key,name,signedIn', 'hint,key,name,signedIn'].includes(Object.keys(s).sort().join())), 'only a plain hint besides the name and state')
    const outcome = (await handleSignIn('signInOpen', {op: 'signInOpen', site: 'instagram'} as Request, deps)) as {signedIn: boolean; state: {sites: {key: string; signedIn: boolean}[]}}
    assert.equal(outcome.signedIn, true)
    assert.equal(outcome.state.sites.find((s) => s.key === 'instagram')!.signedIn, true)
    assert.ok(!JSON.stringify(outcome).includes(SECRET))
    const after = (await handleSignIn('signInRemove', {op: 'signInRemove', site: 'instagram'} as Request, deps)) as typeof state
    assert.equal(after.sites.find((s) => s.key === 'instagram')!.signedIn, false)
    assert.deepEqual(cleared, ['instagram'])
    await assert.rejects(handleSignIn('signInOpen', {op: 'signInOpen', site: 'https://evil.example'} as Request, deps), /Unknown website/)
    await assert.rejects(handleSignIn('signInOpen', {op: 'signInOpen'} as Request, deps), /Unknown website/)
    assert.deepEqual(opened, ['instagram'])
    // a window closed before signing in is explained
    const closed = (await handleSignIn('signInOpen', {op: 'signInOpen', site: 'reddit'} as Request, {...deps, open: async () => ({signedIn: false, outcome: 'notSignedIn' as const})})) as {signedIn: boolean; message?: string}
    assert.equal(closed.signedIn, false)
    assert.match(closed.message!, /not signed in to Reddit/)
    // no password store: the window is never opened
    const none = await setup(false)
    try {
      const refused = (await handleSignIn('signInOpen', {op: 'signInOpen', site: 'reddit'} as Request, {...deps, store: none.store})) as {message?: string}
      assert.match(refused.message!, /cannot keep a sign-in/)
      assert.deepEqual(opened, ['instagram'])
    } finally {
      await none.cleanup()
    }
  } finally {
    await cleanup()
    void root
  }
})
