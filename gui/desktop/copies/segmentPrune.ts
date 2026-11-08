// Segment files a larger file of the same actor already holds (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md R5, R6). On a copy or a
// cloud mirror they are removed for good, never moved to versions or trash, once the covering file on that replica has been read
// back and holds the same ops line for line.
import fs from 'node:fs/promises'
import path from 'node:path'
import {decodeSegment, subsumedSegments, type CompactionFile} from '../../shared/core/sync.js'

const SEGMENT = /^(\d{16})-(\d{16})\.seg$/

export type OpsFile = {actor: string; name: string; first: number; last: number; file: string}

export async function listOps(opsDir: string): Promise<OpsFile[]> {
  const out: OpsFile[] = []
  for (const actor of await fs.readdir(opsDir, {withFileTypes: true}).catch(() => [])) {
    if (!actor.isDirectory()) continue
    for (const name of await fs.readdir(path.join(opsDir, actor.name)).catch(() => [] as string[])) {
      const m = SEGMENT.exec(name)
      if (m) out.push({actor: actor.name, name, first: Number(m[1]), last: Number(m[2]), file: path.join(opsDir, actor.name, name)})
    }
  }
  return out.sort((a, b) => (a.actor < b.actor ? -1 : a.actor > b.actor ? 1 : a.first - b.first || a.last - b.last))
}

const asFile = (f: OpsFile): CompactionFile => ({actor: f.actor, first: f.first, last: f.last, bytes: 0, signer: null})

/** The files of [files] that a larger file of the same actor in [files] covers by name (not yet checked op for op). */
export function coveredByName(files: readonly OpsFile[]): {covered: OpsFile; by: OpsFile}[] {
  return subsumedSegments(files.map(asFile)).map((s) => ({covered: files[s.index]!, by: files[s.by]!}))
}

async function decodeFile(file: string, libraryId: string) {
  try {
    return decodeSegment(await fs.readFile(file), libraryId)
  } catch {
    return null
  }
}

/**
 * Removes, from the ops folder [opsDir] of a replica, the segment files that a larger file there covers and that [keep] does not
 * protect (a copy keeps what the main folder still lists). Returns how many were removed. Each removal is preceded by reading both
 * files and comparing the covered ops line for line; an actor with a file that does not decode is left alone.
 */
export async function pruneCoveredSegments(opsDir: string, libraryId: string, keep: (file: OpsFile) => boolean = () => false): Promise<number> {
  const files = await listOps(opsDir)
  if (files.length < 2) return 0
  const broken = new Set<string>()
  const found = coveredByName(files).filter((entry) => !keep(entry.covered))
  if (!found.length) return 0
  const covers = new Map<string, Awaited<ReturnType<typeof decodeFile>>>()
  let removed = 0
  for (const {covered, by} of found) {
    if (broken.has(covered.actor)) continue
    if (!covers.has(by.file)) covers.set(by.file, await decodeFile(by.file, libraryId))
    const large = covers.get(by.file)
    const small = await decodeFile(covered.file, libraryId)
    if (!large?.ok || !small?.ok || large.actor !== small.actor || small.first < large.first || small.last > large.last) {
      broken.add(covered.actor)
      continue
    }
    let same = true
    for (let seq = small.first; seq <= small.last && same; seq++) same = small.ops[seq - small.first] === large.ops[seq - large.first]
    if (!same) continue
    if (await fs.rm(covered.file, {force: true}).then(() => true, () => false)) removed++
  }
  return removed
}
