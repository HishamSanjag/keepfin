package app.keepfin.core.library

import app.keepfin.core.sync.Jv

// The platform-neutral library record that both apps map to and from sync operations (see LibraryMapping).
// It holds exactly the synced content; device-only data (sizes, flags, follow results, absolute paths) stays in each app.
// Paths are the synced placement: '/'-separated, relative to the Keepfin folder (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md
// sections 3 and 4). Numbers are doubles so the apps keep their own integer or fractional types; the mapping scales and
// rounds them for the wire. The behaviour is gui/desktop/store/mapping.ts, proven equal by fixtures/library-mapping.

data class LibChapter(val start: Double, val title: String)

/** One media item. [space] is the app's own space id; [kind] is audio, video, image or other. */
data class LibItem(
    val id: String,
    val space: String,
    val kind: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: Double? = null,
    val disc: Double? = null,
    val year: Double? = null,
    val genre: String? = null,
    /** Seconds. */
    val duration: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val chapters: List<LibChapter>? = null,
    /** Decibels. */
    val gain: Double? = null,
    val subtitles: List<String>? = null,
    val hasLyrics: Boolean? = null,
    val sourceUrl: String? = null,
    val sourceId: String? = null,
    val addedAt: Double? = null,
    /** Tags imported from the source. */
    val tags: List<String>? = null,
    val lastPlayedAt: Double? = null,
    /** Resume position, seconds. */
    val position: Double? = null,
    val watched: Boolean? = null,
    val favorite: Boolean = false,
    /** The user's own tags. */
    val userTags: List<String> = emptyList(),
    /** The total play count (this device's counter is [DiffContext.ownPlays]). */
    val plays: Double? = null,
    /** The placement on this device, or null when the item has none here. */
    val place: String? = null,
)

data class LibSource(val url: String, val provider: String, val id: String? = null)

data class LibFollow(
    val mode: String,
    val intervalHours: Double,
    val wifiOnly: Boolean,
    val notify: Boolean,
    val confirmAbove: Double,
    val pendingCount: Double? = null,
    val pendingAt: Double? = null,
)

/** A playlist (kind `own`) or a downloaded collection (kind `playlist`, `album` or `channel`). */
data class LibCollection(
    val id: String,
    val name: String,
    val kind: String,
    val space: String,
    val itemIds: List<String>,
    val seen: List<String> = emptyList(),
    val createdAt: Double? = null,
    val updatedAt: Double? = null,
    val coverItemId: String? = null,
    val source: LibSource? = null,
    val follow: LibFollow? = null,
    val newItemIds: List<String> = emptyList(),
    val removedItemIds: List<String> = emptyList(),
)

/** A space. [folder] is relative to the Keepfin folder, or the virtual name of a [linked] space; "" for a smart space. */
data class LibSpace(
    val id: String,
    val name: String,
    val kind: String,
    val folder: String,
    val linked: Boolean = false,
    val layout: String = "flat",
    val naming: String = "title",
    val skipExisting: Boolean = false,
    val ignore: List<String>? = null,
    val scanFolder: Boolean? = null,
    val type: String = "folder",
    val color: String? = null,
    val icon: String? = null,
    val pinned: Boolean? = null,
    val rules: Jv? = null,
)

data class LibRoutes(val music: String, val other: String)

data class LibModel(
    val items: List<LibItem>,
    val collections: List<LibCollection>,
    val spaces: List<LibSpace>,
    val routes: LibRoutes,
)

/** A change before it becomes an operation. */
data class OpDraft(val kind: String, val body: Map<String, Jv>)

/** One song of a playlist as the views list it. */
data class Song(val entry: String, val item: String, val key: String)

data class ObservedTags(val tags: List<String>, val heads: List<String>)

/** The tags and heads a delete or remove carries, by key. Unknown keys answer with empty lists. */
interface ObservedLookup {
    fun item(id: String): ObservedTags
    fun playlist(id: String): List<String>
    fun space(id: String): List<String>
    fun member(set: String, element: String): List<String>
}

/** What a diff needs from its writer. */
class DiffContext(
    /** This writer's actor id (for its own play counter). */
    val actor: String,
    /** This device's place id (placements are written under it). */
    val replicaId: String,
    val newId: () -> String,
    /** This actor's own play counter for an item. */
    val ownPlays: (id: String) -> Long,
    /** A playlist's current songs, for reorders. */
    val songs: (playlist: String) -> List<Song>,
    val observed: ObservedLookup,
)

/** The keys a diff asked the observed lookup about (the first pass of a two-pass diff). */
class RecordingObserved : ObservedLookup {
    val items = LinkedHashSet<String>()
    val playlists = LinkedHashSet<String>()
    val spaces = LinkedHashSet<String>()
    val elements = LinkedHashMap<String, LinkedHashSet<String>>()

    override fun item(id: String): ObservedTags {
        items.add(id)
        return ObservedTags(emptyList(), emptyList())
    }

    override fun playlist(id: String): List<String> {
        playlists.add(id)
        return emptyList()
    }

    override fun space(id: String): List<String> {
        spaces.add(id)
        return emptyList()
    }

    override fun member(set: String, element: String): List<String> {
        elements.getOrPut(set) { LinkedHashSet() }.add(element)
        return emptyList()
    }

    val isEmpty: Boolean get() = items.isEmpty() && playlists.isEmpty() && spaces.isEmpty() && elements.isEmpty()

    fun setElements(): List<Pair<String, String>> = elements.flatMap { (set, list) -> list.map { set to it } }
}

/** Answers of the store for a recorded query. */
class ObservedAnswer(
    val items: Map<String, ObservedTags>,
    val playlists: Map<String, List<String>>,
    val spaces: Map<String, List<String>>,
    val setElements: Map<String, Map<String, List<String>>>,
) : ObservedLookup {
    override fun item(id: String) = items[id] ?: ObservedTags(emptyList(), emptyList())
    override fun playlist(id: String) = playlists[id] ?: emptyList()
    override fun space(id: String) = spaces[id] ?: emptyList()
    override fun member(set: String, element: String) = setElements[set]?.get(element) ?: emptyList()
}
