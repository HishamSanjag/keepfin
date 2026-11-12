package app.keepfin

import app.keepfin.core.gallery.GalleryRead
import app.keepfin.core.gallery.PublicPost
import java.util.Base64

/** Photo posts a site shows signed out: the download engine's printed pages hold the files as direct links, no sign-in needed. */
internal object PublicPhotos {
    const val TIMEOUT_MS = 30_000L

    /** The options that make the download engine print the post's answer without looking for formats. */
    val OPTIONS = listOf("--dump-pages", "--skip-download", "--ignore-no-formats-error", "--no-warnings")

    /** The post inside what the engine printed; null for anything else (the usual route then takes over). */
    fun read(output: String, url: String): GalleryRead.Post? {
        val encoded = PublicPost.encodedAnswer(output) ?: return null
        val answer = try { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) } catch (_: IllegalArgumentException) { return null }
        return PublicPost.read(answer, url) as? GalleryRead.Post
    }

    /** The direct links of the chosen positions (null = every one), or null when the post is no longer the one the choice was made on. */
    fun links(post: GalleryRead.Post, count: Int, chosen: List<Int>?): List<String>? {
        if (post.count != count) return null
        val byPosition = post.items.associate { it.position to it.url }
        val positions = chosen ?: (1..count).toList()
        return positions.map { byPosition[it] ?: return null }.takeIf { it.isNotEmpty() }
    }
}
