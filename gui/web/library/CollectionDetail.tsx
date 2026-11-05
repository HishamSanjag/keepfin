// A downloaded collection: cover, follow card (status, Check now, Review), what is new, and every item in source
// order. Items that left the source stay, dimmed, with a note.
import {useEffect, useMemo, useRef, useState} from 'react'
import {agoWords, collectionItems, collectionKindWords, everyWords, followLineWords, type LibraryItem} from '../../shared/library'
import {formatBytes, plural} from '../format'
import {motion, useSpring} from '../motion/spring'
import {playItem} from '../player/play'
import {addToQueue, playNext} from '../player/playerStore'
import {useCollectionRun, useJob, type CollectionRun} from '../store'
import {Button, EmptyState} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {checkCollection, reviewCollection} from './collectionActions'
import type {Density} from './density'
import {openDialog} from './dialogState'
import {Hero} from './Hero'
import {isSong, libraryAct, useLibrary} from './libStore'
import {durationWords, totalDuration} from './model'
import {SongList} from './SongList'

/** Aggregate progress of the downloads a check started. The bar follows reported values on the critically damped truth spring, so it never gets ahead of them. */
function RunStrip({run}: {run: CollectionRun}) {
  const fill = useRef<HTMLDivElement>(null)
  const spring = useSpring(0, motion.truth, (value) => {
    if (fill.current) fill.current.style.transform = `scaleX(${Math.max(0, Math.min(1, value / 100))})`
  }, 0.05)
  useEffect(() => {
    spring.to(run.percent)
  }, [run.percent, spring])
  return (
    <section className="run-strip" aria-label="New downloads">
      <p className="run-text" role="status">
        Downloading <span className="tabular">{run.done} of {run.total}</span> new
        {run.failed > 0 && <span className="muted"> · {run.failed} failed</span>}
      </p>
      <div className="progress run-bar" role="progressbar" aria-label="Progress of new downloads" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(run.percent)}>
        <div className="progress-fill run-fill" ref={fill} />
      </div>
    </section>
  )
}

const stateWords: Record<string, string> = {queued: 'Waiting', preparing: 'Preparing', processing: 'Finishing', paused: 'Paused'}
/** A new item that has not finished yet, with its own state. Subscribes to its job alone. */
function RunItem({id}: {id: string}) {
  const job = useJob(id)
  if (!job) return null
  const state = job.status === 'downloading' ? `${Math.round(job.percent || 0)}%` : (job.status === 'processing' && job.phase) || stateWords[job.status] || job.status
  return (
    <li>
      <span className="new-job">
        <Icon name="download" size={13} /> {job.title} <span className="muted tabular">· {state}</span>
      </span>
    </li>
  )
}

type SelectProps = {selecting: boolean; onSelectingChange: (on: boolean) => void; density: Density}

export function CollectionDetail({id, ...select}: {id: string} & SelectProps) {
  const lib = useLibrary()
  const collection = lib.snapshot.collections.find((entry) => entry.id === id)
  const [checking, setChecking] = useState(false)
  const [reviewing, setReviewing] = useState(false)
  // Opening clears "new"; this visit keeps showing what was new when it began.
  const [fresh, setFresh] = useState<ReadonlySet<string>>(() => new Set(collection?.newItemIds ?? []))
  const run = useCollectionRun(id)
  useEffect(() => {
    void libraryAct('collectionOpened', undefined, {id})
  }, [id])
  // Items a check adds while this page is open join the list, then the marker is cleared as on opening.
  const incoming = collection?.newItemIds
  useEffect(() => {
    if (!incoming?.length) return
    setFresh((previous) => new Set([...previous, ...incoming]))
    void libraryAct('collectionOpened', undefined, {id})
  }, [incoming, id])
  const items = useMemo(() => (collection ? collectionItems(collection, lib.byId) : []), [collection, lib.byId])
  const removed = useMemo<ReadonlySet<string>>(() => new Set(collection?.removedItemIds ?? []), [collection?.removedItemIds])
  if (!collection) return <EmptyState icon="queue" title="This collection is gone">It was removed. Pick another from the list.</EmptyState>

  const follow = collection.follow
  const followable = collection.kind !== 'own' && !!collection.source
  const newItems = items.filter((item) => fresh.has(item.id))
  const cover = collection.coverItemId && lib.byId.get(collection.coverItemId)?.hasArt ? [collection.coverItemId] : items.filter((item) => item.hasArt).slice(0, 4).map((item) => item.id)
  const kindWord = collection.kind === 'own' ? 'Playlist · Yours' : `Downloaded collection · ${collectionKindWords(collection.kind)}`
  const noun = items.some((item) => !isSong(item)) ? 'item' : 'song'
  const gone = collection.removedItemIds?.length ?? 0
  const meta = `${plural(items.length, noun)} · ${durationWords(totalDuration(items))} · ${formatBytes(items.reduce((sum, item) => sum + item.size, 0))}${gone ? ` · ${plural(gone, 'item')} removed upstream` : ''}`

  const card = followable && (
    <section className="follow-card" aria-label="Following">
      <div className="follow-text">
        <strong className="follow-state">
          <Icon name={follow && follow.mode !== 'off' ? 'refresh' : 'close'} size={15} />
          {followLineWords(collection)}
        </strong>
        <span className="muted small">
          {!follow || follow.mode === 'off'
            ? 'Keepfin will not look for new items.'
            : follow.mode === 'auto'
              ? `Checks ${everyWords(follow.intervalHours)} while Keepfin is open.`
              : 'Checks only when you click Check now.'}
          {follow?.lastCheckedAt ? ` Last checked ${agoWords(follow.lastCheckedAt)}.` : ''}
        </span>
        {follow?.lastError && (
          <span className="follow-error" role="alert">
            {follow.lastError}
          </span>
        )}
      </div>
      <div className="follow-actions">
        {follow?.pending && (
          <Button
            variant="primary"
            icon="download"
            loading={reviewing}
            onClick={async () => {
              setReviewing(true)
              await reviewCollection(collection.id, collection.name)
              setReviewing(false)
            }}
          >
            Review {follow.pending.count} new
          </Button>
        )}
        <Button
          icon="refresh"
          loading={checking}
          onClick={async () => {
            setChecking(true)
            await checkCollection(collection.id, collection.name)
            setChecking(false)
          }}
        >
          Check now
        </Button>
        <Button variant="ghost" icon="settings" onClick={() => openDialog({type: 'follow', id: collection.id})}>
          Follow settings…
        </Button>
      </div>
    </section>
  )

  const running = run?.liveIds ?? []
  const news = newItems.length + running.length > 0 && (
    <section className="new-items" aria-label="New in this collection">
      <h3 className="shelf-title">New since your last visit · {newItems.length + running.length}</h3>
      <ul className="new-list">
        {running.map((jobId) => (
          <RunItem key={jobId} id={jobId} />
        ))}
        {newItems.map((item: LibraryItem) => (
          <li key={item.id}>
            <button type="button" className="link-btn" onClick={() => playItem(item, newItems)}>
              <Icon name="play" size={13} /> {item.title}
            </button>
          </li>
        ))}
      </ul>
    </section>
  )

  return (
    <SongList
      {...select}
      items={items}
      label={collection.name}
      menu={[
        {label: 'Play next', icon: 'skipNext', onSelect: () => playNext(items.map((item) => item.id))},
        {label: 'Add to queue', icon: 'queue', onSelect: () => addToQueue(items.map((item) => item.id))},
        'separator',
        {label: 'Move to space…', icon: 'move', onSelect: () => openDialog({type: 'move', ids: [], collection: {id: collection.id, name: collection.name}})},
        {label: 'Copy to space…', icon: 'copy', onSelect: () => openDialog({type: 'move', ids: [], mode: 'copy', collection: {id: collection.id, name: collection.name}})},
      ]}
      extra={
        followable
          ? [
              {label: follow && follow.mode !== 'off' ? 'Keeping in sync' : 'Keep in sync', icon: 'refresh', onSelect: () => openDialog({type: 'follow', id: collection.id})},
              {label: 'Download more', icon: 'download', onSelect: () => openDialog({type: 'addLink', space: collection.space})},
            ]
          : undefined
      }
      showAlbum={false}
      removedIds={removed}
      hero={
        <Hero art={cover} title={collection.name} kicker={kindWord} meta={meta}>
          {card}
          {run && <RunStrip run={run} />}
          {news}
        </Hero>
      }
      empty={
        <EmptyState icon="queue" title="This collection is empty">
          Everything in it was deleted or moved.
        </EmptyState>
      }
    />
  )
}
