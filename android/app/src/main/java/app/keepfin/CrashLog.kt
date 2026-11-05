package app.keepfin

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant

/**
 * Keeps the last unexpected stop on this phone so the next start can show it and offer "Copy details".
 * Nothing is sent anywhere. It also remembers the last shared link, so a link that made Keepfin stop is
 * not inspected again by itself when Android reopens the app with the same share.
 */
internal object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val PREFS = "crash-log"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val shared = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("shared", null)
        val text = buildString {
            appendLine("Keepfin ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Time ${Instant.now()}")
            appendLine("Thread ${thread.name}")
            if (shared != null) appendLine("Last shared link $shared")
            appendLine()
            append(error.stackTraceToString())
        }
        File(context.filesDir, FILE).writeText(text)
        // Written synchronously: the process ends right after this handler.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("crashedAfter", shared).commit()
    }

    /** Adds an entry the handler could not write itself (a native crash or ANR found on the next start), after any earlier one. */
    fun append(context: Context, entry: String) {
        val file = File(context.filesDir, FILE)
        val header = "Keepfin ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n"
        file.appendText((if (file.isFile) "\n---\n" else header + "\n") + entry + "\n")
    }

    /** The details of the last unexpected stop that has not been dismissed yet. */
    fun report(context: Context): String? = File(context.filesDir, FILE).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun dismiss(context: Context) {
        File(context.filesDir, FILE).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("crashedAfter").apply()
    }

    /** Remembers a shared link before it is inspected. */
    fun noteShared(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("shared", text).commit()
    }

    /** True when Keepfin stopped after this same link was shared, so it should wait for a tap instead of inspecting again. */
    fun stoppedOn(context: Context, text: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("crashedAfter", null) == text
}
