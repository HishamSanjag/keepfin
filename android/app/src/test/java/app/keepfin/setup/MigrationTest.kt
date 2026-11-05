package app.keepfin.setup

import app.keepfin.LibraryCodec
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.LibrarySettings
import app.keepfin.core.library.LibCollection
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibSpace
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.library.repo.LibraryRepository
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeLegacy : LegacyFiles {
    val data = LinkedHashMap<String, ByteArray>()
    val foreign = HashSet<String>()
    var consentGranted = false
    val sidecarsOf = HashMap<String, List<LegacySidecar>>()
    override fun size(uri: String): Long? = data[uri]?.size?.toLong()
    override fun open(uri: String): InputStream = data[uri]?.inputStream() ?: throw FileNotFoundException(uri)
    override fun sidecars(rec: LibraryCodec.Rec): List<LegacySidecar> = sidecarsOf[rec.item.id].orEmpty()
    override fun delete(uri: String): DeleteOutcome = when {
        uri !in data -> DeleteOutcome.GONE
        uri in foreign && !consentGranted -> DeleteOutcome.NEEDS_CONSENT
        else -> { data.remove(uri); DeleteOutcome.DELETED }
    }
    override fun openArt(path: String): InputStream? = null
    val located = HashMap<String, app.keepfin.library.repo.LinkedLocator>()
    override fun locate(uri: String): app.keepfin.library.repo.LinkedLocator? = located[uri]
}

private class FakeTarget(override val folder: FakeFolderAccess) : MigrationTarget {
    var spaces: List<LibSpace> = emptyList()
    val items = LinkedHashMap<String, LibraryRepository.MigratedItem>()
    var collections: List<LibCollection> = emptyList()
    override fun layout(spaces: List<LibSpace>, routes: LibRoutes) { this.spaces = spaces }
    override fun items(batch: List<LibraryRepository.MigratedItem>): Int {
        var n = 0
        for (m in batch) if (m.item.id !in items) { items[m.item.id] = m; n++ }
        return n
    }
    override fun collections(list: List<LibCollection>) { collections = list }
    override fun uriFor(path: String): String? = "content://tree/doc/$path"
    /** (path, content id) -> the record an earlier setup into this folder made for that file. */
    val earlier = HashMap<Pair<String, String>, String>()
    override fun existingAt(path: String, contentId: String): String? = earlier[path to contentId]
}

class MigrationTest {
    private lateinit var dir: File
    private val legacy = FakeLegacy()
    private val folder = FakeFolderAccess()
    private val target = FakeTarget(folder)
    private var ids = 0

    @Before fun setUp() { dir = Files.createTempDirectory("kf-migration").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun uri(n: Int) = "content://media/external/audio/media/$n"

    private fun rec(n: Int, space: String = "music", folderName: String = "", owned: Boolean = true, bytes: Int = 50 + n): LibraryCodec.Rec {
        val body = ByteArray(bytes) { (it * n).toByte() }
        legacy.data[uri(n)] = body
        if (!owned) legacy.foreign += uri(n)
        return LibraryCodec.Rec(
            LibraryItem(uri(n), space, uri(n), folderName, LibraryItemKind.AUDIO, "Song $n", size = body.size.toLong(), modifiedAt = 1, addedAt = 1000L + n, favorite = n % 2 == 0, plays = n),
            owned = owned, name = "song$n.mp3",
        )
    }

    private fun inventory(recs: List<LibraryCodec.Rec>, withPlaylist: Boolean = true): LegacyInventory {
        val collections = if (withPlaylist) listOf(app.keepfin.CollectionLogic.fromPlaylist("pl1", "Mix", recs.map { it.item.id }, 1, 2, "music")) else emptyList()
        return LegacyInventories.read(LibraryCodec.encodeFile(recs, collections), LibrarySettings())
    }

    private fun plan(inv: LegacyInventory, choices: Map<String, Choice> = inv.spaces.associate { it.space.id to Choice.MOVE }) =
        Planner.plan(inv, choices, "run1", 5L) { "00000000-0000-4000-8000-%012d".format(++ids) }

    private fun runner(plan: MigrationPlan, inv: LegacyInventory, journal: MigrationJournal, consentBatch: Int = 1000, stop: () -> Boolean = { false }) =
        MigrationRunner(plan, inv, legacy, target, journal, shouldStop = stop, commitEvery = 3, consentBatch = consentBatch)

    private fun journal(crash: CrashPlan? = null) = MigrationJournal.open(File(dir, "run1"), crash)

    @Test fun leftoverStagingFilesOfThePlanAreSweptAndNothingElse() {
        val recs = (1..3).map { rec(it) }
        val inv = inventory(recs)
        val p = plan(inv)
        val stale = p.items[0].dest + ".2340a7a3.tmp"
        val active = p.items[1].dest + ".0badc0de.tmp"
        val unrelated = "Music/notes.1a2b3c4d.tmp"
        val elsewhere = "Other/song1.mp3.2340a7a3.tmp"
        folder.mkdirs("Music"); folder.mkdirs("Other")
        for (n in listOf(stale, active, unrelated, elsewhere)) folder.files[n] = ByteArray(5)
        val r = runner(p, inv, journal())
        assertEquals(1, r.sweepStaleStaging(setOf(active)))
        assertFalse(stale in folder.files)
        assertTrue(active in folder.files); assertTrue(unrelated in folder.files); assertTrue(elsewhere in folder.files)
        // a whole run (it sweeps when it starts and when it finishes) removes the one that is no longer active, never the others
        assertTrue(runner(p, inv, journal()).run() is RunOutcome.Completed)
        assertFalse(active in folder.files)
        assertTrue(unrelated in folder.files); assertTrue(elsewhere in folder.files)
        for (pi in p.items) assertTrue(pi.dest in folder.files)
    }

    @Test fun aStagingFileLeftAfterAnInterruptedRunIsGoneWhenTheRunResumes() {
        val recs = (1..3).map { rec(it) }
        val inv = inventory(recs)
        val p = plan(inv)
        val j = journal()
        var stops = 0
        assertTrue(runner(p, inv, j, stop = { ++stops > 4 }).run() is RunOutcome.Paused)
        val stale = p.items[2].dest + ".deadbeef.tmp"
        folder.mkdirs(app.keepfin.folder.parentOf(stale)); folder.files[stale] = ByteArray(9)
        assertTrue(runner(p, inv, j).run() is RunOutcome.Completed)
        assertFalse(stale in folder.files)
    }

    @Test fun plannerPicksMoveWhileItFitsAndNamesAreUnique() {
        val inv = inventory(listOf(rec(1), rec(2, space = "videos")))
        val tight = Planner.defaultChoices(inv, Planner.MARGIN_BYTES + 60)
        assertEquals(Choice.MOVE, tight["music"]); assertEquals(Choice.LEAVE, tight["videos"])
        assertEquals(Choice.MOVE, Planner.defaultChoices(inv, null)["videos"])
        val p = plan(inventory(listOf(rec(3), rec(4).let { it.copy(item = it.item.copy(id = uri(5)), name = "song3.mp3") })))
        assertEquals(2, p.items.map { it.dest.lowercase() }.toSet().size)
        assertEquals("Music/song3.mp3", p.items[0].dest)
    }

    @Test fun moveCopiesCommitsAndRemovesOriginals() {
        val recs = (1..7).map { rec(it) }
        val inv = inventory(recs)
        val p = plan(inv)
        val out = runner(p, inv, journal()).run()
        assertTrue(out is RunOutcome.Completed)
        assertEquals(7, target.items.size)
        for (pi in p.items) {
            assertTrue(pi.dest in folder.files)
            assertEquals("sha256:" + MessageDigest.getInstance("SHA-256").digest(folder.files.getValue(pi.dest)).joinToString("") { "%02x".format(it) }, target.items.getValue(pi.newId).contentId)
        }
        assertTrue(legacy.data.isEmpty())
        val report = (out as RunOutcome.Completed).report
        assertEquals(7, report.moved)
        assertEquals(1, target.collections.size)
        assertEquals(7, target.collections[0].itemIds.size)
        assertTrue(target.items.values.any { it.item.favorite })
    }

    private fun sha(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun aFileAnEarlierSetupAlreadyPutInTheFolderIsUsedAndItsRecordKept() {
        val recs = (1..3).map { rec(it) }
        val inv = inventory(recs)
        val p = plan(inv)
        // an earlier setup into the same folder copied song 1 and recorded it as "earlier-1"
        val first = p.items[0]
        val bytes = legacy.data.getValue(first.oldId)
        folder.mkdirs(app.keepfin.folder.parentOf(first.dest)); folder.files[first.dest] = bytes.copyOf()
        target.items["earlier-1"] = LibraryRepository.MigratedItem(MigrationMapping.libItem(recs[0], first, first.dest).copy(id = "earlier-1"), sha(bytes), bytes.size.toLong(), null)
        target.earlier[first.dest to sha(bytes)] = "earlier-1"
        assertTrue(runner(p, inv, journal()).run() is RunOutcome.Completed)
        // no "song1 (2).mp3", no second record, and the playlist points at the record that was already there
        assertEquals(listOf(first.dest), folder.files.keys.filter { it.startsWith(app.keepfin.folder.parentOf(first.dest)) && "song1" in it })
        assertFalse(first.newId in target.items)
        assertEquals(3, target.items.size)
        assertTrue("earlier-1" in target.collections.single().itemIds)
        assertFalse(first.newId in target.collections.single().itemIds)
        assertFalse(first.oldId in legacy.data) // the original is still tidied away
    }

    @Test fun anIdenticalFileWithoutARecordIsUsedInsteadOfASecondCopy() {
        val recs = (1..2).map { rec(it) }
        val inv = inventory(recs)
        val p = plan(inv)
        val first = p.items[0]
        folder.mkdirs(app.keepfin.folder.parentOf(first.dest)); folder.files[first.dest] = legacy.data.getValue(first.oldId).copyOf()
        assertTrue(runner(p, inv, journal()).run() is RunOutcome.Completed)
        assertEquals(1, folder.files.keys.count { "song1" in it })
        assertEquals(first.dest, target.items.getValue(first.newId).item.place)
    }

    @Test fun aDifferentFileAtTheNameStillGetsANumberedCopy() {
        val recs = listOf(rec(1))
        val inv = inventory(recs)
        val p = plan(inv)
        val first = p.items[0]
        folder.mkdirs(app.keepfin.folder.parentOf(first.dest)); folder.files[first.dest] = ByteArray(10) { 7 }
        assertTrue(runner(p, inv, journal()).run() is RunOutcome.Completed)
        assertEquals(2, folder.files.keys.count { "song1" in it })
        assertTrue(target.items.getValue(first.newId).item.place!!.endsWith("song1 (2).mp3"))
    }

    @Test fun everyMovedFileTellsTheHistoryWhereItWentBeforeTheOriginalIsRemoved() {
        val recs = (1..4).map { rec(it) }
        val inv = inventory(recs)
        val p = plan(inv)
        val moves = ArrayList<Triple<String, String, String>>()
        val seenOriginal = ArrayList<Boolean>()
        val r = MigrationRunner(p, inv, legacy, target, journal(), commitEvery = 3, relocated = { old, new, path -> moves += Triple(old, new, path); seenOriginal += (old in legacy.data) })
        assertTrue(r.run() is RunOutcome.Completed)
        // every file was reported, to the Keepfin folder, and the first report came while the original was still there
        assertEquals(recs.map { it.item.id }.toSet(), moves.map { it.first }.toSet())
        for ((old, new, path) in moves) { assertEquals("content://tree/doc/$path", new); assertEquals(p.items.first { it.oldId == old }.dest, path) }
        assertTrue(seenOriginal.first())
    }

    @Test fun leaveSpacesReportNothingToTheHistory() {
        val inv = inventory(listOf(rec(1)))
        val moves = ArrayList<String>()
        val r = MigrationRunner(plan(inv, inv.spaces.associate { it.space.id to Choice.LEAVE }), inv, legacy, target, journal(), relocated = { old, _, _ -> moves += old })
        assertTrue(r.run() is RunOutcome.Completed)
        assertTrue(moves.isEmpty())
    }

    @Test fun aStopAtEveryJournalWriteLosesNothingAndDuplicatesNothing() {
        val recs = (1..5).map { rec(it) }
        val inv = inventory(recs)
        val p = plan(inv)
        // count the writes of a clean run on throwaway state
        val probeJournal = MigrationJournal.open(File(dir, "probe"))
        runner(p, inv, probeJournal).run()
        val total = probeJournal.writes
        val originals = HashMap(legacy.data.mapValues { it.value }) // empty now; rebuild per round
        assertTrue(total > 10)
        for (variant in listOf("after", "before")) for (n in 1..total) {
            legacy.data.clear(); folder.files.clear(); target.items.clear(); target.collections = emptyList()
            File(dir, "run1").deleteRecursively()
            recs.forEach { rec(it.item.id.substringAfterLast('/').toInt()) }
            val crash = if (variant == "after") CrashPlan(after = n) else CrashPlan(before = n)
            try {
                runner(p, inv, journal(crash)).run()
            } catch (e: InjectedCrash) { /* the process died here */ }
            // the next start: a new journal over the same files
            var out = runner(p, inv, journal()).run()
            if (out is RunOutcome.NeedsConsent) out = runner(p, inv, journal()).run()
            assertTrue("$variant $n: $out", out is RunOutcome.Completed)
            assertEquals("$variant $n", 5, target.items.size)
            val media = folder.files.keys.filter { it.endsWith(".mp3") }
            assertEquals("$variant $n: copies", 5, media.size)
            assertEquals("$variant $n: originals", 0, legacy.data.size)
            assertEquals("$variant $n", p.items.map { it.dest }.toSet(), media.toSet())
        }
        assertNotNull(originals)
    }

    @Test fun refusedConsentLeavesOriginalsAndListsThem() {
        val recs = (1..4).map { rec(it, owned = it > 2) }
        val inv = inventory(recs)
        val p = plan(inv)
        val j = journal()
        val r = runner(p, inv, j)
        val first = r.run()
        assertTrue(first is RunOutcome.NeedsConsent)
        val batch = (first as RunOutcome.NeedsConsent).batches.single()
        assertEquals(2, batch.ids.size)
        assertEquals(2, legacy.data.size)
        val n = r.beginConsent(batch.ids)
        r.consentResult(n, granted = false)
        val done = r.run()
        assertTrue(done is RunOutcome.Completed)
        val report = (done as RunOutcome.Completed).report
        assertEquals(2, report.kept.size)
        assertEquals(2, legacy.data.size)
        assertEquals(4, folder.files.keys.count { it.endsWith(".mp3") })
        assertEquals(4, target.items.size)
    }

    @Test fun grantedConsentRemovesTheOriginals() {
        val recs = (1..3).map { rec(it, owned = false) }
        val inv = inventory(recs)
        val p = plan(inv)
        val r = runner(p, inv, journal())
        val first = r.run() as RunOutcome.NeedsConsent
        legacy.consentGranted = true
        val n = r.beginConsent(first.batches.single().ids)
        r.consentResult(n, granted = true)
        assertTrue(r.run() is RunOutcome.Completed)
        assertTrue(legacy.data.isEmpty())
    }

    @Test fun consentComesInBatchesAndAKilledRequestIsReconciled() {
        val recs = (1..5).map { rec(it, owned = false) }
        val inv = inventory(recs)
        val p = plan(inv)
        val first = runner(p, inv, journal(), consentBatch = 2).run() as RunOutcome.NeedsConsent
        assertEquals(listOf(2, 2, 1), first.batches.map { it.ids.size })
        // the request was launched, the user said yes, and the process died before the answer was journaled
        val r1 = runner(p, inv, journal(), consentBatch = 2)
        r1.beginConsent(first.batches[0].ids)
        legacy.consentGranted = true
        first.batches[0].ids.forEach { legacy.data.remove(it) }
        val again = runner(p, inv, journal(), consentBatch = 2).run() as RunOutcome.NeedsConsent
        assertEquals(3, again.batches.sumOf { it.ids.size })
    }

    @Test fun leaveRecordsWhereEachFileIsOnThisPhone() {
        val recs = listOf(rec(1), rec(2, owned = false), rec(3))
        legacy.located[uri(1)] = app.keepfin.library.repo.LinkedLocator(uri(1), "Music/Keepfin Music/song1.mp3", 51, 9)
        legacy.located[uri(2)] = app.keepfin.library.repo.LinkedLocator(uri(2), "Music/Keepfin Music/song2.mp3", 52, 9)
        val inv = inventory(recs)
        val p = plan(inv, mapOf("music" to Choice.LEAVE, "videos" to Choice.MOVE, "downloads" to Choice.MOVE))
        runner(p, inv, journal()).run()
        val byOld = p.items.associate { it.oldId to target.items.getValue(it.newId) }
        // what MediaStore says, plus whether this install owns the file (changing a foreign one asks first)
        assertEquals(app.keepfin.library.repo.LinkedLocator(uri(1), "Music/Keepfin Music/song1.mp3", 51, 9, owned = true), byOld.getValue(uri(1)).locator)
        assertEquals(false, byOld.getValue(uri(2)).locator!!.owned)
        // nothing known from MediaStore: the earlier library's address and size are kept, so the file still plays
        val fallback = byOld.getValue(uri(3)).locator!!
        assertEquals(uri(3), fallback.uri)
        assertEquals(53L, fallback.size)
        assertEquals("", fallback.path)
    }

    @Test fun movedItemsCarryNoLocator() {
        val recs = (1..2).map { rec(it) }
        val inv = inventory(recs)
        runner(plan(inv), inv, journal()).run()
        assertTrue(target.items.values.all { it.locator == null })
    }

    @Test fun leaveLinksTheSpaceAndTouchesNoFile() {
        val recs = (1..3).map { rec(it) }
        val inv = inventory(recs)
        val p = plan(inv, mapOf("music" to Choice.LEAVE, "videos" to Choice.MOVE, "downloads" to Choice.MOVE))
        val out = runner(p, inv, journal()).run()
        assertTrue(out is RunOutcome.Completed)
        assertEquals(3, legacy.data.size)
        assertTrue(folder.files.keys.none { it.endsWith(".mp3") })
        assertTrue(target.items.values.all { it.item.place!!.startsWith("linked:music/") && it.contentId == null })
        assertTrue(target.spaces.first { it.id == "music" }.linked)
        assertFalse(target.spaces.first { it.id == "videos" }.linked)
        assertEquals(3, (out as RunOutcome.Completed).report.linked)
    }

    @Test fun aMissingFileFailsAloneAndIsReported() {
        val recs = (1..3).map { rec(it) }
        legacy.data.remove(uri(2))
        val inv = inventory(recs)
        val out = runner(plan(inv), inv, journal()).run() as RunOutcome.Completed
        assertEquals(2, out.report.moved)
        assertEquals(1, out.report.failed.size)
        assertEquals(2, target.items.size)
        assertEquals(2, target.collections[0].itemIds.size)
    }

    @Test fun aFileThatChangedAfterTheCopyIsNotRemoved() {
        val recs = (1..2).map { rec(it) }
        val inv = inventory(recs)
        val p = plan(inv)
        val changing = object : LegacyFiles by legacy {
            override fun size(uri: String): Long? = legacy.size(uri)?.let { if (uri == uri(1) && folder.files.isNotEmpty() && target.items.isNotEmpty()) it + 1 else it }
        }
        val out = MigrationRunner(p, inv, changing, target, journal(), commitEvery = 3).run() as RunOutcome.Completed
        assertEquals(1, out.report.kept.size)
        assertTrue(uri(1) in legacy.data)
        assertFalse(uri(2) in legacy.data)
    }

    @Test fun aFullFolderStopsTheRunAndItContinues() {
        val recs = (1..4).map { rec(it) }
        val inv = inventory(recs)
        val p = plan(inv)
        var calls = 0
        val full = object : app.keepfin.folder.FolderAccess by folder {
            override fun publishFrom(path: String, source: InputStream, onProgress: ((Long) -> Unit)?) =
                if (++calls == 3) throw app.keepfin.folder.FolderError.DiskFull("full") else folder.publishFrom(path, source, onProgress)
        }
        val t2 = object : MigrationTarget by target { override val folder = full }
        val j = journal()
        val out = MigrationRunner(p, inv, legacy, t2, j, commitEvery = 3).run()
        assertTrue(out is RunOutcome.Stopped && out.reason == StopReason.NO_SPACE)
        val resumed = runner(p, inv, journal()).run()
        assertTrue(resumed is RunOutcome.Completed)
        assertEquals(4, target.items.size)
        assertTrue(legacy.data.isEmpty())
    }

    @Test fun sidecarsTravelAndAreRemovedWithTheOriginal() {
        val r = rec(1)
        legacy.data["content://files/9"] = "WEBVTT".toByteArray()
        legacy.sidecarsOf[r.item.id] = listOf(LegacySidecar("content://files/9", ".en.vtt"))
        val inv = inventory(listOf(r))
        val p = plan(inv)
        val out = runner(p, inv, journal()).run() as RunOutcome.Completed
        assertEquals(1, out.report.sidecarsCopied)
        assertTrue(folder.files.containsKey("Music/song1.en.vtt"))
        assertTrue(legacy.data.isEmpty())
    }

    @Test fun stopRequestPausesAndTheJournalResumes() {
        val recs = (1..6).map { rec(it) }
        val inv = inventory(recs)
        val p = plan(inv)
        var polls = 0
        val first = runner(p, inv, journal(), stop = { ++polls > 4 }).run()
        assertTrue(first is RunOutcome.Paused)
        assertTrue(target.items.size < 6)
        val rest = runner(p, inv, journal()).run()
        assertTrue(rest is RunOutcome.Completed)
        assertEquals(6, target.items.size)
        assertEquals(6, folder.files.keys.count { it.endsWith(".mp3") })
    }

    @Test fun aTornJournalLineIsIgnored() {
        val d = File(dir, "torn").also { it.mkdirs() }
        File(d, "journal.jsonl").writeText("{\"k\":\"run\",\"event\":\"started\"}\n{\"k\":\"file\",\"id\":\"a\",\"sta")
        val j = MigrationJournal.open(d)
        assertTrue(j.fold.torn)
        assertTrue(j.fold.run.started)
        j.run("layout")
        assertTrue(MigrationJournal.fold(File(d, "journal.jsonl").readText()).run.layout)
    }

    @Test fun planSurvivesItsJson() {
        val inv = inventory(listOf(rec(1), rec(2, space = "videos")))
        val p = plan(inv)
        val back = MigrationPlan.fromJson(p.toJson())
        assertEquals(p.items, back.items)
        assertEquals(p.spaces, back.spaces)
        assertEquals(p.collectionIds, back.collectionIds)
    }

    @Test fun placesAreClassified() {
        assertTrue(FolderPlaces.forbidden("primary:"))
        assertTrue(FolderPlaces.forbidden("primary:Download"))
        assertTrue(FolderPlaces.forbidden("primary:Android/data"))
        assertFalse(FolderPlaces.forbidden("primary:Keepfin"))
        assertFalse(FolderPlaces.forbidden("1234-ABCD:Keepfin/Music"))
        assertEquals("Internal storage > Keepfin", FolderPlaces.label("primary:Keepfin"))
        assertEquals("Memory card > Keepfin", FolderPlaces.label("1234-ABCD:Keepfin"))
    }
}
