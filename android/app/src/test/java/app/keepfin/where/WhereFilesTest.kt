package app.keepfin.where

import app.keepfin.copies.CopyCardUi
import app.keepfin.copies.CopyHoldsUi
import app.keepfin.copies.CopyProblemUi
import app.keepfin.copies.CopyProgressUi
import app.keepfin.copies.CopyStatus
import app.keepfin.copies.CloudCardUi
import app.keepfin.core.copies.CopyMode
import app.keepfin.core.sqlstate.SqlState
import app.keepfin.folder.sql.CopiesSql
import app.keepfin.folder.sql.JdbcHost
import app.keepfin.folder.sql.WhereHolding
import app.keepfin.folder.sql.WhereSingleRow
import app.keepfin.net.LastSyncUi
import app.keepfin.net.PairedDeviceUi
import app.keepfin.net.PairedLibraryUi
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words of Where your files are, the way they are put together, and the counting query at 200,000 items. */
class WhereFilesTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val spaces = listOf("s1" to "Music", "s2" to "Videos", "s3" to "Podcasts")
    private val playlists = listOf("p1" to "Road trip", "p2" to "Gym")

    @Test fun holdsInWords() {
        assertEquals("Everything", WhereWords.holds(CopyHoldsUi(all = true), spaces, playlists))
        assertEquals("Music, Favourites", WhereWords.holds(CopyHoldsUi(all = false, spaces = setOf("s1"), favourites = true), spaces, playlists))
        assertEquals("Road trip and 2 more", WhereWords.holds(CopyHoldsUi(false, emptySet(), setOf("p1", "p2", "p3"), false), spaces, playlists + ("p3" to "Study")))
        assertEquals("Music and 2 more", WhereWords.holds(CopyHoldsUi(false, setOf("s1"), setOf("p1", "p2"), false), spaces, playlists))
        assertEquals("Nothing yet", WhereWords.holds(CopyHoldsUi(false), spaces, playlists))
        assertEquals("Everything", WhereWords.heldOf(10, 10))
        assertEquals("4 of 10 files", WhereWords.heldOf(4, 10))
        assertEquals("Nothing yet", WhereWords.heldOf(0, 10))
    }

    @Test fun agoAndSingles() {
        assertEquals("just now", WhereWords.ago(now, now - 10_000))
        assertEquals("2 min ago", WhereWords.ago(now, now - 2 * min))
        assertEquals("1 h ago", WhereWords.ago(now, now - 61 * min))
        assertEquals("3 days ago", WhereWords.ago(now, now - 3 * 24 * 60 * min))
        assertEquals("12 videos are only on Desk PC", WhereWords.single(SingleKind.VIDEOS, 12, "Desk PC"))
        assertEquals("3 songs are only on this phone", WhereWords.single(SingleKind.SONGS, 3, "this phone"))
        assertEquals("1 song is only on this phone", WhereWords.single(SingleKind.SONGS, 1, "this phone"))
        assertEquals("1 other file is only on this phone", WhereWords.single(SingleKind.OTHER, 1, "this phone"))
        assertEquals("2,500 other files are only on this phone", WhereWords.single(SingleKind.OTHER, 2500, "this phone"))
    }

    private fun card(id: String, status: CopyStatus, name: String = "Card", verified: Long? = null, cloud: CloudCardUi? = null, waiting: Int = 0, progress: CopyProgressUi? = null) =
        CopyCardUi(id, name, CopyMode.Backup, CopyHoldsUi(all = true), "", status != CopyStatus.DISCONNECTED, status, lastVerifiedAt = verified, waiting = waiting, cloud = cloud, progress = progress)

    @Test fun aCopyKeepsItsWholeWaitingReason() {
        val reason = "Waiting for Wi-Fi: this phone is on mobile data, and Desk PC is not set to take files over mobile data."
        val words = "waiting for Wi-Fi: this phone is on mobile data, and Desk PC is not set to take files over mobile data"
        val cloud = CloudCardUi("x", "Drive", "me", false, false, reason)
        assertEquals(words, WhereWords.copyState(card("a", CopyStatus.WAITING, cloud = cloud), now))
        assertEquals(words, WhereWords.copyState(card("a", CopyStatus.DISCONNECTED, cloud = cloud), now))
        assertEquals("offline", WhereWords.copyState(card("a", CopyStatus.DISCONNECTED, cloud = CloudCardUi("x", "Drive", "me", false, false)), now))
    }

    @Test fun copyStates() {
        assertEquals("up to date 2 min ago", WhereWords.copyState(card("a", CopyStatus.UP_TO_DATE, verified = now - 2 * min), now))
        assertEquals("copying · 40 of 210", WhereWords.copyState(card("a", CopyStatus.SYNCING, progress = CopyProgressUi(40, 210, 0, 0, null)), now))
        assertEquals("waiting for Wi-Fi", WhereWords.copyState(card("a", CopyStatus.WAITING, cloud = CloudCardUi("x", "Drive", "me", false, false, "Waiting for Wi-Fi.")), now))
        assertEquals("last copied 3 days ago · not plugged in", WhereWords.copyState(card("a", CopyStatus.DISCONNECTED, verified = now - 3 * 24 * 60 * min), now))
        assertEquals("not plugged in", WhereWords.copyState(card("a", CopyStatus.DISCONNECTED), now))
        assertEquals("needs a decision", WhereWords.copyState(card("a", CopyStatus.REVIEW), now))
        assertEquals("needs a decision", WhereWords.copyState(card("a", CopyStatus.HELD), now))
    assertEquals("50 files not copied", WhereWords.copyState(card("a", CopyStatus.PROBLEM).copy(problem = CopyProblemUi(50, emptyList(), emptyList(), 0, "50 files not copied")), now))
        assertEquals("sign in again", WhereWords.copyState(card("a", CopyStatus.DISCONNECTED, cloud = CloudCardUi("x", "Drive", "me", true, false)), now))
    }

    private fun device(id: String, name: String, actor: String, online: Boolean = true, lastSync: Long? = now - 2 * min) = PairedDeviceUi(
        id, "fp", name, "windows", 1, now - 3 * 24 * 60 * min, online, listOf(PairedLibraryUi("lib", "Family", emptyList(), actor)), if (online) null else now - 3 * 24 * 60 * min, null,
        false, emptyList(), emptyList(), false, lastSync = lastSync?.let { LastSyncUi(it, 0, 0) },
    )

    private fun inputs(
        holdings: List<WhereHolding>, singles: List<WhereSingleRow> = emptyList(), copies: List<CopyCardUi> = emptyList(), devices: List<PairedDeviceUi> = emptyList(),
        held: Map<String, Pair<Long, Long>> = emptyMap(), total: Long = 100,
    ) = WhereInputs("me", "lib", total, holdings, singles, copies, held, devices, spaces, playlists, now)

    @Test fun placesAreThisPhoneThenDevicesThenCopies() {
        val ui = WhereCompose.build(
            inputs(
                holdings = listOf(WhereHolding("me", setOf("a-me"), 100, 5_000_000_000), WhereHolding("r2", setOf("a-pc"), 60, 3_000_000_000), WhereHolding("rx", setOf("unknown"), 5, 1)),
                copies = listOf(card("c1", CopyStatus.UP_TO_DATE, "Backup card", now - 2 * min), card("c2", CopyStatus.DISCONNECTED, "Google Drive", cloud = CloudCardUi("x", "Drive", "me", false, false))),
                devices = listOf(device("d1", "Desk PC", "a-pc")), held = mapOf("c1" to (100L to 4_000_000_000L)),
            ),
        )
        assertEquals(listOf("This phone", "Desk PC", "Backup card", "Google Drive"), ui.places.map { it.name })
        assertEquals(listOf(PlaceKind.THIS_DEVICE, PlaceKind.DEVICE, PlaceKind.DRIVE, PlaceKind.CLOUD), ui.places.map { it.kind })
        assertEquals("Everything", ui.places[0].holds)
        assertEquals("60 of 100 files", ui.places[1].holds)
        assertEquals("up to date 2 min ago", ui.places[1].state)
        assertEquals(WhereTap.Device("d1"), ui.places[1].tap)
        assertEquals(WhereTap.Copy("c1"), ui.places[2].tap)
        assertEquals("Everything", ui.places[2].holds)
        assertEquals("3.7 GB",app.keepfin.ui.Format.bytes(ui.places[2].bytes))
        assertFalse(ui.onePlaceOnly)
    }

    @Test fun anOfflineDeviceSaysWhenItWasSeen() {
        val ui = WhereCompose.build(inputs(holdings = listOf(WhereHolding("me", setOf("a-me"), 10, 1), WhereHolding("r2", setOf("a-pc"), 10, 1)), devices = listOf(device("d1", "Desk PC", "a-pc", online = false, lastSync = null)), total = 10))
        assertEquals("offline · last seen 3 days ago", ui.places[1].state)
    }

    @Test fun singlesAreOneLinePerKindAndPlace() {
        val ui = WhereCompose.build(
            inputs(
                holdings = listOf(WhereHolding("me", setOf("a-me"), 100, 1), WhereHolding("r2", setOf("a-pc"), 60, 1)),
                singles = listOf(
                    WhereSingleRow("me", "audio", "s1", 2), WhereSingleRow("me", "audio", "s3", 1), WhereSingleRow("r2", "video", "s2", 12), WhereSingleRow("me", "video", null, 0), WhereSingleRow("zz", "image", null, 4),
                ),
                devices = listOf(device("d1", "Desk PC", "a-pc")),
            ),
        )
        assertEquals(listOf("3 songs are only on this phone", "12 videos are only on Desk PC", "4 other files are only on another device"), ui.singles.map { it.sentence })
        assertEquals(setOf("s1", "s3"), ui.singles[0].spaceIds)
        assertTrue(ui.singles[0].onThisPhone)
        assertFalse(ui.singles[1].onThisPhone)
    }

    @Test fun onePlaceOnlyNeedsFilesOnThisPhone() {
        assertTrue(WhereCompose.build(inputs(listOf(WhereHolding("me", setOf("a-me"), 10, 1)), total = 10)).onePlaceOnly)
        assertFalse(WhereCompose.build(inputs(emptyList(), total = 0)).onePlaceOnly)
        assertEquals("Everything is on this phone only. Keep a copy on another device, a drive or the cloud.", WhereWords.ONE_PLACE)
    }

    @Test fun itemLine() {
        assertEquals("Only on this phone", WhereWords.itemLine(listOf(ItemPlace("this phone", false, true))))
        assertEquals("On: this phone, Desk PC (offline), Google Drive", WhereWords.itemLine(listOf(ItemPlace("this phone", false, true), ItemPlace("Desk PC", true), ItemPlace("Google Drive", false))))
        assertNull(WhereWords.itemLine(emptyList()))
    }

    // ---- the counting query ---------------------------------------------------------------------------------------------------------

    @Test fun countsAre200kItemsInBudget() {
        val dir = Files.createTempDirectory("keepfin-where").toFile()
        try {
            val db = JdbcHost().open(File(dir, "state.db").path)
            for (ddl in SqlState.ddl) db.exec(ddl)
            val n = 200_000
            db.exec("BEGIN")
            val item = db.prepare("INSERT INTO item_view (id, status, fields, heads, plays, conflict, dch) VALUES (?, ?, ?, ?, 0, 0, '')")
            val reg = db.prepare("INSERT INTO regs (scope, k1, k2, wall, counter, hactor, oactor, oseq, value) VALUES ('placement', ?, ?, 1, 0, 'h', ?, 1, ?)")
            val media = listOf("audio", "video", "image")
            for (i in 0 until n) {
                val id = "%08x-0000-4000-8000-000000000000".format(i)
                item.run(listOf(id, if (i % 50 == 0) "deleted" else "present", """{"media":"${media[i % 3]}","space":"s${i % 3}","title":"T$i"}""", """[{"op":"o$i","content":"c$i","size":${1000 + i}}]"""))
                reg.run(listOf(id, "me", "a-me", "\"Music/f$i.mp3\""))
                if (i % 3 != 0) reg.run(listOf(id, "pc", "a-pc", "\"Music/f$i.mp3\""))
                if (i % 10 == 0) reg.run(listOf(id, "nas", "a-nas", "\"x/f$i.mp3\""))
            }
            db.exec("COMMIT")
            val q = CopiesSql { sql, params -> db.prepare(sql).rows(params) }
            var t = System.nanoTime()
            val totals = q.presentTotals()
            val totalsMs = (System.nanoTime() - t) / 1_000_000
            t = System.nanoTime()
            val holdings = q.holdings()
            val holdingsMs = (System.nanoTime() - t) / 1_000_000
            t = System.nanoTime()
            val singles = q.singles()
            val singlesMs = (System.nanoTime() - t) / 1_000_000
            t = System.nanoTime()
            val after = WhereSql.singlesAfterCopies(q, "me", listOf(longArrayOf(WhereIds.hash("%08x-0000-4000-8000-000000000000".format(3))).sortedArray()))
            val copiesMs = (System.nanoTime() - t) / 1_000_000
            println("KF-WHERE items=$n totals=${totalsMs}ms holdings=${holdingsMs}ms singles=${singlesMs}ms singlesAfterCopies=${copiesMs}ms")
            val present = n - n / 50
            assertEquals(present.toLong(), totals.first)
            assertEquals(present.toLong(), holdings.first { it.replica == "me" }.items)
            // items held by me only: i % 3 == 0 and i % 10 != 0, not deleted
            val expected = (0 until n).count { it % 3 == 0 && it % 10 != 0 && it % 50 != 0 }.toLong()
            assertEquals(expected, singles.filter { it.replica == "me" }.sumOf { it.count })
            assertEquals(0L, singles.filter { it.replica != "me" }.sumOf { it.count })
            // item 3 is kept by a copy, so it is no longer alone
            assertEquals(expected - 1, after.filter { it.replica == "me" }.sumOf { it.count })
            assertTrue("totals ${totalsMs}ms", totalsMs < 2_000)
            assertTrue("holdings ${holdingsMs}ms", holdingsMs < 4_000)
            assertTrue("singles ${singlesMs}ms", singlesMs < 4_000)
            assertTrue("singles after copies ${copiesMs}ms", copiesMs < 8_000)
            db.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
