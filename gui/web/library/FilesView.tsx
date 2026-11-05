import {useMemo} from 'react'
import type {LibraryItem, Space} from '../../shared/library'
import {plural} from '../format'
import {playAll} from '../player/play'
import {Button} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {CollectionContext, FileRow, folderSub, ItemCard, OpenCard, OpenRow} from './cells'
import {useCollectionController, useTileSelection} from './controller'
import {sizes, type Density} from './density'
import {continueWatching, folderLevel, sortItems, type FolderCard, type SortKey} from './model'
import {SelectionBar, type ViewMode} from './Toolbar'
import {VirtualCollection} from './VirtualCollection'
import {NoMatches} from './Empty'
import {ContinueRemove} from './LibraryHome'

export type KindFilter = 'all' | 'video' | 'audio' | 'image'

type Entry = {type: 'folder'; folder: FolderCard} | {type: 'item'; id: string}

/** Folder cards first, then the items, as posters or rows; Continue watching on top at the root. */
export function FilesView({
  space,
  items,
  path,
  onPath,
  view,
  sort,
  kind,
  query,
  onClearQuery,
  selecting,
  onSelectingChange,
  density,
}: {
  space: Space
  /** Search-filtered items of this space. */
  items: LibraryItem[]
  path: string
  onPath: (path: string) => void
  view: ViewMode
  sort: SortKey
  kind: KindFilter
  query: string
  onClearQuery: () => void
  selecting: boolean
  onSelectingChange: (on: boolean) => void
  density: Density
}) {
  const size = sizes[density]
  const flat = !!query.trim() || kind !== 'all'
  const filtered = useMemo(() => (kind === 'all' ? items : items.filter((item) => item.kind === kind)), [items, kind])
  const level = useMemo(() => (flat ? {folders: [] as FolderCard[], loose: filtered} : folderLevel(filtered, path)), [filtered, flat, path])
  const loose = useMemo(() => sortItems(level.loose, sort), [level.loose, sort])
  const entries = useMemo<Entry[]>(() => [...level.folders.map((folder) => ({type: 'folder' as const, folder})), ...loose.map((item) => ({type: 'item' as const, id: item.id}))], [level.folders, loose])
  const controller = useCollectionController(loose, { selecting, onSelectingChange })
  // A folder tile stands for everything inside it, however deep.
  const folderTiles = useMemo(
    () => level.folders.map((folder) => ({key: `f:${folder.path}`, items: filtered.filter((item) => item.folder === folder.path || item.folder.startsWith(`${folder.path}/`))})),
    [level.folders, filtered],
  )
  const pickedFolders = useTileSelection(folderTiles, { selecting, onSelectingChange })
  const everyId = useMemo(() => [...new Set([...[...controller.selection.selected], ...pickedFolders.ids])], [controller.selection.selected, pickedFolders.ids])
  const selectEverything = () => {
    controller.selection.selectAll()
    pickedFolders.selectAll()
  }
  const clearEverything = () => {
    controller.selection.clear()
    pickedFolders.clear()
  }
  const resume = useMemo(() => (flat || path ? [] : continueWatching(items).slice(0, 12)), [items, flat, path])
  const videoCount = loose.filter((item) => item.kind === 'video').length
  const aspect = videoCount * 2 >= loose.length && (videoCount > 0 || level.folders.length > 0 || space.kind === 'video') ? 0.5625 : 1

  const crumbs = path ? path.split('/') : []
  const header = (
    <>
      {crumbs.length > 0 && (
        <nav className="crumbs" aria-label="Folder path">
          <button type="button" className="link-btn" onClick={() => onPath('')}>
            {space.name}
          </button>
          {crumbs.map((name, index) => (
            <span key={index} className="crumb">
              <Icon name="chevron" size={13} />
              {index === crumbs.length - 1 ? (
                <strong aria-current="page">{name}</strong>
              ) : (
                <button type="button" className="link-btn" onClick={() => onPath(crumbs.slice(0, index + 1).join('/'))}>
                  {name}
                </button>
              )}
            </span>
          ))}
        </nav>
      )}
      {resume.length > 0 && (
        <section className="shelf-block" aria-label="Continue watching">
          <h3 className="shelf-title">Continue watching</h3>
          <div className="shelf shelf-posters" role="list">
            {resume.map((item, index) => (
              <div key={item.id} role="listitem" className="shelf-item">
                <ItemCard id={item.id} state={{index, focused: false, tabIndex: 0}} />
                <ContinueRemove item={item} />
              </div>
            ))}
          </div>
        </section>
      )}
      <div className="list-actions">
        {loose.some((item) => item.kind === 'audio') && (
          <Button size="sm" variant="primary" icon="play" onClick={() => playAll(loose.filter((item) => item.kind === 'audio'))}>
            Play all songs
          </Button>
        )}
        <span className="list-summary tabular">
          {[level.folders.length ? plural(level.folders.length, 'folder') : '', plural(loose.length, 'item')].filter(Boolean).join(' · ')}
        </span>
      </div>
      <SelectionBar
        selection={controller.selection}
        total={loose.length}
        tiles={{
          noun: 'item',
          count: controller.selection.selected.size + pickedFolders.count,
          total: loose.length + level.folders.length,
          ids: everyId,
          selectAll: selectEverything,
          clear: clearEverything,
        }}
      />
    </>
  )

  if (!entries.length) {
    return (
      <div className="lib-scroll-pane">
        {header}
        {query ? <NoMatches query={query} onClear={onClearQuery} /> : <p className="muted pane-note">This folder is empty.</p>}
      </div>
    )
  }

  return (
    <CollectionContext.Provider value={controller.api}>
      <VirtualCollection
        count={entries.length}
        mode={view}
        rowHeight={size.fileRow}
        minCell={view === 'grid' && aspect < 1 ? size.posterMin : size.coverMin}
        cellAspect={aspect}
        cellExtra={size.cellExtra}
        gap={size.gap}
        density={density}
        onSelectAll={selectEverything}
        label={`${space.name} items`}
        header={header}
        keyOf={(index) => {
          const entry = entries[index]
          return entry.type === 'folder' ? `f:${entry.folder.path}` : entry.id
        }}
        onContextKey={(index, rect) => {
          const entry = entries[index]
          if (entry?.type === 'item') controller.openMenuFor(loose.findIndex((item) => item.id === entry.id), rect)
        }}
        renderCell={(state) => {
          const entry = entries[state.index]
          if (entry.type === 'folder') {
            const open = () => onPath(entry.folder.path)
            return view === 'grid' ? (
              <OpenCard state={state} title={entry.folder.name} sub={folderSub(entry.folder)} art={entry.folder.art} icon="folder" onOpen={open} label={`Folder ${entry.folder.name}, ${folderSub(entry.folder)}`} select={pickedFolders.forTile(`f:${entry.folder.path}`)} />
            ) : (
              <OpenRow state={state} title={entry.folder.name} sub={folderSub(entry.folder)} art={entry.folder.art} icon="folder" onOpen={open} select={pickedFolders.forTile(`f:${entry.folder.path}`)} />
            )
          }
          const itemIndex = state.index - level.folders.length
          const cell = {...state, index: itemIndex}
          return view === 'grid' ? <ItemCard id={entry.id} state={cell} /> : <FileRow id={entry.id} state={cell} />
        }}
      />
      {controller.menuNode}
    </CollectionContext.Provider>
  )
}
