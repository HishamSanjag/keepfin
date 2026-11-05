package app.keepfin.copies

import app.keepfin.core.copies.Generation
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import org.json.JSONObject
import java.io.File
import java.util.Timer
import java.util.TimerTask

/**
 * Generations (spec 7). The copy's `folder.json` holds `generation`, incremented at the start of every run that changes the copy; the
 * relationship records the generation it last committed. Equal proceeds; higher means another computer synced in between (additive
 * only); lower means a restored or cloned drive (every destructive action suspended).
 */
internal object Generations {
    const val FOLDER_JSON = ".keepfin/folder.json"

    fun readFolderJson(copy: FolderAccess): JSONObject? = try {
        val text = copy.read(FOLDER_JSON).toString(Charsets.UTF_8).removePrefix("﻿")
        JSONObject(text)
    } catch (e: FolderError.NotFound) {
        null
    } catch (e: org.json.JSONException) {
        null
    }

    /** The copy's generation, or null when `folder.json` is missing or unreadable. A folder written before generations existed has 0. */
    fun read(copy: FolderAccess): Long? {
        val json = readFolderJson(copy) ?: return null
        return json.optLong("generation", 0L)
    }

    /** The lock token (run id) written with the last generation bump. */
    fun lockToken(copy: FolderAccess): String? = readFolderJson(copy)?.let { if (it.has("lockToken")) it.getString("lockToken") else null }

    /** Increments the generation (together with the run's lock token) and returns the new value. */
    fun bump(copy: FolderAccess, token: String): Long {
        val json = readFolderJson(copy) ?: throw IllegalStateException("The copy has no folder.json.")
        val next = json.optLong("generation", 0L) + 1
        json.put("generation", next).put("lockToken", token)
        copy.replace(FOLDER_JSON, json.toString(2).toByteArray(Charsets.UTF_8))
        return next
    }

    fun compare(copy: Long?, relationship: Long?): Generation = when {
        copy == null -> Generation.Unknown
        relationship == null -> Generation.Equal
        copy == relationship -> Generation.Equal
        copy > relationship -> Generation.Higher
        else -> Generation.Lower
    }
}

/** What this device last committed for one relationship, and whether the first preview was confirmed (device-local `relationship.json`). */
internal class Relationship(val generation: Long?, val previewConfirmed: Boolean) {
    companion object {
        fun file(stateDir: File) = File(stateDir, "relationship.json")

        fun read(stateDir: File): Relationship {
            val f = file(stateDir)
            if (!f.isFile) return Relationship(null, false)
            return try {
                val o = JSONObject(f.readText())
                Relationship(if (o.has("generation") && !o.isNull("generation")) o.getLong("generation") else null, o.optBoolean("previewConfirmed"))
            } catch (e: Exception) {
                Relationship(null, false)
            }
        }

        fun write(stateDir: File, rel: Relationship) {
            val o = JSONObject().put("generation", rel.generation ?: JSONObject.NULL).put("previewConfirmed", rel.previewConfirmed)
            writeFileAtomic(file(stateDir), o.toString().toByteArray(Charsets.UTF_8))
        }
    }
}

/**
 * The lock of a copy (spec 7): `.keepfin/lock`, created exclusively, holding {device, run, heartbeatAt}. The heartbeat is refreshed every
 * 10 s; a lock older than 2 minutes may be taken over after the generation is read again. Lock and generation together give endpoint-wide
 * exclusion across devices.
 */
internal class CopyLock private constructor(private val copy: FolderAccess, private val run: String, private val timer: Timer?, val tookOver: Boolean) {
    companion object {
        const val FILE = ".keepfin/lock"
        const val HEARTBEAT_MS = 10_000L
        const val STALE_MS = 120_000L

        class Holder(val device: String, val run: String, val heartbeatAt: Long)

        sealed class Result {
            class Ok(val lock: CopyLock) : Result()
            class Refused(val reason: String, val holder: Holder?) : Result()
        }

        private fun read(copy: FolderAccess): Holder? = try {
            val o = JSONObject(copy.read(FILE).toString(Charsets.UTF_8))
            Holder(o.optString("device"), o.optString("run"), o.optLong("heartbeatAt"))
        } catch (e: FolderError.NotFound) {
            null
        } catch (e: Exception) {
            Holder("", "", 0) // unreadable: a lock with no heartbeat (stale)
        }

        fun peek(copy: FolderAccess): Holder? = read(copy)

        fun acquire(copy: FolderAccess, device: String, run: String, now: () -> Long = System::currentTimeMillis, heartbeatMs: Long = HEARTBEAT_MS, staleMs: Long = STALE_MS): Result {
            fun body() = JSONObject().put("device", device).put("run", run).put("heartbeatAt", now()).toString().toByteArray(Charsets.UTF_8)
            copy.mkdirs(".keepfin")
            var tookOver = false
            var created = false
            for (attempt in 0 until 3) {
                try {
                    copy.publish(FILE, body())
                    created = true
                    break
                } catch (e: FolderError.NameConflict) {
                    // someone holds it, or it is stale
                }
                val held = read(copy) ?: continue
                // a lock of this same device is a run of an earlier process (one run per place in-process): it is taken over at once
                if (now() - held.heartbeatAt < staleMs && held.run != run && held.device != device) return Result.Refused("held", held)
                // stale: read the generation again, then take it over by moving it aside (only one taker can succeed)
                val before = Generations.read(copy)
                val aside = "$FILE.stale-$run"
                try {
                    copy.rename(FILE, leafName(aside))
                } catch (e: FolderError.NotFound) {
                    continue
                } catch (e: FolderError.NameConflict) {
                    runCatching { copy.remove(aside) }
                    continue
                }
                runCatching { copy.remove(aside) }
                val after = Generations.read(copy)
                if (before != after) return Result.Refused("changed", held)
                tookOver = true
            }
            if (!created) {
                val ours = read(copy)
                return Result.Refused("held", ours)
            }
            val timer = Timer("keepfin-copy-lock", true)
            timer.schedule(object : TimerTask() {
                override fun run() {
                    runCatching { copy.replace(FILE, body()) }
                }
            }, heartbeatMs, heartbeatMs)
            return Result.Ok(CopyLock(copy, run, timer, tookOver))
        }
    }

    private var released = false

    /** A killed process leaves its lock behind: stops the heartbeat only. */
    fun abandon() {
        timer?.cancel()
        released = true
    }

    fun release() {
        if (released) return
        released = true
        timer?.cancel()
        val current = read(copy)
        if (current != null && current.run == run) runCatching { copy.remove(FILE) }
    }
}
