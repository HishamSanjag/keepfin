import {useEffect, useState} from 'react'
import type {EngineStatus, ToolStatus} from '../../shared/contracts'
import {messageOf, native, request} from '../bridge'
import {relativeTime} from '../format'
import {setEngine, updateSettings, useMeta} from '../store'
import {toast} from '../toasts'
import {Badge, Button, OptionRow, Segmented, Spinner, Switch} from '../ui/controls'
import {Group} from '../ui/SettingCard'
import {scrubToolNames} from '../../shared/errors'
import {installsWhenNeeded, needsFixing, toolLabels} from '../toolNames'

const roles: Record<ToolStatus['name'], {title: string; role: string}> = {
  'yt-dlp': {title: toolLabels['yt-dlp'], role: 'Finds and downloads video and audio from over a thousand sites.'},
  'gallery-dl': {title: toolLabels['gallery-dl'], role: 'Saves image posts and galleries from sites the main engine does not cover.'},
  ffmpeg: {title: toolLabels.ffmpeg, role: 'Merges streams, converts audio and embeds covers, chapters and subtitles.'},
  'js-runtime': {title: toolLabels['js-runtime'], role: 'Solves site challenges so every format is listed.'},
}

function ToolState({tool}: {tool: ToolStatus}) {
  if (tool.state === 'ready')
    return tool.latest && tool.version && tool.latest !== tool.version ? <Badge tone="warning">Update available</Badge> : <Badge tone="success">Ready</Badge>
  if (tool.state === 'updating' || tool.state === 'installing')
    return (
      <Badge tone="accent">
        <Spinner size={11} /> {tool.state === 'updating' ? 'Updating' : 'Installing'}
      </Badge>
    )
  if (installsWhenNeeded(tool)) return <Badge>Installs when needed</Badge>
  if (tool.state === 'missing') return <Badge tone="warning">Missing</Badge>
  return <Badge tone="danger">Error</Badge>
}

/** The download engine's own update area: automatic by default, with a quiet manual check. */
export function EngineSettings() {
  const {settings, engine} = useMeta()
  const [updating, setUpdating] = useState(false)
  useEffect(() => {
    if (!native) return
    let alive = true
    const refresh = () =>
      request<EngineStatus>({op: 'engineState'})
        .then((state) => alive && setEngine(state))
        .catch(() => {})
    void refresh()
    // Snapshot pushes carry engine changes; this is a slow fallback while a tool is busy.
    const timer = setInterval(() => engine.busy && refresh(), 1500)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [engine.busy])
  async function update() {
    setUpdating(true)
    try {
      setEngine(await request<EngineStatus>({op: 'engineUpdate'}))
    } catch (error) {
      toast({tone: 'error', message: messageOf(error)})
    } finally {
      setUpdating(false)
    }
  }
  const order: ToolStatus['name'][] = ['yt-dlp', 'gallery-dl', 'ffmpeg', 'js-runtime']
  const list = order.map((name) => engine.tools.find((tool) => tool.name === name)).filter(Boolean) as ToolStatus[]
  const main = engine.tools.find((tool) => tool.name === 'yt-dlp')
  const checked = Math.max(0, ...engine.tools.map((tool) => tool.checkedAt || 0))
  const problem = list.find(needsFixing)
  const busy = updating || engine.busy
  const auto = settings.engine.autoUpdate
  return (
    <Group
      id="engine"
      title="Download engine"
      description={auto ? 'Updates automatically' : 'Automatic updates are off'}
    >
      <div className="engine-summary" role="status">
        <div>
          {busy ? (
            <Badge tone="accent">
              <Spinner size={11} /> Updating
            </Badge>
          ) : problem ? (
            <Badge tone="danger">Needs attention</Badge>
          ) : main?.latest && main.version && main.latest !== main.version ? (
            <Badge tone="warning">Update available</Badge>
          ) : native && main ? (
            <Badge tone="success">Up to date</Badge>
          ) : null}
          <p className="muted small tabular engine-meta">
            {native
              ? [checked > 0 ? `Last checked ${relativeTime(checked)}` : 'Not checked yet', main?.version ? `Version ${main.version}` : '']
                  .filter(Boolean)
                  .join(' · ')
              : 'The download engine runs in the desktop app.'}
          </p>
          {problem?.message && <p className="tool-error">{scrubToolNames(problem.message)}</p>}
        </div>
        <Button icon="refresh" loading={busy} disabled={!native} onClick={() => void update()}>
          {busy ? 'Updating…' : problem ? 'Fix and update' : 'Check now'}
        </Button>
      </div>
      <OptionRow
        title="Update automatically"
        description="Checks once a day in the background and installs verified releases. Keeping the engine current is the most effective fix for failed or slow downloads."
        control={
          <Switch
            label="Update engine automatically"
            checked={auto}
            disabled={!native}
            onChange={(autoUpdate) => void updateSettings({engine: {...settings.engine, autoUpdate}})}
          />
        }
      />
      <details className="engine-advanced">
        <summary>Advanced</summary>
        <OptionRow
          title="Release channel"
          description={
            settings.engine.channel === 'nightly'
              ? 'Early access builds get site fixes sooner, with a little more risk.'
              : 'Stable releases are recommended for most people.'
          }
          control={
            <Segmented
              label="Download engine release channel"
              size="sm"
              value={settings.engine.channel}
              disabled={!native}
              onChange={(channel) => void updateSettings({engine: {...settings.engine, channel}})}
              options={[
                {value: 'stable', label: 'Stable'},
                {value: 'nightly', label: 'Early access'},
              ]}
            />
          }
        />
        {list.length > 0 && (
          <ul className="tool-list">
            {list.map((tool) => (
              <li key={tool.name} className={`tool tool-${tool.state}`}>
                <div className="tool-head">
                  <span className="tool-name">{roles[tool.name].title}</span>
                  <ToolState tool={tool} />
                </div>
                <p className="tool-role">{roles[tool.name].role}</p>
                <div className="tool-version tabular">
                  {tool.version ? <span>Installed {tool.version}</span> : <span>Not installed</span>}
                  {tool.latest && tool.latest !== tool.version && <span>Latest {tool.latest}</span>}
                </div>
                {tool.message && <p className={tool.state === 'error' ? 'tool-error' : 'tool-message'}>{scrubToolNames(tool.message)}</p>}
              </li>
            ))}
          </ul>
        )}
      </details>
    </Group>
  )
}
