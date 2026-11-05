package app.keepfin

import app.keepfin.ui.StartPage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spaces v2 pure logic: smart rules, tags, collections, follow, migration, hub order (dev-docs/architecture/SPACES-V2.md "Tests"). */
class SpacesV2Test {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun item(
        id: String, space: String = "music", kind: LibraryItemKind = LibraryItemKind.AUDIO, tags: List<String> = emptyList(), userTags: List<String> = emptyList(),
        artist: String? = null, album: String? = null, favorite: Boolean = false, plays: Int = 0, addedAt: Long = now, sourceUrl: String? = null, jobId: String? = null,
    ) = LibraryItem(id = id, space = space, uri = id, folder = "", kind = kind, title = "Song $id", artist = artist, album = album, size = 1, modifiedAt = 1, addedAt = addedAt,
        favorite = favorite, plays = plays, tags = tags, userTags = userTags, sourceUrl = sourceUrl, jobId = jobId)

    private fun rules(match: SmartMatch, vararg rule: SmartRule, onlyKind: LibraryItemKind? = null, exclude: List<String> = emptyList()) =
        SmartRules(match, rule.toList(), onlyKind, exclude)

    private val ctx = SmartContext(now, mapOf("a" to setOf("col-1"), "b" to setOf("col-1", "col-2")))

    // ---- smart rules ----------------------------------------------------------------------------------

    @Test fun anyMatchesWhenOneRuleHits() {
        val r = rules(SmartMatch.ANY, SmartRule.Values(SmartField.TAG, listOf("jazz")), SmartRule.Favorite)
        assertTrue(SmartLogic.matches(item("a", tags = listOf("jazz")), r, ctx))
        assertTrue(SmartLogic.matches(item("b", favorite = true), r, ctx))
        assertFalse(SmartLogic.matches(item("c", tags = listOf("rock")), r, ctx))
    }

    @Test fun allNeedsEveryRule() {
        val r = rules(SmartMatch.ALL, SmartRule.Values(SmartField.TAG, listOf("jazz")), SmartRule.Unplayed)
        assertTrue(SmartLogic.matches(item("a", tags = listOf("jazz")), r, ctx))
        assertFalse(SmartLogic.matches(item("b", tags = listOf("jazz"), plays = 3), r, ctx))
        assertFalse(SmartLogic.matches(item("c"), r, ctx))
    }

    @Test fun valuesInsideOneRuleAreOred() {
        val r = rules(SmartMatch.ALL, SmartRule.Values(SmartField.TAG, listOf("jazz", "blues")))
        assertTrue(SmartLogic.matches(item("a", tags = listOf("blues")), r, ctx))
        assertTrue(SmartLogic.matches(item("b", userTags = listOf("jazz")), r, ctx))
        assertFalse(SmartLogic.matches(item("c", tags = listOf("pop")), r, ctx))
    }

    @Test fun everyRuleTypeCompares() {
        val i = item("a", space = "gym", artist = "Björk", album = "Homogenic", sourceUrl = "https://www.youtube.com/watch?v=abcdefghijk", addedAt = now - 2 * day)
        fun hit(rule: SmartRule) = SmartLogic.matches(i, rule, ctx)
        assertTrue(hit(SmartRule.Values(SmartField.SPACE, listOf("gym"))))
        assertFalse(hit(SmartRule.Values(SmartField.SPACE, listOf("music"))))
        assertTrue(hit(SmartRule.Values(SmartField.ARTIST, listOf("bjork"))))
        assertTrue(hit(SmartRule.Values(SmartField.ALBUM, listOf("HOMOGENIC"))))
        assertTrue(hit(SmartRule.Values(SmartField.SOURCE, listOf("youtube"))))
        assertFalse(hit(SmartRule.Values(SmartField.SOURCE, listOf("spotify"))))
        assertTrue(hit(SmartRule.Values(SmartField.KIND, listOf("audio"))))
        assertFalse(hit(SmartRule.Values(SmartField.KIND, listOf("video"))))
        assertTrue(hit(SmartRule.Values(SmartField.COLLECTION, listOf("col-1"))))
        assertTrue(SmartLogic.matches(i.copy(id = "b"), SmartRule.Values(SmartField.COLLECTION, listOf("col-2", "other")), ctx))
        assertFalse(hit(SmartRule.Values(SmartField.COLLECTION, listOf("col-2"))))
        assertTrue(hit(SmartRule.AddedWithin(3)))
        assertFalse(hit(SmartRule.AddedWithin(1)))
        assertTrue(hit(SmartRule.Unplayed))
        assertFalse(hit(SmartRule.Favorite))
    }

    @Test fun onlyKindAndExcludedSpacesApplyAfterTheRules() {
        val r = rules(SmartMatch.ANY, SmartRule.Unplayed, onlyKind = LibraryItemKind.VIDEO, exclude = listOf("downloads"))
        assertTrue(SmartLogic.matches(item("a", kind = LibraryItemKind.VIDEO), r, ctx))
        assertFalse(SmartLogic.matches(item("b", kind = LibraryItemKind.AUDIO), r, ctx))
        assertFalse(SmartLogic.matches(item("c", kind = LibraryItemKind.VIDEO, space = "downloads"), r, ctx))
    }

    @Test fun aSmartSpaceWithoutRulesGathersNothing() {
        assertFalse(SmartLogic.matches(item("a"), SmartRules(SmartMatch.ALL), ctx))
        val space = Space("s", "S", SpaceKind.MIXED, "", type = SpaceType.SMART, rules = rules(SmartMatch.ANY, SmartRule.Favorite))
        assertEquals(1, SmartLogic.count(listOf(item("a", favorite = true), item("b")), space, ctx))
    }

    // ---- tags ----------------------------------------------------------------------------------------

    @Test fun tagsAreLowercaseTrimmedDedupedAndFree_ofUrls() {
        val raw = listOf("  Jazz ", "JAZZ", "Bebop", "", "  ", "https://example.com/x", "www.spam.com", "x".repeat(31), "y".repeat(30), "Hip  Hop", "#Soul", null, "example.com/a")
        assertEquals(listOf("jazz", "bebop", "y".repeat(30), "hip hop", "soul"), TagLogic.normalize(raw))
    }

    @Test fun atMostFifteenSourceTags() {
        val tags = TagLogic.normalize((1..40).map { "tag$it" })
        assertEquals(15, tags.size)
        assertEquals("tag1", tags.first())
        assertEquals(40, TagLogic.normalizeUser((1..40).map { "tag$it" }).size)
    }

    @Test fun tagsComeFromTheSourceJsonGenresFirst() {
        assertEquals(listOf("jazz", "live", "music"), TagLogic.fromJson("""{"tags":["Live"],"genres":["Jazz"],"categories":["Music"]}"""))
        assertEquals(emptyList<String>(), TagLogic.fromJson("{}"))
        assertEquals(emptyList<String>(), TagLogic.fromJson("null"))
        assertEquals(emptyList<String>(), TagLogic.fromJson("not json"))
        assertEquals(listOf("pop"), TagLogic.fromJson("""{"genre":"Pop"}"""))
    }

    @Test fun chipsShowTagsUsedTwiceOrMorePlusEveryUserTag() {
        val items = listOf(
            item("a", tags = listOf("jazz", "rare")), item("b", tags = listOf("jazz", "pop")), item("c", tags = listOf("pop"), userTags = listOf("gym")),
        )
        val scoped = TagLogic.inScope(items)
        assertEquals(listOf("jazz", "pop", "gym"), scoped.map { it.tag })
        assertEquals(listOf(2, 2, 1), scoped.map { it.count })
        assertTrue(scoped.single { it.tag == "gym" }.user)
        assertFalse(scoped.any { it.tag == "rare" })
    }

    @Test fun filteringByTagsSupportsAnyAndAll() {
        val items = listOf(item("a", tags = listOf("jazz")), item("b", tags = listOf("jazz", "live")), item("c", userTags = listOf("live")))
        assertEquals(listOf("a", "b"), TagLogic.filter(items, setOf("jazz"), all = false).map { it.id })
        assertEquals(listOf("a", "b", "c"), TagLogic.filter(items, setOf("jazz", "live"), all = false).map { it.id })
        assertEquals(listOf("b"), TagLogic.filter(items, setOf("jazz", "live"), all = true).map { it.id })
    }

    @Test fun userTagsAreAddedAndRemovedCleanly() {
        assertEquals(listOf("gym", "work"), TagLogic.editUserTags(listOf("gym"), listOf(" Work ", "GYM", "http://x.y"), emptyList()))
        assertEquals(listOf("work"), TagLogic.editUserTags(listOf("gym", "work"), emptyList(), listOf("Gym")))
    }

    // ---- collections: identity, new items, gate, order --------------------------------------------------

    @Test fun theSameSourceLinkReusesOneCollection() {
        val a = CollectionLogic.sourceKey("https://www.youtube.com/playlist?list=PLabc&si=zzz")
        assertEquals("https://www.youtube.com/playlist?list=PLabc", a)
        assertEquals(a, CollectionLogic.sourceKey("https://music.youtube.com/playlist?list=PLabc"))
        assertEquals(a, CollectionLogic.sourceKey("https://www.youtube.com/watch?v=abcdefghijk&list=PLabc"))
        assertEquals("https://www.youtube.com/playlist?list=PLabc", CollectionLogic.canonicalSource("https://www.youtube.com/watch?v=abcdefghijk&list=PLabc").url)
        assertEquals("PLabc", CollectionLogic.canonicalSource("https://www.youtube.com/playlist?list=PLabc").id)
        assertEquals("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", CollectionLogic.sourceKey("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=1"))
    }

    @Test fun mixesAreInfiniteAndNeverFollow() {
        assertTrue(CollectionLogic.infinite("https://www.youtube.com/watch?v=abcdefghijk&list=RDabcdefghijk"))
        assertFalse(CollectionLogic.infinite("https://www.youtube.com/playlist?list=PLabc"))
        assertTrue(CollectionLogic.infinite("https://www.youtube.com/playlist?list=PLabc", groupKind = "mix"))
        assertNull(CollectionLogic.defaultFollow(CollectionKind.PLAYLIST, infinite = true))
        val mix = LibraryCollection("m", "Mix", CollectionKind.PLAYLIST, "music", source = CollectionSource("https://www.youtube.com/watch?v=abcdefghijk&list=RDabcdefghijk", "YouTube"),
            follow = Follow(FollowMode.AUTO), createdAt = 1, updatedAt = 1)
        assertFalse(CollectionLogic.followable(mix))
        assertTrue(CollectionLogic.due(listOf(mix), now, metered = false).isEmpty())
    }

    @Test fun defaultFollowIsOffForEveryKindBecauseFollowingIsOptIn() {
        val playlist = CollectionLogic.defaultFollow(CollectionKind.PLAYLIST, false)!!
        assertEquals(FollowMode.OFF, playlist.mode)
        assertEquals(6, playlist.intervalHours); assertTrue(playlist.wifiOnly); assertTrue(playlist.notify); assertEquals(20, playlist.confirmAbove)
        assertEquals(FollowMode.OFF, CollectionLogic.defaultFollow(CollectionKind.CHANNEL, false)!!.mode)
        assertEquals(FollowMode.OFF, CollectionLogic.defaultFollow(CollectionKind.ALBUM, false)!!.mode)
        assertNull(CollectionLogic.defaultFollow(CollectionKind.OWN, false))
    }

    @Test fun newItemsAreTheOnesNeverSeenIncludingDeselectedAndDeleted() {
        // seen holds the downloaded (a), the deselected (b) and the deleted (c) item; only d and e are new, in source order.
        val seen = listOf("youtube:a", "youtube:b", "youtube:c")
        val discovered = listOf("youtube:a", "youtube:b", "youtube:d", "youtube:c", "youtube:e", "youtube:d")
        assertEquals(listOf("youtube:d", "youtube:e"), CollectionLogic.newKeys(discovered, seen))
        assertTrue(CollectionLogic.newKeys(listOf("youtube:a"), seen).isEmpty())
        assertEquals("youtube:abcdefghijk", CollectionLogic.itemKey("https://www.youtube.com/watch?v=abcdefghijk&list=PLabc"))
    }

    @Test fun theConfirmGateHoldsOnlyWhenThereAreMoreThanTheLimit() {
        val twenty = (1..20).map { "k$it" }
        assertEquals(FollowDecision.NothingNew, CollectionLogic.decide(emptyList(), 20))
        assertEquals(FollowDecision.Download(twenty), CollectionLogic.decide(twenty, 20))
        assertEquals(FollowDecision.NeedsReview(21), CollectionLogic.decide(twenty + "k21", 20))
        assertEquals(FollowDecision.Download(twenty + "k21"), CollectionLogic.decide(twenty + "k21", 20, confirmed = true))
        assertEquals(FollowDecision.NeedsReview(1), CollectionLogic.decide(listOf("k1"), 0))
        assertEquals("28 new in Gym Mix · Review", CollectionLogic.reviewText(28, "Gym Mix"))
    }

    @Test fun finishedItemsAreInsertedInSourceOrder() {
        val index = mapOf("a" to 1, "c" to 3, "e" to 5)
        fun at(id: String) = index[id]
        assertEquals(listOf("a", "b", "c", "e"), CollectionLogic.insertInOrder(listOf("a", "c", "e"), "b", 2, ::at))
        assertEquals(listOf("d", "a", "c", "e"), CollectionLogic.insertInOrder(listOf("a", "c", "e"), "d", 0, ::at))
        assertEquals(listOf("a", "c", "e", "z"), CollectionLogic.insertInOrder(listOf("a", "c", "e"), "z", 9, ::at))
        assertEquals(listOf("a", "c", "e", "q"), CollectionLogic.insertInOrder(listOf("a", "c", "e"), "q", null, ::at))
        assertEquals(listOf("a", "c"), CollectionLogic.insertInOrder(listOf("a", "c"), "a", 1, ::at))
        assertEquals(listOf("a", "c", "e"), CollectionLogic.ordered(listOf("e", "a", "c"), ::at))
        assertEquals(listOf("a", "x", "y"), CollectionLogic.ordered(listOf("x", "a", "y"), ::at))
    }

    @Test fun itemsGoneFromTheSourceAreMarkedOnlyWhenTheyHaveASourceKey() {
        val keys = mapOf("i1" to setOf("youtube:a"), "i2" to setOf("youtube:b"), "i3" to emptySet<String>())
        assertEquals(listOf("i2"), CollectionLogic.removedUpstream(listOf("i1", "i2", "i3"), { keys[it].orEmpty() }, setOf("youtube:a")))
    }

    @Test fun followIsDueOnlyWhenAutomaticAndTheIntervalPassed() {
        fun c(id: String, follow: Follow?, source: String = "https://www.youtube.com/playlist?list=PL$id") =
            LibraryCollection(id, id, CollectionKind.PLAYLIST, "music", source = CollectionSource(source, "YouTube"), follow = follow, createdAt = 1, updatedAt = 1)
        val hour = 3_600_000L
        val collections = listOf(
            c("never", Follow(FollowMode.AUTO)),
            c("recent", Follow(FollowMode.AUTO, lastCheckedAt = now - 2 * hour)),
            c("old", Follow(FollowMode.AUTO, lastCheckedAt = now - 7 * hour)),
            c("manual", Follow(FollowMode.MANUAL)),
            c("off", Follow(FollowMode.OFF)),
            c("held", Follow(FollowMode.AUTO, pending = PendingCheck(30, now - hour))),
            c("failed", Follow(FollowMode.AUTO, lastCheckedAt = now - hour, lastError = "Network problem. Check your connection and retry.")),
            c("daily", Follow(FollowMode.AUTO, intervalHours = 24, lastCheckedAt = now - 7 * hour)),
            c("cellular", Follow(FollowMode.AUTO, wifiOnly = false)),
        )
        assertEquals(listOf("never", "old", "cellular"), CollectionLogic.due(collections, now, metered = false).map { it.id })
        // Errors count as a check: "failed" waits for the next interval like everything else.
        assertFalse(CollectionLogic.due(collections, now, metered = false).any { it.id == "failed" || it.id == "recent" || it.id == "held" || it.id == "daily" })
        // Metered network: only collections that may use mobile data.
        assertEquals(listOf("cellular"), CollectionLogic.due(collections, now, metered = true).map { it.id })
    }

    // ---- migration and persistence --------------------------------------------------------------------

    @Test fun playlistsMigrateLosslesslyIntoCollectionsOfKindOwn() {
        val song = LibraryCodec.Rec(item("i1"))
        val old = JSONObject().put("version", 1).put("items", JSONArray().put(LibraryCodec.encodeItem(song)))
            .put("playlists", JSONArray().put(JSONObject().put("id", "pl-1").put("name", "Focus").put("itemIds", JSONArray(listOf("i1", "gone"))).put("createdAt", 5L).put("updatedAt", 9L)))
        val data = LibraryCodec.decodeFile(old.toString())
        val c = data.collections.single()
        assertEquals(LibraryCollection("pl-1", "Focus", CollectionKind.OWN, "music", listOf("i1", "gone"), createdAt = 5L, updatedAt = 9L), c)
        assertFalse("downloaded playlists still have to be migrated", data.groupsMigrated)
        // Written back as v2, they survive another round trip.
        val again = LibraryCodec.decodeFile(LibraryCodec.encodeFile(data.items, data.collections, groupsMigrated = true))
        assertEquals(data.collections, again.collections)
        assertTrue(again.groupsMigrated)
    }

    @Test fun libraryFileRoundTripKeepsEveryNewField() {
        val tagged = item("i1", tags = listOf("jazz", "live"), userTags = listOf("gym"))
        val collection = LibraryCollection(
            "c1", "Gym Mix", CollectionKind.PLAYLIST, "music", listOf("i1", "i2"), "i1",
            CollectionSource("https://www.youtube.com/playlist?list=PLabc", "YouTube", "PLabc"),
            Follow(FollowMode.AUTO, 12, wifiOnly = false, notify = false, confirmAbove = 5, lastCheckedAt = 77L, lastError = "Network problem. Check your connection and retry.", pending = PendingCheck(9, 66L)),
            listOf("youtube:a", "youtube:b"), listOf("i2"), listOf("i1"), 10L, 20L,
        )
        val text = LibraryCodec.encodeFile(listOf(LibraryCodec.Rec(tagged)), listOf(collection), groupsMigrated = false)
        val data = LibraryCodec.decodeFile(text)
        assertEquals(tagged, data.items.single().item)
        assertEquals(listOf(collection), data.collections)
        assertFalse(data.groupsMigrated)
    }

    @Test fun oldItemsWithoutTagsStillLoad() {
        val json = LibraryCodec.encodeItem(LibraryCodec.Rec(item("i1"))).also { it.remove("tags"); it.remove("userTags") }
        val rec = LibraryCodec.decodeItem(json)!!
        assertEquals(emptyList<String>(), rec.item.tags); assertEquals(emptyList<String>(), rec.item.userTags)
    }

    @Test fun jobsKeepTheirCollectionAndTags() {
        val job = Job(
            id = "j", url = "https://www.youtube.com/watch?v=abcdefghijk", title = "t", thumbnail = null, uploader = null, duration = null, provider = null, label = "", kind = MediaKind.AUDIO,
            status = JobStatus.COMPLETED, percent = 100f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null, phase = null,
            error = null, file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 1, completedAt = 2, attempt = 1,
            collectionId = "c1", collectionIndex = 7, tags = listOf("jazz"),
        )
        val back = JobCodec.decodeFile(JobCodec.encodeFile(listOf(JobRecord(job, FormatRequest.Audio(), DownloadOptions())))).single().job
        assertEquals("c1", back.collectionId); assertEquals(7, back.collectionIndex); assertEquals(listOf("jazz"), back.tags)
        val old = JobCodec.decodeFile(JobCodec.encodeFile(listOf(JobRecord(job.copy(collectionId = null, collectionIndex = null, tags = emptyList()), FormatRequest.Audio(), DownloadOptions())))).single().job
        assertNull(old.collectionId); assertNull(old.collectionIndex); assertTrue(old.tags.isEmpty())
    }

    @Test fun theTagsPrintLineIsPartOfEveryRun() {
        val args = ArgsPolicy.output("/tmp/x.%(ext)s")
        assertTrue(args.any { it.name == "--print" && it.value?.startsWith("after_move:${ArgsPolicy.TAGS_PREFIX}") == true })
        assertTrue(args.any { it.name == "--print" && it.value == "after_move:filepath" })
    }

    // ---- spaces: defaults, smart, order ----------------------------------------------------------------

    @Test fun missingSpaceFieldsMigrateToTheirDefaults() {
        val old = JSONObject().put("spaces", JSONArray()
            .put(JSONObject().put("id", "downloads").put("name", "Downloads").put("kind", "mixed").put("folder", "Keepfin"))
            .put(JSONObject().put("id", "music").put("name", "Music").put("kind", "music").put("folder", "Keepfin Music"))
            .put(JSONObject().put("id", "videos").put("name", "Videos").put("kind", "video").put("folder", "Keepfin Videos"))
            .put(JSONObject().put("id", "gym").put("name", "Gym").put("kind", "music").put("folder", "Gym"))
            .put(JSONObject().put("id", "kids").put("name", "Kids").put("kind", "video").put("folder", "Kids")))
        val settings = SpaceLogic.decode(old)
        val byId = settings.spaces.associateBy { it.id }
        assertEquals(SpaceColor.SLATE, byId["downloads"]!!.color); assertEquals(SpaceColor.COBALT, byId["music"]!!.color); assertEquals(SpaceColor.VIOLET, byId["videos"]!!.color)
        assertTrue(listOf("downloads", "music", "videos").all { byId[it]!!.pinned == true })
        assertEquals(false, byId["gym"]!!.pinned)
        assertEquals(SpaceColor.TEAL, byId["gym"]!!.color); assertEquals(SpaceColor.OLIVE, byId["kids"]!!.color)
        assertEquals(SpaceIcon.MUSIC, byId["music"]!!.icon); assertEquals(SpaceIcon.VIDEO, byId["kids"]!!.icon); assertEquals(SpaceIcon.FOLDER, byId["downloads"]!!.icon)
        assertTrue(settings.importTags)
        assertTrue(settings.spaces.filter { it.id != "favorites" }.all { it.type == SpaceType.FOLDER })
    }

    @Test fun favoritesIsAKeptSpaceThatComesBackWhenMissing() {
        val first = SpaceLogic.decode(JSONObject())
        // even an empty list of spaces gets it
        assertTrue(SpaceLogic.decode(JSONObject().put("spaces", JSONArray())).spaces.any { it.id == "favorites" })
        val fav = first.spaces.single { it.id == "favorites" }
        assertEquals(SpaceType.SMART, fav.type); assertEquals(SpaceIcon.HEART, fav.icon); assertEquals(SpaceColor.BERRY, fav.color)
        assertEquals(listOf<SmartRule>(SmartRule.Favorite), fav.rules!!.rules)
        // right after Videos
        assertEquals(listOf("downloads", "music", "videos", "favorites"), first.spaces.map { it.id })
        // removed and saved: it comes back when the settings are read (an older version, or Delete everything)
        val removed = first.copy(spaces = first.spaces.filter { it.id != "favorites" })
        assertEquals(listOf("downloads", "music", "videos", "favorites"), SpaceLogic.decode(SpaceLogic.encode(removed)).spaces.map { it.id })
        // renamed: it keeps its name and is not added a second time
        val renamed = first.copy(spaces = first.spaces.map { if (it.id == "favorites") it.copy(name = "Hearts") else it })
        assertEquals(listOf("Hearts"), SpaceLogic.decode(SpaceLogic.encode(renamed)).spaces.filter { it.id == "favorites" }.map { it.name })
        // a space of the person's own is not Favorites: the kept one is still there by its id
        val own = LibrarySettings(spaces = defaultFolderSpaces() + Space("mine", "Mine", SpaceKind.MIXED, "", type = SpaceType.SMART, rules = SmartRules(rules = listOf(SmartRule.Favorite))))
        assertEquals(listOf("downloads", "music", "videos", "favorites", "mine"), SpaceLogic.withKept(own).spaces.map { it.id })
        // kept spaces are the three built-ins and Favorites; only the three are folders
        assertTrue(fav.kept && !fav.builtin)
        assertTrue(first.spaces.filter { it.kept }.size == 4)
        // sanitize puts a removed one back, so nothing that saves settings can drop it
        assertTrue(SpaceLogic.sanitize(removed).spaces.any { it.id == "favorites" })
    }

    @Test fun twoDevicesThatBothAddFavoritesMakeTheSameSpace() {
        val a = SpaceLogic.withKept(LibrarySettings(spaces = defaultFolderSpaces()))
        val b = SpaceLogic.withKept(LibrarySettings(spaces = defaultFolderSpaces()))
        assertEquals(a.spaces, b.spaces)
        assertEquals("b11c64ea-bcff-5c05-80b4-ab6ed166cc38", app.keepfin.core.library.LibraryMapping.spaceWireId("favorites"))
    }

    @Test fun settingsRoundTripKeepsSmartSpacesAndOptions() {
        val smart = Space("gym", "Gym mix", SpaceKind.MIXED, "", FolderLayout.FLAT, type = SpaceType.SMART, color = SpaceColor.RUST, icon = SpaceIcon.DUMBBELL, pinned = true,
            rules = SmartRules(SmartMatch.ALL, listOf(SmartRule.Values(SmartField.TAG, listOf("gym", "workout")), SmartRule.AddedWithin(30), SmartRule.Favorite, SmartRule.Unplayed),
                LibraryItemKind.AUDIO, listOf("downloads")))
        val settings = LibrarySettings(spaces = defaultSpaces() + smart, importTags = false)
        val back = SpaceLogic.decode(JSONObject(SpaceLogic.encode(SpaceLogic.sanitize(settings)).toString()))
        assertEquals(SpaceLogic.withKept(SpaceLogic.sanitize(settings)), back)
        assertEquals(smart, back.spaces.first { it.id == "gym" })
        assertFalse(back.importTags)
    }

    @Test fun smartSpacesAreNeverATargetOrARoute() {
        val smart = Space("gym", "Gym", SpaceKind.MIXED, "", type = SpaceType.SMART, rules = SmartRules(rules = listOf(SmartRule.Favorite)))
        val settings = SpaceLogic.sanitize(LibrarySettings(spaces = defaultSpaces() + smart, musicRoute = "gym", otherRoute = "gym"))
        assertEquals("music", settings.musicRoute); assertEquals("downloads", settings.otherRoute)
        assertFalse(settings.folderSpaces.any { it.id == "gym" })
        assertEquals(3, settings.folderSpaces.size)
        // Routing never lands in a smart space, even if it is the only candidate that matches by id.
        assertEquals("music", SpaceLogic.route(settings, "https://music.youtube.com/watch?v=abcdefghijk"))
        val clean = SpaceLogic.validate(smart.copy(folder = "Evil/Folder", layout = FolderLayout.ARTIST_ALBUM, skipExisting = true), emptyList())
        assertEquals("", clean.folder); assertFalse(clean.skipExisting)
        // A smart space and a folder space may share a name only if different; a smart space has no folder to clash on.
        assertNotNull(SpaceLogic.validate(smart.copy(id = "other", name = "Other"), listOf(smart)))
    }

    @Test fun homeShowsPinnedSpacesOrTheFirstFiveAndNeverMore() {
        fun s(id: String, pinned: Boolean? = null) = Space(id, id, SpaceKind.MUSIC, "F$id", pinned = pinned)
        val seven = (1..7).map { s("s$it") }
        // Nothing pinned (and none built-in): the first five, with "All spaces · 2 more".
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), SpaceLogic.homeSpaces(seven).map { it.id })
        assertEquals(2, SpaceLogic.moreSpaces(seven))
        val pinned = seven.map { if (it.id in setOf("s6", "s2")) it.copy(pinned = true) else it }
        assertEquals(listOf("s2", "s6"), SpaceLogic.homeSpaces(pinned).map { it.id })
        assertEquals(5, SpaceLogic.moreSpaces(pinned))
        assertEquals(5, SpaceLogic.homeSpaces(seven.map { it.copy(pinned = true) }).size)
        // Built-ins are pinned by default.
        assertEquals(listOf("downloads", "music", "videos", "favorites"), SpaceLogic.homeSpaces(defaultSpaces()).map { it.id })
        // All spaces: folder spaces first, then smart ones.
        val smart = Space("sm", "Sm", SpaceKind.MIXED, "", type = SpaceType.SMART)
        assertEquals(listOf("downloads", "music", "videos", "sm"), SpaceLogic.allSpaces(listOf(defaultSpaces()[0], smart, defaultSpaces()[1], defaultSpaces()[2])).map { it.id })
        assertEquals(listOf("sm", "favorites"), SpaceLogic.allSpaces(listOf(smart) + defaultSpaces()).takeLast(2).map { it.id })
    }

    @Test fun reorderCoversEverySpaceBuiltInsIncluded() {
        val spaces = defaultSpaces() + Space("gym", "Gym", SpaceKind.MUSIC, "Gym")
        assertEquals(listOf("gym", "videos", "downloads", "music", "favorites"), SpaceLogic.reorder(spaces, listOf("gym", "videos")).map { it.id })
        // A stale list (unknown or repeated id) is rejected, like desktop.
        assertTrue(runCatching { SpaceLogic.reorder(spaces, listOf("gym", "nope")) }.isFailure)
        assertTrue(runCatching { SpaceLogic.reorder(spaces, listOf("gym", "videos", "gym")) }.isFailure)
        assertEquals(listOf("music", "downloads", "videos", "favorites", "gym"), SpaceLogic.reorder(spaces, listOf("music")).map { it.id })
        assertEquals(spaces.map { it.id }.toSet(), SpaceLogic.reorder(spaces, listOf("gym")).map { it.id }.toSet())
        assertEquals(SpaceColor.TEAL, SpaceLogic.nextColor(defaultSpaces()))
        assertEquals(SpaceColor.OLIVE, SpaceLogic.nextColor(defaultSpaces() + Space("a", "A", SpaceKind.MUSIC, "A", color = SpaceColor.TEAL)))
    }

    // ---- start page ----------------------------------------------------------------------------------

    @Test fun theStartPageResolvesToATab() {
        assertEquals("downloads", StartPage.DOWNLOADS.resolve("library", true))
        assertEquals("library", StartPage.LIBRARY.resolve(null, true))
        assertEquals("downloads", StartPage.LIBRARY.resolve(null, libraryEnabled = false))
        assertEquals("settings", StartPage.LAST.resolve("settings", true))
        assertEquals("downloads", StartPage.LAST.resolve("library", libraryEnabled = false))
        assertEquals("downloads", StartPage.LAST.resolve(null, true))
        assertEquals(StartPage.LAST, StartPage.of("nonsense"))
        assertEquals(StartPage.LAST, StartPage.of("last"))
    }
}
