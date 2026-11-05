package app.keepfin.net

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.folder.FolderLayout
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.MemberGrant
import app.keepfin.folder.MembershipView
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

// Joining a library over the network (spec 9.4): a paired device that does not hold a library its peer shares with it makes a Keepfin
// folder of its own for that library (the same library id, a new replica id), takes in the peer's segments, and becomes a replica.
// Port of gui/desktop/net/join.ts. The steps:
//   1. the folder: new or empty; `.keepfin/join.json` records the replica id and the actor before anything else, so an interrupted
//      join is resumed with the same identity instead of becoming a second replica;
//   2. `POST /v1/libraries/<id>/join` names the actor this device will write as; the peer, if it administers the library, grants it
//      (and writes the membership root first when the library was not signed yet);
//   3. the segments are pulled by version vector (a broken transfer keeps what arrived: the next pull starts from the new vector);
//   4. `join.json` is marked ready. The host then adds the folder as a library profile of this device and opens it; the library store
//      replays the segments with the membership rules, exactly as for any folder.
// Media is not part of this: it arrives through the place's holds (copies) or on demand ("Play from").

class JoinError(val code: String, message: String) : Exception(message)

class JoinProgress(val stage: String, val segments: Int)

class Joined(
    val folder: FolderAccess,
    val libraryId: String,
    val replicaId: String,
    val actor: String,
    val name: String,
    val hostActor: String,
    val segments: Int,
)

object JoinFile {
    const val PATH = ".keepfin/join.json"

    /** Where the join record of a library whose records root is [layout] lives: beside the records root, so it exists before the root does. */
    fun pathOf(layout: FolderLayout): String = if (layout.recordsPrefix.isEmpty()) PATH else "${layout.root}.join.json"

    class Record(val libraryId: String, val from: String, val replicaId: String, val actor: String, val startedAt: Long, val stage: String, val hostActor: String?, val name: String) {
        fun json(): JSONObject = JSONObject().put("v", 1).put("libraryId", libraryId).put("from", from).put("replicaId", replicaId).put("actor", actor).put("startedAt", startedAt)
            .put("stage", stage).put("name", name).apply { hostActor?.let { put("hostActor", it) } }
    }

    fun read(folder: FolderAccess, layout: FolderLayout = FolderLayout.DEFAULT): Record? = try {
        val o = JSONObject(folder.read(pathOf(layout)).toString(Charsets.UTF_8))
        Record(o.getString("libraryId"), o.getString("from"), o.getString("replicaId"), o.getString("actor"), o.optLong("startedAt"), o.optString("stage", "joining"), o.optString("hostActor").ifEmpty { null }, o.optString("name"))
    } catch (e: FolderError.NotFound) {
        null
    } catch (e: Exception) {
        null
    }

    fun write(folder: FolderAccess, record: Record, layout: FolderLayout = FolderLayout.DEFAULT) {
        val path = pathOf(layout)
        folder.mkdirs(app.keepfin.folder.parentOf(path))
        val bytes = record.json().toString(2).toByteArray(Charsets.UTF_8)
        try {
            folder.publish(path, bytes)
        } catch (e: FolderError.NameConflict) {
            folder.replace(path, bytes)
        }
    }
}

/** The target of a join as a [NetLibrary]: only what pulling segments needs (nothing is open or written by a store yet). */
private class JoinTarget(override val libraryId: String, override val folder: FolderAccess, override val name: String, override val layout: FolderLayout) : NetLibrary {
    override val isOpen: Boolean get() = true
    private var cache: List<SegInfo>? = null
    override fun actor(): String = ""
    override fun flush() {}
    override fun rescan() {}
    override fun onChange(listener: () -> Unit): () -> Unit = {}
    override fun membership(): MembershipView = MembershipView.INACTIVE
    override fun ensureMembership(grant: MemberGrant?) = throw UnsupportedOperationException()
    override fun grantMember(actor: String, spkiBase64: String, perms: List<String>) = throw UnsupportedOperationException()
    override fun revokeMember(actor: String, cutoffSeq: Long) = throw UnsupportedOperationException()
    override fun segments(): List<SegInfo> = cache ?: SegmentFiles.list(folder, layout).also { cache = it }
    override fun invalidate() { cache = null }
    override fun findContent(contentId: String): ContentFile? = null
}

object JoinRunner {
    private fun mapError(e: Throwable, cancelled: () -> Boolean): JoinError {
        if (e is JoinError) return e
        if (cancelled()) return JoinError("cancelled", "The join was stopped.")
        if (e is PeerHttpError) {
            if (e.status == 409 && e.code == "not-admin") return JoinError("not-admin", e.message ?: "")
            if (e.status == 403) return JoinError("denied", "The other device does not share this library with this device. Ask it to share the library first.")
            if (e.status == 426) return JoinError("version", e.message ?: "")
            return JoinError("other", e.message ?: "The library could not be taken in.")
        }
        return JoinError("network", e.message ?: "The other device could not be reached.")
    }

    /**
     * Step 1, the folder: the join record first, then the Keepfin folder or the records root, so a crash between the two leaves a record that
     * resumes with the same identity (never a half-made folder nobody can continue). Throws a [JoinError] for a folder that cannot be used.
     */
    internal fun prepare(
        libraryId: String,
        peerId: String,
        name: String,
        target: FolderAccess,
        layout: FolderLayout = FolderLayout.DEFAULT,
        now: () -> Long = { System.currentTimeMillis() },
        newId: () -> String = { UUID.randomUUID().toString() },
    ): JoinFile.Record {
        val intoRoot = layout.recordsPrefix.isNotEmpty()
        var record = JoinFile.read(target, layout)
        if (record != null && (record.libraryId != libraryId || record.from != peerId)) throw JoinError("folder", "This folder is already being used to join a different library.")
        if (record == null) {
            val entries = try { target.list(if (intoRoot) layout.root else "").entries } catch (e: FolderError.NotFound) { emptyList() }
            if (entries.isNotEmpty()) throw JoinError("folder", if (intoRoot) "This folder already holds that library." else "Choose a new or an empty folder for this library.")
            val replicaId = newId()
            record = JoinFile.Record(libraryId, peerId, replicaId, newId(), now(), "joining", null, name)
            JoinFile.write(target, record, layout)
        }
        if (intoRoot) {
            KeepfinFolders.createRecords(target, layout)
        } else {
            when (val opened = KeepfinFolders.open(target)) {
                is app.keepfin.folder.OpenFolderResult.NotAKeepfinFolder, is app.keepfin.folder.OpenFolderResult.Missing ->
                    KeepfinFolders.create(target, libraryId, record.replicaId, Instant.ofEpochMilli(now()).toString())
                is app.keepfin.folder.OpenFolderResult.Ok ->
                    if (opened.folder.libraryId != libraryId) throw JoinError("folder", "This folder cannot be used to continue the join.")
                else -> throw JoinError("folder", "This folder cannot be used to continue the join.")
            }
        }
        return record
    }
    fun run(
        client: PeerClient,
        peerId: String,
        libraryId: String,
        name: String,
        target: FolderAccess,
        now: () -> Long = { System.currentTimeMillis() },
        newId: () -> String = { UUID.randomUUID().toString() },
        cancelled: () -> Boolean = { false },
        /**
         * Where the library's records go. The default is a new or empty Keepfin folder (the folder itself must be empty). A records root below
         * an existing Keepfin folder (`libraries/<id>`, combining two libraries) must be empty or absent; the folder keeps its own library.
         */
        layout: FolderLayout = FolderLayout.DEFAULT,
        onProgress: (JoinProgress) -> Unit = {},
    ): Joined {
        onProgress(JoinProgress("folder", 0))
        var record = prepare(libraryId, peerId, name, target, layout, now, newId)

        // 2. the peer grants the actor (idempotent: the same actor asking again gets the same answer)
        onProgress(JoinProgress("asking", 0))
        var hostActor = record.hostActor.orEmpty()
        if (record.stage != "ready" || hostActor.isEmpty()) {
            try {
                val answer = client.json("POST", "/v1/libraries/$libraryId/join", JSONObject().put("actor", record.actor))
                hostActor = answer.optString("actor")
            } catch (e: Exception) {
                throw mapError(e, cancelled)
            }
            record = JoinFile.Record(record.libraryId, record.from, record.replicaId, record.actor, record.startedAt, record.stage, hostActor, record.name)
            JoinFile.write(target, record, layout)
        }

        // 3. the segments, until what the peer reported is here (a pass that stores nothing and reaches its vector is the end)
        val lib = JoinTarget(libraryId, target, name, layout)
        var total = lib.segments().size
        onProgress(JoinProgress("pulling", total))
        for (pass in 0 until 50) {
            if (cancelled()) throw JoinError("cancelled", "The join was stopped.")
            val result = try {
                Sync.pull(client, lib, cancelled) { total++; onProgress(JoinProgress("pulling", total)) }
            } catch (e: Exception) {
                throw mapError(e, cancelled)
            }
            if (result.denied) throw JoinError("denied", "The other device does not let this device read the library.")
            lib.invalidate()
            val have = SegmentFiles.vectorOf(lib.segments())
            val remote = result.remote ?: emptyMap()
            val behind = remote.any { (actor, seq) -> (have[actor] ?: 0L) < seq }
            if (!behind) break
            if (result.stored == 0) throw JoinError("incomplete", "The library arrived only in part. Try again.")
        }

        record = JoinFile.Record(record.libraryId, record.from, record.replicaId, record.actor, record.startedAt, "ready", record.hostActor, record.name)
        JoinFile.write(target, record, layout)
        onProgress(JoinProgress("done", total))
        return Joined(target, libraryId, record.replicaId, record.actor, record.name, hostActor, total)
    }
}
