@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.args

// The JavaScript surface of the args core (see gui/shared/core/args.ts): requests and options come in as JSON text
// (checked here with the desktop's rules), results go out as plain classes. A failure is `error`, the message the user sees.

@JsExport
class JsRequest(
    val type: String,
    val maxHeight: Int?,
    val formatId: String?,
    val audioFormatId: String?,
    val fps: Int?,
    val codec: String?,
    val container: String?,
    val target: String?,
    val quality: String?,
)

@JsExport
class JsRequestResult(val request: JsRequest?, val error: String?)

@JsExport
class JsSubtitleOptions(val enabled: Boolean, val languages: String, val auto: Boolean, val embed: Boolean)

@JsExport
class JsOptions(
    val subtitles: JsSubtitleOptions?,
    val embedThumbnail: Boolean?,
    val embedMetadata: Boolean?,
    val embedChapters: Boolean?,
    val writeThumbnail: Boolean?,
    val writeDescription: Boolean?,
    val writeInfoJson: Boolean?,
    val splitChapters: Boolean?,
    val sponsorBlock: String?,
    val sectionStart: Double?,
    val sectionEnd: Double?,
)

@JsExport
class JsOptionsResult(val options: JsOptions?, val error: String?)

@JsExport
class JsArgsResult(val args: Array<String>?, val error: String?)

@JsExport
class JsLabelVideo(val formatId: String, val height: Int, val fps: Int?, val hdr: Boolean, val size: Double?, val sizeApprox: Boolean, val hasAudio: Boolean)

@JsExport
class JsLabelAudio(val formatId: String, val size: Double?, val sizeApprox: Boolean, val codec: String)

@JsExport
class JsQuote(val label: String, val expectedSize: Double?, val approx: Boolean)

@JsExport
class JsQuoteResult(val quote: JsQuote?, val error: String?)

private fun DownloadFormat.toJs(): JsRequest = when (this) {
    is DownloadFormat.Video -> JsRequest("video", maxHeight, formatId, audioFormatId, fps, codec, container, null, null)
    is DownloadFormat.Audio -> JsRequest("audio", null, formatId, null, null, null, null, target, quality)
}

private fun PlanOptions.toJs() = JsOptions(
    subtitles?.let { JsSubtitleOptions(it.enabled, it.languages, it.auto, it.embed) },
    embedThumbnail, embedMetadata, embedChapters, writeThumbnail, writeDescription, writeInfoJson, splitChapters,
    sponsorBlock, sectionStart, sectionEnd,
)

private inline fun <T> checked(block: () -> T): Pair<T?, String?> =
    try {
        Pair(block(), null)
    } catch (e: IllegalArgumentException) {
        Pair(null, e.message ?: "Invalid download request.")
    }

@JsExport
fun argsValidateFormat(json: String): JsRequestResult = checked { ArgsJson.format(json).toJs() }.let { JsRequestResult(it.first, it.second) }

@JsExport
fun argsValidateOptions(json: String?): JsOptionsResult = checked { ArgsJson.options(json).toJs() }.let { JsOptionsResult(it.first, it.second) }

@JsExport
fun argsPreset(id: String, container: String): JsRequestResult = checked { DownloadPlan.preset(id, container).toJs() }.let { JsRequestResult(it.first, it.second) }

/** The engine arguments for a request: the format selection, then the options. */
@JsExport
fun argsBuild(
    formatJson: String,
    optionsJson: String?,
    coverArtForOpusFlac: Boolean,
    squareCover: Boolean,
    chapterTemplate: String?,
): JsArgsResult = checked {
    val plan = DownloadPlan.plan(ArgsJson.format(formatJson), ArgsJson.options(optionsJson), PlanPlatform(coverArtForOpusFlac), squareCover, chapterTemplate)
    DownloadPlan.args(plan).toTypedArray()
}.let { JsArgsResult(it.first, it.second) }

@JsExport
fun argsMetadata(title: String?): Array<String> = DownloadPlan.metadataArgs(title).toTypedArray()

@JsExport
fun argsMetadataLiteral(value: String): String = DownloadPlan.metadataLiteral(value)

@JsExport
fun argsFormatBytes(bytes: Double): String = Labels.formatBytes(bytes)

/** The label alone (a collection entry has no inspected media). */
@JsExport
fun argsLabel(formatJson: String): JsQuoteResult = checked { Labels.label(ArgsJson.format(formatJson)) }.let {
    JsQuoteResult(it.first?.let { label -> JsQuote(label, null, false) }, it.second)
}

/** The label and the expected size of a request against the inspected qualities. */
@JsExport
fun argsQuote(formatJson: String, video: Array<JsLabelVideo>, audio: Array<JsLabelAudio>, duration: Double?, withSize: Boolean): JsQuoteResult = checked {
    Labels.quote(
        ArgsJson.format(formatJson),
        video.map { LabelVideo(it.formatId, it.height, it.fps, it.hdr, it.size?.toLong(), it.sizeApprox, it.hasAudio) },
        audio.map { LabelAudio(it.formatId, it.size?.toLong(), it.sizeApprox, it.codec) },
        duration, withSize,
    )
}.let { r -> JsQuoteResult(r.first?.let { JsQuote(it.label, it.expectedSize?.toDouble(), it.approx) }, r.second) }
