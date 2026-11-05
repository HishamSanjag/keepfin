package app.keepfin.net

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream

/**
 * "Play from <device>" (network spec 6): the player reads a file that a paired device holds through `keepfin-peer://<token>` with real
 * Range requests, so seeking works; nothing is saved on this phone. The token was made by [NetService.playFrom].
 */
@UnstableApi
class PeerDataSource : BaseDataSource(true) {
    private var uri: Uri? = null
    private var stream: NetService.PeerStream? = null
    private var input: InputStream? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var streamName = "the other device"

    private fun fail(message: String): Nothing {
        PeerPlayback.failed(message)
        throw IOException(message)
    }

    override fun open(dataSpec: DataSpec): Long {
        val service = NetRuntime.peek() ?: fail("Devices are not running. Open Settings and turn devices on.")
        var host = dataSpec.uri.host ?: dataSpec.uri.schemeSpecificPart.trimStart('/').substringBefore('/')
        var device: String? = null
        if (host == CONTENT_HOST) {
            // an item of the library that is not on this phone: the device that holds it is looked for now (queues, resumption and Auto
            // hold only this address), and the person asked to play it, so mobile data is allowed
            val hex = dataSpec.uri.lastPathSegment.orEmpty()
            val library = dataSpec.uri.getQueryParameter("library") ?: NetRuntime.libraries().firstOrNull()?.libraryId ?: fail("This phone has no shared library.")
            try {
                val found = service.playFrom(library, "sha256:$hex", dataSpec.uri.getQueryParameter("name") ?: "media", allowMetered = true)
                host = found.uri.removePrefix("keepfin-peer://")
                device = found.deviceName
            } catch (e: IllegalStateException) {
                fail(e.message ?: "No paired device has this file right now.")
            } catch (e: IllegalArgumentException) {
                fail(e.message ?: "This item has no content to look for on other devices.")
            }
        }
        val source = service.peerSource(host) ?: fail("This file is no longer offered by the other device.")
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val start = dataSpec.position
        val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) start + dataSpec.length - 1 else source.size - 1
        val range = if (start > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) start..end else null
        val s = try {
            source.open(range)
        } catch (e: IOException) {
            fail(if (e.message?.startsWith("Waiting for Wi-Fi") == true) e.message!! else "The connection to ${device ?: "the other device"} was lost.")
        }
        stream = s
        if (s.status != 200 && s.status != 206) {
            s.close()
            fail("The other device answered ${s.status}.")
        }
        streamName = device ?: service.peerNameOf(host) ?: "another device"
        PeerPlayback.streaming(streamName)
        input = s.input
        remaining = if (s.contentLength >= 0) s.contentLength else if (source.size >= 0) source.size - start else C.LENGTH_UNSET.toLong()
        // a 200 for a range request: skip to the start
        if (s.status == 200 && start > 0) {
            var left = start
            while (left > 0) {
                val n = s.input.skip(left)
                if (n <= 0) throw IOException("The other device ended early.")
                left -= n
            }
            remaining = source.size - start
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val n = try {
            input?.read(buffer, offset, toRead) ?: return C.RESULT_END_OF_INPUT
        } catch (e: IOException) {
            // the player reopens at this position (the link reconnects); only when that fails too does the person see the message
            fail("The connection to $streamName was lost.")
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            stream?.close?.invoke()
        } finally {
            stream = null
            input = null
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /**
     * Sends `keepfin-peer` URIs here, `content://` files to [app.keepfin.DetachedContentDataSource] (never tied to the storage provider
     * while playing) and everything else to the player's usual data source.
     */
    class Factory(private val normal: DataSource.Factory, private val content: () -> DataSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = Dispatching(normal.createDataSource(), PeerDataSource(), content())
    }

    private class Dispatching(private val normal: DataSource, private val peer: DataSource, private val content: DataSource) : DataSource {
        private var active: DataSource = normal

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            normal.addTransferListener(transferListener)
            peer.addTransferListener(transferListener)
            content.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            active = when (dataSpec.uri.scheme) {
                SCHEME -> peer
                android.content.ContentResolver.SCHEME_CONTENT -> content
                else -> normal
            }
            return active.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)
        override fun getUri(): Uri? = active.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders
        override fun close() = active.close()
    }

    companion object {
        const val SCHEME = "keepfin-peer"

        const val CONTENT_HOST = PeerPlayback.CONTENT_HOST
    }
}

/** The player reads keepfin-peer:// files from paired devices as well as everything it played before. */
@androidx.annotation.OptIn(UnstableApi::class)
fun androidx.media3.exoplayer.ExoPlayer.Builder.withPeerPlayback(context: android.content.Context): androidx.media3.exoplayer.ExoPlayer.Builder =
    setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
        PeerDataSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(context)) { app.keepfin.DetachedContentDataSource(context.contentResolver) },
    ))
