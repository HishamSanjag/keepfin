package app.keepfin

import app.keepfin.core.dedupe.Recordings as CoreRecordings

/**
 * Reads the recording code (ISRC) a song file carries, from bytes alone so it needs no media framework. The phone's tag reader
 * has no key for it. Three places hold it: an ID3v2 TSRC (or v2.2 TRC) frame at the start of an MP3, a Vorbis comment ISRC=
 * (Opus, Ogg, FLAC) in the headers at the start, and an MP4/M4A iTunes freeform atom named ISRC, whose movie box is often at the
 * end. [read] takes the start of the file followed by its end ([WINDOW] bytes each). A value that is not a real code is ignored.
 */
internal object IsrcTags {
    const val WINDOW = 1 shl 20

    fun read(bytes: ByteArray): String? = id3(bytes) ?: mp4(bytes) ?: vorbis(bytes)

    private fun clean(value: String) = CoreRecordings.cleanIsrc(value.trim { it <= ' ' || it == '\u0000' })

    private fun id3(b: ByteArray): String? {
        if (b.size < 10 || b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return null
        val version = b[3].toInt()
        if (version !in 2..4) return null
        val end = minOf(b.size, 10 + syncsafe(b, 6))
        val idLength = if (version == 2) 3 else 4
        val headLength = if (version == 2) 6 else 10
        var at = 10
        while (at + headLength <= end) {
            if (b[at].toInt() == 0) return null
            val id = String(b, at, idLength, Charsets.ISO_8859_1)
            val size = when (version) {
                2 -> (u(b[at + 3]) shl 16) or (u(b[at + 4]) shl 8) or u(b[at + 5])
                3 -> (u(b[at + 4]) shl 24) or (u(b[at + 5]) shl 16) or (u(b[at + 6]) shl 8) or u(b[at + 7])
                else -> syncsafe(b, at + 4)
            }
            val body = at + headLength
            if (size < 0 || body + size > end) return null
            if ((id == "TSRC" || id == "TRC") && size > 1) return clean(text(b, body, size))
            at = body + size
        }
        return null
    }

    private fun text(b: ByteArray, from: Int, size: Int): String {
        val charset = when (b[from].toInt()) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        return String(b, from + 1, size - 1, charset)
    }

    private fun syncsafe(b: ByteArray, at: Int) = (u(b[at]) and 0x7f shl 21) or (u(b[at + 1]) and 0x7f shl 14) or (u(b[at + 2]) and 0x7f shl 7) or (u(b[at + 3]) and 0x7f)

    private fun u(byte: Byte) = byte.toInt() and 0xff

    /** The `name` box "ISRC" of an iTunes freeform atom, then the `data` box that follows it. */
    private fun mp4(b: ByteArray): String? {
        val marker = byteArrayOf(0, 0, 0, 0, 'I'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte())
        var from = 0
        while (true) {
            val at = indexOf(b, marker, from)
            if (at < 0) return null
            from = at + 1
            // box header "name" sits 4 bytes before the version bytes; the data box follows the name box
            if (at < 8 || String(b, at - 4, 4, Charsets.ISO_8859_1) != "name") continue
            val next = at + marker.size
            if (next + 16 > b.size || String(b, next + 4, 4, Charsets.ISO_8859_1) != "data") continue
            val size = (u(b[next]) shl 24) or (u(b[next + 1]) shl 16) or (u(b[next + 2]) shl 8) or u(b[next + 3])
            if (size < 17 || next + size > b.size) continue
            clean(String(b, next + 16, size - 16, Charsets.UTF_8))?.let { return it }
        }
    }

    /** `ISRC=` (any case) at the start of a comment, followed by the code. */
    private fun vorbis(b: ByteArray): String? {
        val limit = minOf(b.size, WINDOW)
        var i = 0
        while (i + 17 <= limit) {
            if (b[i + 4] == '='.code.toByte() && String(b, i, 4, Charsets.ISO_8859_1).equals("ISRC", ignoreCase = true)) {
                // the comment's own length (little endian) sits right before it
                val length = if (i >= 4) u(b[i - 4]) or (u(b[i - 3]) shl 8) or (u(b[i - 2]) shl 16) or (u(b[i - 1]) shl 24) else 0
                if (length in 17..1000) clean(String(b, i + 5, minOf(length - 5, limit - i - 5), Charsets.UTF_8))?.let { return it }
            }
            i++
        }
        return null
    }

    private fun indexOf(b: ByteArray, pattern: ByteArray, from: Int): Int {
        var i = from
        while (i + pattern.size <= b.size) {
            var j = 0
            while (j < pattern.size && b[i + j] == pattern[j]) j++
            if (j == pattern.size) return i
            i++
        }
        return -1
    }
}
