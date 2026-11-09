// Copies this version's release notes (docs/release-notes/<version>.md) to build/release-notes.md, which electron-builder
// ships as resources/release-notes.md. The app shows them as "What's new" after the version changes, with no network needed.
import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const {version} = JSON.parse(await fs.readFile(path.join(root, 'package.json'), 'utf8'))
const source = path.join(root, 'docs', 'release-notes', `${version}.md`)
const notes = await fs.readFile(source, 'utf8').catch(() => '')
if (!notes.trim()) throw new Error(`Add docs/release-notes/${version}.md before building this version.`)
await fs.mkdir(path.join(root, 'build'), {recursive: true})
await fs.writeFile(path.join(root, 'build', 'release-notes.md'), notes, 'utf8')
console.log(`Staged release notes for ${version}`)
