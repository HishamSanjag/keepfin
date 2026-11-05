package app.keepfin.core.merge

import app.keepfin.core.commonMainRoot
import app.keepfin.core.readTextFile
import kotlin.test.*

/** Enumerates mapping source, not a hand-picked sample, so future optional fields cannot escape this gate. */
class FieldCoverageTest {
    @Test fun everyMappingFieldAndModelPropertyHasANamedRule() {
        val root = commonMainRoot() ?: error("commonMain path not set")
        val mapping = readTextFile(root + "/kotlin/app/keepfin/core/library/LibraryMapping.kt")
        fun fields(start: String, end: String): Set<String> {
            val text = mapping.substringAfter("private fun " + start).substringBefore("private fun " + end)
            val bracket = Regex("""fields\[["]([A-Za-z]+)["]\]""").findAll(text).map { it.groupValues[1] }
            val put = Regex("""fields[.]put[(]["]([A-Za-z]+)["]""").findAll(text).map { it.groupValues[1] }
            return (bracket + put).toSet()
        }
        val groups = listOf(
            Triple("itemSnap", "followWire", MergeFieldRules.item),
            Triple("collectionSnap", "spaceSnap", MergeFieldRules.playlist),
            Triple("spaceSnap", "snapshot", MergeFieldRules.space),
        )
        for ((start, end, rules) in groups) {
            val known = fields(start, end)
            assertTrue(known.isNotEmpty(), "coverage extractor found no fields")
            assertEquals(emptySet(), known - rules.keys, start + " fields need a merge rule")
        }
        val model = readTextFile(root + "/kotlin/app/keepfin/core/library/LibraryModel.kt")
        val classes = listOf("LibItem", "LibCollection", "LibSpace", "LibChapter", "LibSource", "LibFollow")
        for (name in classes) {
            val body = model.substringAfter("data class " + name + "(").substringBefore("\ndata class ")
                .substringBefore("/** A change")
            val properties = Regex("val ([A-Za-z]+):").findAll(body).map { it.groupValues[1] }.toSet()
            val rules = when (name) {
                "LibItem" -> MergeFieldRules.item.keys
                "LibCollection" -> MergeFieldRules.playlist.keys
                "LibSpace" -> MergeFieldRules.space.keys
                else -> emptySet()
            } + MergeFieldRules.modelExtras.getValue(name).keys
            assertTrue(properties.isNotEmpty())
            assertEquals(emptySet(), properties - rules, name + " properties need a merge rule")
        }
    }
}
