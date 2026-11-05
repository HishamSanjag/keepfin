package app.keepfin.core.net

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.parseJson
import app.keepfin.core.readTextFile
import app.keepfin.core.requireFixturesRoot
import app.keepfin.core.sync.SegmentError
import app.keepfin.core.sync.SegmentResult
import app.keepfin.core.sync.decodeSegment
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs fixtures/net (written with node:crypto and by hand, not recorded from the core) on every target. */
class NetFixturesTest {
    private fun load(name: String): JsonObject =
        parseJson(readTextFile(requireFixturesRoot().replace('\\', '/').trimEnd('/').substringBeforeLast('/') + "/net/$name.json")) as JsonObject

    private fun JsonValue?.obj() = (this as JsonObject).members
    private fun JsonValue?.arr() = (this as JsonArray).items
    private fun JsonValue?.str() = (this as JsonString).value
    private fun JsonValue?.bool() = (this as JsonBool).value
    private fun JsonValue?.hex() = bytesOfHex(str()) ?: fail("bad hex in fixture")

    @Test
    fun hmacMatchesRfc4231() {
        var n = 0
        for (c in load("pairing").members["hmac"].arr()) {
            val m = c.obj()
            assertEquals(m["mac"].str(), hexOf(hmacSha256(m["key"].hex(), m["data"].hex())), "hmac case $n")
            n++
        }
        assertEquals(3, n)
    }

    @Test
    fun signaturesVerifyAndTamperingFails() {
        val f = load("keys").members
        val a = f["keyA"].obj()["spki"].hex()
        val b = f["keyB"].obj()["spki"].hex()
        assertTrue(isP256Spki(a) && isP256Spki(b))
        assertEquals(f["keyA"].obj()["deviceId"].str(), deviceIdOf(a))
        for (c in f["verify"].arr()) {
            val m = c.obj()
            val domain = m["domain"].str()
            val data = m["data"].hex()
            val sig = m["signature"].hex()
            assertTrue(verifyDomain(a, domain, data, sig), "${m["name"].str()}: valid")
            assertFalse(verifyDomain(b, domain, data, sig), "${m["name"].str()}: other key")
            assertFalse(verifyDomain(a, if (domain == DOMAIN_SEGMENT) DOMAIN_PAIR else DOMAIN_SEGMENT, data, sig), "${m["name"].str()}: other domain")
            assertFalse(verifyDomain(a, domain, data + byteArrayOf(0), sig), "${m["name"].str()}: longer data")
            val bad = sig.copyOf()
            bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 1).toByte()
            assertFalse(verifyDomain(a, domain, data, bad), "${m["name"].str()}: flipped signature")
            assertFalse(verifyDomain(a, domain, data, sig.copyOf(sig.size - 1)), "${m["name"].str()}: truncated signature")
            assertFalse(verifyDomain(a, domain, data, ByteArray(0)), "${m["name"].str()}: empty signature")
        }
        for (s in f["badSpki"].arr()) assertFalse(isP256Spki(s.hex()))
    }

    @Test
    fun pairingVectors() {
        val cases = load("pairing").members["cases"].arr()
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val m = c.obj()
            val name = m["name"].str()
            val na = m["na"].hex()
            val nb = m["nb"].hex()
            val spkiA = m["spkiA"].hex()
            val spkiB = m["spkiB"].hex()
            val cb = pairCommitment(nb, spkiB, spkiA)
            assertEquals(m["commitment"].str(), hexOf(cb), "$name: commitment")
            assertTrue(pairVerifyReveal(cb, nb, spkiB, spkiA), "$name: reveal")
            val other = nb.copyOf()
            other[0] = (other[0].toInt() xor 1).toByte()
            assertFalse(pairVerifyReveal(cb, other, spkiB, spkiA), "$name: a different Nb")
            assertEquals(m["sas"].str(), pairSas(na, nb, spkiA, spkiB), "$name: sas")
        }
    }

    @Test
    fun qrTokens() {
        val qr = load("pairing").members["qr"].obj()
        for (c in qr["valid"].arr()) {
            val m = c.obj()
            val p = parsePairQr(m["text"].str()) ?: fail("rejected ${m["text"].str()}")
            assertEquals(m["host"].str(), p.host)
            assertEquals((m["port"] as JsonNumber).value.toInt(), p.port)
            assertEquals(m["pin"].str(), hexOf(p.pin))
            assertEquals(m["token"].str(), hexOf(p.token))
            if (m["parseOnly"] == null) assertEquals(m["text"].str(), p.build())
        }
        for (t in qr["invalid"].arr()) assertNull(parsePairQr(t.str()), "accepted: ${t.str()}")
    }

    @Test
    fun typedCodes() {
        val code = load("pairing").members["code"].obj()
        for (c in code["valid"].arr()) {
            val m = c.obj()
            val pin = m["pin"].hex()
            val token = (m["token"] as JsonNumber).value.toInt()
            val bits = (m["pinBits"] as JsonNumber).value.toInt()
            assertEquals(m["text"].str(), pairCodeOf(token, pin))
            val p = parsePairCode(m["text"].str()) ?: fail("rejected ${m["text"].str()}")
            assertEquals(token, p.token)
            assertEquals(bits, p.pinBits)
            assertTrue(pinMatches(pin, p.pinBits))
            assertFalse(pinMatches(pin, p.pinBits xor 1))
            assertFalse(pinMatches(pin, p.pinBits xor (1 shl 29)))
            assertFalse(pinMatches(pin.copyOf(31), p.pinBits))
        }
        for (c in code["forgiving"].arr()) {
            val m = c.obj()
            val p = parsePairCode(m["text"].str()) ?: fail("rejected ${m["text"].str()}")
            assertEquals((m["token"] as JsonNumber).value.toInt(), p.token, m["text"].str())
            assertEquals((m["pinBits"] as JsonNumber).value.toInt(), p.pinBits, m["text"].str())
        }
        for (t in code["invalid"].arr()) assertNull(parsePairCode(t.str()), "accepted: ${t.str()}")
        // a different key gives a different code; only the pin's first 30 bits count
        val a = code["valid"].arr()[0].obj()["pin"].hex()
        val b = a.copyOf(); b[3] = (b[3].toInt() xor 0x01).toByte() // the 32nd bit of the pin is outside the 30 the code carries
        assertEquals(pairCodeOf(5, a), pairCodeOf(5, b))
        val c = a.copyOf(); c[0] = (c[0].toInt() xor 0x80).toByte()
        assertFalse(pairCodeOf(5, a) == pairCodeOf(5, c))
    }
    private fun ctxOf(v: JsonValue?): AuthzContext {
        val m = v.obj()
        val peerV = m["peer"]
        val peer = if (peerV is JsonObject) {
            val p = peerV.members
            val libs = HashMap<String, Set<NetAction>>()
            for ((id, acts) in p["libraries"].obj()) libs[id] = acts.arr().map { a -> NetAction.values().first { it.wire == a.str() } }.toSet()
            PeerGrants(p["deviceId"].str(), p["revoked"].bool(), libs)
        } else null
        return AuthzContext(m["pairingMode"].bool(), peer)
    }

    @Test
    fun authorizationMatrix() {
        val f = load("authz").members
        var n = 0
        for (c in f["cases"].arr()) {
            val m = c.obj()
            val action = NetAction.values().firstOrNull { it.wire == m["action"].str() }
            val lib = (m["library"] as? JsonString)?.value
            val exp = m["expected"].obj()
            val d = if (action == null) Decision.deny("action") else authorize(ctxOf(m["ctx"]), action, lib)
            assertEquals(exp["allow"].bool(), d.allow, m["name"].str())
            assertEquals(exp["reason"].str(), d.reason, m["name"].str())
            n++
        }
        assertTrue(n > 50)
        for (c in f["events"].arr()) {
            val m = c.obj()
            val d = authorizeEvent(ctxOf(m["ctx"]), m["kind"].str(), m["library"].str())
            assertEquals(m["expected"].obj()["allow"].bool(), d.allow, "event ${m["kind"].str()}")
            assertEquals(m["expected"].obj()["reason"].str(), d.reason, "event ${m["kind"].str()}")
        }
    }

    @Test
    fun denyByDefaultOverTheWholeSpace() {
        // every combination of (granted actions) x (asked action): allowed exactly when granted
        val acts = NetAction.values().filter { it != NetAction.Hello && it != NetAction.Pair }
        for (mask in 0 until (1 shl acts.size)) {
            val granted = acts.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
            val ctx = AuthzContext(false, PeerGrants("d", false, mapOf("lib" to granted)))
            for (a in acts) assertEquals(a in granted, authorize(ctx, a, "lib").allow, "mask $mask $a")
        }
    }

    @Test
    fun urlPolicy() {
        val f = load("urlpolicy").members
        for (c in f["static"].arr()) {
            val m = c.obj()
            val d = checkDownloadUrl(m["url"].str())
            assertEquals(m["allow"].bool(), d.allowed, "${m["url"].str()} -> ${d.reason}")
            if (!d.allowed) assertEquals(m["reason"].str(), d.reason, m["url"].str())
        }
        for (c in f["addresses"].arr()) {
            val pair = c.arr()
            val expected = (pair[1] as? JsonString)?.value
            assertEquals(expected, deniedAddressClass(pair[0].str()), "address ${pair[0].str()}")
        }
        for (c in f["resolved"].arr()) {
            val m = c.obj()
            val d = checkResolved(m["addresses"].arr().map { it.str() })
            assertEquals(m["allow"].bool(), d.allowed, m["addresses"].arr().joinToString { it.str() })
            assertEquals(m["reason"].str(), d.reason)
        }
        for (c in f["redirects"].arr()) {
            val m = c.obj()
            val d = checkRedirect(m["current"].str(), m["location"].str(), m["resolved"].arr().map { it.str() }, (m["hops"] as JsonNumber).value.toInt())
            assertEquals(m["allow"].bool(), d.allowed, "${m["location"].str()} -> ${d.reason}")
            if (d.allowed) assertEquals(m["href"].str(), d.href) else assertEquals(m["reason"].str(), d.reason, m["location"].str())
        }
    }

    @Test
    fun signedSegmentFromAnIndependentWriter() {
        val f = load("segment").members
        val file = f["file"].hex()
        val r = decodeSegment(file, f["library"].str()) as? SegmentResult.Ok ?: fail("signed segment was refused")
        assertEquals(f["actor"].str(), r.actor)
        assertEquals(f["signer"].str(), r.signer)
        assertEquals(f["key"].str(), base64Encode(r.key!!))
        assertEquals(f["ops"].arr().map { it.str() }, r.ops.map { it.encode() })
        // one flipped byte anywhere in the file is refused (checksum, structure or signature)
        for (i in listOf(0, 9, 40, file.size / 2, file.size - 200, file.size - 60, file.size - 2)) {
            val bad = file.copyOf()
            bad[i] = (bad[i].toInt() xor 1).toByte()
            assertTrue(decodeSegment(bad, f["library"].str()) is SegmentResult.Err, "flip at $i")
        }
        // the trailer of another segment does not fit
        val text = file.decodeToString()
        val cut = text.lastIndexOf("KFSIG ")
        val tampered = (text.substring(0, cut).replace("\"count\":3", "\"count\":3") .replace("three", "thre3") + text.substring(cut)).encodeToByteArray()
        assertTrue(decodeSegment(tampered, f["library"].str()) is SegmentResult.Err)
        val e = (decodeSegment(file.copyOf(file.size - 1), f["library"].str()) as SegmentResult.Err).error
        assertTrue(e is SegmentError.BadTrailer || e is SegmentError.TrailingData, "truncated file: $e")
        assertNotNull(f["keyA"])
    }
}
