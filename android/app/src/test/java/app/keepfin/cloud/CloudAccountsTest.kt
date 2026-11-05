package app.keepfin.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

internal class CloudAccountsTest {
    private val ids = CloudIds("google-client", "ms-client", "dropbox-key")
    private fun tempFile() = File.createTempFile("kf-accounts", ".json").also { it.delete() }

    @Test fun tokensAndPasswordsAreEncryptedAndSurviveARestart() {
        val file = tempFile()
        val box = SoftwareBox()
        val a = CloudAccounts(file, box, ids)
        val oauth = a.addOAuth("graph", TokenSet("secret-access-token", "secret-refresh-token", System.currentTimeMillis() + 3_600_000), "me@example.com")
        val dav = a.addWebdav("https://dav.example.com/remote.php/dav", "alice", "correct horse battery", "alice at dav.example.com")
        val text = file.readText()
        assertFalse("no token in plain text", text.contains("secret-access-token") || text.contains("secret-refresh-token"))
        assertFalse("no password in plain text", text.contains("correct horse battery"))
        val again = CloudAccounts(file, box, ids)
        assertEquals(2, again.list().size)
        assertEquals("Bearer secret-access-token", again.authorization(oauth.id))
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("alice:correct horse battery".toByteArray()), again.authorization(dav.id))
        assertEquals("https://dav.example.com/remote.php/dav" to "alice", again.webdav(dav.id))
        // the views carry neither
        assertFalse(again.list().any { it.toString().contains("secret") })
    }

    @Test fun anotherKeyMeansSignInAgainNeverAGuess() {
        val file = tempFile()
        val a = CloudAccounts(file, SoftwareBox(), ids)
        val acct = a.addOAuth("dropbox", TokenSet("t", "r", null), "dropbox")
        val other = CloudAccounts(file, SoftwareBox(), ids) // a restored backup: the Keystore key is different
        assertTrue(other.get(acct.id)!!.needsSignIn)
        try {
            other.authorization(acct.id)
            fail("a signed-out account must not give a header")
        } catch (e: CloudError) {
            assertEquals(Kind.NEEDS_SIGN_IN, e.kind)
        }
    }

    @Test fun withoutSecureStorageNothingIsKept() {
        val a = CloudAccounts(tempFile(), SoftwareBox(availableFlag = false), ids)
        try {
            a.addWebdav("https://dav.example.com", "u", "p", "u")
            fail("no secure storage, no stored password")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Secure storage"))
        }
    }

    @Test fun theSignInFlowUsesPkceAndStateAndTheProvidersExpiry() {
        val peer = NodePeer.require()
        for (provider in listOf("graph", "dropbox")) {
            val tokens = TokenClient(peer::rewriteOAuth)
            val start = OAuthSignIn.begin(provider, ids)
            assertTrue(start.url.contains("code_challenge_method=S256"))
            assertTrue(start.url.contains("state=${start.state}"))
            val callback = peer.post("/oauth/approve", JSONObject().put("authUrl", start.url)).getString("callback")
            val t = OAuthSignIn.finish(start, callback, ids, tokens)
            assertTrue(t.accessToken.startsWith("access-"))
            assertNotNull(t.refreshToken)
            assertNotNull("the expiry is the provider's expires_in", t.expiresAtMs)
            // a callback with another state is ignored
            val start2 = OAuthSignIn.begin(provider, ids)
            val bad = peer.post("/oauth/approve", JSONObject().put("authUrl", start2.url).put("state", "someone-elses")).getString("callback")
            try {
                OAuthSignIn.finish(start2, bad, ids, tokens)
                fail("a foreign state must be refused")
            } catch (e: SignInException) {
                assertTrue(e.message!!.contains("did not come from this request"))
            }
            // a denial reads as a cancellation
            val start3 = OAuthSignIn.begin(provider, ids)
            val denied = peer.post("/oauth/approve", JSONObject().put("authUrl", start3.url).put("deny", true)).getString("callback")
            try {
                OAuthSignIn.finish(start3, denied, ids, tokens)
                fail("denied")
            } catch (e: SignInException) {
                assertEquals("The sign-in was cancelled.", e.message)
            }
        }
    }

    @Test fun anExpiredTokenIsRefreshedAndTheRotatedTokenReplacesTheOldOne() {
        val peer = NodePeer.require()
        peer.post("/oauth/lifetime", JSONObject().put("seconds", 3600))
        val tokens = TokenClient(peer::rewriteOAuth)
        val start = OAuthSignIn.begin("graph", ids)
        val t = OAuthSignIn.finish(start, peer.post("/oauth/approve", JSONObject().put("authUrl", start.url)).getString("callback"), ids, tokens)
        var clock = System.currentTimeMillis()
        val file = tempFile()
        val box = SoftwareBox()
        val a = CloudAccounts(file, box, ids, tokens, { clock })
        val acct = a.addOAuth("graph", t, "me")
        val before = peer.post("/oauth/state").getInt("refreshCalls")
        assertEquals("Bearer ${t.accessToken}", a.authorization(acct.id))
        assertEquals("no refresh while the token is good", before, peer.post("/oauth/state").getInt("refreshCalls"))
        clock += 2 * 3_600_000L // the provider said one hour
        val h1 = a.authorization(acct.id)
        assertNotEquals("Bearer ${t.accessToken}", h1)
        assertEquals(before + 1, peer.post("/oauth/state").getInt("refreshCalls"))
        // the rotated refresh token was stored atomically: a restarted app can refresh again
        clock += 2 * 3_600_000L
        val restarted = CloudAccounts(file, box, ids, tokens, { clock })
        val h2 = restarted.authorization(acct.id)
        assertNotEquals(h1, h2)
        assertFalse(restarted.get(acct.id)!!.needsSignIn)
    }

    @Test fun aRefusedRefreshMeansSignInAgainButANetworkHiccupDoesNot() {
        val peer = NodePeer.require()
        val tokens = TokenClient(peer::rewriteOAuth)
        val start = OAuthSignIn.begin("dropbox", ids)
        val t = OAuthSignIn.finish(start, peer.post("/oauth/approve", JSONObject().put("authUrl", start.url)).getString("callback"), ids, tokens)
        var clock = System.currentTimeMillis()
        val a = CloudAccounts(tempFile(), SoftwareBox(), ids, tokens, { clock })
        val acct = a.addOAuth("dropbox", t, "me")
        clock += 10 * 3_600_000L
        // the network is gone: an error, but the account is not signed out
        val offline = CloudAccounts(tempFile(), SoftwareBox(), ids, TokenClient({ "http://127.0.0.1:1/nope" }), { clock })
        val acct2 = offline.addOAuth("dropbox", t, "me")
        try {
            offline.authorization(acct2.id)
            fail("offline")
        } catch (e: CloudError) {
            assertEquals(Kind.TRANSIENT, e.kind)
        }
        assertFalse(offline.get(acct2.id)!!.needsSignIn)
        // the person ended the grant: the refresh is refused
        peer.post("/oauth/revoke-all")
        try {
            a.authorization(acct.id)
            fail("revoked")
        } catch (e: CloudError) {
            assertEquals(Kind.NEEDS_SIGN_IN, e.kind)
        }
        assertTrue(a.get(acct.id)!!.needsSignIn)
        // Sign in again clears it
        val s2 = OAuthSignIn.begin("dropbox", ids)
        val t2 = OAuthSignIn.finish(s2, peer.post("/oauth/approve", JSONObject().put("authUrl", s2.url)).getString("callback"), ids, tokens)
        a.replaceTokens(acct.id, t2)
        clock = System.currentTimeMillis()
        assertFalse(a.get(acct.id)!!.needsSignIn)
        assertEquals("Bearer ${t2.accessToken}", a.authorization(acct.id))
    }

    @Test fun googleThroughPlayServicesAsksForAFreshTokenWithoutAScreenAndFallsBackToSignInAgain() {
        var answer: TokenSet? = TokenSet("g-token-2", null, null)
        var clock = System.currentTimeMillis()
        val a = CloudAccounts(tempFile(), SoftwareBox(), ids, TokenClient(), { clock }, googleRefresh = { answer })
        val acct = a.addOAuth("drive", TokenSet("g-token-1", null, clock + 3_600_000), "me@gmail.com", play = true)
        assertEquals("Bearer g-token-1", a.authorization(acct.id))
        clock += 2 * 3_600_000L
        assertEquals("Bearer g-token-2", a.authorization(acct.id))
        // a 401: the core asks for a refresh
        answer = TokenSet("g-token-3", null, null)
        assertTrue(a.refreshNow(acct.id))
        assertEquals("Bearer g-token-3", a.authorization(acct.id))
        // Play services needs the person again
        answer = null
        assertFalse(a.refreshNow(acct.id))
        assertTrue(a.get(acct.id)!!.needsSignIn)
    }

    @Test fun disconnectForgetsTheLocalSignInOnly() {
        val file = tempFile()
        val a = CloudAccounts(file, SoftwareBox(), ids)
        val acct = a.addWebdav("https://dav.example.com", "u", "pw", "u")
        a.disconnect(acct.id)
        assertNull(a.get(acct.id))
        assertFalse(file.readText().contains("dav.example.com"))
    }

    @Test fun webdavAddressesNeedHttpsExceptOnThisPhone() {
        assertEquals("https://dav.example.com/remote.php/dav", UrlPolicy.validateWebdav(" https://dav.example.com/remote.php/dav/ "))
        assertEquals("http://127.0.0.1:8080/dav", UrlPolicy.validateWebdav("http://127.0.0.1:8080/dav"))
        for (bad in listOf("http://dav.example.com", "ftp://x.example.com", "https://user:pw@dav.example.com", "not a url", "")) {
            try {
                UrlPolicy.validateWebdav(bad)
                fail("'$bad' must be refused")
            } catch (e: SignInException) {
                // expected
            }
        }
    }

    @Test fun cloudAddressesRoundTripAndNeverLookLikeFolders() {
        val a = CloudAddress.make("acct-1", "Keepfin/Mein Café ♫/x")
        assertTrue(CloudAddress.isCloud(a))
        assertEquals("acct-1" to "Keepfin/Mein Café ♫/x", CloudAddress.parse(a))
        assertFalse(CloudAddress.isCloud("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertNull(CloudAddress.parse("content://x"))
    }
}


