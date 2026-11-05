import type {ReactNode} from 'react'
import {plural} from '../format'
import {PageToolbar} from '../shell/PageToolbar'
import {Button, Segmented, Select} from '../ui/controls'
import {Menu} from '../ui/Menu'
import {Icon} from '../ui/Icon'
import {openDialog} from './dialogState'
import type {Selection} from './controller'
import {getLibItem, useLibrary} from './libStore'
import {playAll} from '../player/play'
import type {LibraryItem} from '../../shared/library'
import {playableKind, sortLabels, type SortKey} from './model'
import {densities, type Density} from './density'

export type ViewMode = 'list' | 'grid'

const densityLabels: Record<Density, string> = {compact: 'Compact', comfortable: 'Comfortable'}

/** "View" menu: how dense lists and grids are. Remembered per space by the caller. */
export function DensityMenu({value, onChange}: {value: Density; onChange: (value: Density) => void}) {
  return (
    <Menu
      label="Density"
      align="end"
      width={200}
      triggerClassName="density-trigger"
      trigger={
        <>
          <Icon name="list" size={15} />
          <span>{densityLabels[value]}</span>
          <Icon name="chevron" size={12} className="density-chev" />
        </>
      }
      items={[{heading: 'Density'}, ...densities.map((entry) => ({label: densityLabels[entry], checked: entry === value, onSelect: () => onChange(entry)}))]}
    />
  )
}

export function SearchBox({value, onChange, placeholder, label, busy}: {value: string; onChange: (value: string) => void; placeholder: string; label: string; busy?: boolean}) {
  return (
    <div className="search lib-search">
      <Icon name="search" size={15} />
      <input
        className="input"
        type="search"
        aria-label={label}
        placeholder={placeholder}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape' && value) {
            event.stopPropagation()
            onChange('')
          }
        }}
      />
      {busy && <span className="search-busy" aria-hidden="true" />}
    </div>
  )
}

/** The toolbar row of a space: tabs or kind filters on the left; the "Filter <space>" field, sort, view, select and Add on the right. */
export function Toolbar({
  left,
  search,
  sort,
  view,
  density,
  selection,
  addTo,
}: {
  left?: ReactNode
  search: {value: string; onChange: (value: string) => void; placeholder: string; busy?: boolean}
  sort?: {value: SortKey; options: SortKey[]; onChange: (value: SortKey) => void}
  view?: {value: ViewMode; onChange: (value: ViewMode) => void}
  density?: {value: Density; onChange: (value: Density) => void}
  selection?: {selecting: boolean; setSelecting: (on: boolean) => void}
  /** Space id that Add files imports into. */
  addTo?: string
}) {
  return (
    <PageToolbar
      label="Library toolbar"
      left={left}
      right={
        <>
          <SearchBox value={search.value} onChange={search.onChange} placeholder={search.placeholder} label={search.placeholder} busy={search.busy} />
          {sort && <Select<SortKey> label="Sort" value={sort.value} onChange={sort.onChange} options={sort.options.map((value) => ({value, label: sortLabels[value]}))} />}
          {view && (
            <Segmented<ViewMode>
              label="View"
              size="sm"
              value={view.value}
              onChange={view.onChange}
              options={[
                {value: 'list', label: <Icon name="list" size={16} />, title: 'List view'},
                {value: 'grid', label: <Icon name="grid" size={16} />, title: 'Grid view'},
              ]}
            />
          )}
          {density && <DensityMenu value={density.value} onChange={density.onChange} />}
          {selection && (
            <Button size="sm" variant="secondary" icon="check" aria-pressed={selection.selecting} onClick={() => selection.setSelecting(!selection.selecting)}>
              {selection.selecting ? 'Done' : 'Select'}
            </Button>
          )}
          {addTo && (
            <Button size="sm" icon="plus" onClick={() => openDialog({type: 'addFrom', space: addTo})}>
              Add
            </Button>
          )}
        </>
      }
    />
  )
}
/** What a selection of tiles (albums, artists, folders...) looks like to the bar. */
export type TileBar = {
  noun: string
  count: number
  total: number
  /** Items the actions apply to. */
  ids: string[]
  selectAll: () => void
  clear: () => void
  /** Hide actions that destroy files (a playlist is a list of songs, not a folder to wipe). */
  safe?: boolean
}

/** Shown while selecting: what is selected, Select all / Select none, and what the selection can do. */
export function SelectionBar({selection, total, onRemoveFromPlaylist, tiles}: {selection: Selection; total: number; onRemoveFromPlaylist?: (ids: string[]) => void; tiles?: TileBar}) {
  // A long file operation (delete, move, import) owns the files: the actions wait until it ends.
  const working = !!useLibrary().snapshot.operation
  if (!selection.selecting) return null
  const ids = tiles ? tiles.ids : [...selection.selected]
  const count = tiles ? tiles.count : ids.length
  const units = tiles ? tiles.total : total
  const noun = tiles ? tiles.noun : 'item'
  const items = ids.map(getLibItem).filter((item): item is LibraryItem => !!item)
  const songs = items.filter(playableKind)
  const none = ids.length === 0 || working
  const all = units > 0 && count === units
  const toggleAll = () => (all ? (tiles ? tiles.clear() : selection.clear()) : tiles ? tiles.selectAll() : selection.selectAll())
  return (
    <div className="selection-bar" role="region" aria-label="Selection">
      <span className="selection-count tabular" role="status">
        {count === 0
          ? 'Choose items to act on them'
          : `${count.toLocaleString()} selected${tiles && ids.length !== count ? ` · ${plural(ids.length, 'item')}` : ''}`}
      </span>
      <Button size="sm" variant="secondary" className="select-all-btn" disabled={units === 0} title={all ? 'Select none' : `Select all ${plural(units, noun)} (Ctrl+A)`} onClick={toggleAll}>
        {all ? 'Select none' : 'Select all'}
      </Button>
      <span className="selection-actions">
        <Button size="sm" icon="play" disabled={none} onClick={() => playAll(items)}>
          Play
        </Button>
        <Button size="sm" icon="playlistAdd" disabled={!songs.length || working} onClick={() => openDialog({type: 'playlistAdd', ids: songs.map((item) => item.id)})}>
          Add to playlist
        </Button>
        <Button size="sm" icon="tag" disabled={!items.length || working} onClick={() => openDialog({type: 'tags', ids: items.map((item) => item.id)})}>
          Tags…
        </Button>
        {!tiles?.safe && (
          <Button size="sm" icon="move" disabled={none} onClick={() => openDialog({type: 'move', ids})}>
            Move to…
          </Button>
        )}
        {onRemoveFromPlaylist && (
          <Button size="sm" icon="close" disabled={none} onClick={() => onRemoveFromPlaylist(ids)}>
            Remove from playlist
          </Button>
        )}
        {!tiles?.safe && (
          <Button size="sm" variant="danger" icon="trash" disabled={none} onClick={() => openDialog({type: 'deleteFiles', ids})}>
            Delete file…
          </Button>
        )}
      </span>
    </div>
  )
}
