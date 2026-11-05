package app.keepfin.bench

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.EngineSettings
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.LibraryHost
import app.keepfin.LibrarySettings
import app.keepfin.Space
import app.keepfin.PageDirection
import app.keepfin.core.library.DiffContext
import app.keepfin.core.library.LibCollection
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibModel
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibSpace
import app.keepfin.core.library.LibraryMapping
import app.keepfin.core.library.ObservedAnswer
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Op
import app.keepfin.core.sync.OpId
import app.keepfin.core.sync.encodeSegment
import app.keepfin.core.sync.segmentFileName
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.StoreOptions
import app.keepfin.folder.sql.AndroidSqlHost
import app.keepfin.folder.sql.BundledSql
import app.keepfin.folder.sql.SqlMergeState
import app.keepfin.library.repo.LibraryRepository
import app.keepfin.library.repo.RepositoryBackend
import java.io.File
import java.util.Random
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

private const val TAG = "KeepfinBench"
const val BENCH_ITEMS = 200_000
internal const val BENCH_LIBRARY = "55555555-5555-4555-8555-555555555555"
internal const val BENCH_ACTOR = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
internal const val BENCH_REPLICA = "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1"
internal const val BENCH_REMOTE = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** A library of 200,000 items built from ops, in the shapes a real one has (dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md section 9). */
object BenchData {
    val spaces = listOf(
        LibSpace("music", "Music", "music", "Music", layout = "collection"),
        LibSpace("videos", "Videos", "video", "Videos", layout = "collection"),
        LibSpace("downloads", "Downloads", "mixed", "Downloads", layout = "collection"),
    )

    private val tags = (0 until 40).map { "tag$it" }

    fun uuid(rnd: Random): String {
        val msb = (rnd.nextLong() and -0xf001L) or 0x4000L
        val lsb = (rnd.nextLong() and 0x3fffffffffffffffL) or Long.MIN_VALUE
        return UUID(msb, lsb).toString()
    }

    fun ids(count: Int): List<String> {
        val rnd = Random(7)
        return List(count) { uuid(rnd) }
    }

    fun item(i: Int, id: String): LibItem {
        val rnd = Random(i.toLong() * 31 + 5)
        // 70% songs, 10% videos in Videos, 20% of Downloads (audio, video and photos)
        val bucket = i % 20
        val kind: String
        val space: String
        val place: String
        when {
            bucket < 14 -> {
                kind = "audio"; space = "music"
                val artist = (i / 12) % 5000
                val album = (i / 12) % 12000
                place = "Music/Artist $artist/Album $album/Track $i.m4a"
            }
            bucket < 16 -> { kind = "video"; space = "videos"; place = "Videos/Series ${i % 400}/Episode $i.mp4" }
            else -> {
                space = "downloads"
                kind = when (bucket % 3) { 0 -> "audio"; 1 -> "video"; else -> "image" }
                place = "Downloads/Pack ${i % 600}/File $i." + (if (kind == "audio") "m4a" else if (kind == "video") "mp4" else "jpg")
            }
        }
        val plays = if (i % 4 == 0) 1 + rnd.nextInt(30) else 0
        return LibItem(
            id = id, space = space, kind = kind, title = "Title ${i.toString(36)} ${listOf("night", "river", "echo", "stone", "bloom")[i % 5]}",
            artist = if (kind == "audio") "Artist ${(i / 12) % 5000}" else null, album = if (kind == "audio" && space == "music") "Album ${(i / 12) % 12000}" else null,
            track = if (kind == "audio") (i % 12 + 1).toDouble() else null, duration = 60.0 + (i % 600), width = if (kind != "audio") 1920.0 else null, height = if (kind != "audio") 1080.0 else null,
            addedAt = 1_600_000_000_000.0 + i * 1000.0, favorite = i % 33 == 0, plays = plays.toDouble().takeIf { it > 0 }, lastPlayedAt = if (plays > 0) 1_690_000_000_000.0 + i * 500.0 else null,
            tags = listOf(tags[i % tags.size], tags[(i / 7) % tags.size]).distinct(), userTags = if (i % 97 == 0) listOf("mine") else emptyList(),
            sourceUrl = if (i % 5 == 0) "https://www.youtube.com/watch?v=v$i" else null, place = place,
        )
    }

    fun collections(ids: List<String>, rnd: Random, count: Int = 2000, per: Int = 100): List<LibCollection> = List(count) { c ->
        val members = LinkedHashSet<String>()
        while (members.size < per) members.add(ids[rnd.nextInt(ids.size)])
        LibCollection(uuid(rnd), "Playlist $c", "own", "music", members.toList(), createdAt = 1_690_000_000_000.0 + c, updatedAt = 1_690_000_000_000.0 + c)
    }
}

class QuietHost : LibraryHost {
    override val jobs = MutableStateFlow(emptyList<app.keepfin.Job>())
    override val settings = MutableStateFlow(EngineSettings(library = LibrarySettings(spaces = BenchData.spaces.map { Space(it.id, it.name, app.keepfin.SpaceKind.entries.first { k -> k.wire == it.kind }, it.folder) })))
    override fun updateSettings(transform: (EngineSettings) -> EngineSettings) { settings.value = transform(settings.value) }
    override fun jobThumbnail(jobId: String) = null
    override fun sidecarUris() = emptySet<String>()
    override fun repointFile(uri: String, publicPath: String?) {}
    override fun requestCheck(id: String?, review: Boolean) {}
    override fun followSettingsChanged() {}
}

/** One open library: the folder, the store on SQLite, the repository, and how long each step took. */
internal class BenchStack(val root: File, checkpointEvery: Int = 1_000_000_000) {
    val folder = LocalFolderAccess(File(root, "folder"))
    val deviceDir = File(root, "device")
    val timings = LinkedHashMap<String, Double>()
    lateinit var state: SqlMergeState
    lateinit var store: KeepfinStore
    lateinit var repo: LibraryRepository

    private val ckptEvery = checkpointEvery

    fun open(): BenchStack {
        val t0 = System.nanoTime()
        state = SqlMergeState(File(deviceDir, "state/state.db").path, BENCH_LIBRARY, BENCH_REPLICA, null, AndroidSqlHost())
        timings["state open (ms)"] = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        store = KeepfinStore.open(
            StoreOptions(
                folder = folder, libraryId = BENCH_LIBRARY, actor = BENCH_ACTOR, deviceDir = deviceDir, outboxDir = File(deviceDir, "outbox"),
                newId = { UUID.randomUUID().toString() }, flushIdleMs = 3_600_000, checkpointEvery = ckptEvery, checkpointDelayMs = 60_000, state = state,
            ),
        ).get(60, TimeUnit.MINUTES)
        timings["store open (ms)"] = (System.nanoTime() - t1) / 1e6
        val t2 = System.nanoTime()
        repo = LibraryRepository(QuietHost(), { RepositoryBackend(store, folder, BENCH_REPLICA, state) }, deviceDir)
        repo.start(); repo.awaitReady(600, collections = false)
        timings["repository ready (ms)"] = (System.nanoTime() - t2) / 1e6
        return this
    }

    fun close() {
        runCatching { repo.shutdown() }
        runCatching { store.abandon() }
        runCatching { state.close() }
    }

    fun ctx() = DiffContext(BENCH_ACTOR, BENCH_REPLICA, { UUID.randomUUID().toString() }, { 0L }, { emptyList() }, ObservedAnswer(emptyMap(), emptyMap(), emptyMap(), emptyMap()))
}

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ScaleBenchmarkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "bench-$BENCH_ITEMS")
    private val marker = File(root, "generated")
    private val out = File(context.getExternalFilesDir(null), "kf-bench.json")

    private fun record(key: String, value: Any) {
        val json = runCatching { JSONObject(out.readText()) }.getOrDefault(JSONObject())
        json.put(key, value)
        out.writeText(json.toString(2))
        Log.i(TAG, "$key = $value")
    }

    private fun javaHeapMb(): Double {
        repeat(3) { System.gc(); Thread.sleep(150) }
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1048576.0
    }

    private fun nativeHeapMb() = Debug.getNativeHeapAllocatedSize() / 1048576.0

    private fun rssMb(): Double = runCatching { File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.filter { it.isDigit() }.toLong() / 1024.0 }.getOrDefault(0.0)

    private fun median(values: List<Double>) = values.sorted()[values.size / 2]
    private fun pct(values: List<Double>, p: Double) = values.sorted()[((values.size - 1) * p).toInt()]

    /** The checkpoint parts: reopen with a checkpoint due after one op, so every shard is written once; samples the Java heap on the way. */
    private fun writeParts(ids: List<String>) {
        val again = BenchStack(root, checkpointEvery = 1).open()
        var peak = 0.0
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            val rt = Runtime.getRuntime()
            var n = 0
            while (sampling.get()) { val u = (rt.totalMemory() - rt.freeMemory()) / 1048576.0; peak = maxOf(peak, u); if (++n % 40 == 0) Log.i(TAG, "parts: java heap used ${"%.0f".format(u)} MB (total ${rt.totalMemory() / 1048576} MB), native ${"%.0f".format(nativeHeapMb())} MB"); Thread.sleep(50) }
        }.also { it.isDaemon = true; it.start() }
        val t1 = System.nanoTime()
        again.store.apply(listOf(OpDraft("set.add", mapOf("set" to JStr("favourites"), "element" to JStr(ids[1]))))).get(2, TimeUnit.MINUTES)
        again.store.onBackground().get(10, TimeUnit.MINUTES)
        again.store.close().get(10, TimeUnit.MINUTES)
        sampling.set(false)
        record("checkpoint: seconds to write the 17 parts", (System.nanoTime() - t1) / 1e9)
        record("checkpoint: peak Java heap while writing, MB", peak)
    }

    // 1. the library is built from ops (this is also the import measurement) and its checkpoint parts are written ----------------

    /** Writes the parts again over a generated library (diagnostic for the memory peak). */
    @Test fun a_writePartsAgain() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("partsAgain") != null)
        check(File(root, "folder/.keepfin/ops").isDirectory) { "run a_generate first" }
        File(root, "folder/.keepfin/checkpoints").deleteRecursively()
        writeParts(BenchData.ids(BENCH_ITEMS))
        marker.writeText("ok")
    }

    @Test fun a_generate() {
        if (marker.exists()) { Log.i(TAG, "library already generated"); return }
        root.deleteRecursively(); root.mkdirs()
        val stack = BenchStack(root).open()
        val ids = BenchData.ids(BENCH_ITEMS)
        val t0 = System.nanoTime()
        val rnd = Random(3)
        // the repository wrote the default spaces when it opened an empty folder; the items go into those
        val empty = LibModel(emptyList(), emptyList(), BenchData.spaces, LibRoutes("music", "downloads"))
        val batch = 2_000
        var added = 0
        while (added < BENCH_ITEMS) {
            val upto = minOf(BENCH_ITEMS, added + batch)
            val items = (added until upto).map { BenchData.item(it, ids[it]) }
            val drafts = LibraryMapping.diff(empty, empty.copy(items = items), stack.ctx())
            stack.store.apply(drafts.map { OpDraft(it.kind, it.body) }).get(10, TimeUnit.MINUTES)
            added = upto
            if (added % 20_000 == 0) Log.i(TAG, "generated $added items, ${(System.nanoTime() - t0) / 1_000_000_000}s, java heap after GC ${"%.0f".format(javaHeapMb())} MB, native ${"%.0f".format(nativeHeapMb())} MB")
        }
        val collections = BenchData.collections(ids, rnd)
        // the playlists name items that exist: a model with just a few playlists and stubs for the items they name, a slice at a time
        for (slice in collections.chunked(40)) {
            val named = slice.flatMap { it.itemIds }.toSet().map { LibItem(id = it, space = "", kind = "other", title = "") }
            LibraryMapping.diff(
                LibModel(named, emptyList(), BenchData.spaces, LibRoutes("music", "downloads")),
                LibModel(named, slice, BenchData.spaces, LibRoutes("music", "downloads")), stack.ctx(),
            ).chunked(5_000).forEach { chunk -> stack.store.apply(chunk.map { OpDraft(it.kind, it.body) }).get(10, TimeUnit.MINUTES) }
        }
        stack.store.flush().get(10, TimeUnit.MINUTES)
        val ops = stack.store.versionVector().get().values.sum()
        record("generate: ops written", ops)
        record("generate: seconds (200,000 items, ${collections.size} playlists, ops op by op)", (System.nanoTime() - t0) / 1e9)
        stack.close()
        writeParts(ids)
        val parts = File(root, "folder/.keepfin/checkpoints").walkTopDown().filter { it.isFile }.toList()
        record("checkpoint: part files", parts.size)
        record("checkpoint: part bytes (MB)", parts.sumOf { it.length() } / 1048576.0)
        record("folder: segment files", File(root, "folder/.keepfin/ops").walkTopDown().count { it.isFile })
        record("folder: segment bytes (MB)", File(root, "folder/.keepfin/ops").walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576.0)
        marker.writeText("ok")
    }

    // 2. cold build: no state.db, only the checkpoint parts ---------------------------------------------------------------

    @Test fun b_coldBuildFromParts() {
        check(marker.exists()) { "run a_generate first" }
        File(root, "device/state").deleteRecursively()
        val wall = System.nanoTime()
        val stack = BenchStack(root).open()
        val ms = (System.nanoTime() - wall) / 1e6
        val bulk = stack.state.lastBulkLoad
        record("cold build: total ms (state + store + repository + first snapshot)", ms)
        record("cold build: store open ms (parts load, segments above them)", stack.timings["store open (ms)"] ?: 0.0)
        record("cold build: used the checkpoint", stack.store.stats?.checkpoint ?: false)
        record("cold build: segments read above the checkpoint", stack.store.stats?.segmentsRead ?: -1)
        record("cold build: bulk load rows", bulk?.rows ?: -1)
        record("cold build: bulk load ms", bulk?.ms ?: -1.0)
        record("cold build: bulk load decode ms", bulk?.coreMs ?: -1.0)
        record("cold build: bulk load insert ms", bulk?.insertMs ?: -1.0)
        record("cold build: bulk load peak RSS MB", bulk?.peakRssMb ?: -1.0)
        record("cold build: index build ms", stack.state.lastIndexMs)
        stack.state.indexTimings.forEach { (k, v) -> record("cold build: index: $k ms", v) }
        record("cold build: state.db MB", File(root, "device/state/state.db").length() / 1048576.0)
        val n = stack.repo.browse.count(ItemQuery())
        record("cold build: items", n)
        stack.close()
    }

    @Test fun b2_indexRebuild() {
        check(marker.exists()) { "run a_generate first" }
        val stack = BenchStack(root).open()
        stack.state.rebuildIndex()
        record("index rebuild alone: ms", stack.state.lastIndexMs)
        stack.state.indexTimings.forEach { (k, v) -> record("index rebuild: $k ms", v) }
        record("index rebuild: items", stack.repo.browse.count(ItemQuery()))
        stack.close()
    }

    // 3. warm open: state.db kept, to the first library screen --------------------------------------------------------------

    @Test fun c_warmOpen() {
        check(marker.exists()) { "run a_generate first" }
        val times = ArrayList<Double>()
        val parts = LinkedHashMap<String, MutableList<Double>>()
        repeat(5) { run ->
            val t0 = System.nanoTime()
            val stack = BenchStack(root).open()
            val tOpen = System.nanoTime()
            // what the Library hub and the Songs list read for their first frame
            val b = stack.repo.browse
            b.page(ItemQuery(spaces = listOf("music"), kinds = setOf(app.keepfin.LibraryItemKind.AUDIO), order = ItemOrder.ADDED), PageDirection.FIRST, null, 80)
            b.spaceCounts()
            b.top(ItemQuery(resumable = true, order = ItemOrder.RECENT), 12)
            b.top(ItemQuery(order = ItemOrder.ADDED), 12)
            b.top(ItemQuery(everPlayed = true, order = ItemOrder.RECENT), 12)
            b.top(ItemQuery(favorite = true, order = ItemOrder.ADDED), 12)
            val tFirst = System.nanoTime()
            times += (tFirst - t0) / 1e6
            parts.getOrPut("open (state, store, repository)") { ArrayList() } += (tOpen - t0) / 1e6
            parts.getOrPut("first screen reads") { ArrayList() } += (tFirst - tOpen) / 1e6
            stack.timings.forEach { (k, v) -> parts.getOrPut(k) { ArrayList() } += v }
            stack.close()
        }
        record("warm open: to the first library screen, ms (median of 5)", median(times))
        record("warm open: all runs ms", times.map { Math.round(it) })
        parts.forEach { (k, v) -> record("warm open: $k, ms (median)", median(v)) }
    }

    // 4. local edits ---------------------------------------------------------------------------------------------------------

    @Test fun d_localEdits() {
        check(marker.exists()) { "run a_generate first" }
        val stack = BenchStack(root).open()
        val ids = BenchData.ids(BENCH_ITEMS)
        val rnd = Random(11)
        val favourite = ArrayList<Double>(); val tag = ArrayList<Double>(); val play = ArrayList<Double>(); val store = ArrayList<Double>()
        repeat(60) {
            val id = ids[rnd.nextInt(ids.size)]
            var t = System.nanoTime(); stack.repo.setFavorite(id, true); favourite += (System.nanoTime() - t) / 1e6
            t = System.nanoTime(); stack.repo.libraryTag(listOf(id), listOf("bench"), emptyList()); tag += (System.nanoTime() - t) / 1e6
            t = System.nanoTime(); stack.repo.recordPlayback(id, null, true); play += (System.nanoTime() - t) / 1e6
            // the store thread alone: one favourite op, durable in the outbox and admitted
            val other = ids[rnd.nextInt(ids.size)]
            t = System.nanoTime()
            stack.store.apply(listOf(OpDraft("set.add", mapOf("set" to JStr("favourites"), "element" to JStr(other))))).get(10, TimeUnit.SECONDS)
            store += (System.nanoTime() - t) / 1e6
        }
        record("edit: favourite end to end, ms median/p95", "${median(favourite).fmt()} / ${pct(favourite, .95).fmt()}")
        record("edit: tag end to end, ms median/p95", "${median(tag).fmt()} / ${pct(tag, .95).fmt()}")
        record("edit: play end to end, ms median/p95", "${median(play).fmt()} / ${pct(play, .95).fmt()}")
        record("edit: store thread (one op: outbox fsync, admit, index), ms median/p95", "${median(store).fmt()} / ${pct(store, .95).fmt()}")
        stack.close()
    }

    private fun Double.fmt() = "%.1f".format(this)

    // 5. remote batch of 100 -------------------------------------------------------------------------------------------------

    @Test fun e_remoteBatch() {
        check(marker.exists()) { "run a_generate first" }
        val stack = BenchStack(root).open()
        val ids = BenchData.ids(BENCH_ITEMS)
        val rnd = Random(13)
        val admit = ArrayList<Double>(); val visible = ArrayList<Double>()
        val remote = UUID.randomUUID().toString()
        var seq = 1L
        repeat(10) { round ->
            val first = seq
            val ops = (0 until 100).map { i ->
                val s = first + i
                Op(OpId(remote, s), Hlc(System.currentTimeMillis() + s, 0, remote), "item.set", mapOf("item" to JStr(ids[rnd.nextInt(ids.size)]), "field" to JStr("genre"), "value" to JStr("remote $round $i")))
            }
            seq += 100
            val path = "${KeepfinStore.OPS_DIR}/$remote/" + segmentFileName(first, first + 99)
            stack.folder.mkdirs("${KeepfinStore.OPS_DIR}/$remote")
            stack.folder.publish(path, encodeSegment(BENCH_LIBRARY, remote, ops))
            val t = System.nanoTime()
            stack.store.rescan(true).get(60, TimeUnit.SECONDS)
            admit += (System.nanoTime() - t) / 1e6
            // until the repository has re-read what changed
            val v0 = stack.repo.browse.changes.value
            val t2 = System.nanoTime()
            while (stack.repo.browse.changes.value == v0 && (System.nanoTime() - t2) < 5e9) Thread.sleep(2)
            visible += (System.nanoTime() - t) / 1e6
        }
        record("remote batch of 100: store thread (read segment, admit, index), ms median/p95", "${median(admit).fmt()} / ${pct(admit, .95).fmt()}")
        record("remote batch of 100: until the repository published it, ms median/p95 (includes its 100 ms coalescing wait)", "${median(visible).fmt()} / ${pct(visible, .95).fmt()}")
        stack.close()
    }

    // 6. memory -----------------------------------------------------------------------------------------------------------------

    @Test fun f_memory() {
        check(marker.exists()) { "run a_generate first" }
        val before = javaHeapMb(); val nativeBefore = nativeHeapMb(); val rssBefore = rssMb()
        val stack = BenchStack(root).open()
        stack.repo.awaitReady(300) // the collections too
        val b = stack.repo.browse
        // use it the way a session does: the hub, a few pages of every list, search, groups, a few edits
        val ids = BenchData.ids(BENCH_ITEMS)
        var key: List<Any>? = null
        var dir = PageDirection.FIRST
        repeat(60) {
            val p = b.page(ItemQuery(spaces = listOf("music"), order = ItemOrder.TITLE), dir, key, 80)
            key = p.next ?: return@repeat; dir = PageDirection.AFTER
        }
        b.albums(ItemQuery(spaces = listOf("music")), null, 200); b.artists(ItemQuery(spaces = listOf("music")), null, 200)
        b.count(ItemQuery(text = "night")); b.top(ItemQuery(text = "river echo"), 50)
        b.tags(ItemQuery(spaces = listOf("music")), 2); b.spaceCounts()
        repeat(20) { stack.repo.setFavorite(ids[it * 7], true) }
        val after = javaHeapMb(); val nativeAfter = nativeHeapMb(); val rssAfter = rssMb()
        record("memory: Java heap before the library, MB", before)
        record("memory: Java heap with the library open and used, MB", after)
        record("memory: Java heap from the library, MB", after - before)
        record("memory: native heap (malloc) from the library, MB", nativeAfter - nativeBefore)
        record("memory: process RSS before / after, MB", "${rssBefore.fmt()} / ${rssAfter.fmt()}")
        record("sqlite: version and FTS5", BundledSql.probe(File(root, "device/state/state.db").path))
        stack.close()
    }
}
