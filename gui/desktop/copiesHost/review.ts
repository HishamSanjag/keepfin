// What needs the person's decision: the review items of each copy, the library's conflicts, and the answers to them.
import fs from 'node:fs/promises'
import path from 'node:path'
import type {ReviewItem} from '../../shared/core/copies.js'
import {conflictPath} from '../../shared/core/copies.js'
import type {CopiesState, CopyReviewAction, CopyReviewItem, CopyReviewKind} from '../../shared/copies.js'
import type {PlaceConfig} from '../sqlstate/desired.js'
import {acceptSameDrive} from '../copies/run.js'
import {readJson, writeJsonAtomic} from '../store/atomic.js'
import {reassignReplica} from '../store/keepfinFolder.js'
import type {HostCore, Runtime} from './core.js'
import {nameOf} from './helpers.js'

export class CopiesReview {
  constructor(private readonly core: HostCore) {}

  /** A copy whose account needs a new sign-in: one item to act on, wherever the last run stopped. */
  signInReview(): CopyReviewItem[] {
    const out: CopyReviewItem[] = []
    for (const p of this.core.snap().places) {
      const c = this.core.local().places[p.id]?.cloud
      if (p.removed || !c || !this.core.accountList().get(c.accountId)?.needsSignIn) continue
      const acct = this.core.accountViews().find((a) => a.id === c.accountId)
      out.push({
        id: Buffer.from(this.ackKey(p.id, 'signIn', '')).toString('base64url'),
        placeId: p.id,
        placeName: p.name || 'Copy',
        kind: 'signIn',
        title: `Sign in to ${acct?.providerName ?? 'the account'} again`,
        detail: `${acct?.label ? acct.label + ' needs' : 'The account needs'} a new sign-in before this copy can sync. Nothing was changed, and your files are safe where they are.`,
        actions: ['signIn'],
      })
    }
    return out
  }

  private ackKey(placeId: string, kind: string, p: string | undefined) {
    return `${placeId}|${kind}|${p ?? ''}`
  }

  rebuildReview(place: PlaceConfig, rt: Runtime): void {
    const last = rt.last
    const out: CopyReviewItem[] = []
    const seen = new Set<string>()
    if (!last) {
      rt.review = out
      return
    }
    const make = (kind: CopyReviewKind, p: string | undefined, title: string, detail: string, actions: CopyReviewAction[], itemId?: string | null): void => {
      const key = this.ackKey(place.id, kind, p)
      seen.add(key)
      if (this.core.local().acks.includes(key)) return
      out.push({id: Buffer.from(key).toString('base64url'), placeId: place.id, placeName: place.name || 'Copy', kind, title, detail, ...(p ? {path: p} : {}), actions, ...(itemId ? {itemId} : {})})
    }
    if (last.generation.state === 'lower') {
      make(
        'restoredDrive',
        undefined,
        'This drive looks restored from an older backup',
        'Keepfin will not remove or replace anything on it. If it is the same drive restored from its own backup, say so. If it is a clone, treat it as a separate copy.',
        ['sameDrive', 'separateCopy'],
      )
    }
    if (last.status === 'held') {
      make('held', undefined, 'A large change is waiting for you', `${last.holdReason ?? 'Many files would be removed or replaced.'} Nothing has been changed yet.`, ['confirmHeld'])
    }
    for (const r of last.review) this.mapReview(r, make)
    // files that could not be copied are not decisions: they show as a problem on the copy (problemOf) and are tried again at every sync
    // decisions about things that no longer appear are forgotten
    this.core.local().acks = this.core.local().acks.filter((k) => !k.startsWith(`${place.id}|`) || seen.has(k))
    rt.review = out
  }

  private mapReview(r: ReviewItem, make: (kind: CopyReviewKind, p: string | undefined, title: string, detail: string, actions: CopyReviewAction[], itemId?: string | null) => void): void {
    const name = nameOf(r.path)
    switch (r.kind) {
      case 'CopyDeletedProposal':
        make('copyDeleted', r.path, `${name} was removed from the copy`, 'Keepfin copied it back. If you meant to delete it everywhere, confirm: it goes to the Keepfin trash and leaves your library.', ['confirmDelete', 'restore'], r.itemId)
        break
      case 'MissingSource':
        make('missingSource', r.path, `${name} is missing`, 'Keepfin cannot find this file in your library or on the copy. Nothing was removed.', ['dismiss'], r.itemId)
        break
      case 'MissingEverywhere':
        make('missingEverywhere', r.path, `${name} is missing everywhere`, 'The file is gone from your library and from the copy. If another copy has it, restore it from there.', ['dismiss'], r.itemId)
        break
      case 'NameCollision':
        make('nameCollision', r.path, `Two files share the name ${name}`, 'The copy has two entries that Windows treats as one name. Keepfin left both alone.', ['dismiss'], r.itemId)
        break
      case 'UnknownFile':
        make('unknownFile', r.path, `${name} is not in your library`, 'It is on the copy, but your library does not list it, so Keepfin does not copy, change or delete it.', ['dismiss'], r.itemId)
        break
      case 'KeptOnBackup':
        make('keptOnBackup', r.path, `${name} is kept on the backup`, 'It was deleted or is no longer held, so the backup keeps it until you clean up.', ['dismiss'], r.itemId)
        break
      case 'NotHeld':
        make('notHeld', r.path, `${name} is no longer held`, 'This copy no longer holds this file. It is left where it is.', ['dismiss'], r.itemId)
        break
      case 'Difference':
        make('difference', r.path, `${name} differs`, `${r.detail || 'The two versions differ.'} Confirming keeps both versions: nothing is lost.`, ['keepBoth'], r.itemId)
        break
      case 'Suspended':
        make('suspended', r.path, 'Removals are paused', r.detail || 'Keepfin could not be sure what is on the copy, so it only added files.', ['dismiss'], r.itemId)
        break
    }
  }

  conflictReview(): CopyReviewItem[] {
    const out: CopyReviewItem[] = []
    for (const c of this.core.snap().conflicts) {
      const key = this.ackKey('', 'conflict', c.id)
      if (this.core.local().acks.includes(key)) continue
      out.push({
        id: Buffer.from(key).toString('base64url'),
        placeId: '',
        placeName: 'Library',
        kind: 'conflict',
        title: `${c.title || 'A file'} was edited in two places`,
        detail: `Both versions are kept (${c.heads.length}). Choose the one to keep going forward; the other stays in the Keepfin trash.`,
        actions: ['takeMain', 'takeCopy', 'keepBoth'],
      })
    }
    return out
  }

  async resolve(reviewId: string | undefined, action: CopyReviewAction | undefined): Promise<CopiesState> {
    if (!reviewId || !action) throw new Error('Invalid request.')
    const key = Buffer.from(reviewId, 'base64url').toString()
    const [placeId, kind, ...rest] = key.split('|')
    const target = rest.join('|')
    const place = this.core.snap().places.find((p) => p.id === placeId)
    switch (action) {
      case 'confirmDelete': {
        if (!place) throw new Error('That copy is gone.')
        const file = path.join(this.core.stateDir(place.id), 'confirmed-deletes.json')
        const list = (await readJson<string[]>(file).catch(() => undefined)) ?? []
        if (!list.includes(target)) list.push(target)
        await writeJsonAtomic(file, list)
        this.core.local().acks.push(key)
        await this.core.saveLocal()
        void this.core.syncPlace(place.id, {})
        break
      }
      case 'sameDrive': {
        if (!place) throw new Error('That copy is gone.')
        await acceptSameDrive(this.core.stateDir(place.id), this.core.local().places[place.id].path)
        this.core.runtime(place.id).last = undefined
        await fs.rm(path.join(this.core.stateDir(place.id), 'last-run.json'), {force: true})
        this.core.runtime(place.id).review = []
        void this.core.syncPlace(place.id, {})
        break
      }
      case 'separateCopy': {
        if (!place) throw new Error('That copy is gone.')
        await this.forkCopy(place)
        break
      }
      case 'signIn': {
        const cloud = this.core.local().places[placeId]?.cloud
        if (!cloud || !this.core.cloud) throw new Error('That copy is gone.')
        await this.core.cloud.signIn(cloud.provider, cloud.accountId)
        await this.core.refreshCloud()
        for (const p of this.core.snap().places) if (this.core.local().places[p.id]?.cloud?.accountId === cloud.accountId) void this.core.syncPlace(p.id, {})
        void this.core.refreshQuotas(true).catch(() => undefined)
        break
      }
      case 'confirmHeld': {
        if (!place) throw new Error('That copy is gone.')
        void this.core.syncPlace(place.id, {confirmHeld: true})
        break
      }
      case 'takeMain':
      case 'takeCopy': {
        if (kind !== 'conflict') throw new Error('Invalid request.')
        await this.resolveConflict(target, action === 'takeMain' ? 'main' : 'copy')
        break
      }
      case 'keepBoth':
      case 'restore':
      case 'dismiss': {
        this.core.local().acks.push(key)
        await this.core.saveLocal()
        break
      }
      default:
        throw new Error('Invalid request.')
    }
    for (const p of this.core.snap().places) this.rebuildReview(p, this.core.runtime(p.id))
    this.core.scheduleSnapshot(300)
    return this.core.build()
  }

  private async forkCopy(place: PlaceConfig): Promise<void> {
    const local = this.core.local().places[place.id]
    const replica = this.core.deps.newId()
    await reassignReplica(local.path, replica, new Date(this.core.deps.now()).toISOString())
    await this.core.setRegisters(place.id, {replica})
    await fs.rm(this.core.stateDir(place.id), {recursive: true, force: true})
    const rt = this.core.runtime(place.id)
    rt.last = undefined
    rt.review = []
    rt.previewPending = true
    this.core.schedulePush()
  }

  /** Library-side: one revision with both heads as parents, with the chosen head's content. The other head's file goes to the trash. */
  private async resolveConflict(itemId: string, keep: 'main' | 'copy'): Promise<void> {
    const store = this.core.deps.store()
    const part = await store.viewsFor({all: false, items: [itemId], playlists: [], spaces: [], sets: [], places: [], profile: false, findings: false})
    const item = part.items[0]
    const placed = part.placements[itemId]?.[this.core.deps.mainReplica]
    if (!item || item.heads.length < 2 || typeof placed !== 'string') throw new Error('This conflict is already settled.')
    const chosen = keep === 'main' ? item.heads[0] : item.heads[item.heads.length - 1]
    const others = item.heads.filter((h) => h !== chosen)
    const abs = (p: string) => path.join(this.core.deps.mainRoot, ...p.split('/'))
    if (keep === 'copy') {
      // the chosen bytes sit at the head's conflict name; they take the placement path, the old bytes go to the trash
      const from = abs(conflictPath(placed, chosen.content))
      if (await fs.stat(from).then(() => true, () => false)) {
        await this.core.archiveMain(abs(placed), 'conflict')
        await fs.rename(from, abs(placed))
      }
    }
    for (const h of others) {
      const file = abs(conflictPath(placed, h.content))
      if (await fs.stat(file).then(() => true, () => false)) await this.core.archiveMain(file, 'conflict')
    }
    await store.applyExternal([{kind: 'item.revise', body: {item: itemId, content: chosen.content, size: chosen.size, parents: item.heads.map((h) => h.op)}}])
    this.core.noteLibraryChanged()
  }
}
