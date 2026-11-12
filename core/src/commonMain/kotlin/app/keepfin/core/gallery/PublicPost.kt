package app.keepfin.core.gallery

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.parseJson
import app.keepfin.core.music.jsTrim
import app.keepfin.core.providers.Providers
import app.keepfin.core.providers.Url

/**
 * A post its site shows to signed-out visitors, read from the pages the download engine prints while it looks at the link.
 * The files are direct links, so no sign-in is needed to save them. Instagram posts so far; anything unexpected reads as
 * [GalleryRead.Empty] and the apps go on with the usual route.
 */
object PublicPost {
    private const val ANSWER = "Dumping request to https://www.instagram.com/api/graphql"

    /** True for a link worth trying signed out: an Instagram post (`/p/<code>`, also under a profile name). */
    fun supports(url: String): Boolean {
        val host = Providers.host(url)
        if (host != "instagram.com" && !host.endsWith(".instagram.com")) return false
        val parts = (Url.parse(jsTrim(url)) ?: return false).path.split('/').filter { it.isNotEmpty() }
        val at = parts.indexOf("p")
        return at in 0..1 && parts.size > at + 1
    }

    /** The post's answer (base64) inside the engine's printed pages: the line after the request's notice. Null when it is not there. */
    fun encodedAnswer(output: String): String? {
        val lines = output.split('\n')
        val at = lines.indexOfFirst { it.contains(ANSWER) }
        val next = lines.getOrNull(at + 1)?.let { jsTrim(it) } ?: return null
        return if (at < 0 || next.isEmpty() || next.startsWith("[")) null else next
    }

    private fun JsonValue?.get(key: String): JsonValue? = (this as? JsonObject)?.members?.get(key)?.takeIf { it !== JsonNull }

    private fun link(value: JsonValue?): String? = (value as? JsonString)?.value?.takeIf { it.startsWith("https://") }

    private fun first(value: JsonValue?): JsonValue? = (value as? JsonArray)?.items?.firstOrNull()

    /** Reads the decoded answer for the post at [url]. A post that is one video, or holds a video without a direct link, is left to the engine. */
    fun read(answer: String, url: String, limit: Int = 200): GalleryRead {
        val root = try {
            parseJson(answer)
        } catch (_: IllegalArgumentException) {
            return GalleryRead.Empty
        }
        val post = root.get("data").get("xig_polaris_media").get("if_not_gated_logged_out") ?: return GalleryRead.Empty
        val files = (post.get("carousel_media") as? JsonArray)?.items ?: listOf(post)
        val items = ArrayList<GalleryItem>()
        for (file in files) {
            val picture = link(first(file.get("image_versions2").get("candidates")).get("url")) ?: link(file.get("display_uri"))
            val video = file.get("video_versions")
            val isVideo = video != null || (file.get("__typename") as? JsonString)?.value == "XIGPolarisVideoMedia"
            val target = if (isVideo) link(first(video).get("url")) else picture
            items.add(GalleryItem(items.size + 1, target ?: return GalleryRead.Empty, !isVideo, if (isVideo) picture else null))
        }
        if (items.isEmpty() || items.none { it.photo } && items.size == 1) return GalleryRead.Empty

        val caption = (post.get("caption").get("text") as? JsonString)?.value?.let { jsTrim(it) }?.takeIf { it.isNotEmpty() }
        val user = post.get("user")
        val uploader = listOf("full_name", "username").firstNotNullOfOrNull { key -> (user.get(key) as? JsonString)?.value?.let { jsTrim(it) }?.takeIf { it.isNotEmpty() } }
        return GalleryRead.Post(
            title = caption?.take(120) ?: "${Providers.name(url)} post",
            uploader = uploader?.take(200),
            category = "instagram",
            provider = Providers.name(url, "instagram"),
            previews = items.filter { it.photo }.take(12).map { it.url },
            items = items.take(limit),
            count = items.size,
            truncated = items.size > limit,
        )
    }
}
