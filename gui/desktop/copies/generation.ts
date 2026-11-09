// Generations (spec 7). The copy's `folder.json` holds `generation`, incremented at the start of every run that changes the
// copy; the relationship records the generation it last committed. Equal proceeds; higher means another computer synced in
// between (additive only); lower means a restored or cloned drive (every destructive action suspended).
import fs from 'node:fs/promises'
import path from 'node:path'
import {readJson, writeJsonAtomic} from '../store/atomic.js'
import type {GenerationState} from '../../shared/core/copies.js'

const folderJson = (copyRoot: string) => path.join(copyRoot, '.keepfin', 'folder.json')

/** The copy's generation, or null when `folder.json` is missing or unreadable. A folder written before generations existed has 0. */
export async function readGeneration(copyRoot: string): Promise<number | null> {
  try {
    const json = await readJson<{generation?: unknown}>(folderJson(copyRoot))
    if (!json) return null
    return typeof json.generation === 'number' ? json.generation : 0
  } catch {
    return null
  }
}

/** The lock token (run id) written with the last generation bump. */
export async function readLockToken(copyRoot: string): Promise<string | null> {
  const json = await readJson<{lockToken?: unknown}>(folderJson(copyRoot)).catch(() => undefined)
  return typeof json?.lockToken === 'string' ? json.lockToken : null
}

/** Increments the generation (together with the run's lock token) and returns the new value. */
export async function bumpGeneration(copyRoot: string, lockToken: string): Promise<number> {
  const json = await readJson<Record<string, unknown>>(folderJson(copyRoot))
  if (!json) throw new Error('The copy has no folder.json.')
  const next = (typeof json.generation === 'number' ? json.generation : 0) + 1
  await writeJsonAtomic(folderJson(copyRoot), {...json, generation: next, lockToken})
  return next
}

/** Rewrites the generation to a given value (used to roll a run that changed nothing back). */
export async function setGeneration(copyRoot: string, generation: number): Promise<void> {
  const json = await readJson<Record<string, unknown>>(folderJson(copyRoot))
  if (!json) throw new Error('The copy has no folder.json.')
  await writeJsonAtomic(folderJson(copyRoot), {...json, generation})
}

export interface Relationship {
  /** The generation this relationship last committed at, or null for a relationship that never ran. */
  generation: number | null
  /** The user confirmed the first preview (the relationship may remove and replace). */
  previewConfirmed: boolean
  /** The name rule the baseline file is sorted by (absent: written before the rule was recorded; the next run sorts it again). */
  rules?: string
  /** When the last full audit (every file hashed) finished, ms. The first completed run counts: it hashed everything new. */
  auditedAt?: number
}

export const relationshipFile = (stateDir: string) => path.join(stateDir, 'relationship.json')

export async function readRelationship(stateDir: string): Promise<Relationship> {
  const json = await readJson<Partial<Relationship>>(relationshipFile(stateDir)).catch(() => undefined)
  return {
    generation: typeof json?.generation === 'number' ? json.generation : null,
    previewConfirmed: json?.previewConfirmed === true,
    ...(typeof json?.rules === 'string' ? {rules: json.rules} : {}),
    ...(typeof json?.auditedAt === 'number' ? {auditedAt: json.auditedAt} : {}),
  }
}

export async function writeRelationship(stateDir: string, rel: Relationship): Promise<void> {
  await fs.mkdir(stateDir, {recursive: true})
  await writeJsonAtomic(relationshipFile(stateDir), rel)
}

/**
 * Compares the copy's generation with what the relationship last committed. A relationship that never ran has nothing to be
 * wrong about (its first plan is additive only anyway). An unreadable copy generation is unknown.
 */
export function compareGeneration(copy: number | null, relationship: number | null): GenerationState {
  if (copy === null) return 'unknown'
  if (relationship === null) return 'equal'
  if (copy === relationship) return 'equal'
  return copy > relationship ? 'higher' : 'lower'
}
