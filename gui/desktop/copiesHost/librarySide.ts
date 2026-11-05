// The library side of a copy: what a run asks of the library (a file only the copy has, a newer version from the copy, a
// confirmed delete), done as ordinary library operations.
import fs from 'node:fs/promises'
import path from 'node:path'
import type {PlanAction} from '../../shared/core/copies.js'
import {companionField, companionOfEntry} from '../../shared/core/copies.js'
import {mediaKindOf} from '../../shared/library.js'
import type {HostCore} from './core.js'

export class LibrarySide {
  constructor(private readonly core: HostCore) {}

  /** Moves a file of the Keepfin folder into its trash, never over another file. */
  async archiveMain(file: string, why: string): Promise<string> {
    const day = new Date(this.core.deps.now()).toISOString().slice(0, 10)
    const dir = path.join(this.core.deps.mainRoot, '.keepfin', 'trash', 'copies', day)
    await fs.mkdir(dir, {recursive: true})
    let target = path.join(dir, `${why}-${path.basename(file)}`)
    for (let n = 2; await fs.stat(target).then(() => true, () => false); n++) target = path.join(dir, `${why}-${n}-${path.basename(file)}`)
    await fs.rename(file, target)
    return target
  }

  abs(rel: string) {
    return path.join(this.core.deps.mainRoot, ...rel.split('/'))
  }

  async libraryAction(method: string, req: any): Promise<unknown> {
    switch (method) {
      case 'importNew':
        return this.importNew(req.action as PlanAction, req.stagedPath as string, req.destPath as string)
      case 'reviseFromCopy':
        return this.revise(req.action as PlanAction, req.stagedPath as string, false)
      case 'restoreEditedDeleted':
        return this.revise(req.action as PlanAction, req.stagedPath as string, true)
      case 'deleteItem':
        return this.deleteItem(req.action as PlanAction)
      default:
        throw new Error('Unknown library action.')
    }
  }

  private spaceFor(destPath: string): string | undefined {
    let best: string | undefined
    let bestLen = -1
    for (const [id, folder] of Object.entries(this.core.snap().spaceFolders)) {
      if (folder === '' || destPath === folder || destPath.startsWith(`${folder}/`)) {
        if (folder.length > bestLen) {
          best = id
          bestLen = folder.length
        }
      }
    }
    return best ?? Object.keys(this.core.snap().spaceFolders)[0]
  }

  private async currentHeads(item: string): Promise<string[]> {
    const observed = await this.core.deps.store().observed({items: [item]})
    return observed.items[item]?.heads ?? []
  }

  /** The revision op of the head with this content, so the baseline can name it. */
  private async headFor(item: string, content: string): Promise<string | null> {
    const part = await this.core.deps.store().viewsFor({all: false, items: [item], playlists: [], spaces: [], sets: [], places: [], profile: false, findings: false})
    return part.items[0]?.heads.find((h) => h.content === content)?.op ?? null
  }

  private async moveInto(staged: string, dest: string): Promise<void> {
    await fs.mkdir(path.dirname(dest), {recursive: true})
    try {
      await fs.rename(staged, dest)
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EXDEV') throw error
      await fs.copyFile(staged, dest, 1)
      await fs.rm(staged, {force: true})
    }
  }

  /** A file only the copy has: a new item, or (in a conflict) another revision of the item the plan names. */
  async importNew(action: PlanAction, stagedPath: string, destPath: string): Promise<{itemId: string; revisionOp: string | null; path: string}> {
    if (!action.contentId) throw new Error('This file has no content id.')
    const store = this.core.deps.store()
    const size = (await fs.stat(stagedPath)).size
    const dest = this.abs(destPath)
    await this.moveInto(stagedPath, dest)
    let itemId = action.itemId
    // a lyrics or subtitle file changed on both sides: the copy's version is kept beside it under its conflict name, as a file
    if (itemId && companionOfEntry(action.revisionOp)) return {itemId, revisionOp: action.revisionOp, path: destPath}
    if (itemId) {
      // another head of an existing item (a conflict): concurrent with the head already there
      await store.applyExternal([{kind: 'item.revise', body: {item: itemId, content: action.contentId, size, parents: []}}])
    } else {
      itemId = this.core.deps.newId()
      const space = this.spaceFor(destPath)
      const base = path.posix.basename(destPath)
      const dot = base.lastIndexOf('.')
      const kind = mediaKindOf(base) ?? 'other'
      const fields: Record<string, string> = {media: kind, title: dot > 0 ? base.slice(0, dot) : base}
      if (space) fields.space = space
      await store.applyExternal([
        {kind: 'item.add', body: {item: itemId, fields}},
        {kind: 'item.place', body: {item: itemId, place: this.core.deps.mainReplica, path: destPath}},
        {kind: 'item.revise', body: {item: itemId, content: action.contentId, size, parents: []}},
      ])
    }
    this.core.noteLibraryChanged()
    return {itemId, revisionOp: await this.headFor(itemId, action.contentId), path: destPath}
  }

  /** The copy's bytes become the item's next revision (or, for an item deleted meanwhile, an edit to review). */
  async revise(action: PlanAction, stagedPath: string, restoring: boolean): Promise<{revisionOp: string | null}> {
    if (!action.itemId || !action.contentId) throw new Error('This change names no item.')
    const size = (await fs.stat(stagedPath)).size
    const dest = this.abs(action.path)
    // the bytes this replaces stay in the Keepfin folder's trash: no version is lost
    if (await fs.stat(dest).then(() => true, () => false)) await this.archiveMain(dest, restoring ? 'restored' : 'replaced')
    await this.moveInto(stagedPath, dest)
    // the item's lyrics, subtitle or info file: its record changes, never the item's own content
    const suffix = companionOfEntry(action.revisionOp)
    if (suffix) {
      await this.core.deps.store().applyExternal([{kind: 'item.set', body: {item: action.itemId, field: companionField(suffix), value: {content: action.contentId, size}}}])
      this.core.noteLibraryChanged()
      return {revisionOp: action.revisionOp}
    }
    const parents = await this.currentHeads(action.itemId)
    await this.core.deps.store().applyExternal([{kind: 'item.revise', body: {item: action.itemId, content: action.contentId, size, parents}}])
    this.core.noteLibraryChanged()
    return {revisionOp: await this.headFor(action.itemId, action.contentId)}
  }

  private async deleteItem(action: PlanAction): Promise<void> {
    if (!action.itemId) throw new Error('This change names no item.')
    // lyrics deleted on the copy and confirmed: the item keeps its other files and stays in the library
    const suffix = companionOfEntry(action.revisionOp)
    if (suffix) {
      await this.core.deps.store().applyExternal([{kind: 'item.set', body: {item: action.itemId, field: companionField(suffix), value: null}}])
      const file = this.abs(action.path)
      if (await fs.stat(file).then(() => true, () => false)) await this.archiveMain(file, 'deleted')
      this.core.noteLibraryChanged()
      return
    }
    const observed = await this.core.deps.store().observed({items: [action.itemId]})
    const o = observed.items[action.itemId]
    await this.core.deps.store().applyExternal([{kind: 'item.delete', body: {item: action.itemId, observedTags: o?.tags ?? [], observedHeads: o?.heads ?? []}}])
    const file = this.abs(action.path)
    if (await fs.stat(file).then(() => true, () => false)) await this.archiveMain(file, 'deleted')
    this.core.noteLibraryChanged()
  }
}
