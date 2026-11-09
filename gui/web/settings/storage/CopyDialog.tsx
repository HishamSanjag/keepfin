import {useEffect, useState} from 'react'
import {formatBytes} from '../../../shared/contracts'
import type {CopiesState, CopyHolds, CopyPlaceView, CopyVersion} from '../../../shared/copies'
import {messageOf} from '../../bridge'
import {plural, relativeTime} from '../../format'
import {Button, InlineAlert, ProgressBar, SwitchRow, Tabs} from '../../ui/controls'
import {Dialog} from '../../ui/Dialog'
import {cleanUpCopy, editCopy, listVersions, pauseCopies, removeCopy, restoreVersion, signInToCloud, syncCopies} from './copiesApi'
import {freeUpKeeps, freeUpNothing, freeUpSummary, freeUpTitle, nothingToFree as nothingToFreeOf, problemRetry, problemTitle, removeFilesStay, removeLead, removeTitle} from '../../../shared/core/copies'
import {copyFiles, dataProgressPercent, dataProgressWords, holdsText, modeWords, scheduleText, statusWords} from './copyText'
import {HoldsEditor} from './HoldsEditor'

/** Why files were not copied: each plain reason (with how many, when all are listed), the promise to try again, and the files behind a toggle. */
function ProblemDetail({problem}: {problem: NonNullable<CopyPlaceView['problem']>}) {
  const counted = problem.reasons.length > 1 && problem.more === 0
  return (
    <div className="copy-problem">
      {problem.reasons.map((reason) => (
        <p key={reason.text}>
          {reason.text}
          {counted ? <span className="muted"> ({plural(reason.count, 'file')})</span> : null}
        </p>
      ))}
      <p className="muted">{problemRetry()}</p>
      <details className="copy-problem-files">
        <summary>Show the files</summary>
        <ul>
          {problem.files.map((file) => (
            <li key={file}>{file}</li>
          ))}
        </ul>
        {problem.more > 0 && <p className="small muted">and {plural(problem.more, 'more file')}</p>}
      </details>
    </div>
  )
}

type Tab = 'overview' | 'holds' | 'extras' | 'versions'

/** Where a copy lives, in words: a cloud folder, a paired device, or a path. */
export function copyWhere(place: CopyPlaceView): string {
  if (place.cloud) return `${place.cloud.providerName} · ${place.cloud.accountLabel} · ${place.cloud.folder}`
  if (place.device) return `${place.device.deviceName} · ${place.device.online ? 'connected' : 'not connected right now'}`
  return place.path || 'Unknown'
}

/**
 * One copy, opened from Storage: what it is and how it is doing, what it holds, its extra files and (a backup) its earlier versions.
 * Sync now is the main action; Remove copy (keep its files, or move them to the Recycle Bin) and Free up space (a backup's earlier
 * versions) each ask once, in their own dialog, so only one question is ever open.
 */
export function CopyDialog({place, state, onState, onReview, onClose}: {place: CopyPlaceView; state: CopiesState; onState: (state: CopiesState) => void; onReview: () => void; onClose: () => void}) {
  const [tab, setTab] = useState<Tab>('overview')
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [holds, setHolds] = useState<CopyHolds>({...place.holds})
  const [versions, setVersions] = useState<CopyVersion[] | null>(null)
  const [confirm, setConfirm] = useState<'cleanup' | 'remove' | null>(null)
  // Sync now: when it was asked for, so a run that finishes in a second still says what it found
  const [asked, setAsked] = useState(0)
  useEffect(() => {
    if (!asked) return
    const timer = window.setTimeout(() => setAsked(0), 30_000)
    return () => window.clearTimeout(timer)
  }, [asked])
  const finished = !!asked && (place.lastVerifiedAt ?? 0) >= asked && place.status !== 'syncing'
  // Remove copy…: a copy in a folder or on a drive can take its files to the Recycle Bin; keeping them is the default
  const [withFiles, setWithFiles] = useState(false)
  const inFolder = !place.cloud && !place.device && !!place.path
  const nothingToFree = !place.versions || nothingToFreeOf(place.versions.files, place.versions.deletedFiles)
  const status = statusWords[place.status]
  const words = modeWords[place.mode]
  const syncing = place.status === 'syncing'
  const backup = place.mode === 'backup'
  const removing = place.removal?.state === 'removing'

  async function act(name: string, work: () => Promise<CopiesState | void>) {
    setBusy(name)
    setError('')
    try {
      const next = await work()
      if (next) onState(next)
      return true
    } catch (caught) {
      setError(messageOf(caught))
      return false
    } finally {
      setBusy('')
    }
  }

  function choose(next: Tab) {
    setTab(next)
    if (next === 'versions' && !versions && place.connected) void act('versions', async () => setVersions(await listVersions(place.id)))
  }

  const tabs: {value: Tab; label: string}[] = [
    {value: 'overview', label: 'Overview'},
    {value: 'holds', label: 'What it holds'},
    ...(place.exports ? [{value: 'extras' as const, label: 'Extra files'}] : []),
    ...(backup ? [{value: 'versions' as const, label: 'Earlier versions'}] : []),
  ]
  const progress = place.progress
  const files = copyFiles(place)
  const dataPercent = progress?.data ? dataProgressPercent(progress) : undefined
  const percent = dataPercent !== undefined ? dataPercent : progress && progress.totalBytes > 0 ? (progress.doneBytes / progress.totalBytes) * 100 : progress && progress.totalActions > 0 ? (progress.doneActions / progress.totalActions) * 100 : 0
  const free = place.freeBytes
  const usedPercent = place.totalBytes && free !== undefined ? ((place.totalBytes - free) / place.totalBytes) * 100 : undefined
  const holdsOk = holds.all || holds.spaces.length > 0 || holds.playlists.length > 0 || holds.favourites
  const holdsChanged = JSON.stringify(holds) !== JSON.stringify(place.holds)

  return (
    <>
      <Dialog
        className="copy-dialog"
        labelledBy="copy-dialog-title"
        onClose={onClose}
        eyebrow={
          <>
            {words.name} · <span data-tone={status.tone}>{place.status === 'waiting' && files.waiting > 0 ? `${plural(files.waiting, 'file')} waiting` : status.label}</span>
          </>
        }
        title={place.name}
        footer={
          <>
            <Button variant="ghost" icon="close" disabled={!!busy || removing} onClick={() => setConfirm('remove')}>
              Remove copy…
            </Button>
            {backup && (
              <Button variant="ghost" icon="trash" disabled={!place.connected || !!busy} onClick={() => setConfirm('cleanup')}>
                Free up space…
              </Button>
            )}
            <span className="copy-dialog-space" />
            {syncing && (
              <Button icon="pause" loading={busy === 'pause'} onClick={() => void act('pause', () => pauseCopies(place.id))}>
                Pause
              </Button>
            )}
            {place.cloud?.needsSignIn && place.cloud.provider !== 'webdav' && (
              <Button loading={busy === 'signin'} disabled={!!busy} onClick={() => void act('signin', async () => (await signInToCloud(place.cloud!.provider as 'drive' | 'graph' | 'dropbox', place.cloud!.accountId)).state)}>
                Sign in again
              </Button>
            )}
            <Button
              variant="primary"
              icon="refresh"
              disabled={!place.connected || syncing || !!busy || removing}
              loading={busy === 'sync' || syncing || (!!asked && !finished)}
              onClick={() => {
                setAsked(Date.now())
                void act('sync', () => syncCopies(place.id)).then((ok) => !ok && setAsked(0))
              }}
            >
              {place.status === 'paused' ? 'Resume' : place.status === 'preview' ? 'Check and sync' : 'Sync now'}
            </Button>
          </>
        }
      >
        <Tabs value={tab} tabs={tabs} onChange={choose} label={`About ${place.name}`} idBase="copy-dialog" />
        <div className="copy-dialog-panel" role="tabpanel" id="copy-dialog-panel" aria-labelledby={`copy-dialog-tab-${tab}`}>
          {tab === 'overview' && (
            <>
              {place.removal?.state === 'removing' && (
                <div className="copy-progress" role="status">
                  <ProgressBar value={0} label={`Removing ${place.name}`} mode="indeterminate" />
                  <span className="small muted">Removing this copy: {place.removal.text}</span>
                </div>
              )}
              {place.removal?.state === 'failed' && (
                <InlineAlert
                  tone="error"
                  title="This copy was not removed"
                  actions={
                    <Button size="sm" icon="refresh" disabled={!!busy} onClick={() => setConfirm('remove')}>
                      Try again
                    </Button>
                  }
                >
                  {place.removal.text}
                </InlineAlert>
              )}
              {finished && (
                <InlineAlert tone="info">
                  {files.waiting === 0 ? 'Synced just now: everything this copy holds is on it.' : `Synced just now. ${plural(files.waiting, 'file')} still ${files.waiting === 1 ? 'waits' : 'wait'} for the next sync.`}
                </InlineAlert>
              )}
              {place.review > 0 && (
                <button type="button" className="copy-review-link" onClick={onReview}>
                  <span>{place.review === 1 ? '1 file on this copy needs a decision.' : `${place.review.toLocaleString()} files on this copy need a decision.`}</span>
                  <span className="copy-review-link-action">Review…</span>
                </button>
              )}
              {place.problem && !syncing && (
                <InlineAlert
                  tone="warning"
                  title={problemTitle(place.problem.count)}
                  actions={
                    <Button size="sm" icon="refresh" disabled={!place.connected || !!busy} onClick={() => void act('sync', () => syncCopies(place.id))}>
                      Try again
                    </Button>
                  }
                >
                  <ProblemDetail problem={place.problem} />
                </InlineAlert>
              )}
              {place.statusText && !syncing && place.status !== 'upToDate' && place.status !== 'problem' && <InlineAlert tone={place.status === 'refused' ? 'error' : 'warning'}>{place.statusText}</InlineAlert>}
              <p className="small muted">{words.short}</p>
              <dl className="copy-facts">
                <div>
                  <dt>Where</dt>
                  <dd className="setup-path">{copyWhere(place)}</dd>
                </div>
                <div>
                  <dt>Holds</dt>
                  <dd>{holdsText(place.holds, state.rows)}</dd>
                </div>
                <div>
                  <dt>Kept</dt>
                  <dd className="tabular">
                    {plural(files.kept, 'file')} · {files.waiting.toLocaleString()} waiting
                  </dd>
                </div>
                <div>
                  <dt>Last checked</dt>
                  <dd>{place.lastVerifiedAt ? relativeTime(place.lastVerifiedAt) : 'Not yet'}</dd>
                </div>
                <div>
                  <dt>Syncs</dt>
                  <dd>{scheduleText(place)}</dd>
                </div>
              </dl>
              {syncing && (
                <div className="copy-progress" role="status">
                  <ProgressBar value={percent} label={`Syncing ${place.name}`} mode={dataPercent !== undefined || (progress && !progress.data && (progress.totalActions > 0 || progress.totalBytes > 0)) ? 'determinate' : 'indeterminate'} />
                  <span className="small muted tabular">
                    {progress?.data ? dataProgressWords(progress, place.cloud?.providerName) : progress && progress.totalActions > 0 ? `${progress.doneActions.toLocaleString()} of ${plural(progress.totalActions, 'file')}` : 'Looking at both sides…'}
                    {progress?.current && !progress.data ? ` · ${progress.current.split('/').pop()}` : ''}
                  </span>
                </div>
              )}
              {usedPercent !== undefined && free !== undefined && place.totalBytes !== undefined && (
                <div className="storage-bar">
                  <ProgressBar value={usedPercent} label={`Space used on ${place.name}`} />
                  <span className="small muted tabular">
                    {formatBytes(free)} free of {formatBytes(place.totalBytes)}
                  </span>
                </div>
              )}
            </>
          )}
          {tab === 'holds' && (
            <>
              <HoldsEditor holds={holds} rows={state.rows} onChange={setHolds} label={`What ${place.name} holds`} />
              <SwitchRow
                title="Lyrics, subtitles and info files"
                description="The files beside each song and video go with it."
                checked={place.companions !== false}
                disabled={!!busy}
                onChange={(value) => void act('companions', () => editCopy(place.id, {companions: value}))}
              />
              <SwitchRow
                title="Music videos"
                description="Each song's music video goes with it."
                checked={place.musicVideos === true}
                disabled={!!busy}
                onChange={(value) => void act('musicVideos', () => editCopy(place.id, {musicVideos: value}))}
              />
              <p className="small muted">Leaving something out never deletes it from this copy or from anywhere else.</p>
              <div className="copy-actions">
                <Button variant="primary" size="sm" loading={busy === 'holds'} disabled={!holdsOk || !holdsChanged || !!busy} onClick={() => void act('holds', () => editCopy(place.id, {holds}))}>
                  Save
                </Button>
              </div>
            </>
          )}
          {tab === 'extras' && place.exports && (
            <div className="copy-exports" role="group" aria-label={`Extra files on ${place.name}`}>
              <SwitchRow title="Playlist files" description="A .m3u8 file for each playlist, in a Playlists folder, so other players can open them." checked={place.exports.playlistFiles} disabled={!!busy} onChange={(value) => void act('exports', () => editCopy(place.id, {exports: {...place.exports!, playlistFiles: value}}))} />
              <SwitchRow title="One flat folder" description="A second copy of every file in one folder, for players that cannot read folders inside folders." checked={place.exports.flat} disabled={!!busy} onChange={(value) => void act('exports', () => editCopy(place.id, {exports: {...place.exports!, flat: value}}))} />
              <SwitchRow title="MP3 versions" description="An MP3 of each song that is not an MP3, for devices that only play MP3. Each song is converted once." checked={place.exports.mp3} disabled={!!busy} onChange={(value) => void act('exports', () => editCopy(place.id, {exports: {...place.exports!, mp3: value}}))} />
              <p className="small muted">These live in "Playlists" and "Keepfin Exports" on the copy. They are never added to your library, and Keepfin removes them if you turn them off.</p>
            </div>
          )}
          {tab === 'versions' && (
            <div className="copy-versions" role="region" aria-label={`Earlier versions on ${place.name}`}>
              {!place.connected && <p className="small muted">Connect the copy to see its earlier versions.</p>}
              {place.connected && !versions && <p className="small muted">Loading…</p>}
              {versions && versions.length === 0 && <p className="small muted">No earlier versions are kept on this backup yet.</p>}
              {versions && versions.length > 0 && (
                <ul>
                  {versions.map((v) => (
                    <li key={v.id}>
                      <span className="copy-version-name">
                        {v.name}
                        <small className="muted">
                          {' '}
                          {v.deleted ? 'deleted' : 'replaced'} {relativeTime(v.archivedAt)}
                        </small>
                      </span>
                      <Button size="sm" loading={busy === `restore-${v.id}`} disabled={!!busy} onClick={() => void act(`restore-${v.id}`, () => restoreVersion(place.id, v.id))} aria-label={`Restore ${v.name}`}>
                        Restore
                      </Button>
                    </li>
                  ))}
                </ul>
              )}
              <p className="small muted">Restoring brings the version back to your library as a new file. It stays on the backup.</p>
            </div>
          )}
          {error && <InlineAlert tone="error">{error}</InlineAlert>}
        </div>
      </Dialog>
      {confirm === 'cleanup' && (
        <Dialog
          labelledBy="copy-confirm-title"
          onClose={() => setConfirm(null)}
          title={freeUpTitle(place.name)}
          footer={
            nothingToFree ? (
              <Button onClick={() => setConfirm(null)}>OK</Button>
            ) : (
              <>
                <Button onClick={() => setConfirm(null)}>Cancel</Button>
                <Button
                  variant="danger"
                  loading={busy === 'cleanup'}
                  onClick={async () => {
                    await act('cleanup', () => cleanUpCopy(place.id))
                    setConfirm(null)
                  }}
                >
                  Free up space
                </Button>
              </>
            )
          }
        >
          {nothingToFree ? (
            <p>{freeUpNothing()}</p>
          ) : (
            <>
              <p>{freeUpSummary(place.versions!.files, place.versions!.deletedFiles)}</p>
              <p>{freeUpKeeps()}</p>
            </>
          )}
        </Dialog>
      )}
      {confirm === 'remove' && (
        <Dialog
          labelledBy="copy-confirm-title"
          onClose={() => setConfirm(null)}
          title={removeTitle(place.name)}
          footer={
            <>
              <Button onClick={() => setConfirm(null)}>Cancel</Button>
              <Button
                variant="danger"
                disabled={removing}
                onClick={() => {
                  // the work goes on in the background: the copy's row says what it is doing, and the result arrives as a push
                  void removeCopy(place.id, withFiles).then(onState, () => undefined)
                  setConfirm(null)
                  onClose()
                }}
              >
                {withFiles ? 'Remove copy and its files' : 'Remove copy'}
              </Button>
            </>
          }
        >
          <p>{removeLead()}</p>
          {inFolder ? (
            <fieldset className="copy-holds">
              <legend className="sr-only">What happens to its files</legend>
              <label className="copy-choice">
                <input type="radio" name="remove-files" checked={!withFiles} onChange={() => setWithFiles(false)} />
                <span>
                  <strong>Keep its files</strong>
                  <small>They stay in {place.path}, as they are now.</small>
                </span>
              </label>
              <label className="copy-choice">
                <input type="radio" name="remove-files" checked={withFiles} onChange={() => setWithFiles(true)} />
                <span>
                  <strong>Also move its files to the Recycle Bin</strong>
                  <small>The whole folder {place.path} goes to the Recycle Bin, with everything in it. You can restore it from there.</small>
                </span>
              </label>
            </fieldset>
          ) : (
            <p>{removeFilesStay()}</p>
          )}
          {error && <InlineAlert tone="error">{error}</InlineAlert>}
        </Dialog>
      )}
    </>
  )
}
