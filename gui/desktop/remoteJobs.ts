// The queue behind "Download on…" for the app (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md 6): the JobPort the network service talks to, over the
// download queue, plus the two system questions its rules need (is the connection metered, how much room is left). The queue does not
// exist yet when the network service is made, so the port is attached to it afterwards.
import {execFile} from 'node:child_process'
import fs from 'node:fs/promises'
import {folderSpaces} from '../shared/library.js'
import type {DownloadManager, RemoteJobView} from './manager.js'
import type {JobPort} from './net/jobs.js'

export class QueueJobPort implements JobPort {
  private manager?: DownloadManager
  private readonly listeners = new Set<() => void>()

  attach(manager: DownloadManager): void {
    this.manager = manager
    manager.subscribe(() => {
      for (const listener of this.listeners) listener()
    })
  }

  spaces(): {id: string; name: string}[] {
    const library = this.manager?.getSettings().library
    return library?.enabled ? folderSpaces(library.spaces).map((space) => ({id: space.id, name: space.name})) : []
  }

  async create(input: {jobId: string; url: string; format: unknown; options?: unknown; space?: string}): Promise<void> {
    if (!this.manager) throw new Error('This device is not ready to download yet.')
    await this.manager.enqueueRemote({id: input.jobId, url: input.url, format: input.format, options: input.options, space: input.space})
  }

  view(jobId: string): RemoteJobView | undefined {
    return this.manager?.remoteView(jobId)
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  async freeBytes(): Promise<number | undefined> {
    const dir = this.manager?.getSettings().outDir
    if (!dir) return undefined
    const stat = await fs.statfs(dir).catch(() => undefined)
    return stat ? Number(stat.bavail) * Number(stat.bsize) : undefined
  }
}

let cached: {at: number; value: boolean} | undefined

/**
 * True when Windows says the internet connection is metered (a cost of Fixed or Variable). Anything else, including an answer that
 * cannot be had, counts as unmetered: the person is only asked when the system clearly says so. Asked at most once a minute.
 */
export function windowsMetered(now: () => number = Date.now): Promise<boolean> {
  if (process.platform !== 'win32') return Promise.resolve(false)
  if (cached && now() - cached.at < 60_000) return Promise.resolve(cached.value)
  const script =
    "[void][Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime]; $p=[Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile(); if($p){ $p.GetConnectionCost().NetworkCostType } else { 'none' }"
  return new Promise((resolve) => {
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], {windowsHide: true, timeout: 5000}, (error, stdout) => {
      const value = !error && /^(Fixed|Variable)\s*$/m.test(String(stdout))
      cached = {at: now(), value}
      resolve(value)
    })
  })
}
