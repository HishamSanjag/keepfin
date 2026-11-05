import {Fragment, memo, useCallback, useEffect, useRef, useState, type ReactNode} from 'react'
import {CollectionWindow} from './CollectionWindow'
import {useGoneCount} from '../library/libStore'
import {removedSummary} from '../../shared/library'
import {getJob, consumeGroupFocus, act, onGroupFocus, useGroup, usePending, type GroupStats} from '../store'
import {formatBytes, hostOf, plural, relativeTime, speedLabel} from '../format'
import {removedFromHistory, removeFromHistory} from './removal'
import {Badge, Button, IconButton, ProgressBar} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {Menu, type MenuItem} from '../ui/Menu'
import {Thumbnail} from '../ui/Thumbnail'
import {playableItems, playCollection} from './PlayJob'
import {useLibraryEnabled} from '../library/libStore'

const NO_IDS: string[] = []
const kindLabels = {
  playlist: 'Playlist',
  album: 'Album',
  channel: 'Channel',
  collection: 'Collection',
} as const

/** "10 saved · 2 failed". While members are still moving it reads "2 of 6 saved · 1 failed". */
export function groupSummary(stats: GroupStats) {
  const parts = [stats.live ? `${stats.completed} of ${stats.total} saved` : `${stats.completed} saved`]
  if (stats.failed) parts.push(`${stats.failed} failed`)
  if (stats.retrying) parts.push(`${stats.retrying} retrying`)
  if (stats.paused) parts.push(`${stats.paused} paused`)
  if (stats.cancelled) parts.push(`${stats.cancelled} cancelled`)
  return parts.join(' · ')
}

/** The summary line with the saved and failed counts coloured; its text matches groupSummary. */
function GroupSummary({stats}: {stats: GroupStats}) {
  const saved = stats.live ? `${stats.completed} of ${stats.total} saved` : `${stats.completed} saved`
  const rest: [string, string][] = []
  if (stats.failed) rest.push([`${stats.failed} failed`, 'sum-failed'])
  if (stats.retrying) rest.push([`${stats.retrying} retrying`, ''])
  if (stats.paused) rest.push([`${stats.paused} paused`, ''])
  if (stats.cancelled) rest.push([`${stats.cancelled} cancelled`, ''])
  return (
    <>
      <span className="sum-saved">{saved}</span>
      {rest.map(([text, cls]) => (
        <span key={text}>
          {' · '}
          <span className={cls || undefined}>{text}</span>
        </span>
      ))}
    </>
  )
}

/** A collection starts open while anything in it is moving or did not end up saved, and closed once everything is finished. */
export const groupStartsOpen = (stats: GroupStats) => stats.live || stats.failed + stats.cancelled + stats.retrying > 0

/** Members in the order people need them: what failed first, then what is moving, then what is saved. */
export function groupSections(stats: GroupStats, only?: string[]) {
  const keep = only && new Set(only)
  const pick = (list: string[]) => (keep ? list.filter((id) => keep.has(id)) : list)
  const sections = [
    {key: 'attention', title: 'Needs attention', ids: pick(stats.attention)},
    {key: 'active', title: 'In progress', ids: pick(stats.active)},
    {key: 'saved', title: 'Saved', ids: pick(stats.saved)},
  ].filter((section) => section.ids.length)
  return sections
}

/**
 * One collapsible row for a collection kept together. It subscribes to its group's cached
 * aggregate (updated once per push batch), while each child row keeps its own per-job
 * subscription, so a child's progress tick never re-renders its siblings.
 */
export const GroupRow = memo(function GroupRow({
  id,
  mode,
  ids,
  renderChild,
  detail = false,
}: {
  id: string
  mode: 'queue' | 'library'
  /** Internal header reuse in the collection detail surface. */
  detail?: boolean
  /** Restrict the expanded list (Library filters); defaults to every member. */
  ids?: string[]
  renderChild: (jobId: string) => ReactNode
}) {
  const stats = useGroup(id)
  const gone = useGoneCount(stats?.saved ?? NO_IDS)
  const libraryOn = useLibraryEnabled()
  const [choice, setChoice] = useState<boolean | null>(null)
  const [flash, setFlash] = useState(false)
  const root = useRef<HTMLLIElement>(null)
  const open = !detail && (choice ?? (!!stats && groupStartsOpen(stats)))
  const toggle = () => setChoice(!open)
  const pausing = usePending(`pauseGroup:${id}`)
  const resuming = usePending(`resumeGroup:${id}`)
  const cancelling = usePending(`cancelGroup:${id}`)
  const retrying = usePending(`retryGroup:${id}`)
  const removingFailed = usePending(`removeFailed:${id}`)
  const busy = pausing || resuming || cancelling || retrying || removingFailed
  const tests = window.__keepfinTest
  if (tests) {
    const renderKey = `${detail ? 'group-detail' : 'group'}:${id}`
    tests.rowRenders[renderKey] = (tests.rowRenders[renderKey] || 0) + 1
  }

  // A notification click focuses this collection once: scroll + brief highlight, then the request is gone.
  useEffect(() => {
    if (detail) return
    const focus = () => {
      // A row on a hidden screen (Downloads while Library is open) must not swallow the request.
      if (!root.current || root.current.offsetParent === null) return
      if (!consumeGroupFocus(id)) return
      root.current?.scrollIntoView?.({block: 'center', behavior: 'smooth'})
      setFlash(true)
      window.setTimeout(() => setFlash(false), 1600)
    }
    focus()
    return onGroupFocus(focus)
  }, [id, detail])

  if (!stats) return null
  const {group} = stats
  const cover = getJob(stats.coverId)
  const active = stats.running + stats.queued
  const sections = groupSections(stats, ids)
  const source = hostOf(group.url)
  const removed = removedSummary(gone, stats.completed)
  const items: MenuItem[] = [
    {
      label: open ? 'Hide items' : 'Show items',
      icon: 'list',
      onSelect: toggle,
    },
    {
      label: 'Copy source link',
      icon: 'copy',
      onSelect: () => void navigator.clipboard?.writeText(group.url).catch(() => {}),
    },
    {
      label: 'Open source page',
      icon: 'external',
      onSelect: () => void act('openGroupLink', {id}),
    },
  ]
  if (detail) items.shift()
  if (stats.failed > 0)
    items.push({
      label: 'Remove failed',
      icon: 'close',
      onSelect: () => void removeFromHistory('removeFailed', {id}, removedFromHistory(stats.failed)),
    })
  if (!stats.live && !stats.retrying)
    items.push('separator', {
      label: 'Remove from history',
      icon: 'close',
      onSelect: () => void removeFromHistory('removeGroup', {id}, removedFromHistory()),
    })
  return (
    <li
      ref={root}
      className={`group-row group-${mode}${stats.live ? ' group-live' : ''}${flash ? ' group-flash' : ''}`}
      aria-label={`${group.title}, ${groupSummary(stats)}${removed ? `, ${removed}` : ''}`}
      data-group={id}
      onClick={(event) => {
        if (detail) return
        const target = event.target as HTMLElement
        if (target.closest('.group-items')) return
        if (!target.closest('button, a, input, select')) toggle()
      }}
    >
      <div className={`group-head${detail ? ' collection-header' : ''}`}>
        <button
          type="button"
          className="group-toggle"
          aria-expanded={open}
          aria-controls={`group-items-${id}`}
          aria-label={`${open ? 'Hide' : 'Show'} ${plural(stats.total, 'item')} in ${group.title}`}
          onClick={toggle}
        >
          <Icon name="chevron" size={16} className={open ? 'chev open' : 'chev'} />
        </button>
        <div className="group-art">
          <Thumbnail
            jobId={stats.coverId}
            ready={cover?.status === 'completed'}
            kind={cover?.kind}
            label={group.title}
            className={mode === 'queue' ? 'thumb-job' : 'thumb-lib'}
          />
          <span className="group-stack" aria-hidden="true">
            <Icon name="stack" size={12} />
            {stats.total}
          </span>
        </div>
        <div className="group-body">
          <div className="job-head">
            <span className="job-title" title={group.title}>
              {group.title}
            </span>
            <Badge>{kindLabels[group.kind ?? 'collection']}</Badge>
          </div>
          <div className="job-meta sep-list">
            {group.uploader && <span>{group.uploader}</span>}
            <span>
              <button
                type="button"
                className="link-btn group-source"
                title={group.url}
                aria-label={`Open ${group.title} on ${source}`}
                onClick={() => void act('openGroupLink', {id})}
              >
                {source}
                <Icon name="external" size={12} />
              </button>
            </span>
            {!stats.live && stats.when > 0 && <span>{relativeTime(stats.when)}</span>}
          </div>
          {stats.live && (
            <>
              <ProgressBar value={stats.percent} label={`${group.title} progress`} tone={active === 0 ? 'paused' : undefined} />
              <div className="job-stats tabular">
                <strong className="pct">{stats.percent}%</strong>
                {stats.bytes > 0 && (
                  <span>
                    <span className="dot-sep" />
                    {formatBytes(stats.bytes)}
                  </span>
                )}
                {stats.speed > 0 && (
                  <span>
                    <span className="dot-sep" />
                    {speedLabel(stats.speed)}
                  </span>
                )}
                <span>
                  <span className="dot-sep" />
                  {active === 0 ? 'Paused' : `${stats.running || stats.queued} ${stats.running ? 'in progress' : 'waiting'}`}
                </span>
              </div>
            </>
          )}
          <div className={`group-counts tabular${stats.failed ? ' has-failed' : ''}`}>
            {removed === 'Deleted' ? <span className="sum-gone">Deleted</span> : <GroupSummary stats={stats} />}
            {removed && removed !== 'Deleted' && <span className="sum-gone"> · {removed}</span>}
            {!stats.live && stats.bytes > 0 && gone < stats.completed && <span> · {formatBytes(stats.bytes)}</span>}
          </div>
        </div>
        <div className="job-actions">
          {active > 0 && <IconButton icon="pause" label={`Pause all in ${group.title}`} disabled={busy} onClick={() => void act('pauseGroup', {id})} />}
          {stats.paused > 0 && <IconButton icon="play" label={`Resume all in ${group.title}`} disabled={busy} onClick={() => void act('resumeGroup', {id})} />}
          {(stats.live || stats.retrying > 0) && (
            <IconButton icon="close" label={`Cancel all in ${group.title}`} disabled={busy} onClick={() => void act('cancelGroup', {id})} />
          )}
          {stats.failed + stats.cancelled > 0 && (
            <Button
              size="sm"
              icon="retry"
              className={stats.failed ? 'retry-failed-btn' : undefined}
              disabled={busy}
              onClick={() => void act('retryGroup', {id})}
            >
              {stats.failed ? 'Retry failed' : 'Retry cancelled'}
            </Button>
          )}
          {stats.failed > 0 && (
            <Button
              size="sm"
              variant="ghost"
              icon="close"
              disabled={busy}
              onClick={() =>
                void removeFromHistory('removeFailed', {id}, removedFromHistory(stats.failed))
              }
            >
              Remove failed
            </Button>
          )}
          {libraryOn && !stats.live && stats.saved.length > 0 && playableItems(stats.saved).length > 0 && (
            <IconButton icon="play" label={`Play ${group.title}`} onClick={() => playCollection(stats.saved)} />
          )}
          {stats.openId && gone < stats.completed && (
            <IconButton icon="folder" label={`Show ${group.title} in folder`} onClick={() => void act('reveal', {id: stats.openId})} />
          )}
          <Menu label={`More actions for ${group.title}`} items={items} />
        </div>
      </div>
      {!detail && open && (
        <ul className="group-items" id={`group-items-${id}`} aria-label={`Items in ${group.title}`}>
          {sections.map((section) => (
            <Fragment key={section.key}>
              {(section.key === 'attention' || sections.length > 1) && (
                <li className={`group-sub group-sub-${section.key}`} role="presentation">
                  {section.key === 'attention' ? section.title : `${section.title} · ${section.ids.length}`}
                </li>
              )}
              {section.ids.map((jobId) => renderChild(jobId))}
            </Fragment>
          ))}
        </ul>
      )}
    </li>
  )
})

/** The card's count line as separate parts, so a line break can only fall between them: "4 saved", "1 failed", "90.0 MB". */
function countParts(stats: GroupStats, removed: string, gone: number): [string, string][] {
  const parts: [string, string][] = [[stats.live ? `${stats.completed} of ${stats.total} saved` : `${stats.completed} saved`, 'sum-saved']]
  if (stats.failed) parts.push([`${stats.failed} failed`, 'sum-failed'])
  if (stats.retrying) parts.push([`${stats.retrying} retrying`, ''])
  if (stats.paused) parts.push([`${stats.paused} paused`, ''])
  if (stats.cancelled) parts.push([`${stats.cancelled} cancelled`, ''])
  if (removed) parts.push([removed, 'sum-gone'])
  if (!stats.live && stats.bytes > 0 && gone < stats.completed) parts.push([formatBytes(stats.bytes), ''])
  return parts
}

/**
 * A finished collection as a card in the history grid, the same size and style as a single download's card:
 * cover with the item-count badge, title, kind badge, source and "N saved · size", folder and more actions.
 * Opening it (cover, body or "Show items") shows the collection in a popup; the card itself never changes.
 */
export const GroupCard = memo(function GroupCard({id, ids}: {id: string; ids?: string[]}) {
  const stats = useGroup(id)
  const gone = useGoneCount(stats?.saved ?? NO_IDS)
  const libraryOn = useLibraryEnabled()
  const [open, setOpen] = useState(false)
  const cover = useRef<HTMLButtonElement>(null)
  const close = useCallback(() => {
    setOpen(false)
    // The popup returns focus to whatever opened it; a menu item is gone by then, so the cover button takes it.
    requestAnimationFrame(() => cover.current?.focus({preventScroll: true}))
  }, [])
  const tests = window.__keepfinTest
  if (tests) tests.rowRenders[`group:${id}`] = (tests.rowRenders[`group:${id}`] || 0) + 1
  if (!stats) return null
  const {group} = stats
  const coverJob = getJob(stats.coverId)
  const source = hostOf(group.url)
  const removed = removedSummary(gone, stats.completed)
  const items: MenuItem[] = [
    {
      label: 'Show items',
      icon: 'list',
      onSelect: () => setOpen(true),
    },
    {
      label: 'Copy source link',
      icon: 'copy',
      onSelect: () => void navigator.clipboard?.writeText(group.url).catch(() => {}),
    },
    {
      label: 'Open source page',
      icon: 'external',
      onSelect: () => void act('openGroupLink', {id}),
    },
  ]
  if (stats.failed > 0)
    items.push({
      label: 'Remove failed',
      icon: 'close',
      onSelect: () => void removeFromHistory('removeFailed', {id}, removedFromHistory(stats.failed)),
    })
  if (!stats.live && !stats.retrying)
    items.push('separator', {
      label: 'Remove from history',
      icon: 'close',
      onSelect: () => void removeFromHistory('removeGroup', {id}, removedFromHistory()),
    })
  return (
    <li
      className="lib-card group-card"
      aria-label={`${group.title}, ${groupSummary(stats)}${removed ? `, ${removed}` : ''}`}
      data-group={id}
      onClick={(event) => {
        const target = event.target as HTMLElement
        if (!target.closest('button, a, input, select, dialog')) setOpen(true)
      }}
    >
      <button
        ref={cover}
        type="button"
        className="lib-card-media"
        aria-haspopup="dialog"
        aria-label={`Show ${plural(stats.total, 'item')} in ${group.title}`}
        onClick={() => setOpen(true)}
      >
        <Thumbnail jobId={stats.coverId} ready={coverJob?.status === 'completed'} kind={coverJob?.kind} lazy label={group.title} />
        <span className="group-kind" aria-hidden="true">
          {kindLabels[group.kind ?? 'collection']}
        </span>
        <span className="group-stack" aria-hidden="true">
          <Icon name="stack" size={12} />
          {stats.total}
        </span>
      </button>
      <div className="lib-card-body">
        <span className="lib-title" title={group.title}>
          {group.title}
        </span>
        <span className="lib-meta sep-list">
          <span title={group.uploader ? `${group.uploader} · ${group.url}` : group.url}>{source}</span>
        </span>
        <span className={`lib-meta group-counts sep-list tabular${stats.failed ? ' has-failed' : ''}`}>
          {countParts(stats, removed, gone).map(([text, cls]) => (
            <span key={text} className={cls || undefined}>
              {text.replace(/ /g, '\u00a0')}
            </span>
          ))}
        </span>
      </div>
      <div className="lib-card-actions">
        {libraryOn && !stats.live && playableItems(stats.saved).length > 0 && (
          <IconButton icon="play" size="sm" label={`Play ${group.title}`} onClick={() => playCollection(stats.saved)} />
        )}
        {stats.openId && gone < stats.completed && (
          <IconButton icon="folder" size="sm" label={`Show ${group.title} in folder`} onClick={() => void act('reveal', {id: stats.openId})} />
        )}
        <Menu label={`More actions for ${group.title}`} items={items} />
      </div>
      {open && <CollectionWindow id={id} ids={ids} onClose={close} />}
    </li>
  )
})
