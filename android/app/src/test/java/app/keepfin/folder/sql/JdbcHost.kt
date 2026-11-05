package app.keepfin.folder.sql

import app.keepfin.core.sqlstate.SqlDriver
import app.keepfin.core.sqlstate.SqlHost
import app.keepfin.core.sqlstate.SqlStmt
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

// The JVM host of the unit tests: the same SQL as the phone runs through androidx.sqlite-bundled, here through sqlite-jdbc
// (the bundled driver needs Android's native loader). The instrumented tests run the real driver.

private fun cell(rs: ResultSet, i: Int): Any? = when (val v = rs.getObject(i)) {
    null -> null
    is Int -> v.toLong()
    is Short -> v.toLong()
    is Byte -> v.toLong()
    is Float -> v.toDouble()
    else -> v
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

    override fun close() = st.close()
}

private class JdbcDriver(private val conn: Connection) : SqlDriver {
    override fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    override fun prepare(sql: String): SqlStmt = JdbcStmt(conn.prepareStatement(sql))

    override fun close() = conn.close()
}

class JdbcHost : SqlHost {
    override fun open(path: String): SqlDriver = JdbcDriver(DriverManager.getConnection("jdbc:sqlite:$path"))

    override fun remove(path: String) {
        File(path).delete()
    }

    override fun rename(from: String, to: String) {
        Files.move(File(from).toPath(), File(to).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
