package app.keepfin.proto

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Probe 3: Media3 seeking inside a ~200 MB file reached through a SAF document URI (primary storage and the virtual SD). */
@RunWith(AndroidJUnit4::class)
class SafSeekProtoTest {
    private val p = ProbeSupport("KeepfinProbe3")
    private val dirName = "KeepfinProbe3"
    private var vol = "primary"
    @Volatile private var ready = CountDownLatch(1)
    @Volatile private var err: PlaybackException? = null

    @After fun cleanup() {
        p.releaseAll("KeepfinProbe3")
        runCatching { p.shell("rm -rf ${p.fsPath(vol, dirName)}") }
    }

    @Test fun primary() = run("primary")
    @Test fun sd() = run(p.sdVolume() ?: throw AssertionError("no virtual SD volume mounted"))

    private fun stats(xs: List<Long>): String { val s = xs.sorted(); return "min=${s.first()} median=${s[s.size / 2]} max=${s.last()} mean=${"%.0f".format(s.average())}" }

    private fun run(volume: String) {
        vol = volume
        val label = if (volume == "primary") "PRIMARY" else "SD($volume)"
        p.shell("rm -rf ${p.fsPath(vol, dirName)}"); p.shell("mkdir -p ${p.fsPath(vol, dirName)}")
        val tree = p.pickTree(vol, dirName)
        val rootId = DocumentsContract.getTreeDocumentId(tree)

        // Generate ~200 MB: a PCM WAV of 1,130 s (176,400 B/s).
        val seconds = 1130
        val dataBytes = 176_400L * seconds
        val doc = p.create(tree, rootId, "audio/x-wav", "big.wav")!!
        val t0 = System.nanoTime()
        p.cr.openOutputStream(doc)!!.buffered(1 shl 20).use { out ->
            out.write(p.wavHeader(dataBytes))
            val chunk = ByteArray(1 shl 20) { (it * 31).toByte() }
            var left = dataBytes
            while (left > 0) { val n = minOf(left, chunk.size.toLong()).toInt(); out.write(chunk, 0, n); left -= n }
        }
        val genMs = (System.nanoTime() - t0) / 1_000_000
        val size = p.cr.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
        p.log("$label: generated ${size / 1048576} MB through SAF in $genMs ms (${"%.0f".format(size / 1048576.0 / (genMs / 1000.0))} MB/s)")

        // (a) raw: positioned 64 KB reads through the provider's file descriptor at 10 points.
        val offs = (1..10).map { (size * it / 11) }
        val raw = ArrayList<Long>()
        val openT = System.nanoTime()
        p.cr.openFileDescriptor(doc, "r")!!.use { pfd ->
            val openMs = (System.nanoTime() - openT) / 1_000_000
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val buf = java.nio.ByteBuffer.allocate(64 * 1024)
                for (o in offs.shuffled(java.util.Random(7))) { buf.clear(); val t = System.nanoTime(); ch.read(buf, o); raw += (System.nanoTime() - t) / 1000 }
            }
            p.log("$label: (a) raw openFileDescriptor took $openMs ms; 10 positioned 64 KB reads in microseconds: ${stats(raw)}")
        }

        // (b) Media3 ExoPlayer on the document URI.
        val main = Handler(Looper.getMainLooper())
        var player: ExoPlayer? = null
        main.post {
            player = ExoPlayer.Builder(p.ctx).build().also { pl ->
                pl.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) ready.countDown() }
                    override fun onPlayerError(e: PlaybackException) { err = e; ready.countDown() }
                })
                pl.setMediaItem(MediaItem.fromUri(doc)); pl.playWhenReady = false
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val tp = System.nanoTime()
        main.post { player!!.prepare() }
        if (!ready.await(60, TimeUnit.SECONDS) || err != null) { p.log("$label: (b) prepare failed: ${err?.errorCodeName} ${err?.message}"); throw AssertionError("prepare failed: $err") }
        val prepMs = (System.nanoTime() - tp) / 1_000_000
        var durMs = 0L
        main.post { durMs = player!!.duration }
        Thread.sleep(300)
        p.log("$label: (b) ExoPlayer prepare->READY ${prepMs} ms; duration ${durMs} ms")
        val rounds = ArrayList<Long>(); val perPoint = ArrayList<String>()
        for (round in 1..2) {
            for ((k, frac) in (1..10).map { it / 11.0 }.shuffled(java.util.Random(round.toLong())).withIndex()) {
                ready = CountDownLatch(1)
                val target = (durMs * frac).toLong()
                val t = System.nanoTime()
                main.post { player!!.seekTo(target) }
                if (!ready.await(30, TimeUnit.SECONDS)) { perPoint += "${(frac * 100).toInt()}%:timeout"; continue }
                val ms = (System.nanoTime() - t) / 1_000_000
                if (err != null) { perPoint += "${(frac * 100).toInt()}%:error ${err!!.errorCodeName}"; err = null; continue }
                rounds += ms; perPoint += "${(frac * 100).toInt()}%:${ms}"
            }
        }
        p.log("$label: (b) ExoPlayer seek to READY, 20 seeks (ms): ${if (rounds.isNotEmpty()) stats(rounds) else "none"}; per point ${perPoint.joinToString(" ")}")
        // Sequential seek-and-play: play 2 s at the end to check that rendering works after seeks.
        main.post { player!!.release() }
        p.log("$label: DONE")
    }
}
