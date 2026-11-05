// A copy on a paired device, end to end: the real copies host and its worker thread, the real runner, the real library, and a second
// app instance (a real network service on loopback) whose own executor writes its folder. Preview writes nothing there; a backup
// arrives; an edit keeps the old bytes on the peer's side; a device that is away is waiting, not an error; a device that does not allow
// copies is refused with the reason.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash, randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {CopiesState, CopyDraft} from '../shared/copies.js'
import {CopiesHost} from './copiesHost.js'
import {World, until} from './net/testkit.js'
import {createKeepfinFolder} from './store/keepfinFolder.js'
import {BUILTIN_SPACE_IDS} from './store/mapping.js'
import {StoreLibrary} from './store/persistence.js'

const sha = (text: string) => `sha256:${createHash('sha256').update(text).digest('hex')}`
const ACTOR = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

async function world() {
  const w = await World.create()
  const peer = await w.node('Peer', {leaseMs: 5000})
  const self = await w.node('Self')
  await w.pair(self, peer)
  await peer.service.setAccess(self.identity.deviceId, w.libraryId, ['metadata.read', 'metadata.write', 'inventory.read', 'media.read', 'copy.mutate'])
  await until(() => self.service.peerBridge(w.libraryId).devices()[0]?.allowed === true, 'the peer to allow a copy')

  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-peer-app-'))
  const profile = path.join(dir, 'profile')
  const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: w.libraryId, replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
  const library = new StoreLibrary({deviceDir: path.join(dir, 'AppData'), profileDir: profile, newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}})
  await library.create(folder, ACTOR)
  await library.open(folder, ACTOR)
  const session = library.persistence
  const items = new Map<string, string>()
  const addItem = async (name: string, text: string) => {
    const item = randomUUID()
    await fs.writeFile(path.join(folder.root, 'Music', name), text)
    await session.store.apply([
      {kind: 'item.add', body: {item, fields: {media: 'audio', space: BUILTIN_SPACE_IDS.music, title: name}}},
      {kind: 'item.place', body: {item, place: folder.replicaId, path: `Music/${name}`}},
      {kind: 'item.revise', body: {item, content: sha(text), size: Buffer.byteLength(text), parents: []}},
    ])
    items.set(name, item)
  }
  const revise = async (name: string, text: string) => {
    await fs.writeFile(path.join(folder.root, 'Music', name), text)
    const item = items.get(name)!
    const observed = await session.store.observed({items: [item]})
    await session.store.apply([{kind: 'item.revise', body: {item, content: sha(text), size: Buffer.byteLength(text), parents: observed.items[item].heads}}])
  }
  const host = new CopiesHost({
    profileDir: profile,
    mainRoot: folder.root,
    mainReplica: folder.replicaId,
    libraryId: folder.libraryId,
    dbPath: path.join(profile, 'state', 'library-state.v1.db'),
    store: () => session.store,
    newId: randomUUID,
    now: Date.now,
    device: 'test-device',
    push: () => undefined,
    pickFolder: async () => undefined,
    watch: false,
    drives: async () => [],
    peers: () => self.service.peerBridge(w.libraryId),
  })
  await host.start()
  return {
    w,
    profile,
    peer,
    self,
    host,
    folder,
    addItem,
    revise,
    async cleanup() {
      await host.close()
      await library.close()
      await fs.rm(dir, {recursive: true, force: true})
      await w.cleanup()
    },
  }
}

const draft = (deviceId: string, mode: CopyDraft['mode'] = 'backup'): CopyDraft => ({
  path: '',
  device: {deviceId},
  name: 'Peer copy',
  mode,
  holds: {all: true, spaces: [], playlists: [], favourites: false},
  schedule: {everyHours: null, auto: false},
})

async function settle(host: CopiesHost, placeId: string, ok: (s: CopiesState) => boolean, ms = 60_000): Promise<CopiesState> {
  const end = Date.now() + ms
  let state = await host.state()
  while (Date.now() < end) {
    state = await host.state()
    const place = state.places.find((p) => p.id === placeId)
    if (place && place.status !== 'syncing' && ok(state)) return state
    await new Promise((r) => setTimeout(r, 150))
  }
  throw new Error(`The copy did not settle: ${JSON.stringify(state.places.map((p) => [p.name, p.status, p.statusText, p.kept, p.waiting]))} review=${JSON.stringify(state.review.map((r) => r.kind))}`)
}

const read = (file: string) => fs.readFile(file, 'utf8')
const exists = (file: string) => fs.stat(file).then(() => true, () => false)

test('a backup on a paired device: preview writes nothing there, the files arrive, an edit keeps the old bytes, the device being away is a wait', async () => {
  const t = await world()
  try {
    await t.addItem('a.mp3', 'alpha')
    await t.addItem('b.mp3', 'beta')
    const state0 = await t.host.state()
    assert.deepEqual(state0.devices?.map((d) => [d.deviceId, d.allowed]), [[t.peer.identity.deviceId, true]], 'the wizard lists the device that allows copies')

    const preview = await t.host.preview(draft(t.peer.identity.deviceId))
    assert.equal(preview.status, 'ready', preview.reason ?? '')
    assert.equal(preview.copyFiles, 2)
    assert.equal(await exists(path.join(t.peer.root, 'Music', 'a.mp3')), false, 'a preview writes nothing to the peer')
    assert.deepEqual(await fs.readdir(path.join(t.peer.root, '.keepfin', 'ops').replace('ops', 'trash')), [], 'nothing in the peer trash either')

    const added = await t.host.add(preview.placeId)
    const place = added.places[0]
    assert.equal(place.device?.deviceName, t.peer.identity.name)
    const done = await settle(t.host, place.id, (s) => s.places[0].kept >= 2)
    assert.equal(done.places[0].status, 'upToDate', done.places[0].statusText ?? '')
    assert.equal(await read(path.join(t.peer.root, 'Music', 'a.mp3')), 'alpha')
    assert.equal(await read(path.join(t.peer.root, 'Music', 'b.mp3')), 'beta')

    // an edit in the library reaches the peer; the peer's executor replaces it under its own lock and keeps the old bytes
    await t.revise('a.mp3', 'alpha, second take')
    await t.host.handle('copiesSync', {placeId: place.id})
    await until(async () => (await read(path.join(t.peer.root, 'Music', 'a.mp3')).catch(() => '')) === 'alpha, second take', 'the edit to reach the peer', 30_000)
    const trash = path.join(t.peer.root, '.keepfin', 'trash', 'net')
    const kept = await Promise.all((await fs.readdir(trash)).map((n) => read(path.join(trash, n))))
    assert.ok(kept.includes('alpha'), 'the displaced bytes are kept on the peer')
    await settle(t.host, place.id, (s) => s.places[0].status === 'upToDate')

    // the device goes away: the copy is waiting, not broken
    await t.peer.service.disable()
    await until(() => !t.self.service.peerBridge(t.w.libraryId).online(t.peer.identity.deviceId), 'the device to be seen as away')
    await t.host.handle('copiesSync', {placeId: place.id})
    const away = await settle(t.host, place.id, (s) => s.places[0].status === 'disconnected', 30_000)
    assert.match(away.places[0].statusText ?? '', /not connected right now/)
    assert.equal(away.places[0].device?.online, false)
    assert.equal(await read(path.join(t.peer.root, 'Music', 'b.mp3')), 'beta', 'nothing changed while it was away')
  } finally {
    await t.cleanup()
  }
})

test('a device that does not allow copies is refused with the reason, and nothing is made', async () => {
  const t = await world()
  try {
    await t.addItem('a.mp3', 'alpha')
    await t.peer.service.setAccess(t.self.identity.deviceId, t.w.libraryId, ['metadata.read', 'metadata.write'])
    await until(() => t.self.service.peerBridge(t.w.libraryId).devices()[0]?.allowed === false, 'the permission to be withdrawn')
    const state = await t.host.state()
    assert.equal(state.devices?.[0].allowed, false)
    assert.match(state.devices?.[0].why ?? '', /Keep a copy on this device/)
    const preview = await t.host.preview(draft(t.peer.identity.deviceId))
    assert.equal(preview.status, 'refused')
    assert.match(preview.reason ?? '', /Keep a copy on this device/)
    assert.equal((await t.host.state()).places.length, 0)
    assert.equal(await exists(path.join(t.peer.root, 'Music', 'a.mp3')), false)
  } finally {
    await t.cleanup()
  }
})
