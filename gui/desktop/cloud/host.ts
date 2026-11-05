// The main-process side of cloud accounts for the copies host (copiesHost.ts): the provider list a build can offer, sign-in and
// cancel, WebDAV connection with its probe, Disconnect and Revoke, quota for the account cards, replicas found on an account,
// and the authorization the copies worker asks for. Free of Electron imports: the browser opener and the secure storage are
// passed in.
import type {CloudAccountView, CloudFoundReplica, CloudProviderChoice, CloudProviderId} from '../../shared/copies.js'
import {CloudAccounts} from './accounts.js'
import type {AccountView} from './accounts.js'
import {CloudClient} from './client.js'
import {findReplicas, openRig} from './cloudPlace.js'
import type {CloudPlaceSpec, FoundReplica, Rig} from './cloudPlace.js'
import {CloudError, CloudHttp} from './http.js'
import type {HttpOptions} from './http.js'
import {OAUTH_PROVIDERS} from './oauth.js'
import type {BuildConfig, OAuthEnv, OAuthProvider} from './oauth.js'
import {rewriteFor} from './route.js'
import type {CloudTestRoute} from './route.js'
import {signIn, SignInError} from './signin.js'

export const PROVIDER_NAMES: Record<CloudProviderId, string> = {drive: 'Google Drive', graph: 'OneDrive', dropbox: 'Dropbox', webdav: 'WebDAV'}
const SCOPES: Record<CloudProviderId, string> = {
  drive: 'Keepfin sees only the files it creates itself.',
  graph: 'Keepfin gets its own app folder and sees nothing else.',
  dropbox: 'Keepfin gets its own app folder and sees nothing else.',
  webdav: 'A folder on your own server (Nextcloud, a NAS, any WebDAV address).',
}

export interface CloudHostDeps {
  accounts: CloudAccounts
  config: BuildConfig
  openExternal(url: string): Promise<void>
  /** Where the library and this copy's identity are, for finding replicas. */
  libraryId: string
  mainReplica: string
  test?: CloudTestRoute
}

export class SignInCancelled extends Error {
  constructor() {
    super('Sign-in was cancelled.')
  }
}

export class CloudHost {
  private signing?: AbortController
  private readonly support = new Map<string, string[]>()
  private readonly quotas = new Map<string, {freeBytes?: number; totalBytes?: number; at: number}>()
  private readonly env: OAuthEnv

  constructor(private readonly deps: CloudHostDeps, private readonly now: () => number = Date.now) {
    this.env = {rewrite: rewriteFor(deps.test)}
  }

  get accounts(): CloudAccounts {
    return this.deps.accounts
  }

  providers(): CloudProviderChoice[] {
    const list: CloudProviderChoice[] = (['drive', 'graph', 'dropbox'] as const).map((id) => ({id, name: PROVIDER_NAMES[id], available: !!this.deps.config[id], scope: SCOPES[id]}))
    list.push({id: 'webdav', name: PROVIDER_NAMES.webdav, available: true, scope: SCOPES.webdav})
    return list
  }

  async views(copiesByAccount: Map<string, number>): Promise<CloudAccountView[]> {
    const out: CloudAccountView[] = []
    for (const a of await this.deps.accounts.list()) out.push(this.view(a, copiesByAccount.get(a.id) ?? 0))
    return out
  }

  private view(a: AccountView, copies: number): CloudAccountView {
    const q = this.quotas.get(a.id)
    const support = this.support.get(a.id)
    return {
      id: a.id,
      provider: a.provider,
      providerName: PROVIDER_NAMES[a.provider],
      label: a.provider === 'webdav' ? `${a.user ?? ''} at ${hostOf(a.server)}`.trim() : a.label,
      needsSignIn: a.needsSignIn,
      ...(q?.freeBytes !== undefined ? {freeBytes: q.freeBytes} : {}),
      ...(q?.totalBytes !== undefined ? {totalBytes: q.totalBytes} : {}),
      copies,
      ...(support ? {support} : {}),
    }
  }

  async viewOf(id: string, copies: number): Promise<CloudAccountView> {
    const a = await this.deps.accounts.get(id)
    if (!a) throw new Error('This account is not on this computer any more.')
    return this.view(a, copies)
  }

  // ----------------------------------------------------------------------------------------------------------- sign-in

  /** Signs in through the browser. With [accountId] the account gets new tokens (Sign in again); else a new account is made. */
  async signIn(provider: CloudProviderId, accountId?: string): Promise<AccountView> {
    if (provider === 'webdav') throw new Error('WebDAV uses an address and an app password.')
    // A new sign-in replaces one still waiting: its browser tab may show a provider error that never calls back, and the
    // window that started it may be closed, so nobody else can finish or cancel it.
    this.signing?.abort()
    const abort = new AbortController()
    this.signing = abort
    try {
      if (accountId) {
        const existing = await this.deps.accounts.get(accountId)
        if (!existing || existing.provider !== provider) throw new Error('This account is not on this computer any more.')
      }
      let tokens
      try {
        tokens = await signIn({provider, config: this.deps.config[provider], env: this.env, openExternal: this.deps.openExternal, signal: abort.signal})
      } catch (error) {
        if (error instanceof SignInError && error.code === 'cancelled') throw new SignInCancelled()
        throw error
      }
      if (accountId) return await this.deps.accounts.replaceTokens(accountId, tokens)
      // who is this? Google tells an address; the others only that it worked
      let label = PROVIDER_NAMES[provider]
      const probe = await this.deps.accounts.addOAuth(provider, tokens, label)
      try {
        const q = await new CloudClient(this.httpFor(probe.id, provider, {})).quota()
        this.quotas.set(probe.id, {freeBytes: q.freeBytes, totalBytes: q.totalBytes, at: this.now()})
        if (q.account) {
          label = q.account
          const same = (await this.deps.accounts.list()).find((a) => a.id !== probe.id && a.provider === provider && a.label === label)
          if (same) {
            // the same account signed in a second time: one account per sign-in, so the new tokens replace the old
            await this.deps.accounts.replaceTokens(same.id, tokens)
            await this.deps.accounts.disconnect(probe.id)
            return (await this.deps.accounts.get(same.id))!
          }
          await this.deps.accounts.setLabel(probe.id, label)
        }
      } catch {
        // the account is signed in even if its quota could not be read yet
      }
      return (await this.deps.accounts.get(probe.id))!
    } finally {
      if (this.signing === abort) this.signing = undefined
    }
  }

  cancelSignIn(): void {
    this.signing?.abort()
  }

  // ---------------------------------------------------------------------------------------------------------- WebDAV

  /** Connects a WebDAV server: the address, user and app password are checked, then the server is probed for what it supports. */
  async connectWebdav(url: string, user: string, password: string): Promise<{account: AccountView; support: string[]}> {
    const address = validateWebdavUrl(url)
    if (!user.trim()) throw new Error('Enter the user name for the server.')
    if (!password) throw new Error('Enter the app password for the server.')
    const account = await this.deps.accounts.addWebdav(address, user.trim(), password, `${user.trim()} at ${hostOf(address)}`)
    try {
      const client = new CloudClient(this.httpFor(account.id, 'webdav', {}), {base: address})
      try {
        if (!(await client.stat({path: ''}))) throw new CloudError({kind: 'NotFound', status: 404}, 'connect')
      } catch (error) {
        if (error instanceof CloudError && error.kind === 'NeedsSignIn') throw new Error('The server did not accept this user name and password.')
        if (error instanceof CloudError && error.kind === 'NotFound') throw new Error('The server answered, but there is nothing at this address.')
        throw new Error('Keepfin could not reach the server at this address.')
      }
      const scratch = `.keepfin-probe-${this.now().toString(36)}`
      const verdict = await client.probeWebdav(scratch).catch(() => undefined)
      const support = describeProbe(verdict)
      this.support.set(account.id, support)
      return {account, support}
    } catch (error) {
      await this.deps.accounts.disconnect(account.id)
      throw error
    }
  }

  // ------------------------------------------------------------------------------------------------ disconnect, revoke

  async disconnect(id: string): Promise<void> {
    await this.deps.accounts.disconnect(id)
    this.support.delete(id)
    this.quotas.delete(id)
  }

  async revoke(id: string): Promise<{revoked: boolean; note: string; url?: string}> {
    const out = await this.deps.accounts.revoke(id)
    this.support.delete(id)
    this.quotas.delete(id)
    return out
  }

  // ----------------------------------------------------------------------------------------------- reading the account

  /** A client over [accountId] in this process (quota, replicas); not for copies, which run in the worker thread. */
  httpFor(accountId: string, provider: CloudProviderId, options: HttpOptions): CloudHttp {
    return new CloudHttp(this.deps.accounts.account(accountId, provider), {rewrite: rewriteFor(this.deps.test), ...options})
  }

  async rigFor(spec: CloudPlaceSpec): Promise<Rig> {
    return openRig(spec, {account: this.deps.accounts.account(spec.accountId, spec.provider), http: {rewrite: rewriteFor(this.deps.test)}})
  }

  /** Free and total space of the account, kept for ten minutes (the cards show it; a sync refreshes it). */
  async quota(accountId: string, force = false): Promise<{freeBytes?: number; totalBytes?: number}> {
    const acct = await this.deps.accounts.get(accountId)
    if (!acct || acct.needsSignIn) return {}
    const q = this.quotas.get(accountId)
    if (q && !force && this.now() - q.at < 10 * 60_000) return q
    try {
      const base = acct.provider === 'webdav' ? (await this.deps.accounts.webdav(accountId))?.server : undefined
      const got = await new CloudClient(this.httpFor(accountId, acct.provider, {}), {base}).quota()
      const next = {freeBytes: got.freeBytes, totalBytes: got.totalBytes, at: this.now()}
      this.quotas.set(accountId, next)
      return next
    } catch {
      return q ?? {}
    }
  }

  quotaNow(accountId: string): {freeBytes?: number; totalBytes?: number} {
    return this.quotas.get(accountId) ?? {}
  }

  /** Replicas of this library on the account, other than this computer's own. */
  async find(accountId: string, webdavFolder?: string): Promise<FoundReplica[]> {
    const acct = await this.deps.accounts.get(accountId)
    if (!acct) throw new Error('This account is not on this computer any more.')
    if (acct.needsSignIn) throw new Error('Sign in again to look at this account.')
    const base = acct.provider === 'webdav' ? (await this.deps.accounts.webdav(accountId))?.server : undefined
    const client = new CloudClient(this.httpFor(accountId, acct.provider, {}), {base})
    const found = await findReplicas(client, acct.provider, this.deps.libraryId, webdavFolder)
    return found.filter((f) => f.replicaId !== this.deps.mainReplica)
  }

  /** What the copies worker asks for: the Authorization header, refreshed first when [force] (after a 401). */
  async workerAuth(accountId: string, force: boolean): Promise<{header: string; expiresAtMs: number | null}> {
    if (force && !(await this.deps.accounts.refreshNow(accountId))) throw new CloudError({kind: 'NeedsSignIn', status: 401}, 'token')
    return this.deps.accounts.authorization(accountId)
  }

  providersFor(): OAuthProvider[] {
    return OAUTH_PROVIDERS.filter((p) => this.deps.config[p])
  }
}

export function hostOf(server: string | undefined): string {
  try {
    return new URL(server ?? '').host
  } catch {
    return server ?? ''
  }
}

/** An https address (http only for this computer itself, which tests and a local server use); a trailing slash is removed. */
export function validateWebdavUrl(url: string): string {
  let u: URL
  try {
    u = new URL(url.trim())
  } catch {
    throw new Error('Enter the full address of the server, starting with https://')
  }
  const local = u.hostname === 'localhost' || u.hostname === '127.0.0.1' || u.hostname === '[::1]'
  if (u.protocol !== 'https:' && !(u.protocol === 'http:' && local)) throw new Error('Use an https:// address. Keepfin will not send a password over an unencrypted connection.')
  if (u.username || u.password) throw new Error('Leave the user name and password out of the address; enter them in their own boxes.')
  u.hash = ''
  return u.toString().replace(/\/+$/, '')
}

export function describeProbe(v: {cas: boolean; range: boolean; createOnly: boolean; noReplaceMove: boolean; reason: string} | undefined): string[] {
  if (!v) return ['Keepfin could not test this server. It will keep every earlier version and never overwrite a file in place.']
  const out: string[] = []
  out.push(v.cas ? 'Checks that a file has not changed before replacing it.' : 'Does not check files before replacing them: Keepfin keeps the old bytes and never overwrites in place.')
  out.push(v.createOnly || v.noReplaceMove ? 'Never writes over a file that is already there.' : 'May write over a file that appeared at the same moment: only one device should sync at a time.')
  out.push(v.range ? 'Can continue an interrupted download.' : 'Cannot continue an interrupted download: Keepfin starts the file again.')
  return out
}
