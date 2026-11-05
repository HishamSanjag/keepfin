package app.keepfin.core.spaces

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoldersTest {
    @Test fun builtInSpacesKeepTheirFolderNamesAndOwnSpacesUseASafeName() {
        assertEquals("Downloads", defaultFolderName("downloads", "My downloads"))
        assertEquals("Music", defaultFolderName("music", "Songs"))
        assertEquals("Videos", defaultFolderName("videos", "Films"))
        assertEquals("Watch later", defaultFolderName("space-1", "  Watch later  "))
        assertEquals("AC_DC_ live", defaultFolderName("space-2", "AC/DC: live"))
        assertEquals("hidden", defaultFolderName("space-3", "..hidden. "))
        assertEquals("Space", defaultFolderName("space-4", "  "))
    }

    @Test fun theDefaultFolderSitsInsideTheRootWithItsSeparator() {
        assertEquals("F:\\Keepfin\\Music", defaultSpaceFolder("F:\\Keepfin\\", "music", "Music"))
        assertEquals("primary:Keepfin/Podcasts", defaultSpaceFolder("primary:Keepfin", "space-9", "Podcasts"))
    }

    @Test fun inAFolderIgnoresSeparatorsAndCaseButNotSimilarNames() {
        assertTrue(isInFolder("F:\\Keepfin", "f:/keepfin/Music/a.mp3"))
        assertTrue(isInFolder("F:\\Keepfin\\", "F:\\Keepfin"))
        assertFalse(isInFolder("F:\\Keepfin", "F:\\Keepfin2\\a.mp3"))
        assertFalse(isInFolder("F:\\Keepfin\\Music", "F:\\Keepfin\\a.mp3"))
    }

    @Test fun aPickedFolderGetsAKeepfinFolderInsideUnlessItIsOne() {
        assertEquals("F:\\test\\Keepfin", keepfinFolderFor("F:\\test", false))
        assertEquals("D:\\New folder\\Keepfin", keepfinFolderFor("D:\\New folder", false))
        assertEquals("F:\\Keepfin", keepfinFolderFor("F:\\", false))
        assertEquals("F:\\Keepfin", keepfinFolderFor("F:", false))
        assertEquals("\\\\nas\\media\\Keepfin", keepfinFolderFor("\\\\nas\\media", false))
        assertEquals("/Keepfin", keepfinFolderFor("/", false))
        assertEquals("F:\\keepfin", keepfinFolderFor("F:\\keepfin", false))
        assertEquals("E:\\Backup\\Library", keepfinFolderFor("E:\\Backup\\Library", true))
        assertEquals("primary:Music/Keepfin", keepfinFolderFor("primary:Music", false))
    }

    @Test fun aPathFollowsItsMovedFolder() {
        assertEquals("F:\\test\\Keepfin\\Music\\rr\\Dune.mp3", movedPath("F:\\Keepfin", "F:\\test\\Keepfin", "F:\\Keepfin\\Music\\rr\\Dune.mp3"))
        assertEquals("D:\\Keepfin\\a.mp3", movedPath("f:\\keepfin\\", "D:\\Keepfin\\", "F:/Keepfin/a.mp3"))
        assertEquals("D:\\Keepfin", movedPath("F:\\Keepfin", "D:\\Keepfin", "F:\\Keepfin"))
        assertEquals("primary:Keepfin/Music/a.mp3", movedPath("F:\\Keepfin", "primary:Keepfin", "F:\\Keepfin\\Music\\a.mp3"))
        assertNull(movedPath("F:\\Keepfin", "D:\\Keepfin", "F:\\Keepfin2\\a.mp3"))
    }

    @Test fun aMoveIntoOrAboveTheFolderIsRefused() {
        assertEquals("The new place cannot be inside your Keepfin folder.", folderMoveProblem("F:\\Keepfin", "F:\\Keepfin\\Moved"))
        assertEquals("Choose a folder that is not above your Keepfin folder.", folderMoveProblem("F:\\Media\\Keepfin", "F:\\Media"))
        assertNull(folderMoveProblem("F:\\Keepfin", "D:\\Keepfin"))
    }
}
