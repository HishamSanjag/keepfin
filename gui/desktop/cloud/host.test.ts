import assert from 'node:assert/strict'
import {afterEach, beforeEach, describe, it} from 'node:test'
import {CloudAccounts} from './accounts.js'
import {FakeOAuth} from './fakes/oauth.js'
import {CloudHost, SignInCancelled} from './host.js'
import {configFrom} from './oauth.js'
import {testBox, tmpDir} from './testkit.js'

const config = configFrom({KEEPFIN_GOOGLE_CLIENT_ID: 'g-id', KEEPFIN_GOOGLE_CLIENT_SECRET: 'g-secret', KEEPFIN_MS_CLIENT_ID: 'ms-id', KEEPFIN_DROPBOX_APP_KEY: 'db-key'})

describe('cloud sign-in in the window', () => {
  let oauth: FakeOAuth
  beforeEach(async () => {
    oauth = new FakeOAuth()
    await oauth.server.start()
  })
  afterEach(() => oauth.server.stop())

  it('a new sign-in replaces one the browser never finished (the window that started it may be gone)', async () => {
    const route = {origin: oauth.server.origin, hosts: oauth.server.hosts}
    const accounts = new CloudAccounts({dir: await tmpDir(), box: testBox(), config, env: {rewrite: oauth.rewrite}})
    let opened = 0
    const host = new CloudHost({
      accounts,
      config,
      libraryId: 'library',
      mainReplica: 'main',
      test: route,
      // The first browser tab shows a provider error and never calls back; the second one approves.
      openExternal: async (url) => {
        if (++opened === 1) return
        setTimeout(() => void fetch(oauth.approve(url)).catch(() => undefined), 10)
      },
    })
    const stuck = assert.rejects(host.signIn('drive'), (error: unknown) => error instanceof SignInCancelled)
    await new Promise((resolve) => setTimeout(resolve, 50))
    const account = await host.signIn('drive')
    assert.equal(account.provider, 'drive')
    await stuck
  })
})
