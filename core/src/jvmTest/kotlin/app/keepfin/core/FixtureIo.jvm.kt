package app.keepfin.core

import java.io.File

internal actual fun fixturesRoot(): String? = System.getProperty("keepfin.fixtures")

internal actual fun commonMainRoot(): String? = System.getProperty("keepfin.commonMain")

internal actual fun readTextFile(path: String): String = File(path).readText(Charsets.UTF_8)

internal actual fun listDirectory(path: String): List<DirEntry> =
    (File(path).listFiles() ?: throw IllegalStateException("Cannot list $path")).map { DirEntry(it.name, it.isDirectory) }
