package app.keepfin.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.PlaybackItems
import app.keepfin.core.net.NetAction
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Play from another device" through the player the app uses (ExoPlayer with [withPeerPlayback]) and the items the library screens hand it:
 * phone B holds the library but not the file, phone A serves it over the real Devices stack (TLS, pinned keys, Range). The player seeks, and
 * the other phone is asked for the bytes after the seek, not the whole file again.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayFromPlayerTest {
    private lateinit var context: Context
    private val stacks = ArrayList<NetStack>()
    private val library = UUID.randomUUID().toString()
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    @Before fun setUp() {
        diagnostics = { stacks.joinToString("\n") { it.dump() } }
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After fun tearDown() {
        onMain { player?.release(); player = null }
        PeerPlayback.forced = null
        NetRuntime.installForTests(null)
        stacks.forEach { it.close() }
    }

    private fun stack(name: String): NetStack {
        val s = NetStack(name, context, File(context.cacheDir, "playfrom-$name-${UUID.randomUUID().toString().take(6)}"))
        stacks += s
        s.openLibrary(library)
        s.start()
        return s
    }

    private fun <T> onMain(block: () -> T): T {
        val out = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        main.post { out.set(runCatching(block)); latch.countDown() }
        assertTrue("the main thread did not answer", latch.await(20, TimeUnit.SECONDS))
        return out.get().getOrThrow()
    }

    /** 8 kHz mono 16-bit PCM wave of [seconds] seconds: a format the player can seek in by arithmetic. */
    private fun wave(seconds: Int): ByteArray {
        val samples = 8000 * seconds
        val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        for (i in 0 until samples) data.putShort((Math.sin(i * 2 * Math.PI * 440 / 8000) * 6000).toInt().toShort())
        return data.array()
    }

    private fun contentId(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun remoteItem(contentId: String) = LibraryItem(
        id = UUID.randomUUID().toString(), space = "music", uri = "", folder = "", kind = LibraryItemKind.AUDIO, title = "Long Song", artist = "Artist",
        size = 0, modifiedAt = 0, addedAt = 0, remote = true, contentId = contentId,
    )

    private fun pairedPhones(withWave: ByteArray?): Pair<NetStack, NetStack> {
        val a = stack("alpha")
        val b = stack("beta")
        if (withWave != null) a.files[contentId(withWave)] = withWave
        pairBySas(b, a)
        until("beta to be connected") { b.service.links[a.identity.deviceId]?.online == true }
        grant(a, b, library, NetAction.MetadataRead, NetAction.MetadataWrite, NetAction.MediaRead)
        until("beta to learn it may play files") { b.service.links[a.identity.deviceId]?.lastHello?.libraries?.any { "media.read" in it.actions } == true }
        NetRuntime.installForTests(b.service, b.libs.toList())
        return a to b
    }

    private fun newPlayer(): ExoPlayer = onMain { ExoPlayer.Builder(context).withPeerPlayback(context).build().also { player = it } }

    @Test fun anItemThatIsNotOnThisPhonePlaysFromAPairedDeviceAndSeeks() {
        val bytes = wave(600)
        val (a, b) = pairedPhones(bytes)
        val item = remoteItem(contentId(bytes))
        assertEquals(PlayChoice.Peer("alpha"), PeerPlayback.choiceFor())
        val media = PlaybackItems.toMediaItem(context, item)
        assertTrue(media.localConfiguration!!.uri.toString().startsWith("keepfin-peer://content/"))
        val p = newPlayer()
        onMain { p.setMediaItem(media); p.prepare(); p.play() }
        until("the player to be ready and playing from alpha", 30_000) { onMain { p.playbackState == Player.STATE_READY && p.currentPosition > 300 } }
        val duration = onMain { p.duration }
        assertTrue("duration $duration", duration in 599_000..601_000)
        assertEquals("alpha", PeerPlayback.streamingFrom.value)
        // seek to 400 s: the buffer holds about a minute, so only a real reopen at the new position reaches alpha at an offset past 6 MB
        onMain { p.seekTo(400_000) }
        until("the position to pass 400 s", 30_000) { onMain { p.currentPosition >= 400_000 && p.playbackState == Player.STATE_READY } }
        val before = onMain { p.currentPosition }
        until("playback to move on after the seek") { onMain { p.currentPosition > before + 300 } }
        val offsets = a.served!!.opened.toList()
        assertTrue("the file was only opened from the start: $offsets", offsets.any { it >= 400_000L * 16 - 2_000 })
        assertEquals(PlayChoice.Peer("alpha"), PeerPlayback.choiceFor())
        assertNotNull(b.service)
    }

    @Test fun aStreamThatCannotBeFoundSaysSoInPlainWords() {
        pairedPhones(null)
        val p = newPlayer()
        val failure = AtomicReference<PlaybackException>()
        onMain {
            p.addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failure.set(error) } })
            p.setMediaItem(PlaybackItems.toMediaItem(context, remoteItem("sha256:" + "0".repeat(64)))); p.prepare(); p.play()
        }
        until("the player to give up", 40_000) { failure.get() != null }
        val message = PeerPlayback.takeFailure()
        assertNotNull(message)
        assertTrue(message, message!!.contains("No paired device"))
    }

    @Test fun anUnreachableDeviceIsNamedWithWhenItWasSeen() {
        val (a, b) = pairedPhones(null)
        assertEquals(PlayChoice.Peer("alpha"), PeerPlayback.choiceFor())
        a.service.disable()
        until("beta to notice alpha is gone") { PeerPlayback.choiceFor() is PlayChoice.Unreachable }
        val blocked = PeerPlayback.blockedMessage()
        assertTrue(blocked, blocked!!.startsWith("alpha is not reachable. It was last seen "))
        assertNotNull(b.service)
    }
}
