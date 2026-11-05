package app.keepfin.merge

import app.keepfin.core.net.DeviceSigner
import app.keepfin.core.sqlstate.SqlHost
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderLayout
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.ProfileStore
import app.keepfin.folder.sql.SqlMergeState
import java.io.File

/** The open library of this device (S, or T after the switch): the store that runs it and the SQLite state behind it. */
class OpenLibrary(
    val store: KeepfinStore,
    val state: SqlMergeState,
    val replicaId: String,
    val libraryId: String,
    val name: String,
    val layout: FolderLayout,
    /** The device-only directory of its profile (`linked.json`, `follow-state.json`, `job-items.json` live here). */
    val deviceDir: File,
)

/** What joining T gave back (the join record of contract section 2). */
class JoinOutcome(val tReplicaId: String, val tActor: String, val name: String, val hostActor: String)

/** How the S (or T) ids of the library are known outside the library after a switch. Each lambda answers null for an id it does not know. */
class IdMaps(val item: (String) -> String?, val collection: (String) -> String?, val space: (String) -> String?)

/** Why Separate again may need the peer (contract section 7.3). */
interface PeerPort {
    /** The paired device that holds T and administers it; null when none can be reached right now. */
    fun reachable(): Boolean

    /**
     * Pushes B's T operations up to [seq] (its actor's) to the peer and returns once the peer says it holds them durably; false when it cannot
     * (unreachable, refused). Never throws for network errors.
     */
    fun publishAndWait(libraryId: String, actor: String, seq: Long): Boolean

    /** Asks the peer's administrator to revoke B's actor in T (a membership revocation published with step 3). Best effort. */
    fun withdraw(libraryId: String, actor: String, cutoffSeq: Long): Boolean
}

/**
 * Everything a combine or separate needs from the app: the open library, the folder, SQLite, the network join and the places outside the
 * library that refer to its ids. The app implements it ([AndroidMergeEnv]); the tests implement it with fakes.
 */
interface MergeEnv {
    val filesDir: File
    val profiles: ProfileStore
    val sqlHost: SqlHost
    val signer: DeviceSigner?
    val deviceName: String get() = "Android"
    val appVersion: String get() = ""
    fun now(): Long = System.currentTimeMillis()
    fun newId(): String = java.util.UUID.randomUUID().toString()

    /** The Keepfin folder of the profile [treeUri] names. */
    fun folder(treeUri: String): FolderAccess

    /** The library the person is combining into, as a paired device offers it right now: (library id, name). Null when it does not. */
    fun offer(deviceId: String): Pair<String, String>?

    /** The paired device's name when it is connected right now, else null. */
    fun connectedName(deviceId: String): String? = null

    /**
     * Why a connected device offers nothing to combine: "none" (it has no Keepfin folder yet), "held" (it shares this phone's library
     * already) or "denied" (it does not let this phone read its library); null when it has not said hello.
     */
    fun offerGap(deviceId: String): String? = null

    /** The profile in use and the library it holds (the library being combined away). */
    fun currentProfile(): app.keepfin.folder.Profile?

    /** The library that is open now. */
    fun openLibrary(): OpenLibrary

    /** Joins [libraryId] from [peerDeviceId] into its records root (contract 2). Blocks; resumable with the same arguments. */
    fun join(journal: MergeJournal, progress: (stage: String, segments: Int) -> Unit, cancelled: () -> Boolean): JoinOutcome

    /** The peer for Separate again; null when this build has no network. */
    fun peer(journal: MergeJournal): PeerPort?

    /**
     * Closes the library that is open and opens the one the current profile and `folder.json` name, then resumes sync and copies under it.
     * Called after the switch and after switching back.
     */
    fun reopen()

    /** False while the app is still starting and no library is open: recovery then only works on files. */
    val engineReady: Boolean get() = true

    /** Updates every place outside the library that holds its ids (download queue, saved playback queue). Must be repeatable. */
    fun remapOutside(maps: IdMaps)

    /** A reason this device cannot start now (a copy runs, a review is open), or null. */
    fun refusal(): String?

    /** True while a copy runs on this device. */
    fun copyRunning(): Boolean

    /** Plays that finished while the library was fenced are written now. */
    fun replayPlays(libraryId: String, plays: List<MergeFence.Play>, maps: IdMaps?)
}
