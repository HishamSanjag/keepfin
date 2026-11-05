// Reads container facts from the bundled ffmpeg (`ffmpeg -i`): duration, size, codecs, chapters.
// There is no separate probe binary, so the banner on stderr is parsed.
import {run} from '../engine/process.js'

export type Probe = {
  duration?: number
  /** Demuxer names, e.g. ['matroska', 'webm'] or ['mov', 'mp4', 'm4a']. */
  containers: string[]
  video?: {codec: string; width?: number; height?: number}
  audio: {codec: string}[]
  chapters: {start: number; title: string}[]
}

const clock = /(\d+):(\d{2}):(\d{2}(?:\.\d+)?)/

export function parseProbe(banner: string): Probe {
  const probe: Probe = {containers: [], audio: [], chapters: []}
  const input = /^Input #0, ([^,]+(?:,[^,\s]+)*), (?:from|to)/m.exec(banner)
  if (input) probe.containers = input[1].split(',').map((name) => name.trim())
  const duration = /^\s*Duration: (\d+:\d{2}:\d{2}(?:\.\d+)?)/m.exec(banner)
  if (duration) {
    const [, h, m, s] = clock.exec(duration[1])!
    const seconds = Number(h) * 3600 + Number(m) * 60 + Number(s)
    if (Number.isFinite(seconds)) probe.duration = seconds
  }
  const lines = banner.split(/\r?\n/)
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const stream = /^\s*Stream #\d+:\d+(?:\[[^\]]*\])?(?:\([^)]*\))?: (Video|Audio): ([\w-]+)/.exec(line)
    if (stream) {
      if (stream[1] === 'Audio') probe.audio.push({codec: stream[2].toLowerCase()})
      else if (!probe.video && !/attached pic/i.test(line)) {
        const size = /, (\d{2,5})x(\d{2,5})(?:[ ,[]|$)/.exec(line)
        probe.video = {
          codec: stream[2].toLowerCase(),
          width: size ? Number(size[1]) : undefined,
          height: size ? Number(size[2]) : undefined,
        }
      }
      continue
    }
    const chapter = /^\s*Chapter #\d+:\d+: start (\d+(?:\.\d+)?), end/.exec(line)
    if (chapter) {
      let title = ''
      for (let j = i + 1; j < lines.length && j <= i + 4; j++) {
        const named = /^\s+title\s+:\s?(.*)$/.exec(lines[j])
        if (named) {
          title = named[1].trim()
          break
        }
        if (/^\s*(Chapter #|Stream #)/.test(lines[j])) break
      }
      probe.chapters.push({start: Number(chapter[1]), title: title || `Chapter ${probe.chapters.length + 1}`})
    }
  }
  return probe
}

/** Probes a local file. Resolves undefined when ffmpeg is missing or the file cannot be read. */
export async function probeFile(ffmpeg: string | undefined, file: string, signal?: AbortSignal): Promise<Probe | undefined> {
  if (!ffmpeg) return
  try {
    const result = await run(ffmpeg, ['-hide_banner', '-nostdin', '-protocol_whitelist', 'file', '-i', file], {
      signal,
      timeoutMs: 30_000,
    })
    const probe = parseProbe(result.stderr)
    return probe.containers.length ? probe : undefined
  } catch {
    return
  }
}
