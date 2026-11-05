package app.keepfin

import app.keepfin.core.after.Facts
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the phone does when a download finishes (dev-docs/plans/AFTER-DOWNLOAD.md): the core's plan run through the step interface, on an in-memory adapter. */
class AfterDownloadStepsTest {
    /** Records every step the runner asks for; the maps say which step fails or what it returns. */
    private class Memory(
        val saved: String? = "item-1",
        val attached: String? = "song-1",
        val failing: Set<String> = emptySet(),
    ) : AfterDownloadSteps {
        val calls = ArrayList<String>()
        private fun step(name: String, value: String? = null): String? { calls.add(name); if (name in failing) throw IllegalStateException("$name failed"); return value }
        override fun lyrics() { step("lyrics") }
        override fun save(): String? = step("save", saved)
        override fun attachVideo(): String? = step("attachVideo", attached)
        override fun waitForSong() { step("waitForSong") }
        override fun joinPlaylist(itemId: String?) { calls.add("joinPlaylist($itemId)") }
        override fun cover() { step("cover") }
        override fun notifyDone() { step("notify") }
        override fun notifyExisting() { step("notifyExisting") }
        override fun settleVideos() { step("settleVideos") }
        override fun markNotSaved(error: Throwable) { calls.add("notSaved(${error.message})") }
    }

    private fun run(facts: Facts, memory: Memory, only: Set<String>? = null, item: String? = null): List<String> {
        AfterDownloadRunner.run(facts, memory, only, item)
        return memory.calls
    }

    @Test fun aSongRunsInOrderLyricsFirstThenSaveJoinCoverNotify() {
        val calls = run(Facts(audio = true, lyrics = true, playlist = true), Memory())
        assertEquals(listOf("lyrics", "save", "joinPlaylist(item-1)", "cover", "notify", "settleVideos"), calls)
    }

    @Test fun aFailingSaveMarksTheJobAndTheRestStillRuns() {
        val calls = run(Facts(audio = true, playlist = true), Memory(failing = setOf("save")))
        assertEquals(listOf("save", "notSaved(save failed)", "joinPlaylist(null)", "cover", "notify", "settleVideos"), calls)
    }

    @Test fun aFailingLyricsStepDoesNotStopTheSave() {
        val calls = run(Facts(audio = true, lyrics = true), Memory(failing = setOf("lyrics")))
        assertEquals(listOf("lyrics", "save", "cover", "notify", "settleVideos"), calls)
    }

    @Test fun aRecordingThatWasAlreadyThereJoinsThePlaylistAndSaysSo() {
        val calls = run(Facts(audio = true, existing = true, hasFile = false, playlist = true), Memory(), item = "had-it")
        assertEquals(listOf("joinPlaylist(had-it)", "notifyExisting", "settleVideos"), calls)
    }

    @Test fun aMusicVideoKeptBesideItsSongIsNotSavedOnItsOwn() {
        val calls = run(Facts(video = "attach", playlist = true), Memory())
        assertEquals(listOf("attachVideo", "joinPlaylist(song-1)", "cover", "notify", "settleVideos"), calls)
    }

    @Test fun aSongThatCannotTakeTheVideoMakesItAnOrdinaryDownload() {
        assertEquals(listOf("attachVideo", "save", "cover", "notify", "settleVideos"), run(Facts(video = "attach"), Memory(attached = null)))
    }

    @Test fun aFailedAttachAlsoFallsBackToAnOrdinarySaveWithoutMarkingTheJob() {
        val calls = run(Facts(video = "attach", playlist = true), Memory(failing = setOf("attachVideo")))
        assertEquals(listOf("attachVideo", "save", "joinPlaylist(item-1)", "cover", "notify", "settleVideos"), calls)
    }

    @Test fun anOrdinarySaveThatFailsAfterAFailedAttachMarksTheJob() {
        val calls = run(Facts(video = "attach"), Memory(failing = setOf("attachVideo", "save")))
        assertEquals(listOf("attachVideo", "save", "notSaved(save failed)", "cover", "notify", "settleVideos"), calls)
    }

    @Test fun aMusicVideoBeforeItsSongWaits() {
        assertEquals(listOf("waitForSong", "cover", "notify", "settleVideos"), run(Facts(video = "wait", playlist = true), Memory()))
    }

    @Test fun savingAgainRunsOnlyTheSavingSteps() {
        val calls = run(Facts(audio = true, lyrics = true, playlist = true), Memory(), only = AfterDownloadRunner.SAVING)
        assertEquals(listOf("save", "joinPlaylist(item-1)"), calls)
    }
}
