// Wires the merge engine into the app (keepfinBoot): the worker-thread stores, the network service, the profile list, the fences.
// Startup recovery runs here before the library opens, with what is known then; once the library is open, `attach` gives the engine
// the live pieces (the open store, the network service, the copies).
import {randomUUID} from 'node:crypto'
import os from 'node:os'
import path from 'node:path'
import type {CombineState} from '../../shared/combine.js'
import type {CopiesHost} from '../copiesHost.js'
import type {NetService} from '../net/service.js'
import {openKeepfinFolder} from '../store/keepfinFolder.js'
import type {LibrarySession} from '../store/persistence.js'
import {loadProfiles, profileDir, setProfileLibrary} from '../store/profiles.js'
import {StoreClient} from '../store/storeClient.js'
import {netPortFor} from './netPort.js'
import {CombineService} from './service.js'
import {SeparateEngine} from './separate.js'
import {clientStore} from './stores.js'
import type {MergeDeps, MergeFences, MergeStore, ProfileInfo} from './types.js'

export interface CombineBase {
  deviceDir: string
  appVersion: string
  push(event: {type: 'combine'; state: CombineState}): void
  /** Fences the writers (downloads pause, follow checks stop) and lets them go again. */
  pause(): void | Promise<void>
  resume(): void | Promise<void>
  relaunch(): void
}

/** What the engine needs once the library is open. */
export interface LiveCombine {
  profile: ProfileInfo
  persistence(): LibrarySession
  devices(): NetService | undefined
  held(): {libraryId: string; name: string}[]
  copies(): CopiesHost | undefined
  /** Sync with other devices is held while a merge writes (the network service serves no library). */
  holdSync(on: boolean): void
}

export interface CombineSupport {
  recover(): Promise<void>
  attach(live: LiveCombine): void
  engine: SeparateEngine
  service: CombineService
}

export async function combineSupport(base: CombineBase): Promise<CombineSupport> {
  const profiles = await loadProfiles(base.deviceDir).catch(() => undefined)
  const current = profiles?.profiles.find((p) => p.id === profiles.current)
  let live: LiveCombine | undefined
  let boot: ProfileInfo | undefined
  if (current) {
    const opened = await openKeepfinFolder(current.folder).catch(() => undefined)
    boot = {profileId: current.id, folder: current.folder, libraryId: current.libraryId, replicaId: opened?.ok ? opened.folder.replicaId : '', actorId: current.actorId, profileDir: profileDir(base.deviceDir, current.id), name: current.name}
  }
  let releaseWrites: (() => void) | undefined
  const fences: MergeFences = {
    async enter() {
      if (!live) return
      live.holdSync(true)
      releaseWrites = live.persistence().holdWrites()
      live.copies()?.pause()
      await base.pause()
    },
    async leave() {
      if (!live) return
      releaseWrites?.()
      releaseWrites = undefined
      live.holdSync(false)
      await base.resume()
    },
    async blockers() {
      if (!live) return []
      const out: string[] = []
      const copies = live.copies()
      if (copies) {
        const state = await copies.state().catch(() => undefined)
        if (state?.syncing) out.push('A copy is running on this device. Wait for it to finish, then try again.')
        else if (state && state.review.length) out.push('Some copies are waiting for your review. Look at them first, then combine.')
      }
      const status = await live.persistence().store.status().catch(() => undefined)
      if (status?.findings.some((f) => f.startsWith('unreadable-segment'))) out.push('Part of this library could not be read yet. Wait a moment and try again.')
      return out
    },
  }
  const sessionStore = (): MergeStore => clientStore(live!.persistence().store, {closeIt: false})
  const deps: MergeDeps = {
    deviceDir: base.deviceDir,
    newId: randomUUID,
    now: Date.now,
    deviceName: os.hostname().slice(0, 40) || 'This PC',
    profile: () => {
      const p = live?.profile ?? boot
      if (!p) throw new Error('There is no library on this device yet.')
      return p
    },
    sStore: sessionStore,
    activeStore: sessionStore,
    openStore: async (o) =>
      clientStore(
        await StoreClient.open({
          root: o.root,
          records: o.records,
          libraryId: o.libraryId,
          actor: o.actor,
          deviceDir: base.deviceDir,
          outboxDir: o.outboxDir,
          statePath: o.statePath,
          appVersion: base.appVersion,
          tuning: {},
          customRotate: true,
        }),
      ),
    files: {
      hint: (id) => {
        const record = live?.persistence().device.items.get(id)
        return record ? {size: record.size, modifiedAt: record.modifiedAt} : undefined
      },
      resolve: (placement) => live?.persistence().resolvePath(placement),
    },
    setProfileLibrary: async (id, library) => void (await setProfileLibrary(base.deviceDir, id, library)),
    net: netPortFor(() => live?.devices(), () => live?.held() ?? []),
    fences,
    artDir: path.join(base.deviceDir, 'library-art'),
    relaunch: base.relaunch,
    push: (state) => base.push({type: 'combine', state: state as CombineState}),
  }
  const engine = new SeparateEngine(deps)
  return {
    engine,
    service: new CombineService(engine),
    async recover() {
      try {
        await engine.recover()
      } catch (error) {
        console.error('A combine could not be continued at start:', error)
      }
    },
    attach(next) {
      live = next
    },
  }
}
