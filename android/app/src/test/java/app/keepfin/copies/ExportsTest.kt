package app.keepfin.copies

import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.Holds
import app.keepfin.core.copies.PlaceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Playlist files, the flat layout and MP3 versions over the fake copy folder (dev-docs/plans/KEEPFIN-COPIES-SPEC.md section 14). */
internal class ExportsTest {
    private class Counting : Mp3Converter {
        var calls = 0
        override fun convert(src: File, out: File, abort: () -> Boolean) {
            calls++
            out.writeBytes(("mp3:" + src.readText()).toByteArray())
        }
    }

    private fun rigWithSongs(): CopiesRig {
        val rig = CopiesRig(CopyMode.TwoWay)
        rig.put("Music/a.flac", "alpha", "ia")
        rig.put("Music/Sub/b.m4a", "bravo", "ib")
        rig.put("Videos/c.mp4", "charlie", "ic")
        assertEquals("done", rig.run().status)
        return rig
    }

    private val meta = mapOf("ia" to ExportItem("audio", "Alpha"), "ib" to ExportItem("audio", "Bravo"), "ic" to ExportItem("video", "Charlie"))

    private fun export(rig: CopiesRig, options: PlaceOptions, playlists: List<ExportPlaylist> = emptyList(), converter: Mp3Converter? = null): ExportsReport =
        Exports.sync(
            Exports.Job(
                rig.copy, NameMap.load(rig.copy), Holds(all = true), options, rig.desired.values.toList(),
                { id -> meta[id] ?: ExportItem("", id) }, { playlists }, converter, rig.stateDir.resolve("exports-tmp"),
            ),
        )

    @Test fun aPlaylistFileHasRelativePathsAndFollowsTheList() {
        val rig = rigWithSongs()
        val opts = PlaceOptions(playlistFiles = true)
        val r = export(rig, opts, listOf(ExportPlaylist("p1", "Road/Trip", listOf("ia", "ib"))))
        assertEquals(1, r.written)
        val files = rig.copy.files.keys.filter { it.endsWith(".m3u8") }
        assertEquals(1, files.size)
        val text = rig.copy.files[files[0]]!!.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("#EXTM3U\n#PLAYLIST:Road/Trip\n"))
        assertTrue(text.contains("#EXTINF:-1,Alpha\n../Music/a.flac\n"))
        assertTrue(text.contains("#EXTINF:-1,Bravo\n../Music/Sub/b.m4a\n"))
        assertTrue(files[0].startsWith("Playlists/"))
        // an unchanged list writes nothing the second time
        assertEquals(0, export(rig, opts, listOf(ExportPlaylist("p1", "Road/Trip", listOf("ia", "ib")))).written)
        // a changed list rewrites the file in place
        val r2 = export(rig, opts, listOf(ExportPlaylist("p1", "Road/Trip", listOf("ib"))))
        assertEquals(1, r2.written)
        val text2 = rig.copy.files[files[0]]!!.toString(Charsets.UTF_8)
        assertFalse(text2.contains("a.flac"))
        assertTrue(text2.contains("../Music/Sub/b.m4a"))
        // a playlist that is gone, or the option turned off, removes the file
        val r3 = export(rig, opts, emptyList())
        assertEquals(1, r3.removed)
        assertNull(rig.copy.files[files[0]])
    }

    @Test fun turningPlaylistFilesOffRemovesThem() {
        val rig = rigWithSongs()
        export(rig, PlaceOptions(playlistFiles = true), listOf(ExportPlaylist("p1", "Mix", listOf("ia"))))
        assertTrue(rig.copy.files.keys.any { it.endsWith("Mix.m3u8") })
        val r = export(rig, PlaceOptions())
        assertEquals(1, r.removed)
        assertFalse(rig.copy.files.keys.any { it.endsWith(".m3u8") })
    }

    @Test fun theFlatLayoutPutsEveryFileInOneFolderAndKeepsNamesApart() {
        val rig = rigWithSongs()
        rig.put("Other/a.flac", "different", "id")
        assertEquals("done", rig.run().status)
        val r = export(rig, PlaceOptions(flat = true))
        assertEquals(4, r.written)
        val flat = rig.copy.files.keys.filter { it.startsWith("Keepfin Exports/Flat/") }.sorted()
        assertEquals(4, flat.size)
        // two files called a.flac: the second gets a counter, never overwrites
        assertEquals(2, flat.count { it.substringAfterLast('/').startsWith("a") })
        assertEquals(setOf("alpha", "different"), flat.filter { it.substringAfterLast('/').startsWith("a") }.map { rig.copy.files[it]!!.toString(Charsets.UTF_8) }.toSet())
        // the library files are untouched and a second pass writes nothing
        assertEquals("alpha", rig.text(rig.copy, "Music/a.flac"))
        assertEquals(0, export(rig, PlaceOptions(flat = true)).written)
        // turned off: the flat files go, the library's files stay
        assertEquals(4, export(rig, PlaceOptions()).removed)
        assertTrue(rig.copy.files.keys.none { it.startsWith("Keepfin Exports/") })
        assertEquals("alpha", rig.text(rig.copy, "Music/a.flac"))
    }

    @Test fun mp3VersionsAreMadeOnceAndSkippedByTheInventory() {
        val rig = rigWithSongs()
        val conv = Counting()
        val opts = PlaceOptions(mp3 = true)
        val r = export(rig, opts, converter = conv)
        // flac and m4a are converted; the video is not audio
        assertEquals(2, r.converted)
        assertEquals(2, conv.calls)
        assertEquals("mp3:alpha", rig.text(rig.copy, "Keepfin Exports/MP3/Music/a.mp3"))
        assertEquals("mp3:bravo", rig.text(rig.copy, "Keepfin Exports/MP3/Music/Sub/b.mp3"))
        // converted once: a second pass converts nothing
        export(rig, opts, converter = conv)
        // the derived files are never planned or taken into the library in two-way mode
        val plan = rig.run(dryRun = true)
        assertEquals("preview", plan.status)
        assertEquals(0, plan.summary!!.counts.values.sum())
        assertEquals(0, rig.host.imported.size)
        // the same content under a new name is a move of the converted file, not a new encoding
        rig.desired["Music/new.flac"] = rig.desired.remove("Music/a.flac")!!.copy(path = "Music/new.flac")
        rig.main.files["Music/new.flac"] = rig.main.files.remove("Music/a.flac")!!
        rig.copy.files["Music/new.flac"] = rig.copy.files.remove("Music/a.flac")!!
        val r2 = export(rig, opts, converter = conv)
        assertEquals(2, conv.calls)
        assertEquals(1, r2.moved)
        assertEquals("mp3:alpha", rig.text(rig.copy, "Keepfin Exports/MP3/Music/new.mp3"))
        assertNull(rig.copy.files["Keepfin Exports/MP3/Music/a.mp3"])
        // turned off: the MP3 versions are removed, nothing else
        assertEquals(2, export(rig, PlaceOptions(), converter = conv).removed)
        assertTrue(rig.copy.files.keys.none { it.startsWith("Keepfin Exports/MP3/") })
        assertEquals("charlie", rig.text(rig.copy, "Videos/c.mp4"))
    }

    @Test fun aNameHoldingSomethingElseIsNeverOverwritten() {
        val rig = rigWithSongs()
        rig.write(rig.copy, "Keepfin Exports/MP3/Music/a.mp3", "mine")
        val conv = Counting()
        val r = export(rig, PlaceOptions(mp3 = true), converter = conv)
        assertEquals("mine", rig.text(rig.copy, "Keepfin Exports/MP3/Music/a.mp3"))
        assertTrue(r.skipped.any { it.first.endsWith("a.mp3") })
    }
}
