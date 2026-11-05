import fs from 'node:fs/promises'
import path from 'node:path'
import {
  isActive,
  type Job,
  type JobGroup,
  type JobProgress,
  type Snapshot,
} from '../shared/contracts.js'
import {
  notificationPreferences,
  type NotificationState,
} from '../shared/notifications.js'

export type NotificationPayload = {
  id: string
  title: string
  body: string
  silent: boolean
  job?: Job
  /** Set for a finished collection; clicking focuses it in Library. */
  group?: JobGroup
  /** Where a click should land when no single job decides it. */
  tab?: 'Downloads' | 'Library'
}

const settled = ['completed', 'failed', 'cancelled'] as const
type Pending = {job: Job; kind: 'completed' | 'failed'}
/** Owns notification transitions outside the renderer, including minimized windows. */
export class DesktopNotifications {
  private state: NotificationState
  private previous = new Map<string, Job['status']>()
  private attempts = new Map<string, number>()
  private current: Snapshot
  private writes = Promise.resolve()
  private pending: Pending[] = []
  private batchTimer?: NodeJS.Timeout
  private groupsPending = new Set<string>()
  constructor(
    private options: {
      store: string
      initial: Snapshot
      supported: boolean
      show: (
        payload: NotificationPayload,
        fail: (message: string) => void,
      ) => void
      progress: (value: number, indeterminate: boolean) => void
      /** Separate downloads finishing within this window (ms) become one summary. */
      batchMs?: number
    },
  ) {
    this.current = options.initial
    this.state = {
      preferences: notificationPreferences(null),
      supported: options.supported,
    }
    for (const job of options.initial.jobs) {
      this.previous.set(job.id, job.status)
      this.attempts.set(job.id, job.attempt ?? 0)
    }
  }
  async init() {
    try {
      this.state.preferences = notificationPreferences(
        JSON.parse(await fs.readFile(this.options.store, 'utf8')),
      )
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT')
        this.state.lastError =
          'Could not load notification preferences. Defaults are in use.'
    }
    this.updateProgress()
  }
  snapshot(): NotificationState {
    return structuredClone(this.state)
  }
  async configure(value: unknown) {
    const preferences = notificationPreferences(value)
    const write = this.writes.then(async () => {
      await fs.mkdir(path.dirname(this.options.store), {recursive: true})
      await fs.writeFile(
        this.options.store + '.tmp',
        JSON.stringify(preferences),
      )
      await fs.rename(this.options.store + '.tmp', this.options.store)
      this.state.preferences = preferences
      this.updateProgress()
    })
    this.writes = write.catch(() => {})
    await write
    return this.snapshot()
  }
  consume(snapshot: Snapshot) {
    this.current = snapshot
    const p = this.state.preferences
    for (const job of snapshot.jobs) {
      const prior = this.previous.get(job.id)
      // Snapshots are coalesced, so a fast job can first appear already
      // finished, or retry and finish between two snapshots. Both still
      // count as a fresh run that ended.
      const freshRun =
        prior === undefined ||
        (job.attempt ?? 0) > (this.attempts.get(job.id) ?? 0)
      if (
        (freshRun || (prior && isActive(prior) && prior !== job.status)) &&
        (freshRun ? !isActive(job.status) : true) &&
        p.enabled &&
        this.state.supported &&
        (job.status === 'completed' || job.status === 'failed')
      ) {
        // Failures only reach here after the last automatic retry (the job stays queued until then).
        if (job.group) this.groupsPending.add(job.group.id)
        else {
          // a download that finished but could not be saved into the library needs attention like a failed one
          const kind = job.status === 'completed' && !job.saveError ? 'completed' : 'failed'
          if ((kind === 'completed' && p.completed) || (kind === 'failed' && p.failed)) this.pending.push({job: structuredClone(job), kind})
        }
      }
      this.previous.set(job.id, job.status)
      this.attempts.set(job.id, job.attempt ?? 0)
    }
    const ids = new Set(snapshot.jobs.map((job) => job.id))
    for (const id of this.previous.keys())
      if (!ids.has(id)) {
        this.previous.delete(id)
        this.attempts.delete(id)
      }
    this.settleGroups(snapshot)
    this.scheduleFlush()
    this.updateProgress()
  }
  /** One notification per collection, once every item has finished. */
  private settleGroups(snapshot: Snapshot) {
    const p = this.state.preferences
    for (const id of [...this.groupsPending]) {
      const members = snapshot.jobs.filter((job) => job.group?.id === id)
      if (!members.length) {
        this.groupsPending.delete(id)
        continue
      }
      if (!members.every((job) => (settled as readonly string[]).includes(job.status))) continue
      this.groupsPending.delete(id)
      const saved = members.filter((job) => job.status === 'completed' && !job.saveError).length
      const failed = members.filter((job) => job.status === 'failed' || job.saveError).length
      if (!((saved && p.completed) || (failed && p.failed))) continue
      const group = members[0].group!
      const counts = `${saved} of ${members.length} saved${failed ? `, ${failed} failed` : ''}`
      this.deliver({
        id: `group-${id}`,
        title: saved ? `${group.title.slice(0, 120)} downloaded` : `${group.title.slice(0, 120)} needs attention`,
        body: `${counts}\n${saved ? 'Click to open it in Library.' : 'Open Keepfin to review and retry.'}`,
        silent: !p.sound,
        group: structuredClone(group),
        tab: 'Library',
      })
    }
  }
  private scheduleFlush() {
    if (!this.pending.length) return
    const wait = this.options.batchMs ?? 0
    if (wait <= 0) return this.flush()
    if (this.batchTimer) return
    this.batchTimer = setTimeout(() => this.flush(), wait)
    this.batchTimer.unref?.()
  }
  /** Delivers queued single-download alerts: one alert, or one summary for several. */
  flush() {
    clearTimeout(this.batchTimer)
    this.batchTimer = undefined
    const items = this.pending.splice(0)
    if (!items.length) return
    const p = this.state.preferences
    if (items.length === 1) {
      const {job, kind} = items[0]
      return this.deliver({
        id: `download-${job.id}`,
        title: job.existing ? 'Already in your library' : kind === 'completed' ? 'Download saved' : job.saveError ? 'Download not saved' : 'Download needs attention',
        body: `${job.title.slice(0, 160)}\n${kind === 'completed' ? 'Click to show the saved file.' : job.saveError ? 'Open Keepfin to save it again.' : 'Open Keepfin to review and retry.'}`,
        silent: !p.sound,
        job,
      })
    }
    const saved = items.filter((item) => item.kind === 'completed').length
    const failed = items.length - saved
    this.deliver({
      id: `batch-${Date.now()}`,
      title: failed && !saved ? `${failed} downloads need attention` : `${items.length} downloads finished`,
      body: failed && saved ? `${saved} saved, ${failed} failed\nClick to open Library.` : failed ? 'Open Keepfin to review and retry.' : 'Click to open Library.',
      silent: !p.sound,
      tab: failed && !saved ? 'Downloads' : 'Library',
    })
  }
  /** High-frequency ticks only move the taskbar bar; they never trigger alerts. */
  consumeProgress(progress: JobProgress[]) {
    const byId = new Map(this.current.jobs.map((job) => [job.id, job]))
    for (const item of progress) {
      const job = byId.get(item.id)
      if (job) Object.assign(job, item)
    }
    this.updateProgress()
  }
  test() {
    if (!this.state.preferences.enabled)
      throw new Error('Turn on notifications before sending a test.')
    if (!this.state.supported)
      throw new Error('System notifications are unavailable on this device.')
    this.state.lastError = undefined
    this.deliver({
      id: 'keepfin-notification-test',
      title: 'Keepfin',
      body: 'Notifications are connected. Windows notification settings and Do not disturb can silence alerts.',
      silent: !this.state.preferences.sound,
    })
    return this.snapshot()
  }
  private deliver(payload: NotificationPayload) {
    try {
      this.options.show(payload, (message) => {
        this.state.lastError = message
      })
    } catch {
      this.state.lastError =
        'The operating system could not deliver this notification. Check system notification settings.'
    }
  }
  private updateProgress() {
    if (!this.state.preferences.enabled || !this.state.preferences.progress)
      return this.options.progress(-1, false)
    const active = this.current.jobs.filter((job) => isActive(job.status))
    if (!active.length) return this.options.progress(-1, false)
    const working = active.filter((job) => job.status !== 'queued')
    const transferring = working.filter(
      (job) => job.status === 'downloading' && job.percent > 0,
    )
    if (!transferring.length) return this.options.progress(2, true)
    const mean =
      transferring.reduce((sum, job) => sum + job.percent, 0) /
      transferring.length
    this.options.progress(Math.max(0, Math.min(1, mean / 100)), false)
  }
}
