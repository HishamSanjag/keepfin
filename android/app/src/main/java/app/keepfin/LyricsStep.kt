package app.keepfin

import android.util.Log
import app.keepfin.core.lyrics.LyricsQuery
import java.io.File

/**
 * Best-effort lyrics for a finished song download. Runs before the public save so the public copy has them too.
 * Nothing here may fail, block or mark a download: every problem ends in "no lyrics" and the job completes as usual.
 *
 * Where lyrics go: embedded in the song file's lyrics tag (synced text keeps its [mm:ss.xx] stamps, so the player shows
 * synced lines); when the file cannot take the tag, a `<same name>.lrc` file next to it in the app's working folder,
 * which is published with the song as a sidecar and read by the library like any other .lrc.
 */
internal object LyricsStep {
    private const val TAG = "KeepfinLyrics"

    /** Logging must never be what breaks the step (it is also unavailable in JVM unit tests). */
    private fun warn(message: String, error: Throwable) { try { Log.w(TAG, message, error) } catch (_: Throwable) { } }

    enum class Result { SKIPPED, NOT_FOUND, EMBEDDED, SIDECAR, FAILED }

    /** The metadata the job already uses for tags and covers, with the file's own tags as the fallback. */
    fun queryFor(record: JobRecord, tags: Map<String, String>?): LyricsQuery? {
        val title = record.source?.title?.takeIf { it.isNotBlank() } ?: tags?.get("title")?.takeIf { it.isNotBlank() } ?: record.job.title
        val artist = record.source?.artist?.takeIf { it.isNotBlank() } ?: tags?.get("artist")?.takeIf { it.isNotBlank() }
            ?: Restriction.artistOf(record.job.uploader) ?: return null
        val album = record.source?.album?.takeIf { it.isNotBlank() } ?: tags?.get("album")?.takeIf { it.isNotBlank() }
        return LyricsQuery(title, artist, album, record.job.duration?.takeIf { it > 0 })
    }

    fun run(
        record: JobRecord, file: File, stem: String, enabled: Boolean, tools: TagTools,
        find: (LyricsQuery) -> String? = { LyricsLookup.find(it) },
    ): Result = try {
        if (!enabled || record.job.kind != MediaKind.AUDIO || !file.isFile || File(file.parentFile, "$stem.lrc").isFile) Result.SKIPPED
        else {
            val tags = if (tools.available()) tools.metadata(file) else null
            if (tags != null && FfOutput.lyrics(tags) != null) Result.SKIPPED
            else {
                val query = queryFor(record, tags)
                val text = query?.let(find)
                if (text == null) Result.NOT_FOUND
                else if (embed(file, text, tools)) Result.EMBEDDED
                else { File(file.parentFile, "$stem.lrc").writeText(text, Charsets.UTF_8); Result.SIDECAR }
            }
        }
    } catch (error: Throwable) {
        warn("lyrics skipped", error)
        Result.FAILED
    }

    /** Rewrites the file with a lyrics tag, copying every stream. The original stays untouched unless the new file checks out. */
    private fun embed(file: File, text: String, tools: TagTools): Boolean {
        if (!tools.available()) return false
        val temp = File(file.parentFile, "${file.nameWithoutExtension}.lyrics-tmp.${file.extension}")
        try {
            val out = tools.run(listOf("-v", "error", "-i", file.absolutePath, "-map", "0", "-c", "copy", "-map_metadata", "0", "-metadata", "lyrics=$text", "-y", temp.absolutePath), 90)
            if (out == null || out.exit != 0 || !temp.isFile || temp.length() < file.length() * 9 / 10) return false
            if (tools.metadata(temp)?.let(FfOutput::lyrics) == null) return false
            // Same folder: rename replaces the original in one step, so a failure leaves it intact.
            java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return true
        } catch (error: Exception) {
            warn("embedding failed", error); return false
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
