package app.keepfin.core.sync

/** The envelope version: segment and checkpoint layouts and the canonical JSON rules. */
const val FORMAT = 1

/** How ops are interpreted: the kinds, their bodies and their deltas. */
const val SEMANTICS = 1

const val MAX_SAFE_INTEGER = 9007199254740991L

/** One operation's identity: the actor that wrote it and its contiguous sequence number (1..2^53-1). Written `actor:seq`. */
data class OpId(val actor: String, val seq: Long) : Comparable<OpId> {
    /** Actor (string order), then seq as a number. */
    override fun compareTo(other: OpId): Int {
        val c = actor.compareTo(other.actor)
        return if (c != 0) c else seq.compareTo(other.seq)
    }

    override fun toString(): String = "$actor:$seq"

    companion object {
        /** Reads `actor:seq` (the actor must be a lower-case UUID); null when it is not exactly the text [toString] gives. */
        fun parse(text: String): OpId? {
            if (text.length < 38 || text[36] != ':') return null
            val actor = text.substring(0, 36)
            if (!isUuid(actor)) return null
            val seq = parseSeq(text.substring(37)) ?: return null
            return OpId(actor, seq)
        }
    }
}

internal fun parseSeq(digits: String): Long? {
    if (digits.isEmpty() || digits.length > 16) return null
    if (!digits.all { it in '0'..'9' }) return null
    if (digits.length > 1 && digits[0] == '0') return null
    val v = digits.toLong()
    return if (v >= 1 && v <= MAX_SAFE_INTEGER) v else null
}

/** True for a lower-case UUID (8-4-4-4-12 hex), the shape of every id the platform creates. */
fun isUuid(text: String): Boolean {
    if (text.length != 36) return false
    for (i in text.indices) {
        val c = text[i]
        val dash = i == 8 || i == 13 || i == 18 || i == 23
        if (dash) {
            if (c != '-') return false
        } else if (!(c in '0'..'9' || c in 'a'..'f')) {
            return false
        }
    }
    return true
}

/** True for `sha256:` followed by 64 lower-case hex digits. */
fun isContentId(text: String): Boolean {
    if (text.length != 71 || !text.startsWith("sha256:")) return false
    for (i in 7 until 71) {
        val c = text[i]
        if (!(c in '0'..'9' || c in 'a'..'f')) return false
    }
    return true
}

/** The content id of file bytes: `sha256:` plus the lower-case hex SHA-256. It names bytes, never a history node. */
fun contentIdOf(bytes: ByteArray): String = "sha256:" + sha256Hex(bytes)
