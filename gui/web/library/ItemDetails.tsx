import {useEffect, useState} from 'react'
import '../styles/where.css'
import type {WhereItemView} from '../../shared/where'
import {formatBytes, formatDuration} from '../../shared/contracts'
import {messageOf, native} from '../bridge'
import {Button} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {getWhereItem} from '../settings/where/whereApi'
import {itemWords} from '../settings/whereText'
import {closeDialog} from './dialogState'
import {getLibItem} from './libStore'

const kindName = {audio: 'Song', video: 'Video', image: 'Image', other: 'File'} as const

/**
 * Details… on a library item: what it is, how big, and where it is kept. The line "On: this PC, Pixel 9, Google Drive" (or "Only on
 * this PC") comes from the library's placements; a place that is away still counts and is marked (offline). Browser preview: no line.
 */
export function ItemDetailsDialog({id}: {id: string}) {
  const item = getLibItem(id)
  const [where, setWhere] = useState<WhereItemView | undefined>()
  const [problem, setProblem] = useState('')
  useEffect(() => {
    if (!native) return
    let live = true
    getWhereItem(id)
      .then((view) => live && setWhere(view))
      .catch((caught) => live && setProblem(messageOf(caught)))
    return () => {
      live = false
    }
  }, [id])
  if (!item) return null
  const facts: [string, string][] = [
    ['Kind', kindName[item.kind]],
    ...(item.artist ? ([['Artist', item.artist]] as [string, string][]) : []),
    ...(item.album ? ([['Album', item.album]] as [string, string][]) : []),
    ...(item.duration ? ([['Length', formatDuration(item.duration)]] as [string, string][]) : []),
    ['Size', formatBytes(item.size) || 'Unknown'],
  ]
  return (
    <Dialog
      title="Details"
      eyebrow={item.title}
      labelledBy="item-details-title"
      onClose={closeDialog}
      footer={
        <Button variant="primary" onClick={closeDialog}>
          Close
        </Button>
      }
    >
      <dl className="item-details">
        {facts.map(([name, value]) => (
          <div key={name}>
            <dt className="small muted">{name}</dt>
            <dd>{value}</dd>
          </div>
        ))}
        {native && (
          <div>
            <dt className="small muted">Where</dt>
            <dd className="where-item-line" aria-live="polite">
              {where ? itemWords(where.places) : problem ? 'Could not read where this file is.' : 'Looking…'}
            </dd>
          </div>
        )}
      </dl>
    </Dialog>
  )
}
