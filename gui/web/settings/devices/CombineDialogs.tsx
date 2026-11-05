import {useEffect, useId, useRef, useState} from 'react'
import '../../styles/copies.css'
import {messageOf} from '../../bridge'
import {Button, InlineAlert, ProgressBar, Spinner} from '../../ui/controls'
import {Dialog} from '../../ui/Dialog'
import {Icon} from '../../ui/Icon'
import {cancelCombine, keepCombined, pauseCombine, previewCombine, previewSeparate, resumeCombine, separateLibraries, startCombine} from './combineApi'
import {RUNNING, LOOKING, canSeparate, combineLine, combineQuestion, defaultKeep, forDevice, percentOf, reviewEntry, reviewPicture, separateFacts, type PreviewSizes} from './combineLogic'
import {broadcastCombine, useCombine} from './useCombine'

type Target = {deviceId: string; name: string}

/**
 * The first step of the setup when the two devices hold different libraries: "Combine libraries". It asks for the dry run when it opens
 * (nothing changes yet), then offers the choice and the review. Combine starts the work and closes; the device card shows how it goes.
 */
export function CombineStep({device, onClose}: {device: Target; onClose: () => void}) {
  const {state, error: loadError} = useCombine()
  const [step, setStep] = useState<'choose' | 'review'>('choose')
  const [picked, setPicked] = useState<'mine' | 'theirs' | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [expanded, setExpanded] = useState(false)
  const asked = useRef(false)
  const started = useRef(false)
  const titleId = useId()
  const name = device.name
  const mine = forDevice(state, device.deviceId)
  const phase = mine?.phase
  const preview = (phase === 'ready' ? mine?.preview : undefined) as PreviewSizes | undefined
  const keep = picked ?? defaultKeep(preview)

  // ask for the dry run once, as soon as the state is known: a review that is already there (after a restart) is used as it is
  useEffect(() => {
    if (!state || asked.current) return
    asked.current = true
    if (!state.available || (state.phase !== 'idle' && state.deviceId === device.deviceId)) return
    previewCombine(device.deviceId)
      .then(broadcastCombine)
      .catch((caught) => setError(messageOf(caught)))
  }, [state, device.deviceId])

  // leaving without Combine puts the libraries back as they were (the review is dropped)
  const leave = () => {
    if (!started.current && asked.current && state?.available && (phase === 'ready' || (phase && LOOKING.has(phase)))) void cancelCombine().then(broadcastCombine, () => undefined)
    onClose()
  }

  async function begin() {
    setBusy(true)
    setError('')
    try {
      broadcastCombine(await startCombine(device.deviceId, keep))
      started.current = true
      onClose()
    } catch (caught) {
      setError(messageOf(caught))
      setBusy(false)
    }
  }

  const unavailable = state && !state.available
  const working = !!phase && (RUNNING.has(phase) || phase === 'paused' || phase === 'separating' || phase === 'failed')
  const blockers = state?.blockers ?? []
  const ready = !!preview && phase === 'ready'

  let body
  let footer
  if (unavailable) {
    body = <InlineAlert tone="info">{state.unavailable ?? 'Combining libraries is not available right now.'}</InlineAlert>
    footer = <Button variant="primary" onClick={leave}>Continue</Button>
  } else if (working) {
    body = <p>{`Combining with ${name} is already under way or waiting. You can follow it, or change it, on ${name}’s card in Settings › Devices.`}</p>
    footer = <Button variant="primary" onClick={leave}>Close</Button>
  } else if (!ready && (error || loadError || blockers.length > 0)) {
    body = (
      <>
        {(error || loadError) && <InlineAlert>{error || loadError}</InlineAlert>}
        {blockers.map((line) => (
          <InlineAlert key={line} tone="warning">
            {line}
          </InlineAlert>
        ))}
      </>
    )
    footer = (
      <>
        <Button onClick={leave}>Later</Button>
        <Button
          variant="primary"
          onClick={() => {
            setError('')
            previewCombine(device.deviceId).then(broadcastCombine).catch((caught) => setError(messageOf(caught)))
          }}
        >
          Try again
        </Button>
      </>
    )
  } else if (!ready) {
    const p = mine?.progress
    body = (
      <div role="status" className="devices-combine-wait">
        <p className="muted">
          <Spinner /> Looking at both libraries…
        </p>
        {p && <small className="muted">{p.total > 0 ? `${p.label ?? 'Working'} · ${p.done.toLocaleString()} of ${p.total.toLocaleString()}` : (p.label ?? '')}</small>}
        <p className="small muted">Nothing changes yet. This library stays usable.</p>
      </div>
    )
    footer = <Button onClick={leave}>Later</Button>
  } else if (step === 'choose') {
    body = (
      <>
        {(error || loadError) && <InlineAlert>{error || loadError}</InlineAlert>}
        <p>{combineQuestion(preview, name)}</p>
        <fieldset className="copy-holds devices-setup-direction devices-combine-keep">
          <legend>Keep the name, spaces and settings of</legend>
          {(
            [
              ['theirs', `${name}’s library`, `The library on ${name}.`],
              ['mine', 'This PC’s library', 'The library on this PC.'],
            ] as const
          ).map(([value, title, line]) => (
            <label key={value} className="copy-choice">
              <input type="radio" name="combine-keep" checked={keep === value} onChange={() => setPicked(value)} />
              <span>
                <strong>{title}</strong>
                <small>{line}</small>
              </span>
            </label>
          ))}
        </fieldset>
      </>
    )
    footer = (
      <>
        <Button onClick={leave}>Later</Button>
        <Button variant="primary" onClick={() => setStep('review')}>
          Review
        </Button>
      </>
    )
  } else {
    const picture = reviewPicture(preview, name)
    const shown = preview.review ?? []
    const more = Math.max(0, preview.reviewTotal - shown.length)
    body = (
      <div className="devices-setup-review" aria-label="Review">
        {(error || loadError) && <InlineAlert>{error || loadError}</InlineAlert>}
        <p>
          <strong>Your files stay where they are. Nothing is deleted.</strong>
        </p>
        <div className="combine-flow" aria-label="Before and after">
          <div className="combine-lib">
            <span>This PC</span>
            <strong className="tabular">{preview.items.toLocaleString()}</strong>
            <small>{preview.items === 1 ? 'item' : 'items'}</small>
          </div>
          <span className="combine-op" aria-hidden="true">+</span>
          <div className="combine-lib">
            <span>{name}</span>
            <strong className="tabular">{preview.otherItems !== undefined ? preview.otherItems.toLocaleString() : '…'}</strong>
            <small>{preview.otherItems === 1 ? 'item' : 'items'}</small>
          </div>
          <span className="combine-op" aria-hidden="true">
            <Icon name="chevronRight" size={18} />
          </span>
          <div className="combine-lib combine-result">
            <span>One library</span>
            <strong className="tabular">{picture.total !== undefined ? picture.total.toLocaleString() : '…'}</strong>
            <small>{picture.total === 1 ? 'item' : 'items'}</small>
          </div>
        </div>
        {picture.tiles.length > 0 && (
          <ul className="combine-tiles" aria-label="What happens to this PC’s items">
            {picture.tiles.map((tile) => (
              <li key={tile.key} className={`combine-tile combine-tile-${tile.key}`} title={tile.sentence}>
                <Icon name={tile.icon} size={16} />
                <strong className="tabular">{tile.count.toLocaleString()}</strong>
                <span>{tile.label}</span>
                <span className="sr-only">{tile.sentence}</span>
              </li>
            ))}
          </ul>
        )}
        <div className="combine-along">
          <span className="muted">Comes along</span>
          <ul aria-label="Comes along">
            {picture.along.map((chip) => (
              <li key={chip.text} className="combine-chip">
                <Icon name={chip.icon} size={14} />
                {chip.text}
              </li>
            ))}
          </ul>
        </div>
        {(picture.check.length > 0 || preview.reviewTotal > 0) && (
          <div className="devices-combine-entries combine-check">
            <strong>{`To check · ${preview.reviewTotal.toLocaleString()}`}</strong>
            {picture.check.map((line) => (
              <p key={line}>{line}</p>
            ))}
            <button type="button" className="link-btn" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
              {expanded ? 'Hide the details' : `Show the details (${preview.reviewTotal.toLocaleString()})`}
            </button>
            {expanded && (
              <ul aria-label="Details">
                {shown.map((line, index) => (
                  <li key={`${line.kind}-${line.s}-${index}`}>{reviewEntry(line, name)}</li>
                ))}
              </ul>
            )}
            {expanded && more > 0 && <small className="muted">and {more.toLocaleString()} more</small>}
          </div>
        )}
        <p className="muted">{`You can separate them again for ${plural30(preview.separateDays)}.`}</p>
      </div>
    )
    footer = (
      <>
        <Button onClick={() => setStep('choose')} disabled={busy}>
          Back
        </Button>
        <Button variant="primary" loading={busy} onClick={() => void begin()}>
          Combine
        </Button>
      </>
    )
  }

  return (
    <Dialog
      title="Combine libraries"
      eyebrow={step === 'review' && ready ? `Review · ${name}` : name}
      labelledBy={titleId}
      onClose={leave}
      className="devices-dialog devices-setup"
      footer={<div className="devices-dialog-actions">{footer}</div>}
    >
      <div className="devices-dialog-body devices-setup-body" aria-live="polite">
        {body}
      </div>
    </Dialog>
  )
}

const plural30 = (days: number) => `${days.toLocaleString()} ${days === 1 ? 'day' : 'days'}`

/**
 * Separate libraries…: what goes back, whether the other device keeps copies of what this PC brought, and the unreachable case.
 * Nothing is deleted from this PC's folder.
 */
export function SeparateDialog({device, onClose}: {device: Target; onClose: () => void}) {
  const {state} = useCombine()
  const [remove, setRemove] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const asked = useRef(false)
  const titleId = useId()
  const mine = forDevice(state, device.deviceId)
  const facts = mine ? separateFacts(mine) : null

  useEffect(() => {
    if (asked.current) return
    asked.current = true
    previewSeparate(device.deviceId)
      .then(broadcastCombine)
      .catch((caught) => setError(messageOf(caught)))
  }, [device.deviceId])

  async function go() {
    setBusy(true)
    setError('')
    try {
      broadcastCombine(await separateLibraries(device.deviceId, remove ? 'none' : 'all', !!facts?.unreachable))
      onClose()
    } catch (caught) {
      setError(messageOf(caught))
      setBusy(false)
    }
  }

  return (
    <Dialog
      title="Separate libraries"
      eyebrow={device.name}
      labelledBy={titleId}
      onClose={onClose}
      className="devices-dialog devices-setup"
      footer={
        <div className="devices-dialog-actions">
          <Button onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="primary" disabled={!facts} loading={busy} onClick={() => void go()}>
            {facts?.unreachable ? 'Separate anyway' : 'Separate'}
          </Button>
        </div>
      }
    >
      <div className="devices-dialog-body devices-setup-body" aria-live="polite">
        {error && <InlineAlert>{error}</InlineAlert>}
        {!facts && !error && (
          <p className="muted">
            <Spinner /> Checking what goes back…
          </p>
        )}
        {facts && (
          <>
            {facts.unreachable && <InlineAlert tone="warning">{facts.unreachable}</InlineAlert>}
            <p>{facts.goesBack}</p>
            {facts.addedSince && <p>{facts.addedSince}</p>}
            {facts.held && !facts.unreachable && (
              <fieldset className="copy-holds devices-setup-direction devices-combine-held">
                <legend>{facts.held}</legend>
                {(
                  [
                    [false, `Keep them on ${facts.name}`],
                    [true, 'Remove them'],
                  ] as const
                ).map(([value, label]) => (
                  <label key={label} className="copy-choice">
                    <input type="radio" name="separate-held" checked={remove === value} onChange={() => setRemove(value)} />
                    <span>
                      <strong>{label}</strong>
                    </span>
                  </label>
                ))}
              </fieldset>
            )}
            <p className="muted">Changes you made since combining are not brought back.</p>
            <p className="muted">Nothing is deleted from this PC’s folder.</p>
          </>
        )}
      </div>
    </Dialog>
  )
}

/**
 * The combine on a device card: a line with a progress bar while it runs (Pause, Cancel), a paused or stopped note (Resume, Cancel),
 * and when done "Combined · Separate again until <date>" with Separate libraries… and Keep combined in the opened card.
 */
export function CombineCardBlock({device, open, onSeparate, onReview}: {device: Target; open: boolean; onSeparate: () => void; onReview: () => void}) {
  const {state} = useCombine()
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const mine = forDevice(state, device.deviceId)
  if (!mine) return null
  const phase = mine.phase
  const percent = percentOf(mine)

  async function run(label: string, work: () => Promise<unknown>) {
    setBusy(label)
    setError('')
    try {
      const next = await work()
      if (next && typeof next === 'object') broadcastCombine(next as Parameters<typeof broadcastCombine>[0])
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setBusy('')
    }
  }

  const running = RUNNING.has(phase) || LOOKING.has(phase)
  const stopped = phase === 'paused' || phase === 'failed'
  return (
    <div className="devices-combine" role="group" aria-label={`Combining libraries with ${device.name}`} data-phase={phase}>
      <div className="devices-card-statusrow">
        <p className="small devices-combine-line" aria-live="polite" data-phase={phase}>
          {combineLine(mine)}
        </p>
        {phase === 'ready' && (
          <Button size="sm" aria-label={`Review: combining with ${device.name}`} onClick={onReview}>
            Review
          </Button>
        )}
        {running && RUNNING.has(phase) && (
          <Button size="sm" loading={busy === 'pause'} aria-label={`Pause combining with ${device.name}`} onClick={() => void run('pause', pauseCombine)}>
            Pause
          </Button>
        )}
        {(running || stopped || phase === 'ready') && (
          <Button size="sm" variant="ghost" loading={busy === 'cancel'} aria-label={`Cancel combining with ${device.name}`} onClick={() => void run('cancel', cancelCombine)}>
            Cancel
          </Button>
        )}
        {stopped && (
          <Button size="sm" variant="primary" loading={busy === 'resume'} aria-label={`${phase === 'failed' ? 'Try again' : 'Resume'}: combining with ${device.name}`} onClick={() => void run('resume', resumeCombine)}>
            {phase === 'failed' ? 'Try again' : 'Resume'}
          </Button>
        )}
      </div>
      {(running || stopped) && (RUNNING.has(phase) || phase === 'paused') && <ProgressBar value={percent ?? 0} mode={percent === null && phase !== 'paused' ? 'indeterminate' : 'determinate'} tone={phase === 'paused' ? 'paused' : undefined} label={`Combining libraries with ${device.name}`} />}
      {canSeparate(mine) && open && (
        <div className="devices-combine-actions">
          <Button size="sm" onClick={onSeparate}>
            Separate libraries…
          </Button>
          <button type="button" className="link-btn" disabled={busy === 'keep'} onClick={() => void run('keep', () => keepCombined(device.deviceId))}>
            Keep combined
          </button>
        </div>
      )}
      {error && <InlineAlert>{error}</InlineAlert>}
    </div>
  )
}
