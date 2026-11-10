// The state the window sees: the copies, their status and the snapshot of what each copy holds.
import {retryText} from './retry.js'
import fs from 'node:fs/promises'
import path from 'node:path'
import {Worker} from 'node:worker_threads'
import {problemShort} from '../../shared/core/copies.js'
import type {CopiesState, CopyPlaceView, CopyReviewItem, CopyStatus, CloudProviderId} from '../../shared/copies.js'
import {placeMusicVideos, type PlaceConfig} from '../sqlstate/desired.js'
import {readRelationship} from '../copies/generation.js'
import type {RunSummary} from '../copies/placeRun.js'
import type {Snapshot, ToHost, WorkerJob} from '../copiesWorker.js'
import type {AccountView} from '../cloud/accounts.js'
import type {CloudPlaceSpec} from '../cloud/cloudPlace.js'
import {readJson} from '../store/atomic.js'
import type {HostCore} from './core.js'
import {itemOf, problemOf} from './helpers.js'

const REVIEW_LIMIT = 200

export class CopiesView {
  private snapBusy?: Promise<void>
  private snapAgain = false

  constructor(private readonly core: HostCore) {}

  build(): CopiesState {
    const places = this.core.snap().places.filter((p) => !p.removed).map((p) => this.view(p))
    const review: CopyReviewItem[] = []
    for (const p of this.core.snap().places) if (!p.removed) review.push(...this.core.runtime(p.id).review)
    review.push(...this.core.conflictReview())
    review.unshift(...this.core.signInReview())
    return {
      available: true,
      places,
      rows: this.core.snap().rows,
      review: review.slice(0, REVIEW_LIMIT),
      reviewMore: Math.max(0, review.length - REVIEW_LIMIT),
      syncing: [...this.core.rt().values()].some((r) => r.worker),
      ...(this.core.report() ? {report: this.core.report()} : {}),
      ...(this.core.found().length ? {found: this.core.found()} : {}),
      ...(this.core.cloud ? {cloud: {providers: this.core.cloud.providers(), accounts: this.core.accountViews()}} : {}),
      ...(this.core.deps.peers?.() ? {devices: this.core.deps.peers()!.devices()} : {}),
    }
  }

  private cloudPlaceView(spec: CloudPlaceSpec, acct: AccountView | undefined): NonNullable<CopyPlaceView['cloud']> {
    const names: Record<CloudProviderId, string> = {drive: 'Google Drive', graph: 'OneDrive', dropbox: 'Dropbox', webdav: 'WebDAV'}
    return {provider: spec.provider, providerName: names[spec.provider], accountId: spec.accountId, accountLabel: this.core.accountViews().find((a) => a.id === spec.accountId)?.label ?? acct?.label ?? names[spec.provider], folder: spec.provider === 'webdav' ? spec.rootPath : spec.rootPath.replace(/^Keepfin\//, ''), needsSignIn: !!acct?.needsSignIn, signedInHere: !!acct}
  }

  view(p: PlaceConfig): CopyPlaceView {
    const rt = this.core.runtime(p.id)
    const local = this.core.local().places[p.id]
    const cov = this.core.snap().coverage[p.id]
    const last = rt.last
    const reviewCount = rt.review.length
    const problem = last && !rt.worker ? problemOf(last) : undefined
    let status: CopyStatus
    let statusText: string | undefined
    const cloudSpec = local?.cloud
    const cloudAccount = cloudSpec ? this.core.accountList().get(cloudSpec.accountId) : undefined
    const peerSpec = local?.peer
    const peerName = peerSpec ? this.core.deps.peers?.()?.name(peerSpec.deviceId) ?? 'The other device' : undefined
    if (rt.worker) status = 'syncing'
    else if (!rt.connected) {
      status = 'disconnected'
      statusText = cloudSpec ? (cloudAccount?.needsSignIn ? 'Sign in again to sync this copy.' : 'This account is not signed in on this computer.') : peerSpec ? `${peerName} is not connected right now. The copy continues when it is.` : 'The drive or folder is not connected.'
    } else if (rt.retry && !rt.paused) {
      status = 'waiting'
      statusText = retryText(cloudSpec ? this.cloudPlaceView(cloudSpec, cloudAccount).providerName : p.name || 'The copy', rt.retry.reason, rt.retry.at)
    } else if (rt.waitingFor) {
      status = 'waiting'
      statusText = `Waiting for ${rt.waitingFor} to finish.`
    } else if (last?.status === 'refused') {
      status = 'refused'
      statusText = last.reason
    } else if (last?.cloud && last.cloud.state !== 'ok' && last.cloud.state !== 'needsSignIn' && last.cloud.state !== 'busy') {
      status = last.cloud.state === 'quota' ? 'review' : 'waiting'
      statusText = last.cloud.message
    } else if (rt.previewPending) {
      status = 'preview'
      statusText = 'Check the first sync, then confirm it.'
    } else if (last?.status === 'held') {
      status = 'held'
      statusText = last.holdReason ?? 'A large change is waiting for you.'
    } else if (rt.paused || last?.status === 'paused') status = 'paused'
    else if (rt.error) {
      status = 'review'
      statusText = rt.error
    } else if (reviewCount > 0) status = 'review'
    else if (problem) {
      status = 'problem'
      statusText = problemShort(problem.count)
    } else if (!cov?.verified && !last) status = 'new'
    else if ((cov?.waiting ?? 0) > 0) status = 'waiting'
    else status = 'upToDate'
    return {
      id: p.id,
      name: p.name || 'Copy',
      path: local?.path ?? '',
      mode: p.mode,
      holds: {all: p.holds.all, spaces: p.holds.spaces, playlists: p.holds.playlists, favourites: p.holds.favourites, smart: p.holds.smart},
      schedule: p.schedule,
      ...(cloudSpec || peerSpec ? {} : {exports: {playlistFiles: p.options.playlistFiles, flat: p.options.flat, mp3: p.options.mp3}}),
      companions: p.options.companions,
      musicVideos: placeMusicVideos(p),
      connected: rt.connected,
      status,
      ...(statusText ? {statusText} : {}),
      ...(rt.removal ? {removal: {...rt.removal}} : {}),
      ...(rt.worker && rt.progress ? {progress: {...rt.progress}} : {}),
      ...(cov?.verifiedAt ? {lastVerifiedAt: cov.verifiedAt} : {}),
      kept: cov?.kept ?? 0,
      waiting: cov?.waiting ?? 0,
      ...(cov ? {items: cov.items, itemsKept: cov.itemsKept} : {}),
      review: reviewCount,
      ...(problem ? {problem} : {}),
      ...(rt.freeBytes !== undefined ? {freeBytes: rt.freeBytes, totalBytes: rt.totalBytes} : {}),
      ...(this.core.snap().versions[p.id] ? {versions: this.core.snap().versions[p.id]} : {}),
      previewPending: rt.previewPending,
      cells: cov?.cells ?? {},
      ...(cloudSpec ? { cloud: this.cloudPlaceView(cloudSpec, cloudAccount) } : {}),
      ...(peerSpec ? {device: {deviceId: peerSpec.deviceId, deviceName: peerName!, online: rt.connected}} : {}),
    }
  }

  /** Reads the places, rows and what each copy holds (a worker job), then the device-side facts about each copy. */
  async refreshSnapshot(): Promise<void> {
    if (this.snapBusy) {
      this.snapAgain = true
      return this.snapBusy
    }
    this.snapBusy = (async () => {
      do {
        this.snapAgain = false
        const reviewItems: Record<string, string[]> = {}
        const copyRoots: Record<string, string> = {}
        for (const [id, rt] of this.core.rt()) {
          reviewItems[id] = rt.review.map((r) => itemOf(r)).filter((x): x is string => !!x)
          const p = this.core.local().places[id]
          if (rt.connected && p) copyRoots[id] = p.path
        }
        const snap = await this.workerSnapshot({job: 'snapshot', dbPath: this.core.deps.dbPath, mainRoot: this.core.deps.mainRoot, mainReplica: this.core.deps.mainReplica, reviewItems, copyRoots})
        this.core.setSnap(snap)
        for (const place of snap.places) {
          const rt = this.core.runtime(place.id)
          if (!rt.last) rt.last = await readJson<RunSummary>(path.join(this.core.stateDir(place.id), 'last-run.json')).catch(() => undefined)
          const rel = await readRelationship(this.core.stateDir(place.id)).catch(() => ({generation: null, previewConfirmed: false}))
          rt.previewPending = !rel.previewConfirmed
          if (rt.last) this.core.rebuildReview(place, rt)
          const local = this.core.local().places[place.id]
          if (local?.cloud) {
            const q = this.core.cloud?.quotaNow(local.cloud.accountId)
            rt.freeBytes = q?.freeBytes
            rt.totalBytes = q?.totalBytes
          } else if (local && rt.connected && !local.peer) {
            const s = await fs.statfs(local.path).catch(() => undefined)
            if (s) {
              rt.freeBytes = Number(s.bavail) * Number(s.bsize)
              rt.totalBytes = Number(s.blocks) * Number(s.bsize)
            }
          }
        }
        this.core.schedulePush()
      } while (this.snapAgain && !this.core.closed())
    })().finally(() => {
      this.snapBusy = undefined
    })
    return this.snapBusy
  }

  private workerSnapshot(job: Extract<WorkerJob, {job: 'snapshot'}>): Promise<Snapshot> {
    return new Promise((resolve, reject) => {
      const worker = new Worker(this.core.workerUrl(), {workerData: job, resourceLimits: {maxOldGenerationSizeMb: 1536}})
      let settled = false
      const finish = (fn: () => void) => {
        if (settled) return
        settled = true
        void worker.terminate()
        fn()
      }
      worker.on('message', (m: ToHost) => {
        if (m.t === 'done' && m.snapshot) finish(() => resolve(m.snapshot!))
        else if (m.t === 'error') finish(() => reject(new Error(m.message)))
      })
      worker.on('error', (error) => finish(() => reject(error)))
      worker.on('exit', () => finish(() => reject(new Error('The copies reader stopped.'))))
    })
  }
}
