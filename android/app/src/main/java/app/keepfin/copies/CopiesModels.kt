package app.keepfin.copies

import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.ProblemGroup

/** What the Copies screens show (dev-docs/plans/KEEPFIN-COPIES-SPEC.md section 10). Immutable; the manager publishes a new one on every change. */
enum class CopyStatus { NEW, PREVIEW, SYNCING, UP_TO_DATE, WAITING, REVIEW, PROBLEM, HELD, DISCONNECTED, PAUSED, REFUSED }

data class CopyHoldsUi(val all: Boolean = true, val spaces: Set<String> = emptySet(), val playlists: Set<String> = emptySet(), val favourites: Boolean = false, val smart: Set<String> = emptySet())

data class CopyProgressUi(val done: Int, val total: Int, val doneBytes: Long, val totalBytes: Long, val current: String?)

data class CopyCardUi(
    val id: String,
    val name: String,
    val mode: CopyMode,
    val holds: CopyHoldsUi,
    val folderLabel: String,
    val connected: Boolean,
    val status: CopyStatus,
    val statusText: String? = null,
    val lastVerifiedAt: Long? = null,
    val kept: Int = 0,
    val waiting: Int = 0,
    val review: Int = 0,
    val freeBytes: Long? = null,
    val progress: CopyProgressUi? = null,
    val everyHours: Int? = null,
    val auto: Boolean = true,
    val playlistFiles: Boolean = false,
    val flat: Boolean = false,
    val mp3: Boolean = false,
    /** Set when the copy is on a cloud account (Settings > Storage > Copies > Add a copy > A cloud account). */
    val cloud: CloudCardUi? = null,
    /** Set when the copy is kept on a paired device (Add a copy > A paired device). */
    val peer: PeerCardUi? = null,
    /** Lyrics, subtitles, info files and covers go with their items (on unless turned off). */
    val companions: Boolean = true,
    /** Each song's music video goes with it: what the person chose, else on for a copy of everything. */
    val musicVideos: Boolean = true,
    /** Files that could not be copied at the last sync, in plain words; null when none failed. */
    val problem: CopyProblemUi? = null,
    /** Stop keeping this copy is running (what it is doing) or stopped ([CopyRemovalUi.failed]: why; the copy is still here). */
    val removal: CopyRemovalUi? = null,
)

data class CopyRemovalUi(val failed: Boolean, val text: String)

/** Why some files of a copy were not copied (reasons grouped, the most common first). [files] are relative to the copy; [more] are not listed. */
data class CopyProblemUi(val count: Int, val reasons: List<ProblemGroup>, val files: List<String>, val more: Int, val short: String)

/** What a copy on a paired device shows of that device. */
data class PeerCardUi(
    val deviceId: String,
    val deviceName: String,
    val online: Boolean,
    /** Why the copy is waiting right now ("Not connected right now.", the device does not allow it, mobile data), or null. */
    val waiting: String? = null,
)

/** A paired device in the Add a copy step, with whether it lets this phone keep a copy on it. */
data class PeerChoiceUi(val deviceId: String, val name: String, val online: Boolean, val allowed: Boolean, val why: String? = null)

/** What the copies engine asks of the paired devices (implemented by `PeerRuntime`): a copy kept on a device runs straight over the network. */
internal interface PeerHooks {
    fun isPeer(address: String?): Boolean
    fun devices(): List<PeerChoiceUi>
    fun card(address: String): PeerCardUi?
    fun label(address: String): String

    /** The copy's own bookkeeping (`.keepfin`), a folder on this phone inside [stateDir]. */
    fun meta(stateDir: java.io.File): app.keepfin.folder.FolderAccess

    /** The copy on the device behind [address]: its files over the network, its bookkeeping in [stateDir]. */
    fun open(address: String, stateDir: java.io.File, libraryId: String): app.keepfin.folder.FolderAccess

    /** Drops what was opened for a copy that is gone. */
    fun forget(stateDir: java.io.File)
}

/** `peer:<deviceId>` is where a copy on a paired device lives (the way a folder has its tree address). */
internal object PeerAddress {
    private const val PREFIX = "peer:"
    fun make(deviceId: String) = PREFIX + deviceId
    fun isPeer(address: String?) = address != null && address.startsWith(PREFIX)
    fun deviceOf(address: String): String? = address.takeIf { isPeer(it) }?.removePrefix(PREFIX)?.takeIf { it.isNotEmpty() }
}

/** What the copies engine asks of cloud accounts (implemented by `CloudRuntime`): a cloud copy's address opens as a folder like any other. */
internal interface CloudHooks {
    fun isCloud(address: String?): Boolean
    fun label(address: String): String
    fun card(address: String): CloudCardUi?
    fun open(address: String): app.keepfin.folder.FolderAccess
}

/** What a cloud copy's card shows of its account. Never a token or a password. */
data class CloudCardUi(
    val accountId: String,
    val providerName: String,
    val accountLabel: String,
    val needsSignIn: Boolean,
    val mobileData: Boolean,
    /** Why the copy is waiting right now (no Wi-Fi, no network), or null. */
    val waiting: String? = null,
    /** "drive", "dropbox", "graph" or "webdav". */
    val provider: String = "",
)

enum class ReviewAction { CONFIRM_DELETE, RESTORE, KEEP_BOTH, DISMISS, SAME_DRIVE, SEPARATE_COPY, CONFIRM_HELD, TAKE_MAIN, TAKE_COPY }

data class CopyReviewUi(val id: String, val placeId: String, val placeName: String, val kind: String, val title: String, val detail: String, val actions: List<ReviewAction>)

data class CopiesUi(
    val ready: Boolean = false,
    val places: List<CopyCardUi> = emptyList(),
    val review: List<CopyReviewUi> = emptyList(),
    val syncing: Boolean = false,
    val spaces: List<Pair<String, String>> = emptyList(),
    val playlists: List<Pair<String, String>> = emptyList(),
    /** The paired devices (empty when there are none, or this build has no devices): the Add a copy step offers them. */
    val devices: List<PeerChoiceUi> = emptyList(),
    /** What the last Sync all or Sync now did, once every copy in it finished (words from the shared core). */
    val report: SyncReportUi? = null,
)

data class SyncReportUi(val title: String, val detail: String, val warn: Boolean, val at: Long)

data class CopyDraft(
    val treeUri: String,
    val name: String,
    val mode: CopyMode,
    val holds: CopyHoldsUi,
    val everyHours: Int? = null,
    val auto: Boolean = true,
    /** The person's choice of music videos, or null to leave it to what the copy holds. */
    val musicVideos: Boolean? = null,
    /** Lyrics, subtitles and info files go with their items (on unless the person turned it off in the wizard). */
    val companions: Boolean = true,
)

data class CopyFolderCheck(val ok: Boolean, val problem: String? = null, val suggestedName: String = "", val isCopy: Boolean = false, val existingFiles: Int = 0, val freeBytes: Long? = null)

data class CopyPlanLine(val label: String, val files: Int, val bytes: Long)

data class CopyPreview(
    val placeId: String,
    /** ready, held, refused, disconnected, busy */
    val status: String,
    val reason: String? = null,
    val additiveOnly: Boolean = true,
    val lines: List<CopyPlanLine> = emptyList(),
    val copyFiles: Int = 0,
    val copyBytes: Long = 0,
    val destructive: Int = 0,
    val differences: List<Pair<String, String>> = emptyList(),
    val differencesMore: Int = 0,
    val freeBytes: Long? = null,
    val enough: Boolean? = null,
    val importsToLibrary: Int = 0,
)

data class CopyVersionUi(val id: String, val name: String, val path: String, val archivedAt: Long, val why: String, val deleted: Boolean)

/** What Free up space… would delete from a backup: every kept entry, and how many of them are for files deleted since. */
data class CopyVersionStats(val versions: Int, val deletedFiles: Int)
