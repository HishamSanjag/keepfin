@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.spaces

// The JavaScript surface of the spaces core (see gui/shared/core/spaces.ts). Times arrive as numbers (milliseconds),
// lists as arrays, and "no value" as null.

@JsExport
class JsSmartItem(
    val id: String,
    val space: String,
    val kind: String,
    val tags: Array<String>,
    val userTags: Array<String>,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val favorite: Boolean,
    val plays: Double,
    val addedAt: Double,
    val sourceUrl: String?,
    val sourceId: String?,
)

@JsExport
class JsSmartRule(val field: String, val values: Array<String>, val days: Double)

@JsExport
class JsRuleSet(val matchAll: Boolean, val rules: Array<JsSmartRule>, val onlyKind: String?, val excludeSpaces: Array<String>)

@JsExport
class JsScopeTag(val tag: String, val count: Int, val user: Boolean)

@JsExport
class JsTaggedItem(val tags: Array<String>, val userTags: Array<String>)

@JsExport
class JsCollectionSource(val url: String, val id: String?)

@JsExport
class JsFollow(val mode: String, val intervalHours: Int, val wifiOnly: Boolean, val notify: Boolean, val confirmAbove: Int)

@JsExport
class JsSpace(val id: String, val type: String, val pinned: Boolean?)

@JsExport
class JsOwnCollection(
    val id: String,
    val name: String,
    val kind: String,
    val space: String,
    val itemIds: Array<String>,
    val seen: Array<String>,
    val createdAt: Double,
    val updatedAt: Double,
)

private fun JsRuleSet.toCore() = RuleSet(
    matchAll, rules.map { SmartRule(it.field, it.values.toList(), it.days) }, onlyKind, excludeSpaces.toList(),
)

private fun JsSmartItem.toCore() = LibraryItem(
    id, space, kind, tags.toList(), userTags.toList(), artist, albumArtist, album, favorite,
    if (plays > 0) plays.toInt() else 0, addedAt.toLong(), sourceUrl, sourceId,
)

private fun JsSpace.toCore() = Space(id, type, pinned)

// ---- smart rules and tags ----------------------------------------------------------------------------------------

/** [collections] are the ids of the collections that hold the item. */
@JsExport
fun spacesSmartMatches(rules: JsRuleSet, item: JsSmartItem, now: Double, collections: Array<String>): Boolean =
    SmartRules.matches(rules.toCore(), item.toCore(), now.toLong()) { collections.toList() }

@JsExport
fun spacesNormalizeTags(raw: Array<String?>, limit: Int): Array<String> = Tags.normalize(raw.toList(), limit).toTypedArray()

@JsExport
fun spacesTagsInScope(items: Array<JsTaggedItem>, minItems: Int): Array<JsScopeTag> =
    Tags.inScope(items.map { TaggedItem(it.tags.toList(), it.userTags.toList()) }, minItems).map { JsScopeTag(it.tag, it.count, it.user) }.toTypedArray()

// ---- words ---------------------------------------------------------------------------------------------------------

/** "Tag is lofi or chill". [nameOf] gives the name of a collection or space id (field, value), null for none. */
@JsExport
fun spacesDescribeRules(rules: JsRuleSet, nameOf: (String, String) -> String?): String = RuleWords.describe(rules.toCore(), nameOf)

/** The rules "Save as smart space" makes from a tag filter (see RuleWords.tagFilterRules). */
@JsExport
fun spacesTagFilterRules(tags: Array<String>, matchAll: Boolean, space: String?): JsRuleSet =
    RuleWords.tagFilterRules(tags.toList(), matchAll, space).let { set ->
        JsRuleSet(set.matchAll, set.rules.map { JsSmartRule(it.field, it.values.toTypedArray(), it.days) }.toTypedArray(), set.onlyKind, set.excludeSpaces.toTypedArray())
    }

@JsExport
fun spacesTagFilterName(tags: Array<String>): String = RuleWords.tagFilterName(tags.toList())

// ---- collections -------------------------------------------------------------------------------------------------

@JsExport
fun spacesCanonicalSource(url: String): JsCollectionSource = Collections.canonicalSource(url).let { JsCollectionSource(it.url, it.id) }

@JsExport
fun spacesCanonicalUrl(url: String): String = Collections.canonicalUrl(url)

@JsExport
fun spacesIsInfinite(url: String): Boolean = Collections.isInfinite(url)

@JsExport
fun spacesKindOf(url: String): String = Collections.kindOf(url)

@JsExport
fun spacesDefaultFollow(kind: String, url: String?, keepInSyncHours: Int?): JsFollow =
    Collections.defaultFollow(kind, url, keepInSyncHours).let { JsFollow(it.mode, it.intervalHours, it.wifiOnly, it.notify, it.confirmAbove) }

/** [mode] is null when the collection has no follow settings. */
@JsExport
fun spacesIsDue(kind: String, sourceUrl: String?, mode: String?, intervalHours: Int, wifiOnly: Boolean, lastCheckedAt: Double?, pending: Boolean, now: Double): Boolean {
    val follow = if (mode == null) null else Follow(mode, intervalHours, wifiOnly, true, 0, lastCheckedAt?.toLong(), pending)
    return Collections.isDue(LibraryCollection("", "", kind, "", emptyList(), emptyList(), sourceUrl, follow), now.toLong())
}

@JsExport
fun spacesItemKey(url: String): String = Collections.itemKey(url)

/** Positions in [urls] of the entries [seen] does not know, once each, in source order. */
@JsExport
fun spacesNewIndexes(urls: Array<String>, seen: Array<String>): Array<Int> = Collections.newIndexes(urls.toList(), seen.toList()).toTypedArray()

/** "none", "enqueue" or "confirm". */
@JsExport
fun spacesGate(count: Int, confirmAbove: Int, approved: Boolean): String = Collections.gate(count, confirmAbove, approved).wire

/** [sourceIds] runs parallel to [ids]; null for a member without a source id. */
@JsExport
fun spacesRemovedFromSource(ids: Array<String>, sourceIds: Array<String?>, present: Array<String>): Array<String> {
    val byId = HashMap<String, List<String>>()
    ids.forEachIndexed { i, id -> byId[id] = listOfNotNull(sourceIds[i]) }
    return Collections.removedFromSource(ids.toList(), { byId[it] ?: emptyList() }, present.toSet()).toTypedArray()
}

/** [positions] runs parallel to [ids]; null for an item without a place in the source. Returns the ids in order. */
@JsExport
fun spacesSortBySourceOrder(ids: Array<String>, positions: Array<Double?>): Array<String> {
    val byIndex = HashMap<String, Int>()
    ids.forEachIndexed { i, id -> positions[i]?.let { byIndex[id] = it.toInt() } }
    return Collections.sortBySourceOrder(ids.toList()) { byIndex[it] }.toTypedArray()
}

@JsExport
fun spacesFromPlaylist(id: String, name: String, itemIds: Array<String>, createdAt: Double, updatedAt: Double, space: String): JsOwnCollection =
    Collections.fromPlaylist(id, name, itemIds.toList(), createdAt.toLong(), updatedAt.toLong(), space).let {
        JsOwnCollection(it.id, it.name, it.kind, it.space, it.itemIds.toTypedArray(), it.seen.toTypedArray(), it.createdAt.toDouble(), it.updatedAt.toDouble())
    }

// ---- the hub -----------------------------------------------------------------------------------------------------

/** The ids shown on home, in order. */
@JsExport
fun spacesHome(spaces: Array<JsSpace>): Array<String> = Spaces.home(spaces.map { it.toCore() }).map { it.id }.toTypedArray()

/** The ids with one moved a place later or earlier. */
@JsExport
fun spacesMove(ids: Array<String>, id: String, forward: Boolean): Array<String> = Spaces.move(ids.toList(), id, forward).toTypedArray()

/** The new hub order, or null when an id is unknown or repeated. */
@JsExport
fun spacesReorder(spaces: Array<JsSpace>, ids: Array<String>): Array<String>? =
    Spaces.reorder(spaces.map { it.toCore() }, ids.toList())?.map { it.id }?.toTypedArray()

@JsExport
fun spacesIsMusicSource(url: String): Boolean = Spaces.isMusicSource(url)

@JsExport
fun spacesDefaultSpace(url: String, musicRoute: String, otherRoute: String, spaces: Array<JsSpace>): String =
    Spaces.defaultSpace(url, musicRoute, otherRoute, spaces.map { it.toCore() })

@JsExport
fun spacesIsPinned(id: String, pinned: Boolean?): Boolean = Spaces.isPinned(Space(id, "folder", pinned))

/** The kept spaces: Downloads, Music, Videos and Favorites. */
@JsExport
fun spacesKeptIds(): Array<String> = Spaces.KEPT_IDS.toTypedArray()

@JsExport
fun spacesIsKept(id: String): Boolean = Spaces.isKept(id)

/** The kept space ids that [ids] does not hold, in kept order. */
@JsExport
fun spacesMissingKept(ids: Array<String>): Array<String> = Spaces.missingKept(ids.toList()).toTypedArray()

/** Favorites' [name, color, icon]. */
@JsExport
fun spacesFavoritesLook(): Array<String> = arrayOf(Spaces.FAVORITES_NAME, Spaces.FAVORITES_COLOR, Spaces.FAVORITES_ICON)

@JsExport
fun spacesDefaultFolder(root: String, id: String, name: String): String = defaultSpaceFolder(root, id, name)

@JsExport
fun spacesIsInFolder(folder: String, file: String): Boolean = isInFolder(folder, file)

@JsExport
fun spacesMovedPath(from: String, to: String, file: String): String? = movedPath(from, to, file)

@JsExport
fun spacesFolderMoveProblem(from: String, to: String): String? = folderMoveProblem(from, to)

@JsExport
fun spacesKeepfinFolderFor(picked: String, isKeepfinFolder: Boolean): String = keepfinFolderFor(picked, isKeepfinFolder)

@JsExport
fun spacesKeepfinInside(parent: String): String = keepfinInside(parent)

/** Audio anywhere, or a video in a music space (Songs.kt). */
@JsExport
fun spacesIsSong(kind: String, spaceKind: String?): Boolean = Songs.isSong(kind, spaceKind)

@JsExport
fun spacesIsVideoSong(kind: String, spaceKind: String?): Boolean = Songs.isVideoSong(kind, spaceKind)

/** [the Video tag, the Watch video action]. */
@JsExport
fun spacesSongWords(): Array<String> = arrayOf(Songs.VIDEO_TAG, Songs.WATCH)

// ---- collection and "Where your files are" words -----------------------------------------------------------------

/** The date of a time from a week on is written by the screen, which knows the locale. */
@JsExport
fun spacesCollectionKind(kind: String): String = CollectionWords.kind(kind)

@JsExport
fun spacesEveryWords(hours: Int): String = CollectionWords.everyWords(hours)

@JsExport
fun spacesIntervalLabel(hours: Int): String = CollectionWords.interval(hours)

@JsExport
fun spacesFollowLine(kind: String, sourceUrl: String?, mode: String?, intervalHours: Int, press: String): String? =
    CollectionWords.followLine(kind, sourceUrl, mode, intervalHours, press)

@JsExport
fun spacesAgo(timestamp: Double, now: Double, date: (Double) -> String): String = CollectionWords.ago(timestamp.toLong(), now.toLong()) { date(it.toDouble()) }

@JsExport
fun spacesRowSubtitle(
    kind: String, sourceUrl: String?, noun: String, present: Int, mode: String?, lastCheckedAt: Double?, now: Double, date: (Double) -> String,
    extras: Array<String>,
): String = CollectionWords.rowSubtitle(kind, sourceUrl, noun, present, mode, lastCheckedAt?.toLong(), now.toLong(), { date(it.toDouble()) }, extras.toList())

@JsExport
fun spacesWhereAgo(at: Double, now: Double): String = WhereWords.ago(at.toLong(), now.toLong())

/** [held] and [of] are the files a device keeps of the library, or null when the place has a chosen selection. */
@JsExport
fun spacesWhereHolds(all: Boolean, spaces: Array<String>, favourites: Boolean, playlists: Array<String>, held: Double?, of: Double?): String =
    WhereWords.holds(all, spaces.toList(), favourites, playlists.toList(), held?.toLong(), of?.toLong())

@JsExport
fun spacesWhereState(state: String, at: Double?, done: Int, total: Int, reason: String?, unplugged: Boolean, count: Int, toCopy: Boolean, now: Double): String =
    WhereWords.state(PlaceStatus(state, at?.toLong(), done, total, reason, unplugged, count), toCopy, now.toLong())

@JsExport
fun spacesWhereSingle(kind: String, count: Double, place: String): String = WhereWords.single(kind, count.toLong(), place)

@JsExport
fun spacesWhereOnePlace(thisName: String): String = WhereWords.onePlace(thisName)

@JsExport
fun spacesWhereItemLine(names: Array<String>, offline: Array<Boolean>): String = WhereWords.itemLine(names.toList(), offline.toList())

// ---- music videos --------------------------------------------------------------------------------------------------

@JsExport
class JsVideoSource(
    val id: String,
    val kind: String,
    val title: String,
    val artist: String?,
    val hasVideo: Boolean,
    val videoDuration: Double?,
    val videoSize: Double?,
    val addedAt: Double,
)

@JsExport
class JsMusicVideoRow(
    val id: String,
    val itemId: String,
    val title: String,
    val artist: String?,
    val duration: Double?,
    val size: Double?,
    val addedAt: Double,
)

/** The music video rows of the items, newest first (see MusicVideoRows). */
@JsExport
fun spacesMusicVideoRows(items: Array<JsVideoSource>): Array<JsMusicVideoRow> =
    MusicVideoRows.list(items.map { VideoSource(it.id, it.kind, it.title, it.artist, it.hasVideo, it.videoDuration, it.videoSize, it.addedAt.toLong()) })
        .map { JsMusicVideoRow(it.id, it.itemId, it.title, it.artist, it.duration, it.size, it.addedAt.toDouble()) }.toTypedArray()

@JsExport
fun spacesMusicVideoRowId(itemId: String): String = MusicVideoRows.rowId(itemId)

/** The song's id behind a video row id, or null. */
@JsExport
fun spacesMusicVideoItemId(rowId: String): String? = MusicVideoRows.itemIdOf(rowId)

/** [label, shelf, seeAll, goToSong, delete, deleteAsk, joinNote, hasVideoNote, keepLink, hasVideoError]. */
@JsExport
fun spacesMusicVideoWords(): Array<String> = arrayOf(
    MusicVideoRows.LABEL, MusicVideoRows.SHELF, MusicVideoRows.SEE_ALL, MusicVideoRows.GO_TO_SONG, MusicVideoRows.DELETE,
    MusicVideoRows.DELETE_ASK, MusicVideoRows.JOIN_NOTE, MusicVideoRows.HAS_VIDEO_NOTE, MusicVideoRows.KEEP_LINK, MusicVideoRows.HAS_VIDEO_ERROR,
)

/** Where the other side of Song | Video starts (see Songs.switchStart). */
@JsExport
fun spacesSwitchStart(position: Double, otherLength: Double?): Double = Songs.switchStart(position, otherLength)

/** [bothLooking, bothNone, bothFound, bothLink] (see PreviewWords). */
@JsExport
fun spacesPreviewWords(): Array<String> = arrayOf(PreviewWords.BOTH_LOOKING, PreviewWords.BOTH_NONE, PreviewWords.BOTH_FOUND, PreviewWords.BOTH_LINK)

/** [add, none, new] words of the review's "Add to playlist" menu (see PreviewWords). */
@JsExport
fun spacesPlaylistWords(): Array<String> = arrayOf(PreviewWords.PLAYLIST_ADD, PreviewWords.PLAYLIST_NONE, PreviewWords.PLAYLIST_NEW)

@JsExport
fun spacesPlaylistLabel(name: String?): String = PreviewWords.playlistLabel(name)

/** How many Extras groups differ from the defaults (see PreviewWords.extrasCount). */
@JsExport
fun spacesExtrasCount(subtitles: Boolean, cut: Boolean, sponsor: Boolean, chapters: Boolean, inFile: Boolean, beside: Boolean, queue: Boolean): Int =
    PreviewWords.extrasCount(subtitles, cut, sponsor, chapters, inFile, beside, queue)

@JsExport
fun spacesExtrasLabel(changed: Int): String = PreviewWords.extrasLabel(changed)
