package app.keepfin.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpLayerTest {
    private val limits = Limits()
    private fun parse(text: String, l: Limits = limits): HttpRequest? = HttpParser(l).readHead(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)), null)
    private fun refused(text: String, l: Limits = limits): HttpError =
        try { parse(text, l); fail("accepted: ${text.take(60)}"); throw AssertionError() } catch (e: HttpError) { e }

    @Test fun aValidRequestIsParsed() {
        val r = parse("GET /v1/libraries/abc/segments?vector=a%3A1&x=y+z HTTP/1.1\r\nHost: x\r\nX-Thing:  spaced \r\n\r\n")!!
        assertEquals("GET", r.method)
        assertEquals(listOf("v1", "libraries", "abc", "segments"), r.segments)
        assertEquals("a:1", r.query["vector"])
        assertEquals("y z", r.query["x"])
        assertEquals("spaced", r.header("x-thing"))
        assertEquals(0L, r.contentLength)
    }

    @Test fun aCleanCloseBeforeARequestIsNull() {
        assertNull(parse(""))
    }

    @Test fun malformedHeadsAreRefusedWithAStatus() {
        assertEquals(400, refused("GET / HTTP/1.1\nHost: x\n\n").status) // bare line feeds
        assertEquals(400, refused("GET /\r\n\r\n").status) // two parts
        assertEquals(405, refused("DELETE / HTTP/1.1\r\n\r\n").status)
        assertEquals(505, refused("GET / HTTP/1.0\r\n\r\n").status)
        assertEquals(400, refused("GET http://x/ HTTP/1.1\r\n\r\n").status) // absolute form
        assertEquals(400, refused("GET /a b HTTP/1.1\r\n\r\n").status)
        assertEquals(400, refused("GET /a#frag HTTP/1.1\r\n\r\n").status)
        assertEquals(400, refused("GET / HTTP/1.1\r\nBad Name: x\r\n\r\n").status)
        assertEquals(400, refused("GET / HTTP/1.1\r\nNoColon\r\n\r\n").status)
        assertEquals(400, refused("GET / HTTP/1.1\r\nA: 1\r\n folded\r\n\r\n").status)
        assertEquals(400, refused("GET / HTTP/1.1\r\nA: 1\r\nA: 2\r\n\r\n").status) // repeated names
        assertEquals(400, refused("GET / HTTP/1.1\r\nA: x\u0001y\r\n\r\n").status) // control character
        assertEquals(411, refused("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n").status)
        assertEquals(400, refused("POST / HTTP/1.1\r\nContent-Length: 12x\r\n\r\n").status)
        assertEquals(400, refused("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n").status)
        assertEquals(400, refused("GET / HTTP/1.1\r\nContent-Length: 5\r\n\r\n").status) // a GET with a body
        assertEquals(400, refused("GET /%zz HTTP/1.1\r\n\r\n").status)
        assertEquals(400, refused("GET /%c3%28 HTTP/1.1\r\n\r\n").status) // invalid UTF-8
        assertEquals(400, refused("GET /a%00b HTTP/1.1\r\n\r\n").status)
    }

    @Test fun sizeLimitsHold() {
        assertEquals(431, refused("GET / HTTP/1.1\r\nA: " + "x".repeat(limits.maxHeaderBytes) + "\r\n\r\n").status)
        val many = (1..limits.maxHeaders + 1).joinToString("") { "H$it: v\r\n" }
        assertEquals(431, refused("GET / HTTP/1.1\r\n$many\r\n").status)
        assertEquals(400, refused("GET /" + "a".repeat(limits.maxTargetChars) + " HTTP/1.1\r\n\r\n").status)
        val bigQuery = (1..40).joinToString("&") { "k$it=v" }
        assertEquals(400, refused("GET /p?$bigQuery HTTP/1.1\r\n\r\n").status)
    }

    @Test fun aHeadThatEndsInsideIsAnIoErrorNotAnAnswer() {
        try {
            parse("GET / HTTP/1.1\r\nHost: x")
            fail()
        } catch (e: IOException) {
            // closed inside the head
        }
    }

    @Test fun percentDecodingIsStrict() {
        assertEquals("a b", percentDecode("a%20b", plus = false))
        assertEquals("a+b", percentDecode("a+b", plus = false))
        assertEquals("a b", percentDecode("a+b", plus = true))
        assertEquals("é", percentDecode("%C3%A9", plus = false))
        for (bad in listOf("%", "%4", "%gg", "%c3", "%ff")) {
            try { percentDecode(bad, false); fail("accepted $bad") } catch (e: HttpError) { assertEquals(400, e.status) }
        }
    }

    @Test fun fuzzedHeadsOnlyEverEndInAnAnswerOrAClosedConnection() {
        val seed = ("GET /v1/libraries/aaaa/segments?vector=x%3A1 HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\nA: b\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        val rnd = Random(7)
        var answered = 0
        repeat(6000) {
            val bytes = if (it % 4 == 0) ByteArray(rnd.nextInt(300)).also(rnd::nextBytes) else seed.copyOf().also { b ->
                repeat(1 + rnd.nextInt(6)) { _ -> b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            }.let { b -> if (rnd.nextBoolean()) b.copyOf(rnd.nextInt(b.size + 1)) else b }
            try {
                HttpParser(limits).readHead(ByteArrayInputStream(bytes), null)
                answered++
            } catch (e: HttpError) {
                assertTrue(e.status in 400..505)
            } catch (e: IOException) {
                // closed inside the head
            }
        }
        assertTrue(answered > 0)
    }

    @Test fun theResponderWritesWellFormedAnswers() {
        val out = ByteArrayOutputStream()
        val res = HttpResponder(out, false)
        res.json(404, org.json.JSONObject().put("error", "x"))
        res.flush()
        val reply = readReply(ByteArrayInputStream(out.toByteArray()))
        assertEquals(404, reply.status)
        assertEquals("application/json", reply.headers["content-type"]?.substringBefore(';'))
        assertTrue(reply.text.contains("\"error\""))
        val head = ByteArrayOutputStream()
        HttpResponder(head, true).send(200, mapOf("content-type" to "text/plain"), "hello".toByteArray())
        val headReply = readReply(ByteArrayInputStream(head.toByteArray()))
        assertEquals("5", headReply.headers["content-length"])
        assertEquals(0, headReply.body.size) // HEAD: the length, never the bytes
    }

    @Test fun theLimitedBodyNeverReadsPastItsLength() {
        val source = ByteArrayInputStream("abcdefghij".toByteArray())
        val body = LimitedBody(source, 4)
        assertEquals("abcd", String(body.readAll(100, 1, { 0L })))
        assertEquals(6, source.available())
        try {
            LimitedBody(ByteArrayInputStream(ByteArray(50)), 50).readAll(10, 1, { 0L })
            fail("a body over the cap was read")
        } catch (e: HttpError) {
            assertEquals(413, e.status)
        }
    }

    // ---- WebSocket ----------------------------------------------------------------------------------------------------------

    @Test fun theWebSocketAcceptKeyMatchesRfc6455() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketHandshake.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test fun aHandshakeNeedsEveryPart() {
        fun req(vararg h: Pair<String, String>) = HttpRequest("GET", "/v1/events", "/v1/events", emptyMap(), h.toMap(), 0)
        val good = arrayOf("upgrade" to "websocket", "connection" to "keep-alive, Upgrade", "sec-websocket-version" to "13", "sec-websocket-key" to "dGhlIHNhbXBsZSBub25jZQ==")
        assertEquals("dGhlIHNhbXBsZSBub25jZQ==", WebSocketHandshake.check(req(*good)))
        for (drop in good.indices) {
            val partial = good.filterIndexed { i, _ -> i != drop }.toTypedArray()
            try { WebSocketHandshake.check(req(*partial)); fail("accepted without ${good[drop].first}") } catch (e: HttpError) { assertTrue(e.status == 400 || e.status == 426) }
        }
        try { WebSocketHandshake.check(req(*good.map { if (it.first == "sec-websocket-key") it.first to "AAAA" else it }.toTypedArray())); fail() } catch (e: HttpError) { assertEquals(400, e.status) }
    }

    private fun maskedFrame(opcode: Int, payload: ByteArray): ByteArray {
        val mask = byteArrayOf(1, 2, 3, 4)
        val out = ByteArrayOutputStream()
        out.write(0x80 or opcode)
        out.write(0x80 or payload.size)
        out.write(mask)
        for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
        return out.toByteArray()
    }

    @Test fun textGoesOutUnmaskedAndAPingIsAnsweredWithAPong() {
        val inPipe = PipedOutputStream()
        val sessionIn = PipedInputStream(inPipe)
        val outPipe = PipedInputStream()
        val sessionOut = PipedOutputStream(outPipe)
        val ws = WsSession(sessionIn, sessionOut, null, queueSize = 8, pingEveryMs = 60_000)
        val runner = Thread { ws.run() }.also { it.isDaemon = true; it.start() }
        assertTrue(ws.send("hi"))
        val first = ByteArray(4)
        assertEquals(4, readFully(outPipe, first))
        assertEquals(0x81, first[0].toInt() and 0xff) // FIN + text
        assertEquals(2, first[1].toInt()) // not masked
        assertEquals("hi", String(first, 2, 2))
        inPipe.write(maskedFrame(0x9, "abc".toByteArray())) // ping
        inPipe.flush()
        val pong = ByteArray(5)
        assertEquals(5, readFully(outPipe, pong))
        assertEquals(0x8A, pong[0].toInt() and 0xff)
        assertEquals("abc", String(pong, 2, 3))
        inPipe.write(maskedFrame(0x8, byteArrayOf(0x03, 0xE8.toByte()))) // close 1000
        inPipe.flush()
        runner.join(5000)
        assertFalse(ws.isOpen)
    }

    @Test fun aSlowPeerIsDroppedNotBuffered() {
        val ws = WsSession(PipedInputStream(PipedOutputStream()), ByteArrayOutputStream(), null, queueSize = 2, pingEveryMs = 60_000)
        // the writer is not running: the queue fills and the third message closes the stream
        assertTrue(ws.send("1"))
        assertTrue(ws.send("2"))
        assertFalse(ws.send("3"))
        assertFalse(ws.isOpen)
    }

    @Test fun unmaskedOrOversizeFramesFromThePeerEndTheStream() {
        for (frame in listOf(byteArrayOf(0x81.toByte(), 0x01, 0x41), byteArrayOf(0x82.toByte(), 0x80.toByte(), 1, 2, 3, 4), byteArrayOf(0x89.toByte(), 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte()))) {
            val ws = WsSession(ByteArrayInputStream(frame), ByteArrayOutputStream(), null, queueSize = 4, pingEveryMs = 60_000)
            val t = Thread { ws.run() }.also { it.isDaemon = true; it.start() }
            t.join(5000)
            assertFalse("the stream stayed open for ${frame.toList()}", t.isAlive)
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        val deadline = System.currentTimeMillis() + 5000
        while (off < buf.size && System.currentTimeMillis() < deadline) {
            if (input.available() == 0) { Thread.sleep(5); continue }
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}
