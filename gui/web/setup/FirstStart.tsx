import {useState} from 'react'
import type {FolderChoice, StorageState} from '../../shared/storage'
import {messageOf} from '../bridge'
import {Button, InlineAlert} from '../ui/controls'
import {Icon, Mark} from '../ui/Icon'
import {keepfinInside} from './folderPlace'
import {FolderStep, StepHeading, StepIndicator} from './FolderStep'
import {ChoiceCard, PathCrumbs, SetupHero} from './parts'
import {createFolder, inspectFolder, openExisting, pickFolder} from './storageApi'

const steps = ['Welcome', 'Where', 'Extra copies']

/** Places a copy can live: shown as small chips on the last step. */
const copyPlaces: {icon: string; name: string}[] = [
  {icon: 'folder', name: 'A folder on another disk'},
  {icon: 'download', name: 'A USB stick or backup drive'},
  {icon: 'globe', name: 'A cloud account'},
  {icon: 'layers', name: 'A paired phone or PC'},
]

/** "I have a Keepfin folder": the folder picked, or the Keepfin folder inside it (F:\ for F:\Keepfin); undefined when nothing was picked. */
export async function findKeepfinFolder(): Promise<FolderChoice | undefined> {
  const picked = await pickFolder()
  if (!picked) return undefined
  const found = await inspectFolder(picked)
  if (found.isKeepfinFolder) return found
  const inside = await inspectFolder(keepfinInside(picked))
  return inside.isKeepfinFolder ? inside : found
}

/** What "I have a Keepfin folder" found: open it, open a newer one read-only, or say there is none. */
export function FoundFolder({found, busy, onOpen}: {found: FolderChoice; busy: boolean; onOpen: (path: string) => void}) {
  return (
    <div className="setup-result" aria-live="polite">
      {found.isKeepfinFolder && !found.newerFormat && !found.problem && (
        <InlineAlert
          tone="info"
          title="Found a Keepfin folder"
          actions={
            <Button variant="primary" disabled={busy} onClick={() => onOpen(found.path)}>
              Open it
            </Button>
          }
        >
          {found.path}
        </InlineAlert>
      )}
      {found.isKeepfinFolder && found.newerFormat && (
        <InlineAlert
          tone="warning"
          title="Made by a newer Keepfin"
          actions={
            <Button disabled={busy} onClick={() => onOpen(found.path)}>
              Open read-only
            </Button>
          }
        >
          {found.path} was made by a newer version of the app. Update Keepfin to change it.
        </InlineAlert>
      )}
      {found.isKeepfinFolder && !found.newerFormat && found.problem && <InlineAlert tone="error">{found.problem}</InlineAlert>}
      {!found.isKeepfinFolder && (
        <InlineAlert tone="warning" title="No Keepfin folder there">
          {found.path} does not hold a Keepfin folder. Choose another one, or start fresh.
        </InlineAlert>
      )}
    </div>
  )
}

/**
 * The detailed first run, opened from the start pages' "Advanced setup": new here, or bringing a Keepfin folder; where
 * it lives; extra copies; then create it. `onBack` on its first step returns to the start pages.
 */
export function FirstStart({state, onDone, onBack}: {state: StorageState; onDone: () => void; onBack?: () => void}) {
  const [step, setStep] = useState(0)
  const [path, setPath] = useState(state.defaultPath)
  const [music, setMusic] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  // "I have a Keepfin folder": what the picked folder turned out to be.
  const [found, setFound] = useState<FolderChoice | undefined>()
  const [looked, setLooked] = useState(false)

  async function run(work: () => Promise<void>) {
    setBusy(true)
    setError('')
    try {
      await work()
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setBusy(false)
    }
  }
  const choose = () =>
    run(async () => {
      const next = await findKeepfinFolder()
      if (!next) return
      setFound(next)
      setLooked(true)
    })
  const open = (target: string) =>
    run(async () => {
      await openExisting(target)
      onDone()
    })
  const create = () =>
    run(async () => {
      await createFolder(path, music)
      onDone()
    })

  return (
    <main className="setup-page" id="main">
      <div className="setup-col">
        <div className="setup-brand">
          <Mark size={28} />
          <span>Welcome to Keepfin</span>
        </div>
        <StepIndicator steps={steps} current={step} label="First start steps" />
        {step === 0 && (
          <section className="setup-step" aria-busy={busy || undefined}>
            <StepHeading>New to Keepfin, or bringing a Keepfin folder?</StepHeading>
            <SetupHero>
              Everything you keep (music, videos, photos, playlists and history) lives in one Keepfin folder on this PC.
            </SetupHero>
            <div className="setup-choices">
              <ChoiceCard icon="sparkle" title="I am new here" text="Start fresh. Next you choose where Keepfin keeps its folder.">
                <Button variant="primary" disabled={busy} onClick={() => setStep(1)}>
                  Start fresh
                </Button>
              </ChoiceCard>
              <ChoiceCard icon="folder" title="I have a Keepfin folder" text="From another computer, a phone or a backup drive. Choose it and your library, playlists and history come back.">
                <Button loading={busy} onClick={choose}>
                  Choose the folder…
                </Button>
              </ChoiceCard>
            </div>
            {looked && found && <FoundFolder found={found} busy={busy} onOpen={open} />}
            {error && <InlineAlert tone="error">{error}</InlineAlert>}
            {onBack && (
              <div className="setup-actions">
                <Button variant="ghost" disabled={busy} onClick={onBack}>
                  Back
                </Button>
              </div>
            )}
          </section>
        )}
        {step === 1 && (
          <FolderStep
            title="Where should Keepfin keep everything?"
            hint="Pick a place with room to grow. Your music, videos and library live here."
            path={path}
            onPath={setPath}
            suggested={state.defaultPath}
            musicShortcut={music}
            onMusicShortcut={setMusic}
            onBack={() => setStep(0)}
            onContinue={() => setStep(2)}
            onOpenExisting={open}
            busy={busy}
            error={error}
          />
        )}
        {step === 2 && (
          <section className="setup-step" aria-busy={busy || undefined}>
            <StepHeading hint="A copy is a drive or folder that keeps your Keepfin folder up to date: a backup drive, a USB stick or a folder on another disk.">
              Keep extra copies?
            </StepHeading>
            <div className="setup-copies-art">
              <div className="setup-copies-main">
                <Icon name="folder" size={22} />
                <div>
                  <strong>Your Keepfin folder</strong>
                  <PathCrumbs path={path} />
                </div>
              </div>
              <Icon name="chevronRight" size={18} />
              <ul className="setup-copies-places" aria-label="Places a copy can live">
                {copyPlaces.map((place) => (
                  <li key={place.name}>
                    <Icon name={place.icon} size={16} />
                    <span>{place.name}</span>
                  </li>
                ))}
              </ul>
            </div>
            <p className="setup-prose">
              Your Keepfin folder will be <strong className="setup-path-inline">{path}</strong>. You can add a copy on another drive or folder at any time in Settings → Storage & copies → Copies, so there is nothing to decide now.
            </p>
            {error && <InlineAlert tone="error">{error}</InlineAlert>}
            <div className="setup-actions">
              <Button variant="ghost" disabled={busy} onClick={() => setStep(1)}>
                Back
              </Button>
              <Button variant="primary" loading={busy} onClick={create}>
                Create my Keepfin folder
              </Button>
            </div>
          </section>
        )}
      </div>
    </main>
  )
}
