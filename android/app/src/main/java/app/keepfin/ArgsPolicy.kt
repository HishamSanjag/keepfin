package app.keepfin

import app.keepfin.core.args.DownloadFormat
import app.keepfin.core.args.DownloadPlan
import app.keepfin.core.args.PlanOptions
import app.keepfin.core.args.PlanPlatform
import app.keepfin.core.args.SubtitleSettings
import app.keepfin.core.args.SourceTags as CoreTags

/** One yt-dlp option with an optional value. Values are passed as separate argv entries, never through a shell. */
data class Arg(val name: String, val value: String? = null)

/** Source metadata written into resolver downloads (Spotify/Apple Music/Deezer → YouTube Music). */
data class SourceTags(val title: String, val artist: String?, val album: String?, val track: Int? = null, val year: Int? = null, val trackCount: Int? = null, val isrc: String? = null)

/**
 * Pure policy: structured intent (FormatRequest + DownloadOptions + EngineSettings) → validated yt-dlp arguments.
 * Nothing from the UI or a shared link is ever passed through as a raw argument.
 *
 * The decisions (validation, presets, format selection and sort, merge and remux, conversion, subtitles, cover art,
 * SponsorBlock, sections and the embedded tags) are the shared core's (app.keepfin.core.args, the same code the desktop app
 * runs); this object maps Android's types to it and keeps what is Android's own: the output flags, the reliability flags and
 * the multi-connection transfer flags.
 */
object ArgsPolicy {
    private fun core(request: FormatRequest): DownloadFormat = when (request) {
        is FormatRequest.Video -> DownloadFormat.Video(request.maxHeight, request.formatId, request.audioFormatId, request.fps, request.codec, request.container, request.scaleHeight)
        is FormatRequest.Audio -> DownloadFormat.Audio(request.target, request.quality, request.formatId)
        is FormatRequest.Gallery -> throw IllegalArgumentException("A photo post has no download plan.")
    }

    /**
     * Subtitle languages are only checked while subtitles are on: the language field can hold half-typed text while the switch
     * is off (the desktop checks them always).
     */
    private fun core(options: DownloadOptions): PlanOptions = PlanOptions(
        subtitles = options.subtitles.takeIf { it.enabled }?.let { SubtitleSettings(true, it.languages, it.auto, it.embed) },
        embedThumbnail = options.embedThumbnail, embedMetadata = options.embedMetadata, embedChapters = options.embedChapters,
        writeThumbnail = options.writeThumbnail, sponsorBlock = options.sponsorBlock,
        sectionStart = options.sectionStart, sectionEnd = options.sectionEnd,
    )

    /** Throws [IllegalArgumentException] with the message to show when the request is not acceptable. */
    fun validate(request: FormatRequest): FormatRequest {
        if (request is FormatRequest.Gallery) { PhotoPosts.validate(request); return request }
        DownloadPlan.validate(core(request))
        return request
    }

    fun validate(options: DownloadOptions): DownloadOptions {
        DownloadPlan.validate(core(options))
        return options
    }

    fun isSubtitleLanguages(value: String): Boolean = DownloadPlan.isSubtitleLanguages(value)

    fun preset(preset: DownloadPreset, container: String = "mp4"): FormatRequest = when (val f = DownloadPlan.preset(preset.wire, container)) {
        is DownloadFormat.Video -> FormatRequest.Video(maxHeight = f.maxHeight, codec = f.codec, container = f.container)
        is DownloadFormat.Audio -> FormatRequest.Audio(f.target, f.quality)
    }

    fun kind(request: FormatRequest) = when (request) {
        is FormatRequest.Audio -> MediaKind.AUDIO
        is FormatRequest.Gallery -> MediaKind.PHOTOS
        is FormatRequest.Video -> MediaKind.VIDEO
    }

    /**
     * The format selection and the options of a download, in the order the engine receives them. [squareCover] is a YouTube or
     * YouTube Music source (its audio covers are cropped square); [mutagen] says the engine can embed cover art into opus, flac
     * and original-audio files (ARGS-9).
     */
    fun download(request: FormatRequest, options: DownloadOptions, squareCover: Boolean = false, mutagen: Boolean = false): List<Arg> =
        DownloadPlan.argPairs(DownloadPlan.plan(core(request), core(options), PlanPlatform(coverArtForOpusFlac = mutagen), squareCover))
            .map { Arg(it.first, it.second) }

    private fun pairs(args: List<String>): List<Arg> = args.chunked(2).map { Arg(it[0], it[1]) }

    /**
     * Embedded tags for downloads without music tags: the clean job title replaces a caption-sized title, and the description
     * falls back to the title text yt-dlp found when the page has no description, so the original wording is never lost.
     */
    fun metadata(title: String?): List<Arg> = pairs(DownloadPlan.metadataArgs(title))

    fun tags(tags: SourceTags): List<Arg> = pairs(DownloadPlan.tagArgs(CoreTags(tags.title, tags.artist, tags.album, tags.track, tags.year, tags.isrc)))

    fun reliability(fragments: Int): List<Arg> = listOf(
        Arg("--retries", "10"), Arg("--fragment-retries", "10"), Arg("--extractor-retries", "3"),
        Arg("--retry-sleep", "exp=1:20"), Arg("--retry-sleep", "fragment:exp=1:20"),
        Arg("--socket-timeout", "20"), Arg("--concurrent-fragments", fragments.coerceIn(1, 16).toString()),
        Arg("--continue"), Arg("--no-mtime"),
    )

    /**
     * aria2c for plain http(s) streams only; HLS/DASH stay on the native downloader, which already
     * fetches fragments concurrently. The bundled CA file is passed because aria2c has no system store.
     */
    fun aria2c(caCertificate: String?, connections: Int = 8): List<Arg> = listOf(
        Arg("--downloader", "http:libaria2c.so"),
        Arg("--downloader", "m3u8:native"), Arg("--downloader", "dash:native"),
        Arg("--downloader-args", buildString {
            append("aria2c:-x $connections -s $connections -k 1M --summary-interval=1 --console-log-level=warn")
            if (caCertificate != null) append(" --ca-certificate=").append(caCertificate.replace(" ", "\\ "))
        }),
    )

    const val PROGRESS_PREFIX = "[keepfin]"
    val progressTemplate = "download:$PROGRESS_PREFIX %(progress.status)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|" +
        "%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|%(info.format_id)s"

    /** Second printed line of a run: the source's tags, genres and categories as JSON. */
    const val TAGS_PREFIX = "keepfin-tags:"
    private const val TAGS_PRINT = "after_move:${TAGS_PREFIX}%(.{tags,genres,categories})j"

    fun output(template: String): List<Arg> = listOf(
        Arg("--ignore-config"), Arg("--no-playlist"), Arg("--newline"), Arg("--no-colors"), Arg("--progress"),
        Arg("--progress-template", progressTemplate), Arg("--print", "after_move:filepath"), Arg("--print", TAGS_PRINT), Arg("--no-simulate"),
        Arg("--no-quiet"), Arg("-o", template),
    )
}
