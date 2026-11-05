package app.keepfin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs fixtures/engine/spaces through the shared core on a real device or emulator, where strings, Unicode normalisation
 * (the artist and album rules) and the link parser run on the platform's runtime rather than the desktop JVM's. The fixtures are
 * test-APK assets only (see app/build.gradle.kts); the runner is the same code the JVM unit test uses (src/sharedTest).
 */
@RunWith(AndroidJUnit4::class)
class CoreSpacesFixturesDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val files = object : FixtureFiles {
        override fun read(path: String): String = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        override fun list(dir: String): List<String> = assets.list(dir)?.toList().orEmpty().sorted()
    }

    @Test fun spacesFixturesPassOnDevice() {
        val result = SpacesFixtureRunner.run(files)
        println("device fixtures spaces: ran=${result.ran} failed=${result.failures.size}")
        assertTrue("fixtures spaces: ran ${result.ran} cases (are the fixtures in the test APK assets?)", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} spaces fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }
}
