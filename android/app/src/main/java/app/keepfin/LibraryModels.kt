package app.keepfin

// Library contract: spaces, gallery items and playback. Design: dev-docs/architecture/LIBRARY.md.
// Mirrors gui/shared/library.ts. Android spaces are folder names under the standard roots:
// audio → Music/<folder>, video → Movies/<folder>, everything else → Download/<folder>.

enum class SpaceKind(val wire: String) { MUSIC("music"), VIDEO("video"), MIXED("mixed") }

/** A folder space stores files and receives downloads; a smart space stores nothing and gathers items by [SmartRules]. */
enum class SpaceType(val wire: String) { FOLDER("folder"), SMART("smart") }

/** Eight palette colors; each is tuned for light and dark themes by the UI. */
enum class SpaceColor(val wire: String) { COBALT("cobalt"), VIOLET("violet"), TEAL("teal"), OLIVE("olive"), AMBER("amber"), RUST("rust"), BERRY("berry"), SLATE("slate") }

enum class SpaceIcon(val wire: String) {
    MUSIC("music"), VIDEO("video"), FOLDER("folder"), STAR("star"), HEART("heart"), MIC("mic"), BOOK("book"), DUMBBELL("dumbbell"), MOON("moon"), CHILD("child")
}

enum class SmartMatch(val wire: String) { ANY("any"), ALL("all") }

/** Fields a smart rule can compare against a list of values (values are ORed within one rule). */
enum class SmartField(val wire: String) {
    TAG("tag"), COLLECTION("collection"), SPACE("space"), SOURCE("source"), ARTIST("artist"), ALBUM("album"), KIND("kind")
}

sealed interface SmartRule {
    data class Values(val field: SmartField, val values: List<String>) : SmartRule
    /** Added to the library within the last [days] days. */
    data class AddedWithin(val days: Int) : SmartRule
    data object Favorite : SmartRule
    /** Never played. */
    data object Unplayed : SmartRule
}

/** [match] combines the rules; [onlyKind] and [excludeSpaces] apply after, to the result. */
data class SmartRules(
    val match: SmartMatch = SmartMatch.ANY,
    val rules: List<SmartRule> = emptyList(),
    val onlyKind: LibraryItemKind? = null,
    val excludeSpaces: List<String> = emptyList(),
)

enum class FolderLayout(val wire: String) { FLAT("flat"), COLLECTION("collection"), PLATFORM("platform"), ARTIST_ALBUM("artist-album") }

/** File name style per space. */
enum class SpaceNaming(val wire: String) { TITLE("title"), TITLE_UPLOADER("title-uploader"), UPLOADER_TITLE("uploader-title"), DATE_TITLE("date-title"), ARTIST_TITLE("artist-title") }

data class Space(
    /** "downloads" | "music" | "videos" for built-ins; a random id for user spaces. */
    val id: String,
    val name: String,
    val kind: SpaceKind,
    /** Folder name under each standard root, e.g. "Keepfin Music" → Music/Keepfin Music. "Keepfin" for Downloads (legacy layout). */
    val folder: String,
    val layout: FolderLayout = FolderLayout.COLLECTION,
    val naming: SpaceNaming = SpaceNaming.TITLE,
    /** Check for the same recording before downloading; matches are deselected/flagged, never silently dropped. */
    val skipExisting: Boolean = false,
    /** Subfolder names the scan ignores; folders containing .nomedia are always ignored. */
    val ignore: List<String> = emptyList(),
    /** Also list Keepfin-readable files already in this folder that Keepfin did not download or import. */
    val scanFolder: Boolean = false,
    /** Smart spaces have no folder (empty), layout, naming or skip-existing and are never a download target. */
    val type: SpaceType = SpaceType.FOLDER,
    /** Null = the built-in tone (music cobalt, videos violet, downloads slate; custom spaces rotate). [SpaceLogic.sanitize] fills it in. */
    val color: SpaceColor? = null,
    /** Null = from [kind]. */
    val icon: SpaceIcon? = null,
    /** Shown on the home hub. Null = pinned for built-ins only. */
    val pinned: Boolean? = null,
    /** Smart spaces only. */
    val rules: SmartRules? = null,
    /** The synthesized "This device" space (music already on this phone): never saved, never a download, route or move target. */
    val device: Boolean = false,
) {
    /** Downloads, Music or Videos: the folder spaces every library has. Only these are folder-only (never smart). */
    val builtin get() = id in BUILTIN_SPACE_IDS
    /** A kept space: the three built-ins and Favorites. Every library has them; they can be renamed and edited but never removed. */
    val kept get() = app.keepfin.core.spaces.Spaces.isKept(id)
    val smart get() = type == SpaceType.SMART
    val isPinned get() = pinned ?: builtin
}

val BUILTIN_SPACE_IDS = setOf("downloads", "music", "videos")

/** The id of the "This device" space and the prefix of its item ids (`dm-<volume>-<key>`, from the shared core). */
const val DEVICE_SPACE_ID = "device"
const val DEVICE_ITEM_PREFIX = "dm-"

fun isDeviceItem(id: String): Boolean = id.startsWith(DEVICE_ITEM_PREFIX)

/** The "This device" space: music already on the phone, listed in place and never copied. */
fun deviceSpace(): Space = Space(
    DEVICE_SPACE_ID, "This device", SpaceKind.MUSIC, "", FolderLayout.FLAT, SpaceNaming.TITLE, skipExisting = false, color = SpaceColor.TEAL, icon = SpaceIcon.MUSIC,
    pinned = true, device = true,
)

/** What a library without the feature answers for [LibraryApi.deviceMusic]. */
val NO_DEVICE_MUSIC: kotlinx.coroutines.flow.StateFlow<DeviceMusicState> = kotlinx.coroutines.flow.MutableStateFlow(DeviceMusicState.Off)

/** Where [LibraryApi.deviceMusic] stands: off, waiting for the audio permission, reading the phone's music, or listing [Ready.count] songs. */
sealed interface DeviceMusicState {
    data object Off : DeviceMusicState
    data object NeedsPermission : DeviceMusicState
    data class Scanning(val found: Int) : DeviceMusicState
    data class Ready(val count: Int) : DeviceMusicState
}

/** The three folder built-ins, then Favorites: what a new library starts with. */
fun defaultSpaces(): List<Space> = defaultFolderSpaces() + favoritesSpace()

fun defaultFolderSpaces(): List<Space> = listOf(
    Space("downloads", "Downloads", SpaceKind.MIXED, "Keepfin", FolderLayout.COLLECTION, SpaceNaming.TITLE, skipExisting = false, color = SpaceColor.SLATE, icon = SpaceIcon.FOLDER, pinned = true),
    Space("music", "Music", SpaceKind.MUSIC, "Keepfin Music", FolderLayout.COLLECTION, SpaceNaming.TITLE, skipExisting = true, color = SpaceColor.COBALT, icon = SpaceIcon.MUSIC, pinned = true),
    Space("videos", "Videos", SpaceKind.VIDEO, "Keepfin Videos", FolderLayout.COLLECTION, SpaceNaming.TITLE, skipExisting = false, color = SpaceColor.VIOLET, icon = SpaceIcon.VIDEO, pinned = true),
)

/**
 * Favorites: everything marked with a heart, from every space. A kept smart space: every library has it, it can be renamed and its
 * rules edited, but never removed. Name, color and icon come from the shared core, like the Windows app's.
 */
fun favoritesSpace(): Space = Space(
    app.keepfin.core.spaces.Spaces.FAVORITES_ID, app.keepfin.core.spaces.Spaces.FAVORITES_NAME, SpaceKind.MIXED, "", FolderLayout.FLAT, SpaceNaming.TITLE, skipExisting = false,
    type = SpaceType.SMART, color = SpaceColor.entries.first { it.wire == app.keepfin.core.spaces.Spaces.FAVORITES_COLOR },
    icon = SpaceIcon.entries.first { it.wire == app.keepfin.core.spaces.Spaces.FAVORITES_ICON }, pinned = true,
    rules = SmartRules(SmartMatch.ANY, listOf(SmartRule.Favorite)),
)

/** The kept spaces Keepfin can make by itself, by id (the folder built-ins need a folder of their own). */
val KEPT_SPACE_MAKERS: Map<String, () -> Space> = mapOf(app.keepfin.core.spaces.Spaces.FAVORITES_ID to ::favoritesSpace)

/**
 * [spaces] with every missing kept space added, by id alone (so a renamed one is still there and two devices that both add one make
 * the same space). Favorites goes right after Videos, else at the end. The same list when nothing is missing.
 */
fun withKeptSpaces(spaces: List<Space>): List<Space> {
    val missing = app.keepfin.core.spaces.Spaces.missingKept(spaces.map { it.id }).mapNotNull { KEPT_SPACE_MAKERS[it]?.invoke() }
    if (missing.isEmpty()) return spaces
    val out = spaces.toMutableList()
    val at = out.indexOfFirst { it.id == "videos" }.let { if (it < 0) out.size else it + 1 }
    out.addAll(at, missing)
    return out
}

data class LibrarySettings(
    /** Spaces + gallery. Off = plain downloader (today's folders), Library shows history. */
    val enabled: Boolean = true,
    /** True: Keepfin's player opens media; false: the system's default app. */
    val builtInPlayer: Boolean = true,
    /** Volume normalisation (attenuation only). */
    val normalize: Boolean = true,
    /** Crossfade seconds between songs, 0 = off (0–12). Windows only: kept and synced here, with no setting on the phone. */
    val crossfade: Int = 0,
    val spaces: List<Space> = defaultSpaces(),
    /** Default space ids for music-service / YouTube Music links and for everything else. */
    val musicRoute: String = "music",
    val otherRoute: String = "downloads",
    /** Take genre, tags and categories from a download's metadata. Existing items are not backfilled. */
    val importTags: Boolean = true,
    /** Old data from when Favorites was offered once and could be removed. Still read and kept; nothing relies on it any more (kept spaces are ensured by id). */
    val offered: List<String> = emptyList(),
) {
    /** Spaces that store files and can receive downloads, routes and moves. Smart spaces are never in this list. */
    val folderSpaces: List<Space> get() = spaces.filter { it.type == SpaceType.FOLDER && !it.device }
}

enum class LibraryItemKind(val wire: String) { VIDEO("video"), AUDIO("audio"), IMAGE("image"), OTHER("other") }

data class LibraryItem(
    /** Stable id: MediaStore content URI string for public files. */
    val id: String,
    val space: String,
    val uri: String,
    /** Folder relative to the space root, "/"-separated, "" at the top level. Collection folders act as playlists. */
    val folder: String,
    val kind: LibraryItemKind,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: Int? = null,
    val year: Int? = null,
    /** Seconds. */
    val duration: Double? = null,
    val size: Long,
    val modifiedAt: Long,
    val addedAt: Long,
    val sourceUrl: String? = null,
    /** "youtube:<id>", "spotify:<id>", "apple-music:<id>", "deezer:<id>". */
    val sourceId: String? = null,
    val jobId: String? = null,
    /** Local artwork file (cover or poster), if any. */
    val art: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Resume position in seconds (video). */
    val position: Double? = null,
    val plays: Int = 0,
    val lastPlayedAt: Long? = null,
    val favorite: Boolean = false,
    /** Watched past 92% (video). */
    val watched: Boolean = false,
    /** Embedded lyrics tag or .lrc sidecar exists. */
    val hasLyrics: Boolean = false,
    /** Volume normalisation gain in dB (ReplayGain tag or analysed loudness). */
    val gain: Double? = null,
    val disc: Int? = null,
    val genre: String? = null,
    /** Imported from the source (genre, tags, categories): lowercase, trimmed, deduped, at most 15, each at most 30 characters. */
    val tags: List<String> = emptyList(),
    /** Added by the user; always shown. */
    val userTags: List<String> = emptyList(),
    /** In the library but its file is not on this phone (a joined library, an item downloaded on another device): [uri] is empty. */
    val remote: Boolean = false,
    /** `sha256:<hex>` of the file, which a paired device that holds it is asked for. */
    val contentId: String? = null,
    /** Video chapters, in order. Empty in the lists (the index holds none); the player reads them with [LibraryApi.chapters]. */
    val chapters: List<LibraryChapter> = emptyList(),
    /** A song that keeps its official music video beside it (`Song.video.mp4`, dev-docs/plans/MUSIC-VIDEOS.md): the full player offers Song | Video. */
    val musicVideo: Boolean = false,
)

/** A chapter of a video: where it starts (seconds) and its title. */
data class LibraryChapter(val start: Double, val title: String)

/** Kinds of [LibraryCollection]. [OWN] is the user's own list (formerly a playlist). */
enum class CollectionKind(val wire: String) { PLAYLIST("playlist"), ALBUM("album"), CHANNEL("channel"), OWN("own") }

enum class FollowMode(val wire: String) { AUTO("auto"), MANUAL("manual"), OFF("off") }

/** Canonical link of a downloaded collection; [id] is the playlist, channel or album id when known. */
data class CollectionSource(val url: String, val provider: String, val id: String? = null)

/** More new items than [Follow.confirmAbove] were found; they wait for the user's "Review". */
data class PendingCheck(val count: Int, val at: Long)

data class Follow(
    val mode: FollowMode = FollowMode.OFF,
    /** 1, 6, 12, 24 or 168. */
    val intervalHours: Int = 6,
    val wifiOnly: Boolean = true,
    val notify: Boolean = true,
    /** More new items than this wait for the user. */
    val confirmAbove: Int = 20,
    val lastCheckedAt: Long? = null,
    /** Friendly message, no tool names. */
    val lastError: String? = null,
    val pending: PendingCheck? = null,
)

/**
 * A playlist, album or channel downloaded together, or a list of the user's own ([CollectionKind.OWN]). Named LibraryCollection
 * because a type called `Collection` would shadow kotlin.collections.Collection in this package.
 */
data class LibraryCollection(
    val id: String,
    val name: String,
    val kind: CollectionKind,
    /** Folder space it was downloaded into; for [CollectionKind.OWN] the space it was created in (it may hold items from any space). */
    val space: String,
    /** Play order: source order for downloaded collections. */
    val itemIds: List<String> = emptyList(),
    val coverItemId: String? = null,
    val source: CollectionSource? = null,
    val follow: Follow? = null,
    /** Source item keys ever discovered for this collection (downloaded, deselected or deleted): never downloaded again by following. */
    val seen: List<String> = emptyList(),
    /** Added by the last check; cleared when the collection is opened. */
    val newItemIds: List<String> = emptyList(),
    /** Gone from the source, kept locally. */
    val removedItemIds: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)

data class LyricLine(val timeMs: Long?, val text: String)
data class Lyrics(val synced: Boolean, val lines: List<LyricLine>)

/**
 * What the library says besides its items: the collections (small), whether a scan runs, and problems to show. The items are never
 * in it: a library of 200,000 items is read in pages through [LibraryApi.browse].
 */
data class LibrarySnapshot(
    val collections: List<LibraryCollection> = emptyList(),
    val scanning: Boolean = false,
    val scannedAt: Long? = null,
    val problems: List<String> = emptyList(),
) {
    /** The user's own lists (the old "playlists"). */
    val playlists: List<LibraryCollection> get() = collections.filter { it.kind == CollectionKind.OWN }
}

/** The song you already have behind a video link (any space) and whether it has its music video (Capture, and a video download that joins it). */
data class SongOfLink(val itemId: String, val space: String, val hasVideo: Boolean)

/** Where a song's music video file is and how big it is (bytes). Its length is read from the file ([app.keepfin.ui.player.MusicVideoLengths]). */
data class MusicVideoFacts(val uri: String, val size: Long)

/** Why a job did not download: the same recording already exists. by = "source" | "content" | "metadata". */
data class ExistingMatch(val itemId: String?, val uri: String, val space: String, val by: String)

/** Tag changes for [LibraryApi.editInfo]. Null fields stay as they are; blank strings clear the tag. */
data class TagEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    /** content:// uri of a picked image to embed as the cover (songs in MP3, M4A and FLAC only). */
    val coverUri: String? = null,
)

/**
 * Result of an operation that may need the user's consent (files from before a reinstall belong to the previous install).
 * [Done]: finished. [NeedsConfirmation]: launch [NeedsConfirmation.intentSender] with
 * `ActivityResultContracts.StartIntentSenderForResult`; on RESULT_OK call the same API again for edits, moves and renames, and
 * [LibraryApi.confirmationResult] for deletes. On RESULT_CANCELED call [LibraryApi.confirmationResult] with `granted = false`.
 */
sealed interface LibraryAccess {
    data object Done : LibraryAccess
    class NeedsConfirmation(val intentSender: android.content.IntentSender, val ids: List<String>) : LibraryAccess
}

/** Outcome of [LibraryApi.importFiles]: files copied in, skipped because an identical file is already in the library, and failures. */
data class ImportResult(val added: Int, val duplicates: Int, val failed: Int, val errors: List<String> = emptyList())

/** Sidecar subtitle file next to a video. */
data class SubtitleFile(val uri: String, val language: String?, val label: String, val mimeType: String = "text/vtt")

/** Equalizer state of the playback session. Levels are millibels; [available] is false when the device offers no equalizer. */
data class EqualizerBand(val index: Int, val centerHz: Int, val minLevel: Int, val maxLevel: Int, val level: Int)

data class EqualizerState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val bands: List<EqualizerBand> = emptyList(),
    val presets: List<String> = emptyList(),
    /** Index into [presets], or -1 for custom band levels. */
    val preset: Int = -1,
    val bassBoostSupported: Boolean = false,
    /** 0–1000. */
    val bassBoost: Int = 0,
)

/** Sleep timer state. [remainingMs] counts down for a timed timer; [endOfTrack] pauses when the current song ends. */
data class SleepTimerState(val active: Boolean = false, val remainingMs: Long = 0L, val endOfTrack: Boolean = false)

/**
 * One deleted file in the Keepfin trash (the Trash page). [id] names its trash folder and is what restore and erase take; [itemId] is the
 * library item it was. [daysLeft] counts down from 30 to 0, after which the trash empties it.
 */
data class TrashEntry(
    val id: String,
    val itemId: String,
    val title: String,
    val fileName: String,
    val space: String,
    val size: Long,
    val deletedAt: Long,
    val daysLeft: Int,
)
