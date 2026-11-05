package app.keepfin.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.AppIconChoice
import app.keepfin.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On a device: switching the launcher icon leaves exactly one enabled launcher entry, and reconcile restores the stored choice. */
@RunWith(AndroidJUnit4::class)
class AppIconSwitchTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The Keepfin launcher entries the system sees, by alias name. */
    private fun launcherEntries(): List<String> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(query, 0).map { it.activityInfo.name.removePrefix("app.keepfin") }.sorted()
    }

    @Test fun switchingEnablesExactlyOneLauncherEntryAndReconcileRestoresIt() {
        val icons = AppIcons.load(context)!!
        val before = icons.current()
        try {
            val other = icons.catalog.variants.first { it.id != icons.catalog.mainId }
            val alias = other.accents.first().alias
            assertTrue(icons.choose(AppIconChoice(other.id, icons.catalog.accent)))
            assertEquals(listOf(alias), launcherEntries())
            // An update can put the components back to the manifest defaults: simulate it, then reconcile.
            context.packageManager.setComponentEnabledSetting(ComponentName(context.packageName, "app.keepfin$alias"), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
            context.packageManager.setComponentEnabledSetting(ComponentName(context.packageName, "app.keepfin${icons.catalog.defaultAlias}"), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
            assertEquals(listOf(icons.catalog.defaultAlias), launcherEntries())
            AppIcons.reconcile(context)
            assertEquals(listOf(alias), launcherEntries())
            // Back to the shipped icon.
            assertTrue(icons.choose(icons.catalog.defaultChoice))
            assertEquals(listOf(icons.catalog.defaultAlias), launcherEntries())
        } finally { icons.choose(before) }
    }
}
