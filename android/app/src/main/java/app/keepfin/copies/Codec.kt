package app.keepfin.copies

import app.keepfin.core.copies.Action
import app.keepfin.core.copies.ActionKind
import app.keepfin.core.copies.Archive
import app.keepfin.core.copies.BaselineEntry
import app.keepfin.core.copies.DesiredEntry
import app.keepfin.core.copies.Expect
import app.keepfin.core.copies.InvLine
import app.keepfin.core.copies.NameRules
import app.keepfin.core.copies.RemoveReason
import app.keepfin.core.copies.Stat
import org.json.JSONObject

/** The name-equivalence rule of a copy volume (probed once, spec 3). */
internal enum class RulesId(val rules: NameRules) {
    EXACT(NameRules.exact),

    /** ASCII case ignored: the rule relationships were first written with. */
    ASCII(NameRules.caseInsensitiveAscii),

    /** Case sensitive, composed and decomposed accents are one name. */
    NFC(NameRules.composed),

    /** Case ignored, composed and decomposed accents are one name: a copy never holds two visually identical names. */
    UNICODE(NameRules.folded),
    ;

    fun key(path: String): String = rules.key(path)
}

/** JSON-lines codecs (the same shapes as the desktop files, so a line means the same on both). */
internal object Codec {
    private fun JSONObject.str(name: String): String? = if (isNull(name)) null else getString(name)

    private fun JSONObject.long(name: String): Long = getLong(name)

    fun inv(line: InvLine): String {
        val o = JSONObject().put("path", line.path).put("size", line.size).put("mtimeMs", line.mtimeMs)
        if (line.fileId != null) o.put("fileId", line.fileId)
        return o.toString()
    }

    fun invOf(text: String): InvLine {
        val o = JSONObject(text)
        return InvLine(o.getString("path"), o.long("size"), o.long("mtimeMs"), o.str("fileId"))
    }

    private fun statJson(s: Stat): JSONObject {
        val o = JSONObject().put("size", s.size).put("mtimeMs", s.mtimeMs)
        if (s.fileId != null) o.put("fileId", s.fileId)
        return o
    }

    private fun statOf(o: JSONObject?): Stat = if (o == null) Stat(0, 0) else Stat(o.long("size"), o.long("mtimeMs"), o.str("fileId"))

    fun baseline(e: BaselineEntry): String =
        JSONObject().put("path", e.path).put("itemId", e.itemId).put("revisionOp", e.revisionOp ?: JSONObject.NULL).put("contentId", e.contentId)
            .put("main", statJson(e.main)).put("copy", statJson(e.copy)).put("scannedAtMs", e.scannedAtMs).put("generation", e.generation).toString()

    fun baselineOf(text: String): BaselineEntry {
        val o = JSONObject(text)
        return BaselineEntry(
            o.getString("path"), o.getString("itemId"), o.str("revisionOp"), o.getString("contentId"),
            statOf(o.optJSONObject("main")), statOf(o.optJSONObject("copy")), o.long("scannedAtMs"), o.long("generation"),
        )
    }

    fun desired(e: DesiredEntry): String =
        JSONObject().put("path", e.path).put("itemId", e.itemId).put("revisionOp", e.revisionOp ?: JSONObject.NULL).put("contentId", e.contentId).put("bytes", e.bytes).toString()

    fun desiredOf(text: String): DesiredEntry {
        val o = JSONObject(text)
        return DesiredEntry(o.getString("path"), o.getString("itemId"), o.str("revisionOp"), o.getString("contentId"), o.long("bytes"))
    }

    fun actionJson(a: Action): JSONObject {
        val o = JSONObject().put("kind", a.kind.name).put("path", a.path).put("bytes", a.bytes).put("displacedBytes", a.displacedBytes).put("review", a.review)
        a.toPath?.let { o.put("toPath", it) }
        a.itemId?.let { o.put("itemId", it) }
        a.revisionOp?.let { o.put("revisionOp", it) }
        a.contentId?.let { o.put("contentId", it) }
        a.archive?.let { o.put("archive", it.name) }
        a.reason?.let { o.put("reason", it.name) }
        a.expect?.let { e ->
            o.put("expect", JSONObject().put("contentId", e.contentId ?: JSONObject.NULL).also { x -> e.fileId?.let { x.put("fileId", it) } })
        }
        return o
    }

    fun actionOf(o: JSONObject): Action {
        val expect = o.optJSONObject("expect")?.let { Expect(it.str("contentId"), it.str("fileId")) }
        return Action(
            kind = ActionKind.valueOf(o.getString("kind")),
            path = o.getString("path"),
            toPath = o.str("toPath"),
            itemId = o.str("itemId"),
            revisionOp = o.str("revisionOp"),
            contentId = o.str("contentId"),
            bytes = o.optLong("bytes"),
            displacedBytes = o.optLong("displacedBytes"),
            expect = expect,
            archive = o.str("archive")?.let { Archive.valueOf(it) },
            reason = o.str("reason")?.let { RemoveReason.valueOf(it) },
            review = o.optBoolean("review"),
        )
    }
}
