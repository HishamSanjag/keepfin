// Metadata work: tags, sidecars and posters for new and changed files, loudness measuring, and lyrics.
import fs from 'node:fs/promises'
import {normalizeTags, normalizeTitle, parseLrc, type Lyrics} from '../../../shared/library.js'
import {posterFrame} from '../art.js'
import {analyseGain} from '../loudness.js'
import {probeFile} from '../probe.js'
import {findSidecars, readEmbeddedLyrics, readInfoChapters, readTags} from '../tags.js'
import type {LibraryEntry} from '../index.js'
import type {LibraryCore} from './core.js'

export class Enrichment {
  private readonly enrichQueue: string[] = []
  private enrichActive = 0
  private readonly loudnessQueue: string[] = []
  private loudnessRunning = false
  private loudnessTimer?: NodeJS.Timeout

  constructor(private readonly core: LibraryCore) {}

  /** True while metadata or loudness work is queued or running. */
  working() {
    return this.enrichQueue.length > 0 || this.enrichActive > 0 || this.loudnessQueue.length > 0 || this.loudnessRunning
  }

  /** Stops the loudness timer (the index is closing). */
  stop() {
    clearTimeout(this.loudnessTimer)
  }

  queueEnrich(id: string) {
    if (this.core.closing() || this.enrichQueue.includes(id)) return
    this.enrichQueue.push(id)
    this.pumpEnrich()
  }

  private pumpEnrich() {
    while (this.enrichActive < 2 && this.enrichQueue.length) {
      const id = this.enrichQueue.shift()!
      this.enrichActive++
      void this.enrich(id)
        .catch(() => {})
        .finally(() => {
          this.enrichActive--
          this.pumpEnrich()
        })
    }
  }

  private async enrich(id: string) {
    const entry = this.core.entries().get(id)
    if (!entry) return
    const stat = await fs.stat(entry.path).catch(() => undefined)
    if (!stat?.isFile()) {
      if (!entry.missing) {
        entry.missing = true
        this.core.changed()
      }
      return
    }
    entry.missing = undefined
    entry.size = stat.size
    entry.modifiedAt = Math.round(stat.mtimeMs)
    const sig = `${entry.size}:${entry.modifiedAt}`
    const file = entry.path
    const ffmpeg = this.core.options.ffmpeg()
    const sidecars = await findSidecars(file)
    entry.subPaths = sidecars.subtitles.length ? sidecars.subtitles.map((item) => item.path) : undefined
    entry.subtitles = sidecars.subtitles.length ? sidecars.subtitles.map((item) => item.label) : undefined
    entry.lrc = sidecars.lrc
    entry.infoJson = sidecars.info
    // the music video's own size and length: its row shows them (the song's are the item's own). Read first, set together.
    const video = entry.kind === 'audio' ? sidecars.video : undefined
    const videoSize = video ? (await fs.stat(video).catch(() => undefined))?.size : undefined
    const videoDuration = video ? (await probeFile(ffmpeg, video))?.duration : undefined
    entry.videoPath = video
    entry.videoSize = videoSize
    entry.videoDuration = videoDuration
    // a video listed on its own before its song arrived is the song's now (the file stays)
    const listed = entry.videoPath ? this.core.entryAt(entry.videoPath) : undefined
    if (listed && listed.id !== entry.id) this.core.drop([listed.id])
    let hasLyrics = !!sidecars.lrc
    if (entry.kind === 'audio') {
      const tags = await readTags(file, true)
      entry.title = tags.title ?? entry.title
      entry.artist = tags.artist ?? entry.artist
      entry.album = tags.album ?? entry.album
      entry.albumArtist = tags.albumArtist ?? entry.albumArtist
      entry.track = tags.track ?? entry.track
      entry.disc = tags.disc ?? entry.disc
      entry.year = tags.year ?? entry.year
      entry.genre = tags.genre ?? entry.genre
      entry.isrc = tags.isrc ?? entry.isrc
      if (entry.tagsPending) {
        const fromGenre = normalizeTags(tags.genre)
        if (fromGenre.length) entry.tags = fromGenre
        entry.tagsPending = undefined
      }
      entry.duration = tags.duration ?? entry.duration
      hasLyrics ||= tags.hasLyrics
      if (tags.gain !== undefined) {
        entry.gain = tags.gain
        entry.analysed = true
      }
      if (tags.picture) entry.hasArt = await this.core.putArt(entry.id, tags.picture.data, tags.picture.format)
    } else if (entry.kind === 'video') {
      entry.tagsPending = undefined
      const probe = await probeFile(ffmpeg, file)
      if (probe) {
        entry.duration = probe.duration ?? entry.duration
        entry.width = probe.video?.width ?? entry.width
        entry.height = probe.video?.height ?? entry.height
      } else {
        const tags = await readTags(file, false)
        entry.duration = tags.duration ?? entry.duration
      }
      const chapters = (sidecars.info ? await readInfoChapters(sidecars.info) : undefined) ?? (probe?.chapters.length ? probe.chapters : undefined)
      entry.chapters = chapters
    } else entry.hasArt = true
    entry.hasLyrics = hasLyrics || undefined
    if (entry.kind !== 'image' && !entry.hasArt) await this.makePoster(entry, ffmpeg)
    entry.sig = sig
    this.core.artRequests().delete(entry.id)
    this.core.clearTables()
    this.core.changed(true, [entry.id], false)
    if (entry.kind === 'audio') this.queueLoudness(entry.id)
  }

  async makePoster(entry: LibraryEntry, ffmpeg: string | undefined) {
    const request = this.core.artRequests().get(entry.id)
    if (request && this.core.options.jobArt) {
      const image = await this.core.options.jobArt(request).catch(() => undefined)
      if (image && (await this.core.putArt(entry.id, image.bytes, image.mime).catch(() => false))) {
        entry.hasArt = true
        return
      }
    }
    if (entry.kind === 'video' && ffmpeg) {
      const frame = await posterFrame(ffmpeg, entry.path, entry.duration)
      if (frame && (await this.core.putArt(entry.id, frame, 'image/jpeg').catch(() => false))) entry.hasArt = true
    }
  }

  /** Path of the art (cover, poster, or the image itself) served at keepfin-media://art/<id>. */
  async artPath(id: string): Promise<string | undefined> {
    const entry = this.core.entries().get(id)
    if (!entry) return
    if (entry.kind === 'image') return entry.path
    return (await this.core.art().find(id)) ?? this.core.options.persistence?.folderArt?.(id)
  }

  /** Replaces an item's cover (after a tag edit). */
  async setArt(id: string, bytes: Uint8Array, mime?: string) {
    const entry = this.core.entries().get(id)
    if (!entry) return
    entry.hasArt = await this.core.putArt(id, bytes, mime)
    this.core.changed(true, [id], false)
  }

  // ------------------------------------------------------------------ loudness

  queueLoudnessForAll() {
    for (const entry of this.core.entries().values()) if (entry.kind === 'audio') this.queueLoudness(entry.id)
  }

  queueLoudness(id: string) {
    if (this.core.options.analyse === false || !this.core.settings()?.normalize || this.core.closing()) return
    const entry = this.core.entries().get(id)
    if (!entry || entry.kind !== 'audio' || entry.analysed || entry.gain !== undefined || entry.missing) return
    if (this.loudnessQueue.includes(id)) return
    this.loudnessQueue.push(id)
    this.pumpLoudness()
  }

  private pumpLoudness() {
    if (this.loudnessRunning || this.loudnessTimer || !this.loudnessQueue.length || this.core.closing()) return
    this.loudnessTimer = setTimeout(() => {
      this.loudnessTimer = undefined
      void this.analyseNext()
    }, 500)
  }

  private async analyseNext() {
    const id = this.loudnessQueue.shift()
    const ffmpeg = this.core.options.ffmpeg()
    const entry = id ? this.core.entries().get(id) : undefined
    if (!id || !entry || !ffmpeg || !this.core.settings()?.normalize) return this.pumpLoudness()
    this.loudnessRunning = true
    try {
      const gain = await analyseGain(ffmpeg, entry.path)
      const current = this.core.entries().get(id)
      if (current && current.path === entry.path) {
        current.analysed = true
        if (gain !== undefined) current.gain = gain
        this.core.changed(true, [id], false)
      }
    } catch {
      // An unreadable file is not retried until it changes.
      entry.analysed = true
    } finally {
      this.loudnessRunning = false
      this.pumpLoudness()
    }
  }

  // ------------------------------------------------------------------ lyrics

  async lyrics(id: unknown): Promise<Lyrics | undefined> {
    const entry = this.core.item(id)
    if (entry.lrc) {
      const text = await fs.readFile(entry.lrc, 'utf8').catch(() => undefined)
      if (text !== undefined) {
        const parsed = parseLrc(text)
        if (parsed.lines.length) return parsed
      }
    }
    return readEmbeddedLyrics(entry.path)
  }

  /** Ids of items whose lyrics contain the text (case- and accent-insensitive). */
  async searchLyrics(text: unknown): Promise<string[]> {
    const wanted = typeof text === 'string' ? normalizeTitle(text) : ''
    if (wanted.length < 2) return []
    const found: string[] = []
    const candidates = this.core.all().filter((entry) => entry.hasLyrics)
    let next = 0
    const worker = async () => {
      while (next < candidates.length) {
        const entry = candidates[next++]
        const sig = `${entry.size}:${entry.modifiedAt}:${entry.lrc ?? ''}`
        let cached = this.core.lyricsText().get(entry.id)
        if (!cached || cached.sig !== sig) {
          const lyrics = await this.lyrics(entry.id).catch(() => undefined)
          cached = {sig, text: lyrics ? normalizeTitle(lyrics.lines.map((line) => line.text).join(' ')) : ''}
          this.core.lyricsText().set(entry.id, cached)
        }
        if (cached.text.includes(wanted)) found.push(entry.id)
      }
    }
    await Promise.all([worker(), worker(), worker(), worker()])
    return found
  }
}
