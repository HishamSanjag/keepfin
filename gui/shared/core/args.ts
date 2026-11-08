// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the download-intent functions the desktop code already uses. This is the only file that imports the core's args
// package: gui/shared/policy.ts re-exports from here, so call sites keep their imports.
//
// The core validates structured intent, builds the format selection and option arguments, and decides the label and the
// expected size of a queued download. It makes no requests. Network, cookies, tool paths and the progress/output flags are
// the desktop's own and stay in policy.ts.
import {
  argsBuild,
  argsFormatBytes,
  argsLabel,
  argsMetadata,
  argsMetadataLiteral,
  argsPreset,
  argsQuote,
  argsValidateFormat,
  argsValidateOptions,
  JsLabelAudio,
  JsLabelVideo,
  type JsOptions,
  type JsRequest,
} from '../../../build/core-js/keepfin-core.mjs'
import {validateGalleryRequest} from './gallery.js'
import type {
  AudioOption,
  AudioQuality,
  AudioTarget,
  CodecPreference,
  Container,
  DownloadOptions,
  FormatRequest,
  SponsorBlockMode,
  VideoOption,
} from '../contracts.js'

/** JSON for the core: a number that is not finite would turn into null, so it becomes text the core rejects. */
const toJson = (value: unknown): string => JSON.stringify(value, (_key, v) => (typeof v === 'number' && !Number.isFinite(v) ? 'not-a-number' : v))

function requestOf(r: JsRequest): FormatRequest {
  if (r.type === 'video')
    return {
      type: 'video',
      maxHeight: r.maxHeight ?? undefined,
      formatId: r.formatId ?? undefined,
      audioFormatId: r.audioFormatId ?? undefined,
      fps: r.fps ?? undefined,
      codec: r.codec as CodecPreference,
      container: r.container as Container,
    }
  return {type: 'audio', target: r.target as AudioTarget, quality: r.quality as AudioQuality, formatId: r.formatId ?? undefined}
}

function optionsOf(o: JsOptions): DownloadOptions {
  const result: DownloadOptions = {}
  if (o.subtitles) result.subtitles = {enabled: o.subtitles.enabled, languages: o.subtitles.languages, auto: o.subtitles.auto, embed: o.subtitles.embed}
  for (const key of ['embedThumbnail', 'embedMetadata', 'embedChapters', 'writeThumbnail', 'writeDescription', 'writeInfoJson', 'splitChapters'] as const) {
    const flag = o[key]
    if (flag !== null && flag !== undefined) result[key] = flag
  }
  if (o.sponsorBlock) result.sponsorBlock = o.sponsorBlock as SponsorBlockMode
  if (o.sectionStart != null || o.sectionEnd != null) result.section = {start: o.sectionStart ?? undefined, end: o.sectionEnd ?? undefined}
  return result
}

/**
 * The renderer never supplies raw arguments: every value that reaches argv is validated or chosen from a closed set.
 * Throws an Error with the message the user sees.
 */
export function validateFormatRequest(value: unknown): FormatRequest {
  if (!value || typeof value !== 'object') throw new Error('Choose a download format.')
  // A gallery post has no download plan: it is fetched by the gallery engine.
  if ((value as {type?: unknown}).type === 'gallery') return validateGalleryRequest(value)
  const checked = argsValidateFormat(toJson(value))
  if (checked.error != null || !checked.request) throw new Error(checked.error ?? 'Choose a download format.')
  return requestOf(checked.request)
}

/** Accepts only known fields with validated values; unknown keys are dropped. */
export function validateOptions(value: unknown): DownloadOptions {
  if (value === undefined || value === null) return {}
  if (typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid download options.')
  const checked = argsValidateOptions(toJson(value))
  if (checked.error != null || !checked.options) throw new Error(checked.error ?? 'Invalid download options.')
  return optionsOf(checked.options)
}

export function presetFormat(preset: unknown, container: Container = 'mp4'): FormatRequest {
  const checked = argsPreset(typeof preset === 'string' ? preset : '', container)
  if (checked.error != null || !checked.request) throw new Error(checked.error ?? 'Choose a supported download preset.')
  const r = checked.request
  // A preset names only what it fixes: no empty keys.
  if (r.type === 'video') return {type: 'video', ...(r.maxHeight != null ? {maxHeight: r.maxHeight} : {}), codec: r.codec as CodecPreference, container: r.container as Container}
  return {type: 'audio', target: r.target as AudioTarget, quality: r.quality as AudioQuality}
}

export type FormatArgsExtra = {
  /** The source is YouTube / YouTube Music: audio covers are cropped square. */
  squareCover?: boolean
  /** Output template of the per-chapter files when chapters are split. */
  chapterTemplate?: string
}

/** The format selection and the option arguments of a request, in the order the engine receives them. */
export function formatArgs(format: FormatRequest, options: DownloadOptions, extra: FormatArgsExtra = {}): string[] {
  if (format.type === 'gallery') throw new Error('Gallery downloads use gallery-dl.')
  // The desktop always has the optional cover-art module, so every cover-art container is allowed.
  const built = argsBuild(toJson(format), toJson(options), true, extra.squareCover === true, extra.chapterTemplate ?? null)
  if (built.error != null || !built.args) throw new Error(built.error ?? 'Use a valid time range.')
  return Array.from(built.args)
}

/** Embedded tags: the clean job title replaces a caption-sized title; the description falls back to the page's own text. */
export const metadataArgs = (title: string | undefined): string[] => Array.from(argsMetadata(title ?? null))

export const metadataLiteral = (value: string): string => argsMetadataLiteral(value)

/** Display label for a collection entry that has no inspected media, e.g. "Best quality · MP4" or "MP3 · 320 kbps". */
export function formatLabel(format: FormatRequest): string {
  if (format.type === 'gallery') return 'Images & media'
  const label = argsLabel(toJson(format))
  if (label.error != null || !label.quote) throw new Error(label.error ?? 'Choose a download format.')
  return label.quote.label
}

/** Bytes as "12.3 MB" (empty when nothing usable). */
export const formatByteText = (bytes: number): string => argsFormatBytes(bytes)

/**
 * The label and the expected size of a request against the inspected media. The label names the quality that will be fetched
 * with its size; the size is an estimate (approx) for audio and for any stream whose size was itself estimated.
 */
export function jobQuote(
  format: FormatRequest,
  media: {video: readonly VideoOption[]; audio: readonly AudioOption[]; duration?: number},
  withSize = true,
): {label: string; expectedSize?: number; approx: boolean} {
  if (format.type === 'gallery') return {label: 'Images & media', approx: false}
  const video = media.video.map((v) => new JsLabelVideo(v.formatId, v.height, v.fps ?? null, v.hdr, v.size ?? null, v.sizeApprox, !!v.hasAudio))
  const audio = media.audio.map((a) => new JsLabelAudio(a.formatId, a.size ?? null, a.sizeApprox, a.codec ?? 'other'))
  const quote = argsQuote(toJson(format), video, audio, media.duration ?? null, withSize)
  if (quote.error != null || !quote.quote) throw new Error(quote.error ?? 'Choose a download format.')
  return {label: quote.quote.label, expectedSize: quote.quote.expectedSize ?? undefined, approx: quote.quote.approx}
}
