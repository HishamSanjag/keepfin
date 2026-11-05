import {useEffect, useId, useRef, useState, useSyncExternalStore} from 'react'
import '../../styles/copies.css'
import type {CopyPreview} from '../../../shared/copies'
import {messageOf} from '../../bridge'
import {Button, InlineAlert, Spinner, SwitchRow} from '../../ui/controls'
import {Dialog} from '../../ui/Dialog'
import {addCopy, cancelCopyPreview, getCopies, previewCopy} from '../storage/copiesApi'
import {defaultChoices, differentLibraries, directionWords, draftFromChoices, nothingChosen, setupBlocker, setupRows, summarisePreview, type Direction, type SetupChoices} from './setupLogic'
import {CombineStep} from './CombineDialogs'
import {useCombine} from './useCombine'
import {broadcastCopies, useDeviceCopies} from './useDeviceCopies'
import {useDevices} from './useDevices'

/** Which paired device the setup is open for. Module-level so the pairing dialog and a device card can both open it. */
type Target = {deviceId: string; name: string}
let target: Target | null = null
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((listener) => listener())
export const openDeviceSetup = (deviceId: string, name: string) => {
  target = {deviceId, name}
  emit()
}
const closeDeviceSetup = () => {
  target = null
  emit()
}
const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Renders the setup dialog when one is asked for. Lives at the app level beside the pairing dialog. */
export function DeviceSetupHost() {
  const current = useSyncExternalStore(subscribe, () => target)
  if (!current) return null
  return <DeviceSetup key={current.deviceId} device={current} onClose={closeDeviceSetup} />
}

/**
 * Two steps after a device is connected: what stays up to date (spaces, Favourites, one direction, by itself), then a review made by the
 * same engine that will run it ("Nothing is removed or replaced."). Start adds the copy and closes; the card shows how it goes.
 */
export function DeviceSetup({device, onClose}: {device: Target; onClose: () => void}) {
  const {state: copies, error: loadError} = useDeviceCopies()
  const [step, setStep] = useState<1 | 2>(1)
  const [picked, setPicked] = useState<SetupChoices | null>(null)
  const [preview, setPreview] = useState<CopyPreview | undefined>()
  const [planning, setPlanning] = useState(false)
  const [checking, setChecking] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const placeId = useRef<string | undefined>(undefined)
  const done = useRef(false)
  const round = useRef(0)
  const name = device.name
  const rows = setupRows(copies?.rows ?? [])
  const choices = picked ?? defaultChoices(rows)
  const choice = copies?.devices?.find((item) => item.deviceId === device.deviceId)
  const {state: devicesState} = useDevices()
  const peerPaired = devicesState?.paired.find((item) => item.deviceId === device.deviceId)
  const different = differentLibraries(choice, peerPaired)
  const {state: combine} = useCombine()
  const combinedHere = combine?.available === true && combine.deviceId === device.deviceId && combine.phase === 'done'
  const blockedBy = !copies ? null : !copies.available ? 'Copies need the Keepfin folder to be open.' : !copies.devices ? 'Keeping a copy on a device is not available right now.' : setupBlocker(choice, name)
  const titleId = useId()

  // a prepared copy that is left without Start goes back as it was
  useEffect(
    () => () => {
      if (!done.current && placeId.current) void cancelCopyPreview(placeId.current).catch(() => undefined)
    },
    [],
  )

  const change = (patch: Partial<SetupChoices>) => setPicked({...choices, ...patch})
  const toggleSpace = (id: string) => change({spaces: choices.spaces.includes(id) ? choices.spaces.filter((item) => item !== id) : [...choices.spaces, id]})

  async function checkAgain() {
    setChecking(true)
    setError('')
    try {
      broadcastCopies(await getCopies())
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setChecking(false)
    }
  }

  async function plan() {
    const mine = ++round.current
    setPlanning(true)
    setError('')
    setPreview(undefined)
    try {
      const next = await previewCopy(draftFromChoices(device, choices), placeId.current)
      if (mine !== round.current) return
      if (next.status === 'ready' || next.status === 'held') placeId.current = next.placeId
      setPreview(next)
    } catch (caught) {
      if (mine === round.current) setError(messageOf(caught))
    } finally {
      if (mine === round.current) setPlanning(false)
    }
  }

  function next() {
    setStep(2)
    void plan()
  }

  async function start() {
    if (!placeId.current) return
    setBusy(true)
    setError('')
    try {
      broadcastCopies(await addCopy(placeId.current))
      done.current = true
      onClose()
    } catch (caught) {
      setError(messageOf(caught))
      setBusy(false)
    }
  }

  const summary = preview ? summarisePreview(preview, name, choices.direction) : undefined
  const words = directionWords(name)
  const canNext = !!copies && !blockedBy && !nothingChosen(choices)

  // two devices that each hold their own library cannot keep copies until they are one library: the Combine step comes first
  if (copies && different && !combinedHere) return <CombineStep device={device} onClose={onClose} />


  return (
    <Dialog
      title={step === 1 ? 'What stays up to date' : 'Review and start'}
      eyebrow={`Step ${step} of 2 · ${name}`}
      labelledBy={titleId}
      onClose={onClose}
      className="devices-dialog devices-setup"
      footer={
        <div className="devices-dialog-actions">
          {step === 1 ? (
            <>
              <Button onClick={onClose}>Later</Button>
              <Button variant="primary" disabled={!canNext} onClick={next}>
                Next
              </Button>
            </>
          ) : (
            <>
              <Button onClick={() => setStep(1)} disabled={busy}>
                Back
              </Button>
              <Button variant="primary" disabled={!summary?.canStart || planning} loading={busy} onClick={() => void start()}>
                Start
              </Button>
            </>
          )}
        </div>
      }
    >
      <div className="devices-dialog-body devices-setup-body" aria-live="polite">
        {(error || loadError) && <InlineAlert>{error || loadError}</InlineAlert>}
        {step === 1 && (
          <>
            <p>Your library list (names, playlists, what you played) stays in step by itself. It is small.</p>
            {!copies && (
              <p className="muted">
                <Spinner /> Loading…
              </p>
            )}
            {copies && blockedBy && (
              <InlineAlert
                tone="warning"
                title={`${name} is not ready for a copy`}
                actions={
                  <Button size="sm" loading={checking} onClick={() => void checkAgain()}>
                    Check again
                  </Button>
                }
              >
                {blockedBy}
              </InlineAlert>
            )}
            {copies && (
              <>
                <fieldset className="copy-holds devices-setup-files" disabled={!!blockedBy}>
                  <legend>Files to keep up to date</legend>
                  <ul className="devices-setup-list">
                    {rows.map((row) => {
                      const on = row.kind === 'favourites' ? choices.favourites : choices.spaces.includes(row.id)
                      return (
                        <li key={row.key}>
                          <label className="copy-check">
                            <input type="checkbox" checked={on} onChange={() => (row.kind === 'favourites' ? change({favourites: !choices.favourites}) : toggleSpace(row.id))} />
                            <span>{row.name}</span>
                          </label>
                        </li>
                      )
                    })}
                  </ul>
                  {nothingChosen(choices) && <small className="muted">Choose at least one.</small>}
                </fieldset>
                <fieldset className="copy-holds devices-setup-direction" disabled={!!blockedBy}>
                  <legend>Direction</legend>
                  {(['both', 'only'] as Direction[]).map((value) => (
                    <label key={value} className="copy-choice">
                      <input type="radio" name="setup-direction" checked={choices.direction === value} onChange={() => change({direction: value})} />
                      <span>
                        <strong>{words[value].title}</strong>
                        <small>{words[value].line}</small>
                      </span>
                    </label>
                  ))}
                </fieldset>
                <SwitchRow title="Keep it up to date by itself" description="Keepfin copies new files whenever both devices are connected." checked={choices.auto} onChange={(auto) => change({auto})} disabled={!!blockedBy} />
              </>
            )}
          </>
        )}
        {step === 2 && (
          <>
            {planning && (
              <p className="muted" role="status">
                <Spinner /> Comparing this library with {name}…
              </p>
            )}
            {summary && preview && (
              <div className="devices-setup-review" aria-label="Review">
                <ul className="devices-setup-facts">
                  <li>{summary.toDevice}</li>
                  {summary.toHere && <li>{summary.toHere}</li>}
                  <li data-ok={summary.space.ok === false ? 'no' : undefined}>{summary.space.text}</li>
                </ul>
                <p>
                  <strong>The first sync only adds files. {summary.additive}</strong>
                </p>
                {summary.problem && (
                  <InlineAlert
                    tone="warning"
                    actions={
                      <Button size="sm" loading={planning} onClick={() => void plan()}>
                        Check again
                      </Button>
                    }
                  >
                    {summary.problem}
                  </InlineAlert>
                )}
                {summary.note && <InlineAlert tone="info">{summary.note}</InlineAlert>}
                {preview.enough === false && !summary.problem && (
                  <div>
                    <Button size="sm" loading={planning} onClick={() => void plan()}>
                      Check again
                    </Button>
                  </div>
                )}
                {summary.differences && (
                  <div className="copy-diffs" role="group" aria-label="Files that differ">
                    <h5>Files that differ: Keepfin keeps both until you choose</h5>
                    <ul>
                      {summary.differences.names.map((file, index) => (
                        <li key={`${file}-${index}`}>
                          <span>{file}</span>
                        </li>
                      ))}
                    </ul>
                    {summary.differences.more > 0 && <small className="muted">and {summary.differences.more.toLocaleString()} more</small>}
                  </div>
                )}

              </div>
            )}
          </>
        )}
      </div>
    </Dialog>
  )
}
