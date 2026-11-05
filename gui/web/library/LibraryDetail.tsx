// Pages opened from the sidebar's "Your library": one album, artist, playlist or downloaded collection. The hero is the page header;
// its breadcrumb ("Library › Albums") is the way back and returns to the same filter.
import {useMemo, useState} from 'react'
import type {Crumb} from '../shell/PageHeader'
import {EmptyState} from '../ui/controls'
import {useDensity} from './density'
import {HeroTrail} from './Hero'
import {setLibFilter, setLibView, type LibFilter, type LibView} from './libNav'
import {isSong, useLibrary} from './libStore'
import {groupAlbums, groupArtists} from './model'
import {AlbumDetail, ArtistDetail, PlaylistDetail} from './MusicViews'
import {Skeleton} from './Skeleton'

type Detail = Extract<LibView, {type: 'album' | 'artist' | 'playlist' | 'collection'}>

const parents: Record<Detail['type'], {filter: LibFilter; label: string}> = {
  album: {filter: 'albums', label: 'Albums'},
  artist: {filter: 'artists', label: 'Artists'},
  playlist: {filter: 'playlists', label: 'Playlists'},
  collection: {filter: 'collections', label: 'Collections'},
}

/** "Library › Albums": every step is a link back; the filter it lands on is the kind of thing that was open. */
export function libraryTrail(type: Detail['type'] | 'smart'): Crumb[] {
  const home = () => setLibView({type: 'home'})
  if (type === 'smart') return [{label: 'Library', onSelect: home}]
  const parent = parents[type]
  return [
    {label: 'Library', onSelect: home},
    {
      label: parent.label,
      onSelect: () => {
        setLibFilter(parent.filter)
        home()
      },
    },
  ]
}

export function LibraryDetail({view}: {view: Detail}) {
  const lib = useLibrary()
  const [selecting, setSelecting] = useState(false)
  const [density] = useDensity('playlists')
  const songs = useMemo(() => lib.snapshot.items.filter(isSong), [lib.snapshot.items])
  const albums = useMemo(() => (view.type === 'album' || view.type === 'artist' ? groupAlbums(songs) : []), [view.type, songs])
  const artists = useMemo(() => (view.type === 'artist' ? groupArtists(songs, albums) : []), [view.type, songs, albums])
  if (!lib.loaded) return <Skeleton rows={6} />
  const select = {selecting, onSelectingChange: setSelecting, density}
  let body
  if (view.type === 'album') {
    const album = albums.find((entry) => entry.key === view.key)
    body = album ? <AlbumDetail album={album} {...select} /> : <Gone what="album" />
  } else if (view.type === 'artist') {
    const artist = artists.find((entry) => entry.key === view.key)
    body = artist ? <ArtistDetail artist={artist} onOpenAlbum={(album) => setLibView({type: 'album', key: album.key})} {...select} /> : <Gone what="artist" />
  } else if (view.type === 'playlist') body = <PlaylistDetail target={{type: 'playlist', id: view.id}} songs={songs} playlists={lib.playlists} {...select} />
  else body = <PlaylistDetail target={{type: 'collection', id: view.id}} songs={songs} playlists={lib.playlists} {...select} />
  return (
    <HeroTrail.Provider value={libraryTrail(view.type)}>
      <div className="space-view">
        <div className="space-body">{body}</div>
      </div>
    </HeroTrail.Provider>
  )
}

function Gone({what}: {what: string}) {
  return (
    <EmptyState icon="search" title={`This ${what} is no longer here`}>
      It was renamed, moved or removed. Go back to see what is in the library now.
    </EmptyState>
  )
}
