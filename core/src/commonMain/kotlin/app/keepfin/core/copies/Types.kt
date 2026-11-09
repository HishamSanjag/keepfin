package app.keepfin.core.copies

// Phase 3 of the Keepfin folder project: keeping copies up to date (dev-docs/plans/KEEPFIN-COPIES-SPEC.md). This package is
// the planner of section 4: pure, no I/O, portable. It reads sorted streams of the desired set, the two inventories
// and the baseline of one relationship, and answers with a plan (or with the hashes it still needs). The executor of
// each platform does the file work; nothing here touches a disk.

enum class CopyMode { TwoWay, Backup, Mirror }

/** Which side of the relationship a line comes from. */
enum class Side { Main, Copy }

/** What a place holds (the `holds` register: `{"all": true}` or a selection). */
data class Holds(
    val all: Boolean = false,
    val spaces: Set<String> = emptySet(),
    val playlists: Set<String> = emptySet(),
    val favourites: Boolean = false,
    val smart: Set<String> = emptySet(),
)

data class PlaceOptions(
    val playlistFiles: Boolean = false,
    val flat: Boolean = false,
    val mp3: Boolean = false,
    /** Lyrics, subtitles and info files travel with their items (on unless the person turned it off). */
    val companions: Boolean = true,
    /**
     * Songs' music videos (`Song.video.mp4`) travel with them: their own option, apart from [companions]. When the person has not
     * chosen, a copy of everything carries them and a part copy does not (dev-docs/plans/MUSIC-VIDEOS.md decision 5); nor does a copy
     * whose person left out the lyrics and subtitles (so an upgrade never starts filling it with videos).
     */
    val musicVideos: Boolean = true,
    val versionsKeepDays: Int = 90,
    val deleteGuardPercent: Int = 10,
)

/** A place as its `place.set` registers describe it (spec section 2). */
data class PlaceConfig(
    val id: String,
    val kind: String,
    val name: String,
    val replica: String?,
    val mode: CopyMode,
    val holds: Holds,
    val options: PlaceOptions,
    /** Hours between scheduled syncs, or null. */
    val everyHours: Int?,
)

/** `(relativePath, size, mtimeMs, fileId?)` of one file of an inventory (spec section 3). */
data class InvLine(val path: String, val size: Long, val mtimeMs: Long, val fileId: String? = null)

/** What was seen of a file on one endpoint. */
data class Stat(val size: Long, val mtimeMs: Long, val fileId: String? = null)

/**
 * The last reconciled state of one path in one relationship (spec 4.1): the item, its revision and contentId, and what
 * each endpoint looked like then. [scannedAtMs] is when that was observed (for the timestamp-granularity rule).
 */
data class BaselineEntry(
    val path: String,
    val itemId: String,
    val revisionOp: String?,
    val contentId: String,
    val main: Stat,
    val copy: Stat,
    val scannedAtMs: Long,
    val generation: Long,
)

/** One file of the desired set D: an item at its path in its head revision. */
data class DesiredEntry(
    val path: String,
    val itemId: String,
    val revisionOp: String?,
    val contentId: String,
    val bytes: Long,
)

/** A rename or move recorded by the library (`item.place`): the planner follows it instead of guessing. */
data class PlacementMove(val itemId: String, val from: String, val to: String)

/** What the planner needs to know from the library. */
interface LibraryFacts {
    /** True when an `item.delete` removed the item (it is not present and has no delete conflict). */
    fun isDeleted(itemId: String): Boolean

    /** Placement changes since the baseline. */
    val placementMoves: List<PlacementMove>
}

class SimpleLibraryFacts(private val deleted: Set<String> = emptySet(), override val placementMoves: List<PlacementMove> = emptyList()) : LibraryFacts {
    override fun isDeleted(itemId: String): Boolean = itemId in deleted
}

/** Hash evidence the executor computed (spec section 3): a SHA-256 is valid for the stat it was computed at. */
fun interface Evidence {
    fun hash(side: Side, line: InvLine): String?

    companion object {
        val NONE = Evidence { _, _ -> null }
    }
}

class MapEvidence(private val hashes: Map<String, String>) : Evidence {
    override fun hash(side: Side, line: InvLine): String? = hashes[keyOf(side, line)]

    companion object {
        fun keyOf(side: Side, line: InvLine): String = "$side|${line.path}|${line.size}|${line.mtimeMs}"
    }
}

/** What a transport guarantees (spec section 9); the planner is more conservative where a guarantee is missing. */
data class Capabilities(
    /** A consistent, complete enumeration. Without it nothing destructive happens. */
    val completeEnumeration: Boolean = true,
    /** Stable object identity (file index / object id). Without it moves rely on library placements only. */
    val stableIds: Boolean = true,
    /** If-match style mutation. Without it every replacement preserves the displaced bytes. */
    val conditionalMutation: Boolean = false,
    val atomicNoReplace: Boolean = true,
    val serverHash: Boolean = false,
    val resumable: Boolean = true,
    val locking: Boolean = true,
) {
    companion object {
        /** A folder on a local or network drive. */
        val folder = Capabilities()
    }
}

/** What the generation check found (spec section 7). */
enum class Generation {
    /** The copy's generation equals the baseline's: proceed. */
    Equal,

    /** Another computer synced in between: additive only until a fresh full inventory. */
    Higher,

    /** A restored or cloned drive: every destructive action is suspended. */
    Lower,

    /** Not read: treated like Lower. */
    Unknown,
}

enum class ActionKind {
    /** main to copy; [Action.expect] says what the destination must be (absent, or the given content) */
    CopyTo,

    /** rename on the copy, following the library or an unambiguous content match */
    MoveOnCopy,

    /** remove from the copy, into its trash (see [Action.archive]) */
    RemoveFromCopy,

    /** a file only the copy has becomes a new item on main */
    ImportNew,

    /** the copy's bytes become a new revision of the item (main replaced) */
    ReviseFromCopy,

    /** main lost a file the copy still has, unchanged: put it back */
    RestoreFromCopy,

    /** the copy edited what the library deleted: the edit is restored as a revision, for review */
    RestoreEditedDeleted,

    /** a user-confirmed deletion on the copy becomes `item.delete` in the library */
    DeleteItem,

    /** both sides hold the same bytes: only the baseline is written */
    Converge,

    /** both sides are gone and the item is deleted: the baseline entry is dropped */
    ForgetBaseline,
}

enum class Archive { Versions, Trash }

enum class RemoveReason { ItemDeleted, NoLongerHeld, ConfirmedDelete }

/** The destination is expected to be absent ([contentId] null) or to be exactly this content (and file id, when known). */
data class Expect(val contentId: String?, val fileId: String? = null)

data class Action(
    val kind: ActionKind,
    /** The path of the file on the endpoint the action works on (the copy's spelling when the copy has the file). */
    val path: String,
    /** For a move, and for an import that lands under another name, the destination path. */
    val toPath: String? = null,
    val itemId: String? = null,
    val revisionOp: String? = null,
    val contentId: String? = null,
    val bytes: Long = 0,
    /** Bytes of the destination file this action displaces into versions or trash (for the free-space check). */
    val displacedBytes: Long = 0,
    val expect: Expect? = null,
    /** Where displaced or removed bytes go first. */
    val archive: Archive? = null,
    val reason: RemoveReason? = null,
    /** Offered in Needs review rather than done silently. */
    val review: Boolean = false,
)

enum class ReviewKind {
    /** the desired item is on neither side */
    MissingSource,

    /** the item is on no side and is not deleted */
    MissingEverywhere,

    /** two distinct entries of one side would collide under the destination's name rules */
    NameCollision,

    /** a file Keepfin did not put on the copy */
    UnknownFile,

    /** a deleted or no-longer-held file a backup keeps */
    KeptOnBackup,

    /** the copy deleted a file the library still has: re-copied unless the user confirms the deletion */
    CopyDeletedProposal,

    /** an action withheld because of the new relationship, a higher generation or a contentId difference to confirm */
    Difference,

    /** a destructive action withheld for an incomplete inventory, an unknown or lower generation */
    Suspended,

    /** the copy keeps a file the place no longer holds (backup, two-way) */
    NotHeld,

    /** (executors) a file that is only in the cloud, not on the disk: left alone, never read or removed */
    OnlineOnly,

    /** (executors) a file larger than the copy's drive can hold (FAT, 4 GB): not copied */
    TooLarge,
}

data class ReviewItem(val kind: ReviewKind, val path: String, val itemId: String? = null, val detail: String = "", val action: Action? = null)

data class HashRequest(val side: Side, val path: String, val size: Long, val mtimeMs: Long, val why: String)

data class SpaceCheck(val needed: Long, val free: Long?, val enough: Boolean?)

data class PlanSummary(
    val counts: Map<ActionKind, Int>,
    val bytes: Map<ActionKind, Long>,
    /** Removals plus replacements on the copy: what the deletion guard counts. */
    val destructive: Int,
    val copyFiles: Int,
    /** Bytes that will be staged, and bytes displaced into versions or trash, per endpoint. */
    val mainSpace: SpaceCheck,
    val copySpace: SpaceCheck,
)

class Plan(
    val actions: List<Action>,
    val review: List<ReviewItem>,
    val summary: PlanSummary,
    /** True when the deletion guard holds the plan until it is confirmed. */
    val held: Boolean,
    val holdReason: String?,
    /** True when the plan only adds (a new relationship before its preview is confirmed, or a higher generation). */
    val additiveOnly: Boolean,
) {
    val isEmpty: Boolean get() = actions.isEmpty()
}

sealed class PlanOutcome {
    /** The hashes the planner needs before it can finish; compute them and plan again. */
    class NeedsHashes(val requests: List<HashRequest>) : PlanOutcome()

    class Ready(val plan: Plan) : PlanOutcome()
}
