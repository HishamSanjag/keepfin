package app.keepfin

import app.keepfin.core.errors.Action
import app.keepfin.core.errors.Errors
import app.keepfin.core.errors.Platform
import app.keepfin.core.gallery.EarlierSave
import app.keepfin.core.gallery.Gallery
import app.keepfin.core.gallery.GalleryRead
import app.keepfin.core.signin.SignIn
import app.keepfin.core.signin.SignInSite

/** A photo post the site shows only to signed-in people: the preview offers "Sign in to [site]" instead of a failure. */
class SignInNeeded(val site: String, message: String) : IllegalStateException(message)

/**
 * Photo posts on the phone (images and carousels from Instagram, Pinterest, X…): what the gallery engine's listing becomes in the app,
 * what a choice asks for and how it is worded. The reading itself is the shared core's ([Gallery.read]); this is the Android side of it.
 */
object PhotoPosts {
    /** The post as the app's media model: previews instead of formats. */
    fun media(post: GalleryRead.Post, url: String): MediaInfo = MediaInfo(
        url = url, webpageUrl = url, title = post.title, uploader = post.uploader, duration = null, thumbnail = post.previews.firstOrNull(),
        extractor = "gallery-dl:${post.category}", provider = post.provider, isLive = false, music = null, video = emptyList(), audio = emptyList(),
        subtitles = emptyList(), chapters = 0, playlistUrl = null,
        gallery = GalleryPost(post.count, post.previews, post.items, post.truncated),
    )

    /** A failure the gallery engine reported while exiting normally: a sign-in wall becomes [SignInNeeded], anything else its friendly sentence. */
    fun failure(message: String, url: String): IllegalStateException {
        val analysis = Errors.analyze("ERROR: $message", Platform.ANDROID)
        if (analysis.action != Action.SIGN_IN) return IllegalStateException(analysis.message)
        val site = Providers.name(url)
        return SignInNeeded(site, signInMessage(site))
    }

    /**
     * The site that asks for a sign-in when [url] could not be read because of [error]: a sign-in wall (a photo post's listing, or the download
     * engine's own sign-in failure) on a site Keepfin has a sign-in page for. Null for any other failure or site.
     */
    fun signInWall(url: String, error: Throwable): SignInSite? {
        val site = SignIn.siteOf(url) ?: return null
        if (error is SignInNeeded) return site
        val raw = error.message.orEmpty()
        val action = Errors.analyze(if (raw.contains("ERROR:")) raw else "ERROR: $raw", Platform.ANDROID).action
        return site.takeIf { action == Action.SIGN_IN }
    }

    /** What the preview says when [site] shows a link only to signed-in people. */
    fun signInMessage(site: String) = "$site shows this post only to signed-in people."

    /** The positions a choice downloads: every one of the post unless some are left out. */
    fun chosen(count: Int, leftOut: Collection<Int>): List<Int> = (1..count).filter { it !in leftOut }

    /** The request for the [chosen] positions; all of them are asked for as "everything". */
    fun request(count: Int, chosen: List<Int>): FormatRequest.Gallery = FormatRequest.Gallery(count, chosen.takeIf { it.size < count })

    /** Throws [IllegalArgumentException] with the message to show when the request is not acceptable. */
    fun validate(request: FormatRequest.Gallery) {
        require(request.count in 1..MAX_COUNT) { "This post has no files to download." }
        request.items?.let { Gallery.range(it, request.count) }
    }

    /** How many files the request downloads. */
    fun expected(request: FormatRequest.Gallery): Int = request.items?.size ?: request.count

    /** The engine's `--range` for the request; null downloads everything. */
    fun range(request: FormatRequest.Gallery): String? = request.items?.let { Gallery.range(it, request.count) }

    /**
     * The request another device sent for a post read here: its chosen positions (null = every photo) must be positions of this post.
     * Throws [IllegalArgumentException] with the message to show.
     */
    fun fromDevice(count: Int, items: List<Int>?): FormatRequest.Gallery {
        if (items == null) return FormatRequest.Gallery(count, null)
        try { Gallery.range(items, count) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("The photos chosen on the other device are not in this post.") }
        return request(count, items.sorted())
    }

    /** "6 photos", or "6 items" when a video of the post is among the chosen. */
    fun countLabel(media: MediaInfo, chosen: Collection<Int>): String {
        val videos = media.gallery?.items.orEmpty().any { !it.photo && it.position in chosen }
        return Gallery.label(chosen.size, videos)
    }

    /** The finished photo jobs that [alreadySaved] compares a new request with; [present] says whether a saved copy is still there. */
    internal fun earlier(records: List<JobRecord>, self: String, present: (String) -> Boolean): List<Pair<JobRecord, EarlierSave>> =
        records.mapNotNull { record ->
            val format = record.format as? FormatRequest.Gallery ?: return@mapNotNull null
            if (record.id == self || record.status != JobStatus.COMPLETED || record.job.existing != null) return@mapNotNull null
            val complete = record.job.saveError == null && !record.job.filesRemoved && record.photoSaves.size >= expected(format) && record.photoSaves.values.all(present)
            record to EarlierSave(record.job.url, format.count, format.items, complete)
        }

    /** The earlier job that already holds the photos [request] asks for from [url] (see [Gallery.alreadySaved]), or null. */
    internal fun alreadySaved(url: String, request: FormatRequest.Gallery, earlier: List<Pair<JobRecord, EarlierSave>>): JobRecord? =
        earlier.getOrNull(Gallery.alreadySaved(url, request.count, request.items, earlier.map { it.second }))?.first

    /** The line above the grid. */
    fun note(chosen: Int, total: Int, truncated: Boolean): String =
        "$chosen of $total chosen. ${if (chosen > 0) "Tap a photo to leave it out." else "Tap a photo to choose it."}" +
            if (truncated) " The listing was capped, so the post may contain more." else ""

    /** What a photo tile says to a screen reader. */
    fun tileDescription(position: Int, photo: Boolean, chosen: Boolean): String = "${if (photo) "Photo" else "Video"} $position, ${if (chosen) "chosen" else "left out"}"

    /** The job row's label: "6 photos" (the media is known) or the count alone. */
    fun label(request: FormatRequest.Gallery, media: MediaInfo?): String {
        val chosen = request.items ?: chosen(request.count, emptyList())
        return media?.gallery?.let { countLabel(media, chosen) } ?: Gallery.label(chosen.size, false)
    }

    /** The row label once [saved] of the [expected] files are in storage: "4 of 6 photos" while some are missing, "6 photos" when all are. */
    fun savedLabel(saved: Int, expected: Int, label: String): String = Gallery.savedLabel(saved, expected, label)

    private const val MAX_COUNT = 100_000
}
