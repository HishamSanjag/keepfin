package app.keepfin.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the language-neutral cases in fixtures/engine against the core, on every target.
 * Add a category here when its area moves into the core.
 */
class FixturesTest {
    private fun check(category: String, result: CategoryResult) {
        println("fixtures $category: ${result.ran} ran, ${result.skipped} skipped, ${result.failures.size} failed")
        if (result.failures.isNotEmpty()) {
            fail("${result.failures.size} fixture failure(s) in $category:\n" + result.failures.joinToString("\n"))
        }
    }

    @Test
    fun fixturesFolderIsFound() {
        assertTrue(listDirectory(requireFixturesRoot()).isNotEmpty())
    }

    @Test
    fun music() = check("music", runCategory("music", ::musicHandler, ::compareMusic))

    // Progress parses both platforms' engine output: every case runs once per platform it is marked for.
    @Test
    fun progressDesktop() = check("progress (desktop)", runCategory("progress", progressHandler("desktop"), ::compareProgress, "desktop"))

    @Test
    fun progressAndroid() = check("progress (android)", runCategory("progress", progressHandler("android"), ::compareProgress, "android"))

    @Test
    fun errorsDesktop() = check("errors (desktop)", runCategory("errors", { errorsHandler(it, app.keepfin.core.errors.Platform.DESKTOP) }, platform = "desktop"))

    @Test
    fun errorsAndroid() = check("errors (android)", runCategory("errors", { errorsHandler(it, app.keepfin.core.errors.Platform.ANDROID) }, platform = "android"))

    @Test
    fun versions() = check("versions", runCategory("versions", ::versionsHandler))

    @Test
    fun playback() = check("playback", runCategory("playback", ::playbackHandler))

    @Test
    fun gallery() = check("gallery", runCategory("gallery", ::galleryHandler))

    @Test
    fun signin() = check("signin", runCategory("signin", ::signinHandler))

    @Test
    fun compaction() = check("compaction", runCategory("compaction", ::compactionHandler))

    @Test
    fun lyrics() =check("lyrics", runCategory("lyrics", ::lyricsHandler))

    @Test
    fun choice() = check("choice", runCategory("choice", ::choiceHandler, platforms = setOf("desktop", "android")))

    @Test
    fun after() = check("after", runCategory("after", ::afterHandler, platforms = setOf("desktop", "android")))

    @Test
    fun dedupe() = check("dedupe", runCategory("dedupe", ::dedupeHandler))

    @Test
    fun providers() = check("providers", runCategory("providers", ::providersHandler, platforms = setOf("desktop", "android")))

    @Test
    fun formatsDesktop() = check("formats (desktop)", runCategory("formats", { formatsHandler(it, desktopFormatOptions) }, platform = "desktop"))

    @Test
    fun formatsAndroid() = check("formats (android)", runCategory("formats", { formatsHandler(it, androidFormatOptions) }, platform = "android"))

    @Test
    fun spaces() = check("spaces", runCategory("spaces", ::spacesHandler, platforms = setOf("desktop", "android")))

    // The plan differs only in what the phone's engine can do (ARGS-9), so the same cases run once per platform.
    @Test
    fun argsDesktop() = check("args (desktop)", runCategory("args", { argsHandler(it, desktopPlanPlatform) }, platform = "desktop"))

    @Test
    fun argsAndroid() = check("args (android)", runCategory("args", { argsHandler(it, androidPlanPlatform) }, platform = "android"))
}
