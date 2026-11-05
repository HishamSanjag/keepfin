// Check now / Review for followed collections, with a plain-language toast for each outcome. Automatic checks that
// find something while the app is open report through the same wording.
import type {FollowCheckResult} from '../../shared/library'
import {navigate} from '../nav'
import {toast} from '../toasts'
import {setLibView} from './libNav'
import {getLibItem, getLibrary, libraryAct, spaceById} from './libStore'

/** Opens a collection's detail in the Library. */
export function showCollection(id: string) {
  const collection = getLibrary().snapshot.collections.find((entry) => entry.id === id)
  const space = spaceById(collection?.space)
  navigate('Library')
  setLibView(collection && space ? {type: 'space', id: space.id, open: id} : {type: 'home'})
}

/** "song" for audio collections, "item" when anything in it is video. */
function nounOf(id: string): string {
  const collection = getLibrary().snapshot.collections.find((entry) => entry.id === id)
  const kinds = (collection?.itemIds ?? []).map((itemId) => getLibItem(itemId)?.kind)
  return kinds.some((kind) => kind && kind !== 'audio') ? 'item' : 'song'
}
const newCount = (count: number, noun: string) => `${count.toLocaleString()} new ${noun}${count === 1 ? '' : 's'}`

/** What the person should know after a check, shared by Check now, Review and automatic checks. */
export function reportCheck(id: string, name: string, result: {added: number; pending?: number; error?: string; skipped?: boolean}, reviewed = false) {
  const noun = nounOf(id)
  const show = {label: 'Show', run: () => showCollection(id)}
  if (result.error) toast({tone: 'error', message: `Couldn’t check ${name}. ${result.error}`})
  else if (result.skipped) toast({tone: 'info', message: `Your sign-in changed while checking ${name}. Check again.`})
  else if (result.pending) toast({tone: 'info', message: `${newCount(result.pending, noun)} in ${name} wait for your review.`, action: show})
  else if (result.added) toast({tone: 'success', message: reviewed ? `Downloading ${newCount(result.added, noun)} in ${name}.` : `Found ${newCount(result.added, noun)} in ${name} · Downloading`, action: show})
  else toast({tone: 'info', message: `No new ${noun}s in ${name}`})
}

function report(name: string, result: FollowCheckResult | undefined, reviewed: boolean) {
  if (result) reportCheck(result.id, name, result, reviewed)
}

export async function checkCollection(id: string, name: string): Promise<boolean> {
  const result = await libraryAct<FollowCheckResult[]>('collectionCheck', undefined, {id})
  if (!result.ok) return false
  report(name, Array.isArray(result.value) ? result.value[0] : undefined, false)
  return true
}

export async function reviewCollection(id: string, name: string): Promise<boolean> {
  const result = await libraryAct<FollowCheckResult>('collectionReview', undefined, {id})
  if (!result.ok) return false
  report(name, result.value, true)
  return true
}
