package app.keepfin.devicemusic

import app.keepfin.core.devicemusic.DeviceMusic

/** What a scan did, for the log and the tests. */
internal class ScanResult(val seen: Int, val added: Int, val changed: Int, val adopted: Int, val removed: Int)

/**
 * Reads the device's audio list into the [DeviceStore] in batches of [BATCH], one transaction each: files unchanged since the last scan
 * (same id, modified time and size) are only marked seen, new and changed ones are written, ids MediaStore handed out again are matched by
 * folder, name and size, and what the scan did not see is dropped at the end. No batch holds more than [BATCH] files, and a scan that fails
 * halfway removes nothing.
 */
internal object DeviceScanner {
    const val BATCH = 500

    /** True for a file Keepfin already lists some other way (its own downloads, linked files): it is skipped here. */
    fun interface Known { fun skips(file: DeviceFile): Boolean }

    fun scan(source: DeviceAudioSource, store: DeviceStore, leftOut: List<String>, known: Known, onProgress: (Int) -> Unit = {}): ScanResult {
        val gen = store.nextGeneration()
        var seen = 0; var added = 0; var changed = 0; var adopted = 0
        for (volume in source.volumes()) {
            var after = 0L
            while (true) {
                val files = source.batch(volume, after, BATCH)
                if (files.isEmpty()) break
                after = files.maxOf { it.mediaId }
                store.transaction {
                    val usable = files.filter { !known.skips(it) && DeviceMusic.include(it.audioRow(), emptyList()) }
                    store.noteFolders(usable.map { it.relativePath.trimEnd('/') }.filter { it.isNotEmpty() }.toSet(), gen)
                    val listed = usable.filter { !DeviceMusic.leftOutFolder(it.relativePath, leftOut) }
                    val before = store.known(listed.map { it.id })
                    val unchanged = ArrayList<String>()
                    for (f in listed) {
                        val old = before[f.id]
                        when {
                            old != null && old.modified == f.dateModified && old.size == f.size -> unchanged += f.id
                            old != null -> { store.upsert(f, gen); changed++ }
                            store.adopt(f.id, f, gen) -> { store.upsert(f, gen); adopted++ }
                            else -> { store.upsert(f, gen); added++ }
                        }
                    }
                    store.touch(unchanged, gen)
                    seen += listed.size
                }
                onProgress(seen)
                if (files.size < BATCH) break
            }
        }
        val removed = store.transaction { store.sweep(gen) }
        return ScanResult(seen, added, changed, adopted, removed)
    }
}
