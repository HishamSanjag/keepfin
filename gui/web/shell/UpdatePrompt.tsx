import {useEffect, useState} from 'react'
import {releaseHighlights} from '../../shared/updates'
import {native} from '../bridge'
import {Button, InlineAlert, ProgressBar} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {Icon} from '../ui/Icon'
import {covered, loadNotes, runUpdate, skipVersion, useUpdates} from '../updates'

/**
 * One dialog per version when an update is waiting: what is new in a few lines, then Update now, Later
 * (asks again next launch) or Skip this version (never again for that version). It never appears in the
 * browser preview, with no update, or while What’s new or Meet your library is open; it waits for them.
 */
export function UpdatePrompt() {
  const {state, error, pending, whatsNew, introOpen, skipped} = useUpdates()
  // The version answered with Later (this launch only) and whether Update now was chosen for it.
  const [later, setLater] = useState<string>()
  const [engaged, setEngaged] = useState<string>()
  const version = state?.version
  const phase = state?.phase
  const waiting = native && !!state && !!version && phase === 'available' && later !== version && !covered(version, skipped)
  const following = native && !!version && engaged === version && (phase === 'downloading' || phase === 'ready')
  const blocked = !!whatsNew || introOpen
  // Let What’s new and Meet your library claim the screen first so dialogs never stack.
  const [settled, setSettled] = useState(false)
  const candidate = (waiting || following) && !blocked
  useEffect(() => {
    if (!candidate) return setSettled(false)
    const timer = setTimeout(() => setSettled(true), 1200)
    return () => clearTimeout(timer)
  }, [candidate])
  const needsNotes = candidate && settled && !state?.notes
  useEffect(() => {
    if (needsNotes) void loadNotes()
  }, [needsNotes])
  if (!candidate || !settled || !state || !version) return null

  const installed = state.mode === 'installed'
  const bullets = releaseHighlights(state.notes, 'windows')
  const close = () => {
    setLater(version)
    setEngaged(undefined)
  }
  const title =
    phase === 'ready' ? `Keepfin ${version} is ready` : phase === 'downloading' ? `Downloading Keepfin ${version}` : `Keepfin ${version} is available`
  return (
    <Dialog
      title={title}
      eyebrow={`You’re on v${state.current}`}
      labelledBy="update-prompt-title"
      className="dialog-update"
      onClose={close}
      footer={
        phase === 'ready' ? (
          <>
            <Button variant="ghost" onClick={close}>
              Later
            </Button>
            <Button variant="primary" icon="refresh" autoFocus disabled={pending} onClick={() => void runUpdate('updateInstall')}>
              Restart to update
            </Button>
          </>
        ) : phase === 'downloading' ? (
          <>
            <Button variant="ghost" onClick={() => void runUpdate('updateCancel').then(close)}>
              Cancel
            </Button>
            <Button onClick={close} autoFocus>
              Keep working
            </Button>
          </>
        ) : (
          <>
            <Button
              variant="ghost"
              onClick={() => {
                skipVersion(version)
                close()
              }}
            >
              Skip this version
            </Button>
            <Button onClick={close}>Later</Button>
            <Button
              variant="primary"
              icon={installed ? 'download' : 'external'}
              autoFocus
              disabled={pending}
              onClick={() => {
                if (installed) {
                  setEngaged(version)
                  void runUpdate('updateDownload')
                } else {
                  void runUpdate('updateReleases')
                  close()
                }
              }}
            >
              {installed ? 'Update now' : 'View releases'}
            </Button>
          </>
        )
      }
    >
      <div className="update-prompt">
        <span className="update-prompt-icon" aria-hidden="true">
          <Icon name="download" size={22} />
        </span>
        {phase === 'downloading' ? (
          <div className="update-progress">
            <ProgressBar value={state.percent || 0} label="Update download" />
            <span className="tabular small">{Math.round(state.percent || 0)}%</span>
          </div>
        ) : phase === 'ready' ? (
          <p>The update is downloaded. Restart Keepfin to finish installing it.</p>
        ) : bullets.length ? (
          <>
            <p className="muted small">What’s new</p>
            <ul className="update-bullets">
              {bullets.map((line, index) => (
                <li key={index}>{line}</li>
              ))}
            </ul>
          </>
        ) : (
          <p>A new version of Keepfin is ready to install.</p>
        )}
        {phase === 'available' && <p className="muted small">Your downloads and library stay.</p>}
        {!installed && phase === 'available' && (
          <p className="muted small">This copy is portable, so replace the program file with the new download.</p>
        )}
        {error && <InlineAlert>{error}</InlineAlert>}
      </div>
    </Dialog>
  )
}
