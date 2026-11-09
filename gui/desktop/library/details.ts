// The technical details of one library file for its Details dialog: resolution, codecs, bitrate, container. They are read from the
// file itself with the same converter report the library already uses (probe.ts), for the one file the dialog is opened on.
import path from 'node:path'
import type {FileDetails} from '../../shared/library.js'
import {parseProbe, readBanner, type Probe} from './probe.js'

const rotated = /displaymatrix: rotation of (-?\d+(?:\.\d+)?) degrees/

/** The facts of a converter report (`-i file`), the way the Details dialog shows them. */
export function detailsOf(banner: string, probe: Probe = parseProbe(banner)): FileDetails {
  const details: FileDetails = {}
  if (probe.duration !== undefined) details.duration = probe.duration
  if (probe.video) {
    details.videoCodec = probe.video.codec
    let {width, height} = probe.video
    // A phone video is stored sideways with a rotation: people see it turned.
    const turn = Math.abs(Number(rotated.exec(banner)?.[1] ?? 0))
    if (turn === 90 || turn === 270) [width, height] = [height, width]
    if (width && height) {
      details.width = width
      details.height = height
    }
    const fps = /, (\d+(?:\.\d+)?) fps/.exec(banner)
    if (fps) details.fps = Math.round(Number(fps[1]) * 100) / 100
  }
  if (probe.audio[0]) details.audioCodec = probe.audio[0].codec
  const bitrate = /^\s*Duration:.*bitrate: (\d+) kb\/s/m.exec(banner)
  if (bitrate) details.bitrate = Number(bitrate[1])
  return details
}

/** Reads one file. The container is the file's own extension in capitals (MP4, MKV, M4A). Empty details when the file cannot be read. */
export async function fileDetails(ffmpeg: string | undefined, file: string): Promise<FileDetails> {
  const read = await readBanner(ffmpeg, file)
  if (!read) return {}
  return {...detailsOf(read.banner, read.probe), container: path.extname(file).slice(1).toUpperCase() || undefined}
}
