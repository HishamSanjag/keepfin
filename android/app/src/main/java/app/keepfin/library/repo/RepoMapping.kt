package app.keepfin.library.repo

import app.keepfin.CollectionKind
import app.keepfin.CollectionSource
import app.keepfin.Follow
import app.keepfin.FollowMode
import app.keepfin.FolderLayout
import app.keepfin.LibraryCollection
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.PendingCheck
import app.keepfin.Space
import app.keepfin.SpaceColor
import app.keepfin.SpaceIcon
import app.keepfin.SpaceKind
import app.keepfin.SpaceLogic
import app.keepfin.SpaceNaming
import app.keepfin.SpaceType
import app.keepfin.core.library.LibCollection
import app.keepfin.core.library.LibFollow
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibSource
import app.keepfin.core.library.LibSpace
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JBool
import app.keepfin.core.sync.JNull
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import org.json.JSONArray
import org.json.JSONObject

// Conversions between the shared library record (core, wire strings) and the app's own types. Pure; no Android classes.

/** Device-only follow results of a collection (never synced). */
data class FollowDevice(val lastCheckedAt: Long? = null, val lastError: String? = null)

private fun jsonToJv(value: Any?): Jv? = when (value) {
    null, JSONObject.NULL -> JNull
    is Boolean -> JBool(value)
    is String -> JStr(value)
    is Int -> JNum(value.toLong())
    is Long -> JNum(value)
    is Number -> value.toDouble().takeIf { it == Math.floor(it) && Math.abs(it) <= 9007199254740991.0 }?.let { JNum(it.toLong()) }
    is JSONArray -> JArr((0 until value.length()).map { jsonToJv(value.get(it)) ?: return null })
    is JSONObject -> {
        val members = LinkedHashMap<String, Jv>()
        for (key in value.keys()) members[key] = jsonToJv(value.get(key)) ?: return null
        JObj(members)
    }
    else -> null
}

private fun jvToJson(value: Jv): Any = when (value) {
    is JNull -> JSONObject.NULL
    is JBool -> value.value
    is JStr -> value.value
    is JNum -> value.value
    is JArr -> JSONArray().also { a -> value.items.forEach { a.put(jvToJson(it)) } }
    is JObj -> JSONObject().also { o -> for ((k, v) in value.members) o.put(k, jvToJson(v)) }
}

internal fun Space.toLib(): LibSpace = LibSpace(
    id = id, name = name, kind = kind.wire, folder = if (type == SpaceType.SMART) "" else folder, linked = false,
    layout = layout.wire, naming = naming.wire, skipExisting = skipExisting, ignore = ignore.takeIf { it.isNotEmpty() },
    scanFolder = scanFolder, type = type.wire, color = color?.wire, icon = icon?.wire, pinned = pinned,
    rules = rules?.let { jsonToJv(SpaceLogic.encodeRules(it)) },
)

internal fun LibSpace.toSpace(): Space = Space(
    id = id, name = name,
    kind = SpaceKind.entries.firstOrNull { it.wire == kind } ?: SpaceKind.MIXED,
    folder = folder,
    layout = FolderLayout.entries.firstOrNull { it.wire == layout } ?: FolderLayout.FLAT,
    naming = SpaceNaming.entries.firstOrNull { it.wire == naming } ?: SpaceNaming.TITLE,
    skipExisting = skipExisting, ignore = ignore.orEmpty(), scanFolder = scanFolder ?: false,
    type = if (type == "smart") SpaceType.SMART else SpaceType.FOLDER,
    color = SpaceColor.entries.firstOrNull { it.wire == color },
    icon = SpaceIcon.entries.firstOrNull { it.wire == icon },
    pinned = pinned,
    rules = (rules as? JObj)?.let { SpaceLogic.decodeRules(jvToJson(it) as JSONObject) },
)

internal fun LibFollow.toFollow(device: FollowDevice?): Follow = Follow(
    mode = FollowMode.entries.firstOrNull { it.wire == mode } ?: FollowMode.OFF,
    intervalHours = intervalHours.toInt(), wifiOnly = wifiOnly, notify = notify, confirmAbove = confirmAbove.toInt(),
    lastCheckedAt = device?.lastCheckedAt, lastError = device?.lastError,
    pending = if (pendingCount != null && pendingAt != null) PendingCheck(pendingCount!!.toInt(), pendingAt!!.toLong()) else null,
)

internal fun Follow.toLib(): LibFollow = LibFollow(
    mode = mode.wire, intervalHours = intervalHours.toDouble(), wifiOnly = wifiOnly, notify = notify, confirmAbove = confirmAbove.toDouble(),
    pendingCount = pending?.count?.toDouble(), pendingAt = pending?.at?.toDouble(),
)

internal fun LibCollection.toCollection(follows: Map<String, FollowDevice>): LibraryCollection = LibraryCollection(
    id = id, name = name,
    kind = CollectionKind.entries.firstOrNull { it.wire == kind } ?: CollectionKind.PLAYLIST,
    space = space, itemIds = itemIds, coverItemId = coverItemId,
    source = source?.let { CollectionSource(it.url, it.provider, it.id) },
    follow = follow?.toFollow(follows[id]),
    seen = seen, newItemIds = newItemIds, removedItemIds = removedItemIds,
    createdAt = createdAt?.toLong() ?: 0L, updatedAt = updatedAt?.toLong() ?: 0L,
)

/** The folder of an item inside its space: the placement without the space folder and the file name, "/"-separated. */
internal fun folderWithin(spaceFolder: String, place: String): String {
    val rest = if (spaceFolder.isEmpty()) place else if (place.startsWith("$spaceFolder/")) place.substring(spaceFolder.length + 1) else return ""
    return rest.substringBeforeLast('/', "")
}

internal fun LibItem.toItem(uri: String, folder: String, size: Long): LibraryItem = LibraryItem(
    id = id, space = space, uri = uri, folder = folder,
    kind = LibraryItemKind.entries.firstOrNull { it.wire == kind } ?: LibraryItemKind.OTHER,
    title = title, artist = artist, album = album, albumArtist = albumArtist, track = track?.toInt(), year = year?.toInt(),
    duration = duration, size = size, modifiedAt = 0L, addedAt = addedAt?.toLong() ?: 0L,
    sourceUrl = sourceUrl, sourceId = sourceId, width = width?.toInt(), height = height?.toInt(), position = position,
    plays = plays?.toInt() ?: 0, lastPlayedAt = lastPlayedAt?.toLong(), favorite = favorite, watched = watched == true,
    hasLyrics = hasLyrics == true, gain = gain, disc = disc?.toInt(), genre = genre, tags = tags.orEmpty(), userTags = userTags,
    chapters = chapters.orEmpty().map { app.keepfin.LibraryChapter(it.start, it.title) },
)

/** A space keeps being linked (outside the Keepfin folder) when a settings edit or a save turns the app's [Space] back into the record. */
internal fun LibSpace.keepLinked(existing: List<LibSpace>): LibSpace = if (existing.firstOrNull { it.id == id }?.linked == true && type != "smart") copy(linked = true) else this
