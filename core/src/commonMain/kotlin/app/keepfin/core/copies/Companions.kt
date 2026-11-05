package app.keepfin.core.copies

import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.isContentId

// The files that go with an item (dev-docs/plans/COPIES-COMPANIONS.md): synced lyrics (`Song.lrc`), subtitles (`Video.en.srt`),
// the download's details (`Video.info.json`) and a cover (`Song.jpg`), beside the media file and named after it. Both apps decide the same way
// which files these are, and record each one as an item field, `companion:<suffix>` = {"content", "size"} (null once it is
// gone), so a copy holds them too. One field per suffix: a lyrics edit on one device never overwrites a subtitle on another.

/** The item field prefix of a companion record. */
const val COMPANION_FIELD = "companion:"

/** What a companion entry of the desired set carries as its revision: never an op id, so it is never taken for a media revision. */
const val COMPANION_REVISION = "companion:"

private const val MAX_SUFFIX = 64
private val subtitleExtensions = setOf("srt", "vtt", "ass", "ssa")

/** A song's music video beside it (`Song.video.mp4`, dev-docs/plans/MUSIC-VIDEOS.md): the containers both apps play. */
val VIDEO_COMPANION_EXTENSIONS = setOf("mp4", "webm", "m4v", "mov")

/** A cover saved beside the media file (`Song.jpg`; the phone keeps a download's thumbnail so). */
private val coverSuffixes = setOf(".jpg", ".jpeg", ".png", ".webp")

private fun asciiLower(s: String): String = buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') Char(c.code + 32) else c) }

/** One companion of an item: its name after the media file's stem, and its bytes. */
data class Companion(val suffix: String, val content: String, val size: Long)

/**
 * Whether [suffix] names a companion: `.lrc`, `.info.json`, or a subtitle (`.srt`, `.vtt`, `.ass`, `.ssa`) with or without a
 * language tag (`.en.srt`). A plain file-name tail: no separators, no `..`, nothing Windows refuses in a name.
 */
fun isCompanionSuffix(suffix: String): Boolean {
    if (suffix.length < 2 || suffix.length > MAX_SUFFIX || suffix[0] != '.') return false
    for (c in suffix) if (c < ' ' || c in "/\\:*?\"<>|") return false
    if (".." in suffix || suffix.endsWith(' ')) return false
    val lower = asciiLower(suffix)
    if (lower == ".lrc" || lower == ".info.json" || lower in coverSuffixes || isVideoCompanion(suffix)) return true
    val dot = lower.lastIndexOf('.')
    if (lower.substring(dot + 1) !in subtitleExtensions) return false
    // `.srt` alone, or `.<language>.srt` with a non-empty tag
    return dot == 0 || dot > 1
}

/** `.video.mp4`: a song's music video, the one large companion. A copy carries it only when asked to ([companionCandidates]). */
fun isVideoCompanion(suffix: String): Boolean {
    val lower = asciiLower(suffix)
    return lower.startsWith(".video.") && lower.substring(".video.".length) in VIDEO_COMPANION_EXTENSIONS
}

/** The companion suffix of a music video saved with the extension [ext] (`mp4` gives `.video.mp4`), or null for a container the apps do not play. */
fun videoCompanionSuffix(ext: String): String? = asciiLower(ext.removePrefix(".")).takeIf { it in VIDEO_COMPANION_EXTENSIONS }?.let { ".video.$it" }

/**
 * The stem a file named like a music video companion goes with (`Song.video.mp4` gives `Song`), or null. A scanner skips such a file as an
 * item when a media file with that stem is beside it: the video belongs to the song.
 */
fun videoCompanionStem(name: String): String? {
    val lower = asciiLower(name)
    for (ext in VIDEO_COMPANION_EXTENSIONS) {
        val tail = ".video.$ext"
        if (lower.endsWith(tail) && name.length > tail.length) return name.substring(0, name.length - tail.length)
    }
    return null
}

private fun stemOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
}

/** The companion suffix of the file [name] beside the media file [mediaName] (bare names in one folder), or null when it is none. */
fun companionSuffix(mediaName: String, name: String): String? {
    if (name == mediaName) return null
    val stem = stemOf(mediaName)
    if (name.length <= stem.length || !name.startsWith(stem, ignoreCase = true) || name[stem.length] != '.') return null
    val suffix = name.substring(stem.length)
    return if (isCompanionSuffix(suffix)) suffix else null
}

/** Where the companion [suffix] of the media file at [mediaPath] lives (`Music/Song.mp3` and `.lrc` give `Music/Song.lrc`). */
fun companionPath(mediaPath: String, suffix: String): String {
    val slash = maxOf(mediaPath.lastIndexOf('/'), mediaPath.lastIndexOf('\\'))
    return mediaPath.substring(0, slash + 1) + stemOf(mediaPath.substring(slash + 1)) + suffix
}

/** The item field that records the companion [suffix]. */
fun companionField(suffix: String): String = COMPANION_FIELD + suffix

/** The value of a companion field. */
fun companionValue(content: String, size: Long): Jv = JObj(mapOf("content" to JStr(content), "size" to JNum(size)))

/** The companions an item's [fields] record, by suffix. Fields that are null, malformed or name no companion are left out. */
fun companionsOf(fields: Map<String, Jv>): List<Companion> {
    val out = ArrayList<Companion>()
    for ((name, value) in fields) {
        if (!name.startsWith(COMPANION_FIELD)) continue
        val suffix = name.substring(COMPANION_FIELD.length)
        if (!isCompanionSuffix(suffix)) continue
        val m = (value as? JObj)?.members ?: continue
        val content = (m["content"] as? JStr)?.value ?: continue
        val size = (m["size"] as? JNum)?.value ?: continue
        if (!isContentId(content) || size < 0) continue
        out.add(Companion(suffix, content, size))
    }
    out.sortBy { it.suffix }
    return out
}

/** The suffix a desired entry is the companion of, or null for the media file itself. */
fun companionOfEntry(revisionOp: String?): String? =
    if (revisionOp != null && revisionOp.startsWith(COMPANION_REVISION)) revisionOp.substring(COMPANION_REVISION.length) else null

/** The name key companions are told apart by: letter case ignored, as on Windows and most drives. */
fun companionKey(path: String): String = NameRules.caseInsensitiveAscii.key(path)

/**
 * The desired set's companion entries, from every candidate: one per name on the destination. A name that a media file
 * already has ([isMediaKey], given the [companionKey] of a path), or that two items' companions share (`Song.mp3` and
 * `Song.flac` both make `Song.lrc`), keeps only the entry of the smallest item id, so both apps and the SQL twin choose the
 * same one.
 */
fun keepCompanions(candidates: List<DesiredEntry>, isMediaKey: (String) -> Boolean): List<DesiredEntry> =
    keptCompanionIndices(candidates.map { it.path }, candidates.map { it.itemId }, isMediaKey).map { candidates[it] }

/** [keepCompanions] over parallel lists of paths and item ids: the indices kept, in name order. */
fun keptCompanionIndices(paths: List<String>, itemIds: List<String>, isMediaKey: (String) -> Boolean): List<Int> {
    // each key once: a sort that recomputed it per comparison costs seconds at 100,000 lyrics files
    val keys = Array(paths.size) { companionKey(paths[it]) }
    val order = paths.indices.sortedWith { a, b -> keys[a].compareTo(keys[b]).let { if (it != 0) it else itemIds[a].compareTo(itemIds[b]) } }
    val seen = HashSet<String>()
    val out = ArrayList<Int>()
    for (i in order) if (!isMediaKey(keys[i]) && seen.add(keys[i])) out.add(i)
    return out
}

/**
 * The companion candidates of one desired media entry: an item with a single head carries the companions its [fields]
 * record, beside [mediaPath]. Conflicting heads carry none until the conflict is settled. [videos]: music videos
 * ([PlaceOptions.musicVideos]); [others]: lyrics, subtitles and info files ([PlaceOptions.companions]).
 */
fun companionCandidates(itemId: String, mediaPath: String, heads: Int, fields: Map<String, Jv>, videos: Boolean = true, others: Boolean = true): List<DesiredEntry> {
    if (heads != 1) return emptyList()
    return companionsOf(fields).filter { if (isVideoCompanion(it.suffix)) videos else others }.map { DesiredEntry(companionPath(mediaPath, it.suffix), itemId, COMPANION_REVISION + it.suffix, it.content, it.size) }
}

/**
 * One field of [companionCandidates] already read by the caller ([field] is its name, [content] and [size] its value's members,
 * empty and -1 when missing): the entry, or null when the field records no companion.
 */
fun companionCandidate(itemId: String, mediaPath: String, heads: Int, field: String, content: String, size: Long, videos: Boolean = true, others: Boolean = true): DesiredEntry? {
    if (heads != 1 || !field.startsWith(COMPANION_FIELD)) return null
    val suffix = field.substring(COMPANION_FIELD.length)
    if (!isCompanionSuffix(suffix) || !isContentId(content) || size < 0) return null
    if (!(if (isVideoCompanion(suffix)) videos else others)) return null
    return DesiredEntry(companionPath(mediaPath, suffix), itemId, COMPANION_REVISION + suffix, content, size)
}
