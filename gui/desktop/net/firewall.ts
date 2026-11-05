// Windows Firewall and devices (dev-docs/plans/DEVICES-DISCOVERY.md, D1). The installer adds two allow rules for the Keepfin program on every
// network profile (TCP 47215 `Keepfin devices`, UDP 5353 `Keepfin discovery`). This module answers, without elevation, whether the rules
// that exist let other devices in on the network this PC is on (a block rule wins), and can add the same two rules for the running program in
// one elevated (permission prompt) step. It never throws into the service: a failed check reads as "nothing to warn about". Elsewhere it does
// nothing. Nothing but the program path is ever put into a command, and the path is validated first.
import {execFile} from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'

export const DEVICES_TCP_PORT = 47215
export const DISCOVERY_UDP_PORT = 5353

export interface FirewallStatus {
  /** Windows would drop what other devices send to this PC on the current network. */
  blocked: boolean
  /** `Public`, `Private`, `Domain`, or `unknown` when Windows could not say. */
  category: string
}

/** What the service needs; tests pass their own. */
export interface FirewallPort {
  /** Reads the firewall state for this program, or undefined (not Windows, or it could not be read). */
  check(): Promise<FirewallStatus | undefined>
  /** Adds both allow rules for this program (Windows asks for permission). Resolves with what happened; never rejects. */
  allow(): Promise<'done' | 'declined' | 'failed'>
}

const READ_TIMEOUT_MS = 15_000
const ELEVATE_TIMEOUT_MS = 120_000

/** Adapters such as Tailscale, VPNs and virtual switches: a network category means nothing there. */
const NOT_A_LAN = /tailscale|wireguard|vpn|zerotier|hamachi|\btap\b|\btun\b|vethernet|wsl|hyper-v|virtualbox|vmware|loopback|bluetooth/i

/** Why a program path may not be used in a firewall rule, or undefined when it may: absolute, an .exe that exists, no quotes or control characters. */
export function programPathProblem(file: unknown, exists: (file: string) => boolean = fs.existsSync): string | undefined {
  if (typeof file !== 'string' || !file) return 'no path'
  if (file.length > 400) return 'too long'
  if (/["\u0000-\u001f]/.test(file)) return 'quotes or control characters'
  if (!path.win32.isAbsolute(file) || /^[\\/]{2}/.test(file)) return 'not an absolute local path'
  if (!/\.exe$/i.test(file)) return 'not a program'
  if (!exists(file)) return 'does not exist'
  return undefined
}

/** Reads the facts only. The program path comes in the environment, never in the text of the command. */
export const CHECK_SCRIPT = [
  "$ErrorActionPreference = 'Stop'",
  '$exe = $env:KEEPFIN_EXE',
  '$profiles = @(Get-NetConnectionProfile | ForEach-Object { [pscustomobject]@{Alias = [string]$_.InterfaceAlias; Name = [string]$_.Name; Category = [string]$_.NetworkCategory; V4 = [string]$_.IPv4Connectivity} })',
  '$fw = @(Get-NetFirewallProfile | ForEach-Object { [pscustomobject]@{Name = [string]$_.Name; Enabled = [string]$_.Enabled} })',
  '$rules = @()',
  'foreach ($f in @(Get-NetFirewallApplicationFilter -Program $exe -ErrorAction SilentlyContinue)) {',
  '  $r = $f | Get-NetFirewallRule',
  '  $p = $r | Get-NetFirewallPortFilter',
  '  $rules += [pscustomobject]@{Enabled = [string]$r.Enabled; Direction = [string]$r.Direction; Action = [string]$r.Action; Profile = [string]$r.Profile; Protocol = [string]$p.Protocol; LocalPort = (@($p.LocalPort) -join ",")}',
  '}',
  '[pscustomobject]@{profiles = $profiles; firewall = $fw; rules = @($rules)} | ConvertTo-Json -Compress -Depth 4',
].join('\n')

/** The elevated step: removes this program's block rules and old copies of our two rules, then adds both allow rules for every profile. The path is a quoted literal. */
export function allowScript(exePath: string): string {
  const quoted = `'${exePath.replace(/'/g, "''")}'`
  return [
    "$ErrorActionPreference = 'Stop'",
    'try {',
    `  $exe = ${quoted}`,
    "  foreach ($f in @(Get-NetFirewallApplicationFilter -Program $exe -ErrorAction SilentlyContinue)) { $f | Get-NetFirewallRule | Where-Object { $_.Direction -eq 'Inbound' -and $_.Action -eq 'Block' } | Remove-NetFirewallRule }",
    "  foreach ($n in 'Keepfin devices', 'Keepfin discovery') { Get-NetFirewallRule -DisplayName $n -ErrorAction SilentlyContinue | Where-Object { ($_ | Get-NetFirewallApplicationFilter).Program -eq $exe } | Remove-NetFirewallRule }",
    `  New-NetFirewallRule -DisplayName 'Keepfin devices' -Direction Inbound -Action Allow -Program $exe -Protocol TCP -LocalPort ${DEVICES_TCP_PORT} -Profile Any | Out-Null`,
    `  New-NetFirewallRule -DisplayName 'Keepfin discovery' -Direction Inbound -Action Allow -Program $exe -Protocol UDP -LocalPort ${DISCOVERY_UDP_PORT} -Profile Any | Out-Null`,
    '  exit 0',
    '} catch { exit 4 }',
  ].join('\n')
}

/** The outer, unelevated command: starts an elevated copy of PowerShell with the script as an encoded command (only Base64 characters, so nothing needs quoting). */
export function elevateCommand(inner: string): string {
  const encoded = Buffer.from(inner, 'utf16le').toString('base64')
  return `try { $p = Start-Process -FilePath powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden -ErrorAction Stop -ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','${encoded}'; exit $p.ExitCode } catch { exit 3 }`
}

// ---- reading the answer -------------------------------------------------------------------------------------------------------

const asText = (value: unknown): string => (typeof value === 'string' ? value : typeof value === 'number' || typeof value === 'boolean' ? String(value) : '')

function connected(value: unknown): boolean {
  // Disconnected 0, NoTraffic 1, Subnet 2, LocalNetwork 3, Internet 4 (a number from older PowerShell, a word from newer)
  const text = asText(value)
  if (/^\d+$/.test(text)) return Number(text) >= 2
  return /^(subnet|localnetwork|internet)$/i.test(text)
}

/** `Public`, `Private`, `Domain`, or undefined for a word this does not know. */
function categoryOf(value: unknown): 'Public' | 'Private' | 'Domain' | undefined {
  const text = asText(value).trim()
  if (text === '0' || /^public$/i.test(text)) return 'Public'
  if (text === '1' || /^private$/i.test(text)) return 'Private'
  if (text === '2' || /^domain/i.test(text)) return 'Domain'
  return undefined
}

const truthy = (value: unknown): boolean => /^(true|1)$/i.test(asText(value).trim())

/** The profile names a rule's `Profile` covers (`Any`, `Public, Private`, or a number of flags: Domain 1, Private 2, Public 4). */
function profilesOf(value: unknown): Set<string> {
  const text = asText(value).trim()
  if (/^\d+$/.test(text)) {
    const n = Number(text)
    if (n === 0 || n >= 0x7fffffff) return new Set(['Public', 'Private', 'Domain'])
    return new Set([n & 1 ? 'Domain' : '', n & 2 ? 'Private' : '', n & 4 ? 'Public' : ''].filter(Boolean))
  }
  if (/any|all/i.test(text)) return new Set(['Public', 'Private', 'Domain'])
  const out = new Set<string>()
  for (const part of text.split(/[,\s]+/)) {
    const c = categoryOf(part)
    if (c) out.add(c)
  }
  return out
}

function coversTcpPort(protocol: unknown, localPort: unknown, port: number): boolean {
  const proto = asText(protocol).trim()
  if (!(/^(tcp|any)$/i.test(proto) || proto === '6' || proto === '256')) return false
  const ports = asText(localPort).trim()
  if (!ports) return true
  for (const part of ports.split(/[,\s]+/).filter(Boolean)) {
    if (/^any$/i.test(part)) return true
    const range = /^(\d+)-(\d+)$/.exec(part)
    if (range && Number(range[1]) <= port && port <= Number(range[2])) return true
    if (/^\d+$/.test(part) && Number(part) === port) return true
  }
  return false
}

/**
 * Reads the JSON of {@link CHECK_SCRIPT}. For every connected LAN or Wi-Fi network (Tailscale and other virtual adapters are not LANs) the
 * program must have an enabled inbound allow rule that covers that network's category and TCP 47215, and no enabled block rule that does:
 * a block rule wins. A category whose firewall profile is switched off blocks nothing. Undefined when the text is not an answer.
 */
export function parseFirewallStatus(text: string, port = DEVICES_TCP_PORT): FirewallStatus | undefined {
  let data: unknown
  try {
    data = JSON.parse(text.trim().replace(/^﻿/, ''))
  } catch {
    return undefined
  }
  if (!data || typeof data !== 'object' || Array.isArray(data)) return undefined
  const root = data as Record<string, unknown>
  const list = (value: unknown): Record<string, unknown>[] => (Array.isArray(value) ? value : value && typeof value === 'object' ? [value] : []).filter((x): x is Record<string, unknown> => !!x && typeof x === 'object').slice(0, 256)
  if (!('profiles' in root) && !('rules' in root)) return undefined

  const networks: ('Public' | 'Private' | 'Domain')[] = []
  for (const p of list(root.profiles).slice(0, 32)) {
    if (!connected(p.V4)) continue
    if (NOT_A_LAN.test(asText(p.Alias)) || NOT_A_LAN.test(asText(p.Name))) continue
    const category = categoryOf(p.Category)
    if (category) networks.push(category)
  }
  if (!networks.length) return {blocked: false, category: 'unknown'}

  const disabled = new Set<string>()
  for (const f of list(root.firewall)) {
    const name = categoryOf(f.Name)
    if (name && /^(false|0)$/i.test(asText(f.Enabled).trim())) disabled.add(name)
  }
  const rules = list(root.rules).filter((r) => truthy(r.Enabled) && /^(inbound|1)$/i.test(asText(r.Direction).trim()) && coversTcpPort(r.Protocol, r.LocalPort, port))
  for (const category of networks) {
    if (disabled.has(category)) continue
    const applying = rules.filter((r) => profilesOf(r.Profile).has(category))
    const blocks = applying.some((r) => /^(block|4)$/i.test(asText(r.Action).trim()))
    const allows = applying.some((r) => /^(allow|2)$/i.test(asText(r.Action).trim()))
    if (blocks || !allows) return {blocked: true, category}
  }
  return {blocked: false, category: networks[0]}
}

function powershell(command: string, timeoutMs: number, env?: Record<string, string>): Promise<{code: number | null; out: string}> {
  return new Promise((resolve) => {
    try {
      const child = execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], {timeout: timeoutMs, windowsHide: true, maxBuffer: 1024 * 1024, env: env ? {...process.env, ...env} : process.env}, (error, stdout) => {
        const code = error ? ((error as NodeJS.ErrnoException & {code?: number | string}).code as number | string | undefined) : 0
        resolve({code: typeof code === 'number' ? code : error ? 1 : 0, out: String(stdout ?? '')})
      })
      child.stdin?.end()
    } catch {
      resolve({code: 1, out: ''})
    }
  })
}

/** The real port: PowerShell on Windows. [execPath] and the runner are replaceable for tests. */
export function windowsFirewall(options: {execPath?: string; run?: typeof powershell; platform?: string; exists?: (file: string) => boolean} = {}): FirewallPort {
  const run = options.run ?? powershell
  const platform = () => options.platform ?? process.platform
  const exe = () => options.execPath ?? process.execPath
  return {
    async check() {
      if (platform() !== 'win32') return undefined
      const file = exe()
      if (programPathProblem(file, options.exists)) return undefined
      try {
        const {out} = await run(CHECK_SCRIPT, READ_TIMEOUT_MS, {KEEPFIN_EXE: file})
        return parseFirewallStatus(out)
      } catch {
        return undefined
      }
    },
    async allow() {
      if (platform() !== 'win32') return 'failed'
      const file = exe()
      if (programPathProblem(file, options.exists)) return 'failed'
      const {code} = await run(elevateCommand(allowScript(file)), ELEVATE_TIMEOUT_MS)
      return code === 0 ? 'done' : code === 3 ? 'declined' : 'failed'
    },
  }
}
