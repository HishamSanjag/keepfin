// The desktop video player's own helpers. The decisions both apps share (skips, resume, chapters, next item, title
// order, the time label) live in the shared core: gui/shared/core/playback.ts. What stays here is how this app feeds
// them: library items, items that are gone, the sleep chip's wording and the thumbnail cache. No DOM, no React.
import type {LibraryItem} from '../../shared/library'
import {chapterIndexAt, folderOrder, neighbour} from '../../shared/core/playback'

export type Chapter = {start: number; title: string}

/** The chapter playing at `time`, or undefined before the first one. */
export function chapterAt(chapters: readonly Chapter[], time: number): Chapter | undefined {
  return chapters[chapterIndexAt(chapters.map((chapter) => chapter.start), time)]
}

/** The videos beside `current` (same space and folder) still on disk, in the shared natural title order. */
export function folderVideoIds(items: readonly LibraryItem[], current: Pick<LibraryItem, 'id' | 'space' | 'folder'>): string[] {
  return folderOrder(items.filter((item) => item.kind === 'video' && !item.missing && item.space === current.space && item.folder === current.folder))
}

/** The next (1) or previous (-1) id in `list` that still exists. */
export function adjacentId(list: readonly string[], id: string, dir: 1 | -1, exists: (id: string) => boolean): string | undefined {
  return neighbour(list.filter((other) => other === id || exists(other)), id, dir)
}

/** "29m" for the sleep timer chip: whole minutes, rounded up, at least 1. */
export const sleepRemaining = (ms: number) => `${Math.max(1, Math.ceil(ms / 60000))}m`

/** Thumbnails are cached per 2-second bucket. */
export const thumbBucket = (time: number) => Math.max(0, Math.floor(time / 2))

/** A small least-recently-used map (insertion order is the recency order). */
export class Lru<K, V> {
  private map = new Map<K, V>()
  constructor(private limit: number, private onEvict?: (value: V) => void) {}
  get size() {
    return this.map.size
  }
  has(key: K) {
    return this.map.has(key)
  }
  get(key: K): V | undefined {
    const value = this.map.get(key)
    if (value === undefined) return undefined
    this.map.delete(key)
    this.map.set(key, value)
    return value
  }
  set(key: K, value: V) {
    this.map.delete(key)
    this.map.set(key, value)
    while (this.map.size > this.limit) {
      const oldest = this.map.keys().next().value as K
      const gone = this.map.get(oldest)
      this.map.delete(oldest)
      if (gone !== undefined) this.onEvict?.(gone)
    }
  }
  clear() {
    this.map.clear()
  }
}
