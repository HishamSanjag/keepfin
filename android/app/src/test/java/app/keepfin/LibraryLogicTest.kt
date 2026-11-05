package app.keepfin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryLogicTest {
    private val music = defaultSpaces().first { it.id == "music" }
    private val downloads = defaultSpaces().first { it.id == "downloads" }

    // ---- layout and naming ---------------------------------------------------------------------
    @Test fun collectionLayoutUsesSanitizedGroupTitle() {
        assertEquals("Open Movies_ OST", SpaceLogic.subfolder(music, SaveContext("t", groupTitle = "Open Movies: OST")))
        assertEquals("", SpaceLogic.subfolder(music, SaveContext("t")))
    }
    @Test fun flatAndPlatformLayouts() {
        assertEquals("", SpaceLogic.subfolder(music.copy(layout = FolderLayout.FLAT), SaveContext("t", groupTitle = "X")))
        assertEquals("YouTube", SpaceLogic.subfolder(downloads.copy(layout = FolderLayout.PLATFORM), SaveContext("t", url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ")))
    }
    @Test fun artistAlbumLayoutForMusicOnly() {
        val space = music.copy(layout = FolderLayout.ARTIST_ALBUM)
        assertEquals("Blender Studio/Open Movies", SpaceLogic.subfolder(space, SaveContext("t", artist = "Blender Studio - Topic", album = "Open Movies", audio = true)))
        assertEquals("Unknown artist", SpaceLogic.subfolder(space, SaveContext("t", audio = true)))
        assertEquals("", SpaceLogic.subfolder(space, SaveContext("t", artist = "A", audio = false)))
    }
    @Test fun namingStyles() {
        val ctx = SaveContext("Song", uploader = "Band - Topic", artist = "Band", dateMillis = 0L)
        assertEquals("Song", SpaceLogic.fileStem(music, ctx))
        assertEquals("Song - Band", SpaceLogic.fileStem(music.copy(naming = SpaceNaming.TITLE_UPLOADER), ctx))
        assertEquals("Band - Song", SpaceLogic.fileStem(music.copy(naming = SpaceNaming.UPLOADER_TITLE), ctx))
        assertEquals("1970-01-01 - Song", SpaceLogic.fileStem(music.copy(naming = SpaceNaming.DATE_TITLE), ctx))
        assertEquals("Band - Song", SpaceLogic.fileStem(music.copy(naming = SpaceNaming.ARTIST_TITLE), ctx))
    }
    @Test fun folderPathsAndLegacyDownloads() {
        assertEquals("Music/Keepfin Music/Album", SpaceLogic.folderPath(StorageRoot.MUSIC, music, "Album"))
        assertEquals("Music/Keepfin", SpaceLogic.folderPath(StorageRoot.MUSIC, downloads, ""))
        assertEquals("Movies/Keepfin", SpaceLogic.folderPath(StorageRoot.MOVIES, downloads, ""))
        assertEquals("Download/Keepfin", SpaceLogic.folderPath(StorageRoot.DOWNLOAD, downloads, ""))
    }
    @Test fun spaceOfRelativePath() {
        val spaces = defaultSpaces()
        assertEquals("music" to "Album", SpaceLogic.spaceOf("Music/Keepfin Music/Album/", spaces)!!.let { it.first.id to it.second })
        assertEquals("downloads" to "", SpaceLogic.spaceOf("Movies/Keepfin/", spaces)!!.let { it.first.id to it.second })
        assertNull(SpaceLogic.spaceOf("Music/Other/", spaces))
        assertNull(SpaceLogic.spaceOf("DCIM/Keepfin/", spaces))
    }
    @Test fun uniqueNames() {
        assertEquals("a.mp3", SpaceLogic.uniqueName(emptySet(), "a.mp3"))
        assertEquals("a (2).mp3", SpaceLogic.uniqueName(setOf("A.mp3"), "a.mp3"))
        assertEquals("a (3).mp3", SpaceLogic.uniqueName(setOf("a.mp3", "a (2).mp3"), "a.mp3"))
    }
    @Test fun validation() {
        assertEquals("Watch/Later", SpaceLogic.cleanFolder("../Watch\\Later/"))
        assertThrows { SpaceLogic.cleanFolder("  ") }
        assertThrows { SpaceLogic.cleanFolder("Music") }
        assertThrows { SpaceLogic.validate(music.copy(id = "x", name = "Other"), defaultSpaces()) }
        assertEquals(FolderLayout.COLLECTION, SpaceLogic.validate(Space("x", "X", SpaceKind.VIDEO, "Clips", FolderLayout.ARTIST_ALBUM), defaultSpaces()).layout)
    }
    private fun assertThrows(block: () -> Unit) { try { block(); fail("expected IllegalArgumentException") } catch (_: IllegalArgumentException) { } }

    // ---- routing -------------------------------------------------------------------------------
    @Test fun routing() {
        val s = LibrarySettings()
        assertEquals("music", SpaceLogic.route(s, "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
        assertEquals("music", SpaceLogic.route(s, "https://music.youtube.com/watch?v=aqz-KE-bpKQ"))
        assertEquals("downloads", SpaceLogic.route(s, "https://www.youtube.com/watch?v=aqz-KE-bpKQ"))
        assertEquals("music", SpaceLogic.route(s, "https://www.youtube.com/watch?v=aqz-KE-bpKQ", uploader = "Band - Topic"))
        assertNull(SpaceLogic.route(s.copy(enabled = false), "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
        assertEquals("downloads", SpaceLogic.route(s.copy(musicRoute = "gone", spaces = s.spaces.filter { it.id != "music" }), "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
    }

    // ---- settings migration ------------------------------------------------------------------
    @Test fun settingsAbsentMigratesToDefaults() {
        val d = SpaceLogic.decode(null)
        // a new install has the three built-ins and Favorites (given once)
        assertEquals(SpaceLogic.withKept(LibrarySettings()), d)
        assertEquals(defaultSpaces(), SpaceLogic.decode(JSONObject()).spaces)
        assertEquals(listOf("downloads", "music", "videos", "favorites"), d.spaces.map { it.id })
        val legacy = d.spaces.first { it.id == "downloads" }
        assertEquals("Keepfin", legacy.folder)
    }
    @Test fun settingsRoundTripAndRepair() {
        val custom = LibrarySettings(crossfade = 5, spaces = defaultSpaces() + Space("w", "Watch later", SpaceKind.VIDEO, "Watch Later", skipExisting = true, ignore = listOf("tmp"), scanFolder = true), otherRoute = "w")
        // Missing colors, icons and pins are filled once with their defaults, then everything round-trips unchanged.
        val filled = SpaceLogic.withKept(SpaceLogic.sanitize(custom))
        assertEquals(filled, SpaceLogic.decode(SpaceLogic.encode(filled)))
        assertEquals(filled, SpaceLogic.decode(SpaceLogic.encode(custom)))
        val broken = JSONObject().put("spaces", org.json.JSONArray().put(JSONObject().put("id", "q").put("name", "Q").put("folder", "Music"))).put("musicRoute", "nope")
        val repaired = SpaceLogic.decode(broken)
        assertTrue(repaired.spaces.any { it.id == "downloads" })
        assertFalse(repaired.spaces.any { it.id == "q" })
        assertEquals("downloads", repaired.otherRoute)
        assertTrue(repaired.spaces.any { it.id == repaired.musicRoute })
    }
    @Test fun jobMigrationKeepsOldRecords() {
        val old = JSONObject("{\"id\":\"a\",\"url\":\"https://x.test/v\",\"status\":\"completed\",\"kind\":\"video\"}")
        val job = JobCodec.decode(old).job
        assertNull(job.space); assertNull(job.existing); assertFalse(job.force)
        val withSpace = job.copy(space = "music", force = true, existing = ExistingMatch("i", "content://x/1", "music", "source"))
        val again = JobCodec.decode(JobCodec.encode(JobRecord(withSpace, FormatRequest.Video(), DownloadOptions()))).job
        assertEquals("music", again.space); assertTrue(again.force); assertEquals(withSpace.existing, again.existing)
    }

    // ---- source ids ----------------------------------------------------------------------------
    @Test fun sourceIds() {
        assertEquals(setOf("youtube:aqz-KE-bpKQ"), SourceIds.of("https://music.youtube.com/watch?v=aqz-KE-bpKQ&list=RDx"))
        assertEquals(setOf("youtube:aqz-KE-bpKQ"), SourceIds.of("https://youtu.be/aqz-KE-bpKQ"))
        assertEquals(setOf("spotify:4uLU6hMCjMI75M1A2tKUQC"), SourceIds.of("https://open.spotify.com/intl-de/track/4uLU6hMCjMI75M1A2tKUQC?si=1"))
        assertEquals(setOf("deezer:3135556"), SourceIds.of("https://www.deezer.com/en/track/3135556"))
        assertEquals(setOf("apple-music:1440833098"), SourceIds.of("https://music.apple.com/us/album/x/1440833088?i=1440833098"))
        assertTrue(SourceIds.of("https://open.spotify.com/playlist/abc").isEmpty())
        assertTrue(SourceIds.of(null).isEmpty())
    }

    // ---- duplicate matching ----------------------------------------------------------------------
    private fun rec(title: String, artist: String? = "Artist", album: String? = null, duration: Double? = 200.0, ids: Set<String> = emptySet(), key: String? = null) =
        Recording(ids, key, artist, title, album, duration)

    // Title noise rules are covered by fixtures/engine/dedupe (titles.json) through the core; this is the album grouping key.
    @Test fun artistGroupingKey() {
        assertEquals("band", Recordings.normalizeArtist("Band - Topic"))
        assertEquals("a", Recordings.normalizeArtist("A & B"))
    }
    @Test fun tiers() {
        assertEquals("source", Recordings.compare(rec("A", ids = setOf("youtube:1")), rec("Z", artist = "Q", ids = setOf("youtube:1"))))
        assertEquals("content", Recordings.compare(rec("A", key = "k"), rec("Z", artist = "Q", key = "k")))
        assertEquals("metadata", Recordings.compare(rec("Song (Official Audio)", "Band - Topic", duration = 200.0), rec("Song", "Band", duration = 201.5)))
        assertNull(Recordings.compare(rec("Song", duration = 200.0), rec("Song", duration = 203.0)))
        assertNull(Recordings.compare(rec("Song", duration = null), rec("Song", duration = 200.0)))
        assertNull(Recordings.compare(rec("", artist = ""), rec("", artist = "")))
    }
    @Test fun albumRule() {
        assertNull(Recordings.compare(rec("Song", album = "Studio"), rec("Song", album = "Greatest Hits")))
        assertEquals("metadata", Recordings.compare(rec("Song", album = "Studio"), rec("Song", album = null)))
        assertEquals("metadata", Recordings.compare(rec("Song", album = "Studio"), rec("Song", album = "studio")))
    }
    @Test fun versionsStayDistinct() {
        for (word in listOf("Live", "Remix", "Acoustic", "Instrumental", "Extended", "Slowed", "Sped Up", "Nightcore", "Karaoke", "Cover"))
            assertNull(word, Recordings.compare(rec("Song ($word)"), rec("Song")))
        assertEquals("metadata", Recordings.compare(rec("Song (Live)"), rec("Song - Live")))
    }
    @Test fun artistDashTitleSplit() {
        assertEquals("metadata", Recordings.compare(rec("Band - Song (Official Video)", artist = null), rec("Song", "Band")))
    }
    @Test fun findPrefersSource() {
        val known = listOf("a" to rec("Song", ids = emptySet()), "b" to rec("Other", ids = setOf("youtube:1")))
        assertEquals("b" to "source", Recordings.find(rec("Song", ids = setOf("youtube:1")), known))
    }

    // ---- lyrics ----------------------------------------------------------------------------------
    @Test fun lrcParsing() {
        val lrc = Lyrics::class.java.let { LyricsParser.parse("[ar:Band]\n[00:12.50]Hello\n[00:05.00][01:02.5]Repeat <00:05.10>word\n\n[offset:500]\n") }!!
        assertTrue(lrc.synced)
        assertEquals(listOf(4_500L, 12_000L, 62_000L), lrc.lines.map { it.timeMs })
        assertEquals("Repeat word", lrc.lines[0].text)
    }
    @Test fun plainLyricsAndEmpty() {
        val plain = LyricsParser.parse("Line one\nLine two\n")!!
        assertFalse(plain.synced); assertEquals(2, plain.lines.size)
        assertNull(LyricsParser.parse("  \n[ti:x]\n"))
    }

    // ---- tool output -------------------------------------------------------------------------------
    @Test fun ffmetadataAndLoudness() {
        val tags = FfOutput.metadata(";FFMETADATA1\ntitle=A\\=B\nLYRICS=line1\\\nline2\nREPLAYGAIN_TRACK_GAIN=-6.5 dB\n[CHAPTER]\ntitle=ignored\n")
        assertEquals("A=B", tags["title"]); assertEquals("line1\nline2", tags["lyrics"]); assertEquals(-6.5, FfOutput.replayGain(tags)!!, 0.0)
        assertEquals(-2.5, FfOutput.gainFromReplayGain(-6.5), 1e-9)
        assertEquals(0.0, FfOutput.gainFromReplayGain(2.0), 0.0)
        assertEquals(-23.1, FfOutput.integratedLoudness("Summary:\n  Integrated loudness:\n    I:         -23.1 LUFS\n    Threshold: -33.1 LUFS")!!, 0.0)
        assertEquals("quiet files are never boosted", 0.0, FfOutput.gainFor(-23.1), 0.0)
        assertEquals(-6.0, FfOutput.gainFor(-8.0), 1e-9)
    }

    // ---- playback rules ----------------------------------------------------------------------------
    @Test fun playbackRules() {
        assertTrue(PlaybackRules.resumes(LibraryItemKind.VIDEO, 60.0))
        assertFalse(PlaybackRules.resumes(LibraryItemKind.AUDIO, 200.0))
        assertTrue(PlaybackRules.resumes(LibraryItemKind.AUDIO, 3600.0))
        assertTrue(PlaybackRules.watched(92.0, 100.0)); assertFalse(PlaybackRules.watched(91.0, 100.0))
        assertEquals(1f, PlaybackRules.volumeFor(null)); assertEquals(1f, PlaybackRules.volumeFor(3.0))
        assertEquals(0.5012f, PlaybackRules.volumeFor(-6.0), 0.001f)
    }

    // ---- cache codec ---------------------------------------------------------------------------------
    @Test fun cacheRoundTrip() {
        val item = LibraryItem("content://media/external/audio/media/1", "music", "content://media/external/audio/media/1", "Album", LibraryItemKind.AUDIO, "T", "A", "Al", size = 5, modifiedAt = 6, addedAt = 7, gain = -3.0, favorite = true)
        val text = LibraryCodec.encodeFile(listOf(LibraryCodec.Rec(item, setOf("youtube:1"), "fp", 2, owned = false, adopted = true, name = "T.mp3")), listOf(CollectionLogic.fromPlaylist("p", "P", listOf(item.id), 1, 2)))
        val data = LibraryCodec.decodeFile(text)
        val recs = data.items
        assertEquals(item, recs.single().item); assertEquals(setOf("youtube:1"), recs.single().sourceIds); assertFalse(recs.single().owned)
        assertEquals("T.mp3", recs.single().name); assertEquals("P", data.collections.single().name)
    }
    @Test fun fingerprintUsesSizeAndEnds() {
        val a = ByteArray(3 * Fingerprint.WINDOW) { (it % 251).toByte() }
        val b = a.copyOf().also { it[Fingerprint.WINDOW + 5] = 9 }
        fun fp(data: ByteArray) = Fingerprint.of(data.size.toLong()) { o, l -> data.copyOfRange(o.toInt(), o.toInt() + l) }
        assertEquals(fp(a), fp(b))
        assertNotEquals(fp(a), fp(a.copyOf().also { it[0] = 1 }))
    }
}
