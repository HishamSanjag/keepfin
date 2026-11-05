// Discovery (spec 2 and 9.1 #10): DNS-SD service `_keepfin._tcp` through bonjour-service (MIT, pure JavaScript over multicast-dns).
// The TXT record carries only `v` (protocol), `id` (the first 8 bytes of the device id, hex) and `n` (the name); the port comes from
// the SRV record. Everything learned here is a hint: the pin decides who a device is. Only eligible interfaces are used (a private
// IPv4 address on a real adapter: not loopback, link-local, or a virtual switch), and a change of adapters restarts discovery.
import os from 'node:os'
import {Bonjour, type Browser, type Service} from 'bonjour-service'
import type {DiscoveredDevice} from '../../shared/devices.js'
import type {NetLog} from './types.js'

export interface EligibleInterface {
  name: string
  address: string
  /** Dotted netmask (255.255.255.0), when the adapter list has one: the scan walks the /24 around the address. */
  netmask?: string
}

const VIRTUAL = /vethernet|vmware|virtualbox|hyper-v|loopback|docker|wsl|bluetooth|pseudo|isatap|teredo/i

function isPrivateV4(address: string): boolean {
  const [a, b] = address.split('.').map(Number)
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127)
}

/** Real, up, private-IPv4 adapters, best first (a 192.168/10./172.16 address beats a Tailscale 100.64 one). */
export function eligibleInterfaces(interfaces: NodeJS.Dict<os.NetworkInterfaceInfo[]> = os.networkInterfaces()): EligibleInterface[] {
  const out: EligibleInterface[] = []
  for (const [name, list] of Object.entries(interfaces)) {
    if (VIRTUAL.test(name)) continue
    for (const info of list ?? []) {
      if (info.family !== 'IPv4' || info.internal || info.address.startsWith('169.254.') || !isPrivateV4(info.address)) continue
      out.push({name, address: info.address, netmask: info.netmask})
    }
  }
  return out.sort((a, b) => Number(a.address.startsWith('100.')) - Number(b.address.startsWith('100.')))
}

export interface DiscoveryOptions {
  deviceId: string
  name: string
  port: number
  log?: NetLog
  /** Hints of paired devices, to mark them in the list. */
  isPaired(hint: string): boolean
  changed(): void
  /** Adapter list (tests inject their own). */
  interfaces?: () => EligibleInterface[]
}

const SERVICE = 'keepfin'

export class Discovery {
  private bonjour?: Bonjour
  private browser?: Browser
  private published?: {stop?: (cb?: () => void) => void}
  private readonly found = new Map<string, DiscoveredDevice & {key: string}>()
  private signature = ''
  private timer?: NodeJS.Timeout
  private browsing = false

  constructor(private readonly options: DiscoveryOptions) {}

  private get interfaces(): EligibleInterface[] {
    return (this.options.interfaces ?? (() => eligibleInterfaces()))()
  }

  /** The address people on the same network would use to reach this device. */
  primaryAddress(): string | null {
    return this.interfaces[0]?.address ?? null
  }

  start(): void {
    this.begin()
    // adapters come and go (VPN up, Wi-Fi switch): look every 20 seconds and restart on a change
    this.timer = setInterval(() => {
      const now = this.interfaces.map((i) => i.address).join(',')
      if (now !== this.signature) {
        this.options.log?.(`network adapters changed: ${now || 'none'}`)
        this.end()
        this.begin()
      }
    }, 20_000)
    this.timer.unref?.()
  }

  private begin(): void {
    const eligible = this.interfaces
    this.signature = eligible.map((i) => i.address).join(',')
    if (!eligible.length) return // nothing to advertise on; by-address still works
    try {
      this.bonjour = new Bonjour({interface: eligible[0].address} as never, (error: unknown) => this.options.log?.(`discovery error ${error instanceof Error ? error.message : String(error)}`))
      this.published = this.bonjour.publish({
        name: `Keepfin-${this.options.deviceId.slice(0, 8)}`,
        type: SERVICE,
        protocol: 'tcp',
        port: this.options.port,
        txt: {v: '1', id: this.options.deviceId.slice(0, 16), n: this.options.name},
      }) as never
      if (this.browsing) this.browse()
    } catch (error) {
      this.options.log?.(`discovery could not start: ${error instanceof Error ? error.message : String(error)}`)
      this.bonjour = undefined
    }
  }

  /** Starts listening for other devices (while the Devices page is open or a sync is due). */
  browse(): void {
    this.browsing = true
    if (!this.bonjour || this.browser) return
    this.browser = this.bonjour.find({type: SERVICE, protocol: 'tcp'})
    this.browser.on('up', (service: Service) => this.up(service))
    this.browser.on('down', (service: Service) => {
      if (this.found.delete(service.fqdn ?? service.name)) this.options.changed()
    })
  }

  stopBrowsing(): void {
    this.browsing = false
    this.browser?.stop()
    this.browser = undefined
    this.found.clear()
  }

  private up(service: Service): void {
    const txt = (service.txt ?? {}) as Record<string, unknown>
    const hint = typeof txt.id === 'string' ? txt.id.toLowerCase() : ''
    if (!/^[0-9a-f]{16}$/.test(hint) || hint === this.options.deviceId.slice(0, 16)) return
    if (txt.v !== '1' && txt.v !== 1) return
    const addresses = (service.addresses ?? []).filter((a) => /^\d+\.\d+\.\d+\.\d+$/.test(a) || a.includes(':'))
    const host = addresses.find((a) => !a.includes(':')) ?? addresses[0]
    if (!host || !Number.isInteger(service.port) || service.port < 1 || service.port > 65535) return
    const name = typeof txt.n === 'string' && txt.n.trim() ? txt.n.trim().slice(0, 40) : service.name
    this.found.set(service.fqdn ?? service.name, {key: service.fqdn ?? service.name, hint, name, host, port: service.port, paired: this.options.isPaired(hint), route: 'lan'})
    this.options.changed()
  }

  list(): DiscoveredDevice[] {
    return [...this.found.values()].map(({key: _key, ...device}) => ({...device, paired: this.options.isPaired(device.hint)}))
  }

  private end(): void {
    try {
      this.browser?.stop()
      this.published?.stop?.()
      this.bonjour?.unpublishAll()
      this.bonjour?.destroy()
    } catch {
      // closing is best effort
    }
    this.browser = undefined
    this.published = undefined
    this.bonjour = undefined
    this.found.clear()
  }

  stop(): void {
    clearInterval(this.timer)
    this.end()
  }
}
