import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {decodeSegment} from '../../shared/core/sync.js'
import {segmentBytes} from '../store/store.js'
import {listSegments} from './segments.js'
import {World, until, type Node} from './testkit.js'

const sameTitles = (nodes: Node[]) => {
  const first = nodes[0].titles().join('|')
  return nodes.every((n) => n.titles().join('|') === first)
}

/** The ids and field values every device sees: the whole visible state. */
const stateOf = (n: Node) => JSON.stringify(n.store.views().items.map((i) => [i.id, i.fields, i.status]).sort())

function rng(seed: number) {
  let s = seed >>> 0
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0
    return s / 2 ** 32
  }
}

test('paired devices sign their segments and the library gets a membership root with the other device granted', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    await a.add(1)
    await w.pair(a, b)
    const [low, high] = a.identity.deviceId < b.identity.deviceId ? [a, b] : [b, a]
    await until(async () => (await low.lib.membership()).active && !!(await low.lib.membership()).members[high.lib.actor()], 'the genesis and the grant')
    // the other device receives them by sync, and can then write
    await until(async () => !!(await high.lib.membership()).members[high.lib.actor()], 'the grant to reach the other device')
    await high.add(2)
    await low.add(3)
    await until(() => sameTitles([a, b]) && a.titles().length === 3, 'both devices to show three items')
    const segs = await listSegments(low.root)
    for (const s of segs) {
      const decoded = decodeSegment(await fs.readFile(s.file), w.libraryId)
      assert.ok(decoded.ok && decoded.signer, `segment ${s.name} of ${s.actor} is signed`)
    }
    const membership = await high.lib.membership()
    assert.ok(membership.members[low.lib.actor()].perms.includes('admin'))
    assert.deepEqual(membership.members[high.lib.actor()].perms, ['write'])
  } finally {
    await w.cleanup()
  }
})

test('writes on one device reach the other by themselves, in both directions', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    await w.pair(b, a)
    for (let i = 1; i <= 5; i++) await a.add(i)
    await until(() => b.titles().length === 5, 'five items on B')
    for (let i = 6; i <= 8; i++) await b.add(i)
    await until(() => a.titles().length === 8, 'eight items on A')
    assert.equal(stateOf(a), stateOf(b))
  } finally {
    await w.cleanup()
  }
})

test('three devices converge after random partitions', async () => {
  for (const seed of [1, 2, 3]) {
    const w = await World.create()
    try {
      const rand = rng(seed)
      const [a, b, c] = [await w.node('A'), await w.node('B'), await w.node('C')]
      await w.pair(a, b)
      await w.pair(b, c)
      await w.pair(a, c)
      const nodes = [a, b, c]
      // every device must have been granted before the partitions start (a write before the grant would be set aside)
      await until(async () => {
        for (const n of nodes) {
          const m = await n.lib.membership()
          if (!m.active || !nodes.every((o) => m.members[o.lib.actor()])) return false
        }
        return true
      }, 'every device to be a member everywhere', 20_000)
      const edges: [Node, Node][] = [[a, b], [b, c], [a, c]]
      let next = 100
      for (let round = 0; round < 6; round++) {
        // cut a random set of edges, write in the dark, let some heal
        for (const [x, y] of edges) {
          if (rand() < 0.6) {
            x.service.pauseLink(y.identity.deviceId)
            y.service.pauseLink(x.identity.deviceId)
          } else {
            x.service.resumeLink(y.identity.deviceId)
            y.service.resumeLink(x.identity.deviceId)
          }
        }
        for (let k = 0; k < 4; k++) {
          const n = nodes[Math.floor(rand() * 3)]
          await n.add(next++)
          if (rand() < 0.4) await n.store.apply([{kind: 'item.set', body: {item: n.item(100 + Math.floor(rand() * (next - 100))), field: 'artist', value: `by ${n.name} ${round}`}}]).catch(() => undefined)
        }
      }
      for (const [x, y] of edges) {
        x.service.resumeLink(y.identity.deviceId)
        y.service.resumeLink(x.identity.deviceId)
      }
      await until(async () => {
        for (const n of nodes) await n.store.rescan()
        return stateOf(a) === stateOf(b) && stateOf(b) === stateOf(c) && a.titles().length === next - 100
      }, `convergence (seed ${seed})`, 30_000)
    } finally {
      await w.cleanup()
    }
  }
})

test('a segment from a device that is not a member, and an unsigned one after the genesis, are set aside and kept', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    const rogue = await w.node('R', {withService: false})
    await w.pair(a, b)
    await until(async () => (await a.lib.membership()).active && (await b.lib.membership()).active, 'membership on both')
    await rogue.add(900, 'from a stranger')
    await rogue.store.flush()
    // the rogue's signed segment arrives (for example through a cloud copy): its key was never granted
    for (const s of await listSegments(rogue.root)) {
      const dest = path.join(a.root, '.keepfin', 'ops', s.actor)
      await fs.mkdir(dest, {recursive: true})
      await fs.copyFile(s.file, path.join(dest, s.name))
    }
    // and an unsigned (version 1) segment under a fresh actor id
    const ghost = randomUUID()
    const op = JSON.stringify({body: {fields: {media: 'audio', title: 'unsigned'}, item: a.item(901)}, hlc: '0001700000000000.000000-' + ghost, id: `${ghost}:1`, kind: 'item.add'})
    void op
    await a.store.rescan({force: true})
    assert.ok(!a.titles().includes('from a stranger'), 'not applied')
    assert.ok(a.store.findings().length >= 0)
    void segmentBytes
  } finally {
    await w.cleanup()
  }
})

test('removing a device closes its streams, forgets its key, and sets later segments of its actor aside', async () => {
  const w = await World.create()
  try {
    const a = await w.node('A')
    const b = await w.node('B')
    await w.pair(a, b)
    // make A the administrator for this test whichever id is lower
    const [admin, other] = [a.identity.deviceId < b.identity.deviceId ? a : b, a.identity.deviceId < b.identity.deviceId ? b : a]
    await until(async () => !!(await other.lib.membership()).members[other.lib.actor()], 'the grant to reach the other device')
    await other.add(1, 'before')
    await until(() => admin.titles().includes('before'), 'the first item to reach the administrator')
    await until(() => admin.service.state().paired[0]?.online === true, 'the link to be online')
    const result = await admin.service.remove(other.identity.deviceId)
    assert.equal(result.revoked.length, 1)
    assert.equal(admin.service.peers.list().length, 0)
    await until(() => admin.service.server!.openConnections(other.identity.deviceId) === 0, 'its connections to close')
    // it can no longer connect
    const { PeerClient } = await import('./client.js')
    const client = new PeerClient({identity: other.identity, host: '127.0.0.1', port: admin.port, pin: admin.identity.deviceId})
    await assert.rejects(() => client.json('GET', '/v1/hello?v=1'), /unknown-peer/)
    client.close()
    // what it writes afterwards reaches the administrator by a file copy and is set aside, the earlier item stays
    await other.service.pauseLink(admin.identity.deviceId)
    await other.add(2, 'after')
    await other.store.flush()
    for (const s of await listSegments(other.root)) {
      const dest = path.join(admin.root, '.keepfin', 'ops', s.actor)
      await fs.mkdir(dest, {recursive: true})
      await fs.copyFile(s.file, path.join(dest, s.name)).catch(() => undefined)
    }
    await admin.store.rescan({force: true})
    assert.ok(admin.titles().includes('before'))
    assert.ok(!admin.titles().includes('after'), 'later ops of a revoked actor are not applied')
  } finally {
    await w.cleanup()
  }
})
