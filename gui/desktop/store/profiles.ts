import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {readJson, writeJsonAtomic} from './atomic.js'

export interface ProfileLock {
  kind: 'pin'
  salt: string
  hash: string
}

export interface Profile {
  id: string
  name: string
  color: string
  folder: string
  libraryId: string
  actorId: string
  createdAt: string
  lock?: ProfileLock
}

export interface ProfilesFile {
  format: 1
  current: string
  profiles: Profile[]
}

export class ProfilesError extends Error {
  readonly code: 'corrupt' | 'exists' | 'unknown-profile'
  constructor(code: ProfilesError['code'], message: string) {
    super(message)
    this.name = 'ProfilesError'
    this.code = code
  }
}

export const PROFILE_COLORS: readonly string[] = ['#2f6df6', '#18a67a', '#e0752b', '#c2415d', '#8a5cf5', '#d4a017', '#1e9bb8', '#6b7280']

export function pickColor(index: number): string {
  const n = PROFILE_COLORS.length
  return PROFILE_COLORS[((Math.trunc(index) % n) + n) % n]
}

export function defaultProfileName(env: {username?: () => string} = {}): string {
  let name = ''
  try {
    name = (env.username ?? (() => os.userInfo().username))()
  } catch {
    name = ''
  }
  name = (name ?? '').trim()
  return name || 'Me'
}

const profilesFile = (deviceDir: string) => path.join(deviceDir, 'profiles.json')

export function profileDir(deviceDir: string, id: string): string {
  if (!/^[A-Za-z0-9_-]+$/.test(id)) throw new ProfilesError('unknown-profile', `Invalid profile id: ${id}`)
  return path.join(deviceDir, 'profiles', id)
}

export async function ensureProfileDir(deviceDir: string, id: string): Promise<string> {
  const dir = profileDir(deviceDir, id)
  await fs.mkdir(path.join(dir, 'outbox'), {recursive: true})
  await fs.mkdir(path.join(dir, 'cache'), {recursive: true})
  return dir
}

const isString = (v: unknown): v is string => typeof v === 'string' && v.length > 0

function validate(raw: unknown, file: string): ProfilesFile {
  const bad = (why: string): never => {
    throw new ProfilesError('corrupt', `The profiles file ${file} is not valid (${why}).`)
  }
  if (!raw || typeof raw !== 'object') return bad('not an object')
  const o = raw as Record<string, unknown>
  if (o.format !== 1) return bad('unsupported format')
  if (!isString(o.current)) return bad('current')
  if (!Array.isArray(o.profiles) || o.profiles.length === 0) return bad('profiles')
  const seen = new Set<string>()
  const profiles = o.profiles.map((p): Profile => {
    if (!p || typeof p !== 'object') return bad('profile entry')
    const r = p as Record<string, unknown>
    for (const key of ['id', 'name', 'color', 'folder', 'libraryId', 'actorId', 'createdAt'] as const) {
      if (!isString(r[key])) return bad(`profile ${key}`)
    }
    if (seen.has(r.id as string)) return bad('duplicate id')
    seen.add(r.id as string)
    let lock: ProfileLock | undefined
    if (r.lock !== undefined) {
      const l = r.lock as Record<string, unknown> | null
      if (!l || l.kind !== 'pin' || !isString(l.salt) || !isString(l.hash)) return bad('lock')
      lock = {kind: 'pin', salt: l.salt as string, hash: l.hash as string}
    }
    const out: Profile = {
      id: r.id as string,
      name: r.name as string,
      color: r.color as string,
      folder: r.folder as string,
      libraryId: r.libraryId as string,
      actorId: r.actorId as string,
      createdAt: r.createdAt as string,
    }
    if (lock) out.lock = lock
    return out
  })
  if (!seen.has(o.current as string)) return bad('current profile missing')
  return {format: 1, current: o.current as string, profiles}
}

/** Undefined when missing; throws ProfilesError('corrupt') or CorruptJsonError on damaged data. */
export async function loadProfiles(deviceDir: string): Promise<ProfilesFile | undefined> {
  const file = profilesFile(deviceDir)
  const raw = await readJson<unknown>(file)
  if (raw === undefined) return undefined
  return validate(raw, file)
}

export interface FirstProfileInput {
  id: string
  name: string
  color: string
  folder: string
  libraryId: string
  actorId: string
  now: string
}

export async function createFirstProfile(deviceDir: string, input: FirstProfileInput): Promise<ProfilesFile> {
  const file = profilesFile(deviceDir)
  const existing = await fs.stat(file).then(() => true, () => false)
  if (existing) throw new ProfilesError('exists', 'Profiles already exist on this device.')
  const data: ProfilesFile = {
    format: 1,
    current: input.id,
    profiles: [{id: input.id, name: input.name, color: input.color, folder: input.folder, libraryId: input.libraryId, actorId: input.actorId, createdAt: input.now}],
  }
  validate(data, file)
  await writeJsonAtomic(file, data)
  return data
}

/** Adds a profile (a library taken in from another device). It does not become the current one: the person opens it when they choose. */
export async function addProfile(deviceDir: string, input: FirstProfileInput): Promise<ProfilesFile> {
  const data = await loadProfiles(deviceDir)
  if (!data) throw new ProfilesError('unknown-profile', 'No profiles exist on this device.')
  if (data.profiles.some((p) => p.libraryId === input.libraryId)) throw new ProfilesError('exists', 'This device already holds that library.')
  data.profiles.push({id: input.id, name: input.name, color: input.color, folder: input.folder, libraryId: input.libraryId, actorId: input.actorId, createdAt: input.now})
  validate(data, profilesFile(deviceDir))
  await writeJsonAtomic(profilesFile(deviceDir), data)
  return data
}

/** Makes [profileId] the library the app opens next. */
export async function setCurrentProfile(deviceDir: string, profileId: string): Promise<ProfilesFile> {
  const data = await loadProfiles(deviceDir)
  if (!data) throw new ProfilesError('unknown-profile', 'No profiles exist on this device.')
  if (!data.profiles.some((p) => p.id === profileId)) throw new ProfilesError('unknown-profile', `Unknown profile: ${profileId}`)
  data.current = profileId
  await writeJsonAtomic(profilesFile(deviceDir), data)
  return data
}

/** Points a profile at another library (a combine switched the folder to it, or separating switched it back). */
export async function setProfileLibrary(deviceDir: string, profileId: string, library: {libraryId: string; actorId: string}): Promise<ProfilesFile> {
  const data = await loadProfiles(deviceDir)
  if (!data) throw new ProfilesError('unknown-profile', 'No profiles exist on this device.')
  const profile = data.profiles.find((p) => p.id === profileId)
  if (!profile) throw new ProfilesError('unknown-profile', `Unknown profile: ${profileId}`)
  if (!isString(library.libraryId) || !isString(library.actorId)) throw new ProfilesError('corrupt', 'The library and actor ids must not be empty.')
  profile.libraryId = library.libraryId
  profile.actorId = library.actorId
  await writeJsonAtomic(profilesFile(deviceDir), data)
  return data
}

/** Points a profile at its Keepfin folder's new place (Storage › Change location, after the move was verified). */
export async function setProfileFolder(deviceDir: string, profileId: string, folder: string): Promise<ProfilesFile> {
  const data = await loadProfiles(deviceDir)
  if (!data) throw new ProfilesError('unknown-profile', 'No profiles exist on this device.')
  const profile = data.profiles.find((p) => p.id === profileId)
  if (!profile) throw new ProfilesError('unknown-profile', `Unknown profile: ${profileId}`)
  if (!isString(folder)) throw new ProfilesError('corrupt', 'The folder must not be empty.')
  profile.folder = folder
  await writeJsonAtomic(profilesFile(deviceDir), data)
  return data
}

/** Replaces a profile's actor id (restored or cloned install detected). */
export async function rotateActor(deviceDir: string, profileId: string, newActorId: string): Promise<ProfilesFile> {
  const data = await loadProfiles(deviceDir)
  if (!data) throw new ProfilesError('unknown-profile', 'No profiles exist on this device.')
  const profile = data.profiles.find((p) => p.id === profileId)
  if (!profile) throw new ProfilesError('unknown-profile', `Unknown profile: ${profileId}`)
  if (!isString(newActorId)) throw new ProfilesError('corrupt', 'The new actor id is empty.')
  profile.actorId = newActorId
  await writeJsonAtomic(profilesFile(deviceDir), data)
  return data
}
