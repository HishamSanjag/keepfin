// Pure helpers for Spaces v2 in the renderer: smart-space matching, tag filtering, follow status lines and the
// value pickers of the rules editor. Rule evaluation itself lives in gui/shared/library.ts.
import {useMemo} from 'react'
import {
  collectionMembership,
  collectionRowWords,
  matchesSmartRules,
  normalizeArtists,
  tagsInScope,
  type Collection,
  type LibraryItem,
  type LibraryItemKind,
  type SmartRule,
  type SmartRules,
  type Space,
  type SpaceColor,
} from '../../shared/library'
import {providerName} from '../../shared/providers'
import {useLibrary, type LibraryData} from './libStore'

export const colorNames: Record<SpaceColor, string> = {
  cobalt: 'Cobalt',
  violet: 'Violet',
  teal: 'Teal',
  olive: 'Olive',
  amber: 'Amber',
  rust: 'Rust',
  berry: 'Berry',
  slate: 'Slate',
}
export const iconNames: Record<string, string> = {
  music: 'Music note',
  video: 'Video',
  folder: 'Folder',
  star: 'Star',
  heart: 'Heart',
  mic: 'Microphone',
  book: 'Book',
  dumbbell: 'Dumbbell',
  moon: 'Moon',
  child: 'Child',
}

export const isSmart = (space: Pick<Space, 'type'>) => space.type === 'smart'

/** Items a smart space gathers right now (files that are gone from disk are left out). */
export function smartMatches(rules: SmartRules | undefined, items: readonly LibraryItem[], collections: readonly Collection[], now = Date.now()): LibraryItem[] {
  if (!rules) return []
  const collectionsOf = collectionMembership(collections)
  const context = {now, collectionsOf}
  return items.filter((item) => !item.missing && matchesSmartRules(rules, item, context))
}

/** Matches of every smart space, recomputed only when the library or the rules change. */
export function useSmartMatches(space: Space | undefined): LibraryItem[] {
  const lib = useLibrary()
  const rules = space?.type === 'smart' ? space.rules : undefined
  return useMemo(() => smartMatches(rules, lib.snapshot.items, lib.snapshot.collections), [rules, lib.snapshot.items, lib.snapshot.collections])
}

/** Item count shown on a space: files for a folder, live matches for a smart space. */
export function useSpaceCounts(spaces: readonly Space[]): Map<string, number> {
  const lib = useLibrary()
  return useMemo(() => {
    const counts = new Map<string, number>()
    for (const space of spaces) {
      if (space.type === 'smart') counts.set(space.id, smartMatches(space.rules, lib.snapshot.items, lib.snapshot.collections).length)
      else counts.set(space.id, lib.bySpace.get(space.id)?.length ?? 0)
    }
    return counts
  }, [spaces, lib.snapshot.items, lib.snapshot.collections, lib.bySpace])
}

/** Items carrying any (or all) of the chosen tags, source and user tags alike. */
export function filterByTags(items: readonly LibraryItem[], tags: readonly string[], mode: 'any' | 'all'): LibraryItem[] {
  if (!tags.length) return items as LibraryItem[]
  return items.filter((item) => {
    const own = new Set([...(item.tags ?? []), ...(item.userTags ?? [])])
    return mode === 'all' ? tags.every((tag) => own.has(tag)) : tags.some((tag) => own.has(tag))
  })
}
export {tagsInScope}

/** The line under a downloaded collection: the shared words ("Playlist · 31 songs · following, checked 12 min ago"), then what failed, is waiting or is new. */
export function collectionSubtitle(collection: Collection, noun: string, present: number, now = Date.now()): string {
  const follow = collection.follow
  const extras: string[] = []
  if (follow && follow.mode !== 'off' && follow.lastError) extras.push(follow.lastError)
  if (follow?.pending) extras.push(`${follow.pending.count} waiting for review`)
  else if (collection.newItemIds?.length) extras.push(`${collection.newItemIds.length} new`)
  return collectionRowWords(collection, noun, present, extras, now)
}

// ---------------------------------------------------------------------------
// Rules editor
// ---------------------------------------------------------------------------

export type ValueField = Extract<SmartRule, {values: string[]}>['field']
export type RuleField = SmartRule['field']
export const ruleFields: {value: RuleField; label: string}[] = [
  {value: 'tag', label: 'Tag'},
  {value: 'collection', label: 'Collection'},
  {value: 'space', label: 'Space'},
  {value: 'source', label: 'Source'},
  {value: 'artist', label: 'Artist'},
  {value: 'album', label: 'Album'},
  {value: 'kind', label: 'Kind'},
  {value: 'addedWithin', label: 'Added within'},
  {value: 'favorite', label: 'Favorite'},
  {value: 'unplayed', label: 'Unplayed'},
]
export const kindOptions: {value: LibraryItemKind; label: string}[] = [
  {value: 'audio', label: 'Songs'},
  {value: 'video', label: 'Videos'},
  {value: 'image', label: 'Photos'},
  {value: 'other', label: 'Other files'},
]
export const hasValues = (field: RuleField): field is ValueField => field !== 'addedWithin' && field !== 'favorite' && field !== 'unplayed'

export function emptyRule(field: RuleField): SmartRule {
  if (field === 'addedWithin') return {field, days: 30}
  if (field === 'favorite' || field === 'unplayed') return {field}
  return {field, values: []}
}

const foldText = (value: string) => value.normalize('NFKD').replace(/\p{M}+/gu, '').toLowerCase()

export type ValueOption = {value: string; label: string}

/** Choices for a rule's value picker, taken from what the library actually holds. */
export function valueOptions(field: ValueField, lib: LibraryData, spaces: readonly Space[]): ValueOption[] {
  const out = new Map<string, string>()
  const add = (value: string, label: string) => {
    if (value && !out.has(value)) out.set(value, label)
  }
  switch (field) {
    case 'tag':
      for (const entry of tagsInScope(lib.snapshot.items, 1)) add(entry.tag, entry.tag)
      break
    case 'collection':
      for (const collection of lib.snapshot.collections) add(collection.id, collection.name)
      break
    case 'space':
      for (const space of spaces) if (space.type !== 'smart') add(space.id, space.name)
      break
    case 'source':
      for (const item of lib.snapshot.items) {
        if (item.sourceUrl) {
          const name = providerName(item.sourceUrl)
          add(name.toLowerCase(), name)
        }
        const service = item.sourceId?.split(':')[0]
        if (service) add(service.toLowerCase(), service.charAt(0).toUpperCase() + service.slice(1))
      }
      break
    case 'artist':
      for (const item of lib.snapshot.items) {
        const credit = item.artist ?? item.albumArtist
        const names = normalizeArtists(credit)
        if (names.length === 1) add(names[0], credit!.trim())
        else for (const name of names) add(name, name)
      }
      break
    case 'album':
      for (const item of lib.snapshot.items) if (item.album) add(foldText(item.album).trim(), item.album)
      break
    case 'kind':
      for (const option of kindOptions) add(option.value, option.label)
      break
  }
  return [...out].map(([value, label]) => ({value, label})).sort((a, b) => a.label.localeCompare(b.label))
}

// ---------------------------------------------------------------------------
// Ordering
// ---------------------------------------------------------------------------

/** `ids` with `id` dropped before or after `targetId`. */
export function dropId(ids: readonly string[], id: string, targetId: string, after: boolean): string[] {
  if (id === targetId) return [...ids]
  const rest = ids.filter((entry) => entry !== id)
  const at = rest.indexOf(targetId)
  if (at < 0) return [...ids]
  rest.splice(after ? at + 1 : at, 0, id)
  return rest
}
