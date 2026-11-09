package app.keepfin.core.choice

/** One video quality of an inspected link, reduced to what the download choice needs. */
data class ChoiceVideo(
    val id: String,
    val height: Int = 0,
    val fps: Int? = null,
    val hdr: Boolean = false,
    /** h264 | hevc | vp9 | av1 | other */
    val codec: String = "other",
    val hasAudio: Boolean = false,
    val formatId: String = id,
    val size: Long? = null,
    val sizeApprox: Boolean = false,
    /** The height of the served quality a converted size is made from; null for a served one. */
    val convertedFrom: Int? = null,
)

/** One audio stream of an inspected link. */
data class ChoiceAudio(
    val formatId: String,
    /** aac | opus | mp3 | vorbis | flac | other */
    val codec: String = "other",
    val size: Long? = null,
    val sizeApprox: Boolean = false,
)

/** What the inspection found, as far as the download choice reads it; qualities and streams are best first. */
data class ChoiceMedia(
    val duration: Double? = null,
    val video: List<ChoiceVideo> = emptyList(),
    val audio: List<ChoiceAudio> = emptyList(),
)

/**
 * The picks for one download. [mode] is "video" or "audio"; [container] mp4|mkv|webm; [target] mp3|m4a|opus|flac|wav|original;
 * [quality] best|320|256|192|128. [audioTrack] is the audio stream (a language) merged into the video; [audioSource] the source
 * stream an audio download converts. Null means the best one.
 */
data class Choice(
    val mode: String = "video",
    val videoId: String? = null,
    val container: String = "mp4",
    val target: String = "mp3",
    val quality: String = "320",
    val audioTrack: String? = null,
    val audioSource: String? = null,
)

/**
 * What a preview opens with: the default [preset] (best, a height, audio-mp3...), the link's [url], whether the link is a song
 * matched from a music service ([music]) and whether YouTube Music links open as audio ([ytMusicAudio], the music-videos setting).
 */
data class Start(val preset: String, val url: String?, val music: Boolean = false, val ytMusicAudio: Boolean = true)

/** One container of the offer; [reason] says why it cannot be picked. */
data class ContainerOffer(val value: String, val allowed: Boolean, val reason: String?)

/** What the preview shows to pick from. */
data class Offer(val containers: List<ContainerOffer>)
