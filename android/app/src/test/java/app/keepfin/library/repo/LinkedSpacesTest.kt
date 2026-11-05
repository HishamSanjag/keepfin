package app.keepfin.library.repo

import app.keepfin.allItems
import android.content.IntentSender
import app.keepfin.CollectionKind
import app.keepfin.EngineSettings
import app.keepfin.FollowMode
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.LibraryAccess
import app.keepfin.LibraryHost
import app.keepfin.MediaKind
import app.keepfin.Space
import app.keepfin.SpaceKind
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibSpace
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.StoreOptions
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** An IntentSender cannot be built on a plain JVM; the tests only pass it along, so an instance without a constructor will do. */
private fun fakeSender(): IntentSender {
    val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").also { it.isAccessible = true }
    val unsafe = field.get(null)
    return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, IntentSender::class.java) as IntentSender
}

/** Linked spaces (setup "Leave"): files that stay in MediaStore play, rename, move and delete there, and are found again after a reinstall. */
class LinkedSpacesTest {
    private class ConsentAsked(val uris: List<String>, val delete: Boolean)


    private class FakeMedia : LinkedMedia {
        val rows = LinkedHashMap<String, LinkedLocator>()
        var readable = true
        val unwritable = HashSet<String>()
        val asked = ArrayList<ConsentAsked>()
        override fun stat(uri: String) = rows[uri]
        override fun canRead() = readable
        override fun existing(uris: Collection<String>): Set<String>? = if (!readable) null else uris.filter { it in rows }.toSet()
        override fun find(keys: Collection<String>): Map<String, LinkedLocator> {
            val wanted = keys.map { it.lowercase() }.toSet()
            return rows.values.associateBy { it.path.lowercase() }.filterKeys { it in wanted }
        }
        override fun withoutWriteAccess(uris: List<String>) = uris.filter { it in unwritable }
        override fun consentRequest(uris: List<String>, delete: Boolean): IntentSender { asked += ConsentAsked(uris, delete); return fakeSender() }
        override fun recoverable(error: SecurityException): IntentSender? = null
        override fun rename(uri: String, newName: String) {
            val l = rows.getValue(uri)
            rows[uri] = l.copy(path = l.path.substringBeforeLast('/', "").let { if (it.isEmpty()) newName else "$it/$newName" })
        }
        override fun move(uri: String, relativePath: String) {
            val l = rows.getValue(uri)
            rows[uri] = l.copy(path = relativePath + l.path.substringAfterLast('/'))
        }
        override fun delete(uri: String) = rows.remove(uri) != null
    }

    private class Host : LibraryHost {
        override val jobs = MutableStateFlow(emptyList<Job>())
        override val settings = MutableStateFlow(EngineSettings())
        override fun updateSettings(transform: (EngineSettings) -> EngineSettings) { settings.value = transform(settings.value) }
        override fun jobThumbnail(jobId: String) = null
        override fun sidecarUris() = emptySet<String>()
        override fun repointFile(uri: String, publicPath: String?) {}
        override fun requestCheck(id: String?, review: Boolean) {}
        override fun followSettingsChanged() {}
    }

    private val host = Host()
    private val media = FakeMedia()
    private val folder = FakeFolderAccess()
    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var store: KeepfinStore
    private lateinit var repo: LibraryRepository
    private var ids = 0
    private var clock = 1_700_000_000_000L

    private fun uuid() = "00000000-0000-4000-8000-%012d".format(++ids)

    @Before fun setUp() {
        root = Files.createTempDirectory("kf-linked").toFile()
        dir = File(root, "dev")
        val state = testState(dir, "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1")
        store = KeepfinStore.open(
            StoreOptions(folder = folder, libraryId = "11111111-1111-4111-8111-111111111111", actor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", deviceDir = dir, outboxDir = File(dir, "outbox"),
                newId = { uuid() }, now = { clock++ }, flushIdleMs = 3_600_000, checkpointDelayMs = 5, retryMs = 10, state = state),
        ).get(10, TimeUnit.SECONDS)
        repo = LibraryRepository(host, { RepositoryBackend(store, folder, "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1", state, { app.keepfin.folder.sql.JdbcHost().open(it) }) { "doc:$it" } }, dir, newId = { uuid() }, now = { clock++ }, linked = media, sdk = 30)
        repo.start(); repo.awaitReady()
        // a linked Music space (left where it is) and a linked Videos space, plus the regular Downloads space
        val spaces = listOf(
            Space("downloads", "Downloads", SpaceKind.MIXED, "Downloads").toLib(),
            Space("lm", "Music", SpaceKind.MUSIC, "Keepfin Music").toLib().copy(linked = true),
            Space("lr", "Rock", SpaceKind.MUSIC, "Keepfin Rock").toLib().copy(linked = true),
        )
        repo.migrateLayout(spaces, LibRoutes("lm", "downloads"))
    }

    @After fun tearDown() { runCatching { repo.shutdown() }; store.abandon(); root.deleteRecursively() }

    private val uri1 = "content://media/external/audio/media/11"
    private val uri2 = "content://media/external/audio/media/12"

    private fun row(uri: String, path: String, size: Long = 1000, mtime: Long = 50, owned: Boolean = true) = LinkedLocator(uri, path, size, mtime, owned).also { media.rows[uri] = it }

    /** A migrated linked item; [locator] null leaves it without an address on this device (a reinstall). */
    private fun link(title: String, place: String, locator: LinkedLocator?, space: String = "lm"): String {
        val id = uuid()
        repo.migrateItems(listOf(LibraryRepository.MigratedItem(LibItem(id = id, space = space, kind = "audio", title = title, place = place, addedAt = 5.0), null, locator?.size ?: 0, locator)))
        return id
    }

    private fun shown(id: String) = repo.allItems().firstOrNull { it.id == id }

    @Test fun aLinkedItemPlaysFromItsMediaStoreAddress() {
        val id = link("One", "linked:lm/Rock/one.mp3", row(uri1, "Music/Keepfin Music/Rock/one.mp3", size = 4321))
        val item = shown(id)!!
        assertEquals(uri1, item.uri)
        assertEquals("Rock", item.folder)
        assertEquals(4321L, item.size)
        assertTrue(repo.library.value.problems.isEmpty())
    }

    @Test fun addressesSurviveARestartOfTheRepositoryFiles() {
        link("One", "linked:lm/one.mp3", row(uri1, "Music/Keepfin Music/one.mp3"))
        val again = LinkedStore(File(dir, "linked.json"))
        assertEquals(uri1, again.all().values.single().uri)
        assertTrue(again.all().values.single().owned)
    }

    @Test fun renameChangesTheMediaStoreNameAndKeepsTheItem() {
        val id = link("One", "linked:lm/Rock/one.mp3", row(uri1, "Music/Keepfin Music/Rock/one.mp3"))
        assertTrue(repo.rename(id, "Uno") is LibraryAccess.Done)
        assertEquals("Music/Keepfin Music/Rock/Uno.mp3", media.rows.getValue(uri1).path)
        assertEquals(uri1, shown(id)!!.uri)
        assertEquals("linked:lm/Rock/Uno.mp3", repo.modelNow().items.single { it.id == id }.place)
    }

    @Test fun renamingAFileFromBeforeTheReinstallAsksFirstAndChangesNothing() {
        val id = link("One", "linked:lm/one.mp3", row(uri1, "Music/Keepfin Music/one.mp3", owned = false))
        media.unwritable += uri1
        val answer = repo.rename(id, "Uno")
        assertTrue(answer is LibraryAccess.NeedsConfirmation)
        assertEquals(listOf(id), (answer as LibraryAccess.NeedsConfirmation).ids)
        assertEquals(listOf(uri1), media.asked.single().uris)
        assertFalse(media.asked.single().delete)
        assertEquals("Music/Keepfin Music/one.mp3", media.rows.getValue(uri1).path)
        // after the yes the call runs again and goes through
        media.unwritable.clear()
        assertTrue(repo.rename(id, "Uno") is LibraryAccess.Done)
        assertEquals("Music/Keepfin Music/Uno.mp3", media.rows.getValue(uri1).path)
    }

    @Test fun moveBetweenLinkedSpacesMovesTheMediaStoreRowAndTheItem() {
        val id = link("One", "linked:lm/Sub/one.mp3", row(uri1, "Music/Keepfin Music/Sub/one.mp3"))
        assertTrue(repo.move(listOf(id), "lr") is LibraryAccess.Done)
        assertEquals("Music/Keepfin Rock/Sub/one.mp3", media.rows.getValue(uri1).path)
        val item = repo.modelNow().items.single { it.id == id }
        assertEquals("lr", item.space)
        assertEquals("linked:lr/Sub/one.mp3", item.place)
    }

    @Test fun aLinkedFileDoesNotSilentlyLeaveForTheKeepfinFolder() {
        val id = link("One", "linked:lm/one.mp3", row(uri1, "Music/Keepfin Music/one.mp3"))
        val failure = try { repo.move(listOf(id), "downloads"); null } catch (e: IllegalStateException) { e.message }
        assertNotNull(failure)
        assertEquals("Music/Keepfin Music/one.mp3", media.rows.getValue(uri1).path)
        assertEquals("lm", repo.modelNow().items.single { it.id == id }.space)
    }

    @Test fun deletingLinkedFilesRemovesOwnedOnesNowAndAsksOnceForTheOthers() {
        val owned = link("Mine", "linked:lm/mine.mp3", row(uri1, "Music/Keepfin Music/mine.mp3"))
        val foreign = link("Old", "linked:lm/old.mp3", row(uri2, "Music/Keepfin Music/old.mp3", owned = false))
        media.unwritable += uri2
        val answer = repo.deleteItems(listOf(owned, foreign))
        assertEquals(listOf(foreign), (answer as LibraryAccess.NeedsConfirmation).ids)
        assertEquals(listOf(uri2), media.asked.single().uris)
        assertTrue(media.asked.single().delete)
        // the owned file is gone and out of the library; the other is untouched until the user says yes
        assertFalse(uri1 in media.rows)
        assertNull(shown(owned))
        assertNotNull(shown(foreign))
        assertTrue(uri2 in media.rows)
        // the user said yes: Android removed the file, and the item leaves the library
        media.rows.remove(uri2)
        repo.confirmationResult(listOf(foreign), true)
        repo.awaitReady()
        assertNull(shown(foreign))
        assertTrue(LinkedStore(File(dir, "linked.json")).all().isEmpty())
    }

    @Test fun aRefusedDeleteKeepsTheFileAndTheItem() {
        val foreign = link("Old", "linked:lm/old.mp3", row(uri2, "Music/Keepfin Music/old.mp3", owned = false))
        repo.confirmationResult(listOf(foreign), false)
        assertNotNull(shown(foreign))
        assertTrue(uri2 in media.rows)
    }

    @Test fun aFileThatDisappearedShowsAsMissingAndStaysInTheLibrary() {
        val id = link("One", "linked:lm/one.mp3", row(uri1, "Music/Keepfin Music/one.mp3"))
        media.rows.remove(uri1)
        repo.checkLinked()
        assertNotNull(shown(id))
        assertTrue(repo.library.value.problems.any { it.contains("not on this phone") })
        // when nothing can be told (no media permission) nothing is marked
        media.readable = false
        repo.checkLinked()
        assertTrue(repo.library.value.problems.any { it.contains("not on this phone") })
        // the file comes back: the mark goes away
        media.readable = true
        row(uri1, "Music/Keepfin Music/one.mp3")
        repo.checkLinked()
        assertTrue(repo.library.value.problems.isEmpty())
    }

    @Test fun deletingAMissingLinkedItemJustDropsIt() {
        val id = link("One", "linked:lm/one.mp3", row(uri1, "Music/Keepfin Music/one.mp3"))
        media.rows.remove(uri1)
        assertTrue(repo.deleteItems(listOf(id)) is LibraryAccess.Done)
        assertNull(shown(id))
    }

    @Test fun afterAReinstallTheItemsWaitUntilTheFilesAreFoundAgain() {
        val id = link("One", "linked:lm/Rock/one.mp3", null)
        val lost = link("Two", "linked:lm/two.mp3", null)
        // this phone has one of the two files at the same relative path (a different content id after the reinstall)
        row("content://media/external/audio/media/99", "Music/Keepfin Music/Rock/one.mp3", size = 777, owned = false)
        assertNull(shown(id))
        assertEquals(2, repo.linkedNeedingRelink())
        assertTrue(repo.library.value.problems.any { it.contains("Find linked files") })
        val report = repo.relinkLinked()
        assertEquals(1, report.matched)
        assertEquals(1, report.unmatched)
        val item = shown(id)!!
        assertEquals("content://media/external/audio/media/99", item.uri)
        assertEquals(777L, item.size)
        assertNull(shown(lost))
        assertEquals(1, repo.linkedNeedingRelink())
        // the found file is not owned by this install: changing it asks first
        media.unwritable += "content://media/external/audio/media/99"
        assertTrue(repo.rename(id, "Uno") is LibraryAccess.NeedsConfirmation)
    }

    @Test fun aKnownSizeAndTimeMustMatchWhenRelinking() {
        val id = link("One", "linked:lm/one.mp3", row(uri1, "Music/Keepfin Music/one.mp3", size = 500, mtime = 40))
        // the row vanished and a different file (other size) now sits at the same path: not the same file
        media.rows.remove(uri1)
        row("content://media/external/audio/media/77", "Music/Keepfin Music/one.mp3", size = 501, mtime = 40)
        repo.checkLinked()
        val none = repo.relinkLinked()
        assertEquals(0, none.matched)
        assertEquals(1, none.unmatched)
        // the same file again under a new content id
        media.rows.clear()
        row("content://media/external/audio/media/78", "Music/Keepfin Music/one.mp3", size = 500, mtime = 40)
        val found = repo.relinkLinked()
        assertEquals(1, found.matched)
        assertEquals("content://media/external/audio/media/78", shown(id)!!.uri)
        assertTrue(repo.library.value.problems.isEmpty())
    }

    // ---- what the follow check reads and writes in folder mode (FollowEngine)

    private fun job(id: String, collection: String, index: Int) = Job(
        id = id, url = "https://www.youtube.com/watch?v=${id.padEnd(11, 'x')}", title = "Song $id", thumbnail = null, uploader = "Band", duration = 120.0, provider = null, label = "",
        kind = MediaKind.AUDIO, status = JobStatus.COMPLETED, percent = 100f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null,
        phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 0, completedAt = null, attempt = 1, group = null,
        collectionId = collection, collectionIndex = index, space = "downloads",
    )

    @Test fun followChecksWorkOnTheFolderLibrary() {
        val url = "https://www.youtube.com/playlist?list=PLabc"
        val id = repo.collectionIdFor(url, null)
        repo.ensureCollection(url, "Road trip", CollectionKind.PLAYLIST, "downloads", listOf("youtube:a"), false, id)
        // the check updates the follow state and the seen keys, and its results stay on this device
        val changed = repo.mutateCollection(id) { it.copy(seen = it.seen + "youtube:b", follow = (it.follow ?: app.keepfin.Follow()).copy(mode = FollowMode.AUTO, lastCheckedAt = 123L, lastError = "later")) }!!
        assertEquals(setOf("youtube:a", "youtube:b"), changed.seen.toSet())
        assertEquals(123L, changed.follow!!.lastCheckedAt)
        assertEquals("later", repo.collection(id)!!.follow!!.lastError)
        assertEquals(FollowMode.AUTO, repo.collectionsNow().single().follow!!.mode)
        assertNull(repo.mutateCollection("00000000-0000-4000-8000-999999999999") { it })
        // a download queued by a check shows up as new; a manual one does not
        repo.markFollowJobs(listOf("a"))
        val a = File(root, "a.mp3").also { it.writeBytes(ByteArray(40) { 1 }) }
        val b = File(root, "b.mp3").also { it.writeBytes(ByteArray(41) { 2 }) }
        host.jobs.value = listOf(job("a", id, 1), job("b", id, 2))
        val savedA = repo.saveDownload(job("a", id, 1), null, "downloads", "", "Song a", a, emptyList())
        repo.saveDownload(job("b", id, 2), null, "downloads", "", "Song b", b, emptyList())
        assertEquals(listOf(savedA.itemId), repo.collection(id)!!.newItemIds)
        // items answer to their source keys, so removed-upstream detection works
        assertTrue("youtube:axxxxxxxxxx" in repo.sourceKeysOf(savedA.itemId))
        assertTrue(repo.sourceKeysOf("unknown").isEmpty())
    }
}
