package app.keepfin.copies

import app.keepfin.core.copies.Action
import app.keepfin.core.copies.companionField
import app.keepfin.core.copies.companionOfEntry
import app.keepfin.core.copies.companionValue
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JNull
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.folder.OpDraft
import app.keepfin.library.repo.CopiesBackend
import java.time.Instant

/** Library operations for the imports and revisions a run needs (dev-docs/plans/KEEPFIN-COPIES-SPEC.md 5.8 rule 3). */
internal class LibraryHost(private val b: CopiesBackend, private val lib: CopiesLibrary, private val newId: () -> String, private val now: () -> Long) : MainHost {
    private fun jstr(s: String) = JStr(s)

    private fun headOp(itemId: String, content: String): String? = b.read { it.headsOf(itemId) }.firstOrNull { it.content == content }?.op

    private fun spaceFor(destPath: String): String? {
        val folders = b.read { it.spaceFolders() }
        var best: String? = null
        var bestLen = -1
        for ((id, folder) in folders) if (folder.isEmpty() || destPath == folder || destPath.startsWith("$folder/")) if (folder.length > bestLen) { best = id; bestLen = folder.length }
        return best ?: folders.keys.firstOrNull()
    }

    private fun kindOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3", "m4a", "flac", "ogg", "opus", "wav", "aac" -> "audio"
            "mp4", "mkv", "webm", "mov", "avi", "m4v" -> "video"
            "jpg", "jpeg", "png", "webp", "gif" -> "image"
            else -> "other"
        }
    }

    /** Adds a new item for a file already in the Keepfin folder at [path]. */
    fun addItem(path: String, content: String, size: Long, space: String?): String {
        val itemId = newId()
        val base = leafName(path)
        val dot = base.lastIndexOf('.')
        val fields = linkedMapOf<String, Jv>("media" to jstr(kindOf(base)), "title" to jstr(if (dot > 0) base.substring(0, dot) else base))
        (space ?: spaceFor(path))?.let { fields["space"] = jstr(it) }
        lib.apply(
            listOf(
                OpDraft("item.add", mapOf("item" to jstr(itemId), "fields" to JObj(fields))),
                OpDraft("item.place", mapOf("item" to jstr(itemId), "place" to jstr(b.replicaId), "path" to jstr(path))),
                OpDraft("item.revise", mapOf("item" to jstr(itemId), "content" to jstr(content), "size" to JNum(size), "parents" to JArr(emptyList()))),
            ),
        )
        return itemId
    }

    override fun importNew(action: Action, stagedPath: String, destPath: String): ImportOutcome {
        val content = action.contentId ?: throw IllegalStateException("This file has no content id.")
        val size = b.folder.stat(stagedPath)?.size ?: 0
        b.folder.rename(stagedPath, leafName(destPath))
        // a music video file (`Song.video.mp4`) new in a copy belongs to the song beside it: it is that song's companion, never an item of its own
        if (action.itemId == null) app.keepfin.core.copies.videoCompanionStem(leafName(destPath))?.let { stem ->
            val song = b.read { it.songBeside(destPath.substringBeforeLast('/', ""), stem) }
            val suffix = song?.let { app.keepfin.core.copies.companionSuffix(leafName(it.second), leafName(destPath)) }
            if (song != null && suffix != null) {
                lib.apply(listOf(OpDraft("item.set", mapOf("item" to jstr(song.first), "field" to jstr(companionField(suffix)), "value" to companionValue(content, size)))))
                return ImportOutcome(song.first, app.keepfin.core.copies.COMPANION_REVISION + suffix, destPath)
            }
        }
        var itemId = action.itemId
        // a lyrics or subtitle file changed on both sides: the copy's version is kept beside it under its conflict name, as a file
        if (itemId != null && companionOfEntry(action.revisionOp) != null) return ImportOutcome(itemId, action.revisionOp, destPath)
        if (itemId != null) {
            // another head of an existing item (a conflict): concurrent with the head already there
            lib.apply(listOf(OpDraft("item.revise", mapOf("item" to jstr(itemId), "content" to jstr(content), "size" to JNum(size), "parents" to JArr(emptyList())))))
        } else itemId = addItem(destPath, content, size, null)
        return ImportOutcome(itemId, headOp(itemId, content), destPath)
    }

    /** Moves the bytes of a main file that is about to be replaced or deleted into the Keepfin trash: no version is lost. */
    private fun archiveMain(path: String, why: String) {
        val day = Instant.ofEpochMilli(now()).toString().take(10)
        var target = ".keepfin/trash/copies/$day/$why-${leafName(path)}"
        var n = 2
        while (b.folder.stat(target) != null) target = ".keepfin/trash/copies/$day/$why-$n-${leafName(path)}".also { n++ }
        Versions.moveVerified(b.folder, path, target, null)
    }

    private fun revise(action: Action, stagedPath: String, restoring: Boolean): String? {
        val itemId = action.itemId
        val content = action.contentId
        if (itemId == null || content == null) throw IllegalStateException("This change names no item.")
        val size = b.folder.stat(stagedPath)?.size ?: 0
        if (b.folder.stat(action.path) != null) archiveMain(action.path, if (restoring) "restored" else "replaced")
        b.folder.rename(stagedPath, leafName(action.path))
        // the item's lyrics, subtitle or info file: its record changes, never the item's own content
        companionOfEntry(action.revisionOp)?.let { suffix ->
            lib.apply(listOf(OpDraft("item.set", mapOf("item" to jstr(itemId), "field" to jstr(companionField(suffix)), "value" to companionValue(content, size)))))
            return action.revisionOp
        }
        val parents = lib.observed(itemId).second
        lib.apply(listOf(OpDraft("item.revise", mapOf("item" to jstr(itemId), "content" to jstr(content), "size" to JNum(size), "parents" to JArr(parents.map { jstr(it) })))))
        return headOp(itemId, content)
    }

    override fun reviseFromCopy(action: Action, stagedPath: String) = revise(action, stagedPath, false)
    override fun restoreEditedDeleted(action: Action, stagedPath: String) = revise(action, stagedPath, true)

    override fun deleteItem(action: Action) {
        val itemId = action.itemId ?: throw IllegalStateException("This change names no item.")
        // lyrics deleted on the copy and confirmed: the item keeps its other files and stays in the library
        companionOfEntry(action.revisionOp)?.let { suffix ->
            lib.apply(listOf(OpDraft("item.set", mapOf("item" to jstr(itemId), "field" to jstr(companionField(suffix)), "value" to JNull))))
            if (b.folder.stat(action.path) != null) archiveMain(action.path, "deleted")
            return
        }
        val (tags, heads) = lib.observed(itemId)
        lib.apply(listOf(OpDraft("item.delete", mapOf("item" to jstr(itemId), "observedTags" to JArr(tags.map { jstr(it) }), "observedHeads" to JArr(heads.map { jstr(it) })))))
        if (b.folder.stat(action.path) != null) archiveMain(action.path, "deleted")
    }
}

