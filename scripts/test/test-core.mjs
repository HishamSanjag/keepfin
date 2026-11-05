// Runs the shared core's own tests on both targets (JVM for Android, JS for desktop), so every fixture is proven on both.
// CI runs the same tasks in build-android.yml; `npm run core:test` runs them locally. Needs a JDK 17 or newer.
import {spawnSync} from 'node:child_process'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const coreDir = path.join(root, 'core')
const windows = process.platform === 'win32'
// On Windows the .bat runs through cmd (shell), which splits an unquoted path at spaces (C:\Users\First Last\...).
const gradlew = path.join(coreDir, windows ? 'gradlew.bat' : 'gradlew')
const gradle = spawnSync(windows ? `"${gradlew}"` : gradlew, ['jvmTest', 'jsNodeTest', '--console=plain'], {
  cwd: coreDir,
  stdio: 'inherit',
  shell: windows,
})
if (gradle.error) {
  console.error(`core:test failed: could not start Gradle: ${gradle.error.message}`)
  process.exit(1)
}
process.exit(gradle.status ?? 1)
