// Dirty detection: which library entries changed since the last save, found by comparing 32-bit hashes of what is synced
// with the hashes taken at the last save, so that only the changed entries are mapped and diffed (desktop spec section 10:
// a local edit stays within 30 ms).
//
// The tracker keeps no copy of an entry's values (that was about 440 bytes per entry at 200,000 entries). Per entry it keeps,
// in flat typed arrays indexed by a slot: one hash per synced field, one for the placement, one for the device-only fields,
// one whole-record check, the play count, the favourite flag, the size and the missing flag (for the storage overview) and
// references to the entry's own path, space and user-tag strings (no copies: the strings are the entry's).
//
// A save writes only the fields whose hash changed, with the new value taken from the live entry. Hash collisions can only
// hide a change, never invent one. Each field has two independent 32-bit hashes (FNV-1a and a multiply-xorshift): the first is
// kept per field, the second is folded with all the others into the whole-record check. A change is hidden only when both
// collide for the same change, about 2^-64 per changed field. When the whole-record check differs but no field hash does
// (the first hash collided, probability 2^-32 per change, and the second caught it), the entry's fields are all written again,
// which is idempotent. Hashes are never zero for a present value, so zero means "absent" and a present-to-absent change is
// always seen as a change.
import type {Collection} from '../../shared/library.js'
import type {LibraryEntry} from '../library/index.js'
import {DEVICE_ITEM_KEYS} from './device.js'
import {Slicer} from './slices.js'
import {encodeItemField, isUuid, ITEM_WIRE_COUNT, normalizePlays, normalizeTags, wireMedia, type ItemChange} from './mapping.js'

const WIRE = ITEM_WIRE_COUNT
const PLACE = WIRE
const DEVICE = WIRE + 1
const WHOLE = WIRE + 2
const STRIDE = WIRE + 3

const TOMB = 0xffffffff
const FAV = 1
const MISSING = 2

// ---- hashing -------------------------------------------------------------------------------------------------------------

let ha = 0
let hb = 0
const begin = (tag: number) => {
  ha = Math.imul(0x811c9dc5 ^ tag, 0x01000193)
  hb = Math.imul(0x9747b28c ^ tag, 0x5bd1e995)
  hb ^= hb >>> 13
}
const mixWord = (word: number) => {
  ha = Math.imul(ha ^ (word & 0xffff), 0x01000193)
  ha = Math.imul(ha ^ (word >>> 16), 0x01000193)
  hb = Math.imul(hb ^ (word & 0xffff), 0x5bd1e995)
  hb ^= hb >>> 13
  hb = Math.imul(hb ^ (word >>> 16), 0x5bd1e995)
  hb ^= hb >>> 13
}
const mixString = (text: string) => {
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i)
    ha = Math.imul(ha ^ c, 0x01000193)
    hb = Math.imul(hb ^ c, 0x5bd1e995)
    hb ^= hb >>> 13
  }
  mixWord(0x1f)
}

/** Hashes a value into ha/hb. Returns false for an absent value (undefined or null), which hashes to zero. */
function hashValue(value: unknown): boolean {
  switch (typeof value) {
    case 'string':
      begin(1)
      mixString(value)
      break
    case 'number':
      begin(2)
      mixWord(value | 0)
      mixWord(Math.floor(value / 4294967296) | 0)
      if (!Number.isInteger(value)) mixString(String(value))
      break
    case 'boolean':
      begin(3)
      mixWord(value ? 1 : 0)
      break
    case 'undefined':
      ha = hb = 0
      return false
    default:
      if (value === null) {
        ha = hb = 0
        return false
      }
      begin(4)
      if (Array.isArray(value)) {
        mixWord(value.length)
        for (const element of value) mixString(typeof element === 'string' ? element : JSON.stringify(element))
      } else mixString(JSON.stringify(value))
  }
  return true
}

/** The kept hash of the value just hashed: never zero for a present value. */
const kept = (present: boolean) => (present ? (ha || 1) >>> 0 : 0)

// ---- the item tracker ----------------------------------------------------------------------------------------------------

export type ItemScan = {
  added: LibraryEntry[]
  /** Entries whose synced fields (or placement, flags, tags, plays) changed. */
  synced: LibraryEntry[]
  /** Entries whose device-only fields changed (and possibly synced ones too). */
  device: LibraryEntry[]
  removed: string[]
  /** What changed in each of the `synced` entries that were tracked before (not in `added`). */
  changes: Map<string, ItemChange>
}

const sameTags = (a: readonly string[], b: readonly string[]) => {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false
  return true
}

/** Remembers what every managed entry looked like when it was last saved, as hashes. */
export class ItemTracker {
  // id -> slot: an open-addressing table of slot+1 (0 empty, TOMB deleted), with the ids by slot to compare. A Map of 200,000 ids
  // costs 7 MB; this costs 2 MB plus the pointers.
  private table = new Uint32Array(0)
  private slotIds: (string | undefined)[] = []
  private live = 0
  private tombs = 0
  private capacity = 0
  private used = 0
  private free: number[] = []
  private hashes = new Uint32Array(0)
  private plays = new Float64Array(0)
  private sizes = new Float64Array(0)
  private flags = new Uint8Array(0)
  private gens = new Uint32Array(0)
  private paths: (string | undefined)[] = []
  /** The space of each slot, as an index into [spaceNames] (a library has a handful of spaces). */
  private spaceAt = new Uint16Array(0)
  private spaceNames: string[] = []
  private readonly spaceIndex = new Map<string, number>()
  /** The saved user tags by slot, only for the entries that have any (most have none). */
  private tags = new Map<number, readonly string[]>()
  private gen = 0
  private readonly tmp = new Uint32Array(STRIDE)

  has = (id: string) => this.lookup(id) >= 0

  get size() {
    return this.live
  }

  private slotHash(id: string) {
    let h = 0x811c9dc5
    for (let i = 0; i < id.length; i++) h = Math.imul(h ^ id.charCodeAt(i), 0x01000193)
    return h ^ (h >>> 15)
  }

  /** The slot of an id, or -1. */
  private lookup(id: string): number {
    const table = this.table
    if (table.length === 0) return -1
    const mask = table.length - 1
    for (let i = this.slotHash(id) & mask; ; i = (i + 1) & mask) {
      const v = table[i]
      if (v === 0) return -1
      if (v !== TOMB && this.slotIds[v - 1] === id) return v - 1
    }
  }

  private insert(id: string, slot: number) {
    if ((this.live + this.tombs + 1) * 2 > this.table.length) this.resizeTable(Math.max(1024, this.live * 4))
    const table = this.table
    const mask = table.length - 1
    let i = this.slotHash(id) & mask
    while (table[i] !== 0 && table[i] !== TOMB) i = (i + 1) & mask
    if (table[i] === TOMB) this.tombs--
    table[i] = slot + 1
    this.slotIds[slot] = id
    this.live++
  }

  private erase(id: string, slot: number) {
    const table = this.table
    const mask = table.length - 1
    for (let i = this.slotHash(id) & mask; ; i = (i + 1) & mask) {
      if (table[i] === 0) return
      if (table[i] === slot + 1) {
        table[i] = TOMB
        this.tombs++
        this.live--
        this.slotIds[slot] = undefined
        return
      }
    }
  }

  private resizeTable(want: number) {
    let length = 1024
    while (length < want) length *= 2
    const table = new Uint32Array(length)
    const mask = length - 1
    for (let slot = 0; slot < this.used; slot++) {
      const id = this.slotIds[slot]
      if (id === undefined) continue
      let i = this.slotHash(id) & mask
      while (table[i] !== 0) i = (i + 1) & mask
      table[i] = slot + 1
    }
    this.table = table
    this.tombs = 0
  }

  private spaceOf(space: string): number {
    let at = this.spaceIndex.get(space)
    if (at === undefined) {
      at = this.spaceNames.length
      if (at >= 0xffff) return 0xffff
      this.spaceNames.push(space)
      this.spaceIndex.set(space, at)
    }
    return at
  }

  private reserve(count: number) {
    if (count <= this.capacity) return
    // Growth is by an eighth (a copy of the arrays each time), not by half: at 200,000 entries half is 12 MB that stays.
    const capacity = Math.max(count, Math.ceil(this.capacity * 1.125), 1024)
    const grow = <T extends Uint32Array | Float64Array | Uint8Array | Uint16Array>(old: T, length: number): T => {
      const next = new (old.constructor as new (n: number) => T)(length)
      next.set(old)
      return next
    }
    this.hashes = grow(this.hashes, capacity * STRIDE)
    this.plays = grow(this.plays, capacity)
    this.sizes = grow(this.sizes, capacity)
    this.flags = grow(this.flags, capacity)
    this.gens = grow(this.gens, capacity)
    this.spaceAt = grow(this.spaceAt, capacity)
    this.capacity = capacity
  }

  /** Hashes everything the tracker compares into `out` at offset `at`. */
  private compute(entry: LibraryEntry, out: Uint32Array, at: number) {
    let whole = 0x811c9dc5
    for (let i = 0; i < WIRE; i++) {
      const value = i === 0 ? wireMedia(entry.kind) : i === 1 ? entry.space : encodeItemField(entry, i)
      out[at + i] = kept(hashValue(value))
      whole = Math.imul(whole ^ hb, 0x01000193)
    }
    // The placement is a function of the path and the space folders; the path is hashed (a placement is not computed per scan),
    // and the placement itself is computed for the entries that changed, when their operations are made.
    out[at + PLACE] = kept(hashValue(entry.path))
    whole = Math.imul(whole ^ hb, 0x01000193)
    let device = 0x811c9dc5
    let deviceTwo = 0
    for (const key of DEVICE_ITEM_KEYS) {
      const present = hashValue((entry as unknown as Record<string, unknown>)[key])
      device = Math.imul(device ^ (present ? ha : 0), 0x01000193)
      deviceTwo = Math.imul(deviceTwo ^ (present ? hb : 0), 0x01000193)
    }
    out[at + DEVICE] = device >>> 0
    whole = Math.imul(whole ^ deviceTwo, 0x01000193)
    out[at + WHOLE] = whole >>> 0
  }

  private store(slot: number, entry: LibraryEntry) {
    this.plays[slot] = normalizePlays(entry.plays)
    this.sizes[slot] = typeof entry.size === 'number' ? entry.size : 0
    this.flags[slot] = (entry.favorite === true ? FAV : 0) | (entry.missing ? MISSING : 0)
    this.paths[slot] = entry.path
    this.spaceAt[slot] = this.spaceOf(entry.space)
    this.setTags(slot, entry.userTags)
  }

  private setTags(slot: number, tags: readonly string[] | undefined) {
    if (tags === undefined) this.tags.delete(slot)
    else this.tags.set(slot, tags)
  }

  private record(entry: LibraryEntry, gen: number) {
    let slot = this.lookup(entry.id)
    if (slot < 0) {
      slot = this.free.pop() ?? this.used++
      this.reserve(slot + 1)
      this.insert(entry.id, slot)
    }
    this.compute(entry, this.hashes, slot * STRIDE)
    this.store(slot, entry)
    this.gens[slot] = gen
  }

  private clear(expected: number) {
    this.free = []
    this.used = 0
    this.live = 0
    this.tombs = 0
    this.gen++
    this.slotIds = []
    this.paths = []
    this.tags = new Map()
    this.table = new Uint32Array(0)
    this.resizeTable(expected * 2 + 2)
    if (this.capacity < expected || this.capacity > expected * 2 + 4096) {
      this.capacity = 0
      this.hashes = new Uint32Array(0)
      this.plays = new Float64Array(0)
      this.sizes = new Float64Array(0)
      this.flags = new Uint8Array(0)
      this.gens = new Uint32Array(0)
      this.spaceAt = new Uint16Array(0)
      this.reserve(expected + (expected >> 5))
    }
  }

  /** Starts over from a full list (after a full diff or a reload). */
  rebuild(items: readonly LibraryEntry[]) {
    this.clear(items.length)
    for (const entry of items) if (entry.managed !== false && isUuid(entry.id)) this.record(entry, this.gen)
  }

  /** Takes an entry's current values as the saved ones (after a remote change put it in the model). */
  adopt(entry: LibraryEntry) {
    this.record(entry, this.gen)
  }

  remove(id: string) {
    const slot = this.lookup(id)
    if (slot < 0) return
    this.erase(id, slot)
    this.paths[slot] = undefined
    this.tags.delete(slot)
    this.gens[slot] = 0
    this.free.push(slot)
  }

  /** One saved value of an entry (undefined when the entry is not tracked): only the path and the user tags are kept as values. */
  get(id: string, key: 'path' | 'userTags'): unknown {
    const slot = this.lookup(id)
    if (slot < 0) return undefined
    return key === 'path' ? this.paths[slot] : this.tags.get(slot)
  }

  /** Compares an entry with its saved hashes, takes the new ones, and reports what changed. */
  private compare(slot: number, entry: LibraryEntry, out: ItemScan) {
    const at = slot * STRIDE
    const now = this.tmp
    this.compute(entry, now, 0)
    const was = this.hashes
    let device = now[DEVICE] !== was[at + DEVICE]
    let fields: number[] | undefined
    let place = now[PLACE] !== was[at + PLACE]
    for (let i = 0; i < WIRE; i++) if (now[i] !== was[at + i]) (fields ??= []).push(i)
    if (!fields && !place && now[WHOLE] !== was[at + WHOLE] && !device) {
      // The whole-record check saw a change that the field hashes missed (one of them collided): write every field again.
      fields = []
      for (let i = 0; i < WIRE; i++) if (now[i] !== 0 || was[at + i] !== 0) fields.push(i)
      place = true
      device = true
    }
    const playsNow = normalizePlays(entry.plays)
    const playsWas = this.plays[slot]
    const favoriteNow = entry.favorite === true
    const favoriteWas = (this.flags[slot] & FAV) !== 0
    let tagsWas: string[] | undefined
    const tagsWere = this.tags.get(slot)
    if (entry.userTags !== tagsWere) {
      const before = normalizeTags(tagsWere)
      if (!sameTags(before, normalizeTags(entry.userTags))) tagsWas = before
    }
    const changed = fields !== undefined || place || playsNow !== playsWas || favoriteNow !== favoriteWas || tagsWas !== undefined
    if (changed) {
      const change: ItemChange = {fields: fields ?? [], place, playsWas}
      if (favoriteNow !== favoriteWas) change.favoriteWas = favoriteWas
      if (tagsWas) change.tagsWas = tagsWas
      out.synced.push(entry)
      out.changes.set(entry.id, change)
    }
    if (device) out.device.push(entry)
    if (changed || device) {
      for (let i = 0; i < STRIDE; i++) was[at + i] = now[i]
      this.store(slot, entry)
    } else {
      // Unchanged in what is compared; the references still follow the entry (a replaced array with the same content, say).
      this.paths[slot] = entry.path
      this.setTags(slot, entry.userTags)
    }
  }

  /** Looks at the named entries only. An id with no managed entry is a removed one. */
  scanIds(ids: readonly string[], entryOf: (id: string) => LibraryEntry | undefined): ItemScan {
    const out: ItemScan = {added: [], synced: [], device: [], removed: [], changes: new Map()}
    for (const id of ids) {
      const entry = entryOf(id)
      const slot = this.lookup(id)
      if (!entry || entry.managed === false) {
        if (slot >= 0) {
          this.remove(id)
          out.removed.push(id)
        }
        continue
      }
      if (slot < 0) {
        if (!isUuid(id)) continue
        out.added.push(entry)
        this.record(entry, this.gen)
        continue
      }
      this.compare(slot, entry, out)
    }
    return out
  }

  /** The same as rebuild(), in slices that give the thread back in between. */
  async rebuildInChunks(items: readonly LibraryEntry[]) {
    this.clear(items.length)
    const slicer = new Slicer()
    for (let i = 0; i < items.length; i++) {
      const entry = items[i]
      if (entry.managed !== false && isUuid(entry.id)) this.record(entry, this.gen)
      if ((i & 31) === 31) await slicer.tick()
    }
  }

  /** Files, bytes and missing files per space, from the saved values (no entries needed). */
  summary(): Map<string, {files: number; bytes: number; missing: number}> {
    const out = new Map<string, {files: number; bytes: number; missing: number}>()
    for (let slot = 0; slot < this.used; slot++) {
      if (this.slotIds[slot] === undefined) continue
      const id = String(this.spaceNames[this.spaceAt[slot]])
      let row = out.get(id)
      if (!row) out.set(id, (row = {files: 0, bytes: 0, missing: 0}))
      row.files++
      row.bytes += this.sizes[slot]
      if (this.flags[slot] & MISSING) row.missing++
    }
    return out
  }

  ids(): string[] {
    const out: string[] = []
    for (let slot = 0; slot < this.used; slot++) {
      const id = this.slotIds[slot]
      if (id !== undefined) out.push(id)
    }
    return out
  }

  scan(items: readonly LibraryEntry[]): ItemScan {
    const gen = ++this.gen
    const out: ItemScan = {added: [], synced: [], device: [], removed: [], changes: new Map()}
    let seen = 0
    for (const entry of items) {
      if (entry.managed === false) continue
      const slot = this.lookup(entry.id)
      if (slot < 0) {
        if (!isUuid(entry.id)) continue
        out.added.push(entry)
        this.record(entry, gen)
        continue
      }
      this.gens[slot] = gen
      seen++
      this.compare(slot, entry, out)
    }
    if (seen < this.live - out.added.length) {
      for (let slot = 0; slot < this.used; slot++) {
        const id = this.slotIds[slot]
        if (id !== undefined && this.gens[slot] !== gen) out.removed.push(id)
      }
      for (const id of out.removed) this.remove(id)
    }
    return out
  }
}

type RawCollection = {scalars: string; itemIds: string[]; seen: string[]; news: string[]; removed: string[]; gen: number}

const arr = (value: readonly string[] | undefined) => (value ? value.slice() : [])
const sameList = (now: readonly string[] | undefined, was: readonly string[]) => {
  const list = now ?? []
  if (list.length !== was.length) return false
  for (let i = 0; i < list.length; i++) if (list[i] !== was[i]) return false
  return true
}
const scalarsOf = (c: Collection) => JSON.stringify([c.name, c.kind, c.space, c.coverItemId, c.source, c.follow, c.createdAt, c.updatedAt])

export type CollectionScan = {added: Collection[]; changed: Collection[]; removed: string[]}

export class CollectionTracker {
  private raw = new Map<string, RawCollection>()
  private gen = 0

  private snapshotOf(c: Collection, gen: number): RawCollection {
    return {scalars: scalarsOf(c), itemIds: arr(c.itemIds), seen: arr(c.seen), news: arr(c.newItemIds), removed: arr(c.removedItemIds), gen}
  }

  has = (id: string) => this.raw.has(id)

  adopt(c: Collection) {
    this.raw.set(c.id, this.snapshotOf(c, this.gen))
  }

  remove(id: string) {
    this.raw.delete(id)
  }

  /** The saved set-valued fields of a collection. */
  sets(id: string): {seen: string[]; news: string[]; removed: string[]} {
    const raw = this.raw.get(id)
    return {seen: raw?.seen ?? [], news: raw?.news ?? [], removed: raw?.removed ?? []}
  }

  rebuild(collections: readonly Collection[]) {
    this.raw = new Map()
    this.gen++
    for (const c of collections) if (isUuid(c.id)) this.raw.set(c.id, this.snapshotOf(c, this.gen))
  }

  scan(collections: readonly Collection[]): CollectionScan {
    const gen = ++this.gen
    const out: CollectionScan = {added: [], changed: [], removed: []}
    for (const c of collections) {
      if (!isUuid(c.id)) continue
      const raw = this.raw.get(c.id)
      if (!raw) {
        out.added.push(c)
        this.raw.set(c.id, this.snapshotOf(c, gen))
        continue
      }
      raw.gen = gen
      const scalars = scalarsOf(c)
      if (scalars === raw.scalars && sameList(c.itemIds, raw.itemIds) && sameList(c.seen, raw.seen) && sameList(c.newItemIds, raw.news) && sameList(c.removedItemIds, raw.removed)) continue
      out.changed.push(c)
      this.raw.set(c.id, this.snapshotOf(c, gen))
    }
    for (const [id, raw] of this.raw) if (raw.gen !== gen) out.removed.push(id)
    for (const id of out.removed) this.raw.delete(id)
    return out
  }
}
