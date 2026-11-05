@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.cloud

// The JavaScript surface of the cloud providers (gui/shared/core/cloud.ts is the only importer): JSON text in, JSON text out.

@JsExport
fun cloudCall(provider: String, op: String, input: String): String = CloudApi.call(provider, op, input)

/** Streaming quickXorHash for the desktop. */
@JsExport
class JsQuickXor {
    private val h = QuickXorHash()
    fun update(bytes: ByteArray) = h.update(bytes)
    fun digest(): String = h.digest()
}

/** Dropbox content hash from SHA-256 block digests computed by the platform (hex, one per 4 MiB block). */
@JsExport
fun cloudDropboxCombine(digestsHex: Array<String>): String = CloudApi.call("dropbox", "hashBlocks", "{\"digests\":[" + digestsHex.joinToString(",") { "\"$it\"" } + "]}")
