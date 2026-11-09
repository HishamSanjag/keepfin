package app.keepfin.core.formats

import app.keepfin.core.json.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals

class SizeProbesTest {
    private fun format(id: String, extra: String = "", protocol: String = "https") =
        "{\"format_id\":\"$id\",\"protocol\":\"$protocol\",\"vcodec\":\"avc1\",\"acodec\":\"none\",\"height\":540,\"tbr\":300.5," +
            "\"url\":\"https://cdn.example/$id.mp4\",\"http_headers\":{\"User-Agent\":\"UA\",\"Accept\":\"*/*\"}$extra}"

    private fun probes(formats: String, limit: Int = 16, top: String = "") =
        Formats.sizeProbes(parseJson("{$top\"formats\":[$formats]}"), limit)

    @Test
    fun aBitrateWithoutDurationOrSizeIsProbed() {
        val p = probes(format("a") + "," + format("b"))
        assertEquals(listOf("a", "b"), p.map { it.formatId })
        assertEquals("https://cdn.example/a.mp4", p[0].url)
        assertEquals(mapOf("User-Agent" to "UA", "Accept" to "*/*"), p[0].headers)
    }

    @Test
    fun formatsThatAlreadyHaveASizeAreNotProbed() {
        assertEquals(emptyList(), probes(format("a", ",\"filesize\":1000")))
        assertEquals(emptyList(), probes(format("a", ",\"filesize_approx\":1000")))
        assertEquals(emptyList(), probes(format("a"), top = "\"duration\":10,"))
    }

    @Test
    fun streamingAndNonMediaFormatsAreNotProbed() {
        assertEquals(emptyList(), probes(format("a", protocol = "m3u8_native")))
        assertEquals(emptyList(), probes(format("a", ",\"ext\":\"mhtml\"")))
        assertEquals(emptyList(), probes("{\"format_id\":\"x\",\"protocol\":\"https\",\"url\":\"ftp://x/y\",\"tbr\":1}"))
    }

    @Test
    fun theLimitIsRespected() {
        assertEquals(2, probes(format("a") + "," + format("b") + "," + format("c"), limit = 2).size)
    }
}
