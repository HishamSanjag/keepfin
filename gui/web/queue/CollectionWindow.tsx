import {Fragment, useEffect} from 'react'
import {useGroup} from '../store'
import {Dialog} from '../ui/Dialog'
import {IconButton} from '../ui/controls'
import {GroupRow, groupSections} from './GroupRow'
import {JobRow} from './JobRow'
import '../styles/collection-window.css'

const noChildren = () => null

/**
 * A collection from the Downloads grid, in the same window as the download preview (it borrows that window's size and head):
 * the summary row with its actions, then every item under its section label, scrolling inside the window.
 * Both parts subscribe to the live queue, so a retry or a finished item shows without reopening; it closes itself when the collection is gone.
 */
export function CollectionWindow({id, ids, onClose}: {id: string; ids?: string[]; onClose: () => void}) {
  const stats = useGroup(id)
  useEffect(() => {
    if (!stats) onClose()
  }, [stats, onClose])
  if (!stats) return null
  const title = stats.group.title
  const titleId = 'collection-title-' + id
  return (
    <Dialog
      title={title}
      labelledBy={titleId}
      onClose={onClose}
      className="preview collection-window"
      header={
        <header className="preview-head">
          <div className="preview-title">
            <h2 id={titleId}>{title}</h2>
          </div>
          <IconButton icon="close" label="Close" onClick={onClose} />
        </header>
      }
    >
      <div className="preview-scroll">
        <ul className="collection-summary" aria-label="Collection actions and summary">
          <GroupRow id={id} mode="queue" detail renderChild={noChildren} />
        </ul>
        <ul className="collection-items" aria-label={'Items in ' + title}>
          {groupSections(stats, ids).map((section) => (
            <Fragment key={section.key}>
              <li className={'group-sub group-sub-' + section.key} role="presentation">
                {section.title} · {section.ids.length}
              </li>
              {section.ids.map((jobId) => (
                <JobRow key={jobId} id={jobId} />
              ))}
            </Fragment>
          ))}
        </ul>
      </div>
    </Dialog>
  )
}
