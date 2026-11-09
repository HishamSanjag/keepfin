package app.keepfin.net

import app.keepfin.FormatRequest
import app.keepfin.InspectResult
import app.keepfin.MusicMetadata
import app.keepfin.MusicVideoLookup
import app.keepfin.MusicVideoMatch
import app.keepfin.ui.preview.FakeEngine
import app.keepfin.ui.preview.Fixtures
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** "Download on..." with the music video box (issue #32): the receiving phone looks up the song's video itself and queues it with the song. */
class EngineJobPortMusicVideoTest {
    private val engine = FakeEngine()
    private val port = EngineJobPort(engine, metered = { false }, free = { null }, guard = { it }, consentBytes = Long.MAX_VALUE)
    private val audio = JSONObject("""{"type":"audio","target":"mp3","quality":"best"}""")
    private val found = MusicVideoMatch("abcdefghijk", "Song (Official Video)", "Artist", 1000L, 200.0)

    @After fun close() = port.close()

    private fun libraryOn(on: Boolean = true) = engine.updateSettings { it.copy(library = it.library.copy(enabled = on)) }

    private fun send(id: String, format: JSONObject = audio, musicVideo: Boolean = true, artist: String? = "Artist") {
        val link = "https://music.youtube.com/watch?v=$id"
        engine.inspectResults[link] = InspectResult.Media(Fixtures.bigBuckBunny(link).copy(music = MusicMetadata(artist, null, "Song", null)))
        port.create(id, link, format, null, null, musicVideo)
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            if (port.view(id)?.state == "queued") return
            Thread.sleep(20)
        }
        fail("job $id never queued: ${port.view(id)?.state} ${port.view(id)?.error}")
    }

    @Test fun aSongWithTheVideoAskedForIsQueuedWithTheFoundVideo() {
        libraryOn()
        engine.musicVideoLookup = MusicVideoLookup.Found(found)
        send("song0000001")
        assertEquals(listOf(listOf("Artist") to "Song"), engine.musicVideoLookups.toList())
        assertEquals(found, engine.capturedWithVideo.single().second)
        assertEquals(1, engine.enqueued.size)
    }

    @Test fun aFailedOrEmptyLookupStillQueuesTheSongWithoutVideo() {
        libraryOn()
        engine.musicVideoLookup = MusicVideoLookup.None
        send("song0000002")
        engine.musicVideoLookup = MusicVideoLookup.Failed("offline")
        send("song0000003")
        assertEquals(2, engine.musicVideoLookups.size)
        assertEquals(2, engine.enqueued.size)
        assertTrue(engine.capturedWithVideo.isEmpty())
    }

    @Test fun noLookupWhenTheVideoIsNotAskedForOrCannotApply() {
        engine.musicVideoLookup = MusicVideoLookup.Found(found)
        libraryOn()
        send("song0000004", musicVideo = false)
        send("song0000005", format = JSONObject("""{"type":"video","codec":"auto","container":"mp4"}"""))
        send("song0000006", artist = null)
        libraryOn(false)
        send("song0000007")
        assertTrue(engine.musicVideoLookups.isEmpty())
        assertTrue(engine.capturedWithVideo.isEmpty())
        assertEquals(4, engine.enqueued.size)
        assertTrue(engine.enqueued.any { it.second is FormatRequest.Video })
    }
}
