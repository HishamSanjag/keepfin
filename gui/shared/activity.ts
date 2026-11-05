// Activity centre: the pure rules shared by the desktop store and the renderer (dev-docs/architecture/ACTIVITY.md).
// Entries are display text built where the event happens; actions are derived from kind + target here.

export type ActivityKind = 'sync-found' | 'sync-review' | 'batch-done' | 'failed' | 'app-updated' | 'update-ready' | 'sign-in'

export const activityKinds: readonly ActivityKind[] = ['sync-found', 'sync-review', 'batch-done', 'failed', 'app-updated', 'update-ready', 'sign-in']

export type ActivityTarget = {
  collectionId?: string
  jobIds?: string[]
  groupId?: string
  version?: string
  provider?: string
}

export type ActivityEntry = {
  id: string
  kind: ActivityKind
  /** Epoch ms. */
  at: number
  title: string
  detail?: string
  read: boolean
  target?: ActivityTarget
  count?: number
}

/** What the renderer receives: the entries newest first, plus the one-time "while you were away" count. */
export type ActivityList = {entries: ActivityEntry[]; away: number}

export const ACTIVITY_MAX_ENTRIES = 200
export const ACTIVITY_MAX_AGE_MS = 30 * 86_400_000

export type ActivityFilter = 'all' | 'unread' | 'problems'

export type ActivityActionId = 'open' | 'review' | 'retry' | 'details' | 'whats-new' | 'install' | 'sign-in'
export type ActivityAction = {id: ActivityActionId; label: string}

const isKind = (value: unknown): value is ActivityKind => (activityKinds as readonly unknown[]).includes(value)
const text = (value: unknown, max: number) => (typeof value === 'string' && value.trim() ? value.trim().slice(0, max) : undefined)
const ids = (value: unknown, limit = 200) =>
  Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string' && item.length > 0 && item.length <= 200).slice(0, limit) : undefined

/** The noun for a count: "songs" for music collections, "items" otherwise. */
export function noun(count: number, music: boolean): string {
  return `${music ? 'song' : 'item'}${count === 1 ? '' : 's'}`
}

export const isProblem = (entry: Pick<ActivityEntry, 'kind'>) => entry.kind === 'failed' || entry.kind === 'sign-in'

export const unreadCount = (entries: readonly ActivityEntry[]) => entries.filter((entry) => !entry.read).length
export const problemCount = (entries: readonly ActivityEntry[]) => entries.filter(isProblem).length

export function filterEntries(entries: readonly ActivityEntry[], filter: ActivityFilter): ActivityEntry[] {
  if (filter === 'unread') return entries.filter((entry) => !entry.read)
  if (filter === 'problems') return entries.filter(isProblem)
  return [...entries]
}

/** Keeps the newest 200 entries and nothing older than 30 days, newest first. */
export function pruneActivity(entries: readonly ActivityEntry[], now: number): ActivityEntry[] {
  return entries
    .filter((entry) => now - entry.at <= ACTIVITY_MAX_AGE_MS)
    .sort((a, b) => b.at - a.at)
    .slice(0, ACTIVITY_MAX_ENTRIES)
}

/** Validates one stored entry (the file is user-writable); anything malformed is dropped. */
export function sanitizeEntry(raw: unknown): ActivityEntry | undefined {
  if (!raw || typeof raw !== 'object') return
  const value = raw as Record<string, unknown>
  const id = text(value.id, 100)
  const title = text(value.title, 300)
  if (!id || !title || !isKind(value.kind) || typeof value.at !== 'number' || !Number.isFinite(value.at)) return
  const entry: ActivityEntry = {id, kind: value.kind, at: value.at, title, read: value.read === true}
  const detail = text(value.detail, 400)
  if (detail) entry.detail = detail
  if (typeof value.count === 'number' && Number.isFinite(value.count)) entry.count = Math.max(0, Math.floor(value.count))
  if (value.target && typeof value.target === 'object') {
    const t = value.target as Record<string, unknown>
    const target: ActivityTarget = {}
    const collectionId = text(t.collectionId, 200)
    const groupId = text(t.groupId, 200)
    const version = text(t.version, 100)
    const provider = text(t.provider, 100)
    const jobIds = ids(t.jobIds)
    if (collectionId) target.collectionId = collectionId
    if (groupId) target.groupId = groupId
    if (version) target.version = version
    if (provider) target.provider = provider
    if (jobIds?.length) target.jobIds = jobIds
    if (Object.keys(target).length) entry.target = target
  }
  return entry
}

const weekdays = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const startOfDay = (time: number) => {
  const date = new Date(time)
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

/** Today, Yesterday, a weekday name for the rest of the last week, then a date such as "3 Oct". */
export function dayLabel(at: number, now: number): string {
  const days = Math.round((startOfDay(now) - startOfDay(at)) / 86_400_000)
  if (days <= 0) return 'Today'
  if (days === 1) return 'Yesterday'
  const date = new Date(at)
  if (days < 7) return weekdays[date.getDay()]
  const label = `${date.getDate()} ${months[date.getMonth()]}`
  return date.getFullYear() === new Date(now).getFullYear() ? label : `${label} ${date.getFullYear()}`
}

export type ActivityDay = {label: string; entries: ActivityEntry[]}

/** Groups newest-first entries under their day labels, keeping order. */
export function groupByDay(entries: readonly ActivityEntry[], now: number): ActivityDay[] {
  const days: ActivityDay[] = []
  for (const entry of [...entries].sort((a, b) => b.at - a.at)) {
    const label = dayLabel(entry.at, now)
    const last = days.at(-1)
    if (last?.label === label) last.entries.push(entry)
    else days.push({label, entries: [entry]})
  }
  return days
}

/** "now", "5 min", then the clock time; older entries sit under their day heading. */
export function timeLabel(at: number, now: number): string {
  const minutes = Math.floor((now - at) / 60_000)
  if (minutes < 1) return 'now'
  if (minutes < 60) return `${minutes} min`
  const date = new Date(at)
  const hours = date.getHours()
  const clock = `${hours % 12 || 12}:${String(date.getMinutes()).padStart(2, '0')} ${hours < 12 ? 'AM' : 'PM'}`
  return clock
}

/** The actions an entry offers, derived from its kind and what it points at. */
export function activityActions(entry: Pick<ActivityEntry, 'kind' | 'target'>): ActivityAction[] {
  const target = entry.target ?? {}
  switch (entry.kind) {
    case 'sync-found':
      return target.collectionId ? [{id: 'open', label: 'Open'}] : []
    case 'sync-review':
      return target.collectionId ? [{id: 'review', label: 'Review'}] : []
    case 'batch-done':
      return target.collectionId || target.groupId || target.jobIds?.length ? [{id: 'open', label: 'Open'}] : []
    case 'failed':
      return target.groupId || target.jobIds?.length
        ? [
            {id: 'retry', label: 'Retry'},
            {id: 'details', label: 'Details'},
          ]
        : []
    case 'app-updated':
      return [{id: 'whats-new', label: 'What’s new'}]
    case 'update-ready':
      return [{id: 'install', label: 'Install'}]
    case 'sign-in':
      return [{id: 'sign-in', label: 'Sign in'}]
  }
}

/** Unread entries created after the app was last closed: the "N things happened while you were away" count. */
export function awayCount(entries: readonly ActivityEntry[], closedAt: number | undefined): number {
  if (closedAt === undefined) return 0
  return entries.filter((entry) => !entry.read && entry.at > closedAt).length
}

export const awayMessage = (count: number) => `${count} ${count === 1 ? 'thing' : 'things'} happened while you were away`

// ---------------------------------------------------------------------------
// sync-found: one entry per collection while its batch runs, then it settles in place.
// ---------------------------------------------------------------------------

export function syncFoundText(name: string, total: number, music: boolean, space?: string): {title: string; detail: string} {
  return {
    title: `${name} got ${total} new ${noun(total, music)}`,
    detail: space ? `Downloading to ${space}` : 'Downloading now',
  }
}

/** Another check queued more while the batch still runs: the same entry grows. */
export function mergeSyncFound(entry: ActivityEntry, added: number, name: string, music: boolean, space: string | undefined, now: number): ActivityEntry {
  const total = (entry.count ?? 0) + added
  return {...entry, ...syncFoundText(name, total, music, space), count: total, at: now, read: false}
}

/** The batch finished: "All 3 saved" or "2 saved, 1 failed". */
export function settleSyncFound(entry: ActivityEntry, saved: number, failed: number): ActivityEntry {
  const total = saved + failed
  return {...entry, detail: failed ? `${saved} saved, ${failed} failed` : total === 1 ? 'Saved' : `All ${total} saved`}
}

/** "24 of 24 songs saved to Music", used by batch-done. */
export function batchDoneDetail(saved: number, total: number, music: boolean, space?: string): string {
  const base = `${saved} of ${total} ${noun(total, music)} saved`
  return space ? `${base} to ${space}` : base
}
