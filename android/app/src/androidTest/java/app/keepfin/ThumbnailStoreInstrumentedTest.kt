package app.keepfin

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ThumbnailStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun capturedArtworkSurvivesNewInstancesWithoutAnyNetwork() = withStore { directory ->
        val first = ThumbnailStore(context, directory)
        val url = "https://offline.invalid/thumbnail?token=old"
        val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            first.saveUrl(url, bitmap)
            first.promote(url, "retained-job")
            // A new store has no RAM state and the intentionally invalid hostname cannot supply artwork.
            val second = ThumbnailStore(context, directory)
            val captured = second.loadUrl(url)
            val retained = second.loadJob("retained-job")
            assertNotNull(captured)
            assertNotNull(retained)
            assertEquals(480, retained!!.width)
            assertEquals(270, retained.height)
            assertTrue(Color.blue(retained.getPixel(100, 100)) > 240)
            assertTrue("Cache filenames must not expose provider URLs or tokens", directory.walkTopDown().filter { it.isFile }.all {
                it.name.matches(Regex("[a-f0-9]{64}\\.jpg")) && it.length() <= 256 * 1024
            })
            captured!!.recycle(); retained.recycle()
        } finally { bitmap.recycle() }
    }

    @Test fun temporaryCachePruningNeverEvictsRetainedHistoryPreviews() = withStore { directory ->
        val store = ThumbnailStore(context, directory)
        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            store.saveUrl("initial-art", bitmap)
            store.promote("initial-art", "saved-job")
            repeat(180) { store.saveUrl("later-art-$it", bitmap) }
            assertTrue(File(directory, "links").listFiles()!!.size <= 160)
            val image = ThumbnailStore(context, directory).loadJob("saved-job")
            assertNotNull("History artwork must remain after link-cache pruning", image)
            image!!.recycle()
            store.removeJob("saved-job")
            assertNull(store.loadJob("saved-job"))
        } finally { bitmap.recycle() }
    }

    @Test fun orphanPruningRetainsOnlyRequestedJobImages() = withStore { directory ->
        val store = ThumbnailStore(context, directory)
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try {
            store.saveJob("keep", bitmap); store.saveJob("old", bitmap)
            store.retainJobs(setOf("keep"))
            assertNull(store.loadJob("old"))
            store.loadJob("keep").also { assertNotNull(it); it!!.recycle() }
        } finally { bitmap.recycle() }
    }

    private fun withStore(action: (File) -> Unit) {
        val directory = File(context.filesDir, "test-media-previews-${UUID.randomUUID()}")
        check(directory.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        try { action(directory) } finally { directory.deleteRecursively() }
    }
}
