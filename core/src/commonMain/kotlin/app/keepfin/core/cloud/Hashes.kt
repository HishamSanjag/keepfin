package app.keepfin.core.cloud

import app.keepfin.core.sync.sha256

// The providers' own content hashes, computed locally so a download or an upload can be checked against what the provider
// reports (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md 7.1 #11). Neither hash ever establishes Keepfin's SHA-256 identity: where identity
// matters the file is hashed with SHA-256 itself.

/**
 * OneDrive's quickXorHash (https://learn.microsoft.com/onedrive/developer/code-snippets/quickxorhash): a 160-bit vector into
 * which every byte is XORed at bit offset (11 * index) mod 160, then the total length is XORed into the last 8 bytes, little
 * endian. The result is Base64 of the 20 bytes. Feed it chunks with [update]; [digest] may be called once.
 */
class QuickXorHash {
    private val data = LongArray(3)
    private var length = 0L
    private var shiftSoFar = 0

    fun update(array: ByteArray, from: Int = 0, to: Int = array.size) {
        val size = to - from
        if (size <= 0) return
        var vectorArrayIndex = shiftSoFar / 64
        var vectorOffset = shiftSoFar % 64
        val iterations = if (size < WIDTH) size else WIDTH
        for (i in 0 until iterations) {
            val isLastCell = vectorArrayIndex == data.size - 1
            val bitsInCell = if (isLastCell) 32 else 64
            if (vectorOffset <= bitsInCell - 8) {
                var j = i + from
                while (j < size + from) {
                    data[vectorArrayIndex] = data[vectorArrayIndex] xor ((array[j].toLong() and 0xFF) shl vectorOffset)
                    j += WIDTH
                }
            } else {
                val index1 = vectorArrayIndex
                val index2 = if (isLastCell) 0 else vectorArrayIndex + 1
                val low = bitsInCell - vectorOffset
                var xored = 0
                var j = i + from
                while (j < size + from) {
                    xored = xored xor (array[j].toInt() and 0xFF)
                    j += WIDTH
                }
                data[index1] = data[index1] xor (xored.toLong() shl vectorOffset)
                data[index2] = data[index2] xor (xored.toLong() ushr low)
            }
            vectorOffset += SHIFT
            while (vectorOffset >= bitsInCell) {
                vectorArrayIndex = if (isLastCell) 0 else vectorArrayIndex + 1
                vectorOffset -= bitsInCell
            }
        }
        shiftSoFar = (shiftSoFar + SHIFT * (size % WIDTH)) % WIDTH
        length += size
    }

    /** The 20 digest bytes. */
    fun digestBytes(): ByteArray {
        val out = ByteArray(20)
        for (i in 0 until 2) for (b in 0 until 8) out[i * 8 + b] = ((data[i] ushr (8 * b)) and 0xFF).toByte()
        for (b in 0 until 4) out[16 + b] = ((data[2] ushr (8 * b)) and 0xFF).toByte()
        for (b in 0 until 8) out[12 + b] = (out[12 + b].toInt() xor (((length ushr (8 * b)) and 0xFF).toInt())).toByte()
        return out
    }

    /** Base64 of the digest: how Graph writes `file.hashes.quickXorHash`. */
    fun digest(): String = base64Of(digestBytes())

    companion object {
        private const val WIDTH = 160
        private const val SHIFT = 11

        fun of(bytes: ByteArray): String = QuickXorHash().also { it.update(bytes) }.digest()
    }
}

/**
 * Dropbox's content_hash (https://www.dropbox.com/developers/reference/content-hash): SHA-256 of the concatenated SHA-256
 * digests of every 4 MiB block of the file (the last block may be shorter), as lower-case hex. An empty file has no blocks,
 * so its hash is the SHA-256 of nothing.
 */
class DropboxContentHash {
    private val blockDigests = ArrayList<ByteArray>()
    private val block = ByteArray(BLOCK)
    private var filled = 0

    fun update(array: ByteArray, from: Int = 0, to: Int = array.size) {
        var at = from
        while (at < to) {
            val n = minOf(BLOCK - filled, to - at)
            array.copyInto(block, filled, at, at + n)
            filled += n
            at += n
            if (filled == BLOCK) {
                blockDigests.add(sha256(block))
                filled = 0
            }
        }
    }

    fun digest(): String {
        val digests = ArrayList(blockDigests)
        if (filled > 0) digests.add(sha256(block.copyOfRange(0, filled)))
        return combineBlockDigests(digests)
    }

    companion object {
        const val BLOCK = 4 * 1024 * 1024

        /** The final step on its own, for callers that hash blocks with a native SHA-256 (the desktop does). */
        fun combineBlockDigests(digests: List<ByteArray>): String {
            val all = ByteArray(digests.size * 32)
            digests.forEachIndexed { i, d -> d.copyInto(all, i * 32) }
            return hexOf(sha256(all))
        }

        fun of(bytes: ByteArray): String = DropboxContentHash().also { it.update(bytes) }.digest()
    }
}
