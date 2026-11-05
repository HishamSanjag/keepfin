// What the merge needs from its host, so the same code runs in the app (keepfinBoot: worker-thread stores, the network service, the
// profile list) and in the tests (stores opened directly, an in-process two-device world, injected faults).
import type {SyncMembership, SyncObserved, SyncObservedQuery} from '../../shared/core/sync.js'
import type {Joined} from '../net/join.js'
import type {OpDraft} from '../store/mapping.js'
import type {MergeMeta, ScanRow} from '../store/mergeScan.js'

/** A library store as the merge uses it. */
export interface MergeStore {
  readonly actorId: string
  apply(drafts: readonly OpDraft[]): Promise<void>
  flush(): Promise<void>
  rescan(): Promise<void>
  /** The highest sequence number this store holds for [actor] (segments, outbox, applied). */
  lastSeqOf(actor: string): Promise<number>
  scan(onRows: (rows: ScanRow[]) => void | Promise<void>): Promise<void>
  meta(): Promise<MergeMeta>
  observed(query: SyncObservedQuery): Promise<SyncObserved>
  membership(): Promise<SyncMembership>
  close(): Promise<void>
}

export type OpenTOptions = {
  root: string
  records: string
  libraryId: string
  actor: string
  /** Where this merge keeps T's outbox and state database (device-local, in the merge's work folder). */
  outboxDir: string
  statePath: string
}

export interface ProfileInfo {
  profileId: string
  /** The Keepfin folder (media root). */
  folder: string
  libraryId: string
  replicaId: string
  actorId: string
  /** The profile's device folder (`profiles\<id>`): placement-state, linked.json, outbox, cache, state. */
  profileDir: string
  name: string
}

/** The network side. Absent at startup recovery: the steps that need it pause instead. */
export interface MergeNet {
  /** The library [deviceId] offers that this device does not hold (what B joins), or undefined. */
  offer(deviceId: string): {libraryId: string; name: string} | undefined
  deviceName(deviceId: string): string
  /** [deviceId] is connected right now. */
  online?(deviceId: string): boolean
  /** Why connected [deviceId] offers nothing: no library (no Keepfin folder), only libraries this device holds, or none it may read. */
  gap?(deviceId: string): 'none' | 'unshared' | 'held' | 'denied' | undefined
  /** Joins into a records root of the Keepfin folder (net/join.ts with `records`). */
  join(deviceId: string, libraryId: string, name: string, target: {root: string; records: string; recordFile: string}, onProgress?: (segments: number) => void, signal?: AbortSignal): Promise<Joined>
  /** True when this device already holds [libraryId] as another profile. */
  holds(libraryId: string): string | undefined
  /** Waits until a device that holds [libraryId] has durably received [actor]'s operations up to [seq]. */
  published(libraryId: string, actor: string, seq: number, timeoutMs: number): Promise<boolean>
  /** Tells the device that granted [actor] that it leaves [libraryId], so it revokes the actor. */
  leave(deviceId: string, libraryId: string, actor: string): Promise<boolean>
  /** Is any device that holds [libraryId] reachable right now? */
  reachable(libraryId: string): boolean
}

export interface MergeFences {
  /** Pauses what writes or syncs on this device for the duration: copies, sync, downloads starting, the library's own saves. */
  enter(): Promise<void>
  leave(): Promise<void>
  /** Plain reasons a merge cannot start now (a copy runs, a review is open). */
  blockers(): Promise<string[]>
}

export interface MergeDeps {
  deviceDir: string
  newId(): string
  now(): number
  deviceName: string
  profile(): ProfileInfo
  /** S as the live library store (read for the scan and the reverse target). */
  sStore(): MergeStore
  /** The live store of the library that is active now (T while combined). */
  activeStore(): MergeStore
  /** Opens a store on a records root for this merge's own writes. */
  openStore(options: OpenTOptions & {signer?: unknown}): Promise<MergeStore>
  /** What the library index recorded about S files on this device (size and modification time), and where a placement is on disk. */
  files?: {hint(itemId: string): {size?: number; modifiedAt?: number} | undefined; resolve(placement: string): string | undefined}
  /** Replaces the profile's library: after activation (T) and after separating (S). */
  setProfileLibrary(profileId: string, library: {libraryId: string; actorId: string}): Promise<void>
  net?: MergeNet
  fences: MergeFences
  /** `<userData>/library-art`, the device-only artwork named by item id. */
  artDir?: string
  /** Restarts the app so it opens the library the profile now names. */
  relaunch(): void
  push(state: unknown): void
  /** Test hook: called at every durable step. A throw stops the run there, like a crash. */
  faults?: (point: string, detail?: unknown) => void | Promise<void>
  /** Smaller pages in tests. */
  pageSize?: number
  keepDays?: number
}
