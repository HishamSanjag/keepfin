package app.keepfin

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.time.Instant

/** One earlier process end as Android recorded it (the part of [ApplicationExitInfo] that matters here). */
internal data class ExitRecord(val timestamp: Long, val reason: Int, val description: String?, val trace: String?)

/**
 * Native crashes and ANRs end the process without running [CrashLog]'s handler, so the app would just close and
 * nothing would be recorded. Android keeps the reason for every process end; on the next start this reads the new
 * ones and adds them to the crash log.
 */
internal object ExitReports {
    private const val PREFS = "crash-log"
    private const val LAST_SEEN = "lastExitSeen"
    private const val TRACE_LIMIT = 64 * 1024

    // Kotlin/Java crashes are already written by CrashLog's own handler; these are the exits it cannot see.
    private val names = mapOf(
        ApplicationExitInfo.REASON_CRASH_NATIVE to "Native crash",
        ApplicationExitInfo.REASON_ANR to "Not responding (ANR)",
    )

    /** The exits newer than [lastSeen] that are crashes or ANRs, oldest first. */
    fun fresh(records: List<ExitRecord>, lastSeen: Long): List<ExitRecord> =
        records.filter { it.timestamp > lastSeen && it.reason in names }.sortedBy { it.timestamp }

    /** How one exit reads in the crash log. */
    fun entry(record: ExitRecord): String = buildString {
        appendLine("${names[record.reason] ?: "Exit ${record.reason}"} at ${Instant.ofEpochMilli(record.timestamp)}")
        record.description?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        record.trace?.takeIf { it.isNotBlank() }?.let { appendLine(); append(it.trimEnd()) }
    }

    /** Reads the exits since the last start and appends them to the crash log. Never throws. Call off the main thread. */
    fun record(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val records = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10).map { info ->
                val withTrace = info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || info.reason == ApplicationExitInfo.REASON_ANR
                val trace = if (withTrace) runCatching { info.traceInputStream?.use { it.readNBytes(TRACE_LIMIT).toString(Charsets.UTF_8) } }.getOrNull() else null
                ExitRecord(info.timestamp, info.reason, info.description, trace)
            }
            val lastSeen = prefs.getLong(LAST_SEEN, -1L)
            val newest = records.maxOfOrNull { it.timestamp } ?: return@runCatching
            // A fresh install has no earlier history of its own to report: only remember where it stands.
            if (lastSeen >= 0) fresh(records, lastSeen).forEach { CrashLog.append(context, entry(it)) }
            prefs.edit().putLong(LAST_SEEN, newest).apply()
        }
    }
}
