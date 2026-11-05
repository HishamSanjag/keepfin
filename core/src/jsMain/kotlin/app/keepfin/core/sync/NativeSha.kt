package app.keepfin.core.sync

// On Node (the desktop app's main process) a checkpoint's checksum can be taken by the runtime's own SHA-256,
// several times faster than the portable one. It gives the same digest (a test compares them); where the runtime
// has no such module (a browser, a sandboxed renderer) nothing changes and the portable code is used.

private var tried = false

/** Installs the runtime's SHA-256 for large inputs, once. Safe to call from every entry point. */
internal fun installNativeSha() {
    if (tried) return
    tried = true
    try {
        val crypto: dynamic = js("(typeof process !== 'undefined' && typeof process.getBuiltinModule === 'function') ? process.getBuiltinModule('node:crypto') : null")
        if (crypto == null || crypto == undefined) return
        sha256Native = { data, from, to ->
            val view: dynamic = js("new Uint8Array(data.buffer, data.byteOffset + from, to - from)")
            crypto.createHash("sha256").update(view).digest("hex") as String
        }
    } catch (e: Throwable) {
        // keep the portable routine
    }
}
