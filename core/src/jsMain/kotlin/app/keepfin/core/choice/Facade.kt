@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.choice

import app.keepfin.core.args.DownloadFormat

// The JavaScript surface of the download choice (see gui/shared/core/choice.ts): the inspected media and the picks come in as
// plain classes, answers go out as plain classes and arrays.

@JsExport
class JsChoiceVideo(
    val id: String,
    val height: Int,
    val fps: Int?,
    val hdr: Boolean,
    val codec: String,
    val hasAudio: Boolean,
    val formatId: String,
    val size: Double?,
    val sizeApprox: Boolean,
)

@JsExport
class JsChoiceAudio(val formatId: String, val codec: String, val size: Double?, val sizeApprox: Boolean)

@JsExport
class JsChoiceMedia(val duration: Double?, val video: Array<JsChoiceVideo>, val audio: Array<JsChoiceAudio>)

@JsExport
class JsChoice(
    val mode: String,
    val videoId: String?,
    val container: String,
    val target: String,
    val quality: String,
    val audioTrack: String?,
    val audioSource: String?,
)

@JsExport
class JsChoiceSize(val bytes: Double?, val approx: Boolean)

@JsExport
class JsContainerOffer(val value: String, val allowed: Boolean, val reason: String?)

/** What a choice sends to the engine: `type` is "video" or "audio"; the fields of the other type are null. */
@JsExport
class JsChoiceRequest(
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

private fun JsChoiceMedia.toCore() = ChoiceMedia(
    duration,
    video.map { ChoiceVideo(it.id, it.height, it.fps, it.hdr, it.codec, it.hasAudio, it.formatId, it.size?.toLong(), it.sizeApprox) },
    audio.map { ChoiceAudio(it.formatId, it.codec, it.size?.toLong(), it.sizeApprox) },
)

private fun JsChoice.toCore() = Choice(mode, videoId, container, target, quality, audioTrack, audioSource)

private fun Choice.toJs() = JsChoice(mode, videoId, container, target, quality, audioTrack, audioSource)

@JsExport
fun choiceContainers(media: JsChoiceMedia, choice: JsChoice): Array<JsContainerOffer> =
    DownloadChoice.offer(media.toCore(), choice.toCore()).containers.map { JsContainerOffer(it.value, it.allowed, it.reason) }.toTypedArray()

@JsExport
fun choiceSize(media: JsChoiceMedia, choice: JsChoice): JsChoiceSize =
    DownloadChoice.size(media.toCore(), choice.toCore()).let { JsChoiceSize(it.bytes?.toDouble(), it.approx) }

@JsExport
fun choiceRequest(media: JsChoiceMedia, choice: JsChoice): JsChoiceRequest = when (val format = DownloadChoice.request(media.toCore(), choice.toCore())) {
    is DownloadFormat.Video -> JsChoiceRequest("video", format.maxHeight, format.formatId, format.audioFormatId, format.fps, format.codec, format.container, null, null)
    is DownloadFormat.Audio -> JsChoiceRequest("audio", null, format.formatId, null, null, null, null, format.target, format.quality)
}

@JsExport
fun choiceShownFps(fps: Int?): Int? = DownloadChoice.shownFps(fps)

@JsExport
fun choiceVideoLabel(height: Int, fps: Int?): String = DownloadChoice.videoLabel(height, fps)

@JsExport
fun choiceModes(media: JsChoiceMedia): Array<String> = DownloadChoice.modes(media.toCore()).toTypedArray()

/** What a preview opens with; `youtubeMusicAudio` is true when YouTube Music links open as audio. */
@JsExport
fun choiceOpening(media: JsChoiceMedia, preset: String, url: String?, music: Boolean, youtubeMusicAudio: Boolean): JsChoice =
    DownloadChoice.opening(media.toCore(), Start(preset, url, music, youtubeMusicAudio)).toJs()

@JsExport
fun choiceApplyPreset(media: JsChoiceMedia, choice: JsChoice, preset: String): JsChoice =
    DownloadChoice.applyPreset(media.toCore(), choice.toCore(), preset).toJs()

@JsExport
fun choicePickTarget(choice: JsChoice, target: String): JsChoice = DownloadChoice.pickTarget(choice.toCore(), target).toJs()

@JsExport
fun choicePickVideo(media: JsChoiceMedia, choice: JsChoice, videoId: String): JsChoice =
    DownloadChoice.pickVideo(media.toCore(), choice.toCore(), videoId).toJs()
