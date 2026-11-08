import {memo, useMemo, useState} from 'react'
import type {Job, JobStatus} from '../../shared/contracts'
import {focusCapture} from '../capture/useCapture'
import {fileName, formatBytes, formatDuration, plural, providerFromUrl, relativeTime} from '../format'
import {useJobGone} from '../library/libStore'
import {navigate} from '../nav'
import {usePreference} from '../prefs'
import {act, getJob, liveGroupIds, useIndex, useJob} from '../store'
import {removedFromHistory, removeFromHistory} from './removal'
import {Badge, Button, ConfirmInline, EmptyState, IconButton, Segmented, Select} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {Menu, type MenuItem} from '../ui/Menu'
import {Thumbnail} from '../ui/Thumbnail'
import {DeleteFileDialog} from './DeleteFileDialog'
import {ErrorDetailsDialog} from './ErrorDetailsDialog'
import {retryText} from './JobRow'
import {GroupCard, GroupRow} from './GroupRow'
import {PlayThumb, usePlayableItem} from './PlayJob'
import {playItem} from '../player/play'
import type {LibraryItem} from '../../shared/library'

const terminal: JobStatus[] = ['completed', 'failed', 'cancelled']
type Kind = 'all' | 'video' | 'audio' | 'gallery'
type StatusFilter = 'all' | 'completed' | 'failed' | 'cancelled'
type Sort = 'newest' | 'oldest' | 'largest' | 'title'
type View = 'list' | 'grid'

const when = (job?: Job) => job?.completedAt || job?.createdAt || 0

function menuFor(job: Job, onDelete: () => void, onDetails?: () => void, gone = false, playable?: LibraryItem): MenuItem[] {
  const items: MenuItem[] = []
  if (playable && job.status === 'completed' && !gone)
    items.push({
      label: 'Open in another app',
      icon: 'external',
      onSelect: () => void act('open', {id: job.id}),
    })
  items.push({
    label: 'Copy source link',
    icon: 'copy',
    onSelect: () => void navigator.clipboard?.writeText(job.url).catch(() => {}),
  })
  if (job.status !== 'completed' && job.status !== 'queued')
    items.push({
      label: 'Retry download',
      icon: 'retry',
      onSelect: () => void act('retry', {id: job.id}),
    })
  if (job.status === 'failed' && onDetails) items.push({label: 'Error details…', icon: 'info', onSelect: onDetails})
  items.push('separator', {
    label: 'Remove from history',
    icon: 'close',
    onSelect: () => void removeFromHistory('remove', {id: job.id}, removedFromHistory()),
  })
  if (job.status === 'completed' && job.file && !gone)
    items.push({
      label: 'Delete file…',
      icon: 'trash',
      tone: 'danger',
      onSelect: onDelete,
    })
  return items
}

function StatusTag({job, gone}: {job: Job; gone?: boolean}) {
  if (gone) return <Badge tone="warning">Deleted</Badge>
  if (job.status === 'failed') return <Badge tone="danger">Failed</Badge>
  if (job.status === 'cancelled') return <Badge>Cancelled</Badge>
  if (job.saveError) return <Badge tone="warning">Not saved</Badge>
  return null
}

const LibraryRow = memo(function LibraryRow({id}: {id: string}) {
  const job = useJob(id)
  const [deleting, setDeleting] = useState(false)
  const [showError, setShowError] = useState(false)
  const gone = useJobGone(id)
  const playable = usePlayableItem(id)
  if (!job) return null
  const provider = job.provider || providerFromUrl(job.url)
  return (
    <li className={`lib-row lib-${job.status}${gone ? ' lib-gone' : ''}`}>
      <PlayThumb item={job.status === 'completed' && !gone ? playable : undefined} title={job.title}>
        <Thumbnail jobId={id} ready={job.status === 'completed'} kind={job.kind} lazy label={job.title} className="thumb-lib" />
      </PlayThumb>
      <div className="lib-text">
        <span className="lib-title" title={job.title}>
          {job.title}
        </span>
        <span className="lib-meta">
          <StatusTag job={job} gone={gone} />
          <span className="sep-list">
            {[provider, job.label, formatDuration(job.duration), job.signedIn].filter(Boolean).map((part, index) => (
              <span key={index}>{part}</span>
            ))}
          </span>
        </span>
        {job.status === 'failed' && job.error && <span className="lib-error">{job.error}</span>}
        {job.status === 'completed' && job.saveError && <span className="lib-error">{job.saveError}</span>}
        {job.status === 'queued' && job.retry && <span className="lib-note">{retryText(job.retry)}</span>}
      </div>
      <span className="lib-size tabular">{job.status === 'completed' && !gone ? formatBytes(job.fileSize) : ''}</span>
      <span className="lib-date tabular">{relativeTime(when(job))}</span>
      <div className="lib-actions">
        {job.status === 'completed' ? (
          <>
            {job.saveError && (
              <Button size="sm" icon="retry" onClick={() => void act('saveAgain', {id})}>
                Save again
              </Button>
            )}
            {playable && !gone ? (
              <IconButton icon="play" label={`Play ${job.title}`} onClick={() => playItem(playable)} />
            ) : (
              <IconButton icon="open" label={`Open ${fileName(job.file) || job.title}`} disabled={!job.file || gone} onClick={() => void act('open', {id})} />
            )}
            <IconButton icon="folder" label="Show in folder" disabled={!job.file || gone} onClick={() => void act('reveal', {id})} />
          </>
        ) : job.status === 'queued' ? null : (
          <>
            {job.status === 'failed' && (
              <Button size="sm" variant="ghost" icon="info" className="lib-details-btn" onClick={() => setShowError(true)}>
                Error details
              </Button>
            )}
            <Button size="sm" icon="retry" onClick={() => void act('retry', {id})}>
              Retry
            </Button>
          </>
        )}
        <Menu
          label={`More actions for ${job.title}`}
          items={menuFor(
            job,
            () => setDeleting(true),
            () => setShowError(true),
            gone,
            playable,
          )}
        />
      </div>
      {showError && <ErrorDetailsDialog job={job} onClose={() => setShowError(false)} />}
      {deleting && <DeleteFileDialog job={job} onClose={() => setDeleting(false)} />}
    </li>
  )
})

const LibraryCard = memo(function LibraryCard({id}: {id: string}) {
  const job = useJob(id)
  const [deleting, setDeleting] = useState(false)
  const gone = useJobGone(id)
  const playable = usePlayableItem(id)
  if (!job) return null
  const provider = job.provider || providerFromUrl(job.url)
  return (
    <li className={`lib-card lib-${job.status}${gone ? ' lib-gone' : ''}`}>
      <button
        type="button"
        className="lib-card-media"
        aria-label={job.status === 'completed' ? (playable && !gone ? `Play ${job.title}` : `Open ${job.title}`) : `Retry ${job.title}`}
        onClick={() => (job.status === 'completed' && playable && !gone ? playItem(playable) : void act(job.status === 'completed' ? 'open' : 'retry', {id}))}
        disabled={job.status === 'completed' && (!job.file || gone)}
      >
        <Thumbnail jobId={id} ready={job.status === 'completed'} kind={job.kind} duration={job.duration} lazy label={job.title} />
        {playable && !gone && job.status === 'completed' && (
          <span className="thumb-play-badge" aria-hidden="true">
            <Icon name="play" size={20} />
          </span>
        )}
        {gone && (
          <span className="lib-card-state">
            <Icon name="warning" size={15} />
            Deleted
          </span>
        )}
        {job.status !== 'completed' && (
          <span className="lib-card-state">
            <Icon name={job.status === 'failed' ? 'warning' : 'retry'} size={15} />
            {job.status === 'failed' ? 'Failed · Retry' : 'Cancelled · Retry'}
          </span>
        )}
      </button>
      <div className="lib-card-body">
        <span className="lib-title" title={job.title}>
          {job.title}
        </span>
        <span className="lib-meta sep-list">
          <span>{provider}</span>
        </span>
        <span className="lib-meta sep-list">
          {[job.status === 'completed' && !gone ? formatBytes(job.fileSize) : '', relativeTime(when(job))].filter(Boolean).map((part, index) => (
            <span key={index}>{part.replace(/ /g, '\u00a0')}</span>
          ))}
        </span>
      </div>
      <div className="lib-card-actions">
        {job.status === 'completed' && (
          <IconButton icon="folder" size="sm" label="Show in folder" disabled={!job.file || gone} onClick={() => void act('reveal', {id})} />
        )}
        <Menu label={`More actions for ${job.title}`} items={menuFor(job, () => setDeleting(true), undefined, gone, playable)} />
      </div>
      {deleting && <DeleteFileDialog job={job} onClose={() => setDeleting(false)} />}
    </li>
  )
})

const renderLibraryRow = (id: string) => <LibraryRow key={id} id={id} />

export function History({only}: {only?: 'finished' | 'failed'} = {}) {
  const index = useIndex()
  const [query, setQuery] = useState('')
  const [kind, setKind] = useState<Kind>('all')
  const [status, setStatus] = useState<StatusFilter>('all')
  const [provider, setProvider] = useState('all')
  const [sort, setSort] = usePreference<Sort>('keepfin-library-sort', 'newest', (v) => ['newest', 'oldest', 'largest', 'title'].includes(v as string))
  const [view, setView] = usePreference<View>('keepfin-library-view', 'list', (v) => v === 'list' || v === 'grid')

  // A collection appears here as soon as none of its members is moving. Members that failed or wait on
  // an automatic retry count as settled, so a partly failed collection is never hidden.
  const history = useMemo(() => {
    const doneGroups = new Set(index.filter((entry) => entry.group && entry.status === 'completed').map((entry) => entry.group!))
    const live = liveGroupIds(index)
    return index
      .filter((entry) => terminal.includes(entry.status) || (!!entry.group && !!entry.retrying))
      .filter((entry) => !(entry.group && live.has(entry.group)))
      .filter((entry) =>
        only === 'finished'
          ? entry.status === 'completed' || (!!entry.group && doneGroups.has(entry.group))
          : only === 'failed'
            ? entry.status !== 'completed'
            : true,
      )
      .map((entry) => getJob(entry.id)!)
      .filter(Boolean)
  }, [index, only])
  const providers = useMemo(() => [...new Set(history.map((job) => job.provider || providerFromUrl(job.url)).filter(Boolean))].sort(), [history])
  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    const list = history.filter(
      (job) =>
        (kind === 'all' || job.kind === kind) &&
        (status === 'all' || job.status === status) &&
        (provider === 'all' || (job.provider || providerFromUrl(job.url)) === provider) &&
        (!q || [job.title, job.uploader, job.file, job.url].some((value) => value?.toLowerCase().includes(q))),
    )
    const sorters: Record<Sort, (a: Job, b: Job) => number> = {
      newest: (a, b) => when(b) - when(a),
      oldest: (a, b) => when(a) - when(b),
      largest: (a, b) => (b.fileSize || 0) - (a.fileSize || 0),
      title: (a, b) => a.title.localeCompare(b.title),
    }
    return list.sort(sorters[sort])
  }, [history, query, kind, status, provider, sort])
  // Standalone jobs and one entry per collection (at its first visible member).
  const rows = useMemo(() => {
    const seen = new Set<string>()
    const out: {key: string; job: Job; group?: string; ids?: string[]}[] = []
    for (const job of visible) {
      const g = job.group?.id
      if (!g) out.push({key: job.id, job})
      else if (!seen.has(g)) {
        seen.add(g)
        out.push({
          key: `group:${g}`,
          job,
          group: g,
          ids: visible
            .filter((other) => other.group?.id === g)
            .sort((a, b) => a.createdAt - b.createdAt)
            .map((other) => other.id),
        })
      }
    }
    return out
  }, [visible])
  const savedBytes = history.reduce((sum, job) => sum + (job.status === 'completed' ? job.fileSize || 0 : 0), 0)
  const groupCount = new Set(history.map((job) => job.group?.id).filter(Boolean)).size
  const filtered = query || kind !== 'all' || status !== 'all' || provider !== 'all'
  const reset = () => {
    setQuery('')
    setKind('all')
    setStatus('all')
    setProvider('all')
  }

  if (!history.length)
    return (
      <div className="history">
        <EmptyState
          icon="library"
          title={only === 'failed' ? 'Nothing has failed' : 'No finished downloads yet'}
          actions={
            <Button
              variant="primary"
              icon="plus"
              onClick={() => {
                navigate('Downloads')
                requestAnimationFrame(focusCapture)
              }}
            >
              Paste a link
            </Button>
          }
        >
          Finished downloads are kept here with their files, sizes and sources, ready to open or retry.
        </EmptyState>
      </div>
    )

  return (
    <div className="history">
      <div className="lib-summary">
        <span className="tabular">
          {plural(history.length, 'item')}
          {groupCount > 0 && ` · ${plural(groupCount, 'collection')}`}
          {savedBytes > 0 && ` · ${formatBytes(savedBytes)} on disk`}
        </span>
        <ConfirmInline
          label="Clear history"
          icon="trash"
          confirmLabel="Clear history"
          prompt={`Remove ${plural(history.length, 'entry', 'entries')} from history? Files stay on disk.`}
          onConfirm={() => removeFromHistory('clear', {}, removedFromHistory(history.length))}
        />
      </div>
      <div className="lib-toolbar" role="toolbar" aria-label="Download filters">
        <div className="search grow">
          <Icon name="search" size={15} />
          <input
            className="input"
            type="search"
            aria-label="Search downloads"
            placeholder="Search titles, creators, files"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <Segmented<Kind>
          label="Media type"
          size="sm"
          value={kind}
          onChange={setKind}
          options={[
            {value: 'all', label: 'All'},
            {value: 'video', label: 'Video'},
            {value: 'audio', label: 'Audio'},
            {value: 'gallery', label: 'Images'},
          ]}
        />
        {!only && (
          <Select<StatusFilter>
            label="Status"
            value={status}
            onChange={setStatus}
            options={[
              {value: 'all', label: 'Any status'},
              {value: 'completed', label: 'Saved'},
              {value: 'failed', label: 'Failed'},
              {value: 'cancelled', label: 'Cancelled'},
            ]}
          />
        )}
        <Select
          label="Provider"
          value={provider}
          onChange={setProvider}
          options={[{value: 'all', label: 'All sources'}, ...providers.map((name) => ({value: name, label: name}))]}
        />
        <Select<Sort>
          label="Sort"
          value={sort}
          onChange={setSort}
          options={[
            {value: 'newest', label: 'Newest first'},
            {value: 'oldest', label: 'Oldest first'},
            {value: 'largest', label: 'Largest first'},
            {value: 'title', label: 'Title A–Z'},
          ]}
        />
        <Segmented<View>
          label="View"
          size="sm"
          value={view}
          onChange={setView}
          options={[
            {
              value: 'list',
              label: <Icon name="list" size={16} />,
              title: 'List view',
            },
            {
              value: 'grid',
              label: <Icon name="grid" size={16} />,
              title: 'Grid view',
            },
          ]}
        />
      </div>
      {visible.length ? (
        view === 'list' ? (
          <ul className="lib-list" aria-label="Download history">
            {rows.map((row) =>
              row.group ? (
                <GroupRow key={row.key} id={row.group} mode="library" ids={row.ids} renderChild={renderLibraryRow} />
              ) : (
                <LibraryRow key={row.key} id={row.job.id} />
              ),
            )}
          </ul>
        ) : (
          <ul className="lib-grid" aria-label="Download history">
            {rows.map((row) =>
              row.group ? (
                <GroupCard key={row.key} id={row.group} ids={row.ids} />
              ) : (
                <LibraryCard key={row.key} id={row.job.id} />
              ),
            )}
          </ul>
        )
      ) : (
        <EmptyState
          icon="search"
          title="No matches"
          actions={
            filtered && (
              <Button size="sm" onClick={reset}>
                Reset filters
              </Button>
            )
          }
        >
          Nothing here matches these filters.
        </EmptyState>
      )}
    </div>
  )
}
