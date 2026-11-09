// What only Windows can tell about a folder, asked once per run and only where it matters (spec 3, 5.4): the format of the drive
// (FAT cannot hold a file of 4 GiB) and the cloud files whose bytes are not on the disk (OneDrive "online-only" and the like).
// Node has no API for file attributes, so one PowerShell process answers each question; nothing here changes anything. Every
// answer is null when it could not be had, and the run then treats the side as not fully known.
import {execFile} from 'node:child_process'
import path from 'node:path'
import {isOnlineOnly} from '../../shared/core/copies.js'

const MAX_OUT = 512 * 1024 * 1024

function run(file: string, args: string[], env: Record<string, string>, timeoutMs: number, signal?: AbortSignal): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(file, args, {env: {...process.env, ...env}, windowsHide: true, maxBuffer: MAX_OUT, timeout: timeoutMs, signal, encoding: 'utf8'}, (error, stdout) => (error ? reject(error) : resolve(stdout)))
  })
}

const powershell = (script: string, env: Record<string, string>, timeoutMs: number, signal?: AbortSignal) =>
  run('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', `[Console]::OutputEncoding = [Text.Encoding]::UTF8; ${script}`], env, timeoutMs, signal)

const formats = new Map<string, string | null>()

/** The file system of the drive that holds [dir] (`NTFS`, `exFAT`, `FAT32`...), or null when it is not a lettered drive or cannot be read. Windows only. */
export async function volumeFormat(dir: string): Promise<string | null> {
  if (process.platform !== 'win32') return null
  const root = path.parse(path.resolve(dir)).root
  if (!/^[A-Za-z]:\\$/.test(root)) return null
  if (formats.has(root)) return formats.get(root) ?? null
  let format: string | null = null
  try {
    const out = (await powershell('[System.IO.DriveInfo]::new($env:KF_DRIVE).DriveFormat', {KF_DRIVE: root}, 20_000)).trim()
    format = out || null
  } catch {
    format = null
  }
  formats.set(root, format)
  return format
}

/** The folders cloud services keep their files in (`SyncRootManager`), as Windows records them. */
export async function syncRoots(): Promise<string[]> {
  if (process.platform !== 'win32') return []
  try {
    const out = await run('reg.exe', ['query', 'HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\SyncRootManager', '/s', '/v', 'UserSyncRoots'], {}, 15_000)
    const roots: string[] = []
    for (const line of out.split(/\r?\n/)) {
      const m = /\sREG_\w+\s+(.+?)\s*$/.exec(line)
      if (m && /^[A-Za-z]:\\/.test(m[1])) roots.push(m[1].replace(/\\+$/, ''))
    }
    return roots
  } catch {
    return []
  }
}

const inside = (root: string, dir: string) => {
  const a = root.toLowerCase()
  const b = path.resolve(dir).toLowerCase()
  return b === a || b.startsWith(a + '\\')
}

/**
 * The files under [root] whose bytes are not on the disk (cloud placeholders), as paths relative to [root] with `/`. An empty list when
 * the folder is not inside a cloud service's folder (nothing to ask), null when the question could not be answered there.
 */
export async function onlineOnlyFiles(root: string, signal?: AbortSignal): Promise<string[] | null> {
  if (process.platform !== 'win32') return []
  const roots = await syncRoots()
  if (!roots.some((r) => inside(r, root) || inside(root, r))) return []
  return scanOnlineOnly(root, signal)
}

/** [onlineOnlyFiles] without asking whether the folder is a cloud folder: lists every file under [root] whose attributes say it is not on the disk. */
export async function scanOnlineOnly(root: string, signal?: AbortSignal): Promise<string[] | null> {
  // PowerShell only narrows (any of the three attribute bits); the core decides from the bits it prints (isOnlineOnly).
  // Relative paths are built from the names walked, never cut from FullName: PowerShell can spell the same folder differently
  // from [root] (a short 8.3 name such as RUNNER~1 comes back long), which shifted the cut. Links are not followed.
  const script =
    "$mask = 0x400000 -bor 0x40000 -bor 0x1000; " +
    "function Walk($dir, $rel) { foreach ($i in @(Get-ChildItem -LiteralPath $dir -Force -ErrorAction SilentlyContinue)) { " +
    "$r = if ($rel) { $rel + '\\' + $i.Name } else { $i.Name }; " +
    "if ($i.PSIsContainer) { if (-not $i.LinkType) { Walk $i.FullName $r } } " +
    "elseif (([int]$i.Attributes -band $mask) -ne 0) { '{0}|{1}' -f [int]$i.Attributes, $r } } }; " +
    "Walk $env:KF_ROOT ''"
  try {
    const out = await powershell(script, {KF_ROOT: path.resolve(root)}, 15 * 60_000, signal)
    const files: string[] = []
    for (const line of out.split(/\r?\n/)) {
      const bar = line.indexOf('|')
      if (bar < 1) continue
      const attributes = Number(line.slice(0, bar))
      if (Number.isFinite(attributes) && isOnlineOnly(attributes >>> 0)) files.push(line.slice(bar + 1).replace(/\\/g, '/'))
    }
    return files
  } catch {
    return null
  }
}
