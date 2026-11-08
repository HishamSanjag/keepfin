// The store's worker thread: owns the KeepfinStore (the SyncLog, segments, checkpoint parts, the watcher, the flush
// and checkpoint timers) so that no call into the core ever blocks the Electron main thread. The main thread talks to it
// through StoreClient (storeClient.ts): requests with ids, answered by `res` messages, plus pushed `event`s and `batch`es.
import {randomUUID} from 'node:crypto'
import {parentPort} from 'node:worker_threads'
import {projectCollection, projectEnv, projectItem} from './mapping.js'
import {loadProfiles, rotateActor} from './profiles.js'
import {readMeta, scanRows} from './mergeScan.js'
import {KeepfinStore, type StoreOptions} from './store.js'
import type {DeviceData} from './device.js'
import type {CopyCover} from './where.js'

export type WorkerStoreOptions = {
  root: string
  /** The records root below .keepfin/ (format 2 folders; see keepfinFolder.ts). */
  records?: string
  libraryId: string
  actor: string
  deviceDir: string
  outboxDir: string
  /** The database file (default: next to the outbox). */
  statePath?: string
  appVersion?: string
  deviceName?: string
  /** This device's key as Base64 (network spec section 1): segments are signed and own operations admitted under its id. */
  signer?: {pkcs8: string; spki: string}
  /** The serialisable store options (timers, sizes, the watcher). */
  tuning: Partial<Pick<StoreOptions, 'flushIdleMs' | 'flushMaxAgeMs' | 'flushOps' | 'compact' | 'compactDelayMs' | 'compactGapMs' | 'compactSoonMs' | 'leaseWaitMs' | 'checkpointEvery' | 'segmentMaxOps' | 'watch' | 'watchDebounceMs' | 'retryMs' | 'checkpointDelayMs'>>
  /** The caller wants to hear about a retired actor (an event) instead of the default profiles.json update. */
  customRotate: boolean
}

export type ProjectArgs = {replicaId: string; root: string; linked: Record<string, string>; batch?: number}

let store: KeepfinStore | undefined
const port = parentPort
if (!port) throw new Error('worker.ts must run as a worker thread')

const post = (message: unknown) => port.postMessage(message)

const lastOf = () => (store ? [...store.currentLast()] : [])

async function defaultRotate(deviceDir: string, oldActor: string, newActor: string) {
  const profiles = await loadProfiles(deviceDir).catch(() => undefined)
  const profile = profiles?.profiles.find((entry) => entry.actorId === oldActor)
  if (profile) await rotateActor(deviceDir, profile.id, newActor)
}

const handlers: Record<string, (args: any, id: number) => Promise<unknown> | unknown> = {
  async open(options: WorkerStoreOptions) {
    store = await KeepfinStore.open({
      root: options.root,
      records: options.records,
      libraryId: options.libraryId,
      actor: options.actor,
      deviceDir: options.deviceDir,
      outboxDir: options.outboxDir,
      statePath: options.statePath,
      newId: randomUUID,
      now: Date.now,
      appVersion: options.appVersion,
      deviceName: options.deviceName,
      signer: options.signer ? {pkcs8: new Uint8Array(Buffer.from(options.signer.pkcs8, 'base64')), spki: new Uint8Array(Buffer.from(options.signer.spki, 'base64'))} : undefined,
      onRotate: (oldActor, newActor) => (options.customRotate ? post({type: 'event', name: 'rotated', data: {oldActor, newActor}}) : defaultRotate(options.deviceDir, oldActor, newActor)),
      onProgress: (progress) => post({type: 'event', name: 'progress', data: progress}),
      onCheckpoint: (info) => post({type: 'event', name: 'checkpoint', data: info}),
      ...options.tuning,
    })
    store.onChange(() => post({type: 'event', name: 'change', data: {last: lastOf()}}))
    return {actorId: store.actorId, stats: store.stats, last: lastOf()}
  },

  async apply({drafts, external}) {
    await store!.apply(drafts, {external: external === true})
    return {last: lastOf(), pending: store!.pendingOps}
  },

  async flush() {
    await store!.flush()
    return {last: lastOf(), pending: store!.pendingOps}
  },

  /** A segment transfer starts: compaction removes no file until it ends (see KeepfinStore.beginTransfer). */
  async beginTransfer() {
    return {token: await store!.beginTransfer()}
  },

  endTransfer({token}: {token: string}) {
    store!.endTransfer(token)
    return {}
  },

  async compact() {
    const stats = await store!.compact()
    return {stats, last: lastOf(), pending: store!.pendingOps}
  },

  async rescan({force}: {force?: boolean}) {
    await store!.rescan({force})
    return {last: lastOf(), pending: store!.pendingOps}
  },

  membership() {
    return store!.membership()
  },

  async enableMembership(grant?: {actor: string; spkiBase64: string; perms: string[]}) {
    await store!.enableMembership(grant)
    return {last: lastOf(), pending: store!.pendingOps}
  },

  async grantMember({actor, spki, perms}: {actor: string; spki: string; perms: string[]}) {
    await store!.grantMember(actor, spki, perms)
    return {last: lastOf(), pending: store!.pendingOps}
  },

  async revokeMember({actor, cutoff}: {actor: string; cutoff: number}) {
    await store!.revokeMember(actor, cutoff)
    return {last: lastOf(), pending: store!.pendingOps}
  },

  contentItems({contentId}: {contentId: string}) {
    return store!.contentItems(contentId)
  },

  whereCounts({mainReplica, copies}: {mainReplica: string; copies: CopyCover[]}) {
    return store!.whereCounts(mainReplica, copies)
  },

  musicVideoBytes() {
    return store!.musicVideoBytes()
  },

  whereItem({itemId}: {itemId: string}) {
    return store!.whereItem(itemId)
  },

  status() {
    return {last: lastOf(), pending: store!.pendingOps, findings: store!.findings(), actorId: store!.actorId, revision: store!.revision}
  },

  observed({query}) {
    return store!.observed(query)
  },

  songs({playlists}: {playlists: string[]}) {
    const out: Record<string, unknown> = {}
    for (const id of playlists) out[id] = store!.songs(id)
    return out
  },

  viewsFor({touched}) {
    return store!.viewsFor(touched)
  },

  takeRemoteTouched() {
    return {touched: store!.takeRemoteTouched(), last: lastOf()}
  },

  /** Everything, as plain views (tests and small libraries). */
  views() {
    return store!.views()
  },

  memory() {
    const usage = process.memoryUsage()
    return {heapUsed: usage.heapUsed, external: usage.external, rss: usage.rss}
  },

  /**
   * The whole library as the app model, streamed in bounded batches (each small enough for the main thread to take in
   * well under 50 ms). Device-only data is laid over on the main thread, which owns it.
   */
  projectAll(args: ProjectArgs, id: number) {
    const batch = args.batch ?? 5000
    // Read straight from the database, one row at a time: the library is never held as views.
    const sql = store!.state
    const spaces = sql.containers('space')
    const profile = sql.profile()
    const device: DeviceData = {replicaId: args.replicaId, root: args.root, linked: args.linked, items: {}, collections: {}, art: new Set()}
    const env = projectEnv({spaces, profile}, device)
    const favourites = new Set(sql.setsNamed(['favourites']).favourites)
    const tagSets = sql.setsByPrefix('tags:')
    const field = `plays@${store!.actorId}`
    const own: Record<string, number> = {}
    const present = new Set<string>()
    let items: unknown[] = []
    for (const {item: view, placements} of sql.iterateViews()) {
      if (view.status === 'deleted') continue
      present.add(view.id)
      items.push(projectItem(view, placements, favourites.has(view.id), tagSets.get(`tags:${view.id}`), env))
      const mine = view.fields[field]
      if (typeof mine === 'number') own[view.id] = mine
      if (items.length >= batch) {
        post({type: 'batch', id, kind: 'items', data: items})
        items = []
      }
    }
    if (items.length) post({type: 'batch', id, kind: 'items', data: items})
    const seen = sql.setsByPrefix('seen:')
    const news = sql.setsByPrefix('new:')
    const removed = sql.setsByPrefix('removed:')
    let collections: unknown[] = []
    for (const row of sql.containers('playlist')) {
      const playlist = {id: row.id, present: row.present, fields: row.fields, songs: row.present ? sql.songs(row.id) : []}
      const collection = projectCollection(playlist, {seen: seen.get(`seen:${row.id}`) ?? [], news: news.get(`new:${row.id}`) ?? [], removed: removed.get(`removed:${row.id}`) ?? []}, (itemId) => present.has(itemId), env)
      if (collection) collections.push(collection)
      if (collections.length >= batch) {
        post({type: 'batch', id, kind: 'collections', data: collections})
        collections = []
      }
    }
    if (collections.length) post({type: 'batch', id, kind: 'collections', data: collections})
    store!.takeRemoteTouched() // a full projection includes everything reported so far
    return {spaces: env.spaces, routes: env.routes, spaceViews: spaces, profile, own, last: lastOf()}
  },

  /** Every present item as a row, in batches (the library merge's scan). The outbox is written out first. */
  async mergeScan({batch}: {batch?: number}, id: number) {
    await store!.flush()
    const size = batch ?? 1000
    let rows: unknown[] = []
    for (const row of scanRows(store!)) {
      rows.push(row)
      if (rows.length >= size) {
        post({type: 'batch', id, kind: 'rows', data: rows})
        rows = []
        await new Promise((resolve) => setImmediate(resolve))
      }
    }
    if (rows.length) post({type: 'batch', id, kind: 'rows', data: rows})
    return {last: lastOf()}
  },

  mergeMeta() {
    return readMeta(store!)
  },

  async close() {
    await store?.close()
    return {}
  },

  /** Tests: stop without writing anything, as a crash would. */
  abandon() {
    store?.abandon()
    return {}
  },

  crash() {
    process.exit(1)
  },
}

port.on('message', async (message: {id: number; op: string; args: unknown}) => {
  try {
    const handler = handlers[message.op]
    if (!handler) throw new Error(`Unknown store request: ${message.op}`)
    post({type: 'res', id: message.id, ok: true, value: await handler(message.args, message.id)})
  } catch (error) {
    post({type: 'res', id: message.id, ok: false, error: error instanceof Error ? error.message : String(error)})
  }
})
