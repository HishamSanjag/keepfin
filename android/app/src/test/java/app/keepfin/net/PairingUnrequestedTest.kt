package app.keepfin.net

import app.keepfin.core.net.DOMAIN_PAIR
import app.keepfin.core.net.PAIR_NONCE_BYTES
import app.keepfin.core.net.pairTranscript
import app.keepfin.core.net.parsePairQr
import app.keepfin.core.net.signDomain
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Devices phase 1: unrequested comparison requests, scanned codes that need nobody here, the global limit and the mute. Time is the injected clock. */
class PairingUnrequestedTest {
    private lateinit var host: SoftIdentity
    private lateinit var pairing: PairingHost
    private var clock = 1_000_000L
    private val paired = ArrayList<Pair<PairPeerInfo, String>>()
    private val random = SecureRandom()

    @Before fun setUp() {
        host = SoftIdentity("Host phone")
        pairing = PairingHost(
            PairingHostOptions(
                identity = { host }, limits = Limits(), now = { clock }, libraries = { listOf(PairLibraryRef(LIB, ACTOR_A)) },
                onPaired = { peer, method -> paired += peer to method }, changed = {},
            ),
        )
    }

    @After fun tearDown() = pairing.shutdown()

    private fun info(who: SoftIdentity) = PairPeerInfo(who.spki, who.deviceId, who.name, "windows", emptyList())
    private fun code(block: () -> Unit): String? = try { block(); null } catch (e: PairError) { e.code }
    private fun ask(address: String, who: SoftIdentity = SoftIdentity("Laptop"), token: ByteArray? = null) = pairing.start(info(who), address, token)

    // ---- where an unrequested request may come from ----------------------------------------------------------------------

    @Test fun anUnrequestedStartIsAcceptedFromThisNetworkAndFromTailscale() {
        for (address in listOf("192.168.1.20", "10.1.2.3", "172.16.0.9", "172.31.255.1", "169.254.7.7", "100.101.102.103", "100.64.0.1", "100.127.255.255", "fd7a:115c:a1e0::1", "fe80::1", "fe80::1%wlan0", "fd00::5", "::ffff:192.168.1.20")) {
            val s = ask(address)
            assertNotNull(address, s)
            val view = pairing.view()!!
            assertTrue(address, view.unrequested)
            assertFalse(address, view.autoApproved)
            assertEquals(PairStage.Comparing, view.stage)
            pairing.close() // frees the one session for the next address
            // close() ended an unrequested session: three of them would mute, so start over each time
            pairing.dismiss()
            resetMute()
        }
    }

    @Test fun anUnrequestedStartIsRefusedFromTheInternetAndFromTheSameDevice() {
        for (address in listOf("8.8.8.8", "127.0.0.1", "::1", "172.32.0.1", "100.128.0.1", "100.63.255.255", "192.169.1.1", "2001:db8::1", "::ffff:8.8.8.8", "not-an-address", "1.2.3", "")) {
            assertEquals(address, "not-pairing", code { ask(address) })
        }
        assertEquals(null, pairing.view()) // nothing was started, and none of it cost an attempt
        // the same key still has all of its attempts
        val who = SoftIdentity("Laptop")
        repeat(10) { assertEquals("not-pairing", code { ask("8.8.8.8", who) }) }
        assertNotNull(ask("192.168.1.20", who))
    }

    @Test fun anUnrequestedSessionExpiresAfterSixtySecondsAndAWindowSessionAfterTwoMinutes() {
        val (unrequested, who) = askAs("192.168.1.20")
        assertEquals(clock + 60_000L, unrequested.expiresAt)
        assertEquals(clock + 60_000L, pairing.view()!!.expiresAt)
        clock += 60_000L + 1
        assertEquals("expired", code { pairing.confirm(who.deviceId, unrequested.id, ByteArray(64)) })
        assertEquals(PairStage.Failed, pairing.view()!!.stage)
        pairing.open()
        val other = SoftIdentity("Other")
        val window = ask("192.168.1.21", other)
        assertEquals(clock + PAIR_SESSION_MS, window.expiresAt)
        assertFalse(pairing.view()!!.unrequested)
        clock += 60_000L + 1
        // still open at 61 seconds
        assertEquals(null, code { pairing.nonce(other.deviceId, window.id, ByteArray(PAIR_NONCE_BYTES)) })
        assertEquals(PairStage.Comparing, pairing.view()!!.stage)
    }

    private fun askAs(address: String): Pair<StartedPairing, SoftIdentity> {
        val who = SoftIdentity("Laptop")
        return ask(address, who) to who
    }

    // ---- scanned codes ---------------------------------------------------------------------------------------------------

    @Test fun aScannedCodeWorksWithTheWindowClosedAndNeedsNobodyHere() {
        assertFalse(pairing.active)
        val qr = parsePairQr(pairing.makeQr("192.168.1.5", 47215).first)!!
        assertFalse("showing a code does not open the window", pairing.active)
        val who = SoftIdentity("Scanner")
        val started = ask("8.8.8.8", who, qr.token) // the code is the authorization, wherever it came from
        val view = pairing.view()!!
        assertEquals("qr", view.method)
        assertTrue(view.autoApproved)
        assertFalse(view.unrequested)
        val na = ByteArray(PAIR_NONCE_BYTES).also { random.nextBytes(it) }
        val nb = pairing.nonce(who.deviceId, started.id, na)
        val sig = signDomain(who.signer, DOMAIN_PAIR, pairTranscript(na, nb, who.spki, host.spki))
        pairing.confirm(who.deviceId, started.id, sig) // returns without decide()
        assertEquals("qr", paired.single().second)
        val done = pairing.view()!!
        assertEquals(PairStage.Done, done.stage)
        assertTrue(done.autoApproved)
        assertEquals(who.deviceId, done.peerDeviceId)
        assertEquals(clock + PAIR_SESSION_MS, started.expiresAt) // a code session keeps two minutes
    }

    @Test fun aUsedOrExpiredCodeIsStillRefused() {
        val qr = parsePairQr(pairing.makeQr("h", 1).first)!!
        assertNotNull(ask("192.168.1.20", SoftIdentity("One"), qr.token))
        assertEquals("token", code { ask("192.168.1.21", SoftIdentity("Two"), qr.token) })
        val second = parsePairQr(pairing.makeQr("h", 1).first)!!
        clock += PAIR_SESSION_MS + 1
        assertEquals("token", code { ask("192.168.1.22", SoftIdentity("Three"), second.token) })
    }

    // ---- the global limit and the mute -----------------------------------------------------------------------------------

    @Test fun theEleventhUnrequestedStartInTenMinutesIsRateLimited() {
        ask("192.168.1.1", SoftIdentity("First")) // holds the one session; the others are busy, and busy tries count
        repeat(9) { i -> assertEquals("busy", code { ask("192.168.1.${i + 2}", SoftIdentity("Dev$i")) }) }
        assertEquals("rate", code { ask("192.168.1.99", SoftIdentity("Eleventh")) })
        clock += UNREQUESTED_WINDOW_MS + 1
        assertNotNull(ask("192.168.1.100", SoftIdentity("Later"))) // ten minutes on: the count started over (the first session expired)
    }

    @Test fun threeDeclinesOrExpiriesMuteUnrequestedStartsForFiveMinutes() {
        askAs("192.168.1.1") // declined here
        pairing.decide(pairing.view()!!.id, false)
        askAs("192.168.1.2") // expires: the next start finds it out of time
        clock += 60_000L + 1
        askAs("192.168.1.3") // declined by the other device
        val third = pairing.view()!!
        pairing.cancel(third.peerDeviceId, third.id)
        assertEquals("not-pairing", code { ask("192.168.1.4", SoftIdentity("Muted")) })
        // a window start and a code start are never muted
        pairing.open()
        assertNotNull(ask("192.168.1.5", SoftIdentity("Window")))
        pairing.close()
        val link = pairing.makeQr("h", 1).first
        assertNotNull(ask("192.168.1.6", SoftIdentity("Code"), parsePairQr(link)!!.token))
        pairing.close()
        // still muted a moment before five minutes are over, lifted at five
        clock += MUTE_MS - 1
        assertEquals("not-pairing", code { ask("192.168.1.7", SoftIdentity("Early")) })
        clock += 1
        assertNotNull(ask("192.168.1.8", SoftIdentity("After")))
    }

    @Test fun twoDeclinesDoNotMuteAndACompletedPairingDoesNotCount() {
        for (i in 1..2) {
            askAs("192.168.1.$i")
            pairing.decide(pairing.view()!!.id, false)
        }
        // a pairing that completes
        val (s, who) = askAs("192.168.1.3")
        val na = ByteArray(PAIR_NONCE_BYTES).also { random.nextBytes(it) }
        val nb = pairing.nonce(who.deviceId, s.id, na)
        val sig = signDomain(who.signer, DOMAIN_PAIR, pairTranscript(na, nb, who.spki, host.spki))
        val reply = java.util.concurrent.CompletableFuture.supplyAsync { pairing.confirm(who.deviceId, s.id, sig) }
        pairing.decide(s.id, true)
        reply.get(5, TimeUnit.SECONDS)
        assertEquals(PairStage.Done, pairing.view()!!.stage)
        assertNotNull(ask("192.168.1.4", SoftIdentity("Next"))) // not muted: only two failures
    }

    @Test fun declinesOlderThanTenMinutesDoNotAddUp() {
        for (i in 1..2) {
            askAs("192.168.1.$i")
            pairing.decide(pairing.view()!!.id, false)
        }
        clock += UNREQUESTED_WINDOW_MS + 1
        askAs("192.168.1.3")
        pairing.decide(pairing.view()!!.id, false)
        assertNotNull(ask("192.168.1.4", SoftIdentity("Next")))
    }

    // ---- the address classifier on its own -------------------------------------------------------------------------------

    @Test fun theClassifierKnowsLocalAndTailscaleRanges() {
        assertTrue(PairSource.isLocalOrTailscale("[fd7a:115c:a1e0:ab12:4843:cd96:6265:1234]"))
        assertFalse(PairSource.isLocalOrTailscale("fe00::1"))
        assertFalse(PairSource.isLocalOrTailscale("example.com"))
        assertFalse(PairSource.isLocalOrTailscale("0.0.0.0"))
    }

    private fun resetMute() {
        clock += UNREQUESTED_WINDOW_MS + MUTE_MS + 1
        pairing.dismiss()
    }
}
