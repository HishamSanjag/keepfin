import fs from 'node:fs'
import readline from 'node:readline'

/** Every non-empty line of a JSON-lines file, parsed. */
export async function readJsonLines<T>(file: string): Promise<T[]> {
  const out: T[] = []
  for await (const line of readline.createInterface({input: fs.createReadStream(file), crlfDelay: Infinity})) if (line.trim()) out.push(JSON.parse(line) as T)
  return out
}

/** The lines of a JSON-lines file one at a time. */
export async function* eachJsonLine<T>(file: string): AsyncGenerator<T> {
  const lines = readline.createInterface({input: fs.createReadStream(file), crlfDelay: Infinity})
  try {
    for await (const line of lines) if (line.trim()) yield JSON.parse(line) as T
  } finally {
    lines.close()
  }
}
