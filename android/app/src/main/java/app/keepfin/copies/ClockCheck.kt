package app.keepfin.copies

import app.keepfin.core.copies.ClockShift
import java.io.File

/**
 * A time zone or daylight saving change moves the modified time of every file on a card that keeps local time (FAT, exFAT) by whole
 * hours, so "equal stats mean unchanged" cannot be trusted that day (spec 3). Reads the baseline and the copy's inventory side by side
 * (both sorted by [rules]); the shared core decides whether what it sees is such a shift ([ClockShift]). Port of `copies/clock.ts`.
 */
internal object ClockCheck {
    class Result(val detected: Boolean, val hours: Long)

    fun detect(baselineFile: File, inventoryFile: File, rules: RulesId): Result {
        val shift = ClockShift()
        val base = LineReader(baselineFile)
        val inv = LineReader(inventoryFile)
        try {
            var b = if (base.hasNext()) Codec.baselineOf(base.next()) else null
            var c = if (inv.hasNext()) Codec.invOf(inv.next()) else null
            while (b != null && c != null) {
                val kb = rules.key(b.path)
                val kc = rules.key(c.path)
                when {
                    kb < kc -> b = if (base.hasNext()) Codec.baselineOf(base.next()) else null
                    kb > kc -> c = if (inv.hasNext()) Codec.invOf(inv.next()) else null
                    else -> {
                        if (b.copy.size == c.size) shift.add(b.copy.mtimeMs, c.mtimeMs)
                        b = if (base.hasNext()) Codec.baselineOf(base.next()) else null
                        c = if (inv.hasNext()) Codec.invOf(inv.next()) else null
                    }
                }
            }
        } finally {
            base.close()
            inv.close()
        }
        return Result(shift.detected, shift.hours)
    }
}
