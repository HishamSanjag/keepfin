package app.keepfin.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.BuildConfig
import app.keepfin.MainActivity
import app.keepfin.ui.preview.FakeEngine
import app.keepfin.ui.preview.FakeUpdates
import app.keepfin.ui.preview.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Flows that need the real MainActivity: intents, recreation and the ViewModel store. */
@RunWith(AndroidJUnit4::class)
class ActivityFlowsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var engine: FakeEngine

    @Before fun setUp() {
        engine = FakeEngine(context).also { it.inspectDelayMs = 60; it.discoverEntryDelayMs = 40; it.applyScene("empty") }
        StudioEngine.override = engine
        StudioEngine.updatesOverride = FakeUpdates(BuildConfig.VERSION_NAME)
    }

    @After fun tearDown() { StudioEngine.override = null; StudioEngine.updatesOverride = null }

    private fun launch(intent: Intent = Intent(context, MainActivity::class.java)) =
        ActivityScenario.launch<MainActivity>(intent.setClass(context, MainActivity::class.java))

    @Test fun sharedLinkOpensReviewAndNeverDownloads() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Watch https://www.youtube.com/watch?v=aqz-KE-bpKQ now")
        launch(intent).use {
            rule.waitForTag(UiTags.REVIEW)
            assertEquals(listOf("https://www.youtube.com/watch?v=aqz-KE-bpKQ"), engine.inspectCalls.toList())
            assertTrue(engine.enqueued.isEmpty())
            assertTrue(engine.jobs.value.isEmpty())
        }
    }

    @Test fun collectionSelectionSurvivesActivityRecreation() {
        engine.discoverEntryDelayMs = 2
        launch().use { scenario ->
            rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://www.youtube.com/playlist?list=PLblender")
            rule.waitForTag(UiTags.COLLECTION)
            val urls = (1..3).map { Fixtures.entry(it).url }
            urls.forEach { rule.waitForTag(UiTags.entry(it)) }
            // A playlist opens with everything selected, including the entries still arriving.
            rule.waitUntil(10_000) { rule.hasText("Download 60") }
            urls.forEach { rule.onNodeWithTag(UiTags.entry(it)).performClick() }
            rule.waitUntil(10_000) { rule.hasText("Download 57") }
            scenario.recreate()
            rule.waitForTag(UiTags.COLLECTION)
            rule.onNodeWithText("Download 57").assertExists()
            // Back hides the sheet; Downloads keeps the collection as a summary row that reopens it.
            androidx.test.espresso.Espresso.pressBack()
            rule.waitUntil(5_000) { !rule.exists(UiTags.COLLECTION) }
            rule.waitForTag(UiTags.COLLECTION_RESUME)
        }
    }

    @Test fun downloadingSelectedStopsDiscoveryFirstAndEnqueuesOnlySelection() {
        engine.discoverEntryDelayMs = 120
        engine.collectionSize = 200
        launch().use {
            rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://www.youtube.com/playlist?list=PLblender")
            rule.waitForTag(UiTags.COLLECTION)
            val first = Fixtures.entry(1).url
            rule.waitForTag(UiTags.entry(first))
            // A playlist opens with everything selected: clear it, then pick only the first entry.
            rule.waitForTag(UiTags.COLLECTION_SELECT_NONE)
            rule.onNodeWithTag(UiTags.COLLECTION_SELECT_NONE).performClick()
            rule.onNodeWithTag(UiTags.entry(first)).performClick()
            rule.onNodeWithTag(UiTags.COLLECTION_DOWNLOAD).performClick()
            rule.waitUntil(15_000) { engine.enqueuedEntries.size == 1 }
            assertTrue("discovery was stopped before enqueueing", engine.cancelledDiscoveries.isNotEmpty())
            assertEquals(listOf(first), engine.enqueuedEntries.single().first.map { entry -> entry.url })
            rule.waitUntil(5_000) { !rule.exists(UiTags.COLLECTION) }
        }
    }

    @Test fun loadMoreFetchesTheNextPage() {
        engine.collectionSize = 450
        engine.discoverEntryDelayMs = 0
        launch().use {
            rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://www.youtube.com/playlist?list=PLblender")
            rule.waitForTag(UiTags.COLLECTION)
            rule.waitForTag(UiTags.COLLECTION)
            rule.waitUntil(30_000) { engine.discoverCalls.isNotEmpty() && !rule.hasText("still searching") }
            rule.onNodeWithTag(UiTags.COLLECTION_LIST).performScrollToNode(hasTestTag(UiTags.COLLECTION_LOAD_MORE))
            assertEquals(1 to StudioViewModel.COLLECTION_PAGE, engine.discoverCalls.first())
            rule.onNodeWithTag(UiTags.COLLECTION_LOAD_MORE).performScrollTo().performClick()
            rule.waitUntil(10_000) { engine.discoverCalls.size == 2 }
            assertEquals(201 to 400, engine.discoverCalls[1])
        }
    }

    @Test fun backWalksFromSettingsToDownloads() {
        launch().use { scenario ->
            rule.onNodeWithTag(UiTags.nav(Destination.SETTINGS)).performClick()
            rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitForTag(UiTags.LINK_FIELD)
        }
    }

    @Test fun notificationTapRevealsTheLibrary() {
        engine.applyScene("library")
        // With the library on, History opens Downloads › Finished; with it off, the Library tab is the history view.
        engine.updateSettings { it.copy(library = it.library.copy(enabled = false)) }
        val intent = Intent().putExtra(MainActivity.EXTRA_NOTIFICATION_PAGE, "History")
        launch(intent).use {
            rule.waitForTag(UiTags.LIBRARY_LIST)
        }
    }
}
