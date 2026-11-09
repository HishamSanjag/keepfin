// Adapter between the shared core (core/, Kotlin Multiplatform) and Home's mixes and follow feed. This is the only file that imports the
// core's home package. The core decides which mixes exist, their songs and words, how the feed is grouped and who gets the dot; time is passed in.
import {
  feedAgeWords,
  feedArtistsWithNew,
  feedFresh,
  feedGroupsJson,
  feedPickChannel,
  feedSplitKind,
  followKey,
  homeMixesJson,
} from '../../../build/core-js/keepfin-core.mjs'

export type MixSong = {id: string; artist?: string; plays?: number; lastPlayedAt?: number; addedAt: number; favorite?: boolean}
export type Mix = {id: 'on-repeat' | 'forgotten' | 'recent' | 'never' | 'because' | 'late-night'; title: string; blurb: string; ids: string[]}
export type FeedRelease = {id: string; artistKey: string; title: string; foundAt: number; kept: boolean}
export type FeedGroup = {label: string; ids: string[]}

/** "Made from your listening": the mixes with enough songs, from the person's own plays only. */
export function homeMixes(songs: readonly MixSong[], now = Date.now(), limit = 30): Mix[] {
  const offset = -new Date(now).getTimezoneOffset()
  return JSON.parse(homeMixesJson(JSON.stringify(songs), now, offset, limit)) as Mix[]
}

/** The feed as "This week" and "Earlier this month", newest first; kept releases are left out. */
export const feedGroups = (releases: readonly FeedRelease[], now = Date.now()): FeedGroup[] => JSON.parse(feedGroupsJson(JSON.stringify(releases), now)) as FeedGroup[]
export const artistsWithNew = (releases: readonly FeedRelease[], now = Date.now()): Set<string> => new Set(feedArtistsWithNew(JSON.stringify(releases), now))
export const freshReleases = (known: readonly string[], found: readonly string[], firstCheck: boolean): string[] => Array.from(feedFresh(known as string[], found as string[], firstCheck))
export const ageWords = (foundAt: number, now = Date.now()): string => feedAgeWords(foundAt, now)
/** The key an artist is followed under (empty when the name has none). */
export const artistKey = (name: string | undefined): string => (name ? followKey(name) : '')
/** The channel id for the artist among search results, or undefined: never a guess. */
export const pickChannel = (artists: readonly {name: string; browseId: string}[], key: string): string | undefined =>
  feedPickChannel(artists.map((a) => a.name), artists.map((a) => a.browseId), key) || undefined
/** "Album - Salt Season" gives {kind: 'Album', title: 'Salt Season'}. */
export function splitKind(title: string): {kind?: string; title: string} {
  const [kind, rest] = Array.from(feedSplitKind(title))
  return {kind: kind || undefined, title: rest}
}
