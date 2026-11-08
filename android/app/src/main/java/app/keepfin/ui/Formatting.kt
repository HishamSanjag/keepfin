package app.keepfin.ui

import app.keepfin.AudioOption
import app.keepfin.DownloadPreset
import app.keepfin.FormatRequest
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.MediaInfo
import app.keepfin.PhotoPosts
import app.keepfin.VideoOption
import app.keepfin.core.choice.Choice
import app.keepfin.core.choice.ChoiceAudio
import app.keepfin.core.choice.ChoiceMedia
import app.keepfin.core.choice.ChoiceVideo
import app.keepfin.core.choice.ContainerOffer
import app.keepfin.core.args.DownloadFormat
import app.keepfin.core.args.Labels
import app.keepfin.core.choice.DownloadChoice
import app.keepfin.core.choice.Start
import app.keepfin.core.formats.Size
import kotlin.math.roundToLong

/** Pure presentation helpers. Every figure shown in the UI comes from the engine; nothing is invented. */
object Format {
    /** The shared core's words for a size ("1.0 KB" from 1000 bytes), the same as the desktop's. */
    fun bytes(value: Long?): String = when {
        value == null || value < 0 -> "—"
        value == 0L -> "0 B"
        else -> Labels.formatBytes(value.toDouble())
    }

    fun size(value: Long?, approx: Boolean): String? = value?.let { (if (approx) "≈" else "") + bytes(it) }

    /** "280 / 665 MB" when both share a unit, else "280 MB / 1.2 GB". */
    fun progressBytes(done: Long, total: Long): String {
        val a = bytes(done); val b = bytes(total)
        val unitA = a.substringAfter(' '); val unitB = b.substringAfter(' ')
        return if (unitA == unitB) "${a.substringBefore(' ')} / $b" else "$a / $b"
    }

    fun speed(bytesPerSecond: Long?): String? = bytesPerSecond?.takeIf { it > 0 }?.let { bytes(it) + "/s" }

    fun eta(seconds: Long?): String? {
        if (seconds == null || seconds < 0) return null
        if (seconds < 60) return "${seconds}s"
        if (seconds < 3600) return "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
        return "${seconds / 3600}h ${((seconds % 3600) / 60).toString().padStart(2, '0')}m"
    }

    fun duration(seconds: Double?): String? {
        if (seconds == null || seconds <= 0) return null
        val total = seconds.roundToLong()
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
    }

    /** Parses "90", "1:30" or "1:02:03" into seconds; null when blank or invalid. */
    fun parseTime(text: String): Double? {
        val value = text.trim()
        if (value.isEmpty()) return null
        val parts = value.split(':')
        if (parts.size > 3) return null
        var total = 0.0
        for (part in parts) {
            val n = part.toDoubleOrNull() ?: return null
            if (n < 0) return null
            total = total * 60 + n
        }
        return total
    }

    fun resolutionBadge(height: Int): String? = when {
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        height >= 1080 -> "FHD"
        height >= 720 -> "HD"
        else -> null
    }

    fun codec(value: String): String = when (value.lowercase()) {
        "h264", "avc", "avc1" -> "H.264"
        "hevc", "h265" -> "HEVC"
        "vp9" -> "VP9"
        "av1" -> "AV1"
        "aac" -> "AAC"
        "opus" -> "Opus"
        "mp3" -> "MP3"
        "vorbis" -> "Vorbis"
        "flac" -> "FLAC"
        else -> value.uppercase()
    }

    fun videoLabel(option: VideoOption): String = DownloadChoice.videoLabel(option.height, option.fps)

    fun relativeDay(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - timestamp).coerceAtLeast(0)
        val minutes = diff / 60_000
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 24 * 60 -> "${minutes / 60}h ago"
            minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)}d ago"
            else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(timestamp))
        }
    }

    fun host(url: String): String = try {
        java.net.URI(url).host?.removePrefix("www.")?.removePrefix("m.") ?: url
    } catch (_: Exception) { url }

    fun phase(job: Job): String = when (job.status) {
        JobStatus.QUEUED -> if (job.retryAt != null) job.phase ?: "Retrying" else "Waiting"
        JobStatus.PREPARING -> job.phase ?: "Preparing"
        JobStatus.DOWNLOADING -> job.phase ?: "Downloading"
        JobStatus.PROCESSING -> job.phase ?: "Processing"
        JobStatus.PAUSED -> "Paused"
        JobStatus.COMPLETED -> if (job.filesRemoved) "File removed" else if (job.saveError != null) "Downloaded · not saved" else if (job.publicUri == null && job.phase == app.keepfin.MusicVideoLink.WAITING) job.phase else "Saved"
        JobStatus.FAILED -> "Failed"
        JobStatus.CANCELLED -> "Cancelled"
    }

    /** "Movies/Keepfin" from a public path; falls back to the folder implied by the media kind. */
    fun savedFolder(job: Job): String {
        val path = job.publicPath
        if (!path.isNullOrBlank()) {
            val parent = path.trimEnd('/').substringBeforeLast('/', "")
            val parts = parent.split('/').filter { it.isNotBlank() }
            if (parts.size >= 2) return parts.takeLast(2).joinToString("/")
            if (parent.isNotBlank()) return parent
        }
        return when (job.kind) {
            app.keepfin.MediaKind.AUDIO -> "Music/Keepfin"
            app.keepfin.MediaKind.PHOTOS -> "Pictures/Keepfin"
            app.keepfin.MediaKind.VIDEO -> "Movies/Keepfin"
        }
    }
}

/** Link extraction for paste, share and multi-link batches. */
object Links {
    private val pattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    fun extract(text: String): List<String> = pattern.findAll(text)
        .map { it.value.trimEnd('.', ',', ')', ']', '!', '?', ';') }
        .filter { isComplete(it) }
        .distinct().toList()

    /** A link that looks finished enough to inspect without the user pressing anything. */
    fun isComplete(url: String): Boolean = try {
        val uri = java.net.URI(url)
        val host = uri.host ?: ""
        (uri.scheme == "https" || uri.scheme == "http") && host.contains('.') && !host.endsWith('.') && host.substringAfterLast('.').length >= 2
    } catch (_: Exception) { false }
}

/** The user's choice in media review. Pure: the shared choice module turns it into a [FormatRequest] and decides what it offers and sizes. */
data class FormatChoice(
    val audioMode: Boolean = false,
    val videoId: String? = null,
    /** mp4 | mkv | webm */
    val container: String = "mp4",
    /** mp3 | m4a | opus | flac | wav | original */
    val audioTarget: String = "mp3",
    /** best | 320 | 256 | 192 | 128 */
    val audioQuality: String = "best",
    val audioFormatId: String? = null,
    /** A photo post: the 1-based positions left out (empty = every file is chosen). */
    val leftOut: List<Int> = emptyList(),
) {
    fun video(media: MediaInfo): VideoOption? = media.video.firstOrNull { it.id == videoId } ?: media.video.firstOrNull()
    /** The source stream an Original download takes: the picked one, else the first (best, as the core ranks them). */
    fun audioStream(media: MediaInfo): AudioOption? = media.audio.firstOrNull { it.formatId == audioFormatId } ?: media.audio.firstOrNull()

    /** The picks as the shared choice module reads them. */
    fun toCore(media: ChoiceMedia): Choice = Choice(
        mode = if (audioMode || media.video.isEmpty()) "audio" else "video", videoId = videoId, container = container, target = audioTarget, quality = audioQuality, audioSource = audioFormatId,
    )

    /** The containers to show, each with whether the chosen quality can be saved in it and why not. */
    fun containers(media: ChoiceMedia): List<ContainerOffer> = DownloadChoice.offer(media, toCore(media)).containers

    /** The choice after picking a quality: the container goes back to MP4 when the new quality cannot use it. */
    fun withVideo(media: ChoiceMedia, id: String): FormatChoice =
        copy(videoId = id, audioMode = false, container = DownloadChoice.pickVideo(media, toCore(media), id).container)

    /** The choice after picking an audio format: MP3 starts at 320, another format keeps the quality; the picked source stream is cleared. */
    fun withTarget(target: String): FormatChoice = DownloadChoice.pickTarget(toCore(ChoiceMedia()), target).let {
        copy(audioTarget = it.target, audioQuality = it.quality, audioFormatId = it.audioSource, audioMode = true)
    }

    /** What the choice sends to the engine, from the shared choice module. */
    fun request(media: MediaInfo): FormatRequest = media.gallery?.let { PhotoPosts.request(it.count, PhotoPosts.chosen(it.count, leftOut)) } ?: media.forChoice().let { core ->
        when (val format = DownloadChoice.request(core, toCore(core))) {
            is DownloadFormat.Video -> FormatRequest.Video(format.maxHeight, format.formatId, format.audioFormatId, format.fps, format.codec, format.container)
            is DownloadFormat.Audio -> FormatRequest.Audio(format.target, format.quality, format.formatId)
        }
    }

    /** The photo post with [position] left out, or back in when it was. */
    fun toggled(position: Int): FormatChoice = copy(leftOut = if (position in leftOut) leftOut - position else leftOut + position)

    /** The size of what the choice downloads, from the shared choice module (one rule for the preview and the job). */
    fun size(media: ChoiceMedia): Size = DownloadChoice.size(media, toCore(media))

    companion object {
        val LOSSY = setOf("mp3", "m4a", "opus")
        val TARGETS = listOf("mp3", "m4a", "opus", "flac", "wav", "original")
        val QUALITIES = listOf("best", "320", "256", "192", "128")

        /**
         * What the preview opens with, from the shared choice module: the default [preset], the [url], whether it is a matched song
         * ([music]) and whether YouTube Music links open as audio ([ytMusicAudio]).
         */
        fun initial(media: MediaInfo, preset: DownloadPreset, url: String? = null, music: Boolean = false, ytMusicAudio: Boolean = true): FormatChoice {
            val opened = DownloadChoice.opening(media.forChoice(), Start(preset.wire, url, music, ytMusicAudio))
            return FormatChoice(
                audioMode = opened.mode == "audio", videoId = opened.videoId, container = opened.container, audioTarget = opened.target,
                audioQuality = opened.quality, audioFormatId = opened.audioSource,
            )
        }

        /** Preset → request for lists and batches, where one quality applies to every link (MP3 at 320, like one song). */
        fun forPreset(preset: DownloadPreset): FormatRequest = when (val format = DownloadChoice.forList(preset.wire)) {
            is DownloadFormat.Audio -> FormatRequest.Audio(format.target, format.quality)
            is DownloadFormat.Video -> FormatRequest.Video(maxHeight = format.maxHeight, container = format.container)
        }
    }
}

fun DownloadPreset.isAudio() = wire.startsWith("audio-")

/** The inspection as the shared choice module reads it. */
fun MediaInfo.forChoice() = ChoiceMedia(
    duration,
    video.map { ChoiceVideo(it.id, it.height, it.fps, it.hdr, it.codec, it.hasAudio, it.formatId, it.size, it.sizeApprox) },
    audio.map { ChoiceAudio(it.formatId, it.codec, it.size, it.sizeApprox) },
)
