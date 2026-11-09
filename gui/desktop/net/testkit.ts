// Helpers for the network tests: several app instances in one process, each with its own temp profile (device folder), its own Keepfin
// folder (same library id), its own key, store, server and service, all on loopback with an ephemeral port and no multicast.
import {createHash, randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import type {DevicesState} from '../../shared/devices.js'
import {createKeepfinFolder} from '../store/keepfinFolder.js'
import type {OpDraft} from '../store/mapping.js'
import {KeepfinStore} from '../store/store.js'
import {loadIdentity, type DeviceIdentity, type KeyBox} from './identity.js'
import type {JobPort} from './jobs.js'
import type {Joined} from './join.js'
import type {Limits} from './limits.js'
import {NetService, type NetServiceDeps} from './service.js'
import {netLibraryFor} from './storeLibrary.js'
import type {NetLibrary} from './types.js'

export const plainBox: KeyBox = {
  available: () => true,
  encrypt: (plain) => Buffer.from(plain, 'utf8'),
  decrypt: (blob) => blob.toString('utf8'),
}

export async function until(check: () => boolean | Promise<boolean>, label: string, ms = 60_000): Promise<void> {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (await check()) return
    await delay(20)
  }
  throw new Error(`Timed out waiting for ${label}`)
}

export interface Node {
  name: string
  dir: string
  deviceDir: string
  root: string
  libraryId: string
  identity: DeviceIdentity
  store: KeepfinStore
  lib: NetLibrary
  service: NetService
  pushes: DevicesState[]
  port: number
  address: string
  item(n: number): string
  add(n: number, title?: string): Promise<void>
  titles(): string[]
  /** Writes a file below the Keepfin folder and lets this device serve it by its content id. */
  putFile(rel: string, bytes: Buffer): Promise<{contentId: string; file: string}>
  close(): Promise<void>
}

export type NodeOptions = {
  limits?: Partial<Limits>
  now?: () => number
  enable?: boolean
  withService?: boolean
  pairingExtra?: never
  leaseMs?: number
  /** A library of its own instead of the world's (a device that does not hold the shared library yet). */
  libraryId?: string
  jobs?: JobPort
  checkUrl?: (url: string) => Promise<string>
  minFreeBytes?: number
  adoptJoined?: (joined: Joined) => Promise<void>
  networkWatchMs?: number
  firewall?: import('./firewall.js').FirewallPort
  firewallMs?: number
  /** The scan of the network and Tailscale, with its own network calls (default: none; the World has no multicast and no scan). */
  scan?: NetServiceDeps['scan']
  tailscale?: NetServiceDeps['tailscale']
  lookupEveryMs?: number
  discovery?: NetServiceDeps['discovery']
  waitForPeer?: {intervalMs?: number; deadlineMs?: number}
  backoff?: {minMs: number; maxMs: number}
  /** A fixed port (default: any), for a device that goes away and comes back at the same address. */
  port?: number
  /** The loopback address to listen on (127.0.0.1 by default; 127.0.0.7 puts a device on another host of the 127.0.0.0/24 network). */
  bind?: string
}

export class World {
  readonly nodes: Node[] = []
  private clock = 1_700_000_000_000

  private constructor(
    readonly dir: string,
    readonly libraryId: string,
  ) {}

  /** [libraryId]: join a library that already exists (the end-to-end check against the real app). */
  static async create(libraryId: string = randomUUID()): Promise<World> {
    return new World(await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-net-')), libraryId)
  }

  /** A fresh instance. Its identity exists before its store opens, so the store signs from the first segment. */
  async node(name: string, options: NodeOptions = {}): Promise<Node> {
    const dir = path.join(this.dir, name)
    const deviceDir = path.join(dir, 'AppData')
    const root = path.join(dir, 'Keepfin')
    const libraryId = options.libraryId ?? this.libraryId
    const folder = await createKeepfinFolder(root, {libraryId, replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
    const identity = await loadIdentity(deviceDir, plainBox)
    const actor = randomUUID()
    const store = await KeepfinStore.open({
      root,
      libraryId,
      actor,
      deviceDir,
      outboxDir: path.join(deviceDir, 'outbox'),
      newId: randomUUID,
      now: () => (this.clock += 1),
      flushIdleMs: 20,
      watch: false,
      signer: {pkcs8: identity.pkcs8, spki: identity.spki},
    })
    const lib = netLibraryFor(store, {libraryId, name: `${name}'s library`, root, watchDebounceMs: 30})
    const pushes: DevicesState[] = []
    let service: NetService | undefined
    if (options.withService !== false) {
      service = await NetService.create({
        deviceDir,
        box: plainBox,
        libraries: () => [lib],
        push: (state) => pushes.push(state),
        port: options.port ?? 0,
        bind: options.bind ?? '127.0.0.1',
        discovery: options.discovery ?? false,
        advertiseHost: () => '127.0.0.1',
        backoff: options.backoff ?? {minMs: 40, maxMs: 400},
        limits: options.limits,
        now: options.now,
        leaseMs: options.leaseMs,
        jobs: options.jobs,
        checkUrl: options.checkUrl,
        minFreeBytes: options.minFreeBytes,
        adoptJoined: options.adoptJoined,
        networkWatchMs: options.networkWatchMs,
        firewall: options.firewall,
        firewallMs: options.firewallMs,
        scan: options.scan ?? false,
        interfaces: () => [{name: 'Loopback test adapter', address: options.bind ?? '127.0.0.1', netmask: '255.255.255.0'}],
        tailscale: options.tailscale ?? {},
        lookupEveryMs: options.lookupEveryMs,
        waitForPeer: options.waitForPeer,
      })
      if (options.enable !== false) await service.enable()
    }
    const item = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
    const served = new Map<string, string>()
    const realFind = lib.findContent.bind(lib)
    lib.findContent = async (contentId) => served.get(contentId) ?? realFind(contentId)
    const node: Node = {
      name,
      dir,
      deviceDir,
      root,
      libraryId,
      identity,
      store,
      lib,
      service: service!,
      pushes,
      get port() {
        return service!.listeningPort!
      },
      get address() {
        return `${options.bind ?? '127.0.0.1'}:${service!.listeningPort}`
      },
      item,
      add: (n, title) => store.apply([{kind: 'item.add', body: {item: item(n), fields: {media: 'audio', title: title ?? `Item ${n}`}}} satisfies OpDraft]),
      titles: () =>
        store
          .views()
          .items.map((entry) => String(entry.fields.title))
          .sort(),
      putFile: async (rel, bytes) => {
        const file = path.join(root, ...rel.split('/'))
        await fs.mkdir(path.dirname(file), {recursive: true})
        await fs.writeFile(file, bytes)
        const contentId = 'sha256:' + createHash('sha256').update(bytes).digest('hex')
        served.set(contentId, file)
        return {contentId, file}
      },
      close: async () => {
        await service?.close()
        await store.close()
      },
    }
    this.nodes.push(node)
    return node
  }

  /** Pairs [asker] with [answerer] by comparing the numbers; both people agree. Returns once both sides saved each other. */
  async pair(asker: Node, answerer: Node): Promise<void> {
    answerer.service.openPairing()
    const done = asker.service.ask(answerer.address)
    await until(() => asker.service.state().pairing?.sas !== undefined && answerer.service.state().pairing?.sas !== undefined, 'both screens to show the numbers')
    const a = asker.service.state().pairing!
    const b = answerer.service.state().pairing!
    if (a.sas !== b.sas) throw new Error(`The numbers differ: ${a.sas} and ${b.sas}`)
    asker.service.decide(a.id, true)
    answerer.service.decide(b.id, true)
    await done
    await until(() => !!asker.service.peers.get(answerer.identity.deviceId) && !!answerer.service.peers.get(asker.identity.deviceId), 'both devices to remember each other')
  }

  async cleanup(): Promise<void> {
    for (const node of this.nodes.splice(0)) await node.close().catch(() => undefined)
    await fs.rm(this.dir, {recursive: true, force: true}).catch(() => undefined)
  }
}
