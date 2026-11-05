// Play from another device for one library item (the player's libraryPrepare falls back to this when the file is not on this computer):
// the item's head content id from the store, then a paired device that lets this one play its files. If none answers, the device that
// is known to offer its files but is not connected is named, so the player can say who it waited for and when it was last seen.
import type {PlayFromOutcome} from '../library/ops.js'
import type {NetService} from './service.js'

export interface HeadReader {
  viewsFor(request: {all: boolean; items: string[]; playlists: string[]; spaces: string[]; sets: string[]; places: string[]; profile: boolean; findings: boolean}): Promise<{items: {heads: {content?: string}[]}[]}> | {items: {heads: {content?: string}[]}[]}
}

export async function playFromItem(service: NetService, store: HeadReader, libraryId: string, itemId: string, fileName: string): Promise<PlayFromOutcome | undefined> {
  const part = await store.viewsFor({all: false, items: [itemId], playlists: [], spaces: [], sets: [], places: [], profile: false, findings: false})
  const content = part.items[0]?.heads[0]?.content
  if (!content) return undefined
  try {
    const played = await service.playFrom(libraryId, content, fileName)
    return {url: played.url, deviceName: played.deviceName}
  } catch {
    const unreachable = service.unreachableFor(libraryId)
    return unreachable ? {unreachable} : undefined
  }
}
