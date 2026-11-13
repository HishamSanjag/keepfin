// The Library page: everything you keep, reachable from here. A filter row (All · Spaces · Playlists · Collections ·
// Albums · Artists · Videos) picks the kind; "All" is the Library home (LibraryHome.tsx): the sections of the saved layout (spaces,
// playlists, albums, artists, videos, photos, collections). Every entry opens its own page whose breadcrumb ("Library › Albums") returns to the same filter.
import {useEffect} from 'react'
import {EditBar, EditPageButton, usePageEdit, type PageEdit} from '../media/PageEdit'
import {useCardSizes} from '../media/useCardSizes'
import {navigate} from '../nav'
import {PageHeader} from '../shell/PageHeader'
import {PageToolbar} from '../shell/PageToolbar'
import {Button, InlineAlert} from '../ui/controls'
import {DeviceMusicCard} from './DeviceMusicCard'
import {KindPage, type Kind} from './KindPage'
import {LibraryHome} from './LibraryHome'
import {libFilters, setLibFilter, setSpacesEditing, useLibFilter, type LibFilter} from './libNav'
import {loadLibrary, useLibrary} from './libStore'
import {Skeleton} from './Skeleton'
import {AllSpaces} from './SpacesHub'

/** The kinds to browse; it is the left side of the toolbar on every Library page. */
function KindChips() {
  const filter = useLibFilter()
  return (
    <div className="chips lib-filter-row" role="group" aria-label="Show in Library">
      {libFilters.map((entry) => (
        <button
          key={entry.id}
          type="button"
          className={`chip chip-lg${filter === entry.id ? ' on' : ''}`}
          aria-pressed={filter === entry.id}
          onClick={() => setLibFilter(entry.id)}
        >
          {entry.label}
        </button>
      ))}
    </div>
  )
}

function LibraryHeader({edit}: {edit?: PageEdit}) {
  return (
    <PageHeader
      title="Library"
      caption="Your media, close at hand."
      actions={
        <>
          {edit && <EditPageButton edit={edit} name="Library" />}
          <Button size="sm" variant="ghost" icon="info" onClick={() => navigate('Settings', 'guide')}>
            How to use Keepfin
          </Button>
        </>
      }
    />
  )
}

const kindPages: Partial<Record<LibFilter, Kind>> = {
  playlists: 'playlists',
  collections: 'collections',
  albums: 'albums',
  artists: 'artists',
}

export function Overview() {
  const lib = useLibrary()
  const filter = useLibFilter()
  const cardSizes = useCardSizes()
  const edit = usePageEdit('library')
  const {setEditing} = edit
  // another view of the Library is another page: editing ends
  useEffect(() => setEditing(false), [filter, setEditing])
  // one Edit for the whole page: editing the Library also edits the spaces row
  useEffect(() => {
    setSpacesEditing(edit.editing)
    return () => setSpacesEditing(false)
  }, [edit.editing])
  if (!lib.loaded)
    return (
      <div className="home">
        <LibraryHeader />
        <Skeleton rows={7} />
      </div>
    )
  if (lib.error)
    return (
      <div className="home">
        <LibraryHeader />
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
  const problems = lib.snapshot.problems.map((problem, index) => (
    <InlineAlert key={index} tone="warning" title="Couldn’t read a folder">
      {problem.message}
    </InlineAlert>
  ))
  const kind = kindPages[filter]
  if (kind)
    return (
      <div className="space-view library-overview">
        <LibraryHeader />
        {problems}
        <KindPage key={kind} kind={kind} lead={<KindChips />} />
      </div>
    )
  if (filter === 'spaces' || filter === 'videos')
    return (
      <div className="space-view library-overview">
        <LibraryHeader />
        {problems}
        <AllSpaces embedded lead={<KindChips />} only={filter === 'videos' ? (space) => space.type !== 'smart' && space.kind !== 'music' : undefined} />
      </div>
    )
  return (
    <div className="home library-overview library-home" ref={cardSizes}>
      <LibraryHeader edit={edit} />
      <PageToolbar label="Library toolbar" left={<KindChips />} />
      {problems}
      <DeviceMusicCard />
      <LibraryHome edit={edit} />
      <EditBar edit={edit} />
    </div>
  )
}
