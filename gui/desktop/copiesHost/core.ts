// What the parts of the copies host may use of the host. The host builds this from closures over its private members; a part
// never reaches into the host itself.
import type {Worker} from 'node:worker_threads'
import type {PlanAction} from '../../shared/core/copies.js'
import type {CloudAccountView, CopiesState, CopyDraft, CopyReviewItem} from '../../shared/copies.js'
import type {PlaceConfig} from '../sqlstate/desired.js'
import type {Progress} from '../copies/executor.js'
import type {RunSummary} from '../copies/placeRun.js'
import type {CloudJobPayload, Snapshot} from '../copiesWorker.js'
import type {CloudHost} from '../cloud/host.js'
import type {AccountView} from '../cloud/accounts.js'
import type {CloudPlaceSpec} from '../cloud/cloudPlace.js'
import type {PeerJobPayload, PeerPlaceSpec} from '../net/peerPlace.js'
import type {CopiesHostDeps} from '../copiesHost.js'

/** For a cloud or a device copy `path` is the device-local mirror of the replica (the runner works on it) and `cloud` / `peer` says where the replica is. */
export type LocalPlace = {path: string; lastRunAt?: number; seenLast?: number; cloud?: CloudPlaceSpec; peer?: PeerPlaceSpec}
export type LocalFile = {version: 1; places: Record<string, LocalPlace>; acks: string[]}

export type Runtime = {
  connected: boolean
  worker?: Worker
  abort?: () => void
  progress?: Progress
  paused: boolean
  last?: RunSummary
  previewPending: boolean
  review: CopyReviewItem[]
  freeBytes?: number
  totalBytes?: number
  startedAt?: number
  error?: string
  /** A run that did not finish: when the next automatic try is due, and why it stopped. Cleared by a finished run. */
  retry?: {attempts: number; at: number; reason: string}
  /** Queued behind other copies: their names, while this one waits for a free slot. */
  waitingFor?: string
  /** Remove copy…: what it is doing now, or why it stopped. Cleared with the runtime when the copy is gone. */
  removal?: {state: 'removing' | 'failed'; text: string}
}

export type Pending = {draft: CopyDraft; placeId: string; replica: string; created: boolean; madeRoot?: boolean; root: string; cloud?: CloudPlaceSpec; peer?: PeerPlaceSpec}

export interface HostCore {
  readonly deps: CopiesHostDeps
  readonly cloud: CloudHost | undefined
  local(): LocalFile
  snap(): Snapshot
  setSnap(snap: Snapshot): void
  rt(): Map<string, Runtime>
  pending(): Map<string, Pending>
  found(): {path: string; name: string}[]
  /** The last sync the person started, once it finished. */
  report(): CopiesState['report']
  /** True once the host is closing. */
  closed(): boolean
  /** The accounts as the screen last saw them. */
  accountViews(): CloudAccountView[]
  setAccountViews(views: CloudAccountView[]): void
  accountList(): Map<string, AccountView>
  setAccountList(list: Map<string, AccountView>): void
  runtime(id: string): Runtime
  stateDir(placeId: string): string
  saveLocal(): Promise<void>
  schedulePush(): void
  scheduleSnapshot(ms?: number): void
  idArg(value: unknown): string
  setRegisters(placeId: string, fields: Record<string, unknown>): Promise<void>
  noteLibraryChanged(): void
  syncPlace(placeId: string, flags: {previewConfirmed?: boolean; confirmHeld?: boolean; audit?: boolean}): Promise<RunSummary | undefined>
  pause(placeId?: string): void
  checkConnected(place: PlaceConfig): Promise<void>
  cancelPreview(placeId: string): Promise<void>
  placeFrom(id: string, draft: CopyDraft, replica: string): PlaceConfig
  runWorker(place: PlaceConfig, copyRoot: string, stateDir: string, flags: {dryRun?: boolean; previewConfirmed?: boolean; confirmHeld?: boolean; audit?: boolean; confirmedDeletes?: string[]; cloud?: CloudJobPayload; peer?: PeerJobPayload}): Promise<RunSummary>
  /** The worker script (the host's own default when the dependencies name none). */
  workerUrl(): URL
  // what the parts give each other
  build(): CopiesState
  refreshSnapshot(): Promise<void>
  rebuildReview(place: PlaceConfig, rt: Runtime): void
  conflictReview(): CopyReviewItem[]
  signInReview(): CopyReviewItem[]
  refreshCloud(): Promise<void>
  refreshQuotas(force?: boolean): Promise<void>
  abs(rel: string): string
  archiveMain(file: string, why: string): Promise<string>
  revise(action: PlanAction, stagedPath: string, restoring: boolean): Promise<{revisionOp: string | null}>
  importNew(action: PlanAction, stagedPath: string, destPath: string): Promise<{itemId: string; revisionOp: string | null; path: string}>
}
