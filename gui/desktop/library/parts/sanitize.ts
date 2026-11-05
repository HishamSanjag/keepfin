// Pure functions of the library index: ids and keys of files, what the window sees of an entry, and reading stored entries,
// playlists and collections defensively.
import {createHash} from 'node:crypto'
import path from 'node:path'
import {
  defaultFollow,
  followIntervals,
  type Collection,
  type CollectionFollow,
  type CollectionKind,
  type LibraryItem,
  type Space,
} from '../../../shared/library.js'
import {providerName} from '../../../shared/providers.js'
import type {LibraryEntry} from '../index.js'

export const keyOf = (file: string) => (process.platform === 'win32' ? path.normalize(file).toLowerCase() : path.normalize(file))
export const itemIdFor = (file: string) => createHash('sha1').update(keyOf(file)).digest('hex').slice(0, 20)

export function isInside(folder: string, file: string): boolean {
  const relative = path.relative(folder, file)
  return !!relative && !relative.startsWith('..') && !path.isAbsolute(relative)
}

const internal = ['managed', 'sig', 'subPaths', 'lrc', 'infoJson', 'videoPath', 'analysed', 'hash', 'colIndex', 'tagsPending'] as const
export function publicItem(entry: LibraryEntry): LibraryItem {
  const item: Record<string, unknown> = {...entry}
  for (const key of internal) delete item[key]
  if (entry.videoPath) item.musicVideo = true
  return item as LibraryItem
}

export function relativeFolder(space: Space, file: string): string {
  const relative = path.relative(space.folder, path.dirname(file))
  return relative.startsWith('..') || path.isAbsolute(relative) ? '' : relative.split(path.sep).join('/')
}

export function titleFromName(file: string): string {
  return path.basename(file, path.extname(file)).replace(/_/g, ' ').trim() || path.basename(file)
}

const kinds = new Set(['video', 'audio', 'image', 'other'])

export function sanitizeEntry(raw: unknown): LibraryEntry | undefined {
  if (!raw || typeof raw !== 'object') return
  const entry = raw as LibraryEntry
  if (
    typeof entry.path !== 'string' ||
    !path.isAbsolute(entry.path) ||
    typeof entry.space !== 'string' ||
    typeof entry.title !== 'string' ||
    !kinds.has(entry.kind) ||
    typeof entry.size !== 'number' ||
    typeof entry.addedAt !== 'number'
  )
    return
  entry.id = itemIdFor(entry.path)
  entry.managed = entry.managed !== false
  entry.folder = typeof entry.folder === 'string' ? entry.folder : ''
  entry.hasArt = entry.hasArt === true
  entry.modifiedAt = typeof entry.modifiedAt === 'number' ? entry.modifiedAt : 0
  return entry
}

export function sanitizePlaylist(raw: unknown): {id: string; name: string; itemIds: string[]; createdAt: number; updatedAt: number} | undefined {
  if (!raw || typeof raw !== 'object') return
  const playlist = raw as {id?: unknown; name?: unknown; itemIds?: unknown; createdAt?: unknown; updatedAt?: unknown}
  if (typeof playlist.id !== 'string' || typeof playlist.name !== 'string' || !Array.isArray(playlist.itemIds)) return
  return {
    id: playlist.id,
    name: playlist.name,
    itemIds: playlist.itemIds.filter((id): id is string => typeof id === 'string'),
    createdAt: typeof playlist.createdAt === 'number' ? playlist.createdAt : 0,
    updatedAt: typeof playlist.updatedAt === 'number' ? playlist.updatedAt : 0,
  }
}

const collectionKinds = new Set(['playlist', 'album', 'channel', 'own'])
const strings = (value: unknown) => (Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [])
const finite = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : undefined)

/** Reads a stored collection defensively; anything missing gets its default. */
export function sanitizeCollection(raw: unknown): Collection | undefined {
  if (!raw || typeof raw !== 'object') return
  const input = raw as Record<string, unknown>
  if (typeof input.id !== 'string' || typeof input.name !== 'string' || !collectionKinds.has(input.kind as string)) return
  const kind = input.kind as CollectionKind
  const collection: Collection = {
    id: input.id,
    name: input.name,
    kind,
    space: typeof input.space === 'string' ? input.space : 'music',
    itemIds: strings(input.itemIds),
    seen: strings(input.seen),
    createdAt: finite(input.createdAt) ?? 0,
    updatedAt: finite(input.updatedAt) ?? 0,
  }
  if (typeof input.coverItemId === 'string') collection.coverItemId = input.coverItemId
  const source = input.source as Record<string, unknown> | undefined
  if (source && typeof source === 'object' && typeof source.url === 'string') {
    collection.source = {url: source.url, provider: typeof source.provider === 'string' ? source.provider : providerName(source.url)}
    if (typeof source.id === 'string') collection.source.id = source.id
  }
  if (kind !== 'own') {
    const base = defaultFollow(kind, collection.source?.url)
    const stored = input.follow && typeof input.follow === 'object' ? (input.follow as Record<string, unknown>) : {}
    const follow: CollectionFollow = {
      mode: stored.mode === 'auto' || stored.mode === 'manual' || stored.mode === 'off' ? stored.mode : base.mode,
      intervalHours: (followIntervals as readonly unknown[]).includes(stored.intervalHours) ? (stored.intervalHours as CollectionFollow['intervalHours']) : base.intervalHours,
      wifiOnly: typeof stored.wifiOnly === 'boolean' ? stored.wifiOnly : base.wifiOnly,
      notify: typeof stored.notify === 'boolean' ? stored.notify : base.notify,
      confirmAbove:
        typeof stored.confirmAbove === 'number' && Number.isInteger(stored.confirmAbove) && stored.confirmAbove >= 1 && stored.confirmAbove <= 500
          ? stored.confirmAbove
          : base.confirmAbove,
    }
    const checked = finite(stored.lastCheckedAt)
    if (checked !== undefined) follow.lastCheckedAt = checked
    if (typeof stored.lastError === 'string') follow.lastError = stored.lastError
    const pending = stored.pending as Record<string, unknown> | undefined
    if (pending && typeof pending === 'object' && finite(pending.count) !== undefined && finite(pending.at) !== undefined)
      follow.pending = {count: pending.count as number, at: pending.at as number}
    collection.follow = follow
  }
  const fresh = strings(input.newItemIds)
  if (fresh.length) collection.newItemIds = fresh
  const removed = strings(input.removedItemIds)
  if (removed.length) collection.removedItemIds = removed
  return collection
}
