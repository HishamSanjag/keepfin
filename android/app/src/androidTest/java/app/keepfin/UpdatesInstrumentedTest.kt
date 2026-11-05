package app.keepfin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdatesInstrumentedTest {
    private fun release(url: String = "https://github.com/HishamSanjag/keepfin/releases/download/v0.3.10/Keepfin-Android-0.3.10.apk") = JSONObject("""{"tag_name":"v0.3.10","draft":false,"prerelease":false,"body":"Fixed downloads","assets":[{"name":"Keepfin-Android-0.3.10.apk","size":100,"browser_download_url":"$url","digest":null}]}""")
    private fun rejected(work: () -> Unit) {
        try { work(); fail("Unsafe update was accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun stableFeedAndAssetIdentity() {
        assertTrue(UpdateRelease.newer("0.3.10", "0.3.9"))
        assertFalse(UpdateRelease.newer("0.3.8", "0.3.9"))
        assertFalse(UpdateRelease.newer("0.3.9", "0.3.9"))
        assertEquals("0.3.10", UpdateRelease.parse(release()).version)
        rejected { UpdateRelease.parse(release("https://evil.example/update.apk")) }
        rejected { UpdateRelease.parse(release().put("tag_name", "android-v0.3.10")) }
        rejected { UpdateRelease.parse(release().put("prerelease", true)) }
        rejected { UpdateRelease.parse(release().put("assets", org.json.JSONArray())) }
    }
    @Test fun installedApkCannotBeUsedAsDowngrade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val updater = AppUpdates(context)
        rejected { updater.validateApk(File(context.applicationInfo.sourceDir), updater.currentVersion) }
        rejected { updater.validateApk(File(context.applicationInfo.sourceDir), "99.0.0") }
    }
}
