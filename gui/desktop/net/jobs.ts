// Download on... (spec 6 and 9.1 #6): a paired device asks this one to download a link. Two halves.
//
// The receiver (`JobsHost`) treats the request as untrusted input. It checks the link with the core's URL policy (the guard resolves every
// name and every redirect hop), applies the same capture rules as for the person's own downloads (format, options, an allowed space),
// the storage and queue limits, and keeps the request keyed by peer plus requestId, so a retry or reconnect returns the existing job
// and never makes a second one; the same id with a different payload is refused. The record is written (atomically) before the job is
// made and kept for 30 days. Progress goes back to the asking device as `{t:'job'}` notices over its event stream.
//
// The sender (`OutgoingJobs`) writes each request to disk before it is sent (the requestId is persisted first), so a crash or a lost
// connection resends the same id. Its jobs are shown as remote rows, not in the local queue.
import {createHash, randomUUID} from 'node:crypto'
import {readJson, writeJsonAtomic} from '../store/atomic.js'
import type {RemoteJobView} from '../manager.js'
import {guardUrl, UrlRefused} from './guard.js'

export const JOB_RETENTION_MS = 30 * 24 * 60 * 60 * 1000
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const TERMINAL = new Set(['completed', 'failed', 'cancelled', 'refused'])
export const isTerminalJob = (state: string) => TERMINAL.has(state)

// ---- receiver ---------------------------------------------------------------------------------------------------------------

/** What the queue offers the receiver; the app wires the real download queue to it. */
export interface JobPort {
  spaces(): {id: string; name: string}[]
  /** Makes the job under [jobId] (the same id again changes nothing). Throws with words when the capture rules refuse it. */
  create(input: {jobId: string; url: string; format: unknown; options?: unknown; space?: string; musicVideo?: boolean}): Promise<void>
  view(jobId: string): RemoteJobView | undefined
  /** Called when anything about the queue changed (throttled by the queue). */
  subscribe(listener: () => void): () => void
  /** Free bytes where downloads are saved, or undefined when unknown. */
  freeBytes(): Promise<number | undefined>
}

interface Entry {
  peerId: string
  peerName: string
  requestId: string
  jobId: string
  hash: string
  createdAt: number
  /** The reason, when the request was refused for what it asks (a policy refusal never changes by asking again). */
  refused?: {error: string; message: string}
}

type File = {v: 1; entries: Entry[]}

export interface JobsHostOptions {
  file: string
  port: JobPort
  now?: () => number
  /** Tests replace the link check; the app uses the guard. */
  checkUrl?: (url: string) => Promise<string>
  notify: (peerId: string, message: Record<string, unknown>) => void
  /** Requests waiting or running for one device at most. */
  perPeerActive?: number
  /** Do not accept a job when less than this is free. */
  minFreeBytes?: number
}

export interface JobAnswer {
  status: number
  body: Record<string, unknown>
}

const answer = (status: number, body: Record<string, unknown>): JobAnswer => ({status, body})

/** The identity of a request's payload: the same fields in the same order give the same hash. */
export function payloadHash(p: {url: string; format: unknown; options?: unknown; space?: string; musicVideo?: boolean}): string {
  const canon = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(canon)
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value as Record<string, unknown>).filter(([, v]) => v !== undefined).sort(([a], [b]) => (a < b ? -1 : 1)).map(([k, v]) => [k, canon(v)]))
    return value
  }
  return createHash('sha256').update(JSON.stringify(canon({url: p.url, format: p.format, options: p.options ?? null, space: p.space ?? null, ...(p.musicVideo ? {musicVideo: true} : {})}))).digest('hex')
}

/** Exact format ids belong to the device that inspected the link; the receiver chooses by height, codec and container. */
function portable(format: unknown): unknown {
  if (!format || typeof format !== 'object') return format
  const {formatId: _a, audioFormatId: _b, ...rest} = format as Record<string, unknown>
  return rest
}

export class JobsHost {
  private entries = new Map<string, Entry>()
  private byJob = new Map<string, Entry>()
  private tail: Promise<unknown> = Promise.resolve()
  private lastSent = new Map<string, string>()
  private off?: () => void
  private readonly now: () => number

  private constructor(private readonly options: JobsHostOptions) {
    this.now = options.now ?? Date.now
  }

  static async open(options: JobsHostOptions): Promise<JobsHost> {
    const host = new JobsHost(options)
    const saved = await readJson<File>(options.file).catch(() => undefined)
    for (const entry of saved?.v === 1 && Array.isArray(saved.entries) ? saved.entries : []) {
      if (typeof entry?.peerId === 'string' && typeof entry.requestId === 'string' && typeof entry.jobId === 'string') host.add(entry)
    }
    host.off = options.port.subscribe(() => host.push())
    return host
  }

  close(): void {
    this.off?.()
  }

  private add(entry: Entry): void {
    this.entries.set(`${entry.peerId}:${entry.requestId}`, entry)
    this.byJob.set(entry.jobId, entry)
  }

  /** The device that asked for this job (the queue marks such jobs and checks their links). */
  originOf(jobId: string): {device: string} | undefined {
    const entry = this.byJob.get(jobId)
    return entry && !entry.refused ? {device: entry.peerName} : undefined
  }

  get size(): number {
    return this.entries.size
  }

  private save(): Promise<void> {
    const cutoff = this.now() - JOB_RETENTION_MS
    for (const [key, entry] of this.entries) {
      if (entry.createdAt < cutoff && (entry.refused || !this.options.port.view(entry.jobId) || isTerminalJob(this.options.port.view(entry.jobId)!.state))) {
        this.entries.delete(key)
        this.byJob.delete(entry.jobId)
        this.lastSent.delete(key)
      }
    }
    const run = this.tail.then(() => writeJsonAtomic(this.options.file, {v: 1, entries: [...this.entries.values()]} satisfies File))
    this.tail = run.catch(() => undefined)
    return run
  }

  private active(peerId: string): number {
    let n = 0
    for (const entry of this.entries.values()) {
      if (entry.peerId !== peerId || entry.refused) continue
      const view = this.options.port.view(entry.jobId)
      if (!view || !isTerminalJob(view.state)) n++
    }
    return n
  }

  async handle(caller: {deviceId: string; name: string}, body: Record<string, unknown>): Promise<JobAnswer> {
    const requestId = typeof body.requestId === 'string' ? body.requestId.toLowerCase() : ''
    if (!UUID.test(requestId)) return answer(400, {error: 'request-id', message: 'The request needs an id.'})
    const url = typeof body.url === 'string' ? body.url.trim() : ''
    if (!url || url.length > 4096) return answer(400, {error: 'url', message: 'The request needs a link.'})
    if (!body.format || typeof body.format !== 'object' || Array.isArray(body.format)) return answer(400, {error: 'format', message: 'The request needs a format.'})
    if (body.options !== undefined && (typeof body.options !== 'object' || body.options === null || Array.isArray(body.options))) return answer(400, {error: 'options', message: 'The options are not valid.'})
    if (body.space !== undefined && (typeof body.space !== 'string' || body.space.length > 100)) return answer(400, {error: 'space', message: 'The space is not valid.'})
    // optional: an older device never sends it, and only `true` asks for the song's music video (anything else means no video)
    const musicVideo = body.musicVideo === true
    const format = portable(body.format)
    const space = body.space as string | undefined
    const hash = payloadHash({url, format, options: body.options, space, musicVideo})
    const key = `${caller.deviceId}:${requestId}`

    const known = this.entries.get(key)
    if (known) {
      if (known.hash !== hash) return answer(409, {error: 'payload-changed', message: 'This request id was already used for a different download.'})
      if (known.refused) return answer(422, {error: known.refused.error, message: known.refused.message, requestId})
      // an earlier answer may have been lost, or the record was written and the job was not made: make it, the same id changes nothing
      if (!this.options.port.view(known.jobId)) {
        try {
          await this.options.port.create({jobId: known.jobId, url, format, options: body.options, space, musicVideo})
        } catch (error) {
          return this.refuse(known, error instanceof Error ? error.message : 'The download was not accepted.', 'refused')
        }
      }
      return answer(200, {ok: true, requestId, view: this.options.port.view(known.jobId) ?? null})
    }

    if (this.active(caller.deviceId) >= (this.options.perPeerActive ?? 500)) return answer(429, {error: 'queue-full', message: 'This device already has many downloads from you waiting. Try again when some have finished.'})
    const spaces = this.options.port.spaces()
    if (space !== undefined && !spaces.some((s) => s.id === space)) return this.record(caller, requestId, hash, {error: 'space', message: 'That space is not available on this device.'})
    let checked: string
    try {
      checked = await (this.options.checkUrl ?? guardUrl)(url)
    } catch (error) {
      if (error instanceof UrlRefused) return this.record(caller, requestId, hash, {error: 'url', message: error.message})
      throw error
    }
    void checked
    const free = await this.options.port.freeBytes().catch(() => undefined)
    if (free !== undefined && free < (this.options.minFreeBytes ?? 512 * 1024 * 1024)) return answer(507, {error: 'storage', message: 'There is not enough room on this device.'})

    const entry: Entry = {peerId: caller.deviceId, peerName: caller.name, requestId, jobId: randomUUID(), hash, createdAt: this.now()}
    this.add(entry)
    await this.save() // the record exists before the job does
    try {
      await this.options.port.create({jobId: entry.jobId, url, format, options: body.options, space, musicVideo})
    } catch (error) {
      return this.refuse(entry, error instanceof Error ? error.message : 'The download was not accepted.', 'refused')
    }
    return answer(201, {ok: true, requestId, view: this.options.port.view(entry.jobId) ?? null})
  }

  /** A request refused for what it asks: remembered, so asking again gets the same answer. */
  private async record(caller: {deviceId: string; name: string}, requestId: string, hash: string, refused: {error: string; message: string}): Promise<JobAnswer> {
    const entry: Entry = {peerId: caller.deviceId, peerName: caller.name, requestId, jobId: randomUUID(), hash, createdAt: this.now(), refused}
    this.add(entry)
    await this.save()
    return answer(422, {...refused, requestId})
  }

  private async refuse(entry: Entry, message: string, error: string): Promise<JobAnswer> {
    entry.refused = {error, message}
    await this.save()
    return answer(422, {error, message, requestId: entry.requestId})
  }

  /** The queue changed: tell each asking device what is new about its jobs (only what changed). */
  private push(): void {
    for (const [key, entry] of this.entries) {
      if (entry.refused) continue
      const view = this.options.port.view(entry.jobId)
      if (!view) continue
      const text = JSON.stringify({...view, speed: undefined, eta: undefined, percent: Math.round(view.percent)})
      if (this.lastSent.get(key) === text) continue
      this.lastSent.set(key, text)
      this.options.notify(entry.peerId, {t: 'job', requestId: entry.requestId, view})
    }
  }

  /** A device connected (again): its events from before the connection are gone, so everything is sent once. */
  resend(peerId: string): void {
    for (const [key, entry] of this.entries) {
      if (entry.peerId !== peerId) continue
      this.lastSent.delete(key)
    }
    this.push()
  }
}

// ---- sender -----------------------------------------------------------------------------------------------------------------

export type OutgoingState = 'sending' | 'refused' | RemoteJobView['state']

export interface OutgoingJob {
  requestId: string
  deviceId: string
  deviceName: string
  url: string
  format: unknown
  options?: unknown
  space?: string
  /** Also keep the song's music video on the other device (sent only when true). */
  musicVideo?: boolean
  createdAt: number
  updatedAt: number
  state: OutgoingState
  percent: number
  title?: string
  label?: string
  speed?: number
  eta?: number
  totalBytes?: number
  error?: string
}

type OutFile = {v: 1; jobs: OutgoingJob[]}

export class OutgoingJobs {
  private jobs = new Map<string, OutgoingJob>()
  private tail: Promise<unknown> = Promise.resolve()

  private constructor(
    private readonly file: string,
    private readonly now: () => number,
  ) {}

  static async open(file: string, now: () => number = Date.now): Promise<OutgoingJobs> {
    const store = new OutgoingJobs(file, now)
    const saved = await readJson<OutFile>(file).catch(() => undefined)
    for (const job of saved?.v === 1 && Array.isArray(saved.jobs) ? saved.jobs : []) if (typeof job?.requestId === 'string') store.jobs.set(job.requestId, job)
    return store
  }

  list(): OutgoingJob[] {
    return [...this.jobs.values()].sort((a, b) => b.createdAt - a.createdAt)
  }

  get(requestId: string): OutgoingJob | undefined {
    return this.jobs.get(requestId)
  }

  /** Waiting to be delivered or still running on the other device. */
  openFor(deviceId: string): OutgoingJob[] {
    return this.list().filter((j) => j.deviceId === deviceId && !isTerminalJob(j.state))
  }

  /** Written to disk before it is sent. */
  async create(input: {deviceId: string; deviceName: string; url: string; format: unknown; options?: unknown; space?: string; musicVideo?: boolean}): Promise<OutgoingJob> {
    const job: OutgoingJob = {...input, requestId: randomUUID(), createdAt: this.now(), updatedAt: this.now(), state: 'sending', percent: 0}
    this.jobs.set(job.requestId, job)
    await this.save()
    return job
  }

  async update(requestId: string, patch: Partial<OutgoingJob>): Promise<void> {
    const job = this.jobs.get(requestId)
    if (!job || isTerminalJob(job.state)) return
    Object.assign(job, patch, {updatedAt: this.now()})
    await this.save()
  }

  /** Applies what the other device said about the job. */
  async applyView(requestId: string, view: RemoteJobView): Promise<void> {
    await this.update(requestId, {state: view.state, percent: view.percent, title: view.title, label: view.label, speed: view.speed, eta: view.eta, totalBytes: view.totalBytes, error: view.error})
  }

  async dismiss(requestId: string): Promise<void> {
    const job = this.jobs.get(requestId)
    if (!job || !isTerminalJob(job.state)) return
    this.jobs.delete(requestId)
    await this.save()
  }

  private save(): Promise<void> {
    const cutoff = this.now() - JOB_RETENTION_MS
    for (const [id, job] of this.jobs) if (job.updatedAt < cutoff && isTerminalJob(job.state)) this.jobs.delete(id)
    const run = this.tail.then(() => writeJsonAtomic(this.file, {v: 1, jobs: [...this.jobs.values()]} satisfies OutFile))
    this.tail = run.catch(() => undefined)
    return run
  }
}
