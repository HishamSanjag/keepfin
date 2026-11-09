package app.keepfin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SizeFillerTest {
    private fun info() = JSONObject(
        """{"formats":[
          {"format_id":"a","protocol":"https","vcodec":"none","acodec":"mp4a","tbr":48,"url":"https://cdn.example/a.m4a","http_headers":{"User-Agent":"UA"}},
          {"format_id":"v1","protocol":"https","vcodec":"avc1","acodec":"none","height":540,"tbr":300,"url":"https://cdn.example/v1.mp4"},
          {"format_id":"v2","protocol":"m3u8_native","vcodec":"avc1","acodec":"none","height":720,"tbr":500,"url":"https://cdn.example/v2.m3u8"}
        ]}""",
    )

    private fun sizes(info: JSONObject) = (0 until 3).map { info.getJSONArray("formats").getJSONObject(it).optLong("filesize", -1) }

    @Test fun fillsFilesizeFromContentLengthAndSendsHeaders() {
        val seen = mutableListOf<Triple<String, String, Map<String, String>>>()
        val info = info()
        SizeFiller.fill(info) { url, method, headers ->
            synchronized(seen) { seen.add(Triple(url, method, headers)) }
            SizeFiller.Reply(200, if (url.endsWith("a.m4a")) 37_959L else 100_970L, null)
        }
        assertEquals(listOf(37_959L, 100_970L, -1L), sizes(info))
        assertTrue(seen.all { it.second == "HEAD" })
        assertEquals("UA", seen.first { it.first.endsWith("a.m4a") }.third["User-Agent"])
    }

    @Test fun aFailureLeavesTheSizeUnknown() {
        val info = info()
        SizeFiller.fill(info) { url, _, _ -> if (url.endsWith("a.m4a")) null else SizeFiller.Reply(404, null, null) }
        assertEquals(listOf(-1L, -1L, -1L), sizes(info))
    }

    @Test fun aRefusedHeadFallsBackToARangedGet() {
        val info = info()
        SizeFiller.fill(info) { _, method, headers ->
            if (method == "HEAD") SizeFiller.Reply(405, null, null)
            else {
                assertEquals("bytes=0-0", headers["Range"])
                SizeFiller.Reply(206, 1, "bytes 0-0/4321")
            }
        }
        assertEquals(listOf(4321L, 4321L, -1L), sizes(info))
    }

    @Test fun anUnreadableRangeIsIgnored() {
        assertNull(SizeFiller.lengthOf("https://x/y", emptyMap()) { _, m, _ ->
            if (m == "HEAD") SizeFiller.Reply(403, null, null) else SizeFiller.Reply(206, 1, "bytes 0-0/*")
        })
    }

    @Test fun formatsWithASizeAreNotRequested() {
        val info = JSONObject("""{"formats":[{"format_id":"v1","protocol":"https","vcodec":"avc1","height":540,"filesize":5,"url":"https://cdn.example/v1.mp4"}]}""")
        SizeFiller.fill(info) { _, _, _ -> fail("no request expected"); null }
    }
}
