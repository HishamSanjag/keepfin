/** Some files of a photo post reached the library and some did not: the ones that did are kept, the message says which, and Save again tries the rest. */
export class PartlySaved extends Error {
  constructor(
    readonly saved: number,
    readonly total: number,
    reason: string,
  ) {
    super(`Saved ${saved} of ${total} files. The others are safe where they were downloaded: ${reason.replace(/\.+$/, '')}.`)
  }
}

/**
 * Runs `add` for each file and returns the item ids it made. A file outside a photo post stops at its first failure. A photo post (`each`) tries
 * every file: it throws the first failure when none was added, else a PartlySaved naming the count.
 */
export async function addEach(each: boolean, files: Iterable<string | undefined>, add: (file: string | undefined) => Promise<string | undefined>): Promise<string[]> {
  const added: string[] = []
  const failures: unknown[] = []
  for (const file of files) {
    try {
      const id = await add(file)
      if (id) added.push(id)
    } catch (error) {
      if (!each) throw error
      failures.push(error)
    }
  }
  if (!failures.length) return added
  if (!added.length) throw failures[0]
  throw new PartlySaved(added.length, added.length + failures.length, failures[0] instanceof Error ? failures[0].message : 'the library could not add them')
}
