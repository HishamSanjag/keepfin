package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryLogicTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test fun readsTheShaFromTheManifest() {
        assertEquals(a, GalleryLogic.packageSha("{\n  \"packages\": {\"gallery-dl\": \"1.32.19\"},\n  \"sha256\": \"$a\"\n}"))
        assertNull(GalleryLogic.packageSha(null))
        assertNull(GalleryLogic.packageSha("{}"))
    }

    @Test fun installsOnlyWhenMissingOrDifferent() {
        assertTrue(GalleryLogic.needsInstall(null, a, zipPresent = false))
        assertTrue(GalleryLogic.needsInstall(a, a, zipPresent = false))
        assertTrue(GalleryLogic.needsInstall(null, a, zipPresent = true))
        assertTrue(GalleryLogic.needsInstall(a, b, zipPresent = true))
        assertFalse(GalleryLogic.needsInstall(a, a, zipPresent = true))
    }

    @Test fun inspectArgumentsEndWithTheUrlAfterADoubleDash() {
        val plain = GalleryLogic.inspectArgs("https://x.test/p/1", null)
        assertEquals(listOf("--config-ignore", "--retries", "4", "--http-timeout", "30", "-j", "--range", "1-200", "--", "https://x.test/p/1"), plain)
        val signedIn = GalleryLogic.inspectArgs("https://x.test/p/1", "/data/c.txt")
        assertEquals(listOf("--cookies", "/data/c.txt"), signedIn.subList(5, 7))
        assertEquals("--", signedIn[signedIn.size - 2])
    }

    @Test fun commandRunsTheModule() {
        assertEquals(listOf("/lib/libpython.so", "-m", "gallery_dl", "-j"), GalleryLogic.command("/lib/libpython.so", listOf("-j")))
    }

    @Test fun environmentPointsAtTheDownloadEnginePackages() {
        val env = GalleryLogic.environment("/app/lib", "/nb", "/files", "/files/gallery/gdl.zip", "/cache")
        assertEquals("/nb/youtubedl-android/packages/python/usr", env["PYTHONHOME"])
        assertEquals("/nb/youtubedl-android/packages/python/usr/lib:/nb/youtubedl-android/packages/ffmpeg/usr/lib:/app/lib", env["LD_LIBRARY_PATH"])
        assertEquals("/nb/youtubedl-android/packages/python/usr/etc/tls/cert.pem", env["SSL_CERT_FILE"])
        assertEquals("/files/gallery/gdl.zip", env["PYTHONPATH"])
        assertEquals("/files", env["HOME"])
    }
}
