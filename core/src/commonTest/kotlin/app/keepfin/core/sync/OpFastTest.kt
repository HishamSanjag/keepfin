package app.keepfin.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fast routes for ops and views against the reference routes: same text out, same lines accepted and rejected,
 * same judgement, same JSON.
 */
class OpFastTest {
    private fun label(a: Admission): String = when (a) {
        is Admission.Invalid -> "invalid:" + a.reason
        is Admission.Malformed -> "malformed:" + a.problem.name
        else -> a::class.simpleName ?: "?"
    }

    private fun weirdOps(): List<Op> {
        val weird = "q \" \\ / \u0001 \u001f \u007f \u2028 é \u07FF \u0800 \uFFFF \uD83D\uDE00 lone \uD800 and \uDC00 x"
        return listOf(
            add(A, 1, fields = mapOf("media" to JStr("audio"), "title" to JStr(weird))),
            setField(A, 2, 2, "artist", JStr(weird)),
            setField(B, 1, 3, weird, jObj("z" to JNum(-5), weird to JArr(listOf(JNum(0), JNum(MAX_SAFE_INTEGER), JBool(true), JNull)), "a" to JObj(emptyMap()))),
            op(B, 2, 4, "profile.set", "field" to JStr("name"), "value" to JStr(weird)),
            Op(OpId(A, 3), Hlc(5, 0, B), "item.set", mapOf("item" to JStr(ITEM), "field" to JStr("x"), "value" to JNull)), // clock actor differs
            op(A, 4, 6, "future.thing", "x" to JNum(1)),
        )
    }

    private fun opTexts(): List<String> {
        val out = ArrayList<String>()
        for (o in weirdOps()) out.add(o.encode())
        for (seed in 1..12) for (o in OpGen(seed, 3).generate(40)) out.add(o.encode())
        return out
    }

    @Test
    fun theDirectEncoderGivesTheReferenceText() {
        val ops = weirdOps() + OpGen(5, 4).generate(300)
        for (o in ops) assertEquals(encodeCanonical(o.toJson()), o.encode())
    }

    @Test
    fun theReaderRecoversEveryEncodedOp() {
        for (o in weirdOps() + OpGen(9, 4).generate(300)) {
            val text = o.encode()
            val size = utf8LengthOrMinusOne(text)
            assertEquals(text.encodeToByteArray().size, size)
            val back = OpTextReader(text).read() ?: continue // an op the reference route would also refuse (clock actor)
            assertEquals(o, back, text)
        }
        // at least the ordinary ones are read by the fast route
        val plain = add(A, 1).encode()
        assertNotNull(OpTextReader(plain).read())
    }

    private fun damage(rnd: Random, text: String): String {
        val alphabet = "{}[]\",: \\u0123456789e-.\n\t\uD800\uFEFF/ab"
        val chars = text.toMutableList()
        repeat(1 + rnd.nextInt(2)) {
            if (chars.size < 3) return@repeat
            val at = rnd.nextInt(chars.size)
            when (rnd.nextInt(5)) {
                0 -> chars.removeAt(at)
                1 -> chars[at] = alphabet[rnd.nextInt(alphabet.length)]
                2 -> chars.add(at, alphabet[rnd.nextInt(alphabet.length)])
                3 -> chars.add(at, chars[at])
                else -> if (at + 1 < chars.size) { val t = chars[at]; chars[at] = chars[at + 1]; chars[at + 1] = t }
            }
        }
        return chars.joinToString("")
    }

    private val handMade: List<(String) -> String> = listOf(
        { it.replace("\"body\":{", "\"body\": {") },
        { it.replace(",\"hlc\"", ", \"hlc\"") },
        { " $it" },
        { "$it " },
        { it + "\n" },
        { it.replace("\"hlc\"", "\"kind\":\"x\",\"hlc\"") },
        { it.replace("{\"body\":", "{\"zzz\":1,\"body\":") },
        { it.replace(Regex("\"duration\":[0-9]+"), "\"duration\":1.5") },
        { it.replace(Regex("\"duration\":[0-9]+"), "\"duration\":01") },
        { it.replace(Regex("\"duration\":[0-9]+"), "\"duration\":1e2") },
        { it.replace("\"media\"", "\"med\\u0069a\"") },
        { it.replace("\"media\"", "\"media\":1,\"media\"") },
        { it.replace("é", "\\u00e9") },
        { it.replace("\uD83D\uDE00", "\\ud83d\\ude00") },
        { it.replace("\uD83D\uDE00", "\uD83D") },
        { it.replace("\\ud800", "\\uD800") },
        { it.replace("\"kind\":\"", "\"kind\":\"\u0001") },
        { it.replace("\"id\":\"", "\"id\":\"A") },
        { it.replace(":1\"", ":01\"") },
        { it.replace(":1\"", ":0\"") },
        { "\uFEFF$it" },
        { it.replace("{\"body\":{", "{\"body\":[") },
        { it.dropLast(1) },
    )

    @Test
    fun damagedLinesAreJudgedExactlyAsBefore() {
        val rnd = Random(77)
        val texts = opTexts()
        val fast = OpLog()
        val slow = OpLog()
        var accepted = 0
        var rejected = 0
        fun both(text: String) {
            val a = fast.admit(text)
            val b = slow.admitReferenceForTest(text.encodeToByteArray())
            assertEquals(label(b), label(a), "line: $text")
            if (b is Admission.Admitted || b is Admission.Kept || b is Admission.Invalid) accepted++ else rejected++
        }
        for (t in texts) both(t)
        for (t in texts) both(t) // duplicates
        for (t in texts) for (f in handMade) both(f(t))
        for (round in 0 until 3000) both(damage(rnd, texts[rnd.nextInt(texts.size)]))
        assertEquals(slow.state, fast.state)
        assertEquals(slow.versionVector, fast.versionVector)
        assertEquals(slow.pending, fast.pending)
        assertEquals(slow.findings, fast.findings)
        assertEquals(slow.setAside, fast.setAside)
        assertTrue(accepted > 200 && rejected > 1000, "accepted $accepted rejected $rejected")
    }

    @Test
    fun theByteEntryPointAgrees() {
        val rnd = Random(5)
        val texts = opTexts()
        val fast = OpLog()
        val slow = OpLog()
        for (round in 0 until 1500) {
            val text = if (round % 2 == 0) texts[round % texts.size] else damage(rnd, texts[rnd.nextInt(texts.size)])
            val bytes = text.encodeToByteArray()
            assertEquals(label(slow.admitReferenceForTest(bytes)), label(fast.admit(bytes)), text)
        }
        // malformed bytes
        for (bad in listOf(byteArrayOf(0xC0.toByte(), 0x80.toByte()), byteArrayOf(0xFF.toByte()))) {
            val line = "{\"body\":{\"a\":\"".encodeToByteArray() + bad + "\"},\"hlc\":\"x\"}".encodeToByteArray()
            assertEquals(label(slow.admitReferenceForTest(line)), label(fast.admit(line)))
        }
        assertEquals(slow.state, fast.state)
    }

    // ---- the body parser used by makeOp ----

    @Test
    fun theBodyReaderNeverDisagreesWithTheGeneralParser() {
        val cases = listOf(
            "{}", "{\"a\":1}", "{\"b\":1,\"a\":2}", "{\"a\":1,\"a\":2}", "{\"a\": 1}", "{\"a\":1.5}", "{\"a\":1e3}", "{\"a\":-0}", "{\"a\":0}",
            "{\"a\":-7,\"z\":[1,[2,{\"k\":null}],true,false,\"s\"]}", "{\"a\":\"\\u0001\\n\\\"\\\\\"}", "{\"a\":\"\\ud800\"}", "{\"a\":\"\\ud83d\\ude00\"}",
            "{\"a\":\"é😀\"}", "[]", "1", "\"x\"", "{\"a\":9007199254740991}", "{\"a\":9007199254740992}", "{\"a\":123456789012345678}", "{\"a\":01}",
            "{\"a\":[1,2,]}", "{\"a\":}", "{", "", "{\"a\":1}x", "{\"a\":\"\\/\"}", "{\"a\":\"\\u0041\"}", "{\"a\":\"\\u000a\"}",
        )
        for (c in cases) {
            val fast = OpTextReader(c, false).readObjectOnly()
            val loose = (parseLoose(c) as? JObj)?.members
            if (fast != null) assertEquals(loose, fast, c) // when the fast reader accepts, the general parser agrees
        }
        // the usual shapes are taken by the fast reader
        assertNotNull(OpTextReader("{\"b\":1,\"a\":{\"x\":[1,2,\"s\"]}}", false).readObjectOnly())
        assertNull(OpTextReader("{\"a\":1,\"a\":2}", false).readObjectOnly())
    }

    // ---- views ----

    @Test
    fun viewsJsonIsTheReferenceText() {
        for (seed in 1..60) {
            val state = logOf(OpGen(seed, 4).generate(80 + seed * 3)).state
            val finds = setOf("b", "a", "revision-cycle:x")
            assertEquals(encodeCanonical(state.viewsJv(finds)), state.viewsJsonText(finds), "seed $seed")
        }
        val log = logOf(weirdOps())
        assertEquals(encodeCanonical(log.state.viewsJv(emptySet())), log.state.viewsJsonText(emptySet()))
        assertEquals(encodeCanonical(State.EMPTY.viewsJv(emptySet())), State.EMPTY.viewsJsonText(emptySet()))
    }
}
