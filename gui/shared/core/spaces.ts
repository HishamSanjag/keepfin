// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the spaces logic the desktop code and the renderer already use: smart rules, tags, new-item detection, the confirm gate,
// collection sources, kinds and order, follow defaults and the due check, and the hub (home, pins, reorder, default space).
// This is the only file that imports the core's spaces package: gui/shared/library.ts re-exports from here, so call sites keep
// their imports. The core decides; time is always passed in.
import {
  JsRuleSet,
  JsSmartItem,
  JsSmartRule,
  JsSpace,
  JsTaggedItem,
  spacesCanonicalSource,
  spacesAgo,
  spacesCollectionKind,
  spacesDescribeRules,
  spacesEveryWords,
  spacesFollowLine,
  spacesIntervalLabel,
  spacesRowSubtitle,
  spacesWhereAgo,
  spacesWhereHolds,
  spacesWhereItemLine,
  spacesWhereOnePlace,
  spacesWhereSingle,
  spacesWhereState,
  spacesTagFilterName,
  spacesTagFilterRules,
  spacesCanonicalUrl,
  spacesDefaultFollow,
  spacesDefaultSpace,
  spacesFromPlaylist,
  spacesGate,
  spacesHome,
  spacesIsDue,
  spacesIsInfinite,
  spacesIsMusicSource,
  spacesIsPinned,
  spacesKeptIds,
  spacesIsKept,
  spacesMissingKept,
  spacesFavoritesLook,
  spacesDefaultFolder,
  spacesIsInFolder,
  spacesMovedPath,
  spacesFolderMoveProblem,
  spacesKeepfinFolderFor,
  spacesKeepfinInside,
  spacesIsSong,
  spacesMusicVideoItemId,
  spacesMusicVideoRowId,
  spacesMusicVideoRows,
  spacesMusicVideoWords,
  spacesPreviewWords,
  spacesExtrasCount,
  spacesExtrasLabel,
  spacesPlaylistLabel,
  spacesPlaylistWords,
  spacesSwitchStart,
  JsVideoSource,
  spacesIsVideoSong,
  spacesSongWords,
  spacesItemKey,
  spacesKindOf,
  spacesNewIndexes,
  spacesNormalizeTags,
  spacesRemovedFromSource,
  spacesMove,
  spacesReorder,
  spacesSmartMatches,
  spacesSortBySourceOrder,
  spacesTagsInScope,
} from '../../../build/core-js/keepfin-core.mjs'
import type {Collection, CollectionFollow, CollectionKind, LibraryItem, LibrarySettings, ScopeTag, SmartContext, SmartRule, SmartRules, Space} from '../library.js'

// ---- smart spaces ----------------------------------------------------------------------------------------------

const compiled = new WeakMap<SmartRules, JsRuleSet>()

function compile(rules: SmartRules): JsRuleSet {
  let known = compiled.get(rules)
  if (!known) {
    known = new JsRuleSet(
      rules.match === 'all',
      rules.rules.map((rule) =>
        rule.field === 'addedWithin'
          ? new JsSmartRule(rule.field, [], Number(rule.days))
          : 'values' in rule
            ? new JsSmartRule(rule.field, (rule.values ?? []).map(String), 0)
            : new JsSmartRule(rule.field, [], 0),
      ),
      rules.onlyKind ?? null,
      rules.excludeSpaces ?? [],
    )
    compiled.set(rules, known)
  }
  return known
}

/** One item against a smart space's rules. No rules gather nothing. `onlyKind` and `excludeSpaces` apply after the rules. */
export function matchesSmartRules(rules: SmartRules, item: LibraryItem, context: SmartContext): boolean {
  return spacesSmartMatches(
    compile(rules),
    new JsSmartItem(
      item.id, item.space, item.kind, item.tags ?? [], item.userTags ?? [], item.artist ?? null, item.albumArtist ?? null, item.album ?? null,
      item.favorite === true, item.plays ?? 0, item.addedAt, item.sourceUrl ?? null, item.sourceId ?? null,
    ),
    context.now,
    [...context.collectionsOf(item.id)],
  )
}

/** Plain words for a smart space's rules: "Tag is lofi or chill and Favorite · only songs". [nameOf] gives the name of a collection or space id. */
export function describeSmartRules(rules: SmartRules, nameOf: (field: string, value: string) => string | undefined): string {
  return spacesDescribeRules(compile(rules), (field: string, value: string) => nameOf(field, value) ?? null)
}

/** The rules "Save as smart space" makes from a tag filter: tags in A to Z order; with a space, every rule must match. */
export function tagFilterRules(tags: readonly string[], mode: 'any' | 'all', spaceId?: string): SmartRules {
  const set = spacesTagFilterRules([...tags], mode === 'all', spaceId ?? null)
  return {
    match: set.matchAll ? 'all' : 'any',
    rules: [...set.rules].map((rule) => ({field: rule.field, values: [...rule.values]}) as SmartRule),
  }
}

/** The name suggested for it: "Arabic + Pop". */
export const tagFilterName = (tags: readonly string[]): string => spacesTagFilterName([...tags])

// ---- collection words --------------------------------------------------------------------------------------------

/** The date written for a time a week old or older. */
const dateWords = (time: number): string => new Date(time).toLocaleDateString(undefined, {dateStyle: 'medium'})

/** "Playlist", "Album", "Channel", "Yours". */
export const collectionKindWords = (kind: string): string => spacesCollectionKind(kind)

/** "every hour", "every 6 hours". */
export const everyWords = (hours: number): string => spacesEveryWords(hours)

/** "1 hour", "7 days": an interval as a choice. */
export const intervalLabel = (hours: number): string => spacesIntervalLabel(hours)

/** "12 min ago", "3 h ago", "yesterday", or the date from a week on. */
export const agoWords = (timestamp: number, now = Date.now(), date = dateWords): string => spacesAgo(timestamp, now, date)

/** "Following · every 6 hours", "Checks when you click Check now", "Not following"; undefined for a collection that cannot be followed. */
export function followLineWords(collection: Pick<Collection, 'kind' | 'source' | 'follow'>, press = 'click'): string | undefined {
  return (
    spacesFollowLine(collection.kind, collection.source?.url ?? null, collection.follow?.mode ?? null, collection.follow?.intervalHours ?? 0, press) ?? undefined
  )
}

/** "Playlist · 31 songs · following, checked 12 min ago", then any [extras]. */
export function collectionRowWords(
  collection: Pick<Collection, 'kind' | 'source' | 'follow'>,
  noun: string,
  present: number,
  extras: readonly string[] = [],
  now = Date.now(),
  date = dateWords,
): string {
  return spacesRowSubtitle(
    collection.kind, collection.source?.url ?? null, noun, present, collection.follow?.mode ?? null, collection.follow?.lastCheckedAt ?? null, now, date, [...extras],
  )
}

// ---- "Where your files are" words --------------------------------------------------------------------------------

/** "just now", "2 min ago", "3 h ago", "yesterday", "3 weeks ago", "4 months ago". */
export const whereAgo = (at: number, now: number): string => spacesWhereAgo(at, now)

export type WhereHoldsInput = {all: boolean; spaces: readonly string[]; playlists: readonly string[]; favourites: boolean; files?: {held: number; of: number}}

/** "Everything", "Music, Favourites", "Road trip and 2 more", "40 of 1,200 files". */
export const whereHolds = (holds: WhereHoldsInput): string =>
  spacesWhereHolds(holds.all, [...holds.spaces], holds.favourites, [...holds.playlists], holds.files?.held ?? null, holds.files?.of ?? null)

export type WhereStateInput = {state: string; at?: number; done?: number; total?: number; reason?: string; unplugged?: boolean; count?: number}

/** "up to date 2 min ago", "copying · 40 of 210", "offline · last seen 5 min ago". [toCopy]: the place is a copy, not a device. */
export const whereState = (status: WhereStateInput, toCopy: boolean, now: number): string =>
  spacesWhereState(status.state, status.at ?? null, status.done ?? 0, status.total ?? 0, status.reason ?? null, status.unplugged ?? false, status.count ?? 0, toCopy, now)

/** "12 videos are only on Desk PC". [kind] is songs, videos or other. */
export const whereSingle = (kind: string, count: number, place: string): string => spacesWhereSingle(kind, count, place)

/** "Everything is on this PC only. Keep a copy on another device, a drive or the cloud." */
export const whereOnePlace = (thisName: string): string => spacesWhereOnePlace(thisName)

/** "On: this PC, Pixel 9 (offline)", "Only on this PC", "Not on any device yet". */
export const whereItemLine = (places: readonly {name: string; offline: boolean}[]): string =>
  spacesWhereItemLine(places.map((place) => place.name), places.map((place) => place.offline))

// ---- tags ------------------------------------------------------------------------------------------------------

export const MAX_SOURCE_TAGS = 15

/**
 * Lower case, trimmed, whitespace-collapsed, deduplicated tags. Empty values, links and anything over
 * 30 characters are dropped; at most `limit` (15 by default) are kept in their original order.
 */
export function normalizeTags(raw: unknown, limit = MAX_SOURCE_TAGS): string[] {
  const parts: (string | null)[] = []
  const take = (value: unknown) => {
    if (Array.isArray(value)) for (const item of value) take(item)
    else if (typeof value === 'string') parts.push(value)
  }
  take(raw)
  return spacesNormalizeTags(parts, limit)
}

/** Tags worth a chip in a scope: those on at least `minItems` items, plus every user tag. Most used first. */
export function tagsInScope(items: readonly Pick<LibraryItem, 'tags' | 'userTags'>[], minItems = 2): ScopeTag[] {
  return spacesTagsInScope(items.map((item) => new JsTaggedItem(item.tags ?? [], item.userTags ?? [])), minItems).map((entry) => ({
    tag: entry.tag,
    count: entry.count,
    user: entry.user,
  }))
}

// ---- collections -----------------------------------------------------------------------------------------------

/** One link per source: playlist ids on YouTube, channel handles, music service links; tracking removed. */
export const canonicalSourceUrl = (url: string): string => spacesCanonicalUrl(url)

/** The id of the playlist, channel or album a source link points to, when it names one. */
export const sourceIdOfCollection = (url: string): string | undefined => spacesCanonicalSource(url).id ?? undefined

/** Radio mixes never end, so they are bounded by paging and are never followed. */
export const isInfiniteSource = (url: string): boolean => spacesIsInfinite(url)

export const collectionKindOf = (url: string): Exclude<CollectionKind, 'own'> => spacesKindOf(url) as Exclude<CollectionKind, 'own'>

/**
 * Following a new collection is opt-in: it is off unless the user turned on "Keep in sync" when downloading, and then
 * only for playlists and channels. Albums, radio mixes and own lists never follow.
 */
export function defaultFollow(kind: CollectionKind, url?: string, keepInSyncHours?: CollectionFollow['intervalHours']): CollectionFollow {
  const follow = spacesDefaultFollow(kind, url ?? null, keepInSyncHours ?? null)
  return {
    mode: follow.mode as CollectionFollow['mode'],
    intervalHours: follow.intervalHours as CollectionFollow['intervalHours'],
    wifiOnly: follow.wifiOnly,
    notify: follow.notify,
    confirmAbove: follow.confirmAbove,
  }
}

/** Whether a collection should be checked now by the schedule. */
export function isDue(collection: Collection, now: number): boolean {
  const follow = collection.follow
  return spacesIsDue(
    collection.kind,
    collection.source?.url ?? null,
    follow?.mode ?? null,
    follow?.intervalHours ?? 6,
    follow?.wifiOnly ?? true,
    follow?.lastCheckedAt ?? null,
    !!follow?.pending,
    now,
  )
}

/** The identity of a discovered entry across checks: its source id, else its link. */
export const entryKey = (url: string): string => spacesItemKey(url)

/** Entries whose ids are not in `seen`, once each and in source order. */
export function newEntries<T extends {url: string}>(entries: readonly T[], seen: Iterable<string>): T[] {
  return [...spacesNewIndexes(entries.map((entry) => entry.url), [...seen])].map((index) => entries[index])
}

/** What to do with newly found items: nothing, download them, or wait for the user (more than `confirmAbove`). */
export const gateNew = (count: number, confirmAbove: number, approved = false): 'none' | 'enqueue' | 'confirm' =>
  spacesGate(count, confirmAbove, approved) as 'none' | 'enqueue' | 'confirm'

/** Members whose source id is no longer in the discovered listing. Members without a source id are never judged. */
export function removedFromSource(members: readonly {id: string; sourceId?: string}[], discovered: ReadonlySet<string>): string[] {
  return [...spacesRemovedFromSource(members.map((member) => member.id), members.map((member) => member.sourceId ?? null), [...discovered])]
}

/** Orders item ids by their place in the source; items without one keep their order, after the rest. */
export function sortBySourceOrder(ids: readonly string[], indexOf: (id: string) => number | undefined): string[] {
  return [...spacesSortBySourceOrder([...ids], ids.map((id) => indexOf(id) ?? null))]
}

/** A user playlist from before collections, as a collection of kind 'own'. */
export function collectionFromPlaylist(playlist: {id: string; name: string; itemIds: string[]; createdAt?: number; updatedAt?: number}, space: string): Collection {
  const own = spacesFromPlaylist(playlist.id, playlist.name, playlist.itemIds, playlist.createdAt ?? 0, playlist.updatedAt ?? 0, space)
  return {
    id: own.id,
    name: own.name,
    kind: 'own',
    space: own.space,
    itemIds: [...own.itemIds],
    seen: [...own.seen],
    createdAt: own.createdAt,
    updatedAt: own.updatedAt,
  }
}

// ---- the hub ---------------------------------------------------------------------------------------------------

const toJsSpaces = (spaces: readonly Pick<Space, 'id' | 'type' | 'pinned'>[]) => spaces.map((space) => new JsSpace(space.id, space.type, space.pinned ?? null))

/** Whether a space shows on home: an explicit pin, else the built-ins. */
export const isPinned = (space: Pick<Space, 'id' | 'pinned'>): boolean => spacesIsPinned(space.id, space.pinned ?? null)

/** Kept spaces: Downloads, Music, Videos and Favorites. Every library has them; they can be renamed but never removed. */
export const keptSpaceIds = (): string[] => [...spacesKeptIds()]
export const isKeptSpace = (id: string): boolean => spacesIsKept(id)
/** The kept space ids a list of space ids lacks. */
export const missingKeptSpaceIds = (ids: readonly string[]): string[] => [...spacesMissingKept([...ids])]
/** Favorites' name, color and icon. */
export const favoritesLook = (): {name: string; color: string; icon: string} => {
  const [name, color, icon] = spacesFavoritesLook()
  return {name, color, icon}
}

/** Where a space's files go by default: inside the Keepfin folder, Downloads/Music/Videos for the built-in spaces, otherwise its name. */
export const defaultSpaceFolder = (root: string, space: Pick<Space, 'id' | 'name'>): string => spacesDefaultFolder(root, space.id, space.name)
/** Whether `file` is `folder` itself or inside it (separators and letter case do not matter). */
export const isInFolder = (folder: string, file: string): boolean => spacesIsInFolder(folder, file)
/** Where `file` is after the folder `from` moved to `to`; undefined when it was not in `from`. */
export const movedPath = (from: string, to: string, file: string): string | undefined => spacesMovedPath(from, to, file) ?? undefined
/** `<parent>\Keepfin`, with the parent's own separator. */
export const keepfinInside = (parent: string): string => spacesKeepfinInside(parent)

// ---- songs (core/.../spaces/Songs.kt) ----

/** Audio anywhere, or a video in a music space: listed with the songs and played as its sound. */
export const isSongKind = (kind: string, spaceKind: string | undefined): boolean => spacesIsSong(kind, spaceKind ?? null)
/** A song that is a video file: the Video tag and Watch video. */
export const isVideoSongKind = (kind: string, spaceKind: string | undefined): boolean => spacesIsVideoSong(kind, spaceKind ?? null)
const [videoTag, watchVideo] = spacesSongWords()
export const songWords = {videoTag, watchVideo} as const

/** Where the other side of Song | Video starts: the same second, landing 1 s before the other side's end when it is shorter (seconds). */
export const switchStart = (position: number, otherLength: number | undefined): number => spacesSwitchStart(position, otherLength ?? null)

/** A song's music video as a row of its own (the file stays the song's companion). `id` is `<itemId>#video`; the cover is the song's. */
export type MusicVideoRow = {id: string; itemId: string; title: string; artist?: string; duration?: number; size?: number; addedAt: number}

/** What a row reads of a library item (the desktop item: `musicVideo`, `videoDuration`, `videoSize`). */
export type VideoSourceItem = {id: string; kind: string; title: string; artist?: string; musicVideo?: boolean; videoDuration?: number; videoSize?: number; addedAt: number}

/** The music video rows of [items], newest first; none when no song has a video. */
export const musicVideoRows = (items: readonly VideoSourceItem[]): MusicVideoRow[] =>
  spacesMusicVideoRows(
    items.filter((item) => item.musicVideo).map((item) => new JsVideoSource(item.id, item.kind, item.title, item.artist ?? null, true, item.videoDuration ?? null, item.videoSize ?? null, item.addedAt)),
  ).map((row) => ({id: row.id, itemId: row.itemId, title: row.title, artist: row.artist ?? undefined, duration: row.duration ?? undefined, size: row.size ?? undefined, addedAt: row.addedAt}))
export const musicVideoRowId = (itemId: string): string => spacesMusicVideoRowId(itemId)
/** The song behind a video row id, undefined when the id is a song's own. */
export const musicVideoItemId = (rowId: string): string | undefined => spacesMusicVideoItemId(rowId) ?? undefined
const [rowLabel, rowShelf, rowSeeAll, rowGoToSong, rowDelete, rowDeleteAsk, rowJoinNote, rowHasVideoNote, rowKeepLink, rowHasVideoError] = spacesMusicVideoWords()
/** The download preview's Both words, the same on the phone (`PreviewWords`). */
const [bothLooking, bothNone, bothFound, bothLink] = spacesPreviewWords()
export const bothWords = {looking: bothLooking, none: bothNone, found: bothFound, link: bothLink} as const
/** The review's "Add to playlist" menu words (add, none, new), the same on the phone, and the button's label. */
const [playlistAdd, playlistNone, playlistNew] = spacesPlaylistWords()
export const playlistWords = {add: playlistAdd, none: playlistNone, new: playlistNew} as const
export const playlistLabel = (name?: string): string => spacesPlaylistLabel(name ?? null)
/** Which groups on the Extras tab differ from the person's defaults; the core counts them. */
export type ExtrasChanges = {subtitles: boolean; cut: boolean; sponsor: boolean; chapters: boolean; inFile: boolean; beside: boolean; queue: boolean}
export const extrasCount = (c: ExtrasChanges): number => spacesExtrasCount(c.subtitles, c.cut, c.sponsor, c.chapters, c.inFile, c.beside, c.queue)
export const extrasLabel = (changed: number): string => spacesExtrasLabel(changed)
export const musicVideoWords = {label: rowLabel, shelf: rowShelf, seeAll: rowSeeAll, goToSong: rowGoToSong, delete: rowDelete, deleteAsk: rowDeleteAsk, joinNote: rowJoinNote, hasVideoNote: rowHasVideoNote, keepLink: rowKeepLink, hasVideoError: rowHasVideoError} as const
/** The Keepfin folder for a picked folder: one named Keepfin (or already a Keepfin folder) as it is, any other gets Keepfin inside. */
export const keepfinFolderFor = (picked: string, isKeepfinFolder: boolean): string => spacesKeepfinFolderFor(picked, isKeepfinFolder)
/** Why the Keepfin folder cannot move from `from` to `to` by where the two are; undefined when it can. */
export const folderMoveProblem = (from: string, to: string): string | undefined => spacesFolderMoveProblem(from, to) ?? undefined

/** Spaces on home: the pinned ones in settings order (at most five); with no pins, the first five. `more` counts the rest. */
export function homeSpaces(spaces: readonly Space[]): {shown: Space[]; more: number} {
  const byId = new Map(spaces.map((space) => [space.id, space]))
  const shown = [...spacesHome(toJsSpaces(spaces))].map((id) => byId.get(id)!)
  return {shown, more: spaces.length - shown.length}
}

/** Reorders spaces to the given id order. Every id must exist once; spaces the list leaves out keep their order at the end. */
/** `ids` with `id` moved one place up (-1) or down (+1); unchanged at the ends. */
export const shiftId = (ids: readonly string[], id: string, delta: -1 | 1): string[] => [...spacesMove([...ids], id, delta > 0)]

export function reorderSpaces(spaces: readonly Space[], ids: readonly string[]): Space[] {
  const order = spacesReorder(toJsSpaces(spaces), [...ids])
  if (!order) throw new Error('That list of spaces is out of date. Refresh and try again.')
  const byId = new Map(spaces.map((space) => [space.id, space]))
  return [...order].map((id) => byId.get(id)!)
}

/** Links from music services and YouTube Music are music by source, whatever format is chosen. */
export const isMusicSource = (url: string): boolean => spacesIsMusicSource(url)

/** The space a download goes to when the user doesn't pick one. Falls back to the first folder space if a route is stale. */
export const defaultSpaceFor = (url: string, library: Pick<LibrarySettings, 'routes' | 'spaces'>): string =>
  spacesDefaultSpace(url, library.routes.music, library.routes.other, toJsSpaces(library.spaces))
