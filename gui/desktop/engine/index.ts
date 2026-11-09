// Engine facade used by the queue manager: routes links to yt-dlp, gallery-dl or the
// music resolver, and runs downloads (Turbo first when eligible, plain yt-dlp otherwise).
import fs from 'node:fs/promises'
import path from 'node:path'
import type {
  EngineStatus,
  FormatRequest,
  InspectResult,
  MediaInfo,
  MusicAlternate,
  MusicMatch,
  MusicMetadata,
  PlaylistEntry,
  DownloadOptions,
  Settings,
} from '../../shared/contracts.js'
import {isGenericUnavailable, restrictedMessage, restrictedMusicMessage, suggestsGallery} from '../../shared/errors.js'
import {mediaInfoFromYtDlp, type RawInfo} from '../../shared/formats.js'
import {fillSizes} from './sizes.js'
import {CONVERT_PHASE, convertDownload} from './convert.js'
import {buildDownloadArgs, ENCODING_ARGS, type CookieSource} from '../../shared/policy.js'
import {settleOutputs} from './outputs.js'
import {isCollectionUrl, isGalleryHost, isShortMusicLink, isYouTube, musicLink, musicLinkUrl} from '../../shared/providers.js'
import {MusicChoices} from './choices.js'
import {downloadGallery, inspectGallery, type GalleryContext} from './gallery.js'
import {
  classifyProbe,
  isMusicLike,
  probeWatchPage,
  stripTopic,
  youTubeVideoId,
  fetchAlbumPage,
  resolveMusic,
  searchAlbums,
  searchSongs,
  serviceNames,
  serviceOf,
  type Candidate,
  type WatchProbe,
  type MusicSource,
  type SourceCollection,
  type SourceTrack,
} from './music.js'
import {
  albumApplies,
  albumHint,
  collectAlternates,
  matchViaAlbum,
  parseFlatSearchText,
  pickBest,
  rankAlternates,
  searchPlan,
  videoIdOf,
  type AlbumHit,
  type AlbumPage,
  type MatchTrack,
} from '../../shared/core/music.js'
import {run} from './process.js'
import {findLyricsText} from './lyrics.js'
import {tagFile, writeLyrics} from './tagging.js'
import type {LyricsOutcome, LyricsQuery} from '../../shared/core/lyrics.js'
import type {ToolManager} from './tools.js'
import {TurboError, discardTurbo, turboDownload} from './turbo.js'
import {
  EngineError,
  InfoCache,
  ProgressParser,
  discoverPage,
  dumpInfo,
  resolveSelection,
  runDownload,
  streamFileName,
  type RunnerProgress,
  type YtDlpContext,
} from './ytdlp.js'

export type EngineContext = {
  settings: Settings
  cookies?: CookieSource
  signal?: AbortSignal
  /**
   * The network destination check for links that came from another device (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md 9.1 #6). When set, the engine
   * calls it with the link before it starts any network work for it, and an error it throws ends the work. Absent for the person's own captures.
   */
  guard?: (url: string, signal?: AbortSignal) => Promise<void>
}

export type DownloadTask = {
  url: string
  title: string
  format: FormatRequest
  options: DownloadOptions
  /** Directory and collision-free file stem owned by this job. */
  folder: string
  stem: string
  match?: MusicMatch
  /** Collection link the job belongs to (music-like when it is a YouTube Music page). */
  groupUrl?: string
  music?: MusicMetadata & {trackNumber?: number}
  thumbnail?: string
  duration?: number
  /** Gallery item count from inspection (for progress). */
  expectedCount?: number
  /** Seconds between requests for gentle YouTube batches. */
  sleepRequests?: number
}

export type DownloadEvents = {
  onStage: (stage: 'downloading' | 'processing', phase?: string) => void
  onProgress: (progress: RunnerProgress) => void
  onMatch?: (match: MusicMatch) => void
}

/** A recording picked for a music-service song, and how. */
type MusicPick = {
  candidate: Candidate
  confidence: number
  method: 'album' | 'search' | 'chosen'
  album?: string
  position?: number
  alternates: MusicAlternate[]
  /** The title the user's choice was remembered with. */
  chosenTitle?: string
}

export type DownloadOutcome ={file: string; files: string[]; match?: MusicMatch; turbo?: boolean}

export type DiscoveryResult = {truncated: boolean; partialError?: string; notice?: string}

export interface Engine {
  status(): EngineStatus
  onStatus(listener: () => void): () => void
  inspect(url: string, ctx: EngineContext): Promise<InspectResult>
  discover(
    url: string,
    page: {start: number; end: number},
    ctx: EngineContext,
    onEntry: (entry: PlaylistEntry) => void,
    onMeta: (meta: {title?: string; uploader?: string}) => void,
  ): Promise<DiscoveryResult>
  download(task: DownloadTask, ctx: EngineContext, events: DownloadEvents): Promise<DownloadOutcome>
  /** Removes everything a cancelled job wrote (its stem is unique). */
  discard(task: Pick<DownloadTask, 'folder' | 'stem' | 'format'>): Promise<void>
  update(): Promise<EngineStatus>
  autoUpdate(enabled: boolean): Promise<void>
  clearCache(): Promise<void>
  /** Looks the song's lyrics up and saves them in (or next to) the file; returns whether any were saved. Never throws. */
  addLyrics?(file: string, song: LyricsQuery, signal?: AbortSignal): Promise<boolean>
  /** The same lookup for "Find lyrics", saved the same way, saying what it came to: lyrics, a service that could not be reached, or none. Throws when found lyrics could not be saved. */
  findLyrics?(file: string, song: LyricsQuery, signal?: AbortSignal): Promise<LyricsOutcome>
  /** Remembers which recording to use for a Spotify / Apple Music / Deezer link from now on. Throws on an invalid link or recording. */
  rememberMusicChoice?(sourceUrl: string, choice: {matchedUrl: string; title?: string; durationSec?: number}): Promise<void>
  /** Other uploads of the song behind a Spotify / Apple Music / Deezer link (not `exclude`d recordings), best first. */
  musicAlternates?(sourceUrl: string, exclude: string[], ctx: EngineContext): Promise<MusicAlternate[]>
}

/** Below this, Turbo's extra selection step costs more than parallelism saves. */
const TURBO_MIN_BYTES = 8 * 1024 * 1024
const MUSIC_CACHE_MS = 10 * 60_000

export class StudioEngine implements Engine {
  private music = new Map<string, {source: MusicSource; at: number}>()
  // Album lookups are shared by every track of an album (and by parallel workers): one search and one page per album.
  private albumSearches = new Map<string, {value: Promise<AlbumHit[]>; at: number}>()
  private albumPages = new Map<string, {value: Promise<AlbumPage | undefined>; at: number}>()
  private readonly fetcher: typeof fetch
  private readonly choices?: MusicChoices

  constructor(
    private readonly tools: ToolManager,
    private readonly cache: InfoCache,
    private readonly options: {
      ytDlpCacheDir?: string
      fetch?: typeof fetch
      now?: () => number
      userAgent?: string
      /** JSON file for the recordings the user picked for music links (userData/music-choices.json). */
      choicesFile?: string
    } = {},
  ) {
    this.fetcher = options.fetch ?? fetch
    if (options.choicesFile) this.choices = new MusicChoices(options.choicesFile, () => this.now())
  }

  private now() {
    return this.options.now?.() ?? Date.now()
  }

  status() {
    return this.tools.status()
  }
  onStatus(listener: () => void) {
    return this.tools.onChange(listener)
  }
  update() {
    return this.tools.update()
  }
  autoUpdate(enabled: boolean) {
    return this.tools.autoUpdate(enabled)
  }
  async clearCache() {
    this.music.clear()
    this.albumSearches.clear()
    this.albumPages.clear()
    await this.cache.clear()
  }

  private async ytdlp(ctx: EngineContext): Promise<YtDlpContext> {
    const runtime = this.tools.jsRuntime()
    return {
      binary: await this.tools.ytDlp(),
      ffmpeg: this.tools.ffmpeg(),
      jsRuntime: runtime.path,
      env: runtime.env,
      cacheDir: this.options.ytDlpCacheDir,
      network: ctx.settings,
      cookies: ctx.cookies,
      signal: ctx.signal,
    }
  }

  private async gallery(ctx: EngineContext): Promise<GalleryContext> {
    return {
      binary: await this.tools.galleryDl(),
      env: this.tools.jsRuntime().env,
      network: ctx.settings,
      cookies: ctx.cookies,
      signal: ctx.signal,
    }
  }

  // ---------------------------------------------------------------- inspection

  async inspect(url: string, ctx: EngineContext): Promise<InspectResult> {
    await ctx.guard?.(url, ctx.signal)
    if (musicLink(url) || isShortMusicLink(url)) return this.inspectMusic(url, ctx)
    if (isGalleryHost(url)) {
      try {
        return {type: 'media', media: await inspectGallery(await this.gallery(ctx), url)}
      } catch (error) {
        if (ctx.signal?.aborted) throw error
        // Some "image hosts" also carry video yt-dlp understands (Imgur gifv, Tumblr video).
        try {
          return await this.inspectYtDlp(url, ctx, false)
        } catch {
          throw error
        }
      }
    }
    return this.inspectYtDlp(url, ctx, true)
  }

  private async inspectYtDlp(url: string, ctx: EngineContext, galleryFallback: boolean): Promise<InspectResult> {
    const yt = await this.ytdlp(ctx)
    try {
      if (isCollectionUrl(url)) {
        const {info} = await dumpInfo(yt, url, 'flat-probe')
        if (info._type === 'playlist') return collectionResult(url, info)
      }
      const {info, raw} = await dumpInfo(yt, url, 'single')
      if (info._type === 'playlist') return collectionResult(url, info)
      await this.cache.put(url, raw).catch(() => {})
      await fillSizes(info, this.fetcher, undefined, ctx.signal)
      return {type: 'media', media: mediaInfoFromYtDlp(info, url, true)}
    } catch (error) {
      if (ctx.signal?.aborted) throw error
      if (isYouTube(url) && isGenericUnavailable(errorText(error))) {
        const alternate = await this.inspectRestricted(url, ctx)
        if (alternate) return alternate
      }
      if (!galleryFallback) throw error
      const stderr = error instanceof EngineError ? error.stderr : ''
      if (!suggestsGallery(stderr)) throw error
      try {
        return {type: 'media', media: await inspectGallery(await this.gallery(ctx), url)}
      } catch (galleryError) {
        // A photo post the site shows only to signed-in visitors (Instagram): "sign in" says what to do, "no video" does not.
        if (galleryError instanceof EngineError && galleryError.action === 'signIn') throw galleryError
        throw error
      }
    }
  }

  private async musicSource(url: string, signal?: AbortSignal): Promise<MusicSource> {
    const cached = this.music.get(url)
    if (cached && this.now() - cached.at < MUSIC_CACHE_MS) return cached.source
    const source = await resolveMusic(url, this.fetcher, signal)
    if (this.music.size > 50) this.music.delete(this.music.keys().next().value!)
    this.music.set(url, {source, at: this.now()})
    return source
  }

  private searches(track: MatchTrack, yt: YtDlpContext): (() => Promise<Candidate[]>)[] {
    const flat = async (target: string, origin: Candidate['origin']) => {
      const {stdout, code} = await run(
        yt.binary,
        ['--ignore-config', ...ENCODING_ARGS, '--flat-playlist', '-J', '--playlist-end', '8', '--socket-timeout', '20', '--', target],
        {env: yt.env, signal: yt.signal, collectStdout: true, maxStdout: 20_000_000, timeoutMs: 45_000},
      )
      if (code !== 0) return []
      return parseFlatSearchText(stdout, origin)
    }
    return searchPlan(track).map((step) =>
      step.kind === 'songsApi' ? () => searchSongs(step.query, this.fetcher, yt.signal) : () => flat(step.target, step.origin),
    )
  }

  // ------------------------------------------------------------- music matching

  /** The key a remembered choice is stored under: the canonical link of the track, without share parameters. */
  private choiceKey(sourceUrl: string): string {
    const link = musicLink(sourceUrl)
    return link ? musicLinkUrl(link) : sourceUrl
  }

  /** Runs `load` once per key while the entry is fresh; a failed load is not kept. */
  private shared<T>(map: Map<string, {value: Promise<T>; at: number}>, key: string, load: () => Promise<T>): Promise<T> {
    const hit = map.get(key)
    if (hit && this.now() - hit.at < MUSIC_CACHE_MS) return hit.value
    if (map.size >= 200) map.delete(map.keys().next().value!)
    const value = load()
    const entry = {value, at: this.now()}
    map.set(key, entry)
    value.catch(() => {
      if (map.get(key) === entry) map.delete(key)
    })
    return value
  }

  private albumSearch(track: MatchTrack, signal?: AbortSignal): Promise<AlbumHit[]> {
    return this.shared(this.albumSearches, JSON.stringify([track.artists[0], track.album]), async () => (await searchAlbums(track, this.fetcher)).albums).then((albums) => {
      signal?.throwIfAborted()
      return albums
    })
  }

  private albumPage(browseId: string, signal?: AbortSignal): Promise<AlbumPage | undefined> {
    return this.shared(this.albumPages, browseId, () => fetchAlbumPage(browseId, this.fetcher)).then((page) => {
      signal?.throwIfAborted()
      return page
    })
  }

  private matchTrackOf(track: SourceTrack): MatchTrack {
    return {
      title: track.title,
      artists: track.artists,
      durationMs: track.durationMs,
      album: track.album,
      trackNumber: track.trackNumber,
      trackCount: track.trackCount,
      year: track.releaseYear,
    }
  }

  /**
   * Finds the YouTube Music recording for a song from a music service. In order: the recording the user chose for this
   * link before; the same track on the same album (an album search, then the album page, shared by every track of the
   * album); the songs search and the rest of the search plan. A link without an album (Spotify tracks) takes the
   * album of the best songs-search result, and the release year then decides which edition of it.
   */
  private async matchTrack(track: SourceTrack, sourceUrl: string, ctx: EngineContext, yt: YtDlpContext): Promise<MusicPick | undefined> {
    const signal = ctx.signal
    const chosen = await this.choices?.get(this.choiceKey(sourceUrl))
    if (chosen) {
      const candidate: Candidate = {
        url: `https://music.youtube.com/watch?v=${chosen.videoId}`,
        title: chosen.title ?? track.title,
        artists: [],
        durationSec: chosen.durationSec,
        origin: 'ytm-song',
      }
      return {candidate, confidence: 1, method: 'chosen', alternates: [], chosenTitle: chosen.title}
    }
    const source = this.matchTrackOf(track)
    const rethrowAbort = () => signal?.throwIfAborted()
    const plan = this.searches(source, yt)
    let first: Candidate[] | undefined
    let hinted = source
    if (!albumApplies(source)) {
      first = await plan[0]().catch(() => {
        rethrowAbort()
        return [] as Candidate[]
      })
      const album = albumHint(source, first)
      if (album) hinted = {...source, album}
    }
    const viaAlbum = await matchViaAlbum(
      hinted,
      {search: () => this.albumSearch(hinted, signal), page: (browseId) => this.albumPage(browseId, signal)},
      rethrowAbort,
    )
    if (viaAlbum) {
      return {
        candidate: viaAlbum.candidate,
        confidence: viaAlbum.confidence,
        method: 'album',
        album: viaAlbum.albumTitle,
        position: viaAlbum.position,
        alternates: [],
      }
    }
    const collected: Candidate[] = []
    const runs = plan.map((run, index) => async () => {
      const result = index === 0 && first ? first : await run()
      collected.push(...result)
      return result
    })
    const {best} = await pickBest(source, runs, rethrowAbort)
    if (!best) return
    const exclude = [videoIdOf(best.candidate.url)].filter((id): id is string => !!id)
    const alternates = rankAlternates(source, collected, {exclude}).map(({candidate, confidence}): MusicAlternate => ({
      url: candidate.url,
      title: [candidate.title, candidate.artists.join(', ')].filter(Boolean).join(' · '),
      ...(candidate.durationSec ? {durationSec: candidate.durationSec} : {}),
      confidence,
    }))
    return {candidate: best.candidate, confidence: best.confidence, method: 'search', alternates}
  }

  private musicMatch(service: MusicMatch['source'], sourceUrl: string, pick: MusicPick): MusicMatch {
    const {candidate} = pick
    const match: MusicMatch = {
      source: service,
      sourceUrl,
      matchedUrl: candidate.url,
      matchedTitle: pick.chosenTitle ?? [candidate.title, candidate.artists.join(', ')].filter(Boolean).join(' · '),
      confidence: pick.confidence,
      method: pick.method,
    }
    if (pick.method === 'album') {
      match.album = pick.album
      match.position = pick.position
    }
    if (candidate.durationSec) match.durationSec = candidate.durationSec
    if (pick.alternates.length) match.alternates = pick.alternates
    return match
  }

  async rememberMusicChoice(sourceUrl: string, choice: {matchedUrl: string; title?: string; durationSec?: number}): Promise<void> {
    if (!this.choices) throw new Error('Choices cannot be saved here.')
    if (!musicLink(sourceUrl) && !isShortMusicLink(sourceUrl)) throw new Error('This is not a music service link.')
    const videoId = videoIdOf(choice.matchedUrl)
    if (!videoId) throw new Error('Choose a YouTube or YouTube Music recording.')
    await this.choices.set(this.choiceKey(sourceUrl), {videoId, title: choice.title, durationSec: choice.durationSec})
  }

  async musicAlternates(sourceUrl: string, exclude: string[], ctx: EngineContext): Promise<MusicAlternate[]> {
    const source = await this.musicSource(sourceUrl, ctx.signal)
    if (source.kind !== 'track') return []
    const track = this.matchTrackOf(source.track)
    const found = await collectAlternates(track, this.searches(track, await this.ytdlp(ctx)), exclude.map((url) => videoIdOf(url) ?? url), () => ctx.signal?.throwIfAborted())
    return found.map(({candidate, confidence}) => ({
      url: candidate.url,
      title: [candidate.title, candidate.artists.join(', ')].filter(Boolean).join(' · '),
      ...(candidate.durationSec ? {durationSec: candidate.durationSec} : {}),
      confidence,
    }))
  }

  private async inspectMusic(url: string, ctx: EngineContext): Promise<InspectResult> {
    const source = await this.musicSource(url, ctx.signal)
    const service = serviceOf(url) ?? 'spotify'
    if (source.kind === 'collection')
      return {type: 'collection', url, title: source.collection.title, uploader: source.collection.owner}
    const track = source.track
    const yt = await this.ytdlp(ctx)
    const found = await this.matchTrack(track, url, ctx, yt)
    if (!found)
      throw new EngineError(`No matching recording was found on YouTube Music for “${track.title}”.`, 'tryAnother')
    const {info, raw} = await dumpInfo(yt, found.candidate.url, 'single')
    await this.cache.put(found.candidate.url, raw).catch(() => {})
    const matched = mediaInfoFromYtDlp(info, found.candidate.url)
    const media: MediaInfo = {
      ...matched,
      url,
      webpageUrl: track.url,
      title: track.title,
      uploader: track.artists.join(', ') || matched.uploader,
      duration: track.durationMs ? track.durationMs / 1000 : matched.duration,
      thumbnail: track.cover ?? matched.thumbnail,
      provider: serviceNames[service],
      engine: 'music',
      video: [],
      subtitles: [],
      chapters: 0,
      playlist: undefined,
      match: this.musicMatch(service, url, found),
      music: {
        artist: track.artists.join(', ') || undefined,
        album: track.album ?? found.candidate.album,
        track: track.title,
        releaseYear: track.releaseYear,
        isrc: track.isrc,
      },
    }
    return {type: 'media', media}
  }

  // ---------------------------------------------------------------- discovery

  async discover(
    url: string,
    page: {start: number; end: number},
    ctx: EngineContext,
    onEntry: (entry: PlaylistEntry) => void,
    onMeta: (meta: {title?: string; uploader?: string}) => void,
  ): Promise<DiscoveryResult> {
    await ctx.guard?.(url, ctx.signal)
    if (musicLink(url) || isShortMusicLink(url)) {
      const source = await this.musicSource(url, ctx.signal)
      if (source.kind !== 'collection') throw new EngineError('This link is a single track.', 'none')
      return musicPage(source.collection, page, onEntry, onMeta)
    }
    return discoverPage(await this.ytdlp(ctx), url, page, onEntry, onMeta)
  }

  // ---------------------------------------------------------------- downloads

  private template(task: Pick<DownloadTask, 'folder' | 'stem'>) {
    return {
      template: path.join(task.folder, `${task.stem}.%(ext)s`),
      chapters: path.join(task.folder, `${task.stem} - %(section_number)02d %(section_title)s.%(ext)s`),
    }
  }

  async download(task: DownloadTask, ctx: EngineContext, events: DownloadEvents): Promise<DownloadOutcome> {
    await ctx.guard?.(task.url, ctx.signal)
    await fs.mkdir(task.folder, {recursive: true})
    if (task.format.type === 'gallery') {
      const folder = path.join(task.folder, task.stem)
      events.onStage('downloading')
      const {files} = await downloadGallery(await this.gallery(ctx), task.url, folder, task.expectedCount, task.format.items, (done, total) =>
        events.onProgress({percent: total ? Math.min(99, (done / total) * 100) : 0, downloadedBytes: undefined}),
      )
      return {file: files[0], files: files.slice(1)}
    }
    if (musicLink(task.url) || isShortMusicLink(task.url)) return this.downloadMusic(task, ctx, events)
    // A restricted song inspected earlier carries its alternate upload.
    const matched = task.match?.source === 'youtube' ? task.match.matchedUrl : undefined
    try {
      const outcome = await this.downloadYtDlp(task, matched ?? task.url, task.format, task.options, ctx, events)
      if (!matched) return outcome
      events.onStage('processing', 'Tagging')
      await this.tag(outcome.file, this.originalTrack(task), ctx.signal).catch(() => {})
      return {...outcome, match: task.match}
    } catch (error) {
      if (ctx.signal?.aborted || !isYouTube(task.url) || !isGenericUnavailable(errorText(error))) throw error
      return this.recoverRestricted(task, ctx, events, error, matched ? [matched] : [])
    }
  }

  // ------------------------------------------------- regionally restricted songs

  /** The song as the user knows it: original title, artist, album and artwork. */
  private originalTrack(task: Pick<DownloadTask, 'url' | 'title' | 'music' | 'duration' | 'thumbnail'>, video?: WatchProbe['video']): SourceTrack {
    const id = youTubeVideoId(task.url)
    const artists = task.music?.artist ? task.music.artist.split(/\s*,\s*/) : video?.author ? [stripTopic(video.author)] : []
    const seconds = video?.lengthSeconds ?? task.duration
    return {
      title: task.music?.track ?? video?.title ?? task.title,
      artists,
      durationMs: seconds ? seconds * 1000 : undefined,
      url: task.url,
      album: task.music?.album,
      cover: id ? `https://i.ytimg.com/vi/${id}/hqdefault.jpg` : task.thumbnail,
      trackNumber: (task.music as {trackNumber?: number} | undefined)?.trackNumber,
      releaseYear: task.music?.releaseYear,
    }
  }

  /** Probes the anonymous watch page; returns the video details only for a deterministic restriction. */
  private async restriction(url: string, signal?: AbortSignal): Promise<WatchProbe['video']> {
    const id = youTubeVideoId(url)
    if (!id) return
    try {
      const probe = await probeWatchPage(id, this.fetcher, signal)
      return classifyProbe(probe) === 'restricted' ? probe.video : undefined
    } catch {
      return
    }
  }

  /** Up to three other uploads of the same recording, best first. */
  private async alternates(track: SourceTrack, exclude: string[], yt: YtDlpContext) {
    return collectAlternates(track, this.searches(track, yt), exclude, () => yt.signal?.throwIfAborted())
  }

  private restrictedError(musicLike: boolean, notes: string[]) {
    return new EngineError(musicLike ? restrictedMusicMessage : restrictedMessage, musicLike ? 'signIn' : 'none', notes.join('\n'), false, false)
  }

  private async recoverRestricted(
    task: DownloadTask,
    ctx: EngineContext,
    events: DownloadEvents,
    original: unknown,
    tried: string[],
  ): Promise<DownloadOutcome> {
    const video = await this.restriction(task.url, ctx.signal)
    if (!video) throw original
    const musicLike = isMusicLike({url: task.url, author: video.author, groupUrl: task.groupUrl})
    const notes = [`YouTube reports ${task.url} as unplayable without an account in this region.`, ...tried.map((url) => `Tried ${url}: unavailable`)]
    if (!musicLike) throw this.restrictedError(false, notes)
    events.onStage('downloading', 'Finding another copy')
    const track = this.originalTrack(task, video)
    const exclude = [video.id, ...tried.map((url) => youTubeVideoId(url) ?? '')]
    const candidates = await this.alternates(track, exclude, await this.ytdlp(ctx))
    if (!candidates.length) notes.push('No other upload of this song matched closely enough.')
    const audio = task.format.type === 'audio'
    const options: DownloadOptions = audio
      ? {...task.options, embedThumbnail: task.options.embedThumbnail ?? true, embedMetadata: true}
      : task.options
    for (const {candidate, confidence} of candidates) {
      ctx.signal?.throwIfAborted()
      events.onStage('downloading', 'Downloading another copy')
      try {
        const outcome = await this.downloadYtDlp(task, candidate.url, task.format, options, ctx, events)
        const match: MusicMatch = {
          source: 'youtube',
          sourceUrl: task.url,
          matchedUrl: candidate.url,
          matchedTitle: [candidate.title, candidate.artists.join(', ')].filter(Boolean).join(' · '),
          confidence,
        }
        events.onMatch?.(match)
        events.onStage('processing', 'Tagging')
        await this.tag(outcome.file, track, ctx.signal).catch(() => {})
        return {...outcome, match}
      } catch (error) {
        if (ctx.signal?.aborted || !isGenericUnavailable(errorText(error))) throw error
        notes.push(`Tried ${candidate.url} (${Math.round(confidence * 100)}%): unavailable`)
      }
    }
    throw this.restrictedError(true, notes)
  }

  private async inspectRestricted(url: string, ctx: EngineContext): Promise<InspectResult | undefined> {
    const video = await this.restriction(url, ctx.signal)
    if (!video) return
    const notes = [`YouTube reports ${url} as unplayable without an account in this region.`]
    if (!isMusicLike({url, author: video.author})) throw this.restrictedError(false, notes)
    const track = this.originalTrack({url, title: video.title, duration: video.lengthSeconds}, video)
    const yt = await this.ytdlp(ctx)
    const candidates = await this.alternates(track, [video.id], yt)
    if (!candidates.length) notes.push('No other upload of this song matched closely enough.')
    for (const {candidate, confidence} of candidates) {
      try {
        const {info, raw} = await dumpInfo(yt, candidate.url, 'single')
        if (info._type === 'playlist') continue
        await this.cache.put(candidate.url, raw).catch(() => {})
        const matched = mediaInfoFromYtDlp(info, candidate.url)
        const media: MediaInfo = {
          ...matched,
          url,
          webpageUrl: url,
          title: track.title,
          uploader: track.artists.join(', ') || matched.uploader,
          duration: video.lengthSeconds ?? matched.duration,
          thumbnail: track.cover ?? matched.thumbnail,
          match: {
            source: 'youtube',
            sourceUrl: url,
            matchedUrl: candidate.url,
            matchedTitle: [candidate.title, candidate.artists.join(', ')].filter(Boolean).join(' · '),
            confidence,
          },
          music: {artist: track.artists.join(', ') || undefined, album: matched.music?.album, track: track.title},
        }
        return {type: 'media', media}
      } catch (error) {
        if (ctx.signal?.aborted) throw error
        notes.push(`Tried ${candidate.url} (${Math.round(confidence * 100)}%): ${isGenericUnavailable(errorText(error)) ? 'unavailable' : 'failed'}`)
      }
    }
    throw this.restrictedError(true, notes)
  }

  private async downloadYtDlp(
    task: DownloadTask,
    sourceUrl: string,
    format: FormatRequest,
    options: DownloadOptions,
    ctx: EngineContext,
    events: DownloadEvents,
  ): Promise<DownloadOutcome> {
    const yt = await this.ytdlp(ctx)
    const {template, chapters} = this.template(task)
    const turboWanted = ctx.settings.turbo.enabled && !options.section
    let infoJson = await this.cache.get(sourceUrl)
    if (!infoJson && turboWanted) {
      // One extraction serves both the selection preview and the real run.
      const {raw, info} = await dumpInfo(yt, sourceUrl, 'single')
      if (info._type !== 'playlist') infoJson = await this.cache.put(sourceUrl, raw)
    }
    const argsInput = {
      source: infoJson ? {infoJson} : {url: sourceUrl},
      format,
      options,
      template,
      chapterTemplate: chapters,
      network: ctx.settings,
      cookies: ctx.cookies,
      tools: {ffmpeg: yt.ffmpeg, jsRuntime: yt.jsRuntime, cacheDir: yt.cacheDir},
      youtube: isYouTube(sourceUrl),
      sleepRequests: task.sleepRequests,
      title: task.music ? undefined : task.title,
    }
    const args = buildDownloadArgs(argsInput)
    let formatSizes: Record<string, number> | undefined
    let turbo = false
    if (turboWanted && infoJson) {
      try {
        const result = await this.turbo(yt, buildDownloadArgs({...argsInput, preview: true}), ctx, events)
        formatSizes = result.sizes
        turbo = result.used
      } catch (error) {
        if (ctx.signal?.aborted) throw error
        // Fallback: the plain run starts clean from yt-dlp's own .part handling.
        turbo = false
      }
    }
    const parser = new ProgressParser({formatSizes, audioTarget: format.type === 'audio' ? format.target : undefined})
    events.onStage(turbo ? 'processing' : 'downloading', turbo ? 'Finishing' : undefined)
    const outcome = await runDownload(yt, args, parser, {
      onProgress: (progress) => {
        if (!turbo) events.onProgress(progress)
      },
      onPhase: (phase) => events.onStage('processing', phase),
    })
    // The reported path is checked here, before tagging and before the job is saved (see outputs.ts).
    const settled = await settleOutputs({file: outcome.file!, files: outcome.files}, {template, log: (message) => console.warn(message)})
    if (format.type === 'video' && format.scaleHeight) await this.convertSmaller(yt.ffmpeg, settled.file, format.scaleHeight, task.duration, ctx.signal, events)
    return {file: settled.file, files: settled.files, turbo}
  }

  /** A converted size: the downloaded file becomes the smaller one (convert.ts). */
  private async convertSmaller(ffmpeg: string | undefined, file: string, height: number, duration: number | undefined, signal: AbortSignal | undefined, events: DownloadEvents) {
    events.onStage('processing', CONVERT_PHASE)
    events.onProgress({percent: 0})
    await convertDownload({ffmpeg, file, height, duration, signal, onProgress: (percent) => events.onProgress({percent})})
  }

  /** Downloads eligible streams with parallel ranges. Returns whether it was used. */
  private async turbo(
    yt: YtDlpContext,
    previewArgs: string[],
    ctx: EngineContext,
    events: DownloadEvents,
  ): Promise<{used: boolean; sizes?: Record<string, number>}> {
    const selection = await resolveSelection(yt, previewArgs)
    const sizes = Object.fromEntries(selection.formats.filter((f) => f.size).map((f) => [f.formatId, f.size!]))
    const direct = selection.formats.every((f) => !f.fragmented && f.url)
    const total = selection.formats.reduce((sum, f) => sum + (f.size ?? 0), 0)
    if (!direct || total < TURBO_MIN_BYTES) return {used: false, sizes}
    const targets = selection.formats.map((f) => streamFileName(selection, f))
    // A complete file from an earlier run needs no transfer.
    const pending = []
    for (const [index, format] of selection.formats.entries())
      if (!(await fs.stat(targets[index]).catch(() => undefined))) pending.push({url: format.url, headers: format.headers, size: format.size, target: targets[index]})
    if (!pending.length) return {used: true, sizes}
    events.onStage('downloading')
    try {
      await turboDownload(pending, {
        connections: ctx.settings.turbo.connections,
        signal: ctx.signal,
        fetch: this.fetcher,
        onProgress: (p) =>
          events.onProgress({
            percent: Math.min(99, (p.downloadedBytes / p.totalBytes) * 100),
            downloadedBytes: p.downloadedBytes,
            totalBytes: p.totalBytes,
            speed: p.speed,
            eta: p.eta,
          }),
      })
      return {used: true, sizes}
    } catch (error) {
      if (ctx.signal?.aborted) throw error
      await discardTurbo(pending.map((p) => p.target))
      if (error instanceof TurboError) return {used: false, sizes}
      throw error
    }
  }

  private async downloadMusic(task: DownloadTask, ctx: EngineContext, events: DownloadEvents): Promise<DownloadOutcome> {
    events.onStage('downloading', 'Matching on YouTube Music')
    const service = serviceOf(task.url) ?? 'spotify'
    let track: SourceTrack | undefined
    if (task.music?.track || task.title) {
      track = {
        title: task.music?.track ?? task.title,
        artists: task.music?.artist ? task.music.artist.split(/\s*,\s*/) : [],
        durationMs: task.duration ? task.duration * 1000 : undefined,
        url: task.url,
        album: task.music?.album,
        cover: task.thumbnail,
        trackNumber: task.music?.trackNumber,
        trackCount: task.music?.trackCount,
        releaseYear: task.music?.releaseYear,
        isrc: task.music?.isrc,
      }
      // a Deezer track from a collection list has no code yet: one cached lookup, and a failure leaves it empty
      if (service === 'deezer' && !track.isrc) {
        const source = await this.musicSource(task.url, ctx.signal).catch(() => undefined)
        if (source?.kind === 'track') track.isrc = source.track.isrc
      }
    }
    if (!track?.artists.length) {
      const source = await this.musicSource(task.url, ctx.signal)
      if (source.kind !== 'track') throw new EngineError('Open the album or playlist to choose tracks.', 'none')
      track = {...source.track, trackNumber: track?.trackNumber ?? source.track.trackNumber, album: track?.album ?? source.track.album}
    }
    // A match made earlier (at inspection) is reused, unless the user has since chosen another recording for this link.
    const chosen = await this.choices?.get(this.choiceKey(task.url))
    let match = task.match?.matchedUrl && (!chosen || videoIdOf(task.match.matchedUrl) === chosen.videoId) ? task.match : undefined
    if (!match) {
      const found = await this.matchTrack(track, task.url, ctx, await this.ytdlp(ctx))
      if (!found) throw new EngineError(`No matching recording was found on YouTube Music for “${track.title}”.`, 'tryAnother')
      match = this.musicMatch(service, task.url, found)
      events.onMatch?.(match)
    }
    const format: FormatRequest =
      task.format.type === 'audio' ? task.format : {type: 'audio', target: 'mp3', quality: 'best'}
    const options: DownloadOptions = {...task.options, embedThumbnail: task.options.embedThumbnail ?? true, embedMetadata: true}
    const outcome = await this.downloadYtDlp(task, match.matchedUrl!, format, options, ctx, events)
    events.onStage('processing', 'Tagging')
    await this.tag(outcome.file, track, ctx.signal).catch(() => {})
    return {...outcome, match}
  }

  async addLyrics(file: string, song: LyricsQuery, signal?: AbortSignal) {
    return (await this.findLyrics(file, song, signal).catch(() => 'none')) === 'found'
  }

  async findLyrics(file: string, song: LyricsQuery, signal?: AbortSignal): Promise<LyricsOutcome> {
    const {text, outcome} = await findLyricsText(song, {fetch: this.fetcher, signal, userAgent: this.options.userAgent})
    if (!text || signal?.aborted) return outcome === 'found' ? 'none' : outcome
    if (!(await writeLyrics(this.tools.ffmpeg(), file, text, signal).catch(() => undefined))) throw new Error('Keepfin could not save the lyrics next to this song.')
    return 'found'
  }

  /** Writes the source service's metadata and square album art over YouTube's. */
  private async tag(file: string, track: SourceTrack, signal?: AbortSignal) {
    const ffmpeg = this.tools.ffmpeg()
    if (!ffmpeg) return
    await tagFile(ffmpeg, file, track, {fetch: this.fetcher, signal})
  }

  async discard(task: Pick<DownloadTask, 'folder' | 'stem' | 'format'>) {
    const names = await fs.readdir(task.folder).catch(() => [] as string[])
    const lower = task.stem.toLowerCase()
    await Promise.all(
      names
        .filter((name) => {
          const n = name.toLowerCase()
          return n.startsWith(lower + '.') || n.startsWith(lower + ' - ')
        })
        .map((name) => fs.rm(path.join(task.folder, name), {force: true, recursive: task.format.type === 'gallery'}).catch(() => {})),
    )
    if (task.format.type === 'gallery') await fs.rm(path.join(task.folder, task.stem), {recursive: true, force: true}).catch(() => {})
  }
}

function errorText(error: unknown): string {
  if (error instanceof EngineError) return `${error.stderr}\n${error.message}`
  return error instanceof Error ? error.message : String(error ?? '')
}

function collectionResult(url: string, info: RawInfo): InspectResult {
  const text = (value: unknown) => (typeof value === 'string' && value.trim() ? value.slice(0, 300) : undefined)
  const record = info as Record<string, unknown>
  return {
    type: 'collection',
    url,
    title: text(info.title) ?? text(record.playlist_title),
    uploader: text(info.uploader) ?? text(info.channel) ?? text(record.playlist_uploader),
  }
}

export function musicPage(
  collection: SourceCollection,
  page: {start: number; end: number},
  onEntry: (entry: PlaylistEntry) => void,
  onMeta: (meta: {title?: string; uploader?: string}) => void,
): DiscoveryResult {
  onMeta({title: collection.title, uploader: collection.owner})
  const slice = collection.tracks.slice(page.start - 1, page.end)
  slice.forEach((track, index) =>
    onEntry({
      url: track.url,
      title: track.title,
      artist: track.artists.join(', ') || undefined,
      album: track.album,
      thumbnail: track.cover,
      uploader: track.artists.join(', ') || undefined,
      duration: track.durationMs ? Math.round(track.durationMs / 1000) : undefined,
      position: page.start + index,
      albumTrackCount: collection.type === 'album' ? track.trackCount : undefined,
      releaseYear: collection.type === 'album' ? track.releaseYear : undefined,
    }),
  )
  const truncated = collection.tracks.length > page.end
  const service = serviceNames[collection.service]
  const hidden =
    collection.total && collection.total > collection.tracks.length
      ? `${service} shows ${collection.tracks.length} of ${collection.total} tracks publicly.`
      : collection.service === 'spotify' && collection.tracks.length >= 100
        ? 'Spotify shows only the first 100 tracks of large playlists publicly.'
        : undefined
  return {truncated, notice: hidden}
}

