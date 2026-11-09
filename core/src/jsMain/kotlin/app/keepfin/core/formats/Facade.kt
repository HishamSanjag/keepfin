@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.formats

// The JavaScript surface of the formats core (see gui/shared/core/formats.ts): arrays instead of lists,
// nullable numbers (sizes are doubles, exact below 2^53), plain classes.

@JsExport
class JsVideoOption(
    val id: String,
    val height: Int,
    val width: Int?,
    val fps: Int?,
    val hdr: Boolean,
    val codec: String,
    val ext: String,
    val formatId: String,
    val hasAudio: Boolean,
    val size: Double?,
    val sizeApprox: Boolean,
    val tbr: Double?,
    val convertedFrom: Int?,
)

@JsExport
class JsAudioOption(
    val id: String,
    val formatId: String,
    val codec: String,
    val ext: String,
    val abr: Double?,
    val size: Double?,
    val sizeApprox: Boolean,
    val language: String?,
    val note: String?,
)

@JsExport
class JsSubtitleTrack(val lang: String, val name: String?, val auto: Boolean)

@JsExport
class JsMusicMetadata(val artist: String?, val album: String?, val track: String?, val releaseYear: Int?)

@JsExport
class JsMediaInfo(
    val url: String,
    val webpageUrl: String?,
    val title: String,
    val uploader: String?,
    val duration: Double?,
    val thumbnail: String?,
    val extractor: String?,
    val provider: String,
    val isLive: Boolean,
    val music: JsMusicMetadata?,
    val video: Array<JsVideoOption>,
    val audio: Array<JsAudioOption>,
    val subtitles: Array<JsSubtitleTrack>,
    val chapters: Int,
    val playlistUrl: String?,
    val playlistTitle: String?,
)

@JsExport
class JsSize(val size: Double?, val approx: Boolean)

private fun VideoOption.toJs() = JsVideoOption(id, height, width, fps, hdr, codec, ext, formatId, hasAudio, size?.toDouble(), sizeApprox, tbr, convertedFrom)

private fun AudioOption.toJs() = JsAudioOption(id, formatId, codec, ext, abr, size?.toDouble(), sizeApprox, language, note)

private fun SubtitleTrack.toJs() = JsSubtitleTrack(lang, name, auto)

@JsExport
fun formatsMediaInfo(url: String, infoJson: String, codecPreference: String, skipDrm: Boolean, convertSmaller: Boolean): JsMediaInfo {
    val m = Formats.mediaInfo(url, infoJson, FormatOptions(codecPreference, skipDrm, convertSmaller))
    return JsMediaInfo(
        m.url, m.webpageUrl, m.title, m.uploader, m.duration, m.thumbnail, m.extractor, m.provider, m.isLive,
        m.music?.let { JsMusicMetadata(it.artist, it.album, it.track, it.releaseYear) },
        m.video.map { it.toJs() }.toTypedArray(), m.audio.map { it.toJs() }.toTypedArray(),
        m.subtitles.map { it.toJs() }.toTypedArray(), m.chapters, m.playlistUrl, m.playlistTitle,
    )
}

@JsExport
fun formatsSize(formatJson: String, duration: Double?): JsSize = Formats.sizeOf(formatJson, duration).let { JsSize(it.bytes?.toDouble(), it.approx) }

@JsExport
fun formatsVideoCodec(vcodec: String?): String = Formats.videoCodec(vcodec)

@JsExport
fun formatsAudioCodec(acodec: String?): String = Formats.audioCodec(acodec)

@JsExport
fun formatsSubtitles(infoJson: String): Array<JsSubtitleTrack> = Formats.subtitlesOf(infoJson).map { it.toJs() }.toTypedArray()

@JsExport
fun formatsThumbnail(infoJson: String): String? = Formats.thumbnailOf(infoJson)

@JsExport
fun titleClean(raw: String?, uploader: String?, provider: String, kind: String): String = Title.clean(raw, uploader, provider, kind)

/** Why a playlist entry cannot be downloaded (see Unavailable), or null. */
@JsExport
fun formatsUnavailable(title: String?, uploader: String?, duration: Double?, youtube: Boolean): String? = Unavailable.reason(title, uploader, duration, youtube)

@JsExport
fun formatsUnavailableLabel(): String = Unavailable.LABEL

@JsExport
fun formatsHeightLabel(height: Int): String = Formats.heightLabel(height)

@JsExport
class JsSizeProbe(val formatId: String, val url: String, val headerNames: Array<String>, val headerValues: Array<String>)

/** Formats with no size that a request to their link can measure (see Formats.sizeProbes). */
@JsExport
fun formatsSizeProbes(infoJson: String, limit: Int): Array<JsSizeProbe> =
    Formats.sizeProbesOf(infoJson, limit).map {
        JsSizeProbe(it.formatId, it.url, it.headers.keys.toTypedArray(), it.headers.values.toTypedArray())
    }.toTypedArray()

/** The converter's arguments for a smaller size (see Conversion.args). */
@JsExport
fun formatsConversionArgs(input: String, output: String, height: Int, kbps: Int, copyAudio: Boolean): Array<String> = Conversion.args(input, output, height, kbps, copyAudio).toTypedArray()

@JsExport
class JsConversionProbe(val duration: Double?, val copyAudio: Boolean)

/** The length and the audio of a file, read from the converter report of `-i file` (see Conversion.probe). */
@JsExport
fun formatsConversionProbe(report: String): JsConversionProbe = Conversion.probe(report).let { JsConversionProbe(it.duration, it.copyAudio) }

/** The video bitrate (kbit/s) to convert a downloaded file at (see Conversion.kbpsFor). */
@JsExport
fun formatsConversionKbps(height: Int, sourceBytes: Double?, duration: Double?): Int = Conversion.kbpsFor(height, sourceBytes?.toLong(), duration)

/** A converter progress line as a percent of the duration (seconds), or null. */
@JsExport
fun formatsConversionPercent(line: String, duration: Double): Double? = Conversion.percent(line, duration)
