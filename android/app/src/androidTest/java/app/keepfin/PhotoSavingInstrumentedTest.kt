package app.keepfin

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Photo posts on a device (issue #14), with no network: "Save again" saves only the files that did not reach storage, photos go under their
 * space's folder in Pictures, and a post that is already saved is not downloaded again. Run on the emulator only.
 */
@RunWith(AndroidJUnit4::class)
class PhotoSavingInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun withIsolatedContext(action: (Context) -> Unit) {
        val root = File(context.filesDir, "test-photos-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getExternalFilesDir(type: String?) = File(root, "external/${type ?: "root"}").apply { mkdirs() }
        }
        try { action(isolated) } finally { root.deleteRecursively() }
    }

    private fun privateFiles(isolated: Context, id: String, count: Int): List<File> {
        val folder = File(isolated.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Keepfin/photos-$id").apply { mkdirs() }
        return (1..count).map { n ->
            File(folder, "$n.jpg").also { file ->
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(n * 30, 90, 160)) }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
            }
        }
    }

    private fun photoRecord(id: String, title: String, files: List<File>, status: JobStatus = JobStatus.COMPLETED, space: String? = null, url: String = "https://www.instagram.com/p/$id/") =
        JobRecord(
            testJob(status, id).copy(
                url = url, title = title, kind = MediaKind.PHOTOS, label = "${files.size} photos", file = files.first().absolutePath, percent = 100f, space = space,
                completedAt = System.currentTimeMillis(),
            ),
            FormatRequest.Gallery(files.size), DownloadOptions(),
        )

    /** The MediaStore rows (id -> relative path) whose name starts with [stem]. */
    private fun saved(stem: String): Map<Long, String> = buildMap {
        context.contentResolver.query(images, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH), "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("$stem%"), null)?.use {
            while (it.moveToNext()) put(it.getLong(0), it.getString(1))
        }
    }

    private fun clean(stem: String) = saved(stem).keys.forEach { context.contentResolver.delete(android.content.ContentUris.withAppendedId(images, it), null, null) }

    private fun engineFor(isolated: Context, vararg records: JobRecord): MediaEngine {
        File(isolated.filesDir, "queue.json").writeText(JobCodec.encodeFile(records.toList()))
        return MediaEngine(isolated) // the isolated files folder has no profile, so there is no Keepfin folder library
    }

    @Test fun saveAgainSavesOnlyTheFilesThatDidNotReachStorage() = withIsolatedContext { isolated ->
        val id = "photo-save-${UUID.randomUUID()}"
        val title = "PhotoSavingTest ${UUID.randomUUID()}"
        val files = privateFiles(isolated, id, 6)
        val publisher = PublicDownloads(context)
        val done = files.take(4).mapIndexed { i, file -> file.name to publisher.publish(listOf(file), "$title (${i + 1})", null, file.nameWithoutExtension, photos = true).getValue(file) }
        val partial = photoRecord(id, title, files).let {
            it.copy(
                job = it.job.copy(publicUri = done.first().second.uri.toString(), publicPath = done.first().second.path, label = "4 of 6 photos", saveError = "Saved 4 of 6 files. The others are safe in the app: storage unavailable."),
                publicFiles = done.map { (_, saved) -> saved.uri.toString() }, photoSaves = done.associate { (name, saved) -> name to saved.uri.toString() },
            )
        }
        val engine = engineFor(isolated, partial)
        try {
            assertEquals(4, saved(title).size)
            engine.retryPublicSave(id)
            val job = engine.jobs.value.first { it.id == id }
            assertNull("saved without a problem: ${job.saveError}", job.saveError)
            assertEquals("6 photos", job.label)
            assertEquals("the first copy stays the row's file", done.first().second.uri.toString(), job.publicUri)
            val record = engine.record(id)!!
            assertEquals(files.map { it.name }.toSet(), record.photoSaves.keys)
            assertEquals(6, record.publicFiles.size)
            assertEquals("exactly the two missing files were saved", 6, saved(title).size)
            assertTrue(saved(title).values.all { it == "Pictures/Keepfin/" })
            // nothing left to save: another Save again changes nothing
            engine.retryPublicSave(id)
            assertEquals(6, saved(title).size)
        } finally { engine.shutdown(); clean(title) }
    }

    @Test fun photosGoUnderTheirSpaceFolderInPictures() = withIsolatedContext { isolated ->
        val id = "photo-space-${UUID.randomUUID()}"
        val title = "PhotoSpaceTest ${UUID.randomUUID()}"
        val files = privateFiles(isolated, id, 2)
        val engine = engineFor(isolated, photoRecord(id, title, files, space = "videos").let { it.copy(job = it.job.copy(publicUri = null, publicPath = null)) })
        try {
            engine.updateSettings { it.copy(library = it.library.copy(enabled = true)) }
            engine.retryPublicSave(id)
            val job = engine.jobs.value.first { it.id == id }
            assertNull(job.saveError)
            val folders = saved(title).values.toSet()
            val folder = engine.settings.value.library.spaces.first { it.id == "videos" }.folder
            assertEquals(setOf("Pictures/$folder/"), folders)
        } finally { engine.shutdown(); clean(title) }
    }

    @Test fun photosWithoutASpaceGoToPicturesKeepfin() = withIsolatedContext { isolated ->
        val id = "photo-nospace-${UUID.randomUUID()}"
        val title = "PhotoNoSpaceTest ${UUID.randomUUID()}"
        val engine = engineFor(isolated, photoRecord(id, title, privateFiles(isolated, id, 1)))
        try {
            engine.updateSettings { it.copy(library = it.library.copy(enabled = false)) }
            engine.retryPublicSave(id)
            assertEquals(setOf("Pictures/Keepfin/"), saved(title).values.toSet())
        } finally { engine.shutdown(); clean(title) }
    }

    @Test fun aPostThatIsAlreadySavedIsNotDownloadedAgain() = withIsolatedContext { isolated ->
        val earlierId = "photo-earlier-${UUID.randomUUID()}"
        val title = "PhotoExistingTest ${UUID.randomUUID()}"
        val link = "https://www.instagram.com/p/$earlierId/"
        val files = privateFiles(isolated, earlierId, 2)
        val publisher = PublicDownloads(context)
        val copies = files.mapIndexed { i, file -> file.name to publisher.publish(listOf(file), "$title (${i + 1})", null, file.nameWithoutExtension, photos = true).getValue(file) }
        val earlier = photoRecord(earlierId, title, files, url = link).let {
            it.copy(job = it.job.copy(publicUri = copies.first().second.uri.toString(), publicPath = copies.first().second.path), photoSaves = copies.associate { (n, s) -> n to s.uri.toString() })
        }
        val engine = engineFor(isolated, earlier)
        try {
            val post = (app.keepfin.core.gallery.Gallery.read(
                "[[2,{\"category\":\"instagram\",\"title\":\"Trip\"}],[3,\"https://cdn.example/1.jpg\",{\"extension\":\"jpg\",\"count\":2}],[3,\"https://cdn.example/2.jpg\",{\"extension\":\"jpg\",\"count\":2}]]", link,
            ) as app.keepfin.core.gallery.GalleryRead.Post)
            val before = engine.jobs.value.map { it.id }.toSet()
            engine.enqueue(PhotoPosts.media(post, link), FormatRequest.Gallery(2), DownloadOptions())
            val repeat = engine.await(engine.newestJobId(before), timeoutMs = 20_000)
            assertEquals(JobStatus.COMPLETED, repeat.status)
            val existing = repeat.existing ?: error("already there: $repeat")
            assertEquals("source", existing.by)
            assertEquals(copies.first().second.uri.toString(), existing.uri)
            assertEquals("nothing was saved a second time", 2, saved(title).size)

            // a copy removed from the phone: the post is no longer there
            context.contentResolver.delete(copies[1].second.uri, null, null)
            assertFalse(PhotoPosts.earlier(engine.allRecords(), "new", engine.publicDownloads::exists).any { it.second.complete })
        } finally { engine.shutdown(); clean(title) }
    }

}
