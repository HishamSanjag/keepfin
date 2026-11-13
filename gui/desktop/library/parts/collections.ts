// The collections of the library: the user's own, and the ones downloaded from a link (follow options, seen links, order).
import {randomUUID} from 'node:crypto'
import {
  canonicalSourceUrl,
  defaultFollow,
  followIntervals,
  isDeviceId,
  isInfiniteSource,
  sourceIdOfCollection,
  type Collection,
  type CollectionFollow,
  type CollectionKind,
} from '../../../shared/library.js'
import {providerName} from '../../../shared/providers.js'
import type {LibraryCore} from './core.js'

export class LibraryCollections {
  constructor(private readonly core: LibraryCore) {}

  collection(id: unknown): Collection {
    const collection = typeof id === 'string' ? this.core.collections().get(id) : undefined
    if (!collection) throw new Error('That collection no longer exists.')
    return collection
  }

  collectionList(): Collection[] {
    return [...this.core.collections().values()]
  }

  /** The collection downloaded from this link, if there is one. */
  collectionForSource(url: string): Collection | undefined {
    const canonical = canonicalSourceUrl(url)
    return [...this.core.collections().values()].find((collection) => collection.source?.url === canonical)
  }

  private knownIds(input: unknown): string[] {
    if (!Array.isArray(input) || input.length > 20_000) throw new Error('Invalid collection.')
    // songs of Music on this PC are kept on this device only (see DeviceMusic), never in a synced collection
    return [...new Set(input)].filter((id): id is string => typeof id === 'string' && !isDeviceId(id) && this.core.entries().has(id))
  }

  private defaultCollectionSpace() {
    const routed = this.core.settings()?.routes.music
    return this.core.space(routed)?.id ?? this.core.spaces()[0]?.id ?? 'music'
  }

  /** Creates the user's own collection, or renames / re-lists an existing one. Downloaded collections can only be renamed here. */
  saveCollection(input: {id?: string; name: string; itemIds?: string[]; space?: string}): Collection {
    const name = typeof input.name === 'string' ? input.name.replace(/\s+/g, ' ').trim() : ''
    if (!name) throw new Error('Give the collection a name.')
    if (name.length > 100) throw new Error('Collection names can be up to 100 characters.')
    const now = this.core.now()
    const existing = input.id ? this.core.collections().get(input.id) : undefined
    if (input.id && !existing) throw new Error('That collection no longer exists.')
    if (existing) {
      existing.name = name
      if (existing.kind === 'own' && input.itemIds !== undefined) existing.itemIds = this.knownIds(input.itemIds)
      existing.updatedAt = now
      this.core.changed(true, [])
      return existing
    }
    const collection: Collection = {
      id: randomUUID(),
      name,
      kind: 'own',
      space: this.core.space(input.space)?.id ?? this.defaultCollectionSpace(),
      itemIds: input.itemIds === undefined ? [] : this.knownIds(input.itemIds),
      seen: [],
      createdAt: now,
      updatedAt: now,
    }
    this.core.collections().set(collection.id, collection)
    this.core.changed(true, [])
    return collection
  }

  /** Deleting a collection never deletes files. */
  deleteCollection(id: unknown) {
    if (typeof id !== 'string' || !this.core.collections().delete(id)) throw new Error('That collection no longer exists.')
    this.core.changed(true, [])
  }

  /**
   * Puts a deleted collection back (Undo): the same id, name, space and settings, its songs in their order (the ones that have left
   * the library since are left out).
   */
  restoreCollection(saved: Collection): Collection {
    if (this.core.collections().has(saved.id)) throw new Error('That collection is already back.')
    const collection = structuredClone(saved)
    const known = (ids: string[]) => ids.filter((id) => this.core.entries().has(id))
    collection.itemIds = known(collection.itemIds)
    if (collection.newItemIds) collection.newItemIds = known(collection.newItemIds)
    if (collection.removedItemIds) collection.removedItemIds = known(collection.removedItemIds)
    collection.updatedAt = this.core.now()
    this.core.collections().set(collection.id, collection)
    this.core.changed(true, [])
    return collection
  }

  /** By reference: files are never moved. Items already in the collection stay where they are. */
  addToCollection(id: unknown, ids: unknown): Collection {
    const collection = this.collection(id)
    const add = this.knownIds(ids).filter((item) => !collection.itemIds.includes(item))
    if (add.length) {
      collection.itemIds = [...collection.itemIds, ...add]
      collection.updatedAt = this.core.now()
      this.core.changed(true, [])
    }
    return collection
  }

  removeFromCollection(id: unknown, ids: unknown): Collection {
    const collection = this.collection(id)
    const gone = new Set(this.knownIds(ids))
    const kept = collection.itemIds.filter((item) => !gone.has(item))
    if (kept.length !== collection.itemIds.length) {
      collection.itemIds = kept
      if (collection.newItemIds) collection.newItemIds = collection.newItemIds.filter((item) => !gone.has(item))
      collection.updatedAt = this.core.now()
      this.core.changed(true, [])
    }
    return collection
  }

  /** The given order, then any member the list left out. */
  reorderCollection(id: unknown, ids: unknown): Collection {
    const collection = this.collection(id)
    const members = new Set(collection.itemIds)
    const ordered = this.knownIds(ids).filter((item) => members.has(item))
    collection.itemIds = [...ordered, ...collection.itemIds.filter((item) => !ordered.includes(item))]
    collection.updatedAt = this.core.now()
    this.core.changed(true, [])
    return collection
  }

  /** Follow options for a downloaded collection. Radio mixes cannot be followed automatically. */
  setFollow(id: unknown, patch: unknown): Collection {
    const collection = this.collection(id)
    if (collection.kind === 'own' || !collection.source) throw new Error('Only collections downloaded from a link can be followed.')
    if (!patch || typeof patch !== 'object' || Array.isArray(patch)) throw new Error('Invalid follow settings.')
    const input = patch as Record<string, unknown>
    const follow: CollectionFollow = {...(collection.follow ?? defaultFollow(collection.kind, collection.source.url))}
    if (input.mode !== undefined) {
      if (input.mode !== 'auto' && input.mode !== 'manual' && input.mode !== 'off') throw new Error('Choose how to follow this collection.')
      follow.mode = input.mode
    }
    if (input.intervalHours !== undefined) {
      if (!(followIntervals as readonly unknown[]).includes(input.intervalHours)) throw new Error('Choose how often to check.')
      follow.intervalHours = input.intervalHours as CollectionFollow['intervalHours']
    }
    for (const key of ['wifiOnly', 'notify'] as const) {
      if (input[key] === undefined) continue
      if (typeof input[key] !== 'boolean') throw new Error('Invalid follow settings.')
      follow[key] = input[key] as boolean
    }
    if (input.confirmAbove !== undefined) {
      if (typeof input.confirmAbove !== 'number' || !Number.isInteger(input.confirmAbove) || input.confirmAbove < 1 || input.confirmAbove > 500)
        throw new Error('Choose a number between 1 and 500.')
      follow.confirmAbove = input.confirmAbove
    }
    if (follow.mode === 'auto' && isInfiniteSource(collection.source.url)) throw new Error('Radio mixes never end, so they cannot be followed.')
    collection.follow = follow
    collection.updatedAt = this.core.now()
    this.core.changed(true, [])
    return collection
  }

  /** Finds or creates the collection for a link the user downloaded from. Downloading the same link again reuses it. */
  ensureCollection(input: {url: string; name?: string; kind: Exclude<CollectionKind, 'own'>; space: string; keepInSyncHours?: CollectionFollow['intervalHours']}): Collection {
    const existing = this.collectionForSource(input.url)
    if (existing) {
      // "Keep in sync" chosen again for a collection that exists turns following on; leaving it off never turns it off.
      const wanted = input.keepInSyncHours !== undefined ? defaultFollow(existing.kind, existing.source?.url, input.keepInSyncHours) : undefined
      if (wanted?.mode === 'auto') {
        this.setFollow(existing.id, {mode: 'auto', intervalHours: wanted.intervalHours})
        if (existing.follow) existing.follow.lastCheckedAt ??= this.core.now()
      }
      return existing
    }
    const now = this.core.now()
    const canonical = canonicalSourceUrl(input.url)
    const collection: Collection = {
      id: this.core.options.persistence ? this.core.newId() : `col-${randomUUID().slice(0, 12)}`,
      name: (input.name ?? '').replace(/\s+/g, ' ').trim().slice(0, 100) || providerName(canonical),
      kind: input.kind,
      space: input.space,
      itemIds: [],
      source: {url: canonical, provider: providerName(input.url), id: sourceIdOfCollection(canonical)},
      // The listing was read just now, so the first follow check is one interval away.
      follow: {...defaultFollow(input.kind, canonical, input.keepInSyncHours), lastCheckedAt: now},
      seen: [],
      createdAt: now,
      updatedAt: now,
    }
    this.core.collections().set(collection.id, collection)
    this.core.changed(true, [])
    return collection
  }

  /** Records source ids as discovered (downloaded, deselected or deleted). */
  noteSeen(id: unknown, keys: Iterable<string>) {
    const collection = this.collection(id)
    const known = new Set(collection.seen)
    let added = false
    for (const key of keys) {
      if (known.has(key)) continue
      known.add(key)
      collection.seen.push(key)
      added = true
    }
    if (added) this.core.changed(true, [])
  }

  /** Bookkeeping by the follow engine (check results, pending, removed). */
  updateCollection(id: unknown, mutate: (collection: Collection) => void): Collection {
    const collection = this.collection(id)
    mutate(collection)
    this.core.changed(true, [])
    return collection
  }

  /** The collection was opened: its "new" markers clear. */
  markOpened(id: unknown) {
    const collection = this.collection(id)
    if (!collection.newItemIds?.length) return
    collection.newItemIds = undefined
    this.core.changed(true, [])
  }

  /** Members with the source id each was downloaded as (for noticing what left the source). */
  membersOf(id: unknown): {id: string; sourceId?: string}[] {
    return this.collection(id).itemIds.map((itemId) => ({id: itemId, sourceId: this.core.entries().get(itemId)?.sourceId}))
  }

  /** Highest place in the source among saved members, -1 when none. */
  maxCollectionIndex(id: unknown): number {
    let max = -1
    for (const itemId of this.collection(id).itemIds) max = Math.max(max, this.core.entries().get(itemId)?.colIndex ?? -1)
    return max
  }
}
