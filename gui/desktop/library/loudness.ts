// EBU R128 integrated loudness with the bundled ffmpeg, turned into a normalisation gain.
import {run} from '../engine/process.js'

export const TARGET_LUFS = -14

/** Integrated loudness (LUFS) from the ebur128 summary; undefined for silence or unreadable output. */
export function parseIntegratedLoudness(stderr: string): number | undefined {
  const summary = stderr.slice(stderr.lastIndexOf('Summary:'))
  const match = /Integrated loudness:\s*\n\s*I:\s*(-?\d+(?:\.\d+)?)\s*LUFS/.exec(summary)
  if (!match) return
  const value = Number(match[1])
  return Number.isFinite(value) && value > -70 ? value : undefined
}

/** Attenuation only: quieter than the target gets turned down, quiet tracks are left alone. */
export function gainForLoudness(lufs: number): number {
  return Math.min(0, Math.round((TARGET_LUFS - lufs) * 10) / 10)
}

/** Runs the analysis at below-normal priority. Resolves the gain in dB (≤ 0) or undefined when unmeasurable. */
export async function analyseGain(ffmpeg: string, file: string, signal?: AbortSignal): Promise<number | undefined> {
  const result = await run(
    ffmpeg,
    ['-hide_banner', '-nostdin', '-nostats', '-protocol_whitelist', 'file', '-i', file, '-vn', '-af', 'ebur128=framelog=quiet', '-f', 'null', '-'],
    {signal, timeoutMs: 15 * 60_000, lowPriority: true},
  )
  if (result.code !== 0) return
  const loudness = parseIntegratedLoudness(result.stderr)
  return loudness === undefined ? undefined : gainForLoudness(loudness)
}
