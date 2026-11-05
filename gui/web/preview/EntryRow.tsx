import {memo} from 'react'
import type {PlaylistEntry} from '../../shared/contracts'
import {unavailableLabel} from '../../shared/core/formats'
import type {ExistingMatch} from '../../shared/library'
import {formatDuration} from '../format'
import {spaceName} from '../library/libStore'
import {Badge} from '../ui/controls'
import {Thumbnail} from '../ui/Thumbnail'

/** One entry of a playlist, album or channel: tick it, see what it is and whether you already have it. */
export const EntryRow = memo(function EntryRow({
  entry,
  index,
  checked,
  queued,
  disabled,
  music,
  existing,
  onToggle,
}: {
  entry: PlaylistEntry
  index: number
  checked: boolean
  queued: boolean
  disabled: boolean
  music: boolean
  existing?: ExistingMatch
  onToggle: (url: string, index: number, range: boolean) => void
}) {
  const gone = !!entry.unavailable
  const title = gone ? unavailableLabel : entry.title || `Item ${entry.position}`
  const byline = gone ? entry.unavailable : [entry.artist || entry.uploader, entry.album].filter(Boolean).join(' · ')
  return (
    <li
      className={`entry${checked ? ' checked' : ''}${queued ? ' queued' : ''}${gone ? ' unavailable' : ''}`}
      onClick={(event) => {
        if (!disabled && !queued && !gone) onToggle(entry.url, index, event.shiftKey)
      }}
    >
      <input
        type="checkbox"
        className="check"
        checked={checked || queued}
        disabled={disabled || queued || gone}
        aria-label={`Select ${title}`}
        onChange={() => {}}
        onClick={(event) => {
          event.stopPropagation()
          onToggle(entry.url, index, event.shiftKey)
        }}
      />
      <span className="entry-pos tabular">{entry.position}</span>
      <Thumbnail url={entry.thumbnail} kind={music ? 'audio' : 'video'} lazy label={title} className="thumb-xs" />
      <span className="entry-text">
        <span className="entry-title">{title}</span>
        {byline && <span className="entry-meta">{byline}</span>}
      </span>
      {queued ? (
        <Badge tone="success">In queue</Badge>
      ) : existing ? (
        <Badge tone="accent" title="A matching recording is already saved there">
          Already in {spaceName(existing.space)}
        </Badge>
      ) : (
        <span className="entry-dur tabular">{formatDuration(entry.duration)}</span>
      )}
    </li>
  )
})
