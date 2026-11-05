// The Library home ("Your library"): spaces as a light row of links, Continue watching as wide 16:9 cards with progress and a play
// button, then Recently added and Recently played, each split into a video shelf (16:9) and a music shelf (squares) so a shelf
// never mixes shapes, then playlists and collections.
import {useMemo, type MouseEvent, type ReactNode} from 'react'
import {collectionKindWords, type Collection, type LibraryItem} from '../../shared/library'
import {formatDuration, plural} from '../format'
import {timeLeft} from '../../shared/core/playback'
import {setLibFilter, setLibView} from './libNav'
import {Icon} from '../ui/Icon'
import {Button, EmptyState, IconButton} from '../ui/controls'
import {removeFromContinue} from '../player/play'
import {Art, ProgressTick} from './Art'
import {CollectionContext, ItemCard, OpenCard, useCollectionApi} from './cells'
import {useCollectionController} from './controller'
import {openDialog} from './dialogState'
import {PasteLinkButton} from './Empty'
import {getLibItem, isSong, useLibItem, useLibrary, useLibrarySettings} from './libStore'
import {continueWatching, progressFraction, sortItems} from './model'
import {MusicVideoShelf} from './musicVideos/MusicVideoShelf'
import {useMusicVideoRows} from './musicVideos/rows'
import {SpacesHub} from './SpacesHub'

const SHELF = 14

/** "4 min left", or "0:11 left" under a minute, from a resume position; never claims more than the file's own duration and position. */
function timeLeftLabel(item: LibraryItem) {
  if (!item.duration || item.position === undefined) return ''
  return timeLeft(Math.max(0, item.duration - item.position))
}

export function resolutionWord(item: LibraryItem) {
  if (!item.height) return ''
  return item.height >= 4320 ? '8K' : item.height >= 2160 ? '4K' : `${item.height}p`
}

function ResumeCard({id, index}: {id: string; index: number}) {
  const item = useLibItem(id)
  const api = useCollectionApi()
  if (!item) return null
  const left = timeLeftLabel(item)
  const details = [resolutionWord(item), item.year ? String(item.year) : '', formatDuration(item.duration)].filter(Boolean)
  return (
    <div className="cw-card">
      <button
        type="button"
        className="cw-main"
        data-focus
        tabIndex={index === 0 ? 0 : -1}
        aria-label={`Continue ${item.title}${left ? `, ${left}` : ''}`}
        onClick={(event: MouseEvent) => api.activate(id, index, event)}
        onContextMenu={(event) => {
          event.preventDefault()
          api.menuAt(id, {x: event.clientX, y: event.clientY})
        }}
      >
        <span className="cw-art">
          <Art id={item.id} hasArt={item.hasArt} kind={item.kind} label={item.title} size={40} space={item.space} />
          <span className="cw-shade" aria-hidden="true" />
          <span className="cw-play" aria-hidden="true">
            <Icon name="play" size={20} />
          </span>
          {left && <span className="cw-left tabular">{left}</span>}
          {progressFraction(item) > 0 && <ProgressTick item={item} />}
        </span>
        <span className="cw-title" title={item.title}>
          {item.title}
        </span>
        <span className="cw-sub">{details.join(' · ')}</span>
      </button>
      <ContinueRemove item={item} />
    </div>
  )
}

/** The ✕ on a Continue watching card (beside its main button, not inside it). */
export const ContinueRemove = ({item}: {item: LibraryItem}) => (
  <IconButton icon="close" size="sm" className="cw-remove" label={`Remove ${item.title} from Continue watching`} onClick={() => removeFromContinue(item)} />
)

function ContinueWatching({items}: {items: LibraryItem[]}) {
  const shown = items.slice(0, 6)
  const controller = useCollectionController(shown)
  if (!shown.length) return null
  return (
    <section className="home-section lib-section" aria-label="Continue watching">
      <header className="shelf-head">
        <h3 className="shelf-title">Continue watching</h3>
      </header>
      <CollectionContext.Provider value={controller.api}>
        <div className="cw-grid" role="list" aria-label="Continue watching">
          {shown.map((item, index) => (
            <div key={item.id} role="listitem" className="cw-item">
              <ResumeCard id={item.id} index={index} />
            </div>
          ))}
        </div>
        {controller.menuNode}
      </CollectionContext.Provider>
    </section>
  )
}

type Shape = 'video' | 'square'

/** One shelf of one shape: video cards are 16:9, music and photo cards are squares. */
function ShapeShelf({label, shape, items}: {label: string; shape: Shape; items: LibraryItem[]}) {
  const shown = items.slice(0, SHELF)
  const controller = useCollectionController(shown)
  if (!shown.length) return null
  return (
    <div className="shape-shelf">
      <h4 className="shelf-subtitle">{label}</h4>
      <CollectionContext.Provider value={controller.api}>
        <div className={`shelf shelf-posters shelf-${shape}`} data-shape={shape} role="list" aria-label={label}>
          {shown.map((item, index) => (
            <div key={item.id} role="listitem" className="shelf-item">
              <ItemCard id={item.id} state={{index, focused: false, tabIndex: index === 0 ? 0 : -1}} />
            </div>
          ))}
        </div>
        {controller.menuNode}
      </CollectionContext.Provider>
    </div>
  )
}

function Group({title, children}: {title: string; children: ReactNode}) {
  return (
    <section className="home-section lib-section" aria-label={title}>
      <header className="shelf-head">
        <h3 className="shelf-title">{title}</h3>
      </header>
      {children}
    </section>
  )
}

function coverOf(collection: Collection, byId: ReturnType<typeof useLibrary>['byId']) {
  const own = collection.coverItemId && byId.get(collection.coverItemId)
  if (own && own.hasArt) return [own.id]
  return collection.itemIds
    .map((id) => byId.get(id))
    .filter((item) => item?.hasArt)
    .slice(0, 4)
    .map((item) => item!.id)
}

function PlaylistShelf({title, filter, children}: {title: string; filter: 'playlists' | 'collections'; children: ReactNode}) {
  return (
    <section className="home-section lib-section" aria-label={title}>
      <header className="shelf-head">
        <h3 className="shelf-title">{title}</h3>
        <button type="button" className="link-btn" onClick={() => setLibFilter(filter)} aria-label={`See all ${title}`}>
          See all
        </button>
      </header>
      <div className="shelf shelf-open shelf-square" data-shape="square" role="list" aria-label={title}>
        {children}
      </div>
    </section>
  )
}

export function LibraryHome() {
  const lib = useLibrary()
  const settings = useLibrarySettings()!
  const all = lib.snapshot.items
  const rows = useMemo(() => {
    const playable = all.filter((item) => (item.kind === 'video' || item.kind === 'audio' || item.kind === 'image') && !item.missing)
    const byAdded = sortItems(playable, 'added')
    const played = playable.filter((item) => item.lastPlayedAt).sort((a, b) => (b.lastPlayedAt ?? 0) - (a.lastPlayedAt ?? 0))
    const of = (list: LibraryItem[], kind: LibraryItem['kind']) => list.filter((item) => item.kind === kind)
    // music rows hold the songs (a video kept in a music space among them); the video rows the other videos
    const videos = (list: LibraryItem[]) => list.filter((item) => item.kind === 'video' && !isSong(item))
    return {
      resume: continueWatching(playable),
      addedVideos: videos(byAdded),
      addedMusic: byAdded.filter(isSong),
      addedPhotos: of(byAdded, 'image'),
      playedVideos: videos(played),
      playedMusic: played.filter(isSong),
    }
  }, [all])
  const musicVideos = useMusicVideoRows()
  const state = {index: 0, focused: false, tabIndex: 0 as const}
  const playlists = lib.playlists.slice(0, SHELF)
  const collections = useMemo(() => lib.downloaded.slice(0, SHELF), [lib.downloaded])
  const anyAdded = rows.addedVideos.length + rows.addedMusic.length + rows.addedPhotos.length > 0
  const anyPlayed = rows.playedVideos.length + rows.playedMusic.length > 0
  return (
    <>
      <SpacesHub />
      <ContinueWatching items={rows.resume} />
      <MusicVideoShelf rows={musicVideos} onSeeAll={() => setLibView({type: 'musicVideos'})} />
      {anyAdded && (
        <Group title="Recently added">
          <ShapeShelf label="Recently added videos" shape="video" items={rows.addedVideos} />
          <ShapeShelf label="Recently added music" shape="square" items={rows.addedMusic} />
          <ShapeShelf label="Recently added photos" shape="square" items={rows.addedPhotos} />
        </Group>
      )}
      {anyPlayed && (
        <Group title="Recently played">
          <ShapeShelf label="Recently played videos" shape="video" items={rows.playedVideos} />
          <ShapeShelf label="Recently played music" shape="square" items={rows.playedMusic} />
        </Group>
      )}
      <PlaylistShelf title="Playlists" filter="playlists">
        <div role="listitem" className="shelf-item">
          <OpenCard state={state} title="New playlist" sub="Collect songs from anywhere" icon="plus" onOpen={() => openDialog({type: 'playlistName'})} label="New playlist" />
        </div>
        {playlists.map((playlist) => (
          <div key={playlist.id} role="listitem" className="shelf-item">
            <OpenCard
              state={state}
              title={playlist.name}
              sub={`Yours · ${plural(playlist.itemIds.filter((id) => getLibItem(id)).length, 'song')}`}
              art={coverOf(playlist, lib.byId)}
              icon="queue"
              onOpen={() => setLibView({type: 'playlist', id: playlist.id})}
            />
          </div>
        ))}
      </PlaylistShelf>
      {collections.length > 0 && (
        <PlaylistShelf title="Collections" filter="collections">
          {collections.map((collection) => (
            <div key={collection.id} role="listitem" className="shelf-item">
              <OpenCard
                state={state}
                title={collection.name}
                sub={`${collectionKindWords(collection.kind)} · ${plural(collection.itemIds.length, 'item')}`}
                art={coverOf(collection, lib.byId)}
                icon="queue"
                onOpen={() => setLibView({type: 'collection', id: collection.id})}
              />
            </div>
          ))}
        </PlaylistShelf>
      )}
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
