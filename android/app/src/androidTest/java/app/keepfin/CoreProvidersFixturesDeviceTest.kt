package app.keepfin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs fixtures/engine/providers through the shared core on a real device or emulator, where strings, Unicode normalisation
 * (the host-name mapping) and regex run on the platform's ICU rather than the desktop JVM's. The fixtures are test-APK assets only
 * (see app/build.gradle.kts); the runner is the same code the JVM unit test uses (src/sharedTest).
 */
@RunWith(AndroidJUnit4::class)
class CoreProvidersFixturesDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val files = object : FixtureFiles {
        override fun read(path: String): String = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        override fun list(dir: String): List<String> = assets.list(dir)?.toList().orEmpty().sorted()
    }

    @Test fun providersFixturesPassOnDevice() {
        val result = ProvidersFixtureRunner.run(files)
        println("device fixtures providers: ran=${result.ran} failed=${result.failures.size}")
        assertTrue("fixtures providers: ran ${result.ran} cases (are the fixtures in the test APK assets?)", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} providers fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }
}
