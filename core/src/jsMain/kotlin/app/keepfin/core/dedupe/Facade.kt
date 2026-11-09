@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.dedupe

// The JavaScript surface of the dedupe core (see gui/shared/core/dedupe.ts): nullable values, arrays instead of lists.

/** A recording; `duration` is in seconds, `sourceIds` are stable ids such as "youtube:<id>". */
@JsExport
class JsRecording(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val duration: Double? = null,
    val sourceIds: Array<String> = emptyArray(),
    val contentKey: String? = null,
    val isrc: String? = null,
)

/** The position in the list that was searched and the tier it matched by ("source", "isrc", "content" or "metadata"). */
@JsExport
class JsRecordingMatch(val index: Int, val tier: String)

private fun JsRecording.toRecording() = Recording(title, artist, album, duration, sourceIds.toSet(), contentKey, isrc)

@JsExport
fun dedupeCleanIsrc(raw: String?): String? = Recordings.cleanIsrc(raw)

@JsExport
fun dedupeSourceId(url: String?): String? = Recordings.sourceId(url)

@JsExport
fun dedupeSourceIds(url: String?): Array<String> = Recordings.sourceIds(url).toTypedArray()

@JsExport
fun dedupeNormalizeTitle(raw: String, artist: String? = null): String = Recordings.normalizeTitle(raw, artist)

@JsExport
fun dedupeNormalizeArtists(raw: String?): Array<String> = Recordings.normalizeArtists(raw).toTypedArray()

@JsExport
fun dedupeVersionsOf(normalizedTitle: String): Array<String> = Recordings.versionsOf(normalizedTitle).toTypedArray()

@JsExport
fun dedupeSameRecording(a: JsRecording, b: JsRecording): Boolean = Recordings.sameRecording(a.toRecording(), b.toRecording())

/** The strictest tier of two recordings, or null when they are different. */
@JsExport
fun dedupeCompare(a: JsRecording, b: JsRecording): String? = Recordings.compare(a.toRecording(), b.toRecording())?.wire

/** The best match for [candidate] among [known], with its position, or null. */
@JsExport
fun dedupeFind(candidate: JsRecording, known: Array<JsRecording>): JsRecordingMatch? {
    val found = Recordings.find(candidate.toRecording(), known.mapIndexed { index, r -> index to r.toRecording() }) ?: return null
    return JsRecordingMatch(found.first, found.second.wire)
}
