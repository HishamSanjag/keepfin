// Shares equal strings (and equal short string lists) between the entries of one load.
//
// A library of 200,000 tracks has a few hundred albums, a few thousand artists, one space and three kinds. Parsed or
// cloned from JSON, every entry holds its own copy of those strings (20 to 40 bytes each, a dozen per entry). One shared
// copy per distinct text takes that to a Map lookup per field and makes the main thread's heap smaller by a fifth. Lists of
// tags and subtitles repeat the same way (a list and its strings per entry become one list).
import type {LibraryEntry} from '../library/index.js'

/** The fields whose values repeat across entries. Their values are only ever replaced, never edited in place. */
const SHARED = ['space', 'kind', 'folder', 'artist', 'albumArtist', 'album', 'genre'] as const
/** The list fields that repeat. They are only ever replaced, never edited in place, so entries may share one list. */
const SHARED_LISTS = ['tags', 'userTags', 'subtitles'] as const
/** Lists longer than this are left alone (they are unlikely to repeat, and the key would be long). */
const MAX_SHARED_LIST = 16

export class StringPool {
  private readonly pool = new Map<string, string>()
  private readonly lists = new Map<string, readonly string[]>()

  /** Seeds the pool with strings that already exist (so the entries' copies are dropped in favour of them). */
  add(value: string): void {
    if (!this.pool.has(value)) this.pool.set(value, value)
  }

  /** The pool's copy of a string. */
  share(value: string): string {
    const shared = this.pool.get(value)
    if (shared !== undefined) return shared
    this.pool.set(value, value)
    return value
  }

  entry(entry: LibraryEntry): void {
    const record = entry as unknown as Record<string, unknown>
    // An id that the pool was seeded with (the device records' keys) is replaced by that copy: one string per id, not two.
    const id = this.pool.get(entry.id)
    if (id !== undefined) entry.id = id
    for (const key of SHARED) {
      const value = record[key]
      if (typeof value !== 'string' || value.length === 0) continue
      const shared = this.pool.get(value)
      if (shared === undefined) this.pool.set(value, value)
      else record[key] = shared
    }
    for (const key of SHARED_LISTS) {
      const value = record[key]
      if (!Array.isArray(value) || value.length === 0 || value.length > MAX_SHARED_LIST) continue
      let list = ''
      let plain = true
      for (const element of value) {
        if (typeof element !== 'string') {
          plain = false
          break
        }
        list += `${element.length}:${element}`
      }
      if (!plain) continue
      const shared = this.lists.get(list)
      if (shared === undefined) {
        this.lists.set(list, value as string[])
        // The strings of the first list are the pool's copies.
        for (let i = 0; i < value.length; i++) value[i] = this.share(value[i] as string)
      } else record[key] = shared
    }
  }

  get size() {
    return this.pool.size + this.lists.size
  }
}
