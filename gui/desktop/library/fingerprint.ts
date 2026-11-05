// Content identity for duplicate detection and move verification.
import {createHash} from 'node:crypto'
import fs from 'node:fs/promises'
import {createReadStream} from 'node:fs'
import {HASH_WINDOW} from '../../shared/library.js'

/** Size plus SHA-256 of the first and last MiB (the whole file when it is small). Cheap enough for imports. */
export async function fingerprint(file: string, size?: number): Promise<string> {
  const handle = await fs.open(file, 'r')
  try {
    const length = size ?? (await handle.stat()).size
    const hash = createHash('sha256').update(String(length))
    const read = async (position: number, count: number) => {
      const buffer = Buffer.alloc(count)
      const {bytesRead} = await handle.read(buffer, 0, count, position)
      hash.update(buffer.subarray(0, bytesRead))
    }
    if (length <= HASH_WINDOW * 2) await read(0, length)
    else {
      await read(0, HASH_WINDOW)
      await read(length - HASH_WINDOW, HASH_WINDOW)
    }
    return hash.digest('hex')
  } finally {
    await handle.close()
  }
}

/** SHA-256 of the whole file, streamed. Used to verify cross-volume copies before the original goes to the Recycle Bin. */
export function fullHash(file: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha256')
    const stream = createReadStream(file)
    stream.on('data', (chunk) => hash.update(chunk))
    stream.on('error', reject)
    stream.on('end', () => resolve(hash.digest('hex')))
  })
}
