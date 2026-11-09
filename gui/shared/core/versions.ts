// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the release helpers the apps already use. This is the only file that imports the core's versions
// package, so the core's generated API stays behind one boundary. The core makes the decisions (version
// validity and order, channel selection, release-notes highlights); it performs no requests.
import {releasesCompare, releasesHighlights, releasesIsPrerelease, releasesIsValid, releasesPick, releasesWhatsNewDue} from '../../../build/core-js/keepfin-core.mjs'

export type UpdateChannel = 'stable' | 'beta'

/** X.Y.Z or X.Y.Z-beta.N (a leading `v` and surrounding whitespace are tolerated). */
export const isValidVersion = (value: string): boolean => releasesIsValid(value)

/** True for 0.5.0-beta.1 and similar; false for plain releases and unparsable text. */
export const isPrerelease = (value: string): boolean => releasesIsPrerelease(value)

/** Semver ordering (0.4.2 < 0.5.0-beta.1 < 0.5.0-beta.2 < 0.5.0). Unparsable versions sort lowest. Returns -1, 0 or 1. */
export const compareReleases = (a: string, b: string): number => releasesCompare(a, b)

/** True when the running version is newer than the last one whose notes were seen; null (nothing stored) is a fresh install. */
export const whatsNewDue = (seen: string | null | undefined, current: string): boolean => releasesWhatsNewDue(seen ?? null, current)

/**
 * The newest release a channel offers from a list of release versions: Stable ignores pre-releases,
 * Beta takes the newest of both. Never returns anything not newer than `current` (no downgrades).
 */
export function pickRelease(versions: string[], channel: UpdateChannel, current: string): string | undefined {
  return releasesPick(versions, channel, current) ?? undefined
}

/**
 * The few bullets worth showing in the "update available" dialog: the bullets of the platform's section
 * of the release notes (or every bullet when the notes have no platform headings), as short plain text.
 */
export function releaseHighlights(notes: string | undefined, platform: 'windows' | 'android' = 'windows', limit = 3): string[] {
  return Array.from(releasesHighlights(notes ?? null, platform, limit))
}
