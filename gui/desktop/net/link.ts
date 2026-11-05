// One paired peer, kept in step (spec 4, 7): connect with the pinned key, say hello, listen to its change notices, and sync the
// libraries both share. An unreachable peer is retried with exponential back-off (at most every 30 minutes, jittered); a change of
// network, a change in our own library or a notice from the peer makes the next pass happen at once.
import type WebSocket from 'ws'
import {PeerClient, PeerHttpError, PinMismatch} from './client.js'
import type {DeviceIdentity} from './identity.js'
import type {Peer, PeerStore} from './peers.js'
import {parseVector} from './segments.js'
import {PROTOCOL_VERSIONS} from './server.js'
import {syncLibrary} from './sync.js'
import {isTailscaleHost} from './tailscale.js'
import type {NetLibrary, NetLog} from './types.js'

export interface HelloAnswer {
  version: number
  name: string
  deviceId: string
  platform?: string
  libraries: {libraryId: string; name: string; actor: string; actions: string[]}[]
  /** How many libraries the peer holds that it has not shared with this device (absent from older versions). */
  unshared?: number
  capabilities?: {segments?: boolean; inventory?: boolean; revisions?: boolean; copies?: boolean; join?: boolean; jobs?: boolean}
  /** How the peer can be reached (its eligible IPv4 addresses, a MagicDNS name): kept as hints for reconnecting. */
  addresses?: string[]
  /** Where this peer lets us save a job ("Download on..."); empty when it does not take jobs from us. */
  jobSpaces?: {id: string; name: string}[]
}

export interface LinkDeps {
  identity: () => DeviceIdentity
  peers: PeerStore
  peerId: string
  libraries: () => NetLibrary[]
  /** Where this peer might be right now: what DNS-SD, the scan and Tailscale saw (tried before the addresses kept in the pin). */
  hints: () => {host: string; port: number}[]
  /** Asked once when every address failed: scans the network and answers where the peer was seen (throttled by the service). */
  lookup?: () => Promise<{host: string; port: number}[]>
  now: () => number
  log?: NetLog
  /** The connection state or a sync result changed (the page shows "last seen"). */
  changed: () => void
  /** A pass finished with these counts. */
  synced?: (report: {pulled: number; pushed: number; rejected: number}) => void
  backoffMinMs?: number
  backoffMaxMs?: number
  /** A connection is up and the peer said hello (jobs are delivered, offers are read). */
  connected?: (client: PeerClient, hello: HelloAnswer) => void
  /** A notice other than the library vectors arrived (the progress of a job). */
  event?: (message: Record<string, unknown>) => void
  /** The peer could not be reached (called with the number of failed attempts in a row), and when it could again. */
  unreachable?: (failures: number) => void
  reachable?: () => void
}

/** Exponential back-off (doubling from [minMs]) with a jitter of plus or minus a quarter, and never longer than [maxMs]: 30 minutes by default (spec 7). */
export function backoffDelay(failures: number, minMs = 2000, maxMs = 30 * 60_000, random: () => number = Math.random): number {
  return Math.min(maxMs, minMs * 2 ** Math.min(Math.max(failures, 0), 20) * (0.75 + random() * 0.5))
}

export type Hint = {host: string; port: number}

/**
 * The addresses to try for a paired device, in this order (dev-docs/plans/DEVICES-DISCOVERY.md D3): what DNS-SD, the scan or Tailscale saw now, the address
 * that worked last, the addresses kept from its hello that are on a home network, then its Tailscale addresses and name. Duplicates are tried once.
 */
export function reconnectOrder(input: {discovered: Hint[]; lastHost?: string; lastPort?: number; addresses?: string[]; defaultPort?: number}): Hint[] {
  const port = input.lastPort ?? input.defaultPort ?? 47215
  const stored = input.addresses ?? []
  const all: Hint[] = [
    ...input.discovered,
    ...(input.lastHost ? [{host: input.lastHost, port}] : []),
    ...stored.filter((a) => !isTailscaleHost(a)).map((host) => ({host, port})),
    ...stored.filter((a) => isTailscaleHost(a)).map((host) => ({host, port})),
  ]
  return all.filter((h, i) => all.findIndex((x) => x.host === h.host && x.port === h.port) === i)
}

export class PeerLink {
  online = false
  /** When the last sync pass with the peer finished, and how many library changes it took in and sent (Devices shows it). */
  lastSync: {at: number; pulled: number; pushed: number} | null = null
  /** The peer needs an update (or we do) before they can talk. */
  versionNote: string | null = null
  private stopped = false
  private client?: PeerClient
  private socket?: WebSocket
  private timer?: NodeJS.Timeout
  private failures = 0
  private pass: Promise<void> = Promise.resolve()
  private wake?: () => void
  private hello?: HelloAnswer
  private dirty = new Set<string>()
  private syncFailed = false
  private firstPassDone!: () => void
  private readonly firstPass = new Promise<void>((resolve) => (this.firstPassDone = resolve))

  constructor(private readonly deps: LinkDeps) {}

  start(): void {
    void this.loop()
  }

  stop(): void {
    this.stopped = true
    clearTimeout(this.timer)
    this.socket?.terminate()
    this.client?.close()
    this.wake?.()
    if (this.online) {
      this.online = false
      this.deps.changed()
    }
  }

  /** Resolves when the first sync pass with this peer ended (or after [timeoutMs], or if it could not connect). */
  async settled(timeoutMs: number): Promise<void> {
    await Promise.race([this.firstPass, new Promise<void>((resolve) => setTimeout(resolve, timeoutMs).unref?.())])
  }

  /** Something may have changed here or on the network: sync now (or connect now when waiting to retry). */
  kick(): void {
    for (const lib of this.deps.libraries()) this.dirty.add(lib.libraryId)
    if (this.online) void this.syncDirty()
    else {
      this.failures = 0
      clearTimeout(this.timer)
      this.wake?.()
    }
  }

  private get peer(): Peer | undefined {
    return this.deps.peers.get(this.deps.peerId)
  }

  private async loop(): Promise<void> {
    while (!this.stopped && this.peer) {
      try {
        this.syncFailed = false
        await this.session()
        this.failures = this.syncFailed ? this.failures + 1 : 0
      } catch (error) {
        if (error instanceof PinMismatch) this.deps.log?.('a device answered with a different key than the one paired')
        this.failures++
      }
      if (this.online) {
        this.online = false
        this.deps.changed()
      }
      if (!this.stopped && this.failures > 0) this.deps.unreachable?.(this.failures)
      this.firstPassDone() // nobody should wait on a link that cannot connect
      if (this.stopped) break
      const min = this.deps.backoffMinMs ?? 2000
      const max = this.deps.backoffMaxMs ?? 30 * 60_000
      const delay = backoffDelay(this.failures, min, max)
      this.nextDelayMs = delay
      await new Promise<void>((resolve) => {
        this.wake = resolve
        this.timer = setTimeout(resolve, delay)
        this.timer.unref?.()
      })
    }
  }

  private async session(): Promise<void> {
    const peer = this.peer
    if (!peer) return
    let lastError: unknown
    const tried: Hint[] = []
    const candidates = (list: Hint[]) => list.filter((h) => !tried.some((x) => x.host === h.host && x.port === h.port))
    let round = reconnectOrder({discovered: this.deps.hints(), lastHost: peer.lastHost, lastPort: peer.lastPort, addresses: peer.addresses})
    for (let pass = 0; pass < 2; pass++) {
      for (const hint of candidates(round)) {
        tried.push(hint)
        const client = new PeerClient({identity: this.deps.identity(), host: hint.host, port: hint.port, pin: peer.deviceId, connectTimeoutMs: tried.length === 1 ? 6000 : 4000})
        try {
          const hello = await client.json<HelloAnswer>('GET', `/v1/hello?v=${PROTOCOL_VERSIONS.join(',')}`, {timeoutMs: 8000})
          this.client = client
          this.hello = hello
          this.versionNote = null
          this.lastAddress = {host: hint.host, port: hint.port}
          this.deps.peers.touch(peer.deviceId, this.deps.now(), hint.host, hint.port)
          this.deps.peers.rememberAddresses(peer.deviceId, hello.addresses)
          this.deps.peers.rememberName(peer.deviceId, hello.name)
          await this.connected(client, hello)
          return
        } catch (error) {
          client.close()
          lastError = error
          if (error instanceof PeerHttpError && error.status === 426) {
            this.versionNote = error.message
            this.deps.changed()
          }
          if (error instanceof PeerHttpError && error.status === 403) throw error // not paired on their side any more
        }
      }
      // nothing answered: look for the device on the network once, then try what that found
      if (pass === 0 && this.deps.lookup) round = await this.deps.lookup().catch(() => [])
      else break
    }
    throw lastError ?? new Error('no address')
  }
  /** The address the last successful connection used. */
  lastAddress?: {host: string; port: number}

  /** What the peer said in its last hello (its libraries and what it lets us do, where jobs may be saved). */
  get lastHello(): HelloAnswer | undefined {
    return this.hello
  }

  /** The delay before the next attempt, as scheduled (tests read the back-off from it). */
  nextDelayMs = 0

  private refreshing = false
  /** Reads the peer's hello again (it shares something new, or changed what this device may do). */
  async refreshHello(): Promise<void> {
    const client = this.client
    if (!client || !this.online || this.refreshing) return
    this.refreshing = true
    try {
      const hello = await client.json<HelloAnswer>('GET', `/v1/hello?v=${PROTOCOL_VERSIONS.join(',')}`, {timeoutMs: 8000})
      this.hello = hello
      this.deps.peers.rememberAddresses(this.deps.peerId, hello.addresses)
      this.deps.peers.rememberName(this.deps.peerId, hello.name)
      this.deps.connected?.(client, hello)
      this.deps.changed()
    } catch {
      // the connection is going away; the next session reads it anyway
    } finally {
      this.refreshing = false
    }
  }

  /** Runs [fn] on the live connection; undefined when the peer is not connected right now. */
  async withClient<T>(fn: (client: PeerClient) => Promise<T>): Promise<T | undefined> {
    const client = this.client
    if (!client || !this.online) return undefined
    return fn(client)
  }

  private async connected(client: PeerClient, hello: HelloAnswer): Promise<void> {
    this.online = true
    this.deps.reachable?.()
    this.deps.changed()
    this.deps.connected?.(client, hello)
    for (const lib of this.deps.libraries()) this.dirty.add(lib.libraryId)
    const closed = new Promise<void>((resolve) => {
      this.socket = client.events(
        (message) => {
          const m = message as {t?: string; library?: string; vector?: string}
          if (m.t === 'job') return void this.deps.event?.(message as Record<string, unknown>)
          // the peer changed what this device may do, or its own name
          if (m.t === 'access' || m.t === 'hello') return void this.refreshHello()
          if (m.t !== 'segments' || typeof m.library !== 'string' || typeof m.vector !== 'string') return
          const theirs = parseVector(m.vector)
          if (!theirs) return
          // a library we have not heard of: the peer started sharing something, so its hello is read again
          if (!this.hello?.libraries.some((l) => l.libraryId === m.library)) void this.refreshHello()
          // anything the peer has beyond what we hold (or anything at all, on the first notice) is worth a pass
          this.dirty.add(m.library)
          void this.syncDirty()
        },
        () => resolve(),
      )
    })
    await this.syncDirty()
    this.firstPassDone()
    await closed
    this.socket = undefined
    client.close()
    if (this.client === client) this.client = undefined
    void hello
  }

  /** One pass per dirty library, one at a time. */
  private syncDirty(): Promise<void> {
    this.pass = this.pass.then(async () => {
      const client = this.client
      const hello = this.hello
      if (!client || !hello) return
      for (const libraryId of [...this.dirty]) {
        this.dirty.delete(libraryId)
        const lib = this.deps.libraries().find((l) => l.libraryId === libraryId)
        const offered = hello.libraries.find((l) => l.libraryId === libraryId)
        if (!lib || !offered) continue
        try {
          const report = await syncLibrary(client, lib)
          this.lastSync = {at: this.deps.now(), pulled: report.pulled, pushed: report.pushed}
          this.deps.changed()
          if (report.pulled || report.pushed || report.rejected) this.deps.synced?.(report)
        } catch (error) {
          this.deps.log?.(`sync of a library failed: ${error instanceof Error ? error.message : String(error)}`)
          this.dirty.add(libraryId)
          this.syncFailed = true
          this.socket?.terminate() // reconnect with back-off
        }
      }
    })
    return this.pass
  }
}
