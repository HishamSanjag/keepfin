package app.keepfin.net

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.bench.LocalFolderAccess
import app.keepfin.copies.Generations
import app.keepfin.copies.FaultHook
import app.keepfin.copies.ImportOutcome
import app.keepfin.copies.LocalDirAccess
import app.keepfin.copies.MainHost
import app.keepfin.copies.PeerFolderAccess
import app.keepfin.copies.RunConfig
import app.keepfin.copies.RunReport
import app.keepfin.copies.CopyRunner
import app.keepfin.core.copies.Action
import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.DesiredEntry
import app.keepfin.core.net.NetAction
import app.keepfin.folder.FolderAccess
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A copy kept on a paired device, between two whole in-process Android stacks (real key store, TLS 1.3 with pinned keys, the real HTTP
 * layer and the real peer-side executor): the copies engine runs straight over the transport with no local mirror. Backup with earlier
 * versions, a two-way edit coming back, a mirror removal going to the other device's trash, a replacement that never overwrites a file it
 * has not seen, and the device going away in the middle of a run and coming back.
 */
@RunWith(AndroidJUnit4::class)
class PeerCopyTest {
    private lateinit var context: Context
    private val library = UUID.randomUUID().toString()
    private val stacks = ArrayList<NetStack>()
    private lateinit var phone: NetStack
    private lateinit var device: NetStack
    private lateinit var main: FolderAccess
    private lateinit var deviceFolder: FolderAccess
    private lateinit var work: File
    private lateinit var meta: LocalDirAccess
    private lateinit var access: PeerFolderAccess
    private lateinit var transport: PeerCopyTransport
    private val desired = LinkedHashMap<String, DesiredEntry>()
    private val deleted = HashSet<String>()
    private var runNo = 0

    /** Set by a test to make the other device unreachable (as if it left the network). */
    @Volatile private var away = false

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        diagnostics = { stacks.joinToString("\n") { it.dump() } }
        work = File(context.cacheDir, "peercopy-${UUID.randomUUID().toString().take(6)}").also { it.mkdirs() }
        phone = NetStack("phone", context, File(work, "phone")).also { stacks += it }
        device = NetStack("device", context, File(work, "device")).also { stacks += it }
        main = LocalFolderAccess(File(work, "main-folder"))
        deviceFolder = LocalFolderAccess(File(work, "device-folder"))
        phone.openLibrary(library, folder = main)
        device.openLibrary(library, folder = deviceFolder)
        phone.start()
        device.start()
        pairBySas(phone, device)
        grant(device, phone, library, NetAction.MetadataRead, NetAction.MetadataWrite, NetAction.InventoryRead, NetAction.MediaRead, NetAction.CopyMutate)
        until("the phone to learn it may keep a copy on the device") {
            phone.service.links[device.identity.deviceId]?.lastHello?.libraries?.any { l -> "copy.mutate" in l.actions } == true
        }
        transport = PeerCopyTransport(
            clientOf = { if (away) null else phone.service.links[device.identity.deviceId]?.takeIf { it.online }?.withClient { it } },
            libraryId = library, device = "phone",
        )
        meta = LocalDirAccess(File(work, "copy-bookkeeping"))
        meta.mkdirs(".keepfin")
        meta.publish(".keepfin/folder.json", """{"format":1,"libraryId":"$library","replicaId":"copy-replica","createdAt":"2026-10-17T00:00:00Z","generation":0}""".toByteArray())
        access = PeerFolderAccess(transport, meta, File(work, "staging"))
    }

    @After fun tearDown() {
        stacks.forEach { it.close() }
        work.deleteRecursively()
    }

    private fun put(path: String, text: String) {
        val bytes = text.toByteArray()
        if (main.stat(path) != null) main.remove(path)
        val r = main.publish(path, bytes)
        desired[path] = DesiredEntry(path, "item-$path", "op$runNo", "sha256:${r.sha256}", bytes.size.toLong())
    }

    private fun drop(path: String) {
        desired.remove(path)?.let { deleted.add(it.itemId) }
        main.remove(path)
    }

    private fun text(a: FolderAccess, path: String) = if (a.stat(path) == null) null else a.read(path).toString(Charsets.UTF_8)

    private class Host(val main: FolderAccess) : MainHost {
        override fun importNew(action: Action, stagedPath: String, destPath: String): ImportOutcome {
            main.rename(stagedPath, destPath.substringAfterLast('/'))
            return ImportOutcome("new-item", null, destPath)
        }

        override fun reviseFromCopy(action: Action, stagedPath: String): String? {
            main.remove(action.path)
            main.rename(stagedPath, action.path.substringAfterLast('/'))
            return null
        }

        override fun restoreEditedDeleted(action: Action, stagedPath: String) = reviseFromCopy(action, stagedPath)
        override fun deleteItem(action: Action) {}
    }

    private fun run(mode: CopyMode, fault: FaultHook? = null, dry: Boolean = false): RunReport {
        runNo++
        return CopyRunner.run(
            RunConfig(
                main = main, copy = access, stateDir = File(work, "state"), placeId = "place", mode = mode, device = "phone", desired = desired.values.toList().asSequence(),
                deleted = deleted.toSet(), previewConfirmed = true, dryRun = dry, host = Host(main), fault = fault, parallelism = 2, runId = "r$runNo",
                lockHeartbeatMs = 1_000, mainReplica = "main-replica", copyReplica = "copy-replica",
            ),
        )
    }

    private fun why(r: RunReport) = "status=${r.status} reason=${r.reason} failed=${r.exec?.failed?.map { it.path + ":" + it.reason }} skipped=${r.exec?.skipped?.map { it.path + ":" + it.reason }} counts=${r.summary?.counts}"

    private fun trashed(): Map<String, String> = (runCatching { deviceFolder.list(".keepfin/trash/net").entries }.getOrDefault(emptyList()))
        .associate { it.name to deviceFolder.read(".keepfin/trash/net/${it.name}").toString(Charsets.UTF_8) }

    @Test fun aBackupKeepsEarlierVersionsInTheOtherDevicesTrash() {
        put("Music/a.wav", "alpha"); put("Music/b.wav", "bravo"); put("Videos/c.mp4", "charlie")
        val first = run(CopyMode.Backup)
        assertEquals(why(first), "done", first.status)
        assertEquals("alpha", text(deviceFolder, "Music/a.wav"))
        assertEquals("charlie", text(deviceFolder, "Videos/c.mp4"))
        // the other device's own executor and lock did the work: its generation moved, its lock is gone, no staged file is left
        assertTrue("generation", (Generations.read(deviceFolder) ?: 0L) >= 1L)
        assertNull(deviceFolder.stat(".keepfin/lock"))
        assertEquals(0, deviceFolder.list("Music").entries.count { it.name.contains("kftmp") })
        // the copy's own bookkeeping stayed on this phone: the device's .keepfin holds no copy data of ours
        assertNotNull(meta.stat(".keepfin/folder.json"))
        assertNull(deviceFolder.stat(".keepfin/places"))

        put("Music/a.wav", "alpha two")
        val second = run(CopyMode.Backup)
        assertEquals(why(second), "done", second.status)
        assertEquals("alpha two", text(deviceFolder, "Music/a.wav"))
        val kept = trashed()
        assertEquals(kept.toString(), 1, kept.size)
        assertTrue(kept.keys.single().endsWith("-a.wav"))
        assertEquals("alpha", kept.values.single())
        // nothing more to do
        assertEquals(0, run(CopyMode.Backup, dry = true).summary!!.counts.values.sum())
    }

    @Test fun anEditOnTheOtherDeviceComesBackInTwoWayMode() {
        put("Music/a.wav", "alpha"); put("Music/b.wav", "bravo")
        val first = run(CopyMode.TwoWay)
        assertEquals(why(first), "done", first.status)
        deviceFolder.remove("Music/a.wav")
        deviceFolder.publish("Music/a.wav", "edited on the other device".toByteArray())
        val r = run(CopyMode.TwoWay)
        assertEquals(why(r), "done", r.status)
        assertEquals("edited on the other device", text(main, "Music/a.wav"))
        assertEquals("edited on the other device", text(deviceFolder, "Music/a.wav"))
    }

    @Test fun aMirrorRemovalGoesToTheOtherDevicesTrash() {
        put("Music/a.wav", "alpha"); put("Music/b.wav", "bravo"); put("Videos/c.mp4", "charlie")
        assertEquals("done", run(CopyMode.Mirror).status)
        drop("Music/b.wav")
        val r = run(CopyMode.Mirror)
        assertEquals(why(r), "done", r.status)
        assertNull(deviceFolder.stat("Music/b.wav"))
        assertEquals("alpha", text(deviceFolder, "Music/a.wav"))
        val kept = trashed()
        assertEquals(kept.toString(), 1, kept.size)
        assertTrue(kept.keys.single().endsWith("-b.wav"))
        assertEquals("bravo", kept.values.single())
        assertNull(deviceFolder.stat(".keepfin/lock"))
    }

    @Test fun aReplacementNeverOverwritesAFileItHasNotSeen() {
        put("Music/a.wav", "alpha"); put("Music/b.wav", "bravo")
        assertEquals("done", run(CopyMode.Backup).status)
        put("Music/a.wav", "alpha two")
        // between planning and replacing, somebody edits the file on the other device
        var edited = false
        val r = run(CopyMode.Backup, fault = FaultHook { step, _, _ ->
            if (step == "before-publish" && !edited) {
                edited = true
                deviceFolder.remove("Music/a.wav")
                deviceFolder.publish("Music/a.wav", "edited meanwhile".toByteArray())
            }
        })
        assertTrue(why(r), edited)
        // the replacement was conditional on the content the phone had seen: it was refused, the run planned again with the edit in view
        assertTrue(why(r), r.exec!!.skipped.any { it.path == "Music/a.wav" && it.reason == "unexpected-destination" })
        // and whatever then replaced the edit kept it: the edited bytes are in the other device's trash, never lost
        assertTrue(trashed().toString(), trashed().values.contains("edited meanwhile"))
        assertEquals("alpha two", text(deviceFolder, "Music/a.wav"))
    }

    @Test fun theOtherDeviceGoingAwayMidRunIsAWaitAndTheRunResumes() {
        for (i in 1..8) put("Music/s$i.wav", "song $i ".repeat(20))
        var staged = 0
        val r = run(CopyMode.Backup, fault = FaultHook { s, _, _ -> if (s == "staged" && ++staged == 3) away = true })
        assertEquals(why(r), "disconnected", r.status)
        assertEquals(0, r.exec?.failedCount ?: 0)
        away = false
        val back = run(CopyMode.Backup)
        assertEquals(why(back), "done", back.status)
        for (i in 1..8) assertEquals("song $i ".repeat(20), text(deviceFolder, "Music/s$i.wav"))
        assertEquals(0, deviceFolder.list("Music").entries.count { it.name.contains("kftmp") })
        assertEquals(0, run(CopyMode.Backup, dry = true).summary!!.counts.values.sum())
        assertNull(deviceFolder.stat(".keepfin/lock"))
    }
}
