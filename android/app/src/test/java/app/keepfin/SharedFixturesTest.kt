package app.keepfin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue

import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Runs the language-neutral engine fixtures in every JSON file under fixtures/engine against the real Kotlin logic. The desktop runner
 * (gui/shared/fixtures.test.ts) runs the same files; see fixtures/engine/KOTLIN-RUNNER.md and README.md for the rules.
 * One @Test per category; every failing case is collected and reported together as "category/file: case name".
 */
class SharedFixturesTest {
    private fun fail(message: String): Nothing = throw AssertionError(message)

    // ---- locating and loading --------------------------------------------------------------------------------

    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "fixtures/engine")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        error("fixtures/engine was not found above ${System.getProperty("user.dir")}; run the unit tests from the repository checkout.")
    }

    private class FixtureCase(val label: String, val case: JSONObject) {
        val name: String get() = case.getString("name")
        val input: JSONObject get() = case.getJSONObject("input")
        val expected: Any? get() = case.opt("expected")
    }

    private fun files(category: String): List<File> = File(root, category).walkTopDown()
        .filter { it.isFile && it.extension == "json" && it.parentFile.name != "samples" }.sortedBy { it.path }.toList()

    private fun cases(category: String): List<Pair<FixtureCase, Boolean>> = files(category).flatMap { file ->
        val json = JSONObject(file.readText())
        val array = json.getJSONArray("cases")
        val label = "$category/${file.nameWithoutExtension}"
        val seen = HashSet<String>()
        (0 until array.length()).map { index ->
            val case = array.getJSONObject(index)
            val item = FixtureCase(label, case)
            require(seen.add(item.name)) { "duplicate case name \"${item.name}\" in $label" }
            val platforms = case.optJSONArray("platforms")
            val runs = platforms == null || (0 until platforms.length()).any { platforms.optString(it) == "android" }
            item to runs
        }
    }


    // ---- comparison ------------------------------------------------------------------------------------------

    private fun isNull(value: Any?) = value == null || value == JSONObject.NULL

    /** `expected` is a subset of `actual` (README "Comparison rules"). [tolerance] names keys that may differ by 1. */
    private fun assertSubset(actual: Any?, expected: Any?, where: String, tolerance: Set<String> = setOf("size")) {
        when {
            isNull(expected) -> if (!isNull(actual)) fail("$where: expected null, got $actual")
            expected is JSONArray -> {
                if (actual !is JSONArray) fail("$where: expected an array, got $actual")
                actual as JSONArray
                if (actual.length() != expected.length()) fail("$where: array length ${actual.length()} vs ${expected.length()} (actual $actual)")
                for (i in 0 until expected.length()) assertSubset(actual.opt(i), expected.opt(i), "$where[$i]", tolerance)
            }
            expected is JSONObject -> {
                if (actual !is JSONObject) fail("$where: expected an object, got $actual")
                actual as JSONObject
                for (key in expected.keys()) {
                    val want = expected.get(key)
                    val got = actual.opt(key)
                    if (key in tolerance && want is Number && got is Number) {
                        if (abs(got.toDouble() - want.toDouble()) > 1.0) fail("$where.$key: $got vs $want")
                    } else assertSubset(got, want, "$where.$key", tolerance)
                }
            }
            expected is Number -> {
                if (actual !is Number || abs(actual.toDouble() - expected.toDouble()) > 1e-9) fail("$where: expected $expected, got $actual")
            }
            else -> if (actual != expected) fail("$where: expected $expected, got $actual")
        }
    }

    // ---- runner ----------------------------------------------------------------------------------------------

    private fun run(category: String, check: (FixtureCase) -> Unit) {
        val failures = ArrayList<String>()
        var ran = 0
        var skipped = 0
        var known = 0
        for ((item, runs) in cases(category)) {
            if (!runs) { skipped++; continue }
            ran++
            val mismatch = item.case.optString("suspectedMismatch", "").takeIf { it.isNotEmpty() }
            val failure = try { check(item); null } catch (e: Throwable) { "${e.message ?: e::class.java.simpleName}" }
            when {
                mismatch != null && failure != null -> { known++; println("KNOWN MISMATCH ${mismatch.substringBefore(' ')}: ${item.label}: ${item.name}") }
                mismatch != null -> failures.add("${item.label}: ${item.name} now passes: remove its suspectedMismatch")
                failure != null -> failures.add("${item.label}: ${item.name}\n      $failure")
            }
        }
        println("fixtures $category: ran=$ran skipped=$skipped knownMismatches=$known failed=${failures.size}")
        assertTrue("fixtures $category: ran $ran cases", ran > 0)
        if (failures.isNotEmpty()) fail("${failures.size} of $ran $category fixture cases failed:\n  - " + failures.joinToString("\n  - "))
    }

    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.bool(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { (k, v) -> if (v != null) o.put(k, v) } }
    private fun arr(items: Iterable<Any?>) = JSONArray().also { a -> items.forEach { a.put(it ?: JSONObject.NULL) } }

    // ---- formats ---------------------------------------------------------------------------------------------

    /**
     * Formats, sizes, codecs, subtitles and the clean title are decided by the shared core (app.keepfin.core.formats). The runner
     * is shared with the device test (CoreFormatsFixturesDeviceTest): both run src/sharedTest/.../FormatsFixtureRunner.kt.
     */
    @Test fun formats() {
        val result = FormatsFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures formats: ran=${result.ran} skipped=${result.skipped} failed=${result.failures.size}")
        assertTrue("fixtures formats: ran ${result.ran} cases", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} formats fixture cases failed: " + result.failures.joinToString(" | "))
    }

    // ---- args ------------------------------------------------------------------------------------------------

    /**
     * Validation, presets, the format selection and every option argument, the embedded tags, the label and the expected size are decided
     * by the shared core (app.keepfin.core.args). The runner is shared with the device test (CoreArgsFixturesDeviceTest): both run
     * src/sharedTest/.../ArgsFixtureRunner.kt through [ArgsPolicy].
     */
    @Test fun args() {
        val result = ArgsFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures args: ran=${result.ran} skipped=${result.skipped} failed=${result.failures.size}")
        assertTrue("fixtures args: ran ${result.ran} cases", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} args fixture cases failed: " + result.failures.joinToString(" | "))
    }

    // ---- progress --------------------------------------------------------------------------------------------

    /**
     * Progress parsing is the shared core's (app.keepfin.core.progress). The runner is shared with the device test
     * (CoreProgressFixturesDeviceTest): both run src/sharedTest/.../ProgressFixtureRunner.kt.
     */
    @Test fun progress() {
        val result = ProgressFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures progress: ran=${result.ran} skipped=${result.skipped} failed=${result.failures.size}")
        assertTrue("fixtures progress: ran ${result.ran} cases", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} progress fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }

    // ---- errors ----------------------------------------------------------------------------------------------

    /**
     * Failure classes, retry kinds, scrubbing and retry pacing are decided by the shared core (app.keepfin.core.errors). The runner is
     * shared with the device test (CoreErrorsFixturesDeviceTest): both run src/sharedTest/.../ErrorsFixtureRunner.kt.
     */
    @Test fun errors() {
        val result = ErrorsFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures errors: ran=${result.ran} skipped=${result.skipped} failed=${result.failures.size}")
        assertTrue("fixtures errors: ran ${result.ran} cases", result.ran > 0)
        assertTrue("messages naming a tool: ${ErrorsFixtureRunner.everyFixedMessageIsFree()}", ErrorsFixtureRunner.everyFixedMessageIsFree().isEmpty())
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} errors fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }

    // ---- providers -------------------------------------------------------------------------------------------

    /**
     * Providers, routing and naming are decided by the shared core (app.keepfin.core.providers). The runner is shared with the
     * device test (CoreProvidersFixturesDeviceTest): both run src/sharedTest/.../ProvidersFixtureRunner.kt.
     */
    @Test fun providers() {
        val result = ProvidersFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures providers: ran=${result.ran} failed=${result.failures.size}")
        assertTrue("fixtures providers: ran ${result.ran} cases", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} providers fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }

    // ---- music -----------------------------------------------------------------------------------------------

    /**
     * Music matching is decided by the shared core (app.keepfin.core.music). The runner is shared with the device test
     * (CoreMusicFixturesDeviceTest): both run src/sharedTest/.../MusicFixtureRunner.kt, only the way fixtures are read differs.
     */
    @Test fun music() {
        val result = MusicFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures music: ran=${result.ran} skipped=${result.skipped} knownMismatches=${result.knownMismatches} failed=${result.failures.size}")
        assertTrue("fixtures music: ran ${result.ran} cases", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} music fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }

    // ---- dedupe ----------------------------------------------------------------------------------------------

    /**
     * Skip-existing is decided by the shared core (app.keepfin.core.dedupe.Recordings). The runner is shared with the device test
     * (CoreDedupeFixturesDeviceTest): both run src/sharedTest/.../DedupeFixtureRunner.kt, only the way fixtures are read differs.
     */
    @Test fun dedupe() {
        val result = DedupeFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures dedupe: ran=${result.ran} skipped=${result.skipped} failed=${result.failures.size}")
        assertTrue("fixtures dedupe: ran ${result.ran} cases", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} dedupe fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }
    // ---- versions --------------------------------------------------------------------------------------------

    /**
     * Every versions op runs through the shared core (app.keepfin.core.versions.Releases), the code the app runs. The same runner
     * also runs on a device (CoreVersionsFixturesDeviceTest); the release-feed parsing that stays in Android (UpdateRelease.select)
     * is covered by UpdateChannelTest.
     */
    @Test fun versions() {
        val result = VersionsFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures versions: ran=${result.ran} skipped=${result.skipped} failed=${result.failures.size}")
        assertTrue("fixtures versions: ran ${result.ran} cases", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} versions fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }

    // ---- lyrics ----------------------------------------------------------------------------------------------

    /**
     * The lyrics requests, record choosing and retry decisions are the shared core's (app.keepfin.core.lyrics.Lyrics), the code
     * LyricsLookup calls. The lookup around it (waiting, cancelling, the HTTP call) is covered by LyricsLookupTest.
     */
    @Test fun lyrics() {
        val result = LyricsFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures lyrics: ran=${result.ran} skipped=${result.skipped} failed=${result.failures.size}")
        assertTrue("fixtures lyrics: ran ${result.ran} cases", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} lyrics fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }

    // ---- playback --------------------------------------------------------------------------------------------

    /**
     * Player decisions (skips that add up, resume, chapters, what plays next, title order, the time label) are the shared core's
     * (app.keepfin.core.playback). The runner is shared with the device test (CorePlaybackFixturesDeviceTest).
     */
    @Test fun playback() {
        val result = PlaybackFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures playback: ran=${result.ran} skipped=${result.skipped} failed=${result.failures.size}")
        assertTrue("fixtures playback: ran ${result.ran} cases", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} playback fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }

    // ---- choice ----------------------------------------------------------------------------------------------

    /** What a preview offers, opens with and estimates is the shared core's (app.keepfin.core.choice.DownloadChoice); FormatChoice calls it. */
    @Test fun choice() {
        val result = ChoiceFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures choice: ran=${result.ran} skipped=${result.skipped} failed=${result.failures.size}")
        assertTrue("fixtures choice: ran ${result.ran} cases", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} choice fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }

    // ---- after -----------------------------------------------------------------------------------------------

    /** The steps of a finished download and what a failure of each means are the shared core's (app.keepfin.core.after.AfterDownload). */
    @Test fun after() {
        val result = AfterFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures after: ran=${result.ran} skipped=${result.skipped} failed=${result.failures.size}")
        assertTrue("fixtures after: ran ${result.ran} cases", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} after fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }

    // ---- spaces ----------------------------------------------------------------------------------------------

    /**
     * Spaces v2 logic (smart rules, tags, new items, the confirm gate, collections, follow, the hub) is decided by the shared core
     * (app.keepfin.core.spaces). The runner is shared with the device test (CoreSpacesFixturesDeviceTest): both run
     * src/sharedTest/.../SpacesFixtureRunner.kt. The app's own wrappers (TagLogic, SmartLogic, CollectionLogic, SpaceLogic) are
     * covered by SpacesV2Test, OptInFollowTest and the other Android unit tests.
     */
    @Test fun spaces() {
        val result = SpacesFixtureRunner.run(DirectoryFixtureFiles(root.parentFile))
        println("fixtures spaces: ran=${result.ran} failed=${result.failures.size}")
        assertTrue("fixtures spaces: ran ${result.ran} cases", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} spaces fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }
}
