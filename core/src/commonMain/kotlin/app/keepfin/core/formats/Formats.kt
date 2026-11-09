package app.keepfin.core.formats

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.parseJson
import app.keepfin.core.music.asciiLower
import app.keepfin.core.music.containsJsWord
import app.keepfin.core.music.isArrayIndex
import app.keepfin.core.music.jsTrim
import app.keepfin.core.providers.Providers
import kotlin.math.floor

/**
 * yt-dlp info JSON to video/audio options, size estimates, codec names, subtitle tracks, music metadata and the
 * thumbnail. The behaviour is the desktop app's (`gui/shared/formats.ts`); the info JSON is untrusted input, so
 * every field is read defensively, the way the desktop read JavaScript values.
 */
object Formats {
    // ---- reading untyped JSON ---------------------------------------------------------------------------------

    private fun JsonValue?.get(key: String): JsonValue? = (this as? JsonObject)?.members?.get(key)

    /** A non-blank string cut to [max] UTF-16 units (not trimmed, like the desktop); anything else is null. */
    private fun str(value: JsonValue?, max: Int = 1000): String? {
        val s = (value as? JsonString)?.value ?: return null
        return if (jsTrim(s).isEmpty()) null else s.take(max)
    }

    /** A finite number above zero; anything else is null. */
    private fun num(value: JsonValue?): Double? {
        val n = (value as? JsonNumber)?.value ?: return null
        return if (n.isFinite() && n > 0) n else null
    }

    private fun httpUrl(value: JsonValue?): String? {
        val text = str(value, 4096) ?: return null
        val lower = asciiLower(text)
        return if (lower.startsWith("http://") || lower.startsWith("https://")) text else null
    }

    /** JavaScript Math.round. */
    private fun round(x: Double): Long = floor(x + 0.5).toLong()

    private fun roundedOrNull(value: Double?): Double? = value?.let { round(it).toDouble() }

    /** The string value of a field the way the desktop's `String(x ?? '')` showed it, for the fields that only matter as text. */
    private fun text(value: JsonValue?): String = (value as? JsonString)?.value ?: ""

    private fun isStringValue(value: JsonValue?, expected: String) = (value as? JsonString)?.value == expected

    // ---- codecs -----------------------------------------------------------------------------------------------

    fun videoCodec(vcodec: String?): String {
        val v = asciiLower(vcodec ?: "")
        return when {
            v.startsWith("avc") || v.startsWith("h264") -> "h264"
            v.startsWith("hev") || v.startsWith("hvc") || v.startsWith("h265") -> "hevc"
            v.startsWith("vp9") || v.startsWith("vp09") -> "vp9"
            v.startsWith("av1") || v.startsWith("av01") -> "av1"
            else -> "other"
        }
    }

    fun audioCodec(acodec: String?): String {
        val a = asciiLower(acodec ?: "")
        return when {
            a.startsWith("mp4a") || a.startsWith("aac") -> "aac"
            a == "opus" -> "opus"
            a == "mp3" -> "mp3"
            a == "vorbis" -> "vorbis"
            a == "flac" || a == "alac" -> "flac"
            else -> "other"
        }
    }

    private fun vcodecOf(f: JsonObject) = videoCodec((f.members["vcodec"] as? JsonString)?.value)
    private fun acodecOf(f: JsonObject) = audioCodec((f.members["acodec"] as? JsonString)?.value)

    // ---- one format -------------------------------------------------------------------------------------------

    private fun hasVideo(f: JsonObject): Boolean {
        val v = f.members["vcodec"]
        return if (v is JsonString) v.value != "none" else num(f.members["height"]) != null
    }

    private fun hasAudio(f: JsonObject): Boolean {
        val a = f.members["acodec"]
        return if (a is JsonString) a.value != "none" else false
    }

    /** Storyboards, image tracks and unknown non-media entries never become options. */
    private fun isMedia(f: JsonObject, options: FormatOptions): Boolean {
        if (isStringValue(f.members["ext"], "mhtml") || isStringValue(f.members["protocol"], "mhtml")) return false
        val note = f.members["format_note"]
        if (note is JsonString && asciiLower(note.value).contains("storyboard")) return false
        if (options.skipDrm && (f.members["has_drm"] as? JsonBool)?.value == true) return false
        val id = f.members["format_id"]
        return id is JsonString && id.value.length <= 100
    }

    /** Size estimate: filesize, then filesize_approx, then bitrate x duration / 8 (kbit/s). */
    internal fun size(f: JsonObject, duration: Double?): Size {
        val exact = num(f.members["filesize"])
        if (exact != null) return Size(round(exact), false)
        val approx = num(f.members["filesize_approx"])
        if (approx != null) return Size(round(approx), true)
        val rate = num(f.members["tbr"]) ?: num(f.members["abr"]) ?: num(f.members["vbr"])
        if (rate != null && duration != null && duration != 0.0) return Size(round(rate * 1000 * duration / 8), true)
        return Size(null, true)
    }

    /**
     * Direct http(s) media formats that carry no size at all (no filesize, filesize_approx or bitrate x duration), so an
     * app can ask the server for the length. Some sites (Instagram) give a bitrate but no duration. Pure: no requests.
     */
    internal fun sizeProbes(info: JsonValue, limit: Int = 16, options: FormatOptions = FormatOptions()): List<SizeProbe> {
        val duration = num(info.get("duration"))
        val result = ArrayList<SizeProbe>()
        for (f in mediaFormats(info, options)) {
            if (result.size >= limit) break
            if (!isDirect(f) || size(f, duration).bytes != null) continue
            val url = httpUrl(f.members["url"]) ?: continue
            val headers = LinkedHashMap<String, String>()
            for ((name, value) in (f.members["http_headers"] as? JsonObject)?.members ?: emptyMap()) {
                if (value is JsonString) headers[name] = value.value
            }
            result.add(SizeProbe((f.members["format_id"] as JsonString).value, url, headers))
        }
        return result
    }

    fun sizeProbesOf(infoJson: String, limit: Int): List<SizeProbe> = sizeProbes(parseJson(infoJson), limit)

    private fun isHdr(f: JsonObject): Boolean {
        val d = f.members["dynamic_range"]
        return d is JsonString && asciiLower(d.value) != "sdr" && d.value != ""
    }

    private fun isDirect(f: JsonObject): Boolean {
        val p = f.members["protocol"]
        return p is JsonString && (p.value == "http" || p.value == "https")
    }

    private fun isDrc(f: JsonObject): Boolean =
        asciiLower(text(f.members["format_id"])).endsWith("-drc") || containsJsWord(text(f.members["format_note"]), "DRC")

    private fun isOriginal(f: JsonObject): Boolean {
        if (asciiLower(text(f.members["format_note"])).contains("original")) return true
        val pref = f.members["language_preference"]
        return pref is JsonNumber && pref.value >= 10
    }

    private fun fpsBucket(f: JsonObject): Int? = num(f.members["fps"])?.let { round(it).toInt() }

    private fun intOf(value: Double): Int = value.toInt()

    // ---- ranking ----------------------------------------------------------------------------------------------

    // Compatibility first (H.264 plays everywhere), then modern codecs that MP4 can carry. av1 and vp9 are equal
    // unless the options prefer one of them.
    private fun videoCodecRank(codec: String, options: FormatOptions): Double {
        val base = when (codec) {
            "h264" -> 4.0
            "av1" -> 3.0
            "vp9" -> 3.0
            "hevc" -> 2.0
            else -> 1.0
        }
        return if (options.codecPreference == codec) base + 0.5 else base
    }

    private fun videoScore(f: JsonObject, duration: Double?, options: FormatOptions): Double {
        val sized = size(f, duration)
        // Codec compatibility dominates, then direct (sized) transfers over HLS, then bitrate as the tiebreaker.
        return videoCodecRank(vcodecOf(f), options) * 1e9 +
            (if (isDirect(f)) 1e8 else 0.0) +
            (if (sized.approx) 0.0 else 1e7) +
            (num(f.members["tbr"]) ?: 0.0)
    }

    private fun audioScore(f: JsonObject): Double =
        (if (isOriginal(f)) 1e9 else 0.0) +
            (if (isDrc(f)) 0.0 else 1e8) +
            (if (isDirect(f)) 1e7 else 0.0) +
            (num(f.members["abr"]) ?: num(f.members["tbr"]) ?: 0.0)

    /** Best audio that muxes cleanly with a video stream of the given extension. */
    private fun companionAudio(audio: List<JsonObject>, videoExt: String): JsonObject? {
        val sorted = audio.sortedByDescending { audioScore(it) }
        val family = if (videoExt == "webm") "webm" else "m4a"
        return sorted.firstOrNull { isStringValue(it.members["ext"], family) } ?: sorted.firstOrNull()
    }

    private fun videoId(height: Int, fps: Int?, hdr: Boolean) =
        "${height}p${if (fps != null && fps > 32) fps.toString() else ""}${if (hdr) "-hdr" else ""}"

    private fun mediaFormats(info: JsonValue, options: FormatOptions): List<JsonObject> {
        val formats = info.get("formats") as? JsonArray ?: return emptyList()
        return formats.items.filterIsInstance<JsonObject>().filter { isMedia(it, options) }
    }

    private fun quality(f: JsonObject): Int {
        val height = num(f.members["height"])!!
        val width = num(f.members["width"])
        return intOf(if (width != null && width < height) width else height)
    }

    internal fun videoOptions(info: JsonValue, options: FormatOptions = FormatOptions()): List<VideoOption> {
        val duration = num(info.get("duration"))
        val formats = mediaFormats(info, options)
        val audio = formats.filter { hasAudio(it) && !hasVideo(it) }
        val buckets = LinkedHashMap<String, MutableList<JsonObject>>()
        for (f in formats) {
            if (!hasVideo(f) || num(f.members["height"]) == null) continue
            val key = videoId(quality(f), fpsBucket(f), isHdr(f))
            buckets.getOrPut(key) { ArrayList() }.add(f)
        }
        val result = ArrayList<VideoOption>()
        for ((id, candidates) in buckets) {
            val best = candidates.sortedByDescending { videoScore(it, duration, options) }[0]
            val muxed = hasAudio(best)
            val ext = str(best.members["ext"], 10) ?: "mp4"
            val own = size(best, duration)
            val partner = if (muxed) null else companionAudio(audio, ext)
            val extra = if (partner != null) size(partner, duration) else Size(0L, false)
            val total = if (own.bytes != null) own.bytes + (extra.bytes ?: 0L) else null
            result.add(
                VideoOption(
                    id = id,
                    height = quality(best),
                    width = num(best.members["width"])?.let { intOf(it) },
                    fps = fpsBucket(best),
                    hdr = isHdr(best),
                    codec = vcodecOf(best),
                    ext = ext,
                    formatId = (best.members["format_id"] as JsonString).value,
                    hasAudio = muxed,
                    size = total,
                    sizeApprox = own.approx || extra.approx || (!muxed && partner == null),
                    tbr = roundedOrNull(num(best.members["tbr"])),
                ),
            )
        }
        return result.sortedWith(
            compareByDescending<VideoOption> { it.height }.thenByDescending { it.fps ?: 0 }.thenByDescending { it.hdr },
        )
    }

    /** A stable key for "the same JavaScript value" in a Set of languages: absent, null and each string differ. */
    private fun languageKey(f: JsonObject, nullAsEmpty: Boolean): String {
        val v = f.members["language"]
        return when {
            v is JsonString -> "s:" + v.value
            v == null || v is JsonNull -> if (nullAsEmpty) "s:" else if (v == null) "u" else "n"
            else -> "x:" + show(v)
        }
    }

    private fun show(v: JsonValue): String = when (v) {
        is JsonNumber -> v.value.toString()
        is JsonBool -> v.value.toString()
        else -> v.hashCode().toString()
    }

    /**
     * Audio streams: every codec/DRC variant of the original (or only) language, plus the best stream per other
     * language (dubs) preferring AAC for compatibility.
     */
    internal fun audioOptions(info: JsonValue, options: FormatOptions = FormatOptions()): List<AudioOption> {
        val duration = num(info.get("duration"))
        val formats = mediaFormats(info, options).filter { hasAudio(it) && !hasVideo(it) }
        // HLS audio renditions without codec info duplicate the https streams on YouTube.
        val anyKnown = formats.any { acodecOf(it) != "other" }
        val known = formats.filter { acodecOf(it) != "other" || !anyKnown }
        val originalLanguage = str(known.firstOrNull { isOriginal(it) }?.members?.get("language"))
            ?: (if (known.map { languageKey(it, true) }.toSet().size <= 1) str(known.firstOrNull()?.members?.get("language")) else null)
        val chosen = LinkedHashMap<String, JsonObject>()
        for (f in known.sortedByDescending { audioScore(it) }) {
            val language = str(f.members["language"]) ?: ""
            val primary = originalLanguage == null || language == originalLanguage || language.isEmpty()
            val key = if (primary) "p|${acodecOf(f)}|${if (isDrc(f)) "drc" else ""}|$language" else "l|$language"
            val current = chosen[key]
            if (current == null) chosen[key] = f
            else if (!primary && acodecOf(f) == "aac" && acodecOf(current) != "aac" && !isDrc(f)) chosen[key] = f
        }
        val manyLanguages = known.map { languageKey(it, false) }.toSet().size > 1
        val built = chosen.values.map { f ->
            val sized = size(f, duration)
            val notes = ArrayList<String>()
            val language = str(f.members["language"], 20)
            val formatNote = asciiLower(text(f.members["format_note"]))
            if (isOriginal(f) && originalLanguage != null && manyLanguages) notes.add("original")
            else if (formatNote.contains("dubbed") || (language != null && originalLanguage != null && language != originalLanguage)) notes.add("dubbed")
            if (formatNote.contains("descriptive")) notes.add("descriptive")
            if (isDrc(f)) notes.add("DRC")
            val abr = num(f.members["abr"]) ?: num(f.members["tbr"])
            val formatId = (f.members["format_id"] as JsonString).value
            AudioOption(
                id = "a-$formatId",
                formatId = formatId,
                codec = acodecOf(f),
                ext = str(f.members["ext"], 10) ?: "m4a",
                abr = roundedOrNull(abr),
                size = sized.bytes,
                sizeApprox = sized.approx,
                language = language,
                note = if (notes.isEmpty()) null else notes.joinToString(", "),
            )
        }
        fun rank(o: AudioOption): Double =
            (if (o.language == originalLanguage || o.language == null || originalLanguage == null) 2e6 else 0.0) +
                (if (o.note?.contains("DRC") == true) 0.0 else 1e6) +
                (o.abr ?: 0.0)
        return built.sortedWith(compareByDescending<AudioOption> { rank(it) }.thenBy { it.language ?: "" }).take(40)
    }

    // ---- subtitles --------------------------------------------------------------------------------------------

    // Automatic captions on YouTube include ~150 machine translations; keep a useful subset.
    private val commonCaptionLanguages = listOf(
        "en", "es", "fr", "de", "pt", "it", "ar", "ru", "ja", "ko", "zh-Hans",
        "zh-Hant", "hi", "tr", "id", "nl", "pl", "vi", "uk", "fa",
    )
    private const val MAX_AUTO_CAPTIONS = 24

    private fun validLanguage(lang: String): Boolean =
        lang.length in 1..30 && lang != "live_chat" && lang.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

    /** Object.entries order in JavaScript: integer-like keys ascending first, then the others as written. */
    private fun entriesOf(value: JsonValue?): List<Pair<String, JsonValue>> {
        val obj = value as? JsonObject ?: return emptyList()
        val keys = obj.members.keys
        val indexKeys = keys.filter { isArrayIndex(it) }.sortedWith(compareBy({ it.length }, { it }))
        return (indexKeys + keys.filterNot { isArrayIndex(it) }).map { it to obj.members.getValue(it) }
    }

    private fun trackName(entries: JsonValue): String? = (entries as? JsonArray)?.items?.firstOrNull()?.let { str(it.get("name"), 100) }

    internal fun subtitles(info: JsonValue): List<SubtitleTrack> {
        val manual = entriesOf(info.get("subtitles")).filter { validLanguage(it.first) }
            .map { SubtitleTrack(it.first, trackName(it.second), false) }
        val have = manual.map { it.lang }.toSet()
        val autoEntries = entriesOf(info.get("automatic_captions")).filter { validLanguage(it.first) && it.first !in have }
        fun priority(lang: String): Int {
            if (lang.endsWith("-orig")) return 0
            val index = commonCaptionLanguages.indexOf(lang)
            return if (index == -1) 1000 else index + 1
        }
        val auto = autoEntries
            .filter { priority(it.first) < 1000 || autoEntries.size <= MAX_AUTO_CAPTIONS }
            .sortedBy { priority(it.first) }
            .take(MAX_AUTO_CAPTIONS)
            .map { SubtitleTrack(it.first, trackName(it.second), true) }
        return manual.sortedBy { it.lang } + auto
    }

    // ---- music, thumbnail -------------------------------------------------------------------------------------

    internal fun music(info: JsonValue): MusicMetadata? {
        val artistsValue = info.get("artists")
        val artists = if (artistsValue is JsonArray) artistsValue.items.filterIsInstance<JsonString>().joinToString(", ") { it.value } else null
        val artist = str(artists?.let { JsonString(it) }, 300) ?: str(info.get("artist"), 300)
        val album = str(info.get("album"), 300)
        val track = str(info.get("track"), 300)
        if (artist == null && album == null && track == null) return null
        return MusicMetadata(artist, album, track, num(info.get("release_year"))?.let { intOf(it) })
    }

    /** The single thumbnail if it is an http(s) link, else the last http(s) one of the list (the largest). */
    internal fun thumbnail(info: JsonValue): String? {
        httpUrl(info.get("thumbnail"))?.let { return it }
        val thumbs = info.get("thumbnails") as? JsonArray ?: return null
        for (i in thumbs.items.indices.reversed()) httpUrl(thumbs.items[i].get("url"))?.let { return it }
        return null
    }

    // ---- the original file ------------------------------------------------------------------------------------

    private val originalExtensions = listOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "flv", "ts")

    /** A direct file (generic extractor) often has no codec or height: offer the file itself instead of nothing. */
    private fun originalOption(info: JsonValue, options: FormatOptions): List<VideoOption> {
        val formats = (info.get("formats") as? JsonArray)?.items?.filterIsInstance<JsonObject>() ?: emptyList()
        val fromFormats = formats.lastOrNull {
            isMedia(it, options) && !isStringValue(it.members["vcodec"], "none") &&
                (it.members["ext"] as? JsonString)?.value in originalExtensions
        }
        val ownExt = text(info.get("ext"))
        val self = formats.isEmpty() && ownExt in listOf("mp4", "webm", "mkv", "mov", "m4v")
        val f = fromFormats ?: (if (self) info as? JsonObject else null) ?: return emptyList()
        val sized = size(f, num(info.get("duration")))
        return listOf(
            VideoOption(
                id = "original",
                height = num(f.members["height"])?.let { intOf(it) } ?: 0,
                width = num(f.members["width"])?.let { intOf(it) },
                fps = fpsBucket(f),
                hdr = false,
                codec = vcodecOf(f),
                ext = str(f.members["ext"], 10) ?: "mp4",
                formatId = str(f.members["format_id"], 100) ?: "0",
                hasAudio = !isStringValue(f.members["acodec"], "none"),
                size = sized.bytes,
                sizeApprox = sized.approx,
                tbr = roundedOrNull(num(f.members["tbr"])),
            ),
        )
    }

    // ---- the whole item ---------------------------------------------------------------------------------------

    /** Converts a single-item yt-dlp info dict (as JSON text) into [MediaInfo]. Throws when the text is not a JSON object. */
    fun mediaInfo(url: String, infoJson: String, options: FormatOptions = FormatOptions()): MediaInfo =
        mediaInfo(url, parseJson(infoJson), options)

    internal fun mediaInfo(url: String, info: JsonValue, options: FormatOptions = FormatOptions()): MediaInfo {
        if (info !is JsonObject) throw IllegalArgumentException("The engine returned no media information.")
        val extractor = str(info.get("extractor_key"), 100)
        val live = (info.get("is_live") as? JsonBool)?.value == true ||
            isStringValue(info.get("live_status"), "is_live") || isStringValue(info.get("live_status"), "is_upcoming")
        val uploader = str(info.get("uploader"), 300) ?: str(info.get("channel"), 300) ?: str(info.get("creator"), 300)
        val provider = Providers.name(url, extractor)
        val primary = videoOptions(info, options)
        val video = if (primary.isEmpty()) originalOption(info, options)
        else if (options.convertSmaller) Conversion.withConverted(primary, num(info.get("duration")))
        else primary
        val audio = audioOptions(info, options)
        val playlist = Providers.playlistUrl(url)
        return MediaInfo(
            url = url,
            // The engine reports a YouTube Music link as its plain YouTube page; only the link still says it is music.
            webpageUrl = if (Providers.isYouTubeMusic(url)) url else httpUrl(info.get("webpage_url")),
            // Posts use their whole caption as the title; the full text stays in the description.
            title = Title.clean(str(info.get("title"), 2000), uploader, provider, if (video.isNotEmpty()) "video" else if (audio.isNotEmpty()) "audio" else "video"),
            uploader = uploader,
            duration = num(info.get("duration")),
            thumbnail = thumbnail(info),
            extractor = extractor,
            provider = provider,
            // An upcoming premiere cannot be downloaded yet, so it counts as live.
            isLive = live,
            music = music(info),
            video = video,
            audio = audio,
            subtitles = subtitles(info),
            chapters = (info.get("chapters") as? JsonArray)?.items?.size ?: 0,
            playlistUrl = playlist,
            playlistTitle = if (playlist != null) str(info.get("playlist_title"), 300) else null,
        )
    }

    /** The name of a height; a direct file has none to read (0), so it is the original. */
    fun heightLabel(height: Int): String = if (height > 0) "${height}p" else "Original"

    // ---- text entry points (for the facades and tests) --------------------------------------------------------

    fun sizeOf(formatJson: String, duration: Double?): Size {
        val f = parseJson(formatJson) as? JsonObject ?: return Size(null, true)
        return size(f, duration)
    }

    fun subtitlesOf(infoJson: String): List<SubtitleTrack> = subtitles(parseJson(infoJson))

    /** The thumbnail of an info dict or a flat collection entry (http(s) links only), or null. */
    fun thumbnailOf(infoJson: String): String? = thumbnail(parseJson(infoJson))
}
