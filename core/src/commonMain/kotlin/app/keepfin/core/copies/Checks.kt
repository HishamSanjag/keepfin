package app.keepfin.core.copies

// Safety checks of the executors (dev-docs/plans/KEEPFIN-COPIES-SPEC.md 3, 5.4, 5.7): the decisions are written once here; each
// platform only gathers the facts (a volume's format, a file's attributes, a stat) and does the file work.

// ---- the 4 GiB limit of FAT ----

/** The largest file a volume of [format] can hold, or null when that is not limited (or the format is not known). */
fun fileSizeLimit(format: String?): Long? {
    val f = format?.trim()?.lowercase() ?: return null
    return when (f) {
        "fat", "fat16", "fat32", "vfat", "msdos" -> 4_294_967_295L
        else -> null
    }
}

/** The words for a file the copy cannot hold: plain, with the reason, saying that nothing was copied. */
fun fileTooLargeWords(name: String, format: String?): String {
    val label = when (format?.trim()?.lowercase()) {
        "fat", "fat16" -> "FAT"
        else -> "FAT32"
    }
    return "$name is larger than 4 GB and this drive is formatted as $label, which cannot hold a file that large. It was not copied; format the drive as exFAT or NTFS to keep it there."
}

// ---- files that are not on the disk (cloud placeholders) ----

private const val ATTR_OFFLINE = 0x1000L
private const val ATTR_RECALL_ON_OPEN = 0x40000L
private const val ATTR_RECALL_ON_DATA_ACCESS = 0x400000L

/**
 * A Windows file whose bytes are not on the disk: reading it would download it (cloud "online-only" files). [attributes] are the
 * file's attribute bits (`OFFLINE`, `RECALL_ON_OPEN`, `RECALL_ON_DATA_ACCESS`).
 */
fun isOnlineOnlyWindows(attributes: Long): Boolean = attributes and (ATTR_OFFLINE or ATTR_RECALL_ON_OPEN or ATTR_RECALL_ON_DATA_ACCESS) != 0L

/** The plain note for a file that is only in the cloud, left alone by a copy. */
fun onlineOnlyWords(name: String): String = "$name is only in the cloud, not on this device. It was left alone; make it available offline and Keepfin will include it."

// ---- a changed clock ----

private const val HOUR_MS = 3_600_000L
private const val SHIFT_TOLERANCE_MS = 2_000L
private const val MIN_SHIFTED = 3
private const val SHIFTED_PERCENT = 80

/**
 * Detects a time zone or daylight saving shift (spec 3): every file whose size is unchanged shows a modified time offset by the
 * same whole number of hours (FAT and exFAT keep local time). Feed it each file that has a baseline and a current stat; when
 * [detected] is true a full audit hashes everything instead of trusting stats.
 */
class ClockShift {
    private var sameSize = 0
    private val byHours = HashMap<Long, Int>()

    /** [baselineMtimeMs] and [currentMtimeMs] of a file whose size is the same as in the baseline. */
    fun add(baselineMtimeMs: Long, currentMtimeMs: Long) {
        sameSize++
        val delta = currentMtimeMs - baselineMtimeMs
        if (delta == 0L) return
        val hours = wholeHours(delta)
        if (hours == 0L) return
        if (kotlin.math.abs(delta - hours * HOUR_MS) > SHIFT_TOLERANCE_MS) return
        byHours[hours] = (byHours[hours] ?: 0) + 1
    }

    private fun wholeHours(delta: Long): Long {
        val half = HOUR_MS / 2
        return if (delta >= 0) (delta + half) / HOUR_MS else -((-delta + half) / HOUR_MS)
    }

    /** The offset in hours that most files share, or 0. */
    val hours: Long get() = byHours.entries.maxByOrNull { it.value }?.key ?: 0L

    val detected: Boolean
        get() {
            val top = byHours.values.maxOrNull() ?: return false
            return top >= MIN_SHIFTED && top.toLong() * 100 >= sameSize.toLong() * SHIFTED_PERCENT
        }
}

// ---- the background audit ----

/** How often a place gets a full audit by itself (spec 3). */
const val AUDIT_EVERY_DAYS = 30

/** True when the last full audit is [AUDIT_EVERY_DAYS] days old or more. A place that has never been audited is not due: its first run hashed everything new. */
fun auditDue(lastAuditAtMs: Long?, nowMs: Long): Boolean = lastAuditAtMs != null && nowMs - lastAuditAtMs >= AUDIT_EVERY_DAYS * 86_400_000L
