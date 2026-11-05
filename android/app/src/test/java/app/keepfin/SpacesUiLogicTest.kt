package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacesUiLogicTest {
    private fun item(id: String, space: String = "music", kind: LibraryItemKind = LibraryItemKind.AUDIO) =
        LibraryItem(id = id, space = space, uri = id, folder = "", kind = kind, title = id, size = 1, modifiedAt = 0, addedAt = 0)

    private fun collection(id: String, ids: List<String>, space: String = "music", kind: CollectionKind = CollectionKind.PLAYLIST, follow: Follow? = null,
                           new: List<String> = emptyList(), url: String? = "https://www.youtube.com/playlist?list=PL1") =
        LibraryCollection(id, id, kind, space, ids, source = url?.let { CollectionSource(it, "youtube", "PL1") }, follow = follow, newItemIds = new, createdAt = 0, updatedAt = 0)

    @Test fun everyPaletteColorKeepsIconAndTextReadableInLightAndDark() {
        assertEquals(SpaceColor.entries.toSet(), SpaceTones.palettes.keys)
        val lightInk = 0xFF192333; val lightMuted = 0xFF536178; val darkInk = 0xFFEFF3FA; val darkMuted = 0xFFA9B6CC
        for ((color, palette) in SpaceTones.palettes) {
            val name = SpaceTones.label(color)
            assertTrue("$name light icon", SpaceTones.contrast(palette.light.accent, palette.light.onAccent) >= 4.5)
            assertTrue("$name dark icon", SpaceTones.contrast(palette.dark.accent, palette.dark.onAccent) >= 4.5)
            assertTrue("$name light tile on card", SpaceTones.contrast(palette.light.accent, palette.light.container) >= 3.0)
            assertTrue("$name dark tile on card", SpaceTones.contrast(palette.dark.accent, palette.dark.container) >= 3.0)
            assertTrue("$name light text", SpaceTones.contrast(lightInk, palette.light.container) >= 4.5)
            assertTrue("$name light secondary text", SpaceTones.contrast(lightMuted, palette.light.container) >= 4.5)
            assertTrue("$name dark text", SpaceTones.contrast(darkInk, palette.dark.container) >= 4.5)
            assertTrue("$name dark secondary text", SpaceTones.contrast(darkMuted, palette.dark.container) >= 4.5)
        }
        assertEquals(8, SpaceColor.entries.map(SpaceTones::label).toSet().size)
        assertEquals(10, SpaceIcon.entries.map(SpaceTones::label).toSet().size)
    }

    @Test fun ruleDescriptionsReadLikeTheMockups() {
        assertEquals("Tag is lofi or chill", RuleText.describe(SmartRule.Values(SmartField.TAG, listOf("lofi", "chill"))))
        assertEquals("Tag is a, b or c", RuleText.describe(SmartRule.Values(SmartField.TAG, listOf("a", "b", "c"))))
        assertEquals("Kind is Songs", RuleText.describe(SmartRule.Values(SmartField.KIND, listOf("audio"))))
        assertEquals("Space is Workout", RuleText.describe(SmartRule.Values(SmartField.SPACE, listOf("w")) , { f, v -> if (f == SmartField.SPACE && v == "w") "Workout" else null }))
        assertEquals("Added in the last 30 days", RuleText.describe(SmartRule.AddedWithin(30)))
        assertEquals("Added in the last 3 months", RuleText.describe(SmartRule.AddedWithin(90)))
        assertEquals("Favorite", RuleText.describe(SmartRule.Favorite))
        assertEquals("Not played yet", RuleText.describe(SmartRule.Unplayed))
        val rules = SmartRules(SmartMatch.ALL, listOf(SmartRule.Values(SmartField.TAG, listOf("pop")), SmartRule.Favorite), LibraryItemKind.AUDIO)
        assertEquals("Tag is pop and Favorite · only songs", RuleText.describe(rules))
        assertEquals("Tag is pop or Favorite", RuleText.describe(rules.copy(match = SmartMatch.ANY, onlyKind = null)))
        assertEquals("No rules yet", RuleText.describe(SmartRules()))
    }

    @Test fun countsUseTheRightNoun() {
        assertEquals("1 song", RuleText.countLabel(listOf(item("a"))))
        assertEquals("2 videos", RuleText.countLabel(listOf(item("a", kind = LibraryItemKind.VIDEO), item("b", kind = LibraryItemKind.VIDEO))))
        assertEquals("2 items", RuleText.countLabel(listOf(item("a"), item("b", kind = LibraryItemKind.VIDEO))))
        assertEquals("0 items", RuleText.countLabel(emptyList()))
        val items = List(51) { item("m$it") } + List(7) { item("d$it", "downloads") }
        assertEquals("From Music (51) and Downloads (7)", RuleText.origin(items) { mapOf("music" to "Music", "downloads" to "Downloads")[it] })
    }

    @Test fun hubReorderMovesOneStepOrByDragAndStopsAtTheEnds() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "c", "b", "d"), HubLogic.move(ids, "b", forward = true))
        assertEquals(listOf("b", "a", "c", "d"), HubLogic.move(ids, "b", forward = false))
        assertEquals(ids, HubLogic.move(ids, "a", forward = false))
        assertEquals(ids, HubLogic.move(ids, "d", forward = true))
        assertEquals(ids, HubLogic.move(ids, "zzz", forward = true))
        assertEquals(listOf("d", "a", "b", "c"), HubLogic.drag(ids, 3, 0))
        assertEquals(listOf("b", "c", "a", "d"), HubLogic.drag(ids, 0, 2))
        assertEquals(ids, HubLogic.drag(ids, 1, 9))
    }

    @Test fun newBadgeCountsItemsThatArrivedAndShelfPutsNewsFirst() {
        val cs = listOf(
            collection("gym", listOf("1", "2", "3"), new = listOf("1", "2", "3")),
            collection("held", listOf("4"), follow = Follow(mode = FollowMode.AUTO, pending = PendingCheck(28, 0))),
            collection("quiet", listOf("5"), space = "videos"),
        )
        assertEquals(3, HubLogic.newCount(cs))
        assertEquals(3, HubLogic.newInSpace(cs, "music"))
        assertEquals(0, HubLogic.newInSpace(cs, "videos"))
        assertEquals(listOf("gym", "held", "quiet"), HubLogic.shelf(cs).map { it.id })
    }

    @Test fun followStatusLinesFollowTheMockup() {
        val now = 10_000_000_000L
        val following = collection("gym", listOf("1"), follow = Follow(mode = FollowMode.AUTO, intervalHours = 6, lastCheckedAt = now - 12 * 60_000), new = listOf("1", "2", "3"))
        assertEquals("Following · every 6 hours", CollectionText.followLine(following))
        assertEquals("Checked 12 min ago · 3 new songs added", CollectionText.checkedLine(following, "song", now))
        assertEquals("Playlist · 1 song · following, checked 12 min ago", CollectionText.rowSubtitle(following, 1, "song", now))
        assertEquals("Following · every day", CollectionText.followLine(following.copy(follow = following.follow!!.copy(intervalHours = 24))))
        assertEquals("Following · every week", CollectionText.followLine(following.copy(follow = following.follow!!.copy(intervalHours = 168))))
        assertEquals("Checks when you tap Check now", CollectionText.followLine(following.copy(follow = Follow(mode = FollowMode.MANUAL))))
        assertEquals("Not following", CollectionText.followLine(following.copy(follow = Follow(mode = FollowMode.OFF))))
        assertEquals("Not checked yet", CollectionText.checkedLine(following.copy(follow = Follow(mode = FollowMode.AUTO)), "song", now))
        assertEquals("Offline", CollectionText.checkedLine(following.copy(follow = Follow(mode = FollowMode.AUTO, lastError = "Offline")), "song", now))
        // Your own lists and radio mixes are never followed.
        val own = collection("mine", listOf("1", "2"), kind = CollectionKind.OWN, url = null)
        assertEquals(null, CollectionText.followLine(own))
        assertEquals("Yours · 2 songs", CollectionText.rowSubtitle(own, 2, "song", now))
        assertEquals(null, CollectionText.followLine(collection("mix", listOf("1"), url = "https://www.youtube.com/watch?v=x&list=RDabc")))
        assertEquals("Album · 11 songs", CollectionText.rowSubtitle(collection("album", List(11) { "$it" }, kind = CollectionKind.ALBUM, follow = Follow(mode = FollowMode.OFF)), 11, "song", now))
    }

    @Test fun agoTextHasMinutesHoursAndYesterday() {
        val now = 100_000_000_000L
        assertEquals("just now", CollectionText.ago(now - 20_000, now))
        assertEquals("5 min ago", CollectionText.ago(now - 5 * 60_000, now))
        assertEquals("3 h ago", CollectionText.ago(now - 3 * 3_600_000, now))
        assertEquals("yesterday", CollectionText.ago(now - 30 * 3_600_000, now))
        assertEquals("4 days ago", CollectionText.ago(now - 4 * 86_400_000, now))
    }

    @Test fun addPickerCountsOnlyWhatWouldMoveAndDedupes() {
        val items = listOf(item("a"), item("b"), item("c", "workout"), item("d", "downloads"))
        val cs = listOf(collection("gym", listOf("a", "b", "c")), collection("other", listOf("b", "d")))
        // c is already in Workout, b is picked twice (alone and through two collections).
        assertEquals(listOf("b", "a", "d"), AddPicker.resolve(items, cs, setOf("b"), setOf("gym", "other"), "workout"))
        assertEquals(emptyList<String>(), AddPicker.resolve(items, cs, setOf("c"), emptySet(), "workout"))
        assertEquals("2 collections · 3 songs", AddPicker.summary(2, listOf(item("a"), item("b"), item("c"))))
        assertEquals("1 collection", AddPicker.summary(1, emptyList()))
        assertEquals("2 songs", AddPicker.summary(0, listOf(item("a"), item("b"))))
    }

    @Test fun tagFilterBecomesSmartRules() {
        val any = TagSmart.rules(setOf("pop", "arabic"), all = false, onlyKind = LibraryItemKind.AUDIO)
        assertEquals(SmartMatch.ANY, any.match)
        assertEquals(listOf(SmartRule.Values(SmartField.TAG, listOf("arabic", "pop"))), any.rules)
        assertEquals(LibraryItemKind.AUDIO, any.onlyKind)
        val all = TagSmart.rules(setOf("pop", "arabic"), all = true)
        assertEquals(SmartMatch.ALL, all.match)
        assertEquals(2, all.rules.size)
        assertEquals("Arabic + Pop", TagSmart.suggestedName(setOf("pop", "arabic")))
        // The rules gather exactly what the filter showed.
        val songs = listOf(item("a").copy(tags = listOf("pop", "arabic")), item("b").copy(tags = listOf("pop")), item("c").copy(userTags = listOf("arabic")))
        val ctx = SmartContext()
        assertEquals(listOf("a"), SmartLogic.evaluate(songs, all, ctx).map { it.id })
        assertEquals(listOf("a", "b", "c"), SmartLogic.evaluate(songs, any, ctx).map { it.id })
        assertEquals(TagLogic.filter(songs, setOf("pop", "arabic"), all = true).map { it.id }, SmartLogic.evaluate(songs, all, ctx).map { it.id })
    }

    @Test fun aSpacesTagFilterSavedAsSmartKeepsItsVideoSongsAndStaysInTheSpace() {
        // as on Windows: these tags in this space, any kind (a video kept in Music is a song there)
        val pool = listOf(
            item("song").copy(tags = listOf("pop")),
            item("clip", kind = LibraryItemKind.VIDEO).copy(tags = listOf("pop")),
            item("elsewhere", space = "videos", kind = LibraryItemKind.VIDEO).copy(tags = listOf("pop")),
            item("other").copy(tags = listOf("rock")),
        )
        val ctx = SmartContext()
        assertEquals(listOf("song", "clip"), SmartLogic.evaluate(pool, TagSmart.rules(setOf("pop"), all = false, space = "music"), ctx).map { it.id })
        assertEquals(listOf("song", "clip", "other"), SmartLogic.evaluate(pool, TagSmart.rules(setOf("pop", "rock"), all = false, space = "music"), ctx).map { it.id })
        assertEquals(emptyList<String>(), SmartLogic.evaluate(pool, TagSmart.rules(setOf("pop", "rock"), all = true, space = "music"), ctx).map { it.id })
    }

    @Test fun collectionListSortsAndFiltersByKindAndShelfOrderStaysTheDefault() {
        fun c(id: String, name: String, kind: CollectionKind, updated: Long) = LibraryCollection(id, name, kind, "music", emptyList(), createdAt = 0, updatedAt = updated)
        val shelf = listOf(c("a", "Zed", CollectionKind.PLAYLIST, 5), c("b", "Alpha", CollectionKind.ALBUM, 9), c("c", "Mine", CollectionKind.OWN, 1), c("d", "Beta", CollectionKind.PLAYLIST, 7))
        assertEquals(listOf("a", "b", "c", "d"), CollectionListLogic.apply(shelf, CollectionSort.SHELF, CollectionFilter.ALL).map { it.id })
        assertEquals(listOf("b", "d", "a", "c"), CollectionListLogic.apply(shelf, CollectionSort.UPDATED, CollectionFilter.ALL).map { it.id })
        assertEquals(listOf("b", "d", "c", "a"), CollectionListLogic.apply(shelf, CollectionSort.NAME, CollectionFilter.ALL).map { it.id })
        assertEquals("Playlists, then albums, then your own; by name inside a kind", listOf("d", "a", "b", "c"), CollectionListLogic.apply(shelf, CollectionSort.KIND, CollectionFilter.ALL).map { it.id })
        assertEquals(listOf("a", "d"), CollectionListLogic.apply(shelf, CollectionSort.SHELF, CollectionFilter.PLAYLISTS).map { it.id })
        assertEquals(listOf("b"), CollectionListLogic.apply(shelf, CollectionSort.NAME, CollectionFilter.ALBUMS).map { it.id })
        assertEquals(listOf("c"), CollectionListLogic.apply(shelf, CollectionSort.NAME, CollectionFilter.YOURS).map { it.id })
        assertTrue(CollectionListLogic.apply(shelf, CollectionSort.NAME, CollectionFilter.CHANNELS).isEmpty())
    }
}
