import fs from 'node:fs/promises'
import path from 'node:path'

export interface ShortcutDetails {
  target: string
  description?: string
}

export interface ShortcutShell {
  writeShortcutLink(file: string, operation: 'create' | 'replace', details: ShortcutDetails): boolean
  /** Throws when the shortcut is missing or unreadable. */
  readShortcutLink(file: string): ShortcutDetails
}

export type ShortcutResult = 'created' | 'updated' | 'unchanged' | 'removed' | 'absent' | 'unsupported'

export interface MusicShortcutOptions {
  enabled: boolean
  keepfinMusicDir: string
  userMusicDir: string
  shell: ShortcutShell
  platform?: NodeJS.Platform
}

const norm = (p: string) => path.win32.resolve(p).replace(/[\\/]+$/, '').toLowerCase()

async function isOurs(target: string, keepfinMusicDir: string): Promise<boolean> {
  if (norm(target) === norm(keepfinMusicDir)) return true
  if (path.win32.basename(target).toLowerCase() !== 'music') return false
  return fs.stat(path.join(path.win32.dirname(target), '.keepfin')).then((s) => s.isDirectory(), () => false)
}

export async function syncMusicShortcut(options: MusicShortcutOptions): Promise<ShortcutResult> {
  if ((options.platform ?? process.platform) !== 'win32') return 'unsupported'
  const {shell} = options
  const link = path.join(options.userMusicDir, 'Keepfin.lnk')
  const present = await fs.stat(link).then(() => true, () => false)
  let current: ShortcutDetails | undefined
  if (present) {
    try {
      current = shell.readShortcutLink(link)
    } catch {
      current = undefined
    }
  }

  if (!options.enabled) {
    if (!present) return 'absent'
    // Remove only a shortcut we can prove is ours; anything else named Keepfin.lnk is left alone.
    if (!current || !(await isOurs(current.target, options.keepfinMusicDir))) return 'absent'
    await fs.rm(link, {force: true})
    return 'removed'
  }

  const details = {target: options.keepfinMusicDir, description: 'Your Keepfin music'}
  if (!present) {
    await fs.mkdir(options.userMusicDir, {recursive: true})
    if (!shell.writeShortcutLink(link, 'create', details)) throw new Error('Windows could not create the shortcut in your Music folder.')
    return 'created'
  }
  if (current && norm(current.target) === norm(options.keepfinMusicDir)) return 'unchanged'
  if (!shell.writeShortcutLink(link, 'replace', details)) throw new Error('Windows could not update the shortcut in your Music folder.')
  return 'updated'
}
