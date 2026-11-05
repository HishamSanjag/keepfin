// Turns an open library store (the worker-thread StoreClient in the app, a KeepfinStore in tests) into the NetLibrary the network
// layer works with.
import {watch, type FSWatcher} from 'node:fs'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {SyncMembership} from '../../shared/core/sync.js'
import {probeNameRules, scanInventory} from '../copies/inventory.js'
import {resolvePlacement} from '../store/device.js'
import {opsDirOf} from './segments.js'
import type {NetLibrary} from './types.js'

type Maybe<T> = T | Promise<T>

export interface StoreLike {
  readonly actorId: string
  flush(): Promise<void>
  rescan(options?: {force?: boolean}): Promise<void>
  membership(): Maybe<SyncMembership>
  enableMembership(grant?: {actor: string; spkiBase64: string; perms: string[]}): Promise<void>
  grantMember(actor: string, spkiBase64: string, perms: string[]): Promise<void>
  revokeMember(actor: string, cutoff: number): Promise<void>
  contentItems(contentId: string): Maybe<{id: string; placements: Record<string, string | null>}[]>
}

export function netLibraryFor(store: StoreLike, options: {libraryId: string; name: string; root: string; records?: string; watchDebounceMs?: number}): NetLibrary {
  const {root} = options
  return {
    libraryId: options.libraryId,
    name: options.name,
    root,
    records: options.records,
    actor: () => store.actorId,
    flush: () => store.flush(),
    rescan: () => store.rescan(),
    membership: async () => store.membership(),
    ensureMembership: (grant) => store.enableMembership(grant),
    grantMember: (actor, spki, perms) => store.grantMember(actor, spki, perms),
    revokeMember: (actor, cutoff) => store.revokeMember(actor, cutoff),
    onChange(listener) {
      // any segment file appearing under ops/ (our own flush or a peer's arrival) is a change worth telling peers about
      let timer: NodeJS.Timeout | undefined
      let watcher: FSWatcher | undefined
      try {
        watcher = watch(opsDirOf(root, options.records), {recursive: true, persistent: false}, () => {
          clearTimeout(timer)
          timer = setTimeout(listener, options.watchDebounceMs ?? 300)
          timer.unref?.()
        })
        watcher.on('error', () => undefined)
      } catch {
        // a folder that cannot be watched: peers still pull on their own schedule
      }
      return () => {
        clearTimeout(timer)
        watcher?.close()
      }
    },
    async findContent(contentId) {
      for (const item of await store.contentItems(contentId)) {
        for (const placement of Object.values(item.placements)) {
          if (!placement) continue
          const file = resolvePlacement(placement, root, [])
          if (file && (await fs.stat(file).then((s) => s.isFile(), () => false))) return file
        }
      }
      return null
    },
    async scanInventory(outFile, tmpDir) {
      return scanInventory(root, outFile, {rules: await probeNameRules(root), tmpDir: path.join(tmpDir, 'sort')})
    },
  }
}
