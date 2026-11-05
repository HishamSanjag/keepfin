package app.keepfin.ui.home

import app.keepfin.ItemQuery
import app.keepfin.LibraryCollection
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.ui.library.LibraryViews

/** Home's chips. [wire] is what UiPreferences remembers. */
enum class HomeKind(val wire: String, val label: String) {
    ALL("all", "All"), VIDEOS("videos", "Videos"), MUSIC("music", "Music"), PHOTOS("photos", "Photos");
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: ALL }
}

/** One tile of Home's quick grid: a video, song or photo, or a whole collection (album, playlist, channel). */
sealed interface QuickTile {
    val key: String
    data class Item(val item: LibraryItem) : QuickTile { override val key get() = "i-" + item.id }
    data class Collection(val collection: LibraryCollection, val cover: LibraryItem?) : QuickTile { override val key get() = "c-" + collection.id }
}

/** The four music rows of "Made from your listening". Each shows only when it has [HomeLogic.MIN_ROW] items. */
enum class ListeningRow(val title: String) {
    ON_REPEAT("On repeat"), FORGOTTEN("Forgotten favourites"), RECENT("Recently added music"), NEVER_PLAYED("Never played")
}

/**
 * What Home shows, computed from the library snapshot alone (no network, no account). Pure, so screens and tests agree.
 *
 * Play history on Android is the item's lifetime play count and its last-played time, not a dated list of plays, so "On repeat"
 * is the most-played songs among those played in the last 30 days.
 */
object HomeLogic {
    const val QUICK_MAX = 8
    const val MIN_ROW = 5
    const val DAY = 86_400_000L

    /** [musicSpaces]: a video kept in one of them is a song ([app.keepfin.core.spaces.Songs.isSong]): under Music, not Videos. */
    fun isSong(item: LibraryItem, musicSpaces: Set<String>): Boolean =
        app.keepfin.core.spaces.Songs.isSong(item.kind.wire, if (item.space in musicSpaces) app.keepfin.SpaceKind.MUSIC.wire else null)

    fun matches(item: LibraryItem, kind: HomeKind, musicSpaces: Set<String> = emptySet()): Boolean = when (kind) {
        HomeKind.ALL -> item.kind != LibraryItemKind.OTHER
        HomeKind.VIDEOS -> item.kind == LibraryItemKind.VIDEO && !isSong(item, musicSpaces)
        HomeKind.MUSIC -> isSong(item, musicSpaces)
        HomeKind.PHOTOS -> item.kind == LibraryItemKind.IMAGE
    }

    /** The latest things you touched: items by last play or arrival, collections by last change, mixed; up to [QUICK_MAX]. */
    fun quickGrid(items: List<LibraryItem>, collections: List<LibraryCollection>, kind: HomeKind, musicSpaces: Set<String> = emptySet()): List<QuickTile> {
        val byId = items.associateBy { it.id }
        val visibleItems = items.filter { matches(it, kind, musicSpaces) }
        val tiles = ArrayList<Pair<Long, QuickTile>>()
        // A collection shows only when it has something of the chosen kind; its songs then stay out of the grid as single tiles.
        val covered = HashSet<String>()
        for (c in collections) {
            val members = c.itemIds.mapNotNull { byId[it] }.filter { matches(it, kind, musicSpaces) }
            if (members.isEmpty()) continue
            covered += members.map { it.id }
            tiles += c.updatedAt.coerceAtLeast(c.createdAt) to QuickTile.Collection(c, members.firstOrNull { it.art != null } ?: members.first())
        }
        for (item in visibleItems) {
            if (item.id in covered) continue
            tiles += (item.lastPlayedAt ?: item.addedAt) to QuickTile.Item(item)
        }
        return tiles.sortedByDescending { it.first }.take(QUICK_MAX).map { it.second }
    }

    fun continueWatching(items: List<LibraryItem>, kind: HomeKind): List<LibraryItem> =
        if (kind == HomeKind.MUSIC || kind == HomeKind.PHOTOS) emptyList()
        else LibraryViews.continueWatching(items).filter { it.kind == LibraryItemKind.VIDEO }

    /** Recently added videos and photos (the music rows have their own). [HomeKind.MUSIC] has none. */
    fun recentlyAdded(items: List<LibraryItem>, kind: HomeKind): List<LibraryItem> =
        if (kind == HomeKind.MUSIC) emptyList()
        else items.filter { (it.kind == LibraryItemKind.VIDEO || it.kind == LibraryItemKind.IMAGE) && matches(it, kind) }.sortedByDescending { it.addedAt }

    /** The music rows, in order, each with at least [MIN_ROW] songs; hidden for the Videos and Photos chips. */
    fun listening(items: List<LibraryItem>, kind: HomeKind, now: Long, musicSpaces: Set<String> = emptySet()): List<Pair<ListeningRow, List<LibraryItem>>> {
        if (kind == HomeKind.VIDEOS || kind == HomeKind.PHOTOS) return emptyList()
        val songs = items.filter { isSong(it, musicSpaces) }
        val rows = listOf(
            ListeningRow.ON_REPEAT to songs.filter { it.plays > 0 && (it.lastPlayedAt ?: 0L) >= now - 30 * DAY }
                .sortedWith(compareByDescending<LibraryItem> { it.plays }.thenByDescending { it.lastPlayedAt ?: 0L }),
            // A favourite you never played is not "forgotten": it belongs to Never played.
            ListeningRow.FORGOTTEN to songs.filter { (it.favorite || it.plays >= 5) && (it.lastPlayedAt ?: Long.MAX_VALUE) < now - 60 * DAY }
                .sortedWith(compareBy<LibraryItem> { it.lastPlayedAt ?: 0L }.thenByDescending { it.plays }),
            ListeningRow.RECENT to songs.sortedByDescending { it.addedAt },
            ListeningRow.NEVER_PLAYED to songs.filter { it.plays == 0 && it.lastPlayedAt == null }.sortedByDescending { it.addedAt },
        )
        return rows.filter { it.second.size >= MIN_ROW }.map { it.first to it.second.take(12) }
    }
}

/** The rows Home shows, read from the index: a few rows per shelf, then shaped by [HomeLogic]. */
class HomeShelves(
    val quick: List<QuickTile>,
    val resume: List<LibraryItem>,
    val listening: List<Pair<ListeningRow, List<LibraryItem>>>,
    val recent: List<LibraryItem>,
) {
    companion object {
        val EMPTY = HomeShelves(emptyList(), emptyList(), emptyList(), emptyList())

        private fun kinds(kind: HomeKind): Set<LibraryItemKind> = when (kind) {
            HomeKind.ALL -> setOf(LibraryItemKind.VIDEO, LibraryItemKind.AUDIO, LibraryItemKind.IMAGE)
            HomeKind.VIDEOS -> setOf(LibraryItemKind.VIDEO)
            HomeKind.MUSIC -> setOf(LibraryItemKind.AUDIO)
            HomeKind.PHOTOS -> setOf(LibraryItemKind.IMAGE)
        }

        /** The candidates for the quick grid and the shelves, as a few indexed queries. */
        fun read(b: app.keepfin.LibraryBrowse, collections: List<LibraryCollection>, kind: HomeKind, now: Long, musicSpaces: Set<String> = emptySet()): HomeShelves {
            val shown = kinds(kind)
            // Music: the songs (videos kept in a music space among them); Videos: the other videos
            val base = when (kind) {
                HomeKind.MUSIC -> ItemQuery(songs = true)
                HomeKind.VIDEOS -> ItemQuery(kinds = shown, excludeSpaces = musicSpaces.toList())
                else -> ItemQuery(kinds = shown)
            }
            // the quick grid: the latest things touched (a play, or an arrival), and the latest changed collections with a few members each
            val byPlay = b.top(base.copy(everPlayed = true, order = app.keepfin.ItemOrder.RECENT), 24)
            val byAdd = b.top(base.copy(order = app.keepfin.ItemOrder.ADDED), 24)
            val newest = collections.sortedByDescending { it.updatedAt.coerceAtLeast(it.createdAt) }.take(24)
            val members = b.byIds(newest.flatMap { it.itemIds.take(12) }.distinct())
            val candidates = (byPlay + byAdd + members).distinctBy { it.id }
            val quick = HomeLogic.quickGrid(candidates, newest, kind, musicSpaces)
            val resume = HomeLogic.continueWatching(b.top(ItemQuery(resumable = true, order = app.keepfin.ItemOrder.RECENT), 24), kind)
            val recent = if (kind == HomeKind.MUSIC) emptyList() else b.top(ItemQuery(kinds = shown - LibraryItemKind.AUDIO, order = app.keepfin.ItemOrder.ADDED), 40).filterNot { HomeLogic.isSong(it, musicSpaces) }
            val listening = if (kind == HomeKind.VIDEOS || kind == HomeKind.PHOTOS) emptyList() else {
                val songs = ItemQuery(songs = true)
                val since = now - 30 * HomeLogic.DAY
                val stale = now - 60 * HomeLogic.DAY
                val rows = listOf(
                    ListeningRow.ON_REPEAT to b.top(songs.copy(played = true, playedSince = since, order = app.keepfin.ItemOrder.MOST_PLAYED), 24),
                    // a favourite or a song played five times or more, untouched for two months: the oldest first
                    ListeningRow.FORGOTTEN to (b.top(songs.copy(favorite = true, playedBefore = stale, order = app.keepfin.ItemOrder.STALE), 24) + b.top(songs.copy(minPlays = 5, playedBefore = stale, order = app.keepfin.ItemOrder.STALE), 24)).distinctBy { it.id },
                    ListeningRow.RECENT to b.top(songs.copy(order = app.keepfin.ItemOrder.ADDED), 12),
                    ListeningRow.NEVER_PLAYED to b.top(songs.copy(unplayed = true, order = app.keepfin.ItemOrder.ADDED), 40),
                )
                shapeListening(rows)
            }
            return HomeShelves(quick, resume, listening, recent)
        }

        private fun shapeListening(rows: List<Pair<ListeningRow, List<LibraryItem>>>): List<Pair<ListeningRow, List<LibraryItem>>> {
            val sorted = rows.map { (row, list) ->
                row to when (row) {
                    ListeningRow.ON_REPEAT -> list.sortedWith(compareByDescending<LibraryItem> { it.plays }.thenByDescending { it.lastPlayedAt ?: 0L })
                    ListeningRow.FORGOTTEN -> list.sortedWith(compareBy<LibraryItem> { it.lastPlayedAt ?: 0L }.thenByDescending { it.plays })
                    else -> list
                }
            }
            return sorted.filter { it.second.size >= HomeLogic.MIN_ROW }.map { it.first to it.second.take(12) }
        }
    }
}