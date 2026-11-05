package app.keepfin.core

/** File access for the tests; implemented per target (java.io.File on the JVM, node:fs on JS). */
internal data class DirEntry(val name: String, val isDirectory: Boolean)

/** Absolute path of fixtures/engine, set by the Gradle build; null when it is not set. */
internal expect fun fixturesRoot(): String?

/** Absolute path of core/src/commonMain, set by the Gradle build; null when it is not set. */
internal expect fun commonMainRoot(): String?

internal expect fun readTextFile(path: String): String

internal expect fun listDirectory(path: String): List<DirEntry>
