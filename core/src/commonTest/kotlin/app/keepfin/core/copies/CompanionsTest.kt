package app.keepfin.core.copies

import app.keepfin.core.sync.A
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JNull
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.logOf
import app.keepfin.core.sync.op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionsTest {
    private val replica = "99999999-9999-9999-9999-999999999999"
    private val i1 = "11111111-1111-1111-1111-111111111111"
    private val i2 = "22222222-2222-2222-2222-222222222222"

    @Test
    fun aSongsMusicVideoIsItsCompanion() {
        assertEquals(".video.mp4", companionSuffix("Night Swim.m4a", "Night Swim.video.mp4"))
        // the name keeps its own spelling, as a subtitle's does
        assertEquals(".VIDEO.webm", companionSuffix("Night Swim.mp3", "night swim.VIDEO.webm"))
        assertNull(companionSuffix("Night Swim.m4a", "Night Swim.video.avi"), "a container the apps do not play")
        assertTrue(isVideoCompanion(".video.mp4"))
        assertFalse(isVideoCompanion(".lrc"))
        assertEquals(".video.mp4", videoCompanionSuffix("MP4"))
        assertEquals(".video.webm", videoCompanionSuffix(".webm"))
        assertNull(videoCompanionSuffix("mkv"))
        assertEquals("Night Swim", videoCompanionStem("Night Swim.video.mp4"))
        assertNull(videoCompanionStem("Night Swim.mp4"))
        assertNull(videoCompanionStem(".video.mp4"))
    }

    @Test
    fun aCopyCarriesMusicVideosOnlyWhenAsked() {
        val fields = mapOf(
            companionField(".lrc") to companionValue("sha256:" + "a".repeat(64), 10),
            companionField(".video.mp4") to companionValue("sha256:" + "b".repeat(64), 50_000_000),
        )
        assertEquals(listOf("Music/Song.lrc", "Music/Song.video.mp4"), companionCandidates(i1, "Music/Song.m4a", 1, fields).map { it.path })
        assertEquals(listOf("Music/Song.lrc"), companionCandidates(i1, "Music/Song.m4a", 1, fields, videos = false).map { it.path })
        assertNull(companionCandidate(i1, "Music/Song.m4a", 1, companionField(".video.mp4"), "sha256:" + "b".repeat(64), 50_000_000, videos = false))
        assertEquals("Music/Song.video.mp4", companionCandidate(i1, "Music/Song.m4a", 1, companionField(".video.mp4"), "sha256:" + "b".repeat(64), 50_000_000)?.path)
    }

    @Test
    fun musicVideosAreTheirOwnOptionAndFollowWhatTheCopyHoldsUntilChosen() {
        fun cfg(holds: Map<String, app.keepfin.core.sync.Jv>, options: Map<String, app.keepfin.core.sync.Jv> = emptyMap()) =
            placeConfigFrom("p", mapOf("mode" to JStr("backup"), "holds" to app.keepfin.core.sync.JObj(holds), "options" to app.keepfin.core.sync.JObj(options)))!!.options
        val all = mapOf("all" to app.keepfin.core.sync.JBool(true))
        val part = mapOf("spaces" to JArr(listOf(JStr("music"))))
        assertTrue(cfg(all).musicVideos, "a copy of everything carries them")
        assertFalse(cfg(part).musicVideos, "a part copy does not, until its person turns them on")
        assertTrue(cfg(part, mapOf("musicVideos" to app.keepfin.core.sync.JBool(true))).musicVideos)
        assertFalse(cfg(all, mapOf("musicVideos" to app.keepfin.core.sync.JBool(false))).musicVideos)
        // a copy that already left out the files beside each song gets no videos until its person turns them on
        val noLyrics = cfg(all, mapOf("companions" to app.keepfin.core.sync.JBool(false)))
        assertFalse(noLyrics.companions)
        assertFalse(noLyrics.musicVideos)
        // apart from the lyrics once chosen: either can be off while the other is on
        assertTrue(cfg(all, mapOf("companions" to app.keepfin.core.sync.JBool(false), "musicVideos" to app.keepfin.core.sync.JBool(true))).musicVideos)
    }

    @Test
    fun theDesiredSetCarriesMusicVideosAndLyricsEachByItsOwnOption() {
        val ops = item(1, i1, "Music/Song.mp3", sha(1)) + listOf(
            op(A, 9L, 9L, "item.set", "item" to JStr(i1), "field" to JStr("space"), "value" to JStr("music")),
            companion(10, i1, ".lrc", sha(11)),
            companion(11, i1, ".video.mp4", sha(12)),
        )
        val state = logOf(ops).state
        fun paths(holds: Holds, companions: Boolean = true, videos: Boolean? = null) =
            (if (videos == null) desiredSetFrom(state, replica, holds, companions) else desiredSetFrom(state, replica, holds, companions, musicVideos = videos)).entries.map { it.path }
        assertEquals(listOf("Music/Song.lrc", "Music/Song.mp3", "Music/Song.video.mp4"), paths(Holds(all = true)))
        assertEquals(listOf("Music/Song.lrc", "Music/Song.mp3"), paths(Holds(spaces = setOf("music"))), "a part copy, by default")
        assertEquals(listOf("Music/Song.lrc", "Music/Song.mp3", "Music/Song.video.mp4"), paths(Holds(spaces = setOf("music")), videos = true))
        assertEquals(listOf("Music/Song.mp3", "Music/Song.video.mp4"), paths(Holds(all = true), companions = false))
        assertEquals(listOf("Music/Song.mp3"), paths(Holds(all = true), companions = false, videos = false))
    }

    @Test
    fun turningMusicVideosOffKeepsTheOnesOnAMirror() {
        val same = InvLine("Song.video.mp4", 100, 1_000_000)
        val main = listOf(InvLine("Song.video.mp4", 100, 1_000_000))
        assertEquals(emptyList(), planFor(CopyMode.Mirror, "companion:.video.mp4", same, main, deleted = false, PlaceOptions(musicVideos = false)).map { it.kind })
        // lyrics turned off say nothing about the video: no longer desired, it leaves a mirror like any file
        assertEquals(listOf(ActionKind.RemoveFromCopy), planFor(CopyMode.Mirror, "companion:.video.mp4", same, main, deleted = false, PlaceOptions(companions = false)).map { it.kind })
    }

    @Test
    fun theFilesBesideAMediaFileAreItsCompanions() {
        assertEquals(".lrc", companionSuffix("Song.mp3", "Song.lrc"))
        assertEquals(".en.srt", companionSuffix("Video.mp4", "Video.en.srt"))
        assertEquals(".pt-BR.vtt", companionSuffix("Video.webm", "Video.pt-BR.vtt"))
        assertEquals(".srt", companionSuffix("Video.mp4", "Video.srt"))
        assertEquals(".info.json", companionSuffix("Video.mp4", "Video.info.json"))
        assertEquals(".lrc", companionSuffix("I Speak... It Is Done.mp3", "I Speak... It Is Done.lrc"))
        // letter case of the stem is ignored, as Windows does; the suffix keeps the file's own spelling
        assertEquals(".LRC", companionSuffix("Song.mp3", "song.LRC"))
        assertNull(companionSuffix("Song.mp3", "Song.mp3"))
        assertNull(companionSuffix("Song.mp3", "Song.webm"))
        assertNull(companionSuffix("Song.mp3", "Songs.lrc"))
        assertNull(companionSuffix("Song.mp3", "Song.en.lrc"))
        assertEquals(".jpg", companionSuffix("Song.mp3", "Song.jpg"))
        assertEquals(".webp", companionSuffix("Video.mp4", "Video.webp"))
        assertNull(companionSuffix("Song.mp3", "Song.en.jpg"))
        assertNull(companionSuffix("Song.mp3", "Song.gif"))
        assertNull(companionSuffix("Video.mp4", "Video.live_chat.json"))
    }

    @Test
    fun aSuffixIsAPlainNameTail() {
        assertTrue(isCompanionSuffix(".lrc"))
        assertTrue(isCompanionSuffix(".en.srt"))
        assertFalse(isCompanionSuffix("lrc"))
        assertFalse(isCompanionSuffix(".x/../.lrc"))
        assertFalse(isCompanionSuffix("..srt"))
        assertFalse(isCompanionSuffix(".a\\b.srt"))
        assertFalse(isCompanionSuffix(".en:x.srt"))
        assertFalse(isCompanionSuffix("." + "e".repeat(70) + ".srt"))
        assertEquals("Music/rr/Song.en.srt", companionPath("Music/rr/Song.mp3", ".en.srt"))
        assertEquals("Song.lrc", companionPath("Song.mp3", ".lrc"))
        assertEquals("Music/My.Band - Live.lrc", companionPath("Music/My.Band - Live.flac", ".lrc"))
    }

    private fun item(n: Int, id: String, path: String, content: String) = listOf(
        op(A, n.toLong(), n.toLong(), "item.add", "item" to JStr(id)),
        op(A, n + 1L, n + 1L, "item.place", "item" to JStr(id), "place" to JStr(replica), "path" to JStr(path)),
        op(A, n + 2L, n + 2L, "item.revise", "item" to JStr(id), "content" to JStr(content), "size" to JNum(10), "parents" to JArr(emptyList())),
    )

    private fun companion(n: Int, id: String, suffix: String, content: String?) =
        op(A, n.toLong(), n.toLong(), "item.set", "item" to JStr(id), "field" to JStr(companionField(suffix)), "value" to (if (content == null) JNull else companionValue(content, 3)))

    @Test
    fun theDesiredSetHoldsEachItemsCompanionsUnlessTurnedOff() {
        val ops = item(1, i1, "Music/Song.mp3", sha(1)) + item(4, i2, "Video/Clip.mp4", sha(2)) + listOf(
            companion(10, i1, ".lrc", sha(11)),
            companion(11, i2, ".en.srt", sha(21)),
            companion(12, i2, ".info.json", sha(22)),
            companion(13, i2, ".info.json", null), // removed again
        )
        val state = logOf(ops).state
        val set = desiredSetFrom(state, replica, Holds(all = true))
        assertEquals(listOf("Music/Song.lrc", "Music/Song.mp3", "Video/Clip.en.srt", "Video/Clip.mp4"), set.entries.map { it.path })
        val lrc = set.entries.first { it.path == "Music/Song.lrc" }
        assertEquals(i1, lrc.itemId)
        assertEquals(sha(11), lrc.contentId)
        assertEquals(3L, lrc.bytes)
        // its own revision: never mistaken for a new version of the song
        assertEquals(".lrc", companionOfEntry(lrc.revisionOp))
        assertNull(companionOfEntry(set.entries.first { it.path == "Music/Song.mp3" }.revisionOp))
        assertEquals(listOf("Music/Song.mp3", "Video/Clip.mp4"), desiredSetFrom(state, replica, Holds(all = true), companions = false).entries.map { it.path })
    }

    @Test
    fun conflictingVersionsCarryNoCompanionsAndANameIsUsedOnce() {
        val ops = item(1, i1, "Music/Song.mp3", sha(1)) + item(4, i2, "Music/song.flac", sha(2)) + listOf(
            companion(10, i1, ".lrc", sha(11)),
            companion(11, i2, ".lrc", sha(21)),
        )
        // Song.mp3 and song.flac would both make Song.lrc: the smaller item id keeps the name
        val both = desiredSetFrom(logOf(ops).state, replica, Holds(all = true)).entries.filter { it.path.endsWith(".lrc", ignoreCase = true) }
        assertEquals(listOf(i1 to sha(11)), both.map { it.itemId to it.contentId })
        // a second head of the song: its lyrics wait until the conflict is settled
        val conflict = ops + op(A, 20L, 20L, "item.revise", "item" to JStr(i1), "content" to JStr(sha(3)), "size" to JNum(10), "parents" to JArr(emptyList()))
        val after = desiredSetFrom(logOf(conflict).state, replica, Holds(all = true)).entries.filter { it.path.endsWith(".lrc", ignoreCase = true) }
        assertEquals(listOf(i2 to sha(21)), after.map { it.itemId to it.contentId })
    }

    // ---- the planner: a companion is never a revision of its item, nor an item of its own ----

    private fun base(path: String, revision: String) = BaselineEntry(path, "x", revision, sha(1), Stat(100, 1_000_000), Stat(100, 1_000_000), 90_000_000, 1)

    private fun planFor(mode: CopyMode, revision: String, onCopy: InvLine, main: List<InvLine>, deleted: Boolean, options: PlaceOptions = PlaceOptions()): List<Action> =
        Rel(
            mode = mode, m = main, c = listOf(onCopy), l = listOf(base(onCopy.path, revision)),
            copyHashes = mapOf(onCopy.path to if (onCopy.mtimeMs == 1_000_000L) sha(1) else sha(9)),
            library = SimpleLibraryFacts(deleted = if (deleted) setOf("x") else emptySet()),
        ).plan(options = options).actions

    @Test
    fun lyricsEditedOnATwoWayCopyOfADeletedSongWaitInItsTrash() {
        val edited = InvLine("Song.lrc", 120, 2_000_000)
        val lyrics = planFor(CopyMode.TwoWay, "companion:.lrc", edited, emptyList(), deleted = true)
        assertEquals(listOf(ActionKind.RemoveFromCopy), lyrics.map { it.kind })
        assertEquals(Archive.Trash, lyrics[0].archive)
        // the song itself would come back for review
        val song = planFor(CopyMode.TwoWay, "op-x", InvLine("Song.mp3", 120, 2_000_000), emptyList(), deleted = true)
        assertEquals(listOf(ActionKind.RestoreEditedDeleted), song.map { it.kind })
    }

    @Test
    fun lyricsNoLongerListedAreNeverImportedAsAnItem() {
        val edited = InvLine("Song.lrc", 120, 2_000_000)
        assertEquals(emptyList(), planFor(CopyMode.TwoWay, "companion:.lrc", edited, emptyList(), deleted = false).map { it.kind })
        assertEquals(listOf(ActionKind.ImportNew), planFor(CopyMode.TwoWay, "op-x", InvLine("Song.mp3", 120, 2_000_000), emptyList(), deleted = false).map { it.kind })
    }

    @Test
    fun turningCompanionsOffKeepsTheOnesOnAMirror() {
        val same = InvLine("Song.lrc", 100, 1_000_000)
        val main = listOf(InvLine("Song.lrc", 100, 1_000_000))
        assertEquals(emptyList(), planFor(CopyMode.Mirror, "companion:.lrc", same, main, deleted = false, PlaceOptions(companions = false)).map { it.kind })
        // lyrics removed in the library leave a mirror like any file
        assertEquals(listOf(ActionKind.RemoveFromCopy), planFor(CopyMode.Mirror, "companion:.lrc", same, main, deleted = false).map { it.kind })
    }

    @Test
    fun aPlaceHoldsCompanionsUnlessTurnedOff() {
        assertTrue(placeConfigFrom("p", mapOf("mode" to JStr("backup")))!!.options.companions)
        val off = placeConfigFrom("p", mapOf("mode" to JStr("backup"), "options" to app.keepfin.core.sync.JObj(mapOf("companions" to app.keepfin.core.sync.JBool(false)))))
        assertFalse(off!!.options.companions)
    }
}
