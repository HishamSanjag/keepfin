import {useEffect, useState} from 'react'
import {formatBytes} from '../../shared/contracts'
import type {
  SetupCounts,
  SetupInventory,
  SetupPhase,
  SetupPlan,
  SetupProgress,
  SetupReport,
  SourceChoice,
  StorageState,
} from '../../shared/storage'
import {messageOf, onPush} from '../bridge'
import {plural} from '../format'
import {Badge, Button, ConfirmInline, InlineAlert, ProgressBar, Spinner} from '../ui/controls'
import {Icon, Mark} from '../ui/Icon'
import {FolderStep, StepHeading, StepIndicator} from './FolderStep'
import {PathCrumbs, SetupHero, SpaceBar, sourceIcon} from './parts'
import {abandonSetup, getInventory, getPlan, getReport, saveReport, startSetup} from './storageApi'

const steps = ['Welcome', 'Your files', 'Where', 'How', 'Review', 'Bring over', 'Report']
const WELCOME = 0
const WHAT = 1
const WHERE = 2
const HOW = 3
const REVIEW = 4
const RUN = 5
const DONE = 6

/** What the Keepfin folder gives, shown before anything is counted or chosen. */
const gains: Array<{icon: 'folder' | 'layers' | 'globe'; title: string; text: string}> = [
  {icon: 'folder', title: 'One folder for everything', text: 'Music, videos, photos, playlists and history in one place you choose, easy to back up or move.'},
  {icon: 'layers', title: 'Copies where you want them', text: 'Keep a copy on another drive, a USB stick or a cloud account, and Keepfin keeps it up to date.'},
  {icon: 'globe', title: 'Your devices share it', text: 'Pair your PC and your phone, and they sync the library and play from each other.'},
]

/** The steps ahead, in plain words. */
const ahead = [
  'Keepfin counts what you have today: your files, playlists and history.',
  'You choose where your Keepfin folder goes.',
  'For each folder you have today, you choose: move it, copy it or leave it where it is.',
  'You check the plan. Nothing happens until you press Start.',
]

const countLabels: Array<[keyof SetupCounts, string, string]> = [
  ['media', 'Songs, videos and photos', 'stack'],
  ['sidecars', 'Extra files (subtitles, notes)', 'subtitles'],
  ['history', 'Download history entries', 'clock'],
  ['playlists', 'Playlists', 'queue'],
  ['collections', 'Followed collections', 'layers'],
  ['favourites', 'Favourites', 'heart'],
  ['userTags', 'Tags you added', 'tag'],
  ['art', 'Artwork', 'image'],
]

const phaseWords: Record<SetupPhase, string> = {
  preparing: 'Getting ready',
  copying: 'Copying your files',
  verifying: 'Checking the copies',
  saving: 'Saving your library',
  cleaning: 'Tidying up the old files',
  done: 'Finished',
  failed: 'Stopped by a problem',
  stoppedDiskFull: 'Stopped: the drive is full',
}

const choiceInfo: Record<SourceChoice, {label: string; icon: string; text: string}> = {
  move: {label: 'Move', icon: 'move', text: 'Copy the files, check every one, then send the old files to the Recycle Bin.'},
  copy: {label: 'Copy', icon: 'copy', text: 'Copy the files and keep the originals where they are.'},
  leave: {label: 'Leave where it is', icon: 'link', text: 'Keep the folder where it is. Keepfin lists it as a linked folder.'},
}

/** The phases of a run as a stepper: which phases belong to which step. */
const phaseSteps: Array<{name: string; phases: SetupPhase[]}> = [
  {name: 'Copy', phases: ['preparing', 'copying']},
  {name: 'Check', phases: ['verifying']},
  {name: 'Save', phases: ['saving']},
  {name: 'Tidy', phases: ['cleaning']},
]
const phaseIndex = (phase: SetupPhase | undefined) => (phase === 'done' ? phaseSteps.length : phase ? phaseSteps.findIndex((step) => step.phases.includes(phase)) : 0)

/** The arrow label for a source in the plan. */
const flowWord: Record<SourceChoice, string> = {move: 'Move', copy: 'Copy', leave: 'Linked'}

/** Before the save point nothing has been touched: stopping is safe. */
const stoppable: SetupPhase[] = ['preparing', 'copying', 'verifying']

/** Full-window guided setup for people who already have files from an earlier version (and the resume of an interrupted run). */
export function SetupWindow({state, onDone}: {state: StorageState; onDone: () => void}) {
  const resuming = state.phase === 'resumeSetup' && !!state.runId
  const [step, setStep] = useState(resuming ? RUN : WELCOME)
  const [inventory, setInventory] = useState<SetupInventory | undefined>()
  const [path, setPath] = useState(state.defaultPath)
  const [music, setMusic] = useState(true)
  const [choices, setChoices] = useState<Record<string, SourceChoice>>({})
  const [plan, setPlan] = useState<SetupPlan | undefined>()
  const [runId, setRunId] = useState(resuming ? state.runId : undefined)
  const [progress, setProgress] = useState<SetupProgress | undefined>()
  const [report, setReport] = useState<SetupReport | undefined>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)

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

  const loadInventory = () =>
    run(async () => {
      const next = await getInventory()
      setInventory(next)
      setChoices(Object.fromEntries(next.sources.map((source) => [source.id, source.suggested])))
    })
  useEffect(() => {
    if (!resuming) void loadInventory()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Progress pushes are collected for the whole life of the window, so a run that starts or resumes is never missed.
  useEffect(
    () =>
      onPush((event) => {
        if (event.type === 'setup') setProgress(event.progress)
      }),
    [],
  )
  // After a restart nothing is running: ask main to pick the interrupted run up from its journal.
  useEffect(() => {
    if (!resuming || !state.runId) return
    startSetup(state.runId).catch((caught) => setError(messageOf(caught)))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const live = progress && (!runId || progress.runId === runId) ? progress : undefined
  const running = step === RUN && (!live || stoppable.includes(live.phase) || live.phase === 'saving' || live.phase === 'cleaning')

  // A running setup cannot be closed by accident.
  useEffect(() => {
    if (!running) return
    const block = (event: BeforeUnloadEvent) => event.preventDefault()
    window.addEventListener('beforeunload', block)
    return () => window.removeEventListener('beforeunload', block)
  }, [running])

  // The run finished: fetch the report.
  const finished = step === RUN && live?.phase === 'done'
  useEffect(() => {
    if (!finished || !live) return
    void run(async () => {
      setReport(await getReport(live.runId))
      setStep(DONE)
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [finished])

  const review = () =>
    run(async () => {
      const next = await getPlan(path, choices)
      setPlan(next)
      setRunId(next.runId)
      setProgress(undefined)
      setStep(REVIEW)
    })
  const begin = () =>
    run(async () => {
      if (!plan) return
      setProgress(undefined)
      setStep(RUN)
      await startSetup(plan.runId)
    })
  const retry = () =>
    run(async () => {
      if (!runId) return
      setProgress(undefined)
      await startSetup(runId)
    })
  const stop = async () => {
    if (!runId) return
    setError('')
    try {
      await abandonSetup(runId)
      setPlan(undefined)
      setRunId(undefined)
      setProgress(undefined)
      setStep(WHAT)
      await loadInventory()
    } catch (caught) {
      setError(messageOf(caught))
    }
  }
  const save = () =>
    run(async () => {
      if (!report) return
      await saveReport(report.runId)
      setSaved(true)
    })

  const maxBytes = Math.max(0, ...(inventory?.sources.map((source) => source.bytes) ?? [0]))
  const diskFull = step === RUN && live?.phase === 'stoppedDiskFull'
  const failed = step === RUN && live?.phase === 'failed'

  const at = phaseIndex(live?.phase)

  return (
    <main className="setup-page" id="main">
      <div className="setup-col">
        <div className="setup-brand">
          <Mark size={28} />
          <span>Keepfin setup</span>
        </div>
        <StepIndicator steps={steps} current={step} label="Setup steps" />

        {step === WELCOME && (
          <section className="setup-step">
            <StepHeading>Keepfin now keeps everything in one folder</StepHeading>
            <SetupHero>
              Until now, what you downloaded was spread over a few folders on this PC. From this version on, everything you keep lives together in one Keepfin folder. This setup brings what you already have into it.
            </SetupHero>
            <ul className="setup-gains" aria-label="What the Keepfin folder gives you">
              {gains.map((gain) => (
                <li key={gain.title}>
                  <span className="setup-card-icon">
                    <Icon name={gain.icon} size={20} />
                  </span>
                  <div className="setup-card-text">
                    <strong>{gain.title}</strong>
                    <span>{gain.text}</span>
                  </div>
                </li>
              ))}
            </ul>
            <div className="setup-ahead">
              <h2>What happens next</h2>
              <ol>
                {ahead.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ol>
            </div>
            <p className="setup-prose">
              Nothing is removed until its copy has been checked, and your library, playlists and history come with your files. It takes a few minutes; you can
              close Keepfin at any point and it carries on where it stopped.
            </p>
            <div className="setup-actions">
              <Button variant="primary" onClick={() => setStep(WHAT)}>
                Get started
              </Button>
            </div>
          </section>
        )}

        {step === WHAT && (
          <section className="setup-step" aria-busy={busy || undefined}>
            <StepHeading hint="This is what Keepfin found from the earlier version. All of it can come into your Keepfin folder; you choose how in the next steps.">
              What you have today
            </StepHeading>
            {!inventory && busy && (
              <p className="setup-sub">
                <Spinner /> Counting your files…
              </p>
            )}
            {error && (
              <InlineAlert
                tone="error"
                actions={
                  <Button onClick={loadInventory} disabled={busy}>
                    Try again
                  </Button>
                }
              >
                {error}
              </InlineAlert>
            )}
            {inventory && (
              <>
                <ul className="source-cards" aria-label="Where your files are today">
                  {inventory.sources.map((source) => (
                    <li key={source.id} className="source-card" data-empty={!source.files || undefined}>
                      <span className="source-card-icon" aria-hidden="true">
                        <Icon name={sourceIcon(source.name)} size={22} />
                      </span>
                      <div className="source-card-text">
                        <div className="source-card-title">
                          <strong>{source.name}</strong>
                          <Badge tone={source.files ? 'accent' : 'neutral'}>{source.files ? plural(source.files, 'file') : 'Empty'}</Badge>
                        </div>
                        <span className="setup-path">{source.path}</span>
                        <div className="source-bar" role="img" aria-label={`${source.name} takes ${formatBytes(source.bytes) || '0 B'}`}>
                          <span style={{width: `${maxBytes > 0 ? Math.max(source.bytes > 0 ? 3 : 0, (source.bytes / maxBytes) * 100) : 0}%`}} />
                        </div>
                      </div>
                      <span className="setup-row-meta tabular">{source.files ? formatBytes(source.bytes) : 'Nothing to bring'}</span>
                    </li>
                  ))}
                </ul>
                <dl className="setup-counts" aria-label="What comes with your files">
                  {countLabels
                    .filter(([key]) => inventory.counts[key] > 0)
                    .map(([key, label, icon]) => (
                      <div key={key}>
                        <Icon name={icon} size={18} />
                        <dt>{label}</dt>
                        <dd className="tabular">{inventory.counts[key].toLocaleString()}</dd>
                      </div>
                    ))}
                </dl>
                {inventory.unindexed > 0 && (
                  <p className="setup-prose">
                    {plural(inventory.unindexed, 'file')} in your folders were not in your library yet. They come over and are added to it.
                  </p>
                )}
                {inventory.missing > 0 && (
                  <p className="setup-prose">
                    {plural(inventory.missing, 'library entry', 'library entries')} point to files that are no longer on disk. They come over as missing;
                    nothing is lost, and you can locate them later.
                  </p>
                )}
              </>
            )}
            <div className="setup-actions">
              <Button variant="ghost" disabled={busy} onClick={() => setStep(WELCOME)}>
                Back
              </Button>
              <Button variant="primary" disabled={!inventory || busy} onClick={() => setStep(WHERE)}>
                Continue
              </Button>
            </div>
          </section>
        )}

        {step === WHERE && (
          <FolderStep
            title="Where should your Keepfin folder go?"
            hint="Everything comes together in one folder. Pick a place with room for your files."
            path={path}
            onPath={setPath}
            suggested={state.defaultPath}
            musicShortcut={music}
            onMusicShortcut={setMusic}
            onBack={() => setStep(WHAT)}
            onContinue={() => setStep(HOW)}
            neededBytes={inventory?.sources.reduce((sum, source) => sum + source.bytes, 0)}
          />
        )}

        {step === HOW && inventory && (
          <section className="setup-step" aria-busy={busy || undefined}>
            <StepHeading
              hint={
                <>
                  Everything goes into <span className="setup-path-inline">{path}</span>. Choose what happens to each folder you have today.
                </>
              }
            >
              How should your files come over?
            </StepHeading>
            <div className="setup-sources">
              {inventory.sources.map((source) => (
                <fieldset key={source.id} className="setup-source" disabled={busy || !source.files}>
                  <legend>
                    <Icon name={sourceIcon(source.name)} size={18} /> {source.name}
                    <span className="setup-sub">
                      {source.files ? ` · ${plural(source.files, 'file')} · ${formatBytes(source.bytes)}` : ' · empty, nothing to bring'}
                    </span>
                  </legend>
                  {source.files > 0 && (
                    <>
                      <div className="setup-radios">
                        {(['move', 'copy', 'leave'] as SourceChoice[]).map((choice) => (
                          <label key={choice} className={`setup-radio${choices[source.id] === choice ? ' on' : ''}`}>
                            <input
                              type="radio"
                              name={`how-${source.id}`}
                              value={choice}
                              checked={choices[source.id] === choice}
                              onChange={() => setChoices((current) => ({...current, [source.id]: choice}))}
                            />
                            <span className="setup-radio-icon" aria-hidden="true">
                              <Icon name={choiceInfo[choice].icon} size={22} />
                            </span>
                            <span>
                              <strong>
                                {choiceInfo[choice].label}
                                {source.suggested === choice && <em className="setup-tag">Suggested</em>}
                              </strong>
                              <span>
                                {choiceInfo[choice].text}
                                {choice === 'move' && source.sameDrive ? ' On the same drive this is instant.' : ''}
                              </span>
                            </span>
                          </label>
                        ))}
                      </div>
                      <p className="setup-flow" aria-live="polite">
                        <span className="setup-path">{source.path}</span>
                        <Icon name="arrowLeft" size={14} className="flip" />
                        {choices[source.id] === 'leave' ? (
                          <span>stays here, linked from the Keepfin folder</span>
                        ) : (
                          <span className="setup-path">{path}</span>
                        )}
                      </p>
                    </>
                  )}
                </fieldset>
              ))}
            </div>
            {error && <InlineAlert tone="error">{error}</InlineAlert>}
            <div className="setup-actions">
              <Button variant="ghost" disabled={busy} onClick={() => setStep(WHERE)}>
                Back
              </Button>
              <Button variant="primary" loading={busy} onClick={review}>
                Review the plan
              </Button>
            </div>
          </section>
        )}

        {step === REVIEW && plan && (
          <section className="setup-step" aria-busy={busy || undefined}>
            <StepHeading hint="Check this over. Nothing happens until you press Start.">The plan</StepHeading>
            <div className="setup-diagram">
              <ul className="setup-diagram-from" aria-label="Where each folder goes">
                {(inventory?.sources ?? [])
                  .filter((source) => source.files > 0)
                  .map((source) => {
                    const choice = plan.choices[source.id] ?? source.suggested
                    return (
                      <li key={source.id}>
                        <span className="source-card-icon" aria-hidden="true">
                          <Icon name={sourceIcon(source.name)} size={20} />
                        </span>
                        <div className="source-card-text">
                          <strong>{source.name}</strong>
                          <span className="small muted tabular">
                            {plural(source.files, 'file')} · {formatBytes(source.bytes)}
                          </span>
                        </div>
                        <span className="setup-diagram-arrow" data-choice={choice}>
                          <em>{flowWord[choice]}</em>
                          <Icon name="chevronRight" size={16} />
                        </span>
                      </li>
                    )
                  })}
              </ul>
              <div className="setup-diagram-to">
                <Icon name="folder" size={30} />
                <strong>Your Keepfin folder</strong>
                <PathCrumbs path={plan.target} />
              </div>
            </div>
            <div className="setup-room" data-fits={plan.fits}>
              <div>
                <strong>{plan.bytesToCopy ? `${formatBytes(plan.bytesToCopy)} to copy` : 'Nothing to copy'}</strong>
                <span>
                  {plural(plan.files, 'file')} in all
                  {plan.freeBytes !== undefined && formatBytes(plan.freeBytes) ? ` · ${formatBytes(plan.freeBytes)} free at ${plan.target}` : ''}
                </span>
              </div>
              {plan.freeBytes !== undefined && <SpaceBar needed={plan.bytesToCopy} free={plan.freeBytes} neededLabel="To copy" />}
            </div>
            <details className="setup-lines">
              <summary>Every step in words</summary>
              <ul className="setup-plan" aria-label="What will happen">
                {plan.lines.map((line) => (
                  <li key={line}>
                    <Icon name="check" size={15} />
                    <span>{line}</span>
                  </li>
                ))}
              </ul>
            </details>
            {!plan.fits && (
              <InlineAlert tone="error" title="This does not fit">
                There is not enough free space at {plan.target}. Choose a drive with more room, or move instead of copying where the files are already on the same
                drive.
              </InlineAlert>
            )}
            {error && <InlineAlert tone="error">{error}</InlineAlert>}
            <div className="setup-actions">
              <Button variant="ghost" disabled={busy} onClick={() => setStep(HOW)}>
                Back
              </Button>
              {!plan.fits && (
                <Button disabled={busy} onClick={() => setStep(WHERE)}>
                  Choose another folder
                </Button>
              )}
              <Button variant="primary" loading={busy} disabled={!plan.fits} onClick={begin}>
                Start
              </Button>
            </div>
          </section>
        )}

        {step === RUN && (
          <section className="setup-step" aria-busy={running || undefined}>
            <StepHeading hint="You can leave this window open and keep using your PC. If you close Keepfin, it continues next time.">
              Bringing it over
            </StepHeading>
            <div className="setup-run" aria-live="polite">
              <ol className="setup-phases" aria-label="Where the setup is">
                {phaseSteps.map((phase, index) => (
                  <li key={phase.name} data-state={index < at ? 'done' : index === at ? 'now' : 'next'}>
                    <span className="setup-step-dot" aria-hidden="true">
                      {index < at ? <Icon name="check" size={13} /> : index + 1}
                    </span>
                    <span>{phase.name}</span>
                  </li>
                ))}
              </ol>
              <div className="setup-run-phase">
                {running && <Spinner />}
                <strong>{live ? phaseWords[live.phase] : resuming ? 'Picking up where it stopped…' : 'Getting ready…'}</strong>
              </div>
              {live && live.total > 0 && (
                <>
                  <ProgressBar
                    label="Setup progress"
                    value={live.bytesTotal > 0 ? (live.bytesDone / live.bytesTotal) * 100 : (live.done / live.total) * 100}
                  />
                  <div className="setup-run-meta tabular">
                    <span>
                      {live.done.toLocaleString()} of {live.total.toLocaleString()} files
                    </span>
                    {live.bytesTotal > 0 && (
                      <span>
                        {formatBytes(live.bytesDone) || '0 B'} of {formatBytes(live.bytesTotal)}
                      </span>
                    )}
                  </div>
                </>
              )}
              {live?.current && <div className="setup-run-current">{live.current}</div>}
              {!!live?.failed && (
                <p className="setup-prose">
                  {plural(live.failed, 'file')} could not be copied yet. They stay where they are, and you can see them in the report.
                </p>
              )}
            </div>
            {diskFull && (
              <InlineAlert
                tone="warning"
                title="The drive ran out of space"
                actions={
                  <Button
                    variant="primary"
                    onClick={() => {
                      setPlan(undefined)
                      setProgress(undefined)
                      setStep(HOW)
                      if (!inventory) void loadInventory()
                    }}
                  >
                    Change the plan
                  </Button>
                }
              >
                Nothing was lost and your originals are untouched. Free some space or change what moves, then try again.
              </InlineAlert>
            )}
            {failed && (
              <InlineAlert
                tone="error"
                title="Setup stopped"
                actions={
                  <Button variant="primary" loading={busy} onClick={retry}>
                    Try again
                  </Button>
                }
              >
                Something went wrong while bringing your files over. Your originals are safe.
              </InlineAlert>
            )}
            {error && <InlineAlert tone="error">{error}</InlineAlert>}
            <div className="setup-actions">
              {(!live || stoppable.includes(live.phase)) && (
                <ConfirmInline
                  label="Stop"
                  confirmLabel="Stop and discard"
                  prompt="Stop now? Your original files are untouched."
                  size="md"
                  onConfirm={stop}
                />
              )}
            </div>
          </section>
        )}

        {step === DONE && report && (
          <section className="setup-step" aria-busy={busy || undefined}>
            <StepHeading
              hint={
                report.attention.length
                  ? `${plural(report.attention.length, 'file')} need your attention below. Everything else is in place.`
                  : 'Counted before and after, everything matches.'
              }
            >
              {report.attention.length ? 'Nearly there' : 'Everything is in your Keepfin folder'}
            </StepHeading>
            <ul className="setup-tiles" aria-label="Counted before and after">
              {countLabels
                .filter(([key]) => report.before[key] > 0 || report.after[key] > 0)
                .map(([key, label, icon]) => {
                  const same = report.before[key] === report.after[key]
                  return (
                    <li key={key} data-ok={same}>
                      <Icon name={icon} size={18} />
                      <span className="setup-tile-label">{label}</span>
                      <span className="setup-tile-numbers tabular">
                        <span>
                          <small>Before</small>
                          {report.before[key].toLocaleString()}
                        </span>
                        <Icon name="chevronRight" size={14} />
                        <span>
                          <small>Now</small>
                          {report.after[key].toLocaleString()}
                        </span>
                      </span>
                      {same ? (
                        <span className="setup-tile-ok">
                          <Icon name="check" size={15} />
                          <span className="sr-only">Matches</span>
                        </span>
                      ) : (
                        <span className="setup-flag">Needs attention</span>
                      )}
                    </li>
                  )
                })}
            </ul>
            {report.attention.length > 0 && (
              <ul className="setup-attention-cards" aria-label="Files that need attention">
                {report.attention.map((item) => (
                  <li key={item.name}>
                    <Icon name="warning" size={18} />
                    <div className="setup-row-text">
                      <strong>{item.name}</strong>
                      <span>{item.reason}</span>
                    </div>
                    {item.actions.length > 0 && (
                      // what the person can do later (from the library); plain text, not buttons, because nothing here acts on it
                      <span className="setup-attention-actions small muted">
                        You can {item.actions.map((action) => (action === 'retry' ? 'retry' : action === 'locate' ? 'locate it' : 'leave it')).join(' or ')}.
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {error && <InlineAlert tone="error">{error}</InlineAlert>}
            {saved && <InlineAlert tone="info">The report is saved.</InlineAlert>}
            <div className="setup-actions">
              <Button disabled={busy} onClick={save}>
                Save report
              </Button>
              <Button variant="primary" onClick={onDone}>
                Done
              </Button>
            </div>
          </section>
        )}
      </div>
    </main>
  )
}