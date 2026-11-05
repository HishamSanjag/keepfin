// Section 5.2: the plan file. Destinations are resolved here, once, so a resumed run never chooses differently.
import path from 'node:path'
import {writeJsonAtomic, readJson} from '../atomic.js'
import {builtinDestination} from './inventory.js'
import {exists, fromSlash, pathKey} from './io.js'
import type {Choice, InvFile, Inventory, Plan, PlanFile, PlanSpace} from './types.js'

export type PlanOptions = {
  /** The Keepfin folder (absolute). */
  target: string
  /** Per source (space id): move, copy or leave. A source without a choice is moved (the default). */
  choices: Record<string, Choice>
  newId: () => string
  runId: string
  now: () => number
  /** Compare names case-insensitively (default: on Windows). */
  caseInsensitive?: boolean
  /** Unindexed files the user chose to ignore (still travel, get no library entry). */
  ignore?: string[]
  /** Existing-file check, replaceable in tests. */
  exists?: (file: string) => Promise<boolean>
}

const reserved = new Set(['.keepfin', 'con', 'prn', 'aux', 'nul'])

/** A folder name that is legal on Windows. */
export function safeFolderName(name: string): string {
  // eslint-disable-next-line no-control-regex
  let clean = name.replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_').replace(/[. ]+$/g, '').replace(/^ +/, '')
  if (!clean || /^\.+$/.test(clean) || reserved.has(clean.toLowerCase())) clean = `${clean || ''}${clean ? ' ' : ''}Space`.trim()
  return clean.slice(0, 80)
}

const posixDir = (rel: string) => (rel.includes('/') ? rel.slice(0, rel.lastIndexOf('/')) : '')
const posixBase = (rel: string) => rel.slice(rel.lastIndexOf('/') + 1)

export async function buildPlan(inventory: Inventory, options: PlanOptions): Promise<Plan> {
  const caseInsensitive = options.caseInsensitive ?? process.platform === 'win32'
  const exist = options.exists ?? exists
  const key = (rel: string) => (caseInsensitive ? rel.toLowerCase() : rel)
  const taken = new Set<string>()
  // A candidate that is exactly one of the group's own files is free: that file is already in place.
  const free = async (rel: string, own: string[] = []) => {
    if (taken.has(key(rel))) return false
    const abs = path.join(options.target, fromSlash(rel))
    if (own.some((file) => pathKey(file) === pathKey(abs))) return true
    return !(await exist(abs))
  }
  const reserve = (rel: string) => taken.add(key(rel))

  // Spaces and their destination folders.
  const folderSpaces = inventory.spaces.filter((space) => space.type === 'folder' && space.folder)
  const planSpaces: PlanSpace[] = []
  const usedFolders = new Set<string>()
  const ordered = [...folderSpaces].sort((a, b) => Number(!!builtinDestination[b.id]) - Number(!!builtinDestination[a.id]))
  for (const space of ordered) {
    const choice = options.choices[space.id] ?? 'move'
    let destRel: string | null = null
    if (choice !== 'leave') {
      const base = builtinDestination[space.id] ?? safeFolderName(space.name)
      let candidate = base
      for (let n = 2; usedFolders.has(candidate.toLowerCase()); n++) candidate = `${base} (${n})`
      usedFolders.add(candidate.toLowerCase())
      destRel = candidate
    }
    planSpaces.push({id: space.id, name: space.name, folder: space.folder, choice, destRel})
  }
  const spaceById = new Map(planSpaces.map((space) => [space.id, space]))

  const files: PlanFile[] = []
  const left: Plan['left'] = []
  const paths: Record<string, string> = {}
  const mapping: Plan['mapping'] = {items: {}, collections: {}, unindexed: {}, paths, linkedSpaces: {}}
  const ignored = new Set(options.ignore ?? [])
  let counter = 0
  const nextId = () => `f${String(++counter).padStart(6, '0')}`

  for (const space of planSpaces) if (space.choice === 'leave') mapping.linkedSpaces[space.id] = space.folder

  // Identity mapping, in a stable order.
  for (const item of inventory.library.items) mapping.items[item.id] = options.newId()
  for (const collection of inventory.library.collections) mapping.collections[collection.id] = options.newId()
  for (const file of inventory.media)
    if (file.indexed === false && !ignored.has(file.path)) mapping.unindexed[file.path] = options.newId()

  const mediaIds = new Map<string, string>()
  const sidecarsOf = new Map<string, InvFile[]>()
  for (const sidecar of inventory.sidecars) {
    const list = sidecarsOf.get(sidecar.owner ?? '') ?? []
    list.push(sidecar)
    sidecarsOf.set(sidecar.owner ?? '', list)
  }

  const push = (file: InvFile, rel: string, action: 'move' | 'copy', owner?: string): PlanFile => {
    const planFile: PlanFile = {
      id: nextId(), kind: file.kind, space: file.space, source: file.path, dest: path.join(options.target, fromSlash(rel)), rel,
      action, size: file.size, mtimeMs: file.mtimeMs, itemId: file.itemId, owner,
    }
    files.push(planFile)
    paths[file.path] = rel
    return planFile
  }

  for (const media of inventory.media) {
    const space = spaceById.get(media.space)
    if (!space || space.destRel === null) {
      left.push({path: media.path, kind: 'media', space: media.space, itemId: media.itemId})
      for (const sidecar of sidecarsOf.get(media.path) ?? []) left.push({path: sidecar.path, kind: 'sidecar', space: sidecar.space, itemId: undefined})
      continue
    }
    const action = space.choice === 'copy' ? 'copy' : 'move'
    const directory = posixDir(media.rel)
    const fileName = posixBase(media.rel)
    const extension = path.posix.extname(fileName)
    const stem = fileName.slice(0, fileName.length - extension.length)
    const prefix = (directory ? `${space.destRel}/${directory}` : space.destRel) + '/'
    const linked = (sidecarsOf.get(media.path) ?? []).filter((item) => !item.shared)
    const ownerStem = stem
    for (let n = 1; ; n++) {
      const newStem = n === 1 ? stem : `${stem} (${n})`
      const names = [prefix + newStem + extension, ...linked.map((item) => prefix + newStem + posixBase(item.rel).slice(ownerStem.length))]
      let allFree = true
      const own = [media.path, ...linked.map((item) => item.path)]
      for (const name of names) if (!(await free(name, own))) allFree = false
      if (!allFree) continue
      names.forEach(reserve)
      const mediaFile = push(media, names[0], action)
      mediaIds.set(media.path, mediaFile.id)
      linked.forEach((item, index) => push(item, names[index + 1], action, mediaFile.id))
      break
    }
  }

  // Covers that serve a whole folder are placed on their own.
  for (const sidecar of inventory.sidecars.filter((item) => item.shared)) {
    const space = spaceById.get(sidecar.space)
    if (!space || space.destRel === null) {
      left.push({path: sidecar.path, kind: 'sidecar', space: sidecar.space})
      continue
    }
    const directory = posixDir(sidecar.rel)
    const fileName = posixBase(sidecar.rel)
    const extension = path.posix.extname(fileName)
    const stem = fileName.slice(0, fileName.length - extension.length)
    const prefix = (directory ? `${space.destRel}/${directory}` : space.destRel) + '/'
    for (let n = 1; ; n++) {
      const name = prefix + (n === 1 ? stem : `${stem} (${n})`) + extension
      if (!(await free(name, [sidecar.path]))) continue
      reserve(name)
      push(sidecar, name, space.choice === 'copy' ? 'copy' : 'move', mediaIds.get(sidecar.owner ?? ''))
      break
    }
  }

  // Saved art always travels as a copy, so the old library stays whole and restorable.
  for (const art of inventory.art) {
    const newId = art.itemId ? mapping.items[art.itemId] : undefined
    if (!newId) continue
    const extension = path.posix.extname(art.rel)
    let rel = `.keepfin/art/${newId}${extension}`
    for (let n = 2; !(await free(rel)); n++) rel = `.keepfin/art/${newId} (${n})${extension}`
    reserve(rel)
    push(art, rel, 'copy')
  }

  return {
    version: 1,
    runId: options.runId,
    createdAt: options.now(),
    target: options.target,
    choices: Object.fromEntries(planSpaces.map((space) => [space.id, space.choice])),
    spaces: planSpaces,
    files,
    left,
    mapping,
    ignored: [...ignored],
    inventory,
  }
}

export const planFile = (runDir: string) => path.join(runDir, 'plan.json')

/** Writes plan.json atomically and flushed. The runner calls this before touching any file. */
export async function writePlan(runDir: string, plan: Plan): Promise<void> {
  await writeJsonAtomic(planFile(runDir), plan)
}

export async function readPlan(runDir: string): Promise<Plan | undefined> {
  return readJson<Plan>(planFile(runDir))
}
