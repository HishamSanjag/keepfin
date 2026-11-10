// Home (full mode): the paste/search box first, then what to pick up next. Videos, music and photos get equal
// weight; every row hides when it has nothing to show.
import {useMemo, useState, type MouseEvent} from 'react'
import {homeMixes, type Mix} from '../../shared/core/home'
import type {LibraryItem} from '../../shared/library'
import {navigate} from '../nav'
import {usePreference} from '../prefs'
import {isLiveEntry, useIndex, useJob} from '../store'
import {Button, EmptyState, InlineAlert, ProgressBar} from '../ui/controls'
import {Art, ProgressTick} from './Art'
import {CollectionContext, ItemCard, useCollectionApi} from './cells'
import {useCollectionController} from './controller'
import {openDialog} from './dialogState'
import {setLibView, type LibView} from './libNav'
import {loadLibrary, useLibItem, useLibrary, useLibrarySettings} from './libStore'
import {continueWatching, progressFraction, sortItems} from './model'
import {PasteLinkButton} from './Empty'
import {PageHeader} from '../shell/PageHeader'
import {PageToolbar} from '../shell/PageToolbar'
import {Skeleton} from './Skeleton'
import {ContinueRemove} from './LibraryHome'
import {MusicVideoShelf} from './musicVideos/MusicVideoShelf'
import {useMusicVideoRows} from './musicVideos/rows'
import {NewReleasesCard} from './Follow'
import {MixSection} from './HomeMixes'
import {SongList} from './SongList'

type Filter = 'all' | 'video' | 'audio' | 'image'
const filters: {id: Filter; label: string}[] = [
  {id: 'all', label: 'All'},
  {id: 'video', label: 'Videos'},
  {id: 'audio', label: 'Music'},
  {id: 'image', label: 'Photos'},
]
const filterIds = filters.map((entry) => entry.id)

function Shelf({title, blurb, items, seeAll, label, removable}: {title: string; blurb?: string; items: LibraryItem[]; seeAll?: LibView; label?: string; removable?: boolean}) {
  const shown = items.slice(0, 14)
  const controller = useCollectionController(shown)
  if (!shown.length) return null
  return (
    <section className="home-section" aria-label={label || title}>
      <header className="shelf-head">
        <div className="shelf-heading">
          <h3 className="shelf-title">{title}</h3>
          {blurb && <p className="muted small">{blurb}</p>}
        </div>
        {seeAll && (
          <button type="button" className="link-btn" onClick={() => (setLibView(seeAll), navigate('Library'))} aria-label={`See all ${title}`}>
            See all
          </button>
        )}
      </header>
      <CollectionContext.Provider value={controller.api}>
        <div className="shelf shelf-posters" role="list" aria-label={title}>
          {shown.map((item, index) => (
            <div key={item.id} role="listitem" className="shelf-item">
              <ItemCard id={item.id} state={{index, focused: false, tabIndex: index === 0 ? 0 : -1}} />
              {removable && <ContinueRemove item={item} />}
            </div>
          ))}
        </div>
        {controller.menuNode}
      </CollectionContext.Provider>
    </section>
  )
}

const kindWord: Record<LibraryItem['kind'], string> = {video: 'Video', audio: 'Song', image: 'Photo', other: 'File'}

function QuickTile({id, index}: {id: string; index: number}) {
  const item = useLibItem(id)
  const api = useCollectionApi()
  if (!item) return null
  return (
    <button
      type="button"
      className="quick-tile"
      aria-label={`${item.title}, ${kindWord[item.kind]}`}
      onClick={(event: MouseEvent) => api.activate(id, index, event)}
      onContextMenu={(event) => {
        event.preventDefault()
        api.menuAt(id, {x: event.clientX, y: event.clientY})
      }}
    >
      <span className="quick-art">
        <Art id={item.id} hasArt={item.hasArt} kind={item.kind} size={18} />
        {progressFraction(item) > 0 && <ProgressTick item={item} />}
      </span>
      <span className="quick-text">
        <span className="quick-title">{item.title}</span>
        <span className="quick-sub">{item.artist || kindWord[item.kind]}</span>
      </span>
    </button>
  )
}

function QuickGrid({items}: {items: LibraryItem[]}) {
  const controller = useCollectionController(items)
  if (!items.length) return null
  return (
    <section className="home-section" aria-label="Jump back in">
      <CollectionContext.Provider value={controller.api}>
        <div className="quick-grid" role="list" aria-label="Jump back in">
          {items.map((item, index) => (
            <div key={item.id} role="listitem">
              <QuickTile id={item.id} index={index} />
            </div>
          ))}
        </div>
        {controller.menuNode}
      </CollectionContext.Provider>
    </section>
  )
}

const stateWords: Record<string, string> = {queued: 'Waiting', preparing: 'Preparing', processing: 'Finishing', paused: 'Paused', downloading: 'Downloading'}
function LiveJob({id}: {id: string}) {
  const job = useJob(id)
  if (!job) return null
  const percent = job.status === 'downloading' ? job.percent || 0 : 0
  return (
    <li className="dl-now-row">
      <span className="dl-now-title" title={job.title}>
        {job.title}
      </span>
      <span className="muted small tabular">{job.status === 'downloading' ? `${Math.round(percent)}%` : stateWords[job.status] || job.status}</span>
      <ProgressBar value={percent} label={`Progress of ${job.title}`} mode={job.status === 'downloading' ? 'determinate' : 'indeterminate'} tone={job.status === 'paused' ? 'paused' : undefined} />
    </li>
  )
}

function DownloadingNow() {
  const live = useIndex().filter(isLiveEntry)
  if (!live.length) return null
  return (
    <section className="home-section" aria-label="Downloading now">
      <header className="shelf-head">
        <h3 className="shelf-title">Downloading now</h3>
        <button type="button" className="link-btn" onClick={() => navigate('Downloads')}>
          All downloads
        </button>
      </header>
      <ul className="dl-now" aria-label="Active downloads">
        {live.slice(0, 3).map((entry) => (
          <LiveJob key={entry.id} id={entry.id} />
        ))}
      </ul>
    </section>
  )
}

const greeting = () => {
  const hour = new Date().getHours()
  return hour < 5 ? 'Late night' : hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'
}

export function Home() {
  const lib = useLibrary()
  const settings = useLibrarySettings()!
  const [filter, setFilter] = usePreference<Filter>('keepfin-home-filter', 'all', (value) => filterIds.includes(value as Filter))
  const all = lib.snapshot.items
  // a song's music video is a video: it shows with the Videos
  const musicVideos = useMusicVideoRows()
  const [selecting, setSelecting] = useState(false)
  const [mixView, setMixView] = useState<{type: 'mix'; id: Mix['id']} | {type: 'all'} | null>(null)
  const rows = useMemo(() => {
    const playable = all.filter((item) => item.kind !== 'other' && !item.missing)
    const wanted = (item: LibraryItem) => filter === 'all' || item.kind === filter
    const seen = new Set<string>()
    const quick: LibraryItem[] = []
    const recentPlayed = sortItems(playable.filter((item) => item.lastPlayedAt), 'added').sort((a, b) => (b.lastPlayedAt ?? 0) - (a.lastPlayedAt ?? 0))
    for (const item of [...recentPlayed, ...sortItems(playable, 'added')])
      if (wanted(item) && !seen.has(item.id) && quick.length < 8) {
        seen.add(item.id)
        quick.push(item)
      }
    const music = filter === 'all' || filter === 'audio'
    const songs = music ? playable.filter((item) => item.kind === 'audio') : []
    const byId = new Map(songs.map((item) => [item.id, item]))
    const mixes = homeMixes(songs.map((item) => ({id: item.id, artist: item.artist || item.albumArtist, plays: item.plays, lastPlayedAt: item.lastPlayedAt, addedAt: item.addedAt, favorite: item.favorite})))
    return {
      playable,
      songs,
      byId,
      mixes,
      quick,
      resume: filter === 'all' || filter === 'video' ? continueWatching(all) : [],
      added: filter === 'audio' ? [] : sortItems(playable.filter((item) => (item.kind === 'video' || item.kind === 'image') && wanted(item)), 'added'),
    }
  }, [all, filter])

  if (!lib.loaded)
    return (
      <div className="screen home">
        <PageHeader title="Home" />
        <Skeleton rows={7} />
      </div>
    )
  if (lib.error)
    return (
      <div className="screen home">
        <PageHeader title="Home" />
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
  if (mixView) {
    const back = (
      <Button onClick={() => setMixView(null)}>
        Back to Home
      </Button>
    )
    if (mixView.type === 'all')
      return (
        <div className="screen home">
          <PageHeader title="Made from your listening" caption="Mixes update as you play" actions={back} />
          {rows.mixes.map((mix) => (
            <Shelf key={mix.id} title={mix.title} blurb={mix.blurb} items={mix.ids.map((id) => rows.byId.get(id)).filter((item): item is LibraryItem => !!item)} />
          ))}
        </div>
      )
    const mix = rows.mixes.find((entry) => entry.id === mixView.id)
    if (mix)
      return (
        <div className="screen home">
          <PageHeader title={mix.title} caption={mix.blurb} actions={back} />
          <SongList selecting={selecting} onSelectingChange={setSelecting} items={mix.ids.map((id) => rows.byId.get(id)).filter((item): item is LibraryItem => !!item)} label={mix.title} />
        </div>
      )
  }
  const empty = !rows.playable.length
  const videoRows = filter === 'video' ? musicVideos : []
  const nothingForFilter = !empty && !rows.quick.length && !rows.resume.length && !rows.mixes.length && !rows.added.length && !videoRows.length
  return (
    <div className="screen home">
      <PageHeader title="Home" caption={`${greeting()}. Paste a link in the search bar to keep something, or find what you already have.`} />
      {!empty && (
        <PageToolbar
          label="Home toolbar"
          left={
            <div className="chips" role="group" aria-label="Show on Home">
              {filters.map((entry) => (
                <button key={entry.id} type="button" className={`chip${filter === entry.id ? ' on' : ''}`} aria-pressed={filter === entry.id} onClick={() => setFilter(entry.id)}>
                  {entry.label}
                </button>
              ))}
            </div>
          }
        />
      )}
      {lib.snapshot.problems.map((problem, index) => (
        <InlineAlert key={index} tone="warning" title="Couldn’t read a folder">
          {problem.message}
        </InlineAlert>
      ))}
      {(filter === 'all' || filter === 'audio') && (
        <>
          <NewReleasesCard />
          {!empty && <MixSection mixes={rows.mixes} hasSongs={rows.songs.length > 0} onOpen={(mix) => setMixView({type: 'mix', id: mix.id})} onSeeAll={() => setMixView({type: 'all'})} />}
        </>
      )}
      {empty ? (
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
          Videos, music and photos you save land in spaces, each in its own folder. Paste a link in the search bar to save something, or add files you already have.
        </EmptyState>
      ) : (
        <>
          <QuickGrid items={rows.quick} />
          <Shelf title="Continue watching" items={rows.resume} removable />
          <DownloadingNow />
          <MusicVideoShelf rows={videoRows} onSeeAll={() => (setLibView({type: 'musicVideos'}), navigate('Library'))} />
          <Shelf title="New videos and photos" items={rows.added} />
          {nothingForFilter && (
            <p className="muted pane-note">
              Nothing in {filters.find((entry) => entry.id === filter)?.label} yet.{' '}
              <button type="button" className="link-btn" onClick={() => setFilter('all')}>
                Show everything
              </button>
            </p>
          )}
        </>
      )}
      {empty && <DownloadingNow />}
    </div>
  )
}

