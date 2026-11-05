// Windows Firewall (dev-docs/plans/DEVICES-DISCOVERY.md D1): the non-elevated check on sample PowerShell output, the validation of the program
// path, what goes into the elevated command, and the service's card (Allow Keepfin), all with injected runners: nothing here touches this
// machine's firewall.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {allowScript, CHECK_SCRIPT, elevateCommand, parseFirewallStatus, programPathProblem, windowsFirewall, type FirewallPort, type FirewallStatus} from './firewall.js'
import {until, World} from './testkit.js'

const lan = (category: string | number, extra: Record<string, unknown> = {}) => ({Alias: 'Ethernet', Name: 'Network', Category: String(category), V4: 'Internet', ...extra})
const rule = (extra: Record<string, unknown> = {}) => ({Enabled: 'True', Direction: 'Inbound', Action: 'Allow', Profile: 'Any', Protocol: 'TCP', LocalPort: '47215', ...extra})
const answer = (profiles: unknown[], rules: unknown[], firewall: unknown[] = []) => JSON.stringify({profiles, firewall, rules})

test('an allow rule for every profile on a public network: not blocked', () => {
  assert.deepEqual(parseFirewallStatus(answer([lan('Public')], [rule()])), {blocked: false, category: 'Public'})
})

test('no rule at all on a public network: blocked (this is how the owner\'s PC looked)', () => {
  assert.deepEqual(parseFirewallStatus(answer([lan('Public')], [])), {blocked: true, category: 'Public'})
})

test('a rule for Private only does not cover a Public network, but covers a Private one', () => {
  const privateOnly = [rule({Profile: 'Private'})]
  assert.deepEqual(parseFirewallStatus(answer([lan('Public')], privateOnly)), {blocked: true, category: 'Public'})
  assert.deepEqual(parseFirewallStatus(answer([lan('Private')], privateOnly)), {blocked: false, category: 'Private'})
})

test('profile as flag numbers and lists: Domain 1, Private 2, Public 4, Any 0', () => {
  assert.equal(parseFirewallStatus(answer([lan(0)], [rule({Profile: 6})]))?.blocked, false, 'Private + Public covers Public (0)')
  assert.equal(parseFirewallStatus(answer([lan(0)], [rule({Profile: 2})]))?.blocked, true, 'Private alone does not')
  assert.equal(parseFirewallStatus(answer([lan(1)], [rule({Profile: 0})]))?.blocked, false, 'Any')
  assert.equal(parseFirewallStatus(answer([lan(0)], [rule({Profile: 'Public, Private'})]))?.blocked, false)
  assert.equal(parseFirewallStatus(answer([lan('DomainAuthenticated')], [rule({Profile: 'Domain'})]))?.category, 'Domain')
})

test('a block rule wins over an allow rule, but only when it applies to this network and to TCP 47215', () => {
  const allow = rule()
  assert.deepEqual(parseFirewallStatus(answer([lan('Public')], [allow, rule({Action: 'Block'})])), {blocked: true, category: 'Public'})
  assert.equal(parseFirewallStatus(answer([lan('Public')], [allow, rule({Action: 'Block', Profile: 'Private'})]))?.blocked, false, 'a block on another profile')
  assert.equal(parseFirewallStatus(answer([lan('Public')], [allow, rule({Action: 'Block', Protocol: 'UDP', LocalPort: '5353'})]))?.blocked, false, 'a block on UDP')
  assert.equal(parseFirewallStatus(answer([lan('Public')], [allow, rule({Action: 'Block', Enabled: 'False'})]))?.blocked, false, 'a disabled block')
  assert.equal(parseFirewallStatus(answer([lan('Public')], [allow, rule({Action: 'Block', Direction: 'Outbound'})]))?.blocked, false, 'an outbound block')
  // the rules Windows makes when its prompt is dismissed: block TCP and UDP for the program, any port
  assert.equal(parseFirewallStatus(answer([lan('Public')], [allow, rule({Action: 'Block', LocalPort: 'Any'}), rule({Action: 'Block', Protocol: 'UDP', LocalPort: 'Any'})]))?.blocked, true)
})

test('only enabled inbound TCP rules that cover the port count: ranges, lists and Any', () => {
  assert.equal(parseFirewallStatus(answer([lan('Public')], [rule({Enabled: 'False'})]))?.blocked, true, 'disabled')
  assert.equal(parseFirewallStatus(answer([lan('Public')], [rule({Direction: 'Outbound'})]))?.blocked, true, 'outbound')
  assert.equal(parseFirewallStatus(answer([lan('Public')], [rule({LocalPort: '80,443'})]))?.blocked, true, 'other ports')
  assert.equal(parseFirewallStatus(answer([lan('Public')], [rule({LocalPort: '80,47215'})]))?.blocked, false, 'a list')
  assert.equal(parseFirewallStatus(answer([lan('Public')], [rule({LocalPort: '47000-48000'})]))?.blocked, false, 'a range')
  assert.equal(parseFirewallStatus(answer([lan('Public')], [rule({LocalPort: 'Any', Protocol: 'Any'})]))?.blocked, false, 'any port, any protocol')
  assert.equal(parseFirewallStatus(answer([lan('Public')], [rule({Protocol: 'UDP', LocalPort: '5353'})]))?.blocked, true, 'UDP only')
  assert.equal(parseFirewallStatus(answer([lan('Public')], [rule({Enabled: true, Action: 2, Direction: 1, Protocol: 6, LocalPort: 47215})]))?.blocked, false, 'numbers from older PowerShell')
})

test('Tailscale and other virtual adapters are not networks to check; a disconnected network is not either; a switched-off firewall blocks nothing', () => {
  const tailscale = lan('Public', {Alias: 'Tailscale', Name: 'tailnet'})
  assert.deepEqual(parseFirewallStatus(answer([tailscale], [])), {blocked: false, category: 'unknown'})
  assert.deepEqual(parseFirewallStatus(answer([lan('Public', {V4: 'Disconnected'})], [])), {blocked: false, category: 'unknown'})
  assert.equal(parseFirewallStatus(answer([lan('Public')], [], [{Name: 'Public', Enabled: 'False'}]))?.blocked, false)
  assert.equal(parseFirewallStatus(answer([lan('Public')], [], [{Name: 'Private', Enabled: 'False'}]))?.blocked, true, 'another profile being off changes nothing')
  // Wi-Fi private and allowed, Ethernet public and not: the answer names the one that is blocked
  assert.deepEqual(parseFirewallStatus(answer([lan('Private', {Alias: 'Wi-Fi'}), lan('Public')], [rule({Profile: 'Private'})])), {blocked: true, category: 'Public'})
})

test('output that is not an answer reads as unknown, never as a warning', () => {
  for (const bad of ['', 'not json', 'null', '42', '[]', '{}', '"x"', '{"profiles": ']) assert.equal(parseFirewallStatus(bad), undefined, bad)
  assert.equal(parseFirewallStatus('\uFEFF' + answer([lan('Public')], [rule()]) + '\r\n')?.blocked, false, 'a byte order mark and a newline')
  // one rule or one profile as a plain object (PowerShell 5 unwraps a one-element array)
  assert.equal(parseFirewallStatus(JSON.stringify({profiles: lan('Public'), firewall: {Name: 'Public', Enabled: 'True'}, rules: rule()}))?.blocked, false)
})

// ---- the program path --------------------------------------------------------------------------------------------------------------

test('the program path must be absolute, an .exe that exists, and free of quotes and control characters', () => {
  const exists = () => true
  assert.equal(programPathProblem('C:\\Program Files\\Keepfin\\Keepfin.exe', exists), undefined)
  assert.equal(programPathProblem('C:\\Users\\O\'Brien\\app\\Keepfin.exe', exists), undefined, 'an apostrophe is fine: it is doubled inside the quoted literal')
  for (const bad of ['', 'Keepfin.exe', '.\\Keepfin.exe', '\\\\server\\share\\Keepfin.exe', 'C:\\app\\"Keepfin".exe', 'C:\\app\\a".exe', 'C:\\app\\a\nb.exe', 'C:\\app\\a\u0000.exe', 'C:\\app\\data.txt', 'C:/' + 'x'.repeat(500) + '.exe']) {
    assert.ok(programPathProblem(bad, exists), `rejected: ${JSON.stringify(bad)}`)
  }
  assert.equal(programPathProblem('C:\\gone\\Keepfin.exe', () => false), 'does not exist')
  assert.equal(programPathProblem(undefined as never), 'no path')
})

test('the elevated command: the path is one quoted literal, the outer command carries only Base64, and the rules are the two of the plan', () => {
  const script = allowScript("C:\\Users\\O'Brien\\Keepfin.exe")
  assert.match(script, /\$exe = 'C:\\Users\\O''Brien\\Keepfin\.exe'/)
  assert.match(script, /-DisplayName 'Keepfin devices' -Direction Inbound -Action Allow -Program \$exe -Protocol TCP -LocalPort 47215 -Profile Any/)
  assert.match(script, /-DisplayName 'Keepfin discovery' -Direction Inbound -Action Allow -Program \$exe -Protocol UDP -LocalPort 5353 -Profile Any/)
  assert.match(script, /Action -eq 'Block'/, 'a block rule of the prompt Windows showed is removed, or it would still win')
  const outer = elevateCommand(script)
  assert.match(outer, /-Verb RunAs/)
  const encoded = /'-EncodedCommand','([^']*)'/.exec(outer)![1]
  assert.match(encoded, /^[A-Za-z0-9+/=]+$/, 'only Base64 characters reach the outer command')
  assert.equal(Buffer.from(encoded, 'base64').toString('utf16le'), script)
  assert.ok(!outer.includes('Brien') && !outer.includes('Keepfin.exe'), 'no part of the path is in the outer command')
})

test('the check reads with the path in the environment, never in the command', async () => {
  const seen: {command: string; env?: Record<string, string>}[] = []
  const port = windowsFirewall({
    execPath: 'C:\\Program Files\\Keepfin\\Keepfin.exe',
    platform: 'win32',
    exists: () => true,
    run: async (command, _timeout, env) => {
      seen.push({command, env})
      return {code: 0, out: answer([lan('Public')], [])}
    },
  })
  assert.deepEqual(await port.check(), {blocked: true, category: 'Public'})
  assert.equal(seen.length, 1)
  assert.equal(seen[0].command, CHECK_SCRIPT)
  assert.deepEqual(seen[0].env, {KEEPFIN_EXE: 'C:\\Program Files\\Keepfin\\Keepfin.exe'})
  assert.ok(!seen[0].command.includes('Keepfin.exe'))
  // development: the program is electron.exe; the check works the same
  const dev = windowsFirewall({execPath: 'F:\\dev\\keepfin\\node_modules\\electron\\dist\\electron.exe', platform: 'win32', exists: () => true, run: async () => ({code: 0, out: answer([lan('Private')], [rule({Profile: 'Private'})])})})
  assert.deepEqual(await dev.check(), {blocked: false, category: 'Private'})
})

test('allow: a bad path is never run; the exit codes read as done, declined or failed; elsewhere it does nothing', async () => {
  const ran: string[] = []
  const make = (code: number, execPath = 'C:\\Keepfin\\Keepfin.exe', platform = 'win32') =>
    windowsFirewall({
      execPath,
      platform,
      exists: () => true,
      run: async (command) => {
        ran.push(command)
        return {code, out: ''}
      },
    })
  assert.equal(await make(0).allow(), 'done')
  assert.equal(await make(3).allow(), 'declined')
  assert.equal(await make(4).allow(), 'failed')
  assert.equal(ran.length, 3)
  assert.equal(await make(0, 'C:\\Keepfin\\"x".exe').allow(), 'failed')
  assert.equal(await make(0, 'relative.exe').allow(), 'failed')
  assert.equal(await make(0, 'C:\\Keepfin\\Keepfin.exe', 'linux').allow(), 'failed')
  assert.equal(ran.length, 3, 'nothing was run for the bad paths or on another system')
  assert.equal(await make(0, 'C:\\Keepfin\\Keepfin.exe', 'linux').check(), undefined)
  assert.equal(await windowsFirewall({execPath: 'C:\\x\\a.exe', platform: 'win32', exists: () => false, run: async () => ({code: 0, out: ''})}).check(), undefined, 'a missing program reads as unknown')
})

// ---- the service's card ------------------------------------------------------------------------------------------------------------

function fakeFirewall(initial: FirewallStatus | undefined) {
  const state = {current: initial, allowed: 0, result: 'declined' as 'done' | 'declined' | 'failed', reads: 0}
  const port: FirewallPort = {
    check: async () => {
      state.reads++
      return state.current
    },
    allow: async () => {
      state.allowed++
      if (state.result === 'done') state.current = {blocked: false, category: state.current?.category ?? 'Public'}
      return state.result
    },
  }
  return {state, port}
}

test('the service reports a blocking firewall while devices are on, Allow Keepfin clears it, and a later change is noticed', async () => {
  const w = await World.create()
  try {
    const fw = fakeFirewall({blocked: true, category: 'Public'})
    const a = await w.node('A', {firewall: fw.port, firewallMs: 40})
    await until(() => a.service.state().firewall.blocked, 'the firewall card')
    assert.equal(a.service.state().firewall.category, 'Public')
    // declining the permission prompt: a calm note, still blocked
    await a.service.allowFirewall()
    assert.equal(fw.state.allowed, 1)
    assert.match(a.service.state().firewall.notice ?? '', /did not get your permission/)
    assert.equal(a.service.state().firewall.blocked, true)
    // the prompt fails: the note says where to do it by hand
    fw.state.result = 'failed'
    await a.service.allowFirewall()
    assert.match(a.service.state().firewall.notice ?? '', /Allow an app through firewall/)
    // allowing it: the next read finds it open and the card goes
    fw.state.result = 'done'
    await a.service.allowFirewall()
    assert.equal(a.service.state().firewall.blocked, false)
    assert.equal(a.service.state().firewall.notice, undefined)
    // a new network later (a cafe): the periodic read notices
    fw.state.current = {blocked: true, category: 'Public'}
    await until(() => a.service.state().firewall.blocked, 'the periodic re-check')
    // turning devices off removes the card
    await a.service.disable()
    assert.deepEqual(a.service.state().firewall, {blocked: false, category: 'unknown'})
  } finally {
    await w.cleanup()
  }
})

test('rules added but still blocked (an organisation rule) say so instead of claiming success', async () => {
  const w = await World.create()
  try {
    const state = {allowed: 0}
    const a = await w.node('A', {
      firewall: {
        check: async () => ({blocked: true, category: 'Domain'}),
        allow: async () => {
          state.allowed++
          return 'done' as const
        },
      },
      firewallMs: 10_000,
    })
    await until(() => a.service.state().firewall.blocked, 'the card')
    await a.service.allowFirewall()
    assert.match(a.service.state().firewall.notice ?? '', /still blocks Keepfin here/)
  } finally {
    await w.cleanup()
  }
})

test('a failing firewall reader never breaks the service', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A', {
      firewall: {
        check: async () => {
          throw new Error('powershell is missing')
        },
        allow: async () => 'failed' as const,
      },
      firewallMs: 30,
    })
    await new Promise((r) => setTimeout(r, 150))
    assert.deepEqual(a.service.state().firewall, {blocked: false, category: 'unknown'})
    assert.equal(a.service.state().enabled, true)
  } finally {
    await w.cleanup()
  }
})
