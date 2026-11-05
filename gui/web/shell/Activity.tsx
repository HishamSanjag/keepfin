import {useEffect, useMemo, useRef, useState} from 'react'
import {
  activityActions,
  filterEntries,
  groupByDay,
  isProblem,
  problemCount,
  timeLabel,
  type ActivityActionId,
  type ActivityEntry,
  type ActivityFilter,
} from '../../shared/activity'
import {closeActivity, getActivityEntries, markRead, removeEntry, toggleActivity, useActivityEntries, useActivityOpen, useUnread} from '../activity'
import {runActivityAction} from '../activityActions'
import {Icon} from '../ui/Icon'

function iconFor(entry: ActivityEntry): {name: string; tone: 'new' | 'ok' | 'bad' | 'app'} {
  switch (entry.kind) {
    case 'sync-found':
      return {name: 'refresh', tone: 'new'}
    case 'sync-review':
      return {name: 'list', tone: 'new'}
    case 'batch-done':
      return {name: 'check', tone: 'ok'}
    case 'failed':
      return {name: 'warning', tone: 'bad'}
    case 'sign-in':
      return {name: 'key', tone: 'bad'}
    case 'app-updated':
    case 'update-ready':
      return {name: 'download', tone: 'app'}
  }
}

/** The bell in the app bar and the Activity panel it opens. */
export function Activity() {
  const open = useActivityOpen()
  const unread = useUnread()
  const root = useRef<HTMLDivElement>(null)
  const label = unread > 0 ? `Activity, ${unread} unread` : 'Activity'

  useEffect(() => {
    if (!open) return
    const outside = (event: MouseEvent) => {
      if (!root.current?.contains(event.target as Node)) closeActivity()
    }
    const key = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      closeActivity()
      root.current?.querySelector<HTMLButtonElement>('.act-bell')?.focus()
    }
    window.addEventListener('mousedown', outside)
    window.addEventListener('keydown', key)
    return () => {
      window.removeEventListener('mousedown', outside)
      window.removeEventListener('keydown', key)
    }
  }, [open])

  return (
    <div className="act" ref={root}>
      <button
        type="button"
        className={`icon-btn act-bell${open ? ' is-open' : ''}`}
        aria-label={label}
        title={label}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls="activity-panel"
        onClick={toggleActivity}
      >
        <Icon name="bell" size={18} />
        {unread > 0 && (
          <span className="act-count tabular" aria-hidden="true">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>
      {open && <ActivityPanel />}
    </div>
  )
}

function ActivityPanel() {
  const entries = useActivityEntries()
  const panel = useRef<HTMLDivElement>(null)
  const [filter, setFilter] = useState<ActivityFilter>('all')
  // Opening marks everything shown as read, but the dots stay for this visit so you can see what was new.
  const [fresh, setFresh] = useState<Set<string>>(() => new Set(entries.filter((entry) => !entry.read).map((entry) => entry.id)))
  const now = Date.now()

  useEffect(() => {
    panel.current?.focus({preventScroll: true})
    if (getActivityEntries().some((entry) => !entry.read)) void markRead('all')
  }, [])
  useEffect(() => {
    const arrived = entries.filter((entry) => !entry.read && !fresh.has(entry.id))
    if (!arrived.length) return
    setFresh((current) => new Set([...current, ...arrived.map((entry) => entry.id)]))
    void markRead(arrived.map((entry) => entry.id))
  }, [entries, fresh])

  const freshCount = entries.filter((entry) => fresh.has(entry.id)).length
  const problems = problemCount(entries)
  const days = useMemo(() => {
    const list = filter === 'unread' ? entries.filter((entry) => fresh.has(entry.id)) : filterEntries(entries, filter)
    return groupByDay(list, Date.now())
  }, [entries, filter, fresh])

  // Using an entry clears its dot at once, not only on Mark all read.
  const seen = (id: string) =>
    setFresh((current) => {
      if (!current.has(id)) return current
      const next = new Set(current)
      next.delete(id)
      return next
    })
  const run = async (entry: ActivityEntry, action: ActivityActionId) => {
    seen(entry.id)
    if (await runActivityAction(entry, action)) closeActivity()
  }
  const chips: [ActivityFilter, string, number][] = [
    ['all', 'All', 0],
    ['unread', 'Unread', freshCount],
    ['problems', 'Problems', problems],
  ]

  return (
    <div id="activity-panel" className="act-panel" role="dialog" aria-label="Activity" tabIndex={-1} ref={panel}>
      <div className="act-head">
        <strong>Activity</strong>
        <button
          type="button"
          className="link-btn"
          disabled={!entries.length}
          onClick={() => {
            setFresh(new Set())
            void markRead('all')
          }}
        >
          Mark all read
        </button>
      </div>
      <div className="act-chips" role="group" aria-label="Filter activity">
        {chips.map(([value, name, count]) => (
          <button key={value} type="button" className={`act-chip${filter === value ? ' is-on' : ''}`} aria-pressed={filter === value} onClick={() => setFilter(value)}>
            {name}
            {count > 0 && <span className="tabular"> {count}</span>}
          </button>
        ))}
      </div>
      <div className="act-list">
        {!days.length ? (
          <p className="act-empty">
            {entries.length && filter === 'unread'
              ? 'Nothing unread.'
              : entries.length && filter === 'problems'
                ? 'No problems.'
                : 'Nothing yet. Downloads, synced playlists and updates show up here.'}
          </p>
        ) : (
          days.map((day) => (
            <section key={day.label} aria-label={day.label}>
              <h2 className="act-day">{day.label}</h2>
              <ul>
                {day.entries.map((entry) => (
                  <Row
                    key={entry.id}
                    entry={entry}
                    isNew={fresh.has(entry.id)}
                    now={now}
                    run={run}
                    remove={() => {
                      void removeEntry(entry.id)
                      panel.current?.focus({preventScroll: true})
                    }}
                  />
                ))}
              </ul>
            </section>
          ))
        )}
      </div>
    </div>
  )
}

function Row({
  entry,
  isNew,
  now,
  run,
  remove,
}: {
  entry: ActivityEntry
  isNew: boolean
  now: number
  run: (entry: ActivityEntry, action: ActivityActionId) => void
  remove: () => void
}) {
  const icon = iconFor(entry)
  const actions = activityActions(entry)
  return (
    <li className={`act-row${isNew ? ' is-new' : ''}${isProblem(entry) ? ' is-problem' : ''}`}>
      <span className={`act-ico act-ico-${icon.tone}`} aria-hidden="true">
        <Icon name={icon.name} size={16} />
      </span>
      <div className="act-text">
        <strong title={entry.title}>
          {isNew && <span className="sr-only">Unread. </span>}
          {entry.title}
        </strong>
        {entry.detail && <span className="act-detail">{entry.detail}</span>}
        {actions.length > 0 && (
          <span className="act-actions">
            {actions.map((action) => (
              <button key={action.id} type="button" className="act-action" onClick={() => run(entry, action.id)}>
                {action.label}
              </button>
            ))}
          </span>
        )}
      </div>
      <time className="act-time tabular" dateTime={new Date(entry.at).toISOString()}>
        {timeLabel(entry.at, now)}
      </time>
      <button type="button" className="icon-btn icon-btn-sm act-remove" aria-label={`Remove: ${entry.title}`} onClick={remove}>
        <Icon name="close" size={13} />
      </button>
    </li>
  )
}
