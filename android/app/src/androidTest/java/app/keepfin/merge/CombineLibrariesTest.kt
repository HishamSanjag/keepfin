package app.keepfin.merge

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.bench.LocalFolderAccess
import app.keepfin.core.library.DiffContext
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibModel
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibraryMapping
import app.keepfin.core.library.RecordingObserved
import app.keepfin.core.net.DeviceSigner
import app.keepfin.core.net.NetAction
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.OpDraft
import app.keepfin.folder.OpenFolderResult
import app.keepfin.folder.Profile
import app.keepfin.folder.ProfileStore
import app.keepfin.folder.StoreOptions
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.sql.AndroidSqlHost
import app.keepfin.folder.sql.SqlMergeState
import app.keepfin.net.FileServingLibrary
import app.keepfin.net.NetStack
import app.keepfin.net.StoreNetLibrary
import app.keepfin.net.pairBySas
import app.keepfin.net.until
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone side of combining two libraries on the emulator: B (this phone) holds its own library S in a Keepfin folder with real files, A (a
 * second whole Devices stack) holds the main library T. Real key store, TLS, join, store, SQLite state and SAF-like folder access.
 */
@RunWith(AndroidJUnit4::class)
class CombineLibrariesTest {
    private lateinit var context: Context
    private val stacks = ArrayList<NetStack>()
    private val tId = UUID.randomUUID().toString()
    private val sId = UUID.randomUUID().toString()

    @Before fun setUp() { context = InstrumentationRegistry.getInstrumentation().targetContext }
    @After fun tearDown() { MergeFence.clear(); stacks.forEach { it.close() } }

    /** B's side of the app: what [AndroidMergeEnv] is on a phone, over the stack's service and a plain-file folder. */
    private inner class BEnv(val b: NetStack, val dir: File, val folder: FolderAccess) : MergeEnv {
        override val filesDir: File = File(dir, "files").also { it.mkdirs() }
        override val profiles = ProfileStore(File(filesDir, "profiles.json"))
        override val sqlHost = AndroidSqlHost()
        override val signer: DeviceSigner? = b.identity.signer
        var current: OpenLibrary? = null
        var nets: StoreNetLibrary? = null
        val remaps = ArrayList<IdMaps>()
        var reopens = 0
        var peerOverride: PeerPort? = null

        override fun folder(treeUri: String) = folder
        override fun currentProfile() = profiles.current()
        override fun offer(deviceId: String) = b.service.computeState().offers.firstOrNull { it.deviceId == deviceId }?.let { it.libraryId to it.name }
        override fun openLibrary(): OpenLibrary = current ?: throw IllegalStateException("closed")

        fun openFromPointer(register: Boolean = true) {
            current?.let { runCatching { it.store.close().get(30, TimeUnit.SECONDS) } }
            val opened = KeepfinFolders.open(folder) as OpenFolderResult.Ok
            val profile = profiles.current()!!
            val deviceDir = profiles.deviceDir(filesDir, profile.id)
            val st = SqlMergeState(File(deviceDir, "state/state.db").path, opened.folder.libraryId, opened.folder.replicaId, b.identity.deviceId, sqlHost)
            val store = KeepfinStore.open(
                StoreOptions(
                    folder = folder, libraryId = opened.folder.libraryId, actor = profile.actorId, deviceDir = deviceDir, outboxDir = File(deviceDir, "outbox"),
                    newId = { UUID.randomUUID().toString() }, state = st, flushIdleMs = 150, signer = signer, retryMs = 50, layout = opened.folder.layout,
                ),
            ).get(60, TimeUnit.SECONDS)
            current = OpenLibrary(store, st, opened.folder.replicaId, opened.folder.libraryId, profile.name, opened.folder.layout, deviceDir)
            if (register) {
                val lib = StoreNetLibrary(opened.folder.libraryId, profile.name, folder, store, st, opened.folder.replicaId, layout = opened.folder.layout)
                nets = lib
                b.libs += lib
                b.service.libraryOpened(lib)
            }
        }

        override fun join(journal: MergeJournal, progress: (String, Int) -> Unit, cancelled: () -> Boolean): JoinOutcome {
            val j = b.service.joinInto(journal.peerDeviceId, journal.tLibraryId, journal.tName, folder, app.keepfin.folder.FolderLayout.forLibrary(journal.tLibraryId), cancelled, progress)
            return JoinOutcome(j.replicaId, j.actor, j.name, j.hostActor)
        }

        override fun peer(journal: MergeJournal): PeerPort? = peerOverride ?: b.service.mergePeer(journal.peerDeviceId)
        override fun reopen() { reopens++; nets?.markClosed(); openFromPointer() }
        override fun remapOutside(maps: IdMaps) { remaps += maps }
        override fun refusal(): String? = null
        override fun copyRunning() = false
        override fun replayPlays(libraryId: String, plays: List<MergeFence.Play>, maps: IdMaps?) {}
    }

    private fun sha(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Media(val path: String, val bytes: ByteArray, val title: String)

    private fun drafts(actor: String, replica: String, id: String, title: String, place: String?, hash: String, size: Long): List<OpDraft> {
        val item = LibItem(id = id, space = "music", kind = "audio", title = title, addedAt = System.currentTimeMillis().toDouble(), place = place)
        val ctx = DiffContext(actor, replica, { UUID.randomUUID().toString() }, { 0L }, { emptyList() }, RecordingObserved())
        val model = LibModel(listOf(item), emptyList(), emptyList(), LibRoutes("music", "downloads"))
        val out = LibraryMapping.diff(null, model, ctx).filter { it.kind != "profile.set" }.map { OpDraft(it.kind, it.body) }.toMutableList()
        out.add(1, LibraryMapping.reviseDraft(id, hash, size, emptyList()).let { OpDraft(it.kind, it.body) })
        return out
    }

    private class World(val a: NetStack, val b: NetStack, val env: BEnv, val media: List<Media>, val hashes: Map<String, String>, val folderRoot: File)

    private fun world(): World {
        val a = NetStack("alpha", context, File(context.cacheDir, "cl-a-${UUID.randomUUID().toString().take(6)}")).also { stacks += it }
        val b = NetStack("beta", context, File(context.cacheDir, "cl-b-${UUID.randomUUID().toString().take(6)}")).also { stacks += it }
        val same = ByteArray(2048) { (it % 251).toByte() }
        val fresh = ByteArray(3000) { (it % 241).toByte() }
        // A: the main library T holds one song whose bytes B also has
        a.openLibrary(tId)
        val aStore = a.storeOf.getValue(tId)
        aStore.apply(drafts(aStore.actorId, "a-rep", UUID.randomUUID().toString(), "Shared song", null, sha(same), same.size.toLong())).get(30, TimeUnit.SECONDS)
        aStore.flush().get(30, TimeUnit.SECONDS)
        a.start(); b.start()
        // B: its own library S in a Keepfin folder with real files
        val folderRoot = File(b.root, "keepfin-folder")
        val folder = LocalFolderAccess(folderRoot)
        val env = BEnv(b, b.root, folder)
        val replica = UUID.randomUUID().toString()
        KeepfinFolders.create(folder, sId, replica, java.time.Instant.now().toString())
        val actor = UUID.randomUUID().toString()
        env.profiles.add(Profile("ps", "My phone", "file:///b", sId, actor, java.time.Instant.now().toString()))
        env.openFromPointer(register = false)
        val media = listOf(Media("Music/same.bin", same, "Same"), Media("Music/new.bin", fresh, "New"), Media("Music/new copy.bin", fresh, "New again"))
        val s = env.current!!
        for (m in media) {
            folder.mkdirs("Music")
            folder.publish(m.path, m.bytes)
            s.store.apply(drafts(actor, replica, UUID.randomUUID().toString(), m.title, m.path, sha(m.bytes), m.bytes.size.toLong())).get(30, TimeUnit.SECONDS)
        }
        s.store.flush().get(30, TimeUnit.SECONDS)
        val hashes = media.associate { it.path to sha(folder.read(it.path)) }
        pairBySas(b, a)
        a.service.shareLibrary(b.identity.deviceId, tId)
        until("B to be offered T", 30_000) { env.offer(a.identity.deviceId)?.first == tId }
        until("the link to be online", 30_000) { b.service.links[a.identity.deviceId]?.online == true }
        return World(a, b, env, media, hashes, folderRoot)
    }

    private fun pointer(w: World) = (KeepfinFolders.open(w.env.folder) as OpenFolderResult.Ok).folder

    private fun mediaHashes(w: World) = w.media.associate { it.path to sha(w.env.folder.read(it.path)) }

    private fun service(w: World) = MergeService(w.env)

    private fun combine(w: World, svc: MergeService): MergeJournal {
        val id = svc.combineStart(w.a.identity.deviceId, keepMine = false)
        assertTrue("the combine finished", svc.idle(180_000))
        val j = svc.journals.read(id)!!
        assertEquals(j.error ?: "no error", MergeStage.Active, j.stage)
        return j
    }

    @Test fun bCombinesIntoTAndASeesItsItemsWhileMediaStaysPut() {
        val w = world()
        val svc = service(w)
        val before = mediaHashes(w)
        assertEquals(null, svc.combinePreview(w.a.identity.deviceId).refusal)
        val j = combine(w, svc)
        // contract 1 and 6: the pointer names T's records root, folder.json stays in .keepfin, S's records are where they were
        val p = pointer(w)
        assertEquals(2, p.format)
        assertEquals(tId, p.libraryId)
        assertEquals("libraries/$tId", p.records)
        assertEquals(setOf("previous", "active"), p.libraries.map { it.role }.toSet())
        assertNotNull(w.env.folder.stat(".keepfin/ops"))
        assertNotNull(w.env.folder.stat(".keepfin/libraries/$tId/ops"))
        // the map: one line per S item, linked / added / kept-duplicate
        val map = PlanFiles.mapLines(File(svc.journals.workDir(j.mergeId), "map.jsonl")).toList()
        assertEquals(setOf("linked", "added", "kept-duplicate"), map.map { it.how.wire }.toSet())
        assertNotNull("the map travels with the folder", w.env.folder.stat(".keepfin/libraries/$tId/merge/${j.mergeId}/map.jsonl"))
        // media bytes and paths unchanged
        assertEquals(before, mediaHashes(w))
        assertEquals(w.hashes, mediaHashes(w))
        // the library is T now, the profile too, outside ids were asked to be remapped
        assertEquals(tId, w.env.openLibrary().libraryId)
        assertEquals(j.tProfileId, w.env.profiles.current()!!.id)
        assertEquals(1, w.env.remaps.size)
        assertFalse(MergeFence.downloadsWait)
        // A sees B's items (the added ones; the linked one is A's own song)
        until("A to see B's songs", 60_000) { w.a.views(tId).let { it.contains("\"New\"") && it.contains("\"New again\"") } }
        assertTrue(w.a.views(tId).contains("Shared song"))
        // B's placement was written for the linked item: B's T library reports a path for it
        val bViews = w.env.openLibrary().store.viewsJson().get(30, TimeUnit.SECONDS)
        assertTrue(bViews.contains("Music/same.bin"))
    }

    @Test fun killAndRestartAtActivatingFinishesFromEitherSideOfThePointerSwitch() {
        val w = world()
        val svc = service(w)
        val j = combine(w, svc)
        val fresh = svc.journals
        for (pointerAlreadyT in listOf(true, false)) {
            // the app died inside the activation: the journal says activating, nothing after it was done
            val s = KeepfinFolders.open(w.env.folder) as OpenFolderResult.Ok
            if (!pointerAlreadyT) {
                // the pointer is still S's (format 2 not yet written): put the old pointer back
                val old = s.folder.copy(format = 1, libraryId = j.sLibraryId, replicaId = j.sReplicaId, records = "", libraries = emptyList())
                w.env.folder.replace(KeepfinFolders.FOLDER_JSON, KeepfinFolders.folderJsonText(old).toByteArray())
            }
            w.env.profiles.setCurrent(j.sProfileId)
            w.env.profiles.remove(j.tProfileId, fallback = j.sProfileId)
            fresh.write(fresh.read(j.mergeId)!!.copy(stage = MergeStage.Activating, profileSwitched = false, outsideRemapped = false))
            MergeFence.clear()
            val restarted = MergeService(w.env, fresh)
            restarted.recoverBeforeOpen()
            val after = fresh.read(j.mergeId)!!
            assertEquals(after.error ?: "ok", MergeStage.Active, after.stage)
            assertEquals(j.tLibraryId, pointer(w).libraryId)
            assertEquals(j.tProfileId, w.env.profiles.current()!!.id)
            assertTrue(after.profileSwitched && after.outsideRemapped)
            assertFalse("the fences are lifted", MergeFence.downloadsWait)
            w.env.openFromPointer()
        }
    }

    @Test fun separateWithAReachableBringsTheOwnLibraryBackAndLeavesTheFiles() {
        val w = world()
        val svc = service(w)
        val before = mediaHashes(w)
        val j = combine(w, svc)
        until("A to see B's songs", 60_000) { w.a.views(tId).contains("\"New\"") }
        val preview = svc.separatePreview()
        assertEquals(2L, preview.brought)
        assertTrue(preview.peerReachable)
        // a song downloaded after combining comes back to the own library
        val folder = w.env.folder
        val extra = ByteArray(900) { (it % 199).toByte() }
        folder.publish("Music/after.bin", extra)
        val t = w.env.openLibrary()
        t.store.apply(drafts(t.store.actorId, t.replicaId, UUID.randomUUID().toString(), "After", "Music/after.bin", sha(extra), extra.size.toLong())).get(30, TimeUnit.SECONDS)
        t.store.flush().get(30, TimeUnit.SECONDS)
        svc.separate(keepOnPeer = true, anyway = false)
        assertTrue(svc.idle(180_000))
        val done = svc.journals.read(j.mergeId)!!
        assertEquals(done.error ?: "ok", MergeStage.Separated, done.stage)
        val p = pointer(w)
        assertEquals(sId, p.libraryId)
        assertEquals("", p.records)
        assertEquals("left", p.libraries.first { it.libraryId == tId }.role)
        assertEquals("My phone", w.env.openLibrary().name)
        assertEquals(sId, w.env.openLibrary().libraryId)
        // nothing under media changed, and the file added after combining is in the own library again
        assertEquals(before, mediaHashes(w))
        assertTrue(folder.stat("Music/after.bin") != null)
        val views = w.env.openLibrary().store.viewsJson().get(30, TimeUnit.SECONDS)
        assertTrue(views.contains("\"After\""))
        assertTrue(views.contains("\"Same\"") && views.contains("\"New\""))
        assertEquals(j.sProfileId, w.env.profiles.current()!!.id)
        assertFalse(MergeFence.downloadsWait)
        // A forgot what B alone brought (New, New again are B's own) and keeps its own
        until("A to drop B's songs", 60_000) { w.a.views(tId).let { v -> v.contains("Shared song") && v.contains("\"status\":\"deleted\"") } }
        // and A, which administers T, revoked the actor B had in it, with a cutoff (the request Windows sends too)
        until("A to revoke B's actor", 60_000) { w.a.libs.first { it.libraryId == tId }.membership().members[j.tActor!!]?.cutoff != null }
    }

    private fun awaitReview(svc: MergeService): CombineState {
        until("the review", 120_000) { svc.state.value.preview != null || svc.state.value.error != null }
        return svc.state.value
    }

    @Test fun theReviewIsADryRunWithNamesAndCombineUsesTheStagedCopy() {
        val w = world()
        val svc = service(w)
        val before = mediaHashes(w)
        assertEquals(null, svc.combinePreview(w.a.identity.deviceId).refusal)
        val review = awaitReview(svc)
        assertEquals(review.error ?: "no error", null, review.error)
        val p = review.preview!!
        // S holds Same (A has these bytes), New and New again (the same bytes twice): linked, added, kept twice
        assertEquals(listOf(3L, 1L, 1L, 1L, 0L), listOf(p.items, p.added, p.linked, p.keptDuplicates, p.missingFiles))
        assertEquals(1L, p.otherItems)
        assertEquals(false, review.started)
        assertTrue("a row for the song kept twice, with its name", p.review.any { it.kind == "duplicateInS" && it.title != null && it.title!!.startsWith("New") })
        assertTrue("rows carry names, never ids", p.review.all { r -> r.title != null && !Regex("^[0-9a-f]{8}-[0-9a-f]{4}-").containsMatchIn(r.title!!) })
        assertEquals(p.reviewTotal, p.review.size.toLong())
        // a dry run changes nothing: S is still in charge, no batch was written, the other library is only staged beside it
        assertEquals(sId, pointer(w).libraryId)
        assertNotNull(w.env.folder.stat(".keepfin/libraries/$tId"))
        val staged = svc.journals.unfinished()!!
        assertFalse(staged.started)
        assertFalse(File(svc.journals.workDir(staged.mergeId), "batches").exists())
        assertEquals(before, mediaHashes(w))
        // the review survives a restart of the app
        val restarted = MergeService(w.env, svc.journals)
        assertEquals(p.added, restarted.state.value.preview!!.added)
        // Combine reuses the staged copy: the same profile for the other library, no second join
        val id = svc.combineStart(w.a.identity.deviceId, keepMine = false)
        assertEquals(staged.mergeId, id)
        assertTrue(svc.idle(180_000))
        val j = svc.journals.read(id)!!
        assertEquals(j.error ?: "ok", MergeStage.Active, j.stage)
        assertEquals(staged.tProfileId, j.tProfileId)
        assertEquals(tId, pointer(w).libraryId)
    }

    @Test fun cancellingTheReviewLeavesTheFolderAsItWas() {
        val w = world()
        val svc = service(w)
        svc.combinePreview(w.a.identity.deviceId)
        awaitReview(svc)
        assertNotNull(w.env.folder.stat(".keepfin/libraries/$tId"))
        svc.combineCancel()
        assertEquals(null, w.env.folder.stat(".keepfin/libraries/$tId"))
        assertEquals(sId, pointer(w).libraryId)
        assertEquals(null, svc.journals.unfinished())
        assertEquals("idle", svc.state.value.stage)
        assertFalse(MergeFence.downloadsWait)
        // and a fresh review can be made afterwards
        assertEquals(null, svc.combinePreview(w.a.identity.deviceId).refusal)
        assertTrue(awaitReview(svc).preview != null)
    }

    @Test fun separateWhenTheMainDeviceIsGoneWaitsThenWorksAnywayWithoutDeletingAnything() {
        val w = world()
        val svc = service(w)
        val before = mediaHashes(w)
        val j = combine(w, svc)
        // the main device goes away
        w.a.service.close()
        until("the link to notice", 30_000) { w.b.service.links[w.a.identity.deviceId]?.online != true }
        svc.separate(keepOnPeer = true, anyway = false)
        assertTrue(svc.idle(120_000))
        val waiting = svc.journals.read(j.mergeId)!!
        assertEquals(MergeStage.Separating, waiting.stage)
        assertTrue(svc.state.value.peerUnreachable)
        assertEquals("still combined while it waits", tId, pointer(w).libraryId)
        svc.separate(keepOnPeer = true, anyway = true)
        assertTrue(svc.idle(180_000))
        val done = svc.journals.read(j.mergeId)!!
        assertEquals(done.error ?: "ok", MergeStage.Separated, done.stage)
        assertEquals(sId, pointer(w).libraryId)
        assertEquals(before, mediaHashes(w))
    }
}