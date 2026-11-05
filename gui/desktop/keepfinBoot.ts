// Starts the Keepfin folder (phase 1) inside the Electron app, behind KEEPFIN_FOLDER=1. Free of Electron imports: main.ts
// passes the dialogs, shell and fences in. See dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md sections 2, 5, 6 and 8.
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {CopiesArgs, CopiesState, CopyDraft, CopyPreview} from '../shared/copies.js'
import type {DevicesArgs, DevicesState, LibraryProfile, PlayFromResult} from '../shared/devices.js'
import type {LibrarySettings} from '../shared/library.js'
import type {MoveCheck, MoveProgress, SetupProgress, StorageArgs, StorageOverview, StorageState} from '../shared/storage.js'
import {CopiesHost} from './copiesHost.js'
import {loadIdentity, type KeyBox} from './net/identity.js'
import type {JobPort} from './net/jobs.js'
import type {FirewallPort} from './net/firewall.js'
import {handleDevices, unavailableState} from './net/ops.js'
import {NetService} from './net/service.js'
import {netLibraryFor} from './net/storeLibrary.js'
import type {NetLibrary} from './net/types.js'
import {CloudAccounts, type SecretBox} from './cloud/accounts.js'
import {buildConfig} from './cloud/oauth.js'
import type {LibraryPersistence} from './library/index.js'
import type {PlayFromOutcome} from './library/ops.js'
import {playFromItem} from './net/playFromItem.js'
import {combineSupport} from './merge/support.js'
import {emptyCombineState, type CombineArgs, type CombineState} from '../shared/combine.js'
import {emptyWhere, type WhereItemView, type WhereState} from '../shared/where.js'
import {WhereHost} from './whereHost.js'
import {readJson, writeJsonAtomic} from './store/atomic.js'
import {acquireFolderLock, createKeepfinFolder, defaultFolderPath, openKeepfinFolder} from './store/keepfinFolder.js'
import {readJournal} from './store/migration/journal.js'
import type {Plan} from './store/migration/types.js'
import {syncMusicShortcut, type ShortcutShell} from './store/musicShortcut.js'
import {importPlan, StoreLibrary} from './store/persistence.js'
import {addProfile, createFirstProfile, defaultProfileName, ensureProfileDir, loadProfiles, pickColor, profileDir, setCurrentProfile} from './store/profiles.js'
import {finishMove, forgetMove, forgetPendingBackup, moveProblem, readMove, readPendingBackup, requestMove, runMove} from './store/folderMove.js'
import {StorageService} from './store/service.js'
import {StoreClient} from './store/storeClient.js'
import {createSetupPort} from './store/setupPort.js'

export type KeepfinBootDeps = {
  /** %APPDATA%\Keepfin */
  deviceDir: string
  userMusicDir: string
  appVersion: string
  pickFolder(defaultPath: string): Promise<string | undefined>
  /** The audio converter for MP3 exports on copies. */
  ffmpeg?: () => string | undefined
  openPath(target: string): Promise<void>
  shortcuts: ShortcutShell
  trash(file: string): Promise<void>
  /** A system notification for what a sync the person started did. */
  notify?(note: {title: string; body: string}): void
  saveReportDialog(text: string): Promise<boolean>
  /** Cloud accounts: the browser for sign-in and the secure storage for tokens (Electron's shell and safeStorage). */
  cloud?: {box: SecretBox; openExternal(url: string): Promise<void>}
  /** Devices on the network: the secure storage that wraps this install's device key (Electron's safeStorage), and the queue behind "Download on…". */
  devices?: {box: KeyBox; jobs?: JobPort; checkUrl?: (url: string) => Promise<string>; firewall?: FirewallPort}
  push(event: {type: 'storage'; state: StorageState} | {type: 'setup'; progress: SetupProgress} | {type: 'storageMove'; progress: MoveProgress} | {type: 'copies'; state: CopiesState} | {type: 'devices'; state: DevicesState} | {type: 'combine'; state: CombineState} | {type: 'where'; state: WhereState}): void
  /** Fences the writers while the migration runs (the queue, follow checks). */
  pause(): void | Promise<void>
  resume(): void | Promise<void>
  /** Restarts the app so it opens the library that was just created. */
  relaunch(): void
  /** Change location: main closes what reads the library (downloads, the index) so the folder can move without a restart first. */
  closeLibrary?(): Promise<void>
}

export type KeepfinBoot = {
  /** The library to give the LibraryIndex; set only when a profile was opened. */
  persistence?: LibraryPersistence
  /** True when the app opened a Keepfin folder (phase 'ready'); false behind the first-start and setup screens. */
  live: boolean
  /** The open Keepfin folder's root while `live` (a deleted file inside it goes to its trash), otherwise undefined. */
  root?: string
  /** The storage* and setup* requests. */
  handle(op: string, args: StorageArgs): Promise<unknown>
  /** The copies* requests (Settings → Storage → Copies). */
  handleCopies(op: string, args: CopiesArgs): Promise<unknown>
  /** The devices* requests (Settings → Devices). Play from answers a media URL; everything else the current state. */
  handleDevices(op: string, args: DevicesArgs): Promise<DevicesState | PlayFromResult>
  /** The where* requests (gui/shared/where.ts): the places that hold the library, and the places one file is on (`id` is the item). */
  handleWhere(op: string, id?: string): Promise<WhereState | WhereItemView>
  /** The combine* requests (combining two libraries: gui/shared/combine.ts). */
  handleCombine(op: string, args: CombineArgs): Promise<CombineState>
  /** The device that asked for a download (survives restarts), for the queue to mark and to check its link. */
  jobOrigin(jobId: string): {device: string} | undefined
  /** The source behind a `keepfin-media://peer/<token>` URL, or undefined. */
  peerSource(token: string): ReturnType<NetService['peerSource']>
  /** Play from a paired device: a media URL for the library item (its content is looked up in the library), or undefined. */
  playFromItem(itemId: string, fileName: string): Promise<PlayFromOutcome | undefined>
  close(): Promise<void>
}

/** What the app answers to the storage requests when the flag is off: ready, with no folder (the renderer shows the app). */
export const flagOffState: StorageState = {phase: 'ready', defaultPath: ''}

/**
 * Change location › Keep the old place as a backup copy: the old folder is added under Storage › Copies as a Backup of everything.
 * Its files are already there, so the first sync only checks them. Until it is added, every start tries again.
 */
async function addPendingBackup(host: CopiesHost, deviceDir: string): Promise<void> {
  const target = await readPendingBackup(deviceDir)
  if (!target) return
  const draft: CopyDraft = {
    path: target,
    name: 'Backup (old place)',
    mode: 'backup',
    holds: {all: true, spaces: [], playlists: [], favourites: false},
    schedule: {everyHours: null, auto: true},
  }
  const preview = (await host.handle('copiesPreview', {draft})) as CopyPreview
  if (preview.status !== 'ready') {
    if (preview.placeId) await host.handle('copiesCancelPreview', {placeId: preview.placeId}).catch(() => undefined)
    console.error('The old place could not become a backup copy yet:', preview.reason)
    return
  }
  await host.handle('copiesAdd', {placeId: preview.placeId})
  await forgetPendingBackup(deviceDir)
}

export async function bootKeepfinFolder(deps: KeepfinBootDeps): Promise<KeepfinBoot> {
  const {deviceDir} = deps
  // The device key (network spec section 1) is made on first use; every store opened from here on signs its segments with it.
  let deviceKeyProblem = ''
  if (deps.devices) {
    try {
      const identity = await loadIdentity(deviceDir, deps.devices.box)
      StoreClient.defaultSigner = {pkcs8: Buffer.from(identity.pkcs8).toString('base64'), spki: Buffer.from(identity.spki).toString('base64')}
    } catch (error) {
      deviceKeyProblem = error instanceof Error ? error.message : String(error)
      console.error('The device key could not be loaded; segments stay unsigned:', deviceKeyProblem)
    }
  }
  // A combine or a separate that was stopped is finished before the library opens (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 3):
  // it may switch the folder's pointer and the profile, so nothing may be reading them yet. Steps that need the other device wait.
  const merging = await combineSupport(deps)
  await merging.recover()
  const profiles = await loadProfiles(deviceDir)
  const profile = profiles?.profiles.find((entry) => entry.id === profiles.current)
  const library = new StoreLibrary({
    deviceDir,
    profileDir: profile ? profileDir(deviceDir, profile.id) : undefined,
    newId: randomUUID,
    now: Date.now,
    appVersion: deps.appVersion,
    deferStore: true,
  })
  let release: (() => Promise<void>) | undefined
  let opened_: {root: string; records?: string; replicaId: string; libraryId: string} | undefined
  // Storage › Change location: a requested move runs in the move window before the library opens; a finished one is tidied up.
  let move = profile ? await readMove(deviceDir) : undefined
  let moving = !!move && move.from === profile?.folder
  if (move && !moving) await finishMove(move, {deviceDir, push: () => {}, trash: deps.trash}).catch((error) => console.error('Could not tidy up after the folder move:', error))
  if (profile && !moving) {
    const opened = await openKeepfinFolder(profile.folder)
    if (!opened.ok) {
      throw new Error(
        opened.reason === 'newer-format'
          ? 'The Keepfin folder was made by a newer version of Keepfin. Update Keepfin to open it.'
          : `The Keepfin folder at ${profile.folder} could not be opened (${opened.reason}). Reconnect its drive or restore the folder.`,
      )
    }
    const lock = await acquireFolderLock(deviceDir, opened.folder.replicaId)
    if (!lock.ok) throw new Error('Keepfin is already open on this folder.')
    release = lock.release
    await library.open(opened.folder, profile.actorId)
    opened_ = {root: opened.folder.root, records: opened.folder.records, replicaId: opened.folder.replicaId, libraryId: opened.folder.libraryId}
  }
  // false once the library closes for Change location (the folder then moves in this same run)
  let live = library.isOpen

  // Where your files are: the counts of the places that hold the library (gui/shared/where.ts). Copies and devices tell it when they move on.
  let where: WhereHost | undefined

  // Copies on folders and drives: a host over the open library. It starts once the store is open (it reads the state database).
  let copies: CopiesHost | undefined
  // A combined folder keeps its records in a records root of its own; copies are set up again under it (dev-docs/plans/LIBRARY-MERGE.md,
  // Scope), so the copies host is not started for one.
  if (live && profile && opened_ && !opened_.records) {
    const pdir = profileDir(deviceDir, profile.id)
    const session = library.persistence
    copies = new CopiesHost({
      profileDir: pdir,
      mainRoot: opened_.root,
      mainReplica: opened_.replicaId,
      libraryId: opened_.libraryId,
      dbPath: path.join(pdir, 'state', 'library-state.v1.db'),
      store: () => session.store,
      newId: randomUUID,
      now: Date.now,
      device: os.hostname(),
      push: (event) => {
        deps.push(event)
        where?.noteOthersChanged()
      },
      pickFolder: deps.pickFolder,
      ffmpeg: deps.ffmpeg,
      trash: deps.trash,
      notify: (report) => deps.notify?.({title: report.title, body: report.detail}),
      // a copy on a paired device: the network service is made once the store is open, so it is looked up when needed
      peers: () => devices?.peerBridge(opened_!.libraryId),
      // accounts live beside this profile's other device-local files and are never synced
      ...(deps.cloud
        ? {cloud: {accounts: new CloudAccounts({dir: path.join(pdir, 'cloud'), box: deps.cloud.box, config: buildConfig()}), config: buildConfig(), openExternal: deps.cloud.openExternal}}
        : {}),
    })
    const host = copies
    void session.ready
      .then(() => host.start())
      .then(() => addPendingBackup(host, deviceDir))
      .catch(() => undefined)
  }

  // Devices on the network: the service starts once the store is open (it needs the library). It listens only after the person turns it on.
  let devices: NetService | undefined
  let profileList = profiles?.profiles ?? []
  /** The library profiles on this device, for "Open another library" (the app restarts into the chosen one). */
  const withProfiles = (state: DevicesState): DevicesState => ({
    ...state,
    profiles: profileList.map((p): LibraryProfile => ({id: p.id, name: p.name, libraryId: p.libraryId, current: p.id === profile?.id})),
  })
  let devicesReady: Promise<void> = Promise.resolve()
  let syncHeld = false
  if (live && profile && opened_ && deps.devices && !deviceKeyProblem) {
    const session = library.persistence
    const folder = opened_
    let netLibrary: NetLibrary | undefined
    devicesReady = session.ready
      .then(async () => {
        netLibrary = netLibraryFor(session.store, {libraryId: folder.libraryId, name: profile.name, root: folder.root, records: folder.records})
        devices = await NetService.create({
          deviceDir,
          box: deps.devices!.box,
          libraries: () => (netLibrary && !syncHeld ? [netLibrary] : []),
          push: (state) => {
            deps.push({type: 'devices', state: withProfiles(state)})
            copies?.devicesChanged()
            where?.noteOthersChanged()
          },
          jobs: deps.devices!.jobs,
          firewall: deps.devices!.firewall,
          checkUrl: deps.devices!.checkUrl,
          // a library taken in from another device becomes a profile of this one; the person opens it when they choose
          adoptJoined: async (joined) => {
            const existing = await loadProfiles(deviceDir)
            const id = randomUUID()
            await ensureProfileDir(deviceDir, id)
            await addProfile(deviceDir, {id, name: joined.name, color: pickColor(existing?.profiles.length ?? 1), folder: joined.root, libraryId: joined.libraryId, actorId: joined.actor, now: new Date().toISOString()})
            profileList = (await loadProfiles(deviceDir))?.profiles ?? profileList
          },
        })
      })
      .catch((error) => console.error('Devices on the network could not start:', error))
  }

  if (live && profile && opened_) {
    const session = library.persistence
    const mainReplica = opened_.replicaId
    const host = new WhereHost({
      store: () => session.store,
      mainReplica,
      copies: () => copies?.whereInputs() ?? {copies: [], rows: []},
      devices: () => devices?.state(),
      push: deps.push,
      thisName: () => os.hostname(),
    })
    where = host
    // a library change (ours or another device's) makes the counts stale
    void session.ready.then(() => session.store.onEvent((event) => event.name === 'change' && host.noteLibraryChanged())).catch(() => undefined)
  }

  // Combining two libraries: the engine gets the live pieces now that the library is open.
  if (live && profile && opened_) {
    const folder = opened_
    merging.attach({
      profile: {profileId: profile.id, folder: folder.root, libraryId: folder.libraryId, replicaId: folder.replicaId, actorId: profile.actorId, profileDir: profileDir(deviceDir, profile.id), name: profile.name},
      persistence: () => library.persistence,
      devices: () => devices,
      held: () => profileList.map((p) => ({libraryId: p.libraryId, name: p.name})),
      copies: () => copies,
      holdSync(on) {
        syncHeld = on
        if (on) return
        // sync picks up where it stopped
        for (const peer of devices?.state().paired ?? []) {
          try {
            devices?.syncNow(peer.deviceId)
          } catch {
            // a device that is not connected right now syncs when it is
          }
        }
      },
    })
  }

  const applyShortcut = (root: string) =>
    syncMusicShortcut({enabled: true, keepfinMusicDir: path.join(root, 'Music'), userMusicDir: deps.userMusicDir, shell: deps.shortcuts}).catch(() => undefined)

  /**
   * The switch point of the setup window: the Keepfin folder is created at the plan's target, the old library becomes its
   * first operations, and profiles.json is written last. Safe to run again after a crash.
   */
  const commit = async (plan: Plan, context: {runDir: string}) => {
    if (await loadProfiles(deviceDir)) return
    const marker = path.join(context.runDir, 'commit.json')
    let ids = await readJson<{libraryId: string; replicaId: string; actorId: string; profileId: string}>(marker).catch(() => undefined)
    if (!ids) {
      ids = {libraryId: randomUUID(), replicaId: randomUUID(), actorId: randomUUID(), profileId: randomUUID()}
      await writeJsonAtomic(marker, ids)
    }
    let opened = await openKeepfinFolder(plan.target)
    if (!opened.ok) {
      if (opened.reason !== 'missing' && opened.reason !== 'not-a-keepfin-folder') throw new Error('The chosen folder cannot be used.')
      await createKeepfinFolder(plan.target, {libraryId: ids.libraryId, replicaId: ids.replicaId, now: new Date().toISOString()})
      opened = await openKeepfinFolder(plan.target)
      if (!opened.ok) throw new Error('The Keepfin folder could not be created.')
    }
    const folder = opened.folder
    const queue = await readJson<{settings?: {library?: LibrarySettings}}>(path.join(deviceDir, 'queue.json')).catch(() => undefined)
    const fold = await readJournal(context.runDir)
    const hashes: Record<string, {content: string; size: number}> = {}
    for (const file of plan.files) {
      if (file.kind !== 'media' || !file.itemId) continue
      const hash = fold.files.get(file.id)?.destHash
      if (hash) hashes[file.itemId] = {content: hash.startsWith('sha256:') ? hash : `sha256:${hash}`, size: file.size}
    }
    const importer = new StoreLibrary({deviceDir, newId: randomUUID, now: Date.now, appVersion: deps.appVersion})
    await importer.open(folder, ids.actorId)
    try {
      await importPlan(importer.persistence, plan, {settings: queue?.settings?.library, hashes})
    } finally {
      await importer.close()
    }
    await ensureProfileDir(deviceDir, ids.profileId)
    await writeJsonAtomic(path.join(profileDir(deviceDir, ids.profileId), 'storage.json'), {musicShortcut: true})
    await createFirstProfile(deviceDir, {
      id: ids.profileId,
      name: defaultProfileName(),
      color: pickColor(0),
      folder: folder.root,
      libraryId: folder.libraryId,
      actorId: ids.actorId,
      now: new Date().toISOString(),
    })
    await applyShortcut(folder.root)
  }

  let paused = false
  const service = new StorageService({
    deviceDir,
    userMusicDir: deps.userMusicDir,
    pickFolder: deps.pickFolder,
    openPath: deps.openPath,
    shortcuts: deps.shortcuts,
    newId: randomUUID,
    now: Date.now,
    // A 'ready' state while no library is open would unmount the screens early; the next storageState request restarts the app.
    push: (state) => {
      if (state.phase !== 'ready' || live) deps.push({type: 'storage', state})
    },
    library,
    setup: createSetupPort({
      deviceDir,
      defaultTarget: defaultFolderPath(),
      newId: randomUUID,
      now: Date.now,
      trash: deps.trash,
      pause: async () => {
        if (!paused) {
          paused = true
          await deps.pause()
        }
      },
      resume: async () => {
        if (paused) {
          paused = false
          await deps.resume()
        }
      },
      push: (progress) => deps.push({type: 'setup', progress}),
      saveReportDialog: deps.saveReportDialog,
      commit,
    }),
  })

  /** Closes the library, its copies, devices and the folder lock, once (on quit, or before Change location moves the folder). */
  let closing: Promise<void> | undefined
  const closeOwn = () =>
    (closing ??= (async () => {
      where?.close()
      await devices?.close().catch(() => undefined)
      await copies?.close().catch(() => undefined)
      await library.close().catch(() => undefined)
      await release?.().catch(() => undefined)
    })())

  let relaunching = false
  let moveRunning = false
  let moveStop: AbortController | undefined
  let moveRun: Promise<unknown> | undefined
  const pushMove = (progress: MoveProgress) => deps.push({type: 'storageMove', progress})
  /** Storage › Change location (dev-docs/plans/KEEPFIN-FOLDER-MOVE.md): check and request while live, run or cancel in the move window. */
  const handleMove = async (op: string, args: StorageArgs): Promise<unknown> => {
    if (op === 'storageMoveCheck' || op === 'storageMoveRequest') {
      if (!live || !opened_) throw new Error('Open your Keepfin folder first.')
      // never path.resolve('') (that would be the app's own folder): only a full path the person chose
      if (typeof args.path !== 'string' || !path.isAbsolute(args.path)) throw new Error('Choose a folder.')
      const target = path.resolve(args.path)
      if (op === 'storageMoveRequest') {
        await requestMove(deviceDir, opened_.root, target, args.keepOld === true)
        if (!deps.closeLibrary) {
          relaunching = true
          setTimeout(() => deps.relaunch(), 300)
          return null
        }
        // one restart, at the end: everything that reads the folder closes now and the window shows the move
        await deps.closeLibrary()
        await closeOwn()
        live = false
        move = await readMove(deviceDir)
        moving = true
        deps.push({type: 'storage', state: await service.state()})
        return null
      }
      const sameDrive = path.parse(opened_.root).root.toLowerCase() === path.parse(target).root.toLowerCase()
      const overview = (await service.handle('storageOverview', {})) as StorageOverview
      const free = await fs.statfs(path.parse(target).root).then((s) => Number(s.bavail) * Number(s.bsize)).catch(() => undefined)
      return {problem: await moveProblem(opened_.root, target), bytes: overview.bytes, freeBytes: free, sameDrive} satisfies MoveCheck
    }
    if (!moving || !move || !profile) throw new Error('There is no move to run.')
    if (op === 'storageMoveCancel') {
      // a copy stops after the file it is on; a rename or the switch cannot be stopped
      moveStop?.abort()
      await moveRun
      if (!(await fs.stat(move.from).catch(() => undefined)) || (await loadProfiles(deviceDir))?.profiles.find((p) => p.id === profile.id)?.folder !== move.from)
        throw new Error('The move can no longer be stopped.')
      await forgetMove(deviceDir)
      // the new place was empty when the move was asked for, so everything in it is a partial copy
      await fs.rm(move.to, {recursive: true, force: true})
      relaunching = true
      setTimeout(() => deps.relaunch(), 300)
      return null
    }
    if (moveRunning) return null
    moveRunning = true
    moveStop = new AbortController()
    const running = move
    moveRun = runMove(running, {deviceDir, profileId: profile.id, push: pushMove, trash: deps.trash, signal: moveStop.signal})
      .then(async () => {
        const settings = await readJson<{musicShortcut?: boolean}>(path.join(profileDir(deviceDir, profile.id), 'storage.json')).catch(() => undefined)
        if (settings?.musicShortcut) await applyShortcut(running.to)
      })
      .catch((error) => pushMove({phase: 'failed', done: 0, total: 0, bytesDone: 0, bytesTotal: 0, error: error instanceof Error ? error.message : String(error)}))
      .finally(() => (moveRunning = false))
      .catch(() => undefined)
    return null
  }

  return {
    persistence: live ? library.persistence : undefined,
    live,
    root: live ? opened_?.root : undefined,
    async handle(op, args) {
      if (op === 'storageState') {
        const state = await service.state()
        if (state.phase === 'ready' && !live && !relaunching) {
          relaunching = true
          setTimeout(() => deps.relaunch(), 300)
        }
        return state
      }
      if (op.startsWith('storageMove')) return handleMove(op, args)
      return service.handle(op, args)
    },
    async handleCopies(op, args) {
      if (copies) return copies.handle(op, args)
      // Behind first start and setup there is no library to copy: the screen shows nothing to manage.
      if (op === 'copiesState') return {available: false, places: [], rows: [], review: [], reviewMore: 0, syncing: false} satisfies CopiesState
      throw new Error('Copies are not available until Keepfin has a folder.')
    },
    async handleDevices(op, args) {
      await devicesReady
      if (op === 'devicesOpenLibrary') {
        // switching library: the next start opens the chosen profile (an unfinished join is never offered)
        if (typeof args.profileId !== 'string') throw new Error('Invalid request.')
        await setCurrentProfile(deviceDir, args.profileId)
        setTimeout(() => deps.relaunch(), 300)
        profileList = (await loadProfiles(deviceDir))?.profiles ?? profileList
        return withProfiles(devices ? devices.state() : unavailableState(''))
      }
      const out = await handleDevices(devices, op, args, deviceKeyProblem || (live ? 'Devices are not available in this build.' : 'Devices need the Keepfin folder first.'))
      return 'url' in out ? out : withProfiles(out)
    },
    async handleWhere(op, id) {
      if (!where) return op === 'whereItem' ? {places: []} : emptyWhere
      await library.persistence.ready
      return op === 'whereItem' ? where.item(String(id ?? '')) : where.state()
    },
    async handleCombine(op, args) {
      await devicesReady
      if (!live || !deps.devices) return emptyCombineState('Combining libraries needs the Keepfin folder and devices on the network.')
      return merging.service.handle(op, args)
    },
    jobOrigin: (jobId) => devices?.jobsHost?.originOf(jobId),
    peerSource: (token) => devices?.peerSource(token),
    async playFromItem(itemId, fileName) {
      if (!devices || !opened_ || !live) return undefined
      try {
        return await playFromItem(devices, library.persistence.store, opened_.libraryId, itemId, fileName)
      } catch {
        return undefined
      }
    },
    close: closeOwn,
  }
}
