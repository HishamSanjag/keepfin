// The spaces hub: pinned spaces as colored cards, an in-place Edit mode (drag or move up/down, pin, new space),
// "All spaces" with search, and the sidebar list that shows the same pinned spaces in the same order.
import {useMemo, useRef, useState, type ReactNode} from 'react'
import {homeSpaces, isPinned, spaceColorOf, spaceIconOf, HOME_SPACE_LIMIT, shiftId, type Space} from '../../shared/library'
import {native} from '../bridge'
import {plural} from '../format'
import {PageHeader} from '../shell/PageHeader'
import {PageToolbar} from '../shell/PageToolbar'
import {Button, IconButton, Switch} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {openDialog} from './dialogState'
import {setLibFilter, setLibView, setSpacesEditing, useSpacesEditing} from './libNav'
import {libraryAct, useLibrarySettings} from './libStore'
import {SearchBox} from './Toolbar'
import {dropId, useSpaceCounts} from './spacesModel'

export function SpaceMark({space, size = 18}: {space: Pick<Space, 'id' | 'color' | 'icon' | 'kind'>; size?: number}) {
  return (
    <span className={`space-mark sp-${spaceColorOf(space)}`} aria-hidden="true">
      <Icon name={spaceIconOf(space)} size={size} />
    </span>
  )
}

export const countWords = (space: Space, count: number) => (space.type === 'smart' ? plural(count, 'match', 'matches') : plural(count, 'item'))
const typeWord = (space: Space) => (space.type === 'smart' ? 'Smart' : 'Folder')

export const openSpace = (space: Space) => setLibView({type: 'space', id: space.id})
export const togglePin = (space: Space) => libraryAct('spaceSave', undefined, {spaceDef: {...space, pinned: !isPinned(space)}})

// ---------------------------------------------------------------------------
// Edit mode rows
// ---------------------------------------------------------------------------

function EditCards({spaces, counts}: {spaces: Space[]; counts: Map<string, number>}) {
  const ids = useMemo(() => spaces.map((space) => space.id), [spaces])
  const [dragging, setDraggingState] = useState<string>()
  // The ref answers dragover/drop events that arrive before React has re-rendered.
  const dragRef = useRef<string | undefined>(undefined)
  const setDragging = (id: string | undefined) => {
    dragRef.current = id
    setDraggingState(id)
  }
  const [over, setOver] = useState<{id: string; after: boolean}>()
  const [note, setNote] = useState('')
  const save = async (next: string[], moved: Space) => {
    if (next.join() === ids.join()) return
    const result = await libraryAct('spaceReorder', undefined, {ids: next})
    if (result.ok) setNote(`${moved.name} is now number ${next.indexOf(moved.id) + 1} of ${next.length}.`)
  }
  return (
    <>
      <ul className="space-cards space-cards-edit" aria-label="Spaces, in order">
        {spaces.map((space, index) => (
          <li
            key={space.id}
            className={`space-card is-editing space-edit-row sp-${spaceColorOf(space)}${isPinned(space) ? '' : ' is-unpinned'}${dragging === space.id ? ' is-dragging' : ''}${over?.id === space.id && dragging && dragging !== space.id ? (over.after ? ' drop-after' : ' drop-before') : ''}`}
            draggable
            onDragStart={(event) => {
              event.dataTransfer.setData('application/x-keepfin-space', space.id)
              event.dataTransfer.effectAllowed = 'move'
              setDragging(space.id)
            }}
            onDragEnter={(event) => dragRef.current && event.preventDefault()}
            onDragOver={(event) => {
              if (!dragRef.current) return
              event.preventDefault()
              event.dataTransfer.dropEffect = 'move'
              const rect = event.currentTarget.getBoundingClientRect()
              setOver({id: space.id, after: event.clientX > rect.left + rect.width / 2})
            }}
            onDrop={(event) => {
              const from = dragRef.current
              if (!from) return
              event.preventDefault()
              const moved = spaces.find((entry) => entry.id === from)
              const rect = event.currentTarget.getBoundingClientRect()
              const after = event.clientX > rect.left + rect.width / 2
              setDragging(undefined)
              setOver(undefined)
              if (moved) void save(dropId(ids, from, space.id, after), moved)
            }}
            onDragEnd={() => {
              setDragging(undefined)
              setOver(undefined)
            }}
          >
            <div className="space-card-body">
              <SpaceMark space={space} size={20} />
              <span className="space-card-text">
                <strong title={space.name}>{space.name}</strong>
                <span>
                  {typeWord(space)} · {countWords(space, counts.get(space.id) ?? 0)}
                </span>
              </span>
            </div>
            <div className="space-card-controls">
              <span className="drag-handle space-grip" title="Drag to reorder" aria-hidden="true">
                <Icon name="drag" size={16} />
              </span>
              <IconButton icon="chevronLeft" size="sm" label={`Move ${space.name} up`} disabled={index === 0} onClick={() => void save(shiftId(ids, space.id, -1), space)} />
              <IconButton icon="chevronRight" size="sm" label={`Move ${space.name} down`} disabled={index === spaces.length - 1} onClick={() => void save(shiftId(ids, space.id, 1), space)} />
              <Switch label={`Show ${space.name} on home`} checked={isPinned(space)} onChange={() => void togglePin(space)} />
              <IconButton icon="edit" size="sm" label={`Edit ${space.name}`} onClick={() => openDialog({type: 'space', space})} />
            </div>
          </li>
        ))}
        <li>
          <button type="button" className="space-card space-card-new" onClick={() => openDialog({type: 'space'})}>
            <Icon name="plus" size={18} />
            <span className="space-card-text">
              <strong>New space</strong>
              <span>A folder or a smart space</span>
            </span>
          </button>
        </li>
      </ul>
      <p className="sr-only" role="status" aria-live="polite">
        {note}
      </p>
    </>
  )
}

// ---------------------------------------------------------------------------
// Hub on Home
// ---------------------------------------------------------------------------

export function SpacesHub() {
  const settings = useLibrarySettings()!
  const editing = useSpacesEditing()
  const counts = useSpaceCounts(settings.spaces)
  const {shown, more} = useMemo(() => homeSpaces(settings.spaces), [settings.spaces])
  const pinnedCount = settings.spaces.filter(isPinned).length
  return (
    <section className="hub" aria-label="Spaces">
      <header className="shelf-head">
        <h3 className="shelf-title">Spaces</h3>
        <Button size="sm" variant={editing ? 'primary' : 'secondary'} icon={editing ? 'check' : 'edit'} disabled={!native} aria-pressed={editing} onClick={() => setSpacesEditing(!editing)}>
          {editing ? 'Done' : 'Edit'}
        </Button>
      </header>
      {editing ? (
        <>
          <p className="muted small hub-help">
            Drag a space, or use the arrows to move it earlier or later. The switch decides what shows here and in the sidebar; dimmed spaces are not pinned
            {pinnedCount > HOME_SPACE_LIMIT ? `; only the first ${HOME_SPACE_LIMIT} pinned spaces fit.` : '.'}
          </p>
          <EditCards spaces={settings.spaces} counts={counts} />
        </>
      ) : (
        <ul className="space-cards" aria-label="Pinned spaces">
          {shown.map((space) => (
            <li key={space.id}>
              <button type="button" className={`space-card sp-${spaceColorOf(space)}`} aria-label={`${space.name}, ${countWords(space, counts.get(space.id) ?? 0)}`} onClick={() => openSpace(space)}>
                <SpaceMark space={space} size={20} />
                <span className="space-card-text">
                  <strong title={space.name}>{space.name}</strong>
                  <span>
                    {space.type === 'smart' ? 'Smart · ' : ''}
                    {countWords(space, counts.get(space.id) ?? 0)}
                  </span>
                </span>
              </button>
            </li>
          ))}
          {more > 0 && (
            <li>
              <button type="button" className="space-card space-card-more" onClick={() => (setLibFilter('spaces'), setLibView({type: 'home'}))}>
                <span className="space-card-text">
                  <strong>All spaces · {more} more</strong>
                  <span>Folders and smart spaces</span>
                </span>
                <Icon name="chevron" size={15} />
              </button>
            </li>
          )}
        </ul>
      )}
    </section>
  )
}

// ---------------------------------------------------------------------------
// All spaces
// ---------------------------------------------------------------------------

export function AllSpaces({embedded = false, only, lead}: {embedded?: boolean; only?: (space: Space) => boolean; lead?: ReactNode} = {}) {
  const settings = useLibrarySettings()!
  const counts = useSpaceCounts(settings.spaces)
  const [query, setQuery] = useState('')
  const words = query.trim().toLowerCase()
  const matching = settings.spaces.filter((space) => (!only || only(space)) && (!words || space.name.toLowerCase().includes(words)))
  const groups = [
    {title: 'Folders', spaces: matching.filter((space) => space.type !== 'smart')},
    {title: 'Smart spaces', spaces: matching.filter((space) => space.type === 'smart')},
  ]
  const newSpace = (
    <Button size="sm" variant={embedded ? 'secondary' : 'primary'} icon="plus" onClick={() => openDialog({type: 'space'})} disabled={!native}>
      New space
    </Button>
  )
  return (
    <div className="space-view">
      {!embedded && (
        <PageHeader
          title="All spaces"
          caption={`${plural(settings.spaces.length, 'space')}. Folders hold files; smart spaces gather them by rules.`}
          crumbs={[{label: 'Library', onSelect: () => setLibView({type: 'home'})}]}
          actions={newSpace}
        />
      )}
      <PageToolbar
        label="Spaces toolbar"
        left={lead}
        right={
          <>
            <SearchBox value={query} onChange={setQuery} placeholder="Filter spaces" label="Filter spaces" />
            {embedded && newSpace}
          </>
        }
      />      <div className="space-body all-spaces">
        {groups.map(
          (group) =>
            group.spaces.length > 0 && (
              <section key={group.title} aria-label={group.title}>
                <h3 className="shelf-title">{group.title}</h3>
                <ul className="space-list-rows" aria-label={group.title}>
                  {group.spaces.map((space) => (
                    <li key={space.id} className={`sp-${spaceColorOf(space)}`}>
                      <button type="button" className="space-row-open" onClick={() => openSpace(space)} aria-label={`${space.name}, ${countWords(space, counts.get(space.id) ?? 0)}`}>
                        <SpaceMark space={space} />
                        <span className="space-edit-text">
                          <strong title={space.name}>{space.name}</strong>
                          <span className="muted small">{countWords(space, counts.get(space.id) ?? 0)}</span>
                        </span>
                      </button>
                      <IconButton
                        icon="pin"
                        size="sm"
                        className={isPinned(space) ? 'pinned' : undefined}
                        label={isPinned(space) ? `Unpin ${space.name} from home` : `Pin ${space.name} to home`}
                        aria-pressed={isPinned(space)}
                        onClick={() => void togglePin(space)}
                      />
                    </li>
                  ))}
                </ul>
              </section>
            ),
        )}
        {!matching.length && <p className="muted pane-note">No space matches “{query}”.</p>}
      </div>
    </div>
  )
}
