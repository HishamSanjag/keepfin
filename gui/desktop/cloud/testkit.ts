// Test helpers for the cloud transport: accounts with a fixed token, a client wired to a fake provider with no real waiting.
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {CloudProvider} from '../../shared/core/cloud.js'
import type {SecretBox} from './accounts.js'
import {CloudClient} from './client.js'
import {CloudHttp} from './http.js'
import type {CloudAccount, HttpOptions} from './http.js'
import {MemoryJournal} from './journal.js'
import {CloudTransport} from './transport.js'

export class StaticAccount implements CloudAccount {
  refreshes = 0
  tokens = ['T1', 'T2', 'T3']
  constructor(readonly provider: CloudProvider, readonly id = 'acct', private auth?: string) {}
  async authorization() {
    return this.auth ?? `Bearer ${this.tokens[Math.min(this.refreshes, this.tokens.length - 1)]}`
  }
  async refresh() {
    this.refreshes++
    return true
  }
}

export interface Rig {
  http: CloudHttp
  client: CloudClient
  transport: CloudTransport
  journal: MemoryJournal
  sleeps: number[]
  account: StaticAccount
}

export function rig(provider: CloudProvider, rewrite: (u: string) => string, extra: {base?: string; auth?: string; rootPath?: string; now?: () => number; http?: HttpOptions} = {}): Rig {
  const sleeps: number[] = []
  const account = new StaticAccount(provider, 'acct', extra.auth)
  const http = new CloudHttp(account, {rewrite, sleep: async (ms) => void sleeps.push(ms), random: () => 0.5, baseDelayMs: 100, ...extra.http})
  const journal = new MemoryJournal()
  const client = new CloudClient(http, {base: extra.base, now: extra.now}, journal)
  const transport = new CloudTransport({client, rootPath: extra.rootPath ?? 'Keepfin', now: extra.now})
  return {http, client, transport, journal, sleeps, account}
}

export async function tmpDir(): Promise<string> {
  return fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-cloud-'))
}

export const contentIdOf = (b: Buffer) => 'sha256:' + crypto.createHash('sha256').update(b).digest('hex')

export async function writeTemp(dir: string, name: string, data: Buffer): Promise<string> {
  const file = path.join(dir, name)
  await fs.writeFile(file, data)
  return file
}

// An in-memory stand-in for the platform's secure storage (AES-GCM with a throwaway key).
const KEY = crypto.randomBytes(32)
export const testBox = (available = true): SecretBox => ({
  available: () => available,
  encrypt(plain) {
    const iv = crypto.randomBytes(12)
    const c = crypto.createCipheriv('aes-256-gcm', KEY, iv)
    const body = Buffer.concat([c.update(plain, 'utf8'), c.final()])
    return Buffer.concat([iv, c.getAuthTag(), body])
  },
  decrypt(blob) {
    const d = crypto.createDecipheriv('aes-256-gcm', KEY, blob.subarray(0, 12))
    d.setAuthTag(blob.subarray(12, 28))
    return Buffer.concat([d.update(blob.subarray(28)), d.final()]).toString('utf8')
  },
})

