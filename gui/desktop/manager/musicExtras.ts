// Music extras: lyrics, music videos and "already in your library". Moved out of manager.ts; everything it needs from the queue
// comes through `QueueCore`. (The video look-up chain `lookForVideo` / `videoLooks` stays in the manager.)
import fs from 'node:fs/promises'
import {randomUUID} from 'node:crypto'
import {
  validateUrl,
  type DownloadOptions,
  type FormatRequest,
  type MediaInfo,
  type MusicAlternate,
} from '../../shared/contracts.js'
import {
  channelArtist,
  folderSpaces,
  sourceIdFromUrl,
  type ExistingMatch,
  type ExistingProbe,
  musicVideoWords,
  type MusicVideoFound,
  type SongOfLink,
} from '../../shared/library.js'
import {jobQuote} from '../../shared/policy.js'
import {isYouTube, prefersAudio, youTubeVideoId} from '../../shared/providers.js'
import {cleanSongTitle, lyricsOutcomeText, normalizeLyricsKey, type LyricsOutcome, type LyricsQuery} from '../../shared/core/lyrics.js'
import {lookupMusicVideo} from '../engine/music.js'
import type {StoredJob} from '../migrate.js'
import type {QueueCore} from './core.js'

/** The id of a YouTube video the find step offered (11 characters), or an error. */
export const musicVideoId = (input: unknown): string => {
  if (typeof input !== 'string' || !/^[A-Za-z0-9_-]{11}$/.test(input)) throw new Error('That is not a video Keepfin can download.')
  return input
}

/** The user's options for a video kept beside a song: without anything that would write extra files or cut the video. */
const companionOptions = (options: DownloadOptions): DownloadOptions => ({
  ...options,
  subtitles: undefined,
  section: undefined,
  splitChapters: undefined,
  writeThumbnail: false,
  writeDescription: false,
  writeInfoJson: false,
})

export class MusicExtras {
  // lyrics lookups for finished songs (aborted when the app closes)
  private readonly lyricsControllers = new Set<AbortController>()

  constructor(private readonly core: QueueCore) {}

  /** The app is closing: stop every lyrics lookup. */
  close() {
    for (const controller of this.lyricsControllers) controller.abort(new Error('Keepfin is closing.'))
  }

  /** What LRCLIB is asked for a finished song: its music tags, else the channel and title ("Artist - Title" when needed). */
  private lyricsQuery(job: StoredJob): LyricsQuery | undefined {
    let artist = job.music?.artist ?? channelArtist(job.uploader)
    let title = job.music?.track ?? job.title
    if (!title) return
    if (!job.music?.track) {
      const split = title.match(/^(.+?)\s+[-–—]\s+(.+)$/)
      if (split && (!artist || normalizeLyricsKey(split[1]) === normalizeLyricsKey(artist))) [artist, title] = [split[1], split[2]]
    }
    if (!artist) return
    return {title: cleanSongTitle(title, artist), artist, album: job.music?.album, duration: job.duration}
  }

  /** Best effort, before the song is saved: lyrics go into (or next to) its file, so the library has them from the start. Never throws. */
  async addLyrics(job: StoredJob, signal?: AbortSignal) {
    const engine = this.core.options.engine
    const file = job.file
    const song = this.lyricsQuery(job)
    if (!engine.addLyrics || !file || !song || this.core.closing()) return
    const controller = new AbortController()
    this.lyricsControllers.add(controller)
    try {
      await engine.addLyrics(file, song, signal ? AbortSignal.any([controller.signal, signal]) : controller.signal)
    } catch {
      // lyrics never affect a download
    } finally {
      this.lyricsControllers.delete(controller)
    }
  }

  /**
   * "Find lyrics" on a song that has none: the same lookup and the same storage as a finished download (in the file, else next to it),
   * so sync and copies treat them alike. Says what it came to: lyrics, none found, or a service that could not be reached.
   */
  async findLyrics(idInput: unknown): Promise<{outcome: LyricsOutcome; message?: string}> {
    const library = this.core.options.library
    const engine = this.core.options.engine
    const entry = typeof idInput === 'string' ? library?.lookup(idInput) : undefined
    if (!library || !entry || entry.kind !== 'audio' || entry.missing) throw new Error('Choose a song from your library.')
    if (!engine.findLyrics) throw new Error('Lyrics are not available here.')
    if (entry.hasLyrics || entry.lrc) return {outcome: 'found'}
    const artist = entry.artist ?? entry.albumArtist
    if (!artist || this.core.closing()) return {outcome: 'none', message: lyricsOutcomeText('none')}
    const controller = new AbortController()
    this.lyricsControllers.add(controller)
    try {
      const outcome = await engine.findLyrics(entry.path, {title: cleanSongTitle(entry.title, artist), artist, album: entry.album, duration: entry.duration}, controller.signal)
      if (outcome === 'found') await library.refresh(entry.id)
      return {outcome, message: lyricsOutcomeText(outcome)}
    } finally {
      this.lyricsControllers.delete(controller)
    }
  }

  /** A music video that finished before its song failed or was cancelled is kept as an ordinary download, never lost. */
  releaseVideos(song: StoredJob) {
    if (song.status !== 'failed' && song.status !== 'cancelled') return
    for (const video of this.core.jobs())
      if (video.attachVideo?.jobId === song.id && video.status === 'completed') void this.core.options.library?.adoptJob(video).catch(() => undefined)
  }

  /** What is known about a job's recording before anything is transferred. */
  private probeFor(job: StoredJob): ExistingProbe {
    const artist = job.music?.artist ?? channelArtist(job.uploader)
    return {
      sourceId: sourceIdFromUrl(job.url),
      recording: artist
        ? {artist, title: job.music?.track ?? job.title, album: job.music?.album, duration: job.duration}
        : undefined,
    }
  }

  /** The recording already in the job's space, when that space skips existing songs and the user did not override it. */
  async existingRecording(job: StoredJob): Promise<ExistingMatch | undefined> {
    const library = this.core.options.library
    const space = this.core.jobSpace(job)
    if (!library || !space?.skipExisting || job.force || job.format.type === 'gallery') return
    const hit = library.findExisting(space.id, this.probeFor(job))
    if (!hit) return
    // The match must still exist on disk, or the download would be skipped for a file that is gone.
    return (await fs.stat(hit.path).catch(() => undefined))?.isFile() ? hit : undefined
  }

  async finishExisting(job: StoredJob, hit: ExistingMatch) {
    job.existing = hit
    job.file = hit.path
    job.files = undefined
    job.fileSize = (await fs.stat(hit.path).catch(() => undefined))?.size
    job.status = 'completed'
    job.percent = 100
    job.completedAt = this.core.now()
    this.core.clearLive(job)
  }

  /** "Download anyway": re-runs a job that completed because the recording already existed. */
  async downloadAnyway(id: unknown) {
    const job = this.core.job(id)
    if (job.status !== 'completed' || !job.existing) throw new Error('Only songs that were already in your library can be downloaded again.')
    job.force = true
    job.existing = undefined
    job.file = undefined
    job.files = undefined
    job.fileSize = undefined
    job.status = 'queued'
    job.percent = 0
    job.completedAt = undefined
    job.outputTemplate = undefined
    await this.core.commit()
    void this.core.pump()
    return this.core.snapshot()
  }

  /**
   * Downloads the official music video `videoId` (offered by the find step) for the library song `itemId`: inspected like any link, queued
   * as an mp4 of up to 1080p, and kept beside the song when it finishes (`attachVideo`; the library does that). The
   * existing-recording check is skipped, since the song itself is already there.
   */
  async getMusicVideo(itemInput: unknown, videoInput: unknown) {
    const library = this.core.options.library
    const entry = typeof itemInput === 'string' ? library?.lookup(itemInput) : undefined
    if (!library || !entry || entry.kind !== 'audio') throw new Error('Choose a song from your library.')
    if (entry.videoPath) throw new Error('This song already has a music video.')
    if (this.videoComingFor(entry.id)) throw new Error('The music video for this song is already downloading.')
    await this.queueMusicVideo(musicVideoId(videoInput), {itemId: entry.id}, this.core.resolveSpace(`https://music.youtube.com/watch?v=${videoInput}`, entry.space))
  }

  /** Capture: the song you have behind the inspected link at [urlInput], for the line on the Video tab; null when it matches none. */
  songOfInspected(urlInput: unknown): SongOfLink | null {
    const url = validateUrl(urlInput)
    const media = this.core.inspectedMedia(url)
    return (media && this.songOfLink(url, media)) || null
  }

  /** Looks for the official music video of the song inspected at [urlInput] (Capture), by its artist and title; none without an artist. */
  async findMusicVideoOf(urlInput: unknown): Promise<MusicVideoFound> {
    const url = validateUrl(urlInput)
    const media = this.core.inspectedMedia(url)
    if (!media || !prefersAudio(media.webpageUrl || media.url)) return {none: true}
    const artist = media.music?.artist ?? channelArtist(media.uploader)
    const title = media.music?.track ?? media.title
    return artist && title ? lookupMusicVideo(artist, title) : {none: true}
  }

  /** The library song the recording behind [url] already is, in any space (undefined when it matches no song), and whether it has its music video. */
  songOfLink(url: string, media: Pick<MediaInfo, 'title' | 'uploader' | 'music' | 'duration'>): SongOfLink | undefined {
    const library = this.core.options.library
    const artist = media.music?.artist ?? channelArtist(media.uploader)
    const probe: ExistingProbe = {
      sourceId: sourceIdFromUrl(url),
      recording: artist ? {artist, title: media.music?.track ?? media.title, album: media.music?.album, duration: media.duration} : undefined,
    }
    const song = library?.lookup(library.findExisting(undefined, probe, 'audio')?.itemId ?? '')
    if (!song) return
    return {itemId: song.id, space: song.space, hasVideo: !!song.videoPath || this.videoComingFor(song.id)}
  }

  /**
   * A video download of a recording that is a song you have joins that song, the way Get the music video does: the same job with the
   * song to attach to, saved beside it (any space), whatever the existing-song check says. Nothing when it matches no song; it
   * is refused, in words, when the song already has its video (or one is on the way). [format] and [options] come back adjusted:
   * a container a companion can be (mp4 or webm), and nothing that writes extra files.
   */
  joinSong(url: string, media: Pick<MediaInfo, 'title' | 'uploader' | 'music' | 'duration'>, format: FormatRequest, options: DownloadOptions) {
    const song = format.type === 'video' ? this.songOfLink(url, media) : undefined
    if (!song) return
    if (song.hasVideo) throw new Error(musicVideoWords.hasVideoError)
    const video: FormatRequest = format.type === 'video' && format.container === 'mkv' ? {...format, container: 'mp4'} : format
    return {format: video, options: companionOptions(options), space: song.space, force: true, attachVideo: {itemId: song.itemId}}
  }

  /** The library song a finished download became (or already was), by its job id. */
  private songItemOf(jobId: string): string | undefined {
    const song = this.core.jobs().find((job) => job.id === jobId)
    return (song?.file ? this.core.options.library?.entryAt(song.file)?.id : undefined) ?? song?.existing?.itemId
  }

  /** A music video still on its way for the library song [itemId]: asked for on the song, or linked to the job that made it (Capture, a playlist). */
  private videoComingFor(itemId: string): boolean {
    return this.core.jobs().some(
      (job) =>
        !!job.attachVideo && job.status !== 'completed' && job.status !== 'failed' && job.status !== 'cancelled' &&
        (job.attachVideo.itemId ?? (job.attachVideo.jobId ? this.songItemOf(job.attachVideo.jobId) : undefined)) === itemId,
    )
  }

  /**
   * Where the video kept with a song downloaded as audio comes from: the official video found for a YouTube Music link, or, for any
   * other YouTube link, the link itself ([videoId] is then its own id). Throws when it does not fit the download.
   */
  videoUrlFor(videoId: string, format: FormatRequest, media: Pick<MediaInfo, 'url' | 'webpageUrl'>): string {
    const link = media.webpageUrl || media.url
    const own = isYouTube(link) && !prefersAudio(link)
    if (format.type !== 'audio' || !this.core.options.library || !isYouTube(link) || (own && videoId !== youTubeVideoId(link)))
      throw new Error('A music video goes with a song downloaded as audio.')
    return own ? media.url : `https://music.youtube.com/watch?v=${videoId}`
  }

  /** Queues the music video [videoId] ([url]: where it is, a YouTube Music watch page by default) as an mp4 of up to 1080p, kept beside the song [attachVideo] names when it finishes. */
  async queueMusicVideo(videoId: string, attachVideo: NonNullable<StoredJob['attachVideo']>, space: string | undefined, link?: string) {
    const url = link ?? `https://music.youtube.com/watch?v=${videoId}`
    const result = await this.core.inspect(url)
    if (result.type !== 'media') throw new Error('That video could not be read.')
    // checked again after the wait, with nothing awaited before the job is added: "Get the music video" and a playlist look never both queue one
    const song = attachVideo.itemId ?? (attachVideo.jobId ? this.songItemOf(attachVideo.jobId) : undefined)
    if ((song && this.videoComingFor(song)) || (attachVideo.jobId && this.core.jobs().some((job) => job.attachVideo?.jobId === attachVideo.jobId)))
      throw new Error('The music video for this song is already downloading.')
    const media = result.media
    this.core.checkCapacity(1)
    const format: FormatRequest = {type: 'video', maxHeight: 1080, codec: 'h264', container: 'mp4'}
    const options = companionOptions(this.core.resolveOptions(undefined))
    const quote = jobQuote(format, {video: media.video, audio: media.audio, duration: media.duration})
    this.core.jobs().push({
      id: randomUUID(),
      url,
      title: media.title || url,
      thumbnail: media.thumbnail,
      uploader: media.uploader,
      duration: media.duration,
      provider: media.provider,
      label: quote.label,
      kind: 'video',
      format,
      options,
      status: 'queued',
      percent: 0,
      expectedSize: quote.expectedSize,
      space,
      force: true,
      attachVideo,
      createdAt: this.core.now(),
    })
    await this.core.commit()
    void this.core.pump()
  }

  /** Looks up recordings already in a space for the links of a collection review (badges "Already in Music"). */
  libraryMatch(urlsInput: unknown, spaceInput: unknown, sessionInput?: unknown): Record<string, ExistingMatch> {
    const library = this.core.options.library
    const space = typeof spaceInput === 'string' ? folderSpaces(this.core.settings().library.spaces).find((item) => item.id === spaceInput) : undefined
    if (!library || !this.core.settings().library.enabled || !space) return {}
    if (!Array.isArray(urlsInput) || urlsInput.length > 5000) throw new Error('Select links to check.')
    const entries = new Map(
      this.core.entriesOf(sessionInput)?.map((entry) => [entry.url, entry]) ?? [],
    )
    const found: Record<string, ExistingMatch> = {}
    for (const raw of urlsInput) {
      const url = validateUrl(raw)
      const entry = entries.get(url)
      const artist = entry?.artist ?? channelArtist(entry?.uploader)
      const hit = library.findExisting(space.id, {
        sourceId: sourceIdFromUrl(url),
        recording: entry?.title && artist ? {artist, title: entry.title, album: entry.album, duration: entry.duration} : undefined,
      })
      if (hit) found[url] = hit
    }
    return found
  }

  /** Uses this recording for a Spotify / Apple Music / Deezer link from now on (also for jobs that have not downloaded yet). */
  async setMusicChoice(urlInput: unknown, choiceInput: unknown): Promise<null> {
    const url = validateUrl(urlInput)
    const choice = choiceInput as {matchedUrl?: unknown; title?: unknown; durationSec?: unknown} | undefined
    if (!choice || typeof choice !== 'object') throw new Error('Choose a recording.')
    const matchedUrl = validateUrl(choice.matchedUrl)
    const title = typeof choice.title === 'string' ? choice.title.slice(0, 300) : undefined
    const durationSec = typeof choice.durationSec === 'number' && Number.isFinite(choice.durationSec) && choice.durationSec > 0 ? choice.durationSec : undefined
    const engine = this.core.options.engine
    if (!engine.rememberMusicChoice) throw new Error('Choices are not supported here.')
    await engine.rememberMusicChoice(url, {matchedUrl, title, durationSec})
    return null
  }

  /** Other uploads of the song behind a music-service link, for "Not right? Pick another". `excludeInput` lists links to leave out. */
  async musicAlternates(urlInput: unknown, excludeInput?: unknown): Promise<MusicAlternate[]> {
    const url = validateUrl(urlInput)
    if (excludeInput !== undefined && !(Array.isArray(excludeInput) && excludeInput.length <= 20)) throw new Error('Invalid recordings to leave out.')
    const exclude = (excludeInput as unknown[] | undefined)?.map((item) => validateUrl(item)) ?? []
    const engine = this.core.options.engine
    if (!engine.musicAlternates) return []
    const controller = new AbortController()
    return engine.musicAlternates(url, exclude, this.core.context(controller.signal))
  }
}
