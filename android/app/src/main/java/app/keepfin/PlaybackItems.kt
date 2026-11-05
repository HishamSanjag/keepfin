package app.keepfin

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File

/** How library items become Media3 items, and the ids of the browsable tree. */
object PlaybackItems {
    const val EXTRA_KIND = "keepfin.kind"
    const val EXTRA_SPACE = "keepfin.space"
    const val EXTRA_GAIN = "keepfin.gain"
    const val EXTRA_HAS_LYRICS = "keepfin.hasLyrics"
    const val EXTRA_FAVORITE = "keepfin.favorite"
    const val EXTRA_FOLDER = "keepfin.folder"

    const val ROOT = "keepfin:root"
    const val PLAYLISTS = "keepfin:playlists"
    const val FAVORITES = "keepfin:favorites"
    const val RECENT = "keepfin:recent"
    fun space(id: String) = "keepfin:space:$id"
    fun songs(space: String) = "keepfin:songs:$space"
    fun albums(space: String) = "keepfin:albums:$space"
    fun folders(space: String) = "keepfin:folders:$space"
    fun album(space: String, key: String) = "keepfin:album:$space:$key"
    fun folder(space: String, folder: String) = "keepfin:folder:$space:$folder"
    fun playlist(id: String) = "keepfin:playlist:$id"

    /** Set on a reference that is to be watched: a video kept in a music space is then a video, not a song ([Songs.isVideoSong]). */
    const val EXTRA_WATCH = "keepfin.watch"

    /** Set on a resolved item that plays a song's music video file (`Song.video.mp4`): the same song, its picture, its own position (dev-docs/plans/MUSIC-VIDEOS.md). */
    const val EXTRA_MUSIC_VIDEO = "keepfin.musicVideo"

    /** Set on a reference: the song's music video is wanted (when it keeps one); absent, the song itself plays. */
    const val EXTRA_SIDE_VIDEO = "keepfin.sideVideo"

    /**
     * An id-only item: what the UI hands the controller. The service resolves it to a playable item with full metadata. [musicVideo]: true asks for the
     * song's music video file when it keeps one (otherwise the song itself plays).
     */
    fun reference(id: String, watch: Boolean = false, musicVideo: Boolean = false): MediaItem = MediaItem.Builder().setMediaId(id)
        .also {
            if (watch || musicVideo) it.setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(Bundle().apply {
                if (watch) putBoolean(EXTRA_WATCH, true)
                if (musicVideo) putBoolean(EXTRA_SIDE_VIDEO, true)
            }).build())
        }.build()

    /** Album grouping key: album artist (or artist) plus album, folded. */
    fun albumKey(item: LibraryItem): String? {
        val album = item.album?.takeIf { it.isNotBlank() } ?: return null
        return Recordings.normalizeArtist(item.albumArtist ?: item.artist).ifEmpty { "-" } + "|" + album.trim().lowercase()
    }

    /** Playable item with full metadata. [artwork] makes the cover available to the notification and lock screen. [asSong]: a video kept in a music space, described as music. */
    fun toMediaItem(context: Context, item: LibraryItem, subtitles: List<SubtitleFile> = emptyList(), asSong: Boolean = false, musicVideoUri: String? = null): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_KIND, if (musicVideoUri != null) LibraryItemKind.VIDEO.wire else item.kind.wire); putString(EXTRA_SPACE, item.space); putString(EXTRA_FOLDER, item.folder)
            item.gain?.let { putDouble(EXTRA_GAIN, it) }
            putBoolean(EXTRA_HAS_LYRICS, item.hasLyrics); putBoolean(EXTRA_FAVORITE, item.favorite)
            if (item.remote) putBoolean(app.keepfin.net.PeerPlayback.EXTRA_REMOTE, true)
            if (musicVideoUri != null) putBoolean(EXTRA_MUSIC_VIDEO, true)
        }
        // not on this phone: the player reads it from a paired device that holds it (looked for when it opens)
        val uri = musicVideoUri ?: if (item.remote) app.keepfin.net.PeerPlayback.contentUri(item.contentId.orEmpty(), item.title) ?: "keepfin-peer://content/unavailable" else item.uri
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title).setDisplayTitle(item.title).setArtist(item.artist ?: item.albumArtist).setAlbumTitle(item.album).setAlbumArtist(item.albumArtist)
            .setTrackNumber(item.track).setDiscNumber(item.disc).setReleaseYear(item.year).setGenre(item.genre)
            .setDurationMs(item.duration?.let { (it * 1000).toLong() })
            .setArtworkUri(artwork(context, item.art))
            .setIsBrowsable(false).setIsPlayable(true)
            .setMediaType(if (musicVideoUri == null && (item.kind == LibraryItemKind.AUDIO || asSong)) MediaMetadata.MEDIA_TYPE_MUSIC else MediaMetadata.MEDIA_TYPE_VIDEO)
            .setExtras(extras).build()
        return MediaItem.Builder().setMediaId(item.id).setUri(Uri.parse(uri)).setMediaMetadata(metadata)
            .setSubtitleConfigurations(subtitles.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.uri)).setMimeType(sub.mimeType).setLanguage(sub.language).setLabel(sub.label).build()
            }).build()
    }

    /** A browsable folder node (space, album, playlist…). */
    fun folderItem(id: String, title: String, subtitle: String? = null, art: Uri? = null, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED): MediaItem = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtworkUri(art).setIsBrowsable(true).setIsPlayable(false).setMediaType(type).build()).build()

    /** content:// uri of a cached cover, readable by Keepfin's own notification and session. */
    fun artwork(context: Context, art: String?): Uri? {
        val file = art?.let(::File)?.takeIf { it.isFile } ?: return null
        return try { FileProvider.getUriForFile(context, "${context.packageName}.files", file) } catch (_: Exception) { null }
    }
}
