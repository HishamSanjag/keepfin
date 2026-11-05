// Cloud accounts on this device (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md sections 3 and 7.1 #5, 7.2 #4). Tokens (and a WebDAV app
// password, the one credential Keepfin keeps) are encrypted with the platform's secure storage (Electron safeStorage) and never
// synced: each device signs in on its own. The file lives in the profile's folder on this device. Nothing here logs a secret, and
// the renderer only ever sees AccountView (no token, no password).
//  - A refresh is serialised per account (one in flight) and the new tokens replace the old ones atomically.
//  - An expired access token is refreshed before NeedsSignIn is ever shown; NeedsSignIn is set only when the refresh itself is
//    refused (revoked, expired, changed password).
//  - Disconnect on this device deletes the local tokens only. Revoke is separate: it also removes the grant for the user's other
//    devices, where the provider has an endpoint for it.
import crypto from 'node:crypto'
import path from 'node:path'
import {cloud} from '../../shared/core/cloud.js'
import type {CloudProvider, CloudRequest} from '../../shared/core/cloud.js'
import {readJson, writeJsonAtomic} from '../store/atomic.js'
import {CloudError} from './http.js'
import type {CloudAccount} from './http.js'
import {tokenCall} from './oauth.js'
import type {BuildConfig, OAuthEnv, OAuthProvider, TokenSet} from './oauth.js'

/** Encrypts a small string for this computer and user. Electron's safeStorage in the app; a fake in tests. */
export interface SecretBox {
  available(): boolean
  encrypt(plain: string): Buffer
  decrypt(blob: Buffer): string
}

export interface AccountView {
  id: string
  provider: CloudProvider
  /** The account's name as the user will recognise it (an address when the provider tells it, else the provider's name). */
  label: string
  needsSignIn: boolean
  /** WebDAV: where, and as whom (never the password). */
  server?: string
  user?: string
}

type Secret = {accessToken?: string; refreshToken?: string; expiresAtMs?: number | null; password?: string}
type StoredAccount = {id: string; provider: CloudProvider; label: string; needsSignIn: boolean; server?: string; user?: string; secret: string}
type FileShape = {version: 1; accounts: StoredAccount[]}

export const MS_PERMISSIONS_URL = 'https://account.live.com/consent/Manage'

export interface AccountsOptions {
  /** The profile's device-local folder; the file is `<dir>/accounts.json`. */
  dir: string
  box: SecretBox
  config: BuildConfig
  env?: OAuthEnv
  newId?: () => string
  now?: () => number
  /** Microsoft tenant segment (default `consumers`). */
  tenant?: string
}

export class CloudAccounts {
  private accounts: Map<string, {meta: Omit<StoredAccount, 'secret'>; secret: Secret}> | undefined
  private readonly refreshing = new Map<string, Promise<void>>()
  private chain: Promise<unknown> = Promise.resolve()
  private readonly now: () => number

  constructor(private readonly options: AccountsOptions) {
    this.now = options.now ?? Date.now
  }

  private get file() {
    return path.join(this.options.dir, 'accounts.json')
  }

  private async load() {
    if (this.accounts) return this.accounts
    const map = new Map<string, {meta: Omit<StoredAccount, 'secret'>; secret: Secret}>()
    const saved = await readJson<FileShape>(this.file).catch(() => undefined)
    for (const a of saved?.version === 1 ? saved.accounts : []) {
      let secret: Secret = {}
      try {
        secret = JSON.parse(this.options.box.decrypt(Buffer.from(a.secret, 'base64'))) as Secret
      } catch {
        // The key changed (another Windows user, a restored profile): the tokens are unreadable, so sign in again.
        a.needsSignIn = true
      }
      const {secret: _drop, ...meta} = a
      map.set(a.id, {meta, secret})
    }
    this.accounts = map
    return map
  }

  /** Serialises writes; the file is replaced atomically each time. */
  private save(): Promise<void> {
    const run = async () => {
      if (!this.accounts) return
      if (!this.options.box.available()) throw new Error('Secure storage is not available on this computer, so Keepfin cannot keep a sign-in.')
      const accounts: StoredAccount[] = []
      for (const {meta, secret} of this.accounts.values()) accounts.push({...meta, secret: this.options.box.encrypt(JSON.stringify(secret)).toString('base64')})
      await writeJsonAtomic(this.file, {version: 1, accounts} satisfies FileShape)
    }
    const next = this.chain.then(run, run)
    this.chain = next.catch(() => undefined)
    return next
  }

  private view(a: {meta: Omit<StoredAccount, 'secret'>}): AccountView {
    const m = a.meta
    return {id: m.id, provider: m.provider, label: m.label, needsSignIn: m.needsSignIn, ...(m.server ? {server: m.server} : {}), ...(m.user ? {user: m.user} : {})}
  }

  async list(): Promise<AccountView[]> {
    return [...(await this.load()).values()].map((a) => this.view(a))
  }

  async get(id: string): Promise<AccountView | undefined> {
    const a = (await this.load()).get(id)
    return a ? this.view(a) : undefined
  }

  /** Stores the tokens of a new sign-in. */
  async addOAuth(provider: OAuthProvider, tokens: TokenSet, label: string): Promise<AccountView> {
    const map = await this.load()
    const id = (this.options.newId ?? (() => crypto.randomUUID()))()
    map.set(id, {meta: {id, provider, label, needsSignIn: false}, secret: {accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, expiresAtMs: tokens.expiresAtMs}})
    await this.save()
    return this.view(map.get(id)!)
  }

  async addWebdav(server: string, user: string, password: string, label: string): Promise<AccountView> {
    const map = await this.load()
    const id = (this.options.newId ?? (() => crypto.randomUUID()))()
    map.set(id, {meta: {id, provider: 'webdav', label, needsSignIn: false, server, user}, secret: {password}})
    await this.save()
    return this.view(map.get(id)!)
  }

  /** A new sign-in for an existing account (Sign in again): replaces the tokens and clears NeedsSignIn. */
  async replaceTokens(id: string, tokens: TokenSet): Promise<AccountView> {
    const a = (await this.load()).get(id)
    if (!a) throw new Error('This account is not on this computer any more.')
    a.secret = {...a.secret, accessToken: tokens.accessToken, refreshToken: tokens.refreshToken ?? a.secret.refreshToken, expiresAtMs: tokens.expiresAtMs}
    a.meta.needsSignIn = false
    await this.save()
    return this.view(a)
  }

  async setLabel(id: string, label: string): Promise<void> {
    const a = (await this.load()).get(id)
    if (!a || a.meta.label === label) return
    a.meta.label = label
    await this.save()
  }

  async setPassword(id: string, password: string): Promise<AccountView> {
    const a = (await this.load()).get(id)
    if (!a || a.meta.provider !== 'webdav') throw new Error('This account does not use a password.')
    a.secret = {password}
    a.meta.needsSignIn = false
    await this.save()
    return this.view(a)
  }

  /** Disconnect on this device: the local tokens are deleted and nothing else happens. The cloud files stay. */
  async disconnect(id: string): Promise<void> {
    const map = await this.load()
    if (map.delete(id)) await this.save()
  }

  /**
   * Revoke: asks the provider to end the grant, which signs out the user's other devices too, then disconnects here. Where the
   * provider has no endpoint (Microsoft personal accounts, WebDAV) only the local part happens and the answer says where to go.
   */
  async revoke(id: string): Promise<{revoked: boolean; note: string; url?: string}> {
    const a = (await this.load()).get(id)
    if (!a) throw new Error('This account is not on this computer any more.')
    const p = a.meta.provider
    let out: {revoked: boolean; note: string; url?: string}
    if (p === 'graph') out = {revoked: false, note: 'Microsoft does not let an app end this sign-in for personal accounts. Remove Keepfin in your Microsoft account’s app permissions.', url: MS_PERMISSIONS_URL}
    else if (p === 'webdav') out = {revoked: false, note: 'A WebDAV password is changed or removed on the server. Keepfin has forgotten it on this computer.'}
    else {
      out = {revoked: false, note: 'Keepfin could not reach the provider to end the sign-in. It has been forgotten on this computer; remove Keepfin in the provider’s account settings.'}
      try {
        const token = p === 'drive' ? a.secret.refreshToken ?? a.secret.accessToken : (await this.ensureFresh(id), a.secret.accessToken)
        const built = cloud<{request: CloudRequest | null}>(p, 'revokeRequest', {provider: p, token})
        if (built.request) {
          const req = built.request
          const headers = {...req.headers}
          if (req.auth && a.secret.accessToken) headers['Authorization'] = `Bearer ${a.secret.accessToken}`
          const doFetch = this.options.env?.fetch ?? fetch
          const url = this.options.env?.rewrite ? this.options.env.rewrite(req.url) : req.url
          const res = await doFetch(url, {method: req.method, headers, body: req.body, signal: AbortSignal.timeout(30_000)})
          if (res.ok) out = {revoked: true, note: 'The sign-in was ended at the provider, so it is signed out on your other devices too.'}
        }
      } catch {
        // keep the default note
      }
    }
    await this.disconnect(id)
    return out
  }

  // --------------------------------------------------------------------------------------------------------- tokens

  private needsRefresh(s: Secret): boolean {
    if (!s.accessToken) return true
    return typeof s.expiresAtMs === 'number' && this.now() >= s.expiresAtMs - 60_000
  }

  /** Refreshes the access token if it has expired (or [force]); one refresh per account at a time. Throws CloudError. */
  private ensureFresh(id: string, force = false): Promise<void> {
    const a = this.accounts?.get(id)
    if (!a) return Promise.reject(new CloudError({kind: 'NeedsSignIn', status: 0, code: 'no-account'}, 'token'))
    if (a.meta.provider === 'webdav') return Promise.resolve()
    if (!force && !this.needsRefresh(a.secret)) return Promise.resolve()
    const running = this.refreshing.get(id)
    if (running) return running
    const job = this.doRefresh(id).finally(() => this.refreshing.delete(id))
    this.refreshing.set(id, job)
    return job
  }

  private async doRefresh(id: string): Promise<void> {
    const a = this.accounts!.get(id)!
    const provider = a.meta.provider as OAuthProvider
    const config = this.options.config[provider]
    const refreshToken = a.secret.refreshToken
    if (!refreshToken || !config) {
      await this.markNeedsSignIn(id)
      throw new CloudError({kind: 'NeedsSignIn', status: 0, code: refreshToken ? 'unavailable' : 'no-refresh-token'}, 'token')
    }
    const built = cloud<{request: CloudRequest}>(provider, 'refreshRequest', {
      provider,
      refreshToken,
      clientId: config.clientId,
      tenant: this.options.tenant ?? 'consumers',
      ...(config.clientSecret ? {clientSecret: config.clientSecret} : {}),
    })
    let tokens: TokenSet
    try {
      tokens = await tokenCall(provider, built.request, this.options.env ?? {}, refreshToken)
    } catch (error) {
      // Only a refused refresh means "sign in again"; a network or server hiccup does not.
      if (error instanceof CloudError && error.kind === 'NeedsSignIn') await this.markNeedsSignIn(id)
      throw error
    }
    // Refresh-token rotation: the new one replaces the old one in the same atomic write; if the provider sent none, the old stays.
    a.secret = {...a.secret, accessToken: tokens.accessToken, refreshToken: tokens.refreshToken ?? refreshToken, expiresAtMs: tokens.expiresAtMs}
    await this.save()
  }

  private async markNeedsSignIn(id: string): Promise<void> {
    const a = this.accounts?.get(id)
    if (!a || a.meta.needsSignIn) return
    a.meta.needsSignIn = true
    await this.save().catch(() => undefined)
  }

  /** The Authorization header for [id] and when it stops being good (null: no expiry known). Refreshes first when it has expired. */
  async authorization(id: string): Promise<{header: string; expiresAtMs: number | null}> {
    const map = await this.load()
    const a = map.get(id)
    if (!a) throw new CloudError({kind: 'NeedsSignIn', status: 0, code: 'no-account'}, 'token')
    if (a.meta.needsSignIn) throw new CloudError({kind: 'NeedsSignIn', status: 0, code: 'signed-out'}, 'token')
    if (a.meta.provider === 'webdav') {
      const basic = cloud<{authorization: string}>('webdav', 'basicAuth', {user: a.meta.user ?? '', password: a.secret.password ?? ''})
      return {header: basic.authorization, expiresAtMs: null}
    }
    await this.ensureFresh(id)
    return {header: `Bearer ${a.secret.accessToken}`, expiresAtMs: a.secret.expiresAtMs ?? null}
  }

  /** After a 401: a new access token (true), or false when the account cannot get one (and is marked NeedsSignIn if it was refused). */
  async refreshNow(id: string): Promise<boolean> {
    await this.load()
    try {
      await this.ensureFresh(id, true)
      return true
    } catch (error) {
      if (error instanceof CloudError && error.kind === 'NeedsSignIn') return false
      throw error
    }
  }

  /** An account for CloudHttp that lives in this process. */
  account(id: string, provider: CloudProvider): CloudAccount {
    return {
      id,
      provider,
      authorization: async () => (await this.authorization(id)).header,
      refresh: () => this.refreshNow(id),
    }
  }

  /** The server address and user of a WebDAV account. */
  async webdav(id: string): Promise<{server: string; user: string} | undefined> {
    const a = (await this.load()).get(id)
    return a?.meta.provider === 'webdav' && a.meta.server ? {server: a.meta.server, user: a.meta.user ?? ''} : undefined
  }
}

/** A SecretBox over Electron's safeStorage (passed in, so this file stays free of Electron imports). */
export function safeStorageBox(safe: {isEncryptionAvailable(): boolean; encryptString(s: string): Buffer; decryptString(b: Buffer): string}): SecretBox {
  return {available: () => safe.isEncryptionAvailable(), encrypt: (s) => safe.encryptString(s), decrypt: (b) => safe.decryptString(b)}
}
