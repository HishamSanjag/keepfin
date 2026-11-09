// The rows of an item's Details that come from the file itself: container, resolution, codecs, bitrate.
import {bitrateWords, codecName} from '../../shared/core/formats'
import type {FileDetails, LibraryItem} from '../../shared/library'

/** `details` is what the file said (undefined while it is being read); the library's own width and height stand in until then. */
export function technicalFacts(item: Pick<LibraryItem, 'kind' | 'width' | 'height'>, details: FileDetails | undefined): [string, string][] {
  const width = details?.width ?? item.width
  const height = details?.height ?? item.height
  const rows: [string, string | undefined][] = [
    ['Container', details?.container],
    ['Resolution', width && height ? `${width}x${height}${details?.fps ? ` · ${Number(details.fps.toFixed(2))} fps` : ''}` : undefined],
    ['Video codec', item.kind === 'video' ? codecName(details?.videoCodec) : undefined],
    ['Audio codec', item.kind === 'video' || item.kind === 'audio' ? codecName(details?.audioCodec) : undefined],
    ['Bitrate', details?.bitrate ? bitrateWords(details.bitrate) : undefined],
  ]
  return rows.filter((row): row is [string, string] => !!row[1])
}
