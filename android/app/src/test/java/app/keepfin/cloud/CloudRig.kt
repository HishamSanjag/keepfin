package app.keepfin.cloud

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The desktop's cloud providers (scripts/test/android-cloud-peer.mjs: its fake Drive, OneDrive, Dropbox and WebDAV, the real published
 * WebDAV server and a fake authorization server), started once per test JVM as a child process on local ports. The tests need `node` and
 * the repository's dependencies (`npm ci`); without them they are skipped and say so.
 */
internal class NodePeer private constructor(private val process: Process, val info: JSONObject) {
    private val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    val control: String get() = "http://127.0.0.1:${info.getInt("control")}"

    fun post(path: String, body: JSONObject = JSONObject()): JSONObject {
        val req = Request.Builder().url(control + path).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body!!.string()
            check(r.isSuccessful) { "peer $path answered ${r.code}: ${text.take(600)}" }
            return JSONObject(text)
        }
    }

    fun getArray(path: String): JSONArray {
        http.newCall(Request.Builder().url(control + path).build()).execute().use { r -> return JSONArray(r.body!!.string()) }
    }

    /** Maps a provider's real URL to the fake that plays it (the same `/__h/<host>` convention the desktop tests use). */
    fun rewrite(url: String): String {
        val u = URI(url)
        val host = u.host ?: return url
        for (key in listOf("drive", "graph", "dropbox")) {
            val t = info.getJSONObject(key)
            if (t.getJSONArray("hosts").strings().contains(host)) {
                val q = if (u.rawQuery != null) "?" + u.rawQuery else ""
                return "http://127.0.0.1:${t.getInt("port")}/__h/$host${u.rawPath ?: ""}$q"
            }
        }
        return url
    }

    /** The authorization server only (it shares api.dropboxapi.com with the Dropbox fake, so token calls use this and file calls use [rewrite]). */
    fun rewriteOAuth(url: String): String {
        val u = URI(url)
        val host = u.host ?: return url
        val t = info.getJSONObject("oauth")
        if (!t.getJSONArray("hosts").strings().contains(host)) return url
        val q = if (u.rawQuery != null) "?" + u.rawQuery else ""
        return "http://127.0.0.1:${t.getInt("port")}/__h/$host${u.rawPath ?: ""}$q"
    }

    /** File calls go to the provider fakes and sign-in calls (token, revoke) to the fake authorization server. */
    fun rewriteAll(url: String): String {
        val u = URI(url)
        val auth = u.rawPath?.endsWith("/token") == true || u.rawPath?.endsWith("/revoke") == true || u.host in setOf("accounts.google.com", "login.microsoftonline.com", "www.dropbox.com", "oauth2.googleapis.com")
        return if (auth) rewriteOAuth(url) else rewrite(url)
    }

    fun fault(target: String, status: Int, times: Int = 1, method: String? = null, path: String? = null, headers: Map<String, String> = emptyMap(), body: JSONObject = JSONObject()) {
        post("/fault", JSONObject().put("target", target).put("status", status).put("times", times).also { o -> method?.let { o.put("method", it) }; path?.let { o.put("path", it) } }.put("headers", JSONObject(headers)).put("body", body))
    }

    fun reset() {
        post("/reset")
    }

    fun log(target: String): List<String> = getArray("/log?target=$target").strings()

    fun base(target: String): String = info.getJSONObject(target).getString("base")

    fun close() {
        runCatching { post("/quit") }
        process.destroy()
    }

    companion object {
        private var shared: NodePeer? = null
        private var failure: String? = null

        @Synchronized
        fun get(): NodePeer? {
            shared?.let { return it }
            if (failure != null) return null
            val script = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "scripts/test/android-cloud-peer.mjs") }.firstOrNull { it.isFile }
            if (script == null) { failure = "scripts/test/android-cloud-peer.mjs not found"; return null }
            return try {
                val pb = ProcessBuilder("node", script.absolutePath).directory(script.parentFile.parentFile).redirectErrorStream(false)
                val p = pb.start()
                val reader = p.inputStream.bufferedReader()
                val deadline = System.currentTimeMillis() + 90_000
                var line: String? = null
                while (System.currentTimeMillis() < deadline) {
                    if (reader.ready()) {
                        line = reader.readLine()
                        if (line == null || line.startsWith("READY ")) break
                    } else {
                        if (!p.isAlive) break
                        Thread.sleep(50)
                    }
                }
                if (line == null || !line.startsWith("READY ")) {
                    p.destroy()
                    failure = "the peer did not start: ${p.errorStream.bufferedReader().readText().take(400)}"
                    return null
                }
                val peer = NodePeer(p, JSONObject(line.removePrefix("READY ")))
                Runtime.getRuntime().addShutdownHook(Thread { peer.close() })
                // keep draining the child's output so it never blocks
                Thread { runCatching { reader.forEachLine { } } }.also { it.isDaemon = true }.start()
                shared = peer
                peer
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                null
            }
        }

        fun require(): NodePeer {
            val p = get()
            Assume.assumeTrue("the desktop cloud peer is not available (${failure ?: "?"}); needs node and npm ci", p != null)
            return p!!
        }
    }
}

/** An account with a fixed token that counts its refreshes. */
internal class TestAccount(override val provider: String, override val id: String = "acct", private val auth: String? = null) : CloudAccount {
    @Volatile var refreshes = 0
    val tokens = listOf("T1", "T2", "T3")
    override fun authorization(): String = auth ?: "Bearer ${tokens[minOf(refreshes, tokens.size - 1)]}"
    override fun refresh(): Boolean { refreshes++; return true }
}

internal val TARGETS = listOf("drive", "graph", "dropbox", "davfake", "davreal")

internal fun providerOf(target: String) = when (target) { "drive" -> "drive"; "graph" -> "graph"; "dropbox" -> "dropbox"; else -> "webdav" }

internal fun sha256Hex(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))

internal fun contentIdOf(b: ByteArray): String = "sha256:" + sha256Hex(b)

internal fun testBytes(n: Int, seed: Int = 1): ByteArray {
    val b = ByteArray(n)
    var x = seed
    for (i in 0 until n) {
        x = (x * 1103515245 + 12345) and 0x7fffffff
        b[i] = (x shr 16).toByte()
    }
    return b
}

/** A software stand-in for the Keystore: AES-GCM with a throwaway key. */
internal class SoftwareBox(private val key: javax.crypto.SecretKey = javax.crypto.KeyGenerator.getInstance("AES").also { it.init(256) }.generateKey(), private val availableFlag: Boolean = true) : SecretBox {
    override fun available() = availableFlag
    override fun encrypt(plain: String): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
    }
    override fun decrypt(blob: ByteArray): String {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
    }
}

/** A client, a transport and a folder over one fake provider with no real waiting. */
internal class Rig(val peer: NodePeer, val target: String, val root: String, val now: () -> Long = System::currentTimeMillis, journalFile: File? = null) {
    val provider = providerOf(target)
    val sleeps = ArrayList<Long>()
    val account = TestAccount(provider, "acct-$target", if (provider == "webdav") "Basic " + java.util.Base64.getEncoder().encodeToString("keepfin:app-password".toByteArray()) else null)
    val http = CloudHttp(account, HttpOptions(rewrite = peer::rewrite, sleep = { synchronized(sleeps) { sleeps.add(it) } }, random = { 0.5 }, baseDelayMs = 100, now = now))
    val journal: UploadJournal = journalFile?.let { FileJournal(it) } ?: MemoryJournal()
    val staging = java.nio.file.Files.createTempDirectory("kf-cloud-stage").toFile()
    val client = CloudClient(http, if (provider == "webdav") peer.base(target) else null, journal, now, staging)
    val transport = CloudTransport(client, root, now)
    val access = CloudFolderAccess(transport, client, staging, now)

    init {
        if (provider == "webdav") client.probeWebdav(".keepfin-probe-${UUID.randomUUID().toString().take(6)}")
    }

    /** A second view of the same replica as another device would have it (its own client and caches, the same journal file when given). */
    fun another(journalFile: File? = null) = Rig(peer, target, root, now, journalFile)

    /** What the desktop's transport sees at this root, with every file downloaded and hashed. */
    fun desktopScan(hash: Boolean = true): JSONObject = peer.post("/desktop/scan", JSONObject().put("target", target).put("root", root).put("hash", hash))

    fun desktopPut(path: String, text: String) = peer.post("/desktop/put", JSONObject().put("target", target).put("root", root).put("path", path).put("text", text))

    fun desktopFiles(hash: Boolean = true): Map<String, JSONObject> {
        val arr = desktopScan(hash).getJSONArray("files")
        return (0 until arr.length()).associate { arr.getJSONObject(it).getString("path") to arr.getJSONObject(it) }
    }

    companion object {
        private val n = AtomicInteger()
        fun uniqueRoot() = "Keepfin/t${System.currentTimeMillis().toString(36)}${n.incrementAndGet()}"
    }
}


