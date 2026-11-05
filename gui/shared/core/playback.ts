// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the desktop's players. This is the only file that imports the core's playback package. The core decides
// what every Keepfin player decides the same way on both apps (fixtures/engine/playback): skips that add up,
// where playback resumes, chapters, which item plays next, the speeds offered and the time label.
import {
  playbackAccumulateSkip,
  playbackChapterAt,
  playbackChapterTarget,
  playbackClock,
  playbackCompareTitles,
  playbackCrossedChapter,
  playbackFolderOrder,
  playbackList as coreList,
  playbackNeighbour,
  playbackTimeLeft,
  playbackResumeFrom,
  playbackSkipAnnouncement,
  playbackSkipLabel,
  playbackSkipWindowMs,
  playbackSpeeds,
} from '../../../build/core-js/keepfin-core.mjs'

/** A run of skips in one direction: `total` seconds so far (negative = back), `at` the time of the last one (ms). */
export type SkipRun = {total: number; at: number}

/** The speeds a player offers (0.25× to 2×). */
export const speeds: readonly number[] = Array.from(playbackSpeeds())

/** Skips the same way within this many milliseconds add up. */
export const skipWindowMs: number = playbackSkipWindowMs()

/** Adds `delta` seconds to `prev` when it points the same way and came within the window; otherwise starts a new run. */
export function accumulateSkip(prev: SkipRun | undefined, delta: number, now: number): SkipRun {
  const [total, at] = playbackAccumulateSkip(prev?.total ?? null, prev?.at ?? null, delta, now)
  return {total, at}
}

/** "+10 s" / "−15 s". */
export const skipLabel = (total: number): string => playbackSkipLabel(total)

/** What a screen reader says once a run settles. */
export const skipAnnouncement = (total: number): string => playbackSkipAnnouncement(total)

/** Where playback starts, in seconds (over 10 s in, before the last 8 %, not watched); undefined = from the beginning. */
export function resumeFrom(position: number | undefined, duration: number | undefined, watched: boolean | undefined): number | undefined {
  return playbackResumeFrom(position ?? null, duration && Number.isFinite(duration) ? duration : null, !!watched) ?? undefined
}

/** The chapter playing at `time` (index into `starts`, ascending seconds), or -1. */
export const chapterIndexAt = (starts: readonly number[], time: number): number => playbackChapterAt([...starts], time)

/** Where previous (-1) / next (1) chapter goes, in seconds; undefined = nowhere. */
export function chapterTarget(starts: readonly number[], time: number, dir: 1 | -1): number | undefined {
  return playbackChapterTarget([...starts], time, dir) ?? undefined
}

/** Whether moving from `from` to `to` lands in another chapter. */
export const crossedChapter = (starts: readonly number[], from: number, to: number): boolean => playbackCrossedChapter([...starts], from, to)

/** The list next/previous follow: the one opened from, or (opened alone) the folder. */
export function playbackList(context: readonly string[], folder: readonly string[] | undefined): string[] {
  return Array.from(coreList([...context], folder ? [...folder] : null))
}

/** The id after (1) or before (-1) `id` in `list`, which holds only items that still exist. */
export function neighbour(list: readonly string[], id: string, dir: 1 | -1): string | undefined {
  return playbackNeighbour([...list], id, dir) ?? undefined
}

/** A folder's ids in natural title order ("Episode 2" before "Episode 10"; case and accents ignored; ties by id). */
export function folderOrder(items: readonly {id: string; title: string}[]): string[] {
  return Array.from(playbackFolderOrder(items.map((item) => item.id), items.map((item) => item.title)))
}

/** Title order as players use it (for sorting elsewhere with the same rule). */
export const compareTitles = (a: string, b: string): number => playbackCompareTitles(a, b)

/** "2:05", "1:02:03"; negative or unknown time is "0:00". */
export const clock = (seconds: number): string => playbackClock(seconds)

/** "0:11 left" under a minute, then "4 min left" and "1 h 12 min left". */
export const timeLeft = (seconds: number): string => playbackTimeLeft(seconds)
