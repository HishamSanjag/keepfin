package app.keepfin

import org.junit.Assert.*
import org.junit.Test

class CookieExportTest {
    @Test fun parsesCookieManagerHeader() {
        val cookies = CookieExport.parse("SID=abc; HSID=def==; bad cookie=1; =x; empty=", ".youtube.com", 2_000_000_000L)
        assertEquals(listOf("SID", "HSID", "empty"), cookies.map { it.name })
        assertEquals("def==", cookies[1].value)
        assertTrue(cookies.all { it.domain == ".youtube.com" && it.expires == 2_000_000_000L })
        assertTrue(CookieExport.parse(null, ".x.com", 1).isEmpty())
    }

    @Test fun netscapeLinesAreTabSeparated() {
        val line = CookieExport.line(CookieExport.Cookie(".youtube.com", "SID", "abc", true, 123))
        assertEquals(listOf(".youtube.com", "TRUE", "/", "TRUE", "123", "SID", "abc"), line.split('\t'))
    }

    @Test fun mergeReplacesExportedDomainsAndKeepsOthers() {
        val existing = CookieExport.HEADER + ".youtube.com\tTRUE\t/\tTRUE\t1\tOLD\t1\n.vimeo.com\tTRUE\t/\tTRUE\t1\tKEEP\t2\n#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t1\tHTTP\t3\n"
        val merged = CookieExport.merge(existing, listOf(CookieExport.Cookie(".youtube.com", "NEW", "9", true, 5), CookieExport.Cookie(".youtube.com", "NEW", "10", true, 5)))
        assertTrue(merged.startsWith("# Netscape HTTP Cookie File"))
        assertFalse(merged.contains("OLD"))
        assertFalse(merged.contains("HTTP\t3"))
        assertTrue(merged.contains("KEEP"))
        assertEquals("duplicate names collapse", 1, merged.lines().count { it.contains("\tNEW\t") })
    }
}
