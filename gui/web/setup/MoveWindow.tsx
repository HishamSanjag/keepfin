// Storage › Change location (dev-docs/plans/KEEPFIN-FOLDER-MOVE.md): the setup window's look and steps for moving the Keepfin folder.
// Where and Review run in the open app; Start saves the move and Keepfin restarts into this window, which moves the folder while
// the library is closed, then opens Keepfin from the new place.
import {useEffect, useState} from 'react'
import {formatBytes} from '../../shared/contracts'
import type {MoveCheck, MoveProgress, StorageState} from '../../shared/storage'
import {messageOf, onPush} from '../bridge'
import {plural} from '../format'
import {Button, ConfirmInline, InlineAlert, ProgressBar, Spinner, Switch} from '../ui/controls'
import {SettingCard} from '../ui/SettingCard'
import {Icon, Mark} from '../ui/Icon'
import {FolderStep, StepHeading, StepIndicator} from './FolderStep'
import {PathCrumbs, SpaceBar} from './parts'
import {cancelMove, checkMove, requestMove, startMove} from './storageApi'

const steps = ['Where', 'Review', 'Move', 'Done']
const [WHERE, REVIEW, MOVE, DONE] = [0, 1, 2, 3]

function Shell({step, children}: {step: number; children: React.ReactNode}) {
  return (
    <main className="setup-page" id="main">
      <div className="setup-col">
        <div className="setup-brand">
          <Mark size={28} />
          <span>Move your Keepfin folder</span>
        </div>
        <StepIndicator steps={steps} current={step} label="Move steps" />
        {children}
      </div>
    </main>
  )
}

/** Where and Review, over the open app (Storage & copies › Change location…). */
export function MoveFolderDialog({current, onClose}: {current: string; onClose: () => void}) {
  const [step, setStep] = useState(WHERE)
  const [path, setPath] = useState('')
  const [check, setCheck] = useState<MoveCheck | undefined>()
  const [keepOld, setKeepOld] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const work = async (task: () => Promise<void>) => {
    setBusy(true)
    setError('')
    try {
      await task()
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setBusy(false)
    }
  }
  // a rename on the same drive copies nothing; keeping the old place as a backup always copies everything
  const toCopy = !check || (check.sameDrive && !keepOld) ? 0 : check.bytes
  const fits = !check || check.freeBytes === undefined || toCopy <= check.freeBytes
  return (
    <div className="move-overlay setup-host" role="dialog" aria-modal="true" aria-label="Move your Keepfin folder">
      <Shell step={step}>
        {step === WHERE && (
          <>
            <FolderStep
              title="Where should your Keepfin folder go?"
              hint={
                <>
                  Everything in <span className="setup-path-inline">{current}</span> moves there: your files, playlists and history.
                </>
              }
              path={path}
              onPath={setPath}
              suggested=""
              onBack={onClose}
              continueLabel="Review"
              busy={busy}
              error={error}
              onContinue={() =>
                work(async () => {
                  const next = await checkMove(path)
                  if (next.problem) throw new Error(next.problem)
                  setCheck(next)
                  setStep(REVIEW)
                })
              }
            />
          </>
        )}
        {step === REVIEW && check && (
          <section className="setup-step" aria-busy={busy || undefined}>
            <StepHeading hint="Check this over. Nothing happens until you press Start.">The plan</StepHeading>
            <div className="move-plan" aria-label="What happens">
              <div className="move-plan-row">
                <span className="move-plan-label">From</span>
                <span className="setup-path">{current}</span>
              </div>
              <div className="move-plan-how">
                <Icon name="chevronDown" size={16} />
                <span>
                  {keepOld
                    ? 'Copied to the new place and every file checked. The old place stays as a backup copy.'
                    : check.sameDrive
                      ? 'Same drive: the folder is renamed in a moment, nothing is copied.'
                      : 'Copied to the new place and every file checked, then the old folder goes to the Recycle Bin.'}
                </span>
              </div>
              <div className="move-plan-row">
                <span className="move-plan-label">To</span>
                <span className="setup-path">{path}</span>
              </div>
              {toCopy > 0 && check.freeBytes !== undefined && <SpaceBar needed={toCopy} free={check.freeBytes} neededLabel="To copy" />}
            </div>
            <SettingCard
              icon="copy"
              title="Keep the old place as a backup copy"
              description="Instead of the Recycle Bin, the old folder becomes a Backup in Storage › Copies and stays up to date. Its library records are removed, so it never opens as a second library."
              control={<Switch label="Keep the old place as a backup copy" checked={keepOld} disabled={busy} onChange={setKeepOld} />}
            />
            <p className="setup-prose">Keepfin closes your library, moves the folder, then restarts from the new place. Downloads and playback wait until it is done.</p>
            {!fits && <InlineAlert tone="error" title="This does not fit">There is not enough free space there. Choose a drive with more room.</InlineAlert>}
            {error && <InlineAlert tone="error">{error}</InlineAlert>}
            <div className="setup-actions">
              <Button variant="ghost" disabled={busy} onClick={() => setStep(WHERE)}>
                Back
              </Button>
              <Button variant="primary" loading={busy} disabled={!fits} onClick={() => work(async () => void (await requestMove(path, keepOld)))}>
                Start
              </Button>
            </div>
          </section>
        )}
      </Shell>
    </div>
  )
}

/** The move itself, after the restart (phase 'moveFolder'), then Done. */
export function MoveWindow({state, onDone}: {state: StorageState; onDone: () => void}) {
  const [progress, setProgress] = useState<MoveProgress | undefined>()
  const [error, setError] = useState('')
  useEffect(() => onPush((event) => event.type === 'storageMove' && setProgress(event.progress)), [])
  // nothing runs after a restart: ask main to start (or pick up) the move
  useEffect(() => {
    startMove().catch((caught) => setError(messageOf(caught)))
  }, [])
  const done = progress?.phase === 'done'
  const failed = progress?.phase === 'failed'
  const move = state.move
  return (
    <Shell step={done ? DONE : MOVE}>
      {!done && (
        <section className="setup-step" aria-busy={!failed || undefined}>
          <StepHeading hint="Leave this window open. If you close Keepfin, the move carries on next time.">Moving your Keepfin folder</StepHeading>
          {move && <PathCrumbs path={move.to} />}
          <div className="setup-run" aria-live="polite">
            <div className="setup-run-phase">
              {!failed && <Spinner />}
              <strong>{failed ? 'Stopped by a problem' : progress?.phase === 'switching' ? 'Opening the new place' : progress?.phase === 'copying' ? 'Copying and checking your files' : 'Getting ready…'}</strong>
            </div>
            {progress?.phase === 'copying' && progress.total > 0 && (
              <>
                <ProgressBar label="Move progress" value={progress.bytesTotal > 0 ? (progress.bytesDone / progress.bytesTotal) * 100 : (progress.done / progress.total) * 100} />
                <div className="setup-run-meta tabular">
                  <span>
                    {progress.done.toLocaleString()} of {plural(progress.total, 'file')}
                  </span>
                  {progress.bytesTotal > 0 && (
                    <span>
                      {formatBytes(progress.bytesDone) || '0 B'} of {formatBytes(progress.bytesTotal)}
                    </span>
                  )}
                </div>
                {progress.current && <div className="setup-run-current">{progress.current}</div>}
              </>
            )}
          </div>
          {failed && (
            <InlineAlert tone="error" title="The move stopped" actions={<Button variant="primary" onClick={() => (setProgress(undefined), startMove().catch((caught) => setError(messageOf(caught))))}>Try again</Button>}>
              {progress?.error} Your Keepfin folder is still where it was.
            </InlineAlert>
          )}
          {error && <InlineAlert tone="error">{error}</InlineAlert>}
          <div className="setup-actions">
            {(failed || progress?.phase !== 'switching') && (
              <ConfirmInline
                label="Stop"
                confirmLabel="Stop and keep it where it was"
                prompt="Stop now? Your Keepfin folder stays where it is."
                size="md"
                onConfirm={() => cancelMove().catch((caught) => setError(messageOf(caught)))}
              />
            )}
          </div>
        </section>
      )}
      {done && (
        <section className="setup-step">
          <StepHeading hint={move?.keepOld ? 'Every file was checked in the new place. The old place is now a backup copy: find it in Storage › Copies.' : 'Every file was checked in the new place.'}>Your Keepfin folder has moved</StepHeading>
          {move && <PathCrumbs path={move.to} />}
          {progress?.note && <InlineAlert tone="warning">{progress.note}</InlineAlert>}
          <div className="setup-actions">
            <Button variant="primary" onClick={onDone}>
              Open Keepfin
            </Button>
          </div>
        </section>
      )}
    </Shell>
  )
}
