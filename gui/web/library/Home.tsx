// Home (full mode): the sections of the saved layout in order. Videos, music and photos get equal weight; every section hides
// when it has nothing to show.
import {useMemo, useState} from 'react'
import {homeMixes, type Mix} from '../../shared/core/home'
import {musicVideoWords, type LibraryItem} from '../../shared/library'
import {ItemSection} from '../media/ItemSection'
import {SectionFrame} from '../media/Section'
import {EditBar, EditPageButton, PageSections, usePageEdit, type SectionView} from '../media/PageEdit'
import {useCardSizes} from '../media/useCardSizes'
import {navigate} from '../nav'
import {usePreference} from '../prefs'
import {isLiveEntry, useIndex, useJob} from '../store'
import {Button, EmptyState, InlineAlert, ProgressBar} from '../ui/controls'
import {openDialog} from './dialogState'
import {setLibView} from './libNav'
import {loadLibrary, useLibrary, useLibrarySettings} from './libStore'
import {continueWatching, sortItems} from './model'
import {PasteLinkButton} from './Empty'
import {PageHeader} from '../shell/PageHeader'
import {PageToolbar} from '../shell/PageToolbar'
import {Skeleton} from './Skeleton'
import {MusicVideoSection} from './musicVideos/MusicVideoSection'
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

const stateWords: Record<string, string> = {
  queued: 'Waiting',
  preparing: 'Preparing',
  processing: 'Finishing',
  paused: 'Paused',
  downloading: 'Downloading',
}
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
      <ProgressBar
        value={percent}
        label={`Progress of ${job.title}`}
        mode={job.status === 'downloading' ? 'determinate' : 'indeterminate'}
        tone={job.status === 'paused' ? 'paused' : undefined}
      />
    </li>
  )
}

function DownloadingNow() {
  const live = useIndex().filter(isLiveEntry)
  if (!live.length) return null
  return (
    <SectionFrame
      id="downloading"
      title="Downloading now"
      tools={
        <button type="button" className="link-btn" onClick={() => navigate('Downloads')}>
          All downloads
        </button>
      }
    >
      <ul className="dl-now" aria-label="Active downloads">
        {live.slice(0, 3).map((entry) => (
          <LiveJob key={entry.id} id={entry.id} />
        ))}
      </ul>
    </SectionFrame>
  )
}

const greeting = () => {
  const hour = new Date().getHours()
  return hour < 5 ? 'Late night' : hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'
}

export function Home() {
  const lib = useLibrary()
  const settings = useLibrarySettings()!
  const edit = usePageEdit('home')
  const cardSizes = useCardSizes()
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
    const recentPlayed = sortItems(
      playable.filter((item) => item.lastPlayedAt),
      'added',
    ).sort((a, b) => (b.lastPlayedAt ?? 0) - (a.lastPlayedAt ?? 0))
    for (const item of [...recentPlayed, ...sortItems(playable, 'added')])
      if (wanted(item) && !seen.has(item.id) && quick.length < 8) {
        seen.add(item.id)
        quick.push(item)
      }
    const music = filter === 'all' || filter === 'audio'
    const songs = music ? playable.filter((item) => item.kind === 'audio') : []
    const byId = new Map(songs.map((item) => [item.id, item]))
    const mixes = homeMixes(
      songs.map((item) => ({
        id: item.id,
        artist: item.artist || item.albumArtist,
        plays: item.plays,
        lastPlayedAt: item.lastPlayedAt,
        addedAt: item.addedAt,
        favorite: item.favorite,
      })),
    )
    return {
      playable,
      songs,
      byId,
      mixes,
      quick,
      resume: filter === 'all' || filter === 'video' ? continueWatching(all) : [],
      videos:
        filter === 'all' || filter === 'video'
          ? sortItems(
              playable.filter((item) => item.kind === 'video'),
              'added',
            )
          : [],
      music: sortItems(songs, 'added'),
      photos:
        filter === 'all' || filter === 'image'
          ? sortItems(
              playable.filter((item) => item.kind === 'image'),
              'added',
            )
          : [],
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
    const leave = () => setMixView(null)
    const crumbs = [{label: 'Home', onSelect: leave}]
    const songsOf = (mix: Mix) => mix.ids.map((id) => rows.byId.get(id)).filter((item): item is LibraryItem => !!item)
    if (mixView.type === 'all')
      return (
        <div className="screen home" ref={cardSizes}>
          <PageHeader title="Made from your listening" caption="Mixes update as you play" crumbs={crumbs} onBack={leave} />
          {rows.mixes.map((mix) => (
            <ItemSection key={mix.id} id={mix.id} title={mix.title} blurb={mix.blurb} kind="square" items={songsOf(mix)} />
          ))}
        </div>
      )
    const mix = rows.mixes.find((entry) => entry.id === mixView.id)
    if (mix) {
      const songs = songsOf(mix)
      // The song list scrolls by itself, so it needs a fixed-height parent like the Library's.
      return (
        <div className="screen lib-shell">
          <div className="space-view">
            <PageHeader title={mix.title} caption={mix.blurb} crumbs={crumbs} onBack={leave} />
            <div className="space-body">
              {songs.length ? (
                <SongList selecting={selecting} onSelectingChange={setSelecting} items={songs} label={mix.title} />
              ) : (
                <EmptyState icon="music" title="Nothing in this mix yet">
                  It fills itself as you play and keep songs.
                </EmptyState>
              )}
            </div>
          </div>
        </div>
      )
    }
  }
  const empty = !rows.playable.length
  const music = !empty && (filter === 'all' || filter === 'audio')
  const videoRows = filter === 'all' || filter === 'video' ? musicVideos : []
  const nothingForFilter =
    !empty && !rows.quick.length && !rows.resume.length && !rows.mixes.length && !rows.videos.length && !rows.photos.length && !videoRows.length
  const views: Record<string, SectionView> = {
    downloading: {title: 'Downloading now', render: () => <DownloadingNow />},
    'jump-back-in': {
      title: 'Jump back in',
      render: (style) => <ItemSection id="jump-back-in" title="Jump back in" kind="tile" style={style} items={rows.quick} />,
    },
    continue: {
      title: 'Continue watching',
      render: (style) => <ItemSection id="continue" title="Continue watching" kind="video" style={style} items={rows.resume} resume />,
    },
    mixes: {
      title: 'Made from your listening',
      render: (style) =>
        music && (
          <MixSection
            mixes={rows.mixes}
            style={style}
            hasSongs={rows.songs.length > 0}
            onOpen={(mix) => setMixView({type: 'mix', id: mix.id})}
            onSeeAll={() => setMixView({type: 'all'})}
          />
        ),
    },
    'new-releases': {
      title: 'New from artists you follow',
      render: () => music && <NewReleasesCard />,
    },
    'added-videos': {
      title: 'New videos',
      render: (style) => <ItemSection id="added-videos" title="New videos" kind="video" style={style} items={rows.videos} />,
    },
    'added-music': {
      title: 'New music',
      render: (style) => <ItemSection id="added-music" title="New music" kind="square" style={style} items={rows.music} />,
    },
    'music-videos': {
      title: musicVideoWords.shelf,
      render: (style) => <MusicVideoSection rows={videoRows} style={style} onSeeAll={() => (setLibView({type: 'musicVideos'}), navigate('Library'))} />,
    },
    photos: {
      title: 'New photos',
      render: (style) => <ItemSection id="photos" title="New photos" kind="photo" style={style} items={rows.photos} />,
    },
  }
  return (
    <div className="screen home" ref={cardSizes}>
      <PageHeader
        title="Home"
        caption={`${greeting()}. Paste a link in the search bar to keep something, or find what you already have.`}
        actions={!empty && <EditPageButton edit={edit} name="Home" />}
      />
      {!empty && (
        <PageToolbar
          label="Home toolbar"
          left={
            <div className="chips" role="group" aria-label="Show on Home">
              {filters.map((entry) => (
                <button
                  key={entry.id}
                  type="button"
                  className={`chip${filter === entry.id ? ' on' : ''}`}
                  aria-pressed={filter === entry.id}
                  onClick={() => setFilter(entry.id)}
                >
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
      <PageSections edit={edit} views={views} />
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
          Videos, music and photos you save land in spaces, each in its own folder. Paste a link in the search bar to save something, or add files you already
          have.
        </EmptyState>
      ) : (
        nothingForFilter && (
          <p className="muted pane-note">
            Nothing in {filters.find((entry) => entry.id === filter)?.label} yet.{' '}
            <button type="button" className="link-btn" onClick={() => setFilter('all')}>
              Show everything
            </button>
          </p>
        )
      )}
      <EditBar edit={edit} />
    </div>
  )
}
