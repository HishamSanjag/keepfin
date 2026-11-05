// A cloud copy end to end, through the real copies host and its worker thread, the real runner and the real library, with the
// provider answering from a local fake (Microsoft Graph, Dropbox) or a real WebDAV server (webdav-server): preview writes
// nothing to the account; a backup is uploaded and an edit keeps the old bytes as an earlier version; a two-way copy brings a
// file another device put on the account into the library; a second device finds the replica; a refused sign-in is a Needs
// review item and changes nothing; the account's tokens never reach the worker.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash, randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import {v2 as dav} from 'webdav-server'
import type {CloudFoundReplica, CopiesState, CopyDraft, CopyPreview} from '../shared/copies.js'
import {CloudAccounts} from './cloud/accounts.js'
import {openRig} from './cloud/cloudPlace.js'
import type {CloudPlaceSpec} from './cloud/cloudPlace.js'
import {FakeDropbox} from './cloud/fakes/dropbox.js'
import {FakeGraph} from './cloud/fakes/graph.js'
import {configFrom} from './cloud/oauth.js'
import type {CloudAccount} from './cloud/http.js'
import {testBox} from './cloud/testkit.js'
import {CopiesHost} from './copiesHost.js'
import {createKeepfinFolder} from './store/keepfinFolder.js'
import {BUILTIN_SPACE_IDS} from './store/mapping.js'
import {StoreLibrary} from './store/persistence.js'

const sha = (text: string) => `sha256:${createHash('sha256').update(text).digest('hex')}`
const ACTOR = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

type Route = {origin: string; hosts: string[]}

async function world(options: {libraryId?: string; route: Route}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-cloud-app-'))
  const profile = path.join(dir, 'profile')
  const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: options.libraryId ?? randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
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
  const config = configFrom({KEEPFIN_MS_CLIENT_ID: 'ms', KEEPFIN_DROPBOX_APP_KEY: 'db', KEEPFIN_GOOGLE_CLIENT_ID: 'g'})
  const accounts = new CloudAccounts({dir: path.join(profile, 'cloud'), box: testBox(), config})
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
    cloud: {accounts, config, openExternal: async () => undefined, test: options.route},
  })
  await host.start()
  return {
    dir,
    folder,
    session,
    host,
    accounts,
    addItem,
    revise,
    async cleanup() {
      await host.close()
      await library.close()
      await fs.rm(dir, {recursive: true, force: true})
    },
  }
}

const draft = (mode: CopyDraft['mode'], accountId: string, folder: string): CopyDraft => ({
  path: '',
  cloud: {accountId, folder},
  name: 'Cloud copy',
  mode,
  holds: {all: true, spaces: [], playlists: [], favourites: false},
  schedule: {everyHours: null, auto: false},
})

async function settle(host: CopiesHost, placeId: string, until: (s: CopiesState) => boolean, ms = 60_000): Promise<CopiesState> {
  const end = Date.now() + ms
  let state = await host.state()
  while (Date.now() < end) {
    state = await host.state()
    const place = state.places.find((p) => p.id === placeId)
    if (place && place.status !== 'syncing' && until(state)) return state
    await new Promise((r) => setTimeout(r, 150))
  }
  throw new Error(`The copy did not settle: ${JSON.stringify(state.places.map((p) => [p.name, p.status, p.statusText, p.kept, p.waiting]))} accounts=${JSON.stringify(state.cloud?.accounts)} review=${JSON.stringify(state.review.map((r) => r.kind))}`)
}

/** The account as the worker's cousin: reads the replica straight from the provider, as another device would. */
function remote(accounts: CloudAccounts, accountId: string, spec: CloudPlaceSpec, route: Route) {
  const rewrite = (url: string) => {
    const u = new URL(url)
    return route.hosts.includes(u.host) ? `${route.origin}/__h/${u.host}${u.pathname}${u.search}` : url
  }
  return openRig(spec, {account: accounts.account(accountId, spec.provider) as CloudAccount, http: {rewrite, sleep: async () => undefined, random: () => 0.5}})
}

async function remoteFiles(rig: ReturnType<typeof remote>): Promise<Map<string, number>> {
  const out = new Map<string, number>()
  await rig.media.scan('', (l) => out.set(l.path, l.size))
  await rig.meta.scan('', (l) => out.set('.keepfin/' + l.path, l.size))
  return out
}

async function remoteText(rig: ReturnType<typeof remote>, rel: string): Promise<string> {
  const tmp = path.join(os.tmpdir(), `kf-remote-${randomUUID()}`)
  try {
    await rig.media.download(rel, tmp, '*')
    return await fs.readFile(tmp, 'utf8')
  } finally {
    await fs.rm(tmp, {force: true})
  }
}

async function freePort(): Promise<number> {
  return new Promise((resolve) => {
    const s = net.createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as net.AddressInfo).port
      s.close(() => resolve(port))
    })
  })
}

for (const provider of ['graph', 'dropbox'] as const) {
  test(`${provider}: preview writes nothing to the account; a backup uploads; an edit keeps the old bytes as an earlier version`, async () => {
    const fake = provider === 'graph' ? new FakeGraph() : new FakeDropbox()
    await fake.server.start()
    const route = {origin: fake.server.origin, hosts: fake.server.hosts}
    const w = await world({route})
    try {
      await w.addItem('one.mp3', 'ONE-v1')
      await w.addItem('two.mp3', 'TWO-v1')
      await w.host.refreshSnapshot()
      const account = await w.accounts.addOAuth(provider, {accessToken: 'T1', refreshToken: 'R1', expiresAtMs: null}, provider)
      const state0 = await w.host.state()
      assert.deepEqual(state0.cloud?.providers.filter((p) => p.available).map((p) => p.id).sort(), ['drive', 'dropbox', 'graph', 'webdav'])
      assert.equal(state0.cloud?.accounts.length, 1)

      const preview = (await w.host.handle('copiesPreview', {draft: draft('backup', account.id, 'My Music')})) as CopyPreview
      assert.equal(preview.status, 'ready', preview.reason ?? '')
      assert.equal(preview.copyFiles, 2)
      assert.equal(preview.additiveOnly, true)
      const spec: CloudPlaceSpec = {provider, accountId: account.id, rootPath: 'Keepfin/My Music'}
      const rig = remote(w.accounts, account.id, spec, route)
      assert.equal((await remoteFiles(rig)).size, 0, 'a preview writes nothing to the account')

      await w.host.handle('copiesAdd', {placeId: preview.placeId})
      const state = await settle(w.host, preview.placeId, (s) => s.places[0].kept === 2 && s.places[0].waiting === 0)
      const place = state.places[0]
      assert.equal(place.status, 'upToDate', place.statusText ?? '')
      assert.equal(place.cloud?.provider, provider)
      assert.equal(place.cloud?.folder, 'My Music')
      const files = await remoteFiles(rig)
      assert.equal(files.get('Music/one.mp3'), 6)
      assert.equal(files.get('Music/two.mp3'), 6)
      assert.ok(files.has('.keepfin/folder.json'), 'the replica marker is on the account')
      assert.equal(await remoteText(rig, 'Music/one.mp3'), 'ONE-v1')
      assert.equal(await rig.media.readGeneration(), 1, 'the generation is an append-only record')
      assert.equal(JSON.parse(await remoteText(rig, '.keepfin/folder.json')).libraryId, w.folder.libraryId)

      // an edit on the library: the account holds the new bytes, and the old bytes are an earlier version
      await w.revise('one.mp3', 'ONE-v2 is longer')
      await w.host.refreshSnapshot()
      const run = await w.host.syncPlace(preview.placeId, {})
      assert.equal(run?.status, 'done')
      assert.equal(run?.cloud?.state, 'ok')
      assert.equal(await remoteText(rig, 'Music/one.mp3'), 'ONE-v2 is longer')
      const after = await remoteFiles(rig)
      const versions = [...after.keys()].filter((p) => p.startsWith('.keepfin/versions/') && !p.endsWith('.jsonl'))
      assert.equal(versions.length, 1, `one earlier version on the account: ${[...after.keys()].join(', ')}`)
      assert.equal(await remoteText(rig, versions[0]!), 'ONE-v1')
      assert.equal((await settle(w.host, preview.placeId, (s) => s.places[0].waiting === 0)).places[0].versions?.files, 1)

      // the worker was given a header, never a token: nothing on disk of the profile holds a token in clear text
      const raw = await fs.readFile(path.join(w.dir, 'profile', 'cloud', 'accounts.json'), 'utf8')
      // as JSON string values: the encrypted base64 text can contain the letters T1 or R1 by chance, never a quoted "T1"
      assert.ok(!raw.includes('"T1"') && !raw.includes('"R1"'), 'no token is stored in clear text')
    } finally {
      await w.cleanup()
      await fake.server.stop()
    }
  })
}

test('graph: a file another device put on the account comes into the library of a two-way copy; a second device finds the replica', async () => {
  const fake = new FakeGraph()
  await fake.server.start()
  const route = {origin: fake.server.origin, hosts: fake.server.hosts}
  const libraryId = randomUUID()
  const a = await world({route, libraryId})
  const b = await world({route, libraryId})
  try {
    await a.addItem('song.mp3', 'SONG')
    await a.host.refreshSnapshot()
    const accountA = await a.accounts.addOAuth('graph', {accessToken: 'T', refreshToken: 'R', expiresAtMs: null}, 'OneDrive')
    const preview = (await a.host.handle('copiesPreview', {draft: draft('twoWay', accountA.id, 'Shared')})) as CopyPreview
    await a.host.handle('copiesAdd', {placeId: preview.placeId})
    await settle(a.host, preview.placeId, (s) => s.places[0].kept === 1)

    // another device (here: the provider itself) adds a file to the replica
    const spec: CloudPlaceSpec = {provider: 'graph', accountId: accountA.id, rootPath: 'Keepfin/Shared'}
    const rig = remote(a.accounts, accountA.id, spec, route)
    const extra = path.join(a.dir, 'extra.mp3')
    await fs.writeFile(extra, 'FROM-ANOTHER-DEVICE')
    await rig.media.uploadNew(extra, 'Music/phone.mp3', sha('FROM-ANOTHER-DEVICE'))
    const run = await a.host.syncPlace(preview.placeId, {})
    assert.equal(run?.status, 'done', run?.reason ?? '')
    assert.equal(await fs.readFile(path.join(a.folder.root, 'Music', 'phone.mp3'), 'utf8'), 'FROM-ANOTHER-DEVICE', 'the file came into the library')

    // device B signs in to the same account and finds the replica
    const accountB = await b.accounts.addOAuth('graph', {accessToken: 'T', refreshToken: 'R', expiresAtMs: null}, 'OneDrive')
    const found = (await b.host.handle('copiesCloudFind', {cloud: {accountId: accountB.id}})) as CloudFoundReplica[]
    assert.deepEqual(found.map((f) => f.folder), ['Shared'])
    assert.equal(found[0]!.placeId, undefined, 'device B has not got the copy registered: it is added')
    const preview2 = (await b.host.handle('copiesPreview', {draft: draft('twoWay', accountB.id, 'Shared')})) as CopyPreview
    assert.equal(preview2.status, 'ready', preview2.reason ?? '')
    await b.host.handle('copiesAdd', {placeId: preview2.placeId})
    await settle(b.host, preview2.placeId, (s) => s.places[0].status === 'upToDate')
    assert.equal(await fs.readFile(path.join(b.folder.root, 'Music', 'song.mp3'), 'utf8'), 'SONG', 'device B received the library through the shared place')
    assert.equal(await fs.readFile(path.join(b.folder.root, 'Music', 'phone.mp3'), 'utf8'), 'FROM-ANOTHER-DEVICE')
  } finally {
    await a.cleanup()
    await b.cleanup()
    await fake.server.stop()
  }
})

test('graph: a refused sign-in is a Needs review item with Sign in again, changes nothing, and sync resumes after it', async () => {
  const fake = new FakeGraph()
  await fake.server.start()
  const route = {origin: fake.server.origin, hosts: fake.server.hosts}
  const w = await world({route})
  try {
    await w.addItem('one.mp3', 'ONE')
    await w.host.refreshSnapshot()
    const account = await w.accounts.addOAuth('graph', {accessToken: 'T', refreshToken: undefined, expiresAtMs: null}, 'OneDrive')
    const preview = (await w.host.handle('copiesPreview', {draft: draft('backup', account.id, 'Lib')})) as CopyPreview
    await w.host.handle('copiesAdd', {placeId: preview.placeId})
    await settle(w.host, preview.placeId, (s) => s.places[0].kept === 1)
    await w.addItem('two.mp3', 'TWO')
    await w.host.refreshSnapshot()

    // the provider now refuses the token, and there is no refresh token to try
    fake.server.fail(() => true, {status: 401, body: {error: {code: 'InvalidAuthenticationToken', message: 'expired'}}}, 100000)
    const run = await w.host.syncPlace(preview.placeId, {})
    assert.equal(run?.cloud?.state, 'needsSignIn')
    const state = await settle(w.host, preview.placeId, (s) => s.review.some((r) => r.kind === 'signIn'))
    const item = state.review.find((r) => r.kind === 'signIn')!
    assert.deepEqual(item.actions, ['signIn'])
    assert.equal(state.cloud?.accounts[0]!.needsSignIn, true)
    assert.equal(state.places[0]!.cloud?.needsSignIn, true)
    assert.equal(state.places[0]!.connected, false)

    // signing in again (new tokens) lets the next sync go through; the file waiting then goes up
    fake.server.clearFaults()
    await w.accounts.replaceTokens(account.id, {accessToken: 'T2', refreshToken: undefined, expiresAtMs: null})
    await w.host.handle('copiesSync', {placeId: preview.placeId})
    const done = await settle(w.host, preview.placeId, (s) => s.places[0].kept === 2 && !s.review.some((r) => r.kind === 'signIn'))
    assert.equal(done.places[0]!.cloud?.needsSignIn, false)
  } finally {
    await w.cleanup()
    await fake.server.stop()
  }
})

test('webdav on a real server: connect with a probe, back up, edit, disconnect on this device keeps the account files', async () => {
  const port = await freePort()
  const users = new dav.SimpleUserManager()
  const user = users.addUser('keepfin', 'app-password', false)
  const rights = new dav.SimplePathPrivilegeManager()
  rights.setRights(user, '/', ['all'])
  const server = new dav.WebDAVServer({port, httpAuthentication: new dav.HTTPBasicAuthentication(users, 'Keepfin'), privilegeManager: rights, requireAuthentification: true})
  await server.startAsync(port)
  const w = await world({route: {origin: 'http://127.0.0.1:1', hosts: []}})
  try {
    await w.addItem('one.mp3', 'ONE-v1')
    await w.addItem('two.mp3', 'TWO-v1')
    await w.host.refreshSnapshot()
    const bad = await w.host.handle('copiesCloudWebdav', {cloud: {url: `http://127.0.0.1:${port}`, user: 'keepfin', password: 'wrong'}}).catch((e: Error) => e)
    assert.match((bad as Error).message, /did not accept/)
    assert.equal((await w.host.state()).cloud?.accounts.length, 0, 'a refused connection leaves no account behind')
    const insecure = await w.host.handle('copiesCloudWebdav', {cloud: {url: 'http://files.example.org/dav', user: 'u', password: 'p'}}).catch((e: Error) => e)
    assert.match((insecure as Error).message, /https/)

    const connected = (await w.host.handle('copiesCloudWebdav', {cloud: {url: `http://127.0.0.1:${port}`, user: 'keepfin', password: 'app-password'}})) as {account: {id: string; support?: string[]}}
    assert.ok(connected.account.support && connected.account.support.length >= 2, 'what the server supports is shown')
    const preview = (await w.host.handle('copiesPreview', {draft: draft('backup', connected.account.id, 'KeepfinCopy')})) as CopyPreview
    assert.equal(preview.status, 'ready', preview.reason ?? '')
    await w.host.handle('copiesAdd', {placeId: preview.placeId})
    const state = await settle(w.host, preview.placeId, (s) => s.places[0].kept === 2 && s.places[0].waiting === 0)
    assert.equal(state.places[0]!.status, 'upToDate', state.places[0]!.statusText ?? '')
    const spec: CloudPlaceSpec = {provider: 'webdav', accountId: connected.account.id, rootPath: 'KeepfinCopy', base: `http://127.0.0.1:${port}`}
    const rig = remote(w.accounts, connected.account.id, spec, {origin: '', hosts: []})
    assert.equal(await remoteText(rig, 'Music/one.mp3'), 'ONE-v1')

    await w.revise('one.mp3', 'ONE-v2 is longer')
    await w.host.refreshSnapshot()
    assert.equal((await w.host.syncPlace(preview.placeId, {}))?.status, 'done')
    assert.equal(await remoteText(rig, 'Music/one.mp3'), 'ONE-v2 is longer')
    const files = await remoteFiles(rig)
    assert.equal([...files.keys()].filter((p) => p.startsWith('.keepfin/versions/') && !p.endsWith('.jsonl')).length, 1)

    // Disconnect on this device: the local password is gone, the account's files stay
    const after = (await w.host.handle('copiesCloudDisconnect', {cloud: {accountId: connected.account.id}})) as CopiesState
    assert.equal(after.cloud?.accounts.length, 0)
    assert.equal(after.places[0]!.connected, false)
    assert.match(after.places[0]!.statusText ?? '', /not signed in/)
    assert.equal((await fs.readFile(path.join(w.dir, 'profile', 'cloud', 'accounts.json'), 'utf8')).includes('app-password'), false)
  } finally {
    await w.cleanup()
    await server.stopAsync().catch(() => undefined)
  }
})
