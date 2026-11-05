package app.keepfin.ui.preview

import android.content.Context
import app.keepfin.UpdateSnapshot
import app.keepfin.ui.updates.UpdateSource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only [UpdateSource] for fixtures, screenshots and Compose UI tests. No network, no installer. */
class FakeUpdates(
    override val currentVersion: String = app.keepfin.BuildConfig.VERSION_NAME,
    @Volatile var state: UpdateSnapshot = UpdateSnapshot(phase = "current", version = currentVersion, checkedAt = System.currentTimeMillis()),
    @Volatile var notes: String = "# Keepfin\n\n- Bundled release notes for the installed version.",
) : UpdateSource {
    val checks = AtomicInteger()
    val downloads = AtomicInteger()
    val cancels = AtomicInteger()
    @Volatile var latest: UpdateSnapshot? = null
    @Volatile override var channel: app.keepfin.UpdateChannel = app.keepfin.UpdateChannel.STABLE
    override fun applyChannel(next: app.keepfin.UpdateChannel) { channel = next }
    override fun switchChannel(next: app.keepfin.UpdateChannel) { channel = next; check() }
    override fun snapshot() = state
    override fun check() { checks.incrementAndGet(); state = latest ?: if (state.phase in setOf("available", "downloading", "ready")) state else state.copy(phase = "current", checkedAt = System.currentTimeMillis()) }
    override fun download() {
        downloads.incrementAndGet()
        state = state.copy(phase = "downloading", percent = 0)
        Thread {
            for (p in listOf(20, 55, 90, 100)) { Thread.sleep(250); state = state.copy(percent = p) }
            state = state.copy(phase = "ready", percent = 100)
        }.apply { isDaemon = true }.start()
    }
    override fun cancel() { cancels.incrementAndGet(); state = state.copy(phase = "available", message = "Download cancelled.") }
    override fun installFile(): File = File("/dev/null")
    override fun installedNotes(context: Context) = notes

    companion object {
        const val AVAILABLE_NOTES = "# Keepfin v0.4.0\n\n## Windows\n- Faster playlist discovery.\n\n## Android\n- Spaces on the Library home, easier to switch\n- Settings split into simple sections\n- Select all, and smaller items that fit more"
        /** The installed version with its minor number raised, so the fixture is always newer than the build. */
        val NEXT_VERSION: String get() = app.keepfin.BuildConfig.VERSION_NAME.split('.').let { "${it[0]}.${it[1].toInt() + 1}.0" }
        fun available(version: String = NEXT_VERSION) =
            UpdateSnapshot(phase = "available", version = version, notes = AVAILABLE_NOTES, checkedAt = System.currentTimeMillis(), size = 126L * 1024 * 1024)

        /** Entry point used by MainActivity's debug launch extras through reflection. */
        @JvmStatic fun scene(scene: String): FakeUpdates = when (scene) {
            "available" -> FakeUpdates(state = available())
            "downloading" -> FakeUpdates(state = available().copy(phase = "downloading", percent = 46))
            "ready" -> FakeUpdates(state = available().copy(phase = "ready", percent = 100))
            else -> FakeUpdates()
        }
    }
}
