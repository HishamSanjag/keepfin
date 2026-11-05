package app.keepfin.net

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Base64

// A minimal RFC 6455 server side for `GET /v1/events` (spec 4). The stream is one way: the server sends small JSON text messages
// and ignores whatever the peer sends, except for what keeps the connection honest (ping, pong, close). Written here rather than
// taken from a library because the whole thing is about 150 lines, adds no dependency to audit, and needs exactly four things:
// the handshake, unmasked text frames out, masked control frames in, and a bounded queue so a slow peer is dropped instead of
// buffered (spec 9.1 #7 "WebSocket backpressure"). No extensions, no fragmentation, no binary frames.

private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
private const val MAX_INBOUND = 16 * 1024

object WebSocketHandshake {
    fun acceptKey(key: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.ISO_8859_1)))

    /** The client's `Sec-WebSocket-Key` for a valid upgrade request, else an [HttpError]. */
    fun check(request: HttpRequest): String {
        val upgrade = request.header("upgrade")?.lowercase()
        val connection = request.header("connection")?.lowercase()?.split(',')?.map { it.trim() } ?: emptyList()
        if (request.method != "GET" || upgrade != "websocket" || "upgrade" !in connection) throw HttpError(400, "upgrade", "This is not a WebSocket request.")
        if (request.header("sec-websocket-version") != "13") throw HttpError(426, "ws-version", "Only WebSocket version 13 is supported.")
        val key = request.header("sec-websocket-key") ?: throw HttpError(400, "ws-key", "The WebSocket key is missing.")
        val raw = try {
            Base64.getDecoder().decode(key)
        } catch (e: IllegalArgumentException) {
            throw HttpError(400, "ws-key", "The WebSocket key is not valid.")
        }
        if (raw.size != 16) throw HttpError(400, "ws-key", "The WebSocket key is not valid.")
        return key
    }

    fun response(key: String): ByteArray =
        ("HTTP/1.1 101 Switching Protocols\r\nupgrade: websocket\r\nconnection: Upgrade\r\nsec-websocket-accept: ${acceptKey(key)}\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
}

/** One open event stream. [send] never blocks: a full queue means the peer is too slow, and the stream is closed. */
class WsSession(
    private val input: InputStream,
    private val out: OutputStream,
    private val socket: Socket?,
    queueSize: Int = 256,
    private val pingEveryMs: Long = 30_000,
    private val deadAfterMs: Long = 100_000,
) {
    private sealed class Item {
        class Text(val bytes: ByteArray) : Item()
        object Ping : Item()
        class Pong(val payload: ByteArray) : Item()
        class Close(val code: Int) : Item()
    }

    private val queue = ArrayBlockingQueue<Item>(queueSize)
    private val closed = AtomicBoolean(false)
    @Volatile private var lastInbound = System.nanoTime()

    val isOpen: Boolean get() = !closed.get()

    /** Queues a text message. False when the stream is closed or the queue is full (then it is closed here). */
    fun send(text: String): Boolean {
        if (closed.get()) return false
        if (!queue.offer(Item.Text(text.toByteArray(Charsets.UTF_8)))) {
            terminate()
            return false
        }
        return true
    }

    fun close(code: Int = 1000) {
        if (!closed.get()) queue.offer(Item.Close(code))
    }

    /** Drops the connection now (Remove device: its streams end at once). */
    fun terminate() {
        closed.set(true)
        queue.clear()
        runCatching { socket?.close() }
    }

    /** Runs the writer on this thread's helper and the reader here; returns when the stream ends. */
    fun run() {
        val writer = Thread({ writeLoop() }, "keepfin-ws-writer").also { it.isDaemon = true }
        writer.start()
        try {
            readLoop()
        } catch (e: IOException) {
            // the peer went away
        } finally {
            closed.set(true)
            queue.offer(Item.Close(1000))
            runCatching { writer.join(2000) }
            runCatching { socket?.close() }
        }
    }

    private fun writeLoop() {
        try {
            while (true) {
                val item = queue.poll(pingEveryMs, TimeUnit.MILLISECONDS)
                if (closed.get() && item !is Item.Close) return
                when (item) {
                    null -> {
                        if ((System.nanoTime() - lastInbound) / 1_000_000 > deadAfterMs) {
                            terminate()
                            return
                        }
                        frame(0x9, ByteArray(0))
                    }
                    is Item.Text -> frame(0x1, item.bytes)
                    Item.Ping -> frame(0x9, ByteArray(0))
                    is Item.Pong -> frame(0xA, item.payload)
                    is Item.Close -> {
                        frame(0x8, byteArrayOf((item.code shr 8).toByte(), item.code.toByte()))
                        closed.set(true)
                        runCatching { socket?.shutdownOutput() }
                        return
                    }
                }
            }
        } catch (e: IOException) {
            terminate()
        } catch (e: InterruptedException) {
            terminate()
        }
    }

    private fun frame(opcode: Int, payload: ByteArray) {
        val header = java.io.ByteArrayOutputStream(10)
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(payload.size)
            payload.size < 65536 -> {
                header.write(126)
                header.write(payload.size shr 8)
                header.write(payload.size and 0xff)
            }
            else -> {
                header.write(127)
                for (shift in 56 downTo 0 step 8) header.write(((payload.size.toLong() shr shift) and 0xff).toInt())
            }
        }
        synchronized(out) {
            out.write(header.toByteArray())
            out.write(payload)
            out.flush()
        }
    }

    private fun readFully(buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    private fun readLoop() {
        socket?.soTimeout = 0
        val head = ByteArray(8)
        while (!closed.get()) {
            readFully(head, 2)
            lastInbound = System.nanoTime()
            val fin = head[0].toInt() and 0x80 != 0
            val opcode = head[0].toInt() and 0x0f
            val masked = head[1].toInt() and 0x80 != 0
            var length = (head[1].toInt() and 0x7f).toLong()
            if (head[0].toInt() and 0x70 != 0 || !masked) return failClose(1002)
            if (length == 126L) {
                readFully(head, 2)
                length = ((head[0].toInt() and 0xff) shl 8 or (head[1].toInt() and 0xff)).toLong()
            } else if (length == 127L) {
                readFully(head, 8)
                length = 0
                for (i in 0 until 8) length = (length shl 8) or (head[i].toLong() and 0xff)
                if (length < 0) return failClose(1009)
            }
            if (opcode >= 0x8 && (length > 125 || !fin)) return failClose(1002)
            if (length > MAX_INBOUND) return failClose(1009)
            val mask = ByteArray(4)
            readFully(mask, 4)
            val payload = ByteArray(length.toInt())
            readFully(payload, payload.size)
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x8 -> {
                    queue.offer(Item.Close(1000))
                    return
                }
                0x9 -> queue.offer(Item.Pong(payload))
                0xA, 0x0, 0x1, 0x2 -> Unit // pong, or data the stream ignores
                else -> return failClose(1002)
            }
        }
    }

    private fun failClose(code: Int) {
        queue.offer(Item.Close(code))
    }
}
