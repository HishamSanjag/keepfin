// Keeps source files small (dev-docs/plans/PROJECT-STRUCTURE.md, rule 5: aim under 600 lines, over 1,000 needs a reason).
// A new file over 1,000 lines fails. The files already over it are listed in big-files.json with a limit (their size when they
// were split, plus room for a fix): passing it fails too, so new code goes in new files. A limit only ever goes down.
import {readFileSync, readdirSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const ROOTS = ['gui', 'core/src', 'android/app/src/main']
const SOURCE = /\.(ts|tsx|cts|kt)$/
const TEST = /\.test\.tsx?$|[\\/](test|androidTest|commonTest|jvmTest|jsTest)[\\/]/
const LIMIT = 1000
const limits = JSON.parse(readFileSync(path.join(root, 'scripts/check/big-files.json'), 'utf8'))

function files(dir, out = []) {
  for (const entry of readdirSync(path.join(root, dir), {withFileTypes: true})) {
    const rel = `${dir}/${entry.name}`
    if (entry.isDirectory()) {
      if (entry.name !== 'build' && entry.name !== 'node_modules') files(rel, out)
    } else if (SOURCE.test(entry.name) && !TEST.test(rel)) out.push(rel)
  }
  return out
}

const sizes = ROOTS.flatMap((dir) => files(dir)).map((file) => ({file, lines: readFileSync(path.join(root, file), 'utf8').split('\n').length}))
const over = sizes.filter(({file, lines}) => lines > (limits[file] ?? LIMIT))
const unknown = Object.keys(limits).filter((file) => !sizes.some((entry) => entry.file === file))

if (unknown.length > 0) {
  console.error(`File sizes: big-files.json names files that no longer exist:\n  ${unknown.join('\n  ')}`)
  process.exit(1)
}
if (over.length > 0) {
  console.error(`File sizes: ${over.length} file(s) over their limit. Put new code in a new file (scripts/check/big-files.json lists the limits):`)
  for (const {file, lines} of over) console.error(`  ${lines} > ${limits[file] ?? LIMIT}  ${file}`)
  process.exit(1)
}
console.log(`File sizes: ok (${Object.keys(limits).length} known big files, none over its limit)`)
