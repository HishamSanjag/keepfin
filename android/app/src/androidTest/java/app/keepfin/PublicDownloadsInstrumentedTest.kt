package app.keepfin

import android.provider.MediaStore
import android.net.Uri
import android.util.Size
import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PublicDownloadsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Publication requires retained inputs; Android can evict cache files at any point under storage pressure.

    @Test fun collisionsKeepMediaAndSidecarsTogether() {
        val publisher = PublicDownloads(context)
        val stem = "Keepfin-publish-test-${System.nanoTime()}"
        val main = videoFixture(stem)
        val subtitles = File(context.filesDir, "$stem.en.vtt").apply { writeText("WEBVTT\n\n00:00.000 --> 00:01.000\nTest") }
        val created = mutableListOf<PublicDownloads.Saved>()
        try {
            val first = publisher.publish(listOf(main, subtitles), stem)
            created.addAll(first.values)
            val second = publisher.publish(listOf(main, subtitles), stem)
            created.addAll(second.values)
            assertTrue(second.getValue(main).path.endsWith("$stem (2).mp4"))
            assertTrue(second.getValue(main).path.startsWith("Movies/Keepfin/"))
            assertTrue(second.getValue(subtitles).path.startsWith("Download/Keepfin/"))
            assertTrue(second.getValue(subtitles).path.endsWith("$stem (2).en.vtt"))
            for ((source, saved) in first + second) {
                assertArrayEquals(source.readBytes(), context.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() })
                context.contentResolver.query(saved.uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
        } finally { publisher.discard(created); main.delete(); subtitles.delete() }
    }

    @Test fun failedGroupRollsBackOnlyNewCopies() {
        val publisher = PublicDownloads(context)
        val stem = "Keepfin-rollback-test-${System.nanoTime()}"
        val main = videoFixture(stem)
        val missing = File(context.filesDir, "$stem.en.vtt")
        val original = publisher.publish(listOf(main), stem)
        try {
            try { publisher.publish(listOf(main, missing), stem); fail("Missing sidecar must fail") }
            catch (_: IllegalArgumentException) { }
            assertArrayEquals(main.readBytes(), context.contentResolver.openInputStream(original.getValue(main).uri)!!.use { it.readBytes() })
            assertEquals(listOf(main.name), names(stem))
        } finally { publisher.discard(original.values); main.delete() }
    }

    @Test fun cancellationRemovesPartialPublicOutputAndKeepsOriginal() {
        val publisher = PublicDownloads(context)
        val stem = "Keepfin-cancel-test-${System.nanoTime()}"
        val main = File(context.filesDir, "$stem.mp4").apply { writeBytes(ByteArray(180_000) { 42 }) }
        var checks = 0
        try {
            try { publisher.publish(listOf(main), stem) { ++checks >= 3 }; fail("Copy must cancel") }
            catch (_: IllegalStateException) { }
            assertEquals(180_000L, main.length())
            assertTrue(names(stem).isEmpty())
        } finally { main.delete() }
    }

    @Test fun galleryVideoHasAnAppAlbumMetadataAndDecodableThumbnail() {
        val publisher = PublicDownloads(context)
        val main = videoFixture("Keepfin-gallery-test-${System.nanoTime()}")
        val saved = publisher.publish(listOf(main))
        try {
            val video = saved.getValue(main)
            assertTrue(video.uri.toString().contains("/video/media/"))
            assertTrue(video.path.startsWith("Movies/Keepfin/"))
            context.contentResolver.query(video.uri, arrayOf(MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
                MediaStore.Video.Media.DURATION, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("Keepfin", it.getString(0))
                assertEquals("video/mp4", it.getString(1))
                assertTrue("Gallery needs an indexed width", it.getInt(2) > 0)
                assertTrue("Gallery needs an indexed height", it.getInt(3) > 0)
                assertTrue("Gallery needs an indexed duration", it.getLong(4) > 0)
                assertEquals(0, it.getInt(5))
                assertEquals("Movies/Keepfin/", it.getString(6))
            }
            val thumbnail = context.contentResolver.loadThumbnail(video.uri, Size(320, 180), null)
            assertTrue(thumbnail.width > 0 && thumbnail.height > 0)
            thumbnail.recycle()
            val local = LocalMediaPreview.load(context, null, main)
            assertNotNull("An offline local frame must exist without a provider image", local)
            assertTrue(local!!.width in 1..320 && local.height in 1..320)
            local.recycle()
        } finally { publisher.discard(saved.values); main.delete() }
    }

    @Test fun unsupportedLocalFilesFallBackWithoutCrashing() {
        val missing = File(context.filesDir, "missing-${System.nanoTime()}.mp4")
        assertNull(LocalMediaPreview.load(context, Uri.fromFile(missing), missing))
    }

    @Test fun oldDownloadsVideoMovesIntoGalleryWithoutDuplicatingItsBytes() {
        val publisher = PublicDownloads(context)
        val main = videoFixture("Keepfin-migration-test-${System.nanoTime()}")
        val resolver = context.contentResolver
        val oldUri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, main.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Keepfin/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        var saved = PublicDownloads.Saved(oldUri, "Download/Keepfin/${main.name}")
        try {
            resolver.openOutputStream(oldUri)!!.use { output -> main.inputStream().use { it.copyTo(output) } }
            resolver.update(oldUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            saved = publisher.repairGallery(saved, main)
            assertTrue(saved.path.startsWith("Movies/Keepfin/"))
            assertEquals("Relocation should move the original row", oldUri.lastPathSegment, saved.uri.lastPathSegment)
            assertTrue(saved.uri.toString().contains("/video/media/"))
            assertEquals(listOf(main.name), names(main.nameWithoutExtension))
            assertArrayEquals(main.readBytes(), resolver.openInputStream(saved.uri)!!.use { it.readBytes() })
            val thumbnail = resolver.loadThumbnail(saved.uri, Size(320, 180), null)
            assertTrue(thumbnail.width > 0 && thumbnail.height > 0)
            thumbnail.recycle()
            assertEquals(saved, publisher.repairGallery(saved, main))
            assertEquals("A stale pre-move Downloads URI must recover after a restart", saved,
                publisher.repairGallery(PublicDownloads.Saved(oldUri, "Download/Keepfin/${main.name}"), main))
        } finally { publisher.discard(listOf(saved)); main.delete() }
    }

    @Test fun galleryRepairDoesNotMoveFilesTheUserAlreadyOrganized() {
        val publisher = PublicDownloads(context)
        val main = videoFixture("Keepfin-user-folder-test-${System.nanoTime()}")
        val saved = publisher.publish(listOf(main)).getValue(main)
        try {
            context.contentResolver.update(saved.uri, ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Keepfin-test-custom/")
            }, null, null)
            try { publisher.repairGallery(saved, main); fail("A user-organized file must stay in its chosen folder") }
            catch (_: IllegalArgumentException) { }
            context.contentResolver.query(saved.uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("Movies/Keepfin-test-custom/", it.getString(0))
            }
        } finally { publisher.discard(listOf(saved)); main.delete() }
    }

    private fun videoFixture(stem: String) = File(context.filesDir, "$stem.mp4").apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { input ->
            outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun names(stem: String): List<String> = listOf(
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    ).flatMap { uri -> context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("$stem%"), null
    )!!.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } } }.distinct()
}
