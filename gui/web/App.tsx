/* THESIS: A capture workbench that reads like a pro media tool, not a downloader skin. One command
 *   field, the real format table with sizes, and a live transfer list; refuses the dashboard-of-cards
 *   and the bare format dropdown.
 * OWN-WORLD: Ink graphite planes (#10151E canvas, #0C1118 rail), cobalt #4C7EFF reserved for action,
 *   selection and progress; capture-loop mark; Segoe UI Variable; hairline open rows, tabular figures.
 * STORY: Paste anywhere, see every quality with codec, HDR and size, press one button that states the
 *   result, watch a single row move, open the file.
 * FIRST VIEWPORT: 216px rail (mark, three destinations, live engine/throughput footer); 52px command
 *   field across the content column; review table below it; transfers under that.
 * FORM: Desktop workbench, rail + single working column; container-query responsive (icon rail,
 *   then bottom bar).
 */
import {lazy, Suspense, useEffect, useRef, useState} from 'react'
import {useOverlay} from './player/play'
import {native} from './bridge'
import {captureText, focusCapture} from './capture/useCapture'
import {DownloadsScreen} from './DownloadsScreen'
import {extractLinks} from './format'
import {Home} from './library/Home'
import {Library, useLibraryBoot} from './library/Library'
import {SearchScreen} from './library/SearchScreen'
import {LibraryDialogs} from './library/Dialogs'
import {useLibraryEnabled} from './library/libStore'
import {NowPlayingBar} from './player/NowPlaying'
import {LyricsOverlay, NowPlayingPanel} from './player/NowPlayingPanel'
import {closeDrawer, toggleRail, useRailMode} from './shell/layoutState'
import {usePanels} from './player/panels'
import {getPlayer, togglePlay, usePlayer} from './player/playerStore'
import {FullPlayer} from './player/FullPlayer'
import {ImageViewer} from './player/ImageViewer'
import {MoveWindow} from './setup/MoveWindow'
import {SetupWindow} from './setup/SetupWindow'
import {useStorageGate} from './setup/gate'
import {StartPages} from './start/StartPages'
import {useApplyStartMode} from './mode'
import {applyStartPage, libraryTabs, navigate, rememberTab, useNav} from './nav'
import {installHistory} from './history'
import {SettingsScreen} from './settings/Settings'
import {PairingHost} from './settings/devices/PairingHost'
import {Sidebar} from './shell/Sidebar'
import {Toasts} from './shell/Toasts'
import {AppBar, focusSearch} from './shell/AppBar'
import {startActivity} from './activity'
import {UpdatePrompt} from './shell/UpdatePrompt'
import {onFollowFound, onReveal, requestGroupFocus, useMeta} from './store'
import {reportCheck} from './library/collectionActions'
import {startUpdates} from './updates'
import {Mark} from './ui/Icon'
import {setCardScale, stepCardScale, getCardPrefs} from './media/layoutStore'
import {toast} from './toasts'

const VideoPlayer = lazy(() => import('./player/VideoPlayer').then((module) => ({default: module.VideoPlayer})))

function VideoHost() {
  const {video} = useOverlay()
  if (!video) return null
  return (
    <Suspense fallback={null}>
      <VideoPlayer />
    </Suspense>
  )
}

function editable(target: EventTarget | null) {
  const element = target as HTMLElement | null
  return !!element && (element.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(element.tagName))
}

/** Elements that use Space themselves (buttons, switches, tabs, menu items, sliders). */
function pressable(target: EventTarget | null) {
  const element = target as HTMLElement | null
  return !!element?.closest?.('button, a[href], summary, video, audio, [role=button], [role=radio], [role=tab], [role=switch], [role=menuitem], [role=menuitemradio], [role=checkbox], [role=slider], [role=option], [role=separator]')
}

const CARD_STEP = 0.05

/** Ctrl + / Ctrl − / Ctrl 0 and Ctrl + wheel over the page change Card size, never while typing. */
function useCardSizeKeys() {
  useEffect(() => {
    const announce = () => toast({tone: 'info', key: 'card-size', duration: 1500, message: `Card size ${Math.round(getCardPrefs().scale * 100)}%`})
    const keydown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.altKey || editable(event.target)) return
      if (event.key === '+' || event.key === '=') stepCardScale(CARD_STEP)
      else if (event.key === '-' || event.key === '_') stepCardScale(-CARD_STEP)
      else if (event.key === '0') setCardScale(1)
      else return
      event.preventDefault()
      announce()
    }
    const wheel = (event: WheelEvent) => {
      if (!event.ctrlKey || editable(document.activeElement) || !(event.target as Element).closest('#main')) return
      event.preventDefault()
      stepCardScale(event.deltaY < 0 ? CARD_STEP : -CARD_STEP)
      announce()
    }
    window.addEventListener('keydown', keydown)
    window.addEventListener('wheel', wheel, {passive: false})
    return () => {
      window.removeEventListener('keydown', keydown)
      window.removeEventListener('wheel', wheel)
    }
  }, [])
}

function useGlobalCapture() {
  const [dragging, setDragging] = useState(false)
  useEffect(() => {
    const keydown = (event: KeyboardEvent) => {
      const command = (event.ctrlKey || event.metaKey) && !event.shiftKey && !event.altKey
      if (command && event.key.toLowerCase() === 'b') {
        event.preventDefault()
        toggleRail()
      } else if (command && event.key.toLowerCase() === 'l') {
        event.preventDefault()
        navigate('Downloads')
        requestAnimationFrame(focusCapture)
      } else if (command && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        focusSearch()
      } else if (command && event.key === ',') {
        event.preventDefault()
        navigate('Settings', 'downloads')
      } else if (!event.ctrlKey && !event.metaKey && !event.altKey && !editable(event.target) && !document.querySelector('dialog[open]')) {
        if (event.key === '/') {
          if (focusSearch()) event.preventDefault()
        } else if (event.key === ' ' && !pressable(event.target) && !document.querySelector('.video-view, .image-view') && getPlayer().currentId) {
          // Space plays or pauses the song, unless it would press the control that has the focus.
          event.preventDefault()
          togglePlay()
        }
      }
    }
    const paste = (event: ClipboardEvent) => {
      if (editable(event.target) || document.querySelector('dialog[open]')) return
      const text = event.clipboardData?.getData('text/plain') || ''
      if (!extractLinks(text).length) return
      event.preventDefault()
      navigate('Downloads')
      captureText(text)
    }
    let depth = 0
    // A drag that started inside the window (reordering the queue or a playlist, moving items to a space) is never a dropped
    // link, even when it carries plain text: only drags from outside open the drop overlay.
    let inside = false
    const start = () => {
      inside = true
    }
    const end = () => {
      inside = false
    }
    const hasText = (event: DragEvent) =>
      !inside && !!event.dataTransfer && [...event.dataTransfer.types].some((type) => type === 'text/uri-list' || type === 'text/plain')
    const enter = (event: DragEvent) => {
      if (!hasText(event)) return
      depth++
      setDragging(true)
    }
    const over = (event: DragEvent) => {
      if (!hasText(event)) return
      event.preventDefault()
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
    }
    const leave = () => {
      depth = Math.max(0, depth - 1)
      if (!depth) setDragging(false)
    }
    const drop = (event: DragEvent) => {
      depth = 0
      setDragging(false)
      if (!hasText(event)) return end()
      event.preventDefault()
      const text = event.dataTransfer?.getData('text/uri-list') || event.dataTransfer?.getData('text/plain') || ''
      navigate('Downloads')
      captureText(text)
    }
    window.addEventListener('keydown', keydown)
    document.addEventListener('paste', paste)
    window.addEventListener('dragstart', start, true)
    window.addEventListener('dragend', end, true)
    // A reordered row can be re-rendered mid-drag, so its dragend may never reach the window; the next ordinary mouse move ends it.
    window.addEventListener('mousemove', end, true)
    window.addEventListener('dragenter', enter)
    window.addEventListener('dragover', over)
    window.addEventListener('dragleave', leave)
    window.addEventListener('drop', drop)
    const unsubscribe = onReveal((tab, group) => {
      navigate(group ? 'Downloads' : tab)
      if (group) window.dispatchEvent(new CustomEvent('keepfin-downloads-filter', {detail: 'finished'}))
      // After the tab switch has rendered, so a row on the screen being hidden cannot claim it.
      if (group) setTimeout(() => requestGroupFocus(group), 80)
    })
    const stopFound = onFollowFound((found) => reportCheck(found.id, found.name, found))
    return () => {
      stopFound()
      window.removeEventListener('keydown', keydown)
      document.removeEventListener('paste', paste)
      window.removeEventListener('dragstart', start, true)
      window.removeEventListener('dragend', end, true)
      window.removeEventListener('mousemove', end, true)
      window.removeEventListener('dragenter', enter)
      window.removeEventListener('dragover', over)
      window.removeEventListener('dragleave', leave)
      window.removeEventListener('drop', drop)
      unsubscribe()
    }
  }, [])
  return dragging
}

export function App() {
  const {tab} = useNav()
  const libraryOn = useLibraryEnabled()
  useLibraryBoot(libraryOn)
  const dragging = useGlobalCapture()
  useCardSizeKeys()
  const railMode = useRailMode()
  const panels = usePanels()
  const hasSong = usePlayer((s) => !!s.currentId)
  const nowPanel = libraryOn && hasSong && panels.nowPlaying
  useEffect(() => startUpdates(), [])
  useEffect(() => startActivity(), [])
  // Mouse back/forward side buttons and Alt+Left/Right step through the pages visited.
  useEffect(() => installHistory(), [])
  const meta = useMeta()
  const started = useRef(false)
  // "Open Keepfin on" is applied once, when the saved settings first arrive.
  useEffect(() => {
    if (!meta.ready || started.current) return
    started.current = true
    applyStartPage(meta.settings.startPage, libraryOn)
  }, [meta.ready, meta.settings.startPage, libraryOn])
  // Downloader-only mode shows no library surfaces: a stale route (or switching mode while on one) lands on Downloads.
  useEffect(() => {
    if (!libraryOn && libraryTabs.includes(tab)) navigate('Downloads')
  }, [libraryOn, tab])
  useEffect(() => {
    if (started.current) rememberTab(tab)
  }, [tab])
  useEffect(() => {
    document.title = `${tab} · Keepfin`
  }, [tab])
  const storage = useStorageGate()
  useApplyStartMode(storage.state?.phase)
  // The Keepfin folder comes first: the start pages for new users, the setup window for an earlier version's files.
  if (!storage.state) return <div className="app setup-host" aria-busy="true" />
  if (storage.state.phase === 'firstStart')
    return (
      <div className="app setup-host">
        <StartPages state={storage.state} onDone={storage.done} />
      </div>
    )
  if (storage.state.phase === 'moveFolder')
    return (
      <div className="app setup-host">
        <MoveWindow state={storage.state} onDone={storage.done} />
      </div>
    )
  if (storage.state.phase === 'needsSetup' || storage.state.phase === 'resumeSetup')
    return (
      <div className="app setup-host">
        <SetupWindow state={storage.state} onDone={storage.done} />
      </div>
    )
  return (
    <div className="app" data-native={native || undefined} data-np={nowPanel || undefined} data-rail={railMode === 'full' ? undefined : railMode}>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <Sidebar />
      {/* The drawer of a narrow window: a click beside it closes it (so do Esc and choosing a page, in the Sidebar). */}
      {railMode === 'drawer' && <div className="rail-scrim" aria-hidden="true" onClick={closeDrawer} />}
      <main id="main" className="main" tabIndex={-1}>
        <AppBar search={libraryOn} />
        <DownloadsScreen hidden={tab !== 'Downloads'} />
        {libraryOn && tab === 'Home' && <Home />}
        {libraryOn && tab === 'Search' && <SearchScreen />}
        {libraryOn && tab === 'Library' && <Library />}
        {tab === 'Settings' && <SettingsScreen />}
      </main>
      {dragging && (
        <div className="drop-overlay" aria-hidden="true">
          <div className="drop-card">
            <Mark size={40} />
            <strong>Drop to inspect</strong>
            <span>Links in the dropped text are inspected right away.</span>
          </div>
        </div>
      )}
      {nowPanel && <NowPlayingPanel />}
      {libraryOn && hasSong && <LyricsOverlay />}
      {libraryOn && <NowPlayingBar />}
      {libraryOn && <FullPlayer />}
      {libraryOn && <VideoHost />}
      {libraryOn && <ImageViewer />}
      {libraryOn && <LibraryDialogs />}
      <UpdatePrompt />
      <PairingHost />
      <Toasts />
    </div>
  )
}
