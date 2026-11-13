// The folder walk shared by the space scans and the Music on this PC scan: media files under one root, skipping hidden
// folders, folders with a .nomedia file, left-out folders and companion files.
import fs from 'node:fs/promises'
import path from 'node:path'
import {mediaKindOf, type LibraryItemKind} from '../../../shared/library.js'
import {musicVideoNames} from '../tags.js'

const MAX_WALK = 100_000
/** Files still being written by a download or an edit. */
export const transient = /\.(?:part|ytdl|tmp|temp|crdownload|download|kfmove|kfedit)$/i
/** Files made while Keepfin works on a download or an edit; never listed. */
export const intermediate = /\.f\d+(?:-[\w]+)?\.[a-z0-9]+$|\.(?:tagging|temp|cover|kfmove|kfedit)\.[a-z0-9]+$/i

export type WalkOptions = {
  /** Subfolders (relative to the root, '/'-separated, any case) to leave out. */
  ignore?: readonly string[]
  /** Only files of this kind. */
  kind?: LibraryItemKind
  /** A folder (absolute) to leave out. */
  skip?: (folder: string) => boolean
}

/** Every media file under [root] (at most 100,000). */
export async function walkMedia(root: string, options: WalkOptions = {}): Promise<string[]> {
  const found: string[] = []
  const ignored = (options.ignore ?? []).map((item) => item.toLowerCase())
  const pending = [root]
  while (pending.length && found.length < MAX_WALK) {
    const dir = pending.pop()!
    let dirents
    try {
      dirents = await fs.readdir(dir, {withFileTypes: true})
    } catch {
      continue
    }
    if (dirents.some((dirent) => dirent.name === '.nomedia')) continue
    const videos = musicVideoNames(dirents.filter((dirent) => dirent.isFile()).map((dirent) => dirent.name))
    for (const dirent of dirents) {
      const full = path.join(dir, dirent.name)
      if (dirent.isDirectory()) {
        if (dirent.name.startsWith('.') || options.skip?.(full)) continue
        const relative = path.relative(root, full).split(path.sep).join('/').toLowerCase()
        if (ignored.some((item) => relative === item || relative.startsWith(item + '/'))) continue
        pending.push(full)
      } else if (dirent.isFile() && isWanted(dirent.name, options.kind) && !intermediate.test(dirent.name) && !videos.has(dirent.name.toLowerCase())) found.push(full)
    }
  }
  return found
}

const isWanted = (name: string, kind?: LibraryItemKind) => {
  const actual = mediaKindOf(name)
  return !!actual && (!kind || actual === kind)
}