package app.keepfin.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

const val A = "a0000000-0000-0000-0000-00000000000a"
const val B = "b0000000-0000-0000-0000-00000000000b"
const val ITEM = "11111111-1111-1111-1111-111111111111"
const val PL = "33333333-3333-3333-3333-333333333333"
const val PLACE = "55555555-5555-5555-5555-555555555555"

fun content(n: Int) = "sha256:" + sha256Hex("content-$n")

fun op(actor: String, seq: Long, wall: Long, kind: String, vararg body: Pair<String, Jv>) =
    Op(OpId(actor, seq), Hlc(wall, 0, actor), kind, mapOf(*body))

fun add(actor: String, seq: Long, wall: Long = seq, fields: Map<String, Jv>? = null): Op {
    val body = LinkedHashMap<String, Jv>()
    body["item"] = JStr(ITEM)
    if (fields != null) body["fields"] = JObj(fields)
    return Op(OpId(actor, seq), Hlc(wall, 0, actor), "item.add", body)
}

fun revise(actor: String, seq: Long, content: String, parents: List<OpId> = emptyList(), wall: Long = seq) =
    op(
        actor, seq, wall, "item.revise",
        "item" to JStr(ITEM), "content" to JStr(content), "size" to JNum(1), "parents" to JArr(parents.map { JStr(it.toString()) }),
    )

fun delete(actor: String, seq: Long, tags: List<OpId>, heads: List<OpId>, wall: Long = seq) =
    op(actor, seq, wall, "item.delete", "item" to JStr(ITEM), "observedTags" to JArr(tags.map { JStr(it.toString()) }), "observedHeads" to JArr(heads.map { JStr(it.toString()) }))

fun setField(actor: String, seq: Long, wall: Long, field: String, value: Jv) =
    op(actor, seq, wall, "item.set", "item" to JStr(ITEM), "field" to JStr(field), "value" to value)

class ScenarioTest {
    @Test
    fun anAddNotObservedByARemoveStaysPresent() {
        val first = add(A, 1)
        val concurrentAdd = add(B, 1)
        val del = delete(A, 2, listOf(first.id), emptyList())
        val s = stateOf(listOf(first, concurrentAdd, del))
        assertTrue(s.isItemPresent(ITEM))
        assertFalse(stateOf(listOf(first, del)).isItemPresent(ITEM))
        assertEquals(s, stateOf(listOf(del, concurrentAdd, first)))
        assertTrue(stateOf(listOf(first, del, add(A, 3))).isItemPresent(ITEM))
    }

    @Test
    fun aRemoveThatArrivesBeforeItsAddStillRemovesIt() {
        val first = add(A, 1)
        val del = delete(A, 2, listOf(first.id), emptyList())
        val early = stateOf(listOf(del))
        assertEquals(ItemStatus.Unknown, early.itemStatus(ITEM))
        val later = early.join(first.toDelta())
        assertEquals(ItemStatus.Deleted, later.itemStatus(ITEM))
        assertEquals(stateOf(listOf(first, del)), later)
        // the same for a set element
        val tag = op(A, 1, 1, "set.add", "set" to JStr("favourites"), "element" to JStr("x"))
        val untag = op(A, 2, 2, "set.remove", "set" to JStr("favourites"), "element" to JStr("x"), "observedTags" to JArr(listOf(JStr(tag.id.toString()))))
        assertEquals(emptyList(), stateOf(listOf(untag, tag)).setMembers("favourites"))
        assertEquals(listOf("x"), stateOf(listOf(tag)).setMembers("favourites"))
    }

    @Test
    fun aReviseConcurrentWithADeleteIsADeleteConflictAndRestoreOrDeleteAgainResolveIt() {
        val addOp = add(A, 1)
        val r1 = revise(A, 2, content(1))
        val del = delete(A, 3, listOf(addOp.id), listOf(r1.id))
        val concurrent = revise(B, 1, content(2), listOf(r1.id))
        val s = stateOf(listOf(addOp, r1, del, concurrent))
        assertEquals(ItemStatus.DeleteConflict, s.itemStatus(ITEM))
        assertEquals(listOf(concurrent.id), s.deleteConflictHeads(ITEM))
        assertFalse(stateOf(listOf(addOp, r1, del)).hasDeleteConflict(ITEM))
        assertEquals(ItemStatus.Deleted, stateOf(listOf(addOp, r1, del)).itemStatus(ITEM))
        // restore: an add with no fields
        val restored = s.join(add(B, 2).toDelta())
        assertEquals(ItemStatus.Present, restored.itemStatus(ITEM))
        // delete again, observing the remaining head
        val again = s.join(delete(B, 2, listOf(addOp.id), listOf(concurrent.id)).toDelta())
        assertEquals(ItemStatus.Deleted, again.itemStatus(ITEM))
        assertFalse(again.hasDeleteConflict(ITEM))
    }

    @Test
    fun aReviseBeforeItsAddIsUnknownNotADeleteConflict() {
        val r = revise(A, 1, content(1))
        val s = stateOf(listOf(r))
        assertEquals(ItemStatus.Unknown, s.itemStatus(ITEM))
        assertFalse(s.hasDeleteConflict(ITEM))
        assertEquals(ItemStatus.Present, s.join(add(B, 1).toDelta()).itemStatus(ITEM))
        // an item with only field writes is also unknown, and not shown
        assertEquals(ItemStatus.Unknown, stateOf(listOf(setField(A, 1, 1, "title", JStr("x")))).itemStatus(ITEM))
    }

    @Test
    fun twoConcurrentRevisionsAreAContentConflictUntilAMergeRevise() {
        val addOp = add(A, 1)
        val base = revise(A, 2, content(1))
        val left = revise(A, 3, content(2), listOf(base.id))
        val right = revise(B, 1, content(3), listOf(base.id))
        val s = stateOf(listOf(addOp, base, left, right))
        assertEquals(listOf(left.id, right.id), s.itemHeads(ITEM))
        assertTrue(s.hasContentConflict(ITEM))
        val merged = s.join(revise(B, 2, "sha256:" + sha256Hex("merged"), listOf(left.id, right.id)).toDelta())
        assertEquals(1, merged.itemHeads(ITEM).size)
        assertFalse(merged.hasContentConflict(ITEM))
        // a resolution whose content equals one of the heads (picking a side) is a new event too
        val pick = s.join(revise(B, 2, content(2), listOf(left.id, right.id)).toDelta())
        assertEquals(1, pick.itemHeads(ITEM).size)
        assertEquals(listOf(content(2)), pick.headContents(ITEM))
        assertEquals(4, pick.items[ITEM]!!.revisions.size)
    }

    @Test
    fun aRevertToEarlierBytesIsANewEventWithOneHeadAndNoCycle() {
        val addOp = add(A, 1)
        val a = revise(A, 2, content(1))
        val b = revise(A, 3, content(2), listOf(a.id))
        val back = revise(A, 4, content(1), listOf(b.id))
        val s = stateOf(listOf(addOp, a, b, back))
        assertEquals(3, s.items[ITEM]!!.revisions.size)
        assertEquals(listOf(back.id), s.itemHeads(ITEM))
        assertEquals(listOf(content(1)), s.headContents(ITEM))
        assertFalse(s.hasRevisionCycle(ITEM))
        assertFalse(s.views().findings.any { it.startsWith("revision-cycle:") })
    }

    @Test
    fun anOrphanRevisionIsAHeadUntilItsParentArrives() {
        val addOp = add(A, 1)
        val root = revise(A, 2, content(1))
        val parent = revise(B, 1, content(2), listOf(root.id))
        val child = revise(B, 2, content(3), listOf(parent.id))
        val early = stateOf(listOf(addOp, root, child))
        assertEquals(listOf(root.id, child.id), early.itemHeads(ITEM))
        val late = early.join(parent.toDelta())
        assertEquals(listOf(child.id), late.itemHeads(ITEM))
    }

    @Test
    fun aRevisionCycleShowsEveryEventAsAHeadAndAFinding() {
        // only a buggy writer can do this: two revises naming each other
        val x = OpId(A, 2)
        val y = OpId(B, 1)
        val addOp = add(A, 1)
        val rx = revise(A, 2, content(1), listOf(y))
        val ry = revise(B, 1, content(2), listOf(x))
        val s = stateOf(listOf(addOp, rx, ry))
        assertEquals(listOf(x, y), s.itemHeads(ITEM))
        assertTrue(s.hasRevisionCycle(ITEM))
        assertTrue("revision-cycle:$ITEM" in s.views().findings)
    }

    @Test
    fun creationFieldsBecomeRegistersAndARestoreDoesNotOverwriteLaterEdits() {
        val created = add(A, 1, 1, mapOf("media" to JStr("audio"), "title" to JStr("First")))
        val s0 = stateOf(listOf(created))
        assertEquals(JStr("audio"), s0.itemField(ITEM, "media"))
        assertEquals(Register(created.hlc, created.id, JStr("First")), s0.items[ITEM]!!.fields["title"])
        val edit = setField(B, 1, 5, "title", JStr("Edited"))
        val del = delete(B, 2, listOf(created.id), emptyList(), 6)
        val restore = add(A, 2, 9)
        val s = stateOf(listOf(created, edit, del, restore))
        assertEquals(ItemStatus.Present, s.itemStatus(ITEM))
        assertEquals(JStr("Edited"), s.itemField(ITEM, "title"))
        // creation fields lose to a later edit even when delivered last
        assertEquals(s, stateOf(listOf(restore, del, edit, created)))
        // media and space are ordinary fields now: item.add ignores nothing else
        assertTrue(add(A, 3, 3, mapOf("space" to JStr("music"))).toDelta().items[ITEM]!!.fields.containsKey("space"))
        // playlists and spaces take creation fields too
        val pl = op(A, 4, 4, "playlist.add", "playlist" to JStr(PL), "fields" to jObj("name" to JStr("Mix"), "kind" to JStr("playlist")))
        assertEquals(JStr("Mix"), stateOf(listOf(pl)).views().playlists[PL]!!.fields["name"])
        val sp = op(A, 5, 5, "space.add", "space" to JStr(PLACE), "fields" to jObj("name" to JStr("Gym")))
        assertEquals(JStr("Gym"), stateOf(listOf(sp)).views().spaces[PLACE]!!.fields["name"])
    }

    @Test
    fun entriesWithEqualKeysAreOrderedByEntryIdAndPlaylistMustBePresent() {
        val e1 = "e1000000-0000-0000-0000-000000000001"
        val e2 = "e2000000-0000-0000-0000-000000000002"
        val e3 = "e3000000-0000-0000-0000-000000000003"
        fun insert(actor: String, seq: Long, entry: String) =
            op(actor, seq, 2, "entry.insert", "playlist" to JStr(PL), "entry" to JStr(entry), "item" to JStr(ITEM), "key" to JStr("a0"))
        val plAdd = op(B, 1, 1, "playlist.add", "playlist" to JStr(PL))
        val ops = listOf(add(A, 1), plAdd, insert(A, 2, e3), insert(B, 2, e1), insert(B, 3, e2))
        assertEquals(listOf(e1, e2, e3), stateOf(ops).playlistSongs(PL).map { it.entry })
        assertEquals(listOf(e1, e2, e3), stateOf(ops.reversed()).playlistSongs(PL).map { it.entry })
        val moved = ops + op(A, 3, 5, "entry.move", "entry" to JStr(e3), "key" to JStr("Zz")) +
            op(A, 4, 6, "entry.remove", "entry" to JStr(e2))
        assertEquals(listOf(e3, e1), stateOf(moved).playlistSongs(PL).map { it.entry })
        // no present playlist, no songs; they come back when it is restored
        val gone = op(B, 4, 7, "playlist.delete", "playlist" to JStr(PL), "observedTags" to JArr(listOf(JStr(plAdd.id.toString()))))
        val deleted = stateOf(ops + gone)
        assertEquals(emptyList(), deleted.playlistSongs(PL))
        val back = deleted.join(op(B, 5, 8, "playlist.add", "playlist" to JStr(PL)).toDelta())
        assertEquals(listOf(e1, e2, e3), back.playlistSongs(PL).map { it.entry })
        assertEquals(emptyList(), stateOf(ops.filter { it != plAdd }).playlistSongs(PL))
    }

    @Test
    fun aFieldEditConcurrentWithADeleteIsNotAConflictAndIsKept() {
        val addOp = add(A, 1)
        val del = delete(A, 2, listOf(addOp.id), emptyList())
        val edit = setField(B, 1, 10, "title", JStr("New"))
        val s = stateOf(listOf(addOp, del, edit))
        assertEquals(ItemStatus.Deleted, s.itemStatus(ITEM))
        assertEquals(JStr("New"), s.itemField(ITEM, "title"))
        val restored = s.join(add(A, 3).toDelta())
        assertTrue(restored.isItemPresent(ITEM))
        assertEquals(JStr("New"), restored.itemField(ITEM, "title"))
    }

    @Test
    fun registersPickTheLargerHlcAndBreakTiesByActor() {
        assertEquals(JStr("late"), stateOf(listOf(setField(A, 1, 5, "title", JStr("early")), setField(B, 1, 9, "title", JStr("late")))).itemField(ITEM, "title"))
        val x = stateOf(listOf(setField(A, 1, 5, "title", JStr("fromA")), setField(B, 1, 5, "title", JStr("fromB"))))
        val y = stateOf(listOf(setField(B, 1, 5, "title", JStr("fromB")), setField(A, 1, 5, "title", JStr("fromA"))))
        assertEquals(x, y)
        assertEquals(JStr("fromB"), x.itemField(ITEM, "title"))
    }

    @Test
    fun spacesBehaveLikePlaylists() {
        val sp = "22222222-2222-2222-2222-222222222222"
        val add = op(A, 1, 1, "space.add", "space" to JStr(sp))
        val name = op(A, 2, 2, "space.set", "space" to JStr(sp), "field" to JStr("name"), "value" to JStr("Gym"))
        val del = op(A, 3, 3, "space.delete", "space" to JStr(sp), "observedTags" to JArr(listOf(JStr("$A:1"))))
        val s1 = stateOf(listOf(add, name))
        assertTrue(s1.isSpacePresent(sp))
        assertEquals(JStr("Gym"), s1.views().spaces[sp]!!.fields["name"])
        assertFalse(stateOf(listOf(add, name, del)).isSpacePresent(sp))
        assertTrue(stateOf(listOf(add, del, op(A, 4, 4, "space.add", "space" to JStr(sp)))).isSpacePresent(sp))
    }

    @Test
    fun placementsAreItemToPlaceRegisters() {
        val p1 = op(A, 1, 1, "item.place", "item" to JStr(ITEM), "place" to JStr(PLACE), "path" to JStr("Music/a.mp3"))
        val p2 = op(B, 1, 2, "item.place", "item" to JStr(ITEM), "place" to JStr(PLACE), "path" to JNull)
        val s = stateOf(listOf(p1, p2))
        assertEquals(JNull, s.placements[ITEM]!![PLACE]!!.value)
        assertEquals(s, stateOf(listOf(p2, p1)))
    }

    // Admission

    @Test
    fun unknownKindsAreKeptAndInvalidBodiesAreReported() {
        val log = OpLog()
        val future = op(A, 1, 1, "future.thing", "x" to JNum(1))
        assertTrue(log.admit(future) is Admission.Kept)
        assertEquals(future.encode(), log.encoded(future.id))
        assertTrue(log.state.items.isEmpty())
        val broken = op(A, 2, 2, "item.set", "item" to JNum(5))
        val r = log.admit(broken)
        assertTrue(r is Admission.Invalid)
        assertTrue("invalid:${broken.id}" in log.findings)
        // both advance the version vector
        assertEquals(mapOf(A to 2L), log.versionVector)
        assertTrue(log.admit(add(A, 3)) is Admission.Admitted)
        assertEquals(mapOf(A to 3L), log.versionVector)
    }

    @Test
    fun admissionRejectsEachBrokenRule() {
        val self = OpId(A, 1)
        val long = "x".repeat(201)
        val cases = listOf(
            "bad item id" to op(A, 1, 1, "item.set", "item" to JStr("not-a-uuid"), "field" to JStr("f"), "value" to JNull),
            "uppercase uuid" to op(A, 1, 1, "item.set", "item" to JStr("AAAAAAAA-0000-0000-0000-000000000000"), "field" to JStr("f"), "value" to JNull),
            "own id in parents" to revise(A, 1, content(1), listOf(self)),
            "own id in observedTags" to delete(A, 1, listOf(self), emptyList()),
            "bad parent id" to op(A, 1, 1, "item.revise", "item" to JStr(ITEM), "content" to JStr(content(1)), "size" to JNum(1), "parents" to JArr(listOf(JStr("nope")))),
            "bad content id" to op(A, 1, 1, "item.revise", "item" to JStr(ITEM), "content" to JStr("sha256:ABC"), "size" to JNum(1), "parents" to JArr(emptyList())),
            "negative size" to op(A, 1, 1, "item.revise", "item" to JStr(ITEM), "content" to JStr(content(1)), "size" to JNum(-1), "parents" to JArr(emptyList())),
            "bad key" to op(A, 1, 1, "entry.move", "entry" to JStr(PL), "key" to JStr("a00")),
            "path with .." to op(A, 1, 1, "item.place", "item" to JStr(ITEM), "place" to JStr(PLACE), "path" to JStr("a/../b")),
            "absolute path" to op(A, 1, 1, "item.place", "item" to JStr(ITEM), "place" to JStr(PLACE), "path" to JStr("/a")),
            "empty path segment" to op(A, 1, 1, "item.place", "item" to JStr(ITEM), "place" to JStr(PLACE), "path" to JStr("a//b")),
            "long path" to op(A, 1, 1, "item.place", "item" to JStr(ITEM), "place" to JStr(PLACE), "path" to JStr("a/".repeat(600))),
            "empty field name" to setField(A, 1, 1, "", JNull),
            "long field name" to setField(A, 1, 1, long, JNull),
            "long set name" to op(A, 1, 1, "set.add", "set" to JStr(long), "element" to JStr("e")),
            "fields not an object" to op(A, 1, 1, "item.add", "item" to JStr(ITEM), "fields" to JArr(emptyList())),
            "missing value" to op(A, 1, 1, "item.set", "item" to JStr(ITEM), "field" to JStr("f")),
            "too many parents" to op(A, 1, 1, "item.revise", "item" to JStr(ITEM), "content" to JStr(content(1)), "size" to JNum(1), "parents" to JArr(List(10_001) { JStr("$B:${it + 1}") })),
            "oversize op" to op(A, 1, 1, "profile.set", "field" to JStr("f"), "value" to JStr("y".repeat(300_000))),
            "too deep" to op(A, 1, 1, "profile.set", "field" to JStr("f"), "value" to (1..40).fold(JNull as Jv) { acc, _ -> JArr(listOf(acc)) }),
            "hlc actor differs" to Op(OpId(A, 1), Hlc(1, 0, B), "profile.set", mapOf("field" to JStr("f"), "value" to JNull)),
        )
        for ((name, o) in cases) {
            val log = OpLog()
            val r = log.admit(o)
            assertTrue(r is Admission.Invalid, "$name should be invalid but was $r")
            assertTrue(log.state.profile.isEmpty() && log.state.items.isEmpty() && log.state.entryKey.isEmpty() && log.state.placements.isEmpty() && log.state.sets.isEmpty(), "$name changed the state")
            assertEquals(mapOf(A to 1L), log.versionVector, name)
            assertTrue("invalid:$self" in log.findings, name)
        }
        // the same shapes done right are admitted, and unknown extra body fields are ignored
        val ok = listOf(
            op(A, 1, 1, "profile.set", "field" to JStr("f"), "value" to JNull, "extra" to JStr("newer semantics")),
            op(A, 2, 1, "item.place", "item" to JStr(ITEM), "place" to JStr(PLACE), "path" to JStr("a/b.mp3")),
            op(A, 3, 1, "entry.move", "entry" to JStr(PL), "key" to JStr("a0V")),
            revise(A, 4, content(1), listOf(OpId(B, 7))),
        )
        val log = OpLog()
        for (o in ok) assertTrue(log.admit(o) is Admission.Admitted, "$o")
    }

    @Test
    fun malformedBytesAreRejectedAndNotStored() {
        val log = OpLog()
        val good = add(A, 1).encode()
        assertTrue(log.admit((" $good").encodeToByteArray()) is Admission.Malformed)
        assertTrue(log.admit("{}".encodeToByteArray()) is Admission.Malformed)
        assertTrue(log.admit(byteArrayOf(0x22, 0xC3.toByte(), 0x28, 0x22)) is Admission.Malformed)
        assertTrue(log.admit(good.encodeToByteArray()) is Admission.Admitted)
        assertTrue(log.admit(good.encodeToByteArray()) is Admission.Duplicate)
        assertEquals(mapOf(A to 1L), log.versionVector)
    }

    @Test
    fun gapsHoldTheVersionVectorAndFillingThemAdvancesIt() {
        val log = OpLog()
        log.admit(add(A, 1))
        log.admit(add(A, 3))
        log.admit(op(A, 4, 4, "future.thing"))
        assertEquals(mapOf(A to 1L), log.versionVector)
        assertEquals(mapOf(A to setOf(3L, 4L)), log.pending)
        assertEquals(2, log.state.items[ITEM]!!.exists.adds.size) // pending ops are applied already
        log.admit(op(A, 2, 2, "item.set", "item" to JNum(1))) // invalid, still counts
        assertEquals(mapOf(A to 4L), log.versionVector)
        assertTrue(log.pending.isEmpty())
        log.admit(add(B, 2))
        assertEquals(mapOf(A to 4L), log.versionVector)
        log.admit(add(B, 1))
        assertEquals(mapOf(A to 4L, B to 2L), log.versionVector)
    }

    @Test
    fun missingRangesComeFromTheVectors() {
        val missing = VersionVectors.missing(mapOf(A to 2L, B to 5L), mapOf(A to 6L, B to 5L, "c" to 3L))
        assertEquals(mapOf(A to 3L..6L, "c" to 1L..3L), missing)
    }

    // Equivocation

    private fun pset(seq: Long, value: String, actor: String = A) =
        op(actor, seq, seq, "profile.set", "field" to JStr("name"), "value" to JStr(value))

    @Test
    fun equivocationSetsAsideTheActorsSuffixAndAllReplicasAgree() {
        val v = (1L..6L).map { pset(it, "v$it") }
        val other = pset(3, "forged")
        val log1 = OpLog()
        for (o in listOf(v[0], v[1], v[2], v[3], other, v[4], v[5])) log1.admit(o)
        val log2 = OpLog()
        for (o in listOf(v[5], v[4], v[3], other, v[2], v[1], v[0])) log2.admit(o)
        for (log in listOf(log1, log2)) {
            assertEquals(mapOf(A to 3L), log.setAside)
            assertEquals(JStr("v2"), log.state.profile["name"]!!.value)
            assertEquals(mapOf(A to 6L), log.versionVector)
            assertTrue("equivocation:$A:3" in log.findings)
            assertEquals(2, log.variants(OpId(A, 3)).size)
        }
        assertEquals(log1.state, log2.state)
        assertEquals(log1.findings, log2.findings)
        assertContentEqualsBytes(encodeCheckpoint(log1.checkpoint(TEST_LIB)), encodeCheckpoint(log2.checkpoint(TEST_LIB)))
        assertEquals(2L, log1.checkpoint(TEST_LIB).coverage[A]) // coverage stops before the set-aside range
        // same bytes again are ignored; a later op in the set-aside range is stored but not applied
        assertTrue(log1.admit(v[2]) is Admission.Duplicate)
        assertTrue(log1.admit(pset(7, "late")) is Admission.SetAside)
        assertEquals(mapOf(A to 7L), log1.versionVector)
        assertEquals(JStr("v2"), log1.state.profile["name"]!!.value)
        // other actors are untouched
        assertTrue(log1.admit(pset(1, "b1", B)) is Admission.Admitted)
        // the first variant alone is applied normally
        val solo = logOf(v)
        assertEquals(JStr("v6"), solo.state.profile["name"]!!.value)
        assertTrue(solo.setAside.isEmpty())
    }

    private fun assertContentEqualsBytes(a: ByteArray, b: ByteArray) {
        assertEquals(a.decodeToString(), b.decodeToString())
    }

    @Test
    fun equivocationBeforeAndAfterACheckpoint() {
        val v = (1L..6L).map { pset(it, "v$it") }
        val other = pset(3, "forged")
        val everything = OpLog()
        for (o in listOf(v[0], v[1], v[2], v[3], v[4], other, v[5])) everything.admit(o)

        // after: a checkpoint below the equivocation, then both variants arrive on top of it
        val prefix = logOf(listOf(v[0], v[1]))
        val cp = prefix.checkpoint(TEST_LIB)
        assertEquals(mapOf(A to 2L), cp.coverage)
        val fromCp = assertNotNull(OpLog.fromCheckpoint(cp))
        assertTrue(fromCp.admit(v[0]) is Admission.Covered)
        for (o in listOf(v[2], v[3], v[4], other, v[5])) fromCp.admit(o)
        assertEquals(everything.state, fromCp.state)
        assertEquals(everything.setAside, fromCp.setAside)
        assertEquals(everything.versionVector, fromCp.versionVector)
        assertContentEqualsBytes(encodeCheckpoint(everything.checkpoint(TEST_LIB)), encodeCheckpoint(fromCp.checkpoint(TEST_LIB)))

        // before: a checkpoint that already covers the equivocation is discarded once it is known
        val wide = logOf(v.take(4)).checkpoint(TEST_LIB)
        assertEquals(4L, wide.coverage[A])
        assertTrue(wide.isDiscardedBy(mapOf(A to 3L)))
        assertEquals(null, OpLog.fromCheckpoint(wide, mapOf(A to 3L)))
        assertTrue(!wide.isDiscardedBy(mapOf(A to 5L)))
        assertTrue(!wide.isDiscardedBy(mapOf(B to 1L)))
        // a log that learned of the equivocation from the store starts with the set-aside range
        val informed = assertNotNull(OpLog.fromCheckpoint(prefix.checkpoint(TEST_LIB), mapOf(A to 3L)))
        informed.admit(v[2])
        assertEquals(JStr("v2"), informed.state.profile["name"]!!.value)
    }

    @Test
    fun aLogStartedFromACheckpointSkipsCoveredOpsAndHoldsTheRest() {
        val ops = (1L..5L).map { pset(it, "v$it") }
        val cp = logOf(ops.take(3)).checkpoint(TEST_LIB)
        val log = assertNotNull(OpLog.fromCheckpoint(cp))
        assertEquals(mapOf(A to 3L), log.versionVector)
        assertTrue(log.admit(ops[1]) is Admission.Covered)
        assertFalse(log.has(ops[1].id))
        assertTrue(log.admit(ops[4]) is Admission.Admitted)
        assertEquals(mapOf(A to setOf(5L)), log.pending)
        assertEquals(mapOf(A to 3L), log.checkpoint(TEST_LIB).coverage) // prefix only: op 5 sits above a gap
        assertEquals(JStr("v3"), log.checkpoint(TEST_LIB).state.profile["name"]!!.value)
        assertTrue(log.admit(ops[3]) is Admission.Admitted)
        assertEquals(mapOf(A to 5L), log.versionVector)
        assertEquals(logOf(ops).state, log.state)
    }

    @Test
    fun opIdsAndUuidsAreChecked() {
        assertEquals(OpId(A, 12), OpId.parse("$A:12"))
        assertEquals(null, OpId.parse("$A:012"))
        assertEquals(null, OpId.parse("$A:0"))
        assertEquals(null, OpId.parse("$A:9007199254740992"))
        assertEquals(OpId(A, MAX_SAFE_INTEGER), OpId.parse("$A:9007199254740991"))
        assertEquals(null, OpId.parse("nocolon"))
        assertEquals(null, OpId.parse("not-a-uuid:5"))
        assertTrue(OpId(A, 9) < OpId(A, 10)) // numeric, not text
        assertTrue(isUuid(A))
        assertFalse(isUuid("NOT-A-UUID"))
        assertTrue(isContentId(content(1)))
        assertFalse(isContentId("sha256:abc"))
    }
}
