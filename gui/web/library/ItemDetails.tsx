import {useEffect, useState} from 'react'
import '../styles/where.css'
import type {WhereItemView} from '../../shared/where'
import {formatBytes, formatDuration} from '../../shared/contracts'
import type {FileDetails} from '../../shared/library'
import {messageOf, native, request} from '../bridge'
import {Button} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {getWhereItem} from '../settings/where/whereApi'
import {itemWords} from '../settings/whereText'
import {closeDialog} from './dialogState'
import {technicalFacts} from './itemFacts'
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
  // The file's own facts (resolution, codecs, bitrate): one quick read of this one file, only for what has a picture or a sound.
  const [file, setFile] = useState<FileDetails | undefined>()
  const readable = item?.kind === 'video' || item?.kind === 'audio'
  useEffect(() => {
    if (!native || !readable) return
    let live = true
    request<FileDetails>({op: 'libraryDetails', id})
      .then((read) => live && setFile(read))
      .catch(() => {})
    return () => {
      live = false
    }
  }, [id, readable])
  if (!item) return null
  const facts: [string, string][] = [
    ['Kind', kindName[item.kind]],
    ...(item.artist ? ([['Artist', item.artist]] as [string, string][]) : []),
    ...(item.album ? ([['Album', item.album]] as [string, string][]) : []),
    ...(item.duration ? ([['Length', formatDuration(item.duration)]] as [string, string][]) : []),
    ['Size', formatBytes(item.size) || 'Unknown'],
    ...technicalFacts(item, file),
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
