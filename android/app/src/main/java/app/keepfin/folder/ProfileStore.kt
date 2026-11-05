package app.keepfin.folder

import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One profile: a Keepfin folder on this device and this device's actor in it
 * (dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md section 1: `profiles.json` in `filesDir`).
 */
data class Profile(
    val id: String,
    val name: String,
    /** The persisted tree grant of the Keepfin folder (`ACTION_OPEN_DOCUMENT_TREE`). */
    val folderTreeUri: String,
    val libraryId: String,
    val actorId: String,
    val createdAt: String,
)

data class Profiles(val current: String?, val profiles: List<Profile>)

/**
 * Reads and writes `profiles.json` (`{current, profiles:[{id, name, folderTreeUri, libraryId, actorId, createdAt}]}`).
 * A missing, empty or unreadable file means no profile. Writes go through a temp file and rename.
 * Phase 2 has exactly one profile and no profile UI.
 */
class ProfileStore(private val file: File) {
    @Synchronized fun load(): Profiles {
        if (!file.isFile) return Profiles(null, emptyList())
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: java.io.IOException) {
            return Profiles(null, emptyList())
        }
        return try {
            val json = JSONObject(text)
            val array = json.optJSONArray("profiles") ?: JSONArray()
            val list = ArrayList<Profile>()
            for (i in 0 until array.length()) {
                val p = array.optJSONObject(i) ?: continue
                fun s(key: String) = p.optString(key, "").takeIf { it.isNotEmpty() }
                list += Profile(
                    id = s("id") ?: continue, name = s("name") ?: "", folderTreeUri = s("folderTreeUri") ?: continue,
                    libraryId = s("libraryId") ?: continue, actorId = s("actorId") ?: continue, createdAt = s("createdAt") ?: "",
                )
            }
            Profiles(json.optString("current", "").takeIf { it.isNotEmpty() }, list)
        } catch (e: JSONException) {
            Profiles(null, emptyList())
        }
    }

    /** The profile in use, or null. */
    fun current(): Profile? = load().let { all -> all.profiles.firstOrNull { it.id == all.current } }

    @Synchronized fun save(profiles: Profiles) {
        val array = JSONArray()
        for (p in profiles.profiles) {
            array.put(
                JSONObject().put("id", p.id).put("name", p.name).put("folderTreeUri", p.folderTreeUri)
                    .put("libraryId", p.libraryId).put("actorId", p.actorId).put("createdAt", p.createdAt),
            )
        }
        val json = JSONObject().put("current", profiles.current ?: JSONObject.NULL).put("profiles", array)
        writeLocalAtomic(file, json.toString(2).toByteArray(Charsets.UTF_8))
    }

    /** Adds (or replaces, by id) a profile; the first one becomes the current one. */
    @Synchronized fun add(profile: Profile, makeCurrent: Boolean = true) {
        val all = load()
        val rest = all.profiles.filter { it.id != profile.id }
        save(Profiles(if (makeCurrent || all.current == null) profile.id else all.current, rest + profile))
    }

    /** Makes [profileId] the profile in use (it must exist). */
    @Synchronized fun setCurrent(profileId: String) {
        val all = load()
        if (all.profiles.none { it.id == profileId }) return
        save(all.copy(current = profileId))
    }

    /** Forgets a profile; when it was the current one, [fallback] (or the first that is left) takes over. */
    @Synchronized fun remove(profileId: String, fallback: String? = null) {
        val all = load()
        val rest = all.profiles.filter { it.id != profileId }
        val current = if (all.current == profileId) (fallback?.takeIf { f -> rest.any { it.id == f } } ?: rest.firstOrNull()?.id) else all.current
        save(Profiles(current, rest))
    }

    /** The store retired this device's actor (a restored or cloned install): the profile must record the new one. */
    @Synchronized fun updateActor(profileId: String, actorId: String) {
        val all = load()
        save(all.copy(profiles = all.profiles.map { if (it.id == profileId) it.copy(actorId = actorId) else it }))
    }

    /** The directory for the profile's device-only data (outbox, sequence counters, follow state). */
    fun deviceDir(filesDir: File, profileId: String): File = File(File(filesDir, "profiles"), profileId)
}
