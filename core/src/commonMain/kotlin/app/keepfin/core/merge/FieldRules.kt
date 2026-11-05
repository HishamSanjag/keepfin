package app.keepfin.core.merge

/** Each mapping field must explicitly choose a rule; unknown fields fail closed. */
enum class FieldRule { KeepTargetReview, Newer, OwnEntity, Remap, Union, OwnCounter, Placement }
object MergeFieldRules {
    val item: Map<String, FieldRule> = buildMap {
        for (name in listOf("media", "title", "artist", "album", "albumArtist", "track", "disc", "year", "genre",
            "duration", "width", "height", "chapters", "gain", "subtitles", "hasLyrics", "sourceUrl", "sourceId",
            "addedAt", "tags")) put(name, FieldRule.KeepTargetReview)
        put("space", FieldRule.Remap)
        for (name in listOf("lastPlayedAt", "position", "watched")) put(name, FieldRule.Newer)
    }
    val playlist: Map<String, FieldRule> = buildMap {
        for (name in listOf("kind", "collectionKind", "name", "source", "follow", "createdAt", "updatedAt"))
            put(name, FieldRule.OwnEntity)
        for (name in listOf("space", "coverItemId", "rules")) put(name, FieldRule.Remap)
    }
    val space: Map<String, FieldRule> = buildMap {
        for (name in listOf("name", "kind", "folder", "layout", "naming", "skipExisting", "ignore", "scanFolder",
            "type", "color", "icon", "pinned", "linked", "order", "legacyId")) put(name, FieldRule.OwnEntity)
        put("rules", FieldRule.Remap)
    }
    // Model-only properties and nested records are also coverage checked; encoding stays in LibraryMapping.
    val modelExtras: Map<String, Map<String, FieldRule>> = mapOf(
        "LibItem" to mapOf("id" to FieldRule.Remap, "kind" to FieldRule.KeepTargetReview,
            "favorite" to FieldRule.Union, "userTags" to FieldRule.Union,
            "plays" to FieldRule.OwnCounter, "place" to FieldRule.Placement),
        "LibCollection" to mapOf("id" to FieldRule.Remap, "itemIds" to FieldRule.Remap,
            "seen" to FieldRule.Union, "newItemIds" to FieldRule.Remap, "removedItemIds" to FieldRule.Remap),
        "LibSpace" to mapOf("id" to FieldRule.Remap),
        "LibChapter" to listOf("start", "title").associateWith { FieldRule.KeepTargetReview },
        "LibSource" to listOf("url", "provider", "id").associateWith { FieldRule.OwnEntity },
        "LibFollow" to listOf("mode", "intervalHours", "wifiOnly", "notify", "confirmAbove", "pendingCount", "pendingAt")
            .associateWith { FieldRule.OwnEntity },
    )
}
