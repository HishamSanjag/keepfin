import fs from 'node:fs/promises'
import path from 'node:path'
import {renameWithRetry} from '../fsutil.js'

/** Thrown by readJson when a file exists but is not valid JSON. */
export class CorruptJsonError extends Error {
  readonly file: string
  constructor(file: string, cause?: unknown) {
    super(`The file ${file} is damaged and could not be read.`, {cause})
    this.name = 'CorruptJsonError'
    this.file = file
  }
}

/** Writes JSON atomically: temp file in the same folder, fsync, then rename over the target. */
export async function writeJsonAtomic(file: string, value: unknown): Promise<void> {
  await fs.mkdir(path.dirname(file), {recursive: true})
  const temp = `${file}.${process.pid}.${Math.random().toString(36).slice(2)}.tmp`
  const handle = await fs.open(temp, 'w')
  try {
    await handle.writeFile(JSON.stringify(value, null, 2), 'utf8')
    await handle.sync()
  } finally {
    await handle.close()
  }
  try {
    await renameWithRetry(temp, file)
  } catch (error) {
    await fs.rm(temp, {force: true}).catch(() => undefined)
    throw error
  }
}

/** Reads JSON. Undefined when the file is missing; throws CorruptJsonError when it cannot be parsed. */
export async function readJson<T>(file: string): Promise<T | undefined> {
  let text: string
  try {
    text = await fs.readFile(file, 'utf8')
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return undefined
    throw error
  }
  try {
    return JSON.parse(text.charCodeAt(0) === 0xfeff ? text.slice(1) : text) as T
  } catch (error) {
    throw new CorruptJsonError(file, error)
  }
}

/** Appends one line (a newline is added) and fsyncs before returning. */
export async function appendLineDurable(file: string, line: string): Promise<void> {
  await fs.mkdir(path.dirname(file), {recursive: true})
  const handle = await fs.open(file, 'a')
  try {
    await handle.appendFile(`${line}\n`, 'utf8')
    await handle.sync()
  } finally {
    await handle.close()
  }
}
