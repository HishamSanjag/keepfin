import {useEffect, useRef, useState, type ReactNode} from 'react'
import {formatBytes} from '../../shared/contracts'
import type {CloudApp, FolderChoice} from '../../shared/storage'
import {messageOf} from '../bridge'
import {Button, InlineAlert, Spinner, Switch} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {SettingCard} from '../ui/SettingCard'
import {isDriveRoot, placeFor} from './folderPlace'
import {PathCrumbs, SpaceBar} from './parts'
import {inspectFolder, pickFolder} from './storageApi'

export const cloudNames: Record<CloudApp, string> = {
  'google-drive': 'Google Drive',
  onedrive: 'OneDrive',
  dropbox: 'Dropbox',
  icloud: 'iCloud Drive',
}

/** The numbered step list across the top of a full-window flow. */
export function StepIndicator({steps, current, label}: {steps: string[]; current: number; label: string}) {
  return (
    <nav aria-label={label}>
      <ol className="setup-steps">
        {steps.map((name, index) => (
          <li key={name} aria-current={index === current ? 'step' : undefined} data-state={index < current ? 'done' : index === current ? 'now' : 'next'}>
            <span className="setup-step-dot" aria-hidden="true">
              {index < current ? <Icon name="check" size={13} /> : index + 1}
            </span>
            <span className="setup-step-name">{name}</span>
          </li>
        ))}
      </ol>
    </nav>
  )
}

/** The step heading; focus moves here whenever a step appears so keyboard and screen reader users land at its start. */
export function StepHeading({children, hint}: {children: ReactNode; hint?: ReactNode}) {
  const ref = useRef<HTMLHeadingElement>(null)
  useEffect(() => ref.current?.focus(), [])
  return (
    <header className="setup-head">
      <h1 ref={ref} tabIndex={-1}>
        {children}
      </h1>
      {hint && <p>{hint}</p>}
    </header>
  )
}

/** The standing cloud tip (rule 3, decision D12): advised against, never forbidden. */
export function CloudTip({app}: {app: CloudApp}) {
  return (
    <InlineAlert tone="warning" title={`This folder is inside ${cloudNames[app]}`}>
      Keepfin works there, but a cloud app can lock or re-upload files while Keepfin is using them. Keeping the folder on this PC and adding a cloud
      copy later in Settings → Storage & copies is safer.
    </InlineAlert>
  )
}

/**
 * The shared "Where" step: the suggested folder, a native picker, a live check of what is there,
 * and the Windows Music shortcut choice. Continue stays disabled until the folder can be used.
 */
export function FolderStep({
  title,
  hint,
  path,
  onPath,
  suggested,
  musicShortcut,
  onMusicShortcut,
  onBack,
  onContinue,
  continueLabel = 'Continue',
  onOpenExisting,
  busy,
  error,
  neededBytes,
  extra,
}: {
  title: ReactNode
  hint?: ReactNode
  path: string
  onPath: (path: string) => void
  suggested: string
  /** First start and setup: the Windows Music shortcut card (left out when moving the folder). */
  musicShortcut?: boolean
  onMusicShortcut?: (value: boolean) => void
  onBack?: () => void
  onContinue: () => void
  continueLabel?: string
  /** First start only: the chosen folder already is a Keepfin folder, open it instead. */
  onOpenExisting?: (path: string) => void
  busy?: boolean
  error?: string
  /** Setup only: how much the files to bring over take, to show against the free space. */
  neededBytes?: number
  /** Start pages: their links and page dots, just above the buttons. */
  extra?: ReactNode
}) {
  const [choice, setChoice] = useState<FolderChoice | undefined>()
  const [checking, setChecking] = useState(false)
  const [problem, setProblem] = useState('')
  const [picking, setPicking] = useState(false)
  // The folder the person picked when Keepfin's own folder goes inside it (F:\ → F:\Keepfin).
  const [madeIn, setMadeIn] = useState('')
  const usePath = (next: string) => {
    setMadeIn('')
    onPath(next)
  }
  useEffect(() => {
    let live = true
    setChoice(undefined)
    setProblem('')
    if (!path) return
    setChecking(true)
    inspectFolder(path)
      .then((next) => live && setChoice(next))
      .catch((caught) => live && setProblem(messageOf(caught)))
      .finally(() => live && setChecking(false))
    return () => {
      live = false
    }
  }, [path])
  async function change() {
    setPicking(true)
    setProblem('')
    try {
      const picked = await pickFolder()
      if (!picked) return
      const place = placeFor(picked, await inspectFolder(picked))
      setMadeIn(place === picked ? '' : picked)
      onPath(place)
    } catch (caught) {
      setProblem(messageOf(caught))
    } finally {
      setPicking(false)
    }
  }
  const existing = !!choice?.isKeepfinFolder
  const usable = !!choice && choice.writable && !choice.problem && !existing && !checking
  const filled = !!choice && choice.exists && !choice.empty && !existing
  return (
    <section className="setup-step" aria-busy={checking || busy || undefined}>
      <StepHeading hint={hint}>{title}</StepHeading>
      <div className="setup-folder">
        <Icon name="folder" size={20} />
        <div className="setup-folder-text">
          <div className="setup-path" data-testid="setup-path">
            {path || 'No folder chosen'}
          </div>
          <div className="setup-sub">
            {checking ? (
              <>
                <Spinner /> Checking the folder…
              </>
            ) : choice?.freeBytes !== undefined && formatBytes(choice.freeBytes) ? (
              `${formatBytes(choice.freeBytes)} free on this drive`
            ) : path === suggested ? (
              'Suggested place'
            ) : (
              ''
            )}
          </div>
        </div>
        <Button onClick={change} loading={picking} disabled={busy}>
          Change…
        </Button>
      </div>
      {path && <PathCrumbs path={path} />}
      {neededBytes !== undefined && neededBytes > 0 && choice?.freeBytes !== undefined && <SpaceBar needed={neededBytes} free={choice.freeBytes} neededLabel="Your files take" label="Your files against the free space on this drive" />}
      {madeIn && (
        <p className="setup-prose setup-note">
          Keepfin makes a new folder named Keepfin in <span className="setup-path-inline">{madeIn}</span>, so your files stay together and away from what
          is already there.{' '}
          {!isDriveRoot(madeIn) && (
            <button type="button" className="link-btn" disabled={busy} onClick={() => usePath(madeIn)}>
              Use {madeIn} itself
            </button>
          )}
        </p>
      )}
      {path !== suggested && suggested && (
        <p className="setup-note">
          <button type="button" className="link-btn" disabled={busy} onClick={() => usePath(suggested)}>
            Use the suggested folder instead ({suggested})
          </button>
        </p>
      )}
      <div className="setup-notes">
        {choice?.insideCloudApp && <CloudTip app={choice.insideCloudApp} />}
        {choice?.problem && !existing && <InlineAlert tone="error">{choice.problem}</InlineAlert>}
        {choice && !choice.writable && !choice.problem && <InlineAlert tone="error">Keepfin cannot write to this folder. Pick another one.</InlineAlert>}
        {filled && (
          <InlineAlert tone="info">
            This folder already has files in it. Keepfin adds its own folders inside and leaves everything that is there alone.
          </InlineAlert>
        )}
        {existing && !choice?.newerFormat && (
          <InlineAlert
            tone="info"
            title="This is already a Keepfin folder"
            actions={
              onOpenExisting ? (
                <Button variant="primary" disabled={busy} onClick={() => onOpenExisting(path)}>
                  Open it
                </Button>
              ) : undefined
            }
          >
            {onOpenExisting
              ? 'Open it to carry on with your library, or pick a different folder for a new one.'
              : 'Pick a different folder to bring your files into.'}
          </InlineAlert>
        )}
        {existing && choice?.newerFormat && (
          <InlineAlert tone="warning" title="Made by a newer Keepfin">
            This Keepfin folder was made by a newer version of the app. Update Keepfin to use it, or pick a different folder.
          </InlineAlert>
        )}
        {problem && <InlineAlert tone="error">{problem}</InlineAlert>}
        {error && <InlineAlert tone="error">{error}</InlineAlert>}
      </div>
      {onMusicShortcut && (
        <SettingCard
          icon="music"
          title="Show my music in Windows Music"
          description="Adds a Keepfin shortcut to your Music folder. The songs stay in the Keepfin folder."
          control={<Switch label="Show my music in Windows Music" checked={!!musicShortcut} disabled={busy} onChange={onMusicShortcut} />}
        />
      )}
      {extra}
      <div className="setup-actions">
        {onBack && (
          <Button variant="ghost" onClick={onBack} disabled={busy}>
            Back
          </Button>
        )}
        <Button variant="primary" disabled={!usable || busy} loading={busy} onClick={onContinue}>
          {continueLabel}
        </Button>
      </div>
    </section>
  )
}
