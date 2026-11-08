package app.keepfin.net

import app.keepfin.core.net.NetAction
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderLayout
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.MemberGrant
import app.keepfin.folder.MembershipView
import app.keepfin.folder.sql.SqlMergeState
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** A file that holds the bytes of one content id: [open] starts at [offset]. */
/** [name]: the file name it is held under here (the asker takes the extension from it when its own name has none). */
class ContentFile(val size: Long, val name: String = "", val open: (offset: Long) -> InputStream)

/** The count and completeness of an inventory written to a stream (copies spec: complete enumeration with a marker). */
class InventoryResult(val count: Long, val complete: Boolean)

/**
 * What the network layer needs from a library; the Keepfin folder store fills it on a phone, tests fill it in memory. Blocking
 * calls: the network threads are plain threads, never the main thread.
 */
interface NetLibrary {
    val libraryId: String

    /** What people call it (the profile name). */
    val name: String
    val folder: FolderAccess
    val isOpen: Boolean

    /** Where this library keeps its records in [folder] (a combined folder keeps them below `.keepfin/libraries/<id>`). */
    val layout: FolderLayout get() = FolderLayout.DEFAULT

    /** This device's actor in the library right now (it can rotate). */
    fun actor(): String

    /** Writes pending operations into segments, so a peer asking now gets them. */
    fun flush()

    /** Reads segments that arrived in the folder and admits them. */
    fun rescan()

    /** Held while a segment transfer lists and reads files: the store removes no segment file (compaction) until it is closed. */
    fun hold(): AutoCloseable = AutoCloseable {}

    /** A change in the library (own or remote): the peers are told. */
    fun onChange(listener: () -> Unit): () -> Unit
    fun membership(): MembershipView

    /** Starts enforcement: signs a `member.genesis` that freezes the unsigned history. A no-op when one exists. */
    fun ensureMembership(grant: MemberGrant?)
    fun grantMember(actor: String, spkiBase64: String, perms: List<String>)
    fun revokeMember(actor: String, cutoffSeq: Long)

    /** The segment files of the folder (listed again after a flush that wrote something). */
    fun segments(): List<SegInfo>

    /** Segment files were stored in the folder from outside: the next [segments] lists again. */
    fun invalidate() {}

    /** A file whose content is [contentId] (`sha256:<hex>`), or null. */
    fun findContent(contentId: String): ContentFile?

    /** The sorted inventory of the folder as JSON lines for a peer that keeps a copy; null when this build has none. */
    fun inventory(out: OutputStream): InventoryResult? = null
}

/** [NetLibrary] over the Keepfin folder store of a phone. */
class StoreNetLibrary(
    override val libraryId: String,
    override val name: String,
    override val folder: FolderAccess,
    private val store: KeepfinStore,
    private val state: SqlMergeState,
    private val replicaId: String,
    private val now: () -> Long = { System.currentTimeMillis() },
    override val layout: FolderLayout = FolderLayout.DEFAULT,
) : NetLibrary {
    private var cache: List<SegInfo>? = null
    private var cachedAt = 0L
    private var cachedWritten = -1L

    @Volatile private var closed = false
    override val isOpen: Boolean get() = !closed && !store.isClosed && !app.keepfin.merge.MergeFence.isFenced(libraryId)

    fun markClosed() {
        closed = true
    }

    override fun actor(): String = store.actorId

    override fun flush() {
        val had = store.pendingOps
        store.flush().get(60, TimeUnit.SECONDS)
        if (had > 0) synchronized(this) { cache = null }
    }

    override fun hold(): AutoCloseable = store.hold()

    override fun rescan() {
        store.rescan(true).get(60, TimeUnit.SECONDS)
        synchronized(this) { cache = null }
    }

    override fun onChange(listener: () -> Unit): () -> Unit = store.onChange { listener() }

    override fun membership(): MembershipView = store.membership().get(30, TimeUnit.SECONDS)

    override fun ensureMembership(grant: MemberGrant?) {
        store.enableMembership(grant).get(60, TimeUnit.SECONDS)
        synchronized(this) { cache = null }
    }

    override fun grantMember(actor: String, spkiBase64: String, perms: List<String>) {
        store.grantMember(actor, spkiBase64, perms).get(60, TimeUnit.SECONDS)
        synchronized(this) { cache = null }
    }

    override fun revokeMember(actor: String, cutoffSeq: Long) {
        store.revokeMember(actor, cutoffSeq).get(60, TimeUnit.SECONDS)
        synchronized(this) { cache = null }
    }

    @Synchronized override fun segments(): List<SegInfo> {
        val c = cache
        if (c != null && now() - cachedAt < 30_000 && cachedWritten == store.segmentsWritten) return c
        val written = store.segmentsWritten
        return SegmentFiles.list(folder, layout).also { cache = it; cachedAt = now(); cachedWritten = written }
    }

    /** A segment file was stored by a peer: the next listing sees it. */
    @Synchronized override fun invalidate() {
        cache = null
    }

    /**
     * The files of this folder as JSON lines, for a paired device that keeps a copy here (copies spec 9). The library's own data
     * (`.keepfin`) and exports are not part of it, nor are temporary files. A folder that could not be read to the end says so: the asker
     * then removes nothing on the strength of this list.
     */
    override fun inventory(out: java.io.OutputStream): InventoryResult? {
        var count = 0L
        var complete = true
        val stack = ArrayList<String>().apply { add("") }
        val writer = out.bufferedWriter(Charsets.UTF_8)
        while (stack.isNotEmpty()) {
            val dir = stack.removeAt(stack.size - 1)
            val listing = try {
                folder.list(dir)
            } catch (e: app.keepfin.folder.FolderError) {
                complete = false
                continue
            }
            if (listing.possiblyIncomplete) complete = false
            for (e in listing.entries) {
                val path = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                if (e.isDirectory) {
                    if (dir.isEmpty() && e.name.lowercase() in setOf(".keepfin", "keepfin exports")) continue
                    stack.add(path)
                } else {
                    if (app.keepfin.copies.skippedFile(e.name)) continue
                    if (e.size < 0) {
                        complete = false
                        continue
                    }
                    writer.write(org.json.JSONObject().put("path", path).put("size", e.size).put("mtimeMs", e.modifiedMs).toString())
                    writer.write("\n")
                    count++
                }
            }
        }
        writer.flush()
        return InventoryResult(count, complete)
    }

    override fun findContent(contentId: String): ContentFile? {
        if (!Regex("^sha256:[0-9a-f]{64}$").matches(contentId)) return null
        val path = store.withState { _ ->
            val items = state.query("SELECT id FROM item_view WHERE heads LIKE ? LIMIT 4", listOf("%$contentId%")).mapNotNull { it[0] as? String }
            items.firstNotNullOfOrNull { state.views.placement(it, replicaId) }
        }.get(30, TimeUnit.SECONDS) ?: return null
        val entry = folder.stat(path) ?: return null
        if (entry.isDirectory) return null
        return ContentFile(entry.size, path.substringAfterLast('/')) { offset ->
            val input = folder.openRead(path)
            var left = offset
            while (left > 0) {
                val n = input.skip(left)
                if (n <= 0) {
                    if (input.read() < 0) break
                    left--
                } else left -= n
            }
            input
        }
    }
}

/** Reads, writes and locks a copy of this folder for a paired device that keeps one here (copy spec section 9, "a peer as a transport"). */
interface PeerExecutorPort {
    fun state(): JSONObject
    fun lock(owner: String, device: String, run: String, now: Long): JSONObject
    fun heartbeat(owner: String, token: String): JSONObject
    fun unlock(owner: String, token: String): JSONObject
    fun trash(owner: String, token: String, path: String, revision: String?, ifMatch: String?): JSONObject
    fun move(owner: String, token: String, from: String, to: String, revision: String?, ifMatch: String?): JSONObject
    fun appendGeneration(owner: String, token: String, generation: Long, replica: String): JSONObject
    fun put(owner: String, params: PutParams, body: InputStream): JSONObject
    fun open(path: String): OpenedCopyFile
    fun release(owner: String)
}

class PutParams(val token: String, val path: String, val contentId: String, val mode: String, val revision: String?, val ifMatch: String?, val size: Long)

class OpenedCopyFile(val size: Long, val mtimeMs: Long, val open: (offset: Long) -> InputStream)

/** The actions a peer may be granted, in the order the page lists them. */
val GRANTABLE_ACTIONS: List<NetAction> = listOf(
    NetAction.MetadataRead, NetAction.MetadataWrite, NetAction.InventoryRead, NetAction.MediaRead, NetAction.CopyMutate, NetAction.Jobs, NetAction.MembershipAdmin,
)
