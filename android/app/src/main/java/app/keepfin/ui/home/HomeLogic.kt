package app.keepfin.ui.home

import app.keepfin.ItemQuery
import app.keepfin.LibraryCollection
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.core.home.MixSong
import app.keepfin.core.home.Mixes
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

/** One tile of "Made from your listening": the shared core's mix and its songs, in the mix's order. */
class HomeMix(val mix: app.keepfin.core.home.Mix, val songs: List<LibraryItem>)

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

    const val MIX_SONGS = 12

    /**
     * "Made from your listening": the shared core's mixes (On repeat, Forgotten favourites, Recently added music, Never played, Because you play X,
     * Late-night mix) over [items], each with at least [Mixes.MIN_ROW] songs; none for the Videos and Photos chips. [utcOffsetMinutes] is the local clock.
     */
    fun listening(items: List<LibraryItem>, kind: HomeKind, now: Long, musicSpaces: Set<String> = emptySet(), utcOffsetMinutes: Int = 0): List<HomeMix> {
        if (kind == HomeKind.VIDEOS || kind == HomeKind.PHOTOS) return emptyList()
        val songs = items.filter { isSong(it, musicSpaces) }
        val byId = songs.associateBy { it.id }
        val mixSongs = songs.map { MixSong(it.id, it.artist ?: it.albumArtist, it.plays, it.lastPlayedAt, it.addedAt, it.favorite) }
        return Mixes.build(mixSongs, now, utcOffsetMinutes, MIX_SONGS).map { mix -> HomeMix(mix, mix.ids.mapNotNull(byId::get)) }
    }
}

/** The rows Home shows, read from the index: a few rows per shelf, then shaped by [HomeLogic]. */
class HomeShelves(
    val quick: List<QuickTile>,
    val resume: List<LibraryItem>,
    val listening: List<HomeMix>,
    val recent: List<LibraryItem>,
    val recentMusic: List<LibraryItem>,
) {
    companion object {
        val EMPTY = HomeShelves(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

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
            val listening = if (kind == HomeKind.VIDEOS || kind == HomeKind.PHOTOS) emptyList() else mixes(b, kind, now, musicSpaces)
            val recentMusic = if (kind == HomeKind.ALL || kind == HomeKind.MUSIC) b.top(ItemQuery(songs = true, order = app.keepfin.ItemOrder.ADDED), 12) else emptyList()
            return HomeShelves(quick, resume, listening, recent, recentMusic)
        }

        /**
         * The mixes as a few indexed queries (never the whole library): the most played, the longest untouched, the newest, the never played and what
         * was played last make the pool the core reads; the songs of the artist the Because mix names are added when it shows.
         */
        private fun mixes(b: app.keepfin.LibraryBrowse, kind: HomeKind, now: Long, musicSpaces: Set<String>): List<HomeMix> {
            val songs = ItemQuery(songs = true)
            val since = now - 30 * HomeLogic.DAY
            val stale = now - 60 * HomeLogic.DAY
            val offset = java.util.TimeZone.getDefault().getOffset(now) / 60_000
            val pool = LinkedHashMap<String, LibraryItem>()
            fun take(list: List<LibraryItem>) = list.forEach { pool.putIfAbsent(it.id, it) }
            take(b.top(songs.copy(played = true, playedSince = since, order = app.keepfin.ItemOrder.MOST_PLAYED), 24))
            take(b.top(songs.copy(favorite = true, playedBefore = stale, order = app.keepfin.ItemOrder.STALE), 24))
            take(b.top(songs.copy(minPlays = 5, playedBefore = stale, order = app.keepfin.ItemOrder.STALE), 24))
            take(b.top(songs.copy(order = app.keepfin.ItemOrder.ADDED), HomeLogic.MIX_SONGS))
            take(b.top(songs.copy(unplayed = true, order = app.keepfin.ItemOrder.ADDED), 40))
            // the artists played most, and what was played last: the Because and Late-night mixes read these
            val played = b.top(songs.copy(played = true, order = app.keepfin.ItemOrder.MOST_PLAYED), 60)
            take(played)
            take(b.top(songs.copy(everPlayed = true, order = app.keepfin.ItemOrder.RECENT), 100))
            val first = HomeLogic.listening(pool.values.toList(), kind, now, musicSpaces, offset)
            val because = first.firstOrNull { it.mix.id == app.keepfin.core.home.Mixes.BECAUSE } ?: return first
            // the songs by that artist that were never played belong to it too
            take(b.top(songs.copy(text = because.mix.title.removePrefix("Because you play "), textColumns = "artist", order = app.keepfin.ItemOrder.STALE), 100))
            return HomeLogic.listening(pool.values.toList(), kind, now, musicSpaces, offset)
        }
    }
}