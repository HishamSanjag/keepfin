package app.keepfin.core.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetCryptoTest {
    @Test
    fun base64AndHex() {
        val cases = listOf("" to "", "f" to "Zg==", "fo" to "Zm8=", "foo" to "Zm9v", "foob" to "Zm9vYg==", "fooba" to "Zm9vYmE=", "foobar" to "Zm9vYmFy")
        for ((plain, enc) in cases) {
            assertEquals(enc, base64Encode(plain.encodeToByteArray()))
            assertContentEquals(plain.encodeToByteArray(), base64Decode(enc))
        }
        for (bad in listOf("Zh==", "Zm9=", "Zg=", "Zg", "Z===", "Zm9v!", "Zm 9v", "=Zg=", "Zg==Zg==")) assertNull(base64Decode(bad), bad)
        val all = ByteArray(256) { it.toByte() }
        assertContentEquals(all, base64Decode(base64Encode(all)))
        assertContentEquals(all, base64UrlDecode(base64UrlEncode(all)))
        assertFalse(base64UrlEncode(all).any { it == '+' || it == '/' || it == '=' })
        assertNull(base64UrlDecode("Zg=="))
        assertNull(base64UrlDecode("A"))
        assertEquals("00ff10", hexOf(byteArrayOf(0, -1, 16)))
        assertContentEquals(byteArrayOf(0, -1, 16), bytesOfHex("00FF10"))
        assertNull(bytesOfHex("0"))
        assertNull(bytesOfHex("0g"))
        assertTrue(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1)))
    }

    @Test
    fun softwareKeysSignAndVerify() {
        val a = SoftwareSigner.generate()
        val b = SoftwareSigner.generate()
        assertTrue(isP256Spki(a.spki))
        assertEquals(64, deviceIdOf(a.spki).length)
        assertFalse(deviceIdOf(a.spki) == deviceIdOf(b.spki))
        val data = "some bytes".encodeToByteArray()
        val sig = signDomain(a, DOMAIN_SEGMENT, data)
        assertTrue(verifyDomain(a.spki, DOMAIN_SEGMENT, data, sig))
        assertFalse(verifyDomain(b.spki, DOMAIN_SEGMENT, data, sig))
        assertFalse(verifyDomain(a.spki, DOMAIN_PAIR, data, sig))
        assertFalse(verifyDomain(a.spki, DOMAIN_SEGMENT, "other".encodeToByteArray(), sig))
        assertFalse(verifyDomain(byteArrayOf(1, 2, 3), DOMAIN_SEGMENT, data, sig))
    }

    // ---- the pairing is safe against nonce grinding -------------------------------------------------------------------

    /** A small deterministic generator, so every target draws the same numbers. */
    private class Gen(var s: Long) {
        fun next(): Long {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            return (s ushr 33) and 0x7fffffffL
        }

        fun nonce(): ByteArray = ByteArray(PAIR_NONCE_BYTES) { (next() and 0xff).toByte() }
    }

    private val spkiA = ByteArray(91) { (it * 7 + 1).toByte() }
    private val spkiB = ByteArray(91) { (it * 5 + 3).toByte() }

    private fun mod(sas: String, m: Int) = sas.toInt() % m

    @Test
    fun sasIsUniformOverRandomNonces() {
        val g = Gen(11)
        val buckets = IntArray(10)
        val n = 3000
        repeat(n) { buckets[mod(pairSas(g.nonce(), g.nonce(), spkiA, spkiB), 10)]++ }
        var chi = 0.0
        for (c in buckets) chi += (c - n / 10.0) * (c - n / 10.0) / (n / 10.0)
        assertTrue(chi < 33.0, "chi-square $chi over ${buckets.toList()}") // df 9, p = 0.0001 is 33.7
    }

    @Test
    fun aResponderThatCommittedCannotSteerTheNumbers() {
        // The attacker is the responder. Before it sees Na it grinds Nb against a GUESSED Na and commits to the best one;
        // afterwards the real (random) Na arrives and it can only reveal what it committed to.
        val g = Gen(23)
        val trials = 3000
        val candidates = 48
        val target = 424242
        var hits = 0
        val buckets = IntArray(10)
        repeat(trials) {
            val guess = g.nonce()
            var best = g.nonce()
            for (c in 0 until candidates) {
                val nb = g.nonce()
                if (pairSas(guess, nb, spkiA, spkiB).toInt() % 100 == target % 100) {
                    best = nb
                    break
                }
            }
            val commitment = pairCommitment(best, spkiB, spkiA)
            val na = g.nonce() // revealed after the commitment
            // a different Nb at reveal time is refused, so only `best` can be used
            val tricky = g.nonce()
            assertFalse(pairVerifyReveal(commitment, tricky, spkiB, spkiA))
            assertTrue(pairVerifyReveal(commitment, best, spkiB, spkiA))
            val sas = pairSas(na, best, spkiA, spkiB)
            if (sas.toInt() % 100 == target % 100) hits++
            buckets[mod(sas, 10)]++
        }
        // chance is 1 in 100: about 30 hits of 3000. A steered attacker would be near the 1 - 0.99^48 = 38% mark.
        assertTrue(hits < 30 + 5 * 6, "hits $hits of $trials")
        var chi = 0.0
        for (c in buckets) chi += (c - trials / 10.0) * (c - trials / 10.0) / (trials / 10.0)
        assertTrue(chi < 33.0, "chi-square $chi over ${buckets.toList()}")
    }

    @Test
    fun withoutACommitmentTheSameAttackerWouldSteerIt() {
        // The control experiment: seeing Na before choosing Nb steers the numbers (this is what the commitment prevents).
        val g = Gen(23)
        val trials = 400
        val candidates = 48
        val target = 42
        var hits = 0
        repeat(trials) {
            val na = g.nonce()
            for (c in 0 until candidates) {
                val nb = g.nonce()
                if (pairSas(na, nb, spkiA, spkiB).toInt() % 100 == target) {
                    hits++
                    break
                }
            }
        }
        assertTrue(hits > trials * 25 / 100, "hits $hits of $trials")
    }

    @Test
    fun aMachineInTheMiddleSeesDifferentNumbers() {
        // M talks to A with its own key and to B with its own key: the keys differ on each side, so the SAS differs
        val g = Gen(5)
        val spkiM = ByteArray(91) { (it * 11 + 9).toByte() }
        var same = 0
        repeat(200) {
            val na = g.nonce()
            val nb = g.nonce()
            val withA = pairSas(na, nb, spkiA, spkiM) // A thinks it talks to B
            val withB = pairSas(na, nb, spkiM, spkiB) // B thinks it talks to A
            if (withA == withB) same++
        }
        assertTrue(same <= 1, "$same of 200 equal")
    }
}
