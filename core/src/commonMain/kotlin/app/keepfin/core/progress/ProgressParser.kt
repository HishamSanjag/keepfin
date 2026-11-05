package app.keepfin.core.progress

import app.keepfin.core.music.jsTrim
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Engine output lines -> aggregated transfer progress and processing phases (fixtures/engine/progress).
// The behaviour is the desktop parser's; what differs per platform is data: the wire format of the
// engine's progress template (which fields exist, and where) and Android's aria2c output.
//
// Pure and allocation-light: accept() runs for every output line of every download. Bytes are Doubles,
// like JavaScript numbers (exact up to 2^53).

/** Which app's engine output is parsed. */
enum class ProgressPlatform { DESKTOP, ANDROID }

/** What a line changed: nothing, the transfer numbers, or the processing phase. */
enum class ProgressChange { NONE, PROGRESS, PHASE }

/** Phase keys. Each app words them for its own screens; the keys are shared. */
object PhaseKey {
    const val MERGE = "merge"
    const val EXTRACT_AUDIO = "extract-audio"
    const val EMBED_COVER = "embed-cover"
    const val CONVERT_COVER = "convert-cover"
    const val METADATA = "metadata"
    const val EMBED_SUBS = "embed-subs"
    const val CONVERT_SUBS = "convert-subs"
    const val SPONSORBLOCK = "sponsorblock"
    const val MODIFY_CHAPTERS = "modify-chapters"
    const val SPLIT_CHAPTERS = "split-chapters"
    const val FIXUP = "fixup"
    const val REMUX = "remux"
    const val CONVERT_VIDEO = "convert-video"
}

/**
 * The shape of one platform's progress line: its prefix and the position of each field after the prefix,
 * split on `|`. A field the template does not have is -1 (Android has no fragment fields, so a stream
 * without sizes stays at 0%: PROG-3 is this data).
 */
class ProgressWire(
    val prefix: String,
    val minFields: Int,
    val id: Int,
    val status: Int,
    val downloaded: Int,
    val total: Int,
    val estimate: Int,
    val speed: Int,
    val eta: Int,
    val fragmentIndex: Int,
    val fragmentCount: Int,
) {
    companion object {
        /** `KEEPFIN-P|id|status|downloaded|total|estimate|speed|eta|fragmentIndex|fragmentCount` */
        val DESKTOP = ProgressWire("KEEPFIN-P|", 0, 0, 1, 2, 3, 4, 5, 6, 7, 8)

        /** `[keepfin] status|downloaded|total|estimate|speed|eta|id` (seven fields at least) */
        val ANDROID = ProgressWire("[keepfin]", 7, 6, 0, 1, 2, 3, 4, 5, -1, -1)

        fun of(platform: ProgressPlatform): ProgressWire = if (platform == ProgressPlatform.DESKTOP) DESKTOP else ANDROID
    }
}

/** A read-out of the parser. Bytes are Doubles; speed is bytes/s and eta is seconds. */
class ProgressSnapshot(
    val percent: Double,
    /** Null until a transfer line was seen (0 is a real value after that). */
    val downloadedBytes: Double?,
    val totalBytes: Double?,
    val speed: Double?,
    val eta: Double?,
    /** A [PhaseKey], or null when no post-processing step is running. */
    val phase: String?,
    /** The file extension of an audio conversion (`[ExtractAudio] Destination: x.mp3` gives `mp3`), else null. */
    val phaseDetail: String?,
    val processing: Boolean,
    val sawTransfer: Boolean,
    val usedAria2c: Boolean,
)

private class Stream(val id: String, var total: Double) {
    var done = 0.0
    var finished = false
    var touched = false
}

/**
 * @param expectedSize the inspected size of the whole job (Android passes the sum of its streams), or null.
 * @param streamSizes inspected size per format id (desktop), applied to the streams a download announces.
 */
class ProgressParser(
    private val platform: ProgressPlatform,
    private val expectedSize: Double? = null,
    private val streamSizes: Map<String, Double> = emptyMap(),
) {
    private val wire = ProgressWire.of(platform)
    private var streams = ArrayList<Stream>()
    private var ariaCurrent: Stream? = null
    private var ariaCount = 0
    private var peak = 0.0
    private var speed: Double? = null
    private var eta: Double? = null
    private var fragmentIndex: Double? = null
    private var fragmentCount: Double? = null
    private var phase: String? = null
    private var detail: String? = null
    private var processing = false
    var sawTransfer = false
        private set
    var usedAria2c = false
        private set

    /** Feeds one output line. */
    fun accept(raw: String): ProgressChange {
        val change = read(raw)
        // Percent never goes backwards, even if the caller reads the snapshot less often than it feeds lines.
        if (change == ProgressChange.PROGRESS) refresh()
        return change
    }

    private fun read(raw: String): ProgressChange {
        val line = jsTrim(raw)
        if (line.isEmpty()) return ProgressChange.NONE
        if (line.startsWith(wire.prefix)) return progressLine(line.substring(wire.prefix.length))
        // Everything else starts with a bracket: plain log text costs one comparison.
        if (line[0] != '[') return ProgressChange.NONE
        if (platform == ProgressPlatform.ANDROID && line.startsWith("[#")) {
            val aria = Aria2.parse(line)
            if (aria != null) return ariaLine(aria)
        }
        if (line.startsWith("[info] ")) {
            val ids = downloadingFormats(line)
            if (ids != null) {
                streams = ArrayList()
                for (id in ids) streams.add(Stream(id, streamSizes[id] ?: 0.0))
                return ProgressChange.NONE
            }
        }
        if (line.startsWith("[download]")) return downloadLine(line)
        val key = phaseKey(line) ?: return ProgressChange.NONE
        val newDetail = if (key == PhaseKey.EXTRACT_AUDIO && line.startsWith("[ExtractAudio] Destination:")) line.substringAfterLast('.') else null
        val changed = key != phase || newDetail != detail || !processing
        phase = key
        detail = newDetail
        processing = true
        // Post-processing has no transfer speed.
        speed = null
        eta = null
        return if (changed) ProgressChange.PHASE else ProgressChange.NONE
    }

    private fun downloadLine(line: String): ProgressChange {
        if (platform == ProgressPlatform.ANDROID && line.startsWith("[download] Destination:")) {
            // aria2c has no format ids: a new destination is the next stream.
            ariaCurrent?.let {
                it.finished = true
                if (it.total > 0) it.done = max(it.done, it.total)
            }
            ariaCurrent = null
            return clearPhase()
        }
        if (line.contains("has already been downloaded")) {
            val next = streams.firstOrNull { !it.finished && !it.touched }
            if (next != null) {
                next.finished = true
                next.touched = true
                if (next.total > 0) next.done = next.total
            }
        }
        return ProgressChange.NONE
    }

    private fun clearPhase(): ProgressChange {
        if (!processing && phase == null) return ProgressChange.NONE
        phase = null
        detail = null
        processing = false
        return ProgressChange.PHASE
    }

    private fun stream(id: String): Stream {
        for (s in streams) if (s.id == id) return s
        val created = Stream(id, streamSizes[id] ?: 0.0)
        streams.add(created)
        return created
    }

    private fun field(parts: List<String>, index: Int): Double? = if (index < 0 || index >= parts.size) null else parseCount(parts[index])

    private fun progressLine(body: String): ProgressChange {
        val parts = body.split('|')
        if (parts.size < wire.minFields) return ProgressChange.NONE
        val rawId = if (wire.id < parts.size) jsTrim(parts[wire.id]) else ""
        val id = if (rawId.isEmpty() || (platform == ProgressPlatform.ANDROID && rawId == "NA")) "main" else rawId
        val status = if (wire.status < parts.size) jsTrim(parts[wire.status]) else ""
        val s = stream(id)
        val size = field(parts, wire.total)?.takeIf { it > 0 } ?: field(parts, wire.estimate)?.takeIf { it > 0 }
        if (size != null) s.total = size
        field(parts, wire.downloaded)?.let { s.done = it }
        s.touched = true
        if (status == "finished") {
            s.finished = true
            if (s.total > 0) s.done = s.total
        }
        sawTransfer = true
        speed = field(parts, wire.speed)
        eta = field(parts, wire.eta)
        fragmentIndex = field(parts, wire.fragmentIndex)
        fragmentCount = field(parts, wire.fragmentCount)
        // Download progress means post-processing has not started (or a new stream began).
        if (status != "finished") {
            processing = false
            phase = null
            detail = null
        }
        return ProgressChange.PROGRESS
    }

    private fun ariaLine(aria: Aria2): ProgressChange {
        usedAria2c = true
        sawTransfer = true
        var s = ariaCurrent
        if (s == null) {
            s = streams.firstOrNull { !it.finished && !it.touched } ?: Stream("aria2c-${ariaCount++}", 0.0).also { streams.add(it) }
            ariaCurrent = s
        }
        s.touched = true
        s.done = aria.done
        if (aria.total > 0) s.total = aria.total
        speed = aria.speed
        eta = aria.eta
        fragmentIndex = null
        fragmentCount = null
        processing = false
        phase = null
        detail = null
        return ProgressChange.PROGRESS
    }

    private var downloaded = 0.0
    private var total: Double? = null
    private var currentEta: Double? = null

    /** Recomputes the aggregate from the streams; percent only ever moves forward. */
    private fun refresh() {
        var done = 0.0
        var sizedTotal = 0.0
        var allSized = streams.isNotEmpty()
        for (s in streams) {
            done += s.done
            sizedTotal += s.total
            if (s.total <= 0) allSized = false
        }
        val expected = expectedSize?.takeIf { it > 0 && done <= it }
        val known: Double? = expected ?: if (allSized && sizedTotal > 0) sizedTotal else null
        val percent: Double = if (known != null) {
            done / known * 100
        } else if (streams.isNotEmpty()) {
            // Equal weights when sizes are unknown; fragments refine the active stream.
            var fraction = 0.0
            val last = streams[streams.size - 1]
            val index = fragmentIndex
            val count = fragmentCount
            for (s in streams) {
                fraction += when {
                    s.finished -> 1.0
                    s.total > 0 -> min(1.0, s.done / s.total)
                    s === last && index != null && count != null && count > 0 -> index / count
                    else -> 0.0
                }
            }
            fraction / streams.size * 100
        } else {
            0.0
        }
        val rate = speed
        var remaining = eta
        // aria2c reports its own ETA; otherwise the known size and speed give a steadier one.
        if (known != null && rate != null && rate > 0 && !(usedAria2c && remaining != null)) {
            remaining = max(0.0, (known - done) / rate)
        }
        peak = max(peak, max(0.0, min(99.5, percent)))
        downloaded = done
        total = known
        currentEta = remaining
    }

    /** The current read-out. Percent never goes backwards and stays below 100 until the job finishes. */
    val snapshot: ProgressSnapshot
        get() {
            refresh()
            return ProgressSnapshot(
                percent = peak,
                downloadedBytes = if (sawTransfer) downloaded else null,
                totalBytes = if (sawTransfer) total else null,
                speed = speed,
                eta = currentEta,
                phase = phase,
                phaseDetail = detail,
                processing = processing,
                sawTransfer = sawTransfer,
                usedAria2c = usedAria2c,
            )
        }
}

private const val FORMATS_MARKER = ": Downloading "
private const val FORMATS_MIDDLE = " format(s): "

/** `[info] <id>: Downloading N format(s): a+b` -> the format ids, or null for any other line. */
private fun downloadingFormats(line: String): List<String>? {
    for (c in line) if (c == '\n' || c == '\r' || c == ' ' || c == ' ') return null
    var at = line.lastIndexOf(FORMATS_MARKER)
    while (at >= 7) {
        var i = at + FORMATS_MARKER.length
        val digitsStart = i
        while (i < line.length && line[i] in '0'..'9') i++
        if (i > digitsStart && line.startsWith(FORMATS_MIDDLE, i)) {
            val rest = line.substring(i + FORMATS_MIDDLE.length)
            if (rest.isNotEmpty()) return rest.split('+').map { jsTrim(it) }
        }
        if (at == 0) break
        at = line.lastIndexOf(FORMATS_MARKER, at - 1)
    }
    return null
}

private fun phaseKey(line: String): String? = when {
    line.startsWith("[Merger]") -> PhaseKey.MERGE
    line.startsWith("[ExtractAudio]") -> PhaseKey.EXTRACT_AUDIO
    line.startsWith("[EmbedThumbnail]") -> PhaseKey.EMBED_COVER
    line.startsWith("[ThumbnailsConvertor]") -> PhaseKey.CONVERT_COVER
    line.startsWith("[Metadata]") || line.startsWith("[MetadataParser]") -> PhaseKey.METADATA
    line.startsWith("[EmbedSubtitle]") -> PhaseKey.EMBED_SUBS
    line.startsWith("[SubtitlesConvertor]") -> PhaseKey.CONVERT_SUBS
    line.startsWith("[SponsorBlock]") -> PhaseKey.SPONSORBLOCK
    line.startsWith("[ModifyChapters]") -> PhaseKey.MODIFY_CHAPTERS
    line.startsWith("[SplitChapters]") -> PhaseKey.SPLIT_CHAPTERS
    line.startsWith("[Fixup") || line.startsWith("[FFmpegFixup") -> PhaseKey.FIXUP
    line.startsWith("[VideoRemuxer]") -> PhaseKey.REMUX
    line.startsWith("[VideoConvertor]") -> PhaseKey.CONVERT_VIDEO
    else -> null
}

/**
 * A finite, non-negative number as the engine prints it (`12`, `3.5`, `1e-05`), else null: the cases in which
 * JavaScript's `Number(text)` is a finite number >= 0, minus hex and similar forms the engine never prints.
 * `NA` and `None` are null.
 */
internal fun parseCount(text: String): Double? {
    val t = jsTrim(text)
    if (t.isEmpty()) return null
    var i = 0
    var digits = 0
    while (i < t.length && t[i] in '0'..'9') { i++; digits++ }
    if (i < t.length && t[i] == '.') {
        i++
        while (i < t.length && t[i] in '0'..'9') { i++; digits++ }
    }
    if (digits == 0) return null
    if (i < t.length && (t[i] == 'e' || t[i] == 'E')) {
        i++
        if (i < t.length && (t[i] == '+' || t[i] == '-')) i++
        val expStart = i
        while (i < t.length && t[i] in '0'..'9') i++
        if (i == expStart) return null
    }
    if (i != t.length) return null
    val value = t.toDoubleOrNull() ?: return null
    return if (value.isNaN() || value.isInfinite()) null else value
}

/** One aria2c status line, `[#2089b0 12MiB/150MiB(8%) CN:8 DL:5.2MiB ETA:26s]`. */
internal class Aria2(val done: Double, val total: Double, val speed: Double?, val eta: Double?) {
    companion object {
        fun parse(line: String): Aria2? {
            var from = 0
            while (true) {
                val at = line.indexOf("[#", from)
                if (at < 0) return null
                parseAt(line, at + 2)?.let { return it }
                from = at + 2
            }
        }

        private fun isBlank(c: Char) = c == ' ' || c == '\t'
        private fun isWord(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

        private fun skipBlanks(s: String, from: Int): Int {
            var i = from
            while (i < s.length && isBlank(s[i])) i++
            return i
        }

        private fun parseAt(s: String, start: Int): Aria2? {
            var i = start
            while (i < s.length && isWord(s[i])) i++
            if (i == start) return null
            val afterId = i
            i = skipBlanks(s, i)
            if (i == afterId) return null
            val done = quantity(s, i) ?: return null
            i = done.end
            if (i >= s.length || s[i] != '/') return null
            val total = quantity(s, i + 1) ?: return null
            i = total.end
            // Optional "(8%)".
            if (i < s.length && s[i] == '(') {
                var j = i + 1
                val digits = j
                while (j < s.length && s[j] in '0'..'9') j++
                if (j > digits && s.startsWith("%)", j)) i = j + 2
            }
            // Optional " CN:8".
            var j = skipBlanks(s, i)
            if (j > i && s.startsWith("CN:", j)) {
                var k = j + 3
                val digits = k
                while (k < s.length && s[k] in '0'..'9') k++
                if (k > digits) i = k
            }
            // Optional " DL:5.2MiB".
            var speed: Double? = null
            j = skipBlanks(s, i)
            if (j > i && s.startsWith("DL:", j)) {
                val q = quantity(s, j + 3)
                if (q != null) {
                    speed = q.bytes
                    i = q.end
                }
            }
            // Optional " ETA:1m2s".
            var eta: Double? = null
            j = skipBlanks(s, i)
            if (j > i && s.startsWith("ETA:", j)) {
                var k = j + 4
                val begin = k
                while (k < s.length && (s[k] in '0'..'9' || s[k] == 'h' || s[k] == 'm' || s[k] == 's')) k++
                if (k > begin) eta = seconds(s.substring(begin, k))
            }
            return Aria2(done.bytes, total.bytes, speed, eta)
        }

        private class Quantity(val bytes: Double, val end: Int)

        /** `12`, `5.2` followed by `B`, `KiB`, `MiB`, `GiB` or `TiB` (`KB` and the like count as plain bytes, as before). */
        private fun quantity(s: String, from: Int): Quantity? {
            var i = from
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.')) i++
            if (i == from) return null
            val number = s.substring(from, i)
            val unitStart = i
            if (i < s.length && (s[i] == 'K' || s[i] == 'M' || s[i] == 'G' || s[i] == 'T')) i++
            if (i < s.length && s[i] == 'i') i++
            if (i >= s.length || s[i] != 'B') return null
            i++
            val multiplier = when (s.substring(unitStart, i)) {
                "KiB" -> 1024.0
                "MiB" -> 1024.0 * 1024
                "GiB" -> 1024.0 * 1024 * 1024
                "TiB" -> 1024.0 * 1024 * 1024 * 1024
                else -> 1.0
            }
            return Quantity(floor((number.toDoubleOrNull() ?: 0.0) * multiplier), i)
        }

        /** `1h2m3s` -> seconds; digits without a unit count for nothing. */
        private fun seconds(value: String): Double {
            var total = 0.0
            var i = 0
            while (i < value.length) {
                val start = i
                while (i < value.length && value[i] in '0'..'9') i++
                if (i > start && i < value.length && (value[i] == 'h' || value[i] == 'm' || value[i] == 's')) {
                    val unit = if (value[i] == 'h') 3600 else if (value[i] == 'm') 60 else 1
                    total += value.substring(start, i).toDouble() * unit
                    i++
                } else if (i == start) {
                    i++
                }
            }
            return total
        }
    }
}
