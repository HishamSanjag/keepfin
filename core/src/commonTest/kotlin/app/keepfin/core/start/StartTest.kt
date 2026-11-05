package app.keepfin.core.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartTest {
    @Test
    fun bothAppsShareTheOrderAndOnlyThePhoneAsksForPermissions() {
        assertEquals(listOf(StartPage.WELCOME, StartPage.HOW, StartPage.MODE, StartPage.FOLDER), Start.pages(phone = false))
        assertEquals(Start.pages(phone = false) + StartPage.PERMISSIONS, Start.pages(phone = true))
        assertEquals(StartPage.MODE, Start.next(StartPage.HOW, phone = false))
        assertNull(Start.next(StartPage.FOLDER, phone = false))
        assertEquals(StartPage.PERMISSIONS, Start.next(StartPage.FOLDER, phone = true))
        assertNull(Start.previous(StartPage.WELCOME, phone = true))
    }

    @Test
    fun skipIntroLeavesOnlyTheIntroductionAndTheFolderIsNeeded() {
        assertTrue(Start.introduces(StartPage.WELCOME))
        assertTrue(Start.introduces(StartPage.HOW))
        assertFalse(Start.introduces(StartPage.FOLDER))
        assertEquals(StartPage.MODE, Start.skipTo)
        assertFalse(Start.canGoOn(StartPage.FOLDER, folderReady = false))
        assertTrue(Start.canGoOn(StartPage.FOLDER, folderReady = true))
        assertTrue(Start.canGoOn(StartPage.MODE, folderReady = false))
    }

    @Test
    fun justDownloadsHasNoUseForEarlierFiles() {
        assertEquals(listOf(StartPermission.MEDIA, StartPermission.NOTIFICATIONS), Start.permissions(complete = true))
        assertEquals(listOf(StartPermission.NOTIFICATIONS), Start.permissions(complete = false))
    }

    @Test
    fun theWordsSayDevicesAndAppsApart() {
        val pc = Start.words(phone = false)
        val phone = Start.words(phone = true)
        assertEquals(Start.COMPLETE, pc.complete.title)
        assertEquals(Start.DOWNLOADS, phone.downloads.title)
        assertTrue("computer" in pc.welcomeBody)
        assertTrue("phone" in phone.welcomeBody)
        assertTrue(phone.steps.first().title.startsWith("Share"))
        assertEquals(4, pc.features.size)
        assertTrue("YouTube" in pc.sites && "Facebook" in phone.sites)
        assertTrue("browser" in pc.signIn.body && "browser" !in phone.signIn.body, "only Windows uses a browser's sign-in")
        assertEquals(3, pc.steps.size)
        for (w in listOf(pc, phone)) for (word in listOf("yt-dlp", "ffmpeg", "gallery-dl", "aria2c"))
            assertFalse(word in w.toString(), "no tool names in the start pages")
    }
}
