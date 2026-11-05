// Finding devices when multicast does not work (dev-docs/plans/DEVICES-DISCOVERY.md, D2 and D3). DNS-SD alone fails on many routers and access
// points, so while the Devices page (or Add a device) is open Keepfin also asks the neighbours directly: every host of the /24 around each
// real network address gets a plain TCP connect on the Keepfin port (400 ms, 48 at a time), and only hosts that answer get the TLS handshake and
// `GET /v1/announce` (1.5 s). Online Tailscale devices are asked the same way. Everything learned here is a hint: the pin decides who a device is.
import net from 'node:net'
import type {DiscoveredDevice} from '../../shared/devices.js'
import {PeerClient} from './client.js'
import type {EligibleInterface} from './discovery.js'
import type {DeviceIdentity} from './identity.js'
import {detectTailscaleAddress, isTailscaleAddress, isTailscaleHost, type StatusReader, type TailscaleStatus} from './tailscale.js'
import type {NetLog} from './types.js'

export const SCAN_CONNECT_MS = 400
export const SCAN_ANNOUNCE_MS = 1500
export const SCAN_CONCURRENCY = 48
export const SCAN_EVERY_MS = 20_000
/** At most this many networks and this many hosts in one round (a /16 is never walked). */
const MAX_NETWORKS = 4
const MAX_TAILSCALE_PROBES = 64

/** What `GET /v1/announce` says: the same facts as the DNS-SD record. */
export interface Announcement {
  id: string
  name: string
  platform: string
  /** A typed pairing code is showing on that device (it does not say which). */
  code?: boolean
}

export function parseAnnouncement(body: unknown): Announcement | undefined {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return undefined
  const b = body as Record<string, unknown>
  if (b.v !== 1) return undefined
  const id = typeof b.id === 'string' ? b.id.toLowerCase() : ''
  if (!/^[0-9a-f]{16}$/.test(id)) return undefined
  const name = typeof b.n === 'string' ? b.n.trim().slice(0, 40) : ''
  if (!name) return undefined
  const platform = b.p === 'windows' || b.p === 'android' ? b.p : ''
  return {id, name, platform, ...(b.c === 1 ? {code: true} : {})}
}

/** The two network calls of a scan; tests replace them. */
export interface ScanLayer {
  /** True when something accepts a TCP connection at [host]:[port] within [timeoutMs]. */
  connect(host: string, port: number, timeoutMs: number): Promise<boolean>
  /** The device's announcement (TLS then GET /v1/announce), or undefined when it is not a Keepfin device or too slow. */
  announce(host: string, port: number, timeoutMs: number): Promise<Announcement | undefined>
}

export function realScanLayer(identity: () => Pick<DeviceIdentity, 'keyPem' | 'certPem'>): ScanLayer {
  return {
    connect: (host, port, timeoutMs) =>
      new Promise<boolean>((resolve) => {
        let done = false
        const socket = net.connect({host, port})
        const end = (ok: boolean) => {
          if (done) return
          done = true
          clearTimeout(timer)
          socket.destroy()
          resolve(ok)
        }
        const timer = setTimeout(() => end(false), timeoutMs)
        timer.unref?.()
        socket.once('connect', () => end(true))
        socket.once('error', () => end(false))
      }),
    announce: async (host, port, timeoutMs) => {
      const client = new PeerClient({identity: identity(), host, port, pin: null, connectTimeoutMs: timeoutMs})
      let timer: NodeJS.Timeout | undefined
      try {
        const body = await Promise.race([
          client.json('GET', '/v1/announce', {timeoutMs}),
          new Promise<never>((_resolve, reject) => {
            timer = setTimeout(() => reject(new Error('slow')), timeoutMs)
            timer.unref?.()
          }),
        ])
        const found = parseAnnouncement(body)
        // the key that signed the handshake must be the one the announcement names (a stranger cannot name someone else's key)
        if (!found || !client.peerDeviceId?.startsWith(found.id)) return undefined
        return found
      } catch {
        return undefined
      } finally {
        clearTimeout(timer)
        client.close()
      }
    },
  }
}

export interface Scanned extends Omit<DiscoveredDevice, 'paired'> {
  platform: string
}

const toInt = (address: string): number | undefined => {
  const m = /^(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(address)
  if (!m) return undefined
  const parts = m.slice(1).map(Number)
  if (parts.some((n) => n > 255)) return undefined
  return ((parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]) >>> 0
}
const fromInt = (n: number): string => [n >>> 24, (n >>> 16) & 255, (n >>> 8) & 255, n & 255].join('.')

const prefixOf = (netmask?: string): number => {
  const mask = netmask ? toInt(netmask) : undefined
  if (mask === undefined) return 24
  let bits = 0
  for (let i = 31; i >= 0 && (mask >>> i) & 1; i--) bits++
  return bits
}

/**
 * The hosts to ask: for each real (not Tailscale) IPv4 address the /24 around it (a smaller subnet only its own hosts), without the
 * addresses of this device. A network seen on two adapters is walked once; at most {@link MAX_NETWORKS} networks.
 */
export function hostsToScan(interfaces: EligibleInterface[], own: Iterable<string> = []): string[] {
  const skip = new Set<string>([...own, ...interfaces.map((i) => i.address)])
  const seen = new Set<number>()
  const hosts: string[] = []
  let networks = 0
  for (const iface of interfaces) {
    if (isTailscaleAddress(iface.address)) continue
    const address = toInt(iface.address)
    if (address === undefined) continue
    const prefix = Math.max(24, prefixOf(iface.netmask))
    const mask = prefix === 0 ? 0 : (0xffffffff << (32 - prefix)) >>> 0
    const network = (address & mask) >>> 0
    if (seen.has(network)) continue
    seen.add(network)
    if (++networks > MAX_NETWORKS) break
    const size = 2 ** (32 - prefix)
    for (let offset = 1; offset < size - 1; offset++) {
      const host = fromInt(network + offset)
      if (!skip.has(host)) hosts.push(host)
    }
  }
  return hosts
}

/** Runs [work] over [items], at most [limit] at a time, and stops early when [signal] aborts. */
export async function pool<T>(items: T[], limit: number, work: (item: T) => Promise<void>, signal?: AbortSignal): Promise<void> {
  let next = 0
  const worker = async () => {
    while (next < items.length && !signal?.aborted) await work(items[next++])
  }
  await Promise.all(Array.from({length: Math.min(limit, items.length)}, worker))
}

export interface ScanOptions {
  interfaces: EligibleInterface[]
  /** Addresses of this device (skipped). */
  own?: Iterable<string>
  /** The first 16 hex digits of this device's id: an announcement with it is this device. */
  selfHint: string
  port: number
  layer: ScanLayer
  concurrency?: number
  connectMs?: number
  announceMs?: number
  signal?: AbortSignal
}

/** One scan of the local networks. */
export async function scanNetworks(options: ScanOptions): Promise<Scanned[]> {
  const hosts = hostsToScan(options.interfaces, options.own)
  const found: Scanned[] = []
  await probeHosts(hosts, 'lan', options, found)
  return found
}

async function probeHosts(hosts: string[], route: 'lan' | 'tailscale', options: ScanOptions, into: Scanned[]): Promise<void> {
  const {layer, port, selfHint} = options
  await pool(
    hosts,
    options.concurrency ?? SCAN_CONCURRENCY,
    async (host) => {
      if (!(await layer.connect(host, port, options.connectMs ?? SCAN_CONNECT_MS).catch(() => false))) return
      const found = await layer.announce(host, port, options.announceMs ?? SCAN_ANNOUNCE_MS).catch(() => undefined)
      if (!found || found.id === selfHint) return
      into.push({hint: found.id, name: found.name, host, port, platform: found.platform, route})
    },
    options.signal,
  )
}

/**
 * The devices known by DNS-SD and by scanning, one per hint. The DNS-SD entry wins when both saw the device; a LAN entry beats a
 * Tailscale one (the same device on the home network is reached directly).
 */
export function mergeNearby(dnssd: DiscoveredDevice[], scanned: Scanned[]): DiscoveredDevice[] {
  const out = new Map<string, DiscoveredDevice>()
  for (const d of dnssd) if (!out.has(d.hint)) out.set(d.hint, {...d, route: d.route ?? 'lan'})
  const rank = (s: Scanned) => (s.route === 'lan' ? 0 : 1)
  for (const s of [...scanned].sort((a, b) => rank(a) - rank(b))) {
    if (out.has(s.hint)) continue
    out.set(s.hint, {hint: s.hint, name: s.name, host: s.host, port: s.port, paired: false, route: s.route, platform: s.platform})
  }
  return [...out.values()]
}

export interface TailscaleInfo {
  state: 'off' | 'on'
  name?: string
  address?: string
}

export interface NearbyScannerDeps {
  interfaces: () => EligibleInterface[]
  selfHint: string
  /** The port other devices listen on (Keepfin's own is 47215). */
  port: number
  layer: ScanLayer
  /** Reads `tailscale status --json`. Absent: only the adapter tells whether Tailscale is on. */
  tailscaleStatus?: StatusReader
  /** This PC's own adapter list (tests inject theirs). */
  adapters?: () => NodeJS.Dict<import('node:os').NetworkInterfaceInfo[]>
  changed: () => void
  log?: NetLog
  everyMs?: number
  connectMs?: number
  announceMs?: number
  concurrency?: number
}

/** Runs the scan while someone is looking (see {@link start}); keeps the last round's answers. */
export class NearbyScanner {
  private lan: Scanned[] = []
  private tailscaleFound: Scanned[] = []
  private status?: TailscaleStatus
  private timer?: NodeJS.Timeout
  private running?: Promise<void>
  private controller?: AbortController
  private active = false
  private signature = ''

  constructor(private readonly deps: NearbyScannerDeps) {}

  /** Scans at once, then every 20 seconds, until {@link stop}. */
  start(): void {
    if (this.active) return
    this.active = true
    void this.round()
    this.timer = setInterval(() => void this.round(), this.deps.everyMs ?? SCAN_EVERY_MS)
    this.timer.unref?.()
  }

  stop(): void {
    this.active = false
    clearInterval(this.timer)
    this.timer = undefined
    this.controller?.abort()
    this.lan = []
    this.tailscaleFound = []
    this.signature = ''
  }

  get isActive(): boolean {
    return this.active
  }

  /** The devices found by the last round (LAN first). */
  list(): Scanned[] {
    return [...this.lan, ...this.tailscaleFound]
  }

  /** One round now (shared with a round already running). Used by the timer and by a sync that has no known address for a paired device. */
  round(): Promise<void> {
    if (this.running) return this.running
    const controller = new AbortController()
    this.controller = controller
    this.running = this.run(controller.signal)
      .catch((error) => this.deps.log?.(`scan failed: ${error instanceof Error ? error.message : String(error)}`))
      .finally(() => {
        this.running = undefined
      })
    return this.running
  }

  private async run(signal: AbortSignal): Promise<void> {
    const interfaces = this.deps.interfaces()
    const options: ScanOptions = {
      interfaces,
      own: [],
      selfHint: this.deps.selfHint,
      port: this.deps.port,
      layer: this.deps.layer,
      concurrency: this.deps.concurrency,
      connectMs: this.deps.connectMs,
      announceMs: this.deps.announceMs,
      signal,
    }
    const lan: Scanned[] = []
    await probeHosts(hostsToScan(interfaces), 'lan', options, lan)
    const tailscale: Scanned[] = []
    await this.readTailscale()
    if (this.status?.running) {
      const peers = this.status.peers.filter((p) => p.online && p.address !== this.status?.address).slice(0, MAX_TAILSCALE_PROBES)
      await probeHosts(
        peers.map((p) => p.address),
        'tailscale',
        options,
        tailscale,
      )
    }
    if (signal.aborted) return
    this.lan = lan
    this.tailscaleFound = tailscale
    const signature = JSON.stringify(this.list().map((d) => [d.hint, d.host, d.name]).sort())
    if (signature !== this.signature) {
      this.signature = signature
      this.deps.changed()
    }
  }

  /** Reads Tailscale's status (when its program exists) and keeps the answer; the adapter alone says whether it is on. */
  async readTailscale(): Promise<void> {
    if (!this.deps.tailscaleStatus) return
    try {
      const before = JSON.stringify([this.status?.running, this.status?.name, this.status?.address])
      this.status = await this.deps.tailscaleStatus()
      if (JSON.stringify([this.status?.running, this.status?.name, this.status?.address]) !== before) this.deps.changed()
    } catch {
      this.status = undefined
    }
  }

  /** Whether this PC is on a tailnet, with its MagicDNS name when the Tailscale program told it. */
  tailscale(): TailscaleInfo {
    // the adapter is the live truth (the status is read every 20 s while someone looks, and once at the start); the status adds the name
    const address = detectTailscaleAddress(this.deps.adapters?.())
    if (!address) return {state: 'off'}
    return {state: 'on', ...(this.status?.running && this.status.name ? {name: this.status.name} : {}), address}
  }

  /** The MagicDNS name of this PC, once known (told to paired devices in hello). */
  magicName(): string | undefined {
    const name = this.status?.name
    return name && isTailscaleHost(name) ? name : undefined
  }
}
