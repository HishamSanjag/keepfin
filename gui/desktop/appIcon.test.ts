import {test} from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import {applyAppIcon, iconFileFor, resolveIconPath, shortcutCandidates, updateShortcuts, type ShortcutFields} from './appIcon.js'

const exe = 'C:\\Program Files\\Keepfin\\Keepfin.exe'
const manifest = {main: 'classic', accent: '#2855CE'}
const icons = path.join('res', 'app-icons')

test('iconFileFor names the pre-rendered icon by variant and accent', () => {
  assert.equal(iconFileFor('k-fin', '#2855ce'), 'k-fin-2855CE.ico')
  assert.equal(iconFileFor('classic', '#C2410C'), 'classic-C2410C.ico')
})

test('resolveIconPath falls back to the main icon for empty or unknown ids; a different accent is one argument', () => {
  const have = new Set([path.join(icons, 'classic-2855CE.ico'), path.join(icons, 'drop-2855CE.ico'), path.join(icons, 'drop-C2410C.ico')])
  const files = {exists: (f: string) => have.has(f)}
  assert.equal(resolveIconPath(icons, manifest, 'drop', files), path.join(icons, 'drop-2855CE.ico'))
  assert.equal(resolveIconPath(icons, manifest, '', files), path.join(icons, 'classic-2855CE.ico'))
  assert.equal(resolveIconPath(icons, manifest, 'gone', files), path.join(icons, 'classic-2855CE.ico'))
  assert.equal(resolveIconPath(icons, manifest, 'drop', files, '#C2410C'), path.join(icons, 'drop-C2410C.ico'))
})

function fakeShell(links: Record<string, ShortcutFields>) {
  const writes: Array<{file: string; options: ShortcutFields}> = []
  return {
    writes,
    shell: {
      readShortcutLink: (file: string) => {
        if (!links[file]) throw new Error('no link')
        return links[file]
      },
      writeShortcutLink: (file: string, _op: 'replace', options: ShortcutFields) => {
        writes.push({file, options})
        return true
      },
    },
    files: {exists: (file: string) => file in links},
  }
}

test('shortcuts: only Keepfin\'s own are rewritten, every field is kept, missing ones are not created', () => {
  const own = {target: exe, args: '--x', description: 'Keepfin', appUserModelId: 'app.keepfin.desktop', icon: exe, iconIndex: 0}
  const foreign = {target: 'C:\\Other\\App.exe', icon: 'C:\\Other\\App.exe'}
  const fake = fakeShell({a: own, b: foreign})
  const result = updateShortcuts(fake.shell, fake.files, ['a', 'b', 'missing'], exe, 'C:\\ud\\app-icon.ico')
  assert.deepEqual(result, {updated: 1, failed: false})
  assert.equal(fake.writes.length, 1)
  assert.deepEqual(fake.writes[0], {file: 'a', options: {...own, icon: 'C:\\ud\\app-icon.ico', iconIndex: 0}})
})

test('shortcuts: a link already pointing at the kept icon is skipped at start, rewritten when the person just picked', () => {
  const kept = 'C:\\ud\\app-icon.ico'
  const fake = fakeShell({a: {target: exe, icon: kept, iconIndex: 0}})
  assert.equal(updateShortcuts(fake.shell, fake.files, ['a'], exe, kept).updated, 0)
  assert.equal(updateShortcuts(fake.shell, fake.files, ['a'], exe, kept, true).updated, 1)
})

test('shortcuts: after an update recreates them with the exe icon, the chosen icon is re-applied', () => {
  const kept = 'C:\\ud\\app-icon.ico'
  const fake = fakeShell({a: {target: exe, icon: exe, iconIndex: 0}})
  assert.equal(updateShortcuts(fake.shell, fake.files, ['a'], exe, kept).updated, 1)
})

test('shortcuts: an unreadable or unwritable link reports failure without throwing', () => {
  const fake = fakeShell({a: {target: exe}})
  fake.shell.writeShortcutLink = () => false
  assert.deepEqual(updateShortcuts(fake.shell, fake.files, ['a'], exe, 'x.ico'), {updated: 0, failed: true})
  const broken = {readShortcutLink: () => { throw new Error('bad') }, writeShortcutLink: () => true}
  assert.equal(updateShortcuts(broken, {exists: () => true}, ['a'], exe, 'x.ico').failed, true)
})

test('shortcutCandidates lists the per-user, pinned, desktop and all-users Keepfin links', () => {
  const list = shortcutCandidates({appData: 'C:\\A', desktop: 'C:\\D', programData: 'C:\\P'})
  assert.equal(list.length, 4)
  assert.ok(list.every((file) => file.endsWith('Keepfin.lnk')))
})

test('applyAppIcon copies the icon to userData, sets the window, skips shortcuts for the portable copy', () => {
  const copies: string[][] = []
  const windowIcons: string[] = []
  const fake = fakeShell({a: {target: exe}})
  const have = new Set([path.join(icons, 'classic-2855CE.ico'), path.join(icons, 'drop-2855CE.ico')])
  const deps = (shortcuts: boolean) => ({
    iconsDir: icons,
    manifest,
    userData: 'ud',
    exe,
    shortcuts,
    candidates: ['a'],
    shell: fake.shell,
    files: {exists: (f: string) => have.has(f) || f in { a: 1 }, copy: (from: string, to: string) => void copies.push([from, to])},
    setWindowIcon: (file: string) => void windowIcons.push(file),
  })
  assert.deepEqual(applyAppIcon(deps(false), 'drop', true), {applied: true, shortcutsFailed: false})
  assert.deepEqual(copies[0], [path.join(icons, 'drop-2855CE.ico'), path.join('ud', 'app-icon.ico')])
  assert.deepEqual(windowIcons, [path.join('ud', 'app-icon.ico')])
  assert.equal(fake.writes.length, 0)
  applyAppIcon(deps(true), 'nope', true)
  assert.equal(fake.writes.length, 1)
  assert.equal(copies[1][0], path.join(icons, 'classic-2855CE.ico'))
})

test('applyAppIcon leaves everything alone when no icon file exists', () => {
  const fake = fakeShell({})
  const result = applyAppIcon(
    {iconsDir: icons, manifest, userData: 'ud', exe, shortcuts: true, candidates: [], shell: fake.shell, files: {exists: () => false, copy: () => assert.fail('copied')}, setWindowIcon: () => assert.fail('set')},
    'drop',
  )
  assert.deepEqual(result, {applied: false, shortcutsFailed: false})
})
