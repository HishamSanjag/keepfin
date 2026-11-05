// "Needs your attention": one line per real problem, from state the app already has. Nothing is invented: with nothing wrong the list is empty.
import type {CopiesState} from '../../shared/copies'
import type {DevicesState} from '../../shared/devices'
import type {StorageOverview} from '../../shared/storage'
import type {UpdateState} from '../../shared/updates'
import {plural} from '../format'
import type {SettingsCategory} from './sections'

export type AttentionItem = {
  id: string
  text: string
  /** The place that fixes it. */
  category: SettingsCategory
  anchor: string
  fix: string
}

export type AttentionInput = {devices?: DevicesState; overview?: StorageOverview; copies?: CopiesState; update?: UpdateState}

export function deriveAttention({devices, overview, copies, update}: AttentionInput): AttentionItem[] {
  const items: AttentionItem[] = []
  if (devices?.available && devices.enabled && devices.firewall?.blocked) {
    items.push({
      id: 'firewall',
      text: 'Your other devices cannot reach this PC: Windows Firewall is blocking Keepfin.',
      category: 'devices',
      anchor: 'status',
      fix: 'Fix',
    })
  }
  if (devices?.available && devices.enabled && devices.pairing?.role === 'answering' && devices.pairing.stage === 'comparing') {
    items.push({id: 'pairing', text: `${devices.pairing.peerName} wants to pair with this PC.`, category: 'devices', anchor: 'add', fix: 'Review'})
  }
  if (overview && overview.health.missing > 0 && !overview.health.checking) {
    items.push({
      id: 'missing-files',
      text: `${plural(overview.health.missing, 'file')} in the Keepfin folder cannot be found.`,
      category: 'storage',
      anchor: 'files',
      fix: 'Fix',
    })
  }
  if (copies?.available) {
    const failed = copies.places.filter((place) => place.problem).length
    if (failed > 0) items.push({id: 'copy-failed', text: failed === 1 ? 'A copy could not be updated.' : `${failed} copies could not be updated.`, category: 'storage', anchor: 'copies', fix: 'Fix'})
    const review = copies.review.length + copies.reviewMore
    if (review > 0) items.push({id: 'needs-review', text: review === 1 ? '1 item needs a decision.' : `${review.toLocaleString()} items need a decision.`, category: 'storage', anchor: 'review', fix: 'Review'})
  }
  if (update?.phase === 'ready') {
    items.push({id: 'update-ready', text: `Keepfin ${update.version ?? ''} is ready. Restart to finish updating.`.replace('  ', ' '), category: 'about', anchor: 'updates', fix: 'Open'})
  }
  return items
}
