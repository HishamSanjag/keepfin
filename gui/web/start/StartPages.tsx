import {useState} from 'react'
import type {FolderChoice, StorageState} from '../../shared/storage'
import {introduces, pages, skipTo, words, type StartPage} from '../../shared/core/start'
import {messageOf} from '../bridge'
import {rememberStartMode, type KeepfinMode} from '../mode'
import {DownloaderPicture, LibraryPicture} from '../onboarding/ModePictures'
import {FirstStart, findKeepfinFolder, FoundFolder} from '../setup/FirstStart'
import {FolderStep, StepHeading} from '../setup/FolderStep'
import {FolderTreeArt} from '../setup/parts'
import {createFolder, openExisting} from '../setup/storageApi'
import {Button, InlineAlert, Spinner} from '../ui/controls'
import {Icon} from '../ui/Icon'

const featureIcons = ['download', 'play', 'refresh', 'layers']

/**
 * The start pages (dev-docs/plans/START-PAGES.md): Welcome, How it works, How you'll use it, Where your files live. A picture
 * on the left, the page on the right. The pages, their order and their words come from the shared core; the folder page
 * is the same FolderStep the detailed setup uses, and "Advanced setup" opens that detailed setup unchanged.
 */
export function StartPages({state, onDone}: {state: StorageState; onDone: () => void}) {
  const [page, setPage] = useState<StartPage>('WELCOME')
  const [mode, setMode] = useState<KeepfinMode>('full')
  const [advanced, setAdvanced] = useState(false)
  const [path, setPath] = useState(state.defaultPath)
  const [music, setMusic] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [found, setFound] = useState<FolderChoice | undefined>()
  // The app restarts once to open the new library (keepfinBoot): say so, so the closing window is expected.
  const [opening, setOpening] = useState(false)

  const index = pages.indexOf(page)
  const go = (step: number) => setPage(pages[Math.min(Math.max(index + step, 0), pages.length - 1)])
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
  // The answer to "How will you use Keepfin?" is saved once the folder (and with it the settings) exists.
  const finish = () => {
    rememberStartMode(mode)
    setOpening(true)
    onDone()
  }
  const open = (target: string) =>
    run(async () => {
      await openExisting(target)
      finish()
    })
  // FolderStep keeps Start using Keepfin disabled until the folder can be used (the core's canGoOn for the folder page).
  const create = () =>
    run(async () => {
      await createFolder(path, music)
      finish()
    })
  const findExisting = () =>
    run(async () => {
      const next = await findKeepfinFolder()
      if (next) setFound(next)
    })

  if (opening)
    return (
      <main className="start-opening" id="main" role="status">
        <Spinner />
        <strong>Opening your library…</strong>
        <span className="muted">Keepfin restarts once to open it. This takes a moment.</span>
      </main>
    )
  if (advanced) return <FirstStart state={state} onDone={finish} onBack={() => setAdvanced(false)} />

  const dots = (
    <span className="start-dots" role="img" aria-label={`Page ${index + 1} of ${pages.length}`}>
      {pages.map((name) => (
        <i key={name} className={name === page ? 'on' : undefined} />
      ))}
    </span>
  )
  return (
    <main className="start" id="main">
      <section className="start-art" aria-hidden="true">
        {page === 'WELCOME' && <LibraryPicture />}
        {page === 'HOW' && <HowArt />}
        {page === 'MODE' && (mode === 'full' ? <LibraryPicture /> : <DownloaderPicture />)}
        {page === 'FOLDER' && <FolderTreeArt />}
      </section>
      <div className="start-main" key={page}>
        <p className="start-eyebrow">{eyebrow(page)}</p>
        {page === 'FOLDER' ? (
          <FolderStep
            title={words.folderTitle}
            hint={words.folderHint}
            path={path}
            onPath={setPath}
            suggested={state.defaultPath}
            musicShortcut={music}
            onMusicShortcut={setMusic}
            onBack={() => go(-1)}
            onContinue={create}
            continueLabel={words.start}
            onOpenExisting={open}
            busy={busy}
            error={error}
            extra={
              <>
                <div className="start-links">
                  <button type="button" className="link-btn" disabled={busy} onClick={findExisting}>
                    {words.haveFolder}
                  </button>
                  <button type="button" className="link-btn" disabled={busy} onClick={() => setAdvanced(true)}>
                    {words.advanced} ›
                  </button>
                </div>
                <p className="setup-note muted">{words.advancedHint}</p>
                {found && <FoundFolder found={found} busy={busy} onOpen={open} />}
                <p className="setup-note muted">{words.folderLater}</p>
                {dots}
              </>
            }
          />
        ) : (
          <section className="setup-step">
            <StepHeading hint={page === 'WELCOME' ? words.welcomeBody : undefined}>{title(page)}</StepHeading>
            {page === 'WELCOME' && (
              <ul className="start-features">
                {words.features.map((feature, i) => (
                  <li key={feature.title}>
                    <span className="start-icon">
                      <Icon name={featureIcons[i] ?? 'sparkle'} size={18} />
                    </span>
                    <span>
                      <strong>{feature.title}</strong> {feature.body}
                    </span>
                  </li>
                ))}
              </ul>
            )}
            {page === 'WELCOME' && (
              <div className="start-sites">
                <span className="start-sites-title">{words.sitesTitle}</span>
                <ul className="start-chips" aria-label={words.sitesTitle}>
                  {words.sites.map((site) => (
                    <li key={site}>{site}</li>
                  ))}
                  <li className="start-chip-more">{words.sitesMore}</li>
                </ul>
                <p className="setup-note muted">{words.musicLinks}</p>
              </div>
            )}
            {page === 'HOW' && (
              <ol className="start-steps">
                {words.steps.map((step, i) => (
                  <li key={step.title}>
                    <span className="start-number" aria-hidden="true">
                      {i + 1}
                    </span>
                    <span>
                      <strong>{step.title}</strong>
                      <span className="muted">{step.body}</span>
                    </span>
                  </li>
                ))}
              </ol>
            )}
            {page === 'HOW' && (
              <div className="start-tip">
                <Icon name="key" size={18} />
                <span>
                  <strong>{words.signIn.title}</strong>
                  <span className="muted">{words.signIn.body}</span>
                </span>
              </div>
            )}
            {page === 'MODE' && (
              <>
                <div className="mode-choices" role="radiogroup" aria-label="Keepfin mode">
                  {(['full', 'downloader'] as const).map((choice) => {
                    const line = choice === 'full' ? words.complete : words.downloads
                    return (
                      <label key={choice} className={`mode-choice${mode === choice ? ' on' : ''}`}>
                        <input type="radio" name="keepfin-mode" value={choice} checked={mode === choice} onChange={() => setMode(choice)} />
                        {choice === 'full' ? <LibraryPicture /> : <DownloaderPicture />}
                        <span className="mode-text">
                          <strong>
                            {line.title}
                            {choice === 'full' && <span className="start-chip">{words.recommended}</span>}
                          </strong>
                          <span>{line.body}</span>
                        </span>
                      </label>
                    )
                  })}
                </div>
                <p className="setup-note muted">{words.modeLater}</p>
              </>
            )}
            {error && <InlineAlert tone="error">{error}</InlineAlert>}
            {dots}
            <div className="setup-actions">
              {introduces(page) && (
                <Button variant="ghost" className="start-skip" onClick={() => setPage(skipTo)}>
                  {words.skipIntro}
                </Button>
              )}
              {index > 0 && <Button onClick={() => go(-1)}>{words.back}</Button>}
              <Button variant="primary" autoFocus onClick={() => go(1)}>
                {words.next}
              </Button>
            </div>
          </section>
        )}
      </div>
    </main>
  )
}

const eyebrow = (page: StartPage) =>
  ({WELCOME: words.welcomeEyebrow, HOW: words.howEyebrow, MODE: words.modeEyebrow, FOLDER: words.folderEyebrow, PERMISSIONS: ''})[page]
const title = (page: StartPage) => ({WELCOME: words.welcomeTitle, HOW: words.howTitle, MODE: words.modeTitle, FOLDER: words.folderTitle, PERMISSIONS: ''})[page]

/** How it works, drawn: a link pasted, what it holds with a choice, and the result in the library. Decorative. */
function HowArt() {
  return (
    <div className="start-how-art">
      <div className="start-card start-paste">
        <span className="start-link">https://music.youtube.com/playlist?…</span>
        <span className="start-pill">Paste</span>
      </div>
      <div className="start-card">
        <div className="start-row">
          <span className="start-cover" />
          <span>
            <strong>Playlist</strong>
            <span className="muted">24 songs</span>
          </span>
        </div>
        <div className="start-chips">
          <span className="on">Song · MP3</span>
          <span>Video · 1080p</span>
          <span>Lyrics</span>
        </div>
        <span className="start-pill start-pill-end">Download 24</span>
      </div>
      <div className="start-card start-row">
        <Icon name="check" size={16} />
        <span>Saved to your library</span>
      </div>
    </div>
  )
}
