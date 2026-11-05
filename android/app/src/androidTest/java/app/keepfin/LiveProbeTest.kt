package app.keepfin

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.UUID

/**
 * LIVE diagnostics (network, real providers). Skipped unless run with `-e liveProbe true`; output goes to logcat tag KeepfinProbe.
 * Kept out of the regular suite because provider behaviour changes without notice.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine get() = (context.applicationContext as KeepfinApplication).engineApi as MediaEngine
    private fun log(message: String) { Log.i("KeepfinProbe", message) }
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("liveProbe") == "true")
    private fun job(id: String) = engine.jobs.value.first { it.id == id }

    /** Inspection with up to 4 tries; every raw failure is logged so transient YouTube answers are visible. */
    private fun inspectRetry(url: String): MediaInfo? {
        repeat(4) { attempt ->
            try { return engine.mediaOf(url).also { log("inspect ok attempt=${attempt + 1} $url") } }
            catch (e: Exception) { log("inspect attempt=${attempt + 1} $url FAILED ${e.message?.take(300)}"); Thread.sleep(4000) }
        }
        return null
    }

    private val songs = listOf("fcXJMqDW8aI", "qG4iEwDGxtQ", "xbCxyxho1hs", "ZL3xRDCAaII")

    /** What the bundled yt-dlp reports about JS runtimes and challenge solving. */
    @Test fun a_runtimeVerbose() {
        enabled()
        engine.toolsUpdater.ensureInit()
        for (format in listOf("bestaudio", "bv*+ba/b")) {
            val request = YoutubeDLRequest("https://music.youtube.com/watch?v=fcXJMqDW8aI").apply {
                addOption("--ignore-config"); addOption("-v"); addOption("--no-playlist"); addOption("--simulate"); addOption("-f", format)
            }
            var lines = listOf<String>()
            val result = try { val r = YoutubeDL.getInstance().execute(request, "probe-v-${UUID.randomUUID()}") { _, _, _ -> }; lines = (r.err + System.lineSeparator() + r.out).lines(); "ok" } catch (e: Exception) { lines = e.message.orEmpty().lines(); "FAILED " + e.message?.take(400) }
            log("VERBOSE format=$format result=$result lines=${lines.size}")
            lines.filter { l -> Regex("JS runtime|js_runtimes|Python|yt-dlp version|Plugin|EJS|challenge|nsig|n sig|Solving|Only images|WARNING|ERROR|Extractor Plugins|Request Handlers|Exe versions|Optional libraries|Proxy map|Formats? ?(are|may)|Downloading \\d+ format|player|\\[info\\]", RegexOption.IGNORE_CASE).containsMatchIn(l) }
                .take(80).forEach { log("  v> ${it.take(300)}") }
        }
    }

    private fun waitDone(ids: List<String>, ms: Long = 300_000) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline && ids.any { job(it).status.active }) Thread.sleep(500)
    }

    private fun describe(id: String, label: String) {
        val j = job(id)
        log("RESULT $label status=${j.status} retries=${j.retryCount} size=${j.fileSize} file=${j.file} error=${j.error}")
        if (j.status != JobStatus.COMPLETED) log("  RAW> ${j.errorDetail?.replace("\n", " | ")?.take(1500)}")
    }

    /** Four YouTube Music songs as MP3 and as MP4 (the app's "Best quality" video preset), two jobs at a time. */
    @Test fun b_songsMp3AndMp4() {
        enabled()
        engine.updateSettings { it.copy(wifiOnly = false) }
        for (id in songs) {
            val url = "https://music.youtube.com/watch?v=$id"
            val media = inspectRetry(url) ?: continue
            val before = engine.jobs.value.map { it.id }.toSet()
            engine.enqueue(media, FormatRequest.Audio("mp3", "320"), DownloadOptions(embedThumbnail = true))
            engine.enqueue(media, FormatRequest.Video(), DownloadOptions(embedThumbnail = true))
            val ids = engine.jobs.value.map { it.id }.filter { it !in before }
            waitDone(ids)
            ids.forEach { describe(it, "$id ${job(it).label}") }
        }
    }

    private fun cover(path: String?): String {
        if (path == null) return "no file"
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val picture = retriever.embeddedPicture
            val dims = picture?.let { val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(it, 0, it.size, o); "${o.outWidth}x${o.outHeight} ${it.size}B" } ?: "none"
            fun m(key: Int) = retriever.extractMetadata(key)
            "cover=$dims title=${m(MediaMetadataRetriever.METADATA_KEY_TITLE)} artist=${m(MediaMetadataRetriever.METADATA_KEY_ARTIST)} " +
                "albumartist=${m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)} album=${m(MediaMetadataRetriever.METADATA_KEY_ALBUM)} " +
                "track=${m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)} year=${m(MediaMetadataRetriever.METADATA_KEY_YEAR)} date=${m(MediaMetadataRetriever.METADATA_KEY_DATE)}"
        } catch (e: Exception) { "unreadable ${e.message}" } finally { retriever.release() }
    }

    /** YouTube audio cover is cropped square; a Spotify track gets the Spotify tags and album cover. */
    @Test fun c_covers() {
        enabled()
        engine.updateSettings { it.copy(wifiOnly = false) }
        val before = engine.jobs.value.map { it.id }.toSet()
        listOf("https://music.youtube.com/watch?v=fcXJMqDW8aI", "https://www.youtube.com/watch?v=jNQXAC9IVRw").forEach { url ->
            engine.enqueue(inspectRetry(url) ?: return@forEach, FormatRequest.Audio("mp3", "320"), DownloadOptions(embedThumbnail = true))
        }
        val ytIds = engine.jobs.value.map { it.id }.filter { it !in before }
        waitDone(ytIds)
        ytIds.forEach { describe(it, "yt-cover ${job(it).url}"); log("  COVER ${cover(job(it).file)}") }

        val session = UUID.randomUUID().toString()
        val discovery = engine.discover("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", session, 1, 200) { }
        log("spotify discovery state=${discovery.state} entries=${discovery.entries.size} error=${discovery.error}")
        discovery.entries.take(3).forEach { entry ->
            log("spotify entry title=${entry.title} artist=${entry.artist} album=${entry.album} cover=${entry.thumbnail}")
            val before2 = engine.jobs.value.map { it.id }.toSet()
            engine.enqueueEntries(session, listOf(entry), DownloadPreset.MP3, DownloadOptions(embedThumbnail = true), null)
            val ids = engine.jobs.value.map { it.id }.filter { it !in before2 }
            waitDone(ids)
            ids.forEach {
                describe(it, "spotify ${job(it).title}"); log("  COVER ${cover(job(it).file)}")
                val f = CoverArt.fetch(job(it).thumbnail, File(context.cacheDir, "probe"), "src")
                log("  job cover url=${job(it).thumbnail} fetched=${f?.name} dims=" + f?.let { file -> BitmapFactory.Options().also { o -> o.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.path, o) }.let { o -> "${o.outWidth}x${o.outHeight}" } })
            }
        }
    }
}
