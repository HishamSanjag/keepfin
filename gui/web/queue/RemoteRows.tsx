import {useState} from 'react'
import type {RemoteJobRow} from '../../shared/devices'
import {messageOf, request} from '../bridge'
import {etaLabel, formatBytes, hostOf, speedLabel} from '../format'
import {dismissRemoteJob} from '../settings/devices/devicesApi'
import {useDevices} from '../settings/devices/useDevices'
import {getJob, useIndex} from '../store'
import {Badge, Button, InlineAlert} from '../ui/controls'

const words: Record<RemoteJobRow['state'], string> = {
  sending: 'Waiting for the device…',
  refused: 'Not accepted',
  queued: 'Waiting on the other device',
  preparing: 'Starting on the other device',
  downloading: 'Downloading on the other device',
  processing: 'Finishing on the other device',
  completed: 'Saved on the other device',
  failed: 'Failed on the other device',
  cancelled: 'Cancelled on the other device',
  paused: 'Paused on the other device',
  consent: 'Waiting for someone to allow it there',
}

const finished = (state: RemoteJobRow['state']) => state === 'completed' || state === 'failed' || state === 'cancelled' || state === 'refused'

/**
 * Downloads this device asked other devices to make ("Download on…"), shown with the downloads but not part of the local queue: the other
 * device owns the file and the work. And, for downloads another device asked this one for, the question that is waiting for an answer.
 */
export function RemoteRows() {
  const {state} = useDevices()
  const rows = state?.remoteJobs ?? []
  return (
    <>
      <AskRows />
      {rows.length > 0 && (
        <section className="queue-section" aria-label="On other devices">
          <header className="section-head">
            <h2 className="section-title">
              On other devices <span className="count tabular">{rows.length}</span>
            </h2>
          </header>
          <ul className="remote-rows">
            {rows.map((row) => (
              <RemoteRow key={row.requestId} row={row} />
            ))}
          </ul>
        </section>
      )}
    </>
  )
}

function RemoteRow({row}: {row: RemoteJobRow}) {
  const [error, setError] = useState('')
  const live = row.state === 'downloading' || row.state === 'processing'
  return (
    <li className="remote-row" data-state={row.state}>
      <span className="remote-row-title" title={row.title ?? row.url}>
        {row.title ?? hostOf(row.url)}
      </span>
      <span>
        {finished(row.state) ? (
          <Button size="sm" variant="ghost" onClick={() => void dismissRemoteJob(row.requestId).catch((caught) => setError(messageOf(caught)))}>
            Dismiss
          </Button>
        ) : (
          <Badge>{Math.round(row.percent)}%</Badge>
        )}
      </span>
      <p className="remote-row-meta small muted sep-list">
        <span>{row.deviceName}</span>
        <span>{words[row.state]}</span>
        {row.label && <span>{row.label}</span>}
        {live && row.speed ? <span className="tabular">{speedLabel(row.speed)}</span> : null}
        {live && row.eta ? <span className="tabular">{etaLabel(row.eta)}</span> : null}
        {row.totalBytes ? <span className="tabular">{formatBytes(row.totalBytes)}</span> : null}
      </p>
      {!finished(row.state) && row.state !== 'sending' && <progress value={row.percent} max={100} aria-label={`${row.title ?? 'Download'} on ${row.deviceName}`} />}
      {(row.error || error) && (
        <div style={{gridColumn: '1 / -1'}}>
          <InlineAlert tone={row.state === 'failed' || row.state === 'refused' ? 'error' : 'info'}>{row.error || error}</InlineAlert>
        </div>
      )}
    </li>
  )
}

/** Downloads another device asked this one for that wait for an answer (they are large, or the connection is metered). */
function AskRows() {
  const index = useIndex()
  const asks = index.map((entry) => getJob(entry.id)).filter((job) => !!job?.remote?.consent)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  if (!asks.length) return null
  async function answer(id: string, op: 'remoteJobAllow' | 'remoteJobDecline') {
    setBusy(id)
    setError('')
    try {
      await request({op, id})
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setBusy('')
    }
  }
  return (
    <section className="queue-section" aria-label="Waiting for your answer">
      <header className="section-head">
        <h2 className="section-title">
          Waiting for your answer <span className="count tabular">{asks.length}</span>
        </h2>
      </header>
      {error && <InlineAlert>{error}</InlineAlert>}
      {asks.map((job) => (
        <div key={job!.id} className="remote-ask" role="group" aria-label={`Allow ${job!.title}`}>
          <p>
            <strong>{job!.remote!.device}</strong> asked this computer to download <strong>{job!.title}</strong>
            {job!.remote!.consent!.bytes ? ` (${formatBytes(job!.remote!.consent!.bytes)})` : ''}.
          </p>
          <p className="small muted">
            {job!.remote!.consent!.reason === 'metered' ? 'This computer is on a metered connection, so it waits for you.' : 'It is a large download, so it waits for you.'}
          </p>
          <div className="remote-ask-actions">
            <Button size="sm" variant="primary" loading={busy === job!.id} onClick={() => void answer(job!.id, 'remoteJobAllow')}>
              Allow
            </Button>
            <Button size="sm" disabled={busy === job!.id} onClick={() => void answer(job!.id, 'remoteJobDecline')}>
              Decline
            </Button>
          </div>
        </div>
      ))}
    </section>
  )
}
