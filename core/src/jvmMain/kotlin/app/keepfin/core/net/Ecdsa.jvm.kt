package app.keepfin.core.net

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

actual object Ecdsa {
    actual fun generate(): EcKeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        val p = g.generateKeyPair()
        return EcKeyPair(p.public.encoded, p.private.encoded)
    }

    actual fun sign(pkcs8: ByteArray, message: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(key)
        s.update(message)
        return s.sign()
    }

    actual fun verify(spki: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(key)
        s.update(message)
        s.verify(signature)
    } catch (e: Exception) {
        false
    }
}
