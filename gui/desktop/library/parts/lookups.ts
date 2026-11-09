// Lookup tables for duplicate detection (LibraryIndex.findExisting): by source id, by recording code (ISRC) and by artist + title.
import {normalizeTitle} from '../../../shared/library.js'
import type {LibraryEntry} from '../index.js'

export type Lookups = {bySource: Map<string, LibraryEntry[]>; byIsrc: Map<string, LibraryEntry[]>; byTitle: Map<string, LibraryEntry[]>}

const add = (map: Map<string, LibraryEntry[]>, key: string, entry: LibraryEntry) => {
  const list = map.get(key)
  if (list) list.push(entry)
  else map.set(key, [entry])
}

export function buildLookups(entries: Iterable<LibraryEntry>): Lookups {
  const tables: Lookups = {bySource: new Map(), byIsrc: new Map(), byTitle: new Map()}
  for (const entry of entries) {
    if (entry.sourceId) add(tables.bySource, entry.sourceId, entry)
    if (entry.isrc) add(tables.byIsrc, entry.isrc, entry)
    const key = normalizeTitle(entry.title, entry.artist ?? entry.albumArtist)
    if (key) add(tables.byTitle, key, entry)
  }
  return tables
}
