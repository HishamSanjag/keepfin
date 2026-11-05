import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {syncMusicShortcut, type ShortcutDetails, type ShortcutShell} from './musicShortcut.js'

async function setup() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-shortcut-'))
  const userMusic = path.join(dir, 'UserMusic')
  const folder = path.join(dir, 'Keepfin')
  await fs.mkdir(userMusic, {recursive: true})
  await fs.mkdir(path.join(folder, 'Music'), {recursive: true})
  const links = new Map<string, ShortcutDetails>()
  const calls: string[] = []
  const shell: ShortcutShell = {
    writeShortcutLink(file, op, details) {
      calls.push(op)
      links.set(file, details)
      require_write(file)
      return true
    },
    readShortcutLink(file) {
      const d = links.get(file)
      if (!d) throw new Error('missing')
      return d
    },
  }
  const writes: Promise<void>[] = []
  function require_write(file: string) {
    // The fake shell leaves a real file so the module's existence checks work.
    writes.push(fs.writeFile(file, 'lnk'))
  }
  const run = async (enabled: boolean, extra: Partial<Parameters<typeof syncMusicShortcut>[0]> = {}) => {
    const result = await syncMusicShortcut({enabled, keepfinMusicDir: path.join(folder, 'Music'), userMusicDir: userMusic, shell, platform: 'win32', ...extra})
    await Promise.all(writes)
    return result
  }
  return {dir, userMusic, folder, links, calls, run, link: path.join(userMusic, 'Keepfin.lnk')}
}

test('creates, leaves unchanged, updates a different target, and removes its own link', async () => {
  const t = await setup()
  assert.equal(await t.run(true), 'created')
  assert.deepEqual(t.links.get(t.link), {target: path.join(t.folder, 'Music'), description: 'Your Keepfin music'})
  assert.equal(await t.run(true), 'unchanged')
  assert.equal(await t.run(true, {keepfinMusicDir: path.join(t.dir, 'Moved', 'Music')}), 'updated')
  assert.equal(t.calls.join(), 'create,replace')
  assert.equal(await t.run(true, {keepfinMusicDir: path.join(t.dir, 'Moved', 'Music')}), 'unchanged')
  assert.equal(await t.run(false, {keepfinMusicDir: path.join(t.dir, 'Moved', 'Music')}), 'removed')
  await assert.rejects(fs.stat(t.link))
  assert.equal(await t.run(false), 'absent')
})

test('disabling leaves a link that points somewhere else, and other files, alone', async () => {
  const t = await setup()
  const other = path.join(t.dir, 'Elsewhere', 'Songs')
  await fs.mkdir(other, {recursive: true})
  t.links.set(t.link, {target: other})
  await fs.writeFile(t.link, 'lnk')
  await fs.writeFile(path.join(t.userMusic, 'song.mp3'), 'x')
  assert.equal(await t.run(false), 'absent')
  assert.equal(await fs.readFile(t.link, 'utf8'), 'lnk')
  assert.equal((await fs.readdir(t.userMusic)).sort().join(), 'Keepfin.lnk,song.mp3')
})

test('a link into another Keepfin folder (Music beside .keepfin) counts as ours', async () => {
  const t = await setup()
  const old = path.join(t.dir, 'OldFolder')
  await fs.mkdir(path.join(old, 'Music'), {recursive: true})
  await fs.mkdir(path.join(old, '.keepfin'), {recursive: true})
  t.links.set(t.link, {target: path.join(old, 'Music')})
  await fs.writeFile(t.link, 'lnk')
  assert.equal(await t.run(false), 'removed')
})

test('non-Windows does nothing', async () => {
  const t = await setup()
  assert.equal(await t.run(true, {platform: 'linux'}), 'unsupported')
  assert.equal(t.calls.length, 0)
  await assert.rejects(fs.stat(t.link))
})
