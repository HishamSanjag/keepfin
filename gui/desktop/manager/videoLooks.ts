// The music-video look-up of finished collection songs marked `findVideo`: one lookup at a time, resumed after a restart.
import {channelArtist} from '../../shared/library.js'
import {lookupMusicVideo} from '../engine/music.js'
import type {StoredJob} from '../migrate.js'
import type {QueueCore} from './core.js'
import type {MusicExtras} from './musicExtras.js'

export class VideoLooks {
  private chain: Promise<void> = Promise.resolve()

  constructor(
    private readonly core: QueueCore,
    private readonly musicExtras: MusicExtras,
  ) {}

  /** A finished song marked `findVideo`: looks once for its official video, one lookup at a time (a long playlist never fires them all at once). */
  lookFor(song: StoredJob) {
    if (!song.findVideo || song.status !== 'completed' || song.format.type !== 'audio') return
    this.chain = this.chain
      .then(() => this.queueVideoOf(song))
      .catch((error) => console.warn('No music video looked up:', error instanceof Error ? error.message : error))
      .then(() => {
        // the look is over, whatever it found: the job stops asking, so the next start resumes only looks that never ran
        if (this.core.closing() || !song.findVideo || !this.core.jobs().includes(song)) return
        song.findVideo = undefined
        return this.core.save().catch(() => undefined)
      })
  }

  /** Settles once every look queued so far has finished (tests, and a clean close). */
  idle(): Promise<void> {
    return this.chain
  }

  /** Looks that never ran (Keepfin closed with some still waiting): called once the library is open. */
  resume() {
    for (const job of this.core.jobs()) this.lookFor(job)
  }

  private async queueVideoOf(song: StoredJob) {
    const library = this.core.options.library
    if (!library || this.core.closing() || !song.file || !this.core.jobs().includes(song)) return
    const entry = library.entryAt(song.file)
    // already has its video, or one is queued (or was) for this very job: a Retry or a restart never looks twice
    if (entry?.videoPath || this.core.jobs().some((job) => job.attachVideo?.jobId === song.id)) return
    // the song's real artist and title: its saved tags, else what the inspected media said
    const artist = entry?.artist ?? song.music?.artist ?? channelArtist(song.uploader)
    const title = entry?.title ?? song.music?.track ?? song.title
    if (!artist || !title) return
    const found = await (this.core.options.findMusicVideo ?? lookupMusicVideo)(artist, title)
    if ('none' in found || this.core.closing()) return
    await this.musicExtras.queueMusicVideo(found.videoId, {jobId: song.id}, song.space)
  }
}
