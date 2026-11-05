package app.keepfin.ui.copies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.cloud.CloudAccountUi
import app.keepfin.cloud.CloudActions
import app.keepfin.cloud.CloudProviderUi
import app.keepfin.cloud.CloudUi
import app.keepfin.cloud.FoundReplicaUi
import app.keepfin.cloud.RevokeNoteUi
import app.keepfin.cloud.SignInStep
import app.keepfin.cloud.WebdavConnected
import app.keepfin.copies.CloudCardUi
import app.keepfin.copies.CopiesUi
import app.keepfin.copies.CopyCardUi
import app.keepfin.copies.CopyDraft
import app.keepfin.copies.CopyFolderCheck
import app.keepfin.copies.CopyHoldsUi
import app.keepfin.copies.CopyPreview
import app.keepfin.copies.CopyStatus
import app.keepfin.copies.CopyVersionUi
import app.keepfin.copies.ReviewAction
import app.keepfin.core.copies.CopyMode
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The cloud parts of Settings > Storage > Copies over fake state: the account card and the Add a copy > A cloud account steps. */
@RunWith(AndroidJUnit4::class)
class CloudUiTest {
    @get:Rule val rule = createComposeRule()

    private class FakeCloud(accounts: List<CloudAccountUi>) : CloudActions {
        val calls = ArrayList<String>()
        override val state: StateFlow<CloudUi> = MutableStateFlow(
            CloudUi(
                listOf(CloudProviderUi("drive", "Google Drive", true, "Keepfin sees only the files it creates itself."), CloudProviderUi("graph", "OneDrive", false, "Not available in this build."), CloudProviderUi("dropbox", "Dropbox", true, "x"), CloudProviderUi("webdav", "WebDAV", true, "A folder on your own server")),
                accounts,
            ),
        )
        override fun refresh() {}
        override fun beginSignIn(provider: String, accountId: String?): SignInStep { calls += "begin:$provider:$accountId"; return SignInStep.Failed("no browser here") }
        override fun finishSignIn(provider: String, accountId: String?, result: android.content.Intent?) = SignInStep.Cancelled
        override fun connectWebdav(url: String, user: String, password: String): WebdavConnected { calls += "webdav:$url:$user"; throw IllegalStateException("The server did not accept this user name and password.") }
        override fun disconnect(accountId: String) { calls += "disconnect:$accountId" }
        override fun removeAccess(accountId: String) = RevokeNoteUi(false, "n", null)
        override fun setMobileData(accountId: String, allowed: Boolean) { calls += "mobile:$accountId:$allowed" }
        override fun find(accountId: String, webdavFolder: String?) = emptyList<FoundReplicaUi>()
        override fun addressFor(accountId: String, folder: String) = "keepfin-cloud://$accountId/$folder"
        override fun folderNameFor(name: String) = name
    }

    private class Actions(override val cloud: CloudActions?) : CopiesActions {
        override fun sync(placeId: String) {}
        override fun pause(placeId: String) {}
        override fun syncAll() {}
        override fun remove(placeId: String) {}
        override fun resolve(reviewId: String, action: ReviewAction) {}
        override fun edit(placeId: String, name: String?, holds: CopyHoldsUi?, everyHours: Int?, auto: Boolean?, exports: Triple<Boolean, Boolean, Boolean>?) {}
        override fun reconnect(placeId: String, treeUri: String): String? = null
        override fun checkFolder(treeUri: String) = CopyFolderCheck(true, null, "Cloud")
        override fun preview(draft: CopyDraft, previousId: String?) = CopyPreview("p", "ready")
        override fun cancelPreview(placeId: String) {}
        override fun add(placeId: String) {}
        override fun versions(placeId: String) = emptyList<CopyVersionUi>()
        override fun restore(placeId: String, entryId: String) {}
        override fun cleanup(placeId: String) {}
    }

    private val ok = CloudAccountUi("a1", "drive", "Google Drive", "tester@example.com", false, false, 1, 4_000_000_000, 15_000_000_000)
    private val out = CloudAccountUi("a2", "dropbox", "Dropbox", "Dropbox", true, true, 1)

    private fun card(c: CloudCardUi?, status: CopyStatus, text: String?, connected: Boolean) =
        CopyCardUi("p1", "Cloud backup", CopyMode.Backup, CopyHoldsUi(true), "Google Drive · tester / Keepfin / Library", connected, status, text, null, 0, 0, 0, null, null, cloud = c)

    private fun show(ui: CopiesUi, actions: CopiesActions, dark: Boolean = false, scale: Float = 1f) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                KeepfinTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { Column(Modifier.verticalScroll(rememberScrollState())) { CopiesContent(ui, actions) } }
            }
        }
    }

    @Test fun anAccountCardShowsWhoSpaceAndTheSwitches() {
        val cloud = FakeCloud(listOf(ok, out))
        val ui = CopiesUi(true, listOf(card(CloudCardUi("a1", "Google Drive", "tester@example.com", false, false), CopyStatus.UP_TO_DATE, null, true)))
        show(ui, Actions(cloud))
        rule.onNodeWithText("tester@example.com").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("signed in", substring = true).assertExists()
        rule.onNodeWithText("free of", substring = true, ignoreCase = true).assertExists()
        rule.onAllNodesWithTagFirst(CloudTags.MOBILE_DATA).performScrollTo().performClick()
        rule.waitUntil(3000) { cloud.calls.contains("mobile:a1:true") }
        rule.onAllNodesWithTagFirst(CloudTags.DISCONNECT).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Sign out of tester@example.com?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
    }

    @Test fun aSignedOutAccountOffersSignInAgainInPlainWords() {
        val cloud = FakeCloud(listOf(out))
        val ui = CopiesUi(true, listOf(card(CloudCardUi("a2", "Dropbox", "Dropbox", true, true), CopyStatus.DISCONNECTED, "Sign in again to keep this copy up to date. Nothing is missing.", false)))
        show(ui, Actions(cloud))
        rule.onNodeWithText("Sign in again to keep this copy up to date. Nothing is missing.").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithTagFirst(CloudTags.SIGN_IN_AGAIN).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(3000) { cloud.calls.contains("begin:dropbox:a2") }
    }

    @Test fun theWizardOffersACloudAccountAndShowsProvidersNotInThisBuild() {
        val cloud = FakeCloud(emptyList())
        show(CopiesUi(true), Actions(cloud))
        rule.onNodeWithTag(CopiesTags.ADD).performScrollTo().performClick()
        rule.onNodeWithTag(CloudTags.CHOICE + "-open").performClick()
        rule.onNodeWithTag(CloudTags.PROVIDER + "drive").assertIsDisplayed()
        rule.onNodeWithText("Not available in this build.").assertIsDisplayed()
        rule.onNodeWithTag(CloudTags.PROVIDER + "webdav").performClick()
        rule.onNodeWithTag(CloudTags.WEBDAV_URL).assertExists()
        rule.onNodeWithTag(CloudTags.WEBDAV_CONNECT).performScrollTo().assertHeightIsAtLeast(48.dp)
    }

    @Test fun atTwiceTheFontSizeTheWizardAndTheCardFit() {
        val cloud = FakeCloud(listOf(ok))
        show(CopiesUi(true, listOf(card(CloudCardUi("a1", "Google Drive", "tester@example.com", false, false), CopyStatus.UP_TO_DATE, null, true))), Actions(cloud), scale = 2f)
        for (tag in listOf(CloudTags.MOBILE_DATA, CloudTags.DISCONNECT, CloudTags.REMOVE_ACCESS)) rule.onAllNodesWithTagFirst(tag).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(CopiesTags.ADD).performScrollTo().performClick()
        rule.onNodeWithTag(CloudTags.CHOICE + "-open").performClick()
        rule.onNodeWithTag(CloudTags.PROVIDER + "drive").performScrollTo().assertIsDisplayed()
    }

    @Test fun screenshotsLightAndDark() {
        val cloud = FakeCloud(listOf(ok, out))
        val ui = CopiesUi(true, listOf(card(CloudCardUi("a1", "Google Drive", "tester@example.com", false, false), CopyStatus.UP_TO_DATE, null, true), card(CloudCardUi("a2", "Dropbox", "Dropbox", true, true), CopyStatus.DISCONNECTED, "Sign in again to keep this copy up to date. Nothing is missing.", false)))
        val dark = androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { KeepfinTheme(mode = if (dark.value) ThemeMode.DARK else ThemeMode.LIGHT) { Column(Modifier.verticalScroll(rememberScrollState())) { CopiesContent(ui, Actions(cloud)) } } }
        for ((name, d) in listOf("light" to false, "dark" to true)) {
            dark.value = d
            rule.waitForIdle()
            val bmp = rule.onNodeWithTag(CopiesTags.GROUP).captureToImage().asAndroidBitmap()
            val values = android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "kf-cloud-$name.png"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png"); put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/kf-cloud") }
            val cr = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val uri = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            cr.openOutputStream(uri)!!.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertEquals(2, cloud.state.value.accounts.size)
        assertTrue(true)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagFirst(tag: String) = onAllNodesWithTag(tag)[0]
}


