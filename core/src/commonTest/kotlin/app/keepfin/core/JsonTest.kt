package app.keepfin.core

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonException
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.jsonQuote
import app.keepfin.core.json.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonTest {
    private fun invalid(text: String) {
        assertFailsWith<JsonException>("should be invalid: $text") { parseJson(text) }
    }

    @Test
    fun readsScalars() {
        assertEquals(JsonNull, parseJson("null"))
        assertEquals(true, (parseJson(" true ") as JsonBool).value)
        assertEquals(false, (parseJson("false") as JsonBool).value)
        assertEquals(0.0, (parseJson("0") as JsonNumber).value)
        assertEquals(-12.5, (parseJson("-12.5") as JsonNumber).value)
        assertEquals(1500.0, (parseJson("1.5e3") as JsonNumber).value)
        assertEquals(0.015, (parseJson("1.5E-2") as JsonNumber).value)
        assertEquals(2.0, (parseJson("2E+0") as JsonNumber).value)
        assertEquals(Double.POSITIVE_INFINITY, (parseJson("1e999") as JsonNumber).value)
    }

    @Test
    fun readsStringsWithEscapes() {
        assertEquals("a\"b\\c/d\u0008\u000C\n\r\t", (parseJson("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\"") as JsonString).value)
        assertEquals("A\u00e9\u4e2d", (parseJson("\"\\u0041\\u00E9\\u4e2d\"") as JsonString).value)
        assertEquals("\ud83d\ude00", (parseJson("\"\\ud83d\\ude00\"") as JsonString).value)
        assertEquals("\ud83d", (parseJson("\"\\ud83d\"") as JsonString).value)
        assertEquals("\u2028 \u00e9 \ud83d\ude00", (parseJson("\"\u2028 \u00e9 \ud83d\ude00\"") as JsonString).value)
    }

    @Test
    fun objectsKeepOrderAndTheLastDuplicateWins() {
        val value = parseJson("""{"b":1,"a":2,"b":3,"2":4}""") as JsonObject
        assertEquals(listOf("b", "a", "2"), value.members.keys.toList())
        assertEquals(3.0, (value.members.getValue("b") as JsonNumber).value)
        assertEquals("{}", show(parseJson(" { } ")))
        assertEquals("[]", show(parseJson("[ ]")))
        assertEquals("""{"a":[1,{"b":null}],"c":"x"}""", show(parseJson(""" { "a" : [ 1 , { "b" : null } ] , "c" : "x" } """)))
    }

    @Test
    fun rejectsInvalidDocuments() {
        for (text in listOf(
            "", " ", "nul", "tru", "True", "NaN", "Infinity", "undefined", "'a'", "{", "}", "[", "]", "[1,]", "[,1]", "[1 2]", "{\"a\":1,}",
            "{a:1}", "{\"a\" 1}", "{\"a\":}", "{\"a\":1 \"b\":2}", "\"abc", "\"a\nb\"", "\"a\tb\"", "\"\\x\"", "\"\\u12\"", "\"\\u12G4\"", "\"\\\"",
            "01", "-", "-a", "+1", ".5", "1.", "1.e3", "1e", "1e+", "0x10", "1 2", "[] x", "{} {}", "\u00a01", "\ufeff1",
        )) invalid(text)
    }

    @Test
    fun limitsNestingDepth() {
        assertTrue(parseJson("[".repeat(400) + "]".repeat(400)) is JsonArray)
        invalid("[".repeat(3000) + "]".repeat(3000))
    }

    @Test
    fun writerEscapesLikeJsonStringify() {
        assertEquals("\"plain\"", jsonQuote("plain"))
        assertEquals("\"a\\\"b\\\\c\"", jsonQuote("a\"b\\c"))
        assertEquals("\"\\b\\f\\n\\r\\t\"", jsonQuote("\u0008\u000C\n\r\t"))
        assertEquals("\"\\u0000\\u0001\\u001f\\u000b\"", jsonQuote("\u0000\u0001\u001f\u000b"))
        assertEquals("\"/ \u007f \u2028 \u00e9 \u4e2d \ud83d\ude00\"", jsonQuote("/ \u007f \u2028 \u00e9 \u4e2d \ud83d\ude00"))
        assertEquals("\"\\ud83d\"", jsonQuote("\ud83d"))
        assertEquals("\"\\ude00\"", jsonQuote("\ude00"))
        assertEquals("\"\\ude00\\ud83d\"", jsonQuote("\ude00\ud83d"))
        assertEquals("\"\\ud83d\ud83d\ude00\"", jsonQuote("\ud83d\ud83d\ude00"))
    }

    @Test
    fun writtenStringsReadBack() {
        for (text in listOf("", "a\"b\\", "\u0000\u001f", "\ud83d\ude00", "\ud83d", "\ude00x", "\u2028\u2029")) {
            assertEquals(text, (parseJson(jsonQuote(text)) as JsonString).value)
        }
    }
}
