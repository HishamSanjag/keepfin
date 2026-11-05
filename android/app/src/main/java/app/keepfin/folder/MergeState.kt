package app.keepfin.folder

import app.keepfin.core.sqlstate.PartSource
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.Observed

enum class AdmitKind { Admitted, Duplicate, Kept, Invalid, Equivocation, SetAside, Covered, Malformed }

/** The 17 checkpoint shards (sync spec 10.4): the first hex digit of an id, else `meta`. */
val CHECKPOINT_SHARDS: List<String> = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f", "meta")

/**
 * What the store needs from the merge state: the SQLite state of D-A3 ([app.keepfin.folder.sql.SqlMergeState]) on a phone, the in-memory core
 * [app.keepfin.core.sync.OpLog] (`OpLogState`, in the test sources) as the independent oracle in tests. All calls come from the store thread.
 */
interface MergeState {
    /** Admits canonical op lines; one result per line. */
    fun admit(lines: List<String>): List<AdmitKind>

    /** Admits lines that came in a segment signed by the device [signer] (null: an unsigned segment); the membership judges them. */
    fun admit(lines: List<String>, signer: String?): List<AdmitKind> = admit(lines)

    /** Who may write in this library (inactive until a genesis is held). */
    fun membership(): MembershipView = MembershipView.INACTIVE

    /** The highest contiguous seq per actor. */
    fun versionVector(): Map<String, Long>

    fun maxHlc(): Hlc?

    /** Starts from checkpoint parts. False (state unchanged) when the parts are unusable. */
    fun loadParts(parts: List<PartSource>, libraryId: String, equivocations: Map<String, Long>): Boolean

    /** Shards whose part is out of date. */
    fun dirtyShards(): List<String>

    /** The part file of [shard]; marks it clean. */
    fun partBytes(libraryId: String, shard: String): ByteArray?

    fun findings(): Set<String>

    /** Folds the write-ahead log into the state's file; called on the store thread when the app goes to the background and after a checkpoint. */
    fun checkpoint() {}

    /** Releases what the state holds open (the SQLite files). The store calls it last, on its thread. */
    fun close() {}

    /** True when an equivocation moved a set-aside range: the state must be rebuilt from the segments ([rebuild]) before it is read. */
    fun needsRebuild(): Boolean = false

    /** Rebuilds the state from every op line of the segments and the outbox, in order. */
    fun rebuild(ops: Iterator<String>, signers: Iterator<String?>? = null) {}

    /** The whole state as canonical JSON text (for tests and diagnostics; a SQL state may stream instead). */
    fun viewsJson(): String

    /** What a remove or delete of the listed keys must observe (live add-tags, revision heads). Unknown keys answer empty. */
    fun observed(
        items: List<String> = emptyList(),
        playlists: List<String> = emptyList(),
        spaces: List<String> = emptyList(),
        setElements: List<Pair<String, String>> = emptyList(),
    ): Observed
}
