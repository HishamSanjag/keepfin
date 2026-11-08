import {useEffect, useRef, useState} from 'react'
import {downloadPresets, type CookieBrowser, type DownloadOptions, type DownloadPreset, type FilenameStyle} from '../../shared/contracts'
import {native} from '../bridge'
import {ExtrasGroups} from '../preview/Extras'
import {validateOptions} from '../preview/extrasModel'
import {act, updateSettings, useMeta} from '../store'
import {signInTo, signOutOf, useSignIns} from '../signin'
import {Button, InlineAlert, Segmented, Select, Stepper, Switch} from '../ui/controls'
import {Group, SettingCard, SettingExpander} from '../ui/SettingCard'

function DefaultOptions() {
  const meta = useMeta()
  const [draft, setDraft] = useState<DownloadOptions>(meta.settings.options)
  const saved = useRef(JSON.stringify(meta.settings.options))
  useEffect(() => {
    const incoming = JSON.stringify(meta.settings.options)
    if (incoming !== saved.current) {
      saved.current = incoming
      setDraft(meta.settings.options)
    }
  }, [meta.settings.options])
  useEffect(() => {
    const value = JSON.stringify(draft)
    if (value === saved.current || validateOptions(draft) || !native) return
    const timer = setTimeout(() => {
      saved.current = value
      void updateSettings({options: draft})
    }, 450)
    return () => clearTimeout(timer)
  }, [draft])
  const error = validateOptions(draft)
  return (
    <>
      <ExtrasGroups options={draft} onChange={setDraft} mode="defaults" />
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
    </>
  )
}

const browsers: {value: CookieBrowser; label: string}[] = [
  {value: 'firefox', label: 'Firefox (recommended)'},
  {value: 'edge', label: 'Microsoft Edge'},
  {value: 'chrome', label: 'Google Chrome'},
  {value: 'brave', label: 'Brave'},
  {value: 'opera', label: 'Opera'},
  {value: 'vivaldi', label: 'Vivaldi'},
  {value: 'chromium', label: 'Chromium'},
]

/** The sites Keepfin itself is signed in to (through its sign-in window), with Sign out, and a way to sign in to another. */
function KeepfinSignIns({disabled}: {disabled: boolean}) {
  const state = useSignIns()
  const [pick, setPick] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  if (!state) return null
  const signed = state.sites.filter((site) => site.signedIn)
  const open = state.sites.filter((site) => !site.signedIn)
  const chosen = open.some((site) => site.key === pick) ? pick : (open[0]?.key ?? '')
  const hint = open.find((site) => site.key === chosen)?.hint
  const signIn = async () => {
    setBusy(true)
    setMessage('')
    const outcome = await signInTo(chosen)
    setBusy(false)
    if (outcome.message) setMessage(outcome.message)
  }
  return (
    <>
      {signed.map((site) => (
        <SettingCard
          key={site.key}
          icon="lock"
          title={`Signed in to ${site.name}`}
          description="Kept encrypted on this computer. Keepfin uses it when a link on this site asks for a sign-in."
          control={
            <Button variant="ghost" disabled={disabled} onClick={() => void signOutOf(site.key)} aria-label={`Sign out of ${site.name}`}>
              Sign out
            </Button>
          }
        />
      ))}
      {open.length > 0 && (
        <SettingCard
          icon="globe"
          title="Sign in to a site…"
          description={
            state.canKeep
              ? `A window opens with the site's own sign-in page. Keepfin never sees your password, only that you are signed in.${hint ? ` ${hint}.` : ''}`
              : 'This computer has no password store to keep a sign-in in, so Keepfin cannot save one.'
          }
          control={
            <div className="inline-form">
              <Select<string> label="Site" value={chosen} disabled={disabled || busy || !state.canKeep} onChange={setPick} options={open.map((site) => ({value: site.key, label: site.name}))} />
              <Button icon="key" loading={busy} disabled={disabled || !state.canKeep || !chosen} onClick={() => void signIn()}>
                Sign in…
              </Button>
            </div>
          }
        />
      )}
      {message && (
        <p className="field-error" role="status">
          {message}
        </p>
      )}
    </>
  )
}

function WebsiteSignIn() {
  const {settings, hasCookieFile} = useMeta()
  const disabled = !native
  const mode = settings.cookies.mode
  const chromium = mode === 'browser' && settings.cookies.browser && settings.cookies.browser !== 'firefox'
  return (
    <>
      <Group
        id="signin"
        cards
        title="Website sign-in"
        description="Cookies let the engine save private, age-restricted or members-only media that you can already watch while signed in. They never leave this computer."
      >
        <SettingCard
          icon="key"
          title="Use a sign-in when a site asks for one"
          description="Downloads stay signed out. Only when a site says it needs a sign-in does Keepfin try one it can use, and the download's row says so."
          control={<Switch label="Use a sign-in when a site asks for one" checked={settings.autoSignIn} disabled={disabled} onChange={(autoSignIn) => void updateSettings({autoSignIn})} />}
        />
        <KeepfinSignIns disabled={disabled} />
        <SettingCard
          icon="key"
          title="Use cookies from"
          control={
            <Segmented
              label="Cookie source"
              value={mode}
              disabled={disabled}
              onChange={(next) => void updateSettings({cookies: next === 'browser' ? {mode: next, browser: settings.cookies.browser || 'firefox'} : {mode: next}})}
              options={[
                {value: 'none', label: 'None'},
                {value: 'file', label: 'cookies.txt'},
                {value: 'browser', label: 'Browser'},
              ]}
            />
          }
        />
        {mode === 'file' && (
          <SettingCard
            icon="lock"
            title={hasCookieFile ? 'Cookie file imported' : 'No cookie file yet'}
            description={
              hasCookieFile
                ? 'Stored privately in the app profile. Re-import after signing in again if downloads start asking for sign-in.'
                : 'Export cookies.txt (Netscape format) with a browser extension while signed in, then import it here.'
            }
            control={
              <div className="inline-form">
                <Button icon="key" disabled={disabled} onClick={() => void act('importCookies')}>
                  {hasCookieFile ? 'Replace…' : 'Import cookies.txt…'}
                </Button>
                {hasCookieFile && (
                  <Button variant="ghost" disabled={disabled} onClick={() => void act('clearCookies')}>
                    Remove
                  </Button>
                )}
              </div>
            }
          />
        )}
        {mode === 'browser' && (
          <SettingCard
            icon="globe"
            title="Browser"
            description="Read at download time from the browser profile where you are signed in."
            control={
              <Select<CookieBrowser>
                label="Browser"
                value={settings.cookies.browser || 'firefox'}
                disabled={disabled}
                onChange={(browser) => void updateSettings({cookies: {mode: 'browser', browser}})}
                options={browsers}
              />
            }
          />
        )}
      </Group>
      {chromium && (
        <InlineAlert tone="warning" title="Chromium browsers can block this on Windows">
          Chrome, Edge, Brave and similar browsers lock and encrypt their cookie database while running. If downloads still ask for sign-in, close the browser first, use
          Firefox, or import a cookies.txt file instead.
        </InlineAlert>
      )}
    </>
  )
}

function validProxy(value: string) {
  if (!value) return true
  try {
    const url = new URL(value)
    return ['http:', 'https:', 'socks4:', 'socks4a:', 'socks5:', 'socks5h:'].includes(url.protocol) && !!url.hostname
  } catch {
    return false
  }
}

function ProxyCard() {
  const {settings} = useMeta()
  const [proxy, setProxy] = useState(settings.proxy)
  useEffect(() => setProxy(settings.proxy), [settings.proxy])
  const valid = validProxy(proxy.trim())
  const dirty = proxy.trim() !== settings.proxy
  return (
    <SettingCard anchor="proxy" icon="globe" title="Proxy" description="Route engine traffic through a proxy. Leave empty for a direct connection.">
      <div className="field-block">
        <label className="field-label" htmlFor="proxy-input">
          Proxy URL
        </label>
        <div className="inline-form">
          <input
            id="proxy-input"
            className={`input grow${!valid ? ' invalid' : ''}`}
            spellCheck={false}
            placeholder="socks5://127.0.0.1:1080"
            value={proxy}
            disabled={!native}
            aria-invalid={!valid}
            aria-describedby="proxy-help"
            onChange={(event) => setProxy(event.target.value)}
            onKeyDown={(event) => event.key === 'Enter' && valid && dirty && void updateSettings({proxy: proxy.trim()})}
          />
          <Button variant="primary" disabled={!native || !valid || !dirty} onClick={() => void updateSettings({proxy: proxy.trim()})}>
            Save
          </Button>
          {settings.proxy && (
            <Button variant="ghost" disabled={!native} onClick={() => void updateSettings({proxy: ''})}>
              Clear
            </Button>
          )}
        </div>
        <p id="proxy-help" className={valid ? 'field-help' : 'field-error'} role={valid ? undefined : 'alert'}>
          {valid ? 'Supports http://, https://, socks4:// and socks5:// with optional user:password@.' : 'Use a full proxy URL such as http://host:8080 or socks5://host:1080.'}
        </p>
      </div>
    </SettingCard>
  )
}

function SpeedAndConnections() {
  const {settings} = useMeta()
  const disabled = !native
  const [limitText, setLimitText] = useState(settings.rateLimit ? String(+(settings.rateLimit / 1_048_576).toFixed(2)) : '')
  useEffect(() => setLimitText(settings.rateLimit ? String(+(settings.rateLimit / 1_048_576).toFixed(2)) : ''), [settings.rateLimit])
  const limitValue = Number(limitText)
  const limitInvalid = limitText !== '' && (!Number.isFinite(limitValue) || limitValue <= 0 || limitValue > 10_000)
  return (
    <SettingExpander
      anchor="speed"
      icon="bolt"
      title="Speed and connections"
      description="How many downloads run at once, multi-connection transfers, a speed limit and a proxy."
      contains={['parallel', 'fragments', 'turbo', 'connections', 'speed-limit', 'proxy']}
    >
      <SettingCard
        anchor="parallel"
        icon="layers"
        title="Parallel downloads"
        description="How many downloads run at the same time. Others wait in the queue."
        control={<Stepper label="Parallel downloads" value={settings.concurrency} min={1} max={6} disabled={disabled} onChange={(concurrency) => void updateSettings({concurrency})} />}
      />
      <SettingCard
        anchor="fragments"
        icon="stack"
        title="Concurrent fragments"
        description="Segments fetched at once for streaming formats (HLS and DASH). Higher can be faster on fast connections."
        control={<Stepper label="Concurrent fragments" value={settings.fragments} min={1} max={16} disabled={disabled} onChange={(fragments) => void updateSettings({fragments})} />}
      />
      <SettingCard
        anchor="turbo"
        icon="bolt"
        title="Turbo transfer"
        description="Off by default. On direct HTTPS files with a known size, Keepfin can open several connections and download ranges in parallel. It may help when a site throttles each connection, and it often makes no difference on fast links. Streaming formats use concurrent fragments instead. Any error falls back to a normal download."
        control={<Switch label="Turbo transfer" checked={settings.turbo.enabled} disabled={disabled} onChange={(enabled) => void updateSettings({turbo: {...settings.turbo, enabled}})} />}
      />
      <SettingCard
        anchor="connections"
        icon="link"
        title="Connections per file"
        description="Only used when Turbo is on. More connections are not always faster; 8 is a reasonable start."
        disabled={!settings.turbo.enabled}
        control={
          <Stepper
            label="Turbo connections"
            value={settings.turbo.connections}
            min={2}
            max={16}
            disabled={disabled || !settings.turbo.enabled}
            onChange={(connections) => void updateSettings({turbo: {...settings.turbo, connections}})}
          />
        }
      />
      <SettingCard
        anchor="speed-limit"
        icon="speed"
        title="Speed limit"
        description={settings.rateLimit ? 'Each download is capped at this speed.' : 'Unlimited. Set a cap to keep bandwidth free for other apps.'}
        control={
          <div className="inline-form">
            <Switch label="Limit download speed" checked={settings.rateLimit > 0} disabled={disabled} onChange={(on) => void updateSettings({rateLimit: on ? 5 * 1_048_576 : 0})} />
            {settings.rateLimit > 0 && (
              <span className="unit-input">
                <input
                  className={`input tabular${limitInvalid ? ' invalid' : ''}`}
                  inputMode="decimal"
                  aria-label="Speed limit in megabytes per second"
                  aria-invalid={limitInvalid}
                  value={limitText}
                  disabled={disabled}
                  onChange={(event) => setLimitText(event.target.value)}
                  onBlur={() => !limitInvalid && limitText && void updateSettings({rateLimit: Math.round(limitValue * 1_048_576)})}
                  onKeyDown={(event) => event.key === 'Enter' && (event.target as HTMLInputElement).blur()}
                />
                <span>MB/s</span>
              </span>
            )}
          </div>
        }
      />
      <ProxyCard />
    </SettingExpander>
  )
}

export function DownloadsSettings() {
  const {settings} = useMeta()
  const disabled = !native
  return (
    <>
      <Group cards title="Defaults" description="Applied to new reviews, batches and collections. You can still change them per download.">
        <SettingCard
          anchor="quality"
          icon="video"
          title="Default quality"
          description="Pre-selects the closest available quality for each link."
          control={
            <Select<DownloadPreset>
              label="Default quality"
              value={settings.defaultPreset}
              disabled={disabled}
              onChange={(defaultPreset) => void updateSettings({defaultPreset})}
              options={downloadPresets.map((preset) => ({value: preset.id, label: preset.label}))}
            />
          }
        />
        <SettingCard
          anchor="youtube-music"
          icon="music"
          title="YouTube Music links"
          description="Links from music.youtube.com open on Audio with tags and cover art, like Spotify and Apple Music links. Choose Video to open them like any other video."
          control={
            <Segmented
              label="YouTube Music links"
              value={settings.youtubeMusic}
              disabled={disabled}
              onChange={(youtubeMusic) => void updateSettings({youtubeMusic})}
              options={[
                {value: 'audio', label: 'Audio (default)'},
                {value: 'video', label: 'Video'},
              ]}
            />
          }
        />
        <SettingCard anchor="options" icon="settings" title="Default download options" description="Subtitles, cover art, chapters and the other choices every new download starts with.">
          <fieldset className="plain-fieldset" disabled={disabled}>
            <DefaultOptions />
          </fieldset>
        </SettingCard>
      </Group>
      <Group cards title="Location">
        <SettingCard
          anchor="save-to"
          icon="folder"
          title="Save to"
          description={<span className="path">{settings.outDir}</span>}
          control={
            <Button icon="folder" disabled={disabled} onClick={() => void act('chooseFolder')}>
              Change folder…
            </Button>
          }
        />
        <SettingCard
          anchor="organize"
          icon="stack"
          title="Organize files"
          description={settings.organization === 'platform' ? 'Each source gets its own subfolder, such as YouTube or TikTok.' : 'Everything is saved directly in the folder above.'}
          control={
            <Segmented
              label="Organize files"
              value={settings.organization}
              disabled={disabled}
              onChange={(organization) => void updateSettings({organization})}
              options={[
                {value: 'platform', label: 'By platform'},
                {value: 'flat', label: 'One folder'},
              ]}
            />
          }
        />
        <SettingCard
          anchor="file-names"
          icon="edit"
          title="File names"
          description="Names stay readable and never overwrite an existing file."
          control={
            <Select<FilenameStyle>
              label="File name style"
              value={settings.filenameStyle}
              disabled={disabled}
              onChange={(filenameStyle) => void updateSettings({filenameStyle})}
              options={[
                {value: 'title', label: 'Title'},
                {value: 'title-uploader', label: 'Title – Creator'},
                {value: 'uploader-title', label: 'Creator – Title'},
                {value: 'date-title', label: 'Date – Title'},
              ]}
            />
          }
        />
      </Group>
      <Group cards title="Songs">
        <SettingCard
          anchor="lyrics"
          icon="lyrics"
          title="Add lyrics to songs"
          description="Looks up lyrics on LRCLIB, a free lyrics library, and saves them in the song file. Timed lyrics follow along in the player."
          control={<Switch label="Add lyrics to songs" checked={settings.lyrics} disabled={disabled} onChange={(lyrics) => void updateSettings({lyrics})} />}
        />
        <SettingCard
          anchor="music-videos"
          icon="video"
          title="Keep music videos automatically"
          description="Songs you download as audio also get their official music video, where there is one. Videos are large."
          control={<Switch label="Keep music videos automatically" checked={settings.musicVideos} disabled={disabled} onChange={(musicVideos) => void updateSettings({musicVideos})} />}
        />
      </Group>
      <WebsiteSignIn />
      <div className="settings-cards">
        <SpeedAndConnections />
      </div>
    </>
  )
}
