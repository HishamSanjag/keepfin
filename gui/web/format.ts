import {
  formatBytes,
  formatDuration,
  type AudioCodec,
  type AudioOption,
  type VideoCodec,
} from '../shared/contracts'

export {formatBytes, formatDuration}
export {videoLabel} from '../shared/core/choice'

const videoCodecs: Record<VideoCodec, string> = {
  h264: 'H.264',
  hevc: 'HEVC',
  vp9: 'VP9',
  av1: 'AV1',
  other: 'Other codec',
}
const audioCodecs: Record<AudioCodec, string> = {
  aac: 'AAC',
  opus: 'Opus',
  mp3: 'MP3',
  vorbis: 'Vorbis',
  flac: 'FLAC',
  other: 'Audio',
}
export const videoCodecLabel = (codec: VideoCodec) => videoCodecs[codec] || codec
export const audioCodecLabel = (codec: AudioCodec) => audioCodecs[codec] || codec

export function sizeLabel(size?: number, approx?: boolean) {
  const text = formatBytes(size)
  return text ? `${approx ? '≈' : ''}${text}` : ''
}
export function speedLabel(bytesPerSecond?: number) {
  const text = formatBytes(bytesPerSecond)
  return text ? `${text}/s` : ''
}
export function etaLabel(seconds?: number) {
  if (seconds === undefined || !Number.isFinite(seconds) || seconds < 0) return ''
  const s = Math.round(seconds)
  if (s < 60) return `${s}s left`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${String(s % 60).padStart(2, '0')}s left`
  return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m left`
}
export function languageName(code?: string) {
  if (!code) return ''
  try {
    const name = new Intl.DisplayNames(undefined, {type: 'language'}).of(code)
    return name && name !== code ? name : code
  } catch {
    return code
  }
}
export function audioLabel(option: AudioOption) {
  return [audioCodecLabel(option.codec), option.abr ? `${Math.round(option.abr)} kbps` : '']
    .filter(Boolean)
    .join(' · ')
}

/** Parses "83", "1:23" or "1:02:03" into seconds. Empty → undefined, invalid → NaN. */
export function parseClock(value: string): number | undefined {
  const text = value.trim()
  if (!text) return undefined
  if (!/^\d{1,3}(:\d{1,2}){0,2}(\.\d+)?$/.test(text)) return NaN
  const parts = text.split(':').map(Number)
  if (parts.slice(1).some((part) => part >= 60)) return NaN
  return parts.reduce((total, part) => total * 60 + part, 0)
}

export function relativeTime(timestamp?: number) {
  if (!timestamp) return ''
  const date = new Date(timestamp)
  const now = new Date()
  const time = date.toLocaleTimeString(undefined, {hour: '2-digit', minute: '2-digit'})
  const day = 86_400_000
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  if (timestamp >= startOfToday) return `Today ${time}`
  if (timestamp >= startOfToday - day) return `Yesterday ${time}`
  return date.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    year: date.getFullYear() === now.getFullYear() ? undefined : 'numeric',
  })
}

const hosts: [RegExp, string][] = [
  [/(^|\.)music\.youtube\.com$/, 'YouTube Music'],
  [/(^|\.)(youtube\.com|youtu\.be)$/, 'YouTube'],
  [/(^|\.)(open\.)?spotify\.com$|^spotify\.link$|^spoti\.fi$/, 'Spotify'],
  [/(^|\.)music\.apple\.com$/, 'Apple Music'],
  [/(^|\.)deezer\.(com|page\.link)$/, 'Deezer'],
  [/(^|\.)tiktok\.com$/, 'TikTok'],
  [/(^|\.)instagram\.com$/, 'Instagram'],
  [/(^|\.)(x|twitter)\.com$/, 'X'],
  [/(^|\.)facebook\.com$|(^|\.)fb\.watch$/, 'Facebook'],
  [/(^|\.)reddit\.com$|(^|\.)redd\.it$/, 'Reddit'],
  [/(^|\.)vimeo\.com$/, 'Vimeo'],
  [/(^|\.)twitch\.tv$/, 'Twitch'],
  [/(^|\.)soundcloud\.com$/, 'SoundCloud'],
  [/(^|\.)bandcamp\.com$/, 'Bandcamp'],
  [/(^|\.)dailymotion\.com$/, 'Dailymotion'],
  [/(^|\.)bilibili\.com$/, 'Bilibili'],
  [/(^|\.)pinterest\./, 'Pinterest'],
  [/(^|\.)imgur\.com$/, 'Imgur'],
]
export function providerFromUrl(url?: string) {
  if (!url) return ''
  try {
    const host = new URL(url).hostname.replace(/^www\./, '')
    return hosts.find(([pattern]) => pattern.test(host))?.[1] || host
  } catch {
    return ''
  }
}
export function isMusicService(url?: string) {
  return ['Spotify', 'Apple Music', 'Deezer'].includes(providerFromUrl(url))
}
export function hostOf(url?: string) {
  try {
    return new URL(url || '').hostname.replace(/^www\./, '')
  } catch {
    return ''
  }
}
export function fileName(path?: string) {
  return path ? path.split(/[\\/]/).pop() || path : ''
}
export function plural(count: number, one: string, many = `${one}s`) {
  return `${count.toLocaleString()} ${count === 1 ? one : many}`
}

/** Extracts unique http(s) links from pasted or dropped text, in order. */
export function extractLinks(text: string) {
  const found = text.match(/https?:\/\/[^\s<>"'`]+/gi) || []
  const links: string[] = []
  for (const raw of found) {
    const link = raw.replace(/[),.;!?\]]+$/, '')
    if (!links.includes(link)) links.push(link)
  }
  return links
}
