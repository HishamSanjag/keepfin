// Going to another page puts the player away: the lyrics view and the expanded player both sit over the page, so a click in the
// sidebar (Home, Downloads, a space, Search...) would otherwise change a page the person cannot see.
import {onLibFilter, onLibView} from '../library/libNav'
import {onNav} from '../nav'
import {closeLyrics} from './panels'
import {getPlayer, setExpanded} from './playerStore'

export function dismissPlayerSurfaces() {
  closeLyrics()
  if (getPlayer().expanded) setExpanded(false)
}

/** Listens to every kind of navigation; returns the function that stops listening. */
export function installDismissOnNavigate() {
  const stops = [onNav(dismissPlayerSurfaces), onLibView(dismissPlayerSurfaces), onLibFilter(dismissPlayerSurfaces)]
  return () => stops.forEach((stop) => stop())
}
