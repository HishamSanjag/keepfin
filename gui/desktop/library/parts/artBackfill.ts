// Store mode: items without art (downloaded before the move, or added while the library was elsewhere) get it once, one at a
// time: the download's thumbnail, the file's own cover, or a frame of the video. Ids tried are remembered, so a file that has no
// picture is not opened again on every start.
import fs from 'node:fs/promises'
import path from 'node:path'
import {DEVICE_SPACE_ID} from '../../../shared/library.js'
import {readTags} from '../tags.js'
import type {LibraryCore} from './core.js'
import type {Enrichment} from './enrichment.js'
import {keyOf} from './sanitize.js'

export async function backfillArt(core: LibraryCore, enrichment: Enrichment) {
  const triedFile = path.join(path.dirname(core.options.artDir), 'library-art-tried.json')
  const tried = new Set<string>(await fs.readFile(triedFile, 'utf8').then((text) => JSON.parse(text) as string[], () => []))
  // the songs of Music on this PC get their covers when they are shown
  const wanted = [...core.entries().values()].filter((entry) => entry.space !== DEVICE_SPACE_ID && entry.kind !== 'image' && !entry.hasArt && !entry.missing && !tried.has(entry.id)).map((entry) => entry.id)
  if (!wanted.length) return
  const jobs = core.options.jobs?.() ?? []
  const byFile = new Map(jobs.filter((job) => job.file).map((job) => [keyOf(job.file!), job]))
  let unsaved = 0
  const save = () => fs.writeFile(triedFile, JSON.stringify([...tried])).catch(() => undefined)
  for (const id of wanted) {
    if (core.closing()) break
    const entry = core.entries().get(id)
    if (!entry || entry.hasArt) continue
    const job = (entry.jobId ? jobs.find((item) => item.id === entry.jobId) : undefined) ?? byFile.get(keyOf(entry.path))
    if (job && !core.artRequests().has(id)) core.artRequests().set(id, {id: job.id, thumbnail: job.thumbnail, file: entry.path})
    if (entry.kind === 'audio') {
      const picture = (await readTags(entry.path, true).catch(() => undefined))?.picture
      if (picture) entry.hasArt = await core.putArt(id, picture.data, picture.format)
    }
    if (!entry.hasArt) await enrichment.makePoster(entry, core.options.ffmpeg()).catch(() => undefined)
    core.artRequests().delete(id)
    tried.add(id)
    if (entry.hasArt) core.changed(false, [id], false)
    if (++unsaved >= 20) {
      unsaved = 0
      await save()
    }
  }
  await save()
}