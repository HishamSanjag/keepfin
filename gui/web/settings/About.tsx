import {lazy, Suspense, useEffect, useRef, useState} from 'react'
import type {Operation} from '../../shared/contracts'
import {betaStableNote, isPrerelease, releasePage, type UpdateChannel, type UpdateState} from '../../shared/updates'
import {messageOf, native, request} from '../bridge'
import {updateSettings, useMeta} from '../store'
import {Badge, Button, InlineAlert, OptionRow, ProgressBar, Segmented} from '../ui/controls'
import {Logo} from '../ui/Icon'
import {Group} from '../ui/SettingCard'

const ReleaseNotes = lazy(() => import('./ReleaseNotes').then((module) => ({default: module.ReleaseNotes})))

const titles: Record<UpdateState['phase'], string> = {
  idle: 'Ready to check for updates',
  checking: 'Checking releases…',
  available: 'A new version is available',
  current: 'You’re up to date',
  downloading: 'Downloading the update…',
  ready: 'Ready to restart and update',
  error: 'Couldn’t update',
}
const editions: Record<UpdateState['mode'], string> = {
  installed: 'Windows Setup',
  portable: 'Windows Portable',
  msi: 'Windows MSI',
  development: 'Development build',
  unsupported: 'Desktop',
}

export function About() {
  const [state, setState] = useState<UpdateState>()
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  const [showNotes, setShowNotes] = useState(false)
  const notesTrigger = useRef<HTMLButtonElement>(null)
  const phase = state?.phase
  useEffect(() => {
    if (!native) return
    let disposed = false
    const poll = () =>
      request<UpdateState>({op: 'updateState'})
        .then((next) => !disposed && setState(next))
        .catch((e) => !disposed && setError(messageOf(e)))
    void poll()
    const timer = setInterval(poll, phase === 'checking' || phase === 'downloading' ? 800 : 4000)
    return () => {
      disposed = true
      clearInterval(timer)
    }
  }, [phase])
  async function run(op: Operation) {
    setPending(true)
    setError('')
    try {
      setState(await request<UpdateState>({op}))
      if (op === 'updateNotes') setShowNotes(true)
    } catch (e) {
      setError(messageOf(e))
    } finally {
      setPending(false)
    }
  }
  const installed = state?.mode === 'installed'
  const meta = useMeta()
  const channel: UpdateChannel = meta.settings.updateChannel === 'beta' ? 'beta' : 'stable'
  const betaNote = state ? betaStableNote({...state, channel}) : undefined
  const working = pending || phase === 'checking' || phase === 'downloading'
  return (
    <>
      <div className="about-card" id="setting-about">
        <Logo size={96} />
        <div>
          <h3>Keepfin</h3>
          <p className="muted tabular">
            {state ? `Version ${state.current} · ${editions[state.mode]}` : native ? 'Reading version…' : 'Browser preview'}
            {state && isPrerelease(state.current) && (
              <>
                {' '}
                <Badge tone="accent">Beta</Badge>
              </>
            )}
          </p>
          <p className="muted small">
            Save the media you choose, locally on your computer.
          </p>
        </div>
      </div>
      <Group id="updates" title="Updates" description="Keepfin itself. Your settings, history and files stay in place.">
        {native ? (
          <div className="update-status" role="status">
            <strong>{state ? titles[state.phase] : 'Loading…'}</strong>
            <span className="muted small tabular">
              {state
                ? `Installed ${state.current}${state.version ? ` · Latest ${state.version}` : ''}${state.checkedAt ? ` · Checked ${new Date(state.checkedAt).toLocaleString()}` : ''}`
                : ''}
            </span>
          </div>
        ) : (
          <p className="muted">Install the desktop app to receive updates.</p>
        )}
        {phase === 'downloading' && (
          <div className="update-progress">
            <ProgressBar value={state?.percent || 0} label="Update download" />
            <span className="tabular small">{Math.round(state?.percent || 0)}%</span>
          </div>
        )}
        <p className="muted small">
          {installed
            ? 'Updates download here; restart to finish. Your settings, history and files stay in place.'
            : state?.mode === 'portable'
              ? 'Portable edition: download the new Portable EXE from Releases, close this copy and replace the old EXE. Your profile stays in place.'
              : state?.mode === 'msi'
                ? 'MSI edition: install the new MSI from Releases over this one. Your settings, history and files stay in place.'
                : 'In-app installation is available in the Windows Setup edition.'}
        </p>
        {betaNote && <p className="muted small" role="status">{betaNote}</p>}
        {(error || state?.message) && <InlineAlert>{error || state?.message}</InlineAlert>}
        <OptionRow
          title="Update channel"
          description={channel === 'beta' ? 'Try new features early. Betas can have bugs.' : 'Tested releases. Recommended.'}
          control={
            <Segmented<UpdateChannel>
              label="Update channel"
              size="sm"
              value={channel}
              disabled={!native}
              onChange={(updateChannel) => void updateSettings({updateChannel})}
              options={[
                {value: 'stable', label: 'Stable'},
                {value: 'beta', label: 'Beta'},
              ]}
            />
          }
        />
        <div className="button-row" id="setting-whats-new">
          {installed && phase !== 'ready' && (
            <Button variant="primary" icon="refresh" loading={phase === 'checking'} disabled={working} onClick={() => void run('updateCheck')}>
              Check for updates
            </Button>
          )}
          {installed && (phase === 'available' || phase === 'error') && state?.version && (
            <Button variant="primary" icon="download" disabled={working} onClick={() => void run('updateDownload')}>
              Download {state.version}
            </Button>
          )}
          {phase === 'downloading' && <Button onClick={() => void run('updateCancel')}>Cancel download</Button>}
          {installed && phase === 'ready' && (
            <Button variant="primary" disabled={pending} onClick={() => void run('updateInstall')}>
              Restart and update
            </Button>
          )}
          {native && (
            <Button ref={notesTrigger} disabled={pending || !state || phase === 'checking'} onClick={() => void run('updateNotes')}>
              What’s new
            </Button>
          )}
          {native ? (
            <Button variant="ghost" icon="external" onClick={() => void run('updateReleases')}>
              All releases
            </Button>
          ) : (
            <a className="btn btn-ghost btn-md" href={releasePage} target="_blank" rel="noreferrer">
              <span className="btn-label">All releases</span>
            </a>
          )}
        </div>
      </Group>
      {showNotes && state && (
        <Suspense fallback={null}>
          <ReleaseNotes
            notes={state.notes || ''}
            version={state.notesVersion || state.version || state.current}
            close={() => {
              setShowNotes(false)
              notesTrigger.current?.focus()
            }}
            onError={setError}
          />
        </Suspense>
      )}
    </>
  )
}
