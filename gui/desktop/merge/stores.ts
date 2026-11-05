// MergeStore over the two kinds of store the desktop has: the worker-thread client the app uses, and the store class itself (tests,
// and callers that already hold one).
import type {SyncMembership, SyncObserved, SyncObservedQuery} from '../../shared/core/sync.js'
import type {OpDraft} from '../store/mapping.js'
import {readMeta, scanRows, type MergeMeta, type ScanRow} from '../store/mergeScan.js'
import type {KeepfinStore} from '../store/store.js'
import type {StoreClient} from '../store/storeClient.js'
import type {MergeStore} from './types.js'

export function directStore(store: KeepfinStore, options: {closeIt?: boolean} = {}): MergeStore {
  return {
    get actorId() {
      return store.actorId
    },
    apply: (drafts: readonly OpDraft[]) => store.apply(drafts),
    flush: () => store.flush(),
    rescan: () => store.rescan(),
    lastSeqOf: async (actor) => store.currentLast().get(actor) ?? 0,
    async scan(onRows) {
      await store.flush()
      let rows: ScanRow[] = []
      for (const row of scanRows(store)) {
        rows.push(row)
        if (rows.length >= 1000) {
          await onRows(rows)
          rows = []
        }
      }
      if (rows.length) await onRows(rows)
    },
    meta: async (): Promise<MergeMeta> => readMeta(store),
    observed: async (query: SyncObservedQuery): Promise<SyncObserved> => store.observed(query),
    membership: async (): Promise<SyncMembership> => store.membership(),
    close: async () => {
      if (options.closeIt !== false) await store.close()
    },
  }
}

export function clientStore(client: StoreClient, options: {closeIt?: boolean} = {}): MergeStore {
  return {
    get actorId() {
      return client.actorId
    },
    apply: (drafts: readonly OpDraft[]) => client.apply(drafts),
    flush: () => client.flush(),
    rescan: () => client.rescan(),
    lastSeqOf: async (actor) => (await client.status()).last.find(([a]) => a === actor)?.[1] ?? 0,
    scan: (onRows) => client.mergeScan(onRows),
    meta: () => client.mergeMeta(),
    observed: (query) => client.observed(query),
    membership: () => client.membership(),
    close: async () => {
      if (options.closeIt !== false) await client.close()
    },
  }
}
