package app.keepfin.copies

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.PriorityQueue

// JSON-lines files on the phone's own storage: the way inventories, the baseline and run state are kept, so that nothing holds a
// whole inventory in memory (spec section 3). The planner pulls lines synchronously, so the readers are plain blocking iterators.

internal class LineWriter(val file: File) : Closeable {
    private val out = FileOutputStream(file)
    private val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024)
    var count = 0
        private set

    fun write(line: String) {
        writer.write(line)
        writer.write("\n")
        count++
    }

    /** Flushes, and fsyncs unless [sync] is false. */
    fun finish(sync: Boolean = true) {
        writer.flush()
        if (sync) out.fd.sync()
        writer.close()
    }

    override fun close() {
        runCatching { writer.close() }
    }
}

/** Reads a file line by line. A missing file reads as empty. */
internal class LineReader(file: File) : Iterator<String>, Closeable {
    private val reader: BufferedReader? = if (file.isFile) BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 64 * 1024) else null
    private var next: String? = null
    private var done = reader == null

    private fun fill() {
        while (next == null && !done) {
            val line = reader!!.readLine()
            if (line == null) {
                done = true
                close()
            } else if (line.isNotEmpty()) next = line
        }
    }

    override fun hasNext(): Boolean {
        fill()
        return next != null
    }

    override fun next(): String {
        fill()
        val n = next ?: throw NoSuchElementException()
        next = null
        return n
    }

    override fun close() {
        runCatching { reader?.close() }
        done = true
    }
}

/** Keeps track of the readers a planning round opened so they can all be closed afterwards. */
internal class ReaderSet {
    private val open = ArrayList<Closeable>()

    fun <T> source(file: File, parse: (String) -> T): () -> Iterator<T> = {
        val reader = LineReader(file)
        open.add(reader)
        object : Iterator<T> {
            override fun hasNext() = reader.hasNext()
            override fun next(): T = parse(reader.next())
        }
    }

    fun closeAll() {
        for (c in open) runCatching { c.close() }
        open.clear()
    }
}

/** Appends one line to a local file and fsyncs it. */
internal fun appendLineDurable(file: File, line: String) {
    file.parentFile?.mkdirs()
    FileOutputStream(file, true).use { out ->
        out.write((line + "\n").toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
}

internal fun writeFileAtomic(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.${java.util.UUID.randomUUID().toString().take(6)}.tmp")
    FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
    if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("Could not replace ${file.name}")
        }
    }
}

/** One record of a sort: ordered by (key, path, seq). */
internal class SortRec(val key: String, val path: String, val seq: Long, val text: String)

private fun esc(s: String): String {
    if (s.none { it == '\\' || it == '\n' || it == '\r' || it == '\u0000' }) return s
    val sb = StringBuilder(s.length + 4)
    for (c in s) when (c) {
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\u0000' -> sb.append("\\0")
        else -> sb.append(c)
    }
    return sb.toString()
}

private fun unesc(s: String): String {
    if (!s.contains('\\')) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                '0' -> sb.append('\u0000')
                else -> sb.append(s[i + 1])
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

private fun encode(r: SortRec): String = esc(r.key) + "\u0000" + esc(r.path) + "\u0000" + r.seq + "\u0000" + r.text

private fun decode(line: String): SortRec {
    val a = line.indexOf('\u0000')
    val b = line.indexOf('\u0000', a + 1)
    val c = line.indexOf('\u0000', b + 1)
    return SortRec(unesc(line.substring(0, a)), unesc(line.substring(a + 1, b)), line.substring(b + 1, c).toLong(), line.substring(c + 1))
}

private val ORDER = Comparator<SortRec> { x, y ->
    val k = x.key.compareTo(y.key)
    if (k != 0) k else {
        val p = x.path.compareTo(y.path)
        if (p != 0) p else x.seq.compareTo(y.seq)
    }
}

/** A bounded-memory sort: runs of [chunk] records are spilled to [tmpDir] and merged. */
internal class ExternalSorter(private val tmpDir: File, private val chunk: Int, private val tag: String) {
    private var buffer = ArrayList<SortRec>()
    private val runs = ArrayList<File>()

    fun add(rec: SortRec) {
        buffer.add(rec)
        if (buffer.size >= chunk) spill()
    }

    private fun spill() {
        if (buffer.isEmpty()) return
        buffer.sortWith(ORDER)
        tmpDir.mkdirs()
        val file = File(tmpDir, "$tag-run-${runs.size}.srt")
        LineWriter(file).use { w ->
            for (r in buffer) w.write(encode(r))
            w.finish(false)
        }
        runs.add(file)
        buffer = ArrayList()
    }

    /** The records in order. The sorter is spent afterwards; run files are removed when the iterator ends or is closed. */
    fun sorted(): CloseableIterator<SortRec> {
        if (runs.isEmpty()) {
            buffer.sortWith(ORDER)
            val it = buffer.iterator()
            buffer = ArrayList()
            return object : CloseableIterator<SortRec> {
                override fun hasNext() = it.hasNext()
                override fun next() = it.next()
                override fun close() {}
            }
        }
        spill()
        class Head(val rec: SortRec, val reader: LineReader)
        val readers = runs.map { LineReader(it) }
        val heap = PriorityQueue<Head>(runs.size.coerceAtLeast(1)) { x, y -> ORDER.compare(x.rec, y.rec) }
        for (r in readers) if (r.hasNext()) heap.add(Head(decode(r.next()), r))
        return object : CloseableIterator<SortRec> {
            override fun hasNext() = heap.isNotEmpty()
            override fun next(): SortRec {
                val head = heap.poll() ?: throw NoSuchElementException()
                if (head.reader.hasNext()) heap.add(Head(decode(head.reader.next()), head.reader))
                if (heap.isEmpty()) close()
                return head.rec
            }

            override fun close() {
                for (r in readers) r.close()
                for (f in runs) f.delete()
                runs.clear()
            }
        }
    }
}

internal interface CloseableIterator<T> : Iterator<T>, Closeable
