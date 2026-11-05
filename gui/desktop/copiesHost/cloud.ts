// Cloud accounts of the copies host (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 6): sign-in, attaching a copy that another device
// made, and the cloud side of the first-sync preview.
import fs from 'node:fs/promises'
import path from 'node:path'
import type {CloudAccountView, CloudFoundReplica, CopiesArgs, CopiesState, CopyDraft, CopyPreview} from '../../shared/copies.js'
import type {PlaceConfig} from '../sqlstate/desired.js'
import {writeRelationship} from '../copies/generation.js'
import type {RunSummary} from '../copies/placeRun.js'
import type {CloudJobPayload} from '../copiesWorker.js'
import type {AccountView} from '../cloud/accounts.js'
import {describeFailure, folderNameFor, type CloudPlaceSpec} from '../cloud/cloudPlace.js'
import {KEEPFIN_DIR} from '../cloud/transport.js'
import type {HostCore} from './core.js'
import {cloudStopped, emptyPreview, prepareCopyFolder, previewFrom} from './helpers.js'

export class CopiesCloud {
  constructor(private readonly core: HostCore) {}

  mirrorDir(placeId: string) {
    return path.join(this.core.stateDir(placeId), 'mirror')
  }

  /** Reads the accounts and their free space again; the screen shows what this last saw. */
  async refreshCloud(): Promise<void> {
    if (!this.core.cloud) return
    const list = await this.core.cloud.accounts.list().catch(() => [] as AccountView[])
    this.core.setAccountList(new Map(list.map((a) => [a.id, a])))
    const uses = new Map<string, number>()
    for (const p of this.core.snap().places) {
      const c = this.core.local().places[p.id]?.cloud
      if (c && !p.removed) uses.set(c.accountId, (uses.get(c.accountId) ?? 0) + 1)
    }
    this.core.setAccountViews(await this.core.cloud.views(uses))
  }

  /** Free space for each account, off the main path: the cards fill in when it arrives. */
  async refreshQuotas(force = false): Promise<void> {
    if (!this.core.cloud) return
    for (const id of this.core.accountList().keys()) await this.core.cloud.quota(id, force).catch(() => undefined)
    await this.refreshCloud()
    this.core.schedulePush()
  }

  private cloudSpecFor(accountId: string, folder: string): CloudPlaceSpec {
    const acct = this.core.accountList().get(accountId)
    if (!acct) throw new Error('This account is not on this computer any more.')
    if (acct.needsSignIn) throw new Error('Sign in again to use this account.')
    const provider = acct.provider
    const name = String(folder ?? '').trim()
    if (provider === 'webdav') {
      const parts = name.split('/').map((s) => s.trim()).filter(Boolean)
      if (!parts.length || parts.some((s) => s === '.' || s === '..' || s.length > 120 || /[\\\0]/.test(s))) throw new Error('Enter the name of a folder on the server, for example Keepfin.')
      return {provider, accountId, rootPath: parts.join('/'), base: acct.server}
    }
    return {provider, accountId, rootPath: `Keepfin/${folderNameFor(name)}`}
  }

  private cloudArgs(a: CopiesArgs['cloud']): NonNullable<CopiesArgs['cloud']> {
    if (!this.core.cloud) throw new Error('Cloud accounts are not available in this version of Keepfin.')
    if (!a || typeof a !== 'object') throw new Error('Invalid request.')
    return a
  }

  async cloudSignIn(raw: CopiesArgs['cloud']): Promise<{account: CloudAccountView; state: CopiesState}> {
    const a = this.cloudArgs(raw)
    const provider = a.provider
    if (provider !== 'drive' && provider !== 'graph' && provider !== 'dropbox') throw new Error('Invalid request.')
    const accountId = a.accountId ? this.core.idArg(a.accountId) : undefined
    const account = await this.core.cloud!.signIn(provider, accountId)
    await this.refreshCloud()
    if (accountId) for (const p of this.core.snap().places) if (this.core.local().places[p.id]?.cloud?.accountId === accountId) void this.core.syncPlace(p.id, {})
    void this.refreshQuotas(true).catch(() => undefined)
    return {account: this.core.accountViews().find((v) => v.id === account.id) ?? (await this.core.cloud!.viewOf(account.id, 0)), state: this.core.build()}
  }

  async cloudWebdav(raw: CopiesArgs['cloud']): Promise<{account: CloudAccountView; state: CopiesState}> {
    const a = this.cloudArgs(raw)
    const out = await this.core.cloud!.connectWebdav(String(a.url ?? ''), String(a.user ?? ''), String(a.password ?? ''))
    await this.refreshCloud()
    return {account: this.core.accountViews().find((v) => v.id === out.account.id) ?? (await this.core.cloud!.viewOf(out.account.id, 0)), state: this.core.build()}
  }

  /** Replicas of this library that the account already holds, for a second device: attach one that the library knows, or add one it does not. */
  async cloudFind(raw: CopiesArgs['cloud']): Promise<CloudFoundReplica[]> {
    const a = this.cloudArgs(raw)
    const accountId = this.core.idArg(a.accountId)
    await this.refreshCloud()
    const found = await this.core.cloud!.find(accountId, a.folder ? String(a.folder).trim() : undefined)
    return found.map((f) => {
      const place = this.core.snap().places.find((p) => !p.removed && p.replica === f.replicaId)
      return {folder: f.name, ...(place ? {placeName: place.name || 'Copy', placeId: place.id} : {})}
    })
  }

  /** Uses a copy the library already knows (made on another device) on this computer too. */
  async cloudAttach(raw: CopiesArgs['cloud']): Promise<CopiesState> {
    const a = this.cloudArgs(raw)
    const accountId = this.core.idArg(a.accountId)
    await this.refreshCloud()
    const spec = this.cloudSpecFor(accountId, String(a.folder ?? ''))
    const rig = await this.core.cloud!.rigFor(spec)
    const text = await rig.media.readSmall(`${KEEPFIN_DIR}/folder.json`)
    let replicaId = ''
    try {
      const json = JSON.parse(text ?? '') as {libraryId?: unknown; replicaId?: unknown}
      if (json.libraryId === this.core.deps.libraryId && typeof json.replicaId === 'string') replicaId = json.replicaId
    } catch {
      // not a Keepfin folder
    }
    if (!replicaId) throw new Error('There is no Keepfin copy of this library in that folder.')
    const place = this.core.snap().places.find((p) => !p.removed && p.replica === replicaId)
    if (!place) throw new Error('This copy is not in your library yet. Add it as a new copy instead.')
    if (this.core.local().places[place.id]) throw new Error('This copy is already set up on this computer.')
    const stateDir = this.core.stateDir(place.id)
    await fs.mkdir(stateDir, {recursive: true})
    await writeRelationship(stateDir, {generation: null, previewConfirmed: true})
    this.core.local().places[place.id] = {path: this.mirrorDir(place.id), cloud: spec}
    await this.core.saveLocal()
    await this.refreshCloud()
    await this.core.checkConnected(place)
    await this.core.refreshSnapshot()
    void this.core.syncPlace(place.id, {previewConfirmed: true})
    return this.core.build()
  }

  async cloudDisconnect(raw: CopiesArgs['cloud']): Promise<CopiesState> {
    const a = this.cloudArgs(raw)
    const id = this.core.idArg(a.accountId)
    for (const p of this.core.snap().places) if (this.core.local().places[p.id]?.cloud?.accountId === id) this.core.pause(p.id)
    await this.core.cloud!.disconnect(id)
    await this.refreshCloud()
    for (const p of this.core.snap().places) await this.core.checkConnected(p)
    this.core.schedulePush()
    return this.core.build()
  }

  async cloudRevoke(raw: CopiesArgs['cloud']): Promise<{revoked: boolean; note: string; url?: string; state: CopiesState}> {
    const a = this.cloudArgs(raw)
    const id = this.core.idArg(a.accountId)
    for (const p of this.core.snap().places) if (this.core.local().places[p.id]?.cloud?.accountId === id) this.core.pause(p.id)
    const out = await this.core.cloud!.revoke(id)
    await this.refreshCloud()
    for (const p of this.core.snap().places) await this.core.checkConnected(p)
    this.core.schedulePush()
    return {...out, state: this.core.build()}
  }

  async previewCloud(draft: CopyDraft, previousId?: string): Promise<CopyPreview> {
    if (!this.core.cloud || !draft.cloud) throw new Error('Cloud accounts are not available in this version of Keepfin.')
    await this.refreshCloud()
    let spec: CloudPlaceSpec
    try {
      spec = this.cloudSpecFor(draft.cloud.accountId, draft.cloud.folder)
    } catch (error) {
      return emptyPreview(previousId ?? '', 'refused', error instanceof Error ? error.message : String(error))
    }
    let entry = previousId ? this.core.pending().get(previousId) : undefined
    if (entry && (entry.cloud?.rootPath !== spec.rootPath || entry.cloud?.accountId !== spec.accountId)) {
      await this.core.cancelPreview(entry.placeId)
      entry = undefined
    }
    if (!entry) {
      const placeId = this.core.deps.newId()
      const stateDir = this.core.stateDir(placeId)
      const mirror = this.mirrorDir(placeId)
      await fs.mkdir(mirror, {recursive: true})
      let existing: {replicaId: string} | null = null
      try {
        const rig = await this.core.cloud.rigFor(spec)
        const text = await rig.media.readSmall(`${KEEPFIN_DIR}/folder.json`)
        if (text) {
          const json = JSON.parse(text) as {libraryId?: unknown; replicaId?: unknown}
          if (typeof json.libraryId === 'string' && json.libraryId !== this.core.deps.libraryId) throw new Error('This folder on the account belongs to a different Keepfin library.')
          if (typeof json.replicaId === 'string') existing = {replicaId: json.replicaId}
        }
        if (existing?.replicaId === this.core.deps.mainReplica) throw new Error('This is your Keepfin folder itself.')
        const known = existing ? this.core.snap().places.find((p) => !p.removed && p.replica === existing!.replicaId) : undefined
        if (known) throw new Error(`This is already your copy "${known.name || 'Copy'}". Use it on this computer from the account instead.`)
      } catch (error) {
        await fs.rm(stateDir, {recursive: true, force: true})
        const d = describeFailure(error)
        return emptyPreview(previousId ?? '', 'refused', error instanceof SyntaxError ? 'The Keepfin folder on the account is damaged.' : error instanceof Error && !(error as {kind?: string}).kind ? error.message : d.message)
      }
      const replica = existing ? existing.replicaId : (await prepareCopyFolder(mirror, this.core.deps.libraryId, this.core.deps.newId, new Date(this.core.deps.now()).toISOString())).replicaId
      entry = {draft, placeId, replica, created: !existing, root: mirror, cloud: spec}
      this.core.pending().set(placeId, entry)
    }
    entry.draft = draft
    const place: PlaceConfig = {...this.core.placeFrom(entry.placeId, draft, entry.replica), kind: `cloud:${spec.provider}`}
    const stateDir = this.core.stateDir(entry.placeId)
    const payload = await this.cloudPayload(spec)
    if ('summary' in payload) return previewFrom(entry.placeId, payload.summary)
    const summary = await this.core.runWorker(place, entry.root, stateDir, {dryRun: true, cloud: payload.payload})
    return previewFrom(entry.placeId, summary)
  }

  /** What the worker needs to run a cloud copy: the place and a first Authorization header. A refused sign-in ends the run here. */
  async cloudPayload(spec: CloudPlaceSpec): Promise<{payload: CloudJobPayload} | {summary: RunSummary}> {
    try {
      const auth = await this.core.cloud!.workerAuth(spec.accountId, false)
      return {payload: {spec, authorization: auth.header, expiresAtMs: auth.expiresAtMs, ...(this.core.deps.cloud?.test ? {test: this.core.deps.cloud.test} : {})}}
    } catch (error) {
      await this.refreshCloud().catch(() => undefined)
      const message = error instanceof Error ? error.message : String(error)
      const needs = (error as {kind?: string})?.kind === 'NeedsSignIn'
      return {summary: cloudStopped(needs ? 'refused' : 'disconnected', needs ? 'The sign-in for this account has expired or was withdrawn. Sign in again to continue.' : message, needs ? 'needsSignIn' : 'offline', this.core.deps.now())}
    }
  }
}
