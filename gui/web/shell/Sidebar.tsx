import {memo, useEffect, useMemo} from 'react'
import {focusCapture} from '../capture/useCapture'
import {speedLabel} from '../format'
import {native} from '../bridge'
import {setLibView, setSpacesEditing, useLibView, useSpacesEditing, type LibView} from '../library/libNav'
import {useLibrary, useLibrarySettings} from '../library/libStore'
import {SpaceMark} from '../library/SpacesHub'
import {useSpaceCounts} from '../library/spacesModel'
import {useFullMode} from '../mode'
import {closeDrawer, toggleRail, useRailMode} from './layoutState'
import {navigate, useNav, type Tab} from '../nav'
import {useDevices} from '../settings/devices/useDevices'
import {useAggregate} from '../store'
import {useUpdates} from '../updates'
import {Art} from '../library/Art'
import {IconButton} from '../ui/controls'
import {Icon, Mark} from '../ui/Icon'

const fullItems: {tab: Tab; icon: string}[] = [
  {tab: 'Home', icon: 'home'},
  {tab: 'Search', icon: 'search'},
  {tab: 'Library', icon: 'library'},
  {tab: 'Downloads', icon: 'download'},
  {tab: 'Settings', icon: 'settings'},
]
const downloaderItems: {tab: Tab; icon: string}[] = [
  {tab: 'Downloads', icon: 'download'},
  {tab: 'Settings', icon: 'settings'},
]

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

/** The last row of the sidebar: the version number with an info icon; it opens Settings > About Keepfin. Icon only while collapsed. */
function AboutEntry() {
  const {state} = useUpdates()
  const {tab, group} = useNav()
  const label = state?.current ?? 'About'
  const here = tab === 'Settings' && group === 'about'
  const supportHere = tab === 'Settings' && group === 'support'
  // One row: the version, small and quiet, opens About; Support beside it opens its own page (icons only in a narrow sidebar)
  return (
    <div className="side-foot-row">
      <button
        type="button"
        className="side-status side-about"
        aria-label={state?.current ? `About Keepfin, version ${state.current}` : 'About Keepfin'}
        aria-current={here ? 'page' : undefined}
        title={label}
        onClick={() => navigate('Settings', 'about')}
      >
        <Icon name="info" size={16} />
        <span className="side-status-text tabular side-version">{label}</span>
      </button>
      <button
        type="button"
        className="side-status side-support"
        aria-label="Support Keepfin"
        aria-current={supportHere ? 'page' : undefined}
        title="Support Keepfin"
        onClick={() => navigate('Settings', 'support')}
      >
        <Icon name="heart" size={14} />
        <span className="side-status-text">Support</span>
      </button>
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

const LIMIT = 60

type Entry = {
  key: string
  name: string
  count?: number
  view: LibView
  art?: string
  icon: string
  space?: Parameters<typeof SpaceMark>[0]['space']
}

const sameView = (a: LibView, b: LibView) => JSON.stringify(a) === JSON.stringify(b)

/** The one place for what you keep: spaces, then playlists and collections, as a quiet list. Hidden when the sidebar is icons only. */
function YourLibrary() {
  const lib = useLibrary()
  const settings = useLibrarySettings()
  const view = useLibView()
  const editing = useSpacesEditing()
  const {tab} = useNav()
  const counts = useSpaceCounts(settings?.spaces ?? [])
  const spaces = useMemo<Entry[]>(() => {
    if (!settings || !lib.loaded) return []
    return settings.spaces.map((space) => ({
      key: space.id,
      name: space.name,
      count: counts.get(space.id) ?? 0,
      view: {type: 'space', id: space.id} as LibView,
      icon: 'folder',
      space,
    }))
  }, [settings, lib.loaded, counts])
  const playlists = useMemo<Entry[]>(() => {
    if (!settings || !lib.loaded) return []
    return [...lib.playlists, ...lib.downloaded].slice(0, LIMIT).map((collection) => {
      const cover =
        (collection.coverItemId && lib.byId.get(collection.coverItemId)) || collection.itemIds.map((id) => lib.byId.get(id)).find((item) => item?.hasArt)
      return {
        key: collection.id,
        name: collection.name,
        view: collection.kind === 'own' ? ({type: 'playlist', id: collection.id} as LibView) : ({type: 'collection', id: collection.id} as LibView),
        art: cover && cover.hasArt ? cover.id : undefined,
        icon: 'queue',
      }
    })
  }, [settings, lib.loaded, lib.playlists, lib.downloaded, lib.byId])
  if (!settings) return null
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
        >
          {entry.space ? (
            <SpaceMark space={entry.space} size={15} />
          ) : (
            <span className="side-cover" aria-hidden="true">
              {entry.art ? <Art id={entry.art} hasArt kind="audio" /> : <Icon name={entry.icon} size={15} />}
            </span>
          )}
          <span className="side-name">{entry.name}</span>
          {entry.count !== undefined && <span className="side-count tabular">{entry.count.toLocaleString()}</span>}
        </button>
      </li>
    )
  }
  return (
    <section className="your-lib" aria-label="Your library panel">
      <nav className="your-nav" aria-label="Your library">
        <div className="side-label side-label-row">
          <span>Spaces</span>
          {/* the Library's edit mode: reorder, pin and add spaces (the same grid as the phone's Library › Edit) */}
          <IconButton
            icon={editing ? 'check' : 'edit'}
            size="sm"
            label={editing ? 'Done editing spaces' : 'Edit spaces'}
            aria-pressed={editing}
            disabled={!native}
            onClick={() => {
              setSpacesEditing(!editing)
              if (!editing) {
                setLibView({type: 'home'})
                navigate('Library')
              }
            }}
          />
        </div>
        <ul className="your-list" aria-label="Spaces">
          {spaces.map(row)}
          {!spaces.length && <li className="side-empty muted small">{lib.loaded ? 'No spaces yet.' : 'Loading…'}</li>}
        </ul>
        <div className="side-label">Playlists</div>
        <ul className="your-list" aria-label="Playlists">
          {playlists.map(row)}
          {!playlists.length && <li className="side-empty muted small">{lib.loaded ? 'No playlists yet.' : 'Loading…'}</li>}
        </ul>
      </nav>
    </section>
  )
}

export const Sidebar = memo(function Sidebar() {
  const {tab} = useNav()
  const {active} = useAggregate()
  const full = useFullMode()
  const items = full ? fullItems : downloaderItems
  const mode = useRailMode()
  const collapsed = mode === 'collapsed'
  const view = useLibView()
  // The drawer of a narrow window closes when a page is chosen, and on Esc.
  useEffect(() => closeDrawer(), [tab, view])
  useEffect(() => {
    if (mode !== 'drawer') return
    const keydown = (event: KeyboardEvent) => event.key === 'Escape' && !document.querySelector('dialog[open]') && closeDrawer()
    window.addEventListener('keydown', keydown)
    return () => window.removeEventListener('keydown', keydown)
  }, [mode])
  return (
    <aside className="rail">
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
        {items.map((item) => (
          <button
            key={item.tab}
            type="button"
            className={`nav-item${item.tab === 'Settings' ? ' nav-settings' : ''}${tab === item.tab ? ' selected' : ''}`}
            aria-current={tab === item.tab ? 'page' : undefined}
            aria-label={item.tab === 'Downloads' && active ? `Downloads, ${active} active` : item.tab}
            title={item.tab}
            onClick={() => {
              // The sidebar's Library is the root of the library, whatever page of it was open.
              if (item.tab === 'Library') setLibView({type: 'home'})
              if (item.tab === 'Settings') navigate('Settings', 'downloads')
              else navigate(item.tab)
            }}
          >
            <span className="nav-icon">
              <Icon name={item.icon} size={19} />
              {item.tab === 'Downloads' && <ActiveCount />}
            </span>
            <span className="nav-label">{item.tab}</span>
          </button>
        ))}
      </nav>
      {full ? <YourLibrary /> : <div className="rail-fill" />}
      <div className="rail-foot">
        {/* One compact status line: active downloads and connected devices. Theme lives in Settings > Appearance. The last row is the version, which opens About Keepfin, and Support beside it. */}
        <StatusLine />
        <AboutEntry />
      </div>
    </aside>
  )
})
