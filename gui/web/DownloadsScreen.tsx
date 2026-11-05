import {useEffect, useRef, useState} from 'react'
import {native} from './bridge'
import {CaptureBar} from './capture/CaptureBar'
import {useCapture} from './capture/useCapture'
import {QueueList, type DownloadFilter} from './queue/QueueList'
import {History} from './queue/History'
import {useAggregate, useIndex} from './store'
import {useStoredChoice} from './library/controller'
import {PageHeader} from './shell/PageHeader'
import {PageToolbar} from './shell/PageToolbar'
import {EngineNotice} from './shell/EngineNotice'
import {UpdateBanner} from './shell/UpdateBanner'
import {Preview} from './preview/Preview'
import {InlineAlert, Segmented} from './ui/controls'

const filters = ['all', 'active', 'finished', 'failed'] as const

/** The Downloads toolbar: what to show. Only once there is something in the queue to filter. */
function Filters({value, onChange}: {value: DownloadFilter; onChange: (value: DownloadFilter) => void}) {
  const {active} = useAggregate()
  const index = useIndex()
  const failed = index.filter((entry) => entry.status === 'failed' || entry.status === 'cancelled').length
  const finished = index.filter((entry) => entry.status === 'completed').length
  if (!index.length) return null
  return (
    <PageToolbar
      label="Downloads toolbar"
      left={
        <Segmented<DownloadFilter>
          label="Show downloads"
          value={value}
          onChange={onChange}
          options={[
            {value: 'all', label: 'All'},
            {value: 'active', label: <>Active{active > 0 && <span className="seg-count tabular">{active}</span>}</>},
            {value: 'finished', label: <>Finished{finished > 0 && <span className="seg-count tabular">{finished}</span>}</>},
            {value: 'failed', label: <>Failed{failed > 0 && <span className="seg-count tabular">{failed}</span>}</>},
          ]}
        />
      }
    />
  )
}

export function DownloadsScreen({hidden}: {hidden: boolean}) {
  const [filter, setFilter] = useStoredChoice<DownloadFilter>('keepfin-downloads-filter', 'all', filters)
  useEffect(() => {
    const show = (event: Event) => setFilter((event as CustomEvent<DownloadFilter>).detail)
    window.addEventListener('keepfin-downloads-filter', show)
    return () => window.removeEventListener('keepfin-downloads-filter', show)
  }, [setFilter])
  const c = useCapture()
  // The preview opens when a link is added. Close and Esc only put it away (the link stays in the field and the review is kept);
  // Clear in the field discards it.
  const [shown, setShown] = useState(false)
  const had = useRef(false)
  useEffect(() => {
    if (c.entries.length && !had.current) setShown(true)
    if (!c.entries.length) setShown(false)
    had.current = c.entries.length > 0
  }, [c.entries.length])
  return (
    <div className="screen downloads" hidden={hidden}>
      <PageHeader title="Downloads" />
      <Filters value={filter} onChange={setFilter} />
      <UpdateBanner />
      <CaptureBar capture={c} review={{open: () => setShown(true)}} />
      {!native && (
        <InlineAlert tone="info" title="Browser preview">
          The download engine runs only in the Keepfin desktop app, so links are not inspected or saved here.
        </InlineAlert>
      )}
      <EngineNotice />
      <div className="downloads-list">
        {filter === 'finished' || filter === 'failed' ? <History only={filter} /> : <QueueList filter={filter} onFilter={setFilter} />}
      </div>
      <Preview capture={c} open={shown && !hidden} onClose={() => setShown(false)} />
    </div>
  )
}
