package app.keepfin.core.spaces

import app.keepfin.core.music.jsTrim

// Where a space keeps its files inside the Keepfin folder, and how two folders relate (dev-docs/plans/KEEPFIN-FOLDER-MOVE.md).
// Both apps decide these the same way: new spaces, "Use the default folder", the outside-the-folder notice and Change location.

private val builtinFolders = mapOf("downloads" to "Downloads", "music" to "Music", "videos" to "Videos")
private const val FORBIDDEN = "\\/:*?\"<>|"

/** The folder name a space gets in the Keepfin folder: Downloads, Music and Videos for the built-in spaces, otherwise its name made safe. */
fun defaultFolderName(id: String, name: String): String {
    builtinFolders[id]?.let { return it }
    val safe = buildString { for (c in name) append(if (c < ' ' || c in FORBIDDEN) '_' else c) }
    var out = jsTrim(safe)
    while (out.startsWith('.')) out = out.substring(1)
    while (out.endsWith('.') || out.endsWith(' ')) out = out.dropLast(1)
    return out.ifEmpty { "Space" }
}

/** [defaultFolderName] inside [root], with the separator [root] already uses. */
fun defaultSpaceFolder(root: String, id: String, name: String): String {
    val sep = if ('\\' in root) '\\' else '/'
    return root.trimEnd('\\', '/') + sep + defaultFolderName(id, name)
}

/** `<parent>\Keepfin`, with the separator [parent] uses (a bare drive like `F:` gets `\`). */
fun keepfinInside(parent: String): String {
    val sep = if ('\\' in parent || (parent.length == 2 && parent[1] == ':')) '\\' else '/'
    return parent.trimEnd('\\', '/') + sep + "Keepfin"
}

/**
 * The Keepfin folder for a folder the person picked (first start, setup, Change location): a folder named Keepfin, or one that
 * already is a Keepfin folder, is used as it is; any other folder gets a Keepfin folder inside it, so the library keeps its name
 * and stays apart from what is there.
 */
fun keepfinFolderFor(picked: String, isKeepfinFolder: Boolean): String {
    val trimmed = picked.trimEnd('\\', '/')
    val name = trimmed.substring(maxOf(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/')) + 1)
    return if (isKeepfinFolder || name.equals("keepfin", ignoreCase = true)) picked else keepfinInside(picked)
}

private fun norm(path: String) = path.replace('\\', '/').trimEnd('/').lowercase()

/** Whether [file] is [folder] itself or inside it. Separators and letter case do not matter, as on Windows and Android storage. */
fun isInFolder(folder: String, file: String): Boolean {
    val base = norm(folder)
    val target = norm(file)
    return target == base || target.startsWith("$base/")
}

/**
 * Where [file] is after the folder [from] moved to [to]: the same path below [to], with [to]'s separator; null when [file] was not
 * in [from]. Download history and anything else that saved a path follows a moved Keepfin folder or space with it.
 */
fun movedPath(from: String, to: String, file: String): String? {
    if (!isInFolder(from, file)) return null
    val rest = file.replace('\\', '/').substring(norm(from).length).trimStart('/')
    if (rest.isEmpty()) return to
    val sep = if ('\\' in to || (to.length == 2 && to[1] == ':')) '\\' else '/'
    return to.trimEnd('\\', '/') + sep + rest.replace('/', sep)
}

/** Why the Keepfin folder at [from] cannot move to [to] by where the two are, in plain words; null when it can. */
fun folderMoveProblem(from: String, to: String): String? = when {
    isInFolder(from, to) -> "The new place cannot be inside your Keepfin folder."
    isInFolder(to, from) -> "Choose a folder that is not above your Keepfin folder."
    else -> null
}
