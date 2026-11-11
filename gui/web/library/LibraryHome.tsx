// The Library home ("All"): the sections of the saved layout in order. Spaces are a light row of links; playlists, albums, artists,
// videos, photos and collections are card sections, each hidden when it has nothing to show.
import {useMemo} from 'react'
import {collectionKindWords, type Collection} from '../../shared/library'
import {ItemSection} from '../media/ItemSection'
import {Section} from '../media/Section'
import {PageSections, type PageEdit, type SectionView} from '../media/PageEdit'
import {plural} from '../format'
import {Button, EmptyState} from '../ui/controls'
import {albumSub, artistSub, OpenCard, sectionCell} from './cells'
import {openDialog} from './dialogState'
import {PasteLinkButton} from './Empty'
import {setLibFilter, setLibView} from './libNav'
import {getLibItem, isSong, useLibrary, useLibrarySettings} from './libStore'
import {groupAlbums, groupArtists, sortItems} from './model'
import {SpacesHub} from './SpacesHub'

const NEW_PLAYLIST = 'new'

function coverOf(collection: Collection, byId: ReturnType<typeof useLibrary>['byId']) {
  const own = collection.coverItemId && byId.get(collection.coverItemId)
  if (own && own.hasArt) return [own.id]
  return collection.itemIds
    .map((id) => byId.get(id))
    .filter((item) => item?.hasArt)
    .slice(0, 4)
    .map((item) => item!.id)
}

/** The "All" view of the Library; the page header owns the edit button, so the edit state comes from the page. */
export function LibraryHome({edit}: {edit: PageEdit}) {
  const lib = useLibrary()
  const settings = useLibrarySettings()!
  const all = lib.snapshot.items
  const rows = useMemo(() => {
    const playable = sortItems(
      all.filter((item) => (item.kind === 'video' || item.kind === 'audio' || item.kind === 'image') && !item.missing),
      'added',
    )
    const songs = playable.filter(isSong)
    const albums = groupAlbums(songs)
    return {
      albums,
      artists: groupArtists(songs, albums),
      // a video kept in a music space is a song, so the videos here are the others
      videos: playable.filter((item) => item.kind === 'video' && !isSong(item)),
      photos: playable.filter((item) => item.kind === 'image'),
    }
  }, [all])

  const views: Record<string, SectionView> = {
    spaces: {title: 'Spaces', render: () => <SpacesHub />},
    playlists: {
      title: 'Playlists',
      render: (style) => (
        <Section
          id="playlists"
          title="Playlists"
          kind="square"
          style={style}
          items={[NEW_PLAYLIST, ...lib.playlists]}
          itemKey={(playlist) => (typeof playlist === 'string' ? playlist : playlist.id)}
          onSeeAll={() => setLibFilter('playlists')}
          renderItem={(playlist, index) =>
            typeof playlist === 'string' ? (
              <OpenCard
                state={sectionCell(index)}
                title="New playlist"
                sub="Collect songs from anywhere"
                icon="plus"
                onOpen={() => openDialog({type: 'playlistName'})}
                label="New playlist"
              />
            ) : (
              <OpenCard
                state={sectionCell(index)}
                title={playlist.name}
                sub={`Yours · ${plural(playlist.itemIds.filter((id) => getLibItem(id)).length, 'song')}`}
                art={coverOf(playlist, lib.byId)}
                icon="queue"
                onOpen={() => setLibView({type: 'playlist', id: playlist.id})}
              />
            )
          }
        />
      ),
    },
    albums: {
      title: 'Albums',
      render: (style) => (
        <Section
          id="albums"
          title="Albums"
          kind="square"
          style={style}
          items={rows.albums}
          itemKey={(album) => album.key}
          onSeeAll={() => setLibFilter('albums')}
          renderItem={(album, index) => (
            <OpenCard
              state={sectionCell(index)}
              title={album.title}
              sub={albumSub(album)}
              art={album.artId ? [album.artId] : []}
              onOpen={() => setLibView({type: 'album', key: album.key})}
              label={`${album.title}, ${albumSub(album)}`}
            />
          )}
        />
      ),
    },
    artists: {
      title: 'Artists',
      render: (style) => (
        <Section
          id="artists"
          title="Artists"
          kind="round"
          style={style}
          items={rows.artists}
          itemKey={(artist) => artist.key}
          onSeeAll={() => setLibFilter('artists')}
          renderItem={(artist, index) => (
            <OpenCard
              state={sectionCell(index)}
              kind="round"
              title={artist.name}
              sub={artistSub(artist)}
              art={artist.artId ? [artist.artId] : []}
              icon="user"
              onOpen={() => setLibView({type: 'artist', key: artist.key})}
            />
          )}
        />
      ),
    },
    videos: {
      title: 'Videos',
      render: (style) => <ItemSection id="videos" title="Videos" kind="video" style={style} items={rows.videos} onSeeAll={() => setLibFilter('videos')} />,
    },
    photos: {
      title: 'Photos',
      render: (style) => <ItemSection id="photos" title="Photos" kind="photo" style={style} items={rows.photos} />,
    },
    collections: {
      title: 'Collections',
      render: (style) => (
        <Section
          id="collections"
          title="Collections"
          kind="square"
          style={style}
          items={lib.downloaded}
          itemKey={(collection) => collection.id}
          onSeeAll={() => setLibFilter('collections')}
          renderItem={(collection, index) => (
            <OpenCard
              state={sectionCell(index)}
              title={collection.name}
              sub={`${collectionKindWords(collection.kind)} · ${plural(collection.itemIds.length, 'item')}`}
              art={coverOf(collection, lib.byId)}
              icon="queue"
              onOpen={() => setLibView({type: 'collection', id: collection.id})}
            />
          )}
        />
      ),
    },
  }

  return (
    <>
      <PageSections edit={edit} views={views} />
      {!all.length && (
        <EmptyState
          icon="library"
          title="Your library starts here"
          actions={
            <>
              <PasteLinkButton />
              <Button icon="plus" onClick={() => openDialog({type: 'import', space: settings.routes.music})}>
                Add files
              </Button>
            </>
          }
        >
          Everything Keepfin saves lands in a space: Music for songs, Videos for films, Downloads for the rest. Paste a link to save something, or add files you
          already have.
        </EmptyState>
      )}
    </>
  )
}
