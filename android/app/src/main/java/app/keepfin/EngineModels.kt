package app.keepfin

import app.keepfin.core.gallery.GalleryItem

// Studio 2 typed engine model shared by the engine, service, notifications and Compose UI.
// Mirrors gui/shared/contracts.ts so desktop and Android speak the same product language.

enum class JobStatus(val wire: String) {
    QUEUED("queued"), PREPARING("preparing"), DOWNLOADING("downloading"), PROCESSING("processing"),
    COMPLETED("completed"), FAILED("failed"), CANCELLED("cancelled"), PAUSED("paused");
    val active get() = this == QUEUED || this == PREPARING || this == DOWNLOADING || this == PROCESSING
    companion object { fun of(value: String?) = entries.firstOrNull { it.wire == value } ?: FAILED }
}

enum class MediaKind(val wire: String) {
    VIDEO("video"), AUDIO("audio"), PHOTOS("gallery");
    companion object { fun of(value: String?) = entries.firstOrNull { it.wire == value } ?: VIDEO }
}

data class VideoOption(
    val id: String,
    val height: Int,
    val width: Int?,
    val fps: Int?,
    val hdr: Boolean,
    /** h264 | hevc | vp9 | av1 | other */
    val codec: String,
    val ext: String,
    val formatId: String,
    val hasAudio: Boolean,
    /** Estimated total bytes including the matching audio stream. */
    val size: Long?,
    val sizeApprox: Boolean,
    val tbr: Double?,
    /** The height of the served size a converted option is made from after downloading; null for a served one. */
    val convertedFrom: Int? = null,
)

data class AudioOption(
    val id: String,
    val formatId: String,
    /** aac | opus | mp3 | vorbis | flac | other */
    val codec: String,
    val ext: String,
    val abr: Double?,
    val size: Long?,
    val sizeApprox: Boolean,
    val language: String?,
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
    /** Set when the link is one item inside a playlist (watch?v=…&list=…). */
    val playlistUrl: String?,
    /** Set for Spotify/Apple Music/Deezer links matched to YouTube Music. */
    val match: MusicMatch? = null,
    /** Set for a photo post (images, a carousel): the files the gallery engine found. [video] and [audio] are empty then. */
    val gallery: GalleryPost? = null,
    /** Set when a site asked for a sign-in and Keepfin's own was used to inspect the link: the row text ("Signed in"). The download then uses it from the first try. */
    val signedIn: String? = null,
)

/** The files of a photo post, in the gallery engine's order ([GalleryItem.position] is 1-based and is what a range selects). */
data class GalleryPost(val count: Int, val previews: List<String>, val items: List<GalleryItem>, val truncated: Boolean)

data class MusicMatch(
    /** spotify | apple-music | deezer */
    val source: String,
    val sourceUrl: String,
    val matchedUrl: String?,
    val matchedTitle: String?,
    /** 0–1 heuristic confidence. */
    val confidence: Double?,
    /** album = the track at its place on the same YouTube Music album | search = the songs search | chosen = the recording the user picked | null on older matches. */
    val method: String? = null,
    /** method album: the YouTube Music album and the track's place on it. */
    val album: String? = null,
    val position: Int? = null,
    /** Length of the matched recording in seconds, when known. */
    val durationSec: Double? = null,
    /** Up to three other uploads of the same recording, best first (for a later "Not right? Pick another"). */
    val alternates: List<MusicAlternate> = emptyList(),
)

data class MusicAlternate(val url: String, val title: String, val durationSec: Double?, val confidence: Double)

sealed interface InspectResult {
    data class Media(val media: MediaInfo) : InspectResult
    data class Collection(val url: String, val title: String?) : InspectResult
}

/** Structured intent. The engine alone turns this into validated yt-dlp arguments. */
sealed interface FormatRequest {
    data class Video(
        val maxHeight: Int? = null,
        val formatId: String? = null,
        val audioFormatId: String? = null,
        val fps: Int? = null,
        /** auto | h264 | vp9 | av1 | hevc */
        val codec: String = "auto",
        /** mp4 | mkv | webm */
        val container: String = "mp4",
        /** A converted size: the short side the downloaded [formatId] is converted down to afterwards. */
        val scaleHeight: Int? = null,
    ) : FormatRequest
    data class Audio(
        /** mp3 | m4a | opus | flac | wav | original */
        val target: String = "mp3",
        /** best | 320 | 256 | 192 | 128 */
        val quality: String = "best",
        val formatId: String? = null,
    ) : FormatRequest
    /** The photos of a post: [items] are the chosen 1-based positions (null = all [count]). */
    data class Gallery(val count: Int, val items: List<Int>? = null) : FormatRequest
}

data class SubtitleOptions(val enabled: Boolean = false, val languages: String = "en.*", val auto: Boolean = false, val embed: Boolean = true)

data class DownloadOptions(
    val subtitles: SubtitleOptions = SubtitleOptions(),
    val embedThumbnail: Boolean = true,
    val embedMetadata: Boolean = true,
    val embedChapters: Boolean = true,
    /** off | mark | remove */
    val sponsorBlock: String = "off",
    val sectionStart: Double? = null,
    val sectionEnd: Double? = null,
    val writeThumbnail: Boolean = false,
)

/** Presets used for defaults and collection downloads. Order is the UI order. */
enum class DownloadPreset(val wire: String, val label: String) {
    BEST("best", "Best quality"),
    P2160("2160", "4K · 2160p"),
    P1440("1440", "2K · 1440p"),
    P1080("1080", "Full HD · 1080p"),
    P720("720", "HD · 720p"),
    P480("480", "SD · 480p"),
    MP3("audio-mp3", "Audio · MP3"),
    M4A("audio-m4a", "Audio · M4A"),
    OPUS("audio-opus", "Audio · Opus"),
    FLAC("audio-flac", "Audio · FLAC");
    companion object { fun of(value: String?) = entries.firstOrNull { it.wire == value } ?: BEST }
}

data class Job(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val uploader: String?,
    val duration: Double?,
    val provider: String?,
    val label: String,
    val kind: MediaKind,
    val status: JobStatus,
    /** 0–100 */
    val percent: Float,
    /** bytes/second */
    val speed: Long?,
    /** seconds */
    val eta: Long?,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val expectedSize: Long?,
    val fileSize: Long?,
    /** Human processing phase, e.g. "Merging", "Converting to MP3". */
    val phase: String?,
    val error: String?,
    val file: String?,
    val publicUri: String?,
    val publicPath: String?,
    val saveError: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val attempt: Int,
    val match: MusicMatch? = null,
    /** Set when the job was queued as part of a "keep together" collection. */
    val group: JobGroup? = null,
    /** Automatic retries already scheduled or used (0–[RetryPolicy.MAX]). Reset by a manual retry. */
    val retryCount: Int = 0,
    /** Epoch millis when a pending automatic retry starts; null when no retry is waiting. */
    val retryAt: Long? = null,
    /** Scrubbed raw failure text behind [error], shown by "Error details". */
    val errorDetail: String? = null,
    /** Space id the media is saved to; null when the library is off (today's folders). */
    val space: String? = null,
    /** Set when the job completed without downloading because the same recording was already in [space]. */
    val existing: ExistingMatch? = null,
    /** "Download anyway": skip the existing-recording check for this job. */
    val force: Boolean = false,
    /** The saved file was deleted (from Keepfin, Files or any other app). History stays; the row says so. */
    val filesRemoved: Boolean = false,
    /** Library collection this job belongs to (a downloaded playlist, album or channel). */
    val collectionId: String? = null,
    /** Position in the source collection (1-based); finished items are inserted in this order. */
    val collectionIndex: Int? = null,
    /** Tags captured from the source metadata when the download ran (normalised, empty while "Import tags" is off). */
    val tags: List<String> = emptyList(),
    /** The row text when the download used a sign-in a site asked for ("Signed in"); its presence also tells the runner to use Keepfin's sign-in from the first try. */
    val signedIn: String? = null,
)

/** Collection a job belongs to when the user chose "Keep together". Immutable and shared by every child. */
data class JobGroup(
    val id: String,
    val title: String,
    val url: String,
    val uploader: String? = null,
    val thumbnail: String? = null,
    /** playlist | album | channel | mix | collection */
    val kind: String = "playlist",
)

data class PlaylistEntry(
    val url: String,
    val title: String?,
    /** Music resolver entries: source artist/album used for matching and tagging. */
    val artist: String? = null,
    val album: String? = null,
    val thumbnail: String?,
    val uploader: String?,
    val duration: Double?,
    val position: Int,
    /** Why it cannot be downloaded (private, deleted, gone from YouTube; core Unavailable); shown, never selected. */
    val unavailable: String? = null,
)

data class PlaylistDiscovery(
    val id: String,
    val url: String,
    val title: String?,
    val entries: List<PlaylistEntry>,
    /** discovering | completed | cancelled | failed */
    val state: String,
    val error: String?,
    val limit: Int,
    val truncated: Boolean,
)

data class ToolStatus(
    /** yt-dlp | ffmpeg | python */
    val name: String,
    /** ready | updating | error */
    val state: String,
    val version: String?,
    val checkedAt: Long?,
    val message: String?,
)

data class EngineSettings(
    /** platform | flat */
    val organization: String = "platform",
    /** Parallel downloads 1–4. */
    val concurrency: Int = 3,
    /** Concurrent fragments per download 1–16. */
    val fragments: Int = 8,
    val defaultPreset: DownloadPreset = DownloadPreset.BEST,
    val options: DownloadOptions = DownloadOptions(),
    /** stable | nightly */
    val channel: String = "stable",
    val autoUpdate: Boolean = true,
    val wifiOnly: Boolean = false,
    /** Use aria2c multi-connection transfers for direct https streams. */
    val turbo: Boolean = false,
    /** YouTube Music links open as audio (true, default) or as video (false). */
    val ytMusicAudio: Boolean = true,
    /** Look up lyrics for downloaded songs on LRCLIB and save them in the file. */
    val lyrics: Boolean = true,
    /** "Keep music videos automatically": the Capture and collection "music video" boxes start checked. It never starts a download by itself. */
    val keepMusicVideos: Boolean = false,
    /** Downloads go signed out first; when a site then asks for a sign-in, Keepfin's own is tried once. Off: they stay signed out. */
    val autoSignIn: Boolean = true,
    /** Spaces, gallery and player settings; 0.4.x installs migrate to the defaults. */
    val library: LibrarySettings = LibrarySettings(),
)

/** A song's official music video as the lookup found it (dev-docs/plans/MUSIC-VIDEOS.md): what the person confirms before it is downloaded. */
data class MusicVideoMatch(val videoId: String, val title: String, val channel: String, val views: Long?, val durationSec: Double?, val link: String? = null) {
    /** Where it is: the YouTube link itself when a YouTube link kept its own video, else its YouTube Music page. */
    val url: String get() = link ?: "https://music.youtube.com/watch?v=$videoId"
    val thumbnail: String get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}

/** What looking for a song's music video came to: one found, none (a fact about the video), or the look could not be made (a fact about the network). */
sealed interface MusicVideoLookup {
    data class Found(val video: MusicVideoMatch) : MusicVideoLookup
    data object None : MusicVideoLookup
    data class Failed(val message: String) : MusicVideoLookup
}

/** Pure aggregate of a kept-together collection's members, used by notifications and the grouped card. */
data class GroupSummary(val total: Int, val saved: Int, val failed: Int, val cancelled: Int, val active: Int, val paused: Int, val percent: Int, val removed: Int = 0) {
    val finished get() = saved + failed + cancelled
    val done get() = active == 0 && paused == 0
    /** "7 of 12 done · 2 failed" while running. */
    fun text() = listOfNotNull("$saved of $total done", "$failed failed".takeIf { failed > 0 }, "$paused paused".takeIf { paused > 0 }).joinToString(" · ")
    /** "11 of 12 saved, 1 failed" once everything is terminal. */
    fun finalText() = if (removed > 0 && saved == 0 && failed == 0 && cancelled == 0) removedText() else listOfNotNull("$saved of $total saved", "$failed failed".takeIf { failed > 0 }, "$cancelled cancelled".takeIf { cancelled > 0 }).joinToString(", ")
    /** "All 5 files removed" or "2 of 5 files removed": driven by the library state, not by polling. */
    fun removedText() = if (removed >= total) (if (total == 1) "File removed" else "All $total files removed") else "$removed of $total files removed"
    companion object {
        fun of(children: List<Job>): GroupSummary {
            val removed = children.count { it.status == JobStatus.COMPLETED && it.filesRemoved }
            val saved = children.count { it.status == JobStatus.COMPLETED && it.saveError == null && !it.filesRemoved }
            val failed = children.count { it.status == JobStatus.FAILED || (it.status == JobStatus.COMPLETED && it.saveError != null) }
            val cancelled = children.count { it.status == JobStatus.CANCELLED }
            val counted = children.filter { it.status != JobStatus.FAILED && it.status != JobStatus.CANCELLED }
            val percent = if (counted.isEmpty()) 0 else counted.sumOf { if (it.status == JobStatus.COMPLETED) 100.0 else it.percent.toDouble() }.div(counted.size).toInt().coerceIn(0, 100)
            return GroupSummary(children.size, saved, failed, cancelled, children.count { it.status.active }, children.count { it.status == JobStatus.PAUSED }, percent, removed)
        }
    }
}
