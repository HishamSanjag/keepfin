// Library state: spaces' items and playlists as pushed by the main process. Items keep object identity when
// nothing about them changed, and each item has its own subscription, so playback ticks, a favourite toggle or
// a single play count re-render one row, never the gallery.
import {useSyncExternalStore} from 'react'
import type {LibraryBatchPush, LibraryDeltaPush} from '../../shared/contracts'
import {deviceSpace, isSongKind, isVideoSongKind, jobFileState, type Collection, type DeviceMusicState, type LibraryItem, type LibrarySettings, type LibrarySnapshot, type Space} from '../../shared/library'
import {messageOf, native, request} from '../bridge'
import {applySnapshot, getJob, getMeta, useMeta} from '../store'
import {toast} from '../toasts'
import {newUndoId, undoToast} from './undo'

export type LibraryData = {
  snapshot: LibrarySnapshot
  byId: Map<string, LibraryItem>
  bySpace: Map<string, LibraryItem[]>
  /** The user's own collections (shown as playlists), by id. */
  playlistsById: Map<string, Collection>
  playlists: Collection[]
  /** Collections downloaded from a link (playlists, albums, channels). */
  downloaded: Collection[]
  /** Ids of items that belong to a downloaded collection. */
  collectedIds: Set<string>
  /** Items by the download job that produced them, to tell whether a finished download's files are still there. */
  byJob: Map<string, LibraryItem[]>
  /** False until the first state has arrived (or failed). */
  loaded: boolean
  error?: string
}

const emptySnapshot: LibrarySnapshot = {
  items: [],
  collections: [],
  scanning: false,
  problems: [],
}
function derive(snapshot: LibrarySnapshot, loaded: boolean, error?: string): LibraryData {
  const byId = new Map<string, LibraryItem>()
  const bySpace = new Map<string, LibraryItem[]>()
  const byJob = new Map<string, LibraryItem[]>()
  for (const item of snapshot.items) {
    byId.set(item.id, item)
    if (item.jobId) {
      const owned = byJob.get(item.jobId)
      if (owned) owned.push(item)
      else byJob.set(item.jobId, [item])
    }
    const list = bySpace.get(item.space)
    if (list) list.push(item)
    else bySpace.set(item.space, [item])
  }
  const playlists = snapshot.collections.filter((collection) => collection.kind === 'own')
  const downloaded = snapshot.collections.filter((collection) => collection.kind !== 'own')
  const collectedIds = new Set<string>()
  for (const collection of downloaded) for (const id of collection.itemIds) collectedIds.add(id)
  return {
    snapshot,
    byId,
    bySpace,
    byJob,
    playlistsById: new Map(playlists.map((p) => [p.id, p])),
    playlists,
    downloaded,
    collectedIds,
    loaded,
    error,
  }
}

let data: LibraryData = derive(emptySnapshot, false)
const listeners = new Set<() => void>()
const itemListeners = new Map<string, Set<() => void>>()

function sameItem(a: LibraryItem, b: LibraryItem) {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]) as Set<keyof LibraryItem>
  for (const key of keys) {
    const x = a[key]
    const y = b[key]
    if (x === y) continue
    if (x && y && typeof x === 'object' && typeof y === 'object' && JSON.stringify(x) === JSON.stringify(y)) continue
    return false
  }
  return true
}

/** The push sequence the library in memory corresponds to; a delta applies only when its `base` equals it. */
let librarySeq = -1

export function setLibrary(next: LibrarySnapshot) {
  if (!next || !Array.isArray(next.items)) return
  librarySeq = next.seq ?? -1
  const previous = data.byId
  const touched: string[] = []
  const seen = new Set<string>()
  // A first load has nothing to compare with: the items are taken as they are.
  const items = previous.size === 0 ? next.items : next.items.map((item) => {
    seen.add(item.id)
    const old = previous.get(item.id)
    if (old && sameItem(old, item)) return old
    touched.push(item.id)
    return item
  })
  for (const id of previous.keys()) if (!seen.has(id)) touched.push(id)
  const collections = Array.isArray(next.collections) ? next.collections : []
  data = derive({...next, items, collections, problems: next.problems || []}, true)
  listeners.forEach((listener) => listener())
  touched.forEach((id) => itemListeners.get(id)?.forEach((listener) => listener()))
}

/**
 * What changed since push `base` (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md 10.2). Only the touched entries are
 * replaced, appended or removed, in the order a full snapshot has; a delta that does not follow the one before is
 * dropped and the whole library is asked for again, so no update is ever lost silently.
 */
export function applyLibraryDelta(delta: LibraryDeltaPush) {
  if (!data.loaded || delta.base !== librarySeq) {
    if (data.loaded) void resyncLibrary()
    return
  }
  librarySeq = delta.seq
  const prev = data
  const touched: string[] = []
  let items = prev.snapshot.items
  let bySpace = prev.bySpace
  let collections = prev.snapshot.collections
  let derived: Pick<LibraryData, 'playlists' | 'playlistsById' | 'downloaded' | 'collectedIds'> = prev

  if (delta.items?.length || delta.removedItems?.length) {
    const replace = new Map<string, LibraryItem>()
    const appended: LibraryItem[] = []
    const affected = new Set<string>()
    for (const item of delta.items ?? []) {
      const old = prev.byId.get(item.id)
      if (!old) {
        appended.push(item)
        affected.add(item.space)
      } else if (!sameItem(old, item)) {
        replace.set(item.id, item)
        affected.add(old.space)
        affected.add(item.space)
      }
    }
    const gone = new Set((delta.removedItems ?? []).filter((id) => prev.byId.has(id)))
    for (const id of gone) affected.add(prev.byId.get(id)!.space)
    if (replace.size || appended.length || gone.size) {
      for (const id of replace.keys()) touched.push(id)
      for (const item of appended) touched.push(item.id)
      for (const id of gone) touched.push(id)
      // A new array each time a list may change, so list views re-render; the entries themselves keep their identity.
      // Few touched entries are found by identity (a pointer scan); many are found through one pass with a map.
      const few = replace.size + gone.size <= 16
      const next = prev.snapshot.items.slice()
      if (replace.size) {
        if (few) for (const [id, item] of replace) next[next.indexOf(prev.byId.get(id)!)] = item
        else
          for (let at = 0; at < next.length; at++) {
            const replacement = replace.get(next[at].id)
            if (replacement) next[at] = replacement
          }
      }
      for (const item of appended) next.push(item)
      if (gone.size) {
        if (few) for (const id of gone) next.splice(next.indexOf(prev.byId.get(id)!), 1)
        else items = next.filter((item) => !gone.has(item.id))
      }
      items = gone.size && !few ? items : next
      bySpace = new Map(prev.bySpace)
      const spaceMoved = [...replace].some(([id, item]) => prev.byId.get(id)!.space !== item.space)
      if (spaceMoved) {
        // An item changed space: the lists are put together again from the whole order, as a full snapshot would.
        const rebuilt = new Map<string, LibraryItem[]>([...affected].map((space) => [space, []]))
        for (const item of items) rebuilt.get(item.space)?.push(item)
        for (const [space, list] of rebuilt) {
          if (list.length) bySpace.set(space, list)
          else bySpace.delete(space)
        }
      } else {
        for (const space of affected) {
          const list = (prev.bySpace.get(space) ?? []).slice()
          for (const [id, item] of replace) if (item.space === space) list[list.indexOf(prev.byId.get(id)!)] = item
          for (const item of appended) if (item.space === space) list.push(item)
          const kept = gone.size ? list.filter((item) => !gone.has(item.id)) : list
          if (kept.length) bySpace.set(space, kept)
          else bySpace.delete(space)
        }
      }
      for (const id of gone) {
        const old = prev.byId.get(id)!
        prev.byId.delete(id)
        dropFromJob(prev.byJob, old)
      }
      const rejob = new Set<string>()
      for (const [id, item] of replace) {
        const old = prev.byId.get(id)!
        prev.byId.set(id, item)
        if (old.jobId === item.jobId) {
          // The same download: the entry is swapped where it stands, so the first file stays the first.
          const owned = item.jobId ? prev.byJob.get(item.jobId) : undefined
          if (owned && item.jobId) prev.byJob.set(item.jobId, owned.map((entry) => (entry.id === id ? item : entry)))
        } else {
          if (old.jobId) rejob.add(old.jobId)
          if (item.jobId) rejob.add(item.jobId)
        }
      }
      // An item that changed download is placed by its position in the whole order, as a full snapshot would.
      for (const job of rejob) {
        const owned = items.filter((entry) => entry.jobId === job)
        if (owned.length) prev.byJob.set(job, owned)
        else prev.byJob.delete(job)
      }
      for (const item of appended) {
        prev.byId.set(item.id, item)
        addToJob(prev.byJob, item)
      }
    }
  }

  if (delta.collections?.length || delta.removedCollections?.length) {
    collections = upsertCollections(collections, delta.collections, delta.removedCollections)
    const playlists = collections.filter((collection) => collection.kind === 'own')
    const downloaded = collections.filter((collection) => collection.kind !== 'own')
    const collectedIds = new Set<string>()
    for (const collection of downloaded) for (const id of collection.itemIds) collectedIds.add(id)
    derived = {playlists, playlistsById: new Map(playlists.map((p) => [p.id, p])), downloaded, collectedIds}
  }

  const snapshot: LibrarySnapshot = {...prev.snapshot, items, collections, seq: delta.seq}
  if (delta.meta) {
    snapshot.scanning = delta.meta.scanning
    snapshot.scannedAt = delta.meta.scannedAt
    snapshot.problems = delta.meta.problems || []
    snapshot.operation = delta.meta.operation
    snapshot.deviceMusic = delta.meta.deviceMusic
  }
  data = {...prev, ...derived, snapshot, bySpace}
  listeners.forEach((listener) => listener())
  touched.forEach((id) => itemListeners.get(id)?.forEach((listener) => listener()))
}

function dropFromJob(byJob: Map<string, LibraryItem[]>, item: LibraryItem) {
  if (!item.jobId) return
  const list = byJob.get(item.jobId)?.filter((entry) => entry.id !== item.id)
  if (list?.length) byJob.set(item.jobId, list)
  else byJob.delete(item.jobId)
}
function addToJob(byJob: Map<string, LibraryItem[]>, item: LibraryItem) {
  if (!item.jobId) return
  byJob.set(item.jobId, [...(byJob.get(item.jobId) ?? []), item])
}
function upsertCollections(list: Collection[], changed: Collection[] | undefined, removed: string[] | undefined): Collection[] {
  const out = list.slice()
  for (const collection of changed ?? []) {
    const at = out.findIndex((entry) => entry.id === collection.id)
    if (at < 0) out.push(collection)
    else out[at] = collection
  }
  if (!removed?.length) return out
  const gone = new Set(removed)
  return out.filter((entry) => !gone.has(entry.id))
}

let assembling: {seq: number; total: number; items: LibraryItem[]; first: LibraryBatchPush} | undefined
let batchesDone: (() => void) | undefined
/** A big library arrives in batches; it is put together here and shown when the last one is in. */
export function applyLibraryBatch(batch: LibraryBatchPush) {
  if (batch.index === 0) assembling = {seq: batch.seq, total: batch.total, items: [], first: batch}
  if (!assembling || assembling.seq !== batch.seq) return // the start was missed: the next resync begins again
  for (const item of batch.items) assembling.items.push(item)
  if (batch.index !== assembling.total - 1) return
  const {first, items, seq} = assembling
  assembling = undefined
  setLibrary({seq, items, collections: first.collections ?? [], scanning: first.meta?.scanning ?? false, scannedAt: first.meta?.scannedAt, problems: first.meta?.problems ?? [], operation: first.meta?.operation, deviceMusic: first.meta?.deviceMusic})
  batchesDone?.()
}

/** Asks main for the whole library again (the window's sequence was lost). */
export function resyncLibrary() {
  return loadLibrary(true, 'libraryResync')
}

/** Replaces one item locally (optimistic favourite etc.); the next push from main is authoritative. */
function patchItem(id: string, change: Partial<LibraryItem>) {
  if (!data.byId.has(id)) return
  setLibrary({
    ...data.snapshot,
    items: data.snapshot.items.map((item) => (item.id === id ? {...item, ...change} : item)),
  })
}

/** Sets where an item resumes and refreshes the lists, so Continue watching drops it (0) or shows it again at once. */
export const setResumePosition = (id: string, position: number) => patchItem(id, {position})

/** Updates one item in place and notifies only that item's subscribers: lists stay untouched. */
function patchItemQuiet(id: string, change: Partial<LibraryItem>) {
  const old = data.byId.get(id)
  if (!old) return
  const next = {...old, ...change}
  data.byId.set(id, next)
  const swap = (list: LibraryItem[] | undefined) => {
    const at = list ? list.indexOf(old) : -1
    if (list && at >= 0) list[at] = next
  }
  swap(data.snapshot.items)
  swap(data.bySpace.get(old.space))
  itemListeners.get(id)?.forEach((listener) => listener())
}

const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
export const useLibrary = () => useSyncExternalStore(subscribe, () => data)
export const getLibrary = () => data
export const getLibItem = (id: string) => data.byId.get(id)

const itemSubscribers = new Map<string, (listener: () => void) => () => void>()
function subscribeItem(id: string) {
  let fn = itemSubscribers.get(id)
  if (!fn) {
    fn = (listener) => {
      let set = itemListeners.get(id)
      if (!set) itemListeners.set(id, (set = new Set()))
      set.add(listener)
      return () => {
        set!.delete(listener)
        if (!set!.size) itemListeners.delete(id)
      }
    }
    itemSubscribers.set(id, fn)
  }
  return fn
}
/** One item; re-renders only when that item's data changes. */
export function useLibItem(id: string | undefined): LibraryItem | undefined {
  return useSyncExternalStore(id ? subscribeItem(id) : subscribe, () => (id ? data.byId.get(id) : undefined))
}

/** The library entry a download became (its first file), for "Play" on finished downloads. */
export function useJobItem(jobId: string): LibraryItem | undefined {
  return useSyncExternalStore(subscribe, () => data.byJob.get(jobId)?.[0])
}

/** True when a finished download's file was deleted or is gone from its folder. Follows library pushes, never polls. */
export function useJobGone(id: string): boolean {
  return useSyncExternalStore(subscribe, () => {
    const job = getJob(id)
    return !!job && jobFileState(job, data.byJob.get(id) ?? []) === 'deleted'
  })
}
/** How many of these downloads have lost their files. A number, so a row re-renders only when the count changes. */
export function useGoneCount(ids: string[]): number {
  return useSyncExternalStore(subscribe, () => {
    let gone = 0
    for (const id of ids) {
      const job = getJob(id)
      if (job && jobFileState(job, data.byJob.get(id) ?? []) === 'deleted') gone++
    }
    return gone
  })
}

// ---------------------------------------------------------------------------
// Settings access
// ---------------------------------------------------------------------------

// The settings with the device space added while Music on this PC is on. The space is never stored: it comes from the library
// snapshot, so every screen that lists spaces finds it where it looks.
let shownFor: {base: LibrarySettings; shown: LibrarySettings} | undefined
function withDeviceSpace(base: LibrarySettings | undefined, on: boolean): LibrarySettings | undefined {
  if (!base || !on) return base
  if (shownFor?.base !== base) shownFor = {base, shown: {...base, spaces: [...base.spaces, deviceSpace()]}}
  return shownFor.shown
}
const deviceOn = () => data.snapshot.deviceMusic?.enabled === true

export function librarySettings(): LibrarySettings | undefined {
  return withDeviceSpace(getMeta().settings.library, deviceOn())
}
/** Music on this PC: its switch, folders and scan progress. */
export function useDeviceMusic(): DeviceMusicState | undefined {
  return useSyncExternalStore(subscribe, () => data.snapshot.deviceMusic)
}
/** The library is on, in the desktop app, and the main process knows about it. */
export function useLibraryEnabled() {
  const meta = useMeta()
  return !!meta.settings.library?.enabled && meta.platform === 'desktop'
}
export function useLibrarySettings(): LibrarySettings | undefined {
  const base = useMeta().settings.library
  return withDeviceSpace(base, useSyncExternalStore(subscribe, deviceOn))
}
export function spaceById(id: string | undefined): Space | undefined {
  return librarySettings()?.spaces.find((space) => space.id === id)
}
/** Audio anywhere, or a video in a music space (the shared core decides): listed with the songs and played as its sound. */
export const isSong = (item?: LibraryItem): boolean => !!item && isSongKind(item.kind, spaceById(item.space)?.kind)
/** A song that is a video file: it shows the Video tag and offers Watch video. */
export const isVideoSong = (item?: LibraryItem): boolean => !!item && isVideoSongKind(item.kind, spaceById(item.space)?.kind)
export function spaceName(id: string | undefined) {
  return spaceById(id)?.name || 'your library'
}

// ---------------------------------------------------------------------------
// Operations
// ---------------------------------------------------------------------------

type Extra = Record<string, unknown>
/** Sends a library operation for one or many items. Single-item ops carry both `id` and `ids`. */
export async function libraryRequest<T = unknown>(op: string, ids: string[] | string | undefined, extra: Extra = {}): Promise<T> {
  const list = ids === undefined ? undefined : Array.isArray(ids) ? ids : [ids]
  const body: Extra = {op, ...extra}
  if (list) {
    body.ids = list
    if (list.length === 1) body.id = list[0]
  }
  const result = await request<unknown>(body as never)
  if (result && typeof result === 'object') {
    if (Array.isArray((result as LibrarySnapshot).items)) setLibrary(result as LibrarySnapshot)
    // spaceSave, spaceDelete and downloadAnyway answer with the app snapshot (settings and jobs).
    else if (Array.isArray((result as {jobs?: unknown}).jobs)) applySnapshot(result as never)
  }
  return result as T
}

/** Runs a library operation; failures become an error toast. */
export async function libraryAct<T = unknown>(op: string, ids?: string[] | string, extra: Extra = {}): Promise<{ok: true; value: T} | {ok: false}> {
  try {
    return {ok: true, value: await libraryRequest<T>(op, ids, extra)}
  } catch (error) {
    toast({tone: 'error', message: messageOf(error)})
    return {ok: false}
  }
}

let loading = false
export async function loadLibrary(force = false, op: 'libraryState' | 'libraryResync' = 'libraryState') {
  if (!native || loading || (data.loaded && !force)) return
  loading = true
  try {
    const finished = new Promise<void>((resolve) => {
      batchesDone = resolve
      setTimeout(resolve, 120_000)
    })
    const result = await request<LibrarySnapshot>({op})
    if (result?.batched) await finished // the library follows as batches
    else if (result && Array.isArray(result.items)) setLibrary(result)
    else data = derive(data.snapshot, true)
    batchesDone = undefined
  } catch (error) {
    data = derive(data.snapshot, true, messageOf(error))
  } finally {
    loading = false
    listeners.forEach((listener) => listener())
  }
}

export async function toggleFavorite(id: string) {
  const item = data.byId.get(id)
  if (!item) return
  const favorite = !item.favorite
  patchItem(id, {favorite})
  const undoId = newUndoId()
  const result = await libraryAct('libraryFavorite', id, {favorite, undoId})
  if (!result.ok) patchItem(id, {favorite: !favorite})
  else undoToast({undoId, message: favorite ? 'Added to favorites' : 'Removed from favorites'})
}

/** Local bookkeeping while playing, so Continue watching and resume are right before the next push from main. */
export function notePlayback(id: string, position: number | undefined, finished: boolean) {
  const item = data.byId.get(id)
  if (!item) return
  patchItemQuiet(id, {
    position: finished ? undefined : position,
    lastPlayedAt: Date.now(),
    ...(finished && item.kind === 'video' ? {watched: true} : {}),
  })
}
