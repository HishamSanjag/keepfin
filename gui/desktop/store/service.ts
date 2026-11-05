import fs from 'node:fs/promises'
import path from 'node:path'
import type {FolderChoice, SetupInventory, SetupPlan, SetupReport, StorageArgs, StorageOverview, StorageState} from '../../shared/storage.js'
import {readJson, writeJsonAtomic} from './atomic.js'
import {createKeepfinFolder, defaultFolderPath, inspectFolderChoice, openKeepfinFolder, type ChoiceEnv, type KeepfinFolder} from './keepfinFolder.js'
import {readMove} from './folderMove.js'
import {syncMusicShortcut, type ShortcutShell} from './musicShortcut.js'
import {createFirstProfile, defaultProfileName, ensureProfileDir, loadProfiles, pickColor, profileDir, type Profile} from './profiles.js'

/** The library on top of the sync core (store.ts / writer.ts). Injected so this service stays testable. */
export interface LibraryPort {
  /** A brand-new library in a new folder: the default spaces and routes, as sync operations. */
  create(folder: KeepfinFolder, actorId: string): Promise<void>
  /** Opens an existing library: reads its segments and checkpoints and builds the cache. */
  open(folder: KeepfinFolder, actorId: string): Promise<void>
  overview(): Promise<{spaces: StorageOverview['spaces']; bytes: number; musicVideoBytes?: number; missing: number; checking: boolean; lastCheckedAt?: number; mismatched?: number}>
  checkFiles(): Promise<void>
}

/** The setup window (migration/). Injected for the same reason. */
export interface SetupPort {
  unfinishedRun(): Promise<string | undefined>
  inventory(): Promise<SetupInventory>
  plan(target: string, choices: Record<string, 'move' | 'copy' | 'leave'>): Promise<SetupPlan>
  start(runId: string, acceptFailures: boolean): Promise<void>
  abandon(runId: string): Promise<void>
  report(runId: string): Promise<SetupReport>
  saveReport(runId: string): Promise<boolean>
}

export interface StorageDeps {
  /** %APPDATA%\Keepfin */
  deviceDir: string
  userMusicDir: string
  env?: ChoiceEnv
  pickFolder(defaultPath: string): Promise<string | undefined>
  openPath(target: string): Promise<void>
  shortcuts: ShortcutShell
  newId(): string
  now(): number
  push(state: StorageState): void
  library: LibraryPort
  setup?: SetupPort
}

type DeviceStorageSettings = {musicShortcut: boolean}

// queue.json is not a sign of an old library: the app writes it on its very first start. Only library.json means there is something to bring over.
const legacyFiles = ['library.json']

/** Answers the storage* and setup* requests (gui/shared/storage.ts). */
export class StorageService {
  constructor(private readonly deps: StorageDeps) {}

  async handle(op: string, args: StorageArgs): Promise<unknown> {
    switch (op) {
      case 'storageState':
        return this.state()
      case 'storagePickFolder':
        return (await this.deps.pickFolder(this.defaultPath())) ?? null
      case 'storageInspectFolder':
        return this.inspect(this.pathArg(args))
      case 'storageCreate':
        return this.create(this.pathArg(args), args.musicShortcut !== false)
      case 'storageOpenExisting':
        return this.openExisting(this.pathArg(args))
      case 'storageOverview':
        return this.overview()
      case 'storageOpenFolder':
        await this.deps.openPath((await this.current()).folder)
        return null
      case 'storageCheckFiles':
        await this.deps.library.checkFiles()
        return this.overview()
      case 'storageSetMusicShortcut':
        if (typeof args.enabled !== 'boolean') throw new Error('Invalid request.')
        return this.setMusicShortcut(args.enabled)
      case 'setupInventory':
        return this.setupPort().inventory()
      case 'setupPlan':
        return this.setupPort().plan(this.pathArg(args), this.choicesArg(args))
      case 'setupStart':
        await this.setupPort().start(this.runIdArg(args), args.acceptFailures === true)
        return null
      case 'setupAbandon':
        await this.setupPort().abandon(this.runIdArg(args))
        return this.state()
      case 'setupReport':
        return this.setupPort().report(this.runIdArg(args))
      case 'setupSaveReport':
        return this.setupPort().saveReport(this.runIdArg(args))
      default:
        throw new Error('Unknown storage request.')
    }
  }

  async state(): Promise<StorageState> {
    const defaultPath = this.defaultPath()
    const profiles = await loadProfiles(this.deps.deviceDir)
    if (profiles) {
      const profile = profiles.profiles.find((entry) => entry.id === profiles.current)
      if (profile) {
        const move = await readMove(this.deps.deviceDir)
        const folder = {path: profile.folder, libraryId: profile.libraryId}
        return move?.from === profile.folder ? {phase: 'moveFolder', defaultPath, folder, move} : {phase: 'ready', defaultPath, folder}
      }
    }
    const runId = await this.deps.setup?.unfinishedRun()
    if (runId) return {phase: 'resumeSetup', defaultPath, runId}
    for (const file of legacyFiles) {
      if (await exists(path.join(this.deps.deviceDir, file))) return {phase: 'needsSetup', defaultPath}
    }
    return {phase: 'firstStart', defaultPath}
  }

  private defaultPath() {
    return defaultFolderPath(this.deps.env?.env ?? process.env)
  }

  private async inspect(target: string): Promise<FolderChoice> {
    const found = await inspectFolderChoice(target, this.deps.env)
    const choice: FolderChoice = {
      path: target,
      exists: found.exists,
      empty: found.empty,
      isKeepfinFolder: found.isKeepfinFolder,
      writable: found.writable,
      freeBytes: found.freeBytes ?? undefined,
      insideCloudApp: found.insideCloudApp,
    }
    if (found.isKeepfinFolder) {
      const opened = await openKeepfinFolder(target)
      if (!opened.ok && opened.reason === 'newer-format') choice.newerFormat = true
      if (!opened.ok && opened.reason === 'corrupt') choice.problem = 'This Keepfin folder is damaged: its library details could not be read.'
    }
    if (!found.writable) choice.problem ??= 'Keepfin cannot write to this folder. Pick another one.'
    return choice
  }

  private async create(target: string, musicShortcut: boolean): Promise<StorageState> {
    await this.refuseIfSetUp()
    const choice = await this.inspect(target)
    if (choice.problem) throw new Error(choice.problem)
    if (choice.isKeepfinFolder) throw new Error('This folder already holds a Keepfin folder. Open it instead.')
    const folder = await createKeepfinFolder(target, {libraryId: this.deps.newId(), replicaId: this.deps.newId(), now: new Date(this.deps.now()).toISOString()})
    return this.adopt(folder, musicShortcut, (actorId) => this.deps.library.create(folder, actorId))
  }

  private async openExisting(target: string): Promise<StorageState> {
    await this.refuseIfSetUp()
    const opened = await openKeepfinFolder(target)
    if (!opened.ok) {
      if (opened.reason === 'newer-format') throw new Error('This Keepfin folder was made by a newer version of Keepfin. Update Keepfin to open it.')
      if (opened.reason === 'corrupt') throw new Error('This Keepfin folder is damaged: its library details could not be read.')
      throw new Error('There is no Keepfin folder here.')
    }
    return this.adopt(opened.folder, true, (actorId) => this.deps.library.open(opened.folder, actorId))
  }

  /** The library is written first and profiles.json last: until it exists, nothing has switched over. */
  private async adopt(folder: KeepfinFolder, musicShortcut: boolean, build: (actorId: string) => Promise<void>): Promise<StorageState> {
    const actorId = this.deps.newId()
    const profileId = this.deps.newId()
    await build(actorId)
    await ensureProfileDir(this.deps.deviceDir, profileId)
    await writeJsonAtomic(this.settingsFile(profileId), {musicShortcut} satisfies DeviceStorageSettings)
    await createFirstProfile(this.deps.deviceDir, {
      id: profileId,
      name: defaultProfileName(),
      color: pickColor(0),
      folder: folder.root,
      libraryId: folder.libraryId,
      actorId,
      now: new Date(this.deps.now()).toISOString(),
    })
    await this.applyMusicShortcut(folder.root, musicShortcut).catch(() => undefined)
    const state = await this.state()
    this.deps.push(state)
    return state
  }

  private async refuseIfSetUp() {
    if (await loadProfiles(this.deps.deviceDir)) throw new Error('Keepfin already has a folder.')
  }

  private async current(): Promise<{profile: Profile; folder: string}> {
    const profiles = await loadProfiles(this.deps.deviceDir)
    const profile = profiles?.profiles.find((entry) => entry.id === profiles.current)
    if (!profile) throw new Error('Keepfin has no folder yet.')
    return {profile, folder: profile.folder}
  }

  private settingsFile(profileId: string) {
    return path.join(profileDir(this.deps.deviceDir, profileId), 'storage.json')
  }

  private async deviceSettings(profileId: string): Promise<DeviceStorageSettings> {
    const saved = await readJson<Partial<DeviceStorageSettings>>(this.settingsFile(profileId)).catch(() => undefined)
    return {musicShortcut: saved?.musicShortcut !== false}
  }

  private applyMusicShortcut(root: string, enabled: boolean) {
    return syncMusicShortcut({enabled, keepfinMusicDir: path.join(root, 'Music'), userMusicDir: this.deps.userMusicDir, shell: this.deps.shortcuts})
  }

  private async setMusicShortcut(enabled: boolean): Promise<StorageOverview> {
    const {profile, folder} = await this.current()
    await this.applyMusicShortcut(folder, enabled)
    await writeJsonAtomic(this.settingsFile(profile.id), {musicShortcut: enabled} satisfies DeviceStorageSettings)
    return this.overview()
  }

  private async overview(): Promise<StorageOverview> {
    const {profile, folder} = await this.current()
    const library = await this.deps.library.overview()
    const space = await fs.statfs(folder).catch(() => undefined)
    return {
      path: folder,
      drive: path.parse(folder).root,
      freeBytes: space ? Number(space.bavail) * Number(space.bsize) : undefined,
      totalBytes: space ? Number(space.blocks) * Number(space.bsize) : undefined,
      bytes: library.bytes,
      spaces: library.spaces,
      musicVideoBytes: library.musicVideoBytes,
      health: {missing: library.missing, checking: library.checking, lastCheckedAt: library.lastCheckedAt, mismatched: library.mismatched},
      insideCloudApp: (await inspectFolderChoice(folder, this.deps.env)).insideCloudApp,
      musicShortcut: (await this.deviceSettings(profile.id)).musicShortcut,
    }
  }

  private setupPort(): SetupPort {
    if (!this.deps.setup) throw new Error('The setup window is not available.')
    return this.deps.setup
  }

  private pathArg(args: StorageArgs): string {
    const value = args.path
    if (typeof value !== 'string' || !value || value.length > 1024 || value.includes('\0') || !path.isAbsolute(value)) throw new Error('Invalid folder.')
    return path.resolve(value)
  }

  private runIdArg(args: StorageArgs): string {
    if (typeof args.runId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(args.runId)) throw new Error('Invalid request.')
    return args.runId
  }

  private choicesArg(args: StorageArgs): Record<string, 'move' | 'copy' | 'leave'> {
    const choices = args.choices
    if (!choices || typeof choices !== 'object') throw new Error('Invalid request.')
    const out: Record<string, 'move' | 'copy' | 'leave'> = {}
    for (const [key, value] of Object.entries(choices)) {
      if (key.length > 200 || (value !== 'move' && value !== 'copy' && value !== 'leave')) throw new Error('Invalid request.')
      out[key] = value
    }
    return out
  }
}

async function exists(target: string) {
  return fs.stat(target).then(() => true, () => false)
}
