// One kind of thing on the Library page, full width: playlists, collections, albums or artists.
// Opening an entry goes to its own page, whose breadcrumb ("Library › Albums") returns here (the filter is remembered).
import {useMemo, useState, type ReactNode} from 'react'
import {PageToolbar} from '../shell/PageToolbar'
import {Button, EmptyState, Select} from '../ui/controls'
import {useStoredChoice} from './controller'
import {useDensity} from './density'
import {openDialog} from './dialogState'
import {setLibView} from './libNav'
import {isSong, useLibrary} from './libStore'
import {groupAlbums, groupArtists, norm} from './model'
import {AlbumsGrid, ArtistsGrid, PlaylistTiles, type PlaylistTarget, type TileKindFilter} from './MusicViews'
import {Skeleton} from './Skeleton'
import {DensityMenu, SearchBox, type ViewMode} from './Toolbar'

export type Kind = 'playlists' | 'collections' | 'albums' | 'artists'
const words: Record<Kind, string> = {playlists: 'playlists', collections: 'collections', albums: 'albums', artists: 'artists'}

const open = (target: PlaylistTarget) => {
  if (target.type === 'playlist') setLibView({type: 'playlist', id: target.id})
  else if (target.type === 'collection') setLibView({type: 'collection', id: target.id})
  else if (target.type === 'smart') setLibView({type: 'smart', id: target.id})
}

export function KindPage({kind, lead}: {kind: Kind; lead: ReactNode}) {
  const lib = useLibrary()
  const [query, setQuery] = useState('')
  const [mode, setMode] = useStoredChoice<ViewMode>(`keepfin-lib-view:${kind}`, 'grid', ['list', 'grid'])
  const [selecting, setSelecting] = useState(false)
  const [playlistKind, setPlaylistKind] = useState<TileKindFilter>('mine')
  const [density, setDensity] = useDensity('playlists')
  const songs = useMemo(() => lib.snapshot.items.filter(isSong), [lib.snapshot.items])
  const albums = useMemo(() => groupAlbums(songs), [songs])
  const artists = useMemo(() => groupArtists(songs, albums), [songs, albums])
  const q = query.trim().toLowerCase()
  const shownAlbums = useMemo(() => (q ? albums.filter((album) => `${album.title} ${album.artist}`.toLowerCase().includes(q)) : albums), [albums, q])
  const shownArtists = useMemo(() => (q ? artists.filter((artist) => norm(artist.name).includes(norm(q))) : artists), [artists, q])
  if (!lib.loaded) return <Skeleton rows={6} />
  const select = {selecting, onSelectingChange: setSelecting, density}
  const empty = kind === 'collections' ? !lib.downloaded.length : kind === 'playlists' ? false : kind === 'albums' ? !albums.length : !artists.length
  return (
    <>
      <PageToolbar
        label="Library toolbar"
        left={lead}
        right={
          <>
            <SearchBox value={query} onChange={setQuery} placeholder={`Filter ${words[kind]}`} label={`Filter ${words[kind]}`} />
            {kind === 'playlists' && (
              <Select<TileKindFilter>
                label="Show"
                value={playlistKind}
                onChange={setPlaylistKind}
                options={[
                  {value: 'mine', label: 'Yours and smart'},
                  {value: 'yours', label: 'Yours'},
                  {value: 'smart', label: 'Smart'},
                ]}
              />
            )}
            <div className="seg-wrap">
              <button type="button" className={`chip${mode === 'grid' ? ' on' : ''}`} aria-pressed={mode === 'grid'} onClick={() => setMode('grid')}>
                Grid
              </button>
              <button type="button" className={`chip${mode === 'list' ? ' on' : ''}`} aria-pressed={mode === 'list'} onClick={() => setMode('list')}>
                List
              </button>
            </div>
            <DensityMenu value={density} onChange={setDensity} />
            {kind === 'playlists' && (
              <Button size="sm" icon="plus" onClick={() => openDialog({type: 'playlistName'})}>
                New playlist
              </Button>
            )}
            <Button size="sm" variant="secondary" icon="check" aria-pressed={selecting} onClick={() => setSelecting(!selecting)}>
              {selecting ? 'Done' : 'Select'}
            </Button>
          </>
        }
      />
      <div className="space-body">
        {empty ? (
          <EmptyState icon={kind === 'collections' ? 'queue' : 'disc'} title={`No ${words[kind]} yet`}>
            {kind === 'collections'
              ? 'Paste a playlist, album or channel link and keep it. It shows up here.'
              : 'Save some music and it is sorted here by its tags.'}
          </EmptyState>
        ) : kind === 'albums' ? (
          <AlbumsGrid albums={shownAlbums} view={mode} {...select} onOpen={(album) => setLibView({type: 'album', key: album.key})} />
        ) : kind === 'artists' ? (
          <ArtistsGrid artists={shownArtists} view={mode} {...select} onOpen={(artist) => setLibView({type: 'artist', key: artist.key})} />
        ) : (
          <PlaylistTiles
            songs={kind === 'collections' ? lib.snapshot.items : songs}
            playlists={lib.playlists}
            query={query}
            view={mode}
            onOpen={open}
            kindFilter={kind === 'collections' ? 'downloaded' : playlistKind}
            collectionsOnly={kind === 'collections'}
            scopeFolders={false}
            {...select}
          />
        )}
      </div>
    </>
  )
}
