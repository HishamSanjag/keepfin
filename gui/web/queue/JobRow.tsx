import {memo, useEffect, useState} from 'react'
import type {Job} from '../../shared/contracts'
import {optionsSummary} from '../preview/extrasModel'
import {MatchLine} from '../capture/MatchLine'
import {etaLabel, fileName, formatBytes, providerFromUrl, relativeTime, speedLabel} from '../format'
import {act, useJob, usePending} from '../store'
import {removedFromHistory, removeFromHistory} from './removal'
import {Button, IconButton, ProgressBar, Spinner} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {Menu, type MenuItem} from '../ui/Menu'
import {Thumbnail} from '../ui/Thumbnail'
import {DeleteFileDialog} from './DeleteFileDialog'
import {getLibrary, spaceName, useJobGone, useLibraryEnabled} from '../library/libStore'
import {openExternally, playItem} from '../player/play'
import {PlayThumb, usePlayableItem} from './PlayJob'
import {ErrorDetailsDialog} from './ErrorDetailsDialog'

/** Truthful automatic-retry state: the countdown ticks locally, so no push is needed per second. */
export function retryText(retry: NonNullable<Job['retry']>, now = Date.now()) {
  const suffix = `(${retry.n} of ${retry.max})`
  if (retry.updating) return `Updating engine, then retrying ${suffix}`
  const seconds = Math.max(0, Math.ceil(((retry.at ?? now) - now) / 1000))
  return seconds > 0 ? `Retrying in ${seconds} s ${suffix}` : `Retrying now ${suffix}`
}

function RetryNote({retry}: {retry: NonNullable<Job['retry']>}) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (retry.updating || !retry.at) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [retry.at, retry.updating])
  return (
    <span className="phase retry-note" role="status">
      <Spinner size={12} />
      {retryText(retry, now)}
    </span>
  )
}

export function statusText(job: Job) {
  switch (job.status) {
    case 'queued':
      return job.retry ? retryText(job.retry) : 'Queued'
    case 'preparing':
      return job.phase || 'Preparing'
    case 'downloading':
      return job.phase && job.phase !== 'Downloading' ? job.phase : 'Downloading'
    case 'processing':
      return job.phase || 'Processing'
    case 'paused':
      return 'Paused'
    case 'failed':
      return 'Failed'
    case 'cancelled':
      return 'Cancelled'
    case 'completed':
      return 'Saved'
  }
}

function TransferStats({job}: {job: Job}) {
  if (job.status === 'queued' && job.retry) return <RetryNote retry={job.retry} />
  if (job.status === 'queued') return <span>Waiting for a free slot</span>
  if (job.status === 'paused')
    return (
      <span className="tabular">
        Paused at {Math.round(job.percent)}%{job.downloadedBytes ? ` · ${formatBytes(job.downloadedBytes)} kept` : ''}
      </span>
    )
  if (job.status === 'preparing' || job.status === 'processing')
    return (
      <span className="phase">
        <Spinner size={12} />
        {statusText(job)}
        {job.status === 'processing' && job.percent > 0 && job.percent < 100 ? <span className="tabular"> · {Math.round(job.percent)}%</span> : null}
      </span>
    )
  const total = job.totalBytes || job.expectedSize
  const parts = [
    job.downloadedBytes ? `${formatBytes(job.downloadedBytes)}${total ? ` of ${formatBytes(total)}` : ''}` : total ? formatBytes(total) : '',
    speedLabel(job.speed),
    etaLabel(job.eta),
  ].filter(Boolean)
  return (
    <span className="tabular">
      <strong className="pct">{Math.floor(job.percent)}%</strong>
      {parts.map((part, index) => (
        <span key={index}>
          <span className="dot-sep" />
          {part}
        </span>
      ))}
    </span>
  )
}

function Details({job}: {job: Job}) {
  const rows: [string, string][] = [
    ['Source', job.url],
    ['Format', job.label],
    ['Options', optionsSummary(job.options || {})],
    ['File', job.file || ''],
    ['Other files', job.files?.length ? job.files.map(fileName).join(', ') : ''],
    ['Added', job.createdAt ? new Date(job.createdAt).toLocaleString() : ''],
    ['Started', job.startedAt ? new Date(job.startedAt).toLocaleString() : ''],
    ['Finished', job.completedAt ? new Date(job.completedAt).toLocaleString() : ''],
    ['Attempt', job.attempt && job.attempt > 1 ? String(job.attempt) : ''],
  ]
  return (
    <dl className="job-details">
      {rows
        .filter(([, value]) => value)
        .map(([key, value]) => (
          <div key={key}>
            <dt>{key}</dt>
            <dd>{value}</dd>
          </div>
        ))}
    </dl>
  )
}

export const JobRow = memo(function JobRow({id, canMoveUp = false}: {id: string; canMoveUp?: boolean}) {
  const job = useJob(id)
  const [expanded, setExpanded] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [showError, setShowError] = useState(false)
  const pendingCancel = usePending(`cancel:${id}`)
  const pendingPause = usePending(`pause:${id}`)
  const pendingResume = usePending(`resume:${id}`)
  const pendingRetry = usePending(`retry:${id}`)
  const busyAnyway = usePending(`downloadAnyway:${id}`)
  const busy = pendingCancel || pendingPause || pendingResume || pendingRetry
  const libraryOn = useLibraryEnabled()
  const gone = useJobGone(id)
  const playable = usePlayableItem(id)
  const tests = window.__keepfinTest
  if (tests) tests.rowRenders[id] = (tests.rowRenders[id] || 0) + 1
  if (!job) return null
  const provider = job.provider || providerFromUrl(job.url)
  const running = job.status === 'downloading' || job.status === 'preparing' || job.status === 'processing'
  const live = running || job.status === 'queued' || job.status === 'paused'
  // At most two actions stay on the row; the rest (move up, error details) live in its menu.
  const items: MenuItem[] = []
  if (job.status === 'queued' && canMoveUp) items.push({label: 'Move up in queue', icon: 'up', onSelect: () => void act('moveUp', {id})})
  if (job.status === 'failed') items.push({label: 'Error details', icon: 'info', onSelect: () => setShowError(true)})
  items.push(
    {
      label: expanded ? 'Hide details' : 'Show details',
      icon: 'info',
      onSelect: () => setExpanded(!expanded),
    },
    {
      label: 'Copy source link',
      icon: 'copy',
      onSelect: () => void navigator.clipboard?.writeText(job.url).catch(() => {}),
    },
  )
  if (playable && job.status === 'completed' && !gone)
    items.push({
      label: 'Open in another app',
      icon: 'external',
      onSelect: () => void act('open', {id}),
    })
  if (job.status === 'completed' && job.file && !gone)
    items.push('separator', {
      label: 'Delete file…',
      icon: 'trash',
      tone: 'danger',
      onSelect: () => setConfirmDelete(true),
    })
  if (!live)
    items.push('separator', {
      label: 'Remove from history',
      icon: 'close',
      onSelect: () => void removeFromHistory('remove', {id}, removedFromHistory()),
    })
  // An item waiting for an automatic retry: Remove stops the retry, then takes it off the list. Files are never touched.
  else if (job.status === 'queued' && job.retry)
    items.push('separator', {
      label: 'Remove',
      icon: 'close',
      onSelect: () => void act('remove', {id}),
    })
  return (
    <li className={`job job-${job.status}${live ? ' job-live' : ''}${expanded ? ' expanded' : ''}`} aria-label={job.title}>
      <PlayThumb item={job.status === 'completed' && !gone ? playable : undefined} title={job.title}>
        <Thumbnail jobId={id} ready={job.status === 'completed'} kind={job.kind} duration={job.duration} label={job.title} className="thumb-job" />
      </PlayThumb>
      <div className="job-body">
        <div className="job-head">
          <span className="job-title" title={job.title}>
            {job.title}
          </span>
        </div>
        <div className="job-meta sep-list">
          {[job.remote ? `From ${job.remote.device}` : '', provider, job.label, job.uploader, job.signedIn].filter(Boolean).map((part, index) => (
            <span key={index}>{part}</span>
          ))}
        </div>
        {live && (
          <>
            {/* One status line with aligned figures (percent, done of total, speed, time left), then the thin bar. */}
            <div className="job-stats">
              <TransferStats job={job} />
            </div>
            <ProgressBar
              value={job.percent}
              label={`${job.title} progress`}
              mode={job.status === 'preparing' || (job.status === 'processing' && !(job.percent > 0 && job.percent < 100)) ? 'indeterminate' : 'determinate'}
              tone={job.status === 'paused' ? 'paused' : job.status === 'processing' ? 'processing' : undefined}
            />
          </>
        )}
        {job.status === 'failed' && <p className="job-error">{job.error || 'The download stopped unexpectedly.'}</p>}
        {job.status === 'cancelled' && <p className="job-note">Cancelled{job.percent > 0 ? ` at ${Math.round(job.percent)}%` : ''}</p>}
        {libraryOn && job.status === 'completed' && job.existing && (
          <div className="job-existing" role="status">
            <Icon name="check" size={14} />
            <span>
              <strong>Already in {spaceName(job.existing.space)}</strong>
              <span className="muted"> · nothing was downloaded</span>
            </span>
          </div>
        )}
        {gone && (
          <div className="job-existing" role="status">
            <Icon name="warning" size={14} />
            <span>
              <strong>Deleted</strong>
              <span className="muted"> · the file is no longer on this computer</span>
            </span>
          </div>
        )}
        {job.status === 'completed' && !job.existing && !gone && (
          <div className="job-saved sep-list tabular">
            {[libraryOn && job.space ? `Saved to ${spaceName(job.space)}` : '', formatBytes(job.fileSize), relativeTime(job.completedAt), fileName(job.file)]
              .filter(Boolean)
              .map((part, index) => (
                <span key={index}>{part}</span>
              ))}
          </div>
        )}
        {job.match && job.match.matchedUrl && <MatchLine match={job.match} compact />}
        {expanded && <Details job={job} />}
      </div>
      <div className="job-actions">
        {(job.status === 'downloading' || job.status === 'queued') && (
          <IconButton icon="pause" label="Pause" disabled={busy} onClick={() => void act('pause', {id})} />
        )}
        {job.status === 'paused' && <IconButton icon="play" label="Resume" disabled={busy} onClick={() => void act('resume', {id})} />}
        {live && <IconButton icon="close" label="Cancel download" disabled={busy} onClick={() => void act('cancel', {id})} />}
        {(job.status === 'failed' || job.status === 'cancelled') && (
          <Button size="sm" icon="retry" disabled={busy} onClick={() => void act('retry', {id})}>
            Retry
          </Button>
        )}
        {(job.status === 'failed' || job.status === 'cancelled') && (
          <Button size="sm" variant="ghost" icon="close" disabled={busy} onClick={() => void removeFromHistory('remove', {id}, removedFromHistory())}>
            Remove
          </Button>
        )}
        {libraryOn && job.status === 'completed' && job.existing && (
          <>
            {job.existing.itemId && getLibrary().byId.has(job.existing.itemId) && (
              <Button size="sm" icon="play" onClick={() => playItem(getLibrary().byId.get(job.existing!.itemId!)!)}>
                Play
              </Button>
            )}
            <Button size="sm" icon="download" disabled={busyAnyway} onClick={() => void act('downloadAnyway', {id})}>
              Download anyway
            </Button>
          </>
        )}
        {job.status === 'completed' && !(libraryOn && job.existing) && (
          <>
            {playable && !gone ? (
              <Button size="sm" icon="play" aria-label={`Play ${job.title}`} onClick={() => playItem(playable)}>
                Play
              </Button>
            ) : (
              <Button
                size="sm"
                icon="open"
                disabled={!job.file || gone}
                title="Opens in your default app"
                aria-label={libraryOn ? 'Open in another app' : undefined}
                onClick={() => void act('open', {id})}
              >
                Open
              </Button>
            )}
            <IconButton icon="folder" label="Show in folder" disabled={!job.file || gone} onClick={() => void act('reveal', {id})} />
          </>
        )}
        <Menu label={`More actions for ${job.title}`} items={items} />
      </div>
      {showError && <ErrorDetailsDialog job={job} onClose={() => setShowError(false)} />}
      {confirmDelete && <DeleteFileDialog job={job} onClose={() => setConfirmDelete(false)} />}
    </li>
  )
})
