package app.keepfin

import org.json.JSONObject

/** One feature of the in-app guide. The content is content/guide.json, bundled into the APK as the single source. */
data class GuideFeature(
    val id: String,
    val title: String,
    val summary: String,
    val steps: List<String>,
    /** Where it exists; null means everywhere. The Android guide hides features that exclude "android". */
    val platforms: List<String>?,
    val androidNote: String?,
    /** A destination id the "Try it" button can open: downloads, library, library-edit or settings. */
    val tryId: String?,
)

data class GuideSection(val id: String, val title: String, val features: List<GuideFeature>)

data class Guide(val title: String, val intro: String, val sections: List<GuideSection>) {
    val featureCount: Int get() = sections.sumOf { it.features.size }
}

object GuideLogic {
    const val ASSET = "guide.json"
    val TRY_IDS = setOf("downloads", "library", "library-edit", "settings")

    fun parse(json: String): Guide {
        val root = JSONObject(json)
        val sections = root.getJSONArray("sections")
        return Guide(
            title = root.optString("title", "Features & how-to"),
            intro = root.optString("intro"),
            sections = (0 until sections.length()).map { s ->
                val section = sections.getJSONObject(s)
                val features = section.getJSONArray("features")
                GuideSection(
                    section.getString("id"), section.getString("title"),
                    (0 until features.length()).map { f ->
                        val feature = features.getJSONObject(f)
                        val steps = feature.optJSONArray("steps")
                        val platforms = feature.optJSONArray("platforms")
                        GuideFeature(
                            feature.getString("id"), feature.getString("title"), feature.optString("summary"),
                            steps?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                            platforms?.let { a -> (0 until a.length()).map { a.getString(it) } },
                            feature.optJSONObject("notes")?.optString("android")?.takeIf { it.isNotBlank() },
                            feature.optString("try").takeIf { it.isNotBlank() },
                        )
                    },
                )
            },
        )
    }

    /** Drops features for other platforms, and sections left empty. */
    fun forAndroid(guide: Guide): Guide = guide.copy(
        sections = guide.sections.map { s -> s.copy(features = s.features.filter { it.platforms == null || "android" in it.platforms }) }.filter { it.features.isNotEmpty() },
    )

    /** Every word of the query must appear in the title, summary, steps or Android note; sections without a match disappear. */
    fun search(guide: Guide, query: String): Guide {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return guide
        fun matches(f: GuideFeature): Boolean {
            val haystack = (listOf(f.title, f.summary, f.androidNote.orEmpty()) + f.steps).joinToString(" ").lowercase()
            return terms.all { it in haystack }
        }
        return guide.copy(sections = guide.sections.map { s -> s.copy(features = s.features.filter(::matches)) }.filter { it.features.isNotEmpty() })
    }
}
