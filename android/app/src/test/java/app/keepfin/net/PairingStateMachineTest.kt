package app.keepfin.net

import app.keepfin.core.net.DOMAIN_PAIR
import app.keepfin.core.net.PAIR_NONCE_BYTES
import app.keepfin.core.net.pairSas
import app.keepfin.core.net.pairTranscript
import app.keepfin.core.net.pairVerifyReveal
import app.keepfin.core.net.parsePairQr
import app.keepfin.core.net.signDomain
import app.keepfin.core.net.verifyDomain
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The pairing state machine from both ends (network spec 3 and 9.1 #3), without TLS: the asking side's steps are written out as the initiator takes them. */
class PairingStateMachineTest {
    private lateinit var host: SoftIdentity
    private lateinit var asker: SoftIdentity
    private lateinit var pairing: PairingHost
    private var clock = 1_000_000L
    private val paired = ArrayList<Pair<PairPeerInfo, String>>()
    private var changes = 0
    private val random = SecureRandom()

    @Before fun setUp() {
        host = SoftIdentity("Host phone")
        asker = SoftIdentity("Asking laptop")
        pairing = PairingHost(
            PairingHostOptions(
                identity = { host }, limits = Limits(), now = { clock }, libraries = { listOf(PairLibraryRef(LIB, ACTOR_A)) },
                onPaired = { peer, method -> paired += peer to method }, changed = { changes++ },
            ),
        )
    }

    @After fun tearDown() = pairing.shutdown()

    private fun info(who: SoftIdentity = asker) = PairPeerInfo(who.spki, who.deviceId, who.name, "windows", emptyList())

    private class Run(val started: StartedPairing, val na: ByteArray, val nb: ByteArray)

    private fun startAndReveal(who: SoftIdentity = asker, token: ByteArray? = null, address: String = "10.0.0.2"): Run {
        val started = pairing.start(info(who), address, token)
        val na = ByteArray(PAIR_NONCE_BYTES).also { random.nextBytes(it) }
        val nb = pairing.nonce(who.deviceId, started.id, na)
        return Run(started, na, nb)
    }

    private fun confirmInBackground(run: Run, who: SoftIdentity = asker, sig: ByteArray? = null): CompletableFuture<ByteArray> {
        val transcript = pairTranscript(run.na, run.nb, who.spki, host.spki)
        val signature = sig ?: signDomain(who.signer, DOMAIN_PAIR, transcript)
        return CompletableFuture.supplyAsync { pairing.confirm(who.deviceId, run.started.id, signature) }
    }

    private fun code(block: () -> Unit): String? = try { block(); null } catch (e: PairError) { e.code }

    @Test fun bothSidesConfirmAndEachProvesItsKey() {
        pairing.open()
        val run = startAndReveal()
        assertTrue(pairVerifyReveal(run.started.commitment, run.nb, host.spki, asker.spki))
        val sas = pairSas(run.na, run.nb, asker.spki, host.spki)
        assertEquals(6, sas.length)
        val view = pairing.view()!!
        assertEquals(PairStage.Comparing, view.stage)
        assertEquals("sas", view.method)
        assertEquals(sas, view.sas) // both sides compute the same six digits
        val reply = confirmInBackground(run)
        Thread.sleep(100)
        assertFalse("answered before the person decided", reply.isDone)
        pairing.decide(run.started.id, true)
        val theirSig = reply.get(5, TimeUnit.SECONDS)
        assertTrue(verifyDomain(host.spki, DOMAIN_PAIR, pairTranscript(run.na, run.nb, asker.spki, host.spki), theirSig))
        assertEquals(1, paired.size)
        assertEquals("sas", paired[0].second)
        assertEquals(asker.deviceId, paired[0].first.deviceId)
        assertEquals("10.0.0.2", paired[0].first.address)
        assertEquals(PairStage.Done, pairing.view()!!.stage)
    }

    @Test fun nobodyPairsOutsideThePairingWindow() {
        // (a far-away address: a device on this network may now ask unprompted, see the unrequested tests below)
        assertEquals("not-pairing", code { pairing.start(info(), "8.8.8.8", null) })
        pairing.open()
        assertNotNull(pairing.start(info(), "10.0.0.2", null))
        pairing.close()
        assertEquals(PairStage.Failed, pairing.view()!!.stage) // ended, not removed
        assertEquals("not-pairing", code { pairing.start(info(asker), "8.8.4.4", null) })
        assertTrue(paired.isEmpty())
    }

    @Test fun theWindowEndsAfterTwoMinutes() {
        pairing.open()
        assertTrue(pairing.active)
        clock += PAIRING_WINDOW_MS + 1
        assertFalse(pairing.active)
        assertEquals("not-pairing", code { pairing.start(info(), "8.8.8.8", null) })
    }

    @Test fun aQrTokenWorksOnceAndNeedsNoNumbers() {
        val (link, expiresAt) = pairing.makeQr("192.168.1.5", 47215)
        val qr = parsePairQr(link)!!
        assertEquals("192.168.1.5", qr.host)
        assertEquals(47215, qr.port)
        assertArrayEquals(app.keepfin.core.net.pairPinOf(host.spki), qr.pin)
        assertTrue(expiresAt > clock)
        assertFalse("showing a code does not open the window", pairing.active)
        val run = startAndReveal(token = qr.token)
        assertEquals("qr", pairing.view()!!.method)
        val reply = confirmInBackground(run)
        reply.get(5, TimeUnit.SECONDS) // nobody on this side decides: the code authorized it
        assertEquals("qr", paired.single().second)
        assertEquals(PairStage.Done, pairing.view()!!.stage)
        assertTrue(pairing.view()!!.autoApproved)
        // the same code again, from another device: used up
        val other = SoftIdentity("Another")
        assertEquals("token", code { pairing.start(info(other), "10.0.0.9", qr.token) })
    }

    @Test fun anExpiredOrInventedTokenIsRefused() {
        val (link, _) = pairing.makeQr("192.168.1.5", 47215)
        val qr = parsePairQr(link)!!
        clock += PAIR_SESSION_MS + 1
        pairing.open() // the window is open again, the code is not
        assertEquals("token", code { pairing.start(info(), "10.0.0.2", qr.token) })
        assertEquals("token", code { pairing.start(info(SoftIdentity()), "10.0.0.4", ByteArray(16) { 7 }) })
    }

    @Test fun aNewQrReplacesTheLastOne() {
        val first = parsePairQr(pairing.makeQr("h", 1).first)!!
        val second = parsePairQr(pairing.makeQr("h", 1).first)!!
        assertEquals("token", code { pairing.start(info(), "10.0.0.2", first.token) })
        assertNotNull(pairing.start(info(SoftIdentity()), "10.0.0.5", second.token))
    }

    @Test fun triesAreLimitedPerKeyAndPerAddress() {
        pairing.open()
        repeat(5) { code { pairing.start(info(), "10.0.0.2", null) } } // the first begins a session, the others are busy: every try costs one
        assertEquals("rate", code { pairing.start(info(), "10.0.0.2", null) })
        // another key from the same address is limited by the address
        val b = SoftIdentity()
        assertEquals("rate", code { pairing.start(info(b), "10.0.0.2", null) })
        // a fresh key and address is fine once the window of attempts passes
        clock += 10 * 60_000L + 1
        pairing.close()
        pairing.open()
        assertNotNull(pairing.start(info(b), "10.0.0.7", null))
    }

    @Test fun aSecondDeviceWaitsWhileOneIsPairing() {
        pairing.open()
        pairing.start(info(), "10.0.0.2", null)
        assertEquals("busy", code { pairing.start(info(SoftIdentity()), "10.0.0.3", null) })
    }

    @Test fun aWrongSignatureEndsTheSession() {
        pairing.open()
        val run = startAndReveal()
        val reply = confirmInBackground(run, sig = ByteArray(70) { 1 })
        val failure = try { reply.get(5, TimeUnit.SECONDS); null } catch (e: Exception) { (e.cause as? PairError)?.code }
        assertEquals("signature", failure)
        assertEquals(PairStage.Failed, pairing.view()!!.stage)
        assertTrue(paired.isEmpty())
    }

    @Test fun aSignatureFromAnotherKeyIsNotTheAskersProof() {
        pairing.open()
        val run = startAndReveal()
        val imposter = SoftIdentity()
        val forged = signDomain(imposter.signer, DOMAIN_PAIR, pairTranscript(run.na, run.nb, asker.spki, host.spki))
        val reply = confirmInBackground(run, sig = forged)
        assertEquals("signature", (try { reply.get(5, TimeUnit.SECONDS); null } catch (e: Exception) { (e.cause as? PairError)?.code }))
    }

    @Test fun aPersonWhoSaysTheNumbersDifferPairsNothing() {
        pairing.open()
        val run = startAndReveal()
        val reply = confirmInBackground(run)
        pairing.decide(run.started.id, false)
        val failure = try { reply.get(5, TimeUnit.SECONDS); null } catch (e: Exception) { (e.cause as? PairError)?.code }
        assertEquals("declined", failure)
        assertTrue(paired.isEmpty())
        assertEquals(PairStage.Failed, pairing.view()!!.stage)
    }

    @Test fun theOtherDeviceDecliningEndsItToo() {
        pairing.open()
        val run = startAndReveal()
        pairing.cancel(asker.deviceId, run.started.id)
        assertEquals(PairStage.Failed, pairing.view()!!.stage)
        assertEquals("declined", code { pairing.nonce(asker.deviceId, run.started.id, ByteArray(PAIR_NONCE_BYTES)) })
    }

    @Test fun theStepsMustComeInOrderAndOnlyFromTheSameKey() {
        pairing.open()
        val started = pairing.start(info(), "10.0.0.2", null)
        // confirm before the numbers were exchanged
        assertEquals("state", code { pairing.confirm(asker.deviceId, started.id, ByteArray(64)) })
        val na = ByteArray(PAIR_NONCE_BYTES).also { random.nextBytes(it) }
        assertEquals("nonce", code { pairing.nonce(asker.deviceId, started.id, ByteArray(3)) })
        pairing.nonce(asker.deviceId, started.id, na)
        assertEquals("state", code { pairing.nonce(asker.deviceId, started.id, na) }) // a second nonce
        // another key cannot continue the session
        assertEquals("gone", code { pairing.nonce(SoftIdentity().deviceId, started.id, na) })
        assertEquals("gone", code { pairing.nonce(asker.deviceId, "no-such-session", na) })
    }

    @Test fun aSessionExpiresTwoMinutesAfterItStarted() {
        pairing.open()
        val run = startAndReveal()
        clock += PAIR_SESSION_MS + 1
        assertEquals("expired", code { pairing.confirm(asker.deviceId, run.started.id, ByteArray(64)) })
        assertTrue(paired.isEmpty())
    }

    @Test fun aTamperedRevealDoesNotMatchTheCommitment() {
        pairing.open()
        val run = startAndReveal()
        val tampered = run.nb.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(pairVerifyReveal(run.started.commitment, tampered, host.spki, asker.spki))
        // and a commitment made for other keys does not match either (a relay in the middle)
        val relay = SoftIdentity()
        assertFalse(pairVerifyReveal(run.started.commitment, run.nb, host.spki, relay.spki))
    }

    @Test fun aRelayInTheMiddleSeesDifferentNumbersOnEachSide() {
        pairing.open()
        // the asker thinks it talks to the host, but the relay holds its own key towards each: the transcripts bind the keys
        val relay = SoftIdentity("Relay")
        val run = startAndReveal()
        val sasAsHostSeesIt = pairSas(run.na, run.nb, asker.spki, host.spki)
        val sasAsAskerSeesIt = pairSas(run.na, run.nb, asker.spki, relay.spki)
        assertTrue(sasAsHostSeesIt != sasAsAskerSeesIt)
    }

    @Test fun thePageIsToldWhenAnythingChanges() {
        val before = changes
        pairing.open()
        pairing.start(info(), "10.0.0.2", null)
        assertTrue(changes > before)
    }
}
