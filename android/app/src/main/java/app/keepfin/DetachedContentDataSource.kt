package app.keepfin

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.RemoteException
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Plays `content://` files without staying tied to the app that provides them.
 *
 * A descriptor from [ContentResolver.openAssetFileDescriptor] keeps a stable reference to its provider until it is closed, so while a song
 * from the Keepfin folder played, Keepfin was a stable client of the system's storage provider (com.android.externalstorage). Phones that
 * stop that provider in the background (HONOR, others) made Android stop Keepfin with it ("DEPENDENCY DIED"), and the music stopped with the
 * screen off. Here the file is opened through an unstable provider client that is closed at once ([openDetached]): the descriptor reads
 * the same open file and holds no reference to the provider, so the provider stopping does not stop Keepfin.
 *
 * It follows Media3's ContentDataSource otherwise: a typed open that asks for the original media format, the asset's offset and length,
 * and the read-position and length rules of the DataSource contract.
 */
@UnstableApi
class DetachedContentDataSource(private val resolver: ContentResolver) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var asset: AssetFileDescriptor? = null
    private var input: FileInputStream? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            val afd = openDetached(dataSpec.uri)
            asset = afd
            val start = afd.startOffset
            val declared = afd.declaredLength
            val stream = FileInputStream(afd.fileDescriptor)
            input = stream
            // a regular file can be positioned; a pipe or socket (statSize -1) is read forward to the position
            val fileSize = afd.parcelFileDescriptor.statSize
            if (fileSize >= 0) {
                if (start + dataSpec.position > fileSize) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                stream.channel.position(start + dataSpec.position)
            } else {
                var left = start + dataSpec.position
                while (left > 0) {
                    val skipped = stream.skip(left)
                    if (skipped <= 0) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                    left -= skipped
                }
            }
            val available = when {
                declared != AssetFileDescriptor.UNKNOWN_LENGTH -> declared - dataSpec.position
                fileSize >= 0 -> fileSize - start - dataSpec.position
                else -> C.LENGTH_UNSET.toLong()
            }
            if (available != C.LENGTH_UNSET.toLong() && available < 0) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                if (available == C.LENGTH_UNSET.toLong()) dataSpec.length else minOf(dataSpec.length, available)
            } else available
        } catch (e: DataSourceException) {
            throw e
        } catch (e: FileNotFoundException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        } catch (e: SecurityException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        opened = true
        transferStarted(dataSpec)
        // a bounded request answers with the length asked for, even where the file ends earlier (the DataSource contract)
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else remaining
    }

    /**
     * Opens [uri] through an unstable client of its provider: [android.content.ContentProviderClient.openTypedAssetFile] hands back the
     * provider's descriptor as it is (the resolver's own open would wrap it with a stable reference), and the client is closed at once.
     * A provider that died since the last open is asked again once with a fresh client.
     */
    private fun openDetached(uri: Uri): AssetFileDescriptor {
        // as Media3 does: the original bytes, never a version the system transcoded for apps that cannot play HEVC
        val options = Bundle().apply { if (Build.VERSION.SDK_INT >= 31) putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true) }
        for (attempt in 0..1) {
            val client = resolver.acquireUnstableContentProviderClient(uri) ?: throw FileNotFoundException("No provider for $uri")
            try {
                return client.openTypedAssetFile(uri, "*/*", options, null) ?: throw FileNotFoundException("Could not open $uri")
            } catch (e: DeadObjectException) {
                if (attempt == 1) throw IOException("The storage provider stopped while opening the file.", e)
            } catch (e: RemoteException) {
                throw IOException("The storage provider could not open the file.", e)
            } finally {
                client.close()
            }
        }
        throw IOException("The storage provider stopped while opening the file.")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val n = try {
            input?.read(buffer, offset, toRead) ?: return C.RESULT_END_OF_INPUT
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        val stream = input
        val afd = asset
        input = null
        asset = null
        try {
            try {
                stream?.close()
            } finally {
                // the descriptor closes even when the stream's close failed
                afd?.close()
            }
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
