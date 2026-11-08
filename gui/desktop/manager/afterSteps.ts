// The queue's follow-up steps of a finished download (desktop/afterDownload.ts; the order and the meaning of failures are the shared core's
// plan, dev-docs/plans/AFTER-DOWNLOAD.md). The library supplies the saving steps; this adds lyrics, the cover, the job's own state and the
// music-video look-up. Moved out of manager.ts.
import type {AfterKind} from '../../shared/core/after.js'
import type {ExistingMatch} from '../../shared/library.js'
import {noSaving, runAfterDownload, SAVING, type AfterDownloadSteps} from '../afterDownload.js'
import type {StoredJob} from '../migrate.js'
import {errorText, type QueueCore} from './core.js'
import {PartlySaved} from '../library/partial.js'
import type {MusicExtras} from './musicExtras.js'
import {filesOf, relabel} from './photos.js'

export class QueueAfterDownload {
  constructor(
    private readonly core: QueueCore,
    private readonly extras: MusicExtras,
    /** A finished song marked `findVideo` looks once for its official video (the manager keeps those look-ups in line). */
    private readonly lookForVideo: (song: StoredJob) => void,
  ) {}

  /** Runs the plan for a finished job. `existing` is the recording that was already in the library (nothing was downloaded). */
  async run(job: StoredJob, options: {existing?: ExistingMatch; only?: readonly AfterKind[]; signal?: AbortSignal} = {}) {
    const {existing, only, signal} = options
    // fewer files than asked for arrived: "4 of 6 photos"
    if (job.format.type === 'gallery' && !existing && !only) relabel(job, filesOf(job).length)
    const library = this.core.options.library
    const saving = (await library?.savingPlan(job, existing)) ?? {
      facts: {audio: job.kind === 'audio', hasFile: !!job.file && !existing, existing: !!existing, playlist: !!job.playlist},
      items: [],
      steps: noSaving,
    }
    const touch = () => this.core.touch()
    const steps: AfterDownloadSteps = {
      ...saving.steps,
      lyrics: () => this.extras.addLyrics(job, signal),
      save: async () => {
        try {
          const items = await saving.steps.save()
          job.saveError = undefined
          // every photo is in the library now ("4 of 6 photos" becomes "6 photos")
          if (job.format.type === 'gallery') relabel(job, filesOf(job).length)
          return items
        } catch (error) {
          if (error instanceof PartlySaved) relabel(job, error.saved)
          throw error
        }
      },
      attachVideo: async () => {
        const items = await saving.steps.attachVideo()
        if (items) job.saveError = undefined
        return items
      },
      // the library takes the artwork from the file when it adds it
      cover: async () => {},
      notifyDone: async () => {
        job.status = 'completed'
        job.percent = 100
        job.completedAt = this.core.now()
        this.core.clearLive(job)
        touch()
      },
      notifyExisting: async () => {
        await this.extras.finishExisting(job, existing!)
        touch()
      },
      settleVideos: async () => {
        await saving.steps.settleVideos()
        this.lookForVideo(job)
      },
      markNotSaved: async (error) => {
        job.saveError = errorText(error)
        touch()
      },
    }
    await runAfterDownload({...saving.facts, lyrics: this.core.settings().lyrics}, steps, {only, items: saving.items, signal})
  }

  /** "Save again": a finished download that could not be saved into the library tries the saving steps once more. */
  saveAgain(job: StoredJob) {
    return this.run(job, {only: SAVING})
  }
}
