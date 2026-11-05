import {memo, useMemo, type ReactNode} from 'react'
import type {JobStatus} from '../../shared/contracts'
import {plural, speedLabel} from '../format'
import {navigate} from '../nav'
import {getGroup, getJob, liveGroupIds, useAggregate, useIndex, useMeta, type IndexEntry} from '../store'
import {Button, EmptyState, Kbd} from '../ui/controls'
import {GroupRow} from './GroupRow'
import {JobRow} from './JobRow'
import {RemoteRows} from './RemoteRows'

const liveStatuses: JobStatus[] = ['downloading', 'preparing', 'processing', 'queued', 'paused']
const day = 86_400_000

function Throughput() {
  const aggregate = useAggregate()
  const meta = useMeta()
  return (
    <span className="section-aside tabular">
      {aggregate.transferring > 0 && <span className="speed">{speedLabel(aggregate.speed) || '0 B/s'}</span>}
      <button type="button" className="link-btn" onClick={() => navigate('Settings', 'performance')}>
        {meta.settings.concurrency} at a time
      </button>
    </span>
  )
}

function Section({title, count, aside, children}: {title: string; count: number; aside?: ReactNode; children: ReactNode}) {
  return (
    <section className="queue-section" aria-label={title}>
      <header className="section-head">
        <h2 className="section-title">
          {title} <span className="count tabular">{count}</span>
        </h2>
        {aside}
      </header>
      <ul className="jobs">{children}</ul>
    </section>
  )
}

type Item = {key: string; entry: IndexEntry; group?: string}

/** One item per standalone job, one per collection (at its first member). */
function collapse(entries: IndexEntry[]): Item[] {
  const seen = new Set<string>()
  const items: Item[] = []
  for (const entry of entries) {
    if (!entry.group) items.push({key: entry.id, entry})
    else if (!seen.has(entry.group)) {
      seen.add(entry.group)
      items.push({key: `group:${entry.group}`, entry, group: entry.group})
    }
  }
  return items
}

function group(index: IndexEntry[]) {
  // Order of active jobs follows the engine queue: running first, then waiting/paused.
  const rank: Partial<Record<JobStatus, number>> = {downloading: 0, processing: 0, preparing: 0, paused: 1, queued: 2}
  // A collection lives in Transfers while a member is running, paused or waiting for a slot. As soon as
  // nothing is moving (members that failed or wait on an automatic retry do not count), it settles.
  const liveGroups = liveGroupIds(index)
  const active = index.filter((entry) => (entry.group ? liveGroups.has(entry.group) : liveStatuses.includes(entry.status)))
  const rankOf = (entry: IndexEntry) => {
    const best = entry.group
      ? Math.min(...index.filter((other) => other.group === entry.group).map((other) => rank[other.status] ?? 3))
      : (rank[entry.status] ?? 3)
    return best
  }
  active.sort((a, b) => rankOf(a) - rankOf(b))
  const now = Date.now()
  const recentTime = (id: string) => {
    const job = getJob(id)
    return job?.completedAt || job?.createdAt || 0
  }
  const settledGroups = new Set(index.filter((entry) => entry.group && !liveGroups.has(entry.group)).map((entry) => entry.group!))
  const groupWhen = (id: string) => getGroup(id)?.when ?? 0
  const when = (entry: IndexEntry) => (entry.group ? groupWhen(entry.group) : recentTime(entry.id))
  const groupFailed = (id: string) => {
    const stats = getGroup(id)
    return !!stats && stats.failed + stats.cancelled + stats.retrying > 0
  }
  const recent = (entry: IndexEntry) => now - when(entry) < day
  const failed = index
    .filter((entry) =>
      entry.group ? settledGroups.has(entry.group) && groupFailed(entry.group) && recent(entry) : entry.status === 'failed' && recent(entry),
    )
    .sort((a, b) => when(b) - when(a))
  const saved = index
    .filter((entry) =>
      entry.group
        ? settledGroups.has(entry.group) && !groupFailed(entry.group) && entry.status === 'completed' && recent(entry)
        : entry.status === 'completed' && recent(entry),
    )
    .sort((a, b) => when(b) - when(a))
  const firstQueued = active.find((entry) => !entry.group && entry.status === 'queued')?.id
  return {active: collapse(active), failed: collapse(failed), saved: collapse(saved), firstQueued}
}

const renderJob = (id: string) => <JobRow key={id} id={id} />

function rowFor(item: Item, mode: 'queue', extra: {canMoveUp?: boolean} = {}) {
  if (item.group) return <GroupRow key={item.key} id={item.group} mode={mode} renderChild={renderJob} />
  return <JobRow key={item.key} id={item.entry.id} canMoveUp={extra.canMoveUp} />
}

export type DownloadFilter = 'all' | 'active' | 'finished' | 'failed'

export const QueueList = memo(function QueueList({filter = 'all', onFilter}: {filter?: DownloadFilter; onFilter?: (filter: DownloadFilter) => void}) {
  const index = useIndex()
  const {active, failed, saved, firstQueued} = useMemo(() => group(index), [index])
  // Group stats (counts, ordering) change with job pushes; useMemo on `index` is enough because
  // membership changes always change the index, and ordering ties are not time critical.
  const meta = useMeta()
  if (!meta.ready) return <div className="queue-loading" aria-busy="true" />
  const everything = filter === 'all'
  const nothing = !active.length && (everything ? !failed.length && !saved.length : true)
  return (
    <div className="queue">
      {everything && <RemoteRows />}
      {active.length > 0 ? (
        <Section title="Transfers" count={active.length} aside={<Throughput />}>
          {active.map((item) => rowFor(item, 'queue', {canMoveUp: item.entry.status === 'queued' && item.entry.id !== firstQueued}))}
        </Section>
      ) : (
        nothing && (
          <EmptyState
            icon="download"
            title={meta.platform === 'preview' ? 'Downloads run in the desktop app' : everything ? 'Nothing downloading' : 'Nothing is downloading right now'}
            actions={
              index.length > 0 && (
                <Button size="sm" icon="check" onClick={() => onFilter?.('finished')}>
                  Show finished · {plural(index.length, 'item')}
                </Button>
              )
            }
          >
            {meta.platform === 'preview' ? (
              'This browser preview shows the interface only. Install Keepfin for Windows to inspect links and save media locally.'
            ) : (
              <>
                Paste a link above, drop links onto the window, or press <span className="nowrap"><Kbd>Ctrl</Kbd> <Kbd>V</Kbd></span> anywhere. Transfers
                appear here with live speed and progress.
              </>
            )}
          </EmptyState>
        )
      )}
      {everything && failed.length > 0 && (
        <Section title="Needs attention" count={failed.length}>
          {failed.map((item) => rowFor(item, 'queue'))}
        </Section>
      )}
      {everything && saved.length > 0 && (
        <Section
          title="Saved today"
          count={saved.length}
          aside={
            <button type="button" className="link-btn" onClick={() => onFilter?.('finished')}>
              See all finished
            </button>
          }
        >
          {saved.slice(0, 6).map((item) => rowFor(item, 'queue'))}
        </Section>
      )}
    </div>
  )
})
