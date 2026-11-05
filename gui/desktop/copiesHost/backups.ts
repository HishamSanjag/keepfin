// Backups: Restore and Clean up.
import fs from 'node:fs/promises'
import path from 'node:path'
import type {PlanAction} from '../../shared/core/copies.js'
import {COMPANION_FIELD, companionPath, companionSuffix} from '../../shared/core/copies.js'
import type {CopiesState, CopyVersion} from '../../shared/copies.js'
import {hashFile} from '../copies/hash.js'
import {cleanUpDeleted, liveEntries, pruneVersions} from '../copies/versions.js'
import type {HostCore} from './core.js'
import {nameOf} from './helpers.js'

export class CopiesBackups {
  constructor(private readonly core: HostCore) {}

  async versions(placeId: string): Promise<CopyVersion[]> {
    const local = this.core.local().places[placeId]
    if (!local || !this.core.runtime(placeId).connected) throw new Error('Connect the copy first.')
    const out: CopyVersion[] = []
    for (const e of liveEntries(local.path, 'versions')) {
      out.push({id: e.id, name: nameOf(e.path), path: e.path, archivedAt: e.archivedAt, why: e.why, deleted: e.why === 'deleted' || e.why === 'removed'})
      if (out.length >= 500) break
    }
    return out.sort((a, b) => b.archivedAt - a.archivedAt)
  }

  /** Brings an earlier version back into the library as a new file under "Restored from backup"; the version stays on the backup. */
  async restore(placeId: string, entryId: string | undefined): Promise<CopiesState> {
    const local = this.core.local().places[placeId]
    if (!local || !this.core.runtime(placeId).connected) throw new Error('Connect the copy first.')
    let found
    for (const e of liveEntries(local.path, 'versions')) if (e.id === entryId) found = e
    if (!found) throw new Error('That version is no longer on the backup.')
    const source = path.join(local.path, '.keepfin', ...found.file.split('/'))
    const hashed = await hashFile(source)
    if (hashed.contentId !== found.contentId) throw new Error('This version on the backup does not match what Keepfin recorded. It was not restored.')
    const staging = path.join(this.core.deps.mainRoot, '.keepfin', 'staging')
    await fs.mkdir(staging, {recursive: true})
    const staged = path.join(staging, `restore-${this.core.deps.newId()}.kftmp`)
    await fs.copyFile(source, staged)
    try {
      // earlier lyrics or subtitles of a song still in the library go back beside it as its own (the current ones go to the trash)
      // (told by its name beside the item's file: the catalogue keeps the item and the path)
      if (found.itemId) {
        const part = await this.core.deps.store().viewsFor({all: false, items: [found.itemId], playlists: [], spaces: [], sets: [], places: [], profile: false, findings: false})
        const media = part.placements[found.itemId]?.[this.core.deps.mainReplica]
        const dir = (p: string) => p.slice(0, p.lastIndexOf('/') + 1)
        const suffix = typeof media === 'string' && dir(media) === dir(found.path) ? companionSuffix(nameOf(media), nameOf(found.path)) : null
        if (part.items[0]?.status === 'present' && typeof media === 'string' && suffix) {
          const at = companionPath(media, suffix)
          await this.core.revise({kind: 'ReviseFromCopy', path: at, toPath: null, itemId: found.itemId, revisionOp: COMPANION_FIELD + suffix, contentId: found.contentId, bytes: hashed.size, displacedBytes: 0, expect: null, archive: null, reason: null, review: false} as PlanAction, staged, true)
          this.core.scheduleSnapshot(300)
          return this.core.build()
        }
      }
      const stem = nameOf(found.path)
      let rel = `Restored from backup/${stem}`
      for (let n = 2; await fs.stat(this.core.abs(rel)).then(() => true, () => false); n++) {
        const dot = stem.lastIndexOf('.')
        rel = `Restored from backup/${dot > 0 ? `${stem.slice(0, dot)} (${n})${stem.slice(dot)}` : `${stem} (${n})`}`
      }
      await this.core.importNew({kind: 'ImportNew', path: found.path, toPath: rel, itemId: null, revisionOp: null, contentId: found.contentId, bytes: hashed.size, displacedBytes: 0, expect: null, archive: null, reason: null, review: false} as PlanAction, staged, rel)
    } finally {
      await fs.rm(staged, {force: true}).catch(() => undefined)
    }
    this.core.scheduleSnapshot(300)
    return this.core.build()
  }

  async cleanup(placeId: string): Promise<CopiesState> {
    const place = this.core.snap().places.find((p) => p.id === placeId && !p.removed)
    const local = this.core.local().places[placeId]
    if (!place || !local || !this.core.runtime(placeId).connected) throw new Error('Connect the copy first.')
    await cleanUpDeleted(local.path)
    await pruneVersions(local.path, this.core.deps.now(), place.options.versionsKeepDays)
    await this.core.refreshSnapshot()
    return this.core.build()
  }
}
