import {useCallback, useDeferredValue, useEffect, useMemo, useState} from 'react'
import {isKeptSpace, type LibraryItem, type Space} from '../../shared/library'
import {plural} from '../format'
import {PageHeader, type Crumb} from '../shell/PageHeader'
import {PageToolbar} from '../shell/PageToolbar'
import {Button, EmptyState, InlineAlert, Tabs} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {playAll} from '../player/play'
import {addToQueue} from '../player/playerStore'
import {SpaceMark} from './SpacesHub'
import {noTagFilter, TagFilter, type TagFilterState} from './TagFilter'
import {filterByTags} from './spacesModel'
import {Menu} from '../ui/Menu'
import {Skeleton} from './Skeleton'
import {EmptySpace, NoMatches} from './Empty'
import {useStoredChoice} from './controller'
import {useDensity} from './density'
import {setLibView, useLibView, type SpaceDetail} from './libNav'
import {openDialog} from './dialogState'
import {FilesView, type KindFilter} from './FilesView'
import {isSong, libraryAct, libraryRequest, loadLibrary, useLibrary} from './libStore'
import {
  collectionViews,
  groupAlbums,
  groupArtists,
  matchesQuery,
  queryWords,
  sortItems,
  type Album,
  type Artist,
  type SortKey,
} from './model'
import {HeroTrail} from './Hero'
import {AlbumDetail, AlbumsGrid, ArtistDetail, ArtistsGrid, PlaylistDetail, PlaylistTiles, type PlaylistTarget} from './MusicViews'
import {SongList} from './SongList'
import {Toolbar, type ViewMode} from './Toolbar'

type Tab = 'songs' | 'albums' | 'artists' | 'collections'
const tabs: Tab[] = ['songs', 'albums', 'artists', 'collections']
type Detail = SpaceDetail
const songSorts: SortKey[] = ['title', 'artist', 'album', 'added', 'duration', 'plays']
const albumSorts: SortKey[] = ['title', 'artist', 'duration']
const fileSorts: SortKey[] = ['added', 'title', 'duration', 'size', 'plays']
const EMPTY: LibraryItem[] = []

/** Search with lyric matches: instant on the loaded items; lyrics are looked up by the main process. */
export function useSpaceSearch(items: LibraryItem[], query: string, spaceId: string) {
  const deferred = useDeferredValue(query)
  const words = useMemo(() => queryWords(deferred), [deferred])
  const [lyricHits, setLyricHits] = useState<{query: string; ids: Set<string>}>({query: '', ids: new Set()})
  const [searching, setSearching] = useState(false)
  const hasLyrics = useMemo(() => items.some((item) => item.hasLyrics), [items])
  useEffect(() => {
    const text = deferred.trim()
    if (text.length < 3 || !hasLyrics) {
      setLyricHits((previous) => (previous.ids.size ? {query: '', ids: new Set()} : previous))
      return
    }
    let alive = true
    setSearching(true)
    const timer = setTimeout(async () => {
      try {
        const raw = await libraryRequest<unknown>('librarySearchLyrics', undefined, {text, space: spaceId})
        const list = Array.isArray(raw) ? raw : raw && typeof raw === 'object' && Array.isArray((raw as {ids?: unknown}).ids) ? (raw as {ids: unknown[]}).ids : []
        if (alive) setLyricHits({query: text, ids: new Set(list.filter((id): id is string => typeof id === 'string'))})
      } catch {
        if (alive) setLyricHits({query: text, ids: new Set()})
      } finally {
        if (alive) setSearching(false)
      }
    }, 300)
    return () => {
      alive = false
      clearTimeout(timer)
      setSearching(false)
    }
  }, [deferred, hasLyrics, spaceId])
  const filtered = useMemo(() => {
    if (!words.length) return items
    const viaLyrics = lyricHits.query === deferred.trim() ? lyricHits.ids : new Set<string>()
    return items.filter((item) => matchesQuery(item, words) || viaLyrics.has(item.id))
  }, [items, words, lyricHits, deferred])
  const lyricOnly = useMemo(() => {
    if (!words.length || lyricHits.query !== deferred.trim()) return 0
    return filtered.filter((item) => lyricHits.ids.has(item.id) && !matchesQuery(item, words)).length
  }, [filtered, lyricHits, words, deferred])
  return {filtered, searching, lyricOnly, pending: deferred !== query}
}

export function SpaceView({space}: {space: Space}) {
  const lib = useLibrary()
  const music = space.kind === 'music'
  const all = lib.bySpace.get(space.id) ?? EMPTY
  const [query, setQuery] = useState('')
  const [selecting, setSelecting] = useState(false)
  const [tab, setTab] = useStoredChoice<Tab>(`keepfin-lib-tab:${space.id}`, 'songs', tabs)
  const [view, setView] = useStoredChoice<ViewMode>(`keepfin-lib-view:${space.id}`, music ? 'grid' : 'grid', ['list', 'grid'])
  const [sort, setSort] = useStoredChoice<SortKey>(`keepfin-lib-sort:${space.id}`, music ? 'title' : 'added', [...songSorts, ...fileSorts])
  const [density, setDensity] = useDensity(space.id)
  // What is open inside the space lives in the Library's view (not here), so the back button and Alt+Left close it
  const libView = useLibView()
  const detail = libView.type === 'space' && libView.id === space.id ? libView.detail : undefined
  const setDetail = useCallback(
    (next: Detail | undefined) => setLibView(next ? {type: 'space', id: space.id, detail: next} : {type: 'space', id: space.id}),
    [space.id],
  )
  const [path, setPath] = useState('')
  const [kind, setKind] = useState<KindFilter>('all')
  const [tagState, setTagState] = useState<TagFilterState>(noTagFilter)

  const { filtered: searched, searching, lyricOnly } = useSpaceSearch(all, query, space.id)
  const filtered = useMemo(() => filterByTags(searched, tagState.tags, tagState.mode), [searched, tagState])
  const songs = useMemo(() => (music ? filtered.filter(isSong) : EMPTY), [music, filtered])
  const sortedSongs = useMemo(() => sortItems(songs, songSorts.includes(sort) ? sort : 'title'), [songs, sort])
  const groupedAlbums = useMemo(() => (music ? groupAlbums(songs) : []), [music, songs])
  const albumSort = albumSorts.includes(sort) ? sort : 'title'
  const albums = useMemo(() => {
    if (albumSort === 'title') return groupedAlbums
    return [...groupedAlbums].sort((a, b) => (albumSort === 'duration' ? b.duration - a.duration : a.artist.localeCompare(b.artist) || a.title.localeCompare(b.title)))
  }, [groupedAlbums, albumSort])
  const artists = useMemo(() => (music ? groupArtists(songs, albums) : []), [music, songs, albums])
  const collections = useMemo(() => (music ? [] : collectionViews(lib.downloaded, all)), [music, all, lib.downloaded])
  const [shelf, setShelf] = useState<'files' | 'collections'>('files')
  // "Show" on a toast opens one of this space's collections.
  useEffect(() => {
    if (libView.type !== 'space' || libView.id !== space.id || !libView.open) return
    setQuery('')
    setTagState(noTagFilter)
    setPath('')
    setKind('all')
    if (music) setTab('collections')
    else setShelf('collections')
    setDetail({type: 'playlist', target: {type: 'collection', id: libView.open}})
  }, [libView, space.id, music, setTab, setDetail])
  // The last collection in a space was deleted: leave its views instead of showing a stale detail.
  useEffect(() => {
    if (music || collections.length) return
    setShelf('files')
    setDetail(undefined)
  }, [music, collections.length])
  const allSongs = useMemo(() => (music ? all.filter(isSong) : EMPTY), [music, all])
  const problems = lib.snapshot.problems.filter((problem) => problem.space === space.id)
  const kinds = useMemo(() => {
    const present = new Set(all.map((item) => item.kind))
    return (['video', 'audio', 'image'] as const).filter((entry) => present.has(entry))
  }, [all])
  const select = {selecting, onSelectingChange: setSelecting, density}

  // Leaving the folder or tab ends a selection that belonged to it.
  useEffect(() => setSelecting(false), [tab, detail, path, kind])

  const playable = filtered.filter((item) => item.kind === 'audio' || item.kind === 'video')
  // The space's page header: name, item count and folder, Play all (the one primary action), Shuffle and a ⋯ menu.
  const header = (
    <PageHeader
      title={space.name}
      crumbs={[{label: 'Library', onSelect: () => setLibView({type: 'home'})}]}
      caption={
        <>
          <span className="caption-mark">
            <SpaceMark space={space} size={11} />
          </span>
          <span title={space.folder}>
            {plural(all.length, 'item')} · {space.folder}
          </span>
          {lib.snapshot.scanning && (
            <span className="scan-note" role="status">
              <Icon name="refresh" size={12} className="spin" /> Scanning…
            </span>
          )}
        </>
      }
      actions={
        <>
          <Button size="sm" variant="primary" icon="play" className="play-all" aria-label="Play all" title="Play all" disabled={!playable.length} onClick={() => playAll(playable)}>
            Play all
          </Button>
          <Button size="sm" icon="shuffle" aria-label="Shuffle" title="Shuffle" disabled={!playable.length} onClick={() => playAll(playable, true)}>
            Shuffle
          </Button>
          <Menu
            label={`${space.name} options`}
            items={[
              {label: 'Add to queue', icon: 'queue', disabled: !playable.length, onSelect: () => addToQueue(playable.map((item) => item.id))},
              'separator',
              {label: 'Edit space…', icon: 'edit', onSelect: () => openDialog({type: 'space', space})},
              {label: 'Rescan folder', icon: 'refresh', onSelect: () => void libraryAct('libraryScan', undefined, {space: space.id})},
              ...(isKeptSpace(space.id) ? [] : (['separator', {label: 'Remove space…', icon: 'trash', tone: 'danger', onSelect: () => openDialog({type: 'removeSpace', space})}] as const)),
            ]}
          />
        </>
      }
    />
  )

  if (!lib.loaded)
    return (
      <div className="space-view">
        {header}
        <Skeleton rows={9} />
      </div>
    )
  if (lib.error)
    return (
      <div className="space-view">
        {header}
        <InlineAlert
          title="Couldn’t read your library"
          actions={
            <Button size="sm" icon="retry" onClick={() => void loadLibrary(true)}>
              Try again
            </Button>
          }
        >
          {lib.error}
        </InlineAlert>
      </div>
    )

  const problemsNode = problems.map((problem, index) => (
    <InlineAlert key={index} tone="warning" title="Couldn’t read this folder">
      {problem.message}
    </InlineAlert>
  ))

  if (!all.length)
    return (
      <div className="space-view">
        {header}
        {problemsNode}
        <EmptySpace space={space} />
      </div>
    )

  // ----- music -----
  if (music) {
    const albumNow = detail?.type === 'album' ? albums.find((album) => album.key === detail.key) : undefined
    const artistNow = detail?.type === 'artist' ? artists.find((artist) => artist.key === detail.key) : undefined
    const inDetail = !!detail
    const tabWords: Record<Tab, string> = {songs: 'Songs', albums: 'Albums', artists: 'Artists', collections: 'Collections'}
    // "Music › Albums": the detail's hero is the page header, and these are the ways back.
    const trail: Crumb[] = [
      {label: space.name, onSelect: () => (setDetail(undefined), setTab('songs'))},
      {label: tabWords[tab], onSelect: () => setDetail(undefined)},
    ]
    const body = () => {
      if (detail?.type === 'album')
        return albumNow ? <AlbumDetail album={albumNow} {...select} /> : <GoneNote what="album" />
      if (detail?.type === 'artist')
        return artistNow ? (
          <ArtistDetail
            artist={artistNow}
            onOpenAlbum={(album: Album) => setDetail({type: 'album', key: album.key})}
            {...select}
          />
        ) : (
          <GoneNote what="artist" />
        )
      if (detail?.type === 'playlist')
        return <PlaylistDetail target={detail.target} songs={allSongs} playlists={lib.playlists} {...select} />
      if (query && !songs.length) return <NoMatches query={query} onClear={() => setQuery('')} />
      if (tab === 'songs')
        return (
          <SongList
            items={sortedSongs}
            label="Songs"
            {...select}
            noActions
            below={lyricOnly > 0 ? <p className="search-note">{plural(lyricOnly, 'song')} matched by lyrics.</p> : undefined}
          />
        )
      if (tab === 'albums') return <AlbumsGrid albums={albums} view={view} {...select} onOpen={(album) => setDetail({type: 'album', key: album.key})} />
      if (tab === 'artists') return <ArtistsGrid artists={artists} view={view} {...select} onOpen={(artist: Artist) => setDetail({type: 'artist', key: artist.key})} />
      return <PlaylistTiles songs={allSongs} playlists={lib.playlists} query={query} view={view} {...select} onOpen={(target) => setDetail({type: 'playlist', target})} />
    }
    return (
      <HeroTrail.Provider value={trail}>
        <div className="space-view">
          {!inDetail && header}
          {!inDetail && problemsNode}
          {!inDetail && (
            <Toolbar
              left={
                <Tabs<Tab>
                  label={`${space.name} views`}
                  idBase="space"
                  value={tab}
                  onChange={setTab}
                  tabs={[
                    {value: 'songs', label: 'Songs', count: songs.length},
                    {value: 'albums', label: 'Albums', count: albums.length},
                    {value: 'artists', label: 'Artists', count: artists.length},
                    {value: 'collections', label: 'Collections', count: lib.downloaded.length + lib.playlists.length || undefined},
                  ]}
                />
              }
              search={{value: query, onChange: setQuery, placeholder: `Filter ${space.name}`, busy: searching}}
              sort={tab === 'songs' ? {value: songSorts.includes(sort) ? sort : 'title', options: songSorts, onChange: setSort} : tab === 'albums' ? {value: albumSort, options: albumSorts, onChange: setSort} : undefined}
              view={tab !== 'songs' ? {value: view, onChange: setView} : undefined}
              density={{value: density, onChange: setDensity}}
              selection={{selecting, setSelecting}}
              addTo={space.id}
            />
          )}
          {!inDetail && tab !== 'collections' && <TagFilter scope={all} state={tagState} onChange={setTagState} result={filtered} spaceId={space.id} />}
          <div id="space-panel" role={inDetail ? undefined : 'tabpanel'} aria-labelledby={inDetail ? undefined : `space-tab-${tab}`} className="space-body">
            {body()}
          </div>
        </div>
      </HeroTrail.Provider>
    )
  }

  // ----- video and mixed -----
  const shelfTabs = collections.length > 0 && !detail && (
    <Tabs<'files' | 'collections'>
      label={`${space.name} views`}
      idBase="space-shelf"
      value={shelf}
      onChange={setShelf}
      tabs={[
        {value: 'files', label: 'Files', count: all.length},
        {value: 'collections', label: 'Collections', count: collections.length},
      ]}
    />
  )
  if (collections.length > 0 && (shelf === 'collections' || detail))
    return (
      <HeroTrail.Provider value={[{label: space.name, onSelect: () => (setDetail(undefined), setShelf('files'))}, {label: 'Collections', onSelect: () => setDetail(undefined)}]}>
        <div className="space-view">
          {!detail && header}
          {!detail && problemsNode}
          {!detail && <PageToolbar label="Library toolbar" left={shelfTabs} />}
          <div className="space-body">
            {detail?.type === 'playlist' ? (
              <PlaylistDetail target={detail.target} songs={all} playlists={[]} {...select} />
            ) : (
              <PlaylistTiles songs={all} playlists={[]} query="" view={view} collectionsOnly {...select} onOpen={(target) => setDetail({type: 'playlist', target})} />
            )}
          </div>
        </div>
      </HeroTrail.Provider>
    )
  return (
    <div className="space-view">
      {header}
      {problemsNode}
      <Toolbar
        left={
          <>
            {shelfTabs}
            {kinds.length > 1 && (
              <div className="chips" role="group" aria-label="Show">
                {(['all', ...kinds] as KindFilter[]).map((entry) => (
                  <button key={entry} type="button" className={`chip${kind === entry ? ' on' : ''}`} aria-pressed={kind === entry} onClick={() => setKind(entry)}>
                    {entry === 'all' ? 'All' : entry === 'video' ? 'Videos' : entry === 'audio' ? 'Audio' : 'Images'}
                  </button>
                ))}
              </div>
            )}
          </>
        }
        search={{value: query, onChange: setQuery, placeholder: `Filter ${space.name}`, busy: searching}}
        sort={{value: fileSorts.includes(sort) ? sort : 'added', options: fileSorts, onChange: setSort}}
        view={{value: view, onChange: setView}}
        density={{value: density, onChange: setDensity}}
        selection={{selecting, setSelecting}}
        addTo={space.id}
      />
      <TagFilter scope={all} state={tagState} onChange={setTagState} result={filtered} spaceId={space.id} />
      <div className="space-body">
        <FilesView
          space={space}
          items={filtered}
          path={path}
          onPath={setPath}
          view={view}
          sort={fileSorts.includes(sort) ? sort : 'added'}
          kind={kind}
          query={query}
          onClearQuery={() => setQuery('')}
          {...select}
        />
      </div>
    </div>
  )
}
function GoneNote({what}: {what: string}) {
  return (
    <EmptyState icon="search" title={`This ${what} is no longer here`}>
      It was renamed, moved or removed. Go back to see what is in the library now.
    </EmptyState>
  )
}
