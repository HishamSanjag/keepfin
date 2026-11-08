// The coverage manifest of a run (spec 6): `places/<placeId>/manifest-<n>.jsonl` on the copy, plus the same file under the
// main folder's `.keepfin/places/`. A header line records the holds key, the options key and the generation; one line per
// verified (itemId, revisionOp, contentId, path). The `place.set verified` register points at it with its SHA-256.
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {readBaseline} from './baseline.js'
import {lineReader} from './lines.js'

export interface ManifestHeader {
  kind: 'manifest'
  placeId: string
  n: number
  at: number
  generation: number
  holdsKey: string
  optionsKey: string
  mainReplica: string | null
  copyReplica: string | null
}

export interface ManifestLine {
  itemId: string
  revisionOp: string | null
  contentId: string
  path: string
}

export interface ManifestResult {
  n: number
  sha256: string
  files: number
  copyFile: string
  mainFile: string
}

const placeDir = (root: string, placeId: string) => path.join(root, '.keepfin', 'places', placeId)

async function nextNumber(dir: string): Promise<number> {
  let max = 0
  try {
    for (const name of await fs.readdir(dir)) {
      const m = /^manifest-(\d+)\.jsonl$/.exec(name)
      if (m) max = Math.max(max, Number(m[1]))
    }
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
  }
  return max + 1
}

/** How many manifests a place keeps on each side. */
export const MANIFESTS_KEPT = 3

/** Deletes all but the newest [keep] manifests of a place folder (a hard delete: a manifest is a record, not user data). */
export async function pruneManifests(dir: string, keep = MANIFESTS_KEPT): Promise<number> {
  let names: string[]
  try {
    names = await fs.readdir(dir)
  } catch {
    return 0
  }
  const numbered = names.flatMap((name) => {
    const m = /^manifest-(\d+)\.jsonl$/.exec(name)
    return m ? [{name, n: Number(m[1])}] : []
  })
  numbered.sort((a, b) => b.n - a.n)
  let removed = 0
  for (const old of numbered.slice(keep)) {
    if (await fs.rm(path.join(dir, old.name), {force: true}).then(() => true, () => false)) removed++
  }
  return removed
}

/**
 * Writes the manifest of a completed run from the relationship's baseline (every entry in it was verified at the generation
 * it records) to the copy and to the main folder. The caller records the result in the `place.set` register.
 */
export async function writeManifest(options: {
  stateDir: string
  mainRoot: string
  copyRoot: string
  placeId: string
  generation: number
  holdsKey: string
  optionsKey: string
  mainReplica?: string | null
  copyReplica?: string | null
  now: number
}): Promise<ManifestResult> {
  const copyDir = placeDir(options.copyRoot, options.placeId)
  const mainDir = placeDir(options.mainRoot, options.placeId)
  await fs.mkdir(copyDir, {recursive: true})
  await fs.mkdir(mainDir, {recursive: true})
  const n = Math.max(await nextNumber(copyDir), await nextNumber(mainDir))
  const name = `manifest-${n}.jsonl`
  const copyFile = path.join(copyDir, name)
  const mainFile = path.join(mainDir, name)
  const header: ManifestHeader = {
    kind: 'manifest',
    placeId: options.placeId,
    n,
    at: options.now,
    generation: options.generation,
    holdsKey: options.holdsKey,
    optionsKey: options.optionsKey,
    mainReplica: options.mainReplica ?? null,
    copyReplica: options.copyReplica ?? null,
  }
  const hash = crypto.createHash('sha256')
  const tmp = `${copyFile}.${process.pid}.tmp`
  const handle = await fs.open(tmp, 'w')
  let files = 0
  try {
    let buffer: string[] = []
    let size = 0
    const flush = async () => {
      if (buffer.length === 0) return
      const text = buffer.join('')
      buffer = []
      size = 0
      hash.update(text)
      await handle.write(text)
    }
    const add = async (obj: object) => {
      const line = JSON.stringify(obj) + '\n'
      buffer.push(line)
      size += line.length
      if (size > 256 * 1024) await flush()
    }
    await add(header)
    for (const e of readBaseline(options.stateDir)) {
      await add({itemId: e.itemId, revisionOp: e.revisionOp, contentId: e.contentId, path: e.path} satisfies ManifestLine)
      files++
    }
    await flush()
    await handle.sync()
  } finally {
    await handle.close()
  }
  await fs.rename(tmp, copyFile)
  await fs.copyFile(copyFile, `${mainFile}`)
  // Only the newest few are ever read (the latest one; coverage reads its header), so older ones are not kept: without this a
  // library that runs a copy every day gains a manifest of the whole copy per run, for ever (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md R4).
  await pruneManifests(copyDir)
  await pruneManifests(mainDir)
  return {n, sha256: 'sha256:' + hash.digest('hex'), files, copyFile, mainFile}
}

/** The latest manifest of a place on [root]: its header and a streaming reader of the lines after it. */
export async function latestManifest(root: string, placeId: string): Promise<{header: ManifestHeader; lines: () => Generator<ManifestLine>; file: string} | null> {
  const dir = placeDir(root, placeId)
  const n = (await nextNumber(dir)) - 1
  if (n < 1) return null
  const file = path.join(dir, `manifest-${n}.jsonl`)
  const first = lineReader(file)
  const head = first()
  first.close()
  if (!head) return null
  return {
    header: JSON.parse(head) as ManifestHeader,
    file,
    *lines() {
      const read = lineReader(file)
      try {
        read() // header
        for (let line = read(); line !== null; line = read()) yield JSON.parse(line) as ManifestLine
      } finally {
        read.close()
      }
    },
  }
}
