// Back and forward through the pages the user visited: the mouse's back/forward side buttons and Alt+Left/Alt+Right.
// A page is the tab, the settings group, and on the Library tab its view and filter. Nothing here touches the browser history.
import {getLibFilter, getLibView, onLibFilter, onLibView, setLibFilter, setLibView, type LibFilter, type LibView} from './library/libNav'
import {getNav, navigate, onNav, type SettingsGroup, type Tab} from './nav'

// Where the page was scrolled: the panel that scrolled (a selector inside #main) and how far.
type Scroll = {pane: string; top: number}
type Page = {tab: Tab; group: SettingsGroup; view: LibView; filter: LibFilter; scroll?: Scroll}

const MAX = 50
let pages: Page[] = []
let at = -1
let pending = false

const current = (): Page => ({...getNav(), view: getLibView(), filter: getLibFilter()})
// The library view and filter only matter while the Library tab shows them.
const key = (page: Page) =>
  JSON.stringify(page.tab === 'Library' ? {...page, scroll: undefined} : page.tab === 'Settings' ? [page.tab, page.group] : [page.tab])

function record() {
  pending = false
  const page = current()
  if (at >= 0 && key(pages[at]) === key(page)) {
    pages[at] = {...page, scroll: pages[at].scroll}
    return
  }
  pages = [...pages.slice(0, at + 1), page].slice(-MAX)
  at = pages.length - 1
}

/** A selector for the panel that scrolled: `#main` itself, or the element inside it with these classes. */
function paneOf(el: HTMLElement): string | null {
  if (el.id === 'main') return '#main'
  if (!el.closest('#main') || !el.classList.length) return null
  return '#main ' + [...el.classList].map((c) => '.' + CSS.escape(c)).join('')
}

let restoring: {cancel: () => void} | null = null

// Each scroll is kept on the page it happened on (never on one the user is leaving: a navigation not recorded yet is skipped).
function scrolled(event: Event) {
  const el = event.target
  if (restoring || pending || at < 0 || !(el instanceof HTMLElement)) return
  if (key(pages[at]) !== key(current())) return
  const pane = paneOf(el)
  if (pane) pages[at] = {...pages[at], scroll: {pane, top: el.scrollTop}}
}

/** Back on a page: scroll its panel to where it was, as soon as the page has drawn enough to get there (about a second at most). */
function restore(scroll: Scroll) {
  restoring?.cancel()
  const started = performance.now()
  let frame = 0
  const stop = () => {
    cancelAnimationFrame(frame)
    window.removeEventListener('wheel', stop, true)
    window.removeEventListener('pointerdown', stop, true)
    window.removeEventListener('keydown', stop, true)
    restoring = null
  }
  // the user scrolls or clicks meanwhile: they win
  window.addEventListener('wheel', stop, true)
  window.addEventListener('pointerdown', stop, true)
  window.addEventListener('keydown', stop, true)
  restoring = {cancel: stop}
  const step = () => {
    const el = document.querySelector<HTMLElement>(scroll.pane)
    if (el) {
      el.scrollTop = scroll.top
      if (Math.abs(el.scrollTop - scroll.top) < 2) return stop()
    }
    if (performance.now() - started > 1200) return stop()
    frame = requestAnimationFrame(step)
  }
  frame = requestAnimationFrame(step)
}

// One user action can change several stores at once (tab, then view): they are recorded as one page.
function schedule() {
  if (pending) return
  pending = true
  queueMicrotask(record)
}

function go(step: -1 | 1) {
  if (pending) record()
  const next = at + step
  if (next < 0 || next >= pages.length) return
  at = next
  const page = pages[at]
  // a restore still running belongs to the page being left
  restoring?.cancel()
  setLibFilter(page.filter)
  setLibView(page.view)
  navigate(page.tab, page.group)
  if (page.scroll) restore(page.scroll)
}

export const goBack = () => go(-1)
export const goForward = () => go(1)
/** True when there is a page to go back to. */
export const canGoBack = () => (pending ? true : at > 0)

const editable = (target: EventTarget | null) =>
  target instanceof HTMLElement && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName))

/** Starts recording pages and listening for the back/forward buttons; returns the cleanup. */
export function installHistory() {
  pages = [current()]
  at = 0
  const stops = [onNav(schedule), onLibView(schedule), onLibFilter(schedule)]
  // Mouse buttons 3 and 4 are the side buttons ("back" and "forward").
  const mouse = (event: MouseEvent) => {
    if (event.button !== 3 && event.button !== 4) return
    event.preventDefault()
    if (event.type === 'mouseup') go(event.button === 3 ? -1 : 1)
  }
  const keys = (event: KeyboardEvent) => {
    if (!event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || editable(event.target)) return
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
    event.preventDefault()
    go(event.key === 'ArrowLeft' ? -1 : 1)
  }
  window.addEventListener('mousedown', mouse)
  window.addEventListener('mouseup', mouse)
  window.addEventListener('keydown', keys)
  // scroll does not bubble: listen in the capture phase to see every panel
  window.addEventListener('scroll', scrolled, true)
  return () => {
    stops.forEach((stop) => stop())
    restoring?.cancel()
    window.removeEventListener('mousedown', mouse)
    window.removeEventListener('mouseup', mouse)
    window.removeEventListener('keydown', keys)
    window.removeEventListener('scroll', scrolled, true)
  }
}
