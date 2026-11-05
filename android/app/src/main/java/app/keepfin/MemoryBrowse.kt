package app.keepfin

import app.keepfin.core.spaces.Songs
import app.keepfin.ui.library.LibraryViews
import kotlinx.coroutines.flow.StateFlow

/**
 * [LibraryBrowse] over a list held in memory: the MediaStore library and the preview. It answers like the index does (same
 * orders, same keys, same filters), so a screen has one code path whatever the library behind it. The search matches words
 * anywhere in the text where the index matches word starts; the list is small here (the folder library is the one that scales).
 */
class MemoryBrowse(
    private val items: () -> List<LibraryItem>,
    override val changes: StateFlow<Long>,
    private val collections: () -> List<LibraryCollection> = { emptyList() },
    /** The ids of the music spaces: a video kept in one is a song (ItemQuery.songs). */
    private val musicSpaces: () -> Set<String> = { emptySet() },
) : LibraryBrowse {
    override val cheap: Boolean get() = true

    private fun fold(text: String?) = LibraryViewsKeys.fold(text)

    private fun terms(text: String): List<String> = fold(text.trim()).split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun isSong(item: LibraryItem) = Songs.isSong(item.kind.wire, if (item.space in musicSpaces()) SpaceKind.MUSIC.wire else null)

    private fun matches(q: ItemQuery, item: LibraryItem, terms: List<String>): Boolean {
        if (q.spaces.isNotEmpty() && item.space !in q.spaces) return false
        if (q.kinds.isNotEmpty() && item.kind !in q.kinds) return false
        if (q.songs && !isSong(item)) return false
        if (q.favorite && !item.favorite) return false
        if (q.musicVideos && !item.musicVideo) return false
        if (q.unplayed && item.plays != 0) return false
        if (q.played && item.plays <= 0) return false
        if (item.plays < q.minPlays) return false
        if (q.playedSince != null && (item.lastPlayedAt ?: 0L) < q.playedSince) return false
        if (q.playedBefore != null && ((item.lastPlayedAt ?: return false) >= q.playedBefore)) return false
        if (q.everPlayed && item.lastPlayedAt == null) return false
        if (q.resumable && !((item.position ?: 0.0) > 5.0 && !item.watched && (item.kind == LibraryItemKind.AUDIO || item.kind == LibraryItemKind.VIDEO))) return false
        if (q.folder != null && item.folder.trim('/') != q.folder.trim('/')) return false
        if (q.subtree != null) {
            val f = item.folder.trim('/')
            val s = q.subtree.trim('/')
            if (f != s && !f.startsWith("$s/")) return false
        }
        if (q.artist != null && LibraryKeys.artist(item) != q.artist) return false
        if (q.album != null && PlaybackItems.albumKey(item) != q.album) return false
        if (q.ids != null && item.id !in q.ids) return false
        if (q.excludeSpaces.isNotEmpty() && item.space in q.excludeSpaces) return false
        if (q.tagsAny.isNotEmpty() || q.tagsAll.isNotEmpty()) {
            val have = TagLogic.all(item).toSet()
            if (q.tagsAny.isNotEmpty() && q.tagsAny.none { it in have }) return false
            if (q.tagsAll.isNotEmpty() && !q.tagsAll.all { it in have }) return false
        }
        if (terms.isNotEmpty()) {
            val parts = when (q.textColumns) {
                null -> listOfNotNull(item.title, item.artist, item.album, item.albumArtist, item.genre, item.folder).plus(item.tags).plus(item.userTags)
                else -> q.textColumns.split(' ').flatMap { c -> when (c) { "album" -> listOfNotNull(item.album); "artist" -> listOfNotNull(item.artist, item.albumArtist); "title" -> listOf(item.title); else -> emptyList() } }
            }
            val haystack = fold(parts.joinToString(" "))
            if (!terms.all { haystack.contains(it) }) return false
        }
        return true
    }

    private fun select(q: ItemQuery): List<LibraryItem> {
        val t = terms(q.text)
        var list = items().filter { matches(q, it, t) }
        val smart = q.smart
        if (smart != null) list = SmartLogic.evaluate(list, smart, q.smartContext ?: SmartContext.of(collections()))
        return list
    }

    override fun keyOf(item: LibraryItem, order: ItemOrder): PageKey = when (order) {
        ItemOrder.ADDED -> listOf(item.addedAt, item.id)
        ItemOrder.TITLE -> listOf(fold(item.title), item.id)
        ItemOrder.ARTIST -> listOf(fold(item.artist ?: item.albumArtist), fold(item.album), (item.disc ?: 1).toLong(), (item.track ?: Int.MAX_VALUE).toLong(), fold(item.title), item.id)
        ItemOrder.DURATION -> listOf(item.duration ?: 0.0, item.id)
        ItemOrder.SIZE -> listOf(item.size, item.id)
        ItemOrder.MOST_PLAYED -> listOf(item.plays.toLong(), item.lastPlayedAt ?: 0L, item.id)
        ItemOrder.RECENT, ItemOrder.STALE -> listOf(item.lastPlayedAt ?: 0L, item.id)
        ItemOrder.TRACK -> listOf((item.disc ?: 1).toLong(), (item.track ?: Int.MAX_VALUE).toLong(), fold(item.title), item.id)
    }

    private fun compare(a: PageKey, b: PageKey): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i]
            val y = b[i]
            val c = if (x is Number && y is Number) x.toDouble().compareTo(y.toDouble()) else x.toString().compareTo(y.toString())
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    override fun page(query: ItemQuery, direction: PageDirection, key: PageKey?, limit: Int): PageResult {
        val desc = query.order.descending
        val keyed = select(query).map { keyOf(it, query.order) to it }
            .sortedWith { x, y -> if (desc) compare(y.first, x.first) else compare(x.first, y.first) }
        // "after" in the order: a key that sorts later (greater for ascending, smaller for descending)
        fun after(k: PageKey, inclusive: Boolean) = keyed.indexOfFirst { val c = compare(it.first, k); val sign = if (desc) -c else c; if (inclusive) sign >= 0 else sign > 0 }.let { if (it < 0) keyed.size else it }
        val start: Int
        val end: Int
        when {
            direction == PageDirection.FIRST || key == null -> { start = 0; end = minOf(limit, keyed.size) }
            direction == PageDirection.AFTER -> { start = after(key, false); end = minOf(keyed.size, start + limit) }
            direction == PageDirection.AT -> { start = after(key, true); end = minOf(keyed.size, start + limit) }
            else -> { end = after(key, true); start = maxOf(0, end - limit) }
        }
        val slice = keyed.subList(start, maxOf(start, end))
        return PageResult(slice.map { it.second }, if (start > 0 && slice.isNotEmpty()) slice.first().first else null, if (end < keyed.size && slice.isNotEmpty()) slice.last().first else null)
    }

    override fun count(query: ItemQuery): Int = select(query).size

    override fun counts(query: ItemQuery): Counts {
        val list = select(query)
        return Counts(
            list.size, list.count { it.kind == LibraryItemKind.AUDIO }, list.count { it.kind == LibraryItemKind.VIDEO },
            list.count { it.kind == LibraryItemKind.IMAGE }, list.count { it.kind == LibraryItemKind.OTHER }, list.sumOf { it.size },
        )
    }

    override fun facets(field: SmartField, limit: Int): List<Facet> {
        val counts = HashMap<String, Int>()
        for (item in items()) {
            when (field) {
                SmartField.TAG -> TagLogic.all(item).forEach { counts.merge(it, 1, Int::plus) }
                SmartField.ARTIST -> listOfNotNull(item.artist, item.albumArtist).filter { it.isNotBlank() }.distinct().forEach { counts.merge(it, 1, Int::plus) }
                SmartField.ALBUM -> item.album?.takeIf { it.isNotBlank() }?.let { counts.merge(it, 1, Int::plus) }
                SmartField.SOURCE -> item.sourceUrl?.let(Providers::name)?.let { counts.merge(it, 1, Int::plus) }
                else -> {}
            }
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(limit).map { Facet(it.key, it.value) }
    }

    override fun spaceCounts(query: ItemQuery): Map<String, Counts> = select(query).groupBy { it.space }.mapValues { (_, list) ->
        Counts(
            list.size, list.count { it.kind == LibraryItemKind.AUDIO }, list.count { it.kind == LibraryItemKind.VIDEO },
            list.count { it.kind == LibraryItemKind.IMAGE }, list.count { it.kind == LibraryItemKind.OTHER }, list.sumOf { it.size },
        )
    }

    override fun outsideCounts(space: String): Map<String, Int> {
        val spaceOf = items().associate { it.id to it.space }
        return collections().associate { c -> c.id to c.itemIds.count { id -> spaceOf[id]?.let { it != space } == true } }
    }

    override fun spacesOf(ids: Collection<String>): Set<String> {
        val wanted = ids.toHashSet()
        return items().filter { it.id in wanted }.mapTo(HashSet()) { it.space }
    }

    override fun byIds(ids: List<String>): List<LibraryItem> {
        val by = items().associateBy { it.id }
        return ids.mapNotNull { by[it] }
    }

    override fun item(id: String): LibraryItem? = items().firstOrNull { it.id == id }

    override fun byJob(jobId: String): LibraryItem? = items().firstOrNull { it.jobId == jobId }

    override fun byUri(uri: String): LibraryItem? = items().firstOrNull { it.uri == uri }

    private fun albumGroup(a: LibraryViews.Album): AlbumGroup = AlbumGroup(
        key = a.key, space = a.space, title = a.title, artist = a.artist, year = a.year, tracks = a.tracks.size, duration = a.duration, discs = a.discs,
        compilation = a.compilation, routeKey = a.routeKey, cover = a.tracks.firstOrNull(), cursor = "${fold(a.title)}\u0000${a.space}\u0000${a.routeKey}",
    )

    override fun albums(query: ItemQuery, after: String?, limit: Int, byArtist: Boolean): List<AlbumGroup> {
        val songs = select(query.copy(kinds = emptySet(), songs = true))
        val groups = LibraryViews.albums(songs).map { albumGroup(it).let { g -> if (byArtist) g.copy(cursor = "${it.space}\u0000${it.routeKey}") else g } }.sortedBy { it.cursor }
        return groups.filter { after == null || it.cursor > after }.take(limit)
    }

    override fun albumCount(query: ItemQuery): Int = LibraryViews.albums(select(query.copy(kinds = emptySet(), songs = true))).size

    override fun artistCount(query: ItemQuery): Int = select(query.copy(kinds = emptySet(), songs = true)).map { LibraryKeys.artist(it) }.toSet().size

    override fun album(space: String, routeKey: String): AlbumGroup? =
        LibraryViews.albums(items().filter { it.space == space && isSong(it) }).firstOrNull { it.routeKey == routeKey }?.let(::albumGroup)

    override fun artists(query: ItemQuery, after: String?, limit: Int): List<ArtistGroup> {
        val songs = select(query.copy(kinds = emptySet(), songs = true))
        return songs.groupBy { LibraryKeys.artist(it) }.map { (key, list) ->
            ArtistGroup(
                (list.first().artist ?: list.first().albumArtist)?.takeIf { it.isNotBlank() } ?: "Unknown artist", key, list.size,
                list.mapNotNull { PlaybackItems.albumKey(it) }.distinct().size, list.first(), key,
            )
        }.sortedBy { it.key }.filter { after == null || it.key > after }.take(limit)
    }

    override fun artist(key: String, space: String?): ArtistGroup? = artists(ItemQuery(spaces = listOfNotNull(space), artist = key), null, 1).firstOrNull()

    override fun albumsOfArtist(artistKey: String, space: String?): List<AlbumGroup> {
        val songs = select(ItemQuery(spaces = listOfNotNull(space), artist = artistKey, songs = true))
        val keys = songs.mapNotNull { it.space to (PlaybackItems.albumKey(it) ?: return@mapNotNull null) }.toSet()
        return LibraryViews.albums(items().filter { isSong(it) && (it.space to (PlaybackItems.albumKey(it) ?: "")) in keys }).map(::albumGroup)
    }

    override fun folderList(space: String, kinds: Set<LibraryItemKind>): List<FolderGroup> =
        items().filter { it.space == space && it.folder.isNotBlank() && (kinds.isEmpty() || it.kind in kinds) }.groupBy { it.folder.trim('/') }
            .map { (path, list) -> FolderGroup(path.substringAfterLast('/'), path, list.size, list.first()) }.sortedBy { fold(it.path) }

    override fun folders(space: String, folder: String): List<FolderGroup> {
        val (cards, _) = LibraryViews.level(items().filter { it.space == space }, folder)
        return cards.map { FolderGroup(it.name, it.path, it.count, it.cover) }
    }

    override fun tags(query: ItemQuery, minItems: Int): List<ScopedTag> = TagLogic.inScope(select(query), minItems)
}
