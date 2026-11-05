// Runs the TypeScript unit tests, found by name instead of a hand-written list, so a new test folder is never left out
// (until 2026-10-30 the merge tests and the SQL-state scale test were never run).
// - The normal run takes every `*.test.ts`, the scale tests in their quick size (20,000 items).
// - `--slow` runs only the scale tests (`scale.test.ts`) at the full budget of 200,000 items (dev-docs/plans/KEEPFIN-FOLDER.md
//   performance budgets); it takes many minutes and runs before releases.
// - `--list` prints the files without running them, to compare counts before and after moves.
import {spawnSync} from 'node:child_process'
import {readdirSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const ROOTS = ['gui']
const SKIP_DIRS = new Set(['node_modules', 'build', 'dist'])
const FULL_SCALE = '200000'
const isTest = (name) => /\.test\.tsx?$/.test(name)
const isScale = (file) => /(^|\/)scale\.test\.tsx?$/.test(file)

function find(dir, out) {
  for (const entry of readdirSync(dir, {withFileTypes: true})) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) find(path.join(dir, entry.name), out)
    } else if (isTest(entry.name)) {
      out.push(path.relative(root, path.join(dir, entry.name)).split(path.sep).join('/'))
    }
  }
  return out
}

const slow = process.argv.includes('--slow')
const files = ROOTS.flatMap((dir) => find(path.join(root, dir), []))
  .filter((file) => !slow || isScale(file))
  .sort()

if (process.argv.includes('--list')) {
  console.log(files.join('\n'))
  console.log(`${files.length} test files`)
  process.exit(0)
}
if (files.length === 0) {
  console.error('No test files found.')
  process.exit(1)
}
const env = slow ? {...process.env, KEEPFIN_SCALE: FULL_SCALE, KEEPFIN_MERGE_SCALE: FULL_SCALE} : process.env
console.log(`Running ${files.length} test files${slow ? ` at the full scale of ${FULL_SCALE} items` : ''}`)
const result = spawnSync('npx', ['tsx', '--test', ...files], {cwd: root, env, stdio: 'inherit', shell: process.platform === 'win32'})
process.exit(result.status ?? 1)
