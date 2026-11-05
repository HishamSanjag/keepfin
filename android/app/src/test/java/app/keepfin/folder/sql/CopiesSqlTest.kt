package app.keepfin.folder.sql

import app.keepfin.core.copies.Holds
import app.keepfin.core.copies.Names
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.StoreOptions
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The desired set of a place, read from the SQL state, against what the library's operations say it should be. */
class CopiesSqlTest {
    private val library = "11111111-1111-4111-8111-111111111111"
    private val actor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val replica = "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1"
    private lateinit var root: File
    private var store: KeepfinStore? = null
    private var ids = 0
    private var clock = 1_700_000_000_000L

    @Before fun setUp() {
        root = Files.createTempDirectory("keepfin-copiessql").toFile()
    }

    @After fun tearDown() {
        store?.abandon()
        root.deleteRecursively()
    }

    private fun item(n: Int) = "%08x-0000-4000-8000-000000000000".format(n)
    private fun content(n: Int) = "sha256:" + "%064x".format(n)

    private fun draft(kind: String, vararg body: Pair<String, app.keepfin.core.sync.Jv>) = OpDraft(kind, linkedMapOf(*body))

    private fun add(n: Int, space: String, placed: Boolean = true): List<OpDraft> {
        val out = ArrayList<OpDraft>()
        out += draft("item.add", "item" to JStr(item(n)), "fields" to JObj(mapOf("space" to JStr(space), "media" to JStr("audio"), "title" to JStr("Song $n"))))
        if (placed) out += draft("item.place", "item" to JStr(item(n)), "place" to JStr(replica), "path" to JStr("$space/Song $n.mp3"))
        out += draft("item.revise", "item" to JStr(item(n)), "content" to JStr(content(n)), "size" to JNum(100L + n), "parents" to JArr(emptyList()))
        return out
    }

    @Test fun holdsSelectItemsAndConflictsAndDeletionsAreRead() {
        val dir = File(root, "dev")
        val state = SqlMergeState(File(dir, "state.db").path, library, replica, null, JdbcHost())
        val s = KeepfinStore.open(
            StoreOptions(folder = FakeFolderAccess(), libraryId = library, actor = actor, deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ }, flushIdleMs = 3_600_000, state = state),
        ).get(10, TimeUnit.SECONDS)
        store = s
        val drafts = ArrayList<OpDraft>()
        drafts += add(1, "Music")
        drafts += add(2, "Music")
        drafts += add(3, "Videos")
        drafts += add(4, "Videos", placed = false) // no path on this folder yet: cannot be copied
        drafts += add(5, "Music")
        drafts += draft("set.add", "set" to JStr("favourites"), "element" to JStr(item(3)))
        // a content conflict on item 2: a second head concurrent with the first
        drafts += draft("item.revise", "item" to JStr(item(2)), "content" to JStr(content(22)), "size" to JNum(122L), "parents" to JArr(emptyList()))
        s.apply(drafts).get(10, TimeUnit.SECONDS)
        // item 5 is deleted
        val o = s.observed(items = listOf(item(5))).get(10, TimeUnit.SECONDS).items.getValue(item(5))
        s.apply(listOf(draft("item.delete", "item" to JStr(item(5)), "observedTags" to JArr(o.tags.map { JStr(it.toString()) }), "observedHeads" to JArr(o.heads.map { JStr(it.toString()) })))).get(10, TimeUnit.SECONDS)

        val reader = IndexReader(state.statePath, { state.epoch }) { JdbcHost().open(it) }
        val q = CopiesSql(reader::rows)
        fun paths(h: Holds) = q.desiredEntries(replica, h).map { it.path }.sorted().toList()

        val all = q.desiredEntries(replica, Holds(all = true)).toList()
        // items 1, 2 (two heads), 3; not 4 (no path), not 5 (deleted)
        assertEquals(4, all.size)
        assertEquals(listOf("Music/Song 1.mp3", "Music/Song 2.mp3", "Videos/Song 3.mp3"), all.map { it.path }.filter { !it.contains("conflict") }.sorted())
        val second = all.single { it.path.contains("conflict") }
        assertEquals(item(2), second.itemId)
        assertTrue(second.path == Names.conflictPath("Music/Song 2.mp3", second.contentId) || second.path == Names.conflictPath("Music/Song 2.mp3", content(2)))
        assertEquals(listOf("Music/Song 1.mp3", "Music/Song 2.mp3", Names.conflictPath("Music/Song 2.mp3", all.first { it.path.contains("conflict") }.contentId)).sorted(), paths(Holds(spaces = setOf("Music"))))
        assertEquals(listOf("Videos/Song 3.mp3"), paths(Holds(favourites = true)))
        assertEquals(emptyList<String>(), paths(Holds()))
        assertEquals(setOf(item(5)), q.deletedItems())
        assertEquals(listOf(item(2)), q.conflictItems().map { it.id })
        assertEquals(item(2), q.conflictItems().single().id)
        assertEquals(2, q.conflictItems().single().heads.size)
        assertEquals("Music/Song 1.mp3", q.placement(item(1), replica))
        reader.close()
    }

    @Test fun eachItemCarriesItsLyricsAndSubtitlesUnlessTurnedOff() {
        val dir = File(root, "dev")
        val state = SqlMergeState(File(dir, "state.db").path, library, replica, null, JdbcHost())
        val s = KeepfinStore.open(
            StoreOptions(folder = FakeFolderAccess(), libraryId = library, actor = actor, deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ }, flushIdleMs = 3_600_000, state = state),
        ).get(10, TimeUnit.SECONDS)
        store = s
        fun companion(n: Int, suffix: String, value: app.keepfin.core.sync.Jv) = draft("item.set", "item" to JStr(item(n)), "field" to JStr("companion:$suffix"), "value" to value)
        val drafts = ArrayList<OpDraft>()
        drafts += add(1, "Music")
        drafts += add(2, "Music")
        drafts += companion(1, ".lrc", JObj(mapOf("content" to JStr(content(11)), "size" to JNum(40L))))
        drafts += companion(1, ".info.json", JStr("not a record"))
        drafts += companion(2, ".lrc", JObj(mapOf("content" to JStr(content(21)), "size" to JNum(41L))))
        // a second head of song 2: its lyrics wait until the conflict is settled
        drafts += draft("item.revise", "item" to JStr(item(2)), "content" to JStr(content(22)), "size" to JNum(122L), "parents" to JArr(emptyList()))
        s.apply(drafts).get(10, TimeUnit.SECONDS)

        val reader = IndexReader(state.statePath, { state.epoch }) { JdbcHost().open(it) }
        val q = CopiesSql(reader::rows)
        val all = q.desiredEntries(replica, Holds(all = true)).toList()
        val lyrics = all.filter { it.path.endsWith(".lrc") }
        assertEquals(listOf("Music/Song 1.lrc"), lyrics.map { it.path })
        assertEquals(item(1), lyrics.single().itemId)
        assertEquals(content(11), lyrics.single().contentId)
        assertEquals(40L, lyrics.single().bytes)
        assertEquals("companion:.lrc", lyrics.single().revisionOp)
        assertEquals(emptyList<String>(), q.desiredEntries(replica, Holds(all = true), companions = false).map { it.path }.filter { it.endsWith(".lrc") }.toList())
        reader.close()
    }

    @Test fun aMusicVideoFollowsOnlyACopyThatHoldsEverything() {
        val dir = File(root, "dev3")
        val state = SqlMergeState(File(dir, "state.db").path, library, replica, null, JdbcHost())
        val s = KeepfinStore.open(
            StoreOptions(folder = FakeFolderAccess(), libraryId = library, actor = actor, deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ }, flushIdleMs = 3_600_000, state = state),
        ).get(10, TimeUnit.SECONDS)
        store = s
        fun companion(n: Int, suffix: String, size: Long) = draft("item.set", "item" to JStr(item(n)), "field" to JStr("companion:$suffix"), "value" to JObj(mapOf("content" to JStr(content(100 + n)), "size" to JNum(size))))
        val drafts = ArrayList<OpDraft>()
        drafts += add(1, "Music")
        drafts += companion(1, ".lrc", 40L)
        drafts += companion(1, ".video.mp4", 9_000_000L)
        s.apply(drafts).get(10, TimeUnit.SECONDS)

        val reader = IndexReader(state.statePath, { state.epoch }) { JdbcHost().open(it) }
        val q = CopiesSql(reader::rows)
        fun paths(h: Holds) = q.desiredEntries(replica, h).map { it.path }.sorted().toList()
        // a copy of everything carries the video beside the song
        assertEquals(listOf("Music/Song 1.lrc", "Music/Song 1.mp3", "Music/Song 1.video.mp4"), paths(Holds(all = true)))
        // a part copy (one space) keeps the lyrics and leaves the video out
        assertEquals(listOf("Music/Song 1.lrc", "Music/Song 1.mp3"), paths(Holds(spaces = setOf("Music"))))
        assertEquals("companion:.video.mp4", q.desiredEntries(replica, Holds(all = true)).single { it.path.endsWith(".video.mp4") }.revisionOp)
        // the index says the song keeps a music video
        assertEquals(1L, reader.rows("SELECT musicVideo FROM lib_index WHERE id = ?", listOf(item(1))).single()[0])
        // a removed video (null record) is no video
        s.apply(listOf(draft("item.set", "item" to JStr(item(1)), "field" to JStr("companion:.video.mp4"), "value" to app.keepfin.core.sync.JNull))).get(10, TimeUnit.SECONDS)
        reader.close()
        val reader2 = IndexReader(state.statePath, { state.epoch }) { JdbcHost().open(it) }
        assertEquals(0L, reader2.rows("SELECT musicVideo FROM lib_index WHERE id = ?", listOf(item(1))).single()[0])
        assertEquals(listOf("Music/Song 1.lrc", "Music/Song 1.mp3"), CopiesSql(reader2::rows).desiredEntries(replica, Holds(all = true)).map { it.path }.sorted().toList())
        reader2.close()
    }

    @Test fun musicVideosAreTheirOwnOptionApartFromLyricsAndTheirSizeIsCounted() {
        val dir = File(root, "dev4")
        val state = SqlMergeState(File(dir, "state.db").path, library, replica, null, JdbcHost())
        val s = KeepfinStore.open(
            StoreOptions(folder = FakeFolderAccess(), libraryId = library, actor = actor, deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ }, flushIdleMs = 3_600_000, state = state),
        ).get(10, TimeUnit.SECONDS)
        store = s
        fun companion(n: Int, suffix: String, size: Long) = draft("item.set", "item" to JStr(item(n)), "field" to JStr("companion:$suffix"), "value" to JObj(mapOf("content" to JStr(content(100 + n)), "size" to JNum(size))))
        val drafts = ArrayList<OpDraft>()
        drafts += add(1, "Music"); drafts += add(2, "Music")
        drafts += companion(1, ".lrc", 40L); drafts += companion(1, ".video.mp4", 9_000_000L)
        drafts += companion(2, ".video.webm", 1_000_000L)
        s.apply(drafts).get(10, TimeUnit.SECONDS)
        val reader = IndexReader(state.statePath, { state.epoch }) { JdbcHost().open(it) }
        val q = CopiesSql(reader::rows)
        fun paths(h: Holds, companions: Boolean, videos: Boolean? = null) =
            (if (videos == null) q.desiredEntries(replica, h, companions) else q.desiredEntries(replica, h, companions, videos)).map { it.path }.sorted().toList()
        val all = Holds(all = true)
        // the default follows what the copy holds; a part copy can choose them on, a full copy off
        assertEquals(listOf("Music/Song 1.lrc", "Music/Song 1.mp3", "Music/Song 1.video.mp4", "Music/Song 2.mp3", "Music/Song 2.video.webm"), paths(all, true))
        assertEquals(listOf("Music/Song 1.lrc", "Music/Song 1.mp3", "Music/Song 1.video.mp4", "Music/Song 2.mp3", "Music/Song 2.video.webm"), paths(Holds(spaces = setOf("Music")), true, true))
        assertEquals(listOf("Music/Song 1.lrc", "Music/Song 1.mp3", "Music/Song 2.mp3"), paths(all, true, false))
        // lyrics off, videos on: the videos stay and the lyrics go
        assertEquals(listOf("Music/Song 1.mp3", "Music/Song 1.video.mp4", "Music/Song 2.mp3", "Music/Song 2.video.webm"), paths(all, false, true))
        // both off: only the songs
        assertEquals(listOf("Music/Song 1.mp3", "Music/Song 2.mp3"), paths(all, false, false))
        // the Storage line counts the video records (not the lyrics), and a removed video stops counting
        assertEquals(10_000_000L, q.musicVideoBytes())
        reader.close()
        s.apply(listOf(draft("item.set", "item" to JStr(item(2)), "field" to JStr("companion:.video.webm"), "value" to app.keepfin.core.sync.JNull))).get(10, TimeUnit.SECONDS)
        val reader2 = IndexReader(state.statePath, { state.epoch }) { JdbcHost().open(it) }
        assertEquals(9_000_000L, CopiesSql(reader2::rows).musicVideoBytes())
        reader2.close()
    }

    @Test fun thePlaceReadsItsMusicVideoChoiceOrFollowsWhatItHolds() {
        fun config(holdsAll: Boolean, options: Map<String, app.keepfin.core.sync.Jv>?) = app.keepfin.core.copies.placeConfigFrom(
            "p", buildMap {
                put("mode", JStr("backup")); put("holds", JObj(mapOf("all" to app.keepfin.core.sync.JBool(holdsAll))))
                if (options != null) put("options", JObj(options))
            },
        )!!.options.musicVideos
        assertTrue(config(true, null))
        assertEquals(false, config(false, null))
        assertEquals(false, config(false, mapOf("companions" to app.keepfin.core.sync.JBool(true))))
        assertTrue(config(false, mapOf("musicVideos" to app.keepfin.core.sync.JBool(true))))
        assertEquals(false, config(true, mapOf("musicVideos" to app.keepfin.core.sync.JBool(false))))
    }

    @Test fun placeRegistersAreReadAsTheyWereSet() {
        val dir = File(root, "dev2")
        val state = SqlMergeState(File(dir, "state.db").path, library, replica, null, JdbcHost())
        val s = KeepfinStore.open(
            StoreOptions(folder = FakeFolderAccess(), libraryId = library, actor = actor, deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ }, flushIdleMs = 3_600_000, state = state),
        ).get(10, TimeUnit.SECONDS)
        store = s
        val place = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        s.apply(
            listOf(
                draft("place.set", "place" to JStr(place), "field" to JStr("mode"), "value" to JStr("backup")),
                draft("place.set", "place" to JStr(place), "field" to JStr("holds"), "value" to JObj(mapOf("all" to app.keepfin.core.sync.JBool(true)))),
            ),
        ).get(10, TimeUnit.SECONDS)
        val reader = IndexReader(state.statePath, { state.epoch }) { JdbcHost().open(it) }
        val regs = CopiesSql(reader::rows).placeRegisters().getValue(place)
        assertEquals("\"backup\"", regs["mode"])
        val config = app.keepfin.core.copies.placeConfigFrom(place, regs.mapValues { (decodeJv(it.value)) })
        assertEquals(app.keepfin.core.copies.CopyMode.Backup, config!!.mode)
        assertTrue(config.holds.all)
        reader.close()
    }

    private fun decodeJv(text: String) = (app.keepfin.core.sync.decodeCanonical(text) as app.keepfin.core.sync.CanonicalResult.Ok).value
}
