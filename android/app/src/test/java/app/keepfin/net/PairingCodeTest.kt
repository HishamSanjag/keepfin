package app.keepfin.net

import app.keepfin.core.net.pairCodeOf
import app.keepfin.core.net.pairPinOf
import app.keepfin.core.net.parsePairCode
import app.keepfin.core.net.parsePairQr
import app.keepfin.core.net.pinMatches
import java.security.cert.CertificateException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Devices phase 2: the typed code, answering side. One use, expiry, separate from tokens, wrong guesses, the announce flag, the start
 * request's reading of `code`, and the asking side's pin-bits check. Mirrors gui/desktop/net/pairing.code.test.ts. Time is the injected clock.
 */
class PairingCodeTest {
    private lateinit var host: SoftIdentity
    private lateinit var pairing: PairingHost
    private var clock = 1_000_000L
    private val closers = ArrayList<() -> Unit>()
    private val dirs = ArrayList<java.io.File>()

    @Before fun setUp() = build()

    private fun build(limits: Limits = Limits()) {
        host = SoftIdentity("Host phone")
        pairing = PairingHost(PairingHostOptions(identity = { host }, limits = limits, now = { clock }, libraries = { listOf(PairLibraryRef(LIB, ACTOR_A)) }, onPaired = { _, _ -> }, changed = {}))
        closers += { pairing.shutdown() }
    }

    @After fun tearDown() {
        closers.forEach { runCatching { it() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun info(who: SoftIdentity) = PairPeerInfo(who.spki, who.deviceId, who.name, "windows", emptyList())
    private fun errorOf(block: () -> Unit): String? = try { block(); null } catch (e: PairError) { e.code }
    private fun ask(address: String, who: SoftIdentity = SoftIdentity("Laptop"), token: ByteArray? = null, code: Int? = null) = pairing.start(info(who), address, token, code)
    private fun tokenOf(code: String) = parsePairCode(code)!!.token

    @Test fun aCodeIsMadeWithTheQrCarriesThisDevicesPinBitsRenewsAndExpiresWithIt() {
        assertFalse(pairing.codeShowing)
        val (link, expiresAt, code) = pairing.makeQr("192.168.1.50", 47215)
        assertTrue(Regex("^[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}$").matches(code))
        val parsed = parsePairCode(code)!!
        assertTrue(pinMatches(pairPinOf(host.spki), parsed.pinBits))
        assertEquals(code, pairCodeOf(parsed.token, pairPinOf(host.spki)))
        assertNotNull(parsePairQr(link))
        assertEquals(clock + PAIR_SESSION_MS, expiresAt)
        assertTrue(pairing.codeShowing)
        var renewed = false
        repeat(5) { if (!renewed) renewed = pairing.makeQr("192.168.1.50", 47215).third != code }
        assertTrue("each QR brings a new code", renewed)
        clock += PAIR_SESSION_MS + 1
        assertFalse("the code lives as long as the QR", pairing.codeShowing)
        pairing.makeQr("192.168.1.50", 47215)
        assertTrue(pairing.codeShowing)
        pairing.close()
        assertFalse("closing pairing withdraws it", pairing.codeShowing)
    }

    @Test fun aTypedCodeIsAScannedCodeAcceptedWithTheWindowClosedFromAnywhereApprovedByItselfOneUse() {
        val code = pairing.makeQr("192.168.1.50", 47215).third
        assertFalse(pairing.active)
        val started = ask("8.8.8.8", code = tokenOf(code))
        assertNotNull(started)
        val view = pairing.view()!!
        assertEquals("qr", view.method)
        assertEquals(PairStage.Approving, view.stage)
        assertTrue(view.autoApproved)
        assertFalse(view.unrequested)
        assertFalse("consumed", pairing.codeShowing)
        assertEquals("a second use is refused", "token", errorOf { ask("8.8.8.8", code = tokenOf(code)) })
    }

    @Test fun anExpiredCodeIsRefusedAndSoIsACodeNobodyMade() {
        assertEquals("no code is showing", "token", errorOf { ask("192.168.1.5", code = 12345) })
        val code = pairing.makeQr("192.168.1.50", 47215).third
        clock += PAIR_SESSION_MS + 1
        assertEquals("token", errorOf { ask("192.168.1.6", code = tokenOf(code)) })
    }

    @Test fun codesAndTokensAreSeparateSecretsAndUsingOneLeavesTheOther() {
        val (link, _, code) = pairing.makeQr("192.168.1.50", 47215)
        val token = parsePairQr(link)!!.token
        val value = tokenOf(code)
        // the code's number is not a token
        val padded = ByteArray(16).also { it[0] = (value shr 12).toByte(); it[1] = ((value shr 4) and 255).toByte(); it[2] = ((value and 15) shl 4).toByte() }
        assertEquals("token", errorOf { ask("192.168.1.5", token = padded) })
        // the token's leading bits are not the code (unless they happen to be the same number)
        val asNumber = ((token[0].toInt() and 255) shl 12) or ((token[1].toInt() and 255) shl 4) or ((token[2].toInt() and 255) shr 4)
        if (asNumber != value) assertEquals("token", errorOf { ask("192.168.1.5", code = asNumber) })
        // using the token leaves the code, and the other way round
        assertNotNull(ask("192.168.1.6", token = token))
        pairing.decide(pairing.view()!!.id, false)
        assertTrue(pairing.codeShowing)
        assertNotNull(ask("192.168.1.7", code = value))
    }

    @Test fun aWrongCodeCostsAnAttemptLikeAWrongTokenAndFiveWrongGuessesWithdrawTheCode() {
        build(Limits(pairAttempts = 2))
        val a = pairing.makeQr("192.168.1.50", 47215).third
        val wrong = (tokenOf(a) + 1) and 0xfffff
        val key = SoftIdentity("Same key")
        assertEquals("token", errorOf { ask("192.168.1.5", key, code = wrong) })
        assertEquals("token", errorOf { ask("192.168.1.5", key, code = wrong) })
        assertEquals("the third try from the same key is refused, even with the right code", "rate", errorOf { ask("192.168.1.5", key, code = tokenOf(a)) })
        // five wrong guesses from different keys and addresses retire the code (the QR stays)
        build()
        val (link, _, b) = pairing.makeQr("192.168.1.50", 47215)
        val token = parsePairQr(link)!!.token
        for (i in 1..5) assertEquals("token", errorOf { ask("192.168.1.$i", code = (tokenOf(b) + i) and 0xfffff) })
        assertFalse(pairing.codeShowing)
        assertEquals("token", errorOf { ask("192.168.1.9", code = tokenOf(b)) })
        assertNotNull("the QR still works", ask("192.168.1.10", token = token))
    }

    // ---- the server: the announce flag and the reading of `code` -------------------------------------------------------------

    private class Rig(val server: NetServer, val acceptor: PlainAcceptor)

    private fun rig(): Rig {
        val dir = tempDir().also { dirs += it }
        val lib = FakeNetLibrary()
        val acceptor = PlainAcceptor()
        val executor = PeerExecutorImpl(lib.folder)
        val server = NetServer(NetServerDeps(identity = { host }, peers = PeerStore.open(dir), libraries = { listOf(lib) }, executor = { executor }, pairing = pairing, cacheDir = tempDir().also { dirs += it }, limits = Limits()))
        server.start(acceptor)
        closers += { server.close() }
        return Rig(server, acceptor)
    }

    @Test fun theAnnouncementSaysACodeIsShowingWhileItIsAndNeverWhatItIs() {
        val r = rig()
        val stranger = SoftIdentity("Stranger")
        fun announce() = JSONObject(rawRequest(r.acceptor, stranger.spki, get("/v1/announce")).text)
        assertEquals("no code, no flag", setOf("v", "id", "n", "p"), announce().keys().asSequence().toSet())
        val code = pairing.makeQr("192.168.1.50", 47215).third
        val shown = announce()
        assertEquals(setOf("v", "id", "n", "p", "c"), shown.keys().asSequence().toSet())
        assertEquals(1, shown.getInt("c"))
        assertFalse("not even a part of the code", shown.toString().contains(code.replace("-", "").take(4)))
        val seen = parseAnnounce(shown)!!
        assertTrue(seen.code)
        clock += PAIR_SESSION_MS + 1
        assertEquals("expired: the flag is gone", setOf("v", "id", "n", "p"), announce().keys().asSequence().toSet())
        assertFalse(parseAnnounce(announce())!!.code)
        pairing.makeQr("192.168.1.50", 47215)
        pairing.close()
        assertEquals("closed: the flag is gone", setOf("v", "id", "n", "p"), announce().keys().asSequence().toSet())
    }

    @Test fun theStartRequestReadsACodeOnlyAsAWholeNumberBelowTwoToTheTwentiethAndNeverWithAToken() {
        val r = rig()
        pairing.makeQr("192.168.1.50", 47215)
        fun start(extra: String) = rawRequest(r.acceptor, SoftIdentity("S").spki, post("/v1/pair/start", """{"versions":[1],"name":"S","platform":"windows"$extra}"""))
        for (bad in listOf("-1", "1048576", "1.5", "\"12\"", "null", "[1]", "{}", "true")) {
            val reply = start(""","code":$bad""")
            assertEquals(bad, "token", JSONObject(reply.text).optString("error"))
        }
        val both = start(""","code":5,"token":"AAAAAAAAAAAAAAAAAAAAAA==" """)
        assertEquals("token", JSONObject(both.text).optString("error"))
    }

    // ---- the asking side: a key that does not start with the pin bits is refused in the handshake -----------------------------

    @Test fun theClientRefusesAKeyThatDoesNotStartWithThePinBitsAndAcceptsOneThatDoes() {
        val device = SoftIdentity("Answering")
        val bits = parsePairCode(pairCodeOf(1, pairPinOf(device.spki)))!!.pinBits
        PinTrust(null, bits).checkSpki(device.spki)
        val trust = PinTrust(null, (bits + 1) and 0x3fffffff)
        try {
            trust.checkSpki(device.spki)
            fail("a key with other pin bits was accepted")
        } catch (e: CertificateException) {
            assertTrue(trust.mismatch)
            assertNull(trust.seenSpki)
        }
        // a plain pairing by address is unchanged
        PinTrust(null).checkSpki(device.spki)
        assertNotEquals(bits, (bits + 1) and 0x3fffffff)
    }
}