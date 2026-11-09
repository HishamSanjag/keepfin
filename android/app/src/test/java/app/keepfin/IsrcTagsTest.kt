package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsrcTagsTest {
    private fun be32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte())
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun id3(version: Int, vararg frames: Pair<String, String>): ByteArray {
        val body = frames.fold(ByteArray(0)) { all, (id, value) ->
            val content = byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
            all + ascii(id) + (if (version == 4) syncsafe(content.size) else be32(content.size)) + byteArrayOf(0, 0) + content
        }
        return ascii("ID3") + byteArrayOf(version.toByte(), 0, 0) + syncsafe(body.size) + body + ByteArray(64)
    }

    @Test fun id3FramesCarryTheCode() {
        assertEquals("GBDUW0000053", IsrcTags.read(id3(3, "TIT2" to "Song", "TSRC" to "GBDUW0000053")))
        assertEquals("ES6601404373", IsrcTags.read(id3(4, "TSRC" to "ES-660-14-04373")))
        assertNull(IsrcTags.read(id3(3, "TIT2" to "Song")))
        assertNull(IsrcTags.read(id3(3, "TSRC" to "not a code")))
    }

    @Test fun mp4FreeformAtomCarriesTheCode() {
        fun box(type: String, vararg parts: ByteArray): ByteArray { val body = parts.fold(ByteArray(0)) { a, p -> a + p }; return be32(8 + body.size) + ascii(type) + body }
        val atom = box("----", box("mean", be32(0), ascii("com.apple.iTunes")), box("name", be32(0), ascii("ISRC")), box("data", be32(1), be32(0), ascii("GBDUW0000057")))
        val file = ByteArray(40) + box("moov", box("udta", box("meta", be32(0), box("ilst", atom)))) + ByteArray(8)
        assertEquals("GBDUW0000057", IsrcTags.read(file))
        assertNull(IsrcTags.read(ByteArray(100)))
    }

    @Test fun theCodeIsWrittenAsATagAndUsedToCompareRecordings() {
        val args = ArgsPolicy.tags(SourceTags("Song", "Artist", "Album", isrc = "GBDUW0000053"))
        assertEquals(true, args.any { it.value == "%(keepfin_literal|GBDUW0000053)s:%(meta_isrc)s" })
        assertEquals(false, ArgsPolicy.tags(SourceTags("Song", "Artist", "Album")).any { it.value?.contains("meta_isrc") == true })
        val a = Recording(artist = "A", title = "One", isrc = "GBDUW0000053")
        val b = Recording(artist = "Other", title = "Another", isrc = "gb-duw-00-00053")
        assertEquals("isrc", Recordings.compare(a, b))
    }

    @Test fun vorbisCommentCarriesTheCode() {
        val comments = listOf("TITLE=Song", "isrc=GBDUW0000058", "ARTIST=A")
        val file = ascii("OpusTags") + le32(5) + ascii("Keepf") + le32(comments.size) + comments.fold(ByteArray(0)) { a, c -> a + le32(c.length) + ascii(c) }
        assertEquals("GBDUW0000058", IsrcTags.read(file))
        assertNull(IsrcTags.read(ascii("OpusTags") + le32(0) + le32(0)))
    }
}
