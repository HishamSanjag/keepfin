// Devices on the network, as one service (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md): this device's identity, the server, discovery, pairing,
// the paired devices and a link to each, and the operations Settings → Devices asks for. Free of Electron imports; main passes the
// secure storage in. Nothing starts listening until the person turns devices on (or devices were on before).
import {randomUUID} from 'node:crypto'
import os from 'node:os'
import path from 'node:path'
import QRCode from 'qrcode'
import {deviceIdOf, pairPin, parsePairCode, parsePairQr, DEFAULT_GRANTS, type NetAction} from '../../shared/core/net.js'
import {DEFAULT_PORT, PAIRING_WINDOW_MS, parseAddress, type DeviceAction, type DevicesArgs, type DiscoveredDevice, type DevicesState, type JoinView, type LibraryOffer, type PairedDevice, type PairingView, type PlayFromResult, type RemoteJobRow} from '../../shared/devices.js'
import {mediaKindOf} from '../../shared/library.js'
import {readJson, writeJsonAtomic} from '../store/atomic.js'
import type {Readable} from 'node:stream'
import {PeerHttpError, type PeerClient} from './client.js'
import {askToPair, AskError} from './initiator.js'
import type {FirewallPort} from './firewall.js'
import {Discovery, eligibleInterfaces, type EligibleInterface} from './discovery.js'
import {mergeNearby, NearbyScanner, realScanLayer, type ScanLayer} from './scan.js'
import {isTailscaleHost, windowsStatusReader, type StatusReader} from './tailscale.js'
import {fingerprintOf, loadIdentity, renameDevice, type DeviceIdentity, type KeyBox} from './identity.js'
import {DEFAULT_LIMITS, type Limits} from './limits.js'
import {isTerminalJob, JobsHost, OutgoingJobs, type JobPort} from './jobs.js'
import {JoinError, runJoin, type Joined} from './join.js'
import {PeerLink, type HelloAnswer} from './link.js'
import {PairingHost, type PairPeerInfo} from './pairing.js'
import {PeerExecutor} from './peerExecutor.js'
import type {PeerBridge} from './peerPlace.js'
import {PeerStore, type Peer} from './peers.js'
import {isActorId, listSegments, vectorOf} from './segments.js'
import {NetServer} from './server.js'
import {pullSegments, syncLibrary} from './sync.js'
import type {NetLibrary, NetLog} from './types.js'

/**
 * Why a connected device offers no library: it has none (no Keepfin folder), it has one it has not shared with this device yet, this
 * device holds them all, or it may read none of them.
 */
export type OfferGap = 'none' | 'unshared' | 'held' | 'denied'

/** The gap a hello shows (an older peer does not count what it has not shared: then "not shared" is the honest guess). */
export function offerGapOf(hello: Pick<HelloAnswer, 'libraries' | 'unshared'>, held: Set<string>): OfferGap {
  if (!hello.libraries.length) return hello.unshared === 0 ? 'none' : 'unshared'
  return hello.libraries.every((l) => held.has(l.libraryId)) ? 'held' : 'denied'
}

export interface NetServiceDeps {
  deviceDir: string
  box: KeyBox
  libraries: () => NetLibrary[]
  push: (state: DevicesState) => void
  now?: () => number
  limits?: Partial<Limits>
  /** 47215 by default. A conflict is reported, never worked around (spec 9.1 #10). Tests pass 0. */
  port?: number
  /** The address to listen on (all interfaces by default). */
  bind?: string
  /** `false`: no multicast at all (tests). */
  discovery?: false | {interfaces?: () => EligibleInterface[]}
  log?: NetLog
  /** The host a QR code names (tests: loopback). By default the first eligible network address. */
  advertiseHost?: () => string | null
  backoff?: {minMs: number; maxMs: number}
  leaseMs?: number
  /** Downloads other devices ask for ("Download on…"): the queue behind it. Absent: this device takes none. */
  jobs?: JobPort
  /** Replaces the link check of incoming jobs (tests). */
  checkUrl?: (url: string) => Promise<string>
  /** A library was taken in from a paired device: make it a library profile of this device. Absent: the folder is left for the person. */
  adoptJoined?: (joined: Joined) => Promise<void>
  /** Free-space floor for incoming jobs, in bytes (tests). */
  minFreeBytes?: number
  /** How often the network interfaces are compared (10 s by default). */
  networkWatchMs?: number
  /** Windows: reads whether the firewall lets other devices in on the current network and can add the allow rules. Absent: no check. */
  firewall?: FirewallPort
  /** How often the firewall is read again while devices are on (60 s by default). */
  firewallMs?: number
  /**
   * The scan of the local network and Tailscale (dev-docs/plans/DEVICES-DISCOVERY.md D2). On by default whenever discovery is; `false` turns it off.
   * Tests pass their own network calls, adapter list and port.
   */
  scan?: false | {layer?: ScanLayer; interfaces?: () => EligibleInterface[]; port?: number; everyMs?: number; connectMs?: number; announceMs?: number; concurrency?: number}
  /** Tailscale: how its status is read (the real one runs `tailscale status --json` when the program is installed) and which adapters count. */
  tailscale?: {status?: StatusReader; adapters?: () => NodeJS.Dict<os.NetworkInterfaceInfo[]>}
  /** The network adapters that count as this device's addresses (the real, eligible ones by default; tests pass loopback). */
  interfaces?: () => EligibleInterface[]
  /** The least time between two scans started by a sync that found no address for a paired device (60 s by default). */
  lookupEveryMs?: number
  /** Pairing waits for the other device to open Add a device: how often it asks again and for how long (tests shrink both). */
  waitForPeer?: {intervalMs?: number; deadlineMs?: number; sleep?: (ms: number, signal?: AbortSignal) => Promise<void>}
}

type Asking = {view: PairingView; decision?: (match: boolean) => void; /** The person here said the numbers do not match (the message is theirs, not the other device's). */ declinedHere?: boolean; controller: AbortController; /** This device followed the pairing another device started instead: this one is dropped quietly. */ yielded?: boolean}

const ALL_ACTIONS: ReadonlySet<string> = new Set(['metadata.read', 'metadata.write', 'inventory.read', 'media.read', 'copy.mutate', 'jobs', 'membership.admin'])

const safeDecode = (text: string): string => {
  try {
    return decodeURIComponent(text)
  } catch {
    return ''
  }
}

export class NetService {
  readonly peers: PeerStore
  readonly identity: DeviceIdentity
  readonly pairing: PairingHost
  server?: NetServer
  discovery?: Discovery
  readonly links = new Map<string, PeerLink>()
  private readonly executors = new Map<string, PeerExecutor>()
  private enabled = false
  private port: number | undefined
  private portError: string | undefined
  private asking: Asking | undefined
  private qr: DevicesState['qr']
  private notes = new Map<string, string>()
  private libraryInfo = new Map<string, {signed: boolean; admin: boolean}>()
  private pushTimer?: NodeJS.Timeout
  private netTimer?: NodeJS.Timeout
  private profileTimer?: NodeJS.Timeout
  private firewall: {blocked: boolean; category: string; notice?: string} = {blocked: false, category: 'unknown'}
  private checkingProfile = false
  private scanner?: NearbyScanner
  /** Who wants devices looked for: the Devices page (`page`) and Add a device (`pairing`). Nothing is looked for when this is empty. */
  private readonly watchers = new Set<string>()
  private lastLookup = 0
  private closed = false
  private readonly now: () => number
  private unwatch: (() => void)[] = []
  /** Downloads other devices asked for (this device is the receiver). */
  readonly jobsHost?: JobsHost
  /** Downloads this device asked other devices for (this device is the sender). */
  readonly outgoing: OutgoingJobs
  private offers = new Map<string, {libraryId: string; name: string}[]>()
  private offerGaps = new Map<string, OfferGap>()
  private join: {view: JoinView; controller: AbortController} | undefined
  private playTokens = new Map<string, {deviceId: string; libraryId: string; hex: string; size: number; name: string; expires: number}>()

  private constructor(
    private readonly deps: NetServiceDeps,
    identity: DeviceIdentity,
    peers: PeerStore,
    outgoing: OutgoingJobs,
    jobsHost?: JobsHost,
  ) {
    this.identity = identity
    this.peers = peers
    this.outgoing = outgoing
    this.jobsHost = jobsHost
    this.now = deps.now ?? Date.now
    this.pairing = new PairingHost({
      identity: () => this.identity,
      limits: this.limitsFor(),
      now: this.now,
      libraries: () => this.deps.libraries().map((l) => ({libraryId: l.libraryId, actor: l.actor()})),
      onPaired: (peer, method) => this.onPaired(peer, method),
      changed: () => {
        this.yieldToResponder()
        this.changed()
      },
    })
  }

  /**
   * This device was waiting for another one to open its side of pairing, and that device just asked this one instead: the pairing it
   * started is the one to follow now, so the waiting is dropped and the page shows the numbers.
   */
  /** Another device is pairing with this one right now (this device is the one answering). */
  private responding(): boolean {
    const view = this.pairing.view()
    return !!view && view.stage !== 'done' && view.stage !== 'failed'
  }

  private yieldToResponder(): void {
    const asking = this.asking
    if (asking?.view.stage === 'waitingForPeer' && this.responding()) {
      asking.yielded = true
      asking.controller.abort()
      this.asking = undefined
      asking.decision?.(false)
    }
  }

  private limitsFor(): Limits {
    return {...DEFAULT_LIMITS, ...this.deps.limits}
  }

  static async create(deps: NetServiceDeps): Promise<NetService> {
    const identity = await loadIdentity(deps.deviceDir, deps.box, deps.now)
    const peers = await PeerStore.open(deps.deviceDir)
    const outgoing = await OutgoingJobs.open(path.join(deps.deviceDir, 'net', 'outgoing-jobs.json'), deps.now)
    let service: NetService | undefined
    const jobsHost = deps.jobs
      ? await JobsHost.open({
          file: path.join(deps.deviceDir, 'net', 'jobs.json'),
          port: deps.jobs,
          now: deps.now,
          checkUrl: deps.checkUrl,
          minFreeBytes: deps.minFreeBytes,
          notify: (peerId, message) => service?.server?.sendTo(peerId, message),
        })
      : undefined
    service = new NetService(deps, identity, peers, outgoing, jobsHost)
    const saved = await readJson<{enabled?: boolean}>(path.join(deps.deviceDir, 'net', 'state.json')).catch(() => undefined)
    if (saved?.enabled) await service.enable().catch((error) => deps.log?.(`devices could not start: ${error instanceof Error ? error.message : String(error)}`))
    return service
  }

  // ---- switching on and off ------------------------------------------------------------------------------------------------

  get isEnabled(): boolean {
    return this.enabled
  }

  get listeningPort(): number | undefined {
    return this.port
  }

  /** Starts the server, discovery and the links; makes each library's membership root first. */
  async enable(): Promise<void> {
    if (this.enabled) return
    this.enabled = true
    await writeJsonAtomic(path.join(this.deps.deviceDir, 'net', 'state.json'), {enabled: true})
    this.server = new NetServer({
      identity: () => this.identity,
      peers: this.peers,
      libraries: () => this.deps.libraries(),
      executor: (id) => this.executorFor(id),
      pairing: this.pairing,
      now: this.now,
      limits: this.deps.limits,
      log: this.deps.log,
      seen: (peer, address) => this.peers.touch(peer.deviceId, this.now(), address, peer.lastPort),
      jobs: this.jobsHost ? (caller, body) => this.jobsHost!.handle({deviceId: caller.deviceId, name: caller.peer?.name ?? 'A device'}, body) : undefined,
      jobSpaces: this.deps.jobs ? () => this.deps.jobs!.spaces() : undefined,
      join: (caller, libraryId, body) => this.joinRequest(caller.deviceId, libraryId, body),
      addresses: () => this.ownAddresses(),
    })
    try {
      this.port = await this.server.start(this.deps.port ?? DEFAULT_PORT, this.deps.bind)
      this.portError = undefined
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code
      this.portError =
        code === 'EADDRINUSE'
          ? `Another program is using port ${this.deps.port ?? DEFAULT_PORT}. Close it, then turn devices off and on again. Keepfin keeps this port fixed so other devices can find you.`
          : `Devices could not start (${(error as Error).message}).`
      this.server = undefined
      this.port = undefined
    }
    if (this.port !== undefined && this.deps.discovery !== false) {
      this.discovery = new Discovery({
        deviceId: this.identity.deviceId,
        name: this.identity.name,
        port: this.port,
        log: this.deps.log,
        isPaired: (hint) => this.peers.list().some((p) => p.deviceId.startsWith(hint)),
        changed: () => this.changed(),
        interfaces: this.deps.discovery?.interfaces,
      })
      this.discovery.start()
    }
    const scan = this.deps.scan
    if (this.port !== undefined && (typeof scan === 'object' || (scan !== false && this.deps.discovery !== false))) {
      const options = typeof scan === 'object' ? scan : {}
      this.scanner = new NearbyScanner({
        interfaces: () => this.adapters(),
        selfHint: this.identity.deviceId.slice(0, 16),
        port: options.port ?? DEFAULT_PORT,
        layer: options.layer ?? realScanLayer(() => this.identity),
        tailscaleStatus: this.deps.tailscale ? this.deps.tailscale.status : windowsStatusReader(),
        adapters: this.deps.tailscale?.adapters,
        changed: () => this.changed(),
        log: this.deps.log,
        everyMs: options.everyMs,
        connectMs: options.connectMs,
        announceMs: options.announceMs,
        concurrency: options.concurrency,
      })
      void this.scanner.readTailscale()
      if (this.watchers.size) this.scanner.start()
    }
    if (this.port !== undefined) {
      for (const peer of this.peers.list()) this.startLink(peer)
      for (const lib of this.deps.libraries()) {
        this.unwatch.push(lib.onChange(() => this.libraryChanged()))
      }
      this.watchNetwork()
    }
    this.watchProfile()
    void this.refreshLibraryInfo()
    this.changed()
  }

  async disable(): Promise<void> {
    if (!this.enabled) return
    this.enabled = false
    await writeJsonAtomic(path.join(this.deps.deviceDir, 'net', 'state.json'), {enabled: false})
    this.pairing.close()
    this.stopRuntime()
    await this.server?.close()
    this.server = undefined
    this.port = undefined
    this.changed()
  }

  /** A change of network (Wi-Fi switched, a VPN or Tailscale came up) makes every unreachable device be tried at once (spec 7). */
  private watchNetwork(): void {
    const signature = () =>
      JSON.stringify(
        Object.entries(os.networkInterfaces())
          .map(([name, list]) => [name, (list ?? []).filter((a) => !a.internal).map((a) => a.address).sort()])
          .sort(),
      )
    let last = signature()
    this.netTimer = setInterval(() => {
      const now = signature()
      if (now === last) return
      last = now
      this.kickAll()
      this.changed() // Tailscale or the home network came or went: the page says where this device is visible
      if (this.scanner?.isActive) void this.scanner.round()
    }, this.deps.networkWatchMs ?? 10_000)
    this.netTimer.unref?.()
  }

  // ---- Windows Firewall --------------------------------------------------------------------------------------------------------

  private watchProfile(): void {
    if (!this.deps.firewall) return
    void this.checkNetwork()
    this.profileTimer = setInterval(() => void this.checkNetwork(), this.deps.firewallMs ?? 60_000)
    this.profileTimer.unref?.()
  }

  /** Reads the firewall again. Never throws; pushes only when the answer changed. */
  async checkNetwork(): Promise<void> {
    const port = this.deps.firewall
    if (!port || !this.enabled || this.checkingProfile) return
    this.checkingProfile = true
    try {
      const found = await port.check().catch(() => undefined)
      if (this.closed || !this.enabled) return
      const next = found ?? {blocked: false, category: 'unknown'}
      if (next.blocked === this.firewall.blocked && next.category === this.firewall.category) return
      this.firewall = {...next, ...(next.blocked && this.firewall.notice ? {notice: this.firewall.notice} : {})}
      this.changed()
    } finally {
      this.checkingProfile = false
    }
  }

  /** "Allow Keepfin": Windows asks the person for permission once and both rules are added; the answer is kept as a calm note on the card. */
  async allowFirewall(): Promise<void> {
    const port = this.deps.firewall
    if (!port) return
    const result = await port.allow()
    if (result === 'declined') this.firewall.notice = 'Windows did not get your permission, so nothing was changed. You can try again.'
    else if (result === 'failed') this.firewall.notice = 'Windows could not add the rules. Open Windows Security, Firewall and network protection, Allow an app through firewall, and allow Keepfin.'
    else this.firewall.notice = undefined
    this.changed()
    await this.checkNetwork()
    if (result === 'done' && this.firewall.blocked) {
      this.firewall.notice = 'The rules were added, but Windows still blocks Keepfin here. A rule of your organisation may override them.'
      this.changed()
    }
    this.kickAll() // devices that could not get in a moment ago are tried at once
  }

  // ---- looking for devices (DNS-SD + the scan + Tailscale) ----------------------------------------------------------------------

  /** The Devices page is open: devices are looked for until [unwatchNearby]. */
  watchNearby(): void {
    this.setWatching('page', true)
  }

  unwatchNearby(): void {
    this.setWatching('page', false)
  }

  private setWatching(who: string, on: boolean): void {
    const before = this.watchers.size
    if (on) this.watchers.add(who)
    else this.watchers.delete(who)
    if (!this.enabled) return
    if (on) {
      this.discovery?.browse()
      this.scanner?.start()
    } else if (!this.watchers.size && before) {
      this.discovery?.stopBrowsing()
      this.scanner?.stop()
    }
    this.changed()
  }

  /** What DNS-SD and the scan found, one entry per device (see mergeNearby), with the paired ones marked. */
  private nearby(): DiscoveredDevice[] {
    const isPaired = (hint: string) => this.peers.list().some((p) => p.deviceId.startsWith(hint))
    return mergeNearby(this.discovery?.list() ?? [], this.scanner?.list() ?? []).map((d) => ({...d, paired: isPaired(d.hint)}))
  }

  /** The adapters that count as this device's network addresses (tests inject their own). */
  private adapters(): EligibleInterface[] {
    const scan = this.deps.scan && typeof this.deps.scan === 'object' ? this.deps.scan.interfaces : undefined
    const disc = typeof this.deps.discovery === 'object' ? this.deps.discovery.interfaces : undefined
    return (scan ?? this.deps.interfaces ?? disc ?? (() => eligibleInterfaces()))()
  }

  /** The addresses this device tells paired devices in hello: its home-network addresses, Tailscale's, and its MagicDNS name (at most six). */
  private ownAddresses(): string[] {
    const out = this.adapters().map((i) => i.address)
    const name = this.scanner?.magicName()
    if (name) out.push(name)
    return [...new Set(out)].slice(0, 6)
  }

  /** A sync found no address that answers: look at the network once (at most once a minute) and say where the device was seen. */
  private async lookupFor(peer: Peer): Promise<{host: string; port: number}[]> {
    const scanner = this.scanner
    if (!scanner || this.closed) return []
    if (this.now() - this.lastLookup >= (this.deps.lookupEveryMs ?? 60_000)) {
      this.lastLookup = this.now()
      await scanner.round()
    }
    return this.nearby().filter((d) => peer.deviceId.startsWith(d.hint)).map((d) => ({host: d.host, port: d.port}))
  }
  private stopRuntime(): void {
    clearInterval(this.netTimer)
    this.netTimer = undefined
    clearInterval(this.profileTimer)
    this.profileTimer = undefined
    this.firewall = {blocked: false, category: 'unknown'}
    for (const link of this.links.values()) link.stop()
    this.links.clear()
    for (const off of this.unwatch) off()
    this.unwatch = []
    this.discovery?.stop()
    this.discovery = undefined
    this.scanner?.stop()
    this.scanner = undefined
  }

  async close(): Promise<void> {
    this.closed = true
    this.jobsHost?.close()
    this.join?.controller.abort()
    clearTimeout(this.pushTimer)
    this.pairing.close()
    this.stopRuntime()
    await this.server?.close()
    this.server = undefined
  }

  private executorFor(libraryId: string): PeerExecutor | undefined {
    const lib = this.deps.libraries().find((l) => l.libraryId === libraryId)
    if (!lib) return undefined
    let ex = this.executors.get(libraryId)
    if (!ex) {
      ex = new PeerExecutor({root: lib.root, records: lib.records, now: this.now, leaseMs: this.deps.leaseMs, device: this.identity.name})
      this.executors.set(libraryId, ex)
    }
    return ex
  }

  // ---- links ---------------------------------------------------------------------------------------------------------------

  private startLink(peer: Peer): void {
    if (this.links.has(peer.deviceId) || !this.server) return
    const link = new PeerLink({
      identity: () => this.identity,
      peers: this.peers,
      peerId: peer.deviceId,
      libraries: () => this.deps.libraries(),
      hints: () => this.nearby().filter((d) => peer.deviceId.startsWith(d.hint)).map((d) => ({host: d.host, port: d.port})),
      lookup: () => this.lookupFor(peer),
      now: this.now,
      log: this.deps.log,
      changed: () => this.changed(),
      synced: () => void this.refreshLibraryInfo(),
      backoffMinMs: this.deps.backoff?.minMs,
      backoffMaxMs: this.deps.backoff?.maxMs,
      connected: (client, hello) => this.onConnected(peer.deviceId, client, hello),
      event: (message) => void this.onPeerEvent(peer.deviceId, message),
      unreachable: () => this.peers.setReachable(peer.deviceId, false, this.now()),
      reachable: () => this.peers.setReachable(peer.deviceId, true, this.now()),
    })
    this.links.set(peer.deviceId, link)
    link.start()
  }

  /** Stops syncing with one device without forgetting it (a network cut, in tests). */
  pauseLink(deviceId: string): void {
    this.links.get(deviceId)?.stop()
    this.links.delete(deviceId)
    this.changed()
  }

  resumeLink(deviceId: string): void {
    const peer = this.peers.get(deviceId)
    if (peer && this.enabled) this.startLink(peer)
  }

  private libraryChanged(): void {
    for (const link of this.links.values()) link.kick()
  }

  /** The network changed (a VPN came up, Wi-Fi switched): try everyone now. */
  kickAll(): void {
    for (const link of this.links.values()) link.kick()
  }

  // ---- pairing ------------------------------------------------------------------------------------------------------------

  /** "Add a device" is open: this device answers pairing requests for 2 minutes and looks for others. */
  openPairing(): void {
    this.requireRunning()
    this.pairing.open()
    this.setWatching('pairing', true)
    this.changed()
  }

  closePairing(): void {
    this.pairing.close()
    this.qr = undefined
    this.setWatching('pairing', false)
    this.changed()
  }

  async showQr(): Promise<void> {
    this.requireRunning()
    const host = this.deps.advertiseHost?.() ?? this.discovery?.primaryAddress() ?? eligibleInterfaces()[0]?.address ?? os.hostname()
    const {link, expiresAt, code} = this.pairing.makeQr(host, this.port!)
    const svg = await QRCode.toString(link, {type: 'svg', margin: 1, errorCorrectionLevel: 'M', color: {dark: '#000000', light: '#ffffff'}})
    this.qr = {link, image: `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`, code, expiresAt}
    this.setWatching('pairing', true)
    this.changed()
  }

  /** The person typed an address or pasted a link. Resolves when the pairing ended (paired or not). */
  async ask(text: string): Promise<void> {
    const code = this.typedCode(text)
    if (code) {
      this.guardAsk()
      return this.runAskCode(code)
    }
    return this.runAsk(this.parseTarget(text))
  }

  /** Like [ask], but returns as soon as the address is known to be usable; the pairing goes on and the page follows it. */
  beginAsk(text: string): void {
    const code = this.typedCode(text)
    if (code) {
      this.guardAsk()
      void this.runAskCode(code).catch(() => undefined)
      return
    }
    const target = this.parseTarget(text)
    void this.runAsk(target).catch(() => undefined)
  }

  /** What was typed, when it is a pairing code (ten symbols, any case, with or without the dash). A link or an address is not. */
  private typedCode(text: string): {token: number; pinBits: number} | null {
    const t = text.trim()
    return t.startsWith('keepfin://') ? null : parsePairCode(t)
  }

  private guardAsk(): void {
    this.requireRunning()
    if (this.asking && !['done', 'failed'].includes(this.asking.view.stage)) throw new Error('Another pairing is already in progress.')
    if (this.responding()) throw new Error('A device is already pairing with this one. Compare the numbers on the screen.')
  }

  private parseTarget(text: string): {host: string; port: number; pin: string | null; token: Uint8Array | null} {
    this.guardAsk()
    if (text.trim().startsWith('keepfin://')) {
      const link = parsePairQr(text.trim())
      if (!link) throw new Error('That pairing code is not valid.')
      return {host: link.host, port: link.port, pin: Buffer.from(link.pin).toString('hex'), token: link.token}
    }
    const parsed = parseAddress(text)
    if (!parsed) throw new Error('That is not an address. Use a name or number, like 192.168.1.20 or my-pc.tailnet.ts.net, with :port if it is not 47215.')
    return {host: parsed.host, port: parsed.port, pin: null, token: null}
  }

  /**
   * A typed code: ask the devices seen on this network and Tailscale, one try each. A device whose key does not start with the code's
   * 30 pin bits is dropped when the connection opens, before a single request is written; one that does not say a code is showing
   * gets no /start. The first device that accepts the code is paired like a scanned QR. Nothing is retried.
   */
  private async runAskCode(code: {token: number; pinBits: number}): Promise<void> {
    const view: PairingView = {id: randomUUID(), method: 'qr', role: 'asking', stage: 'connecting', peerName: '', peerFingerprint: '', expiresAt: this.now() + PAIRING_WINDOW_MS}
    const asking: Asking = {view, controller: new AbortController()}
    this.asking = asking
    this.changed()
    try {
      let candidates = this.nearby()
      if (!candidates.length && this.scanner) {
        await this.scanner.round().catch(() => undefined)
        candidates = this.nearby()
      }
      const tried = new Set<string>()
      let paired: {peer: PairPeerInfo; host: string; port: number} | undefined
      for (const device of candidates) {
        const key = `${device.host}:${device.port}`
        if (tried.has(key) || asking.controller.signal.aborted) continue
        tried.add(key)
        let started = false
        try {
          const peer = await askToPair({
            identity: this.identity,
            host: device.host,
            port: device.port,
            pin: null,
            token: null,
            code,
            connectTimeoutMs: 2_500,
            libraries: this.deps.libraries().map((l) => ({libraryId: l.libraryId, actor: l.actor()})),
            connected: (info) => {
              started = true
              view.peerName = info.peerName
              view.peerFingerprint = info.peerFingerprint
              this.changed()
            },
            decide: (info) => {
              view.peerName = info.peerName
              view.peerFingerprint = info.peerFingerprint
              view.expiresAt = info.expiresAt
              view.stage = 'waiting'
              this.changed()
              return Promise.resolve(true) // the code named the key and the other device accepted it
            },
          })
          paired = {peer, host: device.host, port: device.port}
          break
        } catch (error) {
          // before the other device accepted, a device that is not the one (wrong key, no code showing, unreachable) is skipped silently
          if (started) throw error
          if (error instanceof AskError && error.code === 'token') throw new AskError('That code is not right or has expired.', 'token')
          if (error instanceof AskError && (error.code === 'rate' || error.code === 'version')) throw error
        }
      }
      if (!paired) throw new AskError('No device showing this code was found. Check the code, and that both devices are on the same network or Tailscale.', 'nocode')
      view.stage = 'done'
      await this.onPaired(paired.peer, 'qr', paired.host, paired.port)
    } catch (error) {
      view.stage = 'failed'
      view.error = error instanceof AskError || error instanceof Error ? error.message : 'Pairing failed.'
    } finally {
      this.changed()
    }
  }
  private async runAsk({host, port, pin, token}: {host: string; port: number; pin: string | null; token: Uint8Array | null}): Promise<void> {
    const view: PairingView = {id: randomUUID(), method: token ? 'qr' : 'sas', role: 'asking', stage: 'connecting', peerName: host, peerFingerprint: '', expiresAt: this.now() + PAIRING_WINDOW_MS}
    const asking: Asking = {view, controller: new AbortController()}
    this.asking = asking
    this.changed()
    const known = this.nearby().find((d) => d.host === host && d.port === port)?.name
    const wait = this.deps.waitForPeer
    try {
      const peer = await askToPair({
        identity: this.identity,
        host,
        port,
        pin,
        token,
        libraries: this.deps.libraries().map((l) => ({libraryId: l.libraryId, actor: l.actor()})),
        waitForPeer: {
          peerLabel: known ?? 'the other device',
          signal: asking.controller.signal,
          intervalMs: wait?.intervalMs,
          deadlineMs: wait?.deadlineMs,
          sleep: wait?.sleep,
          now: this.now,
          waiting: () => {
            view.stage = 'waitingForPeer'
            if (known) view.peerName = known
            this.changed()
          },
        },
        connected: (info) => {
          view.peerName = info.peerName
          view.peerFingerprint = info.peerFingerprint
          this.changed()
        },
        signal: asking.controller.signal,
        decide: (info) =>
          new Promise<boolean>((resolve) => {
            if (asking.yielded) return resolve(false)
            view.peerName = info.peerName
            view.peerFingerprint = info.peerFingerprint
            view.expiresAt = info.expiresAt
            if (info.method === 'qr') {
              view.stage = 'waiting'
              resolve(true) // the code in the QR already named the key
            } else {
              view.stage = 'comparing'
              view.sas = info.sas
              // the first tap sends this side's confirmation and waits for the other device's person (stage 'waiting', code still shown)
              asking.decision = (match) => {
                if (!match) asking.declinedHere = true
                view.stage = match ? 'waiting' : 'failed'
                resolve(match)
                this.changed()
              }
            }
            this.changed()
          }),
      })
      view.stage = 'done'
      await this.onPaired(peer, token ? 'qr' : 'sas', host, port)
    } catch (error) {
      view.stage = 'failed'
      view.error = error instanceof AskError && error.code === 'declined' && !asking.declinedHere ? `${view.peerName || 'The other device'} did not confirm, so nothing was paired.` : error instanceof AskError || error instanceof Error ? error.message : 'Pairing failed.'
    } finally {
      this.changed()
    }
  }

  /** The person answered the numbers screen: they match (true) or not (false); or approved/declined a QR pairing. */
  decide(id: string, match: boolean): void {
    if (this.asking?.view.id === id) {
      // the first tap answers the numbers; once this side confirmed, "no" (Cancel) declines to the other device and closes
      if (this.asking.view.stage === 'comparing' && this.asking.decision) this.asking.decision(match)
      else if (!match) this.dismissPairing()
      return
    }
    this.pairing.decide(id, match)
  }

  dismissPairing(): void {
    // Cancel while waiting for the other device ends the retries cleanly
    if (this.asking && ['waitingForPeer', 'waiting'].includes(this.asking.view.stage)) this.asking.controller.abort()
    if (this.asking && ['done', 'failed', 'waitingForPeer', 'waiting'].includes(this.asking.view.stage)) this.asking = undefined
    // the other device asked: its finished result is closed here too (OK used to do nothing on the answering side)
    else if (!this.asking) this.pairing.dismiss()
    this.changed()
  }

  /** Undo for a pairing a scanned code approved by itself: forgets that device again (the same path as Remove device). */
  async undoPair(deviceId: string): Promise<{revoked: string[]; notRevoked: string[]}> {
    return this.remove(deviceId)
  }

  /** Both sides confirmed. Saves the pin and starts the link; the library side (membership) follows in the background. */
  private async onPaired(info: PairPeerInfo, method: 'sas' | 'qr', host?: string, port?: number): Promise<void> {
    const shared = this.deps.libraries().filter((lib) => info.libraries.some((x) => x.libraryId === lib.libraryId))
    const address = host ?? info.address
    const peer: Peer = {
      deviceId: info.deviceId,
      spki: Buffer.from(info.spki).toString('base64'),
      name: info.name,
      platform: info.platform,
      pairedAt: this.now(),
      lastSeen: this.now(),
      libraries: shared.map((lib) => ({libraryId: lib.libraryId, actor: info.libraries.find((x) => x.libraryId === lib.libraryId)!.actor, actions: [...DEFAULT_GRANTS]})),
      ...(address ? {lastHost: address, lastPort: port ?? DEFAULT_PORT} : {}),
    }
    void method
    await this.peers.put(peer)
    if (this.enabled) this.startLink(this.peers.get(peer.deviceId)!)
    this.qr = undefined
    void this.completeMembership(peer.deviceId).catch((error) => this.deps.log?.(`membership after pairing failed: ${error instanceof Error ? error.message : String(error)}`))
    this.changed()
  }

  /**
   * The library side of a new pairing (spec 9.1 #1). Where nothing is enforced yet, the device with the lower device id writes the
   * genesis and the first grant in one segment, after taking in what the peer holds (so its history is frozen too); the other device
   * simply receives them. Where membership exists, an administrator device grants the new device's actor.
   */
  private async completeMembership(deviceId: string): Promise<void> {
    const link = this.links.get(deviceId)
    for (const lib of this.deps.libraries()) {
      const peer = this.peers.get(deviceId)
      const entry = peer?.libraries.find((l) => l.libraryId === lib.libraryId)
      if (!peer || !entry || !entry.actor) continue // shared but not joined yet: the actor is granted when it joins
      try {
        let membership = await lib.membership()
        const grant = {actor: entry.actor, spkiBase64: peer.spki, perms: entry.actions.includes('membership.admin') ? ['write', 'admin'] : ['write']}
        if (!membership.active) {
          if (this.identity.deviceId > peer.deviceId) continue // the other device writes the genesis; it reaches us by sync
          await link?.settled(15_000)
          membership = await lib.membership()
          if (!membership.active) {
            await lib.ensureMembership(grant)
            await this.refreshLibraryInfo()
            continue
          }
        }
        if (!membership.members[lib.actor()]?.perms.includes('admin')) {
          this.note(`admin:${lib.libraryId}`, `${lib.name}: this device is not an administrator, so ${peer.name} is not yet allowed to write to it. Pair it with the administrator device too.`)
          continue
        }
        const known = membership.members[entry.actor]
        if (known && known.cutoff === null && known.keys.some((k) => k.device === deviceIdOf(new Uint8Array(Buffer.from(peer.spki, 'base64'))) && k.until === null)) continue
        await lib.grantMember(grant.actor, grant.spkiBase64, grant.perms)
      } catch (error) {
        this.note(`grant:${lib.libraryId}`, `${lib.name}: ${peer.name} could not be added to the library (${error instanceof Error ? error.message : String(error)}).`)
      }
    }
    await this.refreshLibraryInfo()
  }
  // ---- paired devices -----------------------------------------------------------------------------------------------------

  /** What this device lets one paired device do with one library. */
  async setAccess(deviceId: string, libraryId: string, actions: DeviceAction[]): Promise<void> {
    const clean = [...new Set(actions.filter((a) => ALL_ACTIONS.has(a)))] as NetAction[]
    const peer = await this.peers.update(deviceId, (p) => {
      const entry = p.libraries.find((l) => l.libraryId === libraryId)
      if (entry) entry.actions = clean
    })
    if (!peer) throw new Error('That device is not paired.')
    const lib = this.deps.libraries().find((l) => l.libraryId === libraryId)
    if (lib && clean.includes('membership.admin')) await this.completeMembership(peer.deviceId)
    this.server?.sendTo(deviceId, {t: 'access'})
    this.changed()
  }

  /**
   * Forgets a device: its pin is deleted, its connections close, and in each shared library an administrator device revokes its actor
   * with a cutoff (its later operations are set aside, never applied). What it already copied stays with it.
   */
  async remove(deviceId: string): Promise<{revoked: string[]; notRevoked: string[]}> {
    const peer = this.peers.get(deviceId)
    if (!peer) throw new Error('That device is not paired.')
    this.server?.dropPeer(deviceId)
    this.links.get(deviceId)?.stop()
    this.links.delete(deviceId)
    for (const ex of this.executors.values()) await ex.release(deviceId)
    await this.peers.remove(deviceId)
    const revoked: string[] = []
    const notRevoked: string[] = []
    for (const entry of peer.libraries) {
      const lib = this.deps.libraries().find((l) => l.libraryId === entry.libraryId)
      if (!lib || !entry.actor) continue
      try {
        const membership = await lib.membership()
        if (!membership.active || !membership.members[entry.actor]) continue
        await lib.flush()
        const cutoff = vectorOf(await listSegments(lib.root, lib.records)).get(entry.actor) ?? 0
        await lib.revokeMember(entry.actor, cutoff)
        revoked.push(lib.name)
      } catch (error) {
        notRevoked.push(lib.name)
        this.note(`revoke:${lib.libraryId}`, `${lib.name}: ${peer.name} could not be revoked here (${error instanceof Error ? error.message : String(error)}). Remove it on the administrator device.`)
      }
    }
    void this.refreshLibraryInfo()
    this.changed()
    return {revoked, notRevoked}
  }

  async rename(name: string): Promise<void> {
    await renameDevice(this.deps.deviceDir, name)
    this.identity.name = name.trim().slice(0, 40) || this.identity.name
    // connected devices read the hello again and show the new name now (the others at their next connection)
    for (const peer of this.peers.list()) this.server?.sendTo(peer.deviceId, {t: 'hello'})
    this.changed()
  }

  // ---- what a connected peer tells us ---------------------------------------------------------------------------------------

  /** A peer said hello: remember the libraries it offers that we do not hold, and deliver the downloads we asked it for. */
  private onConnected(deviceId: string, client: PeerClient, hello: HelloAnswer): void {
    const held = new Set(this.deps.libraries().map((l) => l.libraryId))
    this.offers.set(
      deviceId,
      hello.libraries.filter((l) => !held.has(l.libraryId) && l.actions.includes('metadata.read')).map((l) => ({libraryId: l.libraryId, name: l.name})),
    )
    // why a connected device offers nothing: no library at all, one not shared with this device, only ones this device holds, or none it may read
    this.offerGaps.set(deviceId, offerGapOf(hello, held))
    this.jobsHost?.resend(deviceId)
    void this.deliverJobs(deviceId, client).catch((error) => this.deps.log?.(`jobs could not be delivered: ${error instanceof Error ? error.message : String(error)}`))
    this.changed()
  }

  private async onPeerEvent(deviceId: string, message: Record<string, unknown>): Promise<void> {
    if (message.t !== 'job' || typeof message.requestId !== 'string' || !message.view || typeof message.view !== 'object') return
    const job = this.outgoing.get(message.requestId)
    if (!job || job.deviceId !== deviceId) return
    await this.outgoing.applyView(job.requestId, message.view as never)
    this.changed()
  }

  // ---- joining a library (spec 9.4) ---------------------------------------------------------------------------------------

  /**
   * A paired device asks to join a library this device holds and has shared with it. This device must administer the library: it
   * grants the actor the joining device will write as (and writes the membership root first when the library was not signed yet).
   */
  private async joinRequest(deviceId: string, libraryId: string, body: Record<string, unknown>): Promise<{status: number; body: unknown}> {
    const actor = typeof body.actor === 'string' ? body.actor.toLowerCase() : ''
    if (!isActorId(actor)) return {status: 400, body: {error: 'actor', message: 'The request needs an actor id.'}}
    const peer = this.peers.get(deviceId)
    const lib = this.deps.libraries().find((l) => l.libraryId === libraryId)
    const entry = peer?.libraries.find((l) => l.libraryId === libraryId)
    if (!peer || !lib || !entry) return {status: 403, body: {error: 'library', message: 'This library is not shared with you.'}}
    if (body.leave === true) return this.leaveRequest(deviceId, lib, actor, entry.actor)
    if (entry.actor && entry.actor !== actor) return {status: 409, body: {error: 'actor', message: 'This device already joined this library with another identity.'}}
    const perms = entry.actions.includes('membership.admin') ? ['write', 'admin'] : ['write']
    try {
      const membership = await lib.membership()
      if (!membership.active) await lib.ensureMembership({actor, spkiBase64: peer.spki, perms})
      else {
        if (!membership.members[lib.actor()]?.perms.includes('admin')) return {status: 409, body: {error: 'not-admin', message: 'This device does not administer the library. Ask the device that does to share it.'}}
        const known = membership.members[actor]
        const key = deviceIdOf(new Uint8Array(Buffer.from(peer.spki, 'base64')))
        if (!(known && known.cutoff === null && known.keys.some((k) => k.device === key && k.until === null))) await lib.grantMember(actor, peer.spki, perms)
      }
    } catch (error) {
      return {status: 500, body: {error: 'grant', message: `The library could not add your device (${error instanceof Error ? error.message : String(error)}).`}}
    }
    await this.peers.update(deviceId, (p) => {
      const e = p.libraries.find((l) => l.libraryId === libraryId)
      if (e) e.actor = actor
    })
    await lib.flush()
    void this.refreshLibraryInfo()
    this.changed()
    return {status: 200, body: {ok: true, libraryId, name: lib.name, actor: lib.actor()}}
  }

  /**
   * A device that combined its library into this one and then separated again leaves it (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md
   * section 7.6): the actor it joined as is revoked here with a cutoff at what this device holds of it, so the actor's later
   * operations are never applied. Needs this device to administer the library.
   */
  private async leaveRequest(deviceId: string, lib: NetLibrary, actor: string, granted: string): Promise<{status: number; body: unknown}> {
    if (!granted || granted !== actor) return {status: 409, body: {error: 'actor', message: 'That is not the identity this device granted.'}}
    try {
      const membership = await lib.membership()
      if (!membership.active || !membership.members[actor]) return {status: 200, body: {ok: true, revoked: false}}
      if (!membership.members[lib.actor()]?.perms.includes('admin')) return {status: 409, body: {error: 'not-admin', message: 'This device does not administer the library.'}}
      await lib.flush()
      const cutoff = vectorOf(await listSegments(lib.root, lib.records)).get(actor) ?? 0
      await lib.revokeMember(actor, cutoff)
    } catch (error) {
      return {status: 500, body: {error: 'revoke', message: `The library could not revoke the device (${error instanceof Error ? error.message : String(error)}).`}}
    }
    await this.peers.update(deviceId, (p) => {
      const e = p.libraries.find((l) => l.libraryId === lib.libraryId)
      if (e) e.actor = ''
    })
    this.changed()
    return {status: 200, body: {ok: true, revoked: true}}
  }

  /** The library [deviceId] offers that this device does not hold, for a combine. */
  offerFrom(deviceId: string): {libraryId: string; name: string} | undefined {
    return this.offers.get(deviceId)?.[0]
  }

  /** Why the connected device [deviceId] offers no library to join (from its last hello), or undefined when it has not said hello. */
  offerGap(deviceId: string): OfferGap | undefined {
    return this.offerGaps.get(deviceId)
  }

  /** Is the paired device [deviceId] connected right now (either side's connection)? */
  peerOnline(deviceId: string): boolean {
    return !!this.links.get(deviceId)?.online || !!this.server?.isOnline(deviceId)
  }

  peerName(deviceId: string): string {
    return this.peers.get(deviceId)?.name ?? ''
  }

  /** Is a paired device that shares [libraryId] connected right now? */
  reachableFor(libraryId: string): boolean {
    return this.peers.list().some((p) => p.libraries.some((l) => l.libraryId === libraryId) && !!this.links.get(p.deviceId)?.online)
  }

  /**
   * Waits until a connected device has durably received [actor]'s operations up to [seq] of [libraryId]: it syncs, then reads the
   * peer's version vector back (the answer to a pull states what the peer holds). False when nobody confirmed within [timeoutMs].
   */
  async confirmPublished(libraryId: string, actor: string, seq: number, timeoutMs: number): Promise<boolean> {
    const lib = this.deps.libraries().find((l) => l.libraryId === libraryId)
    if (!lib) return false
    const end = Date.now() + timeoutMs
    do {
      for (const peer of this.peers.list()) {
        if (!peer.libraries.some((l) => l.libraryId === libraryId)) continue
        const link = this.links.get(peer.deviceId)
        const confirmed = await link
          ?.withClient(async (client) => {
            await syncLibrary(client, lib)
            const check = await pullSegments(client, lib)
            return (check.remote?.get(actor) ?? 0) >= seq
          })
          .catch(() => false)
        if (confirmed) return true
      }
      await new Promise((resolve) => setTimeout(resolve, 200))
    } while (Date.now() < end)
    return false
  }

  /** Tells [deviceId], which granted [actor] in [libraryId], that this device leaves the library (it revokes the actor). */
  async leaveLibrary(deviceId: string, libraryId: string, actor: string): Promise<boolean> {
    const link = this.links.get(deviceId)
    const answered = await link
      ?.withClient((client) => client.json<{ok?: boolean}>('POST', `/v1/libraries/${libraryId}/join`, {json: {actor, leave: true}}))
      .catch(() => undefined)
    return !!answered?.ok
  }

  /** Sync now / Retry on a device card: syncs at once when the device answers, else tries to reach it again now. */
  syncNow(deviceId: string): void {
    const link = this.links.get(deviceId)
    if (!link) throw new Error('That device is not paired.')
    link.kick()
  }

  /** Shares a library of this device with one paired device: it may then read and write its operations, and may join it. */
  async shareLibrary(deviceId: string, libraryId: string): Promise<void> {
    const lib = this.deps.libraries().find((l) => l.libraryId === libraryId)
    if (!lib) throw new Error('That library is not open on this device.')
    const peer = await this.peers.update(deviceId, (p) => {
      if (!p.libraries.some((l) => l.libraryId === libraryId)) p.libraries.push({libraryId, actor: '', actions: [...DEFAULT_GRANTS]})
    })
    if (!peer) throw new Error('That device is not paired.')
    this.links.get(deviceId)?.kick()
    this.server?.sendTo(deviceId, {t: 'access'})
    this.server?.announce()
    this.changed()
  }

  /**
   * "Get this library": makes a Keepfin folder at [folder] for a library a paired device shares, takes in its segments and (through
   * `adoptJoined`) makes it a library profile of this device. An interrupted join continues when the same folder is chosen again.
   */
  async joinLibrary(deviceId: string, libraryId: string, folder: string): Promise<Joined> {
    this.requireRunning()
    if (this.join && !['done', 'failed'].includes(this.join.view.stage)) throw new Error('Another library is being taken in. Wait for it to finish.')
    const peer = this.peers.get(deviceId)
    if (!peer) throw new Error('That device is not paired.')
    const offer = this.offers.get(deviceId)?.find((o) => o.libraryId === libraryId)
    if (!offer) throw new Error('That device is not offering this library right now. Make sure it is turned on and connected.')
    if (this.deps.libraries().some((l) => l.libraryId === libraryId)) throw new Error('This device already holds that library.')
    const link = this.links.get(deviceId)
    const controller = new AbortController()
    const view: JoinView = {deviceId, deviceName: peer.name, libraryId, name: offer.name, folder, stage: 'folder', segments: 0}
    this.join = {view, controller}
    this.changed()
    try {
      const joined = await (link?.withClient((client) =>
        runJoin({
          client,
          peerId: deviceId,
          libraryId,
          name: offer.name,
          target: folder,
          now: this.now,
          signal: controller.signal,
          onProgress: (p) => {
            view.stage = p.stage
            view.segments = p.segments
            this.changed()
          },
        }),
      ) ?? Promise.reject(new JoinError('network', 'That device is not connected right now.')))
      if (!joined) throw new JoinError('network', 'That device is not connected right now.')
      // from now on this device and the peer share the library: the pin carries the actors (this device's own is the joined one)
      await this.peers.update(deviceId, (p) => {
        if (!p.libraries.some((l) => l.libraryId === libraryId)) p.libraries.push({libraryId, actor: joined.hostActor, actions: [...DEFAULT_GRANTS]})
      })
      if (this.deps.adoptJoined) await this.deps.adoptJoined(joined)
      view.stage = 'done'
      this.offers.set(deviceId, (this.offers.get(deviceId) ?? []).filter((o) => o.libraryId !== libraryId))
      this.changed()
      return joined
    } catch (error) {
      view.stage = 'failed'
      view.error = error instanceof Error ? error.message : 'The library could not be taken in.'
      view.resumable = !(error instanceof JoinError && (error.code === 'folder' || error.code === 'not-admin'))
      this.changed()
      throw error
    }
  }

  /**
   * A combine: takes a library a paired device offers into a records root of the existing Keepfin folder [target.root] (the media
   * folder is not touched). The join record is kept at [target.recordFile]. It does not become a library profile here: the combine
   * does that when it switches, and until then this device keeps working in its own library.
   */
  async joinLibraryInto(deviceId: string, libraryId: string, target: {root: string; records: string; recordFile: string}, onProgress?: (segments: number) => void, signal?: AbortSignal): Promise<Joined> {
    this.requireRunning()
    const peer = this.peers.get(deviceId)
    if (!peer) throw new Error('That device is not paired.')
    const offer = this.offers.get(deviceId)?.find((o) => o.libraryId === libraryId)
    const link = this.links.get(deviceId)
    const joined = await link?.withClient((client) =>
      runJoin({
        client,
        peerId: deviceId,
        libraryId,
        name: offer?.name ?? peer.name,
        target: target.root,
        records: target.records,
        recordFile: target.recordFile,
        now: this.now,
        signal,
        onProgress: (p) => onProgress?.(p.segments),
      }),
    )
    if (!joined) throw new JoinError('network', 'That device is not connected right now.')
    await this.peers.update(deviceId, (p) => {
      const e = p.libraries.find((l) => l.libraryId === libraryId)
      if (e) e.actor = e.actor || joined.hostActor
      else p.libraries.push({libraryId, actor: joined.hostActor, actions: [...DEFAULT_GRANTS]})
    })
    this.changed()
    return joined
  }

  cancelJoin(): void {
    this.join?.controller.abort()
  }

  // ---- Download on... (phase 6): the sending side -------------------------------------------------------------------------

  /** Asks a paired device to download a link. The request is written to disk first and resent until the device answers. */
  async sendJob(deviceId: string, input: {url: string; format: unknown; options?: unknown; space?: string; musicVideo?: boolean}): Promise<void> {
    const peer = this.peers.get(deviceId)
    if (!peer) throw new Error('That device is not paired.')
    if (typeof input.url !== 'string' || !/^https?:\/\//i.test(input.url.trim())) throw new Error('Paste a complete https:// link.')
    const job = await this.outgoing.create({deviceId, deviceName: peer.name, url: input.url.trim(), format: input.format, options: input.options, space: input.space, ...(input.musicVideo ? {musicVideo: true} : {})})
    this.changed()
    const link = this.links.get(deviceId)
    void link?.withClient((client) => this.deliverOne(client, job.requestId)).catch(() => undefined)
  }

  async dismissJob(requestId: string): Promise<void> {
    await this.outgoing.dismiss(requestId)
    this.changed()
  }

  private async deliverJobs(deviceId: string, client: PeerClient): Promise<void> {
    for (const job of this.outgoing.openFor(deviceId)) await this.deliverOne(client, job.requestId)
  }

  /** One request (or the same one again: the answer is the current state of the job on the other device). */
  private async deliverOne(client: PeerClient, requestId: string): Promise<void> {
    const job = this.outgoing.get(requestId)
    if (!job || isTerminalJob(job.state)) return
    try {
      const answer = await client.json<{view?: never}>('POST', '/v1/jobs', {json: {requestId, url: job.url, format: job.format, ...(job.options !== undefined ? {options: job.options} : {}), ...(job.space ? {space: job.space} : {}), ...(job.musicVideo ? {musicVideo: true} : {})}, timeoutMs: 60_000})
      if (answer.view) await this.outgoing.applyView(requestId, answer.view)
      else await this.outgoing.update(requestId, {state: 'queued'})
    } catch (error) {
      if (error instanceof PeerHttpError) {
        if (error.status === 422 || error.status === 409 || error.status === 400) await this.outgoing.update(requestId, {state: 'refused', error: error.message})
        else if (error.status === 403) await this.outgoing.update(requestId, {state: 'refused', error: 'That device does not allow downloads from this device.'})
        else if (error.status === 501) await this.outgoing.update(requestId, {state: 'refused', error: 'That device does not take downloads from other devices.'})
        else if (error.status === 507 || error.status === 429) await this.outgoing.update(requestId, {error: error.message}) // stays: tried again when it connects
      }
      // anything else (a lost connection): the request stays and is sent again when the device is back
    }
    this.changed()
  }

  // ---- a paired device as a copy (spec 5) ----------------------------------------------------------------------------------

  /** What the copies screen and its workers need to keep a copy of [libraryId] on a paired device. */
  peerBridge(libraryId: string): PeerBridge {
    const needed: DeviceAction[] = ['inventory.read', 'media.read', 'copy.mutate']
    return {
      devices: () =>
        this.peers.list().map((peer) => {
          const link = this.links.get(peer.deviceId)
          const online = !!link?.online
          const offered = link?.lastHello?.libraries.find((l) => l.libraryId === libraryId)
          const missing = offered ? needed.filter((a) => !offered.actions.includes(a)) : needed
          let why: string | undefined
          if (!online) why = 'Not connected right now.'
          else if (!offered) why = `${peer.name} does not share this library with you.`
          else if (missing.length) why = `On ${peer.name}, turn on "Keep a copy on this device" for this device in Settings → Devices.`
          return {deviceId: peer.deviceId, name: peer.name, online, allowed: online && !!offered && !missing.length, ...(why ? {why} : {})}
        }),
      name: (deviceId) => this.peers.get(deviceId)?.name,
      online: (deviceId) => !!this.links.get(deviceId)?.online,
      connection: (deviceId) => {
        const peer = this.peers.get(deviceId)
        const link = this.links.get(deviceId)
        if (!peer || !link?.online) return undefined
        const hint = link.lastAddress ?? (peer.lastHost ? {host: peer.lastHost, port: peer.lastPort ?? DEFAULT_PORT} : undefined)
        if (!hint) return undefined
        return {host: hint.host, port: hint.port, pin: peer.deviceId, keyPem: this.identity.keyPem, certPem: this.identity.certPem}
      },
    }
  }

  // ---- Play from this device (phase 6) ---------------------------------------------------------------------------------------

  /**
   * Finds a paired device that holds the file with [contentId] and lets this device play it: answers with a media URL for the player
   * (Range requests are passed through, so seeking works). Nothing is saved on this device.
   */
  async playFrom(libraryId: string, contentId: string, name: string): Promise<PlayFromResult> {
    const m = /^sha256:([0-9a-f]{64})$/.exec(contentId)
    if (!m) throw new Error('This item has no content to look for on other devices.')
    for (const [deviceId, link] of this.links) {
      const peer = this.peers.get(deviceId)
      const offered = link.lastHello?.libraries.find((l) => l.libraryId === libraryId)
      if (!peer || !offered?.actions.includes('media.read')) continue
      const found = await link
        .withClient(async (client) => {
          const response = await client.request('HEAD', `/v1/revisions/${m[1]}?library=${encodeURIComponent(libraryId)}`, {timeoutMs: 8000})
          response.res.resume()
          if (response.status !== 200) return undefined
          const named = response.headers['x-keepfin-name']
          return {size: Number(response.headers['content-length'] ?? 0), file: typeof named === 'string' ? safeDecode(named) : ''}
        })
        .catch(() => undefined)
      if (found === undefined) continue
      const token = randomUUID().replace(/-/g, '')
      // the file name the device holds it under gives the extension the player needs, when the name this device knows has none
      const shown = mediaKindOf(name) || !mediaKindOf(found.file) ? name : name + path.extname(found.file)
      this.playTokens.set(token, {deviceId, libraryId, hex: m[1], size: found.size, name: shown, expires: this.now() + 6 * 60 * 60_000})
      for (const [key, value] of this.playTokens) if (value.expires < this.now()) this.playTokens.delete(key)
      return {url: `keepfin-media://peer/${token}`, deviceName: peer.name, size: found.size}
    }
    throw new Error('No paired device that lets you play its files has this one right now.')
  }

  /**
   * When [playFrom] found nobody: the paired device that offers its files for this library but is not connected (name and when it was last
   * seen), so the player can say so. Undefined when no such device is known, or one is connected and simply does not have the file.
   */
  unreachableFor(libraryId: string): {device: string; lastSeen: number | null} | undefined {
    let best: {device: string; lastSeen: number | null} | undefined
    for (const [deviceId, link] of this.links) {
      const peer = this.peers.get(deviceId)
      if (!peer || !link.lastHello?.libraries.find((l) => l.libraryId === libraryId)?.actions.includes('media.read')) continue
      if (link.online || this.server?.isOnline(deviceId)) return undefined
      if (!best || (peer.lastSeen ?? 0) > (best.lastSeen ?? 0)) best = {device: peer.name, lastSeen: peer.lastSeen}
    }
    return best
  }

  /** The source behind a `keepfin-media://peer/<token>` URL (the protocol handler reads it with Range). */
  peerSource(token: string): {size: number; name: string; open(range?: {start: number; end: number}): Promise<{status: number; stream: Readable}>} | undefined {
    const entry = this.playTokens.get(token)
    if (!entry || entry.expires < this.now()) return undefined
    const link = this.links.get(entry.deviceId)
    if (!link) return undefined
    return {
      size: entry.size,
      name: entry.name,
      open: async (range) => {
        const response = await link.withClient((client) => client.request('GET', `/v1/revisions/${entry.hex}?library=${encodeURIComponent(entry.libraryId)}`, {headers: range ? {range: `bytes=${range.start}-${range.end}`} : {}, timeoutMs: 120_000}))
        if (!response) throw new Error('The device that has this file is not connected.')
        return {status: response.status, stream: response.res}
      },
    }
  }

  // ---- the page ------------------------------------------------------------------------------------------------------------

  private note(key: string, text: string): void {
    this.notes.set(key, text)
    this.changed()
  }

  private requireRunning(): void {
    if (!this.enabled) throw new Error('Turn devices on first.')
    if (!this.server) throw new Error(this.portError ?? 'Devices are not running.')
  }

  private async refreshLibraryInfo(): Promise<void> {
    for (const lib of this.deps.libraries()) {
      try {
        const m = await lib.membership()
        this.libraryInfo.set(lib.libraryId, {signed: m.active, admin: !!m.members[lib.actor()]?.perms.includes('admin')})
      } catch {
        // the store may be closing
      }
    }
    this.changed()
  }

  private changed(): void {
    if (this.closed || this.pushTimer) return
    this.pushTimer = setTimeout(() => {
      this.pushTimer = undefined
      if (!this.closed) this.deps.push(this.state())
    }, 120)
    this.pushTimer.unref?.()
  }

  state(): DevicesState {
    const paired: PairedDevice[] = this.peers.list().map((peer) => ({
      deviceId: peer.deviceId,
      fingerprint: fingerprintOf(peer.deviceId),
      name: peer.name,
      platform: peer.platform,
      pairedAt: peer.pairedAt,
      lastSeen: peer.lastSeen,
      online: !!this.links.get(peer.deviceId)?.online || !!this.server?.isOnline(peer.deviceId),
      libraries: peer.libraries.map((l) => ({libraryId: l.libraryId, name: this.deps.libraries().find((x) => x.libraryId === l.libraryId)?.name ?? 'Library', actions: l.actions as DeviceAction[]})),
      unreachableSince: peer.unreachableSince ?? null,
      playFrom: !!this.links.get(peer.deviceId)?.lastHello?.libraries.some((l) => l.actions.includes('media.read')),
      address: peer.lastHost ? `${peer.lastHost.includes(':') ? `[${peer.lastHost}]` : peer.lastHost}${(peer.lastPort ?? DEFAULT_PORT) === DEFAULT_PORT ? '' : `:${peer.lastPort}`}` : null,
      route: this.routeOf(peer.deviceId),
      lastSync: this.links.get(peer.deviceId)?.lastSync ?? null,
      jobs: this.jobsAllowedBy(peer.deviceId),
      shareable: this.deps.libraries().filter((lib) => !peer.libraries.some((l) => l.libraryId === lib.libraryId)).map((lib) => ({libraryId: lib.libraryId, name: lib.name})),
    }))
    const offers: LibraryOffer[] = []
    for (const [deviceId, list] of this.offers) {
      const peer = this.peers.get(deviceId)
      if (!peer) continue
      for (const offer of list) offers.push({deviceId, deviceName: peer.name, libraryId: offer.libraryId, name: offer.name, online: !!this.links.get(deviceId)?.online})
    }
    const remoteJobs: RemoteJobRow[] = this.outgoing.list().slice(0, 100).map((j) => ({
      requestId: j.requestId,
      deviceId: j.deviceId,
      deviceName: j.deviceName,
      url: j.url,
      state: j.state,
      percent: j.percent,
      title: j.title,
      label: j.label,
      speed: j.speed,
      eta: j.eta,
      totalBytes: j.totalBytes,
      error: j.error,
      createdAt: j.createdAt,
    }))
    const responder = this.pairing.view()
    const pairing: PairingView | null = this.asking?.view ?? (responder ? {...responder, role: 'answering'} : null)
    const notes = [...this.notes.values()]
    for (const link of this.links.values()) if (link.versionNote) notes.push(link.versionNote)
    return {
      available: true,
      thisDevice: {name: this.identity.name, fingerprint: fingerprintOf(this.identity.deviceId), platform: 'windows'},
      enabled: this.enabled,
      port: this.port,
      portError: this.portError,
      libraries: this.deps.libraries().map((lib) => ({libraryId: lib.libraryId, name: lib.name, signed: this.libraryInfo.get(lib.libraryId)?.signed ?? false, admin: this.libraryInfo.get(lib.libraryId)?.admin ?? false})),
      paired,
      discovered: this.watchers.size ? this.nearby().filter((d) => !d.paired) : [],
      pairingUntil: this.pairing.windowEnd,
      pairing,
      qr: this.qr && this.qr.expiresAt > this.now() ? this.qr : undefined,
      notes,
      offers,
      join: this.join ? {...this.join.view} : null,
      remoteJobs,
      firewall: {...this.firewall},
      tailscale: this.scanner?.tailscale() ?? this.tailscaleFromAdapters(),
      lan: this.adapters().some((i) => !isTailscaleHost(i.address)),
      scanning: !!this.scanner?.isActive && this.watchers.size > 0,
    }
  }

  /** How the connection to a paired device runs, from the address that worked: through Tailscale, or on this network; null while it is not connected. */
  private routeOf(deviceId: string): 'lan' | 'tailscale' | null {
    const link = this.links.get(deviceId)
    if (!link?.online || !link.lastAddress) return null
    return isTailscaleHost(link.lastAddress.host) ? 'tailscale' : 'lan'
  }

  private tailscaleFromAdapters(): DevicesState['tailscale'] {
    const address = this.adapters().find((i) => isTailscaleHost(i.address))?.address
    return address ? {state: 'on', address} : {state: 'off'}
  }

  /** What the peer's last hello says about sending it downloads: whether it allows them and where they may be saved. */
  private jobsAllowedBy(deviceId: string): {allowed: boolean; spaces: {id: string; name: string}[]} {
    const hello = this.links.get(deviceId)?.lastHello
    const allowed = !!hello?.capabilities?.jobs && !!hello.libraries.some((l) => l.actions.includes('jobs'))
    return {allowed, spaces: allowed ? hello?.jobSpaces ?? [] : []}
  }

  /** The server's own pin material for a QR (tests read the link from state). */
  get pin(): Uint8Array {
    return pairPin(this.identity.spki)
  }
}
