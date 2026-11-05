// Moves tracked files with `git mv` (history kept) and rewrites every reference to them, so a move never breaks a link
// or an import (dev-docs/plans/PROJECT-STRUCTURE.md, steps 2 and 5).
//
//   node scripts/dev/move-files.mjs <map.json>           dry run: prints what would move and change, changes nothing
//   node scripts/dev/move-files.mjs <map.json> --apply   does it
//
// The map is {"old/path": "new/path"}: a file, or a folder (everything under it moves). It rewrites:
// - Markdown links in .md files, `[text](relative)`, resolved from the file's old folder and rewritten for its new one;
// - relative imports and paths in code ('./x', '../y') in .ts/.tsx/.cts/.mts/.mjs/.js;
// - repository paths written anywhere (`docs/...`, `scripts/...`, `gui/...`, ...), when not part of a web address.
// References it cannot resolve on its own (a path relative to some other base, such as Gradle's rootProject) are not
// guessed; afterwards `npm run check:links`, the type check and the builds find what is left for a hand edit.
import {execFileSync} from 'node:child_process'
import {existsSync, mkdirSync, readFileSync, writeFileSync} from 'node:fs'
import path from 'node:path'

const posix = path.posix
const root = execFileSync('git', ['rev-parse', '--show-toplevel'], {encoding: 'utf8'}).trim()
const [mapFile, ...flags] = process.argv.slice(2)
if (!mapFile) {
  console.error('usage: move-files.mjs <map.json> [--apply]')
  process.exit(2)
}
const apply = flags.includes('--apply')
const map = Object.entries(JSON.parse(readFileSync(mapFile, 'utf8'))).map(([from, to]) => [trim(from), trim(to)])
function trim(p) {
  return p.replace(/\\/g, '/').replace(/^\.\//, '').replace(/\/+$/, '')
}

const tracked = execFileSync('git', ['ls-files', '-z'], {cwd: root, encoding: 'utf8'}).split('\0').filter(Boolean)
const trackedSet = new Set(tracked)
const folders = new Set(tracked.flatMap((f) => f.split('/').slice(0, -1).map((_, i, parts) => parts.slice(0, i + 1).join('/'))))

/** Where a path (file or folder) ends up, or null when nothing in the map covers it. The longest matching entry wins. */
function moved(p) {
  let best = null
  for (const [from, to] of map) {
    if ((p === from || p.startsWith(`${from}/`)) && (!best || from.length > best[0].length)) best = [from, to]
  }
  return best ? best[1] + p.slice(best[0].length) : null
}
const newPathOf = (p) => moved(p) ?? p

// every file that moves
const moves = tracked.map((f) => [f, moved(f)]).filter(([, to]) => to && to !== tracked)
for (const [from, to] of map) {
  if (!trackedSet.has(from) && !folders.has(from)) throw new Error(`map entry '${from}' is not a tracked file or folder`)
  if (trackedSet.has(to) && !moved(to)) throw new Error(`'${to}' already exists`)
}
const targets = new Map()
for (const [from, to] of moves) {
  if (targets.has(to)) throw new Error(`two files would land on '${to}' (${targets.get(to)} and ${from})`)
  if (trackedSet.has(to) && !moved(to)) throw new Error(`'${to}' already exists (moving ${from})`)
  targets.set(to, from)
}

const TEXT = /\.(md|ts|tsx|cts|mts|mjs|js|kt|kts|yml|yaml|json|html|css|txt)$/
const CODE = /\.(ts|tsx|cts|mts|mjs|js)$/
const MD_LINK = /(\]\(\s*<?)([^)\s>]+)(>?(?:\s+"[^"]*")?\s*\))/g
const REL = /(['"`])(\.\.?\/[^'"`\s]*)\1/g
const TOP = [...new Set(tracked.map((f) => f.split('/')[0]).filter((top) => folders.has(top)))]
const REPO = new RegExp(`(?<![\\w./:-])(?:${TOP.map((t) => t.replace(/[.]/g, '\\.')).join('|')})/[\\w./-]*[\\w-]`, 'g')

function safeDecode(s) {
  try {
    return decodeURI(s)
  } catch {
    return s
  }
}

// TypeScript imports name the compiled file: './x.js' is ./x.ts (or .tsx); '.mjs' and '.cjs' likewise.
const SOURCE_OF = {'.js': ['.ts', '.tsx'], '.mjs': ['.mts'], '.cjs': ['.cts'], '.jsx': ['.tsx']}

/** The tracked file a reference means, and the extension written in it ('' when it names the file exactly). */
function resolveFile(target) {
  if (trackedSet.has(target)) return {file: target, written: ''}
  const ext = posix.extname(target)
  for (const source of SOURCE_OF[ext] ?? []) {
    const file = target.slice(0, -ext.length) + source
    if (trackedSet.has(file)) return {file, written: ext}
  }
  return null
}

/**
 * A relative reference written in `fileOld`, rewritten for `fileNew` (null when it should stay as it is). In code only
 * references to files count (a bare './' in code is a string, not a path); Markdown may also link to folders.
 */
function rewriteRelative(ref, fileOld, fileNew, code = false) {
  const [pathPart, ...rest] = ref.split('#')
  if (!pathPart || /^[a-z][a-z0-9+.-]*:/i.test(pathPart) || pathPart.startsWith('/')) return null
  const target = posix.normalize(posix.join(posix.dirname(fileOld), safeDecode(pathPart))).replace(/\/+$/, '')
  const found = resolveFile(target)
  if (!found && (code || !folders.has(target))) return null // not a repository path (or already broken): left to the checks
  const real = found?.file ?? target
  const movedReal = newPathOf(real)
  const newTarget = found?.written ? movedReal.slice(0, -posix.extname(movedReal).length) + found.written : movedReal
  if (newTarget === target && fileNew === fileOld) return null
  let rel = posix.relative(posix.dirname(fileNew), newTarget) || '.'
  if (pathPart.startsWith('./') || (!rel.startsWith('.') && CODE.test(fileNew))) rel = rel.startsWith('.') ? rel : `./${rel}`
  if (pathPart.endsWith('/') && !rel.endsWith('/')) rel += '/'
  const out = [rel, ...rest].join('#')
  return out === ref ? null : out
}

const edits = []
for (const fileOld of tracked) {
  if (!TEXT.test(fileOld) || fileOld === 'package-lock.json') continue
  const source = path.join(root, fileOld)
  if (!existsSync(source)) continue
  const fileNew = newPathOf(fileOld)
  const before = readFileSync(source, 'utf8')
  let text = before
  const changes = []
  if (fileOld.endsWith('.md')) {
    text = text.replace(MD_LINK, (all, open, ref, close) => {
      const next = rewriteRelative(ref, fileOld, fileNew)
      if (next === null) return all
      changes.push(`${ref} -> ${next}`)
      return open + next + close
    })
  }
  if (CODE.test(fileOld)) {
    text = text.replace(REL, (all, quote, ref) => {
      const next = rewriteRelative(ref, fileOld, fileNew, true)
      if (next === null) return all
      changes.push(`${ref} -> ${next}`)
      return quote + next + quote
    })
  }
  text = text.replace(REPO, (ref) => {
    const clean = ref.replace(/\.+$/, '')
    if (!trackedSet.has(clean) && !folders.has(clean)) return ref
    const next = moved(clean)
    if (!next) return ref
    changes.push(`${clean} -> ${next}`)
    return next + ref.slice(clean.length)
  })
  if (text !== before) edits.push({fileOld, fileNew, text, changes})
}

console.log(`${apply ? 'Moving' : 'Would move'} ${moves.length} files; ${apply ? 'rewriting' : 'would rewrite'} references in ${edits.length} files.`)
for (const [from, to] of moves) console.log(`  mv ${from} -> ${to}`)
for (const {fileNew, changes} of edits) console.log(`  edit ${fileNew}: ${changes.length} reference(s)\n      ${changes.slice(0, 6).join('\n      ')}${changes.length > 6 ? '\n      …' : ''}`)
if (!apply) process.exit(0)

for (const [from, to] of moves) {
  mkdirSync(path.join(root, posix.dirname(to)), {recursive: true})
  execFileSync('git', ['mv', from, to], {cwd: root})
}
for (const {fileNew, text} of edits) writeFileSync(path.join(root, fileNew), text)
console.log('Done. Next: npm run check:links, npm run check, and the builds.')
