package app.keepfin.ui.search

import app.keepfin.LibraryCollection
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.ui.library.LibraryViews
import java.net.URLEncoder

/** The filter row of Search. [wire] is the test tag and the saved name. */
enum class SearchFilter(val wire: String, val label: String) {
    ALL("all", "All"), VIDEOS("videos", "Videos"), SONGS("songs", "Songs"), PHOTOS("photos", "Photos"),
    ALBUMS("albums", "Albums"), ARTISTS("artists", "Artists"), PLAYLISTS("playlists", "Playlists");
}

/** What the library holds for a query, grouped the way Search shows it. */
data class SearchResults(
    val songs: List<LibraryItem>,
    val videos: List<LibraryItem>,
    val photos: List<LibraryItem>,
    val albums: List<LibraryViews.Album>,
    val artists: List<LibraryViews.Artist>,
    val playlists: List<LibraryCollection>,
) {
    val isEmpty get() = songs.isEmpty() && videos.isEmpty() && photos.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
    fun count(filter: SearchFilter) = when (filter) {
        SearchFilter.ALL -> songs.size + videos.size + photos.size + albums.size + artists.size + playlists.size
        SearchFilter.VIDEOS -> videos.size
        SearchFilter.SONGS -> songs.size
        SearchFilter.PHOTOS -> photos.size
        SearchFilter.ALBUMS -> albums.size
        SearchFilter.ARTISTS -> artists.size
        SearchFilter.PLAYLISTS -> playlists.size
    }
}

/** Pure library search: instant, local, diacritic-free ("goransson" finds "Göransson"). */
object SearchLogic {
    fun search(items: List<LibraryItem>, collections: List<LibraryCollection>, query: String): SearchResults {
        val terms = LibraryViews.terms(query)
        if (terms.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val found = items.filter { LibraryViews.matches(it, terms) }.sortedByDescending { it.addedAt }
        val songs = items.filter { it.kind == LibraryItemKind.AUDIO }
        val albums = LibraryViews.albums(songs).filter { a -> terms.all { LibraryViews.fold(a.title + " " + a.artist).contains(it) } }
        val artists = LibraryViews.artists(songs).filter { a -> terms.all { LibraryViews.fold(a.name).contains(it) } }
        val playlists = collections.filter { c -> terms.all { LibraryViews.fold(c.name).contains(it) } }
        return SearchResults(
            songs = found.filter { it.kind == LibraryItemKind.AUDIO },
            videos = found.filter { it.kind == LibraryItemKind.VIDEO },
            photos = found.filter { it.kind == LibraryItemKind.IMAGE },
            albums = albums, artists = artists, playlists = playlists,
        )
    }

    /**
     * The link "Search YouTube for ..." opens in the capture flow: YouTube's own results page, which the download engine reads as a
     * collection (progressive pages, explicit selection). The Android engine accepts web links only, so this is the form it can take.
     */
    fun youtubeSearchLink(query: String): String = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query.trim(), "UTF-8")
}
