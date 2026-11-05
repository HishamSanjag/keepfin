// Helpers for the merge tests: two app instances in one process (A holds the main library T, B holds its own S), each with its own
// device folder, Keepfin folder, key, store and network service on loopback, plus the pieces a merge needs from its host.
import {createHash, randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {CombineState} from '../../shared/combine.js'
import {loadIdentity, type DeviceIdentity} from '../net/identity.js'
import {NetService} from '../net/service.js'
import {netLibraryFor} from '../net/storeLibrary.js'
import type {NetLibrary} from '../net/types.js'
import {plainBox, until} from '../net/testkit.js'
import {readJson} from '../store/atomic.js'
import {createKeepfinFolder, folderJsonPath} from '../store/keepfinFolder.js'
import type {OpDraft} from '../store/mapping.js'
import {createFirstProfile, loadProfiles, pickColor, profileDir, setProfileLibrary, ensureProfileDir} from '../store/profiles.js'
import {KeepfinStore} from '../store/store.js'
import {StoreClient} from '../store/storeClient.js'
import {MergeEngine} from './engine.js'
import {netPortFor} from './netPort.js'
import {clientStore, directStore} from './stores.js'
import type {MergeDeps, MergeFences, MergeStore, OpenTOptions, ProfileInfo} from './types.js'
import {SeparateEngine} from './separate.js'

export const WIRE_MUSIC = '6b8e1c10-2d3a-4f55-8a01-000000000002'
export const uuidN = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
export const contentId = (bytes: Buffer | string) => `sha256:${createHash('sha256').update(bytes).digest('hex')}`

export class Crash extends Error {
  constructor(readonly point: string) {
    super(`crash at ${point}`)
    this.name = 'Crash'
  }
}

export interface MNode {
  name: string
  dir: string
  deviceDir: string
  root: string
  libraryId: string
  replicaId: string
  identity: DeviceIdentity
  clock: {t: number}
  stores: KeepfinStore[]
  /** The libraries this node's service serves right now. */
  libs: NetLibrary[]
  store: KeepfinStore
  service: NetService
  port: number
  /** Writes a file below the Keepfin folder and makes it an item of [store] (add, revise, place). */
  put(n: number, rel: string, bytes: Buffer | string, extra?: Record<string, unknown>): Promise<{id: string; contentId: string; file: string}>
  titles(store?: KeepfinStore): string[]
  close(): Promise<void>
}

export async function makeNode(base: string, name: string, libraryId: string, clock: {t: number}, extra: {port?: number} = {}): Promise<MNode> {
  const dir = path.join(base, name)
  const deviceDir = path.join(dir, 'AppData')
  const root = path.join(dir, 'Keepfin')
  const replicaId = randomUUID()
  await createKeepfinFolder(root, {libraryId, replicaId, now: new Date().toISOString(), hide: () => undefined})
  const identity = await loadIdentity(deviceDir, plainBox)
  const actor = randomUUID()
  const open = (libId: string, rootDir: string, records: string | undefined, act: string, outbox: string, statePath?: string) =>
    KeepfinStore.open({root: rootDir, records, libraryId: libId, actor: act, deviceDir, outboxDir: outbox, statePath, newId: randomUUID, now: () => (clock.t += 1), flushIdleMs: 20, watch: false, signer: {pkcs8: identity.pkcs8, spki: identity.spki}})
  const store = await open(libraryId, root, undefined, actor, path.join(deviceDir, 'outbox'))
  const libs: NetLibrary[] = [netLibraryFor(store, {libraryId, name: `${name}'s library`, root, watchDebounceMs: 30})]
  const service = await NetService.create({
    deviceDir,
    box: plainBox,
    libraries: () => libs,
    push: () => undefined,
    port: extra.port ?? 0,
    bind: '127.0.0.1',
    discovery: false,
    advertiseHost: () => '127.0.0.1',
    backoff: {minMs: 40, maxMs: 400},
    scan: false,
    interfaces: () => [{name: 'Loopback test adapter', address: '127.0.0.1', netmask: '255.255.255.0'}],
    tailscale: {},
  })
  await service.enable()
  const node: MNode = {
    name,
    dir,
    deviceDir,
    root,
    libraryId,
    replicaId,
    identity,
    clock,
    stores: [store],
    libs,
    store,
    service,
    get port() {
      return service.listeningPort!
    },
    async put(n, rel, bytes, more = {}) {
      const file = path.join(root, ...rel.split('/'))
      await fs.mkdir(path.dirname(file), {recursive: true})
      await fs.writeFile(file, bytes)
      const id = uuidN(n)
      const cid = contentId(bytes)
      const buf = Buffer.from(bytes)
      const drafts: OpDraft[] = [
        {kind: 'item.add', body: {item: id, fields: {media: 'audio', title: `Song ${n}`, space: WIRE_MUSIC, addedAt: 0, ...more}}},
        {kind: 'item.revise', body: {item: id, content: cid, size: buf.length, parents: []}},
        {kind: 'item.place', body: {item: id, place: replicaId, path: rel}},
      ]
      await store.apply(drafts)
      await store.flush()
      return {id, contentId: cid, file}
    },
    titles: (s = store) =>
      s
        .views()
        .items.filter((i) => i.status !== 'deleted')
        .map((i) => String(i.fields.title))
        .sort(),
    close: async () => {
      await service.close()
      for (const s of node.stores) await s.close().catch(() => undefined)
    },
  }
  return node
}

export type Fault = {armed: number; hits: string[]; crashed: boolean; /** The next batch applies only this many drafts and then crashes. */ partial?: number}

export interface MRig {
  dir: string
  clock: {t: number}
  a: MNode
  b: MNode
  tId: string
  sId: string
  profileId: string
  profileDirB: string
  engine: SeparateEngine
  deps: MergeDeps
  fault: Fault
  pushes: CombineState[]
  /** Reasons the fences refuse a merge right now (a copy runs, a review is open). */
  blockers: string[]
  /** Libraries this device already holds as profiles. */
  held: {libraryId: string; name: string}[]
  relaunches: number
  /** A fresh engine over the same device folder (what a restart makes). */
  restart(options?: {noFaults?: boolean; net?: boolean}): SeparateEngine
  /** From now on the live library of B is [store] (T after a combine): the engine writes through it and B's service serves it. */
  useActive(store: KeepfinStore, library: {libraryId: string; records: string}): void
  /** Opens the library the profile names, as the next start would. */
  openActive(): Promise<{store: KeepfinStore; libraryId: string; records: string}>
  cleanup(): Promise<void>
}

/** A and B paired, A holding T (shared with B), B holding its own S and a profile for it. Nothing is combined yet. */
export async function mergeRig(options: {pageSize?: number; files?: boolean; /** Open T's store in a worker thread, as the app does. */ worker?: boolean} = {}): Promise<MRig> {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-merge-'))
  const clock = {t: 1_700_000_000_000}
  const tId = randomUUID()
  const sId = randomUUID()
  const a = await makeNode(dir, 'A', tId, clock)
  const b = await makeNode(dir, 'B', sId, clock)
  // pair, then A shares T with B
  a.service.openPairing()
  const done = b.service.ask(`127.0.0.1:${a.port}`)
  await until(() => b.service.state().pairing?.sas !== undefined && a.service.state().pairing?.sas !== undefined, 'the pairing numbers')
  const pa = b.service.state().pairing!
  const pb = a.service.state().pairing!
  b.service.decide(pa.id, true)
  a.service.decide(pb.id, true)
  await done
  await until(() => !!b.service.peers.get(a.identity.deviceId) && !!a.service.peers.get(b.identity.deviceId), 'both devices to remember each other')
  await a.service.shareLibrary(b.identity.deviceId, tId)
  await until(() => !!b.service.offerFrom(a.identity.deviceId), 'the offer to reach B')

  const profileId = randomUUID()
  await ensureProfileDir(b.deviceDir, profileId)
  await createFirstProfile(b.deviceDir, {id: profileId, name: 'B library', color: pickColor(0), folder: b.root, libraryId: sId, actorId: b.store.actorId, now: new Date().toISOString()})
  const profileDirB = profileDir(b.deviceDir, profileId)

  const fault: Fault = {armed: -1, hits: [], crashed: false}
  const pushes: CombineState[] = []
  const rig = {relaunches: 0}
  const profile = (): ProfileInfo => ({profileId, folder: b.root, libraryId: sId, replicaId: b.replicaId, actorId: b.store.actorId, profileDir: profileDirB, name: 'B library'})
  const opened: MergeStore[] = []
  const openStore = async (o: OpenTOptions): Promise<MergeStore> => {
    if (options.worker) {
      // the app's binding: the store runs in its worker thread
      const client = await StoreClient.open({
        root: o.root,
        records: o.records,
        libraryId: o.libraryId,
        actor: o.actor,
        deviceDir: b.deviceDir,
        outboxDir: o.outboxDir,
        statePath: o.statePath,
        appVersion: 'test',
        signer: {pkcs8: Buffer.from(b.identity.pkcs8).toString('base64'), spki: Buffer.from(b.identity.spki).toString('base64')},
        tuning: {flushIdleMs: 20, watch: false},
        customRotate: true,
      })
      const base = clientStore(client, {closeIt: false})
      const merge: MergeStore = {
        ...base,
        apply: async (drafts) => {
          if (fault.partial !== undefined && drafts.length > fault.partial) {
            const first = fault.partial
            fault.partial = undefined
            if (first > 0) await client.apply(drafts.slice(0, first))
            fault.crashed = true
            throw new Crash(`partial batch after ${first} drafts`)
          }
          return client.apply(drafts)
        },
        get actorId() {
          return client.actorId
        },
        close: async () => {
          if (fault.crashed) await client.abandon()
          else await client.close()
        },
      }
      opened.push(merge)
      return merge
    }
    const store = await KeepfinStore.open({root: o.root, records: o.records, libraryId: o.libraryId, actor: o.actor, deviceDir: b.deviceDir, outboxDir: o.outboxDir, statePath: o.statePath, newId: randomUUID, now: () => (clock.t += 1), flushIdleMs: 20, watch: false, signer: {pkcs8: b.identity.pkcs8, spki: b.identity.spki}})
    const wrapped = directStore(store, {closeIt: false})
    const merge: MergeStore = {
      ...wrapped,
      apply: async (drafts) => {
        if (fault.partial !== undefined && drafts.length > fault.partial) {
          const first = fault.partial
          fault.partial = undefined
          if (first > 0) await store.apply(drafts.slice(0, first))
          fault.crashed = true
          throw new Crash(`partial batch after ${first} drafts`)
        }
        return store.apply(drafts)
      },
      get actorId() {
        return store.actorId
      },
      close: async () => {
        if (fault.crashed) store.abandon()
        else await store.close()
      },
    }
    opened.push(merge)
    return merge
  }
  const blockers: string[] = []
  const held: {libraryId: string; name: string}[] = []
  const fences: MergeFences = {enter: async () => undefined, leave: async () => undefined, blockers: async () => [...blockers]}
  let activeRef: MergeStore | undefined
  const make = (noFaults: boolean, withNet: boolean): MergeDeps => ({
    deviceDir: b.deviceDir,
    newId: randomUUID,
    now: () => clock.t,
    deviceName: 'B',
    profile: () => {
      const p = profile()
      return p
    },
    sStore: () => directStore(b.store, {closeIt: false}),
    activeStore: () => activeRef ?? directStore(b.store, {closeIt: false}),
    openStore,
    setProfileLibrary: (id, library) => setProfileLibrary(b.deviceDir, id, library).then(() => undefined),
    net: withNet ? netPortFor(() => b.service, () => held) : undefined,
    fences,
    relaunch: () => void rig.relaunches++,
    push: (state) => pushes.push(state as CombineState),
    pageSize: options.pageSize,
    faults: noFaults
      ? undefined
      : (point) => {
          fault.hits.push(point)
          if (fault.armed >= 0 && fault.hits.length - 1 === fault.armed) {
            fault.crashed = true
            throw new Crash(point)
          }
        },
  })
  const deps = make(false, true)
  const engine = new SeparateEngine(deps)
  const result: MRig = {
    dir,
    clock,
    a,
    b,
    tId,
    sId,
    profileId,
    profileDirB,
    engine,
    deps,
    fault,
    pushes,
    blockers,
    held,
    get relaunches() {
      return rig.relaunches
    },
    restart(o = {}) {
      fault.crashed = false
      fault.armed = -1
      return new SeparateEngine(make(o.noFaults ?? true, o.net ?? true))
    },
    useActive(store, library) {
      activeRef = directStore(store, {closeIt: false})
      b.libs.splice(0, b.libs.length, netLibraryFor(store, {libraryId: library.libraryId, name: 'B', root: b.root, records: library.records || undefined, watchDebounceMs: 30}))
    },
    async openActive() {
      const folder = await readJson<{libraryId: string; records?: string}>(folderJsonPath(b.root))
      const profiles = await loadProfiles(b.deviceDir)
      const current = profiles!.profiles.find((p) => p.id === profileId)!
      const store = await KeepfinStore.open({root: b.root, records: folder!.records, libraryId: current.libraryId, actor: current.actorId, deviceDir: b.deviceDir, outboxDir: path.join(profileDirB, 'outbox'), newId: randomUUID, now: () => (clock.t += 1), flushIdleMs: 20, watch: false, signer: {pkcs8: b.identity.pkcs8, spki: b.identity.spki}})
      b.stores.push(store)
      return {store, libraryId: current.libraryId, records: folder!.records ?? ''}
    },
    async cleanup() {
      await a.close().catch(() => undefined)
      await b.close().catch(() => undefined)
      await fs.rm(dir, {recursive: true, force: true}).catch(() => undefined)
    },
  }
  return result
}

/** Every file below [root] outside `.keepfin`, with its hash: what "media paths and bytes unchanged" compares. */
export async function mediaTree(root: string): Promise<Record<string, string>> {
  const out: Record<string, string> = {}
  const walk = async (dir: string) => {
    for (const e of await fs.readdir(dir, {withFileTypes: true})) {
      const p = path.join(dir, e.name)
      if (e.isDirectory()) {
        if (e.name === '.keepfin') continue
        await walk(p)
      } else out[path.relative(root, p).split(path.sep).join('/')] = createHash('sha256').update(await fs.readFile(p)).digest('hex')
    }
  }
  await walk(root)
  return out
}
