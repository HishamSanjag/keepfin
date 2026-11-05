package app.keepfin.core.formats

/** One selectable video quality, deduplicated per height + frame rate + HDR. */
data class VideoOption(
    /** Stable key within one [MediaInfo], e.g. "2160p60-hdr". */
    val id: String,
    val height: Int,
    val width: Int?,
    val fps: Int?,
    val hdr: Boolean,
    /** h264 | hevc | vp9 | av1 | other */
    val codec: String,
    val ext: String,
    val formatId: String,
    /** True when this stream already carries audio. */
    val hasAudio: Boolean,
    /** Estimated total bytes including the best matching audio stream. */
    val size: Long?,
    val sizeApprox: Boolean,
    /** Total bitrate in kbit/s, rounded. */
    val tbr: Double?,
)

data class AudioOption(
    val id: String,
    val formatId: String,
    /** aac | opus | mp3 | vorbis | flac | other */
    val codec: String,
    val ext: String,
    /** Average audio bitrate in kbit/s, rounded. */
    val abr: Double?,
    val size: Long?,
    val sizeApprox: Boolean,
    val language: String?,
    /** e.g. "original", "dubbed", "descriptive", "DRC". */
    val note: String?,
)

data class SubtitleTrack(val lang: String, val name: String?, val auto: Boolean)

data class MusicMetadata(val artist: String?, val album: String?, val track: String?, val releaseYear: Int?)

data class MediaInfo(
    val url: String,
    val webpageUrl: String?,
    val title: String,
    val uploader: String?,
    val duration: Double?,
    val thumbnail: String?,
    val extractor: String?,
    /** Human provider name, e.g. "YouTube Music". */
    val provider: String,
    val isLive: Boolean,
    val music: MusicMetadata?,
    val video: List<VideoOption>,
    val audio: List<AudioOption>,
    val subtitles: List<SubtitleTrack>,
    val chapters: Int,
    /** Set when the link is one item inside a playlist (watch?v=...&list=...). */
    val playlistUrl: String?,
    val playlistTitle: String?,
)

/** A size in bytes; [bytes] is null when nothing in the format says. */
data class Size(val bytes: Long?, val approx: Boolean)

/**
 * The only differences between the apps.
 * [codecPreference] "auto" ranks av1 and vp9 equally (the desktop); "vp9" ranks vp9 above av1 (phones decode vp9 in
 * hardware far more often; FMT-5 in fixtures/engine/DECISIONS.md). [skipDrm] drops formats flagged `has_drm`.
 */
data class FormatOptions(val codecPreference: String = "auto", val skipDrm: Boolean = false)
