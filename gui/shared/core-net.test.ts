// The shared core's network adapter (gui/shared/core/net.ts and the membership parts of sync.ts) through the JavaScript build
// of the core: the fixtures in fixtures/net (written with node:crypto), signed segments between the JavaScript and the
// JVM-free paths, and a membership round trip. The rules themselves are proved in core/src/commonTest on the JVM and Node.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID, createHash} from 'node:crypto'
import {readFileSync} from 'node:fs'
import {
  authorize, checkDownloadUrl, checkRedirect, checkResolved, deniedAddressClass, deviceIdOf, DOMAIN_SEGMENT, generateDeviceKey,
  pairCodeOf, parsePairCode, pinMatches, pairCommitment, pairSas, pairVerifyReveal, parsePairQr, buildPairQr, signDomain, verifyDomain, isDeviceKey, type AuthzContext, type NetAction,
} from './core/net.js'
import {SyncLog, decodeSegment, encodeSegment, makeOp} from './core/sync.js'

const fixture = (name: string) => JSON.parse(readFileSync(new URL(`../../fixtures/net/${name}.json`, import.meta.url), 'utf8'))
const hex = (text: string) => new Uint8Array(Buffer.from(text, 'hex'))
const toHex = (bytes: Uint8Array) => Buffer.from(bytes).toString('hex')

test('signatures from node:crypto verify, and any change fails', () => {
  const f = fixture('keys')
  const a = hex(f.keyA.spki)
  assert.equal(deviceIdOf(a), f.keyA.deviceId)
  for (const c of f.verify) {
    assert.ok(verifyDomain(a, c.domain, hex(c.data), hex(c.signature)), c.name)
    assert.ok(!verifyDomain(hex(f.keyB.spki), c.domain, hex(c.data), hex(c.signature)), c.name)
    assert.ok(!verifyDomain(a, c.domain + 'x', hex(c.data), hex(c.signature)), c.name)
  }
  for (const s of f.badSpki) assert.ok(!isDeviceKey(hex(s)))
  const k = generateDeviceKey()
  assert.ok(isDeviceKey(k.spki))
  assert.ok(verifyDomain(k.spki, 'd', hex('00ff'), signDomain(k.pkcs8, 'd', hex('00ff'))))
})

test('pairing vectors, QR codes and the grinding control', () => {
  const f = fixture('pairing')
  for (const c of f.cases) {
    const cb = pairCommitment(hex(c.nb), hex(c.spkiB), hex(c.spkiA))
    assert.equal(toHex(cb), c.commitment, c.name)
    assert.ok(pairVerifyReveal(cb, hex(c.nb), hex(c.spkiB), hex(c.spkiA)))
    assert.ok(!pairVerifyReveal(cb, hex(c.na), hex(c.spkiB), hex(c.spkiA)))
    assert.equal(pairSas(hex(c.na), hex(c.nb), hex(c.spkiA), hex(c.spkiB)), c.sas, c.name)
  }
  for (const q of f.qr.valid) {
    const p = parsePairQr(q.text)
    assert.ok(p, q.text)
    assert.equal(p.host, q.host)
    assert.equal(p.port, q.port)
    assert.equal(toHex(p.pin), q.pin)
    assert.equal(toHex(p.token), q.token)
    if (!q.parseOnly) assert.equal(buildPairQr(p), q.text)
  }
  for (const t of f.qr.invalid) assert.equal(parsePairQr(t), null, t)
})

test('authorization is deny by default', () => {
  for (const c of fixture('authz').cases) {
    const d = authorize(c.ctx as AuthzContext, c.action as NetAction, c.library)
    assert.deepEqual(d, c.expected, c.name)
  }
})

test('the Download on... URL policy', () => {
  const f = fixture('urlpolicy')
  for (const c of f.static) {
    const d = checkDownloadUrl(c.url)
    assert.equal(d.allow, c.allow, `${c.url} -> ${d.reason}`)
    if (!c.allow) assert.equal(d.reason, c.reason, c.url)
  }
  for (const [address, cls] of f.addresses) assert.equal(deniedAddressClass(address), cls, address)
  for (const c of f.resolved) assert.deepEqual([checkResolved(c.addresses).allow, checkResolved(c.addresses).reason], [c.allow, c.reason])
  for (const c of f.redirects) {
    const d = checkRedirect(c.current, c.location, c.resolved, c.hops)
    assert.equal(d.allow, c.allow, `${c.location} -> ${d.reason}`)
    if (c.allow) assert.equal(d.href, c.href)
    else assert.equal(d.reason, c.reason, c.location)
  }
})

test('a KFSEG v2 file from an independent writer decodes, and ours verifies in the other direction', () => {
  const f = fixture('segment')
  const r = decodeSegment(hex(f.file), f.library)
  assert.ok(r.ok)
  if (r.ok) {
    assert.equal(r.signer, f.signer)
    assert.equal(r.key, f.key)
    assert.deepEqual(r.ops, f.ops)
  }
  const key = generateDeviceKey()
  const actor = randomUUID()
  const op = makeOp(actor, 1, `1700000000000.000000.${actor}`, 'profile.set', {field: 'name', value: 'x'})
  const bytes = encodeSegment(f.library, actor, [op], key)
  assert.ok(Buffer.from(bytes).toString('utf8').startsWith('KFSEG 2\n'))
  const back = decodeSegment(bytes, f.library)
  assert.ok(back.ok && back.signer === deviceIdOf(key.spki))
  // node:crypto verifies what the core signed: the trailer's signature over everything before it
  const text = Buffer.from(bytes)
  const cut = text.lastIndexOf('KFSIG ')
  const trailer = JSON.parse(text.subarray(cut + 6).toString('utf8')) as {sig: string; key: string}
  assert.ok(verifyDomain(key.spki, DOMAIN_SEGMENT, new Uint8Array(text.subarray(0, cut)), new Uint8Array(Buffer.from(trailer.sig, 'base64'))))
  const v1 = decodeSegment(encodeSegment(f.library, actor, [op]), f.library)
  assert.ok(v1.ok && v1.signer === null)
})

test('membership through the log: genesis, grant, revocation by sequence', () => {
  const a = {actor: randomUUID(), key: generateDeviceKey(), seq: 0}
  const b = {actor: randomUUID(), key: generateDeviceKey(), seq: 0}
  let tick = 0
  const mk = (m: typeof a, kind: string, body: Record<string, never> | Record<string, unknown>) => {
    m.seq += 1
    return makeOp(m.actor, m.seq, `${String(1700000000000 + tick++).padStart(13, '0')}.000000.${m.actor}`, kind, body as never)
  }
  const sha = (s: string) => createHash('sha256').update(s).digest('hex')
  const idA = deviceIdOf(a.key.spki)
  const idB = deviceIdOf(b.key.spki)
  const log = SyncLog.create()
  assert.equal(log.admit(mk(a, 'profile.set', {field: 'h1', value: 1}), null).kind, 'admitted')
  const genesis = mk(a, 'member.genesis', {deviceKey: Buffer.from(a.key.spki).toString('base64'), actors: [{actor: a.actor, seq: 1, lastSegmentHash: sha('x')}]})
  assert.equal(log.admit(genesis, idA).kind, 'admitted')
  assert.equal(log.membership().active, true)
  assert.deepEqual(log.admit(mk(a, 'profile.set', {field: 'late', value: 1}), null), {kind: 'setAside', reason: 'unsigned'})
  assert.deepEqual(log.admit(mk(b, 'profile.set', {field: 'b1', value: 1}), idB), {kind: 'setAside', reason: 'unauthorized'})
  const grant = mk(a, 'member.grant', {actor: b.actor, deviceKey: Buffer.from(b.key.spki).toString('base64'), perms: ['write'], epoch: 1})
  assert.equal(log.admit(grant, idA).kind, 'admitted')
  assert.equal(log.admit(mk(b, 'profile.set', {field: 'b2', value: 1}), idB).kind, 'admitted')
  const revoke = mk(a, 'member.revoke', {actor: b.actor, cutoff: {seq: 1, segmentHash: ''}})
  assert.equal(log.admit(revoke, idA).kind, 'admitted')
  assert.deepEqual(log.admit(mk(b, 'profile.set', {field: 'b3', value: 1}), idB), {kind: 'setAside', reason: 'revoked'})
  const m = log.membership()
  assert.equal(m.members[b.actor].cutoff, 1)
  assert.deepEqual(Object.keys(m.frozen), [a.actor])
})

test('typed pairing codes: fixtures, forgiving input, pin bits', () => {
  const f = fixture('pairing').code
  for (const c of f.valid) {
    const pin = hex(c.pin)
    assert.equal(pairCodeOf(c.token, pin), c.text)
    const p = parsePairCode(c.text)
    assert.deepEqual(p, {token: c.token, pinBits: c.pinBits})
    assert.equal(pinMatches(pin, c.pinBits), true)
    assert.equal(pinMatches(pin, c.pinBits ^ 1), false)
  }
  for (const c of f.forgiving) assert.deepEqual(parsePairCode(c.text), {token: c.token, pinBits: c.pinBits}, c.text)
  for (const t of f.invalid) assert.equal(parsePairCode(t), null, t)
})
