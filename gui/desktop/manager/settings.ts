// Settings, cookies and the engine's state. Moved out of manager.ts; everything it needs from the queue comes through `QueueCore`.
import fs from 'node:fs/promises'
import path from 'node:path'
import {folderSpaces} from '../../shared/library.js'
import {mergeSettings} from '../settings.js'
import type {QueueCore} from './core.js'

export class ManagerSettings {
  constructor(private readonly core: QueueCore) {}

  async updateSettings(patch: unknown) {
    const next = mergeSettings(this.core.settings(), patch)
    if (next.outDir !== this.core.settings().outDir) {
      try {
        await fs.mkdir(next.outDir, {recursive: true})
        await fs.access(next.outDir, fs.constants.W_OK)
      } catch {
        throw new Error(
          'That folder is not writable. Choose another download folder.',
        )
      }
    }
    for (const space of folderSpaces(next.library.spaces)) {
      const before = this.core.settings().library.spaces.find((item) => item.id === space.id)
      if (before?.folder === space.folder) continue
      try {
        await fs.mkdir(space.folder, {recursive: true})
        await fs.access(space.folder, fs.constants.W_OK)
      } catch {
        throw new Error(`That folder is not writable. Choose another folder for ${space.name}.`)
      }
    }
    const libraryChanged = JSON.stringify(next.library) !== JSON.stringify(this.core.settings().library)
    const cookiesChanged =
      JSON.stringify(next.cookies) !== JSON.stringify(this.core.settings().cookies)
    const proxyChanged = next.proxy !== this.core.settings().proxy
    const autoUpdateChanged =
      next.engine.autoUpdate !== this.core.settings().engine.autoUpdate
    this.core.setSettings(next)
    if (libraryChanged) this.core.options.library?.configure(next.library)
    if (cookiesChanged || proxyChanged)
      this.core.invalidateMetadata('Settings changed. Inspect the link again.')
    if (autoUpdateChanged)
      await this.core.options.engine
        .autoUpdate(next.engine.autoUpdate)
        .catch(() => {})
    await this.core.commit()
    void this.core.pump()
    return this.core.snapshot()
  }

  async importCookies(source: string) {
    if (this.core.hasRuns())
      throw new Error(
        'Wait until active downloads finish before changing cookies.',
      )
    if ((await fs.stat(source)).size > 2_000_000)
      throw new Error('Cookie file is too large.')
    const text = await fs.readFile(source, 'utf8')
    if (!/^# (Netscape HTTP Cookie File|HTTP Cookie File)/.test(text))
      throw new Error('Choose a Netscape-format cookies.txt file.')
    await fs.mkdir(path.dirname(this.core.cookieFilePath()), {recursive: true})
    await fs.writeFile(this.core.cookieFilePath(), text, {mode: 0o600})
    this.core.setCookiesFile(this.core.cookieFilePath())
    this.core.setSettings({
      ...this.core.settings(),
      cookies: {...this.core.settings().cookies, mode: 'file'},
    })
    this.core.invalidateMetadata('Cookies changed. Inspect the link again.')
    await this.core.commit()
    return this.core.snapshot()
  }

  async clearCookies() {
    if (this.core.hasRuns())
      throw new Error(
        'Wait until active downloads finish before changing cookies.',
      )
    if (this.core.cookiesFile()) await fs.rm(this.core.cookiesFile()!, {force: true})
    this.core.setCookiesFile(undefined)
    if (this.core.settings().cookies.mode === 'file')
      this.core.setSettings({
        ...this.core.settings(),
        cookies: {...this.core.settings().cookies, mode: 'none'},
      })
    this.core.invalidateMetadata('Cookies changed. Inspect the link again.')
    await this.core.commit()
    return this.core.snapshot()
  }

  engineState() {
    return this.core.options.engine.status()
  }

  async engineUpdate() {
    const status = await this.core.options.engine.update()
    this.core.touch()
    return status
  }
}
