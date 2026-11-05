package app.keepfin.core.spaces

// The slice of the library model that the spaces decisions read. Each app maps its own item, collection and space types
// onto these (desktop: gui/shared/core/spaces.ts, Android: Collections.kt and LibraryLogic.kt); storage stays per platform.
// Kinds, modes and types are the wire strings both apps already store ("audio", "playlist", "auto", "smart"...).

/** What a smart rule can see of a library item. Times are milliseconds since the epoch. */
class LibraryItem(
    val id: String,
    val space: String,
    val kind: String,
    val tags: List<String> = emptyList(),
    val userTags: List<String> = emptyList(),
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val favorite: Boolean = false,
    val plays: Int = 0,
    val addedAt: Long = 0,
    val sourceUrl: String? = null,
    val sourceId: String? = null,
)

/** How a collection is followed. [pending]: a check found more than [confirmAbove] and waits for the user. */
class Follow(
    val mode: String,
    val intervalHours: Int,
    val wifiOnly: Boolean,
    val notify: Boolean,
    val confirmAbove: Int,
    val lastCheckedAt: Long? = null,
    val pending: Boolean = false,
)

/** A playlist, album or channel kept together, or the user's own list. [kind]: playlist, album, channel or own. */
class LibraryCollection(
    val id: String,
    val name: String,
    val kind: String,
    val space: String,
    val itemIds: List<String>,
    val seen: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val follow: Follow? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** A space as the hub sees it. [type]: folder or smart. [pinned] null means the default (built-ins are pinned). */
class Space(val id: String, val type: String = "folder", val pinned: Boolean? = null)

/** The canonical stored link of a collection source and the playlist, channel or album id it names. */
class CollectionSource(val url: String, val id: String?)
