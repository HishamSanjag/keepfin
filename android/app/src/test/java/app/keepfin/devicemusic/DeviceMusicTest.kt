package app.keepfin.devicemusic

import app.keepfin.DEVICE_SPACE_ID
import app.keepfin.DeviceMusicState
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.all
import app.keepfin.folder.sql.JdbcHost
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun file(id: Long, name: String, rel: String = "Music/Artists/", dur: Long = 200_000, size: Long = 4096, modified: Long = 100, volume: String = "external_primary", album: String? = "Album", artist: String? = "Artist") =
    DeviceFile(
        volume, id, "content://media/$volume/audio/media/$id", rel, name, name.substringBeforeLast('.'), artist, album, null, 1, 2020, dur, size, modified, 50, "audio/mpeg", isMusic = true,
    )

private class FakeSource(var files: List<DeviceFile>) : DeviceAudioSource {
    override fun volumes() = files.map { it.volume }.distinct().ifEmpty { listOf("external_primary") }
    override fun batch(volume: String, afterId: Long, limit: Int) = files.filter { it.volume == volume && it.mediaId > afterId }.sortedBy { it.mediaId }.take(limit)
}

private class FakePlatform(val fake: FakeSource) : DeviceMusicPlatform {
    var granted = true
    override val source: DeviceAudioSource get() = fake
    override fun missingPermissions() = if (granted) emptyList() else listOf("audio")
    override fun watch(onChange: () -> Unit): () -> Unit = {}
    override val ownRoots = listOf("Music/Keepfin")
}

class DeviceMusicTest {
    private lateinit var dir: File
    private lateinit var source: FakeSource
    private lateinit var platform: FakePlatform
    private lateinit var manager: DeviceMusicManager
    private var linked: List<Pair<String, String>> = emptyList()
    private var clock = 1_000_000L

    @Before fun setUp() {
        dir = Files.createTempDirectory("kf-device").toFile()
        source = FakeSource(emptyList())
        platform = FakePlatform(source)
        manager = DeviceMusicManager(platform, File(dir, "device-music.db"), KnownFiles { linked }, { JdbcHost().open(it) }, { JdbcHost().open(it) }, { clock += 20_000; clock })
    }

    @After fun tearDown() {
        manager.shutdown()
        dir.deleteRecursively()
    }

    private fun on(count: Int = source.files.size) { manager.setOn(true); waitReady(count) }

    /** Waits for the scan that setOn started: the state is Ready with [count] songs. */
    private fun waitReady(count: Int) {
        val end = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < end) {
            if (manager.state.value == DeviceMusicState.Ready(count)) { Thread.sleep(100); return }
            Thread.sleep(20)
        }
        throw AssertionError("not ready: ${manager.state.value}")
    }

    private fun titles(): List<String> = manager.browse!!.all(ItemQuery(spaces = listOf(DEVICE_SPACE_ID), order = ItemOrder.TITLE)).map { it.title }

    @Test fun scanListsOnlyMusicAndKeepsFilesInPlace() {
        source.files = listOf(
            file(1, "a.mp3"), file(2, "b.mp3"), file(3, "c.mp3"),
            file(4, "short.mp3", dur = 5_000), file(5, "ring.mp3", rel = "Ringtones/").copy(isMusic = false),
        )
        on(3)
        assertEquals(listOf("a", "b", "c"), titles())
        val item = manager.browse!!.item(app.keepfin.core.devicemusic.DeviceMusic.idOf("external_primary", "1"))!!
        assertEquals("content://media/external_primary/audio/media/1", item.uri)
        assertEquals(DEVICE_SPACE_ID, item.space)
        assertEquals("Music/Artists", item.folder)
    }

    @Test fun rescanAddsChangesAndRemoves() {
        source.files = listOf(file(1, "a.mp3"), file(2, "b.mp3"), file(3, "c.mp3"))
        on()
        source.files = listOf(file(1, "a.mp3"), file(2, "b.mp3", modified = 999, size = 5000), file(4, "d.mp3"))
        val r = manager.refreshNow()!!
        assertEquals(3, r.seen); assertEquals(1, r.added); assertEquals(1, r.changed); assertEquals(1, r.removed)
        assertEquals(listOf("a", "b", "d"), titles())
    }

    @Test fun skipsFilesKeepfinAlreadyKnows() {
        linked = listOf("content://media/external/file/2" to "Music/Elsewhere/b.mp3")
        source.files = listOf(file(1, "a.mp3"), file(2, "b.mp3"), file(3, "c.mp3", rel = "Music/Elsewhere/"), file(4, "own.mp3", rel = "Music/Keepfin Music/"), file(5, "mine.mp3", rel = "Music/KEEPFIN/x/"))
        on(2)
        assertEquals(listOf("a", "c"), titles())
        assertFalse("own downloads are not listed twice", titles().any { it == "own" || it == "mine" })
    }

    @Test fun readoptsIdsAfterMediaStoreRebuild() {
        source.files = listOf(file(1, "a.mp3"), file(2, "b.mp3"))
        on()
        val old = app.keepfin.core.devicemusic.DeviceMusic.idOf("external_primary", "1")
        manager.setFavorite(old, true)
        manager.recordPlayback(old, null, true)
        manager.overlayAdd("playlist-1", listOf(old))
        source.files = listOf(file(101, "a.mp3"), file(102, "b.mp3"))
        manager.refreshNow()
        val fresh = app.keepfin.core.devicemusic.DeviceMusic.idOf("external_primary", "101")
        val item = manager.browse!!.item(fresh)!!
        assertTrue(item.favorite); assertEquals(1, item.plays)
        assertEquals(2, manager.browse!!.count(ItemQuery(spaces = listOf(DEVICE_SPACE_ID))))
        assertEquals(listOf(fresh), manager.overlay()["playlist-1"])
    }

    @Test fun hiddenSongsStayHiddenUntilShownAgain() {
        source.files = listOf(file(1, "a.mp3"), file(2, "b.mp3"))
        on()
        manager.hide(listOf(app.keepfin.core.devicemusic.DeviceMusic.idOf("external_primary", "1")))
        assertEquals(listOf("b"), titles())
        manager.refreshNow()
        assertEquals(listOf("b"), titles())
        assertEquals(1, manager.hiddenCount)
        manager.unhideAll()
        manager.refreshNow()
        assertEquals(listOf("a", "b"), titles())
        assertEquals(0, manager.hiddenCount)
    }

    @Test fun favoritesPlaysAndPlaylistsAreKeptOnThisDeviceOnly() {
        source.files = listOf(file(1, "a.mp3"), file(2, "b.mp3"))
        on()
        val id = app.keepfin.core.devicemusic.DeviceMusic.idOf("external_primary", "2")
        manager.setFavorite(id, true)
        manager.recordPlayback(id, 40.0, false)
        manager.recordPlayback(id, 100.0, false)
        manager.overlayAdd("list", listOf(id))
        manager.overlayAdd("list", listOf(id))
        val item = manager.browse!!.item(id)!!
        assertTrue(item.favorite); assertEquals(1, item.plays)
        assertEquals(listOf(id), manager.overlay()["list"])
        manager.overlayRemove("list", listOf(id))
        assertTrue(manager.overlay().isEmpty())
        // the only files this wrote are the device table's own
        assertTrue(dir.walkTopDown().filter { it.isFile }.all { it.name.startsWith("device-music.db") })
    }

    @Test fun favoritesOfEverySpaceIncludeDeviceSongsWhileTheFeatureIsOn() {
        source.files = listOf(file(1, "b.mp3"), file(2, "d.mp3"), file(3, "f.mp3"), file(4, "x.mp3"))
        on()
        listOf("1", "2", "3").forEach { manager.setFavorite(app.keepfin.core.devicemusic.DeviceMusic.idOf("external_primary", it), true) }
        val own = listOf("a", "c", "e", "g").mapIndexed { n, t ->
            app.keepfin.LibraryItem("m$n", "music", "u$n", "", app.keepfin.LibraryItemKind.AUDIO, t, size = 1, modifiedAt = 0, addedAt = 1_000L * (n + 1), favorite = true)
        }
        val browse = DeviceAwareBrowse(app.keepfin.MemoryBrowse({ own }, kotlinx.coroutines.flow.MutableStateFlow(0L))) { manager.browse }
        val byTitle = ItemQuery(favorite = true, order = ItemOrder.TITLE)
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), browse.all(byTitle, pageSize = 2).map { it.title })
        assertEquals(7, browse.count(byTitle))
        assertEquals(7, browse.counts(byTitle).total)
        // newest first: the device songs were added after the others
        assertEquals(listOf("f", "d", "b"), browse.all(ItemQuery(favorite = true, order = ItemOrder.ADDED), pageSize = 3).map { it.title }.take(3))
        // paging backwards from the end gives the same list
        val last = browse.page(byTitle, app.keepfin.PageDirection.FIRST, null, 7).next
        assertNull(last)
        val tail = browse.page(byTitle, app.keepfin.PageDirection.AFTER, browse.keyOf(own[1], ItemOrder.TITLE), 2)
        assertEquals(listOf("d", "e"), tail.items.map { it.title })
        val back = browse.page(byTitle, app.keepfin.PageDirection.BEFORE, browse.keyOf(own[3], ItemOrder.TITLE), 3)
        assertEquals(listOf("d", "e", "f"), back.items.map { it.title })
        // a query for one other space never sees them
        assertEquals(4, browse.all(byTitle.copy(spaces = listOf("music"))).size)
        manager.setOn(false)
        assertEquals(listOf("a", "c", "e", "g"), browse.all(byTitle, pageSize = 2).map { it.title })
        assertEquals(4, browse.count(byTitle))
    }
    @Test fun missingPermissionHidesTheRowsWithoutDeletingThem() {
        source.files = listOf(file(1, "a.mp3"))
        on()
        platform.granted = false
        assertNull(manager.refreshNow())
        assertEquals(DeviceMusicState.NeedsPermission, manager.state.value)
        assertNull(manager.browse)
        platform.granted = true
        manager.setOn(true)
        waitReady(1)
        assertEquals(listOf("a"), titles())
    }

    @Test fun turnedOnWithoutPermissionAsksFirst() {
        platform.granted = false
        manager.setOn(true)
        assertEquals(DeviceMusicState.NeedsPermission, manager.state.value)
        assertEquals(listOf("audio"), manager.missingPermissions())
    }

    @Test fun leftOutFoldersAreRespected() {
        source.files = listOf(file(1, "a.mp3"), file(2, "b.mp3", rel = "Music/Podcasts/"))
        on()
        assertEquals(listOf("a", "b"), titles())
        manager.setLeftOut(listOf("Music/Podcasts/"))
        waitReady(1)
        assertEquals(listOf("a"), titles())
        assertTrue("Music/Podcasts" in manager.folders() || "Music/Podcasts/" in manager.folders())
    }

    @Test fun twentyThousandSongsListWithinBudget() {
        source.files = (1L..20_000L).map { file(it, "song-%05d.mp3".format(it), rel = "Music/Artist ${it % 200}/", album = "Album ${it % 1000}", artist = "Artist ${it % 200}") }
        val t0 = System.nanoTime()
        manager.setOn(true)
        waitReady(20_000)
        val scanMs = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        val page = manager.browse!!.page(ItemQuery(spaces = listOf(DEVICE_SPACE_ID), order = ItemOrder.TITLE), app.keepfin.PageDirection.FIRST, null, 50)
        val pageMs = (System.nanoTime() - t1) / 1_000_000
        assertEquals(50, page.items.size)
        assertEquals(20_000, manager.browse!!.count(ItemQuery(spaces = listOf(DEVICE_SPACE_ID))))
        val t2 = System.nanoTime()
        val again = manager.refreshNow()!!
        val rescanMs = (System.nanoTime() - t2) / 1_000_000
        assertEquals(0, again.added + again.changed + again.removed)
        println("device music 20,000 songs: first scan $scanMs ms, first page $pageMs ms, unchanged rescan $rescanMs ms")
        assertTrue("first scan took $scanMs ms", scanMs < 60_000)
        assertTrue("page took $pageMs ms", pageMs < 1_000)
    }
}
