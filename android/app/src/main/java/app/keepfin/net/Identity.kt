package app.keepfin.net

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.keepfin.core.net.DeviceSigner
import app.keepfin.core.net.deviceIdOf
import app.keepfin.core.net.isP256Spki
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import org.json.JSONObject

// This device's identity (spec 1 and 9.1 #9): a P-256 key made once per install and held by AndroidKeyStore, never exported. The
// device id is the SHA-256 of its SPKI. The self-signed certificate that AndroidKeyStore makes with the key carries it; nobody checks
// the certificate's name, chain or dates (the pinned key is the trust), so it is made valid for 25 years instead of being renewed
// yearly, which would need a certificate builder. The same key signs this device's segments (KFSEG 2) and the pairing transcript.

interface DeviceIdentity {
    val spki: ByteArray
    val deviceId: String
    val signer: DeviceSigner
    val name: String
    fun keyManager(): X509KeyManager
}

/** The short form people compare: the first 20 hex digits of the device id in groups of four. */
fun fingerprintOf(deviceId: String): String = deviceId.take(20).chunked(4).joinToString(" ")

class KeystoreIdentity private constructor(
    private val alias: String,
    private val privateKey: PrivateKey,
    private val chain: Array<X509Certificate>,
    private val nameFile: File?,
    private val defaultName: String,
) : DeviceIdentity {
    override val spki: ByteArray = chain[0].publicKey.encoded
    override val deviceId: String = deviceIdOf(spki)

    @Volatile private var nameValue: String = readName()

    override val name: String get() = nameValue

    override val signer: DeviceSigner = object : DeviceSigner {
        override val spki: ByteArray get() = this@KeystoreIdentity.spki

        override fun sign(message: ByteArray): ByteArray {
            val s = Signature.getInstance("SHA256withECDSA")
            s.initSign(privateKey)
            s.update(message)
            return s.sign()
        }
    }

    private fun readName(): String {
        val f = nameFile ?: return defaultName
        return try {
            JSONObject(f.readText(Charsets.UTF_8)).optString("name").trim().take(40).ifEmpty { defaultName }
        } catch (e: Exception) {
            defaultName
        }
    }

    fun rename(newName: String) {
        val clean = newName.trim().take(40).ifEmpty { defaultName }
        nameValue = clean
        nameFile?.let {
            it.parentFile?.mkdirs()
            it.writeText(JSONObject().put("name", clean).toString(), Charsets.UTF_8)
        }
    }

    /** Presents this key and certificate whatever alias the TLS stack asks about. */
    override fun keyManager(): X509KeyManager = object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(alias)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = alias
        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(alias)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = alias
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = privateKey
    }

    companion object {
        const val DEFAULT_ALIAS = "keepfin_device_key"

        /** Loads the key of this install, making it the first time. [alias] lets tests hold several devices in one process. */
        fun load(context: Context, alias: String = DEFAULT_ALIAS): KeystoreIdentity {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(alias)) {
                val now = System.currentTimeMillis()
                val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                    .setCertificateSubject(X500Principal("CN=keepfin-device"))
                    .setCertificateNotBefore(java.util.Date(now - 86_400_000L))
                    .setCertificateNotAfter(java.util.Date(now + 25L * 365 * 86_400_000L))
                    .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
            }
            val key = ks.getKey(alias, null) as PrivateKey
            val chain = ks.getCertificateChain(alias).map { it as X509Certificate }.toTypedArray()
            check(isP256Spki(chain[0].publicKey.encoded)) { "The device key is not a P-256 key." }
            val file = File(File(context.filesDir, "net"), if (alias == DEFAULT_ALIAS) "device.json" else "device-$alias.json")
            return KeystoreIdentity(alias, key, chain, file, defaultDeviceName())
        }

        fun defaultDeviceName(): String = (Build.MODEL ?: "Android").trim().take(40).ifEmpty { "Android phone" }
    }
}

// ---- TLS -------------------------------------------------------------------------------------------------------------------

/** The SHA-256 of a certificate's SPKI, the device id of whoever holds the key. */
fun deviceIdOfCertificate(cert: X509Certificate): String = deviceIdOf(cert.publicKey.encoded)

/**
 * The server side takes any client certificate into the handshake (the handshake still proves the client holds the key), and the
 * pin is decided right after, from the key: paired or not, allowed or not. This is the same arrangement as the desktop's
 * `rejectUnauthorized: false`.
 */
object AcceptAnyClient : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("no certificate")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = throw CertificateException("not a client trust manager")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class PinMismatch : Exception("This is not the device that was paired: its key is different.")

/**
 * The client side: the server's key must be the pinned one, compared in constant time. With [pin] null the first key is accepted and
 * then insisted on (pairing by address, before any pin exists); [seen] reports it.
 */
class PinTrust(pin: String?, private val pinBits: Int? = null) : X509TrustManager {
    @Volatile private var pinned: String? = pin
    @Volatile var seenSpki: ByteArray? = null
        private set

    /** The server presented a key other than the pinned one (so a failed handshake can be told from a network failure). */
    @Volatile var mismatch = false
        private set

    val seenDeviceId: String? get() = seenSpki?.let { deviceIdOf(it) }

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = throw CertificateException("not a server trust manager")

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        checkSpki(leaf.publicKey.encoded)
    }

    /** The key the server presented: it must be the pinned one and, for a typed code, start with the code's 30 pin bits. */
    internal fun checkSpki(spki: ByteArray) {
        val id = deviceIdOf(spki)
        val expected = pinned
        if (expected != null && !MessageDigest.isEqual(id.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))) {
            mismatch = true
            throw CertificateException("key not pinned")
        }
        if (pinBits != null && !app.keepfin.core.net.pinMatches(app.keepfin.core.net.pairPinOf(spki), pinBits)) {
            mismatch = true
            throw CertificateException("key not pinned")
        }
        pinned = id // the first key stays the key
        seenSpki = spki
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

object Tls {
    /** TLS 1.3 only; no session reuse (spec 9.1 #4: resumption and 0-RTT off). */
    fun serverContext(identity: DeviceIdentity): SSLContext = context(identity, AcceptAnyClient).also { noSessions(it) }

    fun clientContext(identity: DeviceIdentity, trust: PinTrust): SSLContext = context(identity, trust).also { noSessions(it) }

    private fun context(identity: DeviceIdentity, trust: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(identity.keyManager()), arrayOf(trust), SecureRandom()) }

    private fun noSessions(ctx: SSLContext) {
        for (sessions in listOfNotNull(ctx.serverSessionContext, ctx.clientSessionContext)) {
            sessions.sessionTimeout = 1
            sessions.sessionCacheSize = 1
        }
    }

    /** TLS 1.3 needs Android 10; older phones cannot take part (the page says so). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 29
}
