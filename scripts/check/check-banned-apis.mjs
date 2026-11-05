// Calls that are banned in Kotlin source because they failed on real devices, each with what to use instead.
import {readFileSync, readdirSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const DIRS = ['core/src', 'android/app/src/main']

const BANNED = [
  {
    pattern: /\bmatchAt\s*\(/,
    why: 'Regex.matchAt crashed Keepfin natively on Android 15 (ICU 75). Use matchStartAtEnd in core/.../music/JsText.kt.',
  },
]

function files(dir, out = []) {
  for (const entry of readdirSync(path.join(root, dir), {withFileTypes: true})) {
    const rel = `${dir}/${entry.name}`
    if (entry.isDirectory()) files(rel, out)
    else if (entry.name.endsWith('.kt')) out.push(rel)
  }
  return out
}

const problems = []
for (const file of DIRS.flatMap((dir) => files(dir))) {
  readFileSync(path.join(root, file), 'utf8').split('\n').forEach((line, index) => {
    for (const ban of BANNED) if (ban.pattern.test(line)) problems.push(`${file}:${index + 1}  ${ban.why}`)
  })
}

if (problems.length > 0) {
  console.error(`Banned calls: ${problems.length} problem(s)\n${problems.join('\n')}`)
  process.exit(1)
}
console.log('Banned calls: ok')
