// Fails when a link points at a file that does not exist, so moving or renaming docs can't silently break them
// (dev-docs/plans/PROJECT-STRUCTURE.md, step 1). Checks two kinds:
// - Markdown links in tracked .md files, `[text](relative/path)`, resolved from the file's folder;
// - repository paths into docs/ and content/ written anywhere (code comments, Gradle, workflows), such as `dev-docs/plans/<plan>.md`.
// Web links, mail links, in-page anchors and paths with placeholders (<version>, $VERSION, ${...}, *) are not checked.
import {execFileSync} from 'node:child_process'
import {existsSync, readFileSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const TEXT = /\.(md|ts|tsx|cts|mts|mjs|js|kt|kts|yml|yaml|json|html|css)$/
const MD_LINK = /\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g
const DOCS_PATH = /(?<![\w./-])(?:docs|content)\/[\w.\/-]*[\w-]\.(?:md|json|png|jpe?g|gif|webp|svg|html|txt)\b/g
const PLACEHOLDER = /[<>$*{}]/

const tracked = execFileSync('git', ['ls-files', '-z'], {cwd: root, encoding: 'utf8'}).split('\0').filter(Boolean)
// A link target must be published: tracked, or new and not ignored. Local-only files (dev-docs/, CLAUDE.local.md) exist on
// the owner's disk but not in the public repository, so a link to them counts as broken.
const untracked = execFileSync('git', ['ls-files', '-z', '--others', '--exclude-standard'], {cwd: root, encoding: 'utf8'}).split('\0').filter(Boolean)
const published = new Set([...tracked, ...untracked].filter((rel) => existsSync(path.join(root, rel))))
const folders = new Set([...published].flatMap((rel) => rel.split('/').slice(0, -1).map((_, i, parts) => parts.slice(0, i + 1).join('/'))))
const exists = (rel) => {
  const clean = rel.replace(/\/+$/, '')
  return published.has(clean) || folders.has(clean)
}

const problems = []
for (const file of tracked) {
  if (!TEXT.test(file) || file === 'package-lock.json' || file.startsWith('fixtures/')) continue
  if (!exists(file)) continue // deleted in the working tree, not yet committed
  const text = readFileSync(path.join(root, file), 'utf8')
  const lineOf = (index) => text.slice(0, index).split('\n').length
  if (file.endsWith('.md')) {
    for (const match of text.matchAll(MD_LINK)) {
      const target = decodeURI(match[1].split('#')[0])
      if (!target || /^[a-z][a-z0-9+.-]*:/i.test(target) || target.startsWith('//') || PLACEHOLDER.test(target)) continue
      const resolved = target.startsWith('/')
        ? target.slice(1)
        : path.posix.normalize(path.posix.join(path.posix.dirname(file), target))
      if (!exists(resolved)) problems.push(`${file}:${lineOf(match.index)}  link to '${match[1]}' (no such file)`)
    }
  }
  for (const match of text.matchAll(DOCS_PATH)) {
    if (!exists(match[0])) problems.push(`${file}:${lineOf(match.index)}  mentions '${match[0]}' (no such file)`)
  }
}

if (problems.length > 0) {
  console.error(`Links: ${problems.length} broken\n${problems.join('\n')}`)
  process.exit(1)
}
console.log('Links: ok')
