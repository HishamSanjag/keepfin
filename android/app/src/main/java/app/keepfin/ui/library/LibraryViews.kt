package app.keepfin.ui.library

import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.PlaybackItems
import app.keepfin.core.playback.Playback
import java.text.Normalizer
import java.util.Locale

/**
 * Pure grouping, sorting and searching used by every Library surface, so screens and tests agree on what a view shows.
 * Nothing here touches Android.
 */
object LibraryViews {
    private val marks = Regex("\\p{Mn}+")

    /** Lower-case, diacritic-free text for instant search ("Göransson" matches "goransson"). */
    fun fold(text: String?): String = if (text.isNullOrEmpty()) "" else marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    fun terms(query: String): List<String> = fold(query.trim()).split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun matches(item: LibraryItem, terms: List<String>): Boolean {
        if (terms.isEmpty()) return true
        val haystack = fold(listOfNotNull(item.title, item.artist, item.album, item.albumArtist, item.genre, item.folder).joinToString(" "))
        return terms.all { haystack.contains(it) }
    }

    fun search(items: List<LibraryItem>, query: String): List<LibraryItem> = terms(query).let { t -> if (t.isEmpty()) items else items.filter { matches(it, t) } }

    // ---- sorting -----------------------------------------------------------------------------------

    enum class Sort(val label: String) {
        ADDED("Recently added"), TITLE("Title"), ARTIST("Artist"), DURATION("Duration"), SIZE("Size"), MOST_PLAYED("Most played");
        companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: ADDED }
    }

    private fun title(item: LibraryItem) = fold(item.title)

    fun sorted(items: List<LibraryItem>, sort: Sort): List<LibraryItem> = when (sort) {
        Sort.ADDED -> items.sortedByDescending { it.addedAt }
        Sort.TITLE -> items.sortedWith(compareBy({ title(it) }, { it.addedAt }))
        Sort.ARTIST -> items.sortedWith(compareBy({ fold(it.artist ?: it.albumArtist) }, { fold(it.album) }, { it.disc ?: 1 }, { it.track ?: Int.MAX_VALUE }, { title(it) }))
        Sort.DURATION -> items.sortedByDescending { it.duration ?: 0.0 }
        Sort.SIZE -> items.sortedByDescending { it.size }
        Sort.MOST_PLAYED -> items.sortedWith(compareByDescending<LibraryItem> { it.plays }.thenByDescending { it.lastPlayedAt ?: 0L })
    }

    // ---- smart lists -------------------------------------------------------------------------------

    enum class Smart(val label: String, val hint: String) {
        RECENTLY_ADDED("Recently added", "Newest songs first"),
        RECENTLY_PLAYED("Recently played", "What you listened to last"),
        MOST_PLAYED("Most played", "Your top songs"),
        NEVER_PLAYED("Never played", "Songs you have not heard yet"),
        FAVORITES("Favorites", "Songs and videos you marked with a heart");
    }

    fun smart(kind: Smart, items: List<LibraryItem>): List<LibraryItem> = when (kind) {
        Smart.RECENTLY_ADDED -> items.sortedByDescending { it.addedAt }
        Smart.RECENTLY_PLAYED -> items.filter { it.lastPlayedAt != null }.sortedByDescending { it.lastPlayedAt }
        Smart.MOST_PLAYED -> items.filter { it.plays > 0 }.sortedWith(compareByDescending<LibraryItem> { it.plays }.thenByDescending { it.lastPlayedAt ?: 0L })
        Smart.NEVER_PLAYED -> items.filter { it.plays == 0 }.sortedByDescending { it.addedAt }
        Smart.FAVORITES -> items.filter { it.favorite }.sortedByDescending { it.addedAt }
    }

    // ---- albums and artists --------------------------------------------------------------------------

    data class Album(
        val key: String,
        val space: String,
        val title: String,
        val artist: String,
        val year: Int?,
        val tracks: List<LibraryItem>,
        val art: String?,
        val duration: Double,
        val discs: Int,
        val compilation: Boolean,
        /** Key without the space, as used by [LibraryRoute.Album]. */
        val routeKey: String,
    )

    private val variousNames = setOf("various artists", "various", "va", "compilation")

    fun trackOrder(): Comparator<LibraryItem> = compareBy({ it.disc ?: 1 }, { it.track ?: Int.MAX_VALUE }, { title(it) })

    /** Albums of the songs, compilations grouped by album artist ("Various Artists"). Tracks are in disc and track order. */
    fun albums(songs: List<LibraryItem>): List<Album> =
        songs.filter { !it.album.isNullOrBlank() }.groupBy { it.space + "/" + (PlaybackItems.albumKey(it) ?: "") }.map { (key, list) ->
            val ordered = list.sortedWith(trackOrder())
            val first = ordered.first()
            val artists = ordered.mapNotNull { it.artist?.takeIf(String::isNotBlank) }.map(::fold).distinct()
            val albumArtist = first.albumArtist?.takeIf { it.isNotBlank() }
            val compilation = albumArtist?.let { fold(it) in variousNames } == true || (albumArtist != null && artists.size > 1)
            Album(
                key = key, space = first.space, title = first.album.orEmpty(),
                artist = albumArtist ?: if (artists.size > 1) "Various Artists" else first.artist.orEmpty().ifBlank { "Unknown artist" },
                year = ordered.mapNotNull { it.year }.minOrNull(), tracks = ordered, art = ordered.firstNotNullOfOrNull { it.art },
                duration = ordered.sumOf { it.duration ?: 0.0 }, discs = ordered.map { it.disc ?: 1 }.distinct().size, compilation = compilation,
                routeKey = PlaybackItems.albumKey(first).orEmpty(),
            )
        }.sortedBy { fold(it.title) }

    data class Artist(val name: String, val songs: List<LibraryItem>, val albums: List<Album>, val art: String?)

    /** Artists by track artist (an album artist also lists the compilation's songs under each real artist). */
    fun artists(songs: List<LibraryItem>): List<Artist> {
        val albums = albums(songs)
        return songs.groupBy { (it.artist ?: it.albumArtist)?.takeIf(String::isNotBlank) ?: "Unknown artist" }.map { (name, list) ->
            val owned = albums.filter { album -> album.tracks.any { track -> track in list } }
            Artist(name, list.sortedWith(compareBy<LibraryItem> { fold(it.album) }.then(trackOrder())), owned, list.firstNotNullOfOrNull { it.art })
        }.sortedBy { fold(it.name) }
    }

    // ---- folders -------------------------------------------------------------------------------------

    data class FolderCard(val name: String, val path: String, val count: Int, val cover: LibraryItem?)

    /** The immediate sub-folders of [path] and the items that sit directly in it. */
    fun level(items: List<LibraryItem>, path: String): Pair<List<FolderCard>, List<LibraryItem>> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val here = ArrayList<LibraryItem>()
        val below = LinkedHashMap<String, MutableList<LibraryItem>>()
        for (item in items) {
            val folder = item.folder.trim('/')
            when {
                folder == path -> here.add(item)
                path.isEmpty() && folder.isNotEmpty() || folder.startsWith(prefix) -> {
                    val name = folder.removePrefix(prefix).substringBefore('/')
                    below.getOrPut(name) { ArrayList() }.add(item)
                }
            }
        }
        val cards = below.map { (name, list) -> FolderCard(name, prefix + name, list.size, list.firstOrNull { it.kind == LibraryItemKind.VIDEO || it.art != null } ?: list.first()) }
            .sortedBy { fold(it.name) }
        return cards to here
    }

    /** Videos (and long audio) with a saved position that are not watched, last watched first. */
    fun continueWatching(items: List<LibraryItem>): List<LibraryItem> =
        items.filter { it.kind != LibraryItemKind.IMAGE && it.kind != LibraryItemKind.OTHER && (it.position ?: 0.0) > 5.0 && !it.watched }
            .sortedByDescending { it.lastPlayedAt ?: 0L }

    fun progress(item: LibraryItem): Float {
        val duration = item.duration ?: return 0f
        val position = item.position ?: return if (item.watched) 1f else 0f
        return if (duration <= 0) 0f else (position / duration).toFloat().coerceIn(0f, 1f)
    }

    /** Next video in the same folder after [item], in the shared natural title order (the playback core); null at the end. */
    fun nextInFolder(item: LibraryItem, all: List<LibraryItem>): LibraryItem? = inFolder(item, all, 1)

    /** The video before [item] in the same folder, in the shared natural title order; null at the start. */
    fun previousInFolder(item: LibraryItem, all: List<LibraryItem>): LibraryItem? = inFolder(item, all, -1)

    private fun inFolder(item: LibraryItem, all: List<LibraryItem>, dir: Int): LibraryItem? {
        val siblings = all.filter { it.space == item.space && it.folder == item.folder && it.kind == item.kind }
        val order = Playback.folderOrder(siblings.map { it.id }, siblings.map { it.title })
        val id = Playback.neighbour(order, item.id, dir) ?: return null
        return siblings.firstOrNull { it.id == id }
    }

    /** The id [step] places from [id] in a list a video was opened from (+1 next, -1 previous); null at either end or when [id] is not in it. */
    fun neighbourInContext(context: List<String>, id: String, step: Int): String? = Playback.neighbour(context, id, step)

    /** At most [max] ids of [ids] centred on [id] (the list a video carries, so a huge folder stays bounded). */
    fun windowAround(ids: List<String>, id: String, max: Int = 500): List<String> {
        if (ids.size <= max) return ids
        val at = ids.indexOf(id).coerceAtLeast(0)
        val from = (at - max / 2).coerceIn(0, ids.size - max)
        return ids.subList(from, from + max)
    }

    /** "Episode 2" sorts before "Episode 10": the title order of the shared playback core, the same on Windows. */
    fun naturalTitles(): Comparator<LibraryItem> = Comparator { a, b -> Playback.compareTitles(a.title, b.title) }

    fun totalDuration(items: List<LibraryItem>): Double = items.sumOf { it.duration ?: 0.0 }

    fun mmss(seconds: Double?): String {
        val total = (seconds ?: 0.0).toLong().coerceAtLeast(0)
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    /** "1 h 12 min" / "42 min" for album and playlist headers. */
    fun spoken(seconds: Double): String {
        val minutes = (seconds / 60).toLong()
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "${maxOf(1, minutes)} min"
    }
}
