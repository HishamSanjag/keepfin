// Local Android APK for testing on a phone: the optimized release build (not debuggable, so Compose runs at full speed),
// signed with this machine's Android debug key so it installs over earlier local builds. The real release key only signs
// in CI. `node scripts/build/android-build.mjs --debug` builds the debuggable variant instead (fixtures, debug launch extras).
import {spawnSync} from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
const root = path.resolve('android')
const debug = process.argv.includes('--debug')
const sdk =
  process.env.ANDROID_HOME ||
  process.env.ANDROID_SDK_ROOT ||
  (process.platform === 'win32'
    ? path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk')
    : undefined)
if (sdk && fs.existsSync(sdk))
  fs.writeFileSync(
    path.join(root, 'local.properties'),
    `sdk.dir=${sdk.replaceAll('\\', '/')}\n`,
  )

function run(command, args, cwd) {
  // .bat files need the shell on Windows; the shell needs paths with spaces quoted.
  const shell = process.platform === 'win32'
  const quote = (value) => (shell && /\s/.test(value) ? `"${value}"` : value)
  const result = spawnSync(quote(command), args.map(quote), {cwd, stdio: 'inherit', shell})
  if (result.error) {
    console.error(result.error.message)
    process.exit(1)
  }
  if (result.status !== 0) process.exit(result.status ?? 1)
}

// A full path: some shells (NoDefaultCurrentDirectoryInExePath) do not run programs from the current folder by bare name.
const gradle = path.join(root, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')
// The debug key is created by the first debug build; a release-only build on a fresh machine needs one first.
const keystore = path.join(os.homedir(), '.android', 'debug.keystore')
const tasks = debug || fs.existsSync(keystore) ? [] : [':app:assembleDebug']
run(gradle, [...tasks, debug ? ':app:assembleDebug' : ':app:assembleRelease', '--console=plain'], root)

const {version} = JSON.parse(fs.readFileSync('package.json', 'utf8'))
const releaseDir = path.resolve('releases/android')
fs.mkdirSync(releaseDir, {recursive: true})
const releaseApk = path.join(releaseDir, `Keepfin Android ${version}.apk`)
if (debug) {
  fs.copyFileSync(path.join(root, 'app/build/outputs/apk/debug/app-debug.apk'), releaseApk)
} else {
  const buildTools = path.join(sdk || '', 'build-tools')
  const newest = fs.readdirSync(buildTools).sort((a, b) => b.localeCompare(a, undefined, {numeric: true}))[0]
  const tool = (name) => path.join(buildTools, newest, process.platform === 'win32' ? `${name}${name === 'apksigner' ? '.bat' : '.exe'}` : name)
  const unsigned = path.join(root, 'app/build/outputs/apk/release/app-release-unsigned.apk')
  const aligned = path.join(root, 'app/build/outputs/apk/release/app-release-aligned.apk')
  run(tool('zipalign'), ['-f', '-p', '4', unsigned, aligned])
  // The Android SDK's standard debug key; its well-known password is not a secret.
  run(tool('apksigner'), ['sign', '--v4-signing-enabled', 'false', '--ks', keystore, '--ks-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey', '--key-pass', 'pass:android', '--out', releaseApk, aligned])
}
console.log(`Android APK (${debug ? 'debug' : 'optimized, debug-signed'}) built`)
// releases/<version>/Keepfin-Android-<version>.apk, next to the Windows builds of the same version.
run(process.execPath, [path.resolve('scripts/release/organize-releases.mjs')], process.cwd())
