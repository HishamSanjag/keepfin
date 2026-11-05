// A fake authorization server for the sign-in tests: the documented subset of the authorization-code grant with PKCE and refresh
// tokens that rotate, for the three OAuth providers' hosts. It plays the browser's part too: `approve` turns the authorization
// URL the app opened into the callback URL the browser would have been sent to.
import crypto from 'node:crypto'
import {FakeServer, json, unhost} from './base.js'
import type {FakeReply, FakeRequest} from './base.js'

export const OAUTH_HOSTS = ['accounts.google.com', 'oauth2.googleapis.com', 'login.microsoftonline.com', 'www.dropbox.com', 'api.dropboxapi.com']

interface Code {
  challenge: string
  redirectUri: string
  clientId: string
  used: boolean
}

export class FakeOAuth {
  readonly server: FakeServer
  readonly codes = new Map<string, Code>()
  /** The refresh tokens that are good right now (a used one is replaced: rotation). */
  readonly refreshTokens = new Set<string>()
  private n = 0
  accessLifetimeS = 3600
  rotate = true
  /** Refresh requests answered. */
  refreshCalls = 0
  /** Requests the token endpoint saw (form fields), for assertions. */
  readonly seen: Array<Record<string, string>> = []
  revoked = 0

  constructor() {
    this.server = new FakeServer((r) => this.handle(r), OAUTH_HOSTS)
  }

  get rewrite() {
    return this.server.rewrite
  }

  /** Every refresh token is refused from now on (the user revoked the grant). */
  revokeAll(): void {
    this.refreshTokens.clear()
  }

  /** The browser's part: the user approves (or denies); returns the URL the browser is sent back to. */
  approve(authUrl: string, options: {deny?: boolean; state?: string} = {}): string {
    const u = new URL(authUrl)
    const redirect = u.searchParams.get('redirect_uri')!
    const out = new URL(redirect)
    out.searchParams.set('state', options.state ?? u.searchParams.get('state')!)
    if (options.deny) {
      out.searchParams.set('error', 'access_denied')
      return out.toString()
    }
    const code = `code-${++this.n}`
    this.codes.set(code, {challenge: u.searchParams.get('code_challenge')!, redirectUri: redirect, clientId: u.searchParams.get('client_id')!, used: false})
    out.searchParams.set('code', code)
    return out.toString()
  }

  private tokens(): {access_token: string; refresh_token?: string; expires_in: number; token_type: string; scope: string} {
    const refresh = `refresh-${++this.n}`
    this.refreshTokens.add(refresh)
    return {access_token: `access-${++this.n}`, refresh_token: refresh, expires_in: this.accessLifetimeS, token_type: 'bearer', scope: 'files'}
  }

  private handle(req: FakeRequest): FakeReply {
    const {host, path} = unhost(req.url)
    if (/\/revoke$/.test(path) && req.method === 'POST') {
      this.revoked++
      return {status: 200, body: {}}
    }
    if (!/\/token$/.test(path) || req.method !== 'POST') return json(404, {error: 'not_found'})
    const form = Object.fromEntries(new URLSearchParams(req.body.toString('utf8')))
    this.seen.push({...form, __host: host})
    if (form.grant_type === 'authorization_code') {
      const code = this.codes.get(form.code ?? '')
      if (!code || code.used) return json(400, {error: 'invalid_grant', error_description: 'The code is not valid.'})
      code.used = true
      const challenge = crypto.createHash('sha256').update(form.code_verifier ?? '').digest('base64url')
      if (challenge !== code.challenge || form.redirect_uri !== code.redirectUri || form.client_id !== code.clientId) return json(400, {error: 'invalid_grant', error_description: 'PKCE verification failed.'})
      return json(200, this.tokens())
    }
    if (form.grant_type === 'refresh_token') {
      this.refreshCalls++
      const t = form.refresh_token ?? ''
      if (!this.refreshTokens.has(t)) return json(400, {error: 'invalid_grant', error_description: 'The refresh token is not valid.'})
      const next = this.tokens()
      if (this.rotate) this.refreshTokens.delete(t)
      else delete next.refresh_token
      return json(200, next)
    }
    return json(400, {error: 'unsupported_grant_type'})
  }
}
