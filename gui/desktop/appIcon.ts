// Windows app icon: the chosen variant's pre-rendered .ico (resources/app-icons, built by npm run icons:build) is
// copied to <userData>/app-icon.ico, set on the window, and written into Keepfin's own shortcuts (icon fields only).
// Pure of Electron so it can be tested with fakes; main.ts supplies the real shell, window and file system.
import path from 'node:path'

/** The fields of an Electron ShortcutDetails that matter here; every other field is passed through untouched. */
export type ShortcutFields = {target: string; icon?: string; iconIndex?: number; [key: string]: unknown}

export type IconShell = {
  readShortcutLink(file: string): ShortcutFields
  writeShortcutLink(file: string, operation: 'replace', options: ShortcutFields): boolean
}

export type IconFiles = {
  exists(file: string): boolean
  copy(from: string, to: string): void
}

export type IconManifest = {main: string; accent: string}

/** File name of a pre-rendered icon: <variant id>-<accent hex without #, upper case>.ico. */
export function iconFileFor(id: string, accent: string): string {
  return `${id}-${accent.replace(/^#/, '').toUpperCase()}.ico`
}

/**
 * The .ico to use for a saved choice. An empty or unknown id gives the main icon. A later accent setting
 * only has to pass a different accent here (it must be one of the pre-rendered accents).
 */
export function resolveIconPath(
  dir: string,
  manifest: IconManifest,
  id: string,
  files: Pick<IconFiles, 'exists'>,
  accent: string = manifest.accent,
): string {
  const wanted = path.join(dir, iconFileFor(id || manifest.main, accent))
  if (id && files.exists(wanted)) return wanted
  return path.join(dir, iconFileFor(manifest.main, accent))
}

const same = (a: string, b: string) => path.normalize(a).toLowerCase() === path.normalize(b).toLowerCase()

/**
 * Points Keepfin's own shortcuts at `icon`. Only existing shortcuts whose target is `exe` are touched; nothing is
 * created, and every field except icon/iconIndex is written back as read. Returns how many changed and whether any failed.
 */
export function updateShortcuts(
  shell: IconShell,
  files: Pick<IconFiles, 'exists'>,
  candidates: string[],
  exe: string,
  icon: string,
  force = false,
): {updated: number; failed: boolean} {
  let updated = 0
  let failed = false
  for (const file of candidates) {
    if (!files.exists(file)) continue
    try {
      const details = shell.readShortcutLink(file)
      if (!details.target || !same(details.target, exe)) continue
      if (!force && details.icon && same(details.icon, icon) && (details.iconIndex ?? 0) === 0) continue
      if (shell.writeShortcutLink(file, 'replace', {...details, icon, iconIndex: 0})) updated++
      else failed = true
    } catch {
      failed = true
    }
  }
  return {updated, failed}
}

/** Where Keepfin's own shortcuts live: per-user Start menu, Desktop, the pinned taskbar button and the all-users Start menu. */
export function shortcutCandidates(env: {appData?: string; programData?: string; desktop?: string}): string[] {
  const list: string[] = []
  if (env.appData) {
    list.push(path.join(env.appData, 'Microsoft', 'Windows', 'Start Menu', 'Programs', 'Keepfin.lnk'))
    list.push(path.join(env.appData, 'Microsoft', 'Internet Explorer', 'Quick Launch', 'User Pinned', 'TaskBar', 'Keepfin.lnk'))
  }
  if (env.desktop) list.push(path.join(env.desktop, 'Keepfin.lnk'))
  if (env.programData) list.push(path.join(env.programData, 'Microsoft', 'Windows', 'Start Menu', 'Programs', 'Keepfin.lnk'))
  return list
}

export type ApplyDeps = {
  iconsDir: string
  manifest: IconManifest
  userData: string
  exe: string
  /** False for the portable copy, whose exe path changes on every run: window and taskbar only. */
  shortcuts: boolean
  candidates: string[]
  shell: IconShell
  files: IconFiles
  setWindowIcon(file: string): void
}

/** Applies the saved choice. changed (the person just picked) rewrites shortcuts even if they already point at the kept file, so Windows refreshes the icon; at start only stale ones are fixed. Never throws: a missing icon file or an unwritable shortcut leaves the previous icon. */
export function applyAppIcon(deps: ApplyDeps, id: string, changed = false): {applied: boolean; shortcutsFailed: boolean} {
  const source = resolveIconPath(deps.iconsDir, deps.manifest, id, deps.files)
  if (!deps.files.exists(source)) return {applied: false, shortcutsFailed: false}
  const kept = path.join(deps.userData, 'app-icon.ico')
  try {
    deps.files.copy(source, kept)
  } catch {
    return {applied: false, shortcutsFailed: false}
  }
  try {
    deps.setWindowIcon(kept)
  } catch {}
  if (!deps.shortcuts) return {applied: true, shortcutsFailed: false}
  const result = updateShortcuts(deps.shell, deps.files, deps.candidates, deps.exe, kept, changed)
  return {applied: true, shortcutsFailed: result.failed}
}
