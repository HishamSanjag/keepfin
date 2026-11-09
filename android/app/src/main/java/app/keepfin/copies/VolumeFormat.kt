package app.keepfin.copies

import java.io.File
import java.net.URLDecoder

/**
 * The file system behind a copy on a card or USB drive, as far as the phone shows it: FAT cannot hold a file of 4 GiB, and the copy says so
 * up front (the shared core decides: `fileSizeLimit`). The folder address of a card is a tree of the phone's external-storage provider
 * (`.../tree/1234-ABCD%3AKeepfin`); the mount table names the file system under that volume. Null when it cannot be told: the phone's own
 * storage, an address that is not a card, a mount table the app may not read. Then nothing is assumed, and a file too large still fails
 * with a plain reason.
 */
internal object VolumeFormat {
    fun of(treeUri: String, mounts: () -> List<String> = { runCatching { File("/proc/mounts").readLines() }.getOrDefault(emptyList()) }): String? {
        val volume = volumeId(treeUri) ?: return null
        if (volume == "primary" || volume == "home") return null
        for (line in mounts()) {
            val fields = line.split(' ')
            if (fields.size >= 3 && (fields[1] == "/storage/$volume" || fields[1].endsWith("/$volume"))) return fields[2]
        }
        return null
    }

    /** `1234-ABCD` for `content://com.android.externalstorage.documents/tree/1234-ABCD%3AKeepfin`; null for any other address. */
    fun volumeId(treeUri: String): String? {
        val marker = "/tree/"
        val at = treeUri.indexOf(marker)
        if (at < 0 || !treeUri.contains("externalstorage")) return null
        val document = runCatching { URLDecoder.decode(treeUri.substring(at + marker.length), "UTF-8") }.getOrNull() ?: return null
        val volume = document.substringBefore(':')
        return volume.takeIf { it.isNotEmpty() && document.contains(':') }
    }
}
