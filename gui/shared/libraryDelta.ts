// The reference for applying a library delta (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md 10.2): a pure function that gives
// the snapshot the delta leads to. The renderer applies deltas incrementally (gui/web/library/libStore.ts); tests hold
// that and the index's pushes to this meaning.
import type {LibraryBatchPush, LibraryDeltaPush} from './contracts.js'
import type {Collection, LibraryItem, LibrarySnapshot} from './library.js'

/** Updated items keep their place, new ones are appended, removed ones leave: the order the index's snapshot() has. */
export function upsertById<T extends {id: string}>(list: readonly T[], changed: readonly T[] | undefined, removed: readonly string[] | undefined): T[] {
  const out = list.slice()
  const at = new Map(out.map((entry, index) => [entry.id, index] as const))
  for (const entry of changed ?? []) {
    const index = at.get(entry.id)
    if (index === undefined) {
      at.set(entry.id, out.length)
      out.push(entry)
    } else out[index] = entry
  }
  if (!removed?.length) return out
  const gone = new Set(removed)
  return out.filter((entry) => !gone.has(entry.id))
}

export function applyLibraryDelta(snapshot: LibrarySnapshot, delta: Omit<LibraryDeltaPush, 'type' | 'base'>): LibrarySnapshot {
  const next: LibrarySnapshot = {...snapshot, seq: delta.seq}
  if (delta.items || delta.removedItems) next.items = upsertById<LibraryItem>(snapshot.items, delta.items, delta.removedItems)
  if (delta.collections || delta.removedCollections) next.collections = upsertById<Collection>(snapshot.collections, delta.collections, delta.removedCollections)
  if (delta.meta) {
    next.scanning = delta.meta.scanning
    next.scannedAt = delta.meta.scannedAt
    next.problems = delta.meta.problems
    next.operation = delta.meta.operation
  }
  return next
}

/** The snapshot a complete set of batches makes. */
export function snapshotFromBatches(batches: readonly LibraryBatchPush[]): LibrarySnapshot {
  const first = batches[0]
  return {
    seq: first.seq,
    items: batches.flatMap((batch) => batch.items),
    collections: first.collections ?? [],
    scanning: first.meta?.scanning ?? false,
    scannedAt: first.meta?.scannedAt,
    problems: first.meta?.problems ?? [],
    operation: first.meta?.operation,
  }
}
