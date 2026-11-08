// External store for queue + app state. Progress pushes patch single jobs and notify only
// that job's subscribers; list structure (ids and statuses) is a separate subscription so
// rows, thumbnails and lists do not re-render on every transfer tick.
import {useSyncExternalStore} from 'react'
import {
  isActive,
  type EngineStatus,
  type Job,
  type JobGroup,
  type JobProgress,
  type JobStatus,
  type Operation,
  type PlaylistDiscovery,
  type PushEvent,
  type Settings,
  type Snapshot,
} from '../shared/contracts'
import {messageOf, native, onPush, request} from './bridge'
import {toast} from './toasts'
import {showStartOverOutcome} from './startOverNotice'
import {defaultLibrarySettings} from '../shared/library'
import {applyLibraryBatch, applyLibraryDelta, setLibrary} from './library/libStore'
import {flushSaved, notifySaved} from './library/saved'

export type Meta = {
  ready: boolean
  platform: 'desktop' | 'preview'
  settings: Settings
  engine: EngineStatus
  hasCookieFile: boolean
  loadError?: string
}
/** `retrying` marks a queued job parked on an automatic-retry countdown: it holds no worker slot. */
export type IndexEntry = {id: string; status: JobStatus; group?: string; retrying?: boolean}
export type Aggregate = {active: number; transferring: number; speed: number; engineWaiting: number}

export const previewSettings: Settings = {
  outDir: 'Downloads/Keepfin',
  organization: 'platform',
  concurrency: 3,
  fragments: 8,
  rateLimit: 0,
  filenameStyle: 'title',
  defaultPreset: 'best',
  youtubeMusic: 'audio',
  options: {embedMetadata: true, embedThumbnail: true, embedChapters: true, sponsorBlock: 'off'},
  cookies: {mode: 'none'},
  autoSignIn: true,
  proxy: '',
  updateChannel: 'stable',
  startPage: 'last',
  lyrics: true,
  musicVideos: false,
  appIcon: '',
  engine: {channel: 'stable', autoUpdate: true},
  turbo: {enabled: false, connections: 8},
  // The browser preview has no library: the Library tab shows the honest history view.
  library: {...defaultLibrarySettings({downloads: 'Downloads/Keepfin', music: 'Music/Keepfin', videos: 'Videos/Keepfin'}), enabled: false},
}

let meta: Meta = {
  ready: !native,
  platform: native ? 'desktop' : 'preview',
  settings: previewSettings,
  engine: {tools: [], busy: false},
  hasCookieFile: false,
}
const jobs = new Map<string, Job>()
let index: IndexEntry[] = []
let aggregate: Aggregate = {active: 0, transferring: 0, speed: 0, engineWaiting: 0}

const metaListeners = new Set<() => void>()
const indexListeners = new Set<() => void>()
const aggregateListeners = new Set<() => void>()
const jobListeners = new Map<string, Set<() => void>>()
const discoveryListeners = new Set<(discovery: PlaylistDiscovery) => void>()
type FollowFound = Extract<PushEvent, {type: 'follow'}>['found']
const followListeners = new Set<(found: FollowFound) => void>()
const revealListeners = new Set<(tab: 'Downloads' | 'Library', group?: string) => void>()

const emit = (set: Set<() => void>) => set.forEach((listener) => listener())

function equalValue(a: unknown, b: unknown) {
  if (a === b) return true
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object') return false
  return JSON.stringify(a) === JSON.stringify(b)
}
function sameJob(a: Job, b: Job) {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)])
  for (const key of keys)
    if (!equalValue(a[key as keyof Job], b[key as keyof Job])) return false
  return true
}
function setIndex(next: IndexEntry[]) {
  const same =
    next.length === index.length &&
    next.every((entry, i) => entry.id === index[i].id && entry.status === index[i].status && entry.group === index[i].group && entry.retrying === index[i].retrying)
  if (same) return
  index = next
  emit(indexListeners)
}
function recomputeAggregate() {
  let active = 0,
    transferring = 0,
    speed = 0,
    engineWaiting = 0
  for (const job of jobs.values()) {
    if (isActive(job.status)) active++
    if (job.retry?.updating) engineWaiting++
    if (job.status === 'downloading') {
      transferring++
      speed += job.speed && Number.isFinite(job.speed) ? job.speed : 0
    }
  }
  if (
    active === aggregate.active &&
    transferring === aggregate.transferring &&
    speed === aggregate.speed &&
    engineWaiting === aggregate.engineWaiting
  )
    return
  aggregate = {active, transferring, speed, engineWaiting}
  emit(aggregateListeners)
}
function notifyJob(id: string) {
  jobListeners.get(id)?.forEach((listener) => listener())
}

// ---------------------------------------------------------------------------
// Collection groups. One cached stats object per group; headers subscribe per group and are
// notified once per push batch (never once per child), so a child's tick re-renders only
// that child's row and the one header that summarises it.
// ---------------------------------------------------------------------------

export type GroupStats = {
  id: string
  group: JobGroup
  /** Member ids in queue order. */
  members: string[]
  total: number
  completed: number
  failed: number
  cancelled: number
  paused: number
  queued: number
  running: number
  /** Members parked on an automatic-retry countdown (not counted in `queued`). */
  retrying: number
  /** Any member running, paused or waiting for a slot. A group that is only waiting on retries is settled. */
  live: boolean
  /** Member ids by role, each in playlist order: failed or cancelled, running/waiting/retrying, and saved. */
  attention: string[]
  active: string[]
  saved: string[]
  /** 0-100 across members that are not cancelled. */
  percent: number
  speed: number
  /** Bytes saved or transferred so far. */
  bytes: number
  /** First member, used for artwork. */
  coverId: string
  /** A completed member whose folder can be revealed. */
  openId?: string
  /** Latest finish or creation time, for ordering. */
  when: number
}
const groupCache = new Map<string, {key: string; stats: GroupStats}>()
const groupListeners = new Map<string, Set<() => void>>()

function computeGroup(id: string): GroupStats | undefined {
  const members: Job[] = []
  for (const job of jobs.values()) if (job.group?.id === id) members.push(job)
  if (!members.length) return
  members.sort((a, b) => a.createdAt - b.createdAt)
  const stats: GroupStats = {
    id,
    group: members[0].group!,
    members: members.map((job) => job.id),
    total: members.length,
    completed: 0,
    failed: 0,
    cancelled: 0,
    paused: 0,
    queued: 0,
    running: 0,
    retrying: 0,
    live: false,
    attention: [],
    active: [],
    saved: [],
    percent: 0,
    speed: 0,
    bytes: 0,
    coverId: members[0].id,
    when: 0,
  }
  let percentSum = 0
  let counted = 0
  for (const job of members) {
    if (job.status === 'completed') {
      stats.completed++
      stats.bytes += job.fileSize || 0
      stats.openId ??= job.file ? job.id : undefined
      stats.saved.push(job.id)
    } else if (job.status === 'failed') {
      stats.failed++
      stats.attention.push(job.id)
    } else if (job.status === 'cancelled') {
      stats.cancelled++
      stats.attention.push(job.id)
    } else {
      if (job.status === 'paused') stats.paused++
      else if (job.status === 'queued' && job.retry) stats.retrying++
      else if (job.status === 'queued') stats.queued++
      else stats.running++
      stats.active.push(job.id)
    }
    if (job.status !== 'completed') stats.bytes += job.downloadedBytes || 0
    if (job.status === 'downloading' && job.speed && Number.isFinite(job.speed)) stats.speed += job.speed
    if (job.status !== 'cancelled') {
      counted++
      percentSum += job.status === 'completed' ? 100 : job.percent
    }
    stats.when = Math.max(stats.when, job.completedAt || job.createdAt || 0)
  }
  stats.live = stats.paused + stats.queued + stats.running > 0
  stats.percent = counted ? Math.floor(percentSum / counted) : 0
  return stats
}
function refreshGroups(ids: Set<string>) {
  for (const id of ids) {
    const stats = computeGroup(id)
    if (!stats) groupCache.delete(id)
    else {
      const key = JSON.stringify(stats)
      if (groupCache.get(id)?.key === key) continue
      groupCache.set(id, {key, stats})
    }
    groupListeners.get(id)?.forEach((listener) => listener())
  }
}
const groupOf = (id: string) => jobs.get(id)?.group?.id
const subscribeGroup = (id: string) => (listener: () => void) => {
  let set = groupListeners.get(id)
  if (!set) groupListeners.set(id, (set = new Set()))
  set.add(listener)
  return () => {
    set!.delete(listener)
    if (!set!.size) groupListeners.delete(id)
  }
}
const groupSubscribers = new Map<string, (listener: () => void) => () => void>()
export function useGroup(id: string): GroupStats | undefined {
  let fn = groupSubscribers.get(id)
  if (!fn) groupSubscribers.set(id, (fn = subscribeGroup(id)))
  return useSyncExternalStore(fn, () => groupCache.get(id)?.stats)
}
export const getGroup = (id: string) => groupCache.get(id)?.stats
const liveStatuses: JobStatus[] = ['downloading', 'preparing', 'processing', 'queued', 'paused']
/** Running, paused or waiting for a slot. A job parked on a retry countdown is not: it holds no worker. */
export const isLiveEntry = (entry: IndexEntry) => liveStatuses.includes(entry.status) && !entry.retrying
/** Ids of collections that still have a member in progress; the rest are settled and belong with finished work. */
export function liveGroupIds(entries: IndexEntry[]) {
  return new Set(entries.filter((entry) => entry.group && isLiveEntry(entry)).map((entry) => entry.group!))
}

const busyStatuses: Job['status'][] = ['queued', 'preparing', 'downloading', 'processing']
// ---------------------------------------------------------------------------
// Collection runs: the downloads a collection has queued or running right now ("Downloading 2 of 3 new").
// A run starts when a job of the collection becomes live and ends when none is live; it remembers its members so
// finished ones still count. Only reported values are used, so the bar never claims more than the engine said.
// ---------------------------------------------------------------------------

export type CollectionRun = {total: number; done: number; failed: number; live: number; percent: number; /** Jobs still queued or running, in queue order. */ liveIds: string[]}
const runMembers = new Map<string, Set<string>>()
const runCache = new Map<string, CollectionRun>()
const runListeners = new Set<() => void>()
function subscribeRuns(listener: () => void) {
  runListeners.add(listener)
  return () => {
    runListeners.delete(listener)
  }
}

function refreshRuns() {
  let touched = false
  const liveNow = new Map<string, number>()
  for (const job of jobs.values()) {
    if (!job.collectionId) continue
    const live = liveStatuses.includes(job.status)
    if (live) {
      liveNow.set(job.collectionId, (liveNow.get(job.collectionId) ?? 0) + 1)
      let members = runMembers.get(job.collectionId)
      if (!members) runMembers.set(job.collectionId, (members = new Set()))
      members.add(job.id)
    }
  }
  for (const [id, members] of [...runMembers]) {
    if (!liveNow.get(id)) {
      runMembers.delete(id)
      if (runCache.delete(id)) touched = true
      continue
    }
    let done = 0
    let failed = 0
    let sum = 0
    let counted = 0
    let total = 0
    const liveIds: string[] = []
    for (const memberId of [...members]) {
      const job = jobs.get(memberId)
      if (!job) {
        members.delete(memberId)
        continue
      }
      if (job.status === 'cancelled') continue
      total++
      if (liveStatuses.includes(job.status)) liveIds.push(job.id)
      if (job.status === 'failed') {
        failed++
        continue
      }
      counted++
      if (job.status === 'completed') {
        done++
        sum += 100
      } else sum += Math.max(0, Math.min(100, job.percent || 0))
    }
    const next: CollectionRun = {total, done, failed, live: liveNow.get(id)!, liveIds, percent: counted ? Math.round((sum / counted) * 10) / 10 : 0}
    const previous = runCache.get(id)
    if (!previous || JSON.stringify(previous) !== JSON.stringify(next)) {
      runCache.set(id, next)
      touched = true
    }
  }
  if (touched) emit(runListeners)
}
/** The collection's current batch of downloads, or undefined when nothing of it is queued or running. */
export const useCollectionRun = (id: string) => useSyncExternalStore(subscribeRuns, () => runCache.get(id))

export function applySnapshot(snapshot: Snapshot) {
  if (!snapshot || !Array.isArray(snapshot.jobs)) return
  const changed: string[] = []
  const touchedGroups = new Set<string>()
  const seen = new Set<string>()
  for (const job of snapshot.jobs) {
    seen.add(job.id)
    const previous = jobs.get(job.id)
    if (!previous || !sameJob(previous, job)) {
      if (previous && previous.status !== 'completed' && job.status === 'completed' && meta.settings.library?.enabled) notifySaved(job)
      jobs.set(job.id, job)
      changed.push(job.id)
      if (job.group) touchedGroups.add(job.group.id)
      if (previous?.group) touchedGroups.add(previous.group.id)
    }
  }
  for (const id of [...jobs.keys()])
    if (!seen.has(id)) {
      const gone = groupOf(id)
      if (gone) touchedGroups.add(gone)
      jobs.delete(id)
      changed.push(id)
    }
  const nextMeta: Meta = {
    ready: true,
    platform: snapshot.platform || 'desktop',
    settings: snapshot.settings || meta.settings,
    engine: snapshot.engine || meta.engine,
    hasCookieFile: !!snapshot.hasCookieFile,
  }
  if (!equalValue(nextMeta, meta)) {
    meta = nextMeta
    emit(metaListeners)
  }
  if (![...jobs.values()].some((job) => busyStatuses.includes(job.status))) flushSaved()
  setIndex(snapshot.jobs.map((job) => ({id: job.id, status: job.status, group: job.group?.id, retrying: job.status === 'queued' && !!job.retry || undefined})))
  changed.forEach(notifyJob)
  refreshGroups(touchedGroups)
  recomputeAggregate()
  refreshRuns()
}

export function applyProgress(list: JobProgress[]) {
  if (!Array.isArray(list)) return
  let statusChanged = false
  const touchedGroups = new Set<string>()
  for (const progress of list) {
    const previous = jobs.get(progress.id)
    if (!previous) continue
    const next = {...previous, ...progress}
    if (sameJob(previous, next)) continue
    if (previous.status !== next.status) statusChanged = true
    jobs.set(next.id, next)
    notifyJob(next.id)
    if (next.group) touchedGroups.add(next.group.id)
  }
  refreshGroups(touchedGroups)
  if (statusChanged)
    setIndex(index.map((entry) => ({...entry, status: jobs.get(entry.id)?.status || entry.status})))
  recomputeAggregate()
  refreshRuns()
}

export function setEngine(engine: EngineStatus) {
  if (!engine || equalValue(engine, meta.engine)) return
  meta = {...meta, engine}
  emit(metaListeners)
}

let started = false
export function startStore() {
  if (started) return
  started = true
  if (!native) return
  onPush((event) => {
    if (event.type === 'snapshot') applySnapshot(event.snapshot)
    else if (event.type === 'progress') applyProgress(event.progress)
    else if (event.type === 'discovery') discoveryListeners.forEach((listener) => listener(event.discovery))
    else if (event.type === 'library') setLibrary(event.library)
    else if (event.type === 'libraryDelta') applyLibraryDelta(event)
    else if (event.type === 'libraryBatch') applyLibraryBatch(event)
    else if (event.type === 'follow') followListeners.forEach((listener) => listener(event.found))
    else if (event.type === 'reveal') revealListeners.forEach((listener) => listener(event.tab, event.group))
  })
  showStartOverOutcome()
  request<Snapshot>({op: 'state'})
    .then(applySnapshot)
    .catch((error) => {
      meta = {...meta, ready: true, loadError: messageOf(error)}
      emit(metaListeners)
    })
}

// ---------------------------------------------------------------------------
// Hooks
// ---------------------------------------------------------------------------

const subscribe = (set: Set<() => void>) => (listener: () => void) => {
  set.add(listener)
  return () => {
    set.delete(listener)
  }
}
const subscribeMeta = subscribe(metaListeners)
const subscribeIndex = subscribe(indexListeners)
const subscribeAggregate = subscribe(aggregateListeners)

export const useMeta = () => useSyncExternalStore(subscribeMeta, () => meta)
export const useIndex = () => useSyncExternalStore(subscribeIndex, () => index)
export const useAggregate = () => useSyncExternalStore(subscribeAggregate, () => aggregate)
export const getJob = (id: string) => jobs.get(id)
export const getMeta = () => meta

const jobSubscribers = new Map<string, (listener: () => void) => () => void>()
function subscribeJob(id: string) {
  let fn = jobSubscribers.get(id)
  if (!fn) {
    fn = (listener: () => void) => {
      let set = jobListeners.get(id)
      if (!set) jobListeners.set(id, (set = new Set()))
      set.add(listener)
      return () => {
        set!.delete(listener)
        if (!set!.size) jobListeners.delete(id)
      }
    }
    jobSubscribers.set(id, fn)
  }
  return fn
}
export function useJob(id: string) {
  return useSyncExternalStore(subscribeJob(id), () => jobs.get(id))
}

export function onDiscovery(listener: (discovery: PlaylistDiscovery) => void) {
  discoveryListeners.add(listener)
  return () => {
    discoveryListeners.delete(listener)
  }
}
export function onFollowFound(listener: (found: FollowFound) => void) {
  followListeners.add(listener)
  return () => {
    followListeners.delete(listener)
  }
}
export function onReveal(listener: (tab: 'Downloads' | 'Library', group?: string) => void) {
  revealListeners.add(listener)
  return () => {
    revealListeners.delete(listener)
  }
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

const pending = new Set<string>()
const pendingListeners = new Set<() => void>()
const subscribePending = subscribe(pendingListeners)
export function usePending(key: string) {
  return useSyncExternalStore(subscribePending, () => pending.has(key))
}

/** Runs an operation and applies a returned Snapshot. Errors become toasts; returns success. */
export async function act(op: Operation, extra: Partial<Parameters<typeof request>[0]> = {}, quiet = false) {
  const key = `${op}:${extra.id || ''}`
  pending.add(key)
  emit(pendingListeners)
  try {
    const result = await request<unknown>({op, ...extra})
    if (result && typeof result === 'object' && Array.isArray((result as Snapshot).jobs))
      applySnapshot(result as Snapshot)
    return true
  } catch (error) {
    if (!quiet) toast({tone: 'error', message: messageOf(error)})
    return false
  } finally {
    pending.delete(key)
      emit(pendingListeners)
  }
}

/** Main merges the library field by field: send only what changed, never a copy of the spaces that may be older than main's. */
export async function updateSettings(settings: Partial<Omit<Settings, 'library'>> & {library?: Partial<Settings['library']>}) {
  return act('updateSettings', {settings})
}

// ---------------------------------------------------------------------------
// One-shot focus (notification click: scroll to and briefly highlight a collection)
// ---------------------------------------------------------------------------

let pendingFocus: string | undefined
const focusListeners = new Set<() => void>()
/** Asks the matching group row to scroll into view once. A later request simply replaces it. */
export function requestGroupFocus(id: string) {
  pendingFocus = id
  focusListeners.forEach((listener) => listener())
  // An unclaimed request (the collection is gone or filtered out) must not fire later.
  setTimeout(() => {
    if (pendingFocus === id) pendingFocus = undefined
  }, 3000)
}
/** Returns true exactly once per request; the request is cleared as it is consumed. */
export function consumeGroupFocus(id: string) {
  if (pendingFocus !== id) return false
  pendingFocus = undefined
  return true
}
export function onGroupFocus(listener: () => void) {
  focusListeners.add(listener)
  return () => {
    focusListeners.delete(listener)
  }
}
