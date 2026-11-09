// Jobs another device sends: the capture rules, the consent step and what the device is told. Moved out of manager.ts;
// everything it needs from the queue comes through `QueueCore`.
import {validateUrl} from '../../shared/contracts.js'
import {validateGalleryRequest} from '../../shared/core/gallery.js'
import {jobQuote, validateFormatRequest} from '../../shared/policy.js'
import type {StoredJob} from '../migrate.js'
import type {RemoteJobView} from '../manager.js'
import {errorText, type QueueCore} from './core.js'
import {photoLabel} from './photos.js'

export class RemoteJobs {
  // jobs from other devices: waiting for the person here, allowed, declined, and the ones whose link was already checked and inspected
  private readonly consent = new Map<string, {reason: 'size' | 'metered'; bytes?: number}>()
  private readonly consented = new Set<string>()
  private readonly declined = new Set<string>()
  private readonly prepared = new Set<string>()

  constructor(private readonly core: QueueCore) {}

  /** What a remote job waits for the person here to decide, if it waits. */
  consentOf(id: string) {
    return this.consent.get(id)
  }

  /** True for a job another device asked for. */
  isRemote(id: string): boolean {
    return !!this.core.options.remote?.originOf(id)
  }

  /**
   * Adds the job another device asked for, under [id] (the same id again changes nothing, so a retry never duplicates). The capture
   * rules apply as for the person's own: a plain http(s) link, a valid format and options, one of the spaces. Nothing is inspected yet:
   * the link is checked and inspected when the job starts, and the person is asked first if it is large.
   */
  async enqueueRemote(input: {id: string; url: unknown; format: unknown; options?: unknown; space?: unknown; musicVideo?: boolean}): Promise<void> {
    if (!this.core.options.remote) throw new Error('This device does not take downloads from other devices.')
    if (this.core.jobs().some((job) => job.id === input.id)) return
    const url = validateUrl(input.url)
    const format = validateFormatRequest(input.format)
    const options = this.core.resolveOptions(input.options)
    const space = this.core.resolveSpace(url, input.space)
    this.core.checkCapacity(1)
    let label = 'Download'
    try {
      label = jobQuote(format, {video: [], audio: [], duration: undefined}, false).label
    } catch {
      // the label is refined from the inspected formats when the job starts
    }
    this.core.jobs().push({
      id: input.id,
      url,
      title: url,
      label,
      kind: format.type,
      format,
      options,
      status: 'queued',
      percent: 0,
      space,
      // the song's music video is looked up once the song is saved (only a song kept as audio in the library has one)
      findVideo: input.musicVideo === true && format.type === 'audio' && !!this.core.options.library && this.core.settings().library.enabled ? true : undefined,
      createdAt: this.core.now(),
    })
    await this.core.commit()
    void this.core.pump()
  }

  /** What the device that asked is told about its job. */
  remoteView(id: string): RemoteJobView | undefined {
    const job = this.core.jobs().find((item) => item.id === id)
    if (!job) return undefined
    const consent = this.consent.has(id)
    return {
      state: consent ? 'consent' : job.status,
      percent: job.percent,
      title: job.title === job.url ? undefined : job.title,
      label: job.label,
      speed: job.speed,
      eta: job.eta,
      totalBytes: job.totalBytes ?? job.expectedSize,
      error: this.declined.has(id) ? 'Declined on the other device.' : job.error,
    }
  }

  /** The person here allows a job that was waiting. */
  async allowRemote(id: unknown) {
    const job = this.core.job(id)
    if (!this.isRemote(job.id)) throw new Error('That download did not come from another device.')
    this.consented.add(job.id)
    this.consent.delete(job.id)
    if (job.status === 'paused') await this.core.resume(job.id)
    else await this.core.commit()
    return this.core.snapshot()
  }

  async declineRemote(id: unknown) {
    const job = this.core.job(id)
    if (!this.isRemote(job.id)) throw new Error('That download did not come from another device.')
    this.declined.add(job.id)
    this.consent.delete(job.id)
    await this.core.cancelOne(job)
    await this.core.commit()
    return this.core.snapshot()
  }

  /**
   * First thing a remote job does when it starts: the link is checked (every redirect hop, every address), then inspected for its
   * title and size, then, when it is large or the connection is metered, it waits for the person here. Returns false when the job
   * stops here (waiting, refused or failed); it is saved either way.
   */
  async prepareRemote(job: StoredJob, signal: AbortSignal): Promise<boolean> {
    const remote = this.core.options.remote!
    try {
      if (!this.prepared.has(job.id)) {
        await remote.guard(job.url, signal)
        const result = await this.core.inspect(job.url, job.id, true)
        signal.throwIfAborted()
        if (result.type !== 'media') throw new Error('Only a single link can be sent to another device. Send the items of a collection one by one.')
        const media = result.media
        job.title = media.title || job.url
        job.thumbnail = media.thumbnail
        job.uploader = media.uploader
        job.duration = media.duration
        job.provider = media.provider
        if (media.engine === 'gallery-dl') {
          // the photos chosen on the other device are checked against the post read here like a local request; a plain video request takes every photo
          try {
            job.format = validateGalleryRequest(job.format.type === 'gallery' ? job.format : undefined, media.gallery?.count)
          } catch {
            throw new Error('The photos chosen on the other device are not in this post.')
          }
          job.kind = 'gallery'
          job.label = photoLabel(media, job.format)
          job.expectedCount = job.format.items ? job.format.items.length : media.gallery?.count
        } else if (job.format.type === 'gallery') {
          throw new Error('This link has no photos to download.')
        } else {
          try {
            const quote = jobQuote(job.format, {video: media.video, audio: media.audio, duration: media.duration})
            job.label = quote.label
            job.expectedSize = quote.expectedSize
          } catch {
            // a format this link does not offer: the download engine picks the closest, the size is unknown
          }
          job.expectedCount = media.gallery?.count
        }
        job.match = media.match
        job.music = media.music ? {...media.music} : undefined
        job.tags = media.tags
        this.prepared.add(job.id)
      }
      if (!this.consented.has(job.id)) {
        const metered = (await remote.metered?.().catch(() => false)) === true
        const big = (job.expectedSize ?? 0) > remote.consentBytes
        if (metered || big) {
          this.consent.set(job.id, {reason: metered ? 'metered' : 'size', ...(job.expectedSize ? {bytes: job.expectedSize} : {})})
          job.status = 'paused'
          this.core.clearLive(job)
          return false
        }
      }
      return true
    } catch (error) {
      if (signal.aborted) throw error
      job.status = 'failed'
      job.error = errorText(error)
      job.autoRetries = undefined
      this.core.clearLive(job)
      return false
    }
  }
}
