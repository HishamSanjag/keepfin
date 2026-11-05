import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {afterEach, describe, it, mock} from 'node:test'
import {DestinationExistsError, moveNoReplace} from './fsops.js'

describe('moveNoReplace', () => {
  const dirs: string[] = []
  const dir = async () => {
    const made = await fs.mkdtemp(path.join(os.tmpdir(), 'kf-fsops-'))
    dirs.push(made)
    return made
  }
  afterEach(async () => {
    mock.restoreAll()
    await Promise.all(dirs.splice(0).map((d) => fs.rm(d, {recursive: true, force: true})))
  })

  // Google Drive for desktop (a G: drive) has no hard links and answers link() with EISDIR (seen on the owner's PC, 2026-10-24)
  it('falls back to check-and-rename when a drive answers link with EISDIR', async () => {
    const root = await dir()
    const from = path.join(root, 'song.lrc.kftmp-1')
    const to = path.join(root, 'Music', 'song.lrc')
    await fs.writeFile(from, 'lyrics')
    mock.method(fs, 'link', async () => {
      throw Object.assign(new Error("EISDIR: illegal operation on a directory, link"), {code: 'EISDIR'})
    })
    await moveNoReplace(from, to)
    assert.equal(await fs.readFile(to, 'utf8'), 'lyrics')
    await assert.rejects(fs.access(from))
  })

  it('still never replaces a file that is there when it falls back', async () => {
    const root = await dir()
    const from = path.join(root, 'new.kftmp')
    const to = path.join(root, 'old.lrc')
    await fs.writeFile(from, 'new')
    await fs.writeFile(to, 'old')
    mock.method(fs, 'link', async () => {
      throw Object.assign(new Error('EISDIR'), {code: 'EISDIR'})
    })
    await assert.rejects(moveNoReplace(from, to), DestinationExistsError)
    assert.equal(await fs.readFile(to, 'utf8'), 'old')
  })
})
