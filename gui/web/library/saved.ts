// One "Saved N items to Music" notice when the whole queue is done, not one per file. It can be closed, hides itself
// after a few seconds, and a newer one replaces the older.
import type {Job} from '../../shared/contracts'
import {plural} from '../format'
import {navigate} from '../nav'
import {playItem} from '../player/play'
import {toast} from '../toasts'
import {setLibView} from './libNav'
import {getLibrary, spaceById} from './libStore'

type Saved = {jobId: string; space: string}
let pending: Saved[] = []

/** Remembers a finished download; nothing is shown until the queue drains (see flushSaved). */
export function notifySaved(job: Job) {
  const space = spaceById(job.space)
  if (!space || job.existing) return
  if (!pending.some((entry) => entry.jobId === job.id)) pending.push({jobId: job.id, space: space.id})
}

const showSpace = (id: string) => {
  setLibView({type: 'space', id})
  navigate('Library')
}

/** Called when no download is left in progress: turns everything saved since the last notice into one toast. */
export function flushSaved() {
  if (!pending.length) return
  const saved = pending
  pending = []
  const spaces = [...new Set(saved.map((entry) => entry.space))]
  const only = spaces.length === 1 ? spaceById(spaces[0]) : undefined
  const where = only ? ` to ${only.name}` : ''
  const single = saved.length === 1 ? saved[0] : undefined
  toast({
    tone: 'success',
    key: 'saved',
    duration: 6000,
    message: single ? `Saved${where}` : `Saved ${plural(saved.length, 'item')}${where}`,
    action: single
      ? {
          label: 'Play',
          run: () => {
            const item = getLibrary().snapshot.items.find((entry) => entry.jobId === single.jobId)
            if (item) playItem(item, [item])
            else showSpace(single.space)
          },
        }
      : undefined,
    secondary: only ? {label: 'Show in Library', run: () => showSpace(only.id)} : undefined,
  })
}
