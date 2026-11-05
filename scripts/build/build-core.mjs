// Builds the shared core (core/, Kotlin Multiplatform) for the desktop app: runs the Gradle task that
// produces the JavaScript library and copies it to build/core-js/, which gui/shared/core/*.ts import.
// Needs a JDK 17 or newer (the same one the Android build uses). Gradle is incremental: a warm run takes seconds.
import {spawnSync} from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const coreDir = path.join(root, 'core')
const windows = process.platform === 'win32'
const task = 'jsNodeProductionLibraryDistribution'
const dist = path.join(coreDir, 'build', 'dist', 'js', 'productionLibrary')
const out = path.join(root, 'build', 'core-js')

function fail(message) {
  console.error(`core:build failed: ${message}`)
  process.exit(1)
}

function hasJava() {
  const home = process.env.JAVA_HOME
  const candidates = [home ? path.join(home, 'bin', windows ? 'java.exe' : 'java') : null, 'java'].filter(Boolean)
  return candidates.some((java) => {
    const result = spawnSync(java, ['-version'], {stdio: 'ignore'})
    return !result.error && result.status === 0
  })
}

if (!hasJava()) {
  fail(
    'Desktop builds need a JDK 17 or newer to build the shared core (core/). ' +
      'Install one (for example Temurin 17 or 21) and make sure `java -version` works in this shell, ' +
      'or set JAVA_HOME. The Android build uses the same JDK.',
  )
}

// On Windows the .bat runs through cmd (shell), which splits an unquoted path at spaces (C:\Users\First Last\...).
const gradlew = path.join(coreDir, windows ? 'gradlew.bat' : 'gradlew')
const gradle = spawnSync(
  windows ? `"${gradlew}"` : gradlew,
  [task, '--console=plain'],
  {cwd: coreDir, stdio: 'inherit', shell: windows},
)
if (gradle.error) fail(`could not start Gradle: ${gradle.error.message}`)
if (gradle.status !== 0) fail(`Gradle exited with code ${gradle.status}`)
if (!fs.existsSync(path.join(dist, 'keepfin-core.mjs'))) fail(`Gradle did not produce ${path.relative(root, dist)}/keepfin-core.mjs`)

fs.rmSync(out, {recursive: true, force: true})
fs.mkdirSync(out, {recursive: true})
for (const name of fs.readdirSync(dist)) {
  if (name === 'package.json') continue
  fs.copyFileSync(path.join(dist, name), path.join(out, name))
}

const debug = process.env.KEEPFIN_CORE_DEBUG === '1'
if (debug) {
  // Readable output with source maps. Maps list sources relative to the Gradle output folder; make the ones inside
  // this repository relative to build/core-js instead, so stack traces and editors resolve them. Other sources
  // (library files) carry their text in sourcesContent and stay as they are.
  for (const name of fs.readdirSync(out)) {
    if (!name.endsWith('.map')) continue
    const file = path.join(out, name)
    const map = JSON.parse(fs.readFileSync(file, 'utf8'))
    map.sources = map.sources.map((source) =>
      source.startsWith('../') ? path.relative(out, path.resolve(dist, source)).split(path.sep).join('/') : source,
    )
    fs.writeFileSync(file, JSON.stringify(map))
  }
} else {
  // Minify (about 60% smaller, so a smaller main.js and less to parse at launch) and drop the maps, which no
  // longer match. Set KEEPFIN_CORE_DEBUG=1 for readable output with source maps. esbuild comes with tsup.
  let esbuild
  try {
    esbuild = await import('esbuild')
  } catch {
    console.warn('core:build: esbuild is not installed; leaving the core unminified')
  }
  for (const name of fs.readdirSync(out)) {
    if (name.endsWith('.map')) fs.rmSync(path.join(out, name))
    else if (esbuild && name.endsWith('.mjs')) {
      const file = path.join(out, name)
      const code = fs.readFileSync(file, 'utf8').replace(/\n\/\/# sourceMappingURL=.*$/, '')
      fs.writeFileSync(file, esbuild.transformSync(code, {minify: true, format: 'esm', target: 'node22'}).code)
    }
  }
}
console.log(`core:build: copied ${fs.readdirSync(out).length} files to ${path.relative(root, out)}${debug ? " (debug: readable, with source maps)" : " (minified)"}`)
