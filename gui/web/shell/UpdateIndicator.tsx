import {lazy, Suspense, useEffect, useRef, useState} from 'react'
import {releasePage} from '../../shared/updates'
import {native} from '../bridge'
import {covered, dismissWhatsNew, hidePill, loadNotes, openWhatsNew, runUpdate, useUpdates} from '../updates'
import {Button, InlineAlert, ProgressBar} from '../ui/controls'
import {Icon} from '../ui/Icon'

const ReleaseMarkdown = lazy(() => import('../settings/ReleaseNotes').then((module) => ({default: module.ReleaseMarkdown})))
const ReleaseNotes = lazy(() => import('../settings/ReleaseNotes').then((module) => ({default: module.ReleaseNotes})))

export function UpdateIndicator() {
  const {state, error, pending, whatsNew, skipped, hidden} = useUpdates()
  const [open, setOpen] = useState(false)
  const [notesError, setNotesError] = useState('')
  const root = useRef<HTMLDivElement>(null)
  const phase = state?.phase
  const flagged = phase === 'available' || phase === 'downloading' || phase === 'ready'
  // The labelled pill shows while an update exists unless the user skipped that version or closed the pill.
  // Once a download is under way it stays, so progress and the restart step remain reachable.
  const version = state?.version
  const pill = flagged && !!version && (phase !== 'available' || (!covered(version, skipped) && !covered(version, hidden)))
  const pillText = phase === 'ready' ? 'Restart to update' : phase === 'downloading' ? `Downloading ${Math.round(state?.percent || 0)}%` : 'Update available'
  const label = pill ? `${pillText} · v${version}` : 'App updates'
  const shownNotes = state?.notes || ''

  useEffect(() => {
    if (!open) return
    const outside = (event: MouseEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
        root.current?.querySelector<HTMLButtonElement>('.upd-trigger')?.focus()
      }
    }
    window.addEventListener('mousedown', outside)
    window.addEventListener('keydown', key)
    return () => {
      window.removeEventListener('mousedown', outside)
      window.removeEventListener('keydown', key)
    }
  }, [open])

  // Release notes for the pending version load lazily when the popover opens.
  useEffect(() => {
    if (open && flagged && !shownNotes) void loadNotes()
  }, [open, flagged])

  const installed = state?.mode === 'installed'
  const checkable = installed || state?.mode === 'portable' || state?.mode === 'msi'
  const working = pending || phase === 'checking' || phase === 'downloading'

  return (
    <div className="upd" ref={root}>
      {pill ? (
        <div className={`upd-pill${open ? ' is-open' : ''}${phase === 'available' ? '' : ' is-busy'}`}>
          <button
            type="button"
            className="upd-pill-main upd-trigger"
            aria-label={label}
            title={label}
            aria-haspopup="dialog"
            aria-expanded={open}
            onClick={() => setOpen((value) => !value)}
          >
            <Icon name={phase === 'ready' ? 'refresh' : 'download'} size={15} />
            <span>{pillText}</span>
          </button>
          {phase === 'available' && (
            <button
              type="button"
              className="upd-pill-x"
              aria-label={`Hide update notice for v${version}`}
              title="Hide for this version"
              onClick={() => {
                setOpen(false)
                hidePill(version!)
              }}
            >
              <Icon name="close" size={13} />
            </button>
          )}
        </div>
      ) : (
        <button
          type="button"
          className={`icon-btn icon-btn-md upd-trigger${open ? ' is-open' : ''}`}
          aria-label={label}
          title={label}
          aria-haspopup="dialog"
          aria-expanded={open}
          onClick={() => setOpen((value) => !value)}
        >
          <Icon name="refresh" size={18} />
        </button>
      )}
      {open && (
        <div className="upd-pop" role="dialog" aria-label="App updates">
          {!native ? (
            <>
              <strong className="upd-title">Updates arrive in the desktop app</strong>
              <p className="muted small">This is the browser preview. Download the latest Keepfin from the releases page.</p>
              <a className="btn btn-secondary btn-md" href={releasePage} target="_blank" rel="noreferrer">
                <span className="btn-label">View releases</span>
              </a>
            </>
          ) : !state ? (
            <p className="muted small">Reading version…</p>
          ) : (
            <>
              <div className="upd-head">
                <strong className="upd-title">
                  {phase === 'ready'
                    ? 'Ready to restart and update'
                    : phase === 'downloading'
                      ? 'Downloading update…'
                      : phase === 'available'
                        ? 'Update available'
                        : phase === 'checking'
                          ? 'Checking for updates…'
                          : phase === 'error'
                            ? 'Couldn’t check for updates'
                            : `You’re on v${state.current}`}
                </strong>
                {flagged && state.version && <span className="upd-ver tabular">v{state.current} → v{state.version}</span>}
              </div>
              {phase === 'downloading' && (
                <div className="update-progress">
                  <ProgressBar value={state.percent || 0} label="Update download" />
                  <span className="tabular small">{Math.round(state.percent || 0)}%</span>
                </div>
              )}
              {flagged && (
                <div className="upd-notes">
                  <Suspense fallback={null}>
                    <ReleaseMarkdown notes={shownNotes} compact onError={setNotesError} />
                  </Suspense>
                </div>
              )}
              {(notesError || error || (phase === 'error' && state.message)) && (
                <InlineAlert>{notesError || error || state.message}</InlineAlert>
              )}
              {!flagged && phase !== 'error' && (
                <p className="muted small">
                  {checkable
                    ? state.checkedAt
                      ? `Last checked ${new Date(state.checkedAt).toLocaleString()}.`
                      : 'Not checked yet.'
                    : 'Updates are checked in the installed desktop app.'}
                </p>
              )}
              <div className="upd-actions">
                {phase === 'available' && installed && (
                  <Button variant="primary" icon="download" disabled={working} onClick={() => void runUpdate('updateDownload')}>
                    Download v{state.version}
                  </Button>
                )}
                {phase === 'downloading' && <Button onClick={() => void runUpdate('updateCancel')}>Cancel</Button>}
                {phase === 'ready' && installed && (
                  <Button variant="primary" icon="refresh" disabled={pending} onClick={() => void runUpdate('updateInstall')}>
                    Restart to update
                  </Button>
                )}
                {flagged && !installed && (
                  <Button variant="primary" icon="external" onClick={() => void runUpdate('updateReleases')}>
                    View releases
                  </Button>
                )}
                {!flagged && checkable && (
                  <Button icon="refresh" loading={phase === 'checking'} disabled={working} onClick={() => void runUpdate('updateCheck')}>
                    Check now
                  </Button>
                )}
                <Button
                  variant="ghost"
                  icon="sparkle"
                  disabled={pending}
                  onClick={() => {
                    setOpen(false)
                    void openWhatsNew()
                  }}
                >
                  What’s new in v{state.current}
                </Button>
              </div>
            </>
          )}
        </div>
      )}
      {whatsNew && (
        <Suspense fallback={null}>
          <ReleaseNotes
            title={`What’s new in v${whatsNew.version}`}
            notes={whatsNew.notes}
            version={whatsNew.version}
            close={dismissWhatsNew}
            onError={setNotesError}
          />
        </Suspense>
      )}
    </div>
  )
}
