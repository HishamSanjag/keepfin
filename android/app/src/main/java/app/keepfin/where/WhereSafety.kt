package app.keepfin.where

import app.keepfin.core.copies.OnePlace
import app.keepfin.core.copies.Safety
import app.keepfin.core.copies.librarySafety

// The sentence at the top of Storage: is every file kept in more than one place? The words and the rule are the shared core's
// (core/copies/Safety.kt), the same as on Windows; this only hands it what the phone knows.

object WhereSafety {
    /** Null until the places are known. */
    fun of(ui: WhereFilesUi): Safety? {
        if (!ui.ready) return null
        val only = ui.singles.map { OnePlace(kindWord(it.kind), it.count.toInt(), it.place, it.onThisPhone) }
        return librarySafety(ui.places.size, ui.files.toInt(), only, WhereWords.THIS_PHONE)
    }

    /** The spaces Keep a copy… starts with chosen: the ones with files only on this phone, none when this phone is the only place. */
    fun spacesToKeep(ui: WhereFilesUi): Set<String> =
        if (ui.places.size <= 1) emptySet() else ui.singles.filter { it.onThisPhone }.flatMap { it.spaceIds }.toSet()

    private fun kindWord(kind: SingleKind) = when (kind) { SingleKind.SONGS -> "songs"; SingleKind.VIDEOS -> "videos"; SingleKind.OTHER -> "other" }
}
