import {useMemo, useRef, useState, type ReactNode} from 'react'
import type {LibraryItem} from '../../shared/library'
import {plural} from '../format'
import {playAll} from '../player/play'
import {addToQueue} from '../player/playerStore'
import {Button} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {PageToolbar} from '../shell/PageToolbar'
import {Menu, type MenuItem} from '../ui/Menu'
import {CollectionContext, SongRow} from './cells'
import {HeroActions} from './Hero'
import {useCollectionController} from './controller'
import {sizes, type Density} from './density'
import {durationWords, totalDuration} from './model'
import {SelectionBar} from './Toolbar'
import {VirtualCollection} from './VirtualCollection'

type Entry = {type: 'song'; id: string; number?: number | string} | {type: 'disc'; disc: number}

/**
 * A list of songs with Play / Shuffle, selection, and (for user playlists) drag-to-reorder. Albums with several
 * discs get a "Disc N" heading per disc.
 */
export function SongList({
  items,
  label,
  hero,
  showAlbum = true,
  byDisc = false,
  onReorder,
  onRemove,
  selecting,
  onSelectingChange,
  empty,
  extra,
  noActions = false,
  below,
  density = 'compact',
  removedIds,
  menu,
}: {
  items: LibraryItem[]
  label: string
  hero?: ReactNode
  showAlbum?: boolean
  byDisc?: boolean
  /** User playlists: called with the full new id order. */
  onReorder?: (ids: string[]) => void
  onRemove?: (ids: string[]) => void
  selecting: boolean
  onSelectingChange: (on: boolean) => void
  empty?: ReactNode
  /** Entries added to the hero's ⋯ menu after menu (Keep in sync, Download more, Follow). */
  extra?: MenuItem[]
  /** No Play / Shuffle row above the list: the page header owns Play all. */
  noActions?: boolean
  /** Content under the hero and its toolbar (an artist's albums). */
  below?: ReactNode
  density?: Density
  /** Collection items gone from the source: dimmed, with a note. */
  removedIds?: ReadonlySet<string>
  /** The header's ⋯ menu (the collection's own actions). */
  menu?: MenuItem[]
}) {
  const entries = useMemo<Entry[]>(() => {
    if (!byDisc) return items.map((item) => ({type: 'song' as const, id: item.id}))
    const out: Entry[] = []
    let disc = 0
    for (const item of items) {
      if ((item.disc || 1) !== disc) {
        disc = item.disc || 1
        out.push({type: 'disc', disc})
      }
      out.push({type: 'song', id: item.id, number: item.track || undefined})
    }
    return out
  }, [items, byDisc])
  const controller = useCollectionController(items, {selecting, onSelectingChange, onRemoveFromPlaylist: onRemove, removed: removedIds})
  const [dragFrom, setDragFrom] = useState<number | null>(null)
  const [dropAt, setDropAt] = useState<number | null>(null)
  const listRef = useRef<HTMLDivElement>(null)

  const move = (from: number, to: number) => {
    if (!onReorder || from === to || to < 0 || to >= items.length) return
    const ids = items.map((item) => item.id)
    const [moved] = ids.splice(from, 1)
    ids.splice(to, 0, moved)
    onReorder(ids)
  }
  const songIndexOf = (entryIndex: number) => entries.slice(0, entryIndex).filter((entry) => entry.type === 'song').length

  // With a hero the actions sit in its page header (HeroActions): Play, Shuffle and a ⋯ menu that holds the rest. Without one (a space's
  // song list) the page header above already owns Play all, so the list has no action row of its own unless it asks for one.
  const moreItems: MenuItem[] = [...(menu ?? []), ...(extra ?? [])]
  const actionButtons = hero ? (
    <>
      <Button variant="primary" icon="play" className="play-big" disabled={!items.length} onClick={() => playAll(items)}>
        Play
      </Button>
      <Button icon="shuffle" disabled={!items.length} onClick={() => playAll(items, true)}>
        Shuffle
      </Button>
      {moreItems.length > 0 && <Menu label={`More actions for ${label}`} items={moreItems} />}
    </>
  ) : (
    <>
      <Button variant="primary" icon="play" className="play-big" disabled={!items.length} onClick={() => playAll(items)}>
        Play
      </Button>
      <Button icon="shuffle" disabled={!items.length} onClick={() => playAll(items, true)}>
        Shuffle
      </Button>
      <Button variant="ghost" icon="queue" disabled={!items.length} onClick={() => addToQueue(items.map((item) => item.id))}>
        Add to queue
      </Button>
      {menu && menu.length > 0 && <Menu label={`More actions for ${label}`} items={menu} />}
    </>
  )
  const header = (
    <>
      {hero}
      {hero && (
        <PageToolbar
          label="Library toolbar"
          right={
            <Button size="sm" variant="secondary" icon="check" aria-pressed={selecting} onClick={() => controller.selection.setSelecting(!selecting)}>
              {selecting ? 'Done' : 'Select'}
            </Button>
          }
        />
      )}
      {below}
      {!hero && !noActions && (
        <div className="list-actions">
          {actionButtons}
          <span className="list-summary tabular">
            {plural(items.length, 'song')}
            {totalDuration(items) > 0 && ` · ${durationWords(totalDuration(items))}`}
          </span>
        </div>
      )}
      <SelectionBar selection={controller.selection} total={items.length} onRemoveFromPlaylist={onRemove} />
      {items.length > 0 && (
        <div className={`song-head${controller.api.selecting ? ' selecting' : ''}`} aria-hidden="true">
          <span className="sh-lead">#</span>
          <span className="sh-title">Title</span>
          {showAlbum && <span className="sh-album">Album</span>}
          <span className="sh-time">
            <Icon name="clock" size={13} />
          </span>
        </div>
      )}
    </>
  )
  if (!items.length) return <div className="lib-scroll-pane">{hero}{empty}</div>

  return (
    <CollectionContext.Provider value={controller.api}>
      <HeroActions.Provider value={hero ? actionButtons : null}>
      <div className="list-host" ref={listRef} onKeyDownCapture={(event) => {
        if (!onReorder || !event.altKey || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown')) return
        const at = (event.target as HTMLElement).closest('[data-idx]')?.getAttribute('data-idx')
        if (at == null) return
        event.preventDefault()
        event.stopPropagation()
        const from = songIndexOf(Number(at))
        const to = from + (event.key === 'ArrowUp' ? -1 : 1)
        move(from, to)
        // Keep focus on the moved song after the list updates.
        requestAnimationFrame(() => listRef.current?.querySelector<HTMLElement>(`[data-idx="${Number(at) + (to - from)}"] [data-focus]`)?.focus())
      }}>
        <VirtualCollection
          count={entries.length}
          mode="list"
          rowHeight={sizes[density].songRow}
          density={density}
          onSelectAll={items.length ? () => controller.selection.selectAll() : undefined}
          label={label}
          header={header}
          keyOf={(index) => {
            const entry = entries[index]
            return entry.type === 'disc' ? `disc-${entry.disc}` : entry.id
          }}
          focusable={(index) => entries[index]?.type === 'song'}
          onContextKey={(index, rect) => {
            const entry = entries[index]
            if (entry?.type === 'song') controller.openMenuFor(songIndexOf(index), rect)
          }}
          renderCell={(state) => {
            const entry = entries[state.index]
            if (entry.type === 'disc')
              return (
                <div className="disc-heading">
                  <Icon name="disc" size={15} /> Disc {entry.disc}
                </div>
              )
            const songIndex = songIndexOf(state.index)
            return (
              <div
                className={`reorder-cell${dropAt === songIndex && dragFrom !== null && dragFrom !== songIndex ? (dragFrom < songIndex ? ' drop-after' : ' drop-before') : ''}`}
                onDragOver={(event) => {
                  if (dragFrom === null) return
                  event.preventDefault()
                  setDropAt(songIndex)
                }}
                onDrop={(event) => {
                  if (dragFrom === null) return
                  event.preventDefault()
                  move(dragFrom, songIndex)
                  setDragFrom(null)
                  setDropAt(null)
                }}
              >
                {onReorder && (
                  <span
                    className="drag-handle"
                    draggable
                    title="Drag to reorder"
                    aria-hidden="true"
                    onDragStart={(event) => {
                      event.stopPropagation()
                      event.dataTransfer.setData('text/plain', entry.id)
                      event.dataTransfer.effectAllowed = 'move'
                      setDragFrom(songIndex)
                    }}
                    onDragEnd={() => {
                      setDragFrom(null)
                      setDropAt(null)
                    }}
                  >
                    <Icon name="drag" size={16} />
                  </span>
                )}
                <SongRow id={entry.id} state={{...state, index: songIndex}} showAlbum={showAlbum} number={entry.number} />
              </div>
            )
          }}
        />
        {controller.menuNode}
      </div>
      </HeroActions.Provider>
    </CollectionContext.Provider>
  )
}

