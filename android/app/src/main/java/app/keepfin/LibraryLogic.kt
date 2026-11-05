package app.keepfin

import app.keepfin.core.spaces.Space as CoreSpace
import app.keepfin.core.spaces.Spaces as CoreSpaces
import app.keepfin.core.providers.Naming
import app.keepfin.core.providers.SpaceContext
import org.json.JSONArray
import org.json.JSONObject
import app.keepfin.core.dedupe.Recording as CoreRecording
import app.keepfin.core.dedupe.Recordings as CoreRecordings
import java.text.Normalizer
import java.util.Locale

/** The three standard MediaStore roots a space folder lives under. */
enum class StorageRoot(val dir: String) {
    MUSIC("Music"), MOVIES("Movies"), DOWNLOAD("Download");
    companion object {
        fun of(kind: LibraryItemKind) = when (kind) { LibraryItemKind.AUDIO -> MUSIC; LibraryItemKind.VIDEO -> MOVIES; else -> DOWNLOAD }
        fun ofMime(mime: String) = when { mime.startsWith("audio/") -> MUSIC; mime.startsWith("video/") -> MOVIES; else -> DOWNLOAD }
    }
}

/** Everything the layout and naming of a saved file can depend on. */
data class SaveContext(
    val title: String,
    val uploader: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Link used for the platform layout (the source link for Spotify/Apple/Deezer jobs). */
    val url: String = "",
    val groupTitle: String? = null,
    val groupKind: String? = null,
    val audio: Boolean = false,
    val dateMillis: Long = 0L,
) {
    fun toCore() = SpaceContext(title, uploader, artist, album, url, groupTitle, groupKind, audio, dateMillis)
}

/** Pure space logic: folders, layouts, naming, routing, validation. No Android types, fully unit-tested. */
object SpaceLogic {
    fun segment(value: String, max: Int = 80): String = Providers.safeName(value, max)

    /** Sub-path ("" or "A/B") below the space folder for a file saved with [space]'s layout (decided by the shared core). */
    fun subfolder(space: Space, ctx: SaveContext): String = Naming.spaceSubfolder(space.layout.wire, ctx.toCore())

    /** File name (without extension) in the space's naming style (decided by the shared core). */
    fun fileStem(space: Space, ctx: SaveContext): String = Naming.spaceStem(space.naming.wire, ctx.toCore())

    fun isoDate(millis: Long): String = Naming.isoDate(millis)

    /** Folder (no trailing slash) under [root] for [space] and [sub], e.g. "Music/Keepfin Music/Album". */
    fun folderPath(root: StorageRoot, space: Space, sub: String): String =
        listOf(root.dir, space.folder, sub).filter { it.isNotEmpty() }.joinToString("/")

    /** The space owning a MediaStore RELATIVE_PATH ("Music/Keepfin Music/Album/") and the sub-folder inside it; longest folder wins. */
    fun spaceOf(relativePath: String, spaces: List<Space>): Pair<Space, String>? {
        val parts = relativePath.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.size < 2 || StorageRoot.entries.none { it.dir.equals(parts[0], ignoreCase = true) }) return null
        val below = parts.drop(1)
        var best: Pair<Space, String>? = null
        var bestLength = 0
        for (space in spaces) {
            val folder = space.folder.split('/').filter { it.isNotEmpty() }
            if (folder.isEmpty() || folder.size > below.size || folder.size <= bestLength) continue
            if (folder.indices.all { folder[it].equals(below[it], ignoreCase = true) }) {
                best = space to below.drop(folder.size).joinToString("/")
                bestLength = folder.size
            }
        }
        return best
    }

    /** True when the first sub-folder is on the space's ignore list. */
    fun ignored(space: Space, sub: String): Boolean {
        if (space.ignore.isEmpty() || sub.isEmpty()) return false
        val first = sub.substringBefore('/')
        return space.ignore.any { it.equals(first, ignoreCase = true) }
    }

    /** Cleans a user-provided folder into a clean relative path; throws a readable message when nothing usable remains. */
    fun cleanFolder(value: String): String {
        val parts = value.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
        require(parts.isNotEmpty()) { "Choose a folder name." }
        require(parts.size <= 3) { "Use a folder name with at most three levels." }
        val cleaned = parts.map { segment(it, 60) }
        require(cleaned.first().lowercase() !in StorageRoot.entries.map { it.dir.lowercase() } || cleaned.size > 1) { "Pick a folder inside Music, Movies or Downloads, not the folder itself." }
        return cleaned.joinToString("/")
    }

    /** Validates one space against the others. Returns the cleaned space. Smart spaces have no folder, layout or naming. */
    fun validate(space: Space, others: List<Space>): Space {
        val name = space.name.trim().take(40)
        require(name.isNotEmpty()) { "Give the space a name." }
        require(others.none { it.id != space.id && it.name.equals(name, ignoreCase = true) }) { "Another space already has this name." }
        if (space.type == SpaceType.SMART) {
            return space.copy(name = name, folder = "", layout = FolderLayout.FLAT, naming = SpaceNaming.TITLE, skipExisting = false, ignore = emptyList(), scanFolder = false,
                rules = cleanRules(space.rules ?: SmartRules()), kind = SpaceKind.MIXED)
        }
        val folder = cleanFolder(space.folder)
        require(others.none { it.id != space.id && it.type == SpaceType.FOLDER && it.folder.equals(folder, ignoreCase = true) }) { "Another space already uses this folder." }
        val layout = if (space.layout == FolderLayout.ARTIST_ALBUM && space.kind != SpaceKind.MUSIC) FolderLayout.COLLECTION else space.layout
        return space.copy(name = name, folder = folder, layout = layout, ignore = space.ignore.map { it.trim() }.filter { it.isNotEmpty() && '/' !in it }.distinct().take(50), rules = null)
    }

    fun cleanRules(rules: SmartRules): SmartRules = rules.copy(
        rules = rules.rules.mapNotNull { rule ->
            when (rule) {
                is SmartRule.Values -> rule.values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }?.let { SmartRule.Values(rule.field, it) }
                is SmartRule.AddedWithin -> rule.takeIf { it.days in 1..3650 }
                else -> rule
            }
        }.take(20),
        excludeSpaces = rules.excludeSpaces.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    )

    // ---- hub: colors, icons, pins, order ------------------------------------------------------------

    /** Palette order handed to custom spaces in turn. */
    val colorRotation = listOf(SpaceColor.TEAL, SpaceColor.OLIVE, SpaceColor.AMBER, SpaceColor.RUST, SpaceColor.BERRY, SpaceColor.VIOLET, SpaceColor.COBALT, SpaceColor.SLATE)

    fun defaultColor(space: Space, customOrdinal: Int): SpaceColor = when (space.id) {
        "music" -> SpaceColor.COBALT
        "videos" -> SpaceColor.VIOLET
        "downloads" -> SpaceColor.SLATE
        else -> colorRotation[customOrdinal.mod(colorRotation.size)]
    }

    fun defaultIcon(space: Space): SpaceIcon = when {
        space.type == SpaceType.SMART -> SpaceIcon.STAR
        space.kind == SpaceKind.MUSIC -> SpaceIcon.MUSIC
        space.kind == SpaceKind.VIDEO -> SpaceIcon.VIDEO
        else -> SpaceIcon.FOLDER
    }

    /** Fills a missing color, icon and pin with their defaults: built-ins keep their tone and are pinned, custom spaces rotate colors. */
    fun withDefaults(spaces: List<Space>): List<Space> {
        var custom = 0
        return spaces.map { space ->
            // the built-ins and the spaces Keepfin adds (Favorites) have their own colors and take no turn of the palette
            val ordinal = if (space.kept) 0 else custom++
            space.copy(color = space.color ?: defaultColor(space, ordinal), icon = space.icon ?: defaultIcon(space), pinned = space.pinned ?: space.builtin)
        }
    }

    /** The color for a space about to be created: the next palette color not yet used by a custom space (else the rotation continues). */
    fun nextColor(existing: List<Space>): SpaceColor {
        val used = existing.filter { !it.kept }.mapNotNull { it.color }
        return colorRotation.firstOrNull { it !in used } ?: colorRotation[existing.count { !it.kept }.mod(colorRotation.size)]
    }

    const val HOME_MAX = 5

    /** Spaces on the home hub: the pinned ones in hub order, or the first five when nothing is pinned; never more than [HOME_MAX]. */
    fun homeSpaces(spaces: List<Space>): List<Space> {
        val shown = CoreSpaces.home(spaces.map { it.toCore() }).map { it.id }
        val byId = spaces.associateBy { it.id }
        return shown.map { byId.getValue(it) }
    }

    private fun Space.toCore() = CoreSpace(id, type.wire, pinned)

    /** How many spaces "All spaces · N more" stands for. */
    fun moreSpaces(spaces: List<Space>): Int = CoreSpaces.more(spaces.map { it.toCore() })

    /** "All spaces": folder spaces first, then smart spaces, each in hub order. */
    fun allSpaces(spaces: List<Space>): List<Space> = spaces.filter { it.type == SpaceType.FOLDER } + spaces.filter { it.type == SpaceType.SMART }

    /**
     * New hub order from [ids]: covers every space (built-ins included); spaces not listed keep their relative order at the end.
     * An unknown or repeated id means the caller's list is out of date: it is rejected (IllegalArgumentException), never half applied.
     */
    fun reorder(spaces: List<Space>, ids: List<String>): List<Space> {
        val order = CoreSpaces.reorder(spaces.map { it.toCore() }, ids)
        require(order != null) { "That list of spaces is out of date. Refresh and try again." }
        val byId = spaces.associateBy { it.id }
        return order.map { byId.getValue(it.id) }
    }

    /** Default space id for a link: the music route for music-service / YouTube Music links, else the other route. Null when the library is off. */
    fun route(settings: LibrarySettings, url: String, uploader: String? = null, groupUrl: String? = null): String? {
        if (!settings.enabled) return null
        // The core decides from the link; Android also knows from the uploader and the collection link.
        return CoreSpaces.defaultSpace(url, settings.musicRoute, settings.otherRoute, settings.spaces.map { it.toCore() }, music = Restriction.isMusicLike(url, uploader, groupUrl))
    }

    /** [wanted] unless a file of that name (case-insensitive) exists; then "name (2).ext", "name (3).ext"… */
    fun uniqueName(existing: Set<String>, wanted: String): String {
        val taken = existing.map { it.lowercase() }.toSet()
        if (wanted.lowercase() !in taken) return wanted
        val dot = wanted.lastIndexOf('.').takeIf { it > 0 }
        val stem = if (dot == null) wanted else wanted.substring(0, dot)
        val extension = if (dot == null) "" else wanted.substring(dot)
        var n = 2
        while ("$stem ($n)$extension".lowercase() in taken) n++
        return "$stem ($n)$extension"
    }

    // ---- settings JSON ---------------------------------------------------------------------------

    fun encode(settings: LibrarySettings): JSONObject = JSONObject()
        .put("enabled", settings.enabled).put("builtInPlayer", settings.builtInPlayer).put("normalize", settings.normalize)
        .put("crossfade", settings.crossfade).put("musicRoute", settings.musicRoute).put("otherRoute", settings.otherRoute).put("importTags", settings.importTags)
        .put("spaces", JSONArray().also { array -> settings.spaces.forEach { array.put(encode(it)) } })
        .put("offered", JSONArray(settings.offered))

    /**
     * Gives a library every kept space it lacks (Favorites: a new install, an earlier version's library, one removed by an older
     * version). Decided by id alone; a renamed one is still there. The `offered` list is old data and is left as it is.
     */
    fun withKept(settings: LibrarySettings): LibrarySettings {
        val spaces = withKeptSpaces(settings.spaces)
        return if (spaces === settings.spaces) settings else settings.copy(spaces = spaces)
    }

    fun encode(space: Space): JSONObject = JSONObject().put("id", space.id).put("name", space.name).put("kind", space.kind.wire)
        .put("folder", space.folder).put("layout", space.layout.wire).put("naming", space.naming.wire)
        .put("skipExisting", space.skipExisting).put("ignore", JSONArray(space.ignore)).put("scanFolder", space.scanFolder)
        .put("type", space.type.wire).put("color", space.color?.wire).put("icon", space.icon?.wire).put("pinned", space.pinned)
        .put("rules", space.rules?.let(::encodeRules))

    fun encodeRules(rules: SmartRules): JSONObject = JSONObject().put("match", rules.match.wire).put("onlyKind", rules.onlyKind?.wire)
        .put("excludeSpaces", JSONArray(rules.excludeSpaces))
        .put("rules", JSONArray().also { array ->
            rules.rules.forEach { rule ->
                array.put(when (rule) {
                    is SmartRule.Values -> JSONObject().put("field", rule.field.wire).put("values", JSONArray(rule.values))
                    is SmartRule.AddedWithin -> JSONObject().put("field", "addedWithin").put("days", rule.days)
                    SmartRule.Favorite -> JSONObject().put("field", "favorite")
                    SmartRule.Unplayed -> JSONObject().put("field", "unplayed")
                })
            }
        })

    fun decodeRules(json: JSONObject?): SmartRules? {
        if (json == null) return null
        val array = json.optJSONArray("rules")
        val rules = if (array == null) emptyList() else (0 until array.length()).mapNotNull { index ->
            val rule = array.optJSONObject(index) ?: return@mapNotNull null
            when (val field = rule.optString("field")) {
                "addedWithin" -> SmartRule.AddedWithin(rule.optInt("days"))
                "favorite" -> SmartRule.Favorite
                "unplayed" -> SmartRule.Unplayed
                else -> SmartField.entries.firstOrNull { it.wire == field }?.let { f ->
                    val values = rule.optJSONArray("values")?.let { v -> (0 until v.length()).mapNotNull { v.optString(it).takeIf { x -> x.isNotBlank() } } } ?: emptyList()
                    SmartRule.Values(f, values)
                }
            }
        }
        val excluded = json.optJSONArray("excludeSpaces")?.let { v -> (0 until v.length()).mapNotNull { v.optString(it).takeIf { x -> x.isNotBlank() } } } ?: emptyList()
        return SmartRules(
            match = if (json.optString("match") == "all") SmartMatch.ALL else SmartMatch.ANY, rules = rules,
            onlyKind = LibraryItemKind.entries.firstOrNull { it.wire == json.optString("onlyKind") }, excludeSpaces = excluded,
        )
    }

    /**
     * Absent → defaults (lossless migration from 0.4.x and 0.5.0-beta). Unreadable spaces are dropped; "downloads" always exists;
     * routes point at real folder spaces; missing colors, icons and pins get their defaults.
     */
    fun decode(json: JSONObject?): LibrarySettings {
        if (json == null) return withKept(LibrarySettings())
        val defaults = LibrarySettings()
        val array = json.optJSONArray("spaces")
        val decoded = if (array == null) defaultSpaces() else (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::decodeSpace) }
        val unique = LinkedHashMap<String, Space>()
        decoded.forEach { unique.putIfAbsent(it.id, it) }
        val folders = HashSet<String>()
        val spaces = unique.values.filter { it.type == SpaceType.SMART || folders.add(it.folder.lowercase()) }.toMutableList()
        if (spaces.none { it.id == "downloads" }) spaces.add(0, defaultSpaces().first())
        val filled = withDefaults(spaces)
        fun route(key: String, fallback: String) = json.optString(key, fallback).takeIf { id -> filled.any { it.id == id && it.type == SpaceType.FOLDER } } ?: if (filled.any { it.id == fallback }) fallback else "downloads"
        val offered = json.optJSONArray("offered")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { id -> id.isNotBlank() && id.length <= 64 } } }.orEmpty().distinct()
        return withKept(
            LibrarySettings(
                enabled = json.optBoolean("enabled", defaults.enabled), builtInPlayer = json.optBoolean("builtInPlayer", defaults.builtInPlayer),
                normalize = json.optBoolean("normalize", defaults.normalize), crossfade = json.optInt("crossfade", 0).coerceIn(0, 12),
                spaces = filled, musicRoute = route("musicRoute", defaults.musicRoute), otherRoute = route("otherRoute", defaults.otherRoute),
                importTags = json.optBoolean("importTags", defaults.importTags), offered = offered,
            ),
        )
    }

    private fun decodeSpace(json: JSONObject): Space? {
        val id = json.optString("id").takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
        val name = json.optString("name").trim().take(40).takeIf { it.isNotEmpty() } ?: return null
        val type = SpaceType.entries.firstOrNull { it.wire == json.optString("type") } ?: SpaceType.FOLDER
        val color = SpaceColor.entries.firstOrNull { it.wire == json.optString("color") }
        val icon = SpaceIcon.entries.firstOrNull { it.wire == json.optString("icon") }
        val pinned = if (json.has("pinned") && !json.isNull("pinned")) json.optBoolean("pinned") else null
        if (type == SpaceType.SMART) {
            return Space(id, name, SpaceKind.MIXED, "", FolderLayout.FLAT, SpaceNaming.TITLE, false, emptyList(), false, SpaceType.SMART, color, icon, pinned, decodeRules(json.optJSONObject("rules")) ?: SmartRules())
        }
        val folder = try { cleanFolder(json.optString("folder")) } catch (_: IllegalArgumentException) { return null }
        val kind = SpaceKind.entries.firstOrNull { it.wire == json.optString("kind") } ?: SpaceKind.MIXED
        val layout = FolderLayout.entries.firstOrNull { it.wire == json.optString("layout") } ?: FolderLayout.COLLECTION
        val ignore = json.optJSONArray("ignore")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList()
        return Space(id, name, kind, folder, if (layout == FolderLayout.ARTIST_ALBUM && kind != SpaceKind.MUSIC) FolderLayout.COLLECTION else layout,
            SpaceNaming.entries.firstOrNull { it.wire == json.optString("naming") } ?: SpaceNaming.TITLE,
            json.optBoolean("skipExisting", kind == SpaceKind.MUSIC), ignore, json.optBoolean("scanFolder", false), SpaceType.FOLDER, color, icon, pinned, null)
    }

    /** Applies [validate] to every space, fills defaults and keeps routes on folder spaces; used before settings are stored. */
    fun sanitize(settings: LibrarySettings): LibrarySettings {
        val cleaned = ArrayList<Space>()
        for (space in settings.spaces) cleaned.add(validate(space, cleaned))
        if (cleaned.none { it.id == "downloads" }) cleaned.add(0, defaultSpaces().first())
        val filled = withDefaults(withKeptSpaces(cleaned))
        fun route(id: String, fallback: String) = if (filled.any { it.id == id && it.type == SpaceType.FOLDER }) id else if (filled.any { it.id == fallback }) fallback else "downloads"
        return settings.copy(spaces = filled, crossfade = settings.crossfade.coerceIn(0, 12), musicRoute = route(settings.musicRoute, "music"), otherRoute = route(settings.otherRoute, "downloads"))
    }
}

/**
 * Identifiers of the same recording across providers. Links are read by the shared core (app.keepfin.core.dedupe), the same way
 * the desktop app reads them.
 */
object SourceIds {
    /** All ids a link names: "youtube:<id>", "spotify:<id>", "apple-music:<id>", "deezer:<id>". Collections have none. */
    fun of(url: String?): Set<String> = CoreRecordings.sourceIds(url)

    /** Every id for a job: its own link, the music-service link it came from, and the matched or alternate upload. */
    fun of(job: Job): Set<String> = buildSet {
        addAll(of(job.url))
        job.match?.let { addAll(of(it.sourceUrl)); addAll(of(it.matchedUrl)) }
    }

    /** The id shown as [LibraryItem.sourceId]: the originating service first, else the YouTube id. */
    fun primary(job: Job): String? {
        val own = job.match?.takeIf { it.source != Restriction.SOURCE }?.let { of(it.sourceUrl) }.orEmpty()
        return own.firstOrNull() ?: of(job.url).firstOrNull() ?: of(job).firstOrNull()
    }
}

/** Candidate recording (a job about to start, a collection entry, an inspected link) or a known library recording. */
data class Recording(
    val sourceIds: Set<String> = emptySet(),
    val contentKey: String? = null,
    val artist: String? = null,
    val title: String,
    val album: String? = null,
    val duration: Double? = null,
) {
    internal fun toCore() = CoreRecording(title = title, artist = artist, album = album, duration = duration, sourceIds = sourceIds, contentKey = contentKey)
}

/**
 * The same-recording check from dev-docs/architecture/LIBRARY.md: source id, then content, then artist + title + duration. The decision
 * is made by the shared core (app.keepfin.core.dedupe.Recordings); this object only adapts the app's types.
 */
object Recordings {
    private fun fold(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)

    private fun squash(value: String): String = fold(value).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Primary artist only, as a grouping key for albums ("A, B", "A & B", "A feat. B" all fold to "a"; "- Topic" channels are cleaned).
     * This is not part of the duplicate decision, which uses the core's artist rules.
     */
    fun normalizeArtist(raw: String?): String {
        val cleaned = Restriction.artistOf(raw) ?: return ""
        val primary = cleaned.split(Regex("\\s*(?:,|&|;|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bx\\b|\\bwith\\b|\\band\\b)\\s*", RegexOption.IGNORE_CASE)).firstOrNull().orEmpty()
        return squash(primary)
    }

    /** "source" | "isrc" | "content" | "metadata" when [a] and [b] are the same recording, else null. */
    fun compare(a: Recording, b: Recording): String? = CoreRecordings.compare(a.toCore(), b.toCore())?.wire

    /** First known recording that matches [candidate] with the strictest tier, with the tier. */
    fun <T> find(candidate: Recording, known: Iterable<Pair<T, Recording>>): Pair<T, String>? =
        CoreRecordings.find(candidate.toCore(), known.map { (item, recording) -> item to recording.toCore() })?.let { it.first to it.second.wire }
}

/** Parses LRC text (synced) or plain lyrics. */
object LyricsParser {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val meta = Regex("^\\[[A-Za-z#]+:[^\\]]*]\\s*$")
    private val inlineWord = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")

    fun parse(text: String): Lyrics? {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var offset = 0L
        lines.forEach { line -> Regex("^\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(line.trim())?.let { offset = it.groupValues[1].toLong() } }
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<LyricLine>()
        for (raw in lines) {
            val line = raw.trim()
            if (meta.matches(line) && !stamp.containsMatchIn(line)) continue
            val stamps = stamp.findAll(line).toList()
            val body = inlineWord.replace(stamp.replace(line, ""), "").trim()
            if (stamps.isEmpty()) { if (line.isNotEmpty()) plain.add(LyricLine(null, line)); continue }
            for (s in stamps) {
                val fraction = s.groupValues[3]
                val ms = s.groupValues[1].toLong() * 60_000 + s.groupValues[2].toLong() * 1000 +
                    (if (fraction.isEmpty()) 0L else (fraction.padEnd(3, '0').take(3).toLong()))
                timed.add(LyricLine((ms - offset).coerceAtLeast(0), body))
            }
        }
        if (timed.isNotEmpty()) return Lyrics(true, timed.sortedBy { it.timeMs })
        val trimmed = plain.dropWhile { it.text.isEmpty() }
        return if (trimmed.isEmpty()) null else Lyrics(false, trimmed)
    }
}

/** JSON codec for the library cache (library.json). */
internal object LibraryCodec {
    const val VERSION = 2

    /** A cached item plus the bookkeeping that never leaves the store. */
    data class Rec(
        val item: LibraryItem,
        val sourceIds: Set<String> = emptySet(),
        val fingerprint: String? = null,
        /** 0 = not analysed, 1 = tags/fingerprint read, 2 = loudness measured (or not applicable). */
        val analysis: Int = 0,
        val owned: Boolean = true,
        val adopted: Boolean = false,
        /** MediaStore display name (with extension). */
        val name: String = "",
    )

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
    private fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

    private fun strings(array: JSONArray?): List<String> = array?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotEmpty) } } ?: emptyList()

    fun encodeItem(rec: Rec): JSONObject {
        val i = rec.item
        return JSONObject().put("id", i.id).put("space", i.space).put("uri", i.uri).put("folder", i.folder).put("kind", i.kind.wire)
            .put("title", i.title).put("artist", i.artist).put("album", i.album).put("albumArtist", i.albumArtist).put("track", i.track)
            .put("year", i.year).put("duration", i.duration).put("size", i.size).put("modifiedAt", i.modifiedAt).put("addedAt", i.addedAt)
            .put("sourceUrl", i.sourceUrl).put("sourceId", i.sourceId).put("jobId", i.jobId).put("art", i.art).put("width", i.width).put("height", i.height)
            .put("position", i.position).put("plays", i.plays).put("lastPlayedAt", i.lastPlayedAt).put("favorite", i.favorite).put("watched", i.watched)
            .put("hasLyrics", i.hasLyrics).put("gain", i.gain).put("disc", i.disc).put("genre", i.genre)
            .put("tags", JSONArray(i.tags)).put("userTags", JSONArray(i.userTags))
            .put("sourceIds", JSONArray(rec.sourceIds.toList())).put("fingerprint", rec.fingerprint).put("analysis", rec.analysis)
            .put("owned", rec.owned).put("adopted", rec.adopted).put("name", rec.name)
    }

    fun decodeItem(json: JSONObject): Rec? {
        val id = json.str("id") ?: return null
        val kind = LibraryItemKind.entries.firstOrNull { it.wire == json.optString("kind") } ?: return null
        val item = LibraryItem(
            id = id, space = json.str("space") ?: return null, uri = json.str("uri") ?: id, folder = json.optString("folder"), kind = kind,
            title = json.str("title") ?: "Untitled", artist = json.str("artist"), album = json.str("album"), albumArtist = json.str("albumArtist"),
            track = json.int("track"), year = json.int("year"), duration = json.dbl("duration"), size = json.optLong("size"),
            modifiedAt = json.optLong("modifiedAt"), addedAt = json.optLong("addedAt"), sourceUrl = json.str("sourceUrl"), sourceId = json.str("sourceId"),
            jobId = json.str("jobId"), art = json.str("art"), width = json.int("width"), height = json.int("height"), position = json.dbl("position"),
            plays = json.optInt("plays"), lastPlayedAt = json.long("lastPlayedAt"), favorite = json.optBoolean("favorite"), watched = json.optBoolean("watched"),
            hasLyrics = json.optBoolean("hasLyrics"), gain = json.dbl("gain"), disc = json.int("disc"), genre = json.str("genre"),
            tags = strings(json.optJSONArray("tags")), userTags = strings(json.optJSONArray("userTags")),
        )
        val ids = json.optJSONArray("sourceIds")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter(String::isNotEmpty).toSet() } ?: emptySet()
        return Rec(item, ids, json.str("fingerprint"), json.optInt("analysis"), json.optBoolean("owned", true), json.optBoolean("adopted"), json.optString("name"))
    }

    /** What library.json holds besides the items. */
    data class FileData(val items: List<Rec>, val collections: List<LibraryCollection>, val groupsMigrated: Boolean)

    fun encodeFile(recs: Collection<Rec>, collections: List<LibraryCollection>, groupsMigrated: Boolean = true): String = JSONObject().put("version", VERSION)
        .put("items", JSONArray().also { a -> recs.forEach { a.put(encodeItem(it)) } })
        .put("collections", JSONArray().also { a -> collections.forEach { a.put(CollectionLogic.encode(it)) } })
        .put("groupsMigrated", groupsMigrated).toString()

    /**
     * Reads library.json. Version 1 files hold "playlists" ({id, name, itemIds, createdAt, updatedAt}); they become collections of kind
     * own, losslessly, and the downloaded-playlist migration still has to run ([FileData.groupsMigrated] false).
     */
    fun decodeFile(text: String): FileData {
        val root = JSONObject(text)
        val items = root.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::decodeItem) } } ?: emptyList()
        val byId = items.associateBy { it.item.id }
        val collections = ArrayList<LibraryCollection>()
        root.optJSONArray("collections")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(CollectionLogic::decode) }.forEach(collections::add) }
        root.optJSONArray("playlists")?.let { a ->
            for (index in 0 until a.length()) {
                val json = a.optJSONObject(index) ?: continue
                val id = json.str("id") ?: continue
                if (collections.any { it.id == id }) continue
                val ids = strings(json.optJSONArray("itemIds"))
                collections.add(CollectionLogic.fromPlaylist(id, json.str("name") ?: "Playlist", ids, json.optLong("createdAt"), json.optLong("updatedAt"),
                    CollectionLogic.ownSpace(ids) { byId[it]?.item?.space }))
            }
        }
        return FileData(items, collections, root.optBoolean("groupsMigrated", root.has("collections")))
    }
}

/** Playback bookkeeping rules shared by the store and its tests. */
object PlaybackRules {
    const val COUNT_AFTER_SECONDS = 30.0
    const val WATCHED_FRACTION = 0.92
    /** Music restarts; video and audio longer than this resume. */
    const val RESUME_AUDIO_MIN_SECONDS = 20 * 60.0

    /** Whether to keep a resume position for an item. */
    fun resumes(kind: LibraryItemKind, duration: Double?): Boolean =
        kind == LibraryItemKind.VIDEO || (kind == LibraryItemKind.AUDIO && (duration ?: 0.0) > RESUME_AUDIO_MIN_SECONDS)

    fun watched(position: Double, duration: Double?): Boolean = duration != null && duration > 0 && position / duration >= WATCHED_FRACTION

    /** Linear volume for a gain in dB; attenuation only. */
    fun volumeFor(gainDb: Double?): Float = if (gainDb == null) 1f else Math.pow(10.0, gainDb.coerceAtMost(0.0) / 20.0).toFloat().coerceIn(0.01f, 1f)
}

/** Subtitle file extensions, and with `.lrc` every sidecar that travels with a song or video. */
internal val SUBTITLE_EXTENSIONS = setOf("vtt", "srt", "ass", "ssa")
internal val ALL_SIDECARS = SUBTITLE_EXTENSIONS + setOf("lrc")

/** What a job looks like to the same-recording check. */
internal fun JobRecord.recording(): Recording = Recording(
    sourceIds = SourceIds.of(job), artist = source?.artist ?: job.uploader, title = source?.title ?: job.title,
    album = source?.album, duration = job.duration,
)
