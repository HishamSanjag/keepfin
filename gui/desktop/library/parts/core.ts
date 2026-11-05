// What the parts of the library index may use of the index. The index builds this from closures over its private members;
// a part never reaches into the index itself.
import type {Collection, LibrarySettings, Space} from '../../../shared/library.js'
import type {StoredJob as Job} from '../../migrate.js'
import type {ArtStore} from '../art.js'
import type {LibraryEntry, LibraryHooks, LibraryOptions} from '../index.js'

export interface LibraryCore {
  readonly options: LibraryOptions
  entries(): Map<string, LibraryEntry>
  /** Entry ids by file key. */
  byPath(): Map<string, string>
  collections(): Map<string, Collection>
  settings(): LibrarySettings | undefined
  /** True once the index is closing. */
  closing(): boolean
  /** Above zero while a file operation holds folder reconciles. */
  held(): number
  hooks(): LibraryHooks
  art(): ArtStore
  /** Downloads whose art is still to be fetched, by item id. */
  artRequests(): Map<string, {id: string; thumbnail?: string; file: string}>
  /** Lyrics text cache (memory only). */
  lyricsText(): Map<string, {sig: string; text: string}>
  /** Forgets the duplicate-detection lookup tables (rebuilt on next use). */
  clearTables(): void
  now(): number
  newId(): string
  changed(persist?: boolean, items?: readonly string[], collections?: boolean): void
  space(id: string | undefined): Space | undefined
  spaces(): Space[]
  spaceOf(file: string): Space | undefined
  item(id: unknown): LibraryEntry
  all(): LibraryEntry[]
  entryAt(file: string): LibraryEntry | undefined
  drop(ids: string[], options?: {forget?: boolean}): void
  relocate(id: string, newPath: string, stat?: {size: number; mtimeMs: number}): LibraryEntry
  addFile(file: string, options: {space: string; managed: boolean; job?: Job; addedAt?: number; sourceUrl?: string}): Promise<LibraryEntry | undefined>
  syncJobs(): Promise<void>
  /** Saves an item's art and tells the store. */
  putArt(id: string, bytes: Uint8Array, mime?: string): Promise<boolean>
  queueEnrich(id: string): void
  queueLoudness(id: string): void
  relinkEntries(entries: readonly LibraryEntry[]): Promise<LibraryEntry[]>
}
