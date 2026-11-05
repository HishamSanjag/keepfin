package app.keepfin.core.sqlstate

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

// The JVM test driver: org.xerial:sqlite-jdbc. It bundles one known SQLite (with UPSERT, window functions and JSON1, as the
// shared SQL needs) and its natives for Windows, Linux and macOS, so a plain `gradlew jvmTest` runs the same SQL the
// Android build runs through androidx.sqlite-bundled, without an emulator. Chosen over androidx.sqlite-bundled on the JVM
// because that artifact ships natives only for some desktop targets and is built for Android's Room/KMP use; JDBC is
// the stable, synchronous API that matches the SqlDriver contract directly.

private fun cell(rs: ResultSet, i: Int): Any? = when (val v = rs.getObject(i)) {
    null -> null
    is Int -> v.toLong()
    is Short -> v.toLong()
    is Byte -> v.toLong()
    is Float -> v.toDouble()
    else -> v // Long, Double, String, ByteArray
}

private class JdbcStmt(private val st: PreparedStatement) : SqlStmt {
    private fun bind(params: List<Any?>) {
        st.clearParameters()
        for ((i, p) in params.withIndex()) {
            when (p) {
                null -> st.setObject(i + 1, null)
                is String -> st.setString(i + 1, p)
                is Long -> st.setLong(i + 1, p)
                is Int -> st.setInt(i + 1, p)
                is Double -> st.setDouble(i + 1, p)
                is ByteArray -> st.setBytes(i + 1, p)
                else -> throw IllegalArgumentException("unsupported SQL parameter")
            }
        }
    }

    override fun run(params: List<Any?>): Long {
        bind(params)
        return st.executeUpdate().toLong()
    }

    override fun rows(params: List<Any?>): List<List<Any?>> {
        bind(params)
        val out = ArrayList<List<Any?>>()
        st.executeQuery().use { rs ->
            val n = rs.metaData.columnCount
            while (rs.next()) out.add(List(n) { cell(rs, it + 1) })
        }
        return out
    }

    override fun close() {
        st.close()
    }
}

private class JdbcDriver(private val conn: Connection) : SqlDriver {
    override fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    override fun prepare(sql: String): SqlStmt = JdbcStmt(conn.prepareStatement(sql))

    override fun close() {
        conn.close()
    }
}

internal actual class TestSql actual constructor() {
    private val dir: File = Files.createTempDirectory("keepfin-sql-").toFile()

    actual val host: SqlHost = object : SqlHost {
        override fun open(path: String): SqlDriver = JdbcDriver(DriverManager.getConnection("jdbc:sqlite:$path"))

        override fun remove(path: String) {
            File(path).delete()
        }

        override fun rename(from: String, to: String) {
            Files.move(File(from).toPath(), File(to).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    actual fun path(name: String): String = File(dir, name).absolutePath

    actual fun cleanup() {
        dir.deleteRecursively()
    }
}
