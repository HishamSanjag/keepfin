package app.keepfin

import org.json.JSONObject
import app.keepfin.core.args.DownloadFormat
import app.keepfin.core.args.LabelAudio
import app.keepfin.core.args.LabelVideo
import app.keepfin.core.args.Quote
import app.keepfin.core.formats.FormatOptions
import app.keepfin.core.formats.Formats as Core
import app.keepfin.core.args.Labels as CoreLabels

/**
 * yt-dlp info JSON to typed MediaInfo with deduplicated, size-estimated options. The decisions are made by the shared core
 * (core/, package app.keepfin.core.formats), the same code the desktop app runs; this object hands it the info as text and
 * converts the result to the app's model.
 */
object Formats {
    data class Size(val bytes: Long?, val approx: Boolean)

    /** Phones decode vp9 in hardware far more often than av1 (FMT-5), and never get DRM-protected formats offered. */
    private val options = FormatOptions(codecPreference = "vp9", skipDrm = true)

    fun videoCodec(vcodec: String?): String = Core.videoCodec(vcodec)

    fun audioCodec(acodec: String?): String = Core.audioCodec(acodec)

    /** filesize, then filesize_approx, then tbr x duration / 8. */
    fun size(format: JSONObject, duration: Double?): Size = Core.sizeOf(format.toString(), duration).let { Size(it.bytes, it.approx) }

    fun subtitles(info: JSONObject): List<SubtitleTrack> = Core.subtitlesOf(info.toString()).map { SubtitleTrack(it.lang, it.name, it.auto) }

    /** The thumbnail of an info dict or a flat entry; YouTube videos without one get the id-based thumbnail. */
    fun thumbnail(info: JSONObject): String? = Core.thumbnailOf(info.toString()) ?: youtubeThumbnail(info)

    private fun JSONObject.text(key: String): String? = optString(key, "").takeIf { has(key) && !isNull(key) && it.isNotBlank() && it != "none" && it != "null" }

    private val youtubeId = Regex("^[\\w-]{11}$")

    /** Personal YouTube Music playlists list tracks without artwork; every YouTube video has an id-based thumbnail. */
    fun youtubeThumbnail(info: JSONObject): String? {
        val url = info.text("webpage_url") ?: info.text("url")
        val uri = url?.let { runCatching { java.net.URI(it) }.getOrNull() }
        val host = uri?.host?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("music.")
        if (info.text("ie_key") != "Youtube" && host != "youtube.com" && host != "youtu.be") return null
        val fromUrl = if (host == "youtu.be") uri?.path?.trimStart('/')
        else uri?.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.substringAfter("v=")
        val id = info.text("id")?.takeIf { youtubeId.matches(it) } ?: fromUrl?.takeIf { youtubeId.matches(it) }
        return id?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }
    }

    fun isCollection(info: JSONObject): Boolean = info.optString("_type") in setOf("playlist", "multi_video") && info.has("entries")

    /**
     * Older fields the app has always accepted when the main one is missing (title from `fulltitle`, uploader from `artist`,
     * the extractor name for the key), so the core sees one complete dict.
     */
    private fun completed(info: JSONObject): JSONObject {
        val needsTitle = info.text("title") == null && info.text("fulltitle") != null
        val needsUploader = info.text("uploader") == null && info.text("channel") == null && info.text("creator") == null && info.text("artist") != null
        val needsKey = info.text("extractor_key") == null && info.text("extractor") != null
        if (!needsTitle && !needsUploader && !needsKey) return info
        return JSONObject(info.toString()).also {
            if (needsTitle) it.put("title", info.text("fulltitle"))
            if (needsUploader) it.put("uploader", info.text("artist"))
            if (needsKey) it.put("extractor_key", info.text("extractor"))
        }
    }

    fun mediaInfo(url: String, info: JSONObject): MediaInfo {
        val m = Core.mediaInfo(url, completed(info).toString(), options)
        return MediaInfo(
            url = m.url, webpageUrl = m.webpageUrl, title = m.title, uploader = m.uploader, duration = m.duration,
            thumbnail = m.thumbnail ?: youtubeThumbnail(info), extractor = m.extractor, provider = m.provider, isLive = m.isLive,
            music = m.music?.let { MusicMetadata(it.artist, it.album, it.track, it.releaseYear) },
            video = m.video.map {
                VideoOption(it.id, it.height, it.width, it.fps, it.hdr, it.codec, it.ext, it.formatId, it.hasAudio, it.size, it.sizeApprox, it.tbr)
            },
            audio = m.audio.map { AudioOption(it.id, it.formatId, it.codec, it.ext, it.abr, it.size, it.sizeApprox, it.language, it.note) },
            subtitles = m.subtitles.map { SubtitleTrack(it.lang, it.name, it.auto) },
            chapters = m.chapters, playlistUrl = m.playlistUrl,
        )
    }
}

/**
 * Human labels and expected sizes for a chosen FormatRequest. The wording and the estimate are the shared core's
 * (app.keepfin.core.args.Labels, ARGS-13): the desktop's wording, and an estimate that also covers audio conversions.
 * The job row shows the size on its own, so the label does not repeat it.
 */
object Labels {
    private fun quote(format: FormatRequest, media: MediaInfo?): Quote {
        val request = when (format) {
            is FormatRequest.Video -> DownloadFormat.Video(format.maxHeight, format.formatId, format.audioFormatId, format.fps, format.codec, format.container)
            is FormatRequest.Audio -> DownloadFormat.Audio(format.target, format.quality, format.formatId)
            is FormatRequest.Gallery -> throw IllegalArgumentException("A photo post has no quality label.")
        }
        val video = media?.video.orEmpty().map { LabelVideo(it.formatId, it.height, it.fps, it.hdr, it.size, it.sizeApprox, it.hasAudio) }
        val audio = media?.audio.orEmpty().map { LabelAudio(it.formatId, it.size, it.sizeApprox, it.codec) }
        return CoreLabels.quote(request, video, audio, media?.duration, withSize = false)
    }

    fun label(format: FormatRequest, media: MediaInfo?): String =
        if (format is FormatRequest.Gallery) PhotoPosts.label(format, media) else quote(format, media).label

    /** Estimated bytes at enqueue time; null when the inspection gave no usable size. */
    fun expectedSize(format: FormatRequest, media: MediaInfo?): Long? = media?.takeIf { format !is FormatRequest.Gallery }?.let { quote(format, it).expectedSize }
}
