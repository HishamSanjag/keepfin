// Prints what Android recorded about Keepfin's process ends on a connected phone: the exit reasons and any native
// crash reports. Read-only. Native crashes bypass the in-app crash log, so this is how they are found by hand.
//
//   node scripts/dev/phone-crashes.mjs [--serial <id>]
import {execFileSync} from 'node:child_process'
import {existsSync} from 'node:fs'
import path from 'node:path'

const args = process.argv.slice(2)
const serialAt = args.indexOf('--serial')
const serial = serialAt >= 0 ? args[serialAt + 1] : null

const local = process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe')
const adb = local && existsSync(local) ? local : 'adb'
const run = (...cmd) => execFileSync(adb, [...(serial ? ['-s', serial] : []), ...cmd], {encoding: 'utf8', maxBuffer: 64 * 1024 * 1024})

try {
  console.log('== Exit reasons (dumpsys activity exit-info app.keepfin) ==')
  console.log(run('shell', 'dumpsys', 'activity', 'exit-info', 'app.keepfin'))
  console.log('== Native crash reports (dumpsys dropbox --print data_app_native_crash) ==')
  console.log(run('shell', 'dumpsys', 'dropbox', '--print', 'data_app_native_crash'))
} catch (error) {
  console.error(`adb failed: ${error.stderr || error.message}\nConnect one phone, or pass --serial <id>.`)
  process.exit(1)
}
