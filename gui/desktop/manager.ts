// Download queue: parallel workers on top of the Studio engine, persistence, inspection
// cache, collection discovery and the push events the renderer consumes.
import fs from 'node:fs/promises'
import path from 'node:path'
import {randomUUID} from 'node:crypto'
import {errorDetailText, retryBackoff, retryPolicy} from '../shared/errors.js'
import {
  isActive,
  maxAutoRetries,
  validateUrl,
  type DownloadOptions,
  type FormatRequest,
  type InspectResult,
  type JobProgress,
  type MediaInfo,
  type MusicAlternate,
  type PlaylistEntry,
  type PushEvent,
  type Settings,
  type Snapshot,
} from '../shared/contracts.js'
import {platformFolder, safeName} from '../shared/output.js'
import {
  defaultSpaceFor,
  folderSpaces,
  isKeptSpace,
  outputStem,
  outputSubfolders,
  reorderSpaces,
  withKeptSpaces,
  type ExistingMatch,
  type MusicVideoFound,
  type Space,
} from '../shared/library.js'
import type {LibraryIndex} from './library/index.js'
import {
  jobQuote,
  validateFormatRequest,
  validateOptions,
  type CookieSource,
} from '../shared/policy.js'
import {validateGalleryRequest} from '../shared/core/gallery.js'
import {photoLabel} from './manager/photos.js'
import {isYouTube} from '../shared/providers.js'
import type {DownloadTask, Engine} from './engine/index.js'
import {settleOutputs} from './engine/outputs.js'
import {legacySettings, migrateJob, type StoredJob} from './migrate.js'
import type {SignInSource} from '../shared/core/signin.js'
import {findSignIns, withSignIn, type SignInFinder} from './signin/auto.js'
import {defaultSettings, KEPT_SPACES_MESSAGE, legacyFromDownloads, loadSettings} from './settings.js'
import {renameWithRetry} from './fsutil.js'
import {Collections} from './manager/collections.js'
import {errorText, PAGE_SIZE, playlistOf, publicJob, sizeOf, stemOf, type QueueCore} from './manager/core.js'
import {FileRefs} from './manager/fileRefs.js'
import {ManagerSettings} from './manager/settings.js'
import {QueueAfterDownload} from './manager/afterSteps.js'
import {MusicExtras, musicVideoId} from './manager/musicExtras.js'
import {VideoLooks} from './manager/videoLooks.js'
import {RemoteJobs} from './manager/remote.js'

export {PAGE_SIZE}
export const INSPECTION_TTL = 10 * 60_000
const INSPECTION_RETAIN = 50
const MAX_METADATA = 2
const INSPECT_TIMEOUT = 90_000
const MAX_ACTIVE = 2000

export type ManagerOptions = {
  /** queue.json */
  store: string
  /** Used when no outDir has been saved yet. */
  defaultOutDir: string
  engine: Engine
  now?: () => number
  /** Debounce for structural `snapshot` pushes (ms). */
  snapshotDelay?: number
  /** Minimum gap between `progress` pushes (ms). */
  progressInterval?: number
  /** Delays before automatic retries 1..3 (ms). Defaults to 5 s, 15 s, 30 s. */
  retryDelays?: readonly number[]
  /** The library index (spaces, gallery). Without it the manager is a plain downloader. */
  library?: LibraryIndex
  /** Default folders of the Music and Videos spaces on a fresh profile. */
  libraryFolders?: {music: string; videos: string}
  /** Looks for a song's official music video; defaults to the real find step (tests pass their own). */
  findMusicVideo?: (artist: string, title: string) => Promise<MusicVideoFound>
  /** Jobs other devices ask for (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md 6): absent, the queue takes none. */
  remote?: RemoteOptions
  /** The sign-ins that exist for a site; defaults to the Firefox profiles signed in on this PC (tests pass their own). */
  signIns?: SignInFinder
}

export type RemoteOptions = {
  /** The network destination check for the link (resolves names, follows redirects hop by hop); throws with words for the person. */
  guard(url: string, signal?: AbortSignal): Promise<void>
  /** A job larger than this (bytes) waits for the person here. */
  consentBytes: number
  /** True on a metered connection (every job waits for the person there). Desktop: false unless the system says so. */
  metered?: () => Promise<boolean>

  /** Which device asked for this job (it survives a restart: the answer is kept with the request, not in the queue). */
  originOf(jobId: string): {device: string} | undefined
}

/** What a remote job did, for the device that asked (a plain, small value). */
export type RemoteJobView = {
  state: 'queued' | 'preparing' | 'downloading' | 'processing' | 'completed' | 'failed' | 'cancelled' | 'paused' | 'consent'
  percent: number
  title?: string
  label?: string
  speed?: number
  eta?: number
  totalBytes?: number
  /** Why it failed or was declined, in plain words. */
  error?: string
}

/** Above this the person here is asked before a job from another device starts (1 GiB). */
export const REMOTE_CONSENT_BYTES = 1024 * 1024 * 1024

/** The engine is asked to check for updates before an engine-related retry at most this often. */
const ENGINE_CHECK_INTERVAL = 60 * 60_000

type Waiting = {
  timer?: NodeJS.Timeout
  /** Engine refresh started for this wait, if any. */
  update?: Promise<unknown>
}

type Run = {
  controller: AbortController
  mode?: 'pause' | 'cancel' | 'close'
  done: Promise<void>
}
type Inspected = {result: InspectResult; at: number; signIn?: SignInSource}

/** After any YouTube rate limit, grouped YouTube jobs are capped at this many app-wide for the cooldown below. */
const YOUTUBE_BATCH_LIMIT = 2
const YOUTUBE_COOLDOWN_MS = 30 * 60_000
const YOUTUBE_BATCH_SLEEP = 0.75
/** Grouped YouTube / YouTube Music jobs run gently: fewer at once, paused after rate limits. */
const isPaced = (job: StoredJob) =>
  !!job.group && (isYouTube(job.url) || isYouTube(job.match?.matchedUrl))

export class DownloadManager {
  private jobs: StoredJob[] = []
  private settings: Settings
  private cookiesFile?: string
  private readonly runs = new Map<string, Run>()
  private readonly waiting = new Map<string, Waiting>()
  private readonly inspections = new Map<string, Inspected>()
  private readonly inspectControllers = new Map<string, Set<AbortController>>()
  private readonly listeners = new Set<(event: PushEvent) => void>()
  private epoch = 0
  private closing = false
  private saveChain: Promise<void> = Promise.resolve()
  private outputChain: Promise<unknown> = Promise.resolve()
  // push scheduling
  private snapshotTimer?: NodeJS.Timeout
  private progressTimer?: NodeJS.Timeout
  private lastProgress = 0
  private readonly dirty = new Set<string>()
  // music video lookups for finished collection songs, one at a time
  // metadata concurrency
  private metadataActive = 0
  private metadataWaiting: (() => void)[] = []
  private unsubscribeEngine?: () => void
  // Grouped YouTube jobs use the full concurrency until a rate limit; then they are capped until this time.
  private youtubeCappedUntil = 0

  private readonly collections: Collections
  private readonly musicExtras: MusicExtras
  private readonly afterDownload: QueueAfterDownload
  private readonly remoteJobs: RemoteJobs
  private readonly managerSettings: ManagerSettings
  private readonly refs: FileRefs
  private readonly videos: VideoLooks

  constructor(private readonly options: ManagerOptions) {
    this.settings = defaultSettings(options.defaultOutDir, options.libraryFolders)
    const core: QueueCore = {
      options,
      jobs: () => this.jobs,
      settings: () => this.settings,
      epoch: () => this.epoch,
      now: () => this.now(),
      commit: () => this.commit(),
      pump: () => void this.pump(),
      emit: (event) => this.emit(event),
      context: (signal, guarded) => this.context(signal, guarded),
      checkCapacity: (extra) => this.checkCapacity(extra),
      resolveOptions: (input) => this.resolveOptions(input),
      resolveSpace: (url, input) => this.resolveSpace(url, input),
      track: (session, controller) => this.track(session, controller),
      withMetadataSlot: (signal, work) => this.withMetadataSlot(signal, work),
      snapshot: () => this.snapshot(),
      closing: () => this.closing,
      save: () => this.save(),
      touch: () => this.touch(),
      job: (id) => this.job(id),
      resume: (id) => this.resume(id),
      cancelOne: (job) => this.cancelOne(job),
      inspect: (input, session, guarded) => this.inspect(input, session, guarded),
      inspectedMedia: (url) => this.inspectedMedia(url),
      withSignIn: (url, signal, first, run) => this.withSignIn(url, signal, first, run),
      jobSpace: (job) => this.jobSpace(job),
      clearLive: (job) => this.clearLive(job),
      entriesOf: (id) => this.collections.entriesOf(id),
      setJobs: (jobs) => {
        this.jobs = jobs
      },
      setSettings: (settings) => {
        this.settings = settings
      },
      cookiesFile: () => this.cookiesFile,
      setCookiesFile: (file) => {
        this.cookiesFile = file
      },
      cookieFilePath: () => this.cookieFilePath,
      hasRuns: () => this.runs.size > 0,
      invalidateMetadata: (message) => this.invalidateMetadata(message),
    }
    this.collections = new Collections(core)
    this.musicExtras = new MusicExtras(core)
    this.videos = new VideoLooks(core, this.musicExtras)
    this.afterDownload = new QueueAfterDownload(core, this.musicExtras, (song) => this.videos.lookFor(song))
    this.remoteJobs = new RemoteJobs(core)
    this.managerSettings = new ManagerSettings(core)
    this.refs = new FileRefs(core)
    if (options.library)
      options.library.hooks = {
        relocated: (from, to) => this.relocateFile(from, to),
        forgotten: (paths) => this.forgetFiles(paths),
        // Keepfin folder: spaces another device changed reach downloads and "Save to" too
        spacesChanged: (spaces, routes) => {
          this.settings = {...this.settings, library: {...this.settings.library, spaces, routes}}
          legacyFromDownloads(this.settings)
          void this.commit()
        },
      }
  }

  private now() {
    return this.options.now?.() ?? Date.now()
  }
  private get cookieFilePath() {
    return path.join(path.dirname(this.options.store), 'cookies.txt')
  }

  // ------------------------------------------------------------------ lifecycle

  async init() {
    let data: Record<string, unknown> = {}
    try {
      data = JSON.parse(await fs.readFile(this.options.store, 'utf8'))
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
        // A corrupt queue must not lose the user's history: keep a copy and start clean.
        await fs
          .copyFile(
            this.options.store,
            `${this.options.store}.corrupt-${this.now()}`,
          )
          .catch(() => {})
      }
    }
    const jobs = Array.isArray(data.jobs) ? data.jobs : []
    this.jobs = jobs.map(migrateJob).filter((job): job is StoredJob => !!job)
    this.jobs.sort((a, b) => a.createdAt - b.createdAt)
    const legacy = legacySettings(data)
    let settings = defaultSettings(this.options.defaultOutDir, this.options.libraryFolders)
    if (legacy.outDir) settings = {...settings, outDir: legacy.outDir}
    if (legacy.organization)
      settings = {...settings, organization: legacy.organization}
    this.settings = loadSettings(settings, data.settings)
    const cookies =
      typeof data.cookiesFile === 'string'
        ? data.cookiesFile
        : legacy.cookiesFile
    if (cookies && (await fs.stat(cookies).catch(() => undefined))) {
      this.cookiesFile = cookies
      if (this.settings.cookies.mode === 'none' && !data.settings)
        this.settings.cookies = {mode: 'file'}
    }
    this.unsubscribeEngine = this.options.engine.onStatus(() => this.touch())
    // With a Keepfin folder open its spaces are the truth: settings kept the folders from before it (e.g. C:\Users\...\Music\Keepfin).
    const stored = this.options.library?.storeSpaces()
    if (stored) {
      const before = this.settings.library.spaces
      // Favorites (a kept space) is added by id when this library lacks it, for any reason; configure() below hands the list to the
      // library, whose next save commits it to the store (a device that adds it at the same time makes the same space).
      this.settings = {...this.settings, library: {...this.settings.library, spaces: withKeptSpaces(stored.spaces), routes: stored.routes ?? this.settings.library.routes}}
      // the download folder from before spaces (Downloader only, downloads sent from another device) follows the Downloads space
      legacyFromDownloads(this.settings)
      // before the library looks at the history: a finished download still pointing at the old place would be added a second time
      await this.refs.followSpaces(before, stored.spaces)
    }
    this.options.library?.configure(this.settings.library)
    await this.options.engine
      .autoUpdate(this.settings.engine.autoUpdate)
      .catch(() => {})
    void this.pump()
  }

  async close() {
    if (this.closing) return
    this.closing = true
    this.unsubscribeEngine?.()
    this.collections.close()
    this.musicExtras.close()
    for (const id of [...this.inspectControllers.keys()])
      this.cancelInspection(id)
    const pending = [...this.runs.values()]
    for (const wait of this.waiting.values()) clearTimeout(wait.timer)
    this.waiting.clear()
    for (const run of pending) {
      run.mode ??= 'close'
      run.controller.abort()
    }
    let timer: NodeJS.Timeout | undefined
    await Promise.race([
      Promise.all(pending.map((run) => run.done)),
      new Promise((resolve) => (timer = setTimeout(resolve, 5000))),
    ])
    clearTimeout(timer)
    clearTimeout(this.snapshotTimer)
    clearTimeout(this.progressTimer)
    this.collections.clearTimers()
    await this.save()
  }

  subscribe(listener: (event: PushEvent) => void) {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  // ------------------------------------------------------------------ snapshots & push

  snapshot(): Snapshot {
    return structuredClone({
      jobs: this.jobs.map((job) => {
        const origin = this.options.remote?.originOf(job.id)
        return origin ? {...publicJob(job), remote: {device: origin.device, ...(this.remoteJobs.consentOf(job.id) ? {consent: this.remoteJobs.consentOf(job.id)} : {})}} : publicJob(job)
      }),
      settings: this.settings,
      engine: this.options.engine.status(),
      platform: 'desktop' as const,
      hasCookieFile: !!this.cookiesFile,
    })
  }

  getSettings(): Settings {
    return structuredClone(this.settings)
  }

  private emit(event: PushEvent) {
    for (const listener of this.listeners) {
      try {
        listener(event)
      } catch (error) {
        console.error('Push observer failed:', error)
      }
    }
  }

  /** Structural change: schedule one debounced snapshot. */
  private touch() {
    if (this.closing || this.snapshotTimer) return
    this.snapshotTimer = setTimeout(() => {
      this.snapshotTimer = undefined
      this.emit({type: 'snapshot', snapshot: this.snapshot()})
    }, this.options.snapshotDelay ?? 50)
  }

  private markProgress(id: string) {
    this.dirty.add(id)
    if (this.progressTimer || this.closing) return
    const interval = this.options.progressInterval ?? 250
    const wait = Math.max(0, this.lastProgress + interval - Date.now())
    this.progressTimer = setTimeout(() => {
      this.progressTimer = undefined
      this.lastProgress = Date.now()
      const progress: JobProgress[] = []
      for (const id of this.dirty) {
        const job = this.jobs.find((item) => item.id === id)
        if (job)
          progress.push({
            id,
            status: job.status,
            percent: job.percent,
            speed: job.speed,
            eta: job.eta,
            downloadedBytes: job.downloadedBytes,
            totalBytes: job.totalBytes,
            phase: job.phase,
          })
      }
      this.dirty.clear()
      if (progress.length) this.emit({type: 'progress', progress})
    }, wait)
  }

  /** Atomic write; never called from progress ticks. */
  private save(): Promise<void> {
    const data = JSON.stringify({
      version: 2,
      jobs: this.jobs,
      settings: this.settings,
      cookiesFile: this.cookiesFile,
    })
    const write = this.saveChain.then(async () => {
      await fs.mkdir(path.dirname(this.options.store), {recursive: true})
      await fs.writeFile(this.options.store + '.tmp', data)
      await renameWithRetry(this.options.store + '.tmp', this.options.store)
    })
    this.saveChain = write.catch(() => {})
    return write
  }

  private async commit() {
    this.touch()
    await this.save()
  }

  // ------------------------------------------------------------------ metadata work

  private async withMetadataSlot<T>(
    signal: AbortSignal,
    work: () => Promise<T>,
  ): Promise<T> {
    if (this.metadataActive >= MAX_METADATA) {
      await new Promise<void>((resolve, reject) => {
        const grant = () => {
          signal.removeEventListener('abort', onAbort)
          resolve()
        }
        const onAbort = () => {
          this.metadataWaiting = this.metadataWaiting.filter(
            (item) => item !== grant,
          )
          reject(signal.reason)
        }
        this.metadataWaiting.push(grant)
        signal.addEventListener('abort', onAbort, {once: true})
      })
    } else this.metadataActive++
    try {
      signal.throwIfAborted()
      return await work()
    } finally {
      const next = this.metadataWaiting.shift()
      if (next) next()
      else this.metadataActive--
    }
  }

  private track(session: string, controller: AbortController) {
    let set = this.inspectControllers.get(session)
    if (!set) this.inspectControllers.set(session, (set = new Set()))
    set.add(controller)
    return () => {
      set!.delete(controller)
      if (!set!.size) this.inspectControllers.delete(session)
    }
  }

  cancelInspection(id: string) {
    for (const controller of this.inspectControllers.get(id) ?? [])
      controller.abort(new Error('Inspection cancelled.'))
    return null
  }

  private context(signal?: AbortSignal, guarded = false) {
    return {settings: this.settings, cookies: this.cookieSource(), signal, ...(guarded && this.options.remote ? {guard: this.options.remote.guard} : {})}
  }

  private cookieSource(): CookieSource | undefined {
    const {mode, browser} = this.settings.cookies
    if (mode === 'file' && this.cookiesFile) return {file: this.cookiesFile}
    if (mode === 'browser' && browser) return {browser}
    return
  }

  /** One engine request, signed out first; a site that then asks for a sign-in gets one try with a sign-in Keepfin can use (see signin/auto.ts). */
  private withSignIn<T>(url: string, signal: AbortSignal | undefined, first: SignInSource | undefined, run: (cookies?: CookieSource) => Promise<T>) {
    return withSignIn({
      url,
      signal,
      first,
      run,
      autoOn: this.settings.autoSignIn,
      manual: !!this.cookieSource(),
      find: this.options.signIns ?? findSignIns,
    })
  }

  async inspect(
    input: unknown,
    session: string = randomUUID(),
    guarded = false,
  ): Promise<InspectResult> {
    const url = validateUrl(input)
    const cached = this.inspections.get(url)
    if (cached && this.now() - cached.at < INSPECTION_TTL)
      return structuredClone(cached.result)
    const epoch = this.epoch
    const controller = new AbortController()
    const untrack = this.track(session, controller)
    const timer = setTimeout(
      () =>
        controller.abort(
          new Error(
            'Inspection timed out. Check your connection and try again.',
          ),
        ),
      INSPECT_TIMEOUT,
    )
    try {
      const {value: result, used} = await this.withMetadataSlot(controller.signal, () =>
        this.withSignIn(url, controller.signal, undefined, (cookies) => {
          const ctx = this.context(controller.signal, guarded)
          return this.options.engine.inspect(url, cookies ? {...ctx, cookies} : ctx)
        }),
      )
      controller.signal.throwIfAborted()
      if (epoch !== this.epoch)
        throw new Error(
          'Cookies changed during inspection. Inspect this link again.',
        )
      if (result.type === 'media') result.media = {...result.media, url}
      this.inspections.delete(url)
      this.inspections.set(url, {result, at: this.now(), signIn: used})
      while (this.inspections.size > INSPECTION_RETAIN)
        this.inspections.delete(this.inspections.keys().next().value!)
      return structuredClone(result)
    } catch (error) {
      if (controller.signal.aborted) throw controller.signal.reason ?? error
      throw error
    } finally {
      clearTimeout(timer)
      untrack()
    }
  }

  private inspectedMedia(url: string): MediaInfo | undefined {
    const found = this.inspections.get(url)?.result
    return found?.type === 'media' ? found.media : undefined
  }

  private inspectedSignIn(url: string): SignInSource | undefined {
    return this.inspections.get(url)?.signIn
  }

  thumbnailSource(id?: string, url?: string): {url?: string; file?: string} {
    if (id) {
      const job = this.job(id)
      return {
        url: job.thumbnail,
        file: job.status === 'completed' ? job.file : undefined,
      }
    }
    if (!url)
      throw new Error('Preview thumbnail is not part of an inspected link.')
    for (const {result} of this.inspections.values())
      if (
        result.type === 'media' &&
        (result.media.thumbnail === url ||
          result.media.gallery?.previews.includes(url) ||
          result.media.gallery?.items.some((item) => item.url === url || item.cover === url))
      )
        return {url}
    if (this.collections.hasThumbnail(url)) return {url}
    throw new Error('Preview thumbnail is not part of an inspected link.')
  }

  // ------------------------------------------------------------------ enqueue

  private checkCapacity(extra: number) {
    if (
      this.jobs.filter((job) => isActive(job.status)).length + extra >
      MAX_ACTIVE
    )
      throw new Error('Queue is full. Wait for some downloads to finish.')
  }

  private resolveOptions(input: unknown): DownloadOptions {
    return input === undefined
      ? structuredClone(this.settings.options)
      : validateOptions(input)
  }

  /** The space a new job saves into: the caller's choice, else the routed default. Undefined when the library is off. */
  private resolveSpace(url: string, input: unknown): string | undefined {
    const library = this.settings.library
    if (!library.enabled) return undefined
    if (input === undefined || input === null) return defaultSpaceFor(url, library)
    // Smart spaces store nothing, so they are never a place to save to.
    if (typeof input !== 'string' || !folderSpaces(library.spaces).some((space) => space.id === input))
      throw new Error('Choose one of your spaces to save to.')
    return input
  }

  /** [videoInput]: the music video the find step offered for a song downloaded as audio, or the YouTube link's own video; it is queued after the song, linked to it. */
  async enqueue(input: unknown, formatInput: unknown, optionsInput?: unknown, spaceInput?: unknown, videoInput?: unknown, playlistInput?: unknown) {
    const url = validateUrl(input)
    const media = this.inspectedMedia(url)
    if (!media) throw new Error('Inspect the link and choose a format first.')
    const space = this.resolveSpace(url, spaceInput)
    this.checkCapacity(1)
    const format: FormatRequest =
      media.engine === 'gallery-dl'
        ? validateGalleryRequest(formatInput, media.gallery?.count)
        : validateFormatRequest(formatInput)
    const options = this.resolveOptions(optionsInput)
    const videoId = videoInput === undefined ? undefined : musicVideoId(videoInput)
    const videoUrl = videoId ? this.musicExtras.videoUrlFor(videoId, format, media) : undefined
    // The label and the expected size come from the shared core (a size is an estimate for audio conversions).
    const quote = jobQuote(format, {video: media.video, audio: media.audio, duration: media.duration})
    const expectedSize = quote.expectedSize
    // a video of a song you have joins that song instead of becoming a second item
    const joins = this.musicExtras.joinSong(url, media, format, options)
    const song: StoredJob = {
      id: randomUUID(),
      url,
      title: media.title || url,
      thumbnail: media.thumbnail,
      uploader: media.uploader,
      duration: media.duration,
      provider: media.provider,
      label: format.type === 'gallery' ? photoLabel(media, format) : quote.label,
      kind: format.type,
      format,
      options,
      status: 'queued',
      percent: 0,
      expectedSize,
      match: media.match,
      music: media.music ? {...media.music} : undefined,
      tags: media.tags,
      expectedCount: format.type === 'gallery' && format.items ? format.items.length : media.gallery?.count,
      space,
      playlist: space ? playlistOf(playlistInput) : undefined,
      signIn: this.inspectedSignIn(url),
      createdAt: this.now(),
      ...joins,
    }
    this.jobs.push(song)
    await this.commit()
    void this.pump()
    // the song is queued whatever happens to its video (it could not be read, the queue is full): nothing blocks the download
    if (videoId) await this.musicExtras.queueMusicVideo(videoId, {jobId: song.id}, space, videoUrl).catch(() => undefined)
    return this.snapshot()
  }

  // ------------------------------------------------------------------ jobs from other devices (spec 6, 9.1 #6; see manager/remote.ts)

  /** True for a job another device asked for. */
  isRemote(id: string): boolean {
    return this.remoteJobs.isRemote(id)
  }

  async enqueueRemote(input: {id: string; url: unknown; format: unknown; options?: unknown; space?: unknown; musicVideo?: boolean}): Promise<void> {
    return this.remoteJobs.enqueueRemote(input)
  }

  remoteView(id: string): RemoteJobView | undefined {
    return this.remoteJobs.remoteView(id)
  }

  allowRemote(id: unknown) {
    return this.remoteJobs.allowRemote(id)
  }

  declineRemote(id: unknown) {
    return this.remoteJobs.declineRemote(id)
  }

  // ------------------------------------------------------------------ controls

  job(id: unknown): StoredJob {
    const job =
      typeof id === 'string'
        ? this.jobs.find((item) => item.id === id)
        : undefined
    if (!job) throw new Error('Download not found.')
    return job
  }

  private async cancelOne(job: StoredJob) {
    if (!isActive(job.status) && job.status !== 'paused') return
    const run = this.runs.get(job.id)
    this.clearWait(job)
    job.status = 'cancelled'
    job.percent = 0
    job.autoRetries = undefined
    this.clearLive(job)
    if (run) {
      run.mode = 'cancel'
      run.controller.abort()
    } else {
      if (job.outputTemplate) await this.discardPartials(job)
      this.musicExtras.releaseVideos(job)
    }
  }

  async cancel(id: unknown) {
    await this.cancelOne(this.job(id))
    await this.commit()
    return this.snapshot()
  }

  private pauseOne(job: StoredJob) {
    const run = this.runs.get(job.id)
    this.clearWait(job)
    job.status = 'paused'
    this.clearLive(job)
    if (run) {
      run.mode = 'pause'
      run.controller.abort()
    }
  }

  async pause(id: unknown) {
    const job = this.job(id)
    if (!isActive(job.status))
      throw new Error('Only active downloads can be paused.')
    this.pauseOne(job)
    await this.commit()
    return this.snapshot()
  }

  async resume(id: unknown) {
    const job = this.job(id)
    if (job.status !== 'paused')
      throw new Error('Only paused downloads can be resumed.')
    job.status = 'queued'
    await this.commit()
    void this.pump()
    return this.snapshot()
  }

  /** Re-queues a failed or cancelled job; a manual retry restarts the automatic retry budget. */
  private async retryOne(job: StoredJob) {
    // A job turns 'failed'/'cancelled' before its run finishes persisting;
    // wait for that brief settle instead of rejecting a valid retry.
    if (job.status === 'failed' || job.status === 'cancelled') await this.settle(job)
    if (this.runs.has(job.id))
      throw new Error('Cancellation is finishing. Retry in a moment.')
    if (job.status !== 'failed' && job.status !== 'cancelled')
      throw new Error('Only failed or cancelled downloads can be retried.')
    job.status = 'queued'
    job.error = undefined
    job.errorDetail = undefined
    job.autoRetries = undefined
    this.clearLive(job)
  }

  async retry(id: unknown) {
    await this.retryOne(this.job(id))
    await this.commit()
    void this.pump()
    return this.snapshot()
  }

  /** "Save again" on a finished download that could not be saved into the library. */
  async saveAgain(id: unknown) {
    const job = this.job(id)
    if (job.status !== 'completed' || !job.saveError) throw new Error('Only a download that could not be saved can be saved again.')
    await this.afterDownload.saveAgain(job)
    await this.commit()
    return this.snapshot()
  }

  // ------------------------------------------------------------------ groups

  private groupJobs(id: unknown): StoredJob[] {
    const jobs =
      typeof id === 'string' ? this.jobs.filter((job) => job.group?.id === id) : []
    if (!jobs.length) throw new Error('Collection not found.')
    return jobs
  }

  /** The collection link stored with a group (validated when the group was created). */
  groupLink(id: unknown): string {
    return this.groupJobs(id)[0].group!.url
  }

  async pauseGroup(id: unknown) {
    for (const job of this.groupJobs(id)) if (isActive(job.status)) this.pauseOne(job)
    await this.commit()
    return this.snapshot()
  }

  async resumeGroup(id: unknown) {
    for (const job of this.groupJobs(id)) if (job.status === 'paused') job.status = 'queued'
    await this.commit()
    void this.pump()
    return this.snapshot()
  }

  async cancelGroup(id: unknown) {
    for (const job of this.groupJobs(id)) await this.cancelOne(job)
    await this.commit()
    return this.snapshot()
  }

  /** Retries every failed or cancelled item; completed items are untouched. */
  async retryGroup(id: unknown) {
    let retried = 0
    for (const job of this.groupJobs(id))
      if (job.status === 'failed' || job.status === 'cancelled') {
        await this.retryOne(job)
        retried++
      }
    if (!retried) throw new Error('Nothing in this collection needs a retry.')
    await this.commit()
    void this.pump()
    return this.snapshot()
  }

  /** Removes the whole set from history. Files stay on disk; active sets are rejected. */
  async removeGroup(id: unknown) {
    const jobs = this.groupJobs(id)
    for (const job of jobs) await this.settle(job)
    if (jobs.some((job) => isActive(job.status) || this.runs.has(job.id)))
      throw new Error('Wait until this collection has finished before removing its history.')
    for (const job of jobs)
      if (job.status === 'paused' && job.outputTemplate) await this.discardPartials(job)
    this.jobs = this.jobs.filter((job) => !jobs.includes(job))
    await this.commit()
    return this.snapshot()
  }

  /**
   * Takes every failed item of a set off the list and out of history. Files are never touched (a failed item
   * has none). The set's summary is derived from its remaining items, and a followed collection already counts
   * the entry as seen (collectionFor → noteSeen), so a later check does not download it again.
   */
  async removeFailed(id: unknown) {
    const jobs = this.groupJobs(id).filter((job) => job.status === 'failed')
    if (!jobs.length) throw new Error('Nothing in this collection has failed.')
    for (const job of jobs) await this.settle(job)
    this.jobs = this.jobs.filter((job) => !jobs.includes(job))
    await this.commit()
    return this.snapshot()
  }

  async moveUp(id: unknown) {
    const job = this.job(id)
    if (job.status !== 'queued')
      throw new Error('Only waiting downloads can be reordered.')
    const index = this.jobs.indexOf(job)
    for (let i = index - 1; i >= 0; i--) {
      if (this.jobs[i].status === 'queued') {
        this.jobs.splice(index, 1)
        this.jobs.splice(i, 0, job)
        break
      }
    }
    await this.commit()
    return this.snapshot()
  }

  /** A finished job's run persists its result just after the status flips; wait briefly for it. */
  private async settle(job: StoredJob) {
    const settling = this.runs.get(job.id)
    if (settling && !isActive(job.status))
      await Promise.race([settling.done, new Promise((r) => setTimeout(r, 5000).unref())])
  }

  async remove(id: unknown) {
    const job = this.job(id)
    // An item waiting for an automatic retry: drop the pending retry first, then it can go.
    if (job.status === 'queued' && job.retry) await this.cancelOne(job)
    await this.settle(job)
    if (isActive(job.status) || this.runs.has(job.id))
      throw new Error(
        'Wait until this download has finished before removing its history.',
      )
    if (job.status === 'paused' && job.outputTemplate)
      await this.discardPartials(job)
    this.jobs = this.jobs.filter((item) => item !== job)
    await this.commit()
    return this.snapshot()
  }

  async clear() {
    const removable = this.jobs.filter(
      (job) =>
        !isActive(job.status) &&
        job.status !== 'paused' &&
        !this.runs.has(job.id),
    )
    this.jobs = this.jobs.filter((job) => !removable.includes(job))
    await this.commit()
    return this.snapshot()
  }

  /** Deletes the downloaded media (never anything else) and the history entry. */
  async deleteFile(id: unknown) {
    const job = this.job(id)
    await this.settle(job)
    if (isActive(job.status) || this.runs.has(job.id))
      throw new Error(
        'Wait until this download has finished before deleting its file.',
      )
    // A job that completed because the recording already existed points at someone else's file: never delete it.
    const files = job.existing
      ? []
      : [job.file, ...(job.files ?? [])].filter((file): file is string => !!file && path.isAbsolute(file))
    for (const file of files) await fs.rm(file, {force: true}).catch(() => {})
    this.options.library?.forgetPaths(files)
    if (job.format.type === 'gallery' && job.outputTemplate) {
      const folder = path.join(
        path.dirname(job.outputTemplate),
        stemOf(job.outputTemplate),
      )
      await fs.rm(folder, {recursive: true, force: true}).catch(() => {})
    }
    this.jobs = this.jobs.filter((item) => item !== job)
    await this.commit()
    return this.snapshot()
  }

  /** Stops a pending automatic retry (pause, cancel, manual action). */
  private clearWait(job: StoredJob) {
    const wait = this.waiting.get(job.id)
    if (wait) clearTimeout(wait.timer)
    this.waiting.delete(job.id)
    delete job.retry
  }

  private clearLive(job: StoredJob) {
    delete job.speed
    delete job.eta
    delete job.phase
  }

  private async discardPartials(job: StoredJob) {
    const folder = path.dirname(job.outputTemplate!)
    await this.options.engine
      .discard({folder, stem: stemOf(job.outputTemplate!), format: job.format})
      .catch(() => {})
  }

  // ------------------------------------------------------------------ settings & cookies (see manager/settings.ts)

  updateSettings(patch: unknown) {
    return this.managerSettings.updateSettings(patch)
  }

  importCookies(source: string) {
    return this.managerSettings.importCookies(source)
  }

  clearCookies() {
    return this.managerSettings.clearCookies()
  }

  /** Cookie/proxy changes fence in-flight and cached results. */
  private invalidateMetadata(message: string) {
    this.epoch++
    this.inspections.clear()
    void this.options.engine.clearCache().catch(() => {})
    this.collections.invalidate(message)
  }

  // ------------------------------------------------------------------ engine

  engineState() {
    return this.managerSettings.engineState()
  }

  engineUpdate() {
    return this.managerSettings.engineUpdate()
  }

  // ------------------------------------------------------------------ collections (see manager/collections.ts)

  startDiscovery(input: unknown, id: unknown) {
    return this.collections.startDiscovery(input, id)
  }

  discoveryState(id: unknown) {
    return this.collections.discoveryState(id)
  }

  discoveryMore(id: unknown) {
    return this.collections.discoveryMore(id)
  }

  cancelDiscovery(id: unknown) {
    return this.collections.cancelDiscovery(id)
  }

  enqueueDiscovered(
    id: unknown,
    urlsInput: unknown,
    preset: unknown,
    optionsInput?: unknown,
    grouped: unknown = true,
    spaceInput?: unknown,
    keepInSyncInput?: unknown,
    findVideoInput?: unknown,
    playlistInput?: unknown,
  ) {
    return this.collections.enqueueDiscovered(id, urlsInput, preset, optionsInput, grouped, spaceInput, keepInSyncInput, findVideoInput, playlistInput)
  }

  /** Changes whenever cookies or the proxy change; a follow check that spans a change is thrown away. */
  get metadataEpoch() {
    return this.epoch
  }

  /** Lists a followed source: the paged discovery, up to `cap` entries. */
  followDiscover(url: string, cap = 1000) {
    return this.collections.followDiscover(url, cap)
  }

  /** Queues newly found entries of a followed collection into its space, after the items already there. */
  followEnqueue(collectionId: string, entries: PlaylistEntry[]) {
    return this.collections.followEnqueue(collectionId, entries)
  }

  // ------------------------------------------------------------------ workers

  private pump() {
    if (this.closing) return
    const limit = this.settings.concurrency
    const pacedLimit = this.now() < this.youtubeCappedUntil ? YOUTUBE_BATCH_LIMIT : limit
    let paced = this.jobs.filter((job) => this.runs.has(job.id) && isPaced(job)).length
    for (const job of this.jobs) {
      if (this.runs.size >= limit) break
      if (job.status !== 'queued' || this.runs.has(job.id) || this.waiting.has(job.id)) continue
      if (isPaced(job)) {
        // Right after a YouTube rate limit, batches run at most two at once for 30 minutes.
        if (paced >= pacedLimit) continue
        paced++
      }
      this.start(job)
    }
  }

  private start(job: StoredJob) {
    const controller = new AbortController()
    const run: Run = {controller, done: Promise.resolve()}
    this.runs.set(job.id, run)
    job.status = 'preparing'
    job.attempt = (job.attempt ?? 0) + 1
    job.startedAt ??= this.now()
    job.error = undefined
    job.errorDetail = undefined
    this.touch()
    run.done = this.execute(job, run).finally(() => {
      this.runs.delete(job.id)
      void this.pump()
    })
  }

  private outputName(job: StoredJob) {
    const title = safeName(job.title)
    const uploader = job.uploader ? safeName(job.uploader) : ''
    switch (this.settings.filenameStyle) {
      case 'title-uploader':
        return uploader ? safeName(`${title} - ${uploader}`) : title
      case 'uploader-title':
        return uploader ? safeName(`${uploader} - ${title}`) : title
      case 'date-title':
        return safeName(
          `${new Date(job.createdAt).toISOString().slice(0, 10)} - ${title}`,
        )
      default:
        return title
    }
  }

  /** The space a job saves into while the library is on; a deleted space falls back to the routed default. */
  private jobSpace(job: StoredJob): Space | undefined {
    const library = this.settings.library
    if (!library.enabled) return undefined
    const wanted = job.space ?? defaultSpaceFor(job.url, library)
    const space = library.spaces.find((item) => item.id === wanted)
    if (space) return space
    const fallback = library.spaces.find((item) => item.id === defaultSpaceFor(job.url, library))
    if (fallback) job.space = fallback.id
    return fallback
  }

  /** Reserves a unique folder/stem; serialized so concurrent starts never collide. */
  private reserveOutput(
    job: StoredJob,
  ): Promise<{folder: string; stem: string}> {
    const work = async () => {
      if (job.outputTemplate)
        return {
          folder: path.dirname(job.outputTemplate),
          stem: stemOf(job.outputTemplate),
        }
      const space = this.jobSpace(job)
      const folder = space
        ? path.join(space.folder, ...outputSubfolders(space.layout, job))
        : this.settings.organization === 'platform'
          ? path.join(this.settings.outDir, platformFolder(job.url))
          : this.settings.outDir
      await fs.mkdir(folder, {recursive: true})
      const existing = (await fs.readdir(folder)).map((name) =>
        name.toLowerCase(),
      )
      const name = space ? outputStem(space.naming, job) : this.outputName(job)
      for (let suffix = 0; ; suffix++) {
        const stem = suffix ? `${name} (${suffix + 1})` : name
        const lower = stem.toLowerCase()
        const taken =
          existing.some(
            (file) =>
              file === lower ||
              file.startsWith(lower + '.') ||
              file.startsWith(lower + ' - '),
          ) ||
          this.jobs.some(
            (other) =>
              other !== job &&
              other.outputTemplate &&
              path.dirname(other.outputTemplate) === folder &&
              stemOf(other.outputTemplate).toLowerCase() === lower,
          )
        if (!taken) {
          job.outputTemplate = path.join(folder, `${stem}.%(ext)s`)
          return {folder, stem}
        }
      }
    }
    const result = this.outputChain.then(work, work)
    this.outputChain = result.catch(() => {})
    return result
  }

  followMovedFiles(deviceDir: string): Promise<number> {
    return this.refs.followMovedFiles(deviceDir)
  }

  repairJobFile(id: string): Promise<string | undefined> {
    return this.refs.repairJobFile(id)
  }

  private async execute(job: StoredJob, run: Run) {
    const {signal} = run.controller
    try {
      if (this.isRemote(job.id) && !(await this.remoteJobs.prepareRemote(job, signal))) {
        this.touch()
        await this.save().catch((error) => console.error('Could not persist download queue:', error))
        return
      }
      const existing = await this.musicExtras.existingRecording(job)
      signal.throwIfAborted()
      if (existing) {
        // adds nothing: the recording joins the playlist, a music video that finished first goes beside it, and the person is told it was there
        await this.afterDownload.run(job, {existing, signal})
        this.touch()
        await this.save().catch((error) => console.error('Could not persist download queue:', error))
        return
      }
      const {folder, stem} = await this.reserveOutput(job)
      signal.throwIfAborted()
      const task: DownloadTask = {
        url: job.url,
        title: job.title,
        format: job.format,
        options: job.options,
        folder,
        stem,
        match: job.match,
        groupUrl: job.group?.url,
        music: job.music,
        thumbnail: job.thumbnail,
        duration: job.duration,
        expectedCount: job.expectedCount,
        sleepRequests: isPaced(job) ? YOUTUBE_BATCH_SLEEP : undefined,
      }
      await this.save().catch(() => {})
      signal.throwIfAborted()
      const {value: outcome, used} = await this.withSignIn(job.url, signal, job.signIn, (cookies) => {
        const ctx = this.context(signal, this.isRemote(job.id))
        return this.options.engine.download(task, cookies ? {...ctx, cookies} : ctx,
        {
          onStage: (stage, phase) => {
            if (signal.aborted) return
            const changed = job.status !== stage || job.phase !== phase
            job.status = stage
            job.phase = phase
            if (stage === 'processing') {
              job.speed = undefined
              job.eta = undefined
            }
            if (changed) this.touch()
            this.markProgress(job.id)
          },
          onProgress: (progress) => {
            if (signal.aborted) return
            job.percent = Math.max(0, Math.min(99, progress.percent))
            job.speed = progress.speed
            job.eta = progress.eta
            if (progress.downloadedBytes !== undefined)
              job.downloadedBytes = progress.downloadedBytes
            if (progress.totalBytes !== undefined)
              job.totalBytes = progress.totalBytes
            this.markProgress(job.id)
          },
          onMatch: (match) => {
            job.match = match
            this.touch()
          },
        })
      })
      job.signIn = used
      signal.throwIfAborted()
      // The reported files must exist. A damaged report is resolved from the job's output name, then from the folder;
      // when nothing can be found with certainty the job fails rather than saving a path that points nowhere.
      const settled = await settleOutputs(outcome, {
        template: job.format.type === 'gallery' ? undefined : job.outputTemplate,
        owned: (file) => this.refs.ownedByOther(job, file),
        log: (message) => console.warn(message),
      })
      signal.throwIfAborted()
      job.file = settled.file
      job.files = settled.files.length ? settled.files : undefined
      if (outcome.match) job.match = outcome.match
      job.fileSize = await sizeOf(
        job.format.type === 'gallery'
          ? [settled.file, ...settled.files]
          : [settled.file],
      )
      job.status = 'processing'
      job.phase = 'Saving to your library'
      this.touch()
      await this.afterDownload.run(job, {signal})
    } catch (error) {
      await this.settleFailure(job, run, error)
      this.musicExtras.releaseVideos(job)
    }
    this.touch()
    await this.save().catch((error) =>
      console.error('Could not persist download queue:', error),
    )
  }

  /** Looks that never ran (Keepfin closed with some still waiting): called once the library is open. */
  resumeVideoLooks() {
    this.videos.resume()
  }

  // ------------------------------------------------------------------ music extras (see manager/musicExtras.ts)

  downloadAnyway(id: unknown) {
    return this.musicExtras.downloadAnyway(id)
  }

  getMusicVideo(itemInput: unknown, videoInput: unknown) {
    return this.musicExtras.getMusicVideo(itemInput, videoInput)
  }

  findLyrics(idInput: unknown) {
    return this.musicExtras.findLyrics(idInput)
  }

  songOfInspected(urlInput: unknown) {
    return this.musicExtras.songOfInspected(urlInput)
  }

  findMusicVideoOf(urlInput: unknown): Promise<MusicVideoFound> {
    return this.musicExtras.findMusicVideoOf(urlInput)
  }

  libraryMatch(urlsInput: unknown, spaceInput: unknown, sessionInput?: unknown): Record<string, ExistingMatch> {
    return this.musicExtras.libraryMatch(urlsInput, spaceInput, sessionInput)
  }

  setMusicChoice(urlInput: unknown, choiceInput: unknown): Promise<null> {
    return this.musicExtras.setMusicChoice(urlInput, choiceInput)
  }

  musicAlternates(urlInput: unknown, excludeInput?: unknown): Promise<MusicAlternate[]> {
    return this.musicExtras.musicAlternates(urlInput, excludeInput)
  }

  // ------------------------------------------------------------------ spaces

  /** Creates or updates a space. Built-in spaces can be renamed and moved; the folder is created and checked. */
  async saveSpace(input: unknown) {
    if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('Invalid space.')
    const raw = input as Record<string, unknown>
    const spaces = this.settings.library.spaces
    const id = typeof raw.id === 'string' && raw.id ? raw.id : `space-${randomUUID().slice(0, 8)}`
    const space = {...raw, id}
    const exists = spaces.some((item) => item.id === id)
    return this.updateSettings({
      library: {spaces: exists ? spaces.map((item) => (item.id === id ? space : item)) : [...spaces, space]},
    })
  }

  /** Puts every space in the given order (built-ins included); the order is what home and the lists show. */
  async reorderSpaces(ids: unknown) {
    if (!Array.isArray(ids) || ids.length > 100) throw new Error('Invalid space order.')
    return this.updateSettings({library: {spaces: reorderSpaces(this.settings.library.spaces, ids as string[])}})
  }

  /** Removes a space from Keepfin. Its files stay on disk; they return if a space covers the folder again. */
  async deleteSpace(id: unknown) {
    if (typeof id !== 'string' || !this.settings.library.spaces.some((space) => space.id === id)) throw new Error('That space no longer exists.')
    if (isKeptSpace(id)) throw new Error(KEPT_SPACES_MESSAGE)
    return this.updateSettings({library: {spaces: this.settings.library.spaces.filter((space) => space.id !== id)}})
  }

  /**
   * Undo of removing a space: the same space (same id) back at its place in the order. A route that pointed to it goes back to it
   * if it still holds the default it fell back to when the space went; one the person has changed since stays.
   */
  async restoreSpace(space: Space, position: number, routes: {music: string; other: string}) {
    const current = this.settings.library
    if (current.spaces.some((item) => item.id === space.id)) throw new Error('That space is already back.')
    const spaces = [...current.spaces]
    spaces.splice(Math.min(Math.max(0, position), spaces.length), 0, structuredClone(space))
    const back = {...current.routes}
    if (routes.music === space.id && current.routes.music === 'music') back.music = space.id
    if (routes.other === space.id && current.routes.other === 'downloads') back.other = space.id
    return this.updateSettings({library: {spaces, routes: back}})
  }

  // ------------------------------------------------------------------ files that moved, left or were deleted elsewhere (see manager/fileRefs.ts)

  completedJobs(): StoredJob[] {
    return this.refs.completedJobs()
  }

  isPending(id: string): boolean {
    return this.refs.isPending(id)
  }

  relocateFile(from: string, to: string) {
    return this.refs.relocateFile(from, to)
  }

  forgetFiles(paths: string[]) {
    return this.refs.forgetFiles(paths)
  }

  fileRefs(paths: string[]): {jobId: string; file?: string; files?: string[]; fileSize?: number}[] {
    return this.refs.fileRefs(paths)
  }

  restoreFileRefs(refs: {jobId: string; file?: string; files?: string[]; fileSize?: number}[]) {
    return this.refs.restoreFileRefs(refs)
  }

  storedJobs(): StoredJob[] {
    return this.refs.storedJobs()
  }

  restoreJobs(saved: StoredJob[], order: readonly string[]): Promise<number> {
    return this.refs.restoreJobs(saved, order)
  }

  private async settleFailure(job: StoredJob, run: Run, error: unknown) {
    if (run.controller.signal.aborted) {
      if (run.mode === 'cancel') {
        await this.discardPartials(job)
        job.status = 'cancelled'
        job.percent = 0
        delete job.downloadedBytes
      } else if (job.status !== 'queued' && job.status !== 'cancelled') {
        // Pause or app exit: partial files stay so resume continues.
        job.status = 'paused'
      }
      this.clearLive(job)
      return
    }
    const policy = retryPolicy(error)
    if (policy.kind === 'rateLimited' && (isYouTube(job.url) || isYouTube(job.match?.matchedUrl))) this.youtubeCappedUntil = this.now() + YOUTUBE_COOLDOWN_MS
    const used = job.autoRetries ?? 0
    const limit = policy.backoff?.length ?? maxAutoRetries
    if (policy.retryable && used < limit && !this.closing) {
      this.scheduleRetry(job, used + 1, policy.engine, policy.backoff, policy.kind === 'rateLimited')
      return
    }
    job.status = 'failed'
    job.error = errorText(error).slice(0, 1000)
    job.errorDetail = errorDetailText(error)
    job.autoRetries = undefined
    delete job.retry
    this.clearLive(job)
  }

  /**
   * Parks a failed job in 'queued' with a visible countdown. It holds no worker slot, so
   * other downloads keep running. Pause, cancel or a manual retry drop the wait.
   */
  private scheduleRetry(
    job: StoredJob,
    n: number,
    engineFailure: boolean,
    backoff: readonly number[] = retryBackoff,
    rateLimited = false,
  ) {
    const delays = this.options.retryDelays ?? backoff
    const delay = delays[n - 1] ?? delays[delays.length - 1]
    // A retrying job never holds back other downloads, even from the same group.
    job.autoRetries = n
    job.status = 'queued'
    job.error = undefined
    this.clearLive(job)
    job.retry = {n, max: backoff.length, at: this.now() + delay}
    const wait: Waiting = {}
    this.waiting.set(job.id, wait)
    // Engine-related failures: refresh the engine before the second retry (at most hourly).
    if (engineFailure && n === 2 && this.engineCheckDue()) {
      job.retry.updating = true
      wait.update = this.options.engine
        .update()
        .catch(() => {})
        .finally(() => {
          if (this.waiting.get(job.id) !== wait || !job.retry) return
          delete job.retry.updating
          this.touch()
        })
    }
    wait.timer = setTimeout(() => void this.fireRetry(job, wait), delay)
  }

  private engineCheckDue() {
    const checked = Math.max(0, ...this.options.engine.status().tools.map((tool) => tool.checkedAt ?? 0))
    return this.now() - checked >= ENGINE_CHECK_INTERVAL
  }

  private async fireRetry(job: StoredJob, wait: Waiting) {
    if (this.waiting.get(job.id) !== wait) return
    if (wait.update) await wait.update
    if (this.waiting.get(job.id) !== wait || this.closing) return
    this.waiting.delete(job.id)
    delete job.retry
    this.touch()
    void this.pump()
  }
}
