package app.keepfin.ui.library

/** Where the Library tab is. Detail routes sit on top of a space; Back pops one level. */
sealed interface LibraryRoute {
    data object Home : LibraryRoute
    data class Space(val id: String) : LibraryRoute
    data object Playlists : LibraryRoute
    /** Every space, folders first, then smart spaces, with search. */
    data object AllSpaces : LibraryRoute
    /** Every collection of the library (the "See all" of the Collections shelf). */
    data object Collections : LibraryRoute
    /** Every song's music video as a row (the "See all" of the Music videos shelf). */
    data object MusicVideos : LibraryRoute
    /** The smart rules editor for [draft], a space being created or edited. */
    data class Rules(val draft: app.keepfin.Space, val creating: Boolean) : LibraryRoute
    data object Manage : LibraryRoute
    /** The Keepfin trash: files deleted from the Keepfin folder, kept for 30 days. */
    data object Trash : LibraryRoute
    data class Album(val space: String, val key: String) : LibraryRoute
    data class Artist(val space: String, val name: String) : LibraryRoute
    data class UserPlaylist(val id: String) : LibraryRoute
    /** A playlist/album/mix downloaded together, shown in the order it was downloaded. */
    data class Collection(val space: String, val id: String) : LibraryRoute
    data class Folder(val space: String, val path: String) : LibraryRoute
    /** A smart list over one music space, or over every music space when [space] is null. */
    data class SmartList(val kind: LibraryViews.Smart, val space: String?) : LibraryRoute
    /** "See all" of a Home row. */
    data class Section(val section: HomeSection) : LibraryRoute
    /** Files that are in the library more than once: among [ids] (a collection) when given, else what [query] selects. [title] names the scope. */
    data class Duplicates(val title: String, val query: app.keepfin.ItemQuery = app.keepfin.ItemQuery(), val ids: List<String>? = null) : LibraryRoute
}

enum class HomeSection(val title: String) {
    CONTINUE("Continue watching"), RECENTLY_ADDED("Recently added"), RECENTLY_PLAYED("Recently played"), FAVORITES("Favorites"),
}

/** Tabs of a music space. Video and mixed spaces use Songs (their items) and Collections. */
enum class MusicTab(val label: String) { SONGS("Songs"), COLLECTIONS("Collections"), ALBUMS("Albums"), ARTISTS("Artists") }

/** A pending system consent (files from before a reinstall). Launch [sender]; then call the VM's accessResult. */
class PendingAccess(val sender: android.content.IntentSender, val ids: List<String>, val delete: Boolean, val afterDelete: ((Boolean) -> Unit)? = null, val retry: () -> Unit)

/** Full-screen photo viewer request: the ordered photos of a folder and the one tapped. */
data class ImageRequest(val ids: List<String>, val index: Int)

/** Full-screen video request. [startMs] is where playback begins; [resumed] shows the "Start over" prompt. */
data class VideoRequest(val id: String, val startMs: Long, val resumed: Boolean, val context: List<String> = emptyList(), val musicVideo: Boolean = false,
    /** Opened by the full-screen button on the picture in the full player: closing it goes back to that picture, still playing. */
    val fromPlayer: Boolean = false)

/** Pages whose header is an artwork-coloured band that runs up under the status bar and the transparent top bar. */
fun LibraryRoute.banded(): Boolean = this is LibraryRoute.Album || this is LibraryRoute.Artist || this is LibraryRoute.UserPlaylist || this is LibraryRoute.Collection || this is LibraryRoute.SmartList

