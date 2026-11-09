package app.keepfin

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Passes a stream through while keeping what [IsrcTags] reads: its first [IsrcTags.WINDOW] bytes and its last [IsrcTags.WINDOW] bytes (all of
 * it when shorter than twice that). The library copies a file into the folder as a stream, so the recording code is read on the way, with no
 * second pass over the file.
 */
internal class IsrcCapture(private val inner: InputStream) : InputStream() {
    private val head = ByteArrayOutputStream()
    private val ring = ByteArray(IsrcTags.WINDOW)
    private var ringAt = 0
    private var ringFilled = 0

    private fun keep(b: ByteArray, off: Int, len: Int) {
        var from = off
        var left = len
        val room = IsrcTags.WINDOW - head.size()
        if (room > 0) {
            val n = minOf(room, left)
            head.write(b, from, n)
            from += n
            left -= n
        }
        while (left > 0) {
            val n = minOf(left, ring.size - ringAt)
            System.arraycopy(b, from, ring, ringAt, n)
            ringAt = (ringAt + n) % ring.size
            ringFilled = minOf(ring.size, ringFilled + n)
            from += n
            left -= n
        }
    }

    override fun read(): Int {
        val v = inner.read()
        if (v >= 0) keep(byteArrayOf(v.toByte()), 0, 1)
        return v
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = inner.read(b, off, len)
        if (n > 0) keep(b, off, n)
        return n
    }

    override fun close() = inner.close()

    /** The code in what has passed through so far, or null. */
    fun isrc(): String? {
        val tail = ByteArray(ringFilled)
        val start = if (ringFilled < ring.size) 0 else ringAt
        for (i in 0 until ringFilled) tail[i] = ring[(start + i) % ring.size]
        return try { IsrcTags.read(head.toByteArray() + tail) } catch (_: Exception) { null }
    }
}
