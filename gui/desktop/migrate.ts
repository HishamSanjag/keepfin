// v1 queue.json (choice.args jobs) → v2 jobs with structured FormatRequest + options.
import type {
  DownloadOptions,
  FormatRequest,
  Job,
  JobGroup,
  JobStatus,
  MusicMetadata,
} from '../shared/contracts.js'
import type {ExistingMatch} from '../shared/library.js'
import type {SignInSource} from '../shared/core/signin.js'

/** A persisted job plus engine-only hints that never reach the renderer. */
export type StoredJob = Job & {
  music?: MusicMetadata & {trackNumber?: number}
  expectedCount?: number
  /** Automatic retries already used for the current run of attempts. */
  autoRetries?: number
  /** Source genre, tags and categories (normalised) from inspection; the library keeps them when importing tags is on. */
  tags?: string[]
  /** Queued by a follow check: the library marks the finished item as new in its collection. */
  followed?: true
  /** The sign-in a site asked for, found when the link was inspected: the download uses it from the first try. */
  signIn?: SignInSource
}

type V1Choice = {kind?: unknown; label?: unknown; args?: unknown}
type V1Extras = {
  subtitles?: unknown
  autoSubtitles?: unknown
  subtitleLanguages?: unknown
  thumbnail?: unknown
  metadata?: unknown
}

const statuses: JobStatus[] = [
  'queued',
  'preparing',
  'downloading',
  'processing',
  'completed',
  'failed',
  'cancelled',
  'paused',
]

const str = (value: unknown, max = 4096) =>
  typeof value === 'string' ? value.slice(0, max) : undefined
const num = (value: unknown) =>
  typeof value === 'number' && Number.isFinite(value) ? value : undefined

const groupKinds: NonNullable<JobGroup['kind']>[] = ['playlist', 'album', 'channel', 'collection']

/** Validates a stored or constructed group descriptor; undefined when unusable. */
export function sanitizeGroup(raw: unknown): JobGroup | undefined {
  if (!raw || typeof raw !== 'object') return
  const input = raw as Record<string, unknown>
  const id = str(input.id, 100)
  const url = str(input.url)
  if (!id || !/^[a-zA-Z0-9_-]{1,100}$/.test(id)) return
  if (!url || !/^https?:\/\//i.test(url)) return
  const thumbnail = str(input.thumbnail)
  const group: JobGroup = {
    id,
    url,
    title: str(input.title, 300)?.trim() || url,
    uploader: str(input.uploader, 300),
    thumbnail: thumbnail && /^https?:\/\//i.test(thumbnail) ? thumbnail : undefined,
    kind: groupKinds.includes(input.kind as never) ? (input.kind as JobGroup['kind']) : undefined,
  }
  for (const key of Object.keys(group) as (keyof JobGroup)[]) if (group[key] === undefined) delete group[key]
  return group
}

/** The song a music video job belongs to: a library item or a download, by id. */
function attachVideoOf(raw: unknown): Job['attachVideo'] {
  if (!raw || typeof raw !== 'object') return undefined
  const {itemId, jobId} = raw as {itemId?: unknown; jobId?: unknown}
  if (typeof itemId === 'string') return {itemId: itemId.slice(0, 100)}
  if (typeof jobId === 'string') return {jobId: jobId.slice(0, 100)}
  return undefined
}

function sanitizeExisting(raw: unknown): ExistingMatch | undefined {
  if (!raw || typeof raw !== 'object') return
  const input = raw as Record<string, unknown>
  const file = str(input.path)
  const space = str(input.space, 40)
  if (!file || !space || !['source', 'content', 'metadata'].includes(input.by as string)) return
  return {path: file, space, by: input.by as ExistingMatch['by'], itemId: str(input.itemId, 100)}
}

/** Reconstructs intent from the v1 argument list it was built from. */
export function formatFromV1(choice: V1Choice | undefined): FormatRequest {
  const args = Array.isArray(choice?.args)
    ? choice!.args.filter((a): a is string => typeof a === 'string')
    : []
  const value = (flag: string) => {
    const index = args.indexOf(flag)
    return index >= 0 ? args[index + 1] : undefined
  }
  if (choice?.kind === 'audio' || args.includes('-x')) {
    const target = value('--audio-format')
    return {
      type: 'audio',
      target:
        target === 'm4a' ||
        target === 'opus' ||
        target === 'flac' ||
        target === 'wav'
          ? target
          : 'mp3',
      quality: 'best',
    }
  }
  const selector = value('-f') ?? ''
  const height = Number(selector.match(/height<?=(\d{3,4})/)?.[1])
  const container = value('--merge-output-format')
  return {
    type: 'video',
    maxHeight: Number.isFinite(height) && height > 0 ? height : undefined,
    codec: 'auto',
    container: container === 'mkv' || container === 'webm' ? container : 'mp4',
  }
}

export function optionsFromV1(extras: V1Extras | undefined): DownloadOptions {
  const options: DownloadOptions = {}
  if (!extras || typeof extras !== 'object') return options
  if (extras.subtitles === true || extras.autoSubtitles === true) {
    const languages = str(extras.subtitleLanguages, 100)
    options.subtitles = {
      enabled: true,
      languages:
        languages && /^[a-zA-Z0-9.*,\-_]{1,100}$/.test(languages)
          ? languages
          : 'en.*,ar.*',
      auto: extras.autoSubtitles === true,
      embed: false,
    }
  }
  if (extras.thumbnail === true) options.writeThumbnail = true
  if (extras.metadata === true) options.embedMetadata = true
  return options
}

/** A stored sign-in source, or undefined when it is not a valid one (the queue file may be edited by hand). */
function signInOf(raw: unknown): SignInSource | undefined {
  if (!raw || typeof raw !== 'object') return
  const input = raw as Record<string, unknown>
  if (input.kind === 'keepfin') return {kind: 'keepfin'}
  const browser = str(input.browser, 40)
  const label = str(input.label, 100)
  if (input.kind !== 'browser' || !browser || !label) return
  return {kind: 'browser', browser, label, ...(str(input.profile, 1024) ? {profile: str(input.profile, 1024)} : {})}
}

/** Converts one stored job (v1 or v2) into a valid v2 job, or undefined when unusable. */
export function migrateJob(raw: unknown): StoredJob | undefined {
  if (!raw || typeof raw !== 'object') return
  const input = raw as Record<string, unknown>
  const id = str(input.id, 100)
  const url = str(input.url)
  if (!id || !url || !/^https?:\/\//i.test(url)) return
  const v1 = 'choice' in input && !('format' in input)
  const format = v1
    ? formatFromV1(input.choice as V1Choice)
    : (input.format as FormatRequest)
  if (
    !format ||
    typeof format !== 'object' ||
    !['video', 'audio', 'gallery'].includes(format.type)
  )
    return
  let status = statuses.includes(input.status as JobStatus)
    ? (input.status as JobStatus)
    : 'failed'
  // Work interrupted by closing the app resumes from its partial files.
  if (
    status === 'preparing' ||
    status === 'downloading' ||
    status === 'processing'
  )
    status = 'paused'
  const job: StoredJob = {
    id,
    url,
    title: str(input.title, 500) || url,
    thumbnail: str(input.thumbnail),
    uploader: str(input.uploader, 300),
    duration: num(input.duration),
    provider: str(input.provider, 100),
    label:
      str(input.label, 200) ||
      str((input.choice as V1Choice | undefined)?.label, 200) ||
      'Download',
    kind: format.type,
    format,
    options: v1
      ? optionsFromV1(input.extras as V1Extras)
      : ((input.options as DownloadOptions) ?? {}),
    status,
    percent:
      status === 'completed'
        ? 100
        : Math.min(100, Math.max(0, num(input.percent) ?? 0)),
    error: status === 'failed' ? str(input.error, 1000) : undefined,
    errorDetail: status === 'failed' ? str(input.errorDetail, 3000) : undefined,
    file: str(input.file),
    files: Array.isArray(input.files)
      ? input.files
          .filter((f): f is string => typeof f === 'string')
          .slice(0, 5000)
      : undefined,
    outputTemplate: str(input.outputTemplate),
    createdAt: num(input.createdAt) ?? Date.now(),
    startedAt: num(input.startedAt),
    completedAt: num(input.completedAt),
    attempt: num(input.attempt),
    expectedSize: num(input.expectedSize),
    fileSize: num(input.fileSize),
    match:
      input.match && typeof input.match === 'object'
        ? (input.match as Job['match'])
        : undefined,
    music:
      input.music && typeof input.music === 'object'
        ? (input.music as StoredJob['music'])
        : undefined,
    expectedCount: num(input.expectedCount),
    group: sanitizeGroup(input.group),
    autoRetries: num(input.autoRetries),
    space: str(input.space, 40),
    existing: sanitizeExisting(input.existing),
    saveError: str(input.saveError, 400),
    force: input.force === true ? true : undefined,
    attachVideo: attachVideoOf(input.attachVideo),
    findVideo: input.findVideo === true ? true : undefined,
    playlist: str(input.playlist, 100),
    collectionId: str(input.collectionId, 100),
    collectionIndex: num(input.collectionIndex),
    tags: Array.isArray(input.tags) ? input.tags.filter((tag): tag is string => typeof tag === 'string').slice(0, 15) : undefined,
    followed: input.followed === true ? true : undefined,
    signIn: signInOf(input.signIn),
  }
  for (const key of Object.keys(job) as (keyof StoredJob)[])
    if (job[key] === undefined) delete job[key]
  return job
}

/** v1 kept these at the top level; v2 keeps them in settings. */
export function legacySettings(data: Record<string, unknown>) {
  return {
    outDir: str(data.outDir),
    organization:
      data.organization === 'flat'
        ? ('flat' as const)
        : data.organization === 'platform'
          ? ('platform' as const)
          : undefined,
    cookiesFile: str(data.cookiesFile),
  }
}
