package app.keepfin

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The player's reader for content:// files (the Keepfin folder): it reads the same bytes as a plain file read, from any position and
 * for any length, through a provider client it has already let go of.
 */
@UnstableApi
class DetachedContentDataSourceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file: File
    private lateinit var uri: Uri
    private val bytes = ByteArray(300_000) { (it * 31 % 251).toByte() }

    @Before fun setUp() {
        file = File(context.cacheDir, "updates/detached-test.bin").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    @After fun tearDown() { file.delete() }

    private fun read(spec: DataSpec): ByteArray {
        val source = DetachedContentDataSource(context.contentResolver)
        try {
            val length = source.open(spec)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
            }
            if (length != C.LENGTH_UNSET.toLong()) assertEquals(length, out.size().toLong())
            return out.toByteArray()
        } finally {
            source.close()
        }
    }

    @Test fun readsTheWholeFile() = assertArrayEquals(bytes, read(DataSpec(uri)))

    @Test fun readsFromAPositionAsASeekDoes() =
        assertArrayEquals(bytes.copyOfRange(123_456, bytes.size), read(DataSpec.Builder().setUri(uri).setPosition(123_456).build()))

    @Test fun readsALimitedRange() =
        assertArrayEquals(bytes.copyOfRange(1_000, 1_000 + 4_096), read(DataSpec.Builder().setUri(uri).setPosition(1_000).setLength(4_096).build()))

    @Test fun aRangeBeyondTheEndAnswersTheLengthAskedForAndReadsToTheEnd() {
        val source = DetachedContentDataSource(context.contentResolver)
        try {
            // the DataSource contract: a bounded request answers with its own length, and the reads end where the file does
            assertEquals(50_000L, source.open(DataSpec.Builder().setUri(uri).setPosition(bytes.size - 1_000L).setLength(50_000).build()))
            val buffer = ByteArray(8192)
            var total = 0
            while (true) { val n = source.read(buffer, 0, buffer.size); if (n == C.RESULT_END_OF_INPUT) break; total += n }
            assertEquals(1_000, total)
        } finally {
            source.close()
        }
    }

    @Test fun aPositionPastTheEndIsOutOfRange() {
        val error = runCatching { read(DataSpec.Builder().setUri(uri).setPosition(bytes.size + 10L).build()) }.exceptionOrNull()
        assertTrue("expected a data source error, got $error", error is DataSourceException)
        assertEquals(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, (error as DataSourceException).reason)
    }

    @Test fun aMissingFileIsAFileNotFoundError() {
        file.delete()
        val error = runCatching { read(DataSpec(uri)) }.exceptionOrNull()
        assertTrue("expected a data source error, got $error", error is DataSourceException)
        assertEquals(androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, (error as DataSourceException).reason)
    }
}
