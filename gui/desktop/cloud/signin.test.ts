// Sign-in and token storage against a fake authorization server (no real network): loopback PKCE with state, the one-shot
// listener, timeout and cancel, refresh-token rotation, a refused refresh becoming NeedsSignIn, serialised refreshes, tokens
// encrypted at rest and never in the view, Disconnect against Revoke.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import net from 'node:net'
import path from 'node:path'
import {afterEach, beforeEach, describe, it} from 'node:test'
import {CloudAccounts} from './accounts.js'
import {FakeOAuth} from './fakes/oauth.js'
import {CloudError} from './http.js'
import {configFrom} from './oauth.js'
import {DROPBOX_PORTS, signIn, SignInError} from './signin.js'
import {testBox, tmpDir} from './testkit.js'

const config = configFrom({KEEPFIN_GOOGLE_CLIENT_ID: 'g-id', KEEPFIN_GOOGLE_CLIENT_SECRET: 'g-secret', KEEPFIN_MS_CLIENT_ID: 'ms-id', KEEPFIN_DROPBOX_APP_KEY: 'db-key'})

describe('sign-in (loopback PKCE)', () => {
  let oauth: FakeOAuth
  beforeEach(async () => {
    oauth = new FakeOAuth()
    await oauth.server.start()
  })
  afterEach(() => oauth.server.stop())

  /** The browser: follows the callback the fake server hands back. */
  const browser = (options: {deny?: boolean; state?: string} = {}) => async (url: string) => {
    const callback = oauth.approve(url, options)
    setTimeout(() => void fetch(callback).catch(() => undefined), 10)
  }

  for (const provider of ['drive', 'graph', 'dropbox'] as const) {
    it(`${provider}: signs in, with S256, the state, and the redirect it announced`, async () => {
      let opened = ''
      const tokens = await signIn({
        provider,
        config: config[provider],
        env: {rewrite: oauth.rewrite},
        openExternal: async (url) => {
          opened = url
          await browser()(url)
        },
      })
      assert.match(tokens.accessToken, /^access-/)
      assert.match(tokens.refreshToken ?? '', /^refresh-/)
      assert.ok(tokens.expiresAtMs! > Date.now())
      const u = new URL(opened)
      assert.equal(u.searchParams.get('code_challenge_method'), 'S256')
      assert.equal(u.searchParams.get('response_type'), 'code')
      assert.ok(u.searchParams.get('state')!.length >= 20)
      const redirect = new URL(u.searchParams.get('redirect_uri')!)
      assert.equal(redirect.pathname, '/callback')
      assert.equal(redirect.hostname, provider === 'graph' ? 'localhost' : '127.0.0.1')
      if (provider === 'dropbox') assert.ok(DROPBOX_PORTS.includes(Number(redirect.port)), 'one of the registered ports')
      assert.equal(oauth.seen.at(-1)!.client_id, config[provider]!.clientId)
      if (provider === 'drive') assert.equal(oauth.seen.at(-1)!.client_secret, 'g-secret')
      else assert.equal(oauth.seen.at(-1)!.client_secret, undefined)
    })
  }

  /** Ports that are free right now (the real registered ports may be reserved by Windows on this machine). */
  const freePorts = async (n: number) => {
    const ports: number[] = []
    for (let i = 0; i < n; i++) {
      const s = net.createServer()
      await new Promise<void>((resolve) => s.listen(0, '127.0.0.1', resolve))
      ports.push((s.address() as net.AddressInfo).port)
      await new Promise<void>((resolve) => s.close(() => resolve()))
    }
    return ports
  }

  it('dropbox falls back to the next registered port when the first is taken', async () => {
    const [first, second] = await freePorts(2)
    const hold = net.createServer()
    await new Promise<void>((resolve) => hold.listen(first, '127.0.0.1', resolve))
    try {
      let port = 0
      await signIn({
        provider: 'dropbox',
        config: config.dropbox,
        env: {rewrite: oauth.rewrite},
        dropboxPorts: [first, second],
        openExternal: async (url) => {
          port = Number(new URL(new URL(url).searchParams.get('redirect_uri')!).port)
          await browser()(url)
        },
      })
      assert.equal(port, second)
    } finally {
      hold.close()
    }
  })

  it('dropbox with every port taken says so in plain words', async () => {
    const [first, second] = await freePorts(2)
    const a = net.createServer()
    const b = net.createServer()
    await new Promise<void>((resolve) => a.listen(first, '127.0.0.1', resolve))
    await new Promise<void>((resolve) => b.listen(second, '127.0.0.1', resolve))
    try {
      await assert.rejects(
        signIn({provider: 'dropbox', config: config.dropbox, env: {rewrite: oauth.rewrite}, dropboxPorts: [first, second], openExternal: async () => undefined}),
        (e: unknown) => e instanceof SignInError && e.code === 'port-busy',
      )
    } finally {
      a.close()
      b.close()
    }
  })

  it('the registered Dropbox ports include fallbacks below the Windows dynamic range (49152+)', () => {
    assert.ok(DROPBOX_PORTS.some((p) => p < 49152), 'a reserved 5368x block must not take every port')
  })
  it('a callback with another state is refused and nothing is exchanged', async () => {
    const before = oauth.seen.length
    await assert.rejects(
      signIn({provider: 'drive', config: config.drive, env: {rewrite: oauth.rewrite}, openExternal: browser({state: 'forged-state'})}),
      (e: unknown) => e instanceof SignInError && e.code === 'state-mismatch',
    )
    assert.equal(oauth.seen.length, before, 'the code was never sent to the token endpoint')
  })

  it('the user denying access is reported as denied', async () => {
    await assert.rejects(
      signIn({provider: 'graph', config: config.graph, env: {rewrite: oauth.rewrite}, openExternal: browser({deny: true})}),
      (e: unknown) => e instanceof SignInError && e.code === 'denied',
    )
  })

  it('times out when the browser never comes back, and frees the port', async () => {
    let redirect = ''
    await assert.rejects(
      signIn({
        provider: 'drive',
        config: config.drive,
        env: {rewrite: oauth.rewrite},
        timeoutMs: 80,
        openExternal: async (url) => void (redirect = new URL(url).searchParams.get('redirect_uri')!),
      }),
      (e: unknown) => e instanceof SignInError && e.code === 'timeout',
    )
    await assert.rejects(fetch(redirect), 'the listener is gone')
  })

  it('can be cancelled', async () => {
    const abort = new AbortController()
    const run = signIn({provider: 'drive', config: config.drive, env: {rewrite: oauth.rewrite}, signal: abort.signal, openExternal: async () => void setTimeout(() => abort.abort(), 20)})
    await assert.rejects(run, (e: unknown) => e instanceof SignInError && e.code === 'cancelled')
  })

  it('a provider without a client id is unavailable', async () => {
    await assert.rejects(signIn({provider: 'drive', config: undefined, openExternal: async () => undefined}), (e: unknown) => e instanceof SignInError && e.code === 'unavailable')
    assert.deepEqual(Object.keys(configFrom({KEEPFIN_MS_CLIENT_ID: 'x'})), ['graph'])
  })

  it('shows a friendly page and ignores other paths', async () => {
    let page = ''
    await signIn({
      provider: 'drive',
      config: config.drive,
      env: {rewrite: oauth.rewrite},
      openExternal: async (url) => {
        const callback = oauth.approve(url)
        const origin = new URL(callback).origin
        setTimeout(async () => {
          assert.equal((await fetch(origin + '/favicon.ico')).status, 404)
          page = await (await fetch(callback)).text()
        }, 10)
      },
    })
    await new Promise((r) => setTimeout(r, 30))
    assert.match(page, /You can close this tab/)
  })
})

describe('account storage and refresh', () => {
  let oauth: FakeOAuth
  let dir: string
  let t: number
  const make = (box = testBox()) => new CloudAccounts({dir, box, config, env: {rewrite: oauth.rewrite, now: () => t}, now: () => t})
  beforeEach(async () => {
    oauth = new FakeOAuth()
    await oauth.server.start()
    dir = await tmpDir()
    t = 1_000_000
  })
  afterEach(async () => {
    await oauth.server.stop()
    await fs.rm(dir, {recursive: true, force: true})
  })

  const signedIn = async (accounts: CloudAccounts, provider: 'drive' | 'graph' | 'dropbox' = 'drive') => {
    const tokens = await signIn({provider, config: config[provider], env: {rewrite: oauth.rewrite, now: () => t}, openExternal: async (url) => void setTimeout(() => void fetch(oauth.approve(url)), 5)})
    return accounts.addOAuth(provider, tokens, 'me@example.org')
  }

  it('encrypts tokens on disk and never puts them in the view', async () => {
    const accounts = make()
    const view = await signedIn(accounts)
    const raw = await fs.readFile(path.join(dir, 'accounts.json'), 'utf8')
    assert.ok(!raw.includes('access-') && !raw.includes('refresh-'), 'no token in clear text')
    assert.deepEqual(Object.keys(view).sort(), ['id', 'label', 'needsSignIn', 'provider'])
    assert.equal(JSON.stringify(await accounts.list()).includes('access-'), false)
    // a new process reads it back
    const again = make()
    assert.equal((await again.authorization(view.id)).header.startsWith('Bearer access-'), true)
  })

  it('refuses to store anything when secure storage is not available', async () => {
    const accounts = make(testBox(false))
    await assert.rejects(accounts.addOAuth('drive', {accessToken: 'a', refreshToken: 'r', expiresAtMs: null}, 'x'), /Secure storage is not available/)
  })

  it('refreshes an expired token with rotation, and keeps the rotated refresh token', async () => {
    const accounts = make()
    const { id } = await signedIn(accounts)
    const first = (await accounts.authorization(id)).header
    t += 3_600_000 + 1
    const second = (await accounts.authorization(id)).header
    assert.notEqual(first, second)
    assert.equal(oauth.refreshCalls, 1)
    t += 3_600_000 + 1
    await accounts.authorization(id) // would fail if the old (rotated) refresh token were still used
    assert.equal(oauth.refreshCalls, 2)
    assert.equal((await accounts.list())[0]!.needsSignIn, false)
  })

  it('serialises refreshes: many callers, one request', async () => {
    const accounts = make()
    const { id } = await signedIn(accounts)
    t += 3_600_000 + 1
    const results = await Promise.all(Array.from({length: 12}, () => accounts.authorization(id)))
    assert.equal(oauth.refreshCalls, 1)
    assert.equal(new Set(results.map((r) => r.header)).size, 1)
  })

  it('a refused refresh is NeedsSignIn, and it persists; Sign in again clears it', async () => {
    const accounts = make()
    const { id } = await signedIn(accounts)
    oauth.revokeAll()
    t += 3_600_000 + 1
    await assert.rejects(accounts.authorization(id), (e: unknown) => e instanceof CloudError && e.kind === 'NeedsSignIn')
    assert.equal((await accounts.list())[0]!.needsSignIn, true)
    assert.equal((await make().list())[0]!.needsSignIn, true, 'kept across restarts')
    await assert.rejects(accounts.authorization(id), (e: unknown) => e instanceof CloudError && e.kind === 'NeedsSignIn')
    const tokens = await signIn({provider: 'drive', config: config.drive, env: {rewrite: oauth.rewrite, now: () => t}, openExternal: async (url) => void setTimeout(() => void fetch(oauth.approve(url)), 5)})
    await accounts.replaceTokens(id, tokens)
    assert.equal((await accounts.list())[0]!.needsSignIn, false)
    assert.match((await accounts.authorization(id)).header, /^Bearer /)
  })

  it('a network failure during a refresh is not NeedsSignIn', async () => {
    const accounts = new CloudAccounts({dir, box: testBox(), config, env: {rewrite: oauth.rewrite, now: () => t, fetch: async () => { throw new Error('offline') }}, now: () => t})
    const { id } = await accounts.addOAuth('drive', {accessToken: 'a', refreshToken: 'r', expiresAtMs: t - 1}, 'x')
    await assert.rejects(accounts.authorization(id), (e: unknown) => e instanceof CloudError && e.kind === 'Transient')
    assert.equal((await accounts.list())[0]!.needsSignIn, false)
  })

  it('a 401 on a request refreshes through the account object', async () => {
    const accounts = make()
    const { id } = await signedIn(accounts)
    const account = accounts.account(id, 'drive')
    const before = await account.authorization()
    assert.equal(await account.refresh(), true)
    assert.notEqual(await account.authorization(), before)
    oauth.revokeAll()
    assert.equal(await account.refresh(), false)
  })

  it('Disconnect deletes only the local tokens; Revoke asks the provider and says so', async () => {
    const a = make()
    const one = await signedIn(a)
    await a.disconnect(one.id)
    assert.equal(oauth.revoked, 0, 'disconnect never calls the provider')
    assert.deepEqual(await a.list(), [])
    const two = await signedIn(a)
    const out = await a.revoke(two.id)
    assert.equal(out.revoked, true)
    assert.equal(oauth.revoked, 1)
    assert.deepEqual(await a.list(), [])
  })

  it('Microsoft personal accounts: revoke explains where to remove the grant', async () => {
    const a = make()
    const acct = await signedIn(a, 'graph')
    const out = await a.revoke(acct.id)
    assert.equal(out.revoked, false)
    assert.match(out.url ?? '', /^https:\/\//)
    assert.equal(oauth.revoked, 0)
    assert.deepEqual(await a.list(), [])
  })

  it('WebDAV keeps an encrypted app password and builds Basic auth', async () => {
    const a = make()
    const acct = await a.addWebdav('https://dav.example.org/remote.php/dav/files/me', 'me', 'app-password-123', 'Nextcloud')
    assert.equal((await fs.readFile(path.join(dir, 'accounts.json'), 'utf8')).includes('app-password-123'), false)
    assert.equal((await make().authorization(acct.id)).header, 'Basic ' + Buffer.from('me:app-password-123').toString('base64'))
    assert.equal(JSON.stringify(await a.list()).includes('app-password'), false)
  })
})
