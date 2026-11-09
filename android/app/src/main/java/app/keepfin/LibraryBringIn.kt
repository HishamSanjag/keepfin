package app.keepfin

/**
 * A space whose files live outside the Keepfin folder (a linked space, kept where an earlier Keepfin saved them). [files] are how many of its
 * items are still there; [folder] is the folder it would use inside the Keepfin folder (dev-docs/plans/KEEPFIN-FOLDER-MOVE.md, D3 and D4).
 */
data class OutsideSpace(val id: String, val name: String, val files: Int, val folder: String)

/**
 * What "Bring them in" did for one space. [moved] files are in the Keepfin folder now (checked); [kept] stayed where they were because the
 * person chose Leave; [failed] are sentences about files that could not be brought in and stayed where they were. [confirm] is the question
 * Android asks before it removes originals it did not make: the screen shows it, then calls `confirmationResult`.
 */
class BringInResult(val moved: Int, val kept: Int, val failed: List<String>, val confirm: LibraryAccess.NeedsConfirmation?)
