package app.keepfin.library.repo

import app.keepfin.allItems
import app.keepfin.EngineSettings
import app.keepfin.FollowMode
import app.keepfin.FollowUpdate
import app.keepfin.Job
import app.keepfin.TrashEntry
import app.keepfin.LibraryHost
import app.keepfin.Space
import app.keepfin.SpaceKind
import app.keepfin.TagEdit
import app.keepfin.core.library.LibItem
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JNull
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Op
import app.keepfin.core.sync.SegmentResult
import app.keepfin.core.sync.decodeSegment
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.StoreOptions
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
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

class LibraryRepositoryTest {
    private val library = "11111111-1111-4111-8111-111111111111"
    private val actorA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val actorB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val replicaA = "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1"
    private val replicaB = "b2b2b2b2-b2b2-4b2b-8b2b-b2b2b2b2b2b2"

    private class FakeHost : LibraryHost {
        override val jobs = MutableStateFlow(emptyList<Job>())
        override val settings = MutableStateFlow(EngineSettings())
        val checks = ArrayList<Pair<String?, Boolean>>()
        var followChanges = 0
        override fun updateSettings(transform: (EngineSettings) -> EngineSettings) { settings.value = transform(settings.value) }
        override fun jobThumbnail(jobId: String) = null
        override fun sidecarUris() = emptySet<String>()
        override fun repointFile(uri: String, publicPath: String?) {}
        override fun requestCheck(id: String?, review: Boolean) { checks += id to review }
        override fun followSettingsChanged() { followChanges++ }
    }

    private class Picked(val files: Map<String, Pair<String, ByteArray>>) : ImportSource {
        override fun open(uri: String): InputStream = ByteArrayInputStream(files.getValue(uri).second)
        override fun displayName(uri: String) = files.getValue(uri).first
        override fun mimeType(uri: String): String? = null
    }

    private class Replica(val actor: String, val replica: String, val dir: File, val host: FakeHost, val store: KeepfinStore, val repo: LibraryRepository)

    private lateinit var root: File
    private lateinit var folder: FakeFolderAccess
    private val replicas = ArrayList<Replica>()
    private var ids = 0
    private var clock = 1_700_000_000_000L

    @Before fun setUp() {
        root = Files.createTempDirectory("keepfin-repo").toFile()
        folder = FakeFolderAccess()
    }

    @After fun tearDown() {
        for (r in replicas) {
            runCatching { r.repo.shutdown() }
            r.store.abandon()
        }
        root.deleteRecursively()
    }

    private fun nextId() = "00000000-0000-4000-8000-%012d".format(++ids)

    private fun replica(actor: String = actorA, id: String = replicaA, picked: ImportSource? = null, name: String = "device-$actor"): Replica {
        val dir = File(root, name)
        val state = testState(dir, id, library)
        val store = KeepfinStore.open(
            StoreOptions(
                folder = folder, libraryId = library, actor = actor, deviceDir = dir, outboxDir = File(dir, "outbox"), newId = ::nextId,
                now = { clock++ }, flushIdleMs = 3_600_000, checkpointDelayMs = 5, retryMs = 10, state = state,
            ),
        ).get(10, TimeUnit.SECONDS)
        val host = FakeHost()
        val repo = LibraryRepository(host, { RepositoryBackend(store, folder, id, state, { app.keepfin.folder.sql.JdbcHost().open(it) }) }, dir, picked, newId = ::nextId, now = { clock++ })
        repo.start()
        repo.awaitReady()
        return Replica(actor, id, dir, host, store, repo).also { replicas += it }
    }

    private fun Replica.sync() {
        store.rescan(true).get(10, TimeUnit.SECONDS)
        repo.rescan()
        repo.awaitReady()
    }

    private fun Replica.ops(): List<Op> {
        store.flush().get(10, TimeUnit.SECONDS)
        return folder.segmentPaths().flatMap { p -> (decodeSegment(folder.files.getValue(p), library) as SegmentResult.Ok).ops }
            .filter { it.id.actor == actor }.sortedBy { it.id.seq }
    }

    /** The operations a block of calls wrote. */
    private fun Replica.written(block: () -> Unit): List<Op> {
        val before = ops().size
        block()
        return ops().drop(before)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun Replica.addSong(title: String, bytes: ByteArray = title.toByteArray(), space: String = "music", artist: String? = null): String {
        val name = "$title.mp3"
        return repo.addFile(space, "", name, ByteArrayInputStream(bytes), LibItem(id = "", space = space, kind = "audio", title = title, artist = artist, addedAt = (clock++).toDouble())).itemId
    }

    private fun Op.field(name: String) = (body[name] as? JStr)?.value
    private fun List<Op>.kinds() = map { it.kind }

    /** Without the `updatedAt` touch every collection change also writes. */
    private fun List<Op>.untouched() = filterNot { it.kind == "playlist.set" && it.field("field") == "updatedAt" }

    // ------------------------------------------------------------------------------------------ tests

    @Test fun startsWithTheDefaultSpacesAndMirrorsThemIntoTheSettings() {
        val a = replica()
        val spaces = a.host.settings.value.library.spaces
        // Favorites is a kept space, as on Windows and the phone's own library: a new library has it
        assertEquals(listOf("downloads", "music", "videos", "favorites"), spaces.map { it.id })
        assertEquals(listOf("Downloads", "Music", "Videos", ""), spaces.map { it.folder })
        assertEquals("music", a.host.settings.value.library.musicRoute)
        assertTrue(a.repo.allItems().isEmpty())
        val ops = a.ops()
        assertEquals(listOf("space.add", "space.add", "space.add", "space.add", "profile.set"), ops.kinds())
    }

    @Test fun addingAFileWritesTheItemItsPlacementAndItsRevision() {
        val a = replica()
        val bytes = "song one bytes".toByteArray()
        var id = ""
        val ops = a.written { id = a.addSong("Song One", bytes, artist = "Artist") }
        assertEquals(listOf("item.add", "item.place", "item.revise"), ops.kinds())
        assertEquals("Music/Song One.mp3", ops[1].field("path"))
        assertEquals(replicaA, ops[1].field("place"))
        assertEquals("sha256:${sha(bytes)}", ops[2].field("content"))
        assertEquals(bytes.size.toLong(), (ops[2].body["size"] as JNum).value)
        assertTrue(folder.files.containsKey("Music/Song One.mp3"))
        val item = a.repo.allItems().single()
        assertEquals(id, item.id)
        assertEquals("Artist", item.artist)
        assertEquals("keepfin-folder:Music/Song One.mp3", item.uri)
        assertEquals(bytes.size.toLong(), item.size)
    }

    @Test fun everyMutationBecomesTheExpectedOperations() {
        val a = replica()
        val one = a.addSong("One")
        val two = a.addSong("Two")
        val three = a.addSong("Three")

        assertEquals(listOf("set.add"), a.written { a.repo.setFavorite(one, true) }.kinds())
        assertTrue(a.repo.allItems().first { it.id == one }.favorite)
        val unfavourite = a.written { a.repo.setFavorite(one, false) }
        assertEquals(listOf("set.remove"), unfavourite.kinds())
        assertTrue((unfavourite[0].body["observedTags"] as JArr).items.isNotEmpty())

        val tagged = a.written { a.repo.libraryTag(listOf(one, two), add = listOf("rock"), remove = emptyList()) }
        assertEquals(listOf("set.add", "set.add"), tagged.kinds())
        assertEquals("tags:$one", tagged[0].field("set"))
        assertEquals(listOf("set.remove"), a.written { a.repo.libraryTag(listOf(one), remove = listOf("rock")) }.kinds())

        val played = a.written { a.repo.recordPlayback(two, 40.0, false) }
        assertTrue(played.any { it.kind == "item.set" && it.field("field") == "plays@$actorA" && (it.body["value"] as JNum).value == 1L })
        assertTrue(played.any { it.kind == "item.set" && it.field("field") == "lastPlayedAt" })
        assertEquals(1, a.repo.allItems().first { it.id == two }.plays)
        // finishing a play that was already counted at 30 seconds does not count it twice
        a.repo.recordPlayback(two, null, true)
        assertEquals(1, a.repo.allItems().first { it.id == two }.plays)

        val edited = a.written { a.repo.editInfo(three, TagEdit(title = "Three!", artist = "")) }
        assertEquals(listOf("item.set"), edited.kinds())
        assertEquals("title", edited[0].field("field"))

        // rename: the file and its placement
        val renamed = a.written { a.repo.rename(one, "Uno") }
        assertEquals(listOf("item.place"), renamed.kinds())
        assertEquals("Music/Uno.mp3", renamed[0].field("path"))
        assertTrue(folder.files.containsKey("Music/Uno.mp3"))
        assertFalse(folder.files.containsKey("Music/One.mp3"))
        assertEquals("keepfin-folder:Music/Uno.mp3", a.repo.allItems().first { it.id == one }.uri)

        // move into another space
        val moved = a.written { a.repo.move(listOf(two), "videos") }
        assertEquals(setOf("item.set", "item.place"), moved.kinds().toSet())
        assertEquals("Videos/Two.mp3", moved.first { it.kind == "item.place" }.field("path"))
        assertTrue(folder.files.containsKey("Videos/Two.mp3"))
        assertFalse(folder.files.containsKey("Music/Two.mp3"))
        assertEquals("videos", a.repo.allItems().first { it.id == two }.space)

        // delete goes to .keepfin/trash
        val deleted = a.written { a.repo.deleteItems(listOf(three)) }
        assertEquals(listOf("item.delete"), deleted.kinds())
        assertTrue((deleted[0].body["observedTags"] as JArr).items.isNotEmpty())
        assertTrue((deleted[0].body["observedHeads"] as JArr).items.isNotEmpty())
        assertTrue(folder.files.containsKey(".keepfin/trash/$three/Three.mp3"))
        assertFalse(folder.files.containsKey("Music/Three.mp3"))
        assertNull(a.repo.item(three))
    }

    @Test fun playlistsAndCollectionsBecomeEntriesAndSets() {
        val a = replica()
        val one = a.addSong("One")
        val two = a.addSong("Two")
        val three = a.addSong("Three")

        var list = ""
        val created = a.written { list = a.repo.collectionSave(null, "Road trip", listOf(one, two)) }
        assertEquals(listOf("playlist.add", "entry.insert", "entry.insert"), created.kinds())
        assertEquals(listOf(one, two), a.repo.collection(list)!!.itemIds)

        assertEquals(listOf("entry.insert"), a.written { a.repo.collectionAdd(list, listOf(three, one)) }.untouched().kinds())
        assertEquals(listOf(one, two, three), a.repo.collection(list)!!.itemIds)

        val moved = a.written { a.repo.collectionReorder(list, listOf(three, one, two)) }.untouched()
        assertEquals(listOf("entry.move"), moved.kinds())
        assertEquals(listOf(three, one, two), a.repo.collection(list)!!.itemIds)

        assertEquals(listOf("entry.remove"), a.written { a.repo.collectionRemove(list, listOf(one)) }.untouched().kinds())
        assertEquals(listOf(three, two), a.repo.collection(list)!!.itemIds)

        val renamed = a.written { a.repo.collectionSave(list, "Long trip", listOf(three, two)) }
        assertEquals(listOf("playlist.set", "playlist.set"), renamed.kinds().take(2))
        assertEquals("Long trip", a.repo.collection(list)!!.name)

        val deleted = a.written { a.repo.collectionDelete(list) }
        assertEquals(listOf("playlist.delete"), deleted.kinds())
        assertTrue((deleted[0].body["observedTags"] as JArr).items.isNotEmpty())
        assertNull(a.repo.collection(list))
    }

    @Test fun followAndOpenedWorkOnDownloadedCollections() {
        val a = replica()
        val one = a.addSong("One")
        val col = "00000000-0000-4000-8000-0000000000c1"
        a.store.apply(
            listOf(
                OpDraft("playlist.add", mapOf("playlist" to JStr(col), "fields" to JObj(mapOf("kind" to JStr("collection"), "collectionKind" to JStr("album"), "name" to JStr("Album"), "space" to JStr("6b8e1c10-2d3a-4f55-8a01-000000000002"), "source" to JObj(mapOf("url" to JStr("https://music.example/album?list=OLAK1"), "provider" to JStr("music"))))))),
                OpDraft("entry.insert", mapOf("playlist" to JStr(col), "entry" to JStr("00000000-0000-4000-9000-000000000001"), "item" to JStr(one), "key" to JStr("a0"))),
                OpDraft("set.add", mapOf("set" to JStr("new:$col"), "element" to JStr(one))),
            ),
        ).get(10, TimeUnit.SECONDS)
        a.sync()
        assertEquals(listOf(one), a.repo.collection(col)!!.newItemIds)

        val opened = a.written { a.repo.collectionOpened(col) }
        assertTrue(opened.kinds().contains("set.remove"))
        assertTrue(a.repo.collection(col)!!.newItemIds.isEmpty())

        val followed = a.written { a.repo.collectionFollow(col, FollowUpdate(mode = FollowMode.AUTO, intervalHours = 12, wifiOnly = false)) }.untouched()
        assertEquals(listOf("playlist.set"), followed.kinds())
        assertEquals("follow", followed[0].field("field"))
        val follow = a.repo.collection(col)!!.follow!!
        assertEquals(FollowMode.AUTO, follow.mode)
        assertEquals(12, follow.intervalHours)
        assertFalse(follow.wifiOnly)
        assertEquals(1, a.host.followChanges)

        // follow results are device-only: nothing is written
        val results = a.written { a.repo.recordFollowResult(col, 1234L, "Could not reach the playlist.") }
        assertTrue(results.isEmpty())
        assertEquals(1234L, a.repo.collection(col)!!.follow!!.lastCheckedAt)
        assertEquals("Could not reach the playlist.", a.repo.collection(col)!!.follow!!.lastError)
    }

    @Test fun spacesAreWrittenAndMirrored() {
        val a = replica()
        val added = a.written { a.repo.saveSpace(Space("", "Podcasts", SpaceKind.MUSIC, "Podcasts")) }
        assertEquals(listOf("space.add"), added.kinds())
        val id = a.host.settings.value.library.spaces.first { it.name == "Podcasts" }.id
        assertTrue(id.isNotBlank())

        val reordered = a.written { a.repo.spaceReorder(listOf(id, "downloads", "music", "videos")) }
        assertTrue(reordered.isNotEmpty() && reordered.all { it.kind == "space.set" && it.field("field") == "order" })
        assertEquals(id, a.host.settings.value.library.spaces.first().id)

        val deleted = a.written { a.repo.deleteSpace(id) }
        assertEquals(listOf("space.delete"), deleted.filter { it.kind == "space.delete" }.kinds())
        assertFalse(a.host.settings.value.library.spaces.any { it.id == id })
    }

    @Test fun importsCopyHashAndSkipDuplicates() {
        val bytes = "imported bytes".toByteArray()
        val picked = Picked(mapOf("u1" to ("Imported Song.mp3" to bytes), "u2" to ("Same Again.mp3" to bytes), "u3" to ("Other.mp3" to "other".toByteArray())))
        val a = replica(picked = picked)
        var result: app.keepfin.ImportResult? = null
        val ops = a.written { result = a.repo.importFiles(listOf("u1", "u2", "u3"), "music") }
        assertEquals(2, result!!.added)
        assertEquals(1, result!!.duplicates)
        assertEquals(0, result!!.failed)
        assertEquals(listOf("item.add", "item.place", "item.revise", "item.add", "item.place", "item.revise"), ops.kinds())
        assertFalse(folder.files.containsKey("Music/Same Again.mp3"))
        assertEquals(setOf("Imported Song", "Other"), a.repo.allItems().map { it.title }.toSet())
    }

    @Test fun copyingIntoAnotherSpaceKeepsTheOriginalAndSharesTheContent() {
        val a = replica()
        val bytes = "shared".toByteArray()
        val one = a.addSong("One", bytes)
        val ops = a.written { a.repo.libraryAddFrom(listOf(one), emptyList(), "videos", copy = true) }
        assertEquals(listOf("item.add", "item.place", "item.revise"), ops.kinds())
        assertEquals("sha256:${sha(bytes)}", ops[2].field("content"))
        assertTrue(folder.files.containsKey("Music/One.mp3") && folder.files.containsKey("Videos/One.mp3"))
        assertEquals(2, a.repo.allItems().size)
    }

    @Test fun aRestartReproducesTheSameSnapshot() {
        val a = replica()
        val one = a.addSong("One", artist = "A")
        val two = a.addSong("Two")
        a.repo.setFavorite(one, true)
        a.repo.libraryTag(listOf(two), add = listOf("night"))
        a.repo.recordPlayback(two, 45.0, false)
        val list = a.repo.collectionSave(null, "Mix", listOf(two, one))
        a.repo.rename(two, "Dos")
        a.repo.saveSpace(Space("", "Extra", SpaceKind.MIXED, "Extra"))
        val before = a.repo.library.value
        val beforeItems = a.repo.allItems()
        val spaces = a.host.settings.value.library.spaces
        a.repo.shutdown()
        a.store.abandon()
        replicas.remove(a)

        val again = replica()
        val after = again.repo.library.value
        assertEquals(beforeItems.map { it.id to it.uri }, again.repo.allItems().map { it.id to it.uri })
        assertEquals(beforeItems, again.repo.allItems())
        assertEquals(before.collections, after.collections)
        assertEquals(listOf(two, one), again.repo.collection(list)!!.itemIds)
        assertEquals(spaces, again.host.settings.value.library.spaces)
    }

    // ------------------------------------------------------------------------------------------ Undo of a delete

    @Test fun theTrashOfDeletedFilesIsEmptiedAfterThirtyDaysAndNothingElseIsTouched() {
        val a = replica()
        val day = 24L * 3_600_000L
        val old = "0000000a-0000-4000-8000-000000000001"
        val recent = "0000000a-0000-4000-8000-000000000002"
        val unknown = "0000000a-0000-4000-8000-000000000003"
        for (id in listOf(old, recent, unknown)) folder.files[".keepfin/trash/$id/Song.mp3"] = ByteArray(10)
        folder.files[".keepfin/trash/copies/2026-01-01/replaced-Song.mp3"] = ByteArray(10)
        folder.files[".keepfin/trash/index.jsonl"] = ByteArray(1)
        folder.mkdirs(".keepfin/trash/copies/2026-01-01")
        for (id in listOf(old, recent, unknown)) folder.mkdirs(".keepfin/trash/$id")
        folder.dirTimes[".keepfin/trash/$old"] = clock - 31 * day
        folder.dirTimes[".keepfin/trash/$recent"] = clock - 2 * day
        // the copies' trash has its own catalogue and expiry; a folder whose time the storage does not say stays
        folder.dirTimes[".keepfin/trash/copies"] = clock - 400 * day

        assertEquals(1, a.repo.purgeTrash())
        assertFalse("past 30 days: gone for good", folder.files.containsKey(".keepfin/trash/$old/Song.mp3"))
        assertTrue(folder.files.containsKey(".keepfin/trash/$recent/Song.mp3"))
        assertTrue(folder.files.containsKey(".keepfin/trash/$unknown/Song.mp3"))
        assertTrue(folder.files.containsKey(".keepfin/trash/copies/2026-01-01/replaced-Song.mp3"))
        assertTrue(folder.files.containsKey(".keepfin/trash/index.jsonl"))
    }

    @Test fun deleteForGoodErasesWhenItsUndoRunsOutOrAtTheNextOpenAndUndoClearsTheMark() {
        val a = replica()
        val one = a.addSong("One")
        val two = a.addSong("Two")
        val three = a.addSong("Three")
        assertTrue("item ids name trash folders", Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(one))

        // the notice ran out: erased at once
        a.repo.deleteItems(listOf(one))
        a.repo.markForErase(listOf(one))
        assertTrue(folder.files.containsKey(".keepfin/trash/$one/One.mp3"))
        assertEquals(1, a.repo.eraseTrashed(listOf(one)))
        assertFalse(folder.files.keys.any { it.startsWith(".keepfin/trash/$one/") })
        assertEquals("nothing left to bring back", emptyList<String>(), a.repo.trashedAmong(listOf(one)))

        // Undo while the notice shows: the file is back and nothing is erased later, even by a purge
        a.repo.deleteItems(listOf(two))
        a.repo.markForErase(listOf(two))
        assertEquals(listOf(two), a.repo.restoreItems(listOf(two)).restored)
        assertTrue(folder.files.containsKey("Music/Two.mp3"))
        assertFalse(folder.files.containsKey(".keepfin/trash/$two/.erase"))

        // the app closed before the notice ended: the next open (no Undo memory) erases the marked folder, whatever its age
        a.repo.deleteItems(listOf(three))
        a.repo.markForErase(listOf(three))
        folder.dirTimes[".keepfin/trash/$three"] = clock
        a.repo.shutdown()
        a.store.abandon()
        replicas.remove(a)
        val again = replica()
        assertEquals(1, again.repo.purgeTrash())
        assertFalse(folder.files.keys.any { it.startsWith(".keepfin/trash/$three/") })
        assertTrue("an ordinary delete of the past stays", folder.files.containsKey("Music/Two.mp3"))
    }

    @Test fun aDeletedFileComesBackFromTheTrashWithItsTagsFavouriteAndCollectionPlace() {
        val a = replica()
        val one = a.addSong("One", artist = "Band")
        val two = a.addSong("Two", artist = "Band")
        val three = a.addSong("Three")
        a.repo.setFavorite(two, true)
        a.repo.libraryTag(listOf(two), add = listOf("rock", "road"))
        a.repo.recordPlayback(two, 40.0, false)
        folder.files["Music/Two.lrc"] = "[00:01.00]Hello".toByteArray()
        val list = a.repo.collectionSave(null, "Road trip", listOf(one, two, three))
        val bytes = folder.files.getValue("Music/Two.mp3").copyOf()
        val before = a.repo.allItems().single { it.id == two }
        assertEquals(1, before.plays)
        // the delete dialog asks this: files in the Keepfin folder go to its trash (the item addresses are the provider's, not a prefix)
        assertEquals(2, a.repo.trashableCount(listOf(one, two, "not-an-item")))

        a.repo.deleteItems(listOf(two))
        assertEquals("the delete is restorable", listOf(two), a.repo.trashedAmong(listOf(one, two)))
        assertTrue(folder.files.containsKey(".keepfin/trash/$two/Two.mp3"))
        assertTrue("its sidecar went along", folder.files.containsKey(".keepfin/trash/$two/Two.lrc"))
        assertFalse(folder.files.containsKey("Music/Two.mp3"))
        assertNull(a.repo.item(two))
        assertEquals("the collection shows the others", listOf(one, three), a.repo.collection(list)!!.itemIds)

        val result = a.repo.restoreItems(listOf(two))
        assertEquals(listOf(two), result.restored)
        assertTrue(result.problems.isEmpty())
        assertTrue("the file is back at its path with its bytes", bytes.contentEquals(folder.files.getValue("Music/Two.mp3")))
        assertTrue("and its sidecar", folder.files.containsKey("Music/Two.lrc"))
        assertFalse(folder.files.containsKey(".keepfin/trash/$two/Two.mp3"))
        val after = a.repo.allItems().single { it.id == two }
        assertEquals(before, after)
        assertTrue(after.favorite)
        assertEquals(setOf("road", "rock"), after.userTags.toSet())
        assertEquals(1, after.plays)
        assertEquals("back in the collection at its place", listOf(one, two, three), a.repo.collection(list)!!.itemIds)
        assertEquals("nothing to restore twice", emptyList<String>(), a.repo.trashedAmong(listOf(two)))
    }

    @Test fun aRestoredItemSurvivesARestartAndReachesAnotherDevice() {
        val a = replica()
        val one = a.addSong("One")
        val two = a.addSong("Two")
        a.repo.setFavorite(two, true)
        a.repo.libraryTag(listOf(two), add = listOf("night"))
        val list = a.repo.collectionSave(null, "Mix", listOf(two, one))
        a.repo.deleteItems(listOf(two))
        assertEquals(listOf(one), a.repo.collection(list)!!.itemIds)
        val written = a.written { a.repo.restoreItems(listOf(two)) }
        assertEquals(listOf("item.add", "item.place", "item.revise"), written.kinds().filter { it.startsWith("item.") })
        val beforeItems = a.repo.allItems()
        a.repo.shutdown() // writes the segments
        a.store.abandon()
        replicas.remove(a)

        val again = replica()
        assertEquals("the same library after a restart", beforeItems, again.repo.allItems())
        assertEquals(listOf(two, one), again.repo.collection(list)!!.itemIds)
        val other = replica(actorB, replicaB, name = "device-b")
        val seen = other.repo.modelNow().items.single { it.id == two }
        assertTrue(seen.favorite)
        assertEquals(listOf("night"), seen.userTags)
        assertEquals(listOf(two, one), other.repo.collection(list)!!.itemIds)
    }

    @Test fun aFileThatCannotComeBackIsReportedAndStaysInTheTrash() {
        val a = replica()
        val one = a.addSong("One")
        val two = a.addSong("Two")
        a.repo.deleteItems(listOf(one, two))
        // a new file took the name of the first
        folder.files["Music/One.mp3"] = "someone else".toByteArray()
        val result = a.repo.restoreItems(listOf(one, two))
        assertEquals(listOf(two), result.restored)
        assertEquals(1, result.problems.size)
        assertTrue(result.problems[0], result.problems[0].contains("“One”") && result.problems[0].contains("another file has its name"))
        assertEquals("the other file was not touched", "someone else", String(folder.files.getValue("Music/One.mp3")))
        assertTrue(folder.files.containsKey(".keepfin/trash/$one/One.mp3"))
        assertNull(a.repo.item(one))
        assertEquals(listOf(two), a.repo.allItems().map { it.id })
        // and a trash entry that is gone is said too
        folder.files.remove(".keepfin/trash/$one/One.mp3")
        assertEquals(1, a.repo.restoreItems(listOf(one)).problems.size)
    }

    // ------------------------------------------------------------------------------------------ the Trash page

    private val dayMs = 24L * 3_600_000L

    private fun Replica.restart(): Replica {
        repo.shutdown()
        store.abandon()
        replicas.remove(this)
        return replica(actor, replica)
    }

    @Test fun theRecordInTheTrashFolderRoundTripsAndToleratesMissingFields() {
        val item = LibItem(
            id = "x", space = "music", kind = "audio", title = "Glass Harbour", artist = "Band", track = 3.0, favorite = true, userTags = listOf("a", "b"),
            plays = 4.0, tags = listOf("imported"), chapters = listOf(app.keepfin.core.library.LibChapter(1.5, "Intro")), place = "Music/Glass Harbour.mp3",
        )
        val info = TrashInfo("x", "Glass Harbour", "Music/Glass Harbour.mp3", "music", 12L, "audio", 99L, "sha256:ab", listOf("Music/Glass Harbour.lrc"), item, listOf(TrashPlace("c1", listOf("a", "x", "b"))))
        val back = TrashInfo.parse(info.toBytes())!!
        assertEquals(item, back.item)
        assertEquals(99L, back.deletedAt)
        assertEquals(listOf("Music/Glass Harbour.lrc"), back.sidecars)
        assertEquals(listOf("a", "x", "b"), back.places.single().before)
        // the Windows app writes only id, title, path and deletedAt: still readable, nothing else invented
        val sparse = TrashInfo.parse("""{"id":"x","title":"T","path":"C:\\k\\T.mp3","deletedAt":5,"future":{"a":1}}""".toByteArray())!!
        assertEquals("T", sparse.title)
        assertNull(sparse.item)
        assertNull(sparse.place)
        assertNull(TrashInfo.parse("not json".toByteArray()))
    }

    @Test fun theTrashListsDeletedFilesNewestFirstWithDaysLeftAndSkipsEraseMarkedFoldersAndCopies() {
        val a = replica()
        val one = a.addSong("One")
        val two = a.addSong("Two")
        val three = a.addSong("Three")
        a.repo.deleteItems(listOf(one))
        clock += 3 * dayMs
        a.repo.deleteItems(listOf(two))
        clock += 5_000
        a.repo.deleteItems(listOf(three))
        a.repo.markForErase(listOf(three))
        folder.files[".keepfin/trash/copies/2026-01-01/replaced-Song.mp3"] = ByteArray(10)
        folder.files[".keepfin/trash/index.jsonl"] = ByteArray(1)

        val list = a.repo.trashEntries()
        assertEquals("newest first; the marked one and the copies' trash are not listed", listOf(two, one), list.map { it.id })
        assertEquals(listOf("Two", "One"), list.map { it.title })
        assertEquals(listOf("Two.mp3", "One.mp3"), list.map { it.fileName })
        assertEquals(listOf("music", "music"), list.map { it.space })
        assertEquals("size of the file", "Two".toByteArray().size.toLong(), list[0].size)
        assertEquals("deleted three days and a bit ago", 30, list[0].daysLeft)
        assertEquals(27, list[1].daysLeft)
        clock += 40 * dayMs
        assertEquals("never below 0", listOf(0, 0), a.repo.trashEntries().map { it.daysLeft })
    }

    @Test fun aFileInTheTrashComesBackAfterARestartWithItsTagsFavouritePlaysAndCollectionPlace() {
        val a = replica()
        val one = a.addSong("One")
        val two = a.addSong("Two")
        val three = a.addSong("Three")
        a.repo.setFavorite(two, true)
        a.repo.libraryTag(listOf(two), add = listOf("rock", "road"))
        a.repo.recordPlayback(two, 40.0, false)
        folder.files["Music/Two.lrc"] = "[00:01.00]Hello".toByteArray()
        val list = a.repo.collectionSave(null, "Road trip", listOf(one, two, three))
        val bytes = folder.files.getValue("Music/Two.mp3").copyOf()
        val before = a.repo.allItems().single { it.id == two }
        a.repo.deleteItems(listOf(two))
        assertTrue("restore needs a record on disk", folder.files.containsKey(".keepfin/trash/$two/item.json"))

        // nothing in memory any more
        val again = a.restart()
        assertEquals(emptyList<String>(), again.repo.trashedAmong(listOf(two)))
        assertEquals(listOf(two), again.repo.trashEntries().map { it.id })
        val result = again.repo.restoreItems(listOf(two))
        assertEquals(listOf(two), result.restored)
        assertTrue(result.problems.toString(), result.problems.isEmpty())
        assertTrue(bytes.contentEquals(folder.files.getValue("Music/Two.mp3")))
        assertTrue("its sidecar", folder.files.containsKey("Music/Two.lrc"))
        val after = again.repo.allItems().single { it.id == two }
        assertEquals(before, after)
        assertTrue(after.favorite)
        assertEquals(setOf("road", "rock"), after.userTags.toSet())
        assertEquals(1, after.plays)
        assertEquals("its place in the collection", listOf(one, two, three), again.repo.collection(list)!!.itemIds)
        assertTrue("nothing of it is left in the trash", folder.files.keys.none { it.startsWith(".keepfin/trash/$two") })
        assertEquals(emptyList<TrashEntry>(), again.repo.trashEntries())
    }

    @Test fun restoringFromTheTrashNeverReplacesAFileThatHasTakenTheNameAndTheEntryStays() {
        val a = replica()
        val one = a.addSong("One")
        a.repo.deleteItems(listOf(one))
        folder.files["Music/One.mp3"] = "someone else".toByteArray()
        val again = a.restart()
        val result = again.repo.restoreItems(listOf(one))
        assertEquals(emptyList<String>(), result.restored)
        assertTrue(result.problems.single(), result.problems.single().contains("“One”") && result.problems.single().contains("has its name now"))
        assertEquals("someone else", String(folder.files.getValue("Music/One.mp3")))
        assertEquals("still in the trash", listOf(one), again.repo.trashEntries().map { it.id })
    }

    @Test fun deletingForGoodAndEmptyingTheTrashEraseNowAndDropTheUndoRecord() {
        val a = replica()
        val one = a.addSong("One")
        val two = a.addSong("Two")
        val three = a.addSong("Three")
        a.repo.deleteItems(listOf(one, two, three))
        assertEquals(listOf(one, two, three).toSet(), a.repo.trashedAmong(listOf(one, two, three)).toSet())

        assertEquals(1, a.repo.eraseFromTrash(listOf(one)))
        assertTrue(folder.files.keys.none { it.startsWith(".keepfin/trash/$one") })
        assertEquals("the Undo record went with it", emptyList<String>(), a.repo.trashedAmong(listOf(one)))
        assertEquals("so a later Undo says it is gone and does not bring it back", 1, a.repo.restoreItems(listOf(one)).problems.size)
        assertNull(a.repo.item(one))
        assertEquals(setOf(two, three), a.repo.trashEntries().map { it.id }.toSet())

        assertEquals(2, a.repo.emptyTrash())
        assertEquals(emptyList<TrashEntry>(), a.repo.trashEntries())
        assertTrue(folder.files.keys.none { it.startsWith(".keepfin/trash/0") })
    }

    @Test fun restoringFromTheTrashDropsTheUndoRecord() {
        val a = replica()
        val one = a.addSong("One")
        a.repo.deleteItems(listOf(one))
        assertEquals(listOf(one), a.repo.restoreItems(listOf(one)).restored)
        assertEquals(emptyList<String>(), a.repo.trashedAmong(listOf(one)))
        // a second Undo of the same delete finds nothing in the trash and adds nothing twice
        val second = a.repo.restoreItems(listOf(one))
        assertEquals("nothing is restored a second time", emptyList<String>(), second.restored)
        assertEquals(1, second.problems.size)
        assertEquals(1, a.repo.allItems().count { it.id == one })
    }

    @Test fun aFolderWithAnUnreadableRecordIsStillListedByItsFileNameAndComesBackAsANewImport() {
        val a = replica()
        val one = a.addSong("One")
        a.repo.deleteItems(listOf(one))
        folder.files[".keepfin/trash/$one/item.json"] = "{ this is not json".toByteArray()
        val again = a.restart()
        val entry = again.repo.trashEntries().single()
        assertEquals("One", entry.title)
        assertEquals("One.mp3", entry.fileName)
        assertEquals(one, entry.itemId)
        val result = again.repo.restoreItems(listOf(entry.id))
        assertEquals(listOf(one), result.restored)
        assertTrue(folder.files.containsKey("Music/One.mp3"))
        val item = again.repo.allItems().single { it.id == one }
        assertEquals("One", item.title)
        assertEquals(emptyList<TrashEntry>(), again.repo.trashEntries())
    }

    @Test fun aDeletedCollectionComesBackWithTheSameIdNameAndOrder() {
        val a = replica()
        val one = a.addSong("One")
        val two = a.addSong("Two")
        val list = a.repo.collectionSave(null, "Road trip", listOf(two, one))
        val c = a.repo.collection(list)!!
        a.repo.collectionDelete(list)
        assertNull(a.repo.collection(list))
        assertEquals(list, a.repo.collectionSave(list, c.name, c.itemIds, c.space))
        val back = a.repo.collection(list)!!
        assertEquals(listOf(two, one), back.itemIds)
        assertEquals("Road trip", back.name)
        assertEquals(c.space, back.space)
        assertEquals(1, a.repo.library.value.collections.size)
    }

    @Test fun aSecondReplicasSegmentsAppearInTheSnapshot() {
        val a = replica()
        val one = a.addSong("One")
        a.repo.shutdown() // writes the segments
        a.store.abandon()
        replicas.remove(a)

        val first = replica(actorA, replicaA)
        val second = replica(actorB, replicaB, name = "device-b")
        // B has no placement for A's file: the item is listed as not on this phone (a paired device can play it) and counted as a problem
        assertEquals(listOf(true to ""), second.repo.allItems().map { it.remote to it.uri })
        assertTrue(second.repo.library.value.problems.isNotEmpty())
        assertEquals(listOf(one), second.repo.modelNow().items.map { it.id })

        // B edits shared data and creates a playlist; A sees it after a rescan
        second.repo.setFavorite(one, true)
        second.repo.libraryTag(listOf(one), add = listOf("shared"))
        val list = second.repo.collectionSave(null, "From B", listOf(one))
        second.store.flush().get(10, TimeUnit.SECONDS)

        first.sync()
        val item = first.repo.allItems().single { it.id == one }
        assertTrue(item.favorite)
        assertEquals(listOf("shared"), item.userTags)
        assertEquals(listOf(one), first.repo.collection(list)!!.itemIds)

        // and the other way: A's later change reaches B
        first.repo.setFavorite(one, false)
        first.store.flush().get(10, TimeUnit.SECONDS)
        second.sync()
        assertFalse(second.repo.modelNow().items.single().favorite)
    }

    @Test fun lyricsAndSubtitlesComeFromSidecarsNextToTheFile() {
        val a = replica()
        val one = a.addSong("One")
        folder.files["Music/One.lrc"] = "[00:01.00]Hello\n[00:02.00]World".toByteArray()
        folder.files["Music/One.en.vtt"] = "WEBVTT".toByteArray()
        assertEquals(2, a.repo.lyrics(one)!!.lines.size)
        val subs = a.repo.subtitles(one)
        assertEquals(1, subs.size)
        assertEquals("en", subs[0].language)
        assertEquals("keepfin-folder:Music/One.en.vtt", subs[0].uri)
        // a rename takes the sidecars along
        a.repo.rename(one, "Uno")
        assertTrue(folder.files.containsKey("Music/Uno.lrc") && folder.files.containsKey("Music/Uno.en.vtt"))
    }

    @Test fun profilesAreStoredAndRead() {
        val file = File(root, "profiles.json")
        val store = app.keepfin.folder.ProfileStore(file)
        assertNull(store.current())
        val profile = app.keepfin.folder.Profile("p1", "Pixel", "content://tree/Keepfin", library, actorA, "2026-10-15T00:00:00Z")
        store.add(profile)
        assertEquals(profile, store.current())
        store.updateActor("p1", actorB)
        assertEquals(actorB, app.keepfin.folder.ProfileStore(file).current()!!.actorId)
        file.writeText("{ broken")
        assertNull(app.keepfin.folder.ProfileStore(file).current())
    }

    @Test fun theSnapshotCostAtManyItems() {
        val a = replica()
        val count = (System.getProperty("keepfin.repoItems") ?: "20000").toInt()
        val space = JStr("6b8e1c10-2d3a-4f55-8a01-000000000002")
        val drafts = ArrayList<OpDraft>(count * 2)
        for (n in 0 until count) {
            val id = "00000000-0000-4000-8000-%012d".format(900_000 + n)
            drafts += OpDraft("item.add", mapOf("item" to JStr(id), "fields" to JObj(mapOf("media" to JStr("audio"), "space" to space, "title" to JStr("Song $n"), "artist" to JStr("Artist ${n % 500}"), "duration" to JNum(200_000L + n), "addedAt" to JNum(1_700_000_000_000L + n)))))
            drafts += OpDraft("item.place", mapOf("item" to JStr(id), "place" to JStr(replicaA), "path" to JStr("Music/Artist ${n % 500}/Song $n.mp3")))
        }
        for (chunk in drafts.chunked(5000)) a.store.apply(chunk).get(120, TimeUnit.SECONDS)
        val started = System.nanoTime()
        a.repo.rescan()
        a.repo.awaitReady(300)
        val refreshMs = (System.nanoTime() - started) / 1_000_000
        val one = a.repo.allItems().first().id
        val editStarted = System.nanoTime()
        a.repo.setFavorite(one, true)
        val editMs = (System.nanoTime() - editStarted) / 1_000_000
        println("repository at $count items: snapshot rebuild $refreshMs ms, one favourite edit (apply + rebuild) $editMs ms, ${Runtime.getRuntime().totalMemory() / 1_000_000} MB heap in use")
        assertEquals(count, a.repo.allItems().size)
        assertNotNull(a.repo.item(one))
    }
}
