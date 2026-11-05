package app.keepfin

import kotlinx.coroutines.flow.StateFlow

// The paged reading surface of the library (decision D-A4). A library of 200,000 items is never held in memory: screens ask for
// pages of one ordered, filtered view (keyset pagination: the key of the last row, never an OFFSET), for the top few rows of a
// shelf, for counts, for groups (albums, artists, folders) and for single items by id. The folder library answers from its
// SQLite index; the MediaStore library and the preview answer from their in-memory list with the same semantics, so a screen
// has one code path. Every call blocks and must run off the main thread; [changes] grows whenever any answer may have changed.

/** The order of a list. Every order ends with the item id, so it is total and deterministic. */
enum class ItemOrder(val descending: Boolean) {
    /** Newest first. */
    ADDED(true),

    /** Title, case and accents folded. */
    TITLE(false),

    /** Artist, album, disc, track, title. */
    ARTIST(false),

    /** Longest first. */
    DURATION(true),

    /** Largest first. */
    SIZE(true),

    /** Most plays first, then the last play. */
    MOST_PLAYED(true),

    /** The last play first. */
    RECENT(true),

    /** Disc, track, title (the order inside an album). */
    TRACK(false),

    /** The longest since a play first (oldest last-played time first). */
    STALE(false),
}

/** Which items a list holds. Empty fields do not filter. */
data class ItemQuery(
    /** Space ids (as the app knows them). Empty: every space. */
    val spaces: List<String> = emptyList(),
    val kinds: Set<LibraryItemKind> = emptySet(),
    /** Songs only: audio, or a video kept in a music space ([app.keepfin.core.spaces.Songs.isSong]). Narrows [kinds] when both are set. */
    val songs: Boolean = false,
    val order: ItemOrder = ItemOrder.ADDED,
    val favorite: Boolean = false,
    /** Songs that keep a music video beside them (their video rows, dev-docs/plans/MUSIC-VIDEOS.md). */
    val musicVideos: Boolean = false,
    /** Never played. */
    val unplayed: Boolean = false,
    /** Played at least once. */
    val played: Boolean = false,
    /** Played at least this many times. */
    val minPlays: Int = 0,
    /** Last played at or after this time (ms). */
    val playedSince: Long? = null,
    /** Last played before this time (ms), and played at least once. */
    val playedBefore: Long? = null,
    /** Has a last-played time. */
    val everPlayed: Boolean = false,
    /** Saved position past 5 s and not watched, audio and video only ("Continue"). */
    val resumable: Boolean = false,
    /** The items directly in this folder of the (single) space, "" for the top. Null: any folder. */
    val folder: String? = null,
    /** Items in this folder of the (single) space or any folder below it. */
    val subtree: String? = null,
    /** Limits where [text] must match: FTS5 column names, e.g. "album artist". Null: title, artist, album and the rest. */
    val textColumns: String? = null,
    /** Artist key ([LibraryKeys.artist]) of an artist page. */
    val artist: String? = null,
    /** Album key ([PlaybackItems.albumKey]) within the space. */
    val album: String? = null,
    /** Words to find (all must match, the last as a prefix). */
    val text: String = "",
    /** Only these items. */
    val ids: Set<String>? = null,
    /** Smart rules, evaluated against the items of the other fields. */
    val smart: SmartRules? = null,
    val smartContext: SmartContext? = null,
    /** Items with any of these tags (imported or the user's own). */
    val tagsAny: List<String> = emptyList(),
    /** Items with every one of these tags. */
    val tagsAll: List<String> = emptyList(),
    /** Not in these spaces. */
    val excludeSpaces: List<String> = emptyList(),
)

/** The key of a row in an order: the values the order sorts by. Opaque to screens; they hand back the last one they got. */
typealias PageKey = List<Any>

/** A page and the keys around it. [prev]/[next] are null at the ends. */
class PageResult(val items: List<LibraryItem>, val prev: PageKey?, val next: PageKey?)

enum class PageDirection { FIRST, AFTER, BEFORE, AT }

/** An album as the music views group it. */
data class AlbumGroup(
    val key: String,
    val space: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val tracks: Int,
    val duration: Double,
    val discs: Int,
    val compilation: Boolean,
    /** Key without the space, as used by album routes. */
    val routeKey: String,
    /** An item of the album to take its art from. */
    val cover: LibraryItem?,
    /** Where the next page of albums starts. */
    val cursor: String = "",
)

data class ArtistGroup(val name: String, val key: String, val songs: Int, val albums: Int, val cover: LibraryItem?, val cursor: String = "")

/** A value and how many items carry it. */
data class Facet(val value: String, val count: Int)

data class FolderGroup(val name: String, val path: String, val count: Int, val cover: LibraryItem?)

/** How many items a space (or the whole library) holds, by kind. */
data class Counts(val total: Int = 0, val audio: Int = 0, val video: Int = 0, val image: Int = 0, val other: Int = 0, val bytes: Long = 0L) {
    fun of(kind: LibraryItemKind): Int = when (kind) {
        LibraryItemKind.AUDIO -> audio
        LibraryItemKind.VIDEO -> video
        LibraryItemKind.IMAGE -> image
        LibraryItemKind.OTHER -> other
    }
}

interface LibraryBrowse {
    /** Grows whenever an item, a collection or a space changed. Collect it to re-query. */
    val changes: StateFlow<Long>

    /** True when every answer is immediate (a list in memory): the UI then reads on the calling thread instead of hopping to a background one. */
    val cheap: Boolean get() = false

    /**
     * A page of [limit] rows of [query]: from the start ([PageDirection.FIRST]), after [key], before it, or starting at it
     * ([PageDirection.AT], inclusive, to reopen a list where it was).
     */
    fun page(query: ItemQuery, direction: PageDirection, key: PageKey?, limit: Int): PageResult

    /** The key of [item] in [order] (for a page to reopen around). */
    fun keyOf(item: LibraryItem, order: ItemOrder): PageKey

    /** The first [limit] rows. */
    fun top(query: ItemQuery, limit: Int): List<LibraryItem> = page(query, PageDirection.FIRST, null, limit).items

    fun count(query: ItemQuery): Int

    /** Totals by kind of the items [query] selects (its order is ignored). */
    fun counts(query: ItemQuery): Counts

    /** The ids of up to [limit] items of [query], in its order (select all, play all). */
    fun ids(query: ItemQuery, limit: Int): List<String> = top(query, limit).map { it.id }

    /** Totals by kind for every space at once (one pass over the index) among the items [query] selects. */
    fun spaceCounts(query: ItemQuery = ItemQuery()): Map<String, Counts>

    /** The values a smart rule can name, with how many items carry each (most used first, at most [limit]). Tags, artists, albums and sources. */
    fun facets(field: SmartField, limit: Int): List<Facet>

    /** For each collection (playlist) id, how many of its present items are not in [space]. */
    fun outsideCounts(space: String): Map<String, Int>

    /** The spaces (the app's ids) that hold these items. */
    fun spacesOf(ids: Collection<String>): Set<String>

    /** These items in the order given; unknown ids are skipped. */
    fun byIds(ids: List<String>): List<LibraryItem>

    /** One item, null when unknown. */
    fun item(id: String): LibraryItem?

    /** The item a finished download became, or null. */
    fun byJob(jobId: String): LibraryItem? = null

    /** The item whose file address is [uri], or null. */
    fun byUri(uri: String): LibraryItem? = null

    /**
     * Albums of the audio items [query] selects, a page after the cursor [after] (null: the first). [byArtist] orders them by album
     * artist and then title (within one space), else by album title.
     */
    fun albums(query: ItemQuery, after: String?, limit: Int, byArtist: Boolean = false): List<AlbumGroup>

    /** How many albums / artists the audio items of [query] form. */
    fun albumCount(query: ItemQuery): Int
    fun artistCount(query: ItemQuery): Int

    /** One album by its key (space + "/" + route key), or null. */
    fun album(space: String, routeKey: String): AlbumGroup?

    /** Artists of the audio items [query] selects, by name. */
    fun artists(query: ItemQuery, after: String?, limit: Int): List<ArtistGroup>

    fun artist(key: String, space: String?): ArtistGroup?

    /** The albums of one artist (track artist) in the space. */
    fun albumsOfArtist(artistKey: String, space: String?): List<AlbumGroup>

    /** The sub-folders of [folder] in [space] and how many items each holds. */
    fun folders(space: String, folder: String): List<FolderGroup>

    /** Every distinct folder of a space (relative to the space's root, "/"-separated, not empty) with the number of items of [kinds] in it. */
    fun folderList(space: String, kinds: Set<LibraryItemKind>): List<FolderGroup>

    /** Tags in scope with their counts: (tag, items, added by the user), most used first. */
    fun tags(query: ItemQuery, minItems: Int): List<ScopedTag>

    /**
     * Items with exactly the same content (the same file more than once), in groups of two or more, at most [limit] groups: among
     * [ids] when given (a collection), else among the items [query] selects. Each group lists its oldest first. Empty where the
     * library has no content fingerprints (without a Keepfin folder).
     */
    fun duplicates(query: ItemQuery, ids: Collection<String>? = null, limit: Int = 500): List<List<LibraryItem>> = emptyList()

}

/** The keys that lists group by, shared by the index and the in-memory views. */
object LibraryKeys {
    /** The artist page key of an item: its track artist (or album artist) folded, "" when it has none. */
    fun artist(item: LibraryItem): String = LibraryViewsKeys.fold((item.artist ?: item.albumArtist)?.takeIf(String::isNotBlank))
}

object LibraryViewsKeys {
    private val marks = Regex("\\p{Mn}+")

    /** Lower-case, diacritic-free text ("Goeransson"). */
    fun fold(text: String?): String =
        if (text.isNullOrEmpty()) "" else marks.replace(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD), "").lowercase(java.util.Locale.ROOT)
}

/** A [LibraryBrowse] that can be pointed at the real one once it exists (the library opens in the background). Answers empty until then. */
class BrowseHolder : LibraryBrowse {
    @Volatile var target: LibraryBrowse? = null
    private val flow = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override val changes: StateFlow<Long> get() = flow

    fun bump() {
        flow.value = flow.value + 1
    }

    private inline fun <T> with(empty: T, block: (LibraryBrowse) -> T): T = target?.let(block) ?: empty

    override val cheap: Boolean get() = target?.cheap ?: false
    override fun ids(query: ItemQuery, limit: Int) = with(emptyList()) { it.ids(query, limit) }
    override fun spaceCounts(query: ItemQuery) = with(emptyMap()) { it.spaceCounts(query) }
    override fun albumCount(query: ItemQuery) = with(0) { it.albumCount(query) }
    override fun artistCount(query: ItemQuery) = with(0) { it.artistCount(query) }
    override fun folderList(space: String, kinds: Set<LibraryItemKind>) = with(emptyList()) { it.folderList(space, kinds) }
    override fun spacesOf(ids: Collection<String>) = with(emptySet()) { it.spacesOf(ids) }
    override fun outsideCounts(space: String) = with(emptyMap()) { it.outsideCounts(space) }
    override fun facets(field: SmartField, limit: Int) = with(emptyList()) { it.facets(field, limit) }
    override fun page(query: ItemQuery, direction: PageDirection, key: PageKey?, limit: Int) = with(PageResult(emptyList(), null, null)) { it.page(query, direction, key, limit) }
    override fun keyOf(item: LibraryItem, order: ItemOrder) = with(emptyList()) { it.keyOf(item, order) }
    override fun count(query: ItemQuery) = with(0) { it.count(query) }
    override fun counts(query: ItemQuery) = with(Counts()) { it.counts(query) }
    override fun byIds(ids: List<String>) = with(emptyList()) { it.byIds(ids) }
    override fun item(id: String) = with(null) { it.item(id) }
    override fun byJob(jobId: String) = with(null) { it.byJob(jobId) }
    override fun byUri(uri: String) = with(null) { it.byUri(uri) }
    override fun albums(query: ItemQuery, after: String?, limit: Int, byArtist: Boolean) = with(emptyList()) { it.albums(query, after, limit, byArtist) }
    override fun album(space: String, routeKey: String) = with(null) { it.album(space, routeKey) }
    override fun artists(query: ItemQuery, after: String?, limit: Int) = with(emptyList()) { it.artists(query, after, limit) }
    override fun artist(key: String, space: String?) = with(null) { it.artist(key, space) }
    override fun albumsOfArtist(artistKey: String, space: String?) = with(emptyList()) { it.albumsOfArtist(artistKey, space) }
    override fun folders(space: String, folder: String) = with(emptyList()) { it.folders(space, folder) }
    override fun tags(query: ItemQuery, minItems: Int) = with(emptyList()) { it.tags(query, minItems) }
    override fun duplicates(query: ItemQuery, ids: Collection<String>?, limit: Int) = with(emptyList()) { it.duplicates(query, ids, limit) }
}

/** Every item [query] selects, read page by page (for tests and small, bounded callers; never on a screen). */
fun LibraryBrowse.all(query: ItemQuery = ItemQuery(), pageSize: Int = 500): List<LibraryItem> {
    val out = ArrayList<LibraryItem>()
    var key: PageKey? = null
    var direction = PageDirection.FIRST
    while (true) {
        val page = page(query, direction, key, pageSize)
        out += page.items
        key = page.next ?: return out
        direction = PageDirection.AFTER
    }
}
/** Every item of the library, read page by page (tests and small bounded callers; screens use pages). */
fun LibraryApi.allItems(): List<LibraryItem> = browse.all()