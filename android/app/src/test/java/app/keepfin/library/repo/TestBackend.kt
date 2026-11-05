package app.keepfin.library.repo

import app.keepfin.folder.sql.JdbcHost
import app.keepfin.folder.sql.SqlMergeState
import java.io.File

// The merge state the repository tests run on: the same SQLite state and index as a phone, through sqlite-jdbc.

internal const val TEST_LIBRARY = "11111111-1111-4111-8111-111111111111"

internal fun testState(dir: File, replica: String, library: String = TEST_LIBRARY): SqlMergeState =
    SqlMergeState(File(dir, "state/state.db").path, library, replica, null, JdbcHost())
