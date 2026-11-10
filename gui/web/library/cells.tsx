import {createContext, memo, useContext, type DragEvent, type MouseEvent, type ReactNode} from 'react'
import {request} from '../bridge'
import {formatBytes, formatDuration, relativeTime} from '../format'
import {usePlayer} from '../player/playerStore'
import {IconButton} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {Menu} from '../ui/Menu'
import {Art, Mosaic, ProgressTick} from './Art'
import {itemMenu} from './itemMenu'
import {useElsewhereHint} from './elsewhere'
import {isVideoSong, toggleFavorite, useLibItem} from './libStore'
import {songWords} from '../../shared/library'
import type {Album, Artist, FolderCard as FolderModel} from './model'
import type {CellState} from './VirtualCollection'
import type {TileSelect} from './controller'

/** What a list of cells shares: selection, the surrounding items for "Play", and actions. */
export type CollectionApi = {
  selecting: boolean
  selected: ReadonlySet<string>
  /** Click on an item: plays it (or toggles selection in select mode). */
  activate: (id: string, index: number, event: MouseEvent | { shiftKey: boolean; ctrlKey: boolean; metaKey: boolean }) => void
  menuAt: (id: string, at: {x: number; y: number}) => void
  /** Ids that move with a drag out of the window (the selection, when the item is part of it). */
  dragIds: (id: string) => string[]
  /** Items that left the source collection and stay here: shown dimmed with a note. */
  removed?: ReadonlySet<string>
}
export const CollectionContext = createContext<CollectionApi | null>(null)
export const useCollectionApi = () => useContext(CollectionContext)!

function dragOut(event: DragEvent, ids: string[]) {
  // Native drag to Explorer is started by the main process (webContents.startDrag) with a real file path.
  event.preventDefault()
  void request({op: 'libraryDrag', id: ids[0], ids} as never).catch(() => {})
}

function PlayState({id, index, number}: {id: string; index: number; number?: number | string}) {
  const state = usePlayer((s) => (s.currentId === id ? (s.playing ? 2 : 1) : 0))
  return (
    <span className={`row-lead${state ? ' is-current' : ''}`} aria-hidden="true">
      {state === 2 ? (
        <span className="eq">
          <i />
          <i />
          <i />
        </span>
      ) : (
        <span className="row-index tabular">{state === 1 ? <Icon name="pause" size={14} /> : (number ?? index + 1)}</span>
      )}
      <Icon name="play" size={15} className="row-play" />
    </span>
  )
}

function Heart({id, favorite, title}: {id: string; favorite?: boolean; title: string}) {
  return (
    <IconButton
      icon={favorite ? 'heartFill' : 'heart'}
      size="sm"
      tabIndex={-1}
      className={`heart${favorite ? ' on' : ''}`}
      label={favorite ? `Remove ${title} from favorites` : `Add ${title} to favorites`}
      aria-pressed={!!favorite}
      onClick={() => void toggleFavorite(id)}
    />
  )
}

function rowSelect(api: ReturnType<typeof useCollectionApi>, id: string, index: number) {
  return (event: MouseEvent) => api.activate(id, index, event)
}

/** One song in a list: number/equaliser, cover, title and artist, album, duration, heart and menu. */
export const SongRow = memo(function SongRow({id, state, showAlbum = true, number, lead = true}: {id: string; state: CellState; showAlbum?: boolean; number?: number | string; lead?: boolean}) {
  const item = useLibItem(id)
  const away = useElsewhereHint(item)
  const api = useCollectionApi()
  if (!item) return <div className="song-row is-gone" />
  const selected = api.selected.has(id)
  return (
    <div
      className={`song-row${selected ? ' is-selected' : ''}${item.missing && !away ? ' is-missing' : ''}${api.removed?.has(id) ? ' is-removed' : ''}`}
      onContextMenu={(event) => {
        event.preventDefault()
        api.menuAt(id, {x: event.clientX, y: event.clientY})
      }}
    >
      <button
        type="button"
        className="row-main"
        data-focus
        tabIndex={state.tabIndex}
        aria-label={`${item.title}${item.artist ? `, ${item.artist}` : ''}`}
        aria-pressed={api.selecting ? selected : undefined}
        draggable
        onDragStart={(event) => dragOut(event, api.dragIds(id))}
        onClick={rowSelect(api, id, state.index)}
      >
        {api.selecting ? (
          <span className={`check-box${selected ? ' on' : ''}`} aria-hidden="true">
            {selected && <Icon name="check" size={13} />}
          </span>
        ) : lead ? (
          <PlayState id={id} index={state.index} number={number} />
        ) : null}
        <Art id={item.id} hasArt={item.hasArt} kind={item.kind} label={item.title} className="row-art" size={18} />
        <span className="row-text">
          <span className="row-title" title={item.title}>
            {item.title}
          </span>
          <span className="row-sub">
            {/* a music video kept as a song: it plays as its sound, Watch video shows the picture */}
            {isVideoSong(item) && <span className="row-tag">{songWords.videoTag}</span>}
            {item.missing ? away ?? 'File missing' : api.removed?.has(id) ? 'Removed from the playlist · kept here' : item.artist || item.albumArtist}
          </span>
        </span>
        {showAlbum && (
          <span className="row-col row-album" title={item.album}>
            {item.album}
          </span>
        )}
        <span className="row-col row-time tabular">{formatDuration(item.duration)}</span>
      </button>
      <span className="row-actions">
        <Heart id={id} favorite={item.favorite} title={item.title} />
        <Menu label={`More actions for ${item.title}`} items={itemMenu([item])} tabbable={false} />
      </span>
    </div>
  )
})

/** A file in a list view (videos, mixed): thumbnail, name, details, size, added. */
export const FileRow = memo(function FileRow({id, state}: {id: string; state: CellState}) {
  const item = useLibItem(id)
  const away = useElsewhereHint(item)
  const api = useCollectionApi()
  if (!item) return <div className="file-row is-gone" />
  const selected = api.selected.has(id)
  const details = [item.kind === 'video' && item.height ? `${item.height}p` : '', item.artist, formatDuration(item.duration)].filter(Boolean)
  return (
    <div
      className={`file-row${selected ? ' is-selected' : ''}${item.missing && !away ? ' is-missing' : ''}`}
      onContextMenu={(event) => {
        event.preventDefault()
        api.menuAt(id, {x: event.clientX, y: event.clientY})
      }}
    >
      <button
        type="button"
        className="row-main"
        data-focus
        tabIndex={state.tabIndex}
        aria-label={item.title}
        aria-pressed={api.selecting ? selected : undefined}
        draggable
        onDragStart={(event) => dragOut(event, api.dragIds(id))}
        onClick={rowSelect(api, id, state.index)}
      >
        {api.selecting && (
          <span className={`check-box${selected ? ' on' : ''}`} aria-hidden="true">
            {selected && <Icon name="check" size={13} />}
          </span>
        )}
        <span className="file-thumb">
          <Art id={item.id} hasArt={item.hasArt} kind={item.kind} label={item.title} />
          <ProgressTick item={item} />
        </span>
        <span className="row-text">
          <span className="row-title" title={item.title}>
            {item.title}
          </span>
          <span className="row-sub">{item.missing ? away ?? 'File missing' : details.join(' · ')}</span>
        </span>
        <span className="row-col row-size tabular">{formatBytes(item.size)}</span>
        <span className="row-col row-date tabular">{relativeTime(item.addedAt)}</span>
      </button>
      <span className="row-actions">
        {(item.kind === 'video' || item.kind === 'audio') && <Heart id={id} favorite={item.favorite} title={item.title} />}
        <Menu label={`More actions for ${item.title}`} items={itemMenu([item])} tabbable={false} />
      </span>
    </div>
  )
})

/** A poster or cover tile in a grid. */
export const ItemCard = memo(function ItemCard({id, state}: {id: string; state: CellState}) {
  const item = useLibItem(id)
  const away = useElsewhereHint(item)
  const api = useCollectionApi()
  const playing = usePlayer((s) => s.currentId === id)
  if (!item) return <div className="card is-gone" />
  const selected = api.selected.has(id)
  const square = item.kind !== 'video'
  const sub = item.missing ? away ?? 'File missing' : [item.artist, item.kind === 'video' ? formatDuration(item.duration) : item.kind === 'image' && item.width ? `${item.width}×${item.height}` : item.album].filter(Boolean).join(' · ')
  return (
    <div
      className={`card${selected ? ' is-selected' : ''}${playing ? ' is-current' : ''}`}
      onContextMenu={(event) => {
        event.preventDefault()
        api.menuAt(id, {x: event.clientX, y: event.clientY})
      }}
    >
      <button
        type="button"
        className="card-main"
        data-focus
        tabIndex={state.tabIndex}
        aria-label={item.title}
        aria-pressed={api.selecting ? selected : undefined}
        draggable
        onDragStart={(event) => dragOut(event, api.dragIds(id))}
        onClick={rowSelect(api, id, state.index)}
      >
        <span className={`card-art${square ? ' square' : ''}`}>
          <Art id={item.id} hasArt={item.hasArt} kind={item.kind} label={item.title} size={30} space={item.space} />
          {item.kind === 'video' && item.duration ? <span className="card-time tabular">{formatDuration(item.duration)}</span> : null}
          <ProgressTick item={item} />
          {api.selecting ? (
            <span className={`check-box card-check${selected ? ' on' : ''}`} aria-hidden="true">
              {selected && <Icon name="check" size={13} />}
            </span>
          ) : (
            <span className="card-play" aria-hidden="true">
              <Icon name={playing ? 'pause' : 'play'} size={18} />
            </span>
          )}
        </span>
        <span className="card-title" title={item.title}>
          {item.title}
        </span>
        <span className="card-sub">{sub}</span>
      </button>
      <span className="card-actions">
        {(item.kind === 'video' || item.kind === 'audio') && <Heart id={id} favorite={item.favorite} title={item.title} />}
        <Menu label={`More actions for ${item.title}`} items={itemMenu([item])} tabbable={false} />
      </span>
    </div>
  )
})

/** A tile that opens something (album, artist, folder, playlist). */
export const OpenCard = memo(function OpenCard({
  state,
  title,
  sub,
  art,
  round,
  onOpen,
  menu,
  label,
  badge,
  icon,
  select,
  follow,
  note,
}: {
  state: CellState
  title: string
  sub?: string
  art?: string[]
  round?: boolean
  onOpen: () => void
  menu?: () => ReactNode
  label?: string
  badge?: string
  icon?: string
  /** Makes the tile selectable: while selecting a click toggles it, and Ctrl+click starts selecting. */
  select?: TileSelect
  /** A one-tap Follow toggle under the name (always visible). */
  follow?: ReactNode
  /** A calm note that takes the place of the sub line (a failed check). */
  note?: ReactNode
}) {
  return (
    <div className={`card${round ? ' card-round' : ''}${select?.selected ? ' is-selected' : ''}`}>
      <button
        type="button"
        className="card-main"
        data-focus
        tabIndex={state.tabIndex}
        aria-label={label || `${title}${sub ? `, ${sub}` : ''}`}
        aria-pressed={select?.selecting ? select.selected : undefined}
        onClick={(event) => (select && (select.selecting || event.ctrlKey || event.metaKey) ? select.toggle() : onOpen())}
      >
        <span className={`card-art square${round ? ' round' : ''}`}>
          {art && art.length ? <Mosaic ids={art} kind="audio" /> : <span className="art art-placeholder"><Icon name={icon || 'disc'} size={30} /></span>}
          {badge && <span className="card-time tabular">{badge}</span>}
          {select?.selecting ? (
            <span className={`check-box card-check${select.selected ? ' on' : ''}`} aria-hidden="true">
              {select.selected && <Icon name="check" size={13} />}
            </span>
          ) : (
            <span className="card-play" aria-hidden="true">
              <Icon name="chevron" size={18} />
            </span>
          )}
        </span>
        <span className="card-title" title={title}>
          {title}
        </span>
        <span className="card-sub">{note ? <span className="row-note">{note}</span> : sub}</span>
      </button>
      {menu && <span className="card-actions">{menu()}</span>}
      {follow && <div className="card-follow">{follow}</div>}
    </div>
  )
})

export const albumSub = (album: Album) => [album.artist, album.year].filter(Boolean).join(' · ')
export const artistSub = (artist: Artist) => `${artist.albums.length ? `${artist.albums.length} ${artist.albums.length === 1 ? 'album' : 'albums'} · ` : ''}${artist.songs.length} ${artist.songs.length === 1 ? 'song' : 'songs'}`
export const folderSub = (folder: FolderModel) => `${folder.count} ${folder.count === 1 ? 'item' : 'items'}`

/** An album, artist or folder as a row (list view). */
export const OpenRow = memo(function OpenRow({
  state,
  title,
  sub,
  art,
  onOpen,
  icon,
  menu,
  select,
  follow,
  note,
}: {
  state: CellState
  title: string
  sub?: string
  art?: string[]
  onOpen: () => void
  icon?: string
  menu?: () => ReactNode
  select?: TileSelect
  /** A persistent Follow toggle before the hover actions. */
  follow?: ReactNode
  /** A calm note after the sub line (a failed check). */
  note?: ReactNode
}) {
  return (
    <div className={`song-row open-row${select?.selected ? ' is-selected' : ''}`}>
      <button
        type="button"
        className="row-main"
        data-focus
        tabIndex={state.tabIndex}
        aria-label={`${title}${sub ? `, ${sub}` : ''}`}
        aria-pressed={select?.selecting ? select.selected : undefined}
        onClick={(event) => (select && (select.selecting || event.ctrlKey || event.metaKey) ? select.toggle() : onOpen())}
      >
        {select?.selecting && (
          <span className={`check-box${select.selected ? ' on' : ''}`} aria-hidden="true">
            {select.selected && <Icon name="check" size={13} />}
          </span>
        )}
        <span className="art art-open">{art && art.length ? <Mosaic ids={art} kind="audio" /> : <Icon name={icon || 'disc'} size={18} />}</span>
        <span className="row-text">
          <span className="row-title" title={title}>
            {title}
          </span>
          <span className="row-sub">
            {sub}
            {note && <span className="row-note">{note}</span>}
          </span>
        </span>
        <Icon name="chevron" size={15} className="row-chevron" />
      </button>
      {follow && <span className="row-follow">{follow}</span>}
      {menu && <span className="row-actions">{menu()}</span>}
    </div>
  )
})
