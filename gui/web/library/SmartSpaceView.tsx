// A smart space: what its rules gather right now, playable as a whole. It stores nothing.
import {useMemo, useState} from 'react'
import {describeSmartRules, isKeptSpace, type LibraryItem, type Space} from '../../shared/library'
import {plural} from '../format'
import {playAll} from '../player/play'
import {PageHeader} from '../shell/PageHeader'
import {PageToolbar} from '../shell/PageToolbar'
import {Button, EmptyState} from '../ui/controls'
import {Menu} from '../ui/Menu'
import {CollectionContext, FileRow} from './cells'
import {useCollectionController} from './controller'
import {sizes, useDensity} from './density'
import {openDialog} from './dialogState'
import {setLibView} from './libNav'
import {useLibrary, useLibrarySettings} from './libStore'
import {matchesQuery, queryWords, sortItems} from './model'
import {NoMatches} from './Empty'
import {Skeleton} from './Skeleton'
import {SongList} from './SongList'
import {useSmartMatches} from './spacesModel'
import {SpaceMark} from './SpacesHub'
import {SelectionBar, SearchBox} from './Toolbar'
import {VirtualCollection} from './VirtualCollection'

function MatchList({space, items, selecting, onSelectingChange}: {space: Space; items: LibraryItem[]; selecting: boolean; onSelectingChange: (on: boolean) => void}) {
  const [density] = useDensity(space.id)
  const controller = useCollectionController(items, {selecting, onSelectingChange})
  const header = <SelectionBar selection={controller.selection} total={items.length} />
  return (
    <CollectionContext.Provider value={controller.api}>
      <VirtualCollection
        count={items.length}
        mode="list"
        rowHeight={sizes[density].fileRow}
        density={density}
        label={`${space.name} matches`}
        header={header}
        onSelectAll={() => controller.selection.selectAll()}
        keyOf={(index) => items[index].id}
        onContextKey={(index, rect) => controller.openMenuFor(index, rect)}
        renderCell={(state) => <FileRow id={items[state.index].id} state={state} />}
      />
      {controller.menuNode}
    </CollectionContext.Provider>
  )
}

export function SmartSpaceView({space}: {space: Space}) {
  const lib = useLibrary()
  const settings = useLibrarySettings()
  const matches = useSmartMatches(space)
  const [query, setQuery] = useState('')
  const [selecting, setSelecting] = useState(false)
  // Quick filters: only the kinds the space really gathers, shown when there are two or more (as in folder spaces)
  const [kind, setKind] = useState<'all' | LibraryItem['kind']>('all')
  const kinds = useMemo(() => {
    const present = new Set(matches.map((item) => item.kind))
    return (['video', 'audio', 'image', 'other'] as const).filter((entry) => present.has(entry))
  }, [matches])
  const shownKind = kinds.length > 1 && kinds.includes(kind as never) ? kind : 'all'
  const words = useMemo(() => queryWords(query), [query])
  const items = useMemo(
    () => sortItems(matches.filter((item) => (shownKind === 'all' || item.kind === shownKind) && matchesQuery(item, words)), 'added'),
    [matches, words, shownKind],
  )
  const audioOnly = items.length > 0 && items.every((item) => item.kind === 'audio')
  const summary = useMemo(() => {
    if (!space.rules) return 'No rules yet'
    return describeSmartRules(space.rules, (field, value) => {
      if (field === 'collection') return lib.snapshot.collections.find((c) => c.id === value)?.name
      if (field === 'space') return settings?.spaces.find((entry) => entry.id === value)?.name
      return undefined
    })
  }, [space.rules, lib, settings])

  const header = (
    <PageHeader
      title={space.name}
      crumbs={[{label: 'Library', onSelect: () => setLibView({type: 'home'})}]}
      caption={
        <>
          <span className="caption-mark">
            <SpaceMark space={space} size={11} />
          </span>
          <span title={summary}>
            Smart · {plural(matches.length, 'match', 'matches')} · {summary}
          </span>
        </>
      }
      actions={
        <>
          <Button size="sm" variant="primary" icon="play" className="play-all" aria-label="Play all" title="Play all" disabled={!items.length} onClick={() => playAll(items)}>
            Play all
          </Button>
          <Button size="sm" icon="shuffle" aria-label="Shuffle" title="Shuffle" disabled={!items.length} onClick={() => playAll(items, true)}>
            Shuffle
          </Button>
          <Menu
            label={`${space.name} options`}
            items={[
              {label: 'Edit rules…', icon: 'edit', onSelect: () => openDialog({type: 'space', space, section: 'rules'})},
              {label: 'Edit space…', icon: 'edit', onSelect: () => openDialog({type: 'space', space})},
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
        <Skeleton rows={8} />
      </div>
    )
  return (
    <div className="space-view">
      {header}
      <PageToolbar
        label="Library toolbar"
        left={
          kinds.length > 1 ? (
            <div className="chips" role="group" aria-label="Show">
              {(['all', ...kinds] as const).map((entry) => (
                <button key={entry} type="button" className={`chip${shownKind === entry ? ' on' : ''}`} aria-pressed={shownKind === entry} onClick={() => setKind(entry)}>
                  {entry === 'all' ? 'All' : entry === 'video' ? 'Videos' : entry === 'audio' ? 'Audio' : entry === 'image' ? 'Images' : 'Other'}
                </button>
              ))}
            </div>
          ) : undefined
        }
        right={
          <>
            <SearchBox value={query} onChange={setQuery} placeholder={`Filter ${space.name}`} label={`Filter ${space.name}`} />
            <Button size="sm" variant="secondary" icon="check" aria-pressed={selecting} onClick={() => setSelecting(!selecting)}>
              {selecting ? 'Done' : 'Select'}
            </Button>
            <Button size="sm" icon="plus" onClick={() => openDialog({type: 'space', space, section: 'rules'})}>
              Add
            </Button>
          </>
        }
      />
      <div className="space-body">
        {!matches.length ? (
          space.id === 'favorites' ? (
            <EmptyState icon="heart" title="No favorites yet">
              Press the heart on any song or video to keep it here.
            </EmptyState>
          ) : (
            <EmptyState icon="smart" title="Nothing matches yet" actions={<Button icon="edit" onClick={() => openDialog({type: 'space', space, section: 'rules'})}>Edit rules</Button>}>
              This space gathers items by its rules and fills itself as your library grows.
            </EmptyState>
          )
        ) : !items.length ? (
          <NoMatches
            query={query}
            onClear={() => {
              setQuery('')
              setKind('all')
            }}
          />
        ) : audioOnly ? (
          <SongList items={items} label="Songs" selecting={selecting} onSelectingChange={setSelecting} noActions />
        ) : (
          <MatchList space={space} items={items} selecting={selecting} onSelectingChange={setSelecting} />
        )}
      </div>
    </div>
  )
}
