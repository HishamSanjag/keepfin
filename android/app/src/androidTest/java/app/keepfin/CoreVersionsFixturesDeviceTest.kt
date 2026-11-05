package app.keepfin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs fixtures/engine/versions through the shared core on a real device or emulator, where strings and regex run on the
 * platform's ICU rather than the desktop JVM's. The fixtures are test-APK assets only (see app/build.gradle.kts); the runner is
 * the same code the JVM unit test uses (src/sharedTest).
 */
@RunWith(AndroidJUnit4::class)
class CoreVersionsFixturesDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val files = object : FixtureFiles {
        override fun read(path: String): String = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        override fun list(dir: String): List<String> = assets.list(dir)?.toList().orEmpty().sorted()
    }

    @Test fun versionsFixturesPassOnDevice() {
        val result = VersionsFixtureRunner.run(files)
        println("device fixtures versions: ran=${result.ran} skipped=${result.skipped} failed=${result.failures.size}")
        assertTrue("fixtures versions: ran ${result.ran} cases (are the fixtures in the test APK assets?)", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} versions fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }
}
