package app.keepfin.folder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepfinFolderTest {
    private val now = "2026-10-13T10:00:00Z"

    @Test fun createBuildsTheDesktopLayoutAndOpensCleanly() {
        val fake = FakeFolderAccess()
        val json = KeepfinFolders.create(fake, "lib1", "rep1", now)
        assertEquals(FolderJson(1, "lib1", "rep1", now), json)
        for (dir in listOf("Music", "Videos", "Downloads", ".keepfin/actors", ".keepfin/ops", ".keepfin/checkpoints", ".keepfin/art", ".keepfin/trash")) {
            assertTrue(dir, fake.stat(dir)?.isDirectory == true)
        }
        assertEquals(KeepfinFolders.README_TEXT, fake.read(".keepfin/README.txt").toString(Charsets.UTF_8))
        assertEquals(0, fake.read(".keepfin/.nomedia").size)
        assertEquals("{\n  \"format\": 1,\n  \"libraryId\": \"lib1\",\n  \"replicaId\": \"rep1\",\n  \"createdAt\": \"$now\"\n}", fake.read(".keepfin/folder.json").toString(Charsets.UTF_8))
        val opened = KeepfinFolders.open(fake) as OpenFolderResult.Ok
        assertEquals(json, opened.folder)
        assertEquals(false, opened.readOnly)
        assertTrue(runCatching { KeepfinFolders.create(fake, "lib1", "rep1", now) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun openRecognisesMissingCorruptAndNewerFormats() {
        val fake = FakeFolderAccess()
        assertEquals(OpenFolderResult.NotAKeepfinFolder, KeepfinFolders.open(fake))
        fake.replace(KeepfinFolders.FOLDER_JSON, "{oops".toByteArray())
        assertEquals(OpenFolderResult.Corrupt, KeepfinFolders.open(fake))
        fake.replace(KeepfinFolders.FOLDER_JSON, "{\"format\":0,\"libraryId\":\"a\",\"replicaId\":\"b\",\"createdAt\":\"c\"}".toByteArray())
        assertEquals(OpenFolderResult.Corrupt, KeepfinFolders.open(fake))
        fake.replace(KeepfinFolders.FOLDER_JSON, "{\"format\":1,\"libraryId\":\"\",\"replicaId\":\"b\",\"createdAt\":\"c\"}".toByteArray())
        assertEquals(OpenFolderResult.Corrupt, KeepfinFolders.open(fake))
        fake.replace(KeepfinFolders.FOLDER_JSON, "{\"format\":3,\"libraryId\":\"a\",\"replicaId\":\"b\",\"createdAt\":\"c\"}".toByteArray())
        val newer = KeepfinFolders.open(fake) as OpenFolderResult.Ok
        assertTrue(newer.readOnly)
        // a BOM is tolerated, as on the desktop
        fake.replace(KeepfinFolders.FOLDER_JSON, ("﻿" + KeepfinFolders.folderJsonText(FolderJson(1, "a", "b", "c"))).toByteArray())
        assertEquals(FolderJson(1, "a", "b", "c"), (KeepfinFolders.open(fake) as OpenFolderResult.Ok).folder)
    }

    @Test fun providerFailuresAreNeverReadAsNotAKeepfinFolder() {
        val fake = FakeFolderAccess()
        KeepfinFolders.create(fake, "lib1", "rep1", now)
        fake.failWith = FolderError.NeedsAccess("revoked")
        assertTrue(runCatching { KeepfinFolders.open(fake) }.exceptionOrNull() is FolderError.NeedsAccess)
        fake.failWith = FolderError.Unavailable("card out")
        assertTrue(runCatching { KeepfinFolders.open(fake) }.exceptionOrNull() is FolderError.Unavailable)
    }

    @Test fun aCutShortSwitchLeavesTheOldFileReadable() {
        val fake = FakeFolderAccess()
        val json = KeepfinFolders.create(fake, "lib1", "rep1", now)
        fake.files[".keepfin/folder.json.ab12cd34.old"] = fake.files.remove(KeepfinFolders.FOLDER_JSON)!!
        assertEquals(json, (KeepfinFolders.open(fake) as OpenFolderResult.Ok).folder)
    }

    @Test fun aCopiedFolderIsDetectedAndGetsItsOwnReplica() {
        val fake = FakeFolderAccess()
        val json = KeepfinFolders.create(fake, "lib1", "rep1", now)
        assertEquals(ReplicaCheck.Ok, KeepfinFolders.checkReplica(json, "tree://a", emptyMap()))
        assertEquals(ReplicaCheck.Ok, KeepfinFolders.checkReplica(json, "tree://a", mapOf("rep1" to "tree://a")))
        assertEquals(ReplicaCheck.Cloned, KeepfinFolders.checkReplica(json, "tree://b", mapOf("rep1" to "tree://a")))
        val updated = KeepfinFolders.reassignReplica(fake, json, "rep2", now)
        assertEquals("rep2", updated.replicaId)
        assertEquals(updated, (KeepfinFolders.open(fake) as OpenFolderResult.Ok).folder)
        assertTrue(fake.read(".keepfin/actors/clone-rep2.json").toString(Charsets.UTF_8).contains("\"previousReplicaId\": \"rep1\""))
    }
}
