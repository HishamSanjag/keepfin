// Derived exports of a copy on a folder or drive (dev-docs/plans/KEEPFIN-COPIES-SPEC.md section 8): playlist files, a flat layout and MP3 versions.
// They are made after a run from what the copy verifiably holds, and they are not library content:
//   - `.keepfin/exports.json` on the copy records every derived file (kind, source, size, time). It is written BEFORE the files, so a crash
//     leaves a record that is a superset of the files;
//   - the runner leaves recorded paths out of the copy's inventory (`derivedFilter`), so they are never imported back and never planned;
//   - a derived file is rebuilt when its source changes (a content id; playlists: the text), moved when the same source appears under a
//     new name, and removed when it is no longer derived, but only while it is still the file Keepfin wrote (size and time unchanged).
//   - a name that already holds something Keepfin did not write is never overwritten: the export is skipped and reported.
// MP3 versions are made only for audio that is not MP3 already, by the download engine's audio conversion, and are cached by content id:
// a song whose content id is unchanged is never encoded again, wherever it moves.
import {spawn} from 'node:child_process'
import crypto from 'node:crypto'
import type {DatabaseSync} from 'node:sqlite'
import fs from 'node:fs/promises'
import path from 'node:path'
import {readContainers, playlistItems, type PlaceConfig} from '../sqlstate/desired.js'
import {readJson, writeJsonAtomic} from '../store/atomic.js'
import {safeComponent} from './names.js'
import {exists, splitExt, withCounter} from './util.js'

export const EXPORTS_DIR = 'Keepfin Exports'
export const PLAYLISTS_DIR = 'Playlists'
const AUDIO_NOT_MP3 = new Set(['m4a', 'aac', 'flac', 'ogg', 'oga', 'opus', 'wav', 'wma', 'aiff', 'aif', 'mka'])
const AUDIO = new Set([...AUDIO_NOT_MP3, 'mp3'])

export type ExportKind = 'playlist' | 'flat' | 'mp3'

export interface ExportRecord {
  kind: ExportKind
  /** What the file was made from: a content id (flat, mp3) or the hash of the text (playlist). */
  source: string
  size: number
  mtimeMs: number
}

interface ExportFile {
  version: 1
  files: Record<string, ExportRecord>
}

export const exportsFile = (copyRoot: string) => path.join(copyRoot, '.keepfin', 'exports.json')

async function readRecord(copyRoot: string): Promise<ExportFile> {
  const json = await readJson<ExportFile>(exportsFile(copyRoot)).catch(() => undefined)
  return json?.version === 1 && json.files ? json : {version: 1, files: {}}
}

/** The paths the runner must leave out of the copy's inventory (lowercase: the copy may be case-insensitive). */
export async function derivedFilter(copyRoot: string): Promise<(libPath: string) => boolean> {
  const rec = await readRecord(copyRoot)
  const set = new Set(Object.keys(rec.files).map((p) => p.toLowerCase()))
  return set.size === 0 ? () => true : (p) => !set.has(p.toLowerCase())
}

export interface ExportsReport {
  written: number
  converted: number
  removed: number
  moved: number
  /** Exports left undone, with the reason (a name that holds something else, a failed conversion). */
  skipped: {path: string; reason: string}[]
}

interface Entry {
  path: string
  itemId: string
  contentId: string
  bytes: number
}

const extOf = (p: string) => splitExt(p).ext.slice(1).toLowerCase()

/** Runs [ffmpeg] to make an MP3 of [src] at [out]. Metadata and cover art are kept as far as MP3 allows. */
export function convertToMp3(ffmpeg: string, src: string, out: string, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const args = ['-hide_banner', '-loglevel', 'error', '-y', '-i', src, '-map', '0:a:0', '-map', '0:v:0?', '-c:v', 'mjpeg', '-disposition:v', 'attached_pic', '-map_metadata', '0', '-c:a', 'libmp3lame', '-q:a', '2', '-id3v2_version', '3', '-f', 'mp3', out]
    const child = spawn(ffmpeg, args, {windowsHide: true, stdio: ['ignore', 'ignore', 'pipe']})
    let err = ''
    child.stderr.on('data', (d: Buffer) => (err = (err + d.toString()).slice(-500)))
    const stop = () => child.kill()
    signal?.addEventListener('abort', stop, {once: true})
    child.on('error', reject)
    child.on('close', (code) => {
      signal?.removeEventListener('abort', stop)
      if (signal?.aborted) reject(new Error('aborted'))
      else if (code === 0) resolve()
      else reject(new Error(err.trim() || `The conversion ended with code ${code}.`))
    })
  })
}

export interface ExportsJob {
  db: DatabaseSync
  copyRoot: string
  place: PlaceConfig
  /** The entries the place holds (the desired set). */
  entries: Iterable<Entry>
  ffmpeg?: string
  signal?: AbortSignal
  now?: () => number
  /** Replaces the conversion (tests). */
  convert?: (src: string, out: string) => Promise<void>
}

const sha = (text: string) => 'sha256:' + crypto.createHash('sha256').update(text).digest('hex')

function m3u8(name: string, rows: {title: string; rel: string}[]): string {
  const lines = ['#EXTM3U', `#PLAYLIST:${name}`]
  for (const r of rows) {
    lines.push(`#EXTINF:-1,${r.title.replace(/[\r\n]+/g, ' ')}`)
    lines.push(`../${r.rel}`)
  }
  return lines.join('\n') + '\n'
}

export async function syncExports(job: ExportsJob): Promise<ExportsReport> {
  const report: ExportsReport = {written: 0, converted: 0, removed: 0, moved: 0, skipped: []}
  const opts = job.place.options
  const record = await readRecord(job.copyRoot)
  const abs = (rel: string) => path.join(job.copyRoot, ...rel.split('/'))
  const entries = [...job.entries].sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0))
  const meta = new Map<string, {media: string; title: string}>()
  const stmt = job.db.prepare("SELECT json_extract(fields, '$.media') AS media, json_extract(fields, '$.title') AS title FROM item_view WHERE id = ?")
  for (const e of entries) {
    const row = stmt.get(e.itemId) as {media: unknown; title: unknown} | undefined
    meta.set(e.itemId, {media: typeof row?.media === 'string' ? row.media : '', title: typeof row?.title === 'string' ? row.title : path.posix.basename(e.path)})
  }
  const holds = new Map(entries.map((e) => [e.itemId, e]))
  // only what the copy really holds, at the size it should have: a file still being copied or edited is not exported yet
  const held = new Map<string, Entry>()
  for (const e of entries) {
    const st = await fs.stat(abs(e.path)).catch(() => null)
    if (st?.isFile() && st.size === e.bytes && !held.has(e.itemId)) held.set(e.itemId, e)
  }

  type Want = {kind: ExportKind; source: string; make: (dest: string) => Promise<void>}
  const wants = new Map<string, Want>()
  const taken = new Set<string>()
  const claim = (wanted: string): string => {
    let n = 1
    let candidate = wanted
    while (taken.has(candidate.toLowerCase())) candidate = withCounter(wanted, ++n)
    taken.add(candidate.toLowerCase())
    return candidate
  }

  if (opts.playlistFiles) {
    const playlists = readContainers(job.db, 'playlist').filter((p) => job.place.holds.all || job.place.holds.playlists.includes(p.id))
    for (const pl of playlists) {
      const name = typeof pl.fields.name === 'string' && pl.fields.name.trim() ? pl.fields.name.trim() : 'Playlist'
      const rows = playlistItems(job.db, pl.id)
        .map((id) => held.get(id))
        .filter((e): e is Entry => !!e)
        .map((e) => ({title: meta.get(e.itemId)!.title, rel: e.path}))
      const rel = claim(`${PLAYLISTS_DIR}/${safeComponent(name)}.m3u8`)
      const text = m3u8(name, rows)
      wants.set(rel, {kind: 'playlist', source: sha(text), make: (dest) => fs.writeFile(dest, text, 'utf8')})
    }
  }
  if (opts.flat) {
    for (const e of held.values()) {
      const rel = claim(`${EXPORTS_DIR}/Flat/${safeComponent(path.posix.basename(e.path))}`)
      wants.set(rel, {kind: 'flat', source: e.contentId, make: (dest) => fs.copyFile(abs(e.path), dest, fs.constants.COPYFILE_FICLONE)})
    }
  }
  if (opts.mp3) {
    for (const e of held.values()) {
      const m = meta.get(e.itemId)!
      const ext = extOf(e.path)
      if (!AUDIO_NOT_MP3.has(ext) || (m.media && m.media !== 'audio') || !AUDIO.has(ext)) continue
      const stem = splitExt(e.path).stem.split('/').map(safeComponent).join('/')
      const rel = claim(`${EXPORTS_DIR}/MP3/${stem}.mp3`)
      wants.set(rel, {
        kind: 'mp3',
        source: e.contentId,
        make: async (dest) => {
          const tmp = `${dest}.kftmp.mp3`
          try {
            if (job.convert) await job.convert(abs(e.path), tmp)
            else if (job.ffmpeg) await convertToMp3(job.ffmpeg, abs(e.path), tmp, job.signal)
            else throw new Error('No audio converter is available.')
            await fs.rename(tmp, dest)
          } finally {
            await fs.rm(tmp, {force: true}).catch(() => undefined)
          }
          report.converted++
        },
      })
    }
  }
  void holds

  // 1. the record first: everything about to be written is listed before it exists
  const next: ExportFile = {version: 1, files: {...record.files}}
  const todo: {rel: string; want: Want}[] = []
  const reuse = new Map<string, string>() // source -> existing recorded path that still has it (a rename keeps the converted bytes)
  for (const [rel, r] of Object.entries(record.files)) if (r.kind !== 'playlist') reuse.set(`${r.kind}|${r.source}`, rel)
  for (const [rel, want] of wants) {
    const have = record.files[rel]
    const st = await fs.stat(abs(rel)).catch(() => null)
    if (have && have.kind === want.kind && have.source === want.source && st?.isFile() && st.size === have.size) continue
    if (!have && st) {
      report.skipped.push({path: rel, reason: 'A file that Keepfin did not make is already there.'})
      continue
    }
    if (have && st && (st.size !== have.size || Math.abs(st.mtimeMs - have.mtimeMs) > 2000) && have.source !== want.source) {
      // the old export was changed by someone: leave it, the new one cannot take its name
      report.skipped.push({path: rel, reason: 'The export was changed by hand, so it was left alone.'})
      continue
    }
    todo.push({rel, want})
    next.files[rel] = {kind: want.kind, source: want.source, size: have?.size ?? 0, mtimeMs: have?.mtimeMs ?? 0}
  }
  if (todo.length > 0) await writeJsonAtomic(exportsFile(job.copyRoot), next)

  // 2. make them
  for (const {rel, want} of todo) {
    if (job.signal?.aborted) break
    const dest = abs(rel)
    try {
      await fs.mkdir(path.dirname(dest), {recursive: true})
      const old = record.files[rel]
      if (old && (await exists(dest))) await fs.rm(dest, {force: true}) // our own earlier export, replaced by its new version
      const prior = want.kind !== 'playlist' ? reuse.get(`${want.kind}|${want.source}`) : undefined
      if (prior && prior !== rel && !wants.has(prior) && (await exists(abs(prior)))) {
        await fs.rename(abs(prior), dest) // the same content under a new name: no second encoding
        delete next.files[prior]
        reuse.delete(`${want.kind}|${want.source}`)
        report.moved++
      } else {
        await want.make(dest)
        report.written++
      }
      const st = await fs.stat(dest)
      next.files[rel] = {kind: want.kind, source: want.source, size: st.size, mtimeMs: st.mtimeMs}
    } catch (error) {
      delete next.files[rel]
      report.skipped.push({path: rel, reason: error instanceof Error ? error.message : String(error)})
    }
  }

  // 3. no longer derived: removed while it is still what Keepfin wrote
  for (const [rel, r] of Object.entries(record.files)) {
    if (wants.has(rel) || !next.files[rel]) continue
    const st = await fs.stat(abs(rel)).catch(() => null)
    if (st?.isFile() && st.size === r.size && Math.abs(st.mtimeMs - r.mtimeMs) <= 2000) {
      await fs.rm(abs(rel), {force: true})
      report.removed++
    }
    delete next.files[rel]
    let dir = path.dirname(abs(rel))
    while (dir !== job.copyRoot && path.dirname(dir) !== dir) {
      try {
        await fs.rmdir(dir)
      } catch {
        break
      }
      dir = path.dirname(dir)
    }
  }
  const same = JSON.stringify(next.files) === JSON.stringify(record.files)
  if (!same) await writeJsonAtomic(exportsFile(job.copyRoot), next)
  return report
}
