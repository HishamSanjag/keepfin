// The merge journal (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 3): device-local, outside the Keepfin folder, written atomically
// after every step. Startup recovery reads it before the library opens and decides what to do next from the stage alone.
import fs from 'node:fs/promises'
import path from 'node:path'
import {readJson, writeJsonAtomic} from '../store/atomic.js'

export type Stage =
  | 'planned' // the person asked to combine (or to see the review): the join of T into its records root is (to be) resumed
  | 'joined' // T's replica is complete in libraries/<T>; the review (dry run) may be shown
  | 'describing' // batches 0..batchesDone-1 are applied; `batch` is the one in flight
  | 'verified' // every S item has a map line
  | 'activating' // the pointer write started
  | 'active' // T is in charge; Separate is available until keepUntil
  | 'separating' // see `separate.step`
  | 'separated'
  | 'cleaned'
  | 'cancelled'

/** Stages in which nothing more is ever done (recovery ignores them). */
export const TERMINAL: readonly Stage[] = ['separated', 'cleaned', 'cancelled']

const ORDER: Stage[] = ['planned', 'joined', 'describing', 'verified', 'activating', 'active', 'separating', 'separated', 'cleaned']

export interface Receipt {
  index: number
  actorSeqBefore: number
  drafts: number
  draftsHash: string
}

export interface LibraryRef {
  libraryId: string
  replicaId: string
  actor: string
  /** The records root below `.keepfin/`. */
  records: string
}

export type SeparateStep = 'previewed' | 'writing' | 'published' | 'describing' | 'pointer' | 'membership' | 'remap' | 'done'

export interface PreviewNumbers {
  sItems: number
  /** Items in the other library (older journals lack it). */
  tItems?: number
  linked: number
  added: number
  keptDuplicate: number
  missing: number
  differing: number
  playlists: number
  spaces: number
  /** The review list (first entries only; the whole list is `plan/review.jsonl`). */
  review: {kind: string; s: string; t?: string; related?: string; field?: string; title?: string}[]
  reviewTotal: number
  at: number
}

export interface Journal {
  v: 1
  mergeId: string
  stage: Stage
  createdAt: number
  /** Why recovery is waiting (lost folder access, offline): never deletes or rolls anything back. */
  paused?: {reason: string; at: number}
  profileId: string
  /** The Keepfin folder (media root). */
  folder: string
  deviceName: string
  s: LibraryRef
  t: LibraryRef & {name: string; peerId: string; hostActor: string}
  keep: 'mine' | 'theirs'
  /** The person pressed Combine on the review (before that, recovery leaves a joined T alone). */
  started?: boolean
  preview?: PreviewNumbers
  plan?: {batches: number; drafts: number; sItems: number; routesBatch: boolean}
  batchesDone: number
  batch?: Receipt
  artDone?: boolean
  mapDone?: boolean
  verified?: {mapped: number; missing: number}
  activation?: {seq: number; at: number; keepUntil: number}
  after: {deviceData?: boolean; profile?: boolean; outside?: boolean}
  separate?: {
    step: SeparateStep
    keepOnPeer: string[] | 'all' | 'none'
    /** Go ahead although no T device can be reached: nothing is written into T or published. */
    anyway: boolean
    /** The last T sequence number written by the Separate (what the peer must confirm). */
    seq?: number
    writePlan?: {batches: number; drafts: number}
    writeDone: number
    writeBatch?: Receipt
    backPlan?: {batches: number; drafts: number; items: number}
    backDone: number
    backBatch?: Receipt
    left?: boolean
  }
  /** The cleanup after keepUntil (or Keep combined): S's frozen subtree removed. */
  keptCombined?: boolean
}

export const journalFile = (deviceDir: string, mergeId: string) => path.join(deviceDir, 'merges', `${mergeId}.json`)
export const workDir = (deviceDir: string, mergeId: string) => path.join(deviceDir, 'merges', mergeId)

export function stageIndex(stage: Stage): number {
  return ORDER.indexOf(stage)
}

/** The stage machine: forward only, except that a separate can start from `active` and a pause never changes the stage. */
export function canAdvance(from: Stage, to: Stage): boolean {
  if (from === to) return true
  if (to === 'cancelled') return from === 'planned' || from === 'joined'
  if (from === 'cancelled') return false
  return stageIndex(to) > stageIndex(from) && stageIndex(from) >= 0
}

export class MergeJournal {
  private writing: Promise<void> = Promise.resolve()

  private constructor(
    readonly deviceDir: string,
    private data: Journal,
    private readonly hook?: (point: string) => void | Promise<void>,
  ) {}

  static async create(deviceDir: string, data: Journal, hook?: (point: string) => void | Promise<void>): Promise<MergeJournal> {
    const journal = new MergeJournal(deviceDir, data, hook)
    await journal.save('create')
    return journal
  }

  static async open(deviceDir: string, mergeId: string, hook?: (point: string) => void | Promise<void>): Promise<MergeJournal | undefined> {
    const data = await readJson<Journal>(journalFile(deviceDir, mergeId)).catch(() => undefined)
    return data && data.v === 1 && data.mergeId === mergeId ? new MergeJournal(deviceDir, data, hook) : undefined
  }

  get state(): Readonly<Journal> {
    return this.data
  }

  get id(): string {
    return this.data.mergeId
  }

  get work(): string {
    return workDir(this.deviceDir, this.data.mergeId)
  }

  /** Writes the journal (atomic: a crash leaves the previous complete file or this one). [point] names the write for fault injection. */
  async save(point: string): Promise<void> {
    await this.hook?.(`before:${point}`)
    const run = this.writing.then(() => writeJsonAtomic(journalFile(this.deviceDir, this.data.mergeId), this.data))
    this.writing = run.catch(() => undefined)
    await run
    await this.hook?.(`after:${point}`)
  }

  /** Changes fields and writes. */
  async update(point: string, change: (data: Journal) => void): Promise<void> {
    change(this.data)
    await this.save(point)
  }

  async advance(to: Stage): Promise<void> {
    if (!canAdvance(this.data.stage, to)) throw new Error(`The merge cannot go from ${this.data.stage} to ${to}.`)
    if (this.data.stage === to) return
    this.data.stage = to
    delete this.data.paused
    await this.save(`stage:${to}`)
  }

  async pause(reason: string, now: number): Promise<void> {
    this.data.paused = {reason, at: now}
    await this.save('pause')
  }

  async resume(): Promise<void> {
    if (!this.data.paused) return
    delete this.data.paused
    await this.save('resume')
  }
}

/** Every journal on this device (newest first). A damaged file is skipped: it is reported by the caller's listing, never deleted. */
export async function listJournals(deviceDir: string): Promise<Journal[]> {
  const dir = path.join(deviceDir, 'merges')
  const names = await fs.readdir(dir).catch(() => [] as string[])
  const out: Journal[] = []
  for (const name of names) {
    if (!name.endsWith('.json')) continue
    const data = await readJson<Journal>(path.join(dir, name)).catch(() => undefined)
    if (data && data.v === 1 && typeof data.mergeId === 'string') out.push(data)
  }
  return out.sort((a, b) => b.createdAt - a.createdAt)
}
