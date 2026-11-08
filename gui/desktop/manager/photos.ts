// Photo-post jobs: the label a job shows and the "already saved" check. The rules are the shared core's (shared/core/gallery.ts).
import fs from 'node:fs/promises'
import type {FormatRequest, MediaInfo} from '../../shared/contracts.js'
import type {ExistingMatch} from '../../shared/library.js'
import {alreadySavedIndex, photoCountLabel, savedPhotosLabel, type EarlierSave} from '../../shared/core/gallery.js'
import type {StoredJob} from '../migrate.js'

type GalleryFormat = Extract<FormatRequest, {type: 'gallery'}>

/** The label of a photo job: "6 photos" for the photos chosen from the inspected post ("items" when a video of the post is among them). */
export function photoLabel(media: MediaInfo, format: GalleryFormat): string {
  const gallery = media.gallery
  if (!gallery) return 'Photos'
  const chosen = format.items
  const videos = gallery.items.some((item) => !item.photo && (!chosen || chosen.includes(item.position)))
  return photoCountLabel(chosen ? chosen.length : gallery.count, videos)
}

/** How many files the job asked for: the chosen photos, else every photo of the post. */
export function expectedFiles(job: StoredJob): number | undefined {
  return job.format.type === 'gallery' && job.format.items ? job.format.items.length : job.expectedCount
}

/** The distinct files a finished photo job reports. */
export function filesOf(job: StoredJob): string[] {
  return [...new Set([job.file, ...(job.files ?? [])].filter((file): file is string => !!file))]
}

/** The job's label once `saved` of its files are in storage ("4 of 6 photos" while some are missing). */
export function relabel(job: StoredJob, saved: number) {
  const expected = expectedFiles(job)
  if (expected) job.label = savedPhotosLabel(saved, expected, job.label)
}

/**
 * The earlier finished photo job that already holds the photos `job` asks for, when every one of its files is still on disk. A job that
 * lost a file, or could not save one, never counts.
 */
export async function earlierPhotos(job: StoredJob, others: readonly StoredJob[]): Promise<{job: StoredJob; match: ExistingMatch} | undefined> {
  if (job.format.type !== 'gallery' || !job.expectedCount) return
  const candidates: {job: StoredJob; save: EarlierSave}[] = []
  for (const other of others) {
    if (other === job || other.status !== 'completed' || other.format.type !== 'gallery' || other.existing || !other.expectedCount) continue
    const files = filesOf(other)
    const expected = expectedFiles(other) ?? 0
    let complete = !other.saveError && expected > 0 && files.length >= expected
    for (const file of complete ? files : []) if (!(await fs.stat(file).catch(() => undefined))?.isFile()) complete = false
    candidates.push({job: other, save: {url: other.url, count: other.expectedCount, items: other.format.items, complete}})
  }
  const hit = candidates[alreadySavedIndex(job.url, job.expectedCount, job.format.items, candidates.map((candidate) => candidate.save))]
  const file = hit && filesOf(hit.job)[0]
  return hit && file ? {job: hit.job, match: {path: file, space: hit.job.space ?? job.space ?? '', by: 'source'}} : undefined
}
