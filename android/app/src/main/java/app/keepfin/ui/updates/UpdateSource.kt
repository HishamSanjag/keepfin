package app.keepfin.ui.updates

import android.content.Context
import app.keepfin.AppUpdates
import app.keepfin.UpdateChannel
import app.keepfin.UpdateRelease
import app.keepfin.UpdateSnapshot
import app.keepfin.KeepfinApplication
import java.io.File

/**
 * What the UI needs from the app updater. [AppUpdates] is the production implementation and keeps its API as-is;
 * tests and the debug fixture path supply their own.
 */
interface UpdateSource {
    val currentVersion: String
    fun snapshot(): UpdateSnapshot
    fun check()
    fun download()
    fun cancel()
    fun installFile(): File
    /** The channel this source checks. Sources without channels (fixtures) stay on stable. */
    val channel: UpdateChannel get() = UpdateChannel.STABLE
    /** Sets the channel at startup without checking. */
    fun applyChannel(next: UpdateChannel) {}
    /** Switches channel; a fresh check follows. */
    fun switchChannel(next: UpdateChannel) {}
    /** Release notes bundled at build time for the installed version. */
    fun installedNotes(context: Context): String
}

class AppUpdatesSource(private val updates: AppUpdates) : UpdateSource {
    override val currentVersion get() = updates.currentVersion
    override fun snapshot() = updates.snapshot()
    override fun check() = updates.check()
    override fun download() = updates.download()
    override fun cancel() = updates.cancel()
    override fun installFile() = updates.installFile()
    override val channel get() = updates.channel
    override fun applyChannel(next: UpdateChannel) = updates.applyChannel(next)
    override fun switchChannel(next: UpdateChannel) = updates.switchChannel(next)
    override fun installedNotes(context: Context) = app.keepfin.ui.settings.ReleaseNotes.bundledNotes(context)
}

fun productionUpdates(context: Context): UpdateSource = AppUpdatesSource((context.applicationContext as KeepfinApplication).updates)

/** True when a newer release is known (available, downloading or ready to install). */
fun UpdateSnapshot.hasUpdate(current: String): Boolean =
    phase in setOf("available", "downloading", "ready") && version.isNotEmpty() && isNewer(version, current)

fun isNewer(candidate: String, current: String): Boolean = runCatching { UpdateRelease.newer(candidate, current) }.getOrDefault(false)
