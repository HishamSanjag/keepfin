package app.keepfin

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import app.keepfin.core.versions.Releases
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors

data class UpdateSnapshot(
    val phase: String = "idle",
    val version: String = "",
    val notes: String = "",
    val percent: Int = 0,
    val message: String = "",
    val checkedAt: Long = 0,
    /** Size of the release APK in bytes, 0 when unknown. */
    val size: Long = 0,
)
/** Which releases of the app itself are offered. Missing or unknown values mean [STABLE]. */
enum class UpdateChannel(val wire: String) {
    STABLE("stable"), BETA("beta");
    companion object { fun of(value: String?) = entries.firstOrNull { it.wire == value } ?: STABLE }
}

data class AndroidRelease(val version: String, val notes: String, val url: String, val size: Long, val digest: String)

/** Public, stable combined releases only. No maintainer credentials travel with the APK. */
object UpdateRelease {
    const val PAGE = "https://github.com/HishamSanjag/keepfin/releases/latest"
    const val FEED = "https://api.github.com/repos/HishamSanjag/keepfin/releases/latest"
    const val BETA_FEED = "https://api.github.com/repos/HishamSanjag/keepfin/releases?per_page=10"
    /** Release list responses (ten releases with notes) stay well below this. */
    const val MAX_RESPONSE = 2_000_000
    fun feed(channel: UpdateChannel) = if (channel == UpdateChannel.BETA) BETA_FEED else FEED
    /** True for X.Y.Z-beta.N builds. */
    fun isPrerelease(version: String) = Releases.isPrerelease(version)
    private const val PREFIX = "https://github.com/HishamSanjag/keepfin/releases/download/"
    /** X.Y.Z or X.Y.Z-beta.N (the shared core orders them): a beta is older than its final release and newer than the release before it. */
    fun newer(candidate: String, current: String): Boolean {
        require(Releases.isValid(candidate) && Releases.isValid(current)) { "Unsupported release version." }
        return Releases.compare(candidate, current) > 0
    }
    /** Newest valid release of a `releases?per_page=N` list for [channel], or null. Drafts, malformed tags and releases without a valid APK are skipped. */
    fun select(list: JSONArray, channel: UpdateChannel): AndroidRelease? {
        val candidates = (0 until list.length()).mapNotNull { index ->
            val json = list.optJSONObject(index) ?: return@mapNotNull null
            runCatching { parse(json, channel) }.getOrNull()
        }
        return candidates.fold(null as AndroidRelease?) { best, next -> if (best == null || newer(next.version, best.version)) next else best }
    }

    fun parse(json: JSONObject, channel: UpdateChannel = UpdateChannel.STABLE): AndroidRelease {
        val beta = channel == UpdateChannel.BETA
        require(!json.optBoolean("draft") && (beta || !json.optBoolean("prerelease"))) { "No stable release available." }
        val tag = json.getString("tag_name")
        val pattern = if (beta) "v[0-9]+\\.[0-9]+\\.[0-9]+(-beta\\.[0-9]+)?" else "v[0-9]+\\.[0-9]+\\.[0-9]+"
        require(Regex(pattern).matches(tag)) { "Publish a combined vX.Y.Z release first." }
        val version = tag.removePrefix("v")
        val assets = json.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name") == "Keepfin-Android-$version.apk" }
        require(matches.size == 1) { "This release has no signed Android APK." }
        val asset = matches.single()
        val url = asset.getString("browser_download_url")
        val uri = URI(url)
        require(url.startsWith("$PREFIX$tag/") && uri.userInfo == null && uri.fragment == null && uri.query == null) { "Unexpected APK download address." }
        val size = asset.getLong("size")
        require(size in 1..400_000_000) { "Unexpected APK size." }
        val digest = if (asset.isNull("digest")) "" else asset.optString("digest")
        require(digest.isEmpty() || Regex("sha256:[a-fA-F0-9]{64}").matches(digest)) { "Unsupported APK digest." }
        return AndroidRelease(version, json.optString("body").take(20000), url, size, digest.lowercase())
    }
}

class AppUpdates(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var state = UpdateSnapshot()
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var busy = false
    @Volatile var channel: UpdateChannel = UpdateChannel.STABLE
        private set
    private var release: AndroidRelease? = null
    private val directory = File(context.cacheDir, "updates")
    private val apk = File(directory, "update.apk")
    val currentVersion: String get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    fun snapshot() = state
    @Synchronized private fun submit(work: () -> Unit) {
        if (busy) return
        busy = true
        cancelled = false
        worker.execute {
            try { work() }
            catch (e: Exception) {
                state = state.copy(phase = if (cancelled && release != null) "available" else "error", message = if (cancelled) "Download cancelled." else e.message ?: "Update failed. Try again.")
            } finally { connection?.disconnect(); connection = null; busy = false }
        }
    }
    /** Startup: adopt the saved channel; the launch check follows on its own. */
    fun applyChannel(next: UpdateChannel) { channel = next }
    /**
     * Switches the channel and starts a fresh check. A download or ready APK of the other channel is dropped:
     * only what the current channel offers can be installed. Never offers a lower version (see [check]).
     */
    fun switchChannel(next: UpdateChannel) {
        if (next == channel) return
        channel = next
        if (busy) cancel()
        worker.execute {
            release = null
            apk.delete()
            state = UpdateSnapshot()
            check()
        }
    }
    fun check() {
        if (state.phase == "ready" || state.phase == "downloading") return
        submit {
            val asked = channel
            state = UpdateSnapshot(phase = "checking", checkedAt = System.currentTimeMillis())
            release = null
            val body = open(UpdateRelease.feed(asked)).inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer); if (n < 0) break
                    require(out.size() + n <= UpdateRelease.MAX_RESPONSE) { "Release response is too large." }
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
            val found = if (asked == UpdateChannel.BETA) UpdateRelease.select(JSONArray(body), asked) ?: error("No beta release available.") else UpdateRelease.parse(JSONObject(body))
            // The channel changed while the request ran: that answer belongs to the other channel.
            if (asked != channel) return@submit
            release = found
            val newer = UpdateRelease.newer(found.version, currentVersion)
            // Never a downgrade: Android cannot install a lower versionCode and data migrations are not backward safe.
            val note = if (!newer && asked == UpdateChannel.STABLE && UpdateRelease.isPrerelease(currentVersion))
                "You're on beta $currentVersion. You'll move to the next stable version automatically when it's released. Your library and settings stay." else ""
            state = state.copy(phase = if (newer) "available" else "current", version = found.version, notes = found.notes, size = found.size, message = note)
        }
    }
    fun download() {
        if (state.phase !in setOf("available", "error") || release == null) return
        submit {
            val found = release ?: error("Check for updates first.")
            require(UpdateRelease.newer(found.version, currentVersion)) { "This is not a newer release." }
            directory.mkdirs()
            apk.delete()
            val partial = File(directory, "update.part")
            try {
                state = state.copy(phase = "downloading", percent = 0, message = "")
                val hash = MessageDigest.getInstance("SHA-256")
                var total = 0L
                open(found.url).inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            check(!cancelled) { "Download cancelled." }
                            val n = input.read(buffer); if (n < 0) break
                            total += n
                            require(total <= found.size) { "APK exceeds its advertised size." }
                            output.write(buffer, 0, n); hash.update(buffer, 0, n)
                            state = state.copy(percent = (total * 100 / found.size).toInt())
                        }
                    }
                }
                require(total == found.size) { "APK download is incomplete. Retry the download." }
                val digest = "sha256:" + hash.digest().joinToString("") { "%02x".format(it) }
                require(found.digest.isEmpty() || found.digest == digest) { "APK checksum mismatch. Download rejected." }
                validateApk(partial, found.version)
                check(!cancelled) { "Download cancelled." }
                require(partial.renameTo(apk)) { "Cannot save the update. Check free space." }
                state = state.copy(phase = "ready", percent = 100)
                (context.applicationContext as? KeepfinApplication)?.activity?.recordUpdateReady(found.version)
            } finally { partial.delete() }
        }
    }
    fun cancel() { cancelled = true; connection?.disconnect() }
    fun installFile(): File {
        require(state.phase == "ready" && apk.isFile) { "Download the update first." }
        validateApk(apk, state.version)
        return apk
    }
    @Suppress("DEPRECATION")
    internal fun validateApk(file: File, expectedVersion: String) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK.")
        require(candidate.packageName == context.packageName && candidate.versionName == expectedVersion) { "APK identity does not match this release." }
        fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        require(code(candidate) > code(installed)) { "APK versionCode must increase before publishing." }
        fun signers(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures?.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }?.toSet() ?: emptySet()
        }
        val trusted = signers(installed)
        require(trusted.isNotEmpty() && trusted == signers(candidate)) { "Signing key differs from your installed app. Use releases signed with the same key; debug APKs cannot upgrade to the release key." }
    }
    private fun open(address: String): HttpURLConnection {
        var url = address
        repeat(6) {
            check(!cancelled) { "Download cancelled." }
            val uri = URI(url)
            require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) && uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "Unexpected update server." }
            val c = uri.toURL().openConnection() as HttpURLConnection
            connection = c
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Keepfin/$currentVersion")
            if (uri.host == "api.github.com") c.setRequestProperty("Accept", "application/vnd.github+json")
            val status = c.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                val next = c.getHeaderField("Location") ?: error("Missing update redirect.")
                url = uri.resolve(next).toString(); c.disconnect()
            } else {
                require(status == 200) { when (status) {
                    404 -> "No public release found. Publish a combined release and make its downloads public."
                    403, 429 -> "GitHub rate limit or access restriction. Try again later."
                    else -> "Update server returned HTTP $status. Try again."
                } }
                return c
            }
        }
        error("Too many download redirects.")
    }
}
