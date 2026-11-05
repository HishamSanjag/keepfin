// What the buttons of an Activity entry do. They only use existing navigation and operations; a target that is
// gone gets a short notice instead of failing silently.
import type {ActivityActionId, ActivityEntry} from '../shared/activity'
import {navigate} from './nav'
import {act, getGroup, getJob, requestGroupFocus} from './store'
import {toast} from './toasts'
import {showCollection} from './library/collectionActions'
import {getLibrary} from './library/libStore'
import {getUpdates, openWhatsNew, runUpdate} from './updates'

const gone = (message: string) => toast({tone: 'info', message})
const collectionOf = (id: string | undefined) => (id ? getLibrary().snapshot.collections.find((entry) => entry.id === id) : undefined)

function showDownloads(filter: 'finished' | 'failed' | 'all') {
  navigate('Downloads')
  window.dispatchEvent(new CustomEvent('keepfin-downloads-filter', {detail: filter}))
}

function openGroupOrJobs(entry: ActivityEntry, filter: 'finished' | 'failed') {
  const {groupId, jobIds} = entry.target ?? {}
  if (groupId && getGroup(groupId)) {
    showDownloads(filter)
    setTimeout(() => requestGroupFocus(groupId), 80)
    return true
  }
  if (jobIds?.some((id) => getJob(id))) {
    showDownloads(filter)
    return true
  }
  return false
}

/** Runs one action; the panel closes itself for navigation actions. Returns true when it navigated away. */
export async function runActivityAction(entry: ActivityEntry, action: ActivityActionId): Promise<boolean> {
  const target = entry.target ?? {}
  switch (action) {
    case 'open': {
      if (collectionOf(target.collectionId)) {
        showCollection(target.collectionId!)
        return true
      }
      if (openGroupOrJobs(entry, 'finished')) return true
      gone(target.collectionId || target.groupId ? 'That collection is gone' : 'That download is gone')
      return false
    }
    case 'review': {
      const collection = collectionOf(target.collectionId)
      if (!collection) {
        gone('That collection is gone')
        return false
      }
      if (!collection.follow?.pending) {
        toast({tone: 'info', message: `Nothing is waiting for review in ${collection.name}.`})
        return false
      }
      showCollection(collection.id)
      return true
    }
    case 'retry': {
      const group = target.groupId && getGroup(target.groupId)
      if (group) {
        if (!group.failed) {
          toast({tone: 'info', message: 'Nothing failed there any more.'})
          return false
        }
        await act('retryGroup', {id: group.id})
        return false
      }
      const failed = (target.jobIds ?? []).filter((id) => getJob(id)?.status === 'failed')
      if (!failed.length) {
        gone((target.jobIds ?? []).some((id) => getJob(id)) ? 'Nothing failed there any more.' : 'Those downloads are gone')
        return false
      }
      for (const id of failed) await act('retry', {id})
      return false
    }
    case 'details': {
      if (openGroupOrJobs(entry, 'failed')) return true
      gone('Those downloads are gone')
      return false
    }
    case 'whats-new':
      await openWhatsNew()
      return true
    case 'install': {
      if (getUpdates().state?.phase !== 'ready') {
        toast({tone: 'info', message: 'That update is no longer waiting to install.'})
        return false
      }
      await runUpdate('updateInstall')
      const error = getUpdates().error
      if (error) toast({tone: 'error', message: error})
      return false
    }
    case 'sign-in':
      navigate('Settings', 'accounts')
      return true
  }
}
