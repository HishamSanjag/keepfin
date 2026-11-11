// What each page shows and how big its cards are, kept on this computer. Saved text always goes through the shared core, which
// keeps the saved order, drops unknown sections, adds new ones and clamps the card size.
import {useSyncExternalStore} from 'react'
import {defaultLayout, readCardPrefs, readLayout, type CardPrefs, type CardText, type LayoutPage, type PageLayout} from '../../shared/core/layout'
import {readPreference, savePreference} from '../prefs'

const LAYOUT_KEY: Record<LayoutPage, string> = {
  home: 'keepfin-layout-home',
  library: 'keepfin-layout-library',
}
const CARD_KEY = 'keepfin-card-prefs'

const layouts: Record<LayoutPage, PageLayout> = {
  home: readLayout('home', readPreference<unknown>(LAYOUT_KEY.home, undefined)),
  library: readLayout('library', readPreference<unknown>(LAYOUT_KEY.library, undefined)),
}
let cardPrefs = readCardPrefs(readPreference<unknown>(CARD_KEY, undefined))
const listeners = new Set<() => void>()
const changed = () => listeners.forEach((listener) => listener())
const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export const getPageLayout = (page: LayoutPage) => layouts[page]
export const getCardPrefs = () => cardPrefs
export const usePageLayout = (page: LayoutPage) => useSyncExternalStore(subscribe, () => getPageLayout(page))
export const useCardPrefs = () => useSyncExternalStore(subscribe, getCardPrefs)

export function updatePageLayout(page: LayoutPage, edit: (layout: PageLayout) => PageLayout) {
  layouts[page] = edit(layouts[page])
  savePreference(LAYOUT_KEY[page], layouts[page])
  changed()
}
export const resetPageLayout = (page: LayoutPage) => updatePageLayout(page, () => defaultLayout(page))

function updateCardPrefs(next: Partial<CardPrefs>) {
  cardPrefs = readCardPrefs({...cardPrefs, ...next})
  savePreference(CARD_KEY, cardPrefs)
  changed()
}
export const setCardScale = (scale: number) => updateCardPrefs({scale})
/** Moves the card size by `delta` (0.05 is one step), to the nearest hundredth so repeated steps do not drift. */
export const stepCardScale = (delta: number) => setCardScale(Math.round((cardPrefs.scale + delta) * 100) / 100)
export const setCardText = (text: CardText) => updateCardPrefs({text})
