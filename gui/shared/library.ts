// Library contract: spaces, gallery items and playback. Design: dev-docs/architecture/LIBRARY.md.
// Types are re-exported from contracts.ts; pure helpers (routing, layout, duplicate keys) live here too.
import type {FilenameStyle} from './contracts.js'
import {findRecording, type Recording} from './core/dedupe.js'
import {favoritesLook, matchesSmartRules, missingKeptSpaceIds, normalizeTags} from './core/spaces.js'

export type SpaceKind = 'music' | 'video' | 'mixed'
/** Subfolders inside a space: none, one per collection, per site, or Artist/Album (music). */
export type FolderLayout = 'flat' | 'collection' | 'platform' | 'artist-album'
export type SpaceNaming = FilenameStyle | 'artist-title'
/** Eight palette colors; the renderer tunes each for light and dark themes. */
export type SpaceColor = 'cobalt' | 'violet' | 'teal' | 'olive' | 'amber' | 'rust' | 'berry' | 'slate'
export const spaceColors: readonly SpaceColor[] = ['cobalt', 'violet', 'teal', 'olive', 'amber', 'rust', 'berry', 'slate']
export type SpaceIcon = 'music' | 'video' | 'folder' | 'star' | 'heart' | 'mic' | 'book' | 'dumbbell' | 'moon' | 'child'
export const spaceIcons: readonly SpaceIcon[] = ['music', 'video', 'folder', 'star', 'heart', 'mic', 'book', 'dumbbell', 'moon', 'child']

/** What a smart space gathers: rules over every space's items. */
export type SmartRule =
  | {field: 'tag' | 'collection' | 'space' | 'source' | 'artist' | 'album' | 'kind'; values: string[]}
  | {field: 'addedWithin'; days: number}
  | {field: 'favorite' | 'unplayed'}
export type SmartRules = {match: 'any' | 'all'; rules: SmartRule[]; onlyKind?: LibraryItemKind; excludeSpaces?: string[]}

export type Space = {
  /** 'downloads' | 'music' | 'videos' for built-ins; a random id for user spaces. */
  id: string
  name: string
  kind: SpaceKind
  /** Absolute folder on desktop. */
  folder: string
  layout: FolderLayout
  naming: SpaceNaming
  /** Check for the same recording before downloading; matches are deselected/flagged, never silently dropped. */
  skipExisting: boolean
  /** Subfolder names (relative, '/'-separated) the scan ignores; folders containing .nomedia are always ignored. */
  ignore?: string[]
  /** Also list files already in this folder that Keepfin did not download or import (off by default). */
  scanFolder?: boolean
  /** 'folder' stores files and receives downloads. 'smart' stores nothing and gathers items by rules (its folder is ''). */
  type: 'folder' | 'smart'
  /** Palette color; built-ins default to their own tone, custom spaces rotate. */
  color?: SpaceColor
  icon?: SpaceIcon
  /** Shown on home. Built-ins default to pinned. */
  pinned?: boolean
  /** Smart spaces only. */
  rules?: SmartRules
}

export type LibrarySettings = {
  /** Spaces + gallery. Off = plain downloader: everything goes to outDir, Library shows history. */
  enabled: boolean
  /** What opens media: Keepfin's player or the system's default app. */
  openWith: 'keepfin' | 'system'
  /** Volume normalisation (attenuation only). */
  normalize: boolean
  /** Crossfade seconds between songs, 0 = off (0–12). Windows only; the phone keeps and syncs it but has no crossfade. */
  crossfade: number
  spaces: Space[]
  /** Default space ids: music-service and YouTube Music links, and everything else. */
  routes: {music: string; other: string}
  /** Keep the source's genre, tags and categories on new downloads (existing items are not backfilled). */
  importTags: boolean
  /** Old data from when Favorites was offered once and could be removed. Still read and kept; nothing relies on it any more (kept spaces are ensured by id). */
  offered?: string[]
}

export const builtinSpaceIds = ['downloads', 'music', 'videos'] as const

/**
 * Favorites: everything marked with a heart, from every space. A kept smart space: every library has it, it can be renamed and its
 * rules edited, but never removed. Its name, color and icon come from the shared core.
 */
export const favoritesSpace = (): Space => {
  const look = favoritesLook()
  return {
    id: 'favorites',
    name: look.name,
    kind: 'mixed',
    folder: '',
    layout: 'flat',
    naming: 'title',
    skipExisting: false,
    type: 'smart',
    color: look.color as SpaceColor,
    icon: look.icon as SpaceIcon,
    pinned: true,
    rules: {match: 'any', rules: [{field: 'favorite'}]},
  }
}

/** The kept spaces Keepfin can make by itself (the folder built-ins need a folder from the platform), by id. */
export const keptSpaceMakers: Record<string, () => Space> = {favorites: favoritesSpace}

export {isKeptSpace, keptSpaceIds} from './core/spaces.js'

/**
 * The spaces with every missing kept space added (Favorites, for a new library, one made before it existed, one removed by an older
 * version, or after Delete everything). Decided by id alone: a renamed one is still there, and two devices that both add it make the
 * same space. The same list when nothing is missing.
 */
export function withKeptSpaces(spaces: readonly Space[]): Space[] {
  return insertKept(spaces, (space) => space.id)
}

/** The same as [withKeptSpaces] for any list whose entries have an id, reading it with `idOf`; Favorites goes right after Videos, else last. */
export function insertKept<T>(entries: readonly T[], idOf: (entry: T) => string | undefined): (T | Space)[] {
  const ids = entries.map(idOf)
  const missing = missingKeptSpaceIds(ids.filter((id): id is string => typeof id === 'string')).filter((id) => id in keptSpaceMakers)
  const out: (T | Space)[] = [...entries]
  if (!missing.length) return out
  const videos = ids.indexOf('videos')
  out.splice(videos < 0 ? out.length : videos + 1, 0, ...missing.map((id) => keptSpaceMakers[id]()))
  return out
}

export type LibraryItemKind = 'video' | 'audio' | 'image' | 'other'

export type LibraryItem = {
  /** Stable id derived from the absolute path. */
  id: string
  space: string
  path: string
  /** Folder relative to the space root, '/'-separated, '' at the top level. Collection folders act as playlists. */
  folder: string
  kind: LibraryItemKind
  title: string
  artist?: string
  album?: string
  albumArtist?: string
  track?: number
  year?: number
  /** Seconds. */
  duration?: number
  size: number
  modifiedAt: number
  addedAt: number
  sourceUrl?: string
  /** 'youtube:<id>', 'spotify:<id>', 'apple-music:<id>', 'deezer:<id>'. */
  sourceId?: string
  jobId?: string
  /** Cover art or video poster available at keepfin-media://art/<id>. */
  hasArt: boolean
  width?: number
  height?: number
  /** Resume position in seconds (video). */
  position?: number
  plays?: number
  lastPlayedAt?: number
  /** Sidecar subtitle labels, served at keepfin-media://sub/<id>/<index>. */
  subtitles?: string[]
  favorite?: boolean
  /** Watched past 92% (video). */
  watched?: boolean
  /** Embedded lyrics tag or .lrc sidecar exists; text via the libraryLyrics op. */
  hasLyrics?: boolean
  /** A song with its official music video kept beside it (Song.video.mp4); the video plays through the libraryMusicVideo media route. */
  musicVideo?: boolean
  /** The music video's own length (seconds) and size (bytes), when known; the song's are `duration` and `size`. */
  videoDuration?: number
  videoSize?: number
  /** Volume normalisation gain in dB (ReplayGain tag or analysed loudness). */
  gain?: number
  disc?: number
  genre?: string
  /** Chapter starts in seconds with titles (video). */
  chapters?: {start: number; title: string}[]
  /** Tags from the source (genre, tags, categories): lower case, trimmed, deduplicated, at most 15 of 30 characters. */
  tags?: string[]
  /** Tags the user added. */
  userTags?: string[]
  /** The file is gone from disk (deleted or renamed outside Keepfin). Dropped once the user confirms. */
  missing?: boolean
}

export type CollectionKind = 'playlist' | 'album' | 'channel' | 'own'
export type FollowMode = 'auto' | 'manual' | 'off'
export type CollectionFollow = {
  mode: FollowMode
  intervalHours: 1 | 6 | 12 | 24 | 168
  /** Kept for both platforms; desktop ignores it. */
  wifiOnly: boolean
  notify: boolean
  /** More new items than this wait for the user. */
  confirmAbove: number
  lastCheckedAt?: number
  /** Friendly message, no tool names. */
  lastError?: string
  /** Found more than confirmAbove; waiting for the user to review. */
  pending?: {count: number; at: number}
}

/** A playlist, album or channel kept together: downloaded from a link (and optionally followed), or the user's own list. */
export type Collection = {
  id: string
  name: string
  kind: CollectionKind
  /** Folder space it was downloaded into; for 'own', the space it was created in (may hold items from any space). */
  space: string
  /** Play order: source order for downloaded collections. */
  itemIds: string[]
  coverItemId?: string
  /** Canonical link; id = playlist, channel or album id. */
  source?: {url: string; provider: string; id?: string}
  follow?: CollectionFollow
  /** Source item ids ever discovered for this collection: downloaded, deselected or deleted. */
  seen: string[]
  /** Added by the last check; cleared when the collection is opened. */
  newItemIds?: string[]
  /** Gone from the source, kept locally. */
  removedItemIds?: string[]
  createdAt: number
  updatedAt: number
}

export type Lyrics = {synced: boolean; lines: {time?: number; text: string}[]}

export type LibrarySnapshot = {
  /** Push sequence this snapshot corresponds to; deltas apply when their `base` equals it (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md 10.2). */
  seq?: number
  /** The items and collections follow as `libraryBatch` pushes (a big library): this answer carries none of them. */
  batched?: boolean
  items: LibraryItem[]
  collections: Collection[]
  scanning: boolean
  scannedAt?: number
  problems: {space: string; message: string}[]
  /** A long file operation (move, import, tag edit) in progress; absent when idle. Cancel with libraryCancel. */
  operation?: {kind: 'move' | 'copy' | 'import' | 'edit' | 'delete'; done: number; total: number; current?: string}
}

/** Result of libraryImport. Duplicates (same content already in the space) are skipped, never copied. */
export type LibraryImportResult = {
  added: string[]
  duplicates: {path: string; itemId: string}[]
  failed: {path: string; message: string}[]
  cancelled: boolean
}

/** Result of libraryMove. */
export type LibraryMoveResult = {
  moved: string[]
  failed: {id: string; message: string}[]
  /** Moved, but with a caveat (for example the original could not be sent to the Recycle Bin). */
  notes: string[]
  cancelled: boolean
}

/**
 * Result of libraryDeleteFile. Files inside the Keepfin folder go to its trash (.keepfin/trash/<item id>/) and libraryUndo can bring them
 * back; any other file goes to the Recycle Bin. Items whose file is already gone are simply dropped.
 */
export type LibraryDeleteResult = {
  deleted: string[]
  failed: {id: string; message: string}[]
  /** The deleted items Undo can bring back (their files are in the Keepfin trash). Absent when none. */
  undoable?: string[]
}

/** What libraryUndo answers: how many things went back, how many did not, and a plain sentence for the notice when there is one to say. */
export type UndoResult = {
  done: number
  failed: number
  message?: string
}

/** One entry of the Keepfin folder's Trash page (libraryTrashList). `id` is the trash folder's name. */
export type TrashEntry = {
  id: string
  title: string
  fileName: string
  /** The space the file came from ('' when it cannot be known). */
  space: string
  kind: LibraryItemKind
  size: number
  /** Epoch ms. */
  deletedAt: number
  /** 30 minus whole days since deletion, never below 0. */
  daysLeft: number
}

/** libraryTrashList: `hasFolder` is false when the library is not in a Keepfin folder (the trash belongs to that folder). */
export type TrashList = {hasFolder: boolean; entries: TrashEntry[]}

/** libraryTrashRestore / libraryTrashErase / libraryTrashEmpty: `ids` are the entries that were restored or erased. */
export type TrashResult = UndoResult & {ids: string[]}

/** Result of libraryPrepare: a URL the media element can play, or why it cannot. */
export type PrepareResult =
  /** `from` is the name of the paired device the file plays from (it is not on this computer). */
  | {url: string; remuxed: boolean; video?: string; audio?: string; from?: string}
  | {unsupported: true; reason: string}
  /** Not on this computer; the paired device that has it cannot be reached now (when it was last seen, if ever). */
  | {unreachable: {device: string; lastSeen: number | null}}

/** libraryFindMusicVideo: the official video found for a song, or none (Keepfin never guesses). */
/** A song you have that a video link is the recording of (any space): a video downloaded from it joins that song. */
export type SongOfLink = {itemId: string; space: string; hasVideo: boolean}

export type MusicVideoFound = {videoId: string; title: string; channel: string; views?: number; durationSec?: number} | {none: true}

export const MEDIA_SCHEME = 'keepfin-media'

/** URL the renderer gives to <video>/<audio>/<img>; main resolves ids, never paths. */
export function mediaUrl(kind: 'item' | 'art' | 'job' | 'prepared' | 'video', id: string): string
export function mediaUrl(kind: 'sub', id: string, index: number): string
export function mediaUrl(kind: 'item' | 'art' | 'job' | 'prepared' | 'video' | 'sub', id: string, index?: number): string {
  const base = `${MEDIA_SCHEME}://${kind}/${encodeURIComponent(id)}`
  return kind === 'sub' ? `${base}/${index ?? 0}` : base
}

/** Why a job did not download: the same recording already exists. "Download anyway" re-runs it with force. */
export type ExistingMatch = {
  itemId?: string
  path: string
  space: string
  /** source = same source id; isrc = same recording code; content = same file bytes; metadata = same artist/title/duration. */
  by: 'source' | 'isrc' | 'content' | 'metadata'
}

/** Built-in spaces and defaults. `folders` come from the platform (Electron app.getPath). */
export function defaultLibrarySettings(folders: {downloads: string; music: string; videos: string}): LibrarySettings {
  const library: LibrarySettings = {
    enabled: true,
    openWith: 'keepfin',
    normalize: true,
    crossfade: 0,
    spaces: [
      {id: 'downloads', name: 'Downloads', kind: 'mixed', folder: folders.downloads, layout: 'platform', naming: 'title', skipExisting: false, type: 'folder', color: 'slate', icon: 'folder', pinned: true},
      {id: 'music', name: 'Music', kind: 'music', folder: folders.music, layout: 'collection', naming: 'title', skipExisting: true, type: 'folder', color: 'cobalt', icon: 'music', pinned: true},
      {id: 'videos', name: 'Videos', kind: 'video', folder: folders.videos, layout: 'collection', naming: 'title', skipExisting: false, type: 'folder', color: 'violet', icon: 'video', pinned: true},
      // every library has Favorites (a kept space), from the start
      favoritesSpace(),
    ],
    routes: {music: 'music', other: 'downloads'},
    importTags: true,
  }
  return library
}

// Where a download goes by default (music routes, stale routes) is decided by the shared core (see gui/shared/core/spaces.ts).
export {defaultSpaceFor, isMusicSource} from './core/spaces.js'

/** Spaces that store files and can receive downloads. Smart spaces are never targets. */
export function folderSpaces(spaces: readonly Space[]): Space[] {
  return spaces.filter((space) => space.type !== 'smart')
}

export const builtinTones: Record<string, {color: SpaceColor; icon: SpaceIcon}> = {
  downloads: {color: 'slate', icon: 'folder'},
  music: {color: 'cobalt', icon: 'music'},
  videos: {color: 'violet', icon: 'video'},
}
const rotation: SpaceColor[] = ['teal', 'olive', 'amber', 'rust', 'berry', 'cobalt', 'violet', 'slate']

/** A space's color: its own, else the built-in tone, else one from the rotation by position among custom spaces. */
export function spaceColorOf(space: Pick<Space, 'id' | 'color'>, customIndex = 0): SpaceColor {
  return space.color ?? builtinTones[space.id]?.color ?? rotation[Math.abs(customIndex) % rotation.length]
}
export function spaceIconOf(space: Pick<Space, 'id' | 'icon' | 'kind'>): SpaceIcon {
  if (space.icon) return space.icon
  const tone = builtinTones[space.id]
  if (tone) return tone.icon
  return space.kind === 'music' ? 'music' : space.kind === 'video' ? 'video' : 'folder'
}

export {homeSpaces, isPinned, reorderSpaces, shiftId} from './core/spaces.js'

// What counts as a song (audio, or a video in a music space) is decided by the shared core, the same on both apps.
export {bothWords, playlistLabel, playlistWords, extrasCount, extrasLabel, isSongKind, isVideoSongKind, songWords, switchStart, musicVideoRows, musicVideoRowId, musicVideoItemId, musicVideoWords} from './core/spaces.js'
export type {MusicVideoRow} from './core/spaces.js'

export const HOME_SPACE_LIMIT = 5

// ---------------------------------------------------------------------------
// Media kinds
// ---------------------------------------------------------------------------

export const videoExtensions = ['mp4', 'm4v', 'mkv', 'webm', 'mov', 'avi', 'flv', 'ts', 'mpg', 'mpeg', 'wmv', '3gp'] as const
export const audioExtensions = ['mp3', 'm4a', 'aac', 'flac', 'ogg', 'oga', 'opus', 'wav', 'wma', 'aiff', 'aif', 'mka'] as const
export const imageExtensions = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'avif'] as const
export const subtitleExtensions = ['vtt', 'srt', 'ass', 'ssa'] as const

const extensionKinds = new Map<string, LibraryItemKind>([
  ...videoExtensions.map((ext) => [ext, 'video'] as const),
  ...audioExtensions.map((ext) => [ext, 'audio'] as const),
  ...imageExtensions.map((ext) => [ext, 'image'] as const),
])

// Where a space keeps its files in the Keepfin folder: decided in the shared core, the same on both apps.
export {defaultSpaceFolder, folderMoveProblem, isInFolder, movedPath} from './core/spaces.js'

/** The file types whose tags Edit info can rewrite. */
export const taggable = new Set(['mp3', 'm4a', 'flac', 'ogg', 'opus', 'mp4', 'm4v', 'mkv', 'webm', 'mov', 'oga'])
/** A file name or path → its extension without the dot, lower case ('' when it has none). */
export function extensionOf(file: string): string {
  const name = file.slice(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1)
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
}

/** Library kind for a file name by extension; undefined when it is not media Keepfin lists. */
export function mediaKindOf(file: string): LibraryItemKind | undefined {
  return extensionKinds.get(extensionOf(file))
}

/** Bytes hashed from each end of a file for the content fingerprint (imports and duplicate detection). */
export const HASH_WINDOW = 1024 * 1024

// ---------------------------------------------------------------------------
// Where a download goes and what it is called
// ---------------------------------------------------------------------------

// Sub-folders and file name stems are decided by the shared core (see gui/shared/core/providers.ts).
export {channelArtist, outputStem, outputSubfolders} from './core/providers.js'
export type {PathJob} from './core/providers.js'

// ---------------------------------------------------------------------------
// Recording identity: source ids and the same-recording test
// ---------------------------------------------------------------------------

// Recording identity (source ids, title and artist normalisation, the same-recording test) is decided by the shared core
// (gui/shared/core/dedupe.ts).
export {cleanIsrc, normalizeArtists, normalizeTitle, sameRecording, sourceIdFromUrl, versionsOf} from './core/dedupe.js'
export type {Recording} from './core/dedupe.js'

function fold(value: string): string {
  return value.normalize('NFKD').replace(/\p{M}+/gu, '').toLowerCase()
}

export type ExistingProbe = {sourceId?: string; isrc?: string; recording?: Recording}
export type Existing = Pick<LibraryItem, 'id' | 'path' | 'space' | 'sourceId' | 'title' | 'artist' | 'albumArtist' | 'album' | 'duration' | 'missing'> & {isrc?: string}

/**
 * Tiers 1 and 3 over a list of items, decided by the core. Tier 2 (content hash) is decided at import time, where the bytes are known.
 * The running app finds duplicates through LibraryStore.findExisting (indexed lookups with the same core tests).
 */
export function matchExisting<T extends Existing>(items: Iterable<T>, probe: ExistingProbe): {item: T; by: 'source' | 'isrc' | 'metadata'} | undefined {
  const usable = [...items].filter((item) => !item.missing)
  const candidate: Recording = {...probe.recording, title: probe.recording?.title ?? '', sourceIds: probe.sourceId ? [probe.sourceId] : [], isrc: probe.isrc ?? probe.recording?.isrc}
  const found = findRecording(
    candidate,
    usable.map((item) => ({artist: item.artist ?? item.albumArtist, title: item.title, album: item.album, duration: item.duration, sourceIds: item.sourceId ? [item.sourceId] : [], isrc: item.isrc})),
  )
  return found ? {item: usable[found.index], by: found.tier === 'source' ? 'source' : found.tier === 'isrc' ? 'isrc' : 'metadata'} : undefined
}

// ---------------------------------------------------------------------------
// Lyrics and playlists
// ---------------------------------------------------------------------------

const lrcStamp = /^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/

/** Parses LRC text. Lines without timestamps make an unsynced result; [offset:±ms] is applied. */
export function parseLrc(text: string): Lyrics {
  const offset = Number(/^\s*\[offset:\s*([+-]?\d+)\s*\]/im.exec(text)?.[1] ?? 0) / 1000
  const lines: {time?: number; text: string}[] = []
  let synced = false
  for (const raw of text.replace(/^﻿/, '').split(/\r?\n/)) {
    const line = raw.trim()
    if (!line) continue
    const times: number[] = []
    let rest = line
    for (let match = lrcStamp.exec(rest); match; match = lrcStamp.exec(rest)) {
      const fraction = match[3] ? Number(match[3]) / 10 ** match[3].length : 0
      times.push(Number(match[1]) * 60 + Number(match[2]) + fraction)
      rest = rest.slice(match[0].length)
    }
    if (times.length) {
      synced = true
      const spoken = rest.replace(/<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>/g, '').trim()
      for (const time of times) lines.push({time: Math.max(0, time + offset), text: spoken})
    } else if (!/^\[[a-z]{2,8}:[^\]]*\]$/i.test(line)) {
      lines.push({text: line})
    }
  }
  if (synced) {
    const timed = lines.filter((line) => line.time !== undefined)
    timed.sort((a, b) => a.time! - b.time!)
    return {synced: true, lines: timed}
  }
  return {synced: false, lines}
}

/** M3U8 text. Paths must already be relative to the playlist file (or absolute); separators are normalised to '/'. */
export function buildM3u8(name: string, entries: {path: string; title: string; artist?: string; duration?: number}[]): string {
  const lines = ['#EXTM3U', `#PLAYLIST:${name.replace(/[\r\n]+/g, ' ')}`]
  for (const entry of entries) {
    const label = (entry.artist ? `${entry.artist} - ${entry.title}` : entry.title).replace(/[\r\n]+/g, ' ')
    lines.push(`#EXTINF:${entry.duration === undefined ? -1 : Math.round(entry.duration)},${label}`, entry.path.replace(/\\/g, '/'))
  }
  return lines.join('\n') + '\n'
}

/**
 * The order after an Undo of "Remove from playlist": `now` (with any change made since) and each removed id back in front of the
 * first item that followed it in `before` and is still there (or at the end). The same as the phone's CollectionLogic.restoreOrder.
 */
export function restoreOrder(before: readonly string[], now: readonly string[], removed: ReadonlySet<string>): string[] {
  const kept = now.filter((id) => !removed.has(id))
  const present = new Set(kept)
  // each removed id is anchored to the next id after it that is still there ('' when none: the end)
  const anchored = new Map<string, string[]>()
  let anchor = ''
  for (let i = before.length - 1; i >= 0; i--) {
    const id = before[i]
    if (present.has(id)) anchor = id
    else if (removed.has(id)) {
      const group = anchored.get(anchor) ?? []
      group.push(id)
      anchored.set(anchor, group)
    }
  }
  // the groups were filled walking backwards
  const out: string[] = []
  for (const id of kept) {
    out.push(...(anchored.get(id) ?? []).reverse(), id)
  }
  out.push(...(anchored.get('') ?? []).reverse())
  return out
}

// ---------------------------------------------------------------------------
// Tags
// ---------------------------------------------------------------------------

export const MAX_TAG_LENGTH = 30
export {MAX_SOURCE_TAGS, normalizeTags, tagsInScope} from './core/spaces.js'

/** Genre (a list or a single value), tags and categories from inspected metadata, normalised together. */
export function collectSourceTags(info: {genres?: unknown; genre?: unknown; tags?: unknown; categories?: unknown}): string[] {
  return normalizeTags([info.genres, info.genre, info.tags, info.categories])
}

export type ScopeTag = {tag: string; count: number; user: boolean}

// ---------------------------------------------------------------------------
// Smart spaces
// ---------------------------------------------------------------------------

export type SmartContext = {
  now: number
  /** Ids of the collections an item belongs to. */
  collectionsOf: (itemId: string) => readonly string[]
}

export {describeSmartRules, matchesSmartRules, tagFilterName, tagFilterRules} from './core/spaces.js'
export {agoWords, collectionKindWords, collectionRowWords, everyWords, followLineWords, intervalLabel} from './core/spaces.js'

export function smartItems(rules: SmartRules, items: readonly LibraryItem[], context: SmartContext): LibraryItem[] {
  return items.filter((item) => matchesSmartRules(rules, item, context))
}

/** Map of item id → ids of the collections holding it, for SmartContext. */
export function collectionMembership(collections: readonly Pick<Collection, 'id' | 'itemIds'>[]): (itemId: string) => string[] {
  const map = new Map<string, string[]>()
  for (const collection of collections)
    for (const id of collection.itemIds) {
      const list = map.get(id)
      if (list) list.push(collection.id)
      else map.set(id, [collection.id])
    }
  return (itemId) => map.get(itemId) ?? []
}

// ---------------------------------------------------------------------------
// Collections: source links, following, ordering
// ---------------------------------------------------------------------------

// Source links, kinds, follow defaults and new-item detection are decided by the shared core (see gui/shared/core/spaces.ts).
export {canonicalSourceUrl, collectionKindOf, defaultFollow, entryKey, gateNew, isInfiniteSource, newEntries, removedFromSource, sortBySourceOrder, sourceIdOfCollection} from './core/spaces.js'

export const followIntervals = [1, 6, 12, 24, 168] as const

export {collectionFromPlaylist} from './core/spaces.js'

/** The items of a collection in its order; ids no longer in the library are skipped. */
export function collectionItems(collection: Pick<Collection, 'itemIds'>, byId: ReadonlyMap<string, LibraryItem> | ((id: string) => LibraryItem | undefined)): LibraryItem[] {
  const get = typeof byId === 'function' ? byId : (id: string) => byId.get(id)
  return collection.itemIds.map(get).filter((item): item is LibraryItem => !!item)
}

/** Result of collectionCheck for one collection. */
export type FollowCheckResult = {
  id: string
  /** Items queued for download. */
  added: number
  /** More than the confirm limit were found; waiting for collectionReview. */
  pending?: number
  /** Items that left the source and stay here. */
  removed: number
  error?: string
  /** The sign-in changed while checking; nothing was recorded and the next check starts over. */
  skipped?: boolean
}

// ---------------------------------------------------------------------------
// The state of downloaded files
// ---------------------------------------------------------------------------

/** What a finished download's file looks like now. Driven by the library's state, never by polling the disk. */
export type FileState = 'present' | 'deleted'

export function jobFileState(job: {status: string; file?: string; existing?: unknown}, items: Pick<LibraryItem, 'missing'>[]): FileState {
  if (job.status !== 'completed' || job.existing) return 'present'
  // The library deleted the file (history keeps the entry without a path), or flagged it missing from its folder.
  if (!job.file) return 'deleted'
  return items.length > 0 && items.every((item) => item.missing) ? 'deleted' : 'present'
}

/** "Deleted", "2 of 5 files removed" or nothing, for a collection's members. */
export function removedSummary(removed: number, total: number): string {
  if (!removed || !total) return ''
  return removed >= total ? 'Deleted' : `${removed} of ${total} files removed`
}

/** Where a library entry shows when it has no file on this computer (a joined library's items): a path that never exists. */
export const notOnThisPc = (item: Pick<LibraryItem, 'missing' | 'path'>): boolean => item.missing === true && /[\\/]\.keepfin[\\/]unplaced[\\/][^\\/]+$/.test(item.path)

/** What the Details dialog shows about a file's contents, read from the file itself (the codecs as the converter names them, the bitrate in kbit/s). */
export type FileDetails = {
  /** The file's extension in capitals: MP4, MKV, M4A. */
  container?: string
  /** Pixels as people see the picture (a sideways phone video is turned). */
  width?: number
  height?: number
  fps?: number
  /** Seconds. */
  duration?: number
  videoCodec?: string
  audioCodec?: string
  /** The whole file's bitrate in kbit/s. */
  bitrate?: number
}