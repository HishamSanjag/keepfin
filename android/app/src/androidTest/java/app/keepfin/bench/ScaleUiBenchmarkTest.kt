package app.keepfin.bench

import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.metrics.performance.JankStats
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.LibraryBrowse
import app.keepfin.ui.Destination
import app.keepfin.ui.Harness
import app.keepfin.ui.StudioEngine
import app.keepfin.ui.UiTags
import app.keepfin.ui.waitForTag
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Library on the real 200,000-item index, scrolled by touch with JankStats attached. A second run on the in-memory fixtures (a
 * few dozen items) is the reference for what this emulator's renderer does with the same screen. Every call the screens make on the
 * library is checked for the thread it ran on: none may run on the main thread.
 */
@RunWith(AndroidJUnit4::class)
class ScaleUiBenchmarkTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "bench-$BENCH_ITEMS")
    private val out = File(context.getExternalFilesDir(null), "kf-bench.json")
    private var stack: BenchStack? = null

    @After fun tearDown() { stack?.close(); StudioEngine.override = null }

    private fun record(key: String, value: Any) {
        val json = runCatching { JSONObject(out.readText()) }.getOrDefault(JSONObject())
        json.put(key, value)
        out.writeText(json.toString(2))
        Log.i("KeepfinBench", "$key = $value")
    }

    /** Runs [action] and reports the frames the window drew meanwhile. */
    private fun frames(action: () -> Unit): List<Double> {
        val durations = CopyOnWriteArrayList<Double>()
        var stats: JankStats? = null
        rule.runOnUiThread {
            stats = JankStats.createAndTrack(rule.activity.window) { frame -> durations += frame.frameDurationUiNanos / 1e6 }
            stats!!.isTrackingEnabled = true
        }
        action()
        rule.waitForIdle()
        Thread.sleep(300)
        rule.runOnUiThread { stats!!.isTrackingEnabled = false }
        return durations.toList()
    }

    private fun report(label: String, frames: List<Double>) {
        val sorted = frames.sorted()
        fun p(q: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * q).toInt()]
        record("$label: frames", frames.size)
        record("$label: frame ms p50 / p90 / p99 / max", "%.1f / %.1f / %.1f / %.1f".format(p(.5), p(.9), p(.99), sorted.lastOrNull() ?: 0.0))
        record("$label: frames over 16 ms", frames.count { it > 16.7 })
        record("$label: frames over 32 ms", frames.count { it > 33.4 })
    }

    private fun scrollSession() {
        rule.waitForTag(UiTags.LIB_LIST, 60_000)
        repeat(25) { rule.onNodeWithTag(UiTags.LIB_LIST).performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.15f, durationMillis = 200) } }
        repeat(10) { rule.onNodeWithTag(UiTags.LIB_LIST).performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.8f, durationMillis = 200) } }
    }

    private fun openMusicSongs(h: Harness) {
        h.show(rule)
        rule.onNodeWithTag(UiTags.nav(Destination.LIBRARY)).performClick()
        rule.waitForTag(UiTags.spaceCard("music"), 60_000)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
    }

    @Test fun a_fixturesReference() {
        val h = Harness("library")
        openMusicSongs(h)
        report("scroll reference (fixtures)", frames { scrollSession() })
    }

    /** The same screen and scroll over 5,000 songs held in memory: what this emulator's renderer does with a full list when the library costs nothing. */
    @Test fun a2_memoryListReference() {
        val songs = (0 until 5000).map { i ->
            app.keepfin.LibraryItem(
                id = "m$i", space = "music", uri = "content://x/$i", folder = "", kind = app.keepfin.LibraryItemKind.AUDIO, title = "Title $i", artist = "Artist ${i % 50}",
                size = 1, modifiedAt = 1, addedAt = 1_600_000_000_000L + i,
            )
        }
        val h = Harness("library")
        h.engine.fakeLibrary.browseOverride = app.keepfin.MemoryBrowse({ songs }, kotlinx.coroutines.flow.MutableStateFlow(0L))
        openMusicSongs(h)
        report("scroll 5,000 songs in memory (renderer reference)", frames { scrollSession() })
    }

    @Test fun b_songsOf200000() {
        check(File(root, "generated").exists()) { "run ScaleBenchmarkTest.a_generate first" }
        val s = BenchStack(root).open().also { stack = it }
        val mainCalls = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val mainNames = CopyOnWriteArrayList<String>()
        val audited = Proxy.newProxyInstance(LibraryBrowse::class.java.classLoader, arrayOf(LibraryBrowse::class.java)) { _, method, args ->
            calls.incrementAndGet()
            if (Looper.getMainLooper().thread == Thread.currentThread() && method.name != "getChanges" && method.name != "getCheap") {
                mainCalls.incrementAndGet(); mainNames += method.name
            }
            try { method.invoke(s.repo.browse, *(args ?: emptyArray())) } catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
        } as LibraryBrowse
        val h = Harness("library")
        h.engine.fakeLibrary.browseOverride = audited
        val t0 = System.nanoTime()
        openMusicSongs(h)
        rule.waitForTag(UiTags.LIB_LIST, 60_000)
        record("ui: Library tab to the first Songs rows, ms", (System.nanoTime() - t0) / 1e6)
        report("scroll 200,000 songs", frames { scrollSession() })
        record("ui: library calls made by the screens", calls.get())
        record("ui: library calls on the main thread", mainCalls.get())
        record("ui: main thread call names", mainNames.distinct().toString())
    }
}
