// Tailscale (dev-docs/plans/DEVICES-DISCOVERY.md, D3). Keepfin never talks to Tailscale's servers: it only notices that this PC is on a tailnet
// (an adapter with an address in 100.64.0.0/10) and, when the Tailscale program is installed, reads `tailscale status --json` for this PC's
// MagicDNS name and the devices that are online, so they can be offered in Nearby ("via Tailscale") and paired like any other device.
import {execFile} from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

/** An address in 100.64.0.0/10 (the range Tailscale hands out). */
export function isTailscaleAddress(address: string): boolean {
  const m = /^(\d+)\.(\d+)\.\d+\.\d+$/.exec(address)
  return !!m && Number(m[1]) === 100 && Number(m[2]) >= 64 && Number(m[2]) <= 127
}

/** A Tailscale MagicDNS name (`desk-pc.tailnet-name.ts.net`) or a Tailscale address: how a device on a tailnet is reached. */
export const isTailscaleHost = (host: string): boolean => isTailscaleAddress(host) || /\.ts\.net\.?$/i.test(host)

/** This PC's own Tailscale address from the adapter list (the first one up), or undefined when it is not on a tailnet. */
export function detectTailscaleAddress(interfaces: NodeJS.Dict<os.NetworkInterfaceInfo[]> = os.networkInterfaces()): string | undefined {
  for (const list of Object.values(interfaces)) {
    for (const info of list ?? []) if (info.family === 'IPv4' && !info.internal && isTailscaleAddress(info.address)) return info.address
  }
  return undefined
}

export interface TailscalePeer {
  name: string
  address: string
  online: boolean
}

export interface TailscaleStatus {
  /** The backend is running and signed in. */
  running: boolean
  /** This PC's MagicDNS name without the trailing dot, when known. */
  name?: string
  address?: string
  peers: TailscalePeer[]
}

const cleanName = (value: unknown): string | undefined => {
  if (typeof value !== 'string') return undefined
  const text = value.trim().replace(/\.$/, '')
  return text && text.length <= 253 && /^[0-9A-Za-z._-]+$/.test(text) ? text : undefined
}

const v4 = (value: unknown): string | undefined => (Array.isArray(value) ? value.find((x): x is string => typeof x === 'string' && isTailscaleAddress(x)) : undefined)

/** Reads the JSON of `tailscale status --json`. Garbage and unknown shapes give undefined; at most 256 peers are read. */
export function parseTailscaleStatus(text: string): TailscaleStatus | undefined {
  let data: unknown
  try {
    data = JSON.parse(text.trim().replace(/^\uFEFF/, ''))
  } catch {
    return undefined
  }
  if (!data || typeof data !== 'object' || Array.isArray(data)) return undefined
  const root = data as Record<string, unknown>
  const self = root.Self && typeof root.Self === 'object' ? (root.Self as Record<string, unknown>) : undefined
  if (!self && typeof root.BackendState !== 'string') return undefined
  const peers: TailscalePeer[] = []
  const table = root.Peer && typeof root.Peer === 'object' && !Array.isArray(root.Peer) ? (root.Peer as Record<string, unknown>) : {}
  for (const entry of Object.values(table).slice(0, 256)) {
    if (!entry || typeof entry !== 'object') continue
    const p = entry as Record<string, unknown>
    const address = v4(p.TailscaleIPs)
    const name = cleanName(p.DNSName) ?? cleanName(p.HostName)
    if (!address || !name) continue
    peers.push({name, address, online: p.Online === true})
  }
  return {
    running: root.BackendState === 'Running',
    name: cleanName(self?.DNSName) ?? cleanName(self?.HostName),
    address: v4(self?.TailscaleIPs),
    peers,
  }
}

/** Where the Tailscale program lives on Windows. */
export function tailscaleProgram(env: NodeJS.ProcessEnv = process.env): string {
  return path.win32.join(env.ProgramFiles || 'C:\\Program Files', 'Tailscale', 'tailscale.exe')
}

/** Runs `tailscale status --json` (no shell, a timeout), or undefined when the program is missing or fails. */
export type StatusReader = () => Promise<TailscaleStatus | undefined>

export function windowsStatusReader(options: {program?: string; platform?: string; exists?: (file: string) => boolean; timeoutMs?: number} = {}): StatusReader {
  return () =>
    new Promise((resolve) => {
      if ((options.platform ?? process.platform) !== 'win32') return resolve(undefined)
      const program = options.program ?? tailscaleProgram()
      if (!(options.exists ?? fs.existsSync)(program)) return resolve(undefined)
      try {
        const child = execFile(program, ['status', '--json'], {timeout: options.timeoutMs ?? 4000, windowsHide: true, maxBuffer: 4 * 1024 * 1024}, (error, stdout) => {
          // a stopped Tailscale answers with a non-zero code and still prints a status
          void error
          resolve(stdout ? parseTailscaleStatus(String(stdout)) : undefined)
        })
        child.stdin?.end()
      } catch {
        resolve(undefined)
      }
    })
}
