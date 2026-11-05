package app.keepfin.core.cloud

// A small XML reader for WebDAV multistatus bodies (RFC 4918): elements, text, CDATA, entities. Namespace prefixes are
// dropped (`d:getetag` reads as `getetag`); attributes are ignored. Not a general XML parser: DTDs are rejected.

internal class XNode(val name: String, val children: MutableList<XNode> = ArrayList(), val text: StringBuilder = StringBuilder()) {
    fun child(local: String): XNode? = children.firstOrNull { it.name == local }
    fun all(local: String): List<XNode> = children.filter { it.name == local }
    fun content(): String = text.toString().trim()

    /** Depth-first search for the first descendant with this local name. */
    fun find(local: String): XNode? {
        for (c in children) {
            if (c.name == local) return c
            val deeper = c.find(local)
            if (deeper != null) return deeper
        }
        return null
    }
}

internal class XmlException(message: String) : IllegalArgumentException(message)

private fun localName(raw: String): String {
    val colon = raw.indexOf(':')
    return if (colon >= 0) raw.substring(colon + 1) else raw
}

private fun decodeEntities(s: String): String {
    if (s.indexOf('&') < 0) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '&') {
            sb.append(c)
            i++
            continue
        }
        val end = s.indexOf(';', i)
        if (end < 0 || end - i > 10) {
            sb.append(c)
            i++
            continue
        }
        val ent = s.substring(i + 1, end)
        val rep: String? = when {
            ent == "amp" -> "&"
            ent == "lt" -> "<"
            ent == "gt" -> ">"
            ent == "quot" -> "\""
            ent == "apos" -> "'"
            ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { codePointString(it) }
            ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { codePointString(it) }
            else -> null
        }
        if (rep == null) {
            sb.append(c)
            i++
        } else {
            sb.append(rep)
            i = end + 1
        }
    }
    return sb.toString()
}

private fun codePointString(cp: Int): String? {
    if (cp < 0 || cp > 0x10FFFF || (cp in 0xD800..0xDFFF)) return null
    if (cp < 0x10000) return cp.toChar().toString()
    val v = cp - 0x10000
    return (0xD800 + (v shr 10)).toChar().toString() + (0xDC00 + (v and 0x3FF)).toChar().toString()
}

/** Parses [text] and returns the root element. Throws [XmlException] on structure that is not well formed. */
internal fun parseXml(text: String): XNode {
    var i = 0
    val stack = ArrayList<XNode>()
    var root: XNode? = null
    val n = text.length
    while (i < n) {
        val lt = text.indexOf('<', i)
        if (lt < 0) {
            if (stack.isNotEmpty()) throw XmlException("unexpected end")
            break
        }
        if (lt > i && stack.isNotEmpty()) stack.last().text.append(decodeEntities(text.substring(i, lt)))
        i = lt
        when {
            text.startsWith("<!--", i) -> {
                val e = text.indexOf("-->", i + 4)
                if (e < 0) throw XmlException("unterminated comment")
                i = e + 3
            }
            text.startsWith("<![CDATA[", i) -> {
                val e = text.indexOf("]]>", i + 9)
                if (e < 0) throw XmlException("unterminated CDATA")
                if (stack.isNotEmpty()) stack.last().text.append(text.substring(i + 9, e))
                i = e + 3
            }
            text.startsWith("<?", i) -> {
                val e = text.indexOf("?>", i + 2)
                if (e < 0) throw XmlException("unterminated declaration")
                i = e + 2
            }
            text.startsWith("<!", i) -> throw XmlException("DTDs are not accepted")
            text.startsWith("</", i) -> {
                val e = text.indexOf('>', i)
                if (e < 0) throw XmlException("unterminated tag")
                val name = localName(text.substring(i + 2, e).trim())
                if (stack.isEmpty() || stack.last().name != name) throw XmlException("mismatched </$name>")
                stack.removeAt(stack.size - 1)
                i = e + 1
            }
            else -> {
                // a start tag; find its end outside quotes
                var j = i + 1
                var quote = 0.toChar()
                while (j < n) {
                    val c = text[j]
                    if (quote != 0.toChar()) {
                        if (c == quote) quote = 0.toChar()
                    } else if (c == '"' || c == '\'') {
                        quote = c
                    } else if (c == '>') {
                        break
                    }
                    j++
                }
                if (j >= n) throw XmlException("unterminated tag")
                var inner = text.substring(i + 1, j)
                val selfClosing = inner.endsWith("/")
                if (selfClosing) inner = inner.substring(0, inner.length - 1)
                var k = 0
                while (k < inner.length && inner[k] != ' ' && inner[k] != '\t' && inner[k] != '\n' && inner[k] != '\r') k++
                val node = XNode(localName(inner.substring(0, k)))
                if (stack.isNotEmpty()) stack.last().children.add(node) else if (root == null) root = node else throw XmlException("two roots")
                if (!selfClosing) stack.add(node)
                i = j + 1
            }
        }
    }
    if (stack.isNotEmpty()) throw XmlException("unclosed <${stack.last().name}>")
    return root ?: throw XmlException("no element")
}
