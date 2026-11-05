package app.keepfin.core.sqlstate

// The JS test driver: node:sqlite, through the same NodeSqlDriver the desktop uses.

private fun builtin(name: String): dynamic = js("process.getBuiltinModule(name)")

@Suppress("UNUSED_PARAMETER")
private fun newDatabase(sqlite: dynamic, path: String): dynamic = js("new sqlite.DatabaseSync(path)")

internal actual class TestSql actual constructor() {
    private val fs: dynamic = builtin("node:fs")
    private val dir: String = fs.mkdtempSync(builtin("node:path").join(builtin("node:os").tmpdir(), "keepfin-sql-")) as String

    actual val host: SqlHost = object : SqlHost {
        override fun open(path: String): SqlDriver = NodeSqlDriver(newDatabase(builtin("node:sqlite"), path))

        override fun remove(path: String) {
            fs.rmSync(path, js("({force: true})"))
        }

        override fun rename(from: String, to: String) {
            fs.renameSync(from, to)
        }
    }

    actual fun path(name: String): String = builtin("node:path").join(dir, name) as String

    actual fun cleanup() {
        fs.rmSync(dir, js("({recursive: true, force: true})"))
    }
}
