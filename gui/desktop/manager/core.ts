// What the parts of the queue (collections first) may use of the manager. The manager builds this from closures over its
// private members; a part never reaches into the manager itself.
import fs from 'node:fs/promises'
import path from 'node:path'
import type {InspectResult, MediaInfo, PlaylistEntry, PushEvent, Settings, Snapshot} from '../../shared/contracts.js'
import type {Space} from '../../shared/library.js'
import type {StoredJob} from '../migrate.js'
import type {Engine} from '../engine/index.js'
import type {DownloadOptions} from '../../shared/contracts.js'
import type {ManagerOptions} from '../manager.js'
import {scrubToolNames} from '../../shared/errors.js'
import {sourceNotice, type SignInSource} from '../../shared/core/signin.js'
import type {CookieSource} from '../../shared/policy.js'

/** Entries per discovery page. */
export const PAGE_SIZE = 200

export const errorText = (error: unknown) =>
  scrubToolNames(error instanceof Error ? error.message : String(error))

/** The total size of the files that exist (at most 500 looked at); undefined when none do. */
export async function sizeOf(files: string[]) {
  let total = 0
  let seen = false
  for (const file of files.slice(0, 500)) {
    const stat = await fs.stat(file).catch(() => undefined)
    if (stat?.isFile()) {
      total += stat.size
      seen = true
    }
  }
  return seen ? total : undefined
}

export interface QueueCore {
  readonly options: ManagerOptions
  /** The queue itself (the same array the manager keeps; jobs are pushed onto it). */
  jobs(): StoredJob[]
  settings(): Settings
  /** Changes whenever cookies or the proxy change. */
  epoch(): number
  now(): number
  /** Pushes a snapshot and saves queue.json. */
  commit(): Promise<void>
  /** Starts queued jobs while there is room. */
  pump(): void
  emit(event: PushEvent): void
  /** What the engine needs for one request: settings, cookies, the abort signal. */
  context(signal?: AbortSignal, guarded?: boolean): Parameters<Engine['discover']>[2]
  /** Throws when `extra` more active jobs would not fit. */
  checkCapacity(extra: number): void
  resolveOptions(input: unknown): DownloadOptions
  /** The space a new job saves into: the caller's choice, else the routed default. */
  resolveSpace(url: string, input: unknown): string | undefined
  /** Registers an abortable request under a session id; returns the function that unregisters it. */
  track(session: string, controller: AbortController): () => void
  /** Runs metadata work within the two-worker limit. */
  withMetadataSlot<T>(signal: AbortSignal, work: () => Promise<T>): Promise<T>
  snapshot(): Snapshot
  /** True once the manager is closing. */
  closing(): boolean
  /** Saves queue.json (never called from progress ticks). */
  save(): Promise<void>
  /** Schedules one debounced snapshot push. */
  touch(): void
  /** The job with this id; throws when there is none. */
  job(id: unknown): StoredJob
  resume(id: unknown): Promise<Snapshot>
  cancelOne(job: StoredJob): Promise<void>
  inspect(input: unknown, session?: string, guarded?: boolean): Promise<InspectResult>
  /** One engine request, signed out first; a site that then asks for a sign-in gets one try with a sign-in Keepfin can use. `first` is a sign-in already recorded. */
  withSignIn<T>(url: string, signal: AbortSignal | undefined, first: SignInSource | undefined, run: (cookies?: CookieSource) => Promise<T>): Promise<{value: T; used?: SignInSource}>
  /** The media of a link inspected recently (within the cache), if any. */
  inspectedMedia(url: string): MediaInfo | undefined
  /** The space a job saves into while the library is on. */
  jobSpace(job: StoredJob): Space | undefined
  /** Drops the live speed/eta/phase of a job. */
  clearLive(job: StoredJob): void
  /** The entries of an open collection review, if the id names one. */
  entriesOf(id: unknown): PlaylistEntry[] | undefined
  /** Replaces the queue (restoring removed history). */
  setJobs(jobs: StoredJob[]): void
  setSettings(settings: Settings): void
  cookiesFile(): string | undefined
  setCookiesFile(file: string | undefined): void
  /** Where an imported cookies file is kept. */
  cookieFilePath(): string
  /** True while any download is running. */
  hasRuns(): boolean
  /** Cookie/proxy changes fence in-flight and cached results. */
  invalidateMetadata(message: string): void
}

/** The playlist a review asked a download to join: its id, or nothing when none was chosen. */
export const playlistOf = (input: unknown): string | undefined => (typeof input === 'string' && input.length > 0 && input.length <= 100 ? input : undefined)

/** The file stem of an output template (without the extension placeholder). */
export function stemOf(template: string) {
  return path.basename(template).replace(/\.%\(ext\)s$/, '')
}

/** A job as the renderer sees it: no internal bookkeeping. */
export function publicJob(job: StoredJob) {
  const {music: _music, expectedCount: _count, autoRetries: _retries, tags: _tags, followed: _followed, remote: _remote, signIn, ...rest} = job
  return signIn ? {...rest, signedIn: sourceNotice(signIn)} : rest
}
