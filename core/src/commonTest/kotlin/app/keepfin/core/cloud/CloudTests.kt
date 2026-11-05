package app.keepfin.core.cloud

import app.keepfin.core.DirEntry
import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.jsonQuote
import app.keepfin.core.json.parseJson
import app.keepfin.core.listDirectory
import app.keepfin.core.readTextFile
import app.keepfin.core.requireFixturesRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs fixtures/cloud on every target: each case is one CloudApi.call (provider, op, input) and `expected` is the exact JSON
 * result (object key order does not matter), or `{"throws":true}` for an input the core refuses. The cases are written from
 * the providers' documentation, not recorded from live services.
 */
class CloudFixturesTest {
    private fun root(): String = requireFixturesRoot().replace('\\', '/').trimEnd('/').substringBeforeLast('/') + "/cloud"

    private fun same(a: JsonValue?, b: JsonValue?, where: String, out: MutableList<String>) {
        when {
            a is JsonObject && b is JsonObject -> {
                if (a.members.keys != b.members.keys) out.add("$where: keys ${a.members.keys} vs ${b.members.keys}")
                for ((k, v) in a.members) same(v, b.members[k], "$where.$k", out)
            }
            a is JsonArray && b is JsonArray -> {
                if (a.items.size != b.items.size) out.add("$where: length ${a.items.size} vs ${b.items.size}")
                else a.items.forEachIndexed { i, x -> same(x, b.items[i], "$where[$i]", out) }
            }
            a is JsonString && b is JsonString -> if (a.value != b.value) out.add("$where: ${jsonQuote(a.value)} vs ${jsonQuote(b.value)}")
            a is JsonNumber && b is JsonNumber -> if (a.value != b.value) out.add("$where: ${a.value} vs ${b.value}")
            a is JsonBool && b is JsonBool -> if (a.value != b.value) out.add("$where: ${a.value} vs ${b.value}")
            a is JsonNull && b is JsonNull -> {}
            else -> out.add("$where: ${a?.let { it::class.simpleName }} vs ${b?.let { it::class.simpleName }}")
        }
    }

    // The input is passed on as written; encode it back to text.
    private fun text(v: JsonValue?): String = when (v) {
        null, is JsonNull -> "null"
        is JsonString -> jsonQuote(v.value)
        is JsonBool -> v.value.toString()
        is JsonNumber -> if (v.value == kotlin.math.floor(v.value)) v.value.toLong().toString() else v.value.toString()
        is JsonArray -> v.items.joinToString(",", "[", "]") { text(it) }
        is JsonObject -> v.members.entries.joinToString(",", "{", "}") { jsonQuote(it.key) + ":" + text(it.value) }
    }

    @Test
    fun fixtures() {
        val files = listDirectory(root()).filter { !it.isDirectory && it.name.endsWith(".json") }.sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no cloud fixtures under ${root()}")
        var ran = 0
        val failures = ArrayList<String>()
        for (f in files) {
            val doc = parseJson(readTextFile(root() + "/" + f.name)) as JsonObject
            for (c in (doc.members["cases"] as JsonArray).items) {
                c as JsonObject
                val name = f.name + ": " + (c.members["name"] as JsonString).value
                val provider = (c.members["provider"] as JsonString).value
                val op = (c.members["op"] as JsonString).value
                val expected = c.members["expected"]
                ran++
                val throwsExpected = expected is JsonObject && (expected.members["throws"] as? JsonBool)?.value == true
                try {
                    val actual = parseJson(CloudApi.call(provider, op, text(c.members["input"])))
                    if (throwsExpected) failures.add("$name: expected the call to be refused")
                    else {
                        val diffs = ArrayList<String>()
                        same(actual, expected, "result", diffs)
                        if (diffs.isNotEmpty()) failures.add("$name: " + diffs.take(3).joinToString("; "))
                    }
                } catch (e: IllegalArgumentException) {
                    if (!throwsExpected) failures.add("$name: refused: ${e.message}")
                }
            }
        }
        println("fixtures cloud: $ran ran, ${failures.size} failed")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}

class CloudHashesTest {
    private fun bytes(n: Int, seed: Int): ByteArray = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }

    /** Bit-level reference of quickXorHash: byte i is XORed at bit (11 i) mod 160 of a circular 160-bit vector. */
    private fun reference(data: ByteArray): String {
        val bits = BooleanArray(160)
        for (i in data.indices) {
            val v = data[i].toInt() and 0xFF
            val pos = (11 * i) % 160
            for (b in 0 until 8) if ((v shr b) and 1 == 1) bits[(pos + b) % 160] = !bits[(pos + b) % 160]
        }
        val out = ByteArray(20)
        for (i in 0 until 160) if (bits[i]) out[i / 8] = (out[i / 8].toInt() or (1 shl (i % 8))).toByte()
        val len = data.size.toLong()
        for (b in 0 until 8) out[12 + b] = (out[12 + b].toInt() xor ((len shr (8 * b)) and 0xFF).toInt()).toByte()
        return base64Of(out)
    }

    @Test
    fun quickXorEmptyIsTwentyZeroBytes() = assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAA=", QuickXorHash.of(ByteArray(0)))

    @Test
    fun quickXorMatchesTheBitReferenceAndChunking() {
        for (n in listOf(1, 2, 19, 20, 21, 159, 160, 161, 320, 1000, 4097, 100000)) {
            val data = bytes(n, n)
            assertEquals(reference(data), QuickXorHash.of(data), "length $n")
            val h = QuickXorHash()
            var at = 0
            var step = 7
            while (at < n) {
                val to = minOf(n, at + step)
                h.update(data, at, to)
                at = to
                step = step * 3 + 1
            }
            assertEquals(reference(data), h.digest(), "chunked length $n")
        }
    }

    @Test
    fun dropboxEmptyIsSha256OfNothing() =
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DropboxContentHash.of(ByteArray(0)))

    @Test
    fun dropboxBlocksAreHashedThenHashed() {
        val data = bytes(DropboxContentHash.BLOCK * 2 + 123, 5)
        val d1 = app.keepfin.core.sync.sha256(data.copyOfRange(0, DropboxContentHash.BLOCK))
        val d2 = app.keepfin.core.sync.sha256(data.copyOfRange(DropboxContentHash.BLOCK, 2 * DropboxContentHash.BLOCK))
        val d3 = app.keepfin.core.sync.sha256(data.copyOfRange(2 * DropboxContentHash.BLOCK, data.size))
        val expected = hexOf(app.keepfin.core.sync.sha256(d1 + d2 + d3))
        assertEquals(expected, DropboxContentHash.of(data))
        val h = DropboxContentHash()
        h.update(data, 0, 1000)
        h.update(data, 1000, data.size)
        assertEquals(expected, h.digest())
    }

    @Test
    fun reversibleNamesOnEveryProvider() {
        val samples = listOf("a", "a/b", "CON", "con.txt", "x.", "x ", " x", "50%", "%41", "%", "a%2", "~\$t", "a_vti_b", ".lock", "n.part", "\u00e9\u4e2d\ud83c\udfb5", "a:b*c?d\"e<f>g|h\\i", "t\u0001r", "Icon\r", "desktop.ini", "nul", "LPT1.x")
        for (p in listOf("drive", "graph", "dropbox", "webdav")) {
            val rules = NameTables.of(p)
            for (s in samples) {
                val enc = encodeName(rules, s)
                assertEquals(s, decodeName(enc), "$p: $s -> $enc")
                for (c in rules.forbidden) assertTrue(enc.indexOf(c) < 0, "$p: $s kept a forbidden character in $enc")
            }
        }
    }
}
