package app.keepfin.core

private fun env(name: String): String? {
    val value: dynamic = js("globalThis.process.env[name]")
    return if (value == undefined || value == null) null else value as String
}

internal actual fun fixturesRoot(): String? = env("KEEPFIN_FIXTURES")

internal actual fun commonMainRoot(): String? = env("KEEPFIN_COMMON_MAIN")

internal actual fun readTextFile(path: String): String = readFileSync(path, "utf8")

internal actual fun listDirectory(path: String): List<DirEntry> {
    val entries = readdirSync(path, js("({withFileTypes: true})"))
    return entries.map { entry -> DirEntry(entry.name as String, entry.isDirectory() as Boolean) }
}
