// "Where your files are" for the screens (whereState.ts, whereHost.ts): which place is which, what each holds and its state, the files only
// one place holds, the per-file line, and the host's caching and pushes. The counting itself is tested in store/where.test.ts.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash, randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {createKeepfinFolder} from './store/keepfinFolder.js'
import {StoreLibrary} from './store/persistence.js'
import type {SyncMembership} from '../shared/core/sync.js'
import type {CopyPlaceView, CopyRow} from '../shared/copies.js'
import type {DevicesState, PairedDevice} from '../shared/devices.js'
import type {WhereState} from '../shared/where.js'
import type {WhereCounts, WhereItemFacts} from './store/where.js'
import {buildWhere, completeCovers, isComplete, itemPlaces, type WhereCopy} from './whereState.js'
import {WhereHost} from './whereHost.js'

const MAIN = 'replica-main'
const PHONE = 'replica-phone'
const NOW = 1_800_000_000_000

const rows: CopyRow[] = [
  {key: 'space:music', kind: 'space', id: 'music', name: 'Music'},
  {key: 'space:videos', kind: 'space', id: 'videos', name: 'Videos'},
  {key: 'playlist:road', kind: 'playlist', id: 'road', name: 'Road trip'},
  {key: 'favourites', kind: 'favourites', id: 'favourites', name: 'Favourites'},
]

const phone = (patch: Partial<PairedDevice> = {}): PairedDevice => ({
  deviceId: 'dev-phone',
  fingerprint: '',
  name: 'Pixel 9',
  platform: 'android',
  pairedAt: NOW,
  lastSeen: NOW - 5 * 60_000,
  online: true,
  libraries: [{libraryId: 'lib', name: 'Mine', actions: ['metadata.read']}],
  unreachableSince: null,
  address: null,
  route: 'lan',
  jobs: {allowed: false, spaces: []},
  shareable: [],
  ...patch,
})

const membership = (actor: string, device: string): SyncMembership => ({active: true, frozen: {}, members: {[actor]: {cutoff: null, epoch: 1, perms: [], keys: [{device, key: 'k', until: null}]}}})

const copy = (patch: Partial<CopyPlaceView> & {id: string; name: string}): CopyPlaceView => ({
  path: 'E:\\',
  mode: 'backup',
  holds: {all: true, spaces: [], playlists: [], favourites: false},
  schedule: {everyHours: null, auto: true},
  connected: true,
  status: 'upToDate',
  lastVerifiedAt: NOW - 3 * 3600_000,
  kept: 100,
  waiting: 0,
  review: 0,
  previewPending: false,
  cells: {},
  ...patch,
})

const counts = (patch: Partial<WhereCounts> = {}): WhereCounts => ({
  items: 100,
  replicas: [
    {replica: MAIN, actors: ['actor-main'], items: 100, bytes: 5_000_000},
    {replica: PHONE, actors: ['actor-phone'], items: 40, bytes: 2_000_000},
  ],
  solos: [],
  copies: {},
  ...patch,
})

const base = (extra: Partial<Parameters<typeof buildWhere>[0]> = {}) => ({
  thisName: 'Desk PC',
  mainReplica: MAIN,
  counts: counts(),
  copies: [] as WhereCopy[],
  rows,
  paired: [phone()],
  membership: membership('actor-phone', 'dev-phone'),
  ...extra,
})

test('this device is first and the phone is found by the key that signed its placements', () => {
  const state = buildWhere(base())
  assert.equal(state.available, true)
  assert.deepEqual(state.places.map((p) => [p.id, p.kind, p.name]), [['this', 'this', 'Desk PC'], ['dev-phone', 'phone', 'Pixel 9']])
  const [here, away] = state.places
  assert.equal(here.thisDevice, true)
  assert.deepEqual(here.holds, {all: true, spaces: [], playlists: [], favourites: false, files: {held: 100, of: 100}})
  assert.deepEqual(away.holds.files, {held: 40, of: 100})
  assert.equal(away.holds.all, false)
  assert.equal(away.bytes, 2_000_000)
  assert.deepEqual(away.link, {to: 'device', id: 'dev-phone'})
  assert.equal(here.link, undefined)
})

test('a device that is away is offline with when it was last seen; an unsigned library still finds a lone device', () => {
  const away = buildWhere(base({paired: [phone({online: false, lastSeen: NOW - 86_400_000})], membership: null}))
  const place = away.places.find((p) => p.id === 'dev-phone')!
  assert.deepEqual(place.status, {state: 'offline', at: NOW - 86_400_000})
  // without a signed library the one replica nobody explained and the one device that shares the library are the same device
  assert.equal(place.name, 'Pixel 9')
  // two devices and no way to tell which is which: neither is guessed
  const two = buildWhere(base({paired: [phone(), phone({deviceId: 'dev-tab', name: 'Tablet'})], membership: null}))
  assert.deepEqual(two.places.map((p) => p.name), ['Desk PC', 'Another device'])
  assert.equal(two.places[1].link, undefined)
})

test('an online device is up to date, with the time of its last sync when it has one', () => {
  const state = buildWhere(base({paired: [phone({lastSync: {at: NOW - 120_000, pulled: 1, pushed: 0}})]}))
  assert.deepEqual(state.places[1].status, {state: 'upToDate', at: NOW - 120_000})
})

test('copies: holds are named, the states are plain, a drive that is away says so, and a copy that has not finished counts for nothing yet', () => {
  const everything = copy({id: 'p-backup', name: 'Backup drive (E:)'})
  const syncing = copy({id: 'p-laptop', name: 'Work laptop', holds: {all: false, spaces: ['music'], playlists: ['road'], favourites: true}, status: 'syncing', waiting: 170, kept: 40, progress: {doneActions: 40, totalActions: 210, doneBytes: 1, totalBytes: 2, current: null}})
  const away = copy({id: 'p-stick', name: 'Car USB stick (F:)', holds: {all: false, spaces: [], playlists: ['road'], favourites: false}, connected: false, status: 'disconnected', lastVerifiedAt: NOW - 9 * 86_400_000})
  const cloud = copy({id: 'p-cloud', name: 'Cloud backup', status: 'review', review: 2, cloud: {provider: 'drive', providerName: 'Google Drive', accountId: 'a', accountLabel: 'me', folder: 'Music', needsSignIn: false, signedInHere: true}})
  const copies: WhereCopy[] = [everything, syncing, away, cloud].map((view) => ({view, replica: `replica-${view.id}`}))
  const state = buildWhere(base({copies, counts: counts({copies: {'p-backup': {items: 100, bytes: 5_000_000}, 'p-stick': {items: 12, bytes: 300}}})}))
  const by = Object.fromEntries(state.places.map((p) => [p.id, p]))

  assert.deepEqual(by['p-backup'].status, {state: 'upToDate', at: NOW - 3 * 3600_000})
  assert.equal(by['p-backup'].kind, 'drive')
  assert.equal(by['p-backup'].holds.all, true)
  assert.deepEqual(by['p-backup'].link, {to: 'copy', id: 'p-backup'})
  assert.equal(by['p-backup'].bytes, 5_000_000)

  assert.deepEqual(by['p-laptop'].status, {state: 'copying', done: 40, total: 210})
  assert.deepEqual(by['p-laptop'].holds, {all: false, spaces: ['Music'], playlists: ['Road trip'], favourites: true})
  assert.equal(by['p-laptop'].bytes, 0)
  assert.equal(by['p-laptop'].items, 40)

  assert.deepEqual(by['p-stick'].status, {state: 'offline', at: NOW - 9 * 86_400_000, unplugged: true})
  assert.deepEqual(by['p-stick'].holds.playlists, ['Road trip'])
  assert.equal(by['p-stick'].bytes, 300) // complete: it counts, though the drive is away

  assert.equal(by['p-cloud'].kind, 'cloud')
  assert.deepEqual(by['p-cloud'].status, {state: 'needsDecision'})
  // order: this device, paired devices by name, then copies by name
  assert.deepEqual(state.places.map((p) => p.id), ['this', 'dev-phone', 'p-backup', 'p-stick', 'p-cloud', 'p-laptop'])
})

test('which copies are complete: verified, nothing waiting, nothing to review, not syncing, not a first plan waiting', () => {
  const ok = copy({id: 'a', name: 'A'})
  assert.equal(isComplete(ok), true)
  assert.equal(isComplete(copy({id: 'b', name: 'B', waiting: 1, status: 'waiting'})), false)
  assert.equal(isComplete(copy({id: 'c', name: 'C', status: 'syncing'})), false)
  assert.equal(isComplete(copy({id: 'd', name: 'D', lastVerifiedAt: undefined, status: 'new'})), false)
  assert.equal(isComplete(copy({id: 'e', name: 'E', review: 1, status: 'review'})), false)
  assert.equal(isComplete(copy({id: 'f', name: 'F', previewPending: true, status: 'preview'})), false)
  assert.equal(isComplete(copy({id: 'g', name: 'G', connected: false, status: 'disconnected'})), true) // away, but everything was verified
  const covers = completeCovers([ok, copy({id: 'b', name: 'B', waiting: 1, status: 'waiting'})].map((view) => ({view, replica: null})))
  assert.deepEqual(covers, [{id: 'a', holds: {all: true, spaces: [], playlists: [], favourites: false}}])
})

test('singles: counted per place and kind, the space most of them are in is offered for Keep a copy', () => {
  const state = buildWhere(
    base({
      counts: counts({
        solos: [
          {replica: MAIN, media: 'video', space: 'videos', count: 9},
          {replica: MAIN, media: 'video', space: 'music', count: 3},
          {replica: MAIN, media: 'audio', space: 'music', count: 5},
          {replica: MAIN, media: 'image', space: 'pics', count: 2},
          {replica: MAIN, media: 'other', space: 'pics', count: 1},
          {replica: PHONE, media: 'audio', space: 'music', count: 3},
        ],
      }),
    }),
  )
  assert.deepEqual(state.singles, [
    {kind: 'songs', count: 5, placeId: 'this', placeName: 'Desk PC', spaceId: 'music'},
    {kind: 'songs', count: 3, placeId: 'dev-phone', placeName: 'Pixel 9', spaceId: 'music'},
    {kind: 'videos', count: 12, placeId: 'this', placeName: 'Desk PC', spaceId: 'videos'},
    {kind: 'other', count: 3, placeId: 'this', placeName: 'Desk PC'}, // 'pics' is not a space a copy can hold
  ])
})

test('the per-file line: this computer first, a phone that is away marked, a complete copy that selects the file counted', () => {
  const facts: WhereItemFacts = {replicas: [{replica: PHONE, actor: 'actor-phone'}, {replica: MAIN, actor: 'actor-main'}], space: 'music', favourite: false, playlists: []}
  const drive = copy({id: 'p-backup', name: 'Backup drive (E:)', holds: {all: false, spaces: ['music'], playlists: [], favourites: false}, connected: false, status: 'disconnected'})
  const videos = copy({id: 'p-videos', name: 'Videos drive', holds: {all: false, spaces: ['videos'], playlists: [], favourites: false}})
  const waiting = copy({id: 'p-w', name: 'Not finished', status: 'waiting', waiting: 3})
  const view = itemPlaces({mainReplica: MAIN, thisName: 'Desk PC', paired: [phone({online: false})], membership: membership('actor-phone', 'dev-phone'), copies: [drive, videos, waiting].map((v) => ({view: v, replica: null})), facts})
  assert.deepEqual(view.places, [
    {name: 'Desk PC', thisDevice: true, offline: false},
    {name: 'Pixel 9', deviceId: 'dev-phone', offline: true},
    {name: 'Backup drive (E:)', offline: true},
  ])
  // only this computer has it
  const only = itemPlaces({mainReplica: MAIN, thisName: 'Desk PC', paired: [], membership: null, copies: [], facts: {replicas: [{replica: MAIN, actor: 'actor-main'}], space: '', favourite: false, playlists: []}})
  assert.deepEqual(only.places, [{name: 'Desk PC', thisDevice: true, offline: false}])
})

// ---------------------------------------------------------------------------------------------------------------------------------
// The host: counted once, rebuilt cheaply, pushed only while watched

function fakeHost() {
  const calls = {counts: 0, item: 0}
  const pushes: WhereState[] = []
  let copiesNow: WhereCopy[] = [{view: copy({id: 'p-backup', name: 'Backup drive (E:)'}), replica: 'r'}]
  let clock = NOW
  const store = {
    async whereCounts(_main: string, covers: unknown[]) {
      calls.counts++
      return counts({copies: covers.length ? {'p-backup': {items: 100, bytes: 5_000_000}} : {}})
    },
    async whereItem() {
      calls.item++
      return {replicas: [{replica: MAIN, actor: 'actor-main'}], space: 'music', favourite: false, playlists: []} satisfies WhereItemFacts
    },
    async membership() {
      return membership('actor-phone', 'dev-phone')
    },
  }
  const host = new WhereHost({
    store: () => store as never,
    mainReplica: MAIN,
    copies: () => ({copies: copiesNow, rows}),
    devices: () => ({thisDevice: {name: 'Desk PC', fingerprint: '', platform: 'windows'}, paired: [phone()]}) as unknown as DevicesState,
    push: (event) => pushes.push(event.state),
    thisName: () => 'host',
    now: () => clock,
  })
  return {host, calls, pushes, setCopies: (list: WhereCopy[]) => (copiesNow = list), advance: (ms: number) => (clock += ms)}
}

test('the host counts once however often it is asked, and again after a library change or when a copy becomes complete', async () => {
  const h = fakeHost()
  const [a, b] = await Promise.all([h.host.state(), h.host.state()])
  assert.deepEqual(a, b)
  await h.host.state()
  assert.equal(h.calls.counts, 1)
  assert.equal(a.places.find((p) => p.id === 'p-backup')?.bytes, 5_000_000)

  h.host.noteLibraryChanged()
  await h.host.state()
  assert.equal(h.calls.counts, 2)

  // a copy that starts to sync changes the covers (it is no longer complete): counted again, the copy now counts for nothing
  h.setCopies([{view: copy({id: 'p-backup', name: 'Backup drive (E:)', status: 'syncing', waiting: 10}), replica: 'r'}])
  const syncing = await h.host.state()
  assert.equal(h.calls.counts, 3)
  assert.equal(syncing.places.find((p) => p.id === 'p-backup')?.bytes, 0)
  h.host.close()
})

test('changes of a copy or a device rebuild the answer from the counts already held, and pushes only go out while the screen is watching', async () => {
  const h = fakeHost()
  // nobody asked yet: nothing is pushed
  h.host.noteOthersChanged()
  await new Promise((resolve) => setTimeout(resolve, 600))
  assert.equal(h.pushes.length, 0)

  await h.host.state() // the screen looks
  h.setCopies([{view: copy({id: 'p-backup', name: 'Backup drive (E:)', lastVerifiedAt: NOW - 60_000}), replica: 'r'}])
  h.host.noteOthersChanged()
  await new Promise((resolve) => setTimeout(resolve, 1100))
  assert.equal(h.calls.counts, 1) // still the first count
  assert.equal(h.pushes.length, 1)
  assert.deepEqual(h.pushes[0].places.find((p) => p.id === 'p-backup')?.status, {state: 'upToDate', at: NOW - 60_000})

  // the same answer is not pushed twice
  h.host.noteOthersChanged()
  await new Promise((resolve) => setTimeout(resolve, 900))
  assert.equal(h.pushes.length, 1)

  // ten minutes without a look: the host stops pushing
  h.advance(11 * 60_000)
  h.setCopies([])
  h.host.noteOthersChanged()
  await new Promise((resolve) => setTimeout(resolve, 600))
  assert.equal(h.pushes.length, 1)
  h.host.close()
})

test('over a real library: this computer and a phone that wrote placements, the singles, and the per-file line', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-where-host-'))
  const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
  const library = new StoreLibrary({deviceDir: path.join(dir, 'AppData'), profileDir: path.join(dir, 'profile'), newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}})
  const actor = randomUUID()
  await library.create(folder, actor)
  await library.open(folder, actor)
  const store = library.persistence.store
  try {
    const sha = (text: string) => `sha256:${createHash('sha256').update(text).digest('hex')}`
    const add = async (media: string, space: string, size: number, where: Record<string, string>) => {
      const item = randomUUID()
      await store.apply([
        {kind: 'item.add', body: {item, fields: {media, space, title: 'x'}}},
        ...Object.entries(where).map(([place, p]) => ({kind: 'item.place', body: {item, place, path: p}})),
        {kind: 'item.revise', body: {item, content: sha(item), size, parents: []}},
      ])
      return item
    }
    const both = await add('audio', 'music', 1000, {[folder.replicaId]: 'Music/a.mp3', [randomUUID()]: 'Music/a.mp3'})
    await add('video', 'videos', 5000, {[folder.replicaId]: 'Videos/b.mp4'})
    await add('video', 'videos', 7000, {[folder.replicaId]: 'Videos/c.mp4'})
    await store.flush()
    const host = new WhereHost({
      store: () => store,
      mainReplica: folder.replicaId,
      devices: () => ({thisDevice: {name: 'Desk PC', fingerprint: '', platform: 'windows'}, paired: [phone()]}) as unknown as DevicesState,
      push: () => undefined,
      thisName: () => 'host',
    })
    const state = await host.state()
    assert.deepEqual(state.places.map((p) => [p.name, p.items, p.bytes]), [['Desk PC', 3, 13_000], ['Pixel 9', 1, 1000]])
    assert.deepEqual(state.singles, [{kind: 'videos', count: 2, placeId: 'this', placeName: 'Desk PC'}])
    const line = await host.item(both)
    assert.deepEqual(line.places.map((p) => p.name), ['Desk PC', 'Pixel 9'])
    host.close()
  } finally {
    await library.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('the per-file line comes from the facts of that item', async () => {
  const h = fakeHost()
  const view = await h.host.item('some-item')
  assert.deepEqual(view.places, [{name: 'Desk PC', thisDevice: true, offline: false}, {name: 'Backup drive (E:)', offline: false}])
  await assert.rejects(() => h.host.item(''), /Invalid request/)
  h.host.close()
})
