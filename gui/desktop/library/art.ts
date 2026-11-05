// Durable cover art and video posters, one file per item in <userData>/library-art.
// Kept apart from the temporary inspection thumbnails, which are evictable.
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {run} from '../engine/process.js'

const extensions = ['jpg', 'png', 'webp'] as const
const mimeToExt: Record<string, (typeof extensions)[number]> = {
  'image/jpeg': 'jpg',
  'image/jpg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
}
export const artTypes: Record<string, string> = {jpg: 'image/jpeg', png: 'image/png', webp: 'image/webp'}
export const MAX_ART_BYTES = 8_000_000

/** Sniffs the real image type; embedded pictures mislabel their mime often enough. */
export function sniffImage(bytes: Uint8Array): (typeof extensions)[number] | undefined {
  if (bytes.length > 12) {
    if (bytes[0] === 0xff && bytes[1] === 0xd8) return 'jpg'
    if (bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) return 'png'
    if (bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[8] === 0x57 && bytes[9] === 0x45) return 'webp'
  }
}

export class ArtStore {
  constructor(private readonly directory: string) {}

  private file(id: string, ext: string) {
    return path.join(this.directory, `${id}.${ext}`)
  }

  /** Ids of the items that have stored art. */
  async ids(): Promise<string[]> {
    const names = await fs.readdir(this.directory).catch(() => [] as string[])
    return names.filter((name) => /\.(jpg|png|webp)$/.test(name)).map((name) => name.replace(/\.(jpg|png|webp)$/, ''))
  }

  /** Path of the stored art for an item, if any. */
  async find(id: string): Promise<string | undefined> {
    for (const ext of extensions) {
      const candidate = this.file(id, ext)
      if ((await fs.stat(candidate).catch(() => undefined))?.isFile()) return candidate
    }
  }

  /** Stores image bytes atomically; returns false when they are not a supported image. */
  async put(id: string, bytes: Uint8Array, mime?: string): Promise<boolean> {
    if (!bytes.length || bytes.length > MAX_ART_BYTES) return false
    const ext = sniffImage(bytes) ?? (mime ? mimeToExt[mime.toLowerCase()] : undefined)
    if (!ext) return false
    await fs.mkdir(this.directory, {recursive: true})
    const target = this.file(id, ext)
    const temporary = `${target}.tmp`
    try {
      await fs.writeFile(temporary, bytes)
      await this.remove(id)
      await fs.rename(temporary, target)
    } finally {
      await fs.rm(temporary, {force: true})
    }
    return true
  }

  async remove(id: string) {
    await Promise.all(extensions.map((ext) => fs.rm(this.file(id, ext), {force: true})))
  }

  /** Re-keys art when an item's path (and so its id) changes. */
  async rename(from: string, to: string) {
    const existing = await this.find(from)
    if (!existing) return
    await this.remove(to)
    await fs.rename(existing, this.file(to, path.extname(existing).slice(1))).catch(() => {})
  }

  /** Deletes art files whose item is gone. */
  async prune(keep: Set<string>) {
    for (const name of await fs.readdir(this.directory).catch(() => [] as string[])) {
      const id = name.replace(/\.(jpg|png|webp)(\.tmp)?$/, '')
      if (!keep.has(id)) await fs.rm(path.join(this.directory, name), {force: true})
    }
  }
}

/** A poster frame at `fraction` of the duration (10% by default), at most 640 px wide. Falls back to the first frame. */
export async function posterFrame(
  ffmpeg: string,
  file: string,
  duration: number | undefined,
  fraction = 0.1,
): Promise<Buffer | undefined> {
  const seek = duration && duration > 1 ? Math.max(0, duration * fraction) : 0
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-poster-'))
  const target = path.join(directory, 'frame.jpg')
  try {
    for (const position of seek > 0 ? [seek, 0] : [0]) {
      const result = await run(
        ffmpeg,
        [
          '-hide_banner', '-nostdin', '-loglevel', 'error', '-threads', '1', '-protocol_whitelist', 'file',
          '-ss', position.toFixed(2), '-i', file, '-map', '0:v:0', '-frames:v', '1',
          '-vf', 'scale=w=min(640\\,iw):h=-2', '-q:v', '4', '-y', target,
        ],
        {timeoutMs: 30_000, lowPriority: true},
      ).catch(() => undefined)
      if (result?.code === 0) {
        const bytes = await fs.readFile(target).catch(() => undefined)
        if (bytes?.length) return bytes
      }
    }
  } finally {
    await fs.rm(directory, {recursive: true, force: true})
  }
}
