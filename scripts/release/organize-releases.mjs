// Tidies the local releases folder (never used by CI, which keeps releases/desktop and releases/android as they are):
//
//   releases/<current version>/   Keepfin-Setup-…exe (+ .blockmap, latest.yml), Keepfin-Portable-…exe, Keepfin-Android-….apk
//   releases/older/<version>/      every earlier build, one folder per version (Yoinks builds as yoinks-<version>)
//   releases/desktop/              the build's working files only (win-unpacked, builder-debug.yml)
//
// Run by `npm run android:build` and `npm run releases:organize` (after a desktop build). Files are moved, never deleted.
import fs from 'node:fs'
import path from 'node:path'

const releases = path.resolve('releases')
const {version: current} = JSON.parse(fs.readFileSync('package.json', 'utf8'))
const VERSION = /(\d+\.\d+\.\d+(?:-[a-z]+\.\d+)?)/i
const KEEP_IN_DESKTOP = new Set(['win-unpacked', 'builder-debug.yml', 'builder-effective-config.yaml'])

/** Loose build files: in desktop/, android/ and the older test-<version>/<platform>/ folders. */
function looseFiles() {
  const files = []
  const scan = (dir, depth) => {
    if (!fs.existsSync(dir)) return
    for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
      const file = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        if (depth > 0 && !KEEP_IN_DESKTOP.has(entry.name)) scan(file, depth - 1)
      } else if (!KEEP_IN_DESKTOP.has(entry.name)) files.push(file)
    }
  }
  scan(path.join(releases, 'desktop'), 0)
  scan(path.join(releases, 'android'), 0)
  for (const entry of fs.readdirSync(releases, {withFileTypes: true})) if (entry.isDirectory() && /^test-/.test(entry.name)) scan(path.join(releases, entry.name), 1)
  return files
}

/** latest.yml names the version it belongs to inside the file. */
function versionOf(file) {
  const name = path.basename(file)
  if (name === 'latest.yml') return fs.readFileSync(file, 'utf8').match(/^version:\s*(\S+)/m)?.[1]
  return name.match(VERSION)?.[1]
}

/** One naming for every build: Keepfin-Android-<version>.apk like CI; Windows names stay as the builder writes them. */
function cleanName(file, version) {
  const name = path.basename(file)
  if (/\.apk$/i.test(name) && /^keepfin/i.test(name)) return `Keepfin-Android-${version}${/debug/i.test(name) ? '-debug' : ''}.apk`
  return name
}

let moved = 0
for (const file of looseFiles()) {
  const name = path.basename(file)
  const version = versionOf(file)
  // The signature sidecar apksigner writes next to an APK is not needed to install it.
  if (name.endsWith('.idsig')) {
    fs.rmSync(file)
    continue
  }
  if (!version) continue
  const yoinks = /yoinks/i.test(name)
  const dir = version === current && !yoinks ? path.join(releases, version) : path.join(releases, 'older', yoinks ? `yoinks-${version}` : version)
  fs.mkdirSync(dir, {recursive: true})
  const target = path.join(dir, cleanName(file, version))
  if (fs.existsSync(target)) fs.rmSync(target)
  fs.renameSync(file, target)
  moved++
}
for (const entry of fs.readdirSync(releases, {withFileTypes: true}))
  if (entry.isDirectory() && /^test-/.test(entry.name) && !looseFilesIn(path.join(releases, entry.name))) fs.rmSync(path.join(releases, entry.name), {recursive: true})

function looseFilesIn(dir) {
  const stack = [dir]
  while (stack.length) {
    const next = stack.pop()
    for (const entry of fs.readdirSync(next, {withFileTypes: true})) {
      if (entry.isDirectory()) stack.push(path.join(next, entry.name))
      else return true
    }
  }
  return false
}

fs.writeFileSync(
  path.join(releases, 'README.txt'),
  [
    `Keepfin builds on this PC`,
    ``,
    `${current}\\        the build to test now: Windows installer (Keepfin-Setup), Windows portable, Android APK`,
    `older\\             earlier builds, one folder per version`,
    `desktop\\           working files of the Windows build (not for installing)`,
    ``,
  ].join('\r\n'),
)
console.log(`releases: ${moved} file(s) sorted; current builds are in ${path.join(releases, current)}`)
