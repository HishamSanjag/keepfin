package app.keepfin.copies

import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.ActionKind
import app.keepfin.folder.FolderError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class CopyExecutorTest {
    private fun firstRun(mode: CopyMode = CopyMode.Backup): CopiesRig {
        val rig = CopiesRig(mode)
        rig.put("Music/a.mp3", "alpha")
        rig.put("Music/b.mp3", "bravo")
        rig.put("Videos/c.mp4", "charlie")
        val r = rig.run()
        assertEquals("done", r.status)
        return rig
    }

    @Test fun aNewRelationshipCopiesEverythingAndThenPlansNothing() {
        val rig = firstRun()
        assertEquals("alpha", rig.text(rig.copy, "Music/a.mp3"))
        assertEquals("charlie", rig.text(rig.copy, "Videos/c.mp4"))
        assertTrue(rig.planIsEmpty())
        assertTrue(rig.leftovers(rig.copy).isEmpty())
        // the generation moved once, and the coverage manifest is on both sides
        assertTrue(rig.copy.files.keys.any { it.startsWith(".keepfin/places/place1/manifest-") })
        assertTrue(rig.main.files.keys.any { it.startsWith(".keepfin/places/place1/manifest-") })
        assertNull(rig.copy.files[".keepfin/lock"])
    }

    @Test fun theFirstPlanIsAdditiveOnlyUntilTheWizardConfirms() {
        val rig = CopiesRig()
        rig.put("Music/a.mp3", "alpha")
        rig.copy.publish("Music/a.mp3", "different".toByteArray())
        val r = rig.run(dryRun = true, previewConfirmed = false)
        assertEquals("preview", r.status)
        assertTrue(r.additiveOnly)
        assertEquals("different", rig.text(rig.copy, "Music/a.mp3"))
        // a preview writes nothing and bumps nothing
        assertEquals(0L, Generations.read(rig.copy))
    }

    @Test fun backupKeepsEarlierVersionsWithACatalogue() {
        val rig = firstRun()
        rig.put("Music/a.mp3", "alpha v2")
        val r = rig.run()
        assertEquals("done", r.status)
        assertEquals("alpha v2", rig.text(rig.copy, "Music/a.mp3"))
        val versions = rig.versionFiles()
        assertEquals(1, versions.size)
        assertEquals("alpha", rig.copy.files[versions[0]]!!.toString(Charsets.UTF_8))
        val live = Versions.liveEntries(rig.copy, Store.VERSIONS)
        assertEquals(1, live.size)
        assertEquals("replaced", live[0].why)
        assertEquals("Music/a.mp3", live[0].path)
        assertTrue(rig.planIsEmpty())
    }

    @Test fun backupKeepsADeletedFileAndMirrorSendsItToTheTrash() {
        val backup = firstRun(CopyMode.Backup)
        backup.drop("Music/b.mp3")
        assertEquals("done", backup.run().status)
        assertEquals("bravo", backup.text(backup.copy, "Music/b.mp3"))
        val mirror = firstRun(CopyMode.Mirror)
        mirror.drop("Music/b.mp3")
        assertEquals("done", mirror.run().status)
        assertNull(mirror.copy.files["Music/b.mp3"])
        val trash = mirror.trashFiles()
        assertEquals(1, trash.size)
        assertEquals("bravo", mirror.copy.files[trash[0]]!!.toString(Charsets.UTF_8))
        assertEquals("deleted", Versions.liveEntries(mirror.copy, Store.TRASH).single().why)
    }

    @Test fun anIncompleteListingOfTheCopyNeverRemovesAnything() {
        val rig = firstRun(CopyMode.Mirror)
        rig.drop("Music/b.mp3")
        rig.copy.emptyListings = true
        val r = rig.run()
        rig.copy.emptyListings = false
        assertFalse(r.copy!!.complete)
        assertEquals("bravo", rig.text(rig.copy, "Music/b.mp3"))
        assertTrue(rig.trashFiles().isEmpty())
        // and the next complete listing does what the library asked
        assertEquals("done", rig.run().status)
        assertNull(rig.copy.files["Music/b.mp3"])
    }

    @Test fun aMissingFileOnMainIsAbsenceNotDeletion() {
        val rig = firstRun(CopyMode.Mirror)
        rig.main.files.remove("Music/a.mp3") // the file is gone from main, but the library still wants it
        val r = rig.run()
        assertEquals("done", r.status)
        assertEquals("alpha", rig.text(rig.copy, "Music/a.mp3"))
    }

    @Test fun aChangedDestinationSkipsTheActionAndLosesNothing() {
        val rig = firstRun()
        rig.put("Music/a.mp3", "alpha v2")
        val r = rig.run(fault = FaultHook { step, _, _ ->
            if (step == "staged") rig.write(rig.copy, "Music/a.mp3", "edited on the card")
        })
        // the precondition failed (nothing was touched), then the run planned again, saw the edit and kept it before replacing it
        assertTrue(r.exec!!.skipped.any { it.reason == "unexpected-destination" })
        assertEquals("alpha v2", rig.text(rig.copy, "Music/a.mp3"))
        assertTrue(rig.leftovers(rig.copy).isEmpty())
        assertTrue(rig.versionFiles().any { rig.copy.files[it]!!.toString(Charsets.UTF_8) == "edited on the card" })
        assertTrue(rig.planIsEmpty())
    }
    @Test fun aNameThatAppearsBeforePublishingIsNeverReplaced() {
        val rig = CopiesRig()
        rig.put("Music/a.mp3", "alpha")
        val r = rig.run(fault = FaultHook { step, _, _ ->
            if (step == "before-publish") rig.write(rig.copy, "Music/a.mp3", "someone else")
        })
        assertEquals("someone else", rig.text(rig.copy, "Music/a.mp3"))
        assertTrue(r.exec!!.skipped.any { it.reason == "destination-appeared" })
        assertTrue(rig.leftovers(rig.copy).isEmpty())
    }

    @Test fun aReturnedDifferentNameCountsAsAConflict() {
        // the provider answers a rename with another name when the target is taken: the folder layer maps that to NameConflict
        val rig = CopiesRig()
        rig.put("Music/a.mp3", "alpha")
        val failing = object : app.keepfin.folder.FolderAccess by rig.copy {
            override fun rename(path: String, newName: String) {
                if (path.contains(".kftmp")) throw FolderError.NameConflict(newName)
                rig.copy.rename(path, newName)
            }
        }
        val r = CopyRunner.run(
            RunConfig(rig.main, failing, rig.stateDir, "place1", CopyMode.Backup, "test", rig.desired.values.asSequence(), previewConfirmed = true, relistDelayMs = 0, parallelism = 1, runId = "r1", lockHeartbeatMs = 60_000),
        )
        assertTrue(r.exec!!.skipped.any { it.reason == "destination-appeared" })
        assertNull(rig.copy.files["Music/a.mp3"])
        assertTrue(rig.leftovers(rig.copy).isEmpty())
    }

    @Test fun aCrashAtEveryJournalStepRecoversWithoutLosingAVersion() {
        val steps = listOf("intent", "transfer", "staged", "preserve-before-move", "preserved", "before-publish", "published", "committed")
        for (step in steps) {
            val rig = firstRun()
            rig.put("Music/a.mp3", "alpha v2")
            val crashed = rig.run(fault = FaultHook { s, _, a -> if (s == step && a?.path == "Music/a.mp3") throw CrashError(s) })
            assertEquals("crash at $step", "crashed", crashed.status)
            val again = rig.run()
            assertEquals("recovery after $step: ${again.reason}", "done", again.status)
            assertEquals("after $step", "alpha v2", rig.text(rig.copy, "Music/a.mp3"))
            assertTrue("leftovers after $step: ${rig.leftovers(rig.copy)}", rig.leftovers(rig.copy).isEmpty())
            assertTrue("old bytes kept after $step", rig.versionFiles().any { rig.copy.files[it]!!.toString(Charsets.UTF_8) == "alpha" })
            assertTrue("idempotent after $step", rig.planIsEmpty())
            assertNull(rig.copy.files[".keepfin/lock"])
        }
    }

    @Test fun aCrashDuringAFirstCopyCleansTheStagedFile() {
        for (step in listOf("intent", "transfer", "staged", "before-publish", "published")) {
            val rig = CopiesRig()
            rig.put("Music/a.mp3", "alpha")
            val crashed = rig.run(fault = FaultHook { s, _, _ -> if (s == step) throw CrashError(s) })
            assertEquals("crashed", crashed.status)
            val again = rig.run()
            assertEquals("done", again.status)
            assertEquals("alpha", rig.text(rig.copy, "Music/a.mp3"))
            assertTrue(rig.leftovers(rig.copy).isEmpty())
            assertTrue(rig.planIsEmpty())
        }
    }

    @Test fun anEditOnTheCopyComesBackToTheLibraryInTwoWayMode() {
        val rig = firstRun(CopyMode.TwoWay)
        rig.write(rig.copy, "Music/a.mp3", "edited on the card")
        val r = rig.run()
        assertEquals("done", r.status)
        assertEquals(listOf("Music/a.mp3"), rig.host.revised)
        assertEquals("edited on the card", rig.text(rig.main, "Music/a.mp3"))
    }

    @Test fun aFileOnlyTheCopyHasBecomesANewItemInTwoWayMode() {
        val rig = firstRun(CopyMode.TwoWay)
        rig.copy.publish("Music/new.mp3", "fresh".toByteArray())
        val r = rig.run()
        assertEquals("done", r.status)
        assertEquals(listOf("Music/new.mp3"), rig.host.imported)
        assertEquals("fresh", rig.text(rig.main, "Music/new.mp3"))
    }

    @Test fun withoutALibraryHostImportsAreSkippedNotDone() {
        val rig = firstRun(CopyMode.TwoWay)
        rig.copy.publish("Music/new.mp3", "fresh".toByteArray())
        val r = rig.run(useHost = false)
        assertTrue(r.exec!!.skipped.any { it.reason == "needs-library" })
        assertNull(rig.main.files["Music/new.mp3"])
    }

    @Test fun aLargeChangeIsHeldUntilConfirmed() {
        val rig = CopiesRig(CopyMode.Mirror)
        for (i in 1..30) rig.put("Music/s$i.mp3", "song $i")
        assertEquals("done", rig.run().status)
        for (i in 1..25) rig.drop("Music/s$i.mp3")
        val held = rig.run()
        assertEquals("held", held.status)
        assertEquals("song 1", rig.text(rig.copy, "Music/s1.mp3"))
        assertEquals("done", rig.run(confirmHeld = true).status)
        assertNull(rig.copy.files["Music/s1.mp3"])
        assertEquals(25, rig.trashFiles().size)
    }

    @Test fun aRestoredDriveWithALowerGenerationSuspendsRemovals() {
        val rig = firstRun(CopyMode.Mirror)
        // the copy was restored from an older backup: its generation is below what this relationship committed
        val json = Generations.readFolderJson(rig.copy)!!
        json.put("generation", 0)
        rig.copy.replace(Generations.FOLDER_JSON, json.toString().toByteArray())
        rig.drop("Music/b.mp3")
        val r = rig.run()
        assertEquals("bravo", rig.text(rig.copy, "Music/b.mp3"))
        assertEquals(app.keepfin.core.copies.Generation.Lower, r.generation.state)
    }

    @Test fun aLockHeldByAnotherRunRefusesAndAStaleOneIsTakenOver() {
        val rig = firstRun()
        rig.copy.replace(CopyLock.FILE, """{"device":"other","run":"x","heartbeatAt":${System.currentTimeMillis()}}""".toByteArray())
        assertEquals("busy", rig.run().status)
        rig.copy.replace(CopyLock.FILE, """{"device":"other","run":"x","heartbeatAt":1}""".toByteArray())
        assertEquals("done", rig.run().status)
        assertNull(rig.copy.files[CopyLock.FILE])
    }

    @Test fun aVanishedCopyIsADisconnectNotAnAbsence() {
        val rig = firstRun(CopyMode.Mirror)
        rig.drop("Music/b.mp3")
        rig.copy.failWith = FolderError.Unavailable("card removed")
        val r = rig.run()
        rig.copy.failWith = null
        assertEquals("disconnected", r.status)
        assertEquals("bravo", rig.text(rig.copy, "Music/b.mp3"))
        assertEquals("done", rig.run().status)
    }

    @Test fun anInterruptedRunResumesWhenTheCardComesBack() {
        val rig = CopiesRig()
        for (i in 1..6) rig.put("Music/s$i.mp3", "song $i")
        var seen = 0
        val r = rig.run(fault = FaultHook { s, _, _ -> if (s == "staged" && ++seen == 3) rig.copy.failWith = FolderError.Unavailable("card removed") })
        rig.copy.failWith = null
        assertEquals("disconnected", r.status)
        assertEquals("done", rig.run().status)
        for (i in 1..6) assertEquals("song $i", rig.text(rig.copy, "Music/s$i.mp3"))
        assertTrue(rig.leftovers(rig.copy).isEmpty())
        assertTrue(rig.planIsEmpty())
    }

    @Test fun everyActionKindThePlannerEmitsIsHandled() {
        val rig = firstRun(CopyMode.TwoWay)
        // a conflict (both sides edited), a main edit, a copy deletion proposal and a new copy file in one run
        rig.put("Music/a.mp3", "main edit")
        rig.write(rig.copy, "Music/b.mp3", "copy edit")
        rig.copy.files.remove("Videos/c.mp4")
        rig.copy.publish("Music/only-copy.mp3", "x".toByteArray())
        val r = rig.run()
        assertEquals("done", r.status)
        val kinds = r.exec!!.byKind.keys
        assertTrue(kinds.toString(), ActionKind.CopyTo.name in kinds)
        assertNotNull(r.exec)
        assertEquals("main edit", rig.text(rig.copy, "Music/a.mp3"))
        assertEquals("copy edit", rig.text(rig.main, "Music/b.mp3"))
        assertEquals("charlie", rig.text(rig.copy, "Videos/c.mp4"))
    }
}
