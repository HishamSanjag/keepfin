// The copies worker thread. One job per worker: a sync of one place (`run`, or `preview` as a dry run) or a read of what the
// copies hold (`snapshot`). Never the main thread (the executor's readers are synchronous) and never the store worker.
// The library-side actions of a run (importing a file only the copy has, a revision from the copy, a confirmed delete) are
// asked of the main thread, which writes them as ordinary library operations (copiesHost.ts).
import {DatabaseSync} from 'node:sqlite'
import {parentPort, workerData} from 'node:worker_threads'
import type {PlanAction} from '../shared/core/copies.js'
import type {CopyRow} from '../shared/copies.js'
import {coverage, readRows, type Coverage} from './copies/coverage.js'
import type {MainHost, Progress} from './copies/executor.js'
import {runPlace, type PlaceRunJob, type RunSummary} from './copies/placeRun.js'
import {runCloudPlace, type CloudPlaceSpec} from './cloud/cloudPlace.js'
import {PeerClient} from './net/client.js'
import {runPeerPlace, type PeerJobPayload} from './net/peerPlace.js'
import {CloudError} from './cloud/http.js'
import type {CloudAccount} from './cloud/http.js'
import {rewriteFor, type CloudTestRoute} from './cloud/route.js'
import {liveEntries} from './copies/versions.js'
import {conflictItems, readPlaces, type PlaceConfig} from './sqlstate/desired.js'
import type {SyncHead} from '../shared/core/sync.js'

/** What a cloud copy's run needs besides the place: where it is, and the first Authorization header (no refresh token ever reaches the worker). */
export type CloudJobPayload = {spec: CloudPlaceSpec; authorization: string; expiresAtMs: number | null; test?: CloudTestRoute}

export type WorkerJob =
  | ({job: 'run'; cloud?: CloudJobPayload; peer?: PeerJobPayload} & PlaceRunJob)
  | {
      job: 'snapshot'
      dbPath: string
      mainRoot: string
      mainReplica: string
      /** Items that need a decision, by place: their cells show as review. */
      reviewItems: Record<string, string[]>
      /** Where each connected copy is, for the count of earlier versions. */
      copyRoots: Record<string, string>
    }

export type Snapshot = {
  places: PlaceConfig[]
  rows: CopyRow[]
  spaceFolders: Record<string, string>
  coverage: Record<string, Coverage>
  versions: Record<string, {files: number; deletedFiles: number}>
  conflicts: {id: string; title: string; heads: SyncHead[]}[]
}

export type ToHost =
  | {t: 'progress'; p: Progress}
  | {t: 'host'; id: number; method: 'importNew' | 'reviseFromCopy' | 'restoreEditedDeleted' | 'deleteItem'; req: unknown}
  /** The account's Authorization header: renewed (and, after a 401, refreshed) by the main process, which holds the tokens. */
  | {t: 'cloudAuth'; id: number; force: boolean}
  | {t: 'done'; summary?: RunSummary; snapshot?: Snapshot}
  | {t: 'error'; message: string}

export type FromHost =
  | {t: 'hostRes'; id: number; ok: boolean; value?: unknown; error?: string}
  | {t: 'cloudAuthRes'; id: number; ok: boolean; header?: string; expiresAtMs?: number | null; error?: string}
  | {t: 'abort'}

const port = parentPort
if (!port) throw new Error('copiesWorker.ts must run as a worker thread')
const post = (message: ToHost) => port.postMessage(message)

const pending = new Map<number, {resolve: (v: unknown) => void; reject: (e: Error) => void}>()
const abort = new AbortController()
let nextId = 1
port.on('message', (m: FromHost) => {
  if (m.t === 'abort') abort.abort()
  else if (m.t === 'cloudAuthRes') {
    const wait = pending.get(m.id)
    pending.delete(m.id)
    if (wait) m.ok ? wait.resolve({header: m.header, expiresAtMs: m.expiresAtMs ?? null}) : wait.reject(new CloudError({kind: 'NeedsSignIn', status: 401, code: 'signed-out', message: m.error}, 'token'))
  } else if (m.t === 'hostRes') {
    const wait = pending.get(m.id)
    pending.delete(m.id)
    if (wait) m.ok ? wait.resolve(m.value) : wait.reject(new Error(m.error ?? 'The library could not record this change.'))
  }
})

const ask = (method: Extract<ToHost, {t: 'host'}>['method'], req: unknown) =>
  new Promise<any>((resolve, reject) => {
    const id = nextId++
    pending.set(id, {resolve, reject})
    post({t: 'host', id, method, req})
  })

const host: MainHost = {
  importNew: (req: {action: PlanAction; stagedPath: string; destPath: string}) => ask('importNew', req),
  reviseFromCopy: (req: {action: PlanAction; stagedPath: string}) => ask('reviseFromCopy', req),
  restoreEditedDeleted: (req: {action: PlanAction; stagedPath: string}) => ask('restoreEditedDeleted', req),
  deleteItem: (req: {action: PlanAction}) => ask('deleteItem', req).then(() => undefined),
}

/** The account as the worker sees it: the header it was given, renewed through the main process before it expires and after a 401. */
class WorkerAccount implements CloudAccount {
  private renewing: Promise<void> | null = null
  constructor(readonly id: string, readonly provider: CloudPlaceSpec['provider'], private header: string, private expiresAtMs: number | null) {}
  private renew(force: boolean): Promise<void> {
    this.renewing ??= new Promise<{header: string; expiresAtMs: number | null}>((resolve, reject) => {
      const id = nextId++
      pending.set(id, {resolve: resolve as (v: unknown) => void, reject})
      post({t: 'cloudAuth', id, force})
    })
      .then((r) => {
        this.header = r.header
        this.expiresAtMs = r.expiresAtMs
      })
      .finally(() => {
        this.renewing = null
      })
    return this.renewing
  }
  async authorization(): Promise<string> {
    if (this.expiresAtMs !== null && Date.now() >= this.expiresAtMs - 60_000) await this.renew(false)
    return this.header
  }
  async refresh(): Promise<boolean> {
    try {
      await this.renew(true)
      return true
    } catch {
      return false
    }
  }
}

async function snapshot(job: Extract<WorkerJob, {job: 'snapshot'}>): Promise<Snapshot> {
  const db = new DatabaseSync(job.dbPath, {readOnly: true})
  try {
    db.exec('PRAGMA query_only = 1')
    const places = readPlaces(db).filter((p) => !p.removed)
    const {rows, spaceFolders} = readRows(db)
    const out: Snapshot = {places, rows, spaceFolders, coverage: {}, versions: {}, conflicts: conflictItems(db)}
    for (const place of places) {
      out.coverage[place.id] = await coverage(db, place, job.mainRoot, job.mainReplica, rows, new Set(job.reviewItems[place.id] ?? []))
      const root = job.copyRoots[place.id]
      if (root && place.mode === 'backup') {
        let files = 0
        let deletedFiles = 0
        for (const e of liveEntries(root, 'versions')) {
          files++
          if (e.why === 'deleted' || e.why === 'removed') deletedFiles++
        }
        out.versions[place.id] = {files, deletedFiles}
      }
    }
    return out
  } finally {
    db.close()
  }
}

async function main() {
  const job = workerData as WorkerJob
  if (job.job === 'snapshot') {
    post({t: 'done', snapshot: await snapshot(job)})
  } else {
    const progress = (p: Progress) => post({t: 'progress', p})
    if (job.peer) {
      // a copy on a paired device: this worker connects to it (pinned to its key) and the device's own executor makes every change
      const {spec, connection} = job.peer
      const client = new PeerClient({identity: {keyPem: connection.keyPem, certPem: connection.certPem}, host: connection.host, port: connection.port, pin: connection.pin, connectTimeoutMs: 8000})
      try {
        const summary = await runPeerPlace(job, spec, {client}, host, progress, abort.signal)
        post({t: 'done', summary})
      } finally {
        client.close()
      }
    } else if (job.cloud) {
      const {spec, authorization, expiresAtMs, test} = job.cloud
      const account = new WorkerAccount(spec.accountId, spec.provider, authorization, expiresAtMs)
      const summary = await runCloudPlace(job, spec, {account, http: {rewrite: rewriteFor(test)}}, host, progress, abort.signal)
      post({t: 'done', summary})
    } else {
      const summary = await runPlace(job, host, progress, abort.signal)
      post({t: 'done', summary})
    }
  }
}

main().catch((error) => post({t: 'error', message: error instanceof Error ? error.message : String(error)}))
