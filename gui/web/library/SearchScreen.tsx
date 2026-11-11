// Search: your library first (filterable), then a way to look online. A link in the box is a download, not a search.
import {useDeferredValue, useEffect, useMemo, useState} from 'react'
import type {Collection, LibraryItem} from '../../shared/library'
import {plural} from '../format'
import {navigate} from '../nav'
import {ItemSection} from '../media/ItemSection'
import {Section, SectionFrame} from '../media/Section'
import {useCardSizes} from '../media/useCardSizes'
import {Button, EmptyState} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {CollectionContext, OpenCard, sectionCell, SongRow} from './cells'
import {useCollectionController} from './controller'
import {setLibView, type LibView} from './libNav'
import {isSong, useLibrary} from './libStore'
import {MusicVideoSection} from './musicVideos/MusicVideoSection'
import {matchingRows, useMusicVideoRows} from './musicVideos/rows'
import {groupAlbums, groupArtists, matchesQuery, norm, queryWords, type Album, type Artist} from './model'
import {routeLinks} from './OmniBox'
import {focusSearch} from '../shell/AppBar'
import {PageHeader} from '../shell/PageHeader'
import {PageToolbar} from '../shell/PageToolbar'
import {getSearchQuery, useSearchQuery, youtubeSearchLink} from './searchStore'
import {Skeleton} from './Skeleton'

type Filter = 'all' | 'video' | 'song' | 'album' | 'artist' | 'playlist'
const filters: {id: Filter; label: string}[] = [
  {id: 'all', label: 'All'},
  {id: 'video', label: 'Videos'},
  {id: 'song', label: 'Songs'},
  {id: 'album', label: 'Albums'},
  {id: 'artist', label: 'Artists'},
  {id: 'playlist', label: 'Playlists'},
]
const SONG_LIMIT = 30

const open = (view: LibView) => {
  setLibView(view)
  navigate('Library')
}

function SongResults({songs}: {songs: LibraryItem[]}) {
  const shown = songs.slice(0, SONG_LIMIT)
  const controller = useCollectionController(shown)
  return (
    <CollectionContext.Provider value={controller.api}>
      <div className="search-songs" role="list" aria-label="Songs">
        {shown.map((item, index) => (
          <div key={item.id} role="listitem">
            <SongRow id={item.id} state={sectionCell(index)} lead={false} />
          </div>
        ))}
      </div>
      {songs.length > shown.length && <p className="muted small pane-note">Showing the first {SONG_LIMIT} of {songs.length}. Add words to narrow it down.</p>}
      {controller.menuNode}
    </CollectionContext.Provider>
  )
}

export function SearchScreen() {
  const lib = useLibrary()
  const cardSizes = useCardSizes()
  const query = useSearchQuery()
  const [filter, setFilter] = useState<Filter>('all')
  const musicVideoRows = useMusicVideoRows()
  const deferred = useDeferredValue(query)
  const text = deferred.trim()
  const words = useMemo(() => queryWords(text), [text])
  const results = useMemo(() => {
    if (!words.length) return undefined
    const playable = lib.snapshot.items.filter((item) => item.kind !== 'other' && !item.missing)
    const hits = playable.filter((item) => matchesQuery(item, words))
    const songs = hits.filter(isSong)
    const allSongs = playable.filter(isSong)
    const albumPool = groupAlbums(allSongs)
    const has = (value: string) => {
      const key = norm(value)
      return words.every((word) => key.includes(word))
    }
    const albums: Album[] = albumPool.filter((album) => has(`${album.title} ${album.artist}`))
    const artists: Artist[] = groupArtists(allSongs, albumPool).filter((artist) => has(artist.name))
    const playlists: Collection[] = [...lib.playlists, ...lib.downloaded].filter((collection) => has(collection.name))
    return {
      songs,
      // a video kept as a song is under Songs only
      videos: hits.filter((item) => item.kind === 'video' && !isSong(item)),
      // a song's music video is a video too
      musicVideos: matchingRows(musicVideoRows, words),
      photos: hits.filter((item) => item.kind === 'image'),
      albums,
      artists,
      playlists,
    }
  }, [words, lib.snapshot.items, lib.playlists, lib.downloaded, musicVideoRows])

  const total = results ? results.songs.length + results.videos.length + results.musicVideos.length + results.photos.length + results.albums.length + results.artists.length + results.playlists.length : 0
  const show = (wanted: Filter[]) => filter === 'all' || wanted.includes(filter)
  const coverOf = (collection: Collection) => {
    const first = (collection.coverItemId && lib.byId.get(collection.coverItemId)) || collection.itemIds.map((id) => lib.byId.get(id)).find((item) => item?.hasArt)
    return first && first.hasArt ? [first.id] : []
  }

  // The search field is the app bar's; arriving here from the sidebar puts the cursor in it.
  useEffect(() => {
    if (!getSearchQuery()) focusSearch()
  }, [])

  return (
    <div className="screen home search-screen" ref={cardSizes}>
      <PageHeader title="Search" caption="Your library first. Paste a link in the search bar any time to download it." />
      {lib.loaded && text && results && (
        <PageToolbar
          label="Search toolbar"
          left={
            <div className="chips" role="group" aria-label="Filter results">
              {filters.map((entry) => (
                <button key={entry.id} type="button" className={`chip${filter === entry.id ? ' on' : ''}`} aria-pressed={filter === entry.id} onClick={() => setFilter(entry.id)}>
                  {entry.label}
                </button>
              ))}
            </div>
          }
        />
      )}
      {!lib.loaded && <Skeleton rows={5} />}
      {lib.loaded && !text && (
        <EmptyState icon="search" title="Search everything you have saved">
          Songs, videos, photos, albums, artists and playlists. Type a few words; accents and capital letters don’t matter.
        </EmptyState>
      )}
      {lib.loaded && text && results && (
        <>
          <section className="search-group" aria-label="In your library">
            <h3 className="group-title">In your library</h3>
            {total === 0 && (
              <p className="muted pane-note" role="status">
                Nothing in your library matches “{text}”.
              </p>
            )}
            {show(['song']) && results.songs.length > 0 && (
              <SectionFrame id="songs" title="Songs" count={results.songs.length}>
                <SongResults songs={results.songs} />
              </SectionFrame>
            )}
            {show(['video']) && <ItemSection id="videos" title="Videos" count={results.videos.length} kind="video" items={results.videos} />}
            {show(['video']) && <MusicVideoSection rows={results.musicVideos} />}
            {show(['artist']) && (
              <Section
                id="artists"
                title="Artists"
                count={results.artists.length}
                kind="round"
                items={results.artists}
                itemKey={(artist) => artist.key}
                renderItem={(artist, index) => (
                  <OpenCard state={sectionCell(index)} kind="round" title={artist.name} sub={plural(artist.songs.length, 'song')} art={artist.artId ? [artist.artId] : []} icon="user" onOpen={() => open({type: 'artist', key: artist.key})} />
                )}
              />
            )}
            {show(['album']) && (
              <Section
                id="albums"
                title="Albums"
                count={results.albums.length}
                kind="square"
                items={results.albums}
                itemKey={(album) => album.key}
                renderItem={(album, index) => (
                  <OpenCard state={sectionCell(index)} title={album.title} sub={album.artist} art={album.artId ? [album.artId] : []} label={`${album.title}, ${album.artist}`} onOpen={() => open({type: 'album', key: album.key})} />
                )}
              />
            )}
            {show(['playlist']) && (
              <Section
                id="playlists"
                title="Playlists"
                count={results.playlists.length}
                kind="square"
                items={results.playlists}
                itemKey={(collection) => collection.id}
                renderItem={(collection, index) => (
                  <OpenCard
                    state={sectionCell(index)}
                    title={collection.name}
                    sub={plural(collection.itemIds.length, 'item')}
                    art={coverOf(collection)}
                    icon="queue"
                    label={`${collection.name}, ${collection.kind === 'own' ? 'playlist' : 'collection'}`}
                    onOpen={() => open(collection.kind === 'own' ? {type: 'playlist', id: collection.id} : {type: 'collection', id: collection.id})}
                  />
                )}
              />
            )}
            {show(['video']) && <ItemSection id="photos" title="Photos" count={results.photos.length} kind="photo" items={results.photos} />}
          </section>
          <section className="search-group" aria-label="On YouTube">
            <h3 className="group-title">On YouTube</h3>
            <div className="online-card">
              <span className="online-icon">
                <Icon name="globe" size={20} />
              </span>
              <span className="online-text">
                <strong>Search YouTube for “{text}”</strong>
                <span className="muted small">Opens the results in the download picker, where you choose what to keep.</span>
              </span>
              <Button variant="primary" icon="search" onClick={() => routeLinks(youtubeSearchLink(text))} aria-label={`Search YouTube for ${text}`}>
                Search YouTube
              </Button>
            </div>
          </section>
        </>
      )}
    </div>
  )
}
