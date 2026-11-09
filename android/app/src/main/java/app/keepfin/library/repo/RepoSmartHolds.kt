package app.keepfin.library.repo

import app.keepfin.ItemQuery
import app.keepfin.LibraryBrowse
import app.keepfin.PageDirection
import app.keepfin.PageKey
import app.keepfin.core.library.LibSpace
import app.keepfin.core.library.LibraryMapping

/**
 * The ids of the items the smart spaces [wireIds] gather: what a copy that holds a smart space holds. Read through the index like any list,
 * with the rules the Library screen uses. Favorites is a kept smart space every library has; a copy holds it as its own Favourites choice.
 */
internal fun smartItemIdsOf(spaces: List<LibSpace>, browse: LibraryBrowse, wireIds: Set<String>): Set<String> {
    val out = HashSet<String>()
    for (lib in spaces) {
        if (lib.type != "smart" || lib.id == "favorites" || LibraryMapping.spaceWireId(lib.id) !in wireIds) continue
        val rules = lib.toSpace().rules ?: continue
        var key: PageKey? = null
        var direction = PageDirection.FIRST
        while (true) {
            val page = browse.page(ItemQuery(smart = rules), direction, key, 1000)
            for (item in page.items) out.add(item.id)
            key = page.next ?: break
            direction = PageDirection.AFTER
        }
    }
    return out
}
