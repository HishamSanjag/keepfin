package app.keepfin.appicon

import app.keepfin.AppIconChoice
import app.keepfin.AppIconCatalog
import app.keepfin.AppIcons
import app.keepfin.LauncherStates
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launcher icon switch over a fake component table, and the generated catalogue against the manifest and resources. */
class AppIconsTest {
    private val main = File("src/main")
    private val catalog = AppIconCatalog.parse(File(main, "assets/app-icons.json").readText())

    private class Fake(all: List<String>, enabled: Set<String>) : LauncherStates {
        val table = all.associateWith { it in enabled }.toMutableMap()
        val calls = mutableListOf<Pair<String, Boolean>>()
        override fun isEnabled(alias: String) = table.getValue(alias)
        override fun set(alias: String, enabled: Boolean) { calls += alias to enabled; table[alias] = enabled }
        fun on() = table.filterValues { it }.keys.toList()
    }
    private class Memory(var choice: AppIconChoice? = null) : AppIcons.Store {
        override fun read() = choice
        override fun write(choice: AppIconChoice) { this.choice = choice }
    }

    private fun other() = catalog.variants.first { it.id != catalog.mainId }

    @Test fun theCatalogueListsEveryVariantAndNothingIsHardCoded() {
        val ids = org.json.JSONObject(File("../../assets/icons/variants/index.json").readText()).getJSONArray("variants")
        assertEquals((0 until ids.length()).map { ids.getJSONObject(it).getString("id") }, catalog.variants.map { it.id })
        assertEquals(catalog.defaultAlias, catalog.resolve(catalog.defaultChoice)!!.alias)
    }

    @Test fun everyAliasIsDeclaredDisabledInTheManifestAndHasItsResources() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertEquals("exactly one launcher entry is enabled by default", 1, Regex("""<activity-alias[^>]*android:enabled="true"""").findAll(manifest).count())
        assertFalse("MainActivity itself must not be a launcher entry", Regex("""<activity android:name="\.MainActivity".*?</activity>""", RegexOption.DOT_MATCHES_ALL).find(manifest)!!.value.contains("category.LAUNCHER"))
        for (variant in catalog.variants) for (accent in variant.accents) {
            assertTrue("${accent.alias} declared", manifest.contains("""android:name="${accent.alias}""""))
            if (accent.alias == catalog.defaultAlias) continue
            assertTrue("${accent.alias} disabled", Regex("""<activity-alias android:name="${Regex.escape(accent.alias)}"[^>]*android:enabled="false"""").containsMatchIn(manifest))
            for (folder in listOf("mipmap-anydpi-v26", "mipmap-anydpi-v33")) {
                assertTrue(File(main, "res/$folder/${accent.preview}.xml").exists())
                assertTrue(File(main, "res/$folder/${accent.preview}_round.xml").exists())
            }
            assertTrue(File(main, "res/drawable/${accent.preview}_foreground.xml").exists())
            assertTrue(File(main, "res/drawable/${accent.preview}_monochrome.xml").exists())
        }
    }

    @Test fun choosingADesignEnablesTheNewEntryBeforeDisablingTheOldOne() {
        val fake = Fake(catalog.aliases, setOf(catalog.defaultAlias))
        val icons = AppIcons(catalog, fake, Memory())
        val v = other()
        assertTrue(icons.choose(AppIconChoice(v.id, catalog.accent)))
        assertEquals(listOf(v.accents.first().alias), fake.on())
        assertEquals(listOf(v.accents.first().alias to true, catalog.defaultAlias to false), fake.calls)
        assertEquals(v.id, icons.current().id)
        // Back to the shipped icon.
        assertTrue(icons.choose(catalog.defaultChoice))
        assertEquals(listOf(catalog.defaultAlias), fake.on())
    }

    @Test fun reconcileRestoresTheStoredChoiceAndLeavesAMatchingStateAlone() {
        val v = other()
        val fake = Fake(catalog.aliases, setOf(catalog.defaultAlias)) // an update reset the states
        val icons = AppIcons(catalog, fake, Memory(AppIconChoice(v.id, catalog.accent)))
        assertTrue(icons.reconcile())
        assertEquals(listOf(v.accents.first().alias), fake.on())
        fake.calls.clear()
        assertTrue(icons.reconcile())
        assertTrue("no churn when the state already matches", fake.calls.isEmpty())
    }

    @Test fun reconcileFixesAStateWithSeveralOrNoEntriesEnabled() {
        val fake = Fake(catalog.aliases, catalog.aliases.toSet())
        AppIcons(catalog, fake, Memory()).reconcile()
        assertEquals(listOf(catalog.defaultAlias), fake.on())
        val none = Fake(catalog.aliases, emptySet())
        AppIcons(catalog, none, Memory()).reconcile()
        assertEquals(listOf(catalog.defaultAlias), none.on())
    }

    @Test fun anUnknownStoredChoiceFallsBackToTheShippedIconAndAnUnknownChoiceIsRefused() {
        val fake = Fake(catalog.aliases, setOf(catalog.defaultAlias))
        val icons = AppIcons(catalog, fake, Memory(AppIconChoice("gone", "#000000")))
        assertEquals(catalog.defaultChoice, icons.current())
        assertFalse(icons.choose(AppIconChoice("gone", "#000000")))
        assertTrue(fake.calls.isEmpty())
        assertNotNull(catalog.variant(catalog.mainId))
    }
}
