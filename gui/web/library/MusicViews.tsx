import {useMemo, type ReactNode} from 'react'
import {restoreOrder, type Collection, type LibraryItem} from '../../shared/library'
import {formatBytes, plural} from '../format'
import {toast} from '../toasts'
import {navigate} from '../nav'
import {playAll} from '../player/play'
import {addToQueue, playNext} from '../player/playerStore'
import {EmptyState, IconButton} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {Menu, type MenuItem} from '../ui/Menu'
import {Mosaic} from './Art'
import {Hero} from './Hero'
import {CollectionDetail} from './CollectionDetail'
import {albumSub, artistSub, OpenCard, OpenRow} from './cells'
import {useTileSelection} from './controller'
import {sizes, type Density} from './density'
import {openDialog} from './dialogState'
import {setLibView} from './libNav'
import {getLibItem, isSong, libraryAct, useLibrary} from './libStore'
import {
  durationWords,
  norm,
  collectionViews,
  folderPlaylists,
  smartItems,
  smartPlaylists,
  smartTitle,
  totalDuration,
  trackOrder,
  type Album,
  type Artist,
  type SmartId,
} from './model'
import {SongList} from './SongList'
import {collectionSubtitle} from './spacesModel'
import {SelectionBar, type ViewMode} from './Toolbar'
import {VirtualCollection} from './VirtualCollection'

/**
 * Takes songs out of a playlist of yours (the files and the library keep them). Main removes them from the playlist as it is saved, so a
 * second removal right after the first never brings it back. Undo puts them back where they were.
 */
async function removeFromPlaylist(playlist: Collection, ids: string[]) {
  const before = playlist.itemIds
  const removed = new Set(ids.filter((id) => before.includes(id)))
  if (!removed.size) return
  const result = await libraryAct('collectionRemove', undefined, {id: playlist.id, ids: [...removed]})
  if (!result.ok) return
  toast({
    tone: 'success',
    key: 'library-undo',
    message: removed.size === 1 ? `Removed from “${playlist.name}”` : `Removed ${plural(removed.size, 'song')} from “${playlist.name}”`,
    action: {
      label: 'Undo',
      run: () =>
        void (async () => {
          const added = await libraryAct<Collection>('collectionAdd', undefined, {id: playlist.id, ids: [...removed]})
          if (added.ok) await libraryAct('collectionReorder', undefined, {id: playlist.id, ids: restoreOrder(before, added.value.itemIds, removed)})
        })(),
    },
  })
}

export function songsMenu(songs: LibraryItem[]): MenuItem[] {
  const ids = songs.map((song) => song.id)
  return [
    {label: 'Play', icon: 'play', onSelect: () => playAll(songs)},
    {label: 'Play next', icon: 'skipNext', onSelect: () => playNext(ids)},
    {label: 'Add to queue', icon: 'queue', onSelect: () => addToQueue(ids)},
    {label: 'Add to playlist', icon: 'playlistAdd', onSelect: () => openDialog({type: 'playlistAdd', ids})},
  ]
}

/** Move or copy every item of a collection to another space. */
export function collectionSpaceMenu(id: string, name: string): MenuItem[] {
  return [
    {label: 'Move to space…', icon: 'move', onSelect: () => openDialog({type: 'move', ids: [], collection: {id, name}})},
    {label: 'Copy to space…', icon: 'copy', onSelect: () => openDialog({type: 'move', ids: [], mode: 'copy', collection: {id, name}})},
  ]
}

export type TileKindFilter = 'all' | 'yours' | 'downloaded' | 'smart' | 'folders' | 'mine'
type SelectProps = {selecting: boolean; onSelectingChange: (on: boolean) => void; density: Density}
type TileProps = SelectProps & {view: ViewMode}

// ---------------------------------------------------------------------------
// Albums and artists
// ---------------------------------------------------------------------------

/** The bar shown above selectable tiles, wired to a tile selection. */
function TileBar({picked, noun, safe}: {picked: ReturnType<typeof useTileSelection>; noun: string; safe?: boolean}) {
  return (
    <SelectionBar
      selection={{selecting: picked.selecting, selected: new Set(picked.ids), setSelecting: () => {}, clear: picked.clear, selectAll: picked.selectAll, toggle: () => {}}}
      total={picked.total}
      tiles={{noun, count: picked.count, total: picked.total, ids: picked.ids, selectAll: picked.selectAll, clear: picked.clear, safe}}
    />
  )
}

export function AlbumsGrid({albums, onOpen, selecting, onSelectingChange, density, view}: {albums: Album[]; onOpen: (album: Album) => void} & TileProps) {
  const size = sizes[density]
  const picked = useTileSelection(
    albums.map((album) => ({key: album.key, items: album.items})),
    {selecting, onSelectingChange},
  )
  return (
    <VirtualCollection
      count={albums.length}
      mode={view}
      rowHeight={size.songRow}
      minCell={size.albumMin}
      cellAspect={1}
      cellExtra={size.cellExtra}
      gap={size.gap}
      density={density}
      onSelectAll={albums.length ? picked.selectAll : undefined}
      header={<TileBar picked={picked} noun="album" />}
      label="Albums"
      keyOf={(index) => albums[index].key}
      renderCell={(state) => {
        const album = albums[state.index]
        const menu = () => <Menu label={`More actions for ${album.title}`} items={songsMenu(album.items)} tabbable={false} />
        return view === 'grid' ? (
          <OpenCard state={state} title={album.title} sub={albumSub(album)} art={album.artId ? [album.artId] : []} onOpen={() => onOpen(album)} menu={menu} label={`${album.title}, ${albumSub(album)}`} select={picked.forTile(album.key)} />
        ) : (
          <OpenRow state={state} title={album.title} sub={`${albumSub(album)} · ${plural(album.items.length, 'song')}`} art={album.artId ? [album.artId] : []} onOpen={() => onOpen(album)} menu={menu} select={picked.forTile(album.key)} />
        )
      }}
    />
  )
}

export function ArtistsGrid({artists, onOpen, selecting, onSelectingChange, density, view}: {artists: Artist[]; onOpen: (artist: Artist) => void} & TileProps) {
  const size = sizes[density]
  const picked = useTileSelection(
    artists.map((artist) => ({key: artist.key, items: artist.songs})),
    {selecting, onSelectingChange},
  )
  return (
    <VirtualCollection
      count={artists.length}
      mode={view}
      rowHeight={size.songRow}
      minCell={size.artistMin}
      cellAspect={1}
      cellExtra={size.cellExtra}
      gap={size.gap}
      density={density}
      onSelectAll={artists.length ? picked.selectAll : undefined}
      header={<TileBar picked={picked} noun="artist" />}
      label="Artists"
      keyOf={(index) => artists[index].key}
      renderCell={(state) => {
        const artist = artists[state.index]
        const menu = () => <Menu label={`More actions for ${artist.name}`} items={songsMenu(artist.songs)} tabbable={false} />
        return view === 'grid' ? (
          <OpenCard state={state} round title={artist.name} sub={artistSub(artist)} art={artist.artId ? [artist.artId] : []} icon="user" onOpen={() => onOpen(artist)} menu={menu} select={picked.forTile(artist.key)} />
        ) : (
          <OpenRow state={state} title={artist.name} sub={artistSub(artist)} art={artist.artId ? [artist.artId] : []} icon="user" onOpen={() => onOpen(artist)} menu={menu} select={picked.forTile(artist.key)} />
        )
      }}
    />
  )
}

export function AlbumDetail({album, ...select}: {album: Album} & SelectProps) {
  const meta = [
    album.artist,
    album.year,
    plural(album.items.length, 'song'),
    durationWords(album.duration),
    formatBytes(album.items.reduce((sum, item) => sum + item.size, 0)),
    album.discs > 1 ? `${album.discs} discs` : '',
  ]
    .filter(Boolean)
    .join(' · ')
  return (
    <SongList
      {...select}
      items={album.items}
      label={`Songs on ${album.title}`}
      menu={[
        ...songsMenu(album.items).slice(1),
        ...(album.compilation || !album.artist ? [] : [{label: 'Go to artist', icon: 'user', onSelect: () => (setLibView({type: 'artist', key: norm(album.artist)}), navigate('Library'))}]),
      ]}
      byDisc={album.discs > 1}
      showAlbum={false}
      hero={<Hero art={album.artId ? [album.artId] : []} title={album.title} kicker={album.compilation ? 'Compilation' : 'Album'} meta={meta} />}
    />
  )
}

export function ArtistDetail({artist, onOpenAlbum, ...select}: {artist: Artist; onOpenAlbum: (album: Album) => void} & SelectProps) {
  const songs = useMemo(() => artist.songs.slice().sort((a, b) => (a.album || '').localeCompare(b.album || '') || trackOrder(a, b)), [artist])
  return (
    <SongList
      {...select}
      items={songs}
      label={`Songs by ${artist.name}`}
      menu={songsMenu(songs).slice(1)}
      extra={[{label: 'Follow', icon: 'user', disabled: true, detail: 'Following for new releases arrives with online search.', onSelect: () => {}}]}
      hero={<Hero art={artist.artId ? [artist.artId] : []} round title={artist.name} kicker="Artist" meta={artistSub(artist)} />}
      below={
        <>
          {artist.albums.length > 0 && (
            <div className="shelf-block">
              <h3 className="shelf-title">Albums</h3>
              <div className="shelf" role="list" aria-label={`Albums by ${artist.name}`}>
                {artist.albums.map((album) => (
                  <button key={album.key} type="button" role="listitem" className="shelf-card" onClick={() => onOpenAlbum(album)} aria-label={`${album.title}${album.year ? `, ${album.year}` : ''}`}>
                    <span className="shelf-art"><Mosaic ids={album.artId ? [album.artId] : []} kind="audio" /></span>
                    <span className="card-title">{album.title}</span>
                    <span className="card-sub">{album.year || plural(album.items.length, 'song')}</span>
                  </button>
                ))}
              </div>
              <h3 className="shelf-title">All songs</h3>
            </div>
          )}
        </>
      }
    />
  )
}

// ---------------------------------------------------------------------------
// Playlists
// ---------------------------------------------------------------------------

export type PlaylistTarget = {type: 'playlist'; id: string} | {type: 'smart'; id: SmartId} | {type: 'folder'; folder: string} | {type: 'collection'; id: string}

type Tile =
  | {kind: 'new'}
  | {kind: 'smart'; id: SmartId; name: string; icon: string; blurb: string; items: LibraryItem[]}
  | {kind: 'playlist'; playlist: Collection; items: LibraryItem[]}
  | {kind: 'folder'; folder: string; name: string; items: LibraryItem[]}
  | {kind: 'collection'; id: string; name: string; items: LibraryItem[]}

export function PlaylistTiles({
  songs,
  playlists,
  query,
  view,
  onOpen,
  scopeFolders = true,
  collectionsOnly = false,
  kindFilter = 'all',
  selecting,
  onSelectingChange,
  density,
}: {
  songs: LibraryItem[]
  playlists: Collection[]
  query: string
  view: ViewMode
  onOpen: (target: PlaylistTarget) => void
  scopeFolders?: boolean
  /** Only the downloaded collections (video and mixed spaces have no other playlists). */
  collectionsOnly?: boolean
  /** Narrows a list that mixes kinds: yours, downloaded, smart or folders. */
  kindFilter?: TileKindFilter
} & SelectProps) {
  const size = sizes[density]
  const lib = useLibrary()
  const tiles = useMemo(() => {
    const q = query.trim().toLowerCase()
    const out: Tile[] = []
    const wants = (kind: Tile['kind']) => kindFilter === 'all' || (kindFilter === 'mine' && (kind === 'playlist' || kind === 'smart')) || (kindFilter === 'yours' && kind === 'playlist') || (kindFilter === 'downloaded' && kind === 'collection') || (kindFilter === 'smart' && kind === 'smart') || (kindFilter === 'folders' && kind === 'folder')
    const matches = (name: string) => !q || name.toLowerCase().includes(q)
    if (!q && !collectionsOnly && (kindFilter === 'all' || kindFilter === 'mine')) out.push({kind: 'new'})
    if (!collectionsOnly && wants('smart')) for (const smart of smartPlaylists) if (matches(smart.name)) out.push({kind: 'smart', ...smart, items: smartItems(smart.id, songs)})
    if (!collectionsOnly && wants('playlist')) for (const playlist of playlists)
      if (matches(playlist.name)) out.push({kind: 'playlist', playlist, items: playlist.itemIds.map(getLibItem).filter((item): item is LibraryItem => !!item)})
    if (wants('collection')) for (const collection of collectionViews(lib.downloaded, songs)) if (matches(collection.title)) out.push({kind: 'collection', id: collection.id, name: collection.title, items: collection.items})
    if (scopeFolders && !collectionsOnly && wants('folder')) for (const folder of folderPlaylists(songs, lib.collectedIds)) if (matches(folder.name)) out.push({kind: 'folder', ...folder})
    return out
  }, [songs, playlists, query, scopeFolders, collectionsOnly, kindFilter, lib.downloaded, lib.collectedIds])
  const tileKey = (tile: Tile) =>
    tile.kind === 'new' ? 'new' : tile.kind === 'smart' ? `smart:${tile.id}` : tile.kind === 'playlist' ? `pl:${tile.playlist.id}` : tile.kind === 'collection' ? `collection:${tile.id}` : `folder:${tile.folder}`
  // "New playlist" is an action, not a playlist; tiles without songs have nothing to act on.
  const picked = useTileSelection(
    tiles.flatMap((tile) => (tile.kind !== 'new' && tile.items.length ? [{key: tileKey(tile), items: tile.items}] : [])),
    {selecting, onSelectingChange},
  )
  if (!tiles.length) return <EmptyState icon="search" title={collectionsOnly ? 'No collections match' : 'No playlists match'}>Try a different word.</EmptyState>
  return (
    <VirtualCollection
      count={tiles.length}
      mode={view}
      rowHeight={size.songRow}
      minCell={size.playlistMin}
      cellAspect={1}
      cellExtra={size.cellExtra}
      gap={size.gap}
      density={density}
      onSelectAll={picked.total ? picked.selectAll : undefined}
      header={<TileBar picked={picked} noun="playlist" safe />}
      label="Playlists"
      keyOf={(index) => tileKey(tiles[index])}
      renderCell={(state) => {
        const tile = tiles[state.index]
        if (tile.kind === 'new')
          return view === 'grid' ? (
            <OpenCard state={state} title="New playlist" sub="Collect songs from anywhere" icon="plus" onOpen={() => openDialog({type: 'playlistName'})} label="New playlist" />
          ) : (
            <OpenRow state={state} title="New playlist" sub="Collect songs from anywhere" icon="plus" onOpen={() => openDialog({type: 'playlistName'})} />
          )
        const art = tile.kind === 'smart' ? tile.items.filter((item) => item.hasArt).slice(0, 4).map((item) => item.id) : tile.items.filter((item) => item.hasArt).slice(0, 4).map((item) => item.id)
        const title = tile.kind === 'smart' ? tile.name : tile.kind === 'playlist' ? tile.playlist.name : tile.name
        const target: PlaylistTarget = tile.kind === 'smart' ? {type: 'smart', id: tile.id} : tile.kind === 'playlist' ? {type: 'playlist', id: tile.playlist.id} : tile.kind === 'collection' ? {type: 'collection', id: tile.id} : {type: 'folder', folder: tile.folder}
        const noun = tile.items.some((item) => !isSong(item)) ? 'item' : 'song'
        const count = plural(tile.items.length, noun)
        const downloaded = tile.kind === 'collection' ? lib.downloaded.find((entry) => entry.id === tile.id) : undefined
        const sub =
          tile.kind === 'collection' && downloaded
            ? collectionSubtitle(downloaded, noun, tile.items.length)
            : tile.kind === 'playlist'
              ? `Yours · ${count}`
              : `${tile.kind === 'smart' ? 'Smart · ' : tile.kind === 'folder' ? 'Folder · ' : ''}${count}`
        const icon = tile.kind === 'smart' ? tile.icon : tile.kind === 'folder' ? 'folder' : 'queue'
        const menu = () => (
          <>
            <IconButton icon="play" size="sm" tabIndex={-1} label={`Play ${title}`} onClick={() => playAll(tile.items)} />
            <Menu label={`More actions for ${title}`} items={tile.kind === 'collection' || tile.kind === 'playlist' ? [...songsMenu(tile.items), 'separator', ...collectionSpaceMenu(tile.kind === 'collection' ? tile.id : tile.playlist.id, title)] : songsMenu(tile.items)} tabbable={false} />
          </>
        )
        return view === 'grid' ? (
          <OpenCard state={state} title={title} sub={sub} art={art} icon={icon} onOpen={() => onOpen(target)} menu={tile.items.length ? menu : undefined} select={tile.items.length ? picked.forTile(tileKey(tile)) : undefined} />
        ) : (
          <OpenRow state={state} title={title} sub={sub} art={art} icon={icon} onOpen={() => onOpen(target)} menu={tile.items.length ? menu : undefined} select={tile.items.length ? picked.forTile(tileKey(tile)) : undefined} />
        )
      }}
    />
  )
}

export function PlaylistDetail({target, songs, playlists, ...select}: {target: PlaylistTarget; songs: LibraryItem[]; playlists: Collection[]} & SelectProps) {
  const lib = useLibrary()
  if (target.type === 'smart') {
    const items = smartItems(target.id, songs)
    const info = smartPlaylists.find((entry) => entry.id === target.id)
    return (
      <SongList
        {...select}
        items={items}
        label={smartTitle(target.id)}
        menu={songsMenu(items).slice(1)}
        hero={<Hero art={items.filter((i) => i.hasArt).slice(0, 4).map((i) => i.id)} title={smartTitle(target.id)} kicker="Smart playlist" meta={`${info?.blurb ?? ''} ${plural(items.length, 'song')}`.trim()} />}
        empty={
          <EmptyState icon={info?.icon || 'music'} title={target.id === 'favorites' ? 'No favorites yet' : 'Nothing here yet'}>
            {target.id === 'favorites'
              ? 'Press the heart on any song to keep it here.'
              : target.id === 'most-played'
                ? 'Songs you play appear here, most played first.'
                : target.id === 'never-played'
                  ? 'You have played every song at least once.'
                  : 'Newly added songs appear here.'}
          </EmptyState>
        }
      />
    )
  }
  if (target.type === 'collection') return <CollectionDetail id={target.id} {...select} />
  if (target.type === 'folder') {
    const folder = folderPlaylists(songs, lib.collectedIds).find((entry) => entry.folder === target.folder)
    const items = folder?.items || []
    return (
      <SongList
        {...select}
        items={items}
        label={folder?.name || 'Folder'}
        menu={songsMenu(items).slice(1)}
        hero={<Hero art={items.filter((i) => i.hasArt).slice(0, 4).map((i) => i.id)} title={folder?.name || 'Folder'} kicker="Folder playlist" meta={`${plural(items.length, 'song')} · ${durationWords(totalDuration(items))}`} />}
      />
    )
  }
  const playlist = playlists.find((entry) => entry.id === target.id)
  if (!playlist) return <EmptyState icon="queue" title="This playlist is gone">It was deleted. Pick another from the list.</EmptyState>
  const items = playlist.itemIds.map(getLibItem).filter((item): item is LibraryItem => !!item)
  const missing = playlist.itemIds.length - items.length
  const save = (itemIds: string[]) => libraryAct('collectionSave', undefined, {collection: {id: playlist.id, name: playlist.name, itemIds}})
  return (
    <SongList
      {...select}
      items={items}
      label={playlist.name}
      menu={[
        ...songsMenu(items).slice(1),
        'separator',
        {label: 'Rename…', icon: 'edit', onSelect: () => openDialog({type: 'playlistName', id: playlist.id})},
        {label: 'Export M3U8…', icon: 'external', disabled: !items.length, onSelect: () => void libraryAct('collectionExport', undefined, {id: playlist.id})},
        {label: 'Move to space…', icon: 'move', disabled: !items.length, onSelect: () => openDialog({type: 'move', ids: [], collection: {id: playlist.id, name: playlist.name}})},
        {label: 'Copy to space…', icon: 'copy', disabled: !items.length, onSelect: () => openDialog({type: 'move', ids: [], mode: 'copy', collection: {id: playlist.id, name: playlist.name}})},
        'separator',
        {label: 'Delete playlist', icon: 'trash', tone: 'danger', onSelect: () => openDialog({type: 'playlistDelete', id: playlist.id})},
      ]}
      onReorder={(ids) => void save([...ids, ...playlist.itemIds.filter((id) => !ids.includes(id) && !getLibItem(id))])}
      onRemove={(ids) => void removeFromPlaylist(playlist, ids)}
      hero={<Hero art={items.filter((i) => i.hasArt).slice(0, 4).map((i) => i.id)} title={playlist.name} kicker="Playlist" meta={`${plural(items.length, 'song')} · ${durationWords(totalDuration(items))}${missing ? ` · ${plural(missing, 'song')} no longer in your library` : ''}`} />}
      empty={
        <EmptyState icon="playlistAdd" title="This playlist is empty">
          Open Songs, pick a few and choose <strong>Add to playlist</strong>.
        </EmptyState>
      }
    />
  )
}

