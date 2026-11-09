// The smaller sizes Keepfin makes by converting (the core's Conversion decides which sizes, the bitrate and the arguments).
// The download runs as usual; this step then converts the finished file with the bundled converter into a temporary name
// beside it and replaces the download only when that worked and came out smaller. Any failure keeps the downloaded file and
// fails the job with a plain message: a smaller copy is never claimed that does not exist.
import fs from 'node:fs/promises'
import path from 'node:path'
import {conversionArgs, conversionKbps, conversionPercent, conversionProbe} from '../../shared/core/formats.js'
import {renameWithRetry} from '../fsutil.js'
import {run, type RunOptions, type RunResult} from './process.js'
import {EngineError} from './ytdlp.js'

export const CONVERT_PHASE = 'Converting'

const FAILED = 'Keepfin could not make the smaller copy; the original size was kept.'

export type ConvertRun = (command: string, args: string[], options: RunOptions) => Promise<RunResult>

export type ConvertOptions = {
  /** The bundled converter. */
  ffmpeg: string | undefined
  /** The finished download. */
  file: string
  /** The short side to convert to. */
  height: number
  /** Length in seconds when the inspection knew it (else it is read from the file). */
  duration?: number
  signal?: AbortSignal
  onProgress?: (percent: number) => void
  run?: ConvertRun
}

const failure = (stderr: string) => new EngineError(FAILED, 'none', stderr, false)

/** Converts `file` down to `height` and replaces it with the result. Returns the file (the same path). */
export async function convertDownload(options: ConvertOptions): Promise<string> {
  const {file, height, signal} = options
  const runner = options.run ?? run
  const ext = path.extname(file)
  // ".kftmp" in the name: copies and scans skip it while it is being written (copies/inventory.ts)
  const temp = path.join(path.dirname(file), `${path.basename(file, ext)}.kftmp-converting${ext}`)
  const remove = () => fs.rm(temp, {force: true}).catch(() => {})
  if (!options.ffmpeg) throw failure('The converter was not found.')
  try {
    const source = (await fs.stat(file)).size
    // The converter's report of the file (it exits with an error when given no output; the report is what is wanted).
    const report = await runner(options.ffmpeg, ['-hide_banner', '-nostdin', '-i', file], {signal, timeoutMs: 30_000})
    signal?.throwIfAborted()
    const probe = conversionProbe(report.stderr)
    const duration = options.duration ?? probe.duration
    const args = conversionArgs(file, temp, height, conversionKbps(height, source, duration), probe.copyAudio)
    const result = await runner(options.ffmpeg, args, {
      signal,
      onStdoutLine: (line) => {
        const percent = duration === undefined ? undefined : conversionPercent(line, duration)
        if (percent !== undefined) options.onProgress?.(percent)
      },
    })
    signal?.throwIfAborted()
    const made = await fs.stat(temp).catch(() => undefined)
    if (result.code !== 0 || !made || made.size <= 0) throw failure(result.stderr)
    // A "smaller" copy that is not smaller would not be what was asked for.
    if (made.size >= source) throw failure(`The converted file (${made.size} bytes) was not smaller than the download (${source} bytes).`)
    await renameWithRetry(temp, file)
    return file
  } catch (error) {
    await remove()
    // A cancel is not a failure; anything else becomes the plain message.
    if (signal?.aborted) throw error
    throw error instanceof EngineError ? error : failure(error instanceof Error ? error.message : String(error))
  }
}
