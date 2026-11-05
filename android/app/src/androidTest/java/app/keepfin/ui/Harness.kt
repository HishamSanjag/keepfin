package app.keepfin.ui

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.keepfin.ui.components.LocalRowObserver
import app.keepfin.ui.player.SimulatedPlayerPort
import app.keepfin.ui.components.LocalStudioImageLoader
import app.keepfin.ui.components.StudioImages
import app.keepfin.ui.preview.FakeEngine
import app.keepfin.ui.preview.FakeUpdates
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Everything a Compose UI test needs: fake engine, fake updater, isolated prefs, render and image counters. */
class Harness(
    scene: String = "empty",
    val updates: FakeUpdates = FakeUpdates(),
    val prefs: UiPreferences = UiPreferences(ApplicationProvider.getApplicationContext(), "ui-test-" + UUID.randomUUID()),
    freshCheck: Boolean = true,
) {
    val app: Application = ApplicationProvider.getApplicationContext()
    val engine = FakeEngine(app).also { it.inspectDelayMs = 60; it.discoverEntryDelayMs = 2; it.applyScene(scene) }

    // A fake engine installed globally keeps permission prompts, the foreground service and real files out of tests.
    init { StudioEngine.override = engine }

    // The simulated player moves its position four times a second while "playing", which keeps Compose from ever being idle: tests that
    // are not about the position hold it still (the ones that are, set this to true and control the clock themselves).
    init { SimulatedPlayerPort.ticks = false }

    init { if (freshCheck) prefs.lastUpdateCheck = System.currentTimeMillis() }

    // A link left on the emulator's clipboard by an earlier test must not raise the copied-link notice over unrelated tests.
    init { prefs.chooseOfferCopiedLinks(false) }

    val vm: StudioViewModel = StudioViewModel(app, engine, SavedStateHandle(), prefs, updates)

    /** Composition counts per job id (a row reports itself after every successful recomposition). */
    val rowRenders = ConcurrentHashMap<String, AtomicInteger>()

    /** Image requests per model; a stable row issues exactly one. */
    val imageRequests = ConcurrentHashMap<String, AtomicInteger>()

    val loader: ImageLoader = ImageLoader.Builder(app).components {
        add(object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                imageRequests.getOrPut(chain.request.data.toString()) { AtomicInteger() }.incrementAndGet()
                return chain.proceed()
            }
        })
        StudioImages.addTo(this, app)
    }.build()

    /** Opens Settings on one of its category pages, the way a tap on the overview row does. */
    fun openSettings(category: app.keepfin.ui.settings.SettingsCategory) { vm.navigate(Destination.SETTINGS); vm.chooseSettingsPage(category) }

    /** Opens Settings from an id the older Settings used (General, Performance, Updates...): the redirect map decides where it lands. */
    fun openSettings(legacyId: String) { vm.navigate(Destination.SETTINGS); check(vm.openSettings(legacyId)) { "unknown settings id $legacyId" } }

    /** Library off: the Library tab is the plain download history again. */
    fun libraryOff() = engine.updateSettings { it.copy(library = it.library.copy(enabled = false)) }

    fun renders(id: String) = rowRenders[id]?.get() ?: 0
    fun requests(model: String) = imageRequests[model]?.get() ?: 0

    /** [fontScale] and [widthDp] let a test render the app at a narrow phone width and a large font scale. */
    fun show(rule: androidx.compose.ui.test.junit4.ComposeContentTestRule, fontScale: Float? = null, widthDp: Int? = null) {
        rule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                LocalStudioImageLoader provides loader,
                LocalRowObserver provides { id -> rowRenders.getOrPut(id) { AtomicInteger() }.incrementAndGet() },
                androidx.compose.ui.platform.LocalDensity provides (if (fontScale == null) base else androidx.compose.ui.unit.Density(base.density, fontScale)),
            ) {
                if (widthDp == null) StudioApp(vm)
                else androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(widthDp.dp)) { StudioApp(vm) }
            }
        }
    }
}

fun SemanticsNodeInteractionsProvider.exists(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
fun SemanticsNodeInteractionsProvider.hasText(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

fun ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 10_000) = waitUntil(timeoutMs) { exists(tag) }

/** Brings an item of a lazy list on screen even when it is not composed yet. */
fun ComposeTestRule.scrollListTo(listTag: String, itemTag: String) {
    onNodeWithTag(listTag).performScrollToNode(androidx.compose.ui.test.hasTestTag(itemTag))
}

/** The Format dropdown of a collection or several links: what it shows now ("Video", "Audio", "Both"). */
fun ComposeTestRule.formatShown(): String = onNodeWithTag(UiTags.FORMAT_FOR_ALL).fetchSemanticsNode().config.let { config ->
    (config.run { if (contains(androidx.compose.ui.semantics.SemanticsProperties.EditableText)) get(androidx.compose.ui.semantics.SemanticsProperties.EditableText) else null }?.text ?: "")
}

/** Opens the Format dropdown, picks [label] and waits for the screen to settle. */
fun ComposeTestRule.chooseFormat(label: String) {
    onNodeWithTag(UiTags.FORMAT_FOR_ALL).performClick()
    onNodeWithTag("${UiTags.FORMAT_FOR_ALL}-$label").performClick()
    waitForIdle()
}

/** Whether the Format dropdown offers [label]; the menu is closed again afterwards. */
fun ComposeTestRule.formatOffers(label: String): Boolean {
    onNodeWithTag(UiTags.FORMAT_FOR_ALL).performClick()
    val offered = exists("${UiTags.FORMAT_FOR_ALL}-$label")
    // close the menu by choosing what is already shown
    onNodeWithTag("${UiTags.FORMAT_FOR_ALL}-${formatShown()}").performClick()
    waitForIdle()
    return offered
}

/** The download preview's Extras tab (Subtitles, Sponsor parts, In the queue...), and back to the main tab. */
fun ComposeTestRule.openExtras() {
    onNodeWithTag(UiTags.PREVIEW_TAB_EXTRAS).performClick()
    waitForIdle()
}

fun ComposeTestRule.closeExtras() {
    onNodeWithTag(UiTags.PREVIEW_TAB_MAIN).performClick()
    waitForIdle()
}