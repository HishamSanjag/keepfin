// Playability: files whose codecs Chromium decodes but whose container it does not (MKV, AVI, FLV, TS)
// are remuxed once, losslessly, into a cached MP4/WebM. Everything else plays from the original file.
import fs from 'node:fs/promises'
import path from 'node:path'
import {extensionOf, mediaUrl, type PrepareResult} from '../../shared/library.js'
import {run} from '../engine/process.js'
import type {LibraryEntry} from './index.js'
import {probeFile, type Probe} from './probe.js'

export const PLAY_CACHE_LIMIT = 5 * 1024 ** 3

const nativeContainers = new Set(['mp4', 'm4v', 'mov', 'webm', 'mp3', 'm4a', 'aac', 'ogg', 'oga', 'opus', 'wav', 'flac'])
const playableVideo = new Set(['h264', 'vp9', 'vp8', 'av1', 'hevc'])
const playableAudio = new Set(['aac', 'mp3', 'opus', 'vorbis', 'flac'])
const webmVideo = new Set(['vp9', 'vp8', 'av1'])
const webmAudio = new Set(['opus', 'vorbis'])

const codecNames: Record<string, string> = {
  hevc: 'HEVC',
  h264: 'H.264',
  mpeg4: 'MPEG-4',
  mpeg2video: 'MPEG-2',
  mpeg1video: 'MPEG-1',
  wmv1: 'Windows Media Video',
  wmv2: 'Windows Media Video',
  wmv3: 'Windows Media Video',
  vc1: 'VC-1',
  prores: 'ProRes',
  ac3: 'Dolby Digital',
  eac3: 'Dolby Digital Plus',
  dts: 'DTS',
  truehd: 'Dolby TrueHD',
  wmav2: 'Windows Media Audio',
  wmapro: 'Windows Media Audio',
  pcm_s16le: 'PCM',
  pcm_s24le: 'PCM',
}
const codecLabel = (codec: string) => codecNames[codec] ?? codec.toUpperCase()

export type PlayPlan =
  | {kind: 'direct'; video?: string; audio?: string}
  | {kind: 'remux'; target: 'mp4' | 'webm'; video?: string; audio?: string}
  | {kind: 'unsupported'; reason: string}

/** Decides how to play a file from its container extension and streams. Pure. */
export function planPlayback(ext: string, probe: Probe | undefined): PlayPlan {
  const video = probe?.video?.codec
  const audio = probe?.audio[0]?.codec
  if (nativeContainers.has(ext)) return {kind: 'direct', video, audio}
  if (!probe) return {kind: 'unsupported', reason: 'This file could not be read. Open it in another app.'}
  if (video && !playableVideo.has(video))
    return {kind: 'unsupported', reason: `This video uses ${codecLabel(video)}, which the built-in player cannot play. Open it in another app.`}
  if (audio && !playableAudio.has(audio))
    return {kind: 'unsupported', reason: `The sound in this file uses ${codecLabel(audio)}, which the built-in player cannot play. Open it in another app.`}
  if (!video && !audio) return {kind: 'unsupported', reason: 'This file has no audio or video the built-in player can play.'}
  const fitsWebm = (!video || webmVideo.has(video)) && (!audio || webmAudio.has(audio))
  if (fitsWebm) return {kind: 'remux', target: 'webm', video, audio}
  if (audio === 'vorbis') return {kind: 'unsupported', reason: 'This combination of video and sound cannot be repackaged for the built-in player. Open it in another app.'}
  return {kind: 'remux', target: 'mp4', video, audio}
}

export class PlayCache {
  private readonly inflight = new Map<string, Promise<PrepareResult>>()
  private readonly controller = new AbortController()

  constructor(
    private readonly directory: string,
    private readonly ffmpeg: () => string | undefined,
    private readonly limit = PLAY_CACHE_LIMIT,
  ) {}

  close() {
    this.controller.abort()
  }

  /** The newest cached file for an item id (what keepfin-media://prepared/<id> serves). */
  async path(id: string): Promise<string | undefined> {
    if (!/^[a-f0-9]{8,64}$/.test(id)) return
    const names = await fs.readdir(this.directory).catch(() => [] as string[])
    let best: {file: string; at: number} | undefined
    for (const name of names) {
      if (!name.startsWith(`${id}-`) || name.endsWith('.part')) continue
      const file = path.join(this.directory, name)
      const stat = await fs.stat(file).catch(() => undefined)
      if (stat?.isFile() && (!best || stat.mtimeMs > best.at)) best = {file, at: stat.mtimeMs}
    }
    return best?.file
  }

  prepare(entry: LibraryEntry): Promise<PrepareResult> {
    const running = this.inflight.get(entry.id)
    if (running) return running
    const work = this.run(entry).finally(() => this.inflight.delete(entry.id))
    this.inflight.set(entry.id, work)
    return work
  }

  private async run(entry: LibraryEntry): Promise<PrepareResult> {
    if (entry.kind === 'image') return {url: mediaUrl('item', entry.id), remuxed: false}
    if (entry.kind !== 'video' && entry.kind !== 'audio') return {unsupported: true, reason: 'This kind of file cannot be played.'}
    const stat = await fs.stat(entry.path).catch(() => undefined)
    if (!stat?.isFile()) return {unsupported: true, reason: 'The file is no longer at its saved location.'}
    const ext = extensionOf(entry.path)
    const ffmpeg = this.ffmpeg()
    // Containers Chromium opens natively need no probe; the codec names only help the renderer explain failures.
    const probe = nativeContainers.has(ext) && !ffmpeg ? undefined : await probeFile(ffmpeg, entry.path, this.controller.signal)
    const plan = planPlayback(ext, probe)
    if (plan.kind === 'unsupported') return {unsupported: true, reason: plan.reason}
    if (plan.kind === 'direct') return {url: mediaUrl('item', entry.id), remuxed: false, video: plan.video, audio: plan.audio}
    if (!ffmpeg) return {unsupported: true, reason: 'The media processor is not available, so this file cannot be prepared for playback.'}
    const output = path.join(this.directory, `${entry.id}-${stat.size}-${Math.round(stat.mtimeMs)}.${plan.target}`)
    const cached = await fs.stat(output).catch(() => undefined)
    if (cached?.isFile() && cached.size > 0) {
      const now = new Date()
      await fs.utimes(output, now, now).catch(() => {})
      return {url: mediaUrl('prepared', entry.id), remuxed: true, video: plan.video, audio: plan.audio}
    }
    await fs.mkdir(this.directory, {recursive: true})
    const temporary = `${output}.part`
    const attempt = (audioMap: string) =>
      run(
        ffmpeg,
        [
          '-hide_banner', '-nostdin', '-loglevel', 'error', '-y', '-protocol_whitelist', 'file', '-i', entry.path,
          ...(plan.video ? ['-map', '0:v:0'] : []), '-map', audioMap, '-sn', '-dn', '-map_chapters', '-1',
          '-c', 'copy', ...(plan.video === 'hevc' ? ['-tag:v', 'hvc1'] : []),
          ...(plan.target === 'mp4' ? ['-movflags', '+faststart'] : []),
          '-f', plan.target === 'mp4' ? 'mp4' : 'webm', temporary,
        ],
        {signal: this.controller.signal, timeoutMs: 15 * 60_000, lowPriority: true},
      )
    try {
      let result = await attempt('0:a?')
      if (result.code !== 0) {
        await fs.rm(temporary, {force: true})
        result = await attempt('0:a:0?')
      }
      if (result.code !== 0 || !(await fs.stat(temporary).catch(() => undefined))?.size)
        return {unsupported: true, reason: 'This file could not be repackaged for the built-in player. Open it in another app.'}
      await fs.rename(temporary, output)
    } catch (error) {
      if (this.controller.signal.aborted) throw error
      return {unsupported: true, reason: 'This file could not be repackaged for the built-in player. Open it in another app.'}
    } finally {
      await fs.rm(temporary, {force: true}).catch(() => {})
    }
    await this.prune(output).catch(() => {})
    return {url: mediaUrl('prepared', entry.id), remuxed: true, video: plan.video, audio: plan.audio}
  }

  /** Least recently used files go first until the cache fits its budget. */
  async prune(keep?: string) {
    const names = await fs.readdir(this.directory).catch(() => [] as string[])
    const files: {file: string; size: number; at: number}[] = []
    for (const name of names) {
      const file = path.join(this.directory, name)
      const stat = await fs.stat(file).catch(() => undefined)
      if (stat?.isFile()) files.push({file, size: stat.size, at: stat.mtimeMs})
    }
    let total = files.reduce((sum, item) => sum + item.size, 0)
    for (const item of files.sort((a, b) => a.at - b.at)) {
      if (total <= this.limit) break
      if (item.file === keep) continue
      await fs.rm(item.file, {force: true})
      total -= item.size
    }
  }
}
