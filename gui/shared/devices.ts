// Devices on the network (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md): what Settings → Devices shows and asks for. The renderer never
// sees keys, tokens or nonces: only names, fingerprints, the six comparison digits and the QR link. Pure types and helpers.

/** What a paired device may do with one library (spec 9.1 #5). Sync is the default; the rest are chosen by the user. */
export type DeviceAction = 'metadata.read' | 'metadata.write' | 'inventory.read' | 'media.read' | 'copy.mutate' | 'jobs' | 'membership.admin'

export interface DeviceLibraryAccess {
  libraryId: string
  /** The name people know the library by on this device. */
  name: string
  /** What this device lets the peer do. */
  actions: DeviceAction[]
}

export interface PairedDevice {
  deviceId: string
  /** Groups of four hex digits people compare. */
  fingerprint: string
  name: string
  platform: string
  pairedAt: number
  lastSeen: number | null
  /** An open connection to it right now. */
  online: boolean
  libraries: DeviceLibraryAccess[]
  /** Since when it could not be reached (the first failed attempt after it was last seen), or null while it answers. */
  unreachableSince: number | null
  /** This device may play the files of that device (its last answer allowed media.read for a library): the library lists items it has as "On <device>". */
  playFrom?: boolean
  /** The address Keepfin tries first (what was typed or last worked); a Tailscale name stays a name. */
  address: string | null
  /** How the open connection runs: on this network or through Tailscale; null while it is not connected. */
  route: 'lan' | 'tailscale' | null
  /** When this device last finished syncing the library with it (since Keepfin started), and how many changes went each way. */
  lastSync?: {at: number; pulled: number; pushed: number} | null
  /** This device may send downloads to it ("Download on…"), and where it lets them be saved. */
  jobs: {allowed: boolean; spaces: {id: string; name: string}[]}
  /** Libraries on this device that are not shared with it yet (it can be offered a copy of them). */
  shareable: {libraryId: string; name: string}[]
}

/** A library a paired device shares with this one that this device does not hold: "Get this library". */
export interface LibraryOffer {
  deviceId: string
  deviceName: string
  libraryId: string
  name: string
  /** The device is connected now (joining needs it). */
  online: boolean
}

/** The join in progress, or the last one that ended. */
export interface JoinView {
  deviceId: string
  deviceName: string
  libraryId: string
  name: string
  folder: string
  stage: 'folder' | 'asking' | 'pulling' | 'done' | 'failed'
  /** Segment files taken in so far. */
  segments: number
  error?: string
  /** True when the failure can be continued by choosing the same folder again. */
  resumable?: boolean
}

/** A download this device asked another one to make: shown with the downloads, not in the local queue. */
export interface RemoteJobRow {
  requestId: string
  deviceId: string
  deviceName: string
  url: string
  state: 'sending' | 'refused' | 'queued' | 'preparing' | 'downloading' | 'processing' | 'completed' | 'failed' | 'cancelled' | 'paused' | 'consent'
  percent: number
  title?: string
  label?: string
  speed?: number
  eta?: number
  totalBytes?: number
  error?: string
  createdAt: number
}

/** A library profile on this device (the person can open another). */
export interface LibraryProfile {
  id: string
  name: string
  libraryId: string
  current: boolean
}

export interface DiscoveredDevice {
  /** The first 8 bytes of the device id, from the DNS-SD record (a hint, never trusted). */
  hint: string
  name: string
  host: string
  port: number
  /** A device with this hint is already paired. */
  paired: boolean
  /** How it was reached: on this network (DNS-SD or the scan), or through Tailscale. */
  route: 'lan' | 'tailscale'
  /** `windows` or `android`, when the device said (the scan and Tailscale learn it from /v1/announce). */
  platform?: string
}

/** `waitingForPeer`: the other device is not in Add a device yet; Keepfin asks again every few seconds and connects as soon as it is open. */
export type PairingStage = 'connecting' | 'waitingForPeer' | 'comparing' | 'waiting' | 'approving' | 'done' | 'failed'

/** One pairing in progress, seen from this device. */
export interface PairingView {
  id: string
  /** `sas`: both people compare six digits. `qr`: the device that showed the QR approves. */
  method: 'sas' | 'qr'
  /** The device that started the connection asks; the other answers. */
  role: 'asking' | 'answering'
  stage: PairingStage
  peerName: string
  peerFingerprint: string
  /** The six digits, once both nonces are known. */
  sas?: string
  /** This device's person already tapped Codes match: the dialog now waits for the other device's person. */
  confirmed?: boolean
  /** Why it stopped (plain words). */
  error?: string
  expiresAt: number
  /** Answering side: the other device asked without being invited (the window was closed): <name> wants to connect. */
  unrequested?: boolean
  /** Answering side, once done: a scanned code approved it, so the page shows <name> connected with Undo. */
  autoApproved?: boolean
}

export interface DevicesState {
  /** False when secure storage is missing: the page explains it instead of offering pairing. */
  available: boolean
  /** Why not (plain words). */
  unavailable?: string
  thisDevice?: {name: string; fingerprint: string; platform: 'windows'}
  /** Devices are switched on: the server listens and this device can be found. */
  enabled: boolean
  /** The port the server listens on (47215), or why it could not (another program has it). */
  port?: number
  portError?: string
  /** Signed library: this device wrote its membership root, so signed segments are enforced for the library. */
  libraries: {libraryId: string; name: string; signed: boolean; admin: boolean}[]
  paired: PairedDevice[]
  discovered: DiscoveredDevice[]
  /** This device is answering pairing requests until then (the 2-minute window), or null. */
  pairingUntil: number | null
  pairing: PairingView | null
  /** The pairing link shown as a QR code while a QR is active. */
  qr?: {link: string; /** A data URL of the QR picture. */ image: string; /** The typed code (`XXXXX-XXXXX`) that does what the QR does for someone without a camera; renewed with it. */ code?: string; expiresAt: number}
  /** Short notes for the page (for example: the firewall asked, multicast looks blocked). */
  notes: string[]
  /** Libraries paired devices share that this device does not hold. */
  offers: LibraryOffer[]
  join: JoinView | null
  /** Downloads this device sent to other devices. */
  remoteJobs: RemoteJobRow[]
  /** The library profiles on this device (filled in by the app; absent in tests). */
  profiles?: LibraryProfile[]
  /** Windows Firewall: `blocked` when it drops what other devices send to this PC on the current network. [notice] is the last answer to Allow Keepfin. */
  firewall: {blocked: boolean; category: string; notice?: string}
  /** Tailscale: on when this PC has a Tailscale address; [name] is its MagicDNS name when known. */
  tailscale: {state: 'off' | 'on'; name?: string; address?: string}
  /** A home-network (non-Tailscale) address is up, so other devices on the network can find this one. */
  lan: boolean
  /** The scan of the local network and Tailscale is running (Nearby shows "Looking on this network…"). */
  scanning: boolean
}

export interface DevicesArgs {
  host?: string
  port?: number
  /** A pasted address (`host`, `host:port`) or a `keepfin://pair?...` link. */
  address?: string
  pairingId?: string
  /** The person compared the numbers (true) or they differ (false). */
  match?: boolean
  deviceId?: string
  libraryId?: string
  actions?: DeviceAction[]
  name?: string
  /** devicesJoin: the folder chosen for the library (a new or an empty one). */
  path?: string
  /** devicesJob: what to download there. */
  url?: string
  format?: unknown
  options?: unknown
  space?: string
  /** devicesJobDismiss. */
  requestId?: string
  /** devicesPlayFrom: the content of the item (`sha256:…`) and the library it is in. */
  contentId?: string
  /** devicesOpenLibrary: the profile to open (the app restarts). */
  profileId?: string
}

/** What devicesPlayFrom answers: a media URL the player can use (Range works), and which device serves it. */
export interface PlayFromResult {
  url: string
  deviceName: string
  size: number
}

export const DEFAULT_PORT = 47215
export const PAIRING_WINDOW_MS = 2 * 60 * 1000

export const ACTION_LABELS: Record<DeviceAction, string> = {
  'metadata.read': 'Read the library',
  'metadata.write': 'Change the library',
  'inventory.read': 'See which files are here',
  'media.read': 'Play files from this device',
  'copy.mutate': 'Keep a copy on this device',
  jobs: 'Download on this device',
  'membership.admin': 'Add and remove devices',
}

/** Parses `host`, `host:port`, `[v6]:port` or a Tailscale name into a host and port. Null when it is not an address. */
export function parseAddress(text: string): {host: string; port: number} | null {
  const value = text.trim()
  if (!value || value.length > 255 || /\s|\//.test(value)) return null
  const v6 = /^\[([0-9a-fA-F:.%a-zA-Z0-9]+)\](?::(\d{1,5}))?$/.exec(value)
  let host: string
  let portText: string | undefined
  if (v6) {
    host = v6[1]
    portText = v6[2]
  } else if ((value.match(/:/g) ?? []).length > 1) {
    host = value // a bare IPv6 address
  } else {
    const at = value.lastIndexOf(':')
    host = at < 0 ? value : value.slice(0, at)
    portText = at < 0 ? undefined : value.slice(at + 1)
  }
  if (!host || !/^[0-9A-Za-z.:%_-]+$/.test(host)) return null
  const port = portText === undefined ? DEFAULT_PORT : Number(portText)
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null
  return {host, port}
}
