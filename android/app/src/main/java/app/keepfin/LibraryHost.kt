package app.keepfin

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

/** What the library needs from the download engine. */
internal interface LibraryHost {
    val jobs: StateFlow<List<Job>>
    val settings: StateFlow<EngineSettings>
    fun updateSettings(transform: (EngineSettings) -> EngineSettings)
    /** Durable job artwork, if any. The caller recycles it. */
    fun jobThumbnail(jobId: String): Bitmap?
    /** Uris of sidecar files (subtitles, lyrics, cover images) saved next to media: never listed as items. */
    fun sidecarUris(): Set<String>
    /** A file moved ([publicPath] set) or was deleted ([publicPath] null): keeps history entries truthful. */
    fun repointFile(uri: String, publicPath: String?)
    /** "Check now" / "Review": runs a follow check as one-time work for [id], or for every due collection when null. */
    fun requestCheck(id: String?, review: Boolean)
    /** Follow options changed: the periodic check is (re)scheduled. */
    fun followSettingsChanged()
    /** Reads the tags inside a media file, where a download keeps its lyrics when it can. */
    val tagTools: TagTools? get() = null
}
