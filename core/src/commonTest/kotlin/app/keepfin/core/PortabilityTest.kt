package app.keepfin.core

import kotlin.test.Test
import kotlin.test.fail

/**
 * Regex runs on a different engine on each platform (Java, JavaScript, ICU on Android), so the shared code
 * may not use the shorthands and features whose meaning differs between them.
 */
class PortabilityTest {
    private val backslash = '\\'
    private val forbidden = listOf("${backslash}b", "${backslash}s", "${backslash}w", "${backslash}d", "(?<")

    private fun sourceFiles(dir: String): List<String> =
        listDirectory(dir).sortedBy { it.name }.flatMap {
            val full = "$dir/${it.name}"
            when {
                it.isDirectory -> sourceFiles(full)
                it.name.endsWith(".kt") -> listOf(full)
                else -> emptyList()
            }
        }

    @Test
    fun commonSourcesAvoidPlatformDependentRegex() {
        val root = commonMainRoot() ?: error(
            "The common sources folder is not set. Run the tests with Gradle from core/; " +
                "the build passes it as keepfin.commonMain (JVM) and KEEPFIN_COMMON_MAIN (JS).",
        )
        val files = sourceFiles(root)
        if (files.isEmpty()) fail("No Kotlin files found under $root")
        val problems = ArrayList<String>()
        for (file in files) {
            readTextFile(file).split('\n').forEachIndexed { index, line ->
                for (pattern in forbidden) {
                    if (line.contains(pattern)) problems.add("${file.substringAfter("commonMain/")}:${index + 1} uses $pattern")
                }
            }
        }
        if (problems.isNotEmpty()) fail("Platform-dependent regex features in common code:\n" + problems.joinToString("\n"))
    }
}
