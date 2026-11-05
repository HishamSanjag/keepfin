// Pure mapping between a media inspection, the user's selection and the FormatRequest contract.
import type {
  AudioQuality,
  AudioTarget,
  Container,
  DownloadPreset,
  FormatRequest,
  MediaInfo,
  VideoOption,
} from '../../shared/contracts'
import {containerAfterPick, modesOf, openingPicks, picksForPreset, requestOf, sizeOf, type ChoicePicks} from '../../shared/core/choice'
import {audioCodecLabel, formatBytes, videoLabel} from '../format'

export type ChoiceTab = 'video' | 'audio' | 'gallery'
export type Choice = {
  tab: ChoiceTab
  videoId?: string
  container: Container
  /** Specific audio stream merged with the video (language track); default best. */
  audioTrack?: string
  target: AudioTarget
  quality: AudioQuality
  /** Specific source audio stream for the Audio tab; default best. */
  audioSource?: string
}

export const audioTargets: {value: AudioTarget; label: string; lossless?: boolean}[] = [
  {value: 'mp3', label: 'MP3'},
  {value: 'm4a', label: 'M4A'},
  {value: 'opus', label: 'Opus'},
  {value: 'flac', label: 'FLAC', lossless: true},
  {value: 'wav', label: 'WAV', lossless: true},
  {value: 'original', label: 'Original'},
]
export const audioQualities: {value: AudioQuality; label: string}[] = [
  {value: 'best', label: 'Best'},
  {value: '320', label: '320'},
  {value: '256', label: '256'},
  {value: '192', label: '192'},
  {value: '128', label: '128'},
]
export const qualityApplies = (target: AudioTarget) => target === 'mp3' || target === 'm4a' || target === 'opus'

/** The picks as the shared choice module reads them. */
export const picksOf = (choice: Choice): ChoicePicks => ({
  mode: choice.tab === 'audio' ? 'audio' : 'video',
  videoId: choice.videoId,
  container: choice.container,
  target: choice.target,
  quality: choice.quality,
  audioTrack: choice.audioTrack,
  audioSource: choice.audioSource,
})

export function availableTabs(media: MediaInfo): ChoiceTab[] {
  if (media.engine === 'gallery-dl' || media.gallery) return media.video.length ? ['video', 'audio', 'gallery'] : ['gallery']
  return modesOf(media)
}

/** The choice with the picks the shared choice module made. */
export const withPicks = (choice: Choice, picks: ChoicePicks): Choice => ({
  ...choice,
  tab: picks.mode,
  videoId: picks.videoId,
  container: picks.container,
  audioTrack: picks.audioTrack,
  target: picks.target,
  quality: picks.quality,
  audioSource: picks.audioSource,
})

/** What a preview opens with; a gallery has no Video or Audio to open on. */
export function defaultChoice(
  media: MediaInfo,
  preset: DownloadPreset,
  youtubeMusic: 'audio' | 'video' = 'audio',
): Choice {
  const picks = openingPicks(media, {
    preset,
    url: media.webpageUrl || media.url,
    music: media.engine === 'music' || !!media.match,
    youtubeMusic,
  })
  const choice = withPicks({tab: picks.mode, container: picks.container, target: picks.target, quality: picks.quality}, picks)
  return availableTabs(media)[0] === 'gallery' ? {...choice, tab: 'gallery'} : choice
}

export function selectedVideo(media: MediaInfo, choice: Choice) {
  return media.video.find((option) => option.id === choice.videoId) || media.video[0]
}

export function formatRequest(media: MediaInfo, choice: Choice): FormatRequest {
  return choice.tab === 'gallery' ? {type: 'gallery'} : requestOf(media, picksOf(choice))
}

/** The size of what the choice downloads, from the shared choice module; a gallery has none. */
export function estimate(media: MediaInfo, choice: Choice): {size?: number; approx: boolean} {
  return choice.tab === 'gallery' ? {approx: false} : sizeOf(media, picksOf(choice))
}

export function choiceLabel(media: MediaInfo, choice: Choice) {
  if (choice.tab === 'gallery') return media.gallery ? `${media.gallery.count} items` : 'Gallery'
  if (choice.tab === 'audio') {
    const target = audioTargets.find((item) => item.value === choice.target)?.label || choice.target
    if (choice.target === 'original') {
      const source = media.audio.find((option) => option.formatId === choice.audioSource) || media.audio[0]
      return source ? `Original ${audioCodecLabel(source.codec)}` : 'Original audio'
    }
    return qualityApplies(choice.target) && choice.quality !== 'best' ? `${target} ${choice.quality}` : target
  }
  const video = selectedVideo(media, choice)
  return video ? `${videoLabel(video)}${video.hdr ? ' HDR' : ''} · ${choice.container.toUpperCase()}` : 'Video'
}

export function estimateLabel(media: MediaInfo, choice: Choice) {
  const {size, approx} = estimate(media, choice)
  const text = formatBytes(size)
  return text ? `${approx ? '≈' : ''}${text}` : ''
}

// Compact batch selection: one string per row ("v:<optionId>" or "a:<target>").
export function quickValue(choice: Choice) {
  if (choice.tab === 'gallery') return 'g'
  return choice.tab === 'audio' ? `a:${choice.target}` : `v:${choice.videoId}`
}
export function fromQuickValue(media: MediaInfo, base: Choice, value: string): Choice {
  if (value === 'g') return {...base, tab: 'gallery'}
  if (value.startsWith('a:')) {
    return withPicks(base, picksForPreset(media, picksOf(base), `audio-${value.slice(2)}`))
  }
  const video = media.video.find((option) => option.id === value.slice(2))
  return {
    ...base,
    tab: 'video',
    videoId: video?.id,
    container: video ? containerAfterPick(media, picksOf(base), video.id) : base.container,
  }
}
export function quickOptions(media: MediaInfo) {
  const options: {value: string; label: string}[] = []
  if (media.gallery || media.engine === 'gallery-dl') options.push({value: 'g', label: `Gallery · ${media.gallery?.count ?? ''} items`.replace(' ·  items', '')})
  for (const video of media.video) {
    const size = formatBytes(video.size)
    options.push({
      value: `v:${video.id}`,
      label: `${videoLabel(video)}${video.hdr ? ' HDR' : ''}${size ? ` · ${video.sizeApprox ? '≈' : ''}${size}` : ''}`,
    })
  }
  if (media.audio.length || media.video.some((option) => option.hasAudio))
    for (const target of ['mp3', 'm4a', 'opus', 'flac'] as AudioTarget[])
      options.push({value: `a:${target}`, label: `Audio · ${audioTargets.find((item) => item.value === target)!.label}`})
  return options
}

/** What a preset ("best", "1080", "audio-mp3") makes of one inspected link's choice; the choice stays as it is when the link has nothing of that kind. */
export function choiceForPreset(media: MediaInfo, base: Choice, preset: DownloadPreset): Choice {
  if (availableTabs(media)[0] === 'gallery') return base
  return withPicks(base, picksForPreset(media, picksOf(base), preset))
}
