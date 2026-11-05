package app.keepfin

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/** Whether any Keepfin screen is in front of the user. Set by the application's lifecycle callbacks. */
object AppForeground {
    @Volatile var visible: Boolean = false
}

/** What the UI may do with the Activity list. Entries are recorded only by the engine and application layer, never through this surface. */
interface ActivityFeed {
    val entries: StateFlow<List<ActivityEntry>>
    fun markRead(ids: Collection<String>)
    fun markAllRead()
    fun remove(id: String)
    /** Unread entries created while the app was away, once: asking again returns 0 until something new happens. */
    fun takeAwayCount(): Int
}

/**
 * The Activity list: an app-private JSON file written off the main thread with an atomic replace, exposed as a [StateFlow]. [file] null keeps
 * everything in memory (fixtures and tests). Pruned on every write (newest 200, nothing older than 30 days).
 */
class ActivityStore(
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val foreground: () -> Boolean = { AppForeground.visible },
) : ActivityFeed {
    private val lock = Any()
    private val state = MutableStateFlow(load())
    override val entries: StateFlow<List<ActivityEntry>> = state.asStateFlow()
    private val io = if (file != null) Executors.newSingleThreadExecutor { task -> Thread(task, "keepfin-activity").apply { isDaemon = true } } else null

    // ---- reading side ------------------------------------------------------------------------------

    override fun markRead(ids: Collection<String>) {
        if (ids.isEmpty()) return
        update { list -> list.map { if (it.id in ids && !it.read) it.copy(read = true) else it } }
    }

    override fun markAllRead() = update { list -> list.map { if (it.read) it else it.copy(read = true) } }

    override fun remove(id: String) = update { list -> list.filter { it.id != id } }

    override fun takeAwayCount(): Int = synchronized(lock) {
        val count = ActivityLogic.awayCount(state.value)
        if (state.value.any { it.away }) mutate { list -> list.map { if (it.away) it.copy(away = false) else it } }
        count
    }

    // ---- recording side (engine and application layer) -----------------------------------------------

    /** Adds [entry] unless one with the same id exists ([refresh] replaces it and makes it unread). [away] defaults to "the app is not in front". */
    fun record(entry: ActivityEntry, refresh: Boolean = false, away: Boolean = !foreground()) =
        update { list -> ActivityLogic.upsert(list, entry.copy(away = away), refresh) }

    /** A download ended for good (after automatic retries). [queue] is the whole queue; spaces say where it was saved. */
    fun onJobTerminal(job: Job, queue: List<Job>, library: LibrarySettings) {
        val spaces = library.spaces
        synchronized(lock) {
            val ctx = ActivityContext(
                now = clock(), foreground = foreground(),
                spaceName = { id -> if (library.enabled) spaces.firstOrNull { it.id == id }?.name else null },
                isMusicSpace = { id -> library.enabled && spaces.firstOrNull { it.id == id }?.kind == SpaceKind.MUSIC },
                syncJobIds = state.value.filter { it.kind == ActivityKind.SYNC_FOUND && it.open }.flatMap { it.target.jobIds }.toSet(),
            )
            val fresh = ActivityLogic.forTerminal(job, queue, ctx)
            val byId = queue.associateBy { it.id }
            mutate { list ->
                var next = list
                for (entry in fresh) next = ActivityLogic.upsert(next, entry.copy(away = !ctx.foreground), refresh = entry.kind == ActivityKind.SIGN_IN)
                ActivityLogic.settleSync(next) { id -> ActivityLogic.outcomeOf(byId[id]) }
            }
        }
    }

    /** A follow check queued [jobIds] for a collection. [queue] lets an entry settle at once if they already ended. */
    fun recordSyncFound(collectionId: String, name: String, jobIds: List<String>, music: Boolean, spaceName: String?, queue: List<Job>, thumbnail: String? = null) {
        if (jobIds.isEmpty()) return
        val byId = queue.associateBy { it.id }
        synchronized(lock) {
            mutate { list ->
                val next = ActivityLogic.recordSyncFound(list, collectionId, name, jobIds, music, spaceName, clock(), !foreground(), thumbnail)
                ActivityLogic.settleSync(next) { id -> ActivityLogic.outcomeOf(byId[id]) }
            }
        }
    }

    /** A check found more than the review limit; the items wait for the user. */
    fun recordSyncReview(collectionId: String, name: String, count: Int, confirmAbove: Int, music: Boolean) =
        record(ActivityLogic.syncReview(collectionId, name, count, confirmAbove, music, clock(), !foreground()), refresh = true)

    /** First start of a new version. Not "while away": the user just opened the app. */
    fun recordAppUpdated(version: String, firstNote: String?) = record(
        ActivityEntry(id = "app-updated:$version", kind = ActivityKind.APP_UPDATED, at = clock(), title = ActivityLogic.appUpdatedTitle(version),
            detail = firstNote?.takeIf { it.isNotBlank() }, target = ActivityTarget(version = version)),
        away = false,
    )

    /** An app update finished downloading and waits for the user. */
    fun recordUpdateReady(version: String) = record(
        ActivityEntry(id = "update-ready:$version", kind = ActivityKind.UPDATE_READY, at = clock(), title = ActivityLogic.updateReadyTitle(version),
            target = ActivityTarget(version = version)),
    )

    /** Waits for queued writes; tests read the file afterwards. */
    fun flush() { io?.submit { }?.get() }

    // ---- storage -----------------------------------------------------------------------------------

    private fun update(transform: (List<ActivityEntry>) -> List<ActivityEntry>) = synchronized(lock) { mutate(transform) }

    /** Callers hold [lock]: the write is queued in the same order as the state changes. */
    private fun mutate(transform: (List<ActivityEntry>) -> List<ActivityEntry>) {
        val before = state.value
        val next = ActivityLogic.prune(transform(before), clock())
        if (next == before) return
        state.value = next
        val target = file ?: return
        io?.execute { write(target, next) }
    }

    private fun load(): List<ActivityEntry> {
        val source = file?.takeIf { it.isFile } ?: return emptyList()
        return try { ActivityCodec.decode(source.readText()) } catch (error: Exception) {
            Log.w(TAG, "activity.json unreadable; starting empty", error)
            emptyList()
        }
    }

    private fun write(target: File, list: List<ActivityEntry>) {
        try {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(ActivityCodec.encode(list))
            try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } catch (error: Exception) {
            Log.w(TAG, "activity.json not saved", error)
        }
    }

    companion object { private const val TAG = "KeepfinActivity" }
}

/** The on-disk shape: `{"v":1,"entries":[{id, kind, at, title, detail?, read, target?, count?, ...}]}`. */
object ActivityCodec {
    fun encode(entries: List<ActivityEntry>): String = JSONObject().put("v", 1).put("entries", JSONArray().also { array ->
        for (e in entries) array.put(JSONObject().put("id", e.id).put("kind", e.kind.wire).put("at", e.at).put("title", e.title)
            .putOpt("detail", e.detail).put("read", e.read).putOpt("count", e.count).putOpt("thumbnail", e.thumbnail)
            .put("open", e.open).put("away", e.away).put("target", JSONObject()
                .putOpt("collectionId", e.target.collectionId).putOpt("groupId", e.target.groupId).putOpt("version", e.target.version)
                .putOpt("provider", e.target.provider).put("jobIds", JSONArray(e.target.jobIds))))
    }).toString()

    fun decode(text: String): List<ActivityEntry> {
        val array = JSONObject(text).optJSONArray("entries") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = ActivityKind.of(json.optString("kind")) ?: return@mapNotNull null
            val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val target = json.optJSONObject("target")
            fun text(key: String, from: JSONObject? = json) = from?.takeIf { it.has(key) && !it.isNull(key) }?.optString(key)?.takeIf { it.isNotEmpty() }
            ActivityEntry(
                id = id, kind = kind, at = json.optLong("at"), title = json.optString("title"), detail = text("detail"), read = json.optBoolean("read"),
                target = ActivityTarget(
                    collectionId = text("collectionId", target), groupId = text("groupId", target), version = text("version", target), provider = text("provider", target),
                    jobIds = target?.optJSONArray("jobIds")?.let { ids -> (0 until ids.length()).map { ids.optString(it) } }.orEmpty(),
                ),
                count = if (json.has("count") && !json.isNull("count")) json.optInt("count") else null, thumbnail = text("thumbnail"),
                open = json.optBoolean("open"), away = json.optBoolean("away"),
            )
        }
    }
}
