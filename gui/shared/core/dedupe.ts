// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the skip-existing helpers the desktop code already uses. This is the only file that imports the core's dedupe
// package: gui/shared/library.ts re-exports from here, so call sites keep their imports.
//
// The core decides what a link names, when two recordings are the same and which known recording a new one repeats.
// It reads links with its own providers parser, so desktop and Android agree on every link.
import {
  JsRecording,
  dedupeCompare,
  dedupeFind,
  dedupeNormalizeArtists,
  dedupeNormalizeTitle,
  dedupeSameRecording,
  dedupeSourceId,
  dedupeVersionsOf,
} from '../../../build/core-js/keepfin-core.mjs'

/** Duration is in seconds. `sourceIds`, `contentKey` and `isrc` are only used by `compareRecordings` and `findRecording`. */
export type Recording = {
  artist?: string
  title: string
  album?: string
  duration?: number
  sourceIds?: readonly string[]
  contentKey?: string
  isrc?: string
}

export type Tier = 'source' | 'isrc' | 'content' | 'metadata'

const toCore = (r: Recording): JsRecording =>
  new JsRecording(r.title, r.artist ?? null, r.album ?? null, r.duration ?? null, [...(r.sourceIds ?? [])], r.contentKey ?? null, r.isrc ?? null)

/** Stable id of the thing a link points to: 'youtube:<id>', 'spotify:<id>', 'apple-music:<id>', 'deezer:<id>'. */
export const sourceIdFromUrl = (url: string | undefined): string | undefined => dedupeSourceId(url ?? null) ?? undefined

/** Lower-case, diacritic-free title with release noise removed. Version words are kept. */
export const normalizeTitle = (raw: string, artist?: string): string => dedupeNormalizeTitle(raw, artist ?? null)

/** Primary artists of a credit line ("A feat. B & C" → a, b, c), normalised. */
export const normalizeArtists = (raw: string | undefined): string[] => Array.from(dedupeNormalizeArtists(raw ?? null))

/** The version words a normalised title carries. */
export const versionsOf = (normalizedTitle: string): string[] => Array.from(dedupeVersionsOf(normalizedTitle))

/**
 * Metadata tier of duplicate detection: same artist and title (non-empty after noise removal), durations within
 * 2 s, album equal or missing on either side, and the same version words.
 */
export const sameRecording = (a: Recording, b: Recording): boolean => dedupeSameRecording(toCore(a), toCore(b))

/** The strictest way two recordings are the same (source id, ISRC, content, metadata), or undefined. */
export const compareRecordings = (a: Recording, b: Recording): Tier | undefined => (dedupeCompare(toCore(a), toCore(b)) as Tier | null) ?? undefined

/** The first of `known` that repeats `candidate`, preferring the strictest tier, with the tier. */
export function findRecording(candidate: Recording, known: readonly Recording[]): {index: number; tier: Tier} | undefined {
  const found = dedupeFind(toCore(candidate), known.map(toCore))
  return found ? {index: found.index, tier: found.tier as Tier} : undefined
}
