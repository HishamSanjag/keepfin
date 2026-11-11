import {memo, useEffect, useMemo, useRef, useState, type DragEvent, type KeyboardEvent as ReactKeyEvent, type MouseEvent, type PointerEvent, type ReactNode} from 'react'
import {focusCapture} from '../capture/useCapture'
import {speedLabel} from '../format'
import {native} from '../bridge'
import {setLibView, useLibView, type LibView} from '../library/libNav'
import {libraryAct, useLibrary, useLibrarySettings} from '../library/libStore'
import {SpaceMark, togglePin as toggleSpaceShown} from '../library/SpacesHub'
import {useSpaceCounts} from '../library/spacesModel'
import {openDialog} from '../library/dialogState'
import {useFullMode} from '../mode'
import {closeDrawer, getRailWidth, RAIL_DEFAULT, RAIL_MAX, RAIL_MIN, resetRailWidth, setRailWidth, toggleRail, useRailMode, useRailWidth} from './layoutState'
import {canHideTab, moveKey, setSidebarStyle, stepKey, toggleHidden, togglePinned, toggleTabHidden, updateSidebarPrefs, useSidebarPrefs, type NavTab} from './sidebarPrefs'
import {navigate, useNav} from '../nav'
import {useDevices} from '../settings/devices/useDevices'
import {useAggregate} from '../store'
import {useUpdates} from '../updates'
import {Art} from '../library/Art'
import {isPinned as isSpaceShown, type Space} from '../../shared/library'
import {ContextMenu, type MenuItem} from '../ui/Menu'
import {Button, IconButton, Switch} from '../ui/controls'
import {Icon, Mark} from '../ui/Icon'

const tabIcons: Record<NavTab, string> = {Home: 'home', Search: 'search', Library: 'library', Downloads: 'download'}

type OpenMenu = (event: MouseEvent, label: string, items: MenuItem[]) => void

/** The active-downloads summary at the bottom of the sidebar; it opens Downloads. */
function Transfers() {
  const aggregate = useAggregate()
  if (!aggregate.active) return null
  return (
    <button type="button" className="activity" onClick={() => navigate('Downloads')} aria-label={`${aggregate.active} active downloads`}>
      <span className="activity-bars" aria-hidden="true">
        <i />
        <i />
        <i />
      </span>
      <span className="activity-text tabular">
        {aggregate.active} active
        {aggregate.transferring > 0 && aggregate.speed > 0 && <span className="activity-speed"> · {speedLabel(aggregate.speed)}</span>}
      </span>
    </button>
  )
}

/** The sidebar's single status line: active downloads on the left, connected devices on the right. Empty when there is nothing to say. */
function StatusLine() {
  const {active} = useAggregate()
  const {state} = useDevices()
  if (!active && !state?.enabled) return null
  return (
    <div className="side-status-row">
      <Transfers />
      <DevicesStatus />
    </div>
  )
}

/** The last row: the version (opens About Keepfin), then Edit sidebar and Settings. */
function FootRow({editing, setEditing}: {editing: boolean; setEditing: (editing: boolean) => void}) {
  const {state} = useUpdates()
  const {tab, group} = useNav()
  const label = state?.current ?? 'About'
  return (
    <div className="side-foot-row">
      <button
        type="button"
        className="side-version tabular"
        aria-label={state?.current ? `About Keepfin, version ${state.current}` : 'About Keepfin'}
        aria-current={tab === 'Settings' && group === 'about' ? 'page' : undefined}
        title="About Keepfin"
        onClick={() => navigate('Settings', 'about')}
      >
        {label}
      </button>
      <IconButton
        icon={editing ? 'check' : 'edit'}
        className="side-edit-toggle"
        label={editing ? 'Done editing sidebar' : 'Edit sidebar'}
        aria-pressed={editing}
        onClick={() => setEditing(!editing)}
      />
      <IconButton icon="gear" label="Settings" aria-current={tab === 'Settings' ? 'page' : undefined} onClick={() => navigate('Settings', 'downloads')} />
    </div>
  )
}

/** While editing: what the controls do, the floating switch and Done. */
function EditBar({onDone}: {onDone: () => void}) {
  const {style} = useSidebarPrefs()
  return (
    <div className="side-editbar" role="group" aria-label="Edit sidebar">
      <p className="side-editbar-text">Drag to reorder. The eye hides a row; the pin keeps it on top.</p>
      <div className="side-editbar-row">
        <Switch label="Floating sidebar" checked={style === 'floating'} onChange={(on) => setSidebarStyle(on ? 'floating' : 'docked')} />
        <span className="side-editbar-label" aria-hidden="true">
          Floating
        </span>
        <Button size="sm" variant="primary" onClick={onDone}>
          Done
        </Button>
      </div>
    </div>
  )
}

function ActiveCount() {
  const {active} = useAggregate()
  return active ? <span className="nav-count tabular">{active}</span> : null
}

/** "Devices · 2 connected", only while devices are switched on; it opens the Devices page of Settings. */
function DevicesStatus() {
  const {state} = useDevices()
  if (!state?.enabled) return null
  const connected = state.paired.filter((device) => device.online).length
  return (
    <button type="button" className="side-status" onClick={() => navigate('Settings', 'devices')} aria-label={`Devices, ${connected} connected`}>
      <Icon name="layers" size={16} />
      <i className={`side-dot${connected ? ' on' : ''}`} aria-hidden="true" />
      <span className="side-status-text tabular">
        {connected} {connected === 1 ? 'device' : 'devices'}
      </span>
    </button>
  )
}

/** Drag and drop for one list while editing; a drag that started in another list is ignored. */
function useReorder<T extends string>(keys: readonly T[], save: (next: T[]) => void) {
  const dragging = useRef<T | undefined>(undefined)
  const [over, setOver] = useState<{key: T; after: boolean}>()
  const end = () => {
    dragging.current = undefined
    setOver(undefined)
  }
  const half = (event: DragEvent<HTMLElement>) => {
    const rect = event.currentTarget.getBoundingClientRect()
    return event.clientY > rect.top + rect.height / 2
  }
  return (key: T) => ({
    draggable: true,
    onDragStart: (event: DragEvent<HTMLElement>) => {
      event.dataTransfer.setData('application/x-keepfin-sidebar', key)
      event.dataTransfer.effectAllowed = 'move'
      dragging.current = key
    },
    onDragOver: (event: DragEvent<HTMLElement>) => {
      if (!dragging.current) return
      event.preventDefault()
      event.dataTransfer.dropEffect = 'move'
      setOver({key, after: half(event)})
    },
    onDrop: (event: DragEvent<HTMLElement>) => {
      const from = dragging.current
      if (!from) return
      event.preventDefault()
      save(moveKey(keys, from, key, half(event)))
      end()
    },
    onDragEnd: end,
    dropClass: over?.key === key && dragging.current && dragging.current !== key ? (over.after ? ' drop-after' : ' drop-before') : '',
    onStep: (delta: -1 | 1) => save(stepKey(keys, key, delta)),
  })
}

type DragProps = ReturnType<ReturnType<typeof useReorder>>

/** One row while editing: a handle (drag it, or focus it and use the arrow keys), the mark, the name and its controls. */
function EditRow({name, mark, hidden, drag, children}: {name: string; mark: ReactNode; hidden?: boolean; drag?: DragProps; children: ReactNode}) {
  const {dropClass, onStep, ...dragEvents} = drag ?? {dropClass: '', onStep: undefined}
  const onKey = (event: ReactKeyEvent) => {
    if (!onStep || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown')) return
    event.preventDefault()
    onStep(event.key === 'ArrowUp' ? -1 : 1)
  }
  return (
    <li className={`side-edit-row${hidden ? ' is-hidden' : ''}${dropClass}`} {...(drag ? dragEvents : {})}>
      {drag ? (
        <button type="button" className="side-grip" aria-label={`Move ${name}`} title="Drag, or use the arrow keys" onKeyDown={onKey}>
          <Icon name="drag" size={14} />
        </button>
      ) : (
        <span className="side-grip" aria-hidden="true" />
      )}
      {mark}
      <span className="side-name">{name}</span>
      {children}
    </li>
  )
}

const LIMIT = 60

type Entry = {
  key: string
  name: string
  count?: number
  view: LibView
  art?: string
  space?: Space
}

const sameView = (a: LibView, b: LibView) => JSON.stringify(a) === JSON.stringify(b)

function EntryMark({entry}: {entry: Entry}) {
  return entry.space ? (
    <SpaceMark space={entry.space} size={15} />
  ) : (
    <span className="side-cover" aria-hidden="true">
      {entry.art ? <Art id={entry.art} hasArt kind="audio" /> : <Icon name="queue" size={15} />}
    </span>
  )
}

/** What you keep: pinned rows, then spaces and playlists, as a quiet list. Hidden when the sidebar is icons only. */
function YourLibrary({editing, openMenu, startEditing}: {editing: boolean; openMenu: OpenMenu; startEditing: () => void}) {
  const lib = useLibrary()
  const settings = useLibrarySettings()
  const prefs = useSidebarPrefs()
  const view = useLibView()
  const {tab} = useNav()
  const counts = useSpaceCounts(settings?.spaces ?? [])
  const spaces = useMemo<Entry[]>(() => {
    if (!settings || !lib.loaded) return []
    return settings.spaces.map((space) => ({
      key: `space:${space.id}`,
      name: space.name,
      count: counts.get(space.id) ?? 0,
      view: {type: 'space', id: space.id} as LibView,
      space,
    }))
  }, [settings, lib.loaded, counts])
  const playlists = useMemo<Entry[]>(() => {
    if (!settings || !lib.loaded) return []
    return [...lib.playlists, ...lib.downloaded].slice(0, LIMIT).map((collection) => {
      const cover =
        (collection.coverItemId && lib.byId.get(collection.coverItemId)) || collection.itemIds.map((id) => lib.byId.get(id)).find((item) => item?.hasArt)
      const own = collection.kind === 'own'
      return {
        key: `${own ? 'playlist' : 'collection'}:${collection.id}`,
        name: collection.name,
        view: own ? ({type: 'playlist', id: collection.id} as LibView) : ({type: 'collection', id: collection.id} as LibView),
        art: cover && cover.hasArt ? cover.id : undefined,
      }
    })
  }, [settings, lib.loaded, lib.playlists, lib.downloaded, lib.byId])
  const spaceOrder = useMemo(() => spaces.map((entry) => entry.key), [spaces])
  const reorderPinned = useReorder(prefs.pinned, (pinned) => updateSidebarPrefs(() => ({pinned})))
  const reorderSpaces = useReorder(spaceOrder, (keys) => void libraryAct('spaceReorder', undefined, {ids: keys.map((key) => key.slice('space:'.length))}))
  if (!settings) return null

  const byKey = new Map([...spaces, ...playlists].map((entry) => [entry.key, entry]))
  const pinned = prefs.pinned.flatMap((key) => byKey.get(key) ?? [])
  const isHidden = (entry: Entry) => (entry.space ? !isSpaceShown(entry.space) : prefs.hidden.includes(entry.key))
  const notPinned = (entry: Entry) => !prefs.pinned.includes(entry.key)
  const shownSpaces = spaces.filter((entry) => notPinned(entry) && (editing || !isHidden(entry)))
  const shownPlaylists = playlists.filter((entry) => notPinned(entry) && (editing || !isHidden(entry)))

  const hide = (entry: Entry) => (entry.space ? void toggleSpaceShown(entry.space) : toggleHidden(entry.key))
  const hideLabel = (entry: Entry) => (isHidden(entry) ? `Show ${entry.name}` : `Hide ${entry.name}`)
  const menuFor = (entry: Entry): MenuItem[] => [
    {label: prefs.pinned.includes(entry.key) ? 'Unpin from top' : 'Pin to top', icon: 'pin', onSelect: () => togglePinned(entry.key)},
    ...(prefs.pinned.includes(entry.key)
      ? []
      : [{label: entry.space ? 'Hide from sidebar and Home' : 'Hide from sidebar', icon: 'eyeOff', disabled: !!entry.space && !native, onSelect: () => hide(entry)}]),
    'separator',
    {label: 'Edit sidebar', icon: 'edit', onSelect: startEditing},
  ]

  const row = (entry: Entry) => {
    const selected = tab === 'Library' && sameView(view, entry.view)
    return (
      <li key={entry.key}>
        <button
          type="button"
          className={`side-item side-entry${selected ? ' selected' : ''}`}
          aria-current={selected ? 'page' : undefined}
          onClick={() => {
            setLibView(entry.view)
            navigate('Library')
          }}
          onContextMenu={(event) => openMenu(event, entry.name, menuFor(entry))}
        >
          <EntryMark entry={entry} />
          <span className="side-name">{entry.name}</span>
          {entry.count !== undefined && <span className="side-count tabular">{entry.count.toLocaleString()}</span>}
        </button>
      </li>
    )
  }
  const editRow = (entry: Entry, drag?: DragProps) => {
    const isPinnedTop = prefs.pinned.includes(entry.key)
    return (
      <EditRow key={entry.key} name={entry.name} mark={<EntryMark entry={entry} />} hidden={!isPinnedTop && isHidden(entry)} drag={drag}>
        <IconButton
          icon="pin"
          size="sm"
          className={`side-pin${isPinnedTop ? ' on' : ''}`}
          label={isPinnedTop ? `Unpin ${entry.name}` : `Pin ${entry.name} to top`}
          aria-pressed={isPinnedTop}
          onClick={() => togglePinned(entry.key)}
        />
        {!isPinnedTop && <IconButton icon={isHidden(entry) ? 'eyeOff' : 'eye'} size="sm" label={hideLabel(entry)} disabled={!!entry.space && !native} onClick={() => hide(entry)} />}
      </EditRow>
    )
  }
  const list = (label: string, entries: Entry[], drag?: (key: string) => DragProps, empty?: string) => (
    <ul className={`your-list${editing ? ' is-editing' : ''}`} aria-label={label}>
      {entries.map((entry) => (editing ? editRow(entry, drag?.(entry.key)) : row(entry)))}
      {!entries.length && empty && <li className="side-empty muted small">{lib.loaded ? empty : 'Loading…'}</li>}
    </ul>
  )
  return (
    <section className="your-lib" aria-label="Your library panel">
      <nav className="your-nav" aria-label="Your library">
        {(pinned.length > 0 || editing) && (
          <>
            <div className="side-label side-label-row">
              <span>Pinned</span>
              <Icon name="pin" size={13} />
            </div>
            {list('Pinned', pinned, reorderPinned)}
            {editing && !pinned.length && <p className="side-hint">Use the pin on any space or playlist to keep it here.</p>}
          </>
        )}
        {/* a section stays while it has rows or nothing exists yet; it goes when everything in it is pinned or hidden */}
        {(shownSpaces.length > 0 || !spaces.length) && (
          <>
            <div className="side-label side-label-row">
              <span>Spaces</span>
              <IconButton icon="plus" size="sm" label="New space" disabled={!native} onClick={() => openDialog({type: 'space'})} />
            </div>
            {list('Spaces', shownSpaces, reorderSpaces, 'No spaces yet.')}
          </>
        )}
        {(shownPlaylists.length > 0 || !playlists.length) && (
          <>
            <div className="side-label">Playlists</div>
            {list('Playlists', shownPlaylists, undefined, 'No playlists yet.')}
          </>
        )}
      </nav>
    </section>
  )
}

/** Drag or arrow-key the sidebar's right edge. Double-click or Enter resets. The width is remembered on this computer. */
function RailResize() {
  const width = useRailWidth()
  const drag = useRef<{x: number; width: number} | null>(null)
  const zoom = () => Number(getComputedStyle(document.documentElement).getPropertyValue('--zoom')) || 1
  const onDown = (event: PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return
    event.currentTarget.setPointerCapture(event.pointerId)
    drag.current = {x: event.clientX, width: getRailWidth()}
    document.documentElement.dataset.railResizing = ''
  }
  const onMove = (event: PointerEvent<HTMLDivElement>) => {
    if (drag.current) setRailWidth(drag.current.width + (event.clientX - drag.current.x) / zoom())
  }
  const onUp = () => {
    drag.current = null
    delete document.documentElement.dataset.railResizing
  }
  const onKey = (event: ReactKeyEvent<HTMLDivElement>) => {
    const step = event.shiftKey ? 32 : 8
    if (event.key === 'ArrowRight') setRailWidth(getRailWidth() + step)
    else if (event.key === 'ArrowLeft') setRailWidth(getRailWidth() - step)
    else if (event.key === 'Home') setRailWidth(RAIL_MIN)
    else if (event.key === 'End') setRailWidth(RAIL_MAX)
    else if (event.key === 'Enter') resetRailWidth()
    else return
    event.preventDefault()
  }
  return (
    <div
      className="rail-resize"
      role="separator"
      aria-orientation="vertical"
      aria-label="Resize sidebar"
      aria-valuemin={RAIL_MIN}
      aria-valuemax={RAIL_MAX}
      aria-valuenow={width}
      tabIndex={0}
      title={`Drag to resize, double-click to reset (${RAIL_DEFAULT} px)`}
      onPointerDown={onDown}
      onPointerMove={onMove}
      onPointerUp={onUp}
      onPointerCancel={onUp}
      onDoubleClick={resetRailWidth}
      onKeyDown={onKey}
    />
  )
}

export const Sidebar = memo(function Sidebar() {
  const {tab} = useNav()
  const {active} = useAggregate()
  const full = useFullMode()
  const prefs = useSidebarPrefs()
  const mode = useRailMode()
  const collapsed = mode === 'collapsed'
  const view = useLibView()
  const width = useRailWidth()
  const [editing, setEditing] = useState(false)
  const [menu, setMenu] = useState<{label: string; items: MenuItem[]; at: {x: number; y: number}}>()
  const openMenu: OpenMenu = (event, label, items) => {
    event.preventDefault()
    setMenu({label, items, at: {x: event.clientX, y: event.clientY}})
  }
  const tabs: NavTab[] = full ? prefs.nav.filter((entry) => editing || !prefs.hiddenNav.includes(entry)) : ['Downloads']
  const reorderTabs = useReorder(prefs.nav, (nav) => updateSidebarPrefs(() => ({nav})))
  // The grid and the expand animation read the open width from a root variable.
  useEffect(() => document.documentElement.style.setProperty('--rail-open-w', `${width}px`), [width])
  useEffect(() => {
    if (collapsed) setEditing(false)
  }, [collapsed])
  // The drawer of a narrow window closes when a page is chosen, and on Esc.
  useEffect(() => closeDrawer(), [tab, view])
  useEffect(() => {
    if (mode !== 'drawer') return
    const keydown = (event: KeyboardEvent) => event.key === 'Escape' && !document.querySelector('dialog[open]') && closeDrawer()
    window.addEventListener('keydown', keydown)
    return () => window.removeEventListener('keydown', keydown)
  }, [mode])
  const navMenu = (entry: NavTab): MenuItem[] => [
    ...(canHideTab(entry) ? [{label: 'Hide from sidebar', icon: 'eyeOff', onSelect: () => toggleTabHidden(entry)}, 'separator' as const] : []),
    {label: 'Edit sidebar', icon: 'edit', onSelect: () => setEditing(true)},
  ]
  return (
    <aside className="rail" data-style={prefs.style} data-editing={editing || undefined}>
      <div className="rail-top">
        {/* While the sidebar is collapsed the logo is the way to open it: on hover or focus the mark gives way to the sidebar icon. */}
        <button
          type="button"
          className={`rail-brand${collapsed ? ' is-expander' : ''}`}
          aria-label={collapsed ? 'Expand sidebar' : full ? 'Keepfin, go to Home' : 'Keepfin, add a link'}
          aria-expanded={collapsed ? false : undefined}
          title={collapsed ? 'Expand sidebar (Ctrl+B)' : undefined}
          onClick={() => {
            if (collapsed) {
              toggleRail()
              return
            }
            // Home exists only in full mode; downloader-only mode has Downloads (and its paste box) as home.
            if (full) navigate('Home')
            else {
              navigate('Downloads')
              requestAnimationFrame(focusCapture)
            }
          }}
        >
          <span className="brand-mark">
            {collapsed ? <Mark size={30} /> : <img src="./brand/emblem.png" alt="" draggable={false} className="brand-emblem" />}
            {collapsed && <Icon name="sidebar" size={18} className="brand-expand" />}
          </span>
          <img src="./brand/wordmark.png" alt="" draggable={false} className="wordmark wordmark-plain" />
          <img src="./brand/wordmark-light.png" alt="" draggable={false} className="wordmark wordmark-light" />
        </button>
        {!collapsed && (
          <button
            type="button"
            className="icon-btn icon-btn-md rail-toggle"
            aria-label={mode === 'drawer' ? 'Close sidebar' : 'Collapse sidebar'}
            aria-expanded={true}
            title={`${mode === 'drawer' ? 'Close' : 'Collapse'} sidebar (Ctrl+B)`}
            onClick={toggleRail}
          >
            <Icon name="sidebar" size={18} />
          </button>
        )}
      </div>
      <nav className="rail-nav" aria-label="Main navigation">
        {editing && full ? (
          <ul className="your-list is-editing" aria-label="Pages">
            {tabs.map((entry) => {
              const hidden = prefs.hiddenNav.includes(entry)
              return (
                <EditRow key={entry} name={entry} mark={<Icon name={tabIcons[entry]} size={17} />} hidden={hidden} drag={reorderTabs(entry)}>
                  {canHideTab(entry) && <IconButton icon={hidden ? 'eyeOff' : 'eye'} size="sm" label={hidden ? `Show ${entry}` : `Hide ${entry}`} onClick={() => toggleTabHidden(entry)} />}
                </EditRow>
              )
            })}
          </ul>
        ) : (
          tabs.map((entry) => (
            <button
              key={entry}
              type="button"
              className={`nav-item${tab === entry ? ' selected' : ''}`}
              aria-current={tab === entry ? 'page' : undefined}
              aria-label={entry === 'Downloads' && active ? `Downloads, ${active} active` : entry}
              title={entry}
              onClick={() => {
                // The sidebar's Library is the root of the library, whatever page of it was open.
                if (entry === 'Library') setLibView({type: 'home'})
                navigate(entry)
              }}
              onContextMenu={full ? (event) => openMenu(event, entry, navMenu(entry)) : undefined}
            >
              <span className="nav-icon">
                <Icon name={tabIcons[entry]} size={19} />
                {entry === 'Downloads' && <ActiveCount />}
              </span>
              <span className="nav-label">{entry}</span>
            </button>
          ))
        )}
      </nav>
      {full ? <YourLibrary editing={editing} openMenu={openMenu} startEditing={() => setEditing(true)} /> : <div className="rail-fill" />}
      <div className="rail-foot">
        {editing ? <EditBar onDone={() => setEditing(false)} /> : <StatusLine />}
        <FootRow editing={editing} setEditing={setEditing} />
      </div>
      {mode === 'full' && <RailResize />}
      {menu && <ContextMenu label={menu.label} items={menu.items} at={menu.at} onClose={() => setMenu(undefined)} />}
    </aside>
  )
})
