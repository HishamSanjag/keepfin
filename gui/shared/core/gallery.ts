// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the desktop's gallery code. This is the only file that imports the core's gallery package.
//
// The core reads the gallery engine's `-j` output (passed as text) and decides the post, a reported failure, or nothing usable.
import {galleryAlreadySaved, galleryLabel, galleryRange, galleryRead, gallerySavedLabel, JsEarlierSave} from '../../../build/core-js/keepfin-core.mjs'
import type {FormatRequest, MediaInfo} from '../contracts.js'

export type GalleryResult = {kind: 'post'; media: MediaInfo} | {kind: 'failed'; message: string} | {kind: 'empty'}

/** Reads the engine's parsed `-j` output (message tuples) for the link `url`. */
export function readGallery(data: unknown, url: string, limit: number): GalleryResult {
  const read = galleryRead(JSON.stringify(data) ?? '', url, limit)
  if (read.kind === 'failed') return {kind: 'failed', message: read.message ?? ''}
  if (read.kind !== 'post') return {kind: 'empty'}
  const previews = Array.from(read.previews)
  return {
    kind: 'post',
    media: {
      url,
      title: read.title ?? '',
      uploader: read.uploader ?? undefined,
      thumbnail: previews[0],
      extractor: `gallery-dl:${read.category}`,
      provider: read.provider ?? '',
      engine: 'gallery-dl',
      video: [],
      audio: [],
      subtitles: [],
      chapters: 0,
      gallery: {
        count: read.count,
        previews,
        items: Array.from(read.items, (i) => ({position: i.position, url: i.url, photo: i.photo})),
        truncated: read.truncated,
      },
    },
  }
}

/** The most photos one request can name. */
const MAX_CHOSEN = 500

/** The engine's range for the chosen positions among `count`: undefined when all are chosen; throws for an invalid choice. */
export function chosenRange(chosen: number[], count = MAX_CHOSEN): string | undefined {
  try {
    return galleryRange(chosen, count) ?? undefined
  } catch {
    throw new Error('Choose at least one photo from this post.')
  }
}

/** The renderer's gallery request, checked against the inspected `count` (when known); an all-chosen list is dropped. */
export function validateGalleryRequest(value: unknown, count?: number): Extract<FormatRequest, {type: 'gallery'}> {
  const items = (value as {items?: unknown} | null)?.items
  if (items === undefined) return {type: 'gallery'}
  if (!Array.isArray(items) || !items.every((n) => Number.isInteger(n))) throw new Error('Choose at least one photo from this post.')
  const chosen = items as number[]
  chosenRange(chosen, count ?? MAX_CHOSEN)
  return count !== undefined && chosen.length === count ? {type: 'gallery'} : {type: 'gallery', items: chosen}
}

/** A finished photo job as the core compares it: the post link, the photos it asked for (`items` undefined = all `count`) and whether all of them are saved and still there. */
export type EarlierSave = {url: string; count: number; items?: number[]; complete: boolean}

/** Index of the earlier save that already holds the photos asked for (`items` undefined = all `count`), or -1. */
export function alreadySavedIndex(url: string, count: number, items: number[] | undefined, earlier: EarlierSave[]): number {
  return galleryAlreadySaved(
    url,
    count,
    items ?? [],
    items === undefined,
    earlier.map((e) => new JsEarlierSave(e.url, e.count, e.items ?? [], e.items === undefined, e.complete)),
  )
}

/** "6 photos" (or "6 items" when a video of the post is among them). */
export function photoCountLabel(count: number, videos: boolean): string {
  return galleryLabel(count, videos)
}

/** The job label once `saved` of the `expected` files are in storage: "4 of 6 photos" while some are missing, "6 photos" when all are. */
export function savedPhotosLabel(saved: number, expected: number, label: string): string {
  return gallerySavedLabel(saved, expected, label)
}
