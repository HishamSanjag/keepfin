package app.keepfin

import org.json.JSONObject

internal object Fixtures {
    fun text(path: String): String = (Fixtures::class.java.classLoader!!.getResourceAsStream(path) ?: error("missing fixture $path")).use { String(it.readBytes(), Charsets.UTF_8) }
    fun json(path: String) = JSONObject(text(path))
}
