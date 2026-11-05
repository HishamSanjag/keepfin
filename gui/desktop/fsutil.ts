import fs from 'node:fs/promises'

const transient = new Set(['EPERM', 'EBUSY', 'EACCES'])

/**
 * fs.rename that rides out Windows' brief locks: antivirus scanners and the search indexer
 * hold freshly written files for a moment, so an atomic temp → target rename can fail with
 * EPERM/EBUSY even though nothing is wrong. Retries for about a second, then rethrows.
 */
export async function renameWithRetry(from: string, to: string, attempts = 8): Promise<void> {
  for (let attempt = 1; ; attempt++) {
    try {
      await fs.rename(from, to)
      return
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code
      if (attempt >= attempts || !code || !transient.has(code)) throw error
      await new Promise((resolve) => setTimeout(resolve, 25 * attempt))
    }
  }
}
