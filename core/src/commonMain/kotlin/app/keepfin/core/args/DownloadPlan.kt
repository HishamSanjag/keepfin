package app.keepfin.core.args

import app.keepfin.core.music.jsTrim
import kotlin.math.floor

/**
 * Structured download intent to the download engine's arguments, for both apps. The behaviour is the desktop's
 * (`gui/shared/policy.ts`): every value that reaches an argument is validated or chosen from a closed set, and nothing from
 * the UI or a shared link is passed through as a raw argument. Validation failures throw [IllegalArgumentException] with
 * the message the user sees.
 */
object DownloadPlan {
    private val containers = listOf("mp4", "mkv", "webm")
    private val audioTargets = listOf("mp3", "m4a", "opus", "flac", "wav", "original")
    private val audioQualities = listOf("best", "320", "256", "192", "128")
    private val codecs = listOf("auto", "h264", "vp9", "av1", "hevc")
    private val sponsorModes = listOf("off", "mark", "remove")
    private val presets = listOf("best", "2160", "1440", "1080", "720", "480", "audio-mp3", "audio-m4a", "audio-opus", "audio-flac")

    /** Longest time range the engine is asked for: one week. */
    private const val MAX_SECONDS = 604_800.0

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private fun isAlnum(c: Char) = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')

    /**
     * Format ids are spliced into a format selector, so operator characters (+ / , [ ] ( ) * ? spaces and quotes) must never
     * appear. The first character is alphanumeric, so an id can never look like an option. = : ~ @ appear in real DASH/HLS ids.
     */
    fun isFormatId(value: String): Boolean {
        if (value.isEmpty() || value.length > 128 || !isAlnum(value[0])) return false
        return value.all { isAlnum(it) || it == '_' || it == '.' || it == '=' || it == ':' || it == '~' || it == '@' || it == '-' }
    }

    /** Subtitle language selectors such as en.*,ar.* (1 to 100 characters). */
    fun isSubtitleLanguages(value: String): Boolean =
        value.isNotEmpty() && value.length <= 100 && value.all { isAlnum(it) || it == '.' || it == '*' || it == ',' || it == '-' || it == '_' }

    private fun checkedId(value: String?): String? {
        if (value == null || value.isEmpty()) return null
        if (isFormatId(value)) return value
        fail("Choose a format from the inspected list.")
    }

    // ---- validation -------------------------------------------------------------------------------------------

    fun validate(request: DownloadFormat): DownloadFormat = when (request) {
        is DownloadFormat.Video -> {
            request.maxHeight?.let { if (it < 100 || it > 8640) fail("Choose a supported video quality.") }
            val formatId = checkedId(request.formatId)
            val audioFormatId = checkedId(request.audioFormatId)
            // A converted size starts from one exact download and only ever goes smaller (never upscaled).
            request.scaleHeight?.let { if (it < 100 || it > 8640 || formatId == null || (request.maxHeight != null && it >= request.maxHeight)) fail("Choose a supported video quality.") }
            request.fps?.let { if (it < 1 || it > 240) fail("Choose a supported frame rate.") }
            if (request.codec !in codecs) fail("Choose a supported video codec.")
            if (request.container !in containers) fail("Choose MP4, MKV or WebM.")
            request.copy(formatId = formatId, audioFormatId = audioFormatId)
        }
        is DownloadFormat.Audio -> {
            if (request.target !in audioTargets) fail("Choose a supported audio format.")
            if (request.quality !in audioQualities) fail("Choose a supported audio quality.")
            request.copy(formatId = checkedId(request.formatId))
        }
    }

    private fun checkedSeconds(value: Double?): Double? {
        if (value == null) return null
        if (value.isFinite() && value >= 0 && value <= MAX_SECONDS) return value
        fail("Use a time range within the media duration.")
    }

    /** Known fields only, with checked values; blank subtitle languages become "en.*". */
    fun validate(options: PlanOptions): PlanOptions {
        val subtitles = options.subtitles?.let {
            val languages = jsTrim(it.languages).ifEmpty { "en.*" }
            if (!isSubtitleLanguages(languages)) fail("Use subtitle language codes such as en.*,ar.*")
            it.copy(languages = languages)
        }
        options.sponsorBlock?.let { if (it !in sponsorModes) fail("Choose a SponsorBlock mode.") }
        val start = checkedSeconds(options.sectionStart)
        val end = checkedSeconds(options.sectionEnd)
        if (end != null && end <= (start ?: 0.0)) fail("The end of the time range must be after its start.")
        return options.copy(subtitles = subtitles, sectionStart = start, sectionEnd = end)
    }

    /** MP3 starts at 320 kbit/s wherever a person has not picked a quality (one song, a list, several links). */
    const val MP3_QUALITY = "320"

    fun preset(id: String, container: String = "mp4"): DownloadFormat {
        if (id !in presets) fail("Choose a supported download preset.")
        return when (id) {
            "best" -> DownloadFormat.Video(container = container)
            "2160", "1440", "1080", "720", "480" -> DownloadFormat.Video(maxHeight = id.toInt(), container = container)
            "audio-mp3" -> DownloadFormat.Audio("mp3", MP3_QUALITY)
            "audio-m4a" -> DownloadFormat.Audio("m4a", "best")
            "audio-opus" -> DownloadFormat.Audio("opus", "best")
            else -> DownloadFormat.Audio("flac", "best")
        }
    }

    // ---- the plan ---------------------------------------------------------------------------------------------

    private val audioSelector = mapOf("mp4" to "ba[ext=m4a]", "webm" to "ba[ext=webm]", "mkv" to "ba")
    private val sortCodec = mapOf("h264" to "h264", "hevc" to "h265", "vp9" to "vp9", "av1" to "av01")
    private val audioQualityArg = mapOf("best" to "0", "320" to "320K", "256" to "256K", "192" to "192K", "128" to "128K")
    private val coverAudioTargets = setOf("mp3", "m4a")
    private val coverAudioTargetsWithModule = setOf("opus", "flac", "original")

    /**
     * The plan for a request. Both are validated first. [squareCover] is a YouTube or YouTube Music audio source: its 16:9
     * thumbnail is cropped to a centred square album cover. [chapterTemplate] is the output template of the per-chapter files.
     */
    fun plan(
        format: DownloadFormat,
        options: PlanOptions,
        platform: PlanPlatform = PlanPlatform(),
        squareCover: Boolean = false,
        chapterTemplate: String? = null,
    ): Plan {
        val f = validate(format)
        val o = validate(options)
        val video = f as? DownloadFormat.Video
        val audio = f as? DownloadFormat.Audio

        var selector = ""
        var sort: String? = null
        var merge: String? = null
        var remux: String? = null
        var audioPlan: AudioPlan? = null
        if (video != null) {
            val generic = if (video.container == "webm") "bv*[ext=webm]+ba[ext=webm]/bv*+ba/b" else "bv*+ba/b"
            selector = generic
            if (video.formatId != null) {
                val audioPart = video.audioFormatId ?: audioSelector.getValue(video.container)
                // "V+A" fails when V already carries audio (no multistream merges), so the chain falls back to V alone, then the generic best.
                selector = listOfNotNull(
                    "${video.formatId}+$audioPart",
                    if (video.audioFormatId == null) "${video.formatId}+ba" else null,
                    video.formatId,
                    generic,
                ).joinToString("/")
            }
            // Height and frame rate are preferences: a sort degrades gracefully when no stream fits, a filter would fail (ARGS-3).
            val parts = ArrayList<String>()
            parts.add(if (video.maxHeight != null) "res:${video.maxHeight}" else "res")
            parts.add(if (video.fps != null) "fps:${video.fps}" else "fps")
            // SDR by default: HDR output looks washed out on SDR displays. An exact format id is an explicit pick (ARGS-1).
            if (video.formatId == null) parts.add("hdr:sdr")
            val codec = when {
                video.codec != "auto" -> sortCodec.getValue(video.codec)
                video.container == "mp4" -> "h264"
                video.container == "webm" -> "vp9"
                else -> null
            }
            if (codec != null) parts.add("vcodec:$codec")
            if (video.container == "mp4") parts.add("acodec:aac")
            if (video.container == "webm") parts.add("acodec:opus")
            sort = parts.joinToString(",")
            // WebM cannot carry H.264/AAC, so merges fall back to MKV instead of failing (ARGS-5).
            if (video.container == "webm") merge = "webm/mkv"
            else {
                merge = video.container
                remux = video.container
            }
        } else if (audio != null) {
            val preferred = when (audio.target) {
                "m4a" -> "ba[ext=m4a]/ba[acodec^=mp4a]/"
                "opus" -> "ba[acodec=opus]/"
                else -> ""
            }
            selector = if (audio.formatId != null) "${audio.formatId}/${preferred}ba/b" else "${preferred}ba/b"
            val converts = audio.target == "mp3" || audio.target == "m4a" || audio.target == "opus"
            audioPlan = AudioPlan(audio.target, if (converts) audioQualityArg.getValue(audio.quality) else null)
        }

        val container = video?.container ?: audio!!.target
        // Audio-only downloads write no subtitle files (ARGS-7).
        val subs = o.subtitles
        val subtitles = if (subs != null && subs.enabled && video != null) SubtitlePlan(subs.languages, subs.auto, subs.embed) else null
        val coverContainer = when {
            video != null -> container == "mp4" || container == "mkv"
            else -> container in coverAudioTargets || (platform.coverArtForOpusFlac && container in coverAudioTargetsWithModule)
        }
        val embedThumbnail = o.embedThumbnail == true && coverContainer
        // Chapters go into any audio file but wav (ARGS-8).
        val embedChapters = o.embedChapters == true && !(audio != null && audio.target == "wav")
        val section = if (o.sectionStart != null || o.sectionEnd != null) SectionPlan(o.sectionStart ?: 0.0, o.sectionEnd) else null
        return Plan(
            selector = selector, sort = sort, merge = merge, remux = remux, audio = audioPlan, subtitles = subtitles,
            embedThumbnail = embedThumbnail, squareCover = embedThumbnail && squareCover && audio != null,
            writeThumbnail = o.writeThumbnail == true, embedMetadata = o.embedMetadata == true, embedChapters = embedChapters,
            sponsorBlock = when (o.sponsorBlock) { "mark" -> "mark"; "remove" -> "remove"; else -> null },
            section = section, splitChapters = o.splitChapters == true, chapterTemplate = chapterTemplate,
            writeDescription = o.writeDescription == true, writeInfoJson = o.writeInfoJson == true,
        )
    }

    /** Crops YouTube's 16:9 thumbnail to a centred square so players show a proper album cover. */
    private const val SQUARE_COVER =
        "ThumbnailsConvertor+FFmpeg_o:-c:v mjpeg -qmin 1 -qscale:v 1 -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\""

    /** Seconds as the engine reads them: whole numbers plainly, else up to three decimals without trailing zeros. */
    internal fun secondsText(value: Double): String {
        val millis = floor(value * 1000 + 0.5).toLong()
        val whole = millis / 1000
        val fraction = millis % 1000
        if (fraction == 0L) return whole.toString()
        return "$whole." + fraction.toString().padStart(3, '0').trimEnd('0')
    }

    /** The plan as the download engine's arguments: the format selection first, then the options. */
    fun args(plan: Plan): List<String> = argPairs(plan).flatMap { listOfNotNull(it.first, it.second) }

    /** The same arguments as (option, value) pairs; the value is null for an option without one. */
    fun argPairs(plan: Plan): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        fun add(option: String, value: String? = null) { out.add(Pair(option, value)) }
        add("-f", plan.selector)
        if (plan.audio != null) {
            add("-x")
            if (plan.audio.format != "original") add("--audio-format", plan.audio.format)
            if (plan.audio.quality != null) add("--audio-quality", plan.audio.quality)
        } else {
            if (plan.sort != null) add("-S", plan.sort)
            if (plan.merge != null) add("--merge-output-format", plan.merge)
            if (plan.remux != null) add("--remux-video", plan.remux)
        }
        plan.subtitles?.let {
            add("--write-subs")
            add("--sub-langs", it.langs)
            if (it.auto) add("--write-auto-subs")
            if (it.embed) add("--embed-subs") else add("--convert-subs", "srt")
        }
        if (plan.embedThumbnail) { add("--embed-thumbnail"); add("--convert-thumbnails", "jpg") }
        if (plan.squareCover) add("--ppa", SQUARE_COVER)
        if (plan.embedMetadata) add("--embed-metadata")
        if (plan.embedChapters) add("--embed-chapters")
        when (plan.sponsorBlock) {
            "mark" -> add("--sponsorblock-mark", "all")
            "remove" -> add("--sponsorblock-remove", "sponsor,selfpromo,interaction")
        }
        plan.section?.let { add("--download-sections", "*${secondsText(it.start)}-${it.end?.let(::secondsText) ?: "inf"}") }
        if (plan.splitChapters) {
            add("--split-chapters")
            if (!plan.chapterTemplate.isNullOrEmpty()) add("-o", "chapter:${plan.chapterTemplate}")
        }
        if (plan.writeThumbnail) {
            add("--write-thumbnail")
            if (!plan.embedThumbnail) add("--convert-thumbnails", "jpg")
        }
        if (plan.writeDescription) add("--write-description")
        if (plan.writeInfoJson) add("--write-info-json")
        return out
    }

    // ---- embedded tags ----------------------------------------------------------------------------------------

    /**
     * The engine's `--parse-metadata` reads FROM as a field name when it is a single word, else as an output template: `%`
     * and `:` are escaped, and a single word becomes a template with a default so it is never taken for a field.
     */
    fun metadataLiteral(value: String): String {
        val flat = flatten(value)
        if (flat.isNotEmpty() && flat.all { isAlnum(it) || it == '_' }) return "%(keepfin_literal|$flat)s"
        return flat.replace("\\", "\\\\").replace("%", "%%").replace(":", "\\:")
    }

    /** Line breaks become one space and the text is cut to 300 UTF-16 units. */
    private fun flatten(value: String): String {
        val sb = StringBuilder()
        var inBreak = false
        for (c in value) {
            if (c == '\r' || c == '\n') {
                if (!inBreak) sb.append(' ')
                inBreak = true
            } else {
                sb.append(c)
                inBreak = false
            }
        }
        return sb.toString().take(300)
    }

    /**
     * Embedded tags: the clean job title replaces a caption-sized title, and the description falls back to the title text the
     * page gave when it has no description, so the original wording is never lost.
     */
    fun metadataArgs(title: String?): List<String> {
        val out = arrayListOf("--parse-metadata", "%(description,title)s:%(meta_description)s")
        if (!title.isNullOrBlank()) { out.add("--parse-metadata"); out.add("${metadataLiteral(title)}:%(meta_title)s") }
        return out
    }

    /** The source's own tags for a resolver download (title, artist, album, track number, year, ISRC). Blank values are skipped. */
    fun tagArgs(tags: SourceTags): List<String> {
        val out = ArrayList<String>()
        fun text(value: String?, field: String) {
            if (!value.isNullOrBlank()) { out.add("--parse-metadata"); out.add("${metadataLiteral(value)}:%(meta_$field)s") }
        }
        fun number(value: Int?, field: String) {
            if (value != null) { out.add("--parse-metadata"); out.add("$value:%(meta_$field)s") }
        }
        text(tags.title, "title"); text(tags.artist, "artist"); text(tags.album, "album")
        number(tags.track, "track"); number(tags.year, "date")
        text(tags.artist, "album_artist")
        text(tags.isrc, "isrc")
        return out
    }
}
