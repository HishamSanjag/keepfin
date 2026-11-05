package app.keepfin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-app guide is content/guide.json: it must parse, hide other platforms and search every field. */
class GuideTest {
    private val source = listOf("../../content/guide.json", "../content/guide.json", "content/guide.json").map(::File).first { it.isFile }
    private val guide = GuideLogic.parse(source.readText(Charsets.UTF_8))

    @Test fun parsesSectionsAndFeatures() {
        assertTrue(guide.sections.isNotEmpty())
        assertTrue(guide.featureCount >= 20)
        guide.sections.flatMap { it.features }.forEach {
            assertTrue("${it.id} needs steps", it.steps.isNotEmpty())
            assertTrue("${it.id} has a known try id", it.tryId == null || it.tryId in GuideLogic.TRY_IDS)
        }
    }

    @Test fun androidGuideKeepsAndroidAndSharedFeaturesOnly() {
        val android = GuideLogic.forAndroid(guide)
        val all = android.sections.flatMap { it.features }
        assertTrue(all.all { it.platforms == null || "android" in it.platforms })
        assertTrue(all.any { it.id == "share" })
        val desktopOnly = guide.sections.flatMap { it.features }.filter { it.platforms != null && "android" !in it.platforms }
        desktopOnly.forEach { gone -> assertFalse(all.any { it.id == gone.id }) }
    }

    @Test fun searchMatchesTitleSummaryStepsAndDropsEmptySections() {
        val android = GuideLogic.forAndroid(guide)
        assertEquals(android, GuideLogic.search(android, "  "))
        val hit = GuideLogic.search(android, "playlist")
        assertTrue(hit.featureCount in 1 until android.featureCount)
        assertTrue(hit.sections.all { it.features.isNotEmpty() })
        assertEquals(0, GuideLogic.search(android, "zzzz-not-a-word").featureCount)
        // every word has to match, in any field
        assertTrue(GuideLogic.search(android, "paste link").featureCount >= 1)
    }

    @Test fun followShowsTheAndroidNote() {
        val follow = GuideLogic.forAndroid(guide).sections.flatMap { it.features }.first { it.id == "follow" }
        assertTrue(follow.androidNote!!.contains("background"))
    }
}
