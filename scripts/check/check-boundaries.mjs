// Keeps the layers apart (dev-docs/plans/PROJECT-STRUCTURE.md, "The rules every part follows"): the screens never reach into
// the engine, shared code never reaches into an app, only main.ts and the preload talk to Electron, and only the core
// adapters import the generated core bundle. Tests may use Node freely, so they are not checked.
import {readFileSync, readdirSync} from 'node:fs'
import {builtinModules} from 'node:module'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const NODE = new Set(builtinModules.flatMap((name) => [name, `node:${name}`]))
const SOURCE = /\.(ts|tsx|cts|mts)$/
const TEST = /\.test\.tsx?$/

/** Each rule: which files it covers, and what an import specifier (with the file's folder) must not be. */
const RULES = [
  {
    name: 'the screens (gui/web) never import the engine, Electron or Node',
    covers: (file) => file.startsWith('gui/web/'),
    forbidden: (spec, resolved) =>
      resolved?.startsWith('gui/desktop/') || spec === 'electron' || NODE.has(spec) ? true : false,
  },
  {
    name: 'shared code (gui/shared) never imports an app or Electron',
    covers: (file) => file.startsWith('gui/shared/'),
    forbidden: (spec, resolved) =>
      resolved?.startsWith('gui/desktop/') || resolved?.startsWith('gui/web/') || spec === 'electron' ? true : false,
  },
  {
    name: 'only gui/desktop/main.ts and preload.cts import Electron',
    covers: (file) => file.startsWith('gui/desktop/') && !['gui/desktop/main.ts', 'gui/desktop/preload.cts'].includes(file),
    forbidden: (spec) => spec === 'electron' || spec.startsWith('electron/'),
  },
  {
    name: 'only the adapters in gui/shared/core import the generated core bundle (build/core-js)',
    covers: (file) => file.startsWith('gui/') && !file.startsWith('gui/shared/core/'),
    forbidden: (spec, resolved) => (resolved?.startsWith('build/core-js/') ? true : false),
  },
]

const IMPORT = /(?:\bfrom\s*|\bimport\s*\(\s*|\brequire\s*\(\s*|^\s*import\s+)['"]([^'"]+)['"]/gm

function files(dir, out = []) {
  for (const entry of readdirSync(path.join(root, dir), {withFileTypes: true})) {
    const rel = `${dir}/${entry.name}`
    if (entry.isDirectory()) files(rel, out)
    else if (SOURCE.test(entry.name) && !TEST.test(entry.name)) out.push(rel)
  }
  return out
}

const problems = []
for (const file of files('gui')) {
  const rules = RULES.filter((rule) => rule.covers(file))
  if (rules.length === 0) continue
  const text = readFileSync(path.join(root, file), 'utf8')
  for (const match of text.matchAll(IMPORT)) {
    const spec = match[1]
    const resolved = spec.startsWith('.')
      ? path.posix.normalize(path.posix.join(path.posix.dirname(file), spec))
      : undefined
    for (const rule of rules) {
      if (rule.forbidden(spec, resolved)) {
        const line = text.slice(0, match.index).split('\n').length
        problems.push(`${file}:${line}  imports '${spec}'  (rule: ${rule.name})`)
      }
    }
  }
}

if (problems.length > 0) {
  console.error(`Layer boundaries: ${problems.length} problem(s)\n${problems.join('\n')}`)
  process.exit(1)
}
console.log('Layer boundaries: ok')
