package app.keepfin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs fixtures/engine/errors through the shared core on a real device or emulator, where regex and string functions run on
 * the platform's ICU rather than the desktop JVM's. The fixtures are test-APK assets only (see app/build.gradle.kts); the
 * runner is the same code the JVM unit test uses (src/sharedTest).
 */
@RunWith(AndroidJUnit4::class)
class CoreErrorsFixturesDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val files = object : FixtureFiles {
        override fun read(path: String): String = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        override fun list(dir: String): List<String> = assets.list(dir)?.toList().orEmpty().sorted()
    }

    @Test fun errorsFixturesPassOnDevice() {
        val result = ErrorsFixtureRunner.run(files)
        println("device fixtures errors: ran=${result.ran} skipped=${result.skipped} failed=${result.failures.size}")
        assertTrue("fixtures errors: ran ${result.ran} cases (are the fixtures in the test APK assets?)", result.ran > 0)
        if (result.failures.isNotEmpty()) fail("${result.failures.size} of ${result.ran} errors fixture cases failed:\n  - " + result.failures.joinToString("\n  - "))
    }

    @Test fun noMessageNamesATool() {
        val named = ErrorsFixtureRunner.everyFixedMessageIsFree()
        assertTrue("messages naming a tool: $named", named.isEmpty())
    }
}
