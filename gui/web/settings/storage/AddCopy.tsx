import {useEffect, useRef, useState} from 'react'
import {formatBytes} from '../../../shared/contracts'
import type {CloudAccountView, CopiesState, CopyDraft, CopyFolderCheck, CopyHolds, CopyMode, CopyPreview} from '../../../shared/copies'
import {messageOf} from '../../bridge'
import {plural} from '../../format'
import {keepfinFolderFor} from '../../../shared/core/spaces'
import {cloudNames, StepHeading, StepIndicator} from '../../setup/FolderStep'
import {isDriveRoot} from '../../setup/folderPlace'
import {Button, InlineAlert, Segmented, Select, Spinner, SwitchRow} from '../../ui/controls'
import {Dialog} from '../../ui/Dialog'
import {CloudStep} from './CloudStep'
import {DeviceStep} from './DeviceStep'
import {addCopy, cancelCopyPreview, checkCopyFolder, pickCopyFolder, previewCopy} from './copiesApi'
import {modeWords, scheduleOptions} from './copyText'
import {HoldsEditor} from './HoldsEditor'

const steps = ['Where', 'What it holds', 'How it syncs', 'Check and add']
const FIRST_SYNC = 3

/** How each cloud app keeps a folder on this PC, in its own words. */
const keepOffline: Record<string, string> = {
  'google-drive': 'In File Explorer, right-click the folder › Offline access › Available offline.',
  onedrive: 'In File Explorer, right-click the folder › Always keep on this device.',
  dropbox: 'In File Explorer, right-click the folder › Make available offline.',
  icloud: 'In File Explorer, right-click the folder › Always keep on this device.',
}

/**
 * Adding a copy, in a dialog of four steps. The last one is a real plan made by the same engine that will run it: nothing is
 * written to the drive except the Keepfin folder marker until the user presses Add, and the first sync only adds files.
 */
export function AddCopy({state, initialPath, initialSpaces, reason, onClose, onAdded, onState}: {
  state: CopiesState
  initialPath?: string
  /** Spaces to start with chosen under "What should it hold?" (Keep a copy… for the files that have only one copy). */
  initialSpaces?: string[]
  /** Why the dialog opened, shown under its title ("For the 82 files that are only on this PC"). */
  reason?: string
  onClose: () => void
  onAdded: (state: CopiesState) => void
  onState?: (state: CopiesState) => void
}) {
  const [step, setStep] = useState(0)
  const [path, setPath] = useState(initialPath ?? '')
  // the folder the person picked when the copy's Keepfin folder goes inside it (E:\ → E:\Keepfin), as setup does it
  const [madeIn, setMadeIn] = useState('')
  // where the copy lives: a folder or drive on this computer, or a cloud account (offered only when this build has providers)
  const cloudOffered = !!state.cloud && state.cloud.providers.some((p) => p.available)
  const devicesOffered = !!state.devices && state.devices.length > 0
  const [source, setSource] = useState<'folder' | 'cloud' | 'device'>('folder')
  const [deviceId, setDeviceId] = useState('')
  const [account, setAccount] = useState<CloudAccountView | undefined>()
  const [cloudFolder, setCloudFolder] = useState('Library')
  const [check, setCheck] = useState<CopyFolderCheck | undefined>()
  const [checking, setChecking] = useState(false)
  const [holds, setHolds] = useState<CopyHolds>(() => {
    const spaces = (initialSpaces ?? []).filter((id) => state.rows.some((row) => row.kind === 'space' && row.id === id))
    return spaces.length ? {all: false, spaces, playlists: [], favourites: false} : {all: true, spaces: [], playlists: [], favourites: false}
  })
  // a copy holds everything that belongs to an item unless the person turns this off
  const [companions, setCompanions] = useState(true)
  // music videos: until the person chooses, a copy of everything carries them and a part copy does not
  const [musicVideosChoice, setMusicVideos] = useState<boolean | undefined>(undefined)
  const musicVideos = musicVideosChoice ?? holds.all
  const [mode, setMode] = useState<CopyMode>('backup')
  const [name, setName] = useState('')
  const [auto, setAuto] = useState(true)
  const [every, setEvery] = useState('never')
  const [preview, setPreview] = useState<CopyPreview | undefined>()
  const [planning, setPlanning] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const placeId = useRef<string | undefined>(undefined)
  const done = useRef(false)

  // a prepared drive that the wizard leaves without adding goes back as it was
  useEffect(
    () => () => {
      if (!done.current && placeId.current) void cancelCopyPreview(placeId.current).catch(() => undefined)
    },
    [],
  )

  useEffect(() => {
    let live = true
    setCheck(undefined)
    if (!path || source !== 'folder') return
    setChecking(true)
    checkCopyFolder(path)
      .then((next) => {
        if (!live) return
        setCheck(next)
        setName((current) => current || next.suggestedName)
      })
      .catch((caught) => live && setError(messageOf(caught)))
      .finally(() => live && setChecking(false))
    return () => {
      live = false
    }
  }, [path, source])

  const cloudNow = source === 'cloud' ? state.cloud?.accounts.find((a) => a.id === account?.id) : undefined
  const cloudReady = !!cloudNow && !cloudNow.needsSignIn && cloudFolder.trim().length > 0
  const deviceNow = source === 'device' ? state.devices?.find((d) => d.deviceId === deviceId) : undefined
  const deviceReady = !!deviceNow?.allowed
  const draft = (): CopyDraft => ({
    path: source === 'folder' ? path : '',
    ...(source === 'cloud' && cloudNow ? {cloud: {accountId: cloudNow.id, folder: cloudFolder.trim()}} : {}),
    ...(source === 'device' && deviceNow ? {device: {deviceId: deviceNow.deviceId}} : {}),
    name: name.trim() || (source === 'cloud' ? cloudNow?.providerName : source === 'device' ? deviceNow?.name : check?.suggestedName) || 'Copy',
    mode,
    holds,
    companions,
    ...(musicVideosChoice !== undefined ? {musicVideos: musicVideosChoice} : {}),
    schedule: {auto, everyHours: scheduleOptions.find((o) => o.value === every)?.hours ?? null},
  })

  async function choose() {
    setError('')
    try {
      const picked = await pickCopyFolder()
      if (!picked) return
      // the shared core's rule (core/spaces/Folders.kt): a Keepfin folder is used as it is, any other folder gets one inside it
      const found = await checkCopyFolder(picked)
      const place = keepfinFolderFor(picked, !!found.keepfinFolder)
      setMadeIn(place === picked ? '' : picked)
      setPath(place)
      setName('')
    } catch (caught) {
      setError(messageOf(caught))
    }
  }

  async function plan() {
    setPlanning(true)
    setError('')
    setPreview(undefined)
    try {
      const next = await previewCopy(draft(), placeId.current)
      placeId.current = next.status === 'ready' || next.status === 'held' ? next.placeId : placeId.current
      setPreview(next)
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setPlanning(false)
    }
  }

  function go(next: number) {
    setError('')
    setStep(next)
    if (next === FIRST_SYNC) void plan()
  }

  async function add() {
    if (!placeId.current) return
    setBusy(true)
    setError('')
    try {
      const next = await addCopy(placeId.current)
      done.current = true
      onAdded(next)
    } catch (caught) {
      setError(messageOf(caught))
      setBusy(false)
    }
  }

  async function cancel() {
    if (placeId.current) await cancelCopyPreview(placeId.current).catch(() => undefined)
    done.current = true
    onClose()
  }

  const holdsOk = holds.all || holds.spaces.length > 0 || holds.playlists.length > 0 || holds.favourites

  return (
    <Dialog
      className="copy-wizard"
      labelledBy="add-copy-title"
      onClose={() => void cancel()}
      title={reason ? 'Keep a copy' : 'Add a copy'}
      eyebrow={reason}
      footer={
        <>
          <Button variant="ghost" onClick={() => void cancel()} disabled={busy}>
            Cancel
          </Button>
          <span className="copy-dialog-space" />
          {step > 0 && (
            <Button onClick={() => go(step - 1)} disabled={busy}>
              Back
            </Button>
          )}
          {step < FIRST_SYNC && (
            <Button variant="primary" disabled={(step === 0 && (source === 'cloud' ? !cloudReady : source === 'device' ? !deviceReady : !check?.ok)) || (step === 1 && !holdsOk) || (step === 2 && !name.trim())} onClick={() => go(step + 1)}>
              Continue
            </Button>
          )}
          {step === FIRST_SYNC && (
            <Button variant="primary" loading={busy} disabled={planning || !preview || (preview.status !== 'ready' && preview.status !== 'held') || preview.enough === false} onClick={() => void add()}>
              Add this copy
            </Button>
          )}
        </>
      }
    >
      <StepIndicator steps={steps} current={step} label={reason ? 'Keep a copy' : 'Add a copy'} />
      {step === 0 && (
        <div className="setup-step">
          <StepHeading hint="A drive, a USB stick or a folder on another disk. Keepfin makes a folder named Keepfin there for the copy.">Where should the copy live?</StepHeading>
          {(cloudOffered || devicesOffered) && (
            <Segmented
              label="Where the copy lives"
              value={source}
              onChange={(next) => {
                setSource(next)
                setError('')
              }}
              options={[
                {value: 'folder', label: 'A drive or folder'},
                ...(cloudOffered ? [{value: 'cloud' as const, label: 'A cloud account'}] : []),
                ...(devicesOffered ? [{value: 'device' as const, label: 'A paired device'}] : []),
              ]}
            />
          )}
          {source === 'device' && state.devices && (
            <DeviceStep
              devices={state.devices}
              value={deviceId}
              onChange={(device) => {
                setDeviceId(device.deviceId)
                setName((current) => current || device.name)
              }}
            />
          )}
          {source === 'cloud' && (
            <CloudStep
              state={state}
              accountId={account?.id ?? ''}
              folder={cloudFolder}
              onAccount={(next) => {
                setAccount(next)
                setName((current) => current || next.providerName)
              }}
              onFolder={setCloudFolder}
              onContinue={() => go(1)}
              onState={(next) => onState?.(next)}
              onAttached={(next) => {
                done.current = true
                onAdded(next)
              }}
            />
          )}
          {source === 'folder' && (
            <>
          <div className="copy-where">
            <span className="setup-path">{path || 'No folder chosen yet'}</span>
            <Button icon="folder" onClick={() => void choose()}>
              {path ? 'Change…' : 'Choose a folder…'}
            </Button>
          </div>
          {madeIn && (
            <p className="setup-prose setup-note">
              Keepfin makes a new folder named Keepfin in <span className="setup-path-inline">{madeIn}</span>, so the copy stays together and away from what
              is already there.{' '}
              {!isDriveRoot(madeIn) && (
                <button
                  type="button"
                  className="link-btn"
                  onClick={() => {
                    setPath(madeIn)
                    setMadeIn('')
                    setName('')
                  }}
                >
                  Use {madeIn} itself
                </button>
              )}
            </p>
          )}
          {checking && (
            <p className="muted small" role="status">
              <Spinner /> Checking the folder…
            </p>
          )}
          {check && !check.ok && <InlineAlert tone="error">{check.problem}</InlineAlert>}
          {check?.ok && (
            <div className="setup-notes">
              {check.isCopy && <InlineAlert tone="info">This folder is already a Keepfin copy of this library. It will be used as it is.</InlineAlert>}
              {!!check.existingFiles && !check.isCopy && (
                <InlineAlert tone="info" title={`${check.existingFiles >= 1000 ? 'Over 1,000' : check.existingFiles} things are already in this folder`}>
                  Keepfin leaves them alone. A two-way copy would offer to add media files it finds here to your library.
                </InlineAlert>
              )}
              {check.insideCloudApp && (
                <InlineAlert tone="warning" title={`This folder is inside ${cloudNames[check.insideCloudApp as keyof typeof cloudNames] ?? check.insideCloudApp}`}>
                  A copy here is fine, and the cloud app uploads it for you. Keepfin only needs the files kept on this PC, not
                  online-only, so it can check them. {keepOffline[check.insideCloudApp] ?? 'Set the folder to stay available offline in the cloud app.'}
                </InlineAlert>
              )}
              {check.freeBytes !== undefined && (
                <p className="small muted tabular">
                  {formatBytes(check.freeBytes)} free{check.totalBytes ? ` of ${formatBytes(check.totalBytes)}` : ''}
                </p>
              )}
            </div>
          )}
            </>
          )}
        </div>
      )}
      {step === 1 && (
        <div className="setup-step">
          <StepHeading hint="You can change this later, and a row left out never deletes anything.">What should it hold?</StepHeading>
          <HoldsEditor holds={holds} rows={state.rows} onChange={setHolds} />
          <SwitchRow title="Lyrics, subtitles and info files" description="The files beside each song and video go with it." checked={companions} onChange={setCompanions} />
          <SwitchRow title="Music videos" description="Each song's music video goes with it." checked={musicVideos} onChange={setMusicVideos} />
        </div>
      )}
      {step === 2 && (
        <div className="setup-step">
          <StepHeading hint="Pick how the copy and your library treat each other. This cannot be changed later; add another copy for a different mode.">How should it keep in step?</StepHeading>
          <fieldset className="copy-holds">
            <legend className="sr-only">How the copy syncs</legend>
            {(['backup', 'twoWay', 'mirror'] as CopyMode[]).map((m) => (
              <label key={m} className="copy-choice">
                <input type="radio" name="copy-mode" checked={mode === m} onChange={() => setMode(m)} />
                <span>
                  <strong>{modeWords[m].name}</strong>
                  <small>{modeWords[m].long}</small>
                </span>
              </label>
            ))}
          </fieldset>
          <div className="copy-form">
            <label className="copy-field">
              <span>Name</span>
              <input className="input" value={name} maxLength={80} onChange={(event) => setName(event.target.value)} />
            </label>
            <SwitchRow title="Sync by itself" description="When the drive is connected and when your library changes." checked={auto} onChange={setAuto} />
            <div className="copy-field">
              <span>Schedule</span>
              <Select label="Schedule" value={every} onChange={setEvery} options={scheduleOptions.map((o) => ({value: o.value, label: o.label}))} />
            </div>
          </div>
        </div>
      )}
      {step === FIRST_SYNC && (
        <div className="setup-step">
          <StepHeading hint="This is what the first sync would do. Nothing has been written to the drive yet.">The first sync</StepHeading>
          {planning && (
            <p className="muted" role="status">
              <Spinner /> Comparing your library with the folder…
            </p>
          )}
          {preview && preview.status !== 'ready' && preview.status !== 'held' && <InlineAlert tone="error">{preview.reason}</InlineAlert>}
          {preview && (preview.status === 'ready' || preview.status === 'held') && (
            <div className="copy-preview" aria-label="Plan for the first sync">
              {preview.lines.length === 0 ? (
                <p>Everything is already there. Nothing needs to be copied.</p>
              ) : (
                <ul className="copy-plan">
                  {preview.lines.map((line) => (
                    <li key={line.label}>
                      <span>{line.label}</span>
                      <span className="tabular">
                        {plural(line.files, 'file')}
                        {line.bytes ? ` · ${formatBytes(line.bytes)}` : ''}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
              <p className="small muted">
                {preview.additiveOnly ? 'The first sync only adds files. Nothing is removed or replaced until you confirm, and differences are kept, not overwritten.' : 'Removals and replacements keep the old bytes as earlier versions.'}
              </p>
              {preview.enough === false && <InlineAlert tone="error">There is not enough free space on the drive{preview.freeBytes !== undefined ? ` (${formatBytes(preview.freeBytes)} free)` : ''}.</InlineAlert>}
              {preview.importsToLibrary > 0 && <InlineAlert tone="info">{plural(preview.importsToLibrary, 'file')} found on the drive would be added to your library.</InlineAlert>}
              {preview.status === 'held' && <InlineAlert tone="warning">{preview.reason ?? 'This is a large change.'} It will wait for your OK.</InlineAlert>}
              {preview.differences.length > 0 && (
                <div className="copy-diffs">
                  <h5>Differences found</h5>
                  <ul>
                    {preview.differences.map((d) => (
                      <li key={d.path}>
                        <span>{d.path.split('/').pop()}</span>
                        <small className="muted">{d.detail}</small>
                      </li>
                    ))}
                  </ul>
                  {preview.differencesMore > 0 && <small className="muted">and {preview.differencesMore.toLocaleString()} more</small>}
                </div>
              )}
            </div>
          )}
        </div>
      )}
      {error && <InlineAlert tone="error">{error}</InlineAlert>}
    </Dialog>
  )
}
