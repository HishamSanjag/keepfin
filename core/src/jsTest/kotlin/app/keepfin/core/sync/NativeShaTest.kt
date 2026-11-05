package app.keepfin.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** On Node the checkpoint checksum comes from the runtime; it must be the digest the portable code gives. */
class NativeShaTest {
    @Test
    fun theRuntimeDigestEqualsThePortableOne() {
        installNativeSha()
        assertNotNull(sha256Native, "this test runs on Node, which has node:crypto")
        val rnd = Random(3)
        for (n in listOf(0, 1, 63, 64, 65, 8191, 8192, 8193, 100_000, 1_048_577)) {
            val data = rnd.nextBytes(n + 7)
            assertEquals(sha256HexReference(data.copyOfRange(3, 3 + n)), sha256HexRange(data, 3, 3 + n), "length $n at an offset")
        }
        // an encoded checkpoint still reads back through the native checksum
        val log = logOf(OpGen(2, 3).generate(200))
        val bytes = encodeCheckpoint(log.checkpoint(TEST_LIB))
        assertEquals(log.state, (decodeCheckpoint(bytes, TEST_LIB) as CheckpointResult.Ok).checkpoint.state)
    }
}
