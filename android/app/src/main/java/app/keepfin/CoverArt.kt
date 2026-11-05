package app.keepfin

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Album artwork from the source music service (Spotify, Apple Music, Deezer). It is fetched under strict limits
 * (https only, at most [MAX_BYTES], JPEG/PNG/WebP, [TIMEOUT_MS] overall) and injected as the preferred thumbnail of the
 * matched track's info, so the engine embeds it instead of the video frame. Any failure keeps the video's own cover.
 */
internal object CoverArt {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val TIMEOUT_MS = 10_000

    /** Only https image URLs without credentials; other schemes (file:, http:, content:) are never fetched. */
    fun allowedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val parsed = URI(url)
            parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank() && parsed.userInfo == null
        } catch (_: Exception) { false }
    }

    /** The source's own artwork, not a video frame from the matched YouTube link. */
    fun isSourceArtwork(url: String?): Boolean {
        if (!allowedUrl(url)) return false
        val host = URI(url!!).host.lowercase()
        return !(host.contains("ytimg") || host.endsWith("youtube.com") || host.endsWith("youtu.be"))
    }

    /** "jpeg", "png" or "webp" from magic bytes (the server's content type is not trusted); null for anything else. */
    fun sniff(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        else -> null
    }

    /** File extension the engine accepts for a sniffed image type. */
    fun extension(kind: String) = if (kind == "jpeg") "jpg" else kind

    /**
     * Downloads [url] into [directory]/[name].<jpg|png|webp> when it passes every limit and returns that file.
     * Returns null (leaving no file) otherwise.
     */
    fun fetch(url: String?, directory: File, name: String): File? {
        if (!allowedUrl(url)) return null
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS; instanceFollowRedirects = true
                setRequestProperty("Accept", "image/jpeg,image/png,image/webp")
            }
            if (connection.responseCode != 200 || connection.url.protocol != "https") return null
            if (connection.contentLengthLong > MAX_BYTES) return null
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    if (System.nanoTime() > deadline) return null
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
            }
            val bytes = out.toByteArray()
            val kind = sniff(bytes) ?: return null
            directory.mkdirs()
            return File(directory, "$name.${extension(kind)}").also { it.writeBytes(bytes) }
        } catch (_: Exception) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /** Copy of the cached info with [fileUrl] appended as the most preferred thumbnail; the original thumbnails stay as fallbacks. */
    fun patchedInfo(infoJson: String, fileUrl: String): String {
        val info = JSONObject(infoJson)
        val thumbnails = info.optJSONArray("thumbnails") ?: JSONArray()
        thumbnails.put(JSONObject().put("url", fileUrl).put("id", "keepfin-source").put("preference", 1000))
        return info.put("thumbnails", thumbnails).put("thumbnail", fileUrl).toString()
    }
}
