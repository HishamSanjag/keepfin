package app.keepfin.setup

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.library.repo.LinkedStore
import app.keepfin.library.repo.MediaStoreLinked
import java.io.File
import java.util.UUID
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The platform side of linked spaces over real MediaStore rows this app created (nothing of the user's is touched). */
@RunWith(AndroidJUnit4::class)
class LinkedMediaInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val cr = ctx.contentResolver
    private val media = MediaStoreLinked(ctx)
    private val tag = "KeepfinLinkedTest" + UUID.randomUUID().toString().take(6)
    private val made = ArrayList<Uri>()

    @Before fun allowReading() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) listOf("READ_MEDIA_AUDIO", "READ_MEDIA_VIDEO") else listOf("READ_EXTERNAL_STORAGE")
        permissions.forEach { InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ctx.packageName, "android.permission.$it") }
    }

    @After fun cleanup() { made.forEach { runCatching { cr.delete(it, null, null) } } }

    private fun song(name: String, bytes: Int = 300): String {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name); put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$tag"); put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        cr.openOutputStream(uri)!!.use { it.write(ByteArray(bytes) { i -> i.toByte() }) }
        cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        made += uri
        return uri.toString()
    }

    @Test fun aRowIsDescribedRenamedMovedFoundAndRemoved() {
        val uri = song("one.wav", 300)
        val where = media.stat(uri)!!
        assertEquals("Music/$tag/one.wav", where.path)
        assertEquals(300L, where.size)
        assertTrue(media.canRead())
        assertEquals(setOf(uri), media.existing(listOf(uri)))

        // found again by its relative path and name (the way "Find linked files" does after a reinstall)
        val found = media.find(listOf("Music/$tag/ONE.wav"))
        assertEquals(1, found.size)
        assertEquals(300L, found.values.single().size)
        assertTrue(where.sameFileAs(found.values.single()))

        // this app made the row, so nothing needs a yes
        media.rename(uri, "uno.wav")
        assertEquals("Music/$tag/uno.wav", media.stat(uri)!!.path)
        media.move(uri, "Music/$tag/Sub/")
        assertEquals("Music/$tag/Sub/uno.wav", media.stat(uri)!!.path)

        assertTrue(media.delete(uri))
        assertNull(media.stat(uri))
        assertFalse(uri in media.existing(listOf(uri))!!)
    }

    @Test fun addressesAreWrittenAtomicallyAndReadBack() {
        val file = File(ctx.cacheDir, "linked-" + UUID.randomUUID() + ".json")
        try {
            val store = LinkedStore(file)
            store.putAll(mapOf("a" to app.keepfin.library.repo.LinkedLocator("content://media/external/audio/media/1", "Music/x/a.mp3", 5, 6, true)))
            assertNotNull(LinkedStore(file).get("a"))
            LinkedStore(file).remove(listOf("a"))
            assertEquals(0, LinkedStore(file).size)
        } finally { file.delete() }
    }
}
