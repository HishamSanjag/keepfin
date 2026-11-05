// Paired devices (spec section 1): `{deviceId, spki pin, name, platform, libraries:[{libraryId, actor, actions}], pairedAt}`, kept
// device-only in `<deviceDir>\net\peers.json` and never synced. Removing a peer deletes its pin here; revoking it in the library is
// the service's job. Writes are atomic and serialized.
import path from 'node:path'
import type {NetAction, PeerGrants} from '../../shared/core/net.js'
import {DEFAULT_GRANTS} from '../../shared/core/net.js'
import {readJson, writeJsonAtomic} from '../store/atomic.js'

export interface PeerLibrary {
  libraryId: string
  /** The peer's actor in that library (granted membership for it). */
  actor: string
  actions: NetAction[]
}

export interface Peer {
  deviceId: string
  /** SPKI DER, Base64. */
  spki: string
  name: string
  platform: string
  pairedAt: number
  lastSeen: number | null
  libraries: PeerLibrary[]
  /** Last address that worked (a hint for reconnecting; never trusted). */
  lastHost?: string
  lastPort?: number
  /** The addresses the device said it can be reached at (hello), newest first, at most {@link MAX_ADDRESSES}: LAN addresses, Tailscale addresses, a MagicDNS name. Hints only. */
  addresses?: string[]
  /** Since when the device could not be reached (the first failed attempt after it was last seen); absent while it answers. */
  unreachableSince?: number
}

type File = {v: 1; peers: Peer[]}

export const MAX_ADDRESSES = 6

/** The longest device name kept from a hello. */
export const MAX_NAME = 64

/** Hosts a peer may tell us about: an IPv4/IPv6 address or a name, short and made of address characters only. */
export function cleanAddresses(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  const out: string[] = []
  for (const entry of value) {
    if (typeof entry !== 'string') continue
    const host = entry.trim()
    if (!host || host.length > 253 || !/^[0-9A-Za-z.:%_-]+$/.test(host) || out.includes(host)) continue
    out.push(host)
    if (out.length >= MAX_ADDRESSES) break
  }
  return out
}

const ACTIONS: ReadonlySet<string> = new Set(['metadata.read', 'metadata.write', 'inventory.read', 'media.read', 'copy.mutate', 'jobs', 'membership.admin'])

export class PeerStore {
  private peers = new Map<string, Peer>()
  private tail: Promise<unknown> = Promise.resolve()

  private constructor(private readonly file: string) {}

  static async open(deviceDir: string): Promise<PeerStore> {
    const store = new PeerStore(path.join(deviceDir, 'net', 'peers.json'))
    const stored = await readJson<File>(store.file)
    for (const peer of stored?.v === 1 && Array.isArray(stored.peers) ? stored.peers : []) {
      if (typeof peer?.deviceId === 'string' && typeof peer.spki === 'string') {
        store.peers.set(peer.deviceId, {
          ...peer,
          addresses: cleanAddresses(peer.addresses),
          libraries: (Array.isArray(peer.libraries) ? peer.libraries : []).map((entry) => ({...entry, actions: (entry.actions ?? []).filter((action) => ACTIONS.has(action))})),
        })
      }
    }
    return store
  }

  list(): Peer[] {
    return [...this.peers.values()].sort((a, b) => a.pairedAt - b.pairedAt)
  }

  get(deviceId: string): Peer | undefined {
    return this.peers.get(deviceId)
  }

  /** The authorization view of a peer (never revoked here: a removed peer is simply absent). */
  grants(deviceId: string): PeerGrants | null {
    const peer = this.peers.get(deviceId)
    if (!peer) return null
    return {deviceId, revoked: false, libraries: Object.fromEntries(peer.libraries.map((entry) => [entry.libraryId, entry.actions]))}
  }

  /** Adds or replaces a peer (pairing again keeps what the user chose before for libraries both sides still share). */
  async put(peer: Peer): Promise<void> {
    const before = this.peers.get(peer.deviceId)
    const libraries = peer.libraries.map((entry) => {
      const old = before?.libraries.find((x) => x.libraryId === entry.libraryId)
      return {...entry, actions: old ? old.actions : entry.actions.length ? entry.actions : [...DEFAULT_GRANTS]}
    })
    this.peers.set(peer.deviceId, {...peer, libraries})
    await this.save()
  }

  async update(deviceId: string, change: (peer: Peer) => void): Promise<Peer | undefined> {
    const peer = this.peers.get(deviceId)
    if (!peer) return undefined
    change(peer)
    await this.save()
    return peer
  }

  /** The device told us its addresses (hello): they go first, older ones follow, and only six are kept. Written only when the list changed. */
  rememberAddresses(deviceId: string, told: unknown): void {
    const peer = this.peers.get(deviceId)
    if (!peer) return
    const next = cleanAddresses([...cleanAddresses(told), ...(peer.addresses ?? [])])
    if (next.length === (peer.addresses ?? []).length && next.every((a, i) => a === peer.addresses?.[i])) return
    peer.addresses = next
    void this.save().catch(() => undefined)
  }

  /** The name a device gives itself in its hello (it was renamed there). Saved only when it changed; a blank name keeps the old one. */
  rememberName(deviceId: string, told: unknown): void {
    const peer = this.peers.get(deviceId)
    if (!peer || typeof told !== 'string') return
    const next = told.trim().slice(0, MAX_NAME)
    if (!next || next === peer.name) return
    peer.name = next
    void this.save().catch(() => undefined)
  }

  /** A quiet update for `lastSeen`: written at most once a minute. */
  touch(deviceId: string, now: number, host?: string, port?: number): void {
    const peer = this.peers.get(deviceId)
    if (!peer) return
    if (peer.lastSeen !== null && now - peer.lastSeen < 60_000 && peer.lastHost === host) return
    peer.lastSeen = now
    if (host) {
      peer.lastHost = host
      peer.lastPort = port
    }
    void this.save().catch(() => undefined)
  }

  /** The device answered (clears "unreachable since") or did not (sets it once, to [now]). Quiet: written in the background. */
  setReachable(deviceId: string, reachable: boolean, now: number): void {
    const peer = this.peers.get(deviceId)
    if (!peer) return
    if (reachable ? peer.unreachableSince === undefined : peer.unreachableSince !== undefined) return
    if (reachable) delete peer.unreachableSince
    else peer.unreachableSince = now
    void this.save().catch(() => undefined)
  }

  async remove(deviceId: string): Promise<Peer | undefined> {
    const peer = this.peers.get(deviceId)
    if (!peer) return undefined
    this.peers.delete(deviceId)
    await this.save()
    return peer
  }

  /** Resolves when every save started so far has finished (for tests and shutdown). */
  async settled(): Promise<void> {
    await this.tail
  }

  private save(): Promise<void> {
    const run = this.tail.then(() => writeJsonAtomic(this.file, {v: 1, peers: this.list()} satisfies File))
    this.tail = run.catch(() => undefined)
    return run
  }
}
