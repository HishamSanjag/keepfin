// The rules behind the Extras tab, kept apart from the screen: what is valid, what a summary says, and what counts as changed.
import {subtitleLanguagePattern, type DownloadOptions} from '../../shared/contracts'
import {extrasCount} from '../../shared/library'
import {formatDuration} from '../format'

export function validateOptions(options: DownloadOptions, duration?: number): string | undefined {
  const subtitles = options.subtitles
  if (subtitles?.enabled && !subtitleLanguagePattern.test(subtitles.languages || ''))
    return 'Subtitle languages: use codes like en, es or en.*, separated by commas.'
  const section = options.section
  if (section) {
    const {start, end} = section
    if ((start !== undefined && !Number.isFinite(start)) || (end !== undefined && !Number.isFinite(end)))
      return 'Trim times use m:ss or h:mm:ss.'
    if (start !== undefined && end !== undefined && end <= start) return 'Trim end must be after the start.'
    if (duration && start !== undefined && start >= duration) return 'Trim start is past the end of the media.'
  }
}

/** One line for a queue row: what a download was asked to do beyond the basics. */
export function optionsSummary(options: DownloadOptions) {
  const parts: string[] = []
  if (options.subtitles?.enabled) parts.push(`Subtitles ${options.subtitles.languages}${options.subtitles.embed ? ' (embedded)' : ''}`)
  const embeds = [options.embedThumbnail && 'cover', options.embedMetadata && 'metadata', options.embedChapters && 'chapters'].filter(Boolean)
  if (embeds.length) parts.push(`Embed ${embeds.join(', ')}`)
  if (options.sponsorBlock && options.sponsorBlock !== 'off')
    parts.push(options.sponsorBlock === 'remove' ? 'SponsorBlock removes segments' : 'SponsorBlock marks segments')
  if (options.section && (options.section.start !== undefined || options.section.end !== undefined))
    parts.push(`Trim ${formatDuration(options.section.start) || '0:00'}–${formatDuration(options.section.end) || 'end'}`)
  if (options.splitChapters) parts.push('Split by chapters')
  const sidecars = [options.writeThumbnail && 'thumbnail', options.writeDescription && 'description', options.writeInfoJson && 'info JSON'].filter(Boolean)
  if (sidecars.length) parts.push(`Save ${sidecars.join(', ')}`)
  return parts.length ? parts.join(' · ') : 'No extras'
}

export const noSubtitles = {enabled: false, languages: 'en', auto: false, embed: true} as const

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)
const cutOf = (options: DownloadOptions) => {
  const {start, end} = options.section ?? {}
  return start === undefined && end === undefined ? undefined : {start, end}
}

/**
 * How many things on the Extras tab differ from `base` (the person's defaults): one for each group that was changed.
 * `queueChanged` is the collection-only choice that lives outside the options.
 */
export function extrasChanged(options: DownloadOptions, base: DownloadOptions, queueChanged = false) {
  return extrasCount({
    subtitles: !same(options.subtitles ?? noSubtitles, base.subtitles ?? noSubtitles),
    cut: !same(cutOf(options), cutOf(base)),
    sponsor: (options.sponsorBlock ?? 'off') !== (base.sponsorBlock ?? 'off'),
    chapters: !!options.splitChapters !== !!base.splitChapters,
    inFile: !!options.embedThumbnail !== !!base.embedThumbnail || !!options.embedMetadata !== !!base.embedMetadata || !!options.embedChapters !== !!base.embedChapters,
    beside: !!options.writeThumbnail !== !!base.writeThumbnail || !!options.writeDescription !== !!base.writeDescription || !!options.writeInfoJson !== !!base.writeInfoJson,
    queue: queueChanged,
  })
}
