// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`) and the
// desktop preview. This is the only file that imports the core's choice package. The core decides what a preview offers,
// opens with and estimates (fixtures/engine/choice); the preview keeps its picks in its own state and asks again when they
// change. The inspected media is converted once per object, not per call.
import {
  choiceApplyPreset,
  choiceContainers,
  choiceModes,
  choiceOpening,
  choicePickTarget,
  choicePickVideo,
  choiceRequest,
  choiceShownFps,
  choiceSize,
  choiceVideoLabel,
  JsChoice,
  JsChoiceAudio,
  JsChoiceMedia,
  JsChoiceVideo,
} from '../../../build/core-js/keepfin-core.mjs'
import type {AudioOption, AudioQuality, AudioTarget, CodecPreference, Container, FormatRequest, VideoOption} from '../contracts'

/** The picks for one download, as the core reads them. */
export type ChoicePicks = {
  mode: 'video' | 'audio'
  videoId?: string
  container: Container
  target: AudioTarget
  quality: AudioQuality
  audioTrack?: string
  audioSource?: string
}

/** The inspected qualities and streams, best first. */
export type ChoiceSource = {video: readonly VideoOption[]; audio: readonly AudioOption[]; duration?: number}

/** One container of the offer; `reason` says why it cannot be picked. */
export type ContainerOffer = {value: Container; allowed: boolean; reason?: string}

const converted = new WeakMap<ChoiceSource, JsChoiceMedia>()

function coreMedia(media: ChoiceSource): JsChoiceMedia {
  let result = converted.get(media)
  if (!result) {
    result = new JsChoiceMedia(
      media.duration ?? null,
      media.video.map((v) => new JsChoiceVideo(v.id, v.height, v.fps ?? null, v.hdr, v.codec, v.hasAudio, v.formatId, v.size ?? null, v.sizeApprox)),
      media.audio.map((a) => new JsChoiceAudio(a.formatId, a.codec, a.size ?? null, a.sizeApprox)),
    )
    converted.set(media, result)
  }
  return result
}

const coreChoice = (picks: ChoicePicks) =>
  new JsChoice(picks.mode, picks.videoId ?? null, picks.container, picks.target, picks.quality, picks.audioTrack ?? null, picks.audioSource ?? null)

/** The containers to show, each with whether the chosen quality can be saved in it and why not. */
export function containerOffer(media: ChoiceSource, picks: ChoicePicks): ContainerOffer[] {
  return Array.from(choiceContainers(coreMedia(media), coreChoice(picks)), (item) => ({
    value: item.value as Container,
    allowed: item.allowed,
    reason: item.reason ?? undefined,
  }))
}

/** The size of what the choice downloads (undefined when nothing says); `approx` marks an estimate. */
export function sizeOf(media: ChoiceSource, picks: ChoicePicks): {size?: number; approx: boolean} {
  const {bytes, approx} = choiceSize(coreMedia(media), coreChoice(picks))
  return {size: bytes ?? undefined, approx}
}

const fromCore = (choice: JsChoice): ChoicePicks => ({
  mode: choice.mode as ChoicePicks['mode'],
  videoId: choice.videoId ?? undefined,
  container: choice.container as Container,
  target: choice.target as AudioTarget,
  quality: choice.quality as AudioQuality,
  audioTrack: choice.audioTrack ?? undefined,
  audioSource: choice.audioSource ?? undefined,
})

/** What the link offers, in the order Video, Audio; a link that lists nothing offers both. */
export const modesOf = (media: ChoiceSource): ('video' | 'audio')[] => Array.from(choiceModes(coreMedia(media)), (mode) => mode as 'video' | 'audio')

/**
 * The picks a preview opens with. `music` is true for a song matched from a music service; `youtubeMusic` is the music-videos
 * setting (YouTube Music links open as audio or as video).
 */
export function openingPicks(
  media: ChoiceSource,
  start: {preset: string; url?: string; music: boolean; youtubeMusic: 'audio' | 'video'},
): ChoicePicks {
  return fromCore(choiceOpening(coreMedia(media), start.preset, start.url ?? null, start.music, start.youtubeMusic === 'audio'))
}

/** What a preset ("best", "1080", "audio-mp3") makes of one link's picks; they stay as they are when the link has nothing of that kind. */
export const picksForPreset = (media: ChoiceSource, picks: ChoicePicks, preset: string): ChoicePicks =>
  fromCore(choiceApplyPreset(coreMedia(media), coreChoice(picks), preset))

/** The picks after choosing an audio format: MP3 starts at 320, another format keeps the quality; the source stream is cleared. */
export const picksForTarget = (picks: ChoicePicks, target: AudioTarget): ChoicePicks => fromCore(choicePickTarget(coreChoice(picks), target))

/** The container after picking a quality: it goes back to MP4 when the new quality cannot use it. */
export function containerAfterPick(media: ChoiceSource, picks: ChoicePicks, videoId: string): Container {
  return choicePickVideo(coreMedia(media), coreChoice(picks), videoId).container as Container
}

/** What the choice sends to the engine: a video or an audio request (a gallery is the preview's own). */
export function requestOf(media: ChoiceSource, picks: ChoicePicks): Extract<FormatRequest, {type: 'video' | 'audio'}> {
  const request = choiceRequest(coreMedia(media), coreChoice(picks))
  if (request.type === 'audio') return {type: 'audio', target: request.target as AudioTarget, quality: request.quality as AudioQuality, formatId: request.formatId ?? undefined}
  return {
    type: 'video',
    maxHeight: request.maxHeight ?? undefined,
    formatId: request.formatId ?? undefined,
    audioFormatId: request.audioFormatId ?? undefined,
    fps: request.fps ?? undefined,
    codec: request.codec as CodecPreference,
    container: request.container as Container,
  }
}

const wholeFps = (option: Pick<VideoOption, 'fps'>) => (option.fps ? Math.round(option.fps) : null)

/** The frame rate worth showing on a quality row (from 33 fps up), else undefined. */
export const shownFps = (option: Pick<VideoOption, 'fps'>): number | undefined => choiceShownFps(wholeFps(option)) ?? undefined

/** The words on a quality row: the height, then the frame rate when it shows ("1080p60"). */
export const videoLabel = (option: Pick<VideoOption, 'height' | 'fps'>): string => choiceVideoLabel(option.height, wholeFps(option))
