import type {AppUpdater} from 'electron-updater'
import {CancellationToken} from 'builder-util-runtime'
import {compareReleases, releasePage, type UpdateChannel, type UpdateState} from '../shared/updates.js'

export function updateErrorMessage(error: unknown): string {
  const detail = String(error)
  if (/404/.test(detail)) {
    if (/latest[^\s]*\.yml/i.test(detail))
      return 'This release is missing its update metadata. Open Releases or ask the publisher to include latest.yml.'
    return 'The release feed is not publicly accessible. A private repository or unpublished release can cause this. Updates need public downloads or a configured sign-in service.'
  }
  if (/403|429/.test(detail))
    return 'GitHub denied this request or limited update checks. Try again later, or open Releases.'
  if (/ENOTFOUND|ETIMEDOUT|ECONN|network|socket/i.test(detail))
    return 'Could not reach the update server. Check your connection and try again.'
  return 'Could not complete the update. Try again, or open Releases to download it.'
}

/** Which copy of Keepfin runs. Only the Setup edition leaves its uninstaller beside Keepfin.exe; an MSI install does not. */
export function updateMode(copy: {packaged: boolean; platform: string; portableFile?: string; setupUninstaller: () => boolean}): UpdateState['mode'] {
  if (!copy.packaged) return 'development'
  if (copy.portableFile) return 'portable'
  if (copy.platform !== 'win32') return 'unsupported'
  return copy.setupUninstaller() ? 'installed' : 'msi'
}

/** Main-process owner: renderer can request actions, never supply a feed or executable. */
export class DesktopUpdates {
  private readonly updater: AppUpdater
  private token?: CancellationToken
  private running = false
  private recheck = false
  private state: UpdateState
  constructor(
    private readonly options: {
      updater: AppUpdater
      current: string
      channel?: UpdateChannel
      mode: UpdateState['mode']
      hasDownloads: () => boolean
      openReleases: (url: string) => Promise<void>
      fetch?: typeof fetch
      /** An update finished downloading and waits for a restart. */
      onReady?: (version: string) => void
    },
  ) {
    this.updater = options.updater
    this.state = {current: options.current, channel: 'stable', mode: options.mode, phase: 'idle'}
    this.updater.autoDownload = false
    this.updater.autoInstallOnAppQuit = false
    this.applyChannel(options.channel ?? 'stable')
    this.updater.on('update-available', (info) => {
      // Never offer anything that is not strictly newer (belt and braces on top of allowDowngrade = false).
      if (compareReleases(info.version, this.options.current) <= 0) {
        this.state = {...this.state, phase: 'current', message: undefined}
        return
      }
      const notes =
        typeof info.releaseNotes === 'string'
          ? info.releaseNotes
          : info.releaseNotes?.map((n) => n.note).join('\n')
      this.state = {
        ...this.state,
        phase: 'available',
        version: info.version,
        notes: notes?.slice(0, 20000),
        notesVersion: info.version,
        message: undefined,
      }
    })
    this.updater.on('update-not-available', () => {
      this.state = {...this.state, phase: 'current', message: undefined}
    })
    this.updater.on('download-progress', (p) => {
      this.state = {...this.state, phase: 'downloading', percent: p.percent}
    })
    this.updater.on('update-downloaded', (info) => {
      this.state = {...this.state, phase: 'ready', percent: 100}
      const version = info?.version ?? this.state.version
      if (version) this.options.onReady?.(version)
    })
    this.updater.on('error', (error) => this.fail(error))
  }
  /**
   * Stable: GitHub's latest release only (allowPrerelease off). Beta: the provider also considers
   * pre-releases and takes the newest; their metadata is latest.yml (publish.channel is 'latest'), which it
   * falls back to after a beta.yml miss. `updater.channel` is deliberately never set: its setter flips
   * allowDowngrade on, and a beta build would then look for beta.yml. Downgrades stay off either way.
   */
  private applyChannel(channel: UpdateChannel) {
    this.updater.allowPrerelease = channel === 'beta'
    this.updater.allowDowngrade = false
    this.state = {...this.state, channel}
  }

  /** Switches channel and starts a fresh check; nothing found under the other channel carries over. */
  async setChannel(channel: UpdateChannel) {
    if (channel === this.state.channel) return
    this.token?.cancel()
    this.applyChannel(channel)
    this.state = {
      ...this.state,
      phase: 'idle',
      version: undefined,
      notes: undefined,
      notesVersion: undefined,
      percent: undefined,
      message: undefined,
      checkedAt: undefined,
    }
    if (this.state.mode !== 'installed') return
    if (this.running) this.recheck = true
    else await this.action('updateCheck')
  }

  snapshot() {
    return {...this.state}
  }
  private fail(error: unknown) {
    if (this.state.phase === 'ready') this.running = false
    this.state = {
      ...this.state,
      phase: 'error',
      message: updateErrorMessage(error),
    }
  }
  async action(action: string): Promise<UpdateState> {
    if (action === 'updateState') return this.snapshot()
    if (action === 'updateNotes') {
      const version = this.state.version || this.state.current
      if (this.state.notes && this.state.notesVersion === version)
        return this.snapshot()
      const response = await (this.options.fetch || fetch)(
        `https://api.github.com/repos/HishamSanjag/keepfin/releases/tags/v${encodeURIComponent(version)}`,
        {
          headers: {Accept: 'application/vnd.github+json'},
          signal: AbortSignal.timeout(15000),
        },
      )
      if (!response.ok)
        throw new Error(
          'Release notes could not be loaded. Try again or open Releases.',
        )
      const release = (await response.json()) as {body?: unknown}
      if ((this.state.version || this.state.current) !== version)
        return this.snapshot()
      this.state = {
        ...this.state,
        notes:
          typeof release.body === 'string' ? release.body.slice(0, 20000) : '',
        notesVersion: version,
      }
      return this.snapshot()
    }
    if (action === 'updateReleases') {
      await this.options.openReleases(releasePage)
      return this.snapshot()
    }
    if (action === 'updateCancel') {
      this.token?.cancel()
      return this.snapshot()
    }
    if (this.state.mode !== 'installed')
      throw new Error(
        'In-app installation requires the Windows Setup edition. Open Releases for portable or other builds.',
      )
    if (action === 'updateInstall') {
      if (this.state.phase !== 'ready' || this.running)
        throw new Error('Download the update first.')
      if (this.options.hasDownloads())
        throw new Error(
          'Finish or cancel queued downloads before restarting to update.',
        )
      this.running = true
      try {
        // The application's before-quit handler saves/closes the queue. Closing
        // it here would disable downloads if the installer fails to launch.
        // Silent: an update replaces the installed copy in place without the installer's pages, then opens Keepfin again.
        this.updater.quitAndInstall(true, true)
      } catch (error) {
        this.running = false
        throw error
      }
      return this.snapshot()
    }
    if (this.running) return this.snapshot()
    if (
      action === 'updateCheck' &&
      ['ready', 'downloading'].includes(this.state.phase)
    )
      return this.snapshot()
    this.running = true
    try {
      if (action === 'updateCheck') {
        this.state = {
          ...this.state,
          phase: 'checking',
          version: undefined,
          notes: undefined,
          notesVersion: undefined,
          message: undefined,
          checkedAt: Date.now(),
        }
        await this.updater.checkForUpdates()
      } else if (action === 'updateDownload') {
        if (
          !['available', 'error'].includes(this.state.phase) ||
          !this.state.version
        )
          throw new Error('Check for updates first.')
        this.state = {
          ...this.state,
          phase: 'downloading',
          percent: 0,
          message: undefined,
        }
        this.token = new CancellationToken()
        await this.updater.downloadUpdate(this.token)
      } else throw new Error('Unknown update action.')
    } catch (error) {
      if (this.token?.cancelled)
        this.state = {
          ...this.state,
          phase: 'available',
          percent: undefined,
          message: 'Download cancelled.',
        }
      else this.fail(error)
    } finally {
      this.running = false
      this.token = undefined
    }
    if (this.recheck) {
      this.recheck = false
      return this.action('updateCheck')
    }
    return this.snapshot()
  }
}
