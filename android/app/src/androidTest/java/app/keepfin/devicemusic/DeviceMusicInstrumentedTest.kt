package app.keepfin.devicemusic

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.DEVICE_SPACE_ID
import app.keepfin.DeviceMusicState
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.PlaybackItems
import app.keepfin.all
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** "Music on this phone" over the real MediaStore: files this test inserts, listed in place, played, and removed again. */
@RunWith(AndroidJUnit4::class)
class DeviceMusicInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val cr = ctx.contentResolver
    private val tag = "PhoneMusicTest" + UUID.randomUUID().toString().take(6)
    private val made = ArrayList<Uri>()
    private val dir = File(ctx.cacheDir, "device-music-test-$tag")
    private var manager: DeviceMusicManager? = null
    private var player: ExoPlayer? = null

    @Before fun grantAudioPermission() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) "READ_MEDIA_AUDIO" else "READ_EXTERNAL_STORAGE"
        instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, "android.permission.$permission")
        dir.mkdirs()
    }

    @After fun cleanup() {
        player?.let { p -> instrumentation.runOnMainSync { p.release() } }
        manager?.shutdown()
        made.forEach { runCatching { cr.delete(it, null, null) } }
        dir.deleteRecursively()
    }

    /** A 16-bit mono WAV of a tone, [seconds] long. */
    private fun wav(seconds: Int, hz: Double): ByteArray {
        val rate = 8000
        val samples = rate * seconds
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        out.write(header.array())
        val body = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) body.putShort((sin(2 * PI * hz * i / rate) * 8000).toInt().toShort())
        out.write(body.array())
        return out.toByteArray()
    }

    private fun insert(name: String, seconds: Int, folder: String, ringtone: Boolean = false) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name); put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.TITLE, name.substringBeforeLast('.')); put(MediaStore.Audio.Media.ARTIST, "Keepfin Test")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "$folder/$tag"); put(MediaStore.Audio.Media.IS_PENDING, 1)
            if (ringtone) put(MediaStore.Audio.Media.IS_RINGTONE, 1) else put(MediaStore.Audio.Media.IS_MUSIC, 1)
        }
        val uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        cr.openOutputStream(uri)!!.use { it.write(wav(seconds, 440.0)) }
        cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        made += uri
    }

    private fun durations(): Int = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf("duration"), "relative_path LIKE ? AND duration >= 30000", arrayOf("%$tag%"), null)?.use { it.count } ?: 0

    private fun ours(m: DeviceMusicManager) = m.browse!!.all(ItemQuery(spaces = listOf(DEVICE_SPACE_ID), order = ItemOrder.TITLE)).filter { tag in it.folder }

    @Test fun threeSongsAreListedInPlaceAndOnePlays() {
        insert("one.wav", 40, "Music"); insert("two.wav", 40, "Music"); insert("three.wav", 40, "Music")
        insert("short.wav", 3, "Music")
        insert("ring.wav", 40, "Ringtones", ringtone = true)

        // MediaStore reads a file's length a moment after it is added: wait until it has for the long ones
        val ready = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < ready && durations() < 3) Thread.sleep(300)
        val m = DeviceMusicManager(AndroidDeviceMusicPlatform(ctx), File(dir, "device-music.db"), log = { message, error -> android.util.Log.e("DeviceMusicTest", message, error) }).also { manager = it }
        m.start()
        assertTrue("permission is granted", m.missingPermissions().isEmpty())
        m.setOn(true)
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end && !(m.state.value is DeviceMusicState.Ready && m.browse?.let { ours(m).size } == 3)) Thread.sleep(200)

        val items = ours(m)
        assertEquals("state ${m.state.value}, all ${m.browse?.count(ItemQuery(spaces = listOf(DEVICE_SPACE_ID)))}", listOf("one", "three", "two"), items.map { it.title })
        assertTrue("the short clip and the ringtone stay out", items.none { it.title == "short" || it.title == "ring" })
        assertTrue(items.all { it.space == DEVICE_SPACE_ID && it.uri.startsWith("content://media/") && it.duration!! >= 30.0 })

        // plays through the same item the library hands the player
        val media = PlaybackItems.toMediaItem(ctx, items.first())
        var current: ExoPlayer? = null
        instrumentation.runOnMainSync {
            current = ExoPlayer.Builder(ctx).build().also { it.setMediaItem(media); it.prepare(); it.playWhenReady = true }
            player = current
        }
        val until = System.currentTimeMillis() + 20_000
        var state = Player.STATE_IDLE
        while (System.currentTimeMillis() < until && state != Player.STATE_READY) {
            Thread.sleep(100)
            instrumentation.runOnMainSync { state = current!!.playbackState }
        }
        assertEquals("the song reaches READY", Player.STATE_READY, state)
        var position = 0L
        val playUntil = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < playUntil && position <= 0L) {
            Thread.sleep(100)
            instrumentation.runOnMainSync { position = current!!.currentPosition }
        }
        assertTrue("the song plays", position > 0L)

        // taken away from the phone, they leave the list
        made.forEach { cr.delete(it, null, null) }
        made.clear()
        m.refreshNow()
        assertTrue(ours(m).isEmpty())
    }
}
